# Terminal player style (Phase 2 redesign)

**Build status: NOT compiled, NOT tested, NOT run on a device.** The environment this was written in
has no Android SDK, no Kotlin compiler and no network, and cannot push to GitHub, so nothing below
has been through Gradle or CI. Verify before shipping:

    ./gradlew :app:testFossDebugUnitTest --tests "com.vantra.music.ui.player.terminal.*"
    ./gradlew assembleArm64GmsRelease          (what .github/workflows/build.yml runs)

Note: CI only assembles the APK; it does not run unit tests.

## What changed
The Terminal style is the same style in the same place (`PlayerStyle.TERMINAL`, `StyleFrame`, the
rotation/gesture/menu plumbing, the settings sheet and preferences are untouched). Only what it draws changed.

- **Lyrics are now code.** Every lyric becomes a statement with the words as a string inside it
  (`if`/`else`/`else if`, `while`, `for`, `switch`/`case`, `break`/`continue`/`return`, `const`/`let`/`var`/`val`,
  `function`, `async`/`await`, `class`, `object`, `fun`/`suspend`, `emit`, `collect`, `when`, `try`/`catch`,
  and `() {} [] "" '' ; : . , => -> == === != && || ?. ?:`), with line numbers, indentation, comments
  and syntax colours. 25 templates in a fixed shuffled order, so neighbours never look alike and the
  same song always produces the same file. A header (comment, imports, `suspend fun render() {`) opens it.
- **The words are exact.** Each lyric row carries a slice of the lyric between quotes, copied unchanged and
  coloured as a distinct, brighter "lyric" span. Long lines wrap as concatenated strings
  (`"..." +`). If the lyric contains the template's quote character, another quote character is
  used rather than escaping (an escape would change the text).
- **Typing.** Code before the words is written at once; the words type in; the code that closes the
  block arrives with the last letter. Still a pure function of the playback position (seeks land exactly).
- **Real 3D.** Rows are planes projected with a pinhole camera and a projective matrix. Past rows recede in
  depth; rows still to come show only as faint code outlines (never lyrics). Around the page: two
  side panels and one large back panel of filler code at their own depths and scroll speeds (parallax),
  plus a perspective floor grid. The page itself is yawed in space.
- **Camera.** Resting angle onto the page, slow time-based orbit, tilt/roll/dolly springs, a slide that
  follows the cursor along the row, beat kicks from the audio spectrum when available, a gentle push on
  each new line when it isn't. Reduced-motion still shows the static 3D composition.
- **Depth cues.** Scale (projection), opacity by distance, active-line band + accent bar, per-layer alpha.
- **Landscape.** Fullscreen: system bars hidden while it shows (restored on leave), title/artist/status hide
  with the playback controls, a tap brings everything back; only the progress hairline stays.
  Type is code-sized (many rows/columns) with a wide composition and side panels.
- **Portrait.** Same scene with a narrower page, side panels at the edges, tab + controls as before.

## Files
Modified/rewritten in `app/src/main/kotlin/com/vantra/music/ui/player/terminal/`:
`TerminalDocument.kt` (code model), `TerminalSyntax.kt` (tokenizer/palette), `TerminalRenderer.kt`,
`TerminalCamera.kt` (base angles, follow), `TerminalPlayerStyle.kt` (host, immersive bars, chrome).
New: `TerminalAmbient.kt`. Tests: `app/src/test/.../terminal/TerminalCoreTest.kt` (rewritten document tests).
Unchanged: `BeatTracker.kt`, `TerminalOptions.kt`, preferences, all other styles, playback, signing, GitHub, backup.

## Known risks (untested)
- Bold/italic monospace faces are assumed to have the same advance as regular (column maths relies on it).
- Draw cost: ~3 extra panels of ~30 rows each per frame; the existing AUTO quality drops to the light
  mode (no panels, floor, glow, gutter) on low-RAM devices or after sustained slow frames.
- Hiding the system bars uses the window insets controller; verify rotation in/out restores them.
