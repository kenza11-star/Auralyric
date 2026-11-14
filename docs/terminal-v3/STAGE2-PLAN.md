# Stage 2 — camera and depth

Continue the Stage 1 Canvas homography architecture; no GL rewrite or dependencies.

1. Add regression tests for depth parallax, near-plane rejection, bounded continuous camera motion, seeks, reduced motion and orientation.
2. Replace beat impulses with critically damped camera targets. Song time supplies a slow continuous path; actual lyric timing supplies verse progression and instrumental framing. Clamp angles and dolly. Keep all setting keys and playback APIs.
3. Render two structured background document planes (one in LOW) at distinct positive depths, then the Stage 1 editor at z=0. Reuse paints, matrices and scratch arrays. Apply perspective depth fade and protect the active lyric.
4. Fit the projected active lyric band inside a safe region, constrain horizontal page width without cancelling dolly unnecessarily, and retain real perspective/parallax.
5. Compile/run the real Kotlin core tests and compile the renderer against an Android API jar. Attempt the Android Gradle build and report its actual result. Verify unchanged files byte-for-byte against Stage 1 and deliver complete ZIP with report.

Review focus: long wrapped lyrics, rapid timestamps, seek/resume, portrait/landscape resize, no-audio/reduced-motion/LOW paths. Device rendering and performance require an Android device; JVM checks do not replace them.
