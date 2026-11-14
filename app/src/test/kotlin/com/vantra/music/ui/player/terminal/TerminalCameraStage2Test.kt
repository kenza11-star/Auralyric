package com.vantra.music.ui.player.terminal

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class TerminalCameraStage2Test {
    @Test fun audioOnsetsDoNotJoltTheCamera() {
        val a = TerminalCamera(); val b = TerminalCamera()
        repeat(180) {
            a.update(1f / 60, it / 60f, 1f, 0f, 0f, 0f, false)
            b.update(1f / 60, it / 60f, 1f, 0f, 0f, 1f, false)
        }
        assertEquals(a.roll.value, b.roll.value, 0.00001f)
        assertEquals(a.yaw.value, b.yaw.value, 0.00001f)
    }
    @Test fun cameraTranslationProducesDepthParallax() {
        val camera = TerminalCamera(); camera.panX.snap(0.1f)
        val p = TerminalProjector(); val out = FloatArray(2)
        p.setup(500f, 300f, 1000f, camera)
        p.project(0f, 0f, 0f, out, 0); val near = abs(out[0] - 500f)
        p.project(0f, 0f, 1000f, out, 0); val far = abs(out[0] - 500f)
        assertTrue("camera translation must diminish with depth", far < near * 0.6f)
    }
    @Test fun nearPlaneRejectsPointsAndDepthShrinksScale() {
        val p = TerminalProjector(); p.setup(0f, 0f, 1000f, TerminalCamera())
        assertFalse(p.project(0f, 0f, -1000f, FloatArray(2), 0))
        assertTrue(p.scaleAt(800f) < p.scaleAt(300f))
    }
    @Test fun pathsStayBoundedThroughSeeksAndOrientationChanges() {
        val c = TerminalCamera()
        var previous = 0f
        repeat(2400) {
            c.baseYaw = if (it % 600 > 300) 0.10f else 0.06f
            c.basePitch = -0.09f
            c.update(1f / 60, if (it < 1200) it / 60f else 300f - it / 60f,
                1.6f, 1f, 1f, 1f, it % 600 > 300, if (it % 200 > 100) 1f else -1f)
            assertTrue(abs(c.yaw.value) <= 0.19f)
            assertTrue(abs(c.pitch.value) <= 0.13f)
            assertTrue(abs(c.roll.value) <= 0.016f)
            assertTrue(abs(c.yaw.value - previous) < 0.01f)
            previous = c.yaw.value
        }
    }
    @Test fun frameRatesFollowTheSamePath() {
        fun run(fps: Int): TerminalCamera {
            val c = TerminalCamera()
            repeat(fps * 10) { c.update(1f / fps, it.toFloat() / fps, 1f, 0.2f, 0.3f, 0f, false) }
            return c
        }
        val a = run(30); val b = run(120)
        assertEquals(a.yaw.value, b.yaw.value, 0.003f)
        assertEquals(a.panX.value, b.panX.value, 0.003f)
    }
    @Test fun lyricSafeRegionFitsProjectedCornersAtBothOrientations() {
        for ((w, h) in listOf(360f to 620f, 820f to 260f)) {
            val c = TerminalCamera(); c.yaw.snap(0.18f); c.pitch.snap(-0.12f)
            c.roll.snap(0.013f); c.dolly.snap(0.06f); c.panX.snap(0.05f)
            val p = TerminalProjector(); p.setup(w / 2, h / 2, w * 1.5f, c)
            val page = FloatArray(8); val focus = FloatArray(8)
            val x = w * 0.42f
            for (offset in listOf(-h, 0f, h)) {
                val xs = floatArrayOf(-x, x, x, -x)
                val ys = floatArrayOf(-h / 2, -h / 2, h / 2, h / 2)
                val fy = floatArrayOf(offset - h * 0.15f, offset - h * 0.15f, offset + h * 0.15f, offset + h * 0.15f)
                repeat(4) { n ->
                    assertTrue(p.project(xs[n], ys[n], 0f, page, n * 2))
                    assertTrue(p.project(xs[n], fy[n], 0f, focus, n * 2))
                }
                val framing = TerminalFraming()
                framing.fit(page, focus, w, h)
                repeat(4) { n ->
                    val px = focus[n * 2] * framing.scale + framing.dx
                    val py = focus[n * 2 + 1] * framing.scale + framing.dy
                    assertTrue("x=$px width=$w", px in w * 0.04f - 0.1f..w * 0.96f + 0.1f)
                    assertTrue("y=$py height=$h", py in h * 0.12f - 0.1f..h * 0.88f + 0.1f)
                }
            }
        }
    }
    @Test fun instrumentalWidensSmoothlyAndReducedMotionIsStill() {
        val c = TerminalCamera()
        repeat(180) { c.update(1f/60, 4f, 1f, 0f, 0f, 0f, false, progress=1f) }
        val close = c.dolly.value
        c.update(1f/60, 4f, 1f, 0f, 0f, 0f, false, idle=true)
        assertTrue(abs(c.dolly.value - close) < 0.002f)
        repeat(300) { c.update(1f/60, 4f, 1f, 0f, 0f, 0f, false, idle=true) }
        assertTrue(c.dolly.value < -0.05f)
        c.update(1f/60, 200f, 0f, 1f, 1f, 1f, true)
        assertEquals(0f, c.dolly.value, 0f)
        assertEquals(0f, c.roll.value, 0f)
    }
    @Test fun foldedRowsRetainTheirPositionThenSettle() {
        val old = intArrayOf(2, 3, 10, 11, 12, 20)
        val next = intArrayOf(10, 11, 12, 19, 20, 21)
        val m = TerminalViewportMotion()
        val before = old.indexOf(12) - m.position(old, 10f)
        m.change(old, next, 10f, 12)
        val after = next.indexOf(12) - m.position(next, 10f) + m.offset.value
        assertEquals(before, after, 0.00001f)
        repeat(240) { m.offset.step(1f/60, 0f, 36f, 12f) }
        assertEquals(0f, m.offset.value, 0.001f)
    }
}
