package com.vantra.music.ui.player.terminal

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A damped spring on one number, stepped in small fixed slices so it behaves the same at 60 and 120 Hz. */
class Spring(var value: Float = 0f, var velocity: Float = 0f) {
    fun step(dt: Float, target: Float, stiffness: Float, damping: Float) {
        var left = dt.coerceIn(0f, 0.1f)
        while (left > 1e-5f) {
            val h = min(left, MaxStep)
            velocity += (stiffness * (target - value) - damping * velocity) * h
            value += velocity * h
            left -= h
        }
    }

    fun kick(impulse: Float) {
        velocity += impulse
    }

    fun snap(to: Float) {
        value = to
        velocity = 0f
    }

    private companion object {
        const val MaxStep = 1f / 120f
    }
}

/**
 * Bounded camera rig looking at the editor's focal plane. Playback seconds drive long continuous
 * paths; lyric progression and rests only change damped targets, never spring velocity.
 * Angles are radians; translation and dolly are fractions of the focal distance.
 */
class TerminalCamera {
    val yaw = Spring()
    val pitch = Spring()
    val roll = Spring()
    val dolly = Spring()
    val panX = Spring()
    val panY = Spring()

    /** the resting composition the drift moves around: the camera's own angle onto the page, set per orientation */
    var baseYaw = 0f
    var basePitch = 0f

    fun reset() {
        yaw.snap(0f); pitch.snap(0f); roll.snap(0f); dolly.snap(0f); panX.snap(0f); panY.snap(0f)
    }

    /** [progress] is real lyric-interval progress; [idle] comes from the timed document, not audio guesses.
     * [kick] remains source-compatible but intentionally cannot shake this camera.
     */
    @Suppress("UNUSED_PARAMETER")
    fun update(dt: Float, timeS: Float, intensity: Float, bass: Float, energy: Float, kick: Float,
               wide: Boolean, follow: Float = 0f, progress: Float = 0f, idle: Boolean = false) {
        val k = intensity.coerceIn(0f, 1.6f)
        if (k == 0f) {
            reset()
            yaw.snap(baseYaw); pitch.snap(basePitch)
            return
        }
        val w = if (wide) 1.15f else 1f
        val t = timeS
        // A restrained travelling shot, not an orbit around disconnected rows.
        val yawT = (baseYaw + k * (0.035f * sin(t * 0.085f) + 0.012f * follow))
            .coerceIn(-0.18f, 0.18f)
        val pitchT = (basePitch + k * (0.018f * sin(t * 0.11f) - 0.009f * follow))
            .coerceIn(-0.12f, 0.12f)
        val rollT = 0.008f * k * sin(t * 0.07f)
        // Quintic easing gives zero speed/acceleration at both ends of each lyric interval.
        val p = progress.coerceIn(0f, 1f)
        val eased = p * p * p * (p * (p * 6f - 15f) + 10f)
        val dollyT = k * (0.012f * sin(t * 0.035f) + if (idle) -0.065f else
            0.035f * eased + 0.008f * bass.coerceIn(0f, 1f) + 0.006f * energy.coerceIn(0f, 1f))
        val panXT = k * w * (0.026f * sin(t * 0.095f) + 0.009f * follow.coerceIn(-1f, 1f))
        val panYT = k * 0.009f * sin(t * 0.075f)

        // Near-critical damping: smooth focus changes without overshoot on a new lyric or seek.
        yaw.step(dt, yawT, 16f, 8f)
        pitch.step(dt, pitchT, 16f, 8f)
        roll.step(dt, rollT, 12f, 7f)
        dolly.step(dt, dollyT, 12f, 7f)
        panX.step(dt, panXT, 12f, 7f)
        panY.step(dt, panYT, 12f, 7f)
    }

}

/**
 * Pinhole projection of the Terminal's world onto the screen, written to avoid allocating: every
 * result lands in the caller's arrays. World units are pixels, y down, origin at the screen's centre
 * on the focal plane; z grows away from the camera.
 */
