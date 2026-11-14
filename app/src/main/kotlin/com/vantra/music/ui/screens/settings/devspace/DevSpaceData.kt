// repo numbers for dev spaces straight from github. Every response is cached (with its ETag, so
// unchanged answers come back as 304 and don't use up the rate limit) and kept on disk, so when
// github refuses or the phone is offline the last good numbers still show.

package com.vantra.music.ui.screens.settings.devspace

import android.content.Context
import android.content.SharedPreferences
import com.vantra.music.BuildConfig
import com.vantra.music.core.updater.GithubOwner
import com.vantra.music.core.updater.GithubRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Collections
import kotlin.math.ceil

data class DevIssue(
    val number: Int,
    val title: String,
    val open: Boolean,
    val labels: List<String>,
    val url: String,
    val createdAt: Instant,
) {
    /** a "bug" label, or the word in the title, reporters don't always label */
    val isBug: Boolean = labels.any { it.contains("bug", true) } || title.contains("bug", true)

    val isFeature: Boolean = !isBug && (
        labels.any { it.contains("enhancement", true) || it.contains("feature", true) } ||
            title.contains("feature", true) || title.contains("request", true)
        )
}

/**
 * null means "github didn't give us this and we have nothing saved", which is different from 0.
 * issues == null means the issue list is unavailable, not that the repo has no issues.
 */
data class DevSpaceStats(
    val openTotal: Int? = null,
    val closedTotal: Int? = null,
    val newIssues: Int? = null,
    val newResolved: Int? = null,
    val commits: Int? = null,
    val releases: Int? = null,
    val issues: List<DevIssue>? = null,
    val fetchedAt: Long = 0L,
) {
    val reportedTotal: Int? get() = if (openTotal != null && closedTotal != null) openTotal + closedTotal else null
}

object DevSpaceRepository {
    private val stats = MutableStateFlow<DevSpaceStats?>(null)
    private val loadingFlag = MutableStateFlow(false)
    private val errorFlag = MutableStateFlow<String?>(null)
    private val mutex = Mutex()
    private var prefs: SharedPreferences? = null

    val current: StateFlow<DevSpaceStats?> = stats.asStateFlow()
    val loading: StateFlow<Boolean> = loadingFlag.asStateFlow()
    val error: StateFlow<String?> = errorFlag.asStateFlow()

    private const val Api = "https://api.github.com"
    private const val Repo = "$GithubOwner/$GithubRepo"
    private const val KEY_RETRY_AT = "retry_at"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val KEY_SINCE = "since"
    private const val DEFAULT_BACKOFF_MS = 5 * 60_000L

    /** Call once with the application context. Loads the saved numbers so they show before any request. */
    fun attach(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("devspace_cache", Context.MODE_PRIVATE)
        if (stats.value == null) {
            val saved = cachedStats(BuildConfig.BUILD_TIME_MILLIS)
            if (saved != null) stats.value = saved
        }
    }

