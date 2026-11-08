// TurntableWidgetReceiver.kt

package com.vantra.music.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.vantra.music.playback.MusicService

class TurntableWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {

        if (MusicService.isRunning) {
            val intent = Intent(context, MusicService::class.java).apply {
                action = ACTION_UPDATE_TURNTABLE_WIDGET
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {

            }
        }

    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_TURNTABLE_PLAY_PAUSE, ACTION_TURNTABLE_NEXT, ACTION_TURNTABLE_PREVIOUS -> {

                val serviceIntent = Intent(context, MusicService::class.java).apply {
                    action = when (intent.action) {
                        ACTION_TURNTABLE_PLAY_PAUSE -> MusicWidgetReceiver.ACTION_PLAY_PAUSE
                        ACTION_TURNTABLE_NEXT -> MusicWidgetReceiver.ACTION_NEXT
                        ACTION_TURNTABLE_PREVIOUS -> MusicWidgetReceiver.ACTION_PREVIOUS
                        else -> intent.action
                    }
                    putExtras(intent)
                }
                try {
                    androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {

                }
            }
        }
    }

    companion object {
        const val ACTION_TURNTABLE_PLAY_PAUSE = "com.vantra.music.widget.TURNTABLE_PLAY_PAUSE"
        const val ACTION_TURNTABLE_NEXT = "com.vantra.music.widget.TURNTABLE_NEXT"
        const val ACTION_TURNTABLE_PREVIOUS = "com.vantra.music.widget.TURNTABLE_PREVIOUS"
        const val ACTION_UPDATE_TURNTABLE_WIDGET = "com.vantra.music.widget.UPDATE_TURNTABLE_WIDGET"
    }
}
