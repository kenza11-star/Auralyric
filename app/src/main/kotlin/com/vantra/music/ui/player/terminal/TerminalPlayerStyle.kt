package com.vantra.music.ui.player.terminal

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.OrientationEventListener
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.State
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import com.vantra.music.LocalPlayerConnection
import com.vantra.music.constants.PlayerStyle
import com.vantra.music.constants.TerminalBeatKey
import com.vantra.music.constants.TerminalIntensity
import com.vantra.music.constants.TerminalIntensityKey
import com.vantra.music.constants.TerminalOrientation
import com.vantra.music.constants.TerminalOrientationKey
import com.vantra.music.constants.TerminalQuality
import com.vantra.music.constants.TerminalQualityKey
import com.vantra.music.constants.TerminalTypingKey
import com.vantra.music.lyrics.LyricsEntry
import com.vantra.music.ui.player.findActivity
import com.vantra.music.ui.player.styles.LivePosition
import com.vantra.music.ui.player.styles.StyleActions
import com.vantra.music.ui.player.styles.StyleControls
import com.vantra.music.ui.player.styles.StyleFrame
import com.vantra.music.ui.player.styles.StyleTrack
import com.vantra.music.ui.player.styles.rememberDurationMs
import com.vantra.music.ui.player.styles.rememberLivePosition
import com.vantra.music.ui.player.styles.rememberPlayedSeconds
import com.vantra.music.ui.player.styles.rememberStyleLyrics
import com.vantra.music.ui.player.styles.rememberStyleShowing
import com.vantra.music.ui.player.styles.rememberStyleTones
import com.vantra.music.ui.player.styles.rememberStyleTrack
import com.vantra.music.ui.player.styles.styleInsets
import com.vantra.music.ui.player.styles.withLyricsOffset
import com.vantra.music.ui.utils.AppMotion
import com.vantra.music.utils.rememberEnumPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

private val Mono = FontFamily.Monospace
private val Ink = Color(TerminalPalette.Ink)
private val Red = Color(TerminalPalette.Cursor)

/**
 * The Terminal player style: the song's synced lyrics typed out as syntax-highlighted code in a
 * dark 3D scene, with a camera that moves to the music. It sits in the same frame as the other
 * alternate styles (gestures, the ••• menu, the quarter turn), reads the same playback state and
 * lyrics, and keeps all drawing inside [TerminalScene] so a renderer failure can't reach playback.
 */
@Composable
fun TerminalPlayerStyle(actions: StyleActions, modifier: Modifier = Modifier, preview: Boolean = false) {
    val tones = rememberStyleTones()
    val track = rememberStyleTrack()
    val position = rememberLivePosition(animated = !preview).withLyricsOffset()
    val duration = rememberDurationMs()
    val lyrics = rememberStyleLyrics(readings = !preview)
    var orientation by rememberEnumPreference(TerminalOrientationKey, TerminalOrientation.AUTO)
    val sideways = rememberDeviceSideways(enabled = !preview && orientation == TerminalOrientation.AUTO)
    val rotated = when (orientation) {
        TerminalOrientation.AUTO -> sideways
        TerminalOrientation.PORTRAIT -> false
        TerminalOrientation.LANDSCAPE -> true
    }
    val source = remember { mutableStateOf(BeatSource.NONE) }
    val connection = LocalPlayerConnection.current
    val playing = connection?.isPlaying?.collectAsState()?.value == true

    StyleFrame(
        style = PlayerStyle.TERMINAL,
        preview = preview,
        modifier = modifier,
        tones = tones,
        background = { Box(Modifier.fillMaxSize().background(Color.Black)) },
        rotatedOverride = rotated,
    ) { landscape, menuOpen, setMenuOpen, isRotated, _ ->
        val insets = styleInsets(landscape)
        // landscape is a fullscreen code environment: the system bars step aside while it shows
        ImmersiveWhileLandscape(active = landscape && !preview)

        // in landscape the controls step aside while the music plays; a tap brings them back
        var reveal by remember { mutableIntStateOf(0) }
        var controlsOn by remember { mutableStateOf(true) }
        LaunchedEffect(landscape, playing, menuOpen, reveal) {
            controlsOn = true
            if (landscape && playing && !menuOpen && !preview) {
                delay(ControlsHideMs)
                controlsOn = false
            }
        }
        val controlsAlpha by animateFloatAsState(if (controlsOn) 1f else 0f, tween(220), label = "terminalControls")

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // a tap that takes nothing from the gestures around it (double tap still plays and pauses)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val up = waitForUpOrCancellation(PointerEventPass.Initial)
                        if (up != null && (up.position - down.position).getDistance() < viewConfiguration.touchSlop) reveal++
                    }
                },
        ) {
            TerminalScene(
                lines = lyrics.lines,
                track = track,
                position = position,
                landscape = landscape,
                preview = preview,
                source = source,
                modifier = Modifier.fillMaxSize(),
            )
            if (!preview) {
                TerminalChrome(
                    track = track,
                    position = position,
                    duration = duration,
                    hasLyrics = lyrics.lines.isNotEmpty(),
                    source = source.value,
                    landscape = landscape,
                    insetStart = insets.start,
                    insetTop = insets.top,
                    insetEnd = insets.end,
                    insetBottom = insets.bottom,
                    uiAlpha = controlsAlpha,
                )
                if (controlsAlpha > 0.01f) {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = controlsAlpha }) {
                        StyleControls(
                            tones, landscape, insets, menuOpen, setMenuOpen, actions, isRotated,
                            { turned -> orientation = if (turned) TerminalOrientation.LANDSCAPE else TerminalOrientation.PORTRAIT },
                            menuTop = insets.top + 20.dp,
                        )
                    }
                }
            }
        }
    }
}