    /**
     * Fetches the numbers. Lazy calls (force = false) are skipped while a recent copy exists.
     * Forced calls (the Retry button, first open) still never hit GitHub while a rate limit is active.
     */
    suspend fun refresh(sinceMillis: Long, force: Boolean, maxAgeMs: Long = 60_000L) {
        val known = stats.value
        if (!force && known != null && System.currentTimeMillis() - known.fetchedAt < maxAgeMs) return
        if (force) mutex.lock() else if (!mutex.tryLock()) return
        loadingFlag.value = true
        try {
            val p = prefs ?: return
            val now = System.currentTimeMillis()
            val retryAt = p.getLong(KEY_RETRY_AT, 0L)
            val since = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(sinceMillis))
            p.edit().putLong(KEY_SINCE, sinceMillis).apply()

            if (retryAt > now) {
                // github said stop. Don't send anything; show what we already have
                errorFlag.value = rateLimitMessage(retryAt, now)
                stats.value = buildStats(since, sinceMillis) { path -> cached(path) } ?: known
                return
            }

            val failures = Collections.synchronizedList(mutableListOf<Exception>())
            val fresh = withContext(Dispatchers.IO) {
                buildStats(since, sinceMillis) { path ->
                    try {
                        fetch(path)
                    } catch (e: Exception) {
                        failures += e
                        cached(path)
                    }
                }
            }

            val rateLimit = failures.filterIsInstance<RateLimited>().firstOrNull()
            if (rateLimit != null) {
                val until = rateLimit.retryAt ?: (System.currentTimeMillis() + DEFAULT_BACKOFF_MS)
                p.edit().putLong(KEY_RETRY_AT, until).apply()
            }
            errorFlag.value = when {
                rateLimit != null -> rateLimitMessage(p.getLong(KEY_RETRY_AT, 0L), System.currentTimeMillis())
                failures.isNotEmpty() -> "Couldn't reach GitHub"
                else -> null
            }
            // keep an older number only when this round produced nothing for that figure
            val old = known ?: DevSpaceStats()
            val merged = if (fresh == null) old else fresh.copy(
                openTotal = fresh.openTotal ?: old.openTotal,
                closedTotal = fresh.closedTotal ?: old.closedTotal,
                newIssues = fresh.newIssues ?: old.newIssues,
                newResolved = fresh.newResolved ?: old.newResolved,
                commits = fresh.commits ?: old.commits,
                releases = fresh.releases ?: old.releases,
                issues = fresh.issues ?: old.issues,
            )
            if (failures.isEmpty()) p.edit().putLong(KEY_FETCHED_AT, System.currentTimeMillis()).apply()
            stats.value = merged.copy(fetchedAt = p.getLong(KEY_FETCHED_AT, merged.fetchedAt))
        } finally {
            loadingFlag.value = false
            mutex.unlock()
        }
    }

    // ---- building the numbers from responses -------------------------------------------------

    private class Cached(val body: String, val etag: String?, val link: String?)

    private class RateLimited(val retryAt: Long?) : Exception("GitHub rate limit")

    /** Reads every figure through [load]. Returns null only when nothing at all could be read. */
    private fun buildStats(since: String, sinceMillis: Long, load: (String) -> Cached?): DevSpaceStats? {
        val q = { qualifiers: String -> URLEncoder.encode("repo:$Repo is:issue $qualifiers", "UTF-8") }
        fun count(qualifiers: String): Int? = runCatching {
            val c = load("/search/issues?q=${q(qualifiers)}&per_page=1") ?: return null
            JSONObject(c.body).getInt("total_count")
        }.getOrNull()

        val commits = runCatching {
            val c = load("/repos/$Repo/commits?since=$since&per_page=1") ?: return@runCatching null
            val last = c.link?.let { Regex("[?&]page=(\\d+)>; rel=\"last\"").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            last ?: JSONArray(c.body).length()
        }.getOrNull()

        val releases = runCatching {
            val c = load("/repos/$Repo/releases?per_page=100") ?: return@runCatching null
            val arr = JSONArray(c.body)
            var n = 0
            for (i in 0 until arr.length()) {
                val at = arr.getJSONObject(i).optString("published_at").takeIf { it.isNotBlank() } ?: continue
                if (Instant.parse(at).toEpochMilli() >= sinceMillis) n++
            }
            n
        }.getOrNull()

        val issues = runCatching {
            val c = load("/repos/$Repo/issues?state=all&per_page=60&sort=created&direction=desc") ?: return@runCatching null
            parseIssues(c.body)
        }.getOrNull()

        val open = count("is:open")
        val closed = count("is:closed")
        val newIssues = count("created:>=$since")
        val newResolved = count("is:closed closed:>=$since")

        if (open == null && closed == null && newIssues == null && newResolved == null &&
            commits == null && releases == null && issues == null
        ) return null

        return DevSpaceStats(
            openTotal = open,
            closedTotal = closed,
            newIssues = newIssues,
            newResolved = newResolved,
            commits = commits,
            releases = releases,
            issues = issues,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private fun parseIssues(body: String): List<DevIssue> {
        val arr = JSONArray(body)
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.has("pull_request")) continue
                val labels = o.optJSONArray("labels")?.let { l -> (0 until l.length()).map { l.getJSONObject(it).optString("name") } }.orEmpty()
                add(
                    DevIssue(
                        number = o.getInt("number"),
                        title = o.getString("title"),
                        open = o.getString("state") == "open",
                        labels = labels,
                        url = o.getString("html_url"),
                        createdAt = Instant.parse(o.getString("created_at")),
                    ),
                )
            }
        }
    }

    /** the saved numbers only, for offline start and while github is rate limiting us */
    private fun cachedStats(sinceMillis: Long): DevSpaceStats? {
        val p = prefs ?: return null
        val since = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(sinceMillis))
        return buildStats(since, sinceMillis) { cached(it) }?.copy(fetchedAt = p.getLong(KEY_FETCHED_AT, 0L))
    }

    // ---- network and disk --------------------------------------------------------------------

    private fun rateLimitMessage(retryAt: Long, now: Long): String {
        val minutes = ceil((retryAt - now) / 60_000.0).toLong().coerceAtLeast(1L)
        return "GitHub's rate limit is reached. Retry in $minutes min"
    }

    private fun cached(path: String): Cached? {
        val raw = prefs?.getString(cacheKey(path), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            Cached(o.getString("body"), o.optString("etag").ifEmpty { null }, o.optString("link").ifEmpty { null })
        }.getOrNull()
    }

    private fun save(path: String, c: Cached) {
        val o = JSONObject().put("body", c.body).put("etag", c.etag ?: "").put("link", c.link ?: "")
        prefs?.edit()?.putString(cacheKey(path), o.toString())?.apply()
    }

    private fun cacheKey(path: String) = "resp:$path"

    /**
     * One request. A 304 means "the copy you have is still current" and costs no quota, so the
     * saved body is returned. A 403 or 429 with a reset or retry hint becomes a [RateLimited].
     */
    private fun fetch(path: String): Cached {
        val previous = cached(path)
        val c = URL(Api + path).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", "Vantra")
        previous?.etag?.let { c.setRequestProperty("If-None-Match", it) }
        try {
            val code = c.responseCode
            when {
                code == 304 && previous != null -> return previous
                code == 403 || code == 429 -> {
                    val now = System.currentTimeMillis()
                    val remaining = c.getHeaderField("X-RateLimit-Remaining")?.toIntOrNull()
                    val retryAfter = c.getHeaderField("Retry-After")?.toLongOrNull()?.let { now + it * 1000L }
                    val reset = c.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()?.let { it * 1000L }
                    if (code == 429 || remaining == 0 || retryAfter != null) {
                        throw RateLimited(retryAfter ?: if (remaining == 0) reset else null)
                    }
                    throw IOException("GitHub answered $code")
                }
                code !in 200..299 -> throw IOException("GitHub answered $code")
            }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            return Cached(body, c.getHeaderField("ETag"), c.getHeaderField("Link")).also { save(path, it) }
        } finally {
            c.disconnect()
        }
    }
}
