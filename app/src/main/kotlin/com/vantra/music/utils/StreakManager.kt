// StreakManager.kt
//
// The hidden listening streak. A day counts when at least a minute was listened to (see
// DatabaseDao.listeningDays), so it is computed from the play history that already exists:
// nothing extra is collected and nothing leaves the phone.
//
// compute() is the plain count from listening days and stays as it is. Two layers sit on top:
//  - milestones: one notification each time the flame reaches 30, 100, 200, 300, 400 or 500 days
//  - recovery: one missed day (yesterday) can be bridged with one of 2 chances a week (Mon-Sun).
//    A bridged day is filled but adds no count, so a 12-day streak recovered stays at 12.
//
// It stays hidden until the user has listened 3 days in a row. That day a notification goes
// out once ("yey, streak kamu nyala") and the flame card shows up on the profile page.

package com.vantra.music.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.vantra.music.MainActivity
import com.vantra.music.R
import com.vantra.music.constants.StreakNotifiedTierKey
import com.vantra.music.constants.StreakRecoveredDaysKey
import com.vantra.music.constants.StreakRecoveryUsedKey
import com.vantra.music.constants.StreakRecoveryWeekKey
import com.vantra.music.constants.StreakUnlockedKey
import com.vantra.music.constants.StreakWarnedDayKey
import com.vantra.music.db.DatabaseDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.DayOfWeek
import java.time.LocalDate

/** The flame's look at each milestone: 3, 30, 100, 200, 300, 400, then 500 and up is the rainbow. */
enum class FlameTier(val from: Int, val label: String, val outer: Color, val inner: Color) {
    Spark(3, "orange", Color(0xFFFF7A00), Color(0xFFFFD27A)),
    Red(30, "red", Color(0xFFE5251B), Color(0xFFFF9A8A)),
    Blue(100, "blue", Color(0xFF2F7BFF), Color(0xFFAFD3FF)),
    Purple(200, "purple", Color(0xFF9B4DFF), Color(0xFFDDBEFF)),
    Teal(300, "teal", Color(0xFF00D9A5), Color(0xFFB8FFEC)),
    Gold(400, "gold", Color(0xFFFFC400), Color(0xFFFFF3B8)),
    Rainbow(500, "rainbow", Color(0xFFFFFFFF), Color(0xFFFFFFFF));

    companion object {
        fun forDays(days: Int): FlameTier = entries.lastOrNull { days >= it.from } ?: Spark
    }
}

data class StreakInfo(
    /** Days in a row up to today (or up to yesterday while today is still to come). */
    val current: Int,
    val longest: Int,
    /** Has listened today already; if not, the streak is alive but today's listen is still owed. */
    val listenedToday: Boolean,
    /** A missed day can still be recovered: the streak is shown grey, and current is what it would be if recovered. */
    val gray: Boolean = false,
    /** Recovery chances left this week, out of [StreakManager.RECOVERIES_PER_WEEK]. */
    val chancesLeft: Int = StreakManager.RECOVERIES_PER_WEEK,
) {
    val tier: FlameTier get() = FlameTier.forDays(current)
    val next: FlameTier? get() = FlameTier.entries.firstOrNull { it.from > current }
    val daysToNext: Int get() = next?.let { it.from - current } ?: 0

    /** 0..1 progress from this tier's start to the next one's. */
    val progress: Float
        get() {
            val n = next ?: return 1f
            val start = if (current < FlameTier.Spark.from) 0 else tier.from
            return ((current - start).toFloat() / (n.from - start)).coerceIn(0f, 1f)
        }
}

/** What the recovery layer keeps in DataStore. weekStart is the Monday the chances belong to. */
data class RecoveryState(
    val weekStart: Long = -1L,
    val used: Int = 0,
    val recoveredDays: Set<Long> = emptySet(),
    val warnedDay: Long = -1L,
    val notifiedTier: Int = 0,
)

object StreakManager {
    const val UNLOCK_DAYS = 3
    const val RECOVERIES_PER_WEEK = 2
    private const val MILESTONE_FROM = 30
    private const val CHANNEL_ID = "streak"
    private const val NOTIFICATION_ID = 7203
    private const val MILESTONE_NOTIFICATION_ID = 7204
    private const val RECOVERY_NOTIFICATION_ID = 7205

