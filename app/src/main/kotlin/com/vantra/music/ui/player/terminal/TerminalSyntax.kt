package com.vantra.music.ui.player.terminal

import kotlin.math.min

/** The editor palette, as ARGB ints so drawing never builds a Color. */
internal object TerminalPalette {
    /** control flow: if else while for switch case break continue return */
    const val Keyword = 0xFFFF9A82.toInt()

    /** declarations: const let var fun class object function async await suspend emit collect */
    const val Declare = 0xFF79B8FF.toInt()
    const val Function = 0xFFE3C078.toInt()
    const val Type = 0xFF7EB6FF.toInt()
    const val Number = 0xFF79C0FF.toInt()
    const val Constant = 0xFFFF9E64.toInt()
    const val Quoted = 0xFFF0A35E.toInt()
    const val Ink = 0xFFE6EDF3.toInt()
    const val Punct = 0xFF9AA4B2.toInt()
    const val Operator = 0xFFB6C2CF.toInt()
    const val Comment = 0xFF6F7C8A.toInt()

    /** the song's own words: high-contrast neutral ink, distinct from syntax colours */
    const val Lyric = 0xFFF5F7FA.toInt()
    const val Gutter = 0xFF5B6370.toInt()
    const val Cursor = 0xFFE8EDF2.toInt()
    const val Glow = 0xFFFF8A3D.toInt()

    /** nested brackets cycle through these, by depth */
    val Brackets = intArrayOf(0xFFFFD166.toInt(), 0xFF9CC6DB.toInt(), 0xFF6CE0FF.toInt())
}

/** Bit flags in the fourth number of every span. */
internal object TerminalStyle {
    const val Bold = 1
    const val Italic = 2

    /** the span is the song's own text, not code around it */
    const val Lyric = 4
}

/** Running bracket depth, carried across the pieces of one row so a `(` and its `)` get the same colour. */
internal class Lexer {
    var depth = 0
}

/**
 * Syntax colouring for the code the lyrics sit in. It never touches a lyric: the lyric's own
 * characters are added by the document as a single [TerminalStyle.Lyric] span, so what is shown is
 * always exactly what was sent. Spans are quads of (start, end, colour, style) into the row's text.
 */
internal object TerminalSyntax {
    const val Stride = 4

    private val control = hashSetOf(
        "if", "else", "while", "for", "switch", "case", "break", "continue", "return", "do", "try", "catch",
        "when", "yield", "throw", "default", "finally",
    )
    private val declare = hashSetOf(
        "const", "let", "var", "val", "function", "async", "await", "class", "object", "fun", "suspend",
        "emit", "collect", "import", "from", "export", "new", "in", "of", "override", "private", "public",
    )
    private val constants = hashSetOf("true", "false", "null", "this", "it", "undefined")
    private val ops3 = arrayOf("===", "!==")
    private val ops2 = arrayOf("==", "!=", "=>", "->", "&&", "||", "?.", "?:", "<=", ">=", "++", "--", "+=", "-=", "::", "??")
    private const val ops1 = "=+-*/<>!?%&|~^"
    private const val punct = ";:.,@#"

    /** Appends the spans of one piece of code, [base] being where it starts in the finished row. */
    fun code(text: String, base: Int, out: ArrayList<Int>, lex: Lexer) {
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            when {
                c == ' ' -> i++
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    add(out, base + i, base + n, TerminalPalette.Comment, TerminalStyle.Italic)
                    i = n
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val close = text.indexOf("*/", i + 2)
                    val e = if (close < 0) n else close + 2
                    add(out, base + i, base + e, TerminalPalette.Comment, TerminalStyle.Italic)
                    i = e
                }
                c == '"' || c == '\'' || c == '`' -> {
                    var j = i + 1
                    while (j < n && text[j] != c) { if (text[j] == '\\') j++; j++ }
                    val e = min(n, j + 1)
                    add(out, base + i, base + e, TerminalPalette.Quoted, 0)
                    i = e
                }
                c.isDigit() -> {
                    var j = i + 1
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '.')) j++
                    add(out, base + i, base + j, TerminalPalette.Number, 0)
                    i = j
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    var j = i + 1
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '$')) j++
                    word(text, i, j, base, out)
                    i = j
                }
                c == '(' || c == '[' || c == '{' -> {
                    add(out, base + i, base + i + 1, bracket(lex.depth), 0)
                    lex.depth++
                    i++
                }
                c == ')' || c == ']' || c == '}' -> {
                    if (lex.depth > 0) lex.depth--
                    add(out, base + i, base + i + 1, bracket(lex.depth), 0)
                    i++
                }
                else -> i = operatorOrPunct(text, i, base, out)
            }
        }
    }

    private fun bracket(depth: Int) = TerminalPalette.Brackets[depth % TerminalPalette.Brackets.size]

    private fun word(text: String, start: Int, end: Int, base: Int, out: ArrayList<Int>) {
        val w = text.substring(start, end)
        when {
            w in control -> add(out, base + start, base + end, TerminalPalette.Keyword, TerminalStyle.Bold)
            w in declare -> add(out, base + start, base + end, TerminalPalette.Declare, TerminalStyle.Bold)
            w in constants -> add(out, base + start, base + end, TerminalPalette.Constant, 0)
            nextNonSpace(text, end) == '(' -> add(out, base + start, base + end, TerminalPalette.Function, 0)
            w[0].isUpperCase() -> add(out, base + start, base + end, TerminalPalette.Type, 0)
            else -> add(out, base + start, base + end, TerminalPalette.Ink, 0)
        }
    }

    private fun nextNonSpace(text: String, from: Int): Char {
        var k = from
        while (k < text.length && text[k] == ' ') k++
        return if (k < text.length) text[k] else '\u0000'
    }

    private fun operatorOrPunct(text: String, i: Int, base: Int, out: ArrayList<Int>): Int {
        for (op in ops3) if (text.startsWith(op, i)) { add(out, base + i, base + i + 3, TerminalPalette.Operator, 0); return i + 3 }
        for (op in ops2) if (text.startsWith(op, i)) { add(out, base + i, base + i + 2, TerminalPalette.Operator, 0); return i + 2 }
        val c = text[i]
        val color = when {
            ops1.indexOf(c) >= 0 -> TerminalPalette.Operator
            punct.indexOf(c) >= 0 -> TerminalPalette.Punct
            else -> TerminalPalette.Ink
        }
        add(out, base + i, base + i + 1, color, 0)
        return i + 1
    }

    fun add(out: ArrayList<Int>, start: Int, end: Int, color: Int, style: Int) {
        out.add(start); out.add(end); out.add(color); out.add(style)
    }
}
