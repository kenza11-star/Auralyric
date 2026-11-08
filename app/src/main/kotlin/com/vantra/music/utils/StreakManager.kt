// StreakManager.kt
//
// The hidden listening streak. A day counts when at least a minute was listened to (see
// DatabaseDao.listeningDays), so it is computed from the play history that already exists:
// nothing extra is collected and nothing leaves the phone.
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
import com.vantra.music.constants.StreakUnlockedKey
import com.vantra.music.db.DatabaseDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

object StreakManager {
    const val UNLOCK_DAYS = 3
    private const val CHANNEL_ID = "streak"
    private const val NOTIFICATION_ID = 7203

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

    /** Called (off the main thread) after a play is recorded. Fires the notification once, at 3 days. */
    fun onListened(context: Context, dao: DatabaseDao, store: DataStore<Preferences>) {
        val info = compute(dao.listeningDaysOnce())
        if (info.current < UNLOCK_DAYS) return
        val already = runBlocking { store.data.first()[StreakUnlockedKey] ?: false }
        if (already) return
        runBlocking { store.edit { it[StreakUnlockedKey] = true } }
        notifyUnlocked(context, info.current)
    }

    private fun notifyUnlocked(context: Context, days: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

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
            .setContentTitle("Yey, streak kamu nyala! \uD83D\uDD25")
            .setContentText("$days hari berturut-turut kamu dengerin musik. Jaga apinya ya!")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
    }
}
