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
 * The camera of the Terminal scene. Every drift is a function of the song's time, not of a random
 * source, so a seek or a resume lands in the same shot; the beat adds impulses on top that the
 * springs ring out. Angles in radians, [dolly] and pans as fractions of the focal distance.
 */
class TerminalCamera {
    val yaw = Spring()
    val pitch = Spring()
    val roll = Spring()
    val dolly = Spring()
    val panX = Spring()
    val panY = Spring()
    private var side = 1f

    /** the resting composition the drift moves around: the camera's own angle onto the page, set per orientation */
    var baseYaw = 0f
    var basePitch = 0f

    fun reset() {
        yaw.snap(0f); pitch.snap(0f); roll.snap(0f); dolly.snap(0f); panX.snap(0f); panY.snap(0f)
    }

    /**
     * @param timeS the song's time in seconds (drives the slow drift)
     * @param intensity 0 (still) to ~1.6
     * @param bass smoothed bass 0..1, [energy] the slow section loudness 0..1
     * @param kick strength 0..1 of an onset this frame, 0 otherwise
     * @param wide landscape: a wider sweep to match the wider view
     * @param follow -1..1 where the cursor is across the page; the camera slides to keep the words in frame
     */
    fun update(dt: Float, timeS: Float, intensity: Float, bass: Float, energy: Float, kick: Float, wide: Boolean, follow: Float = 0f) {
        val w = if (wide) 1.25f else 1f
        val t = timeS
        val k = intensity
        // slow cinematic orbit: periods of 20 s and up, wider as the section gets louder
        val sweep = (0.10f + 0.07f * energy) * k * w
        val yawT = baseYaw + sweep * sin(t * 0.31f) + 0.05f * k * sin(t * 0.083f)
        val pitchT = basePitch + (0.045f + 0.02f * energy) * k * sin(t * 0.23f + 1.1f)
        val rollT = 0.02f * k * sin(t * 0.17f + 2.3f)
        // the bass breathes the camera toward the text; a louder section sits a little closer
        val dollyT = k * (0.05f * bass + 0.05f * energy)
        val panXT = 0.035f * k * w * sin(t * 0.19f + 0.4f) - 0.045f * follow.coerceIn(-1f, 1f)
        val panYT = 0.02f * k * sin(t * 0.27f + 2f)

        if (kick > 0f && k > 0f) {
            side = -side
            val power = kick * k
            dolly.kick(0.55f * power)
            pitch.kick(-0.35f * power)
            roll.kick(0.22f * side * power)
            yaw.kick(0.18f * side * power)
        }

        yaw.step(dt, yawT, 38f, 9f)
        pitch.step(dt, pitchT, 38f, 9f)
        roll.step(dt, rollT, 38f, 9f)
        dolly.step(dt, dollyT, 55f, 10f)
        panX.step(dt, panXT, 30f, 8f)
        panY.step(dt, panYT, 30f, 8f)
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
        val d = distance + z
        return if (d < focal * MinDepth) 0f else focal / d
    }

    /**
     * Projects the world point ([x],[y],[z]) into [out] at [index] (x, then y). Returns false when
     * the point is behind or too close to the camera to draw.
     */
    fun project(x: Float, y: Float, z: Float, out: FloatArray, index: Int): Boolean {
        // scene rotated about the origin: yaw, then pitch, then roll
        val x1 = x * cy + z * sy
        val z1 = -x * sy + z * cy
        val y2 = y * cp - z1 * sp
        val z2 = y * sp + z1 * cp
        val x3 = x1 * cr - y2 * sr
        val y3 = x1 * sr + y2 * cr
        val d = distance + z2
        if (d < focal * MinDepth) return false
        val s = focal / d
        out[index] = cx + panX + x3 * s
        out[index + 1] = cyScreen + panY + y3 * s
        return true
    }

    private companion object {
        const val MinDepth = 0.12f
    }
}