class TerminalProjector {
    private var cy = 1f; private var sy = 0f
    private var cp = 1f; private var sp = 0f
    private var cr = 1f; private var sr = 0f
    private var cx = 0f
    private var cyScreen = 0f
    private var focal = 1000f
    private var distance = 1000f
    private var panX = 0f
    private var panY = 0f

    /**
     * @param focalPx the focal distance: smaller means a wider field of view and stronger depth
     * @param camera the camera's current angles and dolly
     */
    fun setup(centerX: Float, centerY: Float, focalPx: Float, camera: TerminalCamera) {
        cx = centerX
        cyScreen = centerY
        focal = focalPx
        // dolly toward the text shortens the camera's distance from the focal plane
        distance = focalPx * (1f - camera.dolly.value.coerceIn(-0.4f, 0.6f))
        panX = camera.panX.value * focalPx
        panY = camera.panY.value * focalPx
        cy = cos(camera.yaw.value); sy = sin(camera.yaw.value)
        cp = cos(camera.pitch.value); sp = sin(camera.pitch.value)
        cr = cos(camera.roll.value); sr = sin(camera.roll.value)
    }

    /** the scale a plane at world depth [z] gets, for fading and sorting; 0 if it is at or behind the camera */
    fun scaleAt(z: Float): Float {
        val d = distance + panX * sy * cp - panY * sp + z * cy * cp
        return if (d < focal * MinDepth) 0f else focal / d
    }

    /**
     * Projects the world point ([x],[y],[z]) into [out] at [index] (x, then y). Returns false when
     * the point is behind or too close to the camera to draw.
     */
    fun project(x: Float, y: Float, z: Float, out: FloatArray, index: Int): Boolean {
        // scene rotated about the origin: yaw, then pitch, then roll
        // Orbit rig around the editor, then physical lateral camera translation.
        // Translation happens in world/view space BEFORE perspective division: distant
        // planes move less than the editor. A screen-space post-translation loses parallax.
        val tx = x - panX
        val ty = y - panY
        val x1 = tx * cy + z * sy
        val z1 = -tx * sy + z * cy
        val y2 = ty * cp - z1 * sp
        val z2 = ty * sp + z1 * cp
        val x3 = x1 * cr - y2 * sr
        val y3 = x1 * sr + y2 * cr
        val d = distance + z2
        if (d < focal * MinDepth) return false
        val s = focal / d
        out[index] = cx + x3 * s
        out[index + 1] = cyScreen + y3 * s
        return true
    }

    private companion object {
        const val MinDepth = 0.12f
    }
}


/** Shared screen-space correction for every world layer. Only shrink when the lyric safe band or
 * page width requires it; fitting the entire page every frame would cancel the camera's dolly.
 * Caller-owned projected quads and this retained result avoid per-frame objects.
 */
internal class TerminalFraming {
    var scale = 1f; private set
    var dx = 0f; private set
    var dy = 0f; private set

    fun fit(page: FloatArray, focus: FloatArray, width: Float, height: Float) {
        var left = Float.POSITIVE_INFINITY; var right = Float.NEGATIVE_INFINITY
        var top = Float.POSITIVE_INFINITY; var bottom = Float.NEGATIVE_INFINITY
        for (i in 0..6 step 2) {
            left = kotlin.math.min(left, kotlin.math.min(page[i], focus[i]))
            right = kotlin.math.max(right, kotlin.math.max(page[i], focus[i]))
            top = kotlin.math.min(top, focus[i + 1])
            bottom = kotlin.math.max(bottom, focus[i + 1])
        }
        scale = min(1f, min(width * 0.92f / (right - left).coerceAtLeast(1f),
            height * 0.76f / (bottom - top).coerceAtLeast(1f)))
        dx = width * (1f - scale) / 2f
        dy = height * (1f - scale) / 2f
        val l = left * scale + dx; val r = right * scale + dx
        dx += when { l < width * 0.04f -> width * 0.04f - l
            r > width * 0.96f -> width * 0.96f - r; else -> 0f }
        val t = top * scale + dy; val b = bottom * scale + dy
        dy += when { t < height * 0.12f -> height * 0.12f - t
            b > height * 0.88f -> height * 0.88f - b; else -> 0f }
    }
}