    fun compute(epochDays: List<Long>, today: Long = LocalDate.now().toEpochDay()): StreakInfo {
        if (epochDays.isEmpty()) return StreakInfo(0, 0, false)
        val days = epochDays.distinct().sorted()
        var longest = 1
        var run = 1
        for (i in 1 until days.size) {
            run = if (days[i] == days[i - 1] + 1) run + 1 else 1
            if (run > longest) longest = run
        }
        val last = days.last()
        val listenedToday = last == today
        // still alive when the last listen was today or yesterday
        val current = if (last >= today - 1) {
            var c = 1
            var i = days.size - 1
            while (i > 0 && days[i - 1] == days[i] - 1) { c++; i-- }
            c
        } else 0
        return StreakInfo(current, longest, listenedToday)
    }

    // ---- recovery layer -------------------------------------------------------------------

    /** Monday of the week [today] falls in, as an epoch day. Recovery chances reset on it. */
    fun weekStart(today: Long): Long = LocalDate.ofEpochDay(today).with(DayOfWeek.MONDAY).toEpochDay()

    fun readState(prefs: Preferences): RecoveryState = RecoveryState(
        weekStart = prefs[StreakRecoveryWeekKey] ?: -1L,
        used = prefs[StreakRecoveryUsedKey] ?: 0,
        recoveredDays = prefs[StreakRecoveredDaysKey].orEmpty()
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet(),
        warnedDay = prefs[StreakWarnedDayKey] ?: -1L,
        notifiedTier = prefs[StreakNotifiedTierKey] ?: 0,
    )

    fun chancesLeft(state: RecoveryState, today: Long): Int =
        if (state.weekStart == weekStart(today)) {
            (RECOVERIES_PER_WEEK - state.used).coerceAtLeast(0)
        } else {
            RECOVERIES_PER_WEEK
        }

    /**
     * True when yesterday is the one missed day: the day before it was listened to and yesterday
     * was not. Anything older than that is a broken streak and cannot be recovered.
     */
    private fun missedYesterday(days: List<Long>, today: Long): Boolean =
        (today - 2) in days && (today - 1) !in days

    /**
     * Streak counting with the given days bridged. A bridge is a single missing day that is
     * treated as filled: it links the two runs on either side but adds nothing to the count.
     * Alive when the last listen is today or yesterday, or the day before yesterday with
     * yesterday bridged.
     */
    private fun bridged(epochDays: List<Long>, bridges: Set<Long>, today: Long): StreakInfo {
        if (epochDays.isEmpty()) return StreakInfo(0, 0, false)
        val days = epochDays.distinct().sorted()

        fun linked(i: Int): Boolean {
            val gap = days[i] - days[i - 1]
            return gap == 1L || (gap == 2L && (days[i - 1] + 1) in bridges)
        }

        var longest = 1
        var run = 1
        for (i in 1 until days.size) {
            run = if (linked(i)) run + 1 else 1
            if (run > longest) longest = run
        }
        var current = 1
        var i = days.size - 1
        while (i > 0 && linked(i)) { current++; i-- }

        val last = days.last()
        val alive = last >= today - 1 || (last == today - 2 && (today - 1) in bridges)
        return StreakInfo(if (alive) current else 0, longest, last == today)
    }

    /**
     * What the card should show. A missed yesterday with a chance left gives a grey streak
     * (the number it would be if recovered). A recovered day keeps the streak coloured. With no
     * chance left, or nothing missed, the plain count from [compute] is used.
     */
    fun evaluate(
        epochDays: List<Long>,
        state: RecoveryState,
        today: Long = LocalDate.now().toEpochDay(),
    ): StreakInfo {
        val left = chancesLeft(state, today)
        val gap = today - 1
        val recoverable = missedYesterday(epochDays, today) && gap !in state.recoveredDays && left > 0
        if (recoverable) {
            return bridged(epochDays, state.recoveredDays + gap, today).copy(gray = true, chancesLeft = left)
        }
        val base = if (state.recoveredDays.isEmpty()) {
            compute(epochDays, today)
        } else {
            bridged(epochDays, state.recoveredDays, today)
        }
        return base.copy(chancesLeft = left)
    }

