package com.vantra.music.ui.player.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCoreTest {

    // ---- document: lyrics are preserved, only laid out and coloured ----

    private val times = longArrayOf(1_000L, 5_000L, 9_000L)
    private val texts = listOf("I love you", "she's heard before, and she knows it all", "\"quoted\" 42!")

    @Test
    fun rowsAreSlicesOfTheOriginalText() {
        val doc = TerminalDocument.build(times, texts, columns = 14)
        for (line in doc.lines) {
            val rebuilt = doc.rows.filter { it.line == line.index }.joinToString("") { line.text.substring(it.from, it.to) }
            assertEquals(line.text, rebuilt)
        }
        assertTrue(doc.rows.all { it.to - it.from <= 14 })
    }

    @Test
    fun highlightingNeverTouchesTheText() {
        for (t in texts) {
            val spans = TerminalSyntax.highlight(t)
            var k = 0
            var last = 0
            while (k < spans.size) {
                assertTrue(spans[k] >= last && spans[k + 1] > spans[k] && spans[k + 1] <= t.length)
                last = spans[k + 1]
                k += 3
            }
        }
        // the same word always gets the same colour
        val a = TerminalSyntax.highlight("x feel feel")
        assertEquals(a[2 + 3], a[2 + 6])
    }

    @Test
    fun locateIsAPureFunctionOfPosition() {
        val doc = TerminalDocument.build(times, texts, columns = 40)
        val a = TerminalCursor()
        val b = TerminalCursor()
        doc.locate(5_400L, typing = true, out = a)
        doc.locate(9_500L, typing = true, out = b)
        // going back lands exactly where a fresh look at that time does
        doc.locate(5_400L, typing = true, out = b)
        assertEquals(a.line, b.line)
        assertEquals(a.typed, b.typed)
        assertEquals(a.row, b.row)
    }

    @Test
    fun beforeTheFirstLineTheCursorWaits() {
        val doc = TerminalDocument.build(times, texts, columns = 40)
        val c = TerminalCursor()
        doc.locate(200L, typing = true, out = c)
        assertEquals(-1, c.line)
        assertTrue(c.idle)
    }

    @Test
    fun typingFinishesAndCanBeSwitchedOff() {
        val doc = TerminalDocument.build(times, texts, columns = 40)
        val c = TerminalCursor()
        doc.locate(1_000L, typing = true, out = c)
        assertEquals(0, c.typed)
        doc.locate(1_000L, typing = false, out = c)
        assertEquals(texts[0].length, c.typed)
        doc.locate(2_500L, typing = true, out = c)
        assertEquals(texts[0].length, c.typed)
    }

    @Test
    fun aLongGapIsAnInstrumental() {
        val doc = TerminalDocument.build(longArrayOf(0L, 60_000L), listOf("short", "later"), columns = 40)
        val c = TerminalCursor()
        doc.locate(30_000L, typing = true, out = c)
        assertTrue(c.idle)
        assertEquals(doc.lines[0].firstRow + doc.lines[0].rowCount, c.row)
    }

    // ---- beats ----

    private fun feed(tracker: BeatTracker, seconds: Float, fps: Int, bpm: Int, kickAmp: Float): Int {
        val dt = 1f / fps
        val beat = 60f / bpm
        var kicks = 0
        var t = 0f
        while (t < seconds) {
            val phase = t % beat
            val env = kickAmp * kotlin.math.exp(-phase / 0.09f)
            for (i in 0 until tracker.bandCount) {
                tracker.bands[i] = (0.3f * (1f - i / 60f) + if (i < 12) env * (1f - i / 16f) else 0f).coerceIn(0f, 1f)
            }
            tracker.update(dt, heard = true)
            if (tracker.kick > 0f) kicks++
            t += dt
        }
        return kicks
    }

    @Test
    fun kicksAreFoundAtAnyFrameRate() {
        for (fps in listOf(30, 60, 120)) {
            val kicks = feed(BeatTracker(), 20f, fps, bpm = 120, kickAmp = 0.6f)
            assertTrue("fps $fps found $kicks", kicks in 28..44)
        }
    }

    @Test
    fun aSteadyToneIsNotABeat() {
        assertEquals(0, feed(BeatTracker(), 10f, 60, bpm = 120, kickAmp = 0f))
    }

    @Test
    fun nothingTriggersWithoutAudio() {
        val tracker = BeatTracker()
        repeat(120) { tracker.update(1f / 60f, heard = false) }
        assertEquals(0f, tracker.kick, 0f)
        assertEquals(0, tracker.kickCount)
    }

    // ---- camera ----

    @Test
    fun theCameraIsStillAtZeroIntensityAndSettlesAfterAKick() {
        val still = TerminalCamera()
        repeat(120) { still.update(1f / 60f, it / 60f, intensity = 0f, bass = 1f, energy = 1f, kick = 1f, wide = false) }
        assertEquals(0f, still.yaw.value, 1e-6f)
        assertEquals(0f, still.dolly.value, 1e-6f)

        val cam = TerminalCamera()
        cam.update(1f / 60f, 0f, intensity = 1f, bass = 0f, energy = 0f, kick = 1f, wide = false)
        val pushed = cam.dolly.velocity
        assertTrue(pushed > 0f)
        repeat(240) { cam.update(1f / 60f, 0f, intensity = 1f, bass = 0f, energy = 0f, kick = 0f, wide = false) }
        assertTrue(kotlin.math.abs(cam.dolly.value) < 0.02f)
    }

    @Test
    fun springsGiveTheSameResultAtDifferentFrameRates() {
        fun run(fps: Int): Float {
            val s = Spring()
            s.kick(1f)
            repeat(fps) { s.step(1f / fps, 0f, 55f, 10f) }
            return s.value
        }
        assertEquals(run(60), run(120), 0.01f)
    }

    @Test
    fun aPointOnTheAxisProjectsToTheCentre() {
        val p = TerminalProjector()
        p.setup(500f, 300f, 1000f, TerminalCamera())
        val out = FloatArray(2)
        assertTrue(p.project(0f, 0f, 0f, out, 0))
        assertEquals(500f, out[0], 1e-3f)
        assertEquals(300f, out[1], 1e-3f)
        // further away is nearer the centre
        p.project(100f, 0f, 0f, out, 0)
        val near = out[0]
        p.project(100f, 0f, 1000f, out, 0)
        assertTrue(out[0] < near)
    }
}
