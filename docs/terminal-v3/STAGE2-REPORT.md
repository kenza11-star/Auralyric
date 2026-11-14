# Vantra Terminal V3 — Stage 2

## Implemented

Stage 1's continuous editor, syntax colours, typography, wrapping, gutter, indentation guides, active-row highlight, real lyric strings and native controls remain the foreground. This is an incremental Canvas renderer change, not a renderer or app rebuild.

- Three parallel world-space document planes: editor at z=0, rear scaffolding at 0.45 and 0.95 times the focal distance. LOW draws only the middle rear plane. Scaffolding uses the current document's non-lyric rows; timed lyric strings are never duplicated in the background.
- Depth affects perspective scale and opacity. Rear planes have controlled lateral offsets, stable gutters and consistent row spacing. They share the same camera and final framing correction, preserving parallax. A 92% opaque black editor backing protects the active words from rear code.
- Slow dolly/pan/yaw/tilt/roll targets use playback time, real lyric-interval progress and the existing document's instrumental flag. Quintic interval easing and near-critical damping replace camera impulses. No randomness or synthetic beat detection was added. Existing real audio spectrum/beat tracking remains for the established lighting and source indication.
- Maximum target yaw ±0.18 rad (~10.3°), pitch ±0.12 rad (~6.9°), roll amplitude at maximum intensity 0.0128 rad (~0.73°). Lateral drift is restrained. Instrumental sections gradually pull back; lyric progression gently moves forward. Typing does not steer the camera.
- Regular lyric changes never snap camera state or inject spring velocity. The editor scroll is critically damped; shared rows retain their position through folded-viewport changes and the correction settles smoothly. Initial documents, track changes and explicit playback seeks re-anchor document scroll; camera motion still eases to its new target.
- Portrait focal distance is 1.65 × viewport width; landscape is 1.10 × width. The projected active lyric band is fitted within x=4–96%, y=12–88% of the scene, which already excludes playback controls. The whole page is no longer vertically auto-fitted every frame, so dolly remains visible. Text outside the old local page during a scroll is not discarded before its safe projection.

## Architecture and lifecycle inspection

The active app source is `app/src/main/kotlin/com/vantra/music`, selected by the existing Terminal style dispatcher. Duplicate legacy source trees in the ZIP were preserved unchanged.

- `TerminalScene` renders through Compose `drawBehind` and native `android.graphics.Canvas`. It is not an OpenGL renderer. Projection uses equivalent analytic view transforms followed by pinhole division; four projected corners define each Canvas homography (`Matrix.setPolyToPoly`). No graphics dependency was added.
- World units are pixels, y points down and positive z recedes from the editor. Lateral camera translation is applied before rotation and perspective division, fixing the previous screen-space pan that eliminated translation parallax. `scaleAt` now accounts for the view rotation/translation at a plane's origin. The focal-distance near plane rejects invalid geometry.
- `withFrameNanos` supplies frame time; Media3 position supplies song time; the existing lyrics offset supplies lyric time. Camera springs integrate at ≤1/120 second slices and cap stalled updates at 100 ms. A paused position stops path progression while existing settling and blink behaviour remain.
- Existing `repeatOnLifecycle(STARTED)`, visibility cancellation, frame invalidation without recomposition, paused blink throttling, LOW/AUTO quality logic, failure isolation, style selection, preferences, orientation and playback service APIs were retained. Intensity OFF/reduced motion holds the camera still.
- Retained paints, two matrices, projector, framing result and scratch arrays are reused. No per-frame GPU buffers, textures, bitmaps or projection result objects are introduced. Background row traversal is bounded to at most 29 rows per plane, with clipping, and never scans the full document. Folding selection allocations occur only when the document/cursor viewport changes, as in Stage 1. Canvas recording and the small camera update remain on the existing UI/frame thread; no unsafe background Canvas access or new main-thread I/O was added.

## Changed files

Modified:

1. `app/src/main/kotlin/com/vantra/music/ui/player/terminal/TerminalCamera.kt`
2. `app/src/main/kotlin/com/vantra/music/ui/player/terminal/TerminalRenderer.kt`
3. `app/src/main/kotlin/com/vantra/music/ui/player/terminal/TerminalPlayerStyle.kt`
4. `app/src/test/kotlin/com/vantra/music/ui/player/terminal/TerminalCoreTest.kt`

Added:

5. `app/src/main/kotlin/com/vantra/music/ui/player/terminal/TerminalViewportMotion.kt`
6. `app/src/test/kotlin/com/vantra/music/ui/player/terminal/TerminalCameraStage2Test.kt`
7. `docs/terminal-v3/STAGE2-PLAN.md`
8. `docs/terminal-v3/STAGE2-REPORT.md`
9. `docs/terminal-v3/STAGE2-TEST-OUTPUT.txt`
10. `docs/terminal-v3/STAGE2-ARCHIVE-AUDIT.txt`

## Actual verification

- PASS: Kotlin 2.3.10 compilation of the real Terminal camera, viewport motion, document, lexer, beat tracker and renderer, plus the two Terminal JUnit test classes. Renderer compilation used the Android 4.1.1.4 API stub jar (only as a compile-time API surface); it did not execute Android drawing. No stub library was added to the application.
- PASS: **37 JUnit tests**, zero failures. The 29 existing tests remain, with the old camera-kick expectation deliberately changed to a displacement/settling check because cinematic camera impulses were removed. Eight new tests cover onset immunity, translation parallax, depth/near-plane rejection, bounded movement across seeks and orientation changes, 30/120 Hz consistency, safe-region projected corners in portrait/landscape, instrumental/reduced camera motion and fold continuity/settling. Raw final JUnit output is included alongside this report.
- Regression proof: before camera changes, the new onset, parallax and motion-bound tests failed against Stage 1. They passed after implementation. Safe-framing and fold tests initially could not compile because their new helper classes did not yet exist; they now compile and pass.
- PASS: Kotlin compiler PSI syntax parsing of `TerminalPlayerStyle.kt`. This checks syntax only, not Compose symbol resolution or Android integration compilation. The parser utility emitted a compiler-tool K1 API deprecation warning; that utility is not app code.
- BLOCKED: `./gradlew :app:compileArm64FossDebugKotlin :app:testArm64FossDebugUnitTest --no-daemon` stopped during Gradle 9.3.1 download from the project's configured mirror with `java.net.SocketException: Network is unreachable`. No Android SDK was found in the standard SDK locations checked. Android/Compose compilation and the complete application test suite therefore remain unverified. No APK was produced.
- The existing Stage 1 GitHub verification workflow is preserved. Its Gradle command compiles Android integration and runs application unit tests, including the added Stage 2 tests; it was not executed remotely in this session.
- Archive audit compares every original ZIP member against the output bytes. The separate audit lists exact counts; no original file was deleted, and only the four listed Terminal Kotlin files were modified. Signing, manifest, Gradle configuration, playback service, lyric timing/document generation, settings and other styles remain byte-identical.

## Device verification still required

No Android device/emulator was run. Visual quality, native font/homography rendering, frame time, GPU memory and battery use have not been measured. Unit tests establish the projection and motion properties, not the final native appearance.

Check portrait/landscape, maximum intensity, LOW/AUTO, intensity OFF/system reduced motion, paused seeks, resume, rapid and repeated lyric timestamps, long wrapped/CJK/emoji lyrics, no lyrics, instrumental gaps, controls and style switching. Very small viewports or unusually tall visible lyric blocks can require shrinking the projected band; there is no claim of a fixed minimum post-projection font size on every device.

Stage 2 ends here. No Stage 3 work or unrelated feature changes.
