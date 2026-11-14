# Terminal V3 Stage 1

Goal: improve only Terminal's visual foundation using the current Compose/native Canvas architecture.

1. Inspect the active com.vantra source, style routing, preferences, lyric offset and clock, camera/projector, signing, workflows and the supplied 58.3-second recording.
2. Keep camera/beat interfaces and playback unchanged. Replace per-row depth, detached panels, floor and broad glow with one projected editor plane. Use a measured monospace hierarchy, consistent gutter/guides and a readable active string.
3. Make document generation deterministic, use bounded, syntactically plausible templates, measured Unicode wrapping and visible previous/next lyric context. Keep cursor timing, seek behavior and lyrics intact. Show a code-only no-synced-lyrics fallback.
4. Reserve actual header/control/inset space in both orientations. Preserve controls, gestures, rotation preferences and all other styles.
5. Extend existing document tests for narrow rows, Unicode, vocabulary and focus. Attempt Gradle compilation/tests; report toolchain/network blockers without claiming success. Include a dedicated CI verification workflow if local Android compilation is unavailable.
6. Verify the ZIP against the original archive so unrelated files, signing and update configuration remain byte-identical. Package the full project plus changed-file/changelog/verification notes. Stop before Stage 2.

Review focus: narrow screens and font scaling; long unbroken/CJK/emoji lyrics; seeking/paused lyric replacement; absent lyrics; landscape controls and safe areas. Native device rendering remains a required final visual check if the Android toolchain is unavailable here.