    /**
     * Uses one chance to fill yesterday. Returns false when it cannot be used: nothing missed,
     * already recovered, or no chance left this week.
     */
    suspend fun recover(
        store: DataStore<Preferences>,
        epochDays: List<Long>,
        today: Long = LocalDate.now().toEpochDay(),
    ): Boolean {
        var done = false
        store.edit { prefs ->
            val s = readState(prefs)
            val gap = today - 1
            if (missedYesterday(epochDays, today) && gap !in s.recoveredDays && chancesLeft(s, today) > 0) {
                val used = if (s.weekStart == weekStart(today)) s.used else 0
                prefs[StreakRecoveredDaysKey] = (s.recoveredDays + gap).joinToString(",")
                prefs[StreakRecoveryWeekKey] = weekStart(today)
                prefs[StreakRecoveryUsedKey] = used + 1
                done = true
            }
        }
        return done
    }

    // ---- hook ----------------------------------------------------------------------------

    /**
     * Called (off the main thread) after a play is recorded. Does, in order: the unlock at 3 days,
     * the milestone notification when the flame reaches a new colour, and the recovery warning
     * the morning after a missed day. Each notification goes out once.
     */
    fun onListened(context: Context, dao: DatabaseDao, store: DataStore<Preferences>) {
        val days = dao.listeningDaysOnce()
        val today = LocalDate.now().toEpochDay()
        val prefs = runBlocking { store.data.first() }
        val state = readState(prefs)

        // 1. unlock at 3 days in a row
        val base = compute(days, today)
        if (base.current >= UNLOCK_DAYS && prefs[StreakUnlockedKey] != true) {
            runBlocking { store.edit { it[StreakUnlockedKey] = true } }
            post(
                context,
                NOTIFICATION_ID,
                "Yey, streak kamu nyala! \uD83D\uDD25",
                "${base.current} hari berturut-turut kamu dengerin musik. Jaga apinya ya!",
            )
        }

        // 2. milestone: the flame moved up a colour (30 and up; the 3-day notice is step 1)
        val view = evaluate(days, state, today)
        if (!view.gray) {
            val tier = FlameTier.forDays(view.current)
            if (tier.from >= MILESTONE_FROM && tier.from > state.notifiedTier && view.current >= tier.from) {
                val posted = post(
                    context,
                    MILESTONE_NOTIFICATION_ID,
                    "Level streak baru!",
                    "Apimu jadi ${flameWord(tier)}! ${view.current} hari \uD83D\uDD25",
                )
                if (posted) runBlocking { store.edit { it[StreakNotifiedTierKey] = tier.from } }
            }
        }

        // 3. recovery warning: yesterday was missed, a chance is left, and the warning has not gone out
        val gap = today - 1
        if (missedYesterday(days, today) &&
            gap !in state.recoveredDays &&
            chancesLeft(state, today) > 0 &&
            state.warnedDay != gap
        ) {
            val before = bridged(days.filter { it <= today - 2 }, state.recoveredDays, today - 2).current
            val posted = post(
                context,
                RECOVERY_NOTIFICATION_ID,
                "Streak kamu hampir putus",
                "Kamu melewatkan 1 hari. Pakai kesempatan pemulihan biar streak $before hari tetap aman.",
            )
            if (posted) runBlocking { store.edit { it[StreakWarnedDayKey] = gap } }
        }
    }

    private fun flameWord(tier: FlameTier): String = when (tier) {
        FlameTier.Spark -> "oranye"
        FlameTier.Red -> "merah"
        FlameTier.Blue -> "biru"
        FlameTier.Purple -> "ungu"
        FlameTier.Teal -> "tosca"
        FlameTier.Gold -> "emas"
        FlameTier.Rainbow -> "pelangi"
    }

    /** Posts on the "streak" channel. Returns false, without crashing, when notifications are not allowed. */
    private fun post(context: Context, id: Int, title: String, text: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Streak", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Listening streak"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.vantra_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(id, n)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