private const val ControlsHideMs = 3_500L

// ---- the scene --------------------------------------------------------------------------

/** Everything the scene moves, kept off the snapshot system: the frame loop writes it, the draw reads it. */
private class SceneState {
    val camera = TerminalCamera()
    val tracker = BeatTracker()
    val cursor = TerminalCursor()
    val scroll = Spring()
    var glow = 0f
    var low = false
    var timeNs = 0L
    var solidCursor = false
    var followX = 0f
    var lastLine = -2
    var primed = false
}

@Composable
private fun TerminalScene(
    lines: List<LyricsEntry>,
    track: StyleTrack,
    position: LivePosition,
    landscape: Boolean,
    preview: Boolean,
    source: MutableState<BeatSource>,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val connection = LocalPlayerConnection.current
    val renderer = remember { TerminalRenderer() }
    val state = remember { SceneState() }
    val frame = remember { mutableLongStateOf(0L) }
    val failed = remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    val intensity by rememberEnumPreference(TerminalIntensityKey, TerminalIntensity.NORMAL)
    val quality by rememberEnumPreference(TerminalQualityKey, TerminalQuality.AUTO)
    val beatPref by com.vantra.music.utils.rememberPreference(TerminalBeatKey, defaultValue = true)
    val typingPref by com.vantra.music.utils.rememberPreference(TerminalTypingKey, defaultValue = true)
    val lowDevice = remember(context) { isLowEndDevice(context) }

    val cols = remember(size, landscape) { renderer.layout(size.width, size.height, landscape) }
    val doc = remember(lines, track.title, track.artist, cols) { buildDocument(lines, track, cols) }
    val docNow by rememberUpdatedState(doc)
    val landscapeNow by rememberUpdatedState(landscape)
    val playing = connection?.isPlaying?.collectAsState()?.value == true
    val showing = rememberStyleShowing()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // a selector card: one still frame of the scene, from an angle so it reads as 3D
    if (preview) {
        LaunchedEffect(doc, size, landscape) {
            val s = state
            doc.locate(position.positionMs(), typing = false, out = s.cursor)
            s.scroll.snap(s.cursor.row.toFloat())
            s.camera.reset()
            s.camera.baseYaw = if (landscape) 0.10f else 0.06f
            s.camera.basePitch = -0.09f
            s.camera.yaw.snap(s.camera.baseYaw)
            s.camera.pitch.snap(s.camera.basePitch)
            s.low = false
            s.glow = 0.5f
            s.solidCursor = true
            frame.longValue++
        }
    } else {
        LaunchedEffect(connection, playing, beatPref, typingPref, intensity, quality, lowDevice) {
            val conn = connection ?: return@LaunchedEffect
            val s = state
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                snapshotFlow { showing() }.collectLatest { shown ->
                    if (!shown) {
                        source.value = BeatSource.NONE
                        return@collectLatest
                    }
                    s.tracker.reset()
                    var last = 0L
                    var lastPos = Long.MIN_VALUE
                    var acc = 0f
                    var calm = 0f
                    var unheard = 0f
                    var slowFrames = 0
                    var degraded = false
                    while (true) {
                        if (renderer.failed) {
                            failed.value = true
                            source.value = BeatSource.NONE
                            return@collectLatest
                        }
                        // paused and settled: only the cursor's blink is left, a few redraws a second
                        if (!playing && calm > 1.5f) {
                            delay(BlinkPollMs)
                            s.timeNs = System.nanoTime()
                            val p = conn.player.currentPosition
                            // a seek, or lyrics arriving, while paused: wake up and move to it
                            docNow.locate(position.positionMs(), typingPref && AppMotion.durationScale.scaleFactor != 0f, s.cursor)
                            if (p != lastPos || abs(s.cursor.row - s.scroll.value) > 0.02f) calm = 0f
                            frame.longValue++
                            continue
                        }
                        val now = androidx.compose.runtime.withFrameNanos { it }
                        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.001f, 0.1f)
                        last = now

                        if (quality == TerminalQuality.AUTO) {
                            // frames running long for two seconds: ease off for good
                            slowFrames = if (dt > 0.026f) slowFrames + 1 else maxOf(0, slowFrames - 1)
                            if (slowFrames > 90) degraded = true
                        }
                        val low = quality == TerminalQuality.LOW || (quality == TerminalQuality.AUTO && (lowDevice || degraded))
                        s.low = low
                        // the light mode draws at half rate
                        acc += dt
                        if (low && acc < 0.032f) continue
                        val stepDt = acc
                        acc = 0f

                        val reduced = AppMotion.durationScale.scaleFactor == 0f
                        val k = if (reduced) 0f else intensity.scale
                        val beatOn = beatPref && !reduced
                        val posMs = conn.player.currentPosition

                        // a seek or a restart: what was heard before no longer lines up
                        if (lastPos != Long.MIN_VALUE) {
                            val speed = conn.player.playbackParameters.speed
                            val expected = if (playing) stepDt * 1000f * speed else 0f
                            if (abs(posMs - lastPos - expected) > 400f) s.tracker.reset()
                        }

                        // the audio, as heard right now; only when playing, and only if the player can be tapped
                        val spectrum = if (beatOn && playing) conn.service.spectrumOf(conn.player) else null
                        val heard = spectrum != null && spectrum.read(posMs, s.tracker.bands)
                        s.tracker.update(stepDt, heard)
                        unheard = if (beatOn && playing && !heard) unheard + stepDt else 0f
                        val src = when {
                            heard -> BeatSource.AUDIO
                            beatOn && playing && unheard > 0.8f -> BeatSource.LYRIC_TIMING
                            else -> BeatSource.NONE
                        }
                        if (source.value != src) source.value = src

                        val lyricMs = position.positionMs()
                        docNow.locate(lyricMs, typingPref && !reduced, s.cursor)
                        // the resting angle onto the page, and where along the row the words are being written
                        s.camera.baseYaw = if (landscapeNow) 0.10f else 0.06f
                        s.camera.basePitch = -0.09f
                        s.followX = ((s.cursor.col.toFloat() / maxOf(1, docNow.columns) - 0.5f) * 2f).coerceIn(-1f, 1f)
                        // the lyric fallback: only a gentle push on a new line, never passed off as a beat
                        if (s.cursor.line != s.lastLine) {
                            if (src == BeatSource.LYRIC_TIMING && s.cursor.line >= 0 && s.lastLine != -2) {
                                s.camera.dolly.kick(0.10f * k)
                                s.camera.yaw.kick(0.05f * k)
                            }
                            s.lastLine = s.cursor.line
                        }

                        val target = s.cursor.row.toFloat()
                        if (!s.primed || abs(target - s.scroll.value) > 14f) {
                            s.scroll.snap(target)
                            s.primed = true
                        } else {
                            s.scroll.step(stepDt, target, 70f, 13f)
                        }
                        s.camera.update(
                            dt = stepDt,
                            timeS = posMs / 1000f,
                            intensity = k,
                            bass = if (heard) s.tracker.bass else 0f,
                            energy = if (heard) s.tracker.energy else 0f,
                            kick = if (heard) s.tracker.kick else 0f,
                            wide = landscapeNow,
                            follow = s.followX * k,
                        )
                        s.glow = if (heard) s.tracker.bass else if (src == BeatSource.LYRIC_TIMING) 0.2f else 0.1f
                        s.solidCursor = playing && s.cursor.typed < (docNow.lines.getOrNull(s.cursor.line)?.text?.length ?: 0)
                        s.timeNs = now

                        // still, or only just come to rest: count toward the idle blink
                        calm = if (playing || posMs != lastPos || abs(target - s.scroll.value) > 0.02f) 0f else calm + stepDt
                        lastPos = posMs
                        frame.longValue = now
                    }
                }
            }
        }
    }

    Box(
        modifier
            .onSizeChanged { size = it }
            .drawBehind {
                // reading the tick here is what repaints the scene each frame without recomposing anything
                frame.longValue
                drawIntoCanvas { canvas ->
                    renderer.draw(
                        canvas.nativeCanvas, docNow, state.cursor, state.scroll.value, state.camera,
                        state.timeNs, state.low, landscapeNow, state.glow, state.solidCursor,
                    )
                }
            },
    ) {
        if (failed.value) {
            // the renderer gave up; the music carries on and the words stay readable
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(track.title, color = Ink, style = TextStyle(fontFamily = Mono, fontSize = 18.sp))
                Spacer(Modifier.height(6.dp))
                Text("terminal view unavailable on this device, playback is unaffected", color = Ink.copy(alpha = 0.55f), style = TextStyle(fontFamily = Mono, fontSize = 11.sp))
            }
        }
    }
}

