package com.vantra.music.ui.player.terminal

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal const val KindHeader = 0
internal const val KindPre = 1
internal const val KindLyric = 2
internal const val KindPost = 3

/**
 * One row of the code file. A lyric row carries a slice of the song's text between quotes
 * ([lyricFrom] until [lyricTo] in [text]); [sliceStart] is where that slice begins in the whole line.
 * Every other row is code around it. The lyric characters are copied exactly as they were sent.
 */
internal class TerminalRow(
    val line: Int,
    val kind: Int,
    val text: String,
    /** quads of (start, end, colour, style) into [text] */
    val spans: IntArray,
    val lyricFrom: Int,
    val lyricTo: Int,
    val sliceStart: Int,
    val lineNo: String,
    /** plain ASCII: columns are exactly one monospace cell wide, so positions need no measuring */
    val ascii: Boolean,
)

/** One lyric line: the rows of its code block, and which of them carry the words. */
internal class TerminalLine(
    val index: Int,
    val timeMs: Long,
    val text: String,
    val groupFirst: Int,
    val groupCount: Int,
    val lyricFirst: Int,
    val lyricCount: Int,
    val revealStops: IntArray,
)

/** Where the song is in the document at one moment. Filled in place, so asking every frame costs nothing. */
internal class TerminalCursor {
    /** the lyric line being typed, -1 before the first one */
    var line = -1

    /** characters of that line typed so far */
    var typed = 0

    /** between lines for a while (an instrumental): the cursor waits on a blank row */
    var idle = false

    /** the row holding the cursor */
    var row = -1

    /** the cursor's column in that row's text */
    var col = 0
}

/**
 * The lyrics written out as a code file. Every lyric becomes a statement of its own (an if, a
 * loop, a switch, a call, an assignment...) with the words as a string inside it; long lines wrap as
 * concatenated strings. The choice of code is a pure function of the line's position and the
 * columns, so a seek or a redraw never changes it.
 */
