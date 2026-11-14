package com.vantra.music.ui.player.terminal

import kotlin.math.abs

/** Preserve a shared row's screen position when editor folding changes its slot number.
 * The correction decays without touching lyric timestamps or the camera's velocity.
 */
internal class TerminalViewportMotion {
    val offset = Spring()

    fun change(previous: IntArray, next: IntArray, scroll: Float, anchor: Int) {
        var previousSlot = -1
        var nextSlot = -1
        var nearest = Int.MAX_VALUE
        for (i in next.indices) {
            val j = previous.indexOf(next[i])
            val distance = abs(next[i] - anchor)
            if (j >= 0 && distance < nearest) {
                previousSlot = j; nextSlot = i; nearest = distance
            }
        }
        if (previousSlot < 0) { offset.snap(0f); return }
        val correction = previousSlot - position(previous, scroll) - nextSlot + position(next, scroll)
        // Keep velocity through normal changes; pathological/seek corrections are bounded.
        offset.value = (offset.value + correction).coerceIn(-next.size.toFloat(), next.size.toFloat())
    }

    fun position(rows: IntArray, row: Float): Float {
        if (rows.isEmpty()) return 0f
        if (row <= rows[0]) return row - rows[0]
        for (i in 1 until rows.size) {
            if (row <= rows[i]) return i - 1 + (row - rows[i - 1]) / (rows[i] - rows[i - 1])
        }
        return rows.lastIndex + row - rows.last()
    }
}
