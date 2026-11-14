package com.vantra.music.ui.player.terminal

/** One line of the code that floats around the main page. */
internal class AmbientRow(val text: String, val spans: IntArray)

/**
 * The supporting code in the scene: panels to the sides, one far behind, all drawn at their own
 * depth. It is a fixed program, never lyrics, so it can't be mistaken for or interfere with the words.
 * Every line is plain ASCII, so the renderer lays it out without measuring.
 */
internal object TerminalAmbient {
    private val source = arrayOf(
        "export async function sync(track: Track) {",
        "  const lines = await lrc.fetch(track.id);",
        "  for (const line of lines) {",
        "    if (line.time <= clock.now) continue;",
        "    await delay(line.time - clock.now);",
        "    emit(line.text);",
        "  }",
        "}",
        "",
        "class BeatTracker : Flow<Float> {",
        "  private var energy = 0f",
        "  suspend fun collect(f: (Float) -> Unit) {",
        "    spectrum.bands.collect { bands ->",
        "      val flux = bands.sumOf { it.rise } / bands.size",
        "      if (flux > mean + 1.5f * dev) f(flux)",
        "    }",
        "  }",
        "}",
        "",
        "const camera = { yaw: 0.0, pitch: -0.09, dolly: 0.0 };",
        "function orbit(t) {",
        "  return Math.sin(t * 0.31) * 0.12;",
        "}",
        "",
        "while (playing) {",
        "  const t = await clock.tick();",
        "  camera.yaw = orbit(t) + drift?.yaw ?: 0;",
        "  if (t % 4 === 0 && !paused) {",
        "    camera.dolly += 0.05;",
        "  } else if (t != last || beat) {",
        "    camera.dolly -= 0.02;",
        "  } else {",
        "    continue;",
        "  }",
        "}",
        "",
        "object Palette {",
        "  val keyword = \"#ff5c5c\"",
        "  val string = '#f0a35e'",
        "  val bracket = [gold, pink, cyan]",
        "}",
        "",
        "switch (mode) {",
        "  case 'live': render(); break;",
        "  case 'idle': hold(); break;",
        "  default: return;",
        "}",
        "",
        "let frames = 0, dropped = 0;",
        "var fps = 60 || cap;",
        "fun draw(c: Canvas) = c.drawText(row, x, y, paint)",
        "suspend fun pulse() = bass.collect { glow = it }",
        "emit(\"ready\");",
        "// ---- end of scene ----",
    )

    val rows: Array<AmbientRow> = Array(source.size) { i ->
        val spans = ArrayList<Int>()
        TerminalSyntax.code(source[i], 0, spans, Lexer())
        AmbientRow(source[i], spans.toIntArray())
    }
}
