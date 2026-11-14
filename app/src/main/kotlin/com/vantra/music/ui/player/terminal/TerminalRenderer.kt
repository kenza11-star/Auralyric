package com.vantra.music.ui.player.terminal

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Draws the Terminal scene: every row of code is a flat plane of text placed in a 3D world and
 * seen through [TerminalCamera]. Each row's four corners are projected by [TerminalProjector] and
 * the text is mapped onto them with a projective [Matrix], so depth scaling, foreshortening and
 * the camera's rotation are real perspective, drawn by the platform's own (GPU-backed) canvas.
 *
 * It owns its paints, matrix and scratch arrays; drawing a frame allocates nothing. Any failure
 * flips [failed] and the caller falls back to plain text, so the music is never affected.
 */
internal class TerminalRenderer {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val cursorPaint = Paint()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    private val vignettePaint = Paint()
    private val matrix = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private val pt = FloatArray(2)
    private val projector = TerminalProjector()

    /** set when a draw threw: the caller stops using the renderer and shows plain text */
    @Volatile var failed = false

    private var width = 0
    private var height = 0
    private var wasLandscape = false
    private var textSize = 0f
    private var charW = 0f
    private var glowRadius = 0f

    // where the cursor ended up on screen this frame, for the glow
    private var glowX = 0f
    private var glowY = 0f
    private var glowScale = 0f
    private var glowSeen = false

