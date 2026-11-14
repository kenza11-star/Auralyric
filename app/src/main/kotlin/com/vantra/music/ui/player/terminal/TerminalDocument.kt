package com.vantra.music.ui.player.terminal

import kotlin.math.max
import kotlin.math.min

/** One lyric line, as it was sent, with its colours and where its rows start in the document. */
internal class TerminalLine(
    val index: Int,
    val timeMs: Long,
    val text: String,
    /** (start, end, colour) triples into [text] */
    val spans: IntArray,
    val firstRow: Int,
    val rowCount: Int,
    /** the line number shown in the gutter */
    val gutter: String,
    /** plain ASCII: columns are exactly one monospace cell wide, so positions need no measuring */
    val ascii: Boolean,
)

/** A slice of a line that fits the screen's width; wrapped rows carry the same line on. */
internal class TerminalRow(val line: Int, val from: Int, val to: Int)

/** Where the song is in the document at one moment. Filled in place, so asking every frame costs nothing. */
internal class TerminalCursor {
    /** the line being typed, -1 before the first one */
    var line = -1

    /** characters of that line typed so far */
    var typed = 0

    /** between lines for a while (an instrumental): the cursor waits on a blank row */
    var idle = false

    /** the row holding the cursor */
    var row = -1

    /** characters of the cursor's row typed, so the cursor sits right after them */
    var typedInRow = 0
}

/**
 * The lyrics laid out as a code file: every line as rows of at most [columns] characters, in
 * order. The text of each row is a slice of the real lyric, never altered.
 */
internal class TerminalDocument private constructor(
    val lines: List<TerminalLine>,
    val rows: Array<TerminalRow>,
    val columns: Int,
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /** the index of the last line starting at or before [positionMs], -1 before the first */
    fun lineAt(positionMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    /**
     * Works out what is typed at [positionMs]. A pure function of the position, so a seek in
     * either direction or a recomposition lands exactly where it should, with no state to restart.
     */
    fun locate(positionMs: Long, typing: Boolean, out: TerminalCursor) {
        val i = lineAt(positionMs)
        out.line = i
        if (i < 0) {
            // before the first line: a blank row above it, cursor waiting
            out.typed = 0; out.idle = true; out.row = -1; out.typedInRow = 0
            return
        }
        val line = lines[i]
        val len = line.text.length
        val nextTime = if (i + 1 < lines.size) lines[i + 1].timeMs else Long.MAX_VALUE
        val span = if (nextTime == Long.MAX_VALUE) 6_000L else nextTime - line.timeMs

        val typed = if (!typing) len else {
            val duration = max(250L, min(len * 45L, min(2_200L, (span * 0.8f).toLong())))
            val p = ((positionMs - line.timeMs).toFloat() / duration).coerceIn(0f, 1f)
            kotlin.math.ceil(len * p).toInt().coerceIn(0, len)
        }
        out.typed = typed

        // how long the line stays up before the gap to the next one counts as instrumental
        val shownUntil = line.timeMs + max(3_000L, len * 90L)
        out.idle = nextTime != Long.MAX_VALUE && positionMs >= shownUntil && nextTime - shownUntil > IdleGapMs ||
            nextTime == Long.MAX_VALUE && positionMs >= shownUntil
        if (out.idle) {
            out.row = line.firstRow + line.rowCount // the blank row under the last one
            out.typed = len
            out.typedInRow = 0
            return
        }
        // the row holding the last typed character (the first row while nothing is typed)
        var r = line.firstRow
        if (typed > 0) {
            val at = typed - 1
            while (r < line.firstRow + line.rowCount - 1 && at >= rows[r].to) r++
        }
        out.row = r
        out.typedInRow = if (typed == 0) 0 else typed - rows[r].from
    }

    companion object {
        /** a gap longer than this, after a line has had its time, is shown as an idle cursor */
        const val IdleGapMs = 4_000L

        fun empty() = TerminalDocument(emptyList(), emptyArray(), 1)

        fun build(times: LongArray, texts: List<String>, columns: Int): TerminalDocument {
            val cols = max(columns, 8)
            val lines = ArrayList<TerminalLine>(texts.size)
            val rows = ArrayList<TerminalRow>(texts.size * 2)
            for (i in texts.indices) {
                val text = texts[i]
                val first = rows.size
                var from = 0
                val len = text.length
                while (from < len) {
                    var to = min(from + cols, len)
                    if (to < len) {
                        // break after the last space that fits; a word longer than a row is cut
                        var cut = to
                        while (cut > from && text[cut - 1] != ' ') cut--
                        if (cut > from) to = cut
                    }
                    rows.add(TerminalRow(i, from, to))
                    from = to
                }
                if (rows.size == first) rows.add(TerminalRow(i, 0, 0))
                var ascii = true
                for (ch in text) if (ch.code > 126) { ascii = false; break }
                lines.add(
                    TerminalLine(
                        index = i,
                        timeMs = times[i],
                        text = text,
                        spans = TerminalSyntax.highlight(text),
                        firstRow = first,
                        rowCount = rows.size - first,
                        gutter = (i + 1).toString().padStart(2, '0'),
                        ascii = ascii,
                    )
                )
            }
            return TerminalDocument(lines, rows.toTypedArray(), cols)
        }
    }
}
