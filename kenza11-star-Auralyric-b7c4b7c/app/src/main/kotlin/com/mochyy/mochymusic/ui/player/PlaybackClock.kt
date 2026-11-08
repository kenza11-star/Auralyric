// PlaybackClock.kt

package com.mochyy.mochymusic.ui.player

import android.view.animation.AnimationUtils
import androidx.compose.runtime.Stable
import androidx.media3.common.Player
import kotlin.math.abs
import kotlin.math.exp

// further apart than this (ms) and the clock jumps straight to the player, because that was a seek
private const val SnapMs = 250.0

// how fast a small gap to the player's own position is eased away
private const val PullTimeMs = 60.0

// a longer break between reads than this (ms) means nothing was watching, so start again from the player
private const val StaleMs = 200L

// the playback position for the frame on screen, so lyrics and the seek bar glide every frame instead of stepping with the 66ms poller
@Stable
class PlaybackClock(
    private val player: () -> Player?,
    private val offsetMs: () -> Long = { 0L },
) : () -> Long {
    private var frameMs = Long.MIN_VALUE
    private var smoothed = 0.0

    override fun invoke(): Long {
        // choreographer pins this to the vsync while a frame runs, so every reader in a frame sees the same time
        val now = AnimationUtils.currentAnimationTimeMillis()
        if (now != frameMs) advance(now)
        return smoothed.toLong() + offsetMs()
    }

    private fun advance(now: Long) {
        val p = player() ?: return
        val reported = p.currentPosition.coerceAtLeast(0L).toDouble()
        val fresh = frameMs == Long.MIN_VALUE || now - frameMs !in 1..StaleMs
        val elapsed = (now - frameMs).toDouble()
        frameMs = now
        if (fresh) {
            smoothed = reported
            return
        }
        // move on by the frame's own time, then ease out the little jitter in what the player reports
        val predicted = if (p.isPlaying) smoothed + elapsed * p.playbackParameters.speed else smoothed
        val gap = reported - predicted
        smoothed = if (abs(gap) > SnapMs) reported else predicted + gap * (1.0 - exp(-elapsed / PullTimeMs))
    }
}