internal class TerminalDocument private constructor(
    val lines: List<TerminalLine>,
    val rows: Array<TerminalRow>,
    val columns: Int,
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    private val firstGroupRow: Int get() = if (lines.isEmpty()) rows.size / 2 else lines[0].groupFirst

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
            out.typed = 0; out.idle = true; out.row = firstGroupRow; out.col = IdleCol
            return
        }
        val line = lines[i]
        val len = line.text.length
        if (line.lyricCount == 0) {
            // an empty lyric line is a rest: its comment shows and the cursor waits below it
            out.typed = 0; out.idle = true; out.row = line.groupFirst + line.groupCount; out.col = IdleCol
            return
        }
        val nextTime = if (i + 1 < lines.size) lines[i + 1].timeMs else Long.MAX_VALUE
        val span = if (nextTime == Long.MAX_VALUE) 6_000L else nextTime - line.timeMs

        var typed = if (!typing) len else {
            val duration = max(250L, min(len * 45L, min(2_200L, (span * 0.8f).toLong())))
            val p = ((positionMs - line.timeMs).toFloat() / duration).coerceIn(0f, 1f)
            kotlin.math.ceil(len * p).toInt().coerceIn(0, len)
        }
        if (typing && typed in 1 until len) {
            val stop = java.util.Arrays.binarySearch(line.revealStops, typed)
            if (stop < 0) typed = line.revealStops[-stop - 1]
        }
        out.typed = typed

        // how long the line stays up before the gap to the next one counts as instrumental
        val shownUntil = line.timeMs + max(3_000L, len * 90L)
        out.idle = nextTime != Long.MAX_VALUE && positionMs >= shownUntil && nextTime - shownUntil > IdleGapMs ||
            nextTime == Long.MAX_VALUE && positionMs >= shownUntil
        if (out.idle) {
            out.row = line.groupFirst + line.groupCount // the row under the whole block
            out.typed = len
            out.col = IdleCol
            return
        }
        // the lyric row holding the last typed character (the first one while nothing is typed)
        var r = line.lyricFirst
        val lastRow = line.lyricFirst + line.lyricCount - 1
        if (typed > 0) {
            while (r < lastRow && typed > rows[r].sliceStart + (rows[r].lyricTo - rows[r].lyricFrom)) r++
        }
        val row = rows[r]
        out.row = r
        out.col = row.lyricFrom + (typed - row.sliceStart).coerceIn(0, row.lyricTo - row.lyricFrom)
    }

    /**
     * How many characters of row [r] are on screen with the cursor where it is: whole rows for
     * everything already written, none for what is still to come, and for the words being typed
     * only as much as has been typed (the closing quote and what follows it arrive with the last letter).
     */
    fun visibleChars(r: Int, cursor: TerminalCursor): Int {
        val row = rows[r]
        if (row.kind == KindHeader) return row.text.length
        val now = cursor.line
        if (row.line < now) return row.text.length
        if (row.line > now) return 0
        return when (row.kind) {
            KindPre -> row.text.length
            KindLyric -> {
                val n = row.lyricTo - row.lyricFrom
                val here = (cursor.typed - row.sliceStart).coerceIn(0, n)
                when {
                    here >= n -> row.text.length
                    here == 0 -> if (row.sliceStart == 0) row.lyricFrom else 0
                    else -> row.lyricFrom + here
                }
            }
            else -> if (cursor.typed >= lines[row.line].text.length) row.text.length else 0
        }
    }

    /** Prioritize complete active lyrics and real neighbors over decorative scaffolding. */
    fun focusRow(cursor: TerminalCursor, visibleRows: Int): Float {
        val line = lines.getOrNull(cursor.line) ?: return cursor.row.toFloat()
        if (cursor.idle || line.lyricCount == 0) return cursor.row.toFloat()
        val capacity = lyricCapacity(line, max(5, visibleRows))
        val first = firstVisibleLyric(line, cursor, capacity)
        val count = min(line.lyricCount, capacity)
        return first + (count - 1) / 2f
    }

    private fun lyricCapacity(line: TerminalLine, budget: Int): Int {
        var neighbors = 0
        for (i in line.index - 1 downTo 0) if (lines[i].lyricCount > 0) { neighbors++; break }
        for (i in line.index + 1 until lines.size) if (lines[i].lyricCount > 0) { neighbors++; break }
        return max(1, budget - neighbors)
    }

    private fun firstVisibleLyric(line: TerminalLine, cursor: TerminalCursor, capacity: Int): Int =
        if (line.lyricCount <= capacity) line.lyricFirst else
            (cursor.row - capacity / 2).coerceIn(line.lyricFirst, line.lyricFirst + line.lyricCount - capacity)

    /** Editor folding keeps neighboring real strings inside short landscape viewports.
     * The document is unchanged; skipped line numbers identify folded scaffolding. */
    fun viewportRows(cursor: TerminalCursor, maxRows: Int): IntArray {
        val budget = max(5, maxRows)
        val line = lines.getOrNull(cursor.line)
        if (line == null || cursor.idle || line.lyricCount == 0) {
            val first = max(0, cursor.row - budget / 2)
            return (first..min(rows.lastIndex, first + budget - 1)).toList().toIntArray()
        }
        val lastLyric = line.lyricFirst + line.lyricCount - 1
        val activeBudget = lyricCapacity(line, budget)
        val firstActive = firstVisibleLyric(line, cursor, activeBudget)
        val lastActive = min(lastLyric, firstActive + activeBudget - 1)
        val previous = lines.subList(0, line.index).lastOrNull { it.lyricCount > 0 }
        val next = lines.subList(line.index + 1, lines.size).firstOrNull { it.lyricCount > 0 }
        val chosen = java.util.TreeSet<Int>()
        for (r in firstActive..lastActive) chosen.add(r)
        val sideBudget = (budget - chosen.size) / 2
        val beforeRows = java.util.TreeSet<Int>()
        val afterRows = java.util.TreeSet<Int>()
        previous?.let { beforeRows.add(it.lyricFirst + it.lyricCount - 1) }
        next?.let { afterRows.add(it.lyricFirst) }
        var distance = 1
        while (beforeRows.size < sideBudget && firstActive - distance >= 0) {
            beforeRows.add(firstActive - distance)
            distance++
        }
        distance = 1
        while (afterRows.size < sideBudget && lastActive + distance <= rows.lastIndex) {
            afterRows.add(lastActive + distance)
            distance++
        }
        chosen.addAll(beforeRows)
        chosen.addAll(afterRows)
        return chosen.toIntArray()
    }

    companion object {
        /** a gap longer than this, after a line has had its time, is shown as an idle cursor */
        const val IdleGapMs = 4_000L
        const val MinColumns = 22

        /** where an idle cursor sits: one indent in */
        const val IdleCol = 2
        private const val Indent = ""

        fun empty() = TerminalDocument(emptyList(), emptyArray(), MinColumns)

        fun build(times: LongArray, texts: List<String>, columns: Int, title: String = "", artist: String = "",
            measure: (String) -> Float = { it.length.toFloat() },
        ): TerminalDocument {
            val cols = max(columns, MinColumns)
            val rows = ArrayList<TerminalRow>(texts.size * 4 + 8)
            val lines = ArrayList<TerminalLine>(texts.size)
            addHeader(rows, cols, title, artist, measure)
            val offset = (title.hashCode() and 0x7FFFFFFF) % Templates.size
            var step = 0
            for (i in texts.indices) {
                val text = if (texts[i].isBlank()) "" else texts[i]
                val first = rows.size
                val ms = if (i + 1 < texts.size) (times[i + 1] - times[i]).coerceIn(0L, 60_000L) else 4_000L
                val seconds = "${times[i] / 1000}.${(times[i] % 1000) / 100}"
                val ctx = Ctx(i, ms, seconds, 2 + ((text.hashCode() ushr 8) and 0xFF) % 8)
                var lyricFirst = first
                var lyricCount = 0
                if (text.isEmpty()) {
                    addCode(rows, i, KindPre, "// rest ${ms}ms")
                    lyricFirst = rows.size
                } else {
                    // consecutive lines walk the whole set of templates before any repeats; one that
                    // can't fit this width is skipped
                    var tpl = Templates[Order[(step + offset) % Order.size]]
                    var tries = 0
                    while (!fits(tpl, ctx, cols, measure) && tries < Order.size) {
                        step++; tries++
                        tpl = Templates[Order[(step + offset) % Order.size]]
                    }
                    step++
                    if (!fits(tpl, ctx, cols, measure)) tpl = Compact
                    if (i % 3 == 1) {
                        val comment = ctx.fill(Comments[(i / 3 + offset) % Comments.size])
                        if (measure(comment) <= cols) addCode(rows, i, KindPre, comment)
                    }
                    for (p in tpl.pre) addCode(rows, i, KindPre, Indent + ctx.fill(p))
                    lyricFirst = rows.size
                    addLyric(rows, i, text, tpl, ctx, cols, measure)
                    lyricCount = rows.size - lyricFirstAfterHang(rows, lyricFirst)
                    lyricFirst = lyricFirstAfterHang(rows, lyricFirst)
                    for (p in tpl.post) addCode(rows, i, KindPost, Indent + ctx.fill(p))
                    addCode(rows, i, KindPost, "")
                }
                lines.add(TerminalLine(i, times[i], text, first, rows.size - first, lyricFirst, lyricCount, characterStops(text)))
            }
            return TerminalDocument(lines, rows.toTypedArray(), cols)
        }

        private fun characterStops(text: String): IntArray {
            val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
            val stops = ArrayList<Int>()
            var end = iterator.first()
            while (end != BreakIterator.DONE) {
                stops.add(end)
                end = iterator.next()
            }
            return stops.toIntArray()
        }

        /** a hanging layout puts the lead on a code row of its own; the lyric rows start after it */
        private fun lyricFirstAfterHang(rows: ArrayList<TerminalRow>, from: Int): Int {
            var k = from
            while (k < rows.size && rows[k].kind != KindLyric) k++
            return k
        }

        private fun addHeader(rows: ArrayList<TerminalRow>, cols: Int, title: String, artist: String, measure: (String) -> Float) {
            val clean = { s: String -> s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim() }
            val t = clean(title)
            val a = clean(artist)
            var comment = when {
                t.isNotEmpty() && a.isNotEmpty() -> "// now playing: $t by $a"
                t.isNotEmpty() -> "// now playing: $t"
                else -> "// vantra terminal"
            }
            if (measure(comment) > cols) {
                val boundary = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(comment) }
                var end = boundary.last()
                while (end > 0 && measure(comment.substring(0, end) + "...") > cols) end = boundary.previous()
                comment = comment.substring(0, max(0, end)) + "..."
            }
            addCode(rows, -1, KindHeader, comment)
            addCode(rows, -1, KindHeader, "// lyrics.session")
            addCode(rows, -1, KindHeader, "// synced cue blocks")
            addCode(rows, -1, KindHeader, "")
        }

        /** A code-only fallback: track metadata stays in the real player header, never fake lyrics. */
        fun unavailable(columns: Int): TerminalDocument {
            val cols = max(columns, MinColumns)
            val rows = ArrayList<TerminalRow>()
            for (text in arrayOf(
                "// lyrics.session", "// no synced lyrics", "", "const lyrics = null;", "",
                "if (lyrics == null) {", "  player.keepPlaying();", "}", "",
                "// playback continues",
            )) addCode(rows, -1, KindHeader, text)
            return TerminalDocument(emptyList(), rows.toTypedArray(), cols)
        }

        private fun fits(tpl: Tpl, ctx: Ctx, cols: Int, measure: (String) -> Float): Boolean {
            for (p in tpl.pre) if (measure(Indent + ctx.fill(p)) > cols) return false
            for (p in tpl.post) if (measure(Indent + ctx.fill(p)) > cols) return false
            val lead = Indent + ctx.fill(tpl.lead)
            val continuation = " ".repeat(lead.takeWhile { it == ' ' }.length + 2)
            return measure(lead) <= cols - 6 && measure(continuation + "\"xxxxxx\"" + ctx.fill(tpl.tail)) <= cols
        }

        private fun number(rows: ArrayList<TerminalRow>) = (rows.size + 1).toString().padStart(3)

        private fun isAscii(s: String): Boolean {
            for (ch in s) if (ch.code > 126) return false
            return true
        }

        private fun addCode(rows: ArrayList<TerminalRow>, line: Int, kind: Int, text: String) {
            val spans = ArrayList<Int>()
            TerminalSyntax.code(text, 0, spans, Lexer())
            rows.add(TerminalRow(line, kind, text, spans.toIntArray(), -1, -1, 0, number(rows), isAscii(text)))
        }

        /** one row: [lead] code, the opening quote, a slice of the words, the closing quote, [tail] code */
        private fun addLyricRow(
            rows: ArrayList<TerminalRow>, line: Int, lead: String, quote: Char, slice: String, sliceStart: Int, tail: String, lex: Lexer,
        ) {
            val sb = StringBuilder()
            val spans = ArrayList<Int>()
            TerminalSyntax.code(lead, 0, spans, lex)
            sb.append(lead)
            TerminalSyntax.add(spans, sb.length, sb.length + 1, TerminalPalette.Quoted, 0)
            sb.append(quote)
            val from = sb.length
            sb.append(slice)
            TerminalSyntax.add(spans, from, sb.length, TerminalPalette.Lyric, TerminalStyle.Lyric)
            val to = sb.length
            TerminalSyntax.add(spans, sb.length, sb.length + 1, TerminalPalette.Quoted, 0)
            sb.append(quote)
            TerminalSyntax.code(tail, sb.length, spans, lex)
            sb.append(tail)
            val text = sb.toString()
            rows.add(TerminalRow(line, KindLyric, text, spans.toIntArray(), from, to, sliceStart, number(rows), isAscii(text)))
        }

        private fun addLyric(
            rows: ArrayList<TerminalRow>, line: Int, text: String, tpl: Tpl, ctx: Ctx, cols: Int,
            measure: (String) -> Float,
        ) {
            val q = chooseQuote(tpl.quote, text)
            val lead = Indent + ctx.fill(tpl.lead)
            val tail = ctx.fill(tpl.tail)
            if (measure("$lead$q$text$q$tail") <= cols) {
                addLyricRow(rows, line, lead, q, text, 0, tail, Lexer())
                return
            }
            // Put the declaration/call on its own baseline. Wrapped strings keep a stable indent.
            val cont = " ".repeat(lead.takeWhile { it == ' ' }.length + 2)
            addCode(rows, line, KindPre, lead.trimEnd())
            val boundary = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
            var from = 0
            val lex = Lexer()
            while (from < text.length) {
                val remaining = text.substring(from)
                if (measure("$cont$q$remaining$q$tail") <= cols) {
                    addLyricRow(rows, line, cont, q, remaining, from, tail, lex)
                    return
                }
                var to = boundary.following(from)
                var best = from
                var wordEnd = from
                while (to != BreakIterator.DONE && measure("$cont$q${text.substring(from, to)}$q +") <= cols) {
                    best = to
                    if (text[to - 1].isWhitespace()) wordEnd = to
                    to = boundary.next()
                }
                if (wordEnd > from) best = wordEnd
                if (best == text.length) {
                    val previous = boundary.preceding(best)
                    if (previous > from) best = previous
                }
                // A single grapheme wider than the viewport stays intact; renderer measurement
                // handles normal CJK/emoji widths. Never split surrogate or combining sequences.
                if (best == from) best = boundary.following(from).takeIf { it != BreakIterator.DONE } ?: text.length
                addLyricRow(rows, line, cont, q, text.substring(from, best), from, " +", lex)
                from = best
            }
        }

        /** the quote the template likes, unless the words contain it: then one they don't */
        private fun chooseQuote(preferred: Char, text: String): Char = when {
            preferred !in text -> preferred
            '"' !in text -> '"'
            '\'' !in text -> '\''
            else -> '`'
        }

        private class Ctx(val index: Int, val ms: Long, val seconds: String, val n: Int) {
            fun fill(s: String): String = s
                .replace("{i}", (index + 1).toString())
                .replace("{n}", n.toString())
                .replace("{ms}", ms.toString())
                .replace("{t}", seconds)
        }

        /**
         * A statement around the words. [pre] and [post] are code rows (indented relative to the file
         * body); [lead] and [tail] come before and after the string on the lyric's own row.
         */
        private class Tpl(
            val pre: Array<String>,
            val lead: String,
            val tail: String,
            val post: Array<String>,
            val quote: Char = '"',
        )

        private val Comments = arrayOf(
            "// verse {i} @ {t}s",
            "/* sync point */",
            "// hold {ms} ms",
            "// TODO: match the beat",
            "// ---- {i} ----",
            "// await the drop",
        )

        private val Compact = Tpl(emptyArray(), "emit(", ");", emptyArray())

        private val Templates = arrayOf(
            Tpl(arrayOf("async function cue{i}() {", "  const memory ="), "    ", ";",
                arrayOf("  if (player.isPlaying) {", "    await lyrics.emit(memory);", "  }", "  return memory;", "}")),
            Tpl(arrayOf("for (const word of line.words) {", "  if (word === null) continue;"), "  say(", ");", arrayOf("}")),
            Tpl(arrayOf("while (player.playing) {"), "  await show(", ");", arrayOf("  await delay({ms});", "}")),
            Tpl(arrayOf("switch (section) {", "  case {n}:"), "    show(", ");", arrayOf("    break;", "}")),
            Tpl(
                arrayOf("if (vol < 0.2) {", "  hush();", "} else if (vol > 0.8) {", "  shout();", "} else {"),
                "  emit(", ");", arrayOf("}"),
            ),
            Tpl(emptyArray(), "let verse{i} = ", ";", emptyArray(), '\''),
            Tpl(arrayOf("function verse{i}() {"), "  return (", ");", arrayOf("}")),
            Tpl(emptyArray(), "suspend fun sing{i}() = emit(", ")", emptyArray()),
            Tpl(arrayOf("// Kotlin bridge", "import vantra.lyrics", "lyrics.collect { line ->"), "  show(", ")", arrayOf("}")),
            Tpl(arrayOf("class Verse{i} : Cue() {"), "  private var text = ", "",
                arrayOf("  public override fun play() {", "    emit(text)", "  }", "}")),
            Tpl(arrayOf("object Chorus{i} {"), "  const val line = ", "", arrayOf("}")),
            Tpl(emptyArray(), "const verse{i} = { text: ", ", at: {ms} };", emptyArray()),
            Tpl(arrayOf("const lines = ["), "  ", ",", arrayOf("];"), '\''),
            Tpl(emptyArray(), "val line = track?.lyric ?: ", "", emptyArray()),
            Tpl(emptyArray(), "const out = ready === true && muted === false ? ", " : \"\";", emptyArray()),
            Tpl(emptyArray(), "const sing{i} = async () => await show(", ");", emptyArray()),
            Tpl(arrayOf("for (let i = 0; i < {n}; i++) {", "  if (muted) continue;"), "  emit(", ");", arrayOf("}")),
            Tpl(arrayOf("when (section) {"), "  {n} -> emit(", ")", arrayOf("  else -> hold()", "}")),
            Tpl(arrayOf("if (a === b || c !== d) {"), "  return emit(", ");", arrayOf("}")),
            Tpl(arrayOf("const pending = [];", "try {"), "  await emit(", ");", arrayOf("} catch (e) {", "  throw e;", "} finally {", "  flush();", "}")),
            Tpl(arrayOf("flow.collect { value ->", "  if (value == 0) return@collect"), "  emit(", ")", arrayOf("}")),
            Tpl(arrayOf("const next = (opts = {}) => {"), "  return line?.text ?? ", ";", arrayOf("};")),
            Tpl(arrayOf("async function play{i}() {"), "  await emit(", ");", arrayOf("}")),
            Tpl(arrayOf("var retries = 3;", "do {"), "  show(", ");", arrayOf("  retries--;", "} while (retries != 0);"), '\''),
            Tpl(arrayOf("if (!line) {", "  hold();", "} else {"), "  emit(", ");", arrayOf("}")),
        )

        /** a fixed shuffle of the templates, so neighbouring lines never look alike */
        private val Order = intArrayOf(0, 13, 6, 19, 2, 10, 24, 7, 15, 3, 21, 9, 17, 1, 12, 22, 5, 18, 8, 23, 4, 14, 20, 11, 16)
    }
}
