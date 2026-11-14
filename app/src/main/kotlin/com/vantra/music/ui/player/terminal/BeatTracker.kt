package com.vantra.music.ui.player.terminal

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** Where the Terminal's camera is getting its pulse from, shown on the status line so the two are never confused. */
enum class BeatSource {
    /** the decoded audio, read at the playback position: real onsets and bass */
    AUDIO,

    /** no audio to read (a source the player can't tap): the camera only eases on lyric line changes, no beats are claimed */
    LYRIC_TIMING,

    /** nothing playing, or beat reactions switched off */
    NONE,
}

/**
 * Onsets and bass from the player's spectrum, one call per drawn frame.
 *
 * The caller fills [bands] (AudioSpectrum.read writes straight into it, 0..1, low pitch first) and
 * then calls [update]. A kick is half-wave-rectified spectral flux over the low bands, expressed
 * per second so it doesn't depend on the display's refresh rate, compared with its own running
 * mean and deviation so quiet and loud songs both trigger. Nothing here allocates.
 */
class BeatTracker(val bandCount: Int = 40) {
    /** the bands for this frame, written by the caller before [update] */
    val bands = FloatArray(bandCount)
    private val previous = FloatArray(bandCount)

    /** smoothed level of the lowest bands, 0..1 */
    var bass = 0f
        private set

    /** strength 0..1 of a kick detected in this very frame, 0 on every other frame */
    var kick = 0f
        private set

    /** slow loudness 0..1 (about three seconds): the section the song is in, for smooth camera changes between sections */
    var energy = 0f
        private set

    /** how many kicks so far since the last reset, for alternating the sway direction */
    var kickCount = 0
        private set

    private var primed = false
    private var fluxMean = 0f
    private var fluxVar = 0f
    private var sinceKick = 10f
    private var warm = 0f

    /** Forget what came before: a seek, a pause, a new song. The next frames only warm the baselines up and trigger nothing. */
    fun reset() {
        java.util.Arrays.fill(previous, 0f)
        java.util.Arrays.fill(bands, 0f)
        primed = false
        fluxMean = 0f
        fluxVar = 0f
        sinceKick = 10f
        warm = 0f
        kick = 0f
    }

    /** a clean slate including the section energy (a new song) */
    fun resetAll() {
        reset()
        bass = 0f
        energy = 0f
        kickCount = 0
    }

    /**
     * @param dt seconds since the last frame
     * @param heard whether [bands] holds what is being heard right now
     */
    fun update(dt: Float, heard: Boolean) {
        val h = dt.coerceIn(0.001f, 0.1f)
        kick = 0f
        if (!heard) {
            // nothing to read: let everything settle and start over when it comes back
            bass += (0f - bass) * smoothing(h, 0.2f)
            energy += (0f - energy) * smoothing(h, 1.5f)
            primed = false
            warm = 0f
            return
        }

        var low = 0f
        for (i in 0 until BassBands) low += bands[i]
        low /= BassBands
        val tau = if (low > bass) 0.04f else 0.22f
        bass += (low - bass) * smoothing(h, tau)

        var all = 0f
        for (i in 0 until bandCount) all += bands[i]
        all /= bandCount
        energy += (all - energy) * smoothing(h, 3f)

        if (!primed) {
            System.arraycopy(bands, 0, previous, 0, bandCount)
            primed = true
            return
        }

        var flux = 0f
        for (i in 0 until FluxBands) {
            val rise = bands[i] - previous[i]
            if (rise > 0f) flux += rise * (if (i < BassBands) 1f else 0.5f)
        }
        System.arraycopy(bands, 0, previous, 0, bandCount)
        flux /= (FluxBands * h)

        warm += h
        sinceKick += h
        val a = smoothing(h, 1.2f)
        val diff = flux - fluxMean
        fluxMean += a * diff
        fluxVar += a * (diff * diff - fluxVar)
        val deviation = sqrt(max(fluxVar, 0f))

        if (warm >= WarmSeconds && sinceKick >= RefractorySeconds && bass > MinBass &&
            flux > MinFlux && flux > fluxMean + 1.5f * deviation
        ) {
            val strength = ((flux - fluxMean) / (deviation * 4f + 0.5f)).coerceIn(0.25f, 1f)
            kick = strength
            kickCount++
            sinceKick = 0f
        }
    }

    private fun smoothing(dt: Float, tau: Float): Float = 1f - exp(-dt / tau)

    companion object {
        /** AudioSpectrum's bands run 45 Hz to 15 kHz on a log scale: the first eight cover roughly 45 to 150 Hz */
        const val BassBands = 8
        const val FluxBands = 12
        private const val WarmSeconds = 0.3f
        private const val RefractorySeconds = 0.14f
        private const val MinBass = 0.12f
        private const val MinFlux = 1.2f
    }
}