private const val BlinkPollMs = 265L

/** The song as a document: its synced lyrics as they were sent, or, with none, its title and artist. */
private fun buildDocument(lines: List<LyricsEntry>, track: StyleTrack, columns: Int): TerminalDocument {
    if (lines.isNotEmpty()) {
        val times = LongArray(lines.size) { lines[it].time }
        // a line break or tab can't be drawn in a row; each becomes one space, so every position stays the same
        val texts = lines.map { entry ->
            if (entry.text.any { it == '\n' || it == '\r' || it == '\t' }) entry.text.map { if (it == '\n' || it == '\r' || it == '\t') ' ' else it }.joinToString("") else entry.text
        }
        return TerminalDocument.build(times, texts, columns, track.title, track.artist)
    }
    val head = listOf(track.title, track.artist).filter { it.isNotBlank() }
    if (head.isEmpty()) return TerminalDocument.empty()
    return TerminalDocument.build(LongArray(head.size) { it * 900L }, head, columns, track.title, track.artist)
}

// ---- the chrome around the scene ---------------------------------------------------------

@Composable
private fun TerminalChrome(
    track: StyleTrack,
    position: LivePosition,
    duration: Long,
    hasLyrics: Boolean,
    source: BeatSource,
    landscape: Boolean,
    insetStart: androidx.compose.ui.unit.Dp,
    insetTop: androidx.compose.ui.unit.Dp,
    insetEnd: androidx.compose.ui.unit.Dp,
    insetBottom: androidx.compose.ui.unit.Dp,
    /** landscape keeps only the progress hairline until a tap brings the rest back */
    uiAlpha: Float,
) {
    val chromeAlpha = if (landscape) uiAlpha else 1f
    val seconds = rememberPlayedSeconds(position)
    Box(Modifier.fillMaxSize()) {
        // the editor's tab: artwork, title and artist, over a hairline that is the song's progress
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 18.dp + insetStart, top = insetTop + 14.dp, end = (if (landscape) 76.dp else 70.dp) + insetEnd)
                .fillMaxWidth(),
        ) {
            Row(Modifier.graphicsLayer { alpha = chromeAlpha }, verticalAlignment = Alignment.CenterVertically) {
                if (track.artwork != null) {
                    AsyncImage(
                        model = track.artwork,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(track.title, color = Ink.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Mono, fontSize = 12.sp))
                    Text(track.artist, color = Ink.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Mono, fontSize = 10.sp))
                }
                Spacer(Modifier.width(10.dp))
                Text("${clock(seconds)} / ${clock(duration / 1000L)}", color = Ink.copy(alpha = 0.5f), style = TextStyle(fontFamily = Mono, fontSize = 10.sp))
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .drawBehind {
                        drawRect(Color.White.copy(alpha = 0.12f))
                        val f = (position.positionMs().toFloat() / duration).coerceIn(0f, 1f)
                        drawRect(Red, size = androidx.compose.ui.geometry.Size(size.width * f, size.height))
                    },
            )
        }
        // the status line says plainly where the camera's pulse comes from
        Text(
            text = statusLine(source, hasLyrics),
            color = Ink.copy(alpha = 0.4f),
            style = TextStyle(fontFamily = Mono, fontSize = 10.sp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 18.dp + insetStart, bottom = insetBottom + 10.dp)
                .graphicsLayer { alpha = chromeAlpha },
        )
    }
}

