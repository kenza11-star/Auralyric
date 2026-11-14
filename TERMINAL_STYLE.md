# Terminal player style

**Build status: NOT compiled, NOT tested.** The environment this was written in had no Android SDK
and no network, so Gradle could not run. Compile and run the unit tests before shipping:

    ./gradlew :app:testFossDebugUnitTest --tests "com.vantra.music.ui.player.terminal.*"
    ./gradlew :app:assembleUniversalFossDebug   (or your usual debug variant)

## Added
- `ui/player/terminal/BeatTracker.kt` – onset/bass detection from the player's own spectrum
- `ui/player/terminal/TerminalCamera.kt` – spring camera + perspective projector (pure Kotlin)
- `ui/player/terminal/TerminalSyntax.kt` – colours for ranges of a lyric line (never edits text)
- `ui/player/terminal/TerminalDocument.kt` – lyrics as wrapped rows; typing/cursor as a pure function of position
- `ui/player/terminal/TerminalRenderer.kt` – draws the 3D scene on a Canvas
- `ui/player/terminal/TerminalPlayerStyle.kt` – the style: frame loop, HUD, orientation, controls
- `ui/player/studio/TerminalOptions.kt` – the settings sheet
- `app/src/test/.../terminal/TerminalCoreTest.kt` – unit tests for the pure logic

## Modified
- `constants/PreferenceKeys.kt` – `PlayerStyle.TERMINAL`, selector column, 5 preference keys + enums
- `ui/player/styles/AlternatePlayerStyles.kt` – dispatch; `StyleFrame(rotatedOverride)`; `StyleControls` made internal
- `ui/player/BottomSheetPlayer.kt` – Edit-player entry for Terminal, style sheet host
- `ui/player/studio/PlayerStudioState.kt`, `StudioSheets.kt` – `StudioSheet.TERMINAL` and its sheet
- `ui/screens/gesture/CustomisationPreviews.kt` – a preview card for the style
