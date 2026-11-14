package com.vantra.music.ui.player.terminal

/** The editor palette, as ARGB ints so drawing never builds a Color. */
internal object TerminalPalette {
    const val Keyword = 0xFFFF4D4D.toInt()
    const val Function = 0xFFFFB454.toInt()
    const val Type = 0xFF7EB6FF.toInt()
    const val Number = 0xFF79C0FF.toInt()
    const val Quoted = 0xFFF0A35E.toInt()
    const val Ink = 0xFFE6EDF3.toInt()
    const val Punct = 0xFFB4BCC8.toInt()
    const val Operator = 0xFFFF4D4D.toInt()
    const val Gutter = 0xFF5B6370.toInt()
    const val Cursor = 0xFFFF2A2A.toInt()
    const val Glow = 0xFFFF8A3D.toInt()
}

/**
 * Syntax colouring for a lyric line. It is only a presentation: it hands back colours for ranges
 * of the line's own text and never changes, adds or drops a character. The same words always get
 * the same colour (a hash of the lowercased word), so nothing flickers on a seek or a redraw.
 */
internal object TerminalSyntax {
    private val plain = setOf(
        "i", "you", "the", "a", "an", "to", "and", "of", "in", "my", "me", "it", "is", "on", "at", "be",
        "we", "he", "she", "they", "your", "that", "this", "for", "with", "but", "so", "if", "or", "no",
        "not", "do", "was", "are", "am", "i'm", "i'll", "i've", "i'd", "it's", "don't", "can't", "just",
    )

    /**
     * Triples of (start, end, colour) into [text], in order, for every visible run: words, quoted
     * strings, numbers and punctuation. Whitespace gets no entry.
     */
    fun highlight(text: String): IntArray {
        val out = ArrayList<Int>(text.length / 2 + 3)
        var i = 0
        var wordNo = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == '"' || c == '\u201C' -> {
                    val close = if (c == '"') '"' else '\u201D'
                    var j = i + 1
                    while (j < n && text[j] != close && text[j] != '"') j++
                    val end = if (j < n) j + 1 else n
                    add(out, i, end, TerminalPalette.Quoted)
                    wordNo++
                    i = end
                }
                isWordChar(c) -> {
                    var j = i + 1
                    while (j < n && (isWordChar(text[j]) || (isApostrophe(text[j]) && j + 1 < n && text[j + 1].isLetter()))) j++
                    add(out, i, j, wordColor(text, i, j, wordNo))
                    wordNo++
                    i = j
                }
                else -> {
                    add(out, i, i + 1, if (c == '-' || c == '!' || c == '?' || c == '=' || c == '\u2026') TerminalPalette.Operator else TerminalPalette.Punct)
                    i++
                }
            }
        }
        return out.toIntArray()
    }

    private fun add(out: ArrayList<Int>, start: Int, end: Int, color: Int) {
        out.add(start); out.add(end); out.add(color)
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'
    private fun isApostrophe(c: Char) = c == '\'' || c == '\u2019'

    private fun wordColor(text: String, start: Int, end: Int, wordNo: Int): Int {
        if (wordNo == 0) return TerminalPalette.Keyword
        var digits = true
        for (k in start until end) if (!text[k].isDigit()) { digits = false; break }
        if (digits) return TerminalPalette.Number
        val word = text.substring(start, end).lowercase()
        if (word in plain) return TerminalPalette.Ink
        if (text[start].isUpperCase() && end - start > 1) return TerminalPalette.Type
        return when ((word.hashCode() and 0x7FFFFFFF) % 3) {
            0 -> TerminalPalette.Function
            1 -> TerminalPalette.Type
            else -> TerminalPalette.Ink
        }
    }
}