private fun statusLine(source: BeatSource, hasLyrics: Boolean): String {
    val beat = when (source) {
        BeatSource.AUDIO -> "beat: audio"
        BeatSource.LYRIC_TIMING -> "beat: none (lyric timing only)"
        BeatSource.NONE -> "beat: off"
    }
    return "$beat  |  lyrics: ${if (hasLyrics) "synced" else "none"}"
}

private fun clock(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0L)
    return "%d:%02d".format(s / 60, s % 60)
}

// ---- device -------------------------------------------------------------------------------

private fun isLowEndDevice(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || am?.isLowRamDevice == true
}

/**
 * Whether the phone is held on its side the way the quarter turn reads upright (turned
 * counter-clockwise), for the Auto orientation. The window itself stays as the app has it; only
 * the Terminal turns, so nothing restarts and playback never notices. It listens only while the
 * style is on screen, and not at all when the system's auto-rotate is off.
 */
@Composable
private fun rememberDeviceSideways(enabled: Boolean): Boolean {
    val context = LocalContext.current
    val showing = rememberStyleShowing()
    var sideways by remember { mutableStateOf(false) }
    val listener = remember(context) {
        object : OrientationEventListener(context.applicationContext) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val autoRotate = runCatching {
                    Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) == 1
                }.getOrDefault(true)
                // a band to enter and a wider one to leave, so it doesn't flutter at the edge
                val next = when {
                    !autoRotate -> false
                    orientation in 250..290 -> true
                    orientation !in 220..320 -> false
                    else -> sideways
                }
                if (next != sideways) sideways = next
            }
        }
    }
    LaunchedEffect(enabled, listener) {
        if (!enabled || !listener.canDetectOrientation()) {
            sideways = false
            return@LaunchedEffect
        }
        snapshotFlow { showing() }.collect { on -> if (on) listener.enable() else listener.disable() }
    }
    DisposableEffect(listener) { onDispose { listener.disable() } }
    return enabled && sideways
}

/**
 * Hides the status and navigation bars while the landscape Terminal is on screen, and brings them
 * back when it leaves (turned portrait, another style, the player collapsed, or the app closing).
 * A swipe from the edge shows them briefly. It changes nothing but the window's bars.
 */
@Composable
private fun ImmersiveWhileLandscape(active: Boolean) {
    val view = LocalView.current
    val showing = rememberStyleShowing()
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(active, showing) {
        if (!active) {
            on = false
            return@LaunchedEffect
        }
        snapshotFlow { showing() }.collect { shown -> on = shown }
    }
    DisposableEffect(view, on) {
        val window = view.context.findActivity()?.window
        val controller: WindowInsetsControllerCompat? = window?.let { WindowCompat.getInsetsController(it, view) }
        var hid = false
        if (on && controller != null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            hid = true
        }
        onDispose {
            if (hid) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
