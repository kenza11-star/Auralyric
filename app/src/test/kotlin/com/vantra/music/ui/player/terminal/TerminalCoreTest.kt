package com.vantra.music.ui.player.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCoreTest {

    // ---- document: the lyrics are preserved exactly, only the code around them is added ----

    private val times = longArrayOf(1_000L, 5_000L, 9_000L)
    private val texts = listOf("I love you", "she's heard before, and she knows it all", "\"quoted\" 42! don't stop")

    private fun lyricOf(doc: TerminalDocument, line: TerminalLine): String =
        doc.rows.filter { it.line == line.index && it.kind == KindLyric }
            .joinToString("") { it.text.substring(it.lyricFrom, it.lyricTo) }

    @Test
    fun theWordsInTheCodeAreExactlyTheLyrics() {
        for (columns in listOf(22, 30, 40, 60, 90)) {
            val doc = TerminalDocument.build(times, texts, columns)
            for (line in doc.lines) assertEquals("columns $columns", line.text, lyricOf(doc, line))
        }
    }

    @Test
    fun everyRowFitsTheColumnsAndSpansStayInsideIt() {
        val many = List(40) { "line $it: she walked in the rain and we didn't say a word to anyone at all" }
        for (columns in listOf(26, 34, 48, 70)) {
            val doc = TerminalDocument.build(LongArray(many.size) { it * 4_000L }, many, columns, "Title", "Artist")
            for (row in doc.rows) {
                assertTrue("columns $columns: '${row.text}'", row.text.length <= doc.columns)
                var k = 0
                var last = 0
                while (k < row.spans.size) {
                    assertTrue(row.spans[k] >= last && row.spans[k + 1] > row.spans[k] && row.spans[k + 1] <= row.text.length)
                    last = row.spans[k + 1]
                    k += TerminalSyntax.Stride
                }
            }
        }
    }

    @Test
    fun theLyricIsColouredAsLyricAndCodeIsNot() {
        val doc = TerminalDocument.build(times, texts, 60)
        val row = doc.rows.first { it.kind == KindLyric }
        var found = false
        var k = 0
        while (k < row.spans.size) {
            if (row.spans[k + 3] and TerminalStyle.Lyric != 0) {
                assertEquals(row.lyricFrom, row.spans[k])
                assertEquals(row.lyricTo, row.spans[k + 1])
                found = true
            }
            k += TerminalSyntax.Stride
        }
        assertTrue(found)
        assertTrue(doc.rows.filter { it.kind != KindLyric }.all { r ->
            (0 until r.spans.size / TerminalSyntax.Stride).none { r.spans[it * 4 + 3] and TerminalStyle.Lyric != 0 }
        })
    }

    @Test
    fun theCodeUsesTheWholeVocabulary() {
        val many = List(60) { "word number $it of the song" }
        val doc = TerminalDocument.build(LongArray(many.size) { it * 3_000L }, many, 90, "T", "A")
        val code = doc.rows.joinToString("\n") { it.text }
        val wanted = listOf(
            "if", "else", "else if", "while", "for", "switch", "case", "break", "continue", "return", "const", "let",
            "function", "async", "await", "class", "object", "fun", "suspend", "emit", "collect",
            "finally", "throw", "import", "override", "private", "public", "null", "true", "false", "++", "--",
            "()", "{}", "[]", ";", ":", ".", ",", "=>", "->", "===", "!=", "&&", "||", "?.", "?:",
        )
        for (w in wanted) assertTrue("missing '$w'", code.contains(w))
        assertTrue(code.contains("\"\"") && code.contains("'"))
        assertTrue(code.contains("//") && code.contains("var ") && code.contains("val "))
    }

    @Test
    fun consecutiveLinesGetDifferentCode() {
        val many = List(30) { "same words" }
        val doc = TerminalDocument.build(LongArray(many.size) { it * 3_000L }, many, 80)
        for (i in 1 until doc.lines.size) {
            fun shape(n: Int): String {
                val l = doc.lines[n]
                return (l.groupFirst until l.groupFirst + l.groupCount).joinToString("|") { doc.rows[it].text }.replace(Regex("\\d+"), "#")
            }
            val a = shape(i - 1)
            val b = shape(i)
            assertTrue("line $i repeats the shape of the one before", a != b)
        }
    }

    @Test
    fun aQuoteInTheWordsNeverGetsEscapedOrDropped() {
        val doc = TerminalDocument.build(longArrayOf(0L), listOf("he said \"go\" and 'went'"), 80)
        assertEquals("he said \"go\" and 'went'", lyricOf(doc, doc.lines[0]))
    }

    @Test
    fun documentsAreTheSameEveryTimeTheyAreBuilt() {
        val a = TerminalDocument.build(times, texts, 40, "T", "A")
        val b = TerminalDocument.build(times, texts, 40, "T", "A")
        assertEquals(a.rows.map { it.text }, b.rows.map { it.text })
    }

    @Test
    fun anEmptyLyricLineIsARestNotAGap() {
        val doc = TerminalDocument.build(longArrayOf(0L, 2_000L, 4_000L), listOf("one", "", "two"), 50)
        assertEquals(0, doc.lines[1].lyricCount)
        val c = TerminalCursor()
        doc.locate(2_500L, typing = true, out = c)
        assertTrue(c.idle)
        assertEquals("", lyricOf(doc, doc.lines[1]))
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
        assertEquals(a.col, b.col)
    }

    @Test
    fun beforeTheFirstLineTheCursorWaitsBelowTheHeader() {
        val doc = TerminalDocument.build(times, texts, columns = 40, title = "T", artist = "A")
        val c = TerminalCursor()
        doc.locate(200L, typing = true, out = c)
        assertEquals(-1, c.line)
        assertTrue(c.idle)
        assertEquals(doc.lines[0].groupFirst, c.row)
        // the header is on screen, nothing of the song is
        for (r in doc.rows.indices) {
            val shown = doc.visibleChars(r, c)
            if (doc.rows[r].kind == KindHeader) assertEquals(doc.rows[r].text.length, shown) else assertEquals(0, shown)
        }
    }

    @Test
    fun theWordsAppearAsTheyAreTypedAndTheCodeAroundThemFollows() {
        val doc = TerminalDocument.build(longArrayOf(0L, 20_000L), listOf("hello world", "next"), 80)
        val line = doc.lines[0]
        val c = TerminalCursor()
        doc.locate(0L, typing = true, out = c)
        val row = doc.rows[line.lyricFirst]
        // nothing typed: the code up to the opening quote is there, no word is
        assertEquals(row.lyricFrom, doc.visibleChars(line.lyricFirst, c))
        // the code before the words is written at once; what closes the block waits for the last letter
        for (r in line.groupFirst until line.lyricFirst) assertEquals(doc.rows[r].text.length, doc.visibleChars(r, c))
        val post = (line.lyricFirst + line.lyricCount until line.groupFirst + line.groupCount).toList()
        for (r in post) assertEquals(0, doc.visibleChars(r, c))
        // typed in full: every row of the block is complete
        doc.locate(5_000L, typing = true, out = c)
        for (r in line.groupFirst until line.groupFirst + line.groupCount) assertEquals(doc.rows[r].text.length, doc.visibleChars(r, c))
        // the next line's block is still unwritten
        for (r in doc.lines[1].groupFirst until doc.lines[1].groupFirst + doc.lines[1].groupCount) assertEquals(0, doc.visibleChars(r, c))
    }

    @Test
    fun aWrappedLyricIsTypedRowByRow() {
        val long = "this is a rather long line of words that cannot fit in one short row"
        val doc = TerminalDocument.build(longArrayOf(0L, 30_000L), listOf(long, "x"), 30)
        val line = doc.lines[0]
        assertTrue(line.lyricCount > 1)
        val c = TerminalCursor()
        doc.locate(0L, typing = true, out = c)
        c.typed = 3
        // the second row of the words stays hidden until typing reaches it
        assertEquals(0, doc.visibleChars(line.lyricFirst + 1, c))
        c.typed = long.length
        assertEquals(doc.rows[line.lyricFirst + 1].text.length, doc.visibleChars(line.lyricFirst + 1, c))
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
        assertEquals(doc.lines[0].groupFirst + doc.lines[0].groupCount, c.row)
    }

    @Test
    fun syntaxColoursKeywordsOperatorsAndBracketsByDepth() {
        val out = ArrayList<Int>()
        TerminalSyntax.code("if (a === b) {", 0, out, Lexer())
        fun colourOf(start: Int): Int {
            var k = 0
            while (k < out.size) {
                if (out[k] == start) return out[k + 2]
                k += 4
            }
            return -1
        }
        assertEquals(TerminalPalette.Keyword, colourOf(0))
        assertEquals(TerminalPalette.Operator, colourOf(6))
        assertEquals(TerminalPalette.Brackets[0], colourOf(3))
        // the closing bracket matches its opening one
        assertEquals(colourOf(3), colourOf(11))
    }

    @Test
    fun minimumWidthAndLongUnicodeLyricsStayBounded() {
        val words = listOf("漢字かなカナ".repeat(20), "music 🎵 e\u0301 ".repeat(30), "x".repeat(300))
        for (columns in listOf(22, 26, 34, 70)) {
            val doc = TerminalDocument.build(LongArray(words.size) { it * 10_000L }, words, columns)
            for (line in doc.lines) assertEquals(line.text, lyricOf(doc, line))
            for (row in doc.rows) {
                assertTrue("width $columns: ${row.text}", row.text.length <= columns)
                if (row.kind == KindLyric) {
                    val part = row.text.substring(row.lyricFrom, row.lyricTo)
                    assertTrue(part.isEmpty() || !part.first().isLowSurrogate())
                    assertTrue(part.isEmpty() || !part.last().isHighSurrogate())
                    assertTrue(part.isEmpty() || Character.getType(part.first()) != Character.NON_SPACING_MARK.toInt())
                }
            }
        }
    }

    @Test
    fun everyRequiredKeywordIsRecognized() {
        for (word in listOf("finally", "override", "private", "public")) {
            val spans = ArrayList<Int>()
            TerminalSyntax.code(word, 0, spans, Lexer())
            assertTrue("unrecognized $word", spans[2] == TerminalPalette.Keyword || spans[2] == TerminalPalette.Declare)
        }
    }

    @Test
    fun wideGlyphsUseMeasuredWidthAndKeepTheOriginalText() {
        val text = "漢字かな 🎵 next ".repeat(12)
        val measure: (String) -> Float = { value -> value.sumOf { if (it.code > 126) 2 else 1 }.toFloat() }
        val doc = TerminalDocument.build(longArrayOf(0L), listOf(text), 30, measure = measure)
        assertEquals(text, lyricOf(doc, doc.lines[0]))
        for (row in doc.rows) assertTrue("measured overflow: ${row.text}", measure(row.text) <= doc.columns)
    }

    @Test
    fun wrappingKeepsNormalLyricsInOneStableViewingRegion() {
        val doc = TerminalDocument.build(longArrayOf(0L), listOf("a line that needs several rows of text to be fully readable"), 22)
        val c = TerminalCursor()
        doc.locate(0L, true, c)
        val first = doc.focusRow(c, 20)
        doc.locate(500L, true, c)
        assertEquals(first, doc.focusRow(c, 20), 0f)
        assertTrue(first >= doc.lines[0].lyricFirst)
    }

    @Test
    fun absentLyricsNeverTurnTrackMetadataIntoTimedWords() {
        val doc = TerminalDocument.unavailable(22)
        assertTrue(doc.isEmpty)
        assertTrue(doc.rows.none { it.kind == KindLyric })
        assertTrue(doc.rows.any { "no synced lyrics" in it.text })
        val c = TerminalCursor()
        doc.locate(10_000L, true, c)
        assertTrue(c.idle)
        assertTrue(c.row in doc.rows.indices)
    }

    @Test
    fun typingDoesNotRevealHalfASurrogateOrCombiningSequence() {
        val doc = TerminalDocument.build(longArrayOf(0L), listOf("🎵e\u0301".repeat(12)), 30)
        val c = TerminalCursor()
        for (ms in 0L..2_500L step 13) {
            doc.locate(ms, true, c)
            val typed = doc.lines[0].text.take(c.typed)
            assertTrue(typed.isEmpty() || !typed.last().isHighSurrogate())
            assertTrue(c.typed == doc.lines[0].text.length || doc.lines[0].text[c.typed] != '\u0301')
        }
    }

    @Test
    fun shortLandscapeKeepsBothNeighborLyricsWithoutSqueezingBaselines() {
        val times = longArrayOf(0L, 4_000L, 8_000L)
        val doc = TerminalDocument.build(times, listOf("first", "second", "third"), 60)
        val c = TerminalCursor()
        for (i in times.indices) {
            doc.locate(times[i], false, c)
            val selected = doc.viewportRows(c, 8)
            assertTrue(selected.size <= 8)
            assertTrue(selected.toList() == selected.sorted())
            val focusSlot = selected.indexOf(doc.lines[i].lyricFirst)
            assertTrue(focusSlot >= 0)
            if (i > 0) {
                val previous = selected.indexOf(doc.lines[i - 1].lyricFirst)
                assertTrue(previous >= 0 && focusSlot - previous <= 3)
            }
            if (i < times.lastIndex) {
                val next = selected.indexOf(doc.lines[i + 1].lyricFirst)
                assertTrue(next >= 0 && next - focusSlot <= 3)
            }
        }
    }

    @Test
    fun activeWordsHavePriorityOverCodeWhenAllWordsAndNeighborsFit() {
        val doc = TerminalDocument.build(longArrayOf(0L, 4_000L, 8_000L), listOf("previous", "word ".repeat(11), "next"), 22)
        val c = TerminalCursor()
        doc.locate(4_000L, true, c)
        val active = doc.lines[1]
        val selected = doc.viewportRows(c, 8)
        assertTrue(active.lyricCount <= 6)
        for (r in active.lyricFirst until active.lyricFirst + active.lyricCount) assertTrue(r in selected)
        assertTrue(doc.lines[0].lyricFirst in selected)
        assertTrue(doc.lines[2].lyricFirst in selected)
        assertTrue(selected.size <= 8)
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
    fun theCameraIsStillAtZeroIntensityAndSettlesAfterADisplacement() {
        val still = TerminalCamera()
        repeat(120) { still.update(1f / 60f, it / 60f, intensity = 0f, bass = 1f, energy = 1f, kick = 1f, wide = false) }
        assertEquals(0f, still.yaw.value, 1e-6f)
        assertEquals(0f, still.dolly.value, 1e-6f)

        val cam = TerminalCamera()
        cam.dolly.kick(0.5f)
        cam.update(1f / 60f, 0f, intensity = 1f, bass = 0f, energy = 0f, kick = 0f, wide = false)
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