    /** Sets up sizes for a canvas of [w] x [h] and returns how many columns of code a row holds. */
    fun layout(w: Int, h: Int, landscape: Boolean): Int {
        if (w <= 0 || h <= 0) return 8
        if (w != width || h != height || landscape != wasLandscape || charW == 0f) {
            width = w; height = h; wasLandscape = landscape
            textSize = if (landscape) min(w, h) * 0.085f else w * 0.048f
            text.textSize = textSize
            gutterPaint.textSize = textSize * 0.6f
            gutterPaint.color = TerminalPalette.Gutter
            cursorPaint.color = TerminalPalette.Cursor
            charW = text.measureText("M")
            glowRadius = min(w, h) * 0.55f
            glowPaint.shader = RadialGradient(
                0f, 0f, glowRadius,
                intArrayOf(TerminalPalette.Glow, 0x00000000), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
            )
            vignettePaint.shader = RadialGradient(
                w / 2f, h / 2f, hypot(w.toFloat(), h.toFloat()) * 0.5f,
                intArrayOf(0x00000000, 0x00000000, 0xC0000000.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
        }
        return max(8, floor(w * (if (landscape) 0.8f else 0.86f) / charW).toInt())
    }

    /**
     * @param scroll the row the camera is looking at, fractional while it travels
     * @param glowLevel 0..1 how hard the scene's light is pulsing (the smoothed bass)
     * @param solidCursor typing in progress: the cursor stays lit rather than blinking
     * @param low fewer rows, no glow, no vignette, no line numbers
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

        val focal = width * (if (landscape) 0.72f else 1.35f)
        projector.setup(width / 2f, height * (if (landscape) 0.5f else 0.46f), focal, camera)
        val gap = textSize * 1.6f
        val zStep = focal * 0.085f
        val maxBack = if (low) 5 else 9
        val blinkOn = solidCursor || ((timeNs / BlinkNs) and 1L) == 0L
        val gutterW = if (low) 0f else charW * 2.6f
        glowSeen = false

        val newest = min(cursor.row, doc.rows.size - 1)
        val oldest = max(0, floor(scroll).toInt() - maxBack)
        for (r in oldest..newest) {
            val row = doc.rows[r]
            val line = doc.lines[row.line]
            val rowLen = row.to - row.from
            val typedHere = when {
                row.line < cursor.line -> rowLen
                row.line == cursor.line -> if (cursor.idle) rowLen else (cursor.typed - row.from).coerceIn(0, rowLen)
                else -> 0
            }
            val isCursorRow = r == cursor.row && !cursor.idle
            if (typedHere == 0 && !isCursorRow) continue

            val d = r - scroll
            val rowW = if (line.ascii) rowLen * charW else text.measureText(line.text, row.from, row.to)
            val alpha = rowAlpha(d)
            if (alpha <= 0.01f) continue
            if (!place(d, rowW, gutterW, gap, zStep, doc.columns)) continue

            canvas.save()
            canvas.concat(matrix)
            val baseline = textSize * 1.35f * 0.76f
            val fade = alpha * min(1f, projector.scaleAt(depthOf(d, zStep)) * 1.1f)

            if (!low && r == line.firstRow) {
                gutterPaint.color = fadeColor(TerminalPalette.Gutter, fade * 0.9f)
                canvas.drawText(line.gutter, -gutterW, baseline, gutterPaint)
            }
            val spans = line.spans
            val limit = row.from + typedHere
            var k = 0
            while (k < spans.size) {
                val s = max(spans[k], row.from)
                val e = min(spans[k + 1], limit)
                if (s < e) {
                    text.color = fadeColor(spans[k + 2], fade)
                    val x = if (line.ascii) (s - row.from) * charW else text.measureText(line.text, row.from, s)
                    canvas.drawText(line.text, s, e, x, baseline, text)
                }
                k += 3
            }
            if (isCursorRow) {
                val cx = if (line.ascii) cursor.typedInRow * charW else text.measureText(line.text, row.from, row.from + cursor.typedInRow)
                if (blinkOn) {
                    cursorPaint.color = fadeColor(TerminalPalette.Cursor, fade)
                    canvas.drawRect(cx, baseline - textSize * 0.92f, cx + charW * 0.62f, baseline + textSize * 0.18f, cursorPaint)
                }
                markGlow(cx + charW * 0.3f, textSize * 1.35f * 0.5f)
            }
            canvas.restore()
        }

        // a cursor on a row with no text of its own: before the first line, or waiting out an instrumental
        if (cursor.row >= doc.rows.size || cursor.row < 0 || cursor.idle) {
            val d = cursor.row - scroll
            if (place(d, 0f, gutterW, gap, zStep, doc.columns)) {
                canvas.save()
                canvas.concat(matrix)
                val baseline = textSize * 1.35f * 0.76f
                if (blinkOn) {
                    cursorPaint.color = TerminalPalette.Cursor
                    canvas.drawRect(0f, baseline - textSize * 0.92f, charW * 0.62f, baseline + textSize * 0.18f, cursorPaint)
                }
                markGlow(charW * 0.3f, textSize * 1.35f * 0.5f)
                canvas.restore()
            }
        }

        if (!low) {
            if (glowSeen) {
                glowPaint.alpha = ((0.10f + 0.24f * glowLevel.coerceIn(0f, 1f)) * 255f).toInt().coerceIn(0, 255)
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
     * Builds [matrix] for a row [d] rows from the camera's focus: its four corners in the world,
     * projected, are what the flat text is stretched onto. False when it can't be seen.
     */
    private fun place(d: Float, rowW: Float, gutterW: Float, gap: Float, zStep: Float, columns: Int): Boolean {
        val h = textSize * 1.35f
        val x0 = -columns * charW / 2f
        val yc = d * gap
        val z = depthOf(d, zStep)
        val yaw = d * 0.035f
        val c = cos(yaw)
        val s = sin(yaw)
        val lx0 = -gutterW
        val lx1 = rowW + charW
        // corners: top-left, top-right, bottom-right, bottom-left
        var ok = true
        ok = ok && corner(0, lx0, -h / 2f, x0, yc, z, c, s)
        ok = ok && corner(1, lx1, -h / 2f, x0, yc, z, c, s)
        ok = ok && corner(2, lx1, h / 2f, x0, yc, z, c, s)
        ok = ok && corner(3, lx0, h / 2f, x0, yc, z, c, s)
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

    private fun corner(k: Int, lx: Float, ly: Float, x0: Float, yc: Float, z: Float, c: Float, s: Float): Boolean =
        projector.project(x0 + lx * c, yc + ly, z + lx * s, dst, k * 2)

    /** past rows recede: the further back in the file, the further from the camera */
    private fun depthOf(d: Float, zStep: Float): Float = if (d < 0f) (-d).pow(1.1f) * zStep else 0f

    /** the row in focus is fully lit, earlier ones fall away with distance */
    private fun rowAlpha(d: Float): Float {
        val back = max(0f, -d)
        if (d > 1.2f) return 0f
        return if (back < 0.5f) 1f else 0.7f * exp(-0.30f * (back - 0.5f))
    }

    private fun fadeColor(color: Int, a: Float): Int {
        val aa = ((color ushr 24) * a.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (aa shl 24) or (color and 0xFFFFFF)
    }

    private companion object {
        /** the terminal cursor's blink half-period */
        const val BlinkNs = 530_000_000L
    }
}
