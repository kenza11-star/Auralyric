package com.vantra.music.ui.player.terminal

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Draws the Terminal scene: a code file hanging in 3D, the song's words typed into it, with more
 * code floating around it at other depths. Every row, in the main page and in the panels, is a flat
 * plane of text; its four corners are projected by [TerminalProjector] and the text is mapped onto
 * them with a projective [Matrix], so depth scaling, foreshortening and the camera's rotation are
 * real perspective, drawn by the platform's own (GPU-backed) canvas.
 *
 * It owns its paints, matrix and scratch arrays; drawing a frame allocates nothing. Any failure
 * flips [failed] and the caller falls back to plain text, so the music is never affected.
 */
internal class TerminalRenderer {
    private val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val italic = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC) }
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }
    private val fill = Paint()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    private val vignettePaint = Paint()
    private val matrix = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private val a = FloatArray(2)
    private val b = FloatArray(2)
    private val pt = FloatArray(2)
    private val projector = TerminalProjector()

    /** set when a draw threw: the caller stops using the renderer and shows plain text */
    @Volatile var failed = false

    private var width = 0
    private var height = 0
    private var wasLandscape = false
    private var textSize = 0f
    private var charW = 0f
    private var gutterW = 0f
    private var glowRadius = 0f
    private var panels: Array<Panel> = emptyArray()

    // where the cursor ended up on screen this frame, for the glow
    private var glowX = 0f
    private var glowY = 0f
    private var glowScale = 0f
    private var glowSeen = false

    /** A floating page of supporting code, at its own place in the world. */
    private class Panel(
        val cx: Float, val cy: Float, val cz: Float, val yaw: Float, val scale: Float,
        val alpha: Float, val speed: Float, val offset: Int, val cols: Int,
    )

    /** Sets up sizes for a canvas of [w] x [h] and returns how many columns of code a row holds. */
    fun layout(w: Int, h: Int, landscape: Boolean): Int {
        if (w <= 0 || h <= 0) return TerminalDocument.MinColumns
        if (w != width || h != height || landscape != wasLandscape || charW == 0f) {
            width = w; height = h; wasLandscape = landscape
            // code-sized type: many rows and columns on screen, rather than a few huge words
            textSize = if (landscape) h * 0.056f else w * 0.040f
            regular.textSize = textSize
            bold.textSize = textSize
            italic.textSize = textSize
            gutterPaint.textSize = textSize * 0.78f
            charW = regular.measureText("M")
            gutterW = charW * 3.8f
            glowRadius = min(w, h) * 0.55f
            glowPaint.shader = RadialGradient(
                0f, 0f, glowRadius,
                intArrayOf(TerminalPalette.Glow, 0x00000000), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
            )
            vignettePaint.shader = RadialGradient(
                w / 2f, h / 2f, hypot(w.toFloat(), h.toFloat()) * 0.5f,
                intArrayOf(0x00000000, 0x00000000, 0xD0000000.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
            panels = buildPanels(w.toFloat(), landscape)
        }
        val block = w * (if (landscape) 0.60f else 0.92f) - gutterW
        return max(TerminalDocument.MinColumns, floor(block / charW).toInt())
    }

    private fun buildPanels(w: Float, landscape: Boolean): Array<Panel> =
        if (landscape) arrayOf(
            Panel(0f, 0f, 0.95f * w, 0f, 1.7f, 0.11f, 0.30f, 31, 46),
            Panel(-0.66f * w, 0f, 0.25f * w, -0.62f, 0.9f, 0.30f, 0.55f, 0, 40),
            Panel(0.66f * w, 0f, 0.25f * w, 0.62f, 0.9f, 0.30f, 0.70f, 17, 40),
        ) else arrayOf(
            Panel(0f, 0f, 0.95f * w, 0f, 1.6f, 0.13f, 0.30f, 31, 40),
            Panel(-0.78f * w, 0f, 0.35f * w, -0.70f, 1.0f, 0.22f, 0.50f, 0, 36),
            Panel(0.78f * w, 0f, 0.35f * w, 0.70f, 1.0f, 0.22f, 0.65f, 17, 36),
        )

    /**
     * @param scroll the row the camera is looking at, fractional while it travels
     * @param glowLevel 0..1 how hard the scene's light is pulsing (the smoothed bass)
     * @param solidCursor typing in progress: the cursor stays lit rather than blinking
     * @param low fewer rows, no panels, no floor, no glow, no vignette, no line numbers
     */
    fun draw(
        canvas: Canvas,
        doc: TerminalDocument,
        cursor: TerminalCursor,
        scroll: Float,
        camera: TerminalCamera,
        timeNs: Long,
        low: Boolean,
        landscape: Boolean,
        glowLevel: Float,
        solidCursor: Boolean,
    ) {
        if (failed) return
        try {
            drawScene(canvas, doc, cursor, scroll, camera, timeNs, low, landscape, glowLevel, solidCursor)
        } catch (t: Throwable) {
            failed = true
        }
    }

    private fun drawScene(
        canvas: Canvas, doc: TerminalDocument, cursor: TerminalCursor, scroll: Float, camera: TerminalCamera,
        timeNs: Long, low: Boolean, landscape: Boolean, glowLevel: Float, solidCursor: Boolean,
    ) {
        canvas.drawColor(android.graphics.Color.BLACK)
        if (width == 0 || charW == 0f) return

        val focal = width * (if (landscape) 0.78f else 1.5f)
        // the line being written sits below the middle, so the file above it fills the screen
        projector.setup(width / 2f, height * (if (landscape) 0.56f else 0.58f), focal, camera)
        val rowH = textSize * 1.35f
        val gap = rowH * 1.12f
        val zStep = focal * 0.05f
        val maxBack = if (low) 8 else 20
        val maxAhead = if (low) 3 else 7
        val blinkOn = solidCursor || ((timeNs / BlinkNs) and 1L) == 0L
        val gutter = if (low) 0f else gutterW
        val blockW = doc.columns * charW
        val glow = glowLevel.coerceIn(0f, 1f)
        glowSeen = false

        if (!low) {
            drawFloor(canvas, gap, focal, glow)
            // far to near: the big back page first
            for (p in panels) drawPanel(canvas, p, scroll, glow, gap, rowH, focal)
        }

        val pageYaw = if (landscape) -0.30f else -0.12f
        val first = max(0, floor(scroll).toInt() - maxBack)
        val last = min(doc.rows.size - 1, ceil(scroll).toInt() + maxAhead)
        for (r in first..last) {
            val row = doc.rows[r]
            var vis = doc.visibleChars(r, cursor)
            var ghost = false
            if (vis == 0) {
                // the code still to come is already on the page, faint; the words themselves are not
                if (row.kind == KindLyric || (cursor.idle && r == cursor.row)) continue
                vis = row.text.length
                ghost = true
            }
            val d = r - scroll
            val alpha = if (ghost) ghostAlpha(d) else rowAlpha(d)
            if (alpha <= 0.01f) continue
            val z = depthOf(d, zStep)
            if (!placeRow(0f, z, d * gap, pageYaw + d * 0.010f, -gutter, blockW, rowH, 1f)) continue

            val fade = alpha * min(1f, projector.scaleAt(z) * 1.1f)
            val active = !ghost && row.line == cursor.line && row.line >= 0
            val activeLyric = active && row.kind == KindLyric
            val hasCursor = r == cursor.row && !cursor.idle && !ghost

            canvas.save()
            canvas.concat(matrix)
            val baseline = rowH * 0.76f

            if (activeLyric) {
                // the line being written: a lit band across the page and a bar at its edge
                fill.color = argb((0.10f + 0.10f * glow) * fade, 0xFFFFFF)
                canvas.drawRect(-gutter, 0f, blockW, rowH, fill)
                fill.color = fadeColor(TerminalPalette.Cursor, fade)
                canvas.drawRect(-gutter, 0f, -gutter + charW * 0.2f, rowH, fill)
            }
            if (!low) {
                gutterPaint.color = fadeColor(if (activeLyric) TerminalPalette.Ink else TerminalPalette.Gutter, fade * (if (activeLyric) 0.9f else 0.85f))
                canvas.drawText(row.lineNo, -charW * 0.9f, baseline, gutterPaint)
            }
            drawSpans(canvas, row.text, row.spans, vis, row.ascii, baseline, fade, if (active) 0.95f else 0.62f, if (active) 1f else 0.88f)
            if (hasCursor) {
                val cx = columnX(row.text, row.ascii, cursor.col)
                if (blinkOn) {
                    fill.color = fadeColor(TerminalPalette.Cursor, fade)
                    canvas.drawRect(cx, baseline - textSize * 0.92f, cx + charW * 0.62f, baseline + textSize * 0.18f, fill)
                }
                markGlow(cx + charW * 0.3f, rowH * 0.5f)
            }
            canvas.restore()
        }

        // a cursor on a row with no text of its own: before the first line, or waiting out an instrumental
        if (cursor.row >= doc.rows.size || cursor.row < 0 || cursor.idle) {
            val d = cursor.row - scroll
            val z = depthOf(d, zStep)
            if (placeRow(0f, z, d * gap, pageYaw + d * 0.010f, -gutter, blockW, rowH, 1f)) {
                canvas.save()
                canvas.concat(matrix)
                val baseline = rowH * 0.76f
                val cx = cursor.col * charW
                if (blinkOn) {
                    fill.color = TerminalPalette.Cursor
                    canvas.drawRect(cx, baseline - textSize * 0.92f, cx + charW * 0.62f, baseline + textSize * 0.18f, fill)
                }
                markGlow(cx + charW * 0.3f, rowH * 0.5f)
                canvas.restore()
            }
        }

        if (!low) {
            if (glowSeen) {
                glowPaint.alpha = ((0.10f + 0.24f * glow) * 255f).toInt().coerceIn(0, 255)
                canvas.save()
                canvas.translate(glowX, glowY)
                val s = glowScale.coerceIn(0.4f, 1.6f)
                canvas.scale(s, s)
                canvas.drawCircle(0f, 0f, glowRadius, glowPaint)
                canvas.restore()
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignettePaint)
        }
    }

    private fun columnX(text: String, ascii: Boolean, col: Int): Float =
        if (ascii) col * charW else regular.measureText(text, 0, col.coerceIn(0, text.length))

    /** Draws the spans of one row up to [limit] characters; code and lyric dim separately so the words lead. */
    private fun drawSpans(
        canvas: Canvas, text: String, spans: IntArray, limit: Int, ascii: Boolean, baseline: Float,
        fade: Float, codeDim: Float, lyricDim: Float,
    ) {
        var k = 0
        while (k < spans.size) {
            val s = spans[k]
            val e = min(spans[k + 1], limit)
            if (s < e) {
                val style = spans[k + 3]
                val paint = when {
                    style and TerminalStyle.Bold != 0 -> bold
                    style and TerminalStyle.Italic != 0 -> italic
                    else -> regular
                }
                val dim = if (style and TerminalStyle.Lyric != 0) lyricDim else codeDim
                paint.color = fadeColor(spans[k + 2], fade * dim)
                val x = if (ascii) s * charW else regular.measureText(text, 0, s)
                canvas.drawText(text, s, e, x, baseline, paint)
            }
            k += TerminalSyntax.Stride
        }
    }

    /** A floating page of filler code, scrolling at its own pace so it slides past the main page (parallax). */
    private fun drawPanel(canvas: Canvas, p: Panel, scroll: Float, glow: Float, gap: Float, rowH: Float, focal: Float) {
        val pool = TerminalAmbient.rows
        val baseScroll = scroll * p.speed
        val first = floor(baseScroll).toInt() - 14
        // the page breathes toward the camera with the bass
        val z = p.cz - glow * focal * 0.04f
        val lx1 = p.cols * charW
        val baseline = rowH * 0.76f
        for (i in first..first + 30) {
            val row = pool[Math.floorMod(i + p.offset, pool.size)]
            if (row.spans.isEmpty()) continue
            val yc = p.cy + (i - baseScroll) * gap * p.scale
            if (!placeRow(p.cx, z, yc, p.yaw, 0f, lx1, rowH, p.scale)) continue
            val fade = p.alpha * min(1f, projector.scaleAt(z) * 1.2f)
            canvas.save()
            canvas.concat(matrix)
            drawSpans(canvas, row.text, row.spans, row.text.length, true, baseline, fade, 1f, 1f)
            canvas.restore()
        }
    }

    /** a perspective grid under the page: the strongest cue that this is a space and not a flat screen */
    private fun drawFloor(canvas: Canvas, gap: Float, focal: Float, glow: Float) {
        val floorY = gap * 6.5f
        val near = -focal * 0.3f
        val far = focal * 3.2f
        stroke.strokeWidth = max(1f, width * 0.0016f)
        for (k in -7..7) {
            val x = k * width * 0.2f
            if (!projector.project(x, floorY, near, a, 0) || !projector.project(x, floorY, far, b, 0)) continue
            stroke.color = argb((0.07f + 0.10f * glow) * (1f - abs(k) / 9f), TerminalPalette.Cursor and 0xFFFFFF)
            canvas.drawLine(a[0], a[1], b[0], b[1], stroke)
        }
        for (j in 0..9) {
            val z = near + j * focal * 0.36f
            if (!projector.project(-width * 1.4f, floorY, z, a, 0) || !projector.project(width * 1.4f, floorY, z, b, 0)) continue
            stroke.color = argb((0.14f + 0.10f * glow) * exp(-j * 0.28f), TerminalPalette.Cursor and 0xFFFFFF)
            canvas.drawLine(a[0], a[1], b[0], b[1], stroke)
        }
    }

    /** where the cursor is on screen, taken from the matrix the row is being drawn with */
    private fun markGlow(localX: Float, localY: Float) {
        pt[0] = localX
        pt[1] = localY
        matrix.mapPoints(pt)
        glowX = pt[0]
        glowY = pt[1]
        glowScale = matrix.mapRadius(1f)
        glowSeen = true
    }

    /**
     * Builds [matrix] for a row of local width [lx0]..[lx1] and height [h], centred at world ([cx], [z])
     * and [yc] high, turned by [yaw] about the vertical and scaled by [k]. Its four corners, projected,
     * are what the flat text is stretched onto. False when it can't be seen.
     */
    private fun placeRow(cx: Float, z: Float, yc: Float, yaw: Float, lx0: Float, lx1: Float, h: Float, k: Float): Boolean {
        val c = cos(yaw)
        val s = sin(yaw)
        val mid = (lx0 + lx1) / 2f
        val xl = (lx0 - mid) * k
        val xr = (lx1 - mid) * k
        val top = yc - h * k / 2f
        val bottom = yc + h * k / 2f
        // corners: top-left, top-right, bottom-right, bottom-left
        var ok = true
        ok = ok && projector.project(cx + xl * c, top, z + xl * s, dst, 0)
        ok = ok && projector.project(cx + xr * c, top, z + xr * s, dst, 2)
        ok = ok && projector.project(cx + xr * c, bottom, z + xr * s, dst, 4)
        ok = ok && projector.project(cx + xl * c, bottom, z + xl * s, dst, 6)
        if (!ok) return false

        var minX = dst[0]; var maxX = dst[0]; var minY = dst[1]; var maxY = dst[1]
        for (i in 1..3) {
            minX = min(minX, dst[i * 2]); maxX = max(maxX, dst[i * 2])
            minY = min(minY, dst[i * 2 + 1]); maxY = max(maxY, dst[i * 2 + 1])
        }
        if (maxX < 0f || minX > width || maxY < 0f || minY > height) return false

        src[0] = lx0; src[1] = 0f
        src[2] = lx1; src[3] = 0f
        src[4] = lx1; src[5] = h
        src[6] = lx0; src[7] = h
        return matrix.setPolyToPoly(src, 0, dst, 0, 4)
    }

    /** past rows recede: the further back in the file, the further from the camera; rows ahead lean a little closer */
    private fun depthOf(d: Float, zStep: Float): Float = if (d < 0f) (-d).pow(1.1f) * zStep else -d * zStep * 0.25f

    /** the row in focus is fully lit, earlier ones fall away with distance */
    private fun rowAlpha(d: Float): Float {
        if (d >= 0f) return if (d < 0.5f) 1f else exp(-0.5f * (d - 0.5f))
        val back = -d
        return if (back < 0.5f) 1f else max(0.08f, exp(-0.15f * (back - 0.5f)))
    }

    /** code that hasn't been written yet: a faint outline of the page ahead */
    private fun ghostAlpha(d: Float): Float = if (d < -0.5f) 0.12f * rowAlpha(d) else 0.22f * exp(-0.32f * max(0f, d - 0.5f))

    private fun argb(a: Float, rgb: Int): Int = (((a.coerceIn(0f, 1f) * 255f).toInt()) shl 24) or (rgb and 0xFFFFFF)

    private fun fadeColor(color: Int, a: Float): Int {
        val aa = ((color ushr 24) * a.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (aa shl 24) or (color and 0xFFFFFF)
    }

    private companion object {
        /** the terminal cursor's blink half-period */
        const val BlinkNs = 530_000_000L
    }
}
