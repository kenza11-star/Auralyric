// StreakCard.kt
//
// The profile page's hidden streak card: shows once the user has listened 3 days in a row.
// A flame that flickers and sways, coloured by milestone (3 / 30 / 100 / 200 / 300 / 400 / 500+),
// the day count on an odometer, and a thin bar to the next colour.
//
// A missed yesterday shows the streak grey with a "Pulihkan" button while a recovery chance is
// left this week. The chances line at the bottom always shows how many are left.

package com.vantra.music.ui.screens.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.emptyPreferences
import com.vantra.music.ui.component.OdometerNumber
import com.vantra.music.ui.theme.InterFontFamily
import com.vantra.music.utils.FlameTier
import com.vantra.music.utils.StreakInfo
import com.vantra.music.utils.StreakManager
import com.vantra.music.utils.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val StreakCardBg = Color(0xFF0F0F0F)
private val StreakGrey = Color(0xFF8A8A8A)

/** The streak as the card shows it, plus the call that uses a recovery chance. */
internal class StreakView(val info: StreakInfo, val onRecover: () -> Unit)

/**
 * Reads the recovery state from DataStore and turns the listening days into what the card shows.
 * Used by the profile page in place of a plain StreakManager.compute().
 */
@Composable
internal fun rememberStreakView(listeningDays: List<Long>): StreakView {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by context.dataStore.data.collectAsState(initial = emptyPreferences())
    val info = remember(listeningDays, prefs) {
        StreakManager.evaluate(listeningDays, StreakManager.readState(prefs))
    }
    return remember(info, listeningDays) {
        StreakView(info) {
            scope.launch(Dispatchers.IO) {
                StreakManager.recover(context.dataStore, listeningDays)
            }
        }
    }
}

@Composable
internal fun StreakCard(info: StreakInfo, onRecover: () -> Unit = {}) {
    val gray = info.gray
    val alive = info.current > 0 && !gray
    val tier = info.tier

    // pops in the first time the card is on screen
    val pop = remember { Animatable(0.88f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) }

    val barColor = if (alive) tier.outer.takeUnless { tier == FlameTier.Rainbow } ?: Color(0xFFFF6BD6) else StreakGrey

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .clip(RoundedCornerShape(22.dp))
            .background(StreakCardBg)
            .padding(PaddingValues(horizontal = 20.dp, vertical = 20.dp)),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val k = (maxWidth.value / 320f).coerceIn(0.72f, 1.2f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Flame(tier = tier, alive = alive, modifier = Modifier.size((64 * k).dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    OdometerNumber(
                        value = info.current.toString(),
                        fontSize = figureSp(44f * k),
                        fontWeight = FontWeight.Bold,
                        fontFamily = InterFontFamily,
                        color = if (gray) StreakGrey else Color.White,
                        letterSpacing = (-2).sp,
                    )
                    Text(
                        "day streak",
                        style = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = StreakGrey),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "Longest\n${info.longest} days",
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 18.sp, color = StreakGrey, textAlign = TextAlign.End),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF474747)))
        Spacer(Modifier.height(12.dp))
        val status = when {
            gray -> "Kamu melewatkan 1 hari. Pulihkan biar streak tetap aman."
            !alive -> "Streak putus. Dengerin hari ini buat nyalain lagi."
            !info.listenedToday -> "Dengerin hari ini biar apinya nggak padam."
            info.next != null -> "${info.daysToNext} hari lagi menuju api ${info.next!!.label}."
            else -> "Api pelangi! Kamu legenda."
        }
        Text(
            status,
            style = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = StreakGrey),
        )
        if (gray && info.chancesLeft > 0) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF2A2A2A))
                    .clickable(onClick = onRecover)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    "Pulihkan (sisa ${info.chancesLeft})",
                    style = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White),
                )
            }
        }
        if (alive && info.next != null) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF2A2A2A)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(info.progress.coerceAtLeast(0.03f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(barColor),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Kesempatan pemulihan minggu ini: ${info.chancesLeft} dari ${StreakManager.RECOVERIES_PER_WEEK}",
            style = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, color = StreakGrey),
        )
    }
}

/** A flame that flickers (height), sways (tilt from its base) and glows; the rainbow tier cycles hue. */
@Composable
private fun Flame(tier: FlameTier, alive: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "flame")
    val flick by t.animateFloat(
        0.93f, 1.07f,
        infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "flick",
    )
    val sway by t.animateFloat(
        -4f, 4f,
        infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sway",
    )
    val glow by t.animateFloat(
        0.20f, 0.50f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "glow",
    )
    val hue by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart), label = "hue",
    )

    val outer: Color
    val inner: Color
    when {
        !alive -> { outer = Color(0xFF4A4A4A); inner = Color(0xFF7A7A7A) }
        tier == FlameTier.Rainbow -> { outer = Color.hsv(hue, 0.85f, 1f); inner = Color.hsv((hue + 40f) % 360f, 0.25f, 1f) }
        else -> { outer = tier.outer; inner = tier.inner }
    }
    val glowAlpha = if (alive) glow else 0.08f

    Box(modifier) {
        Canvas(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    if (alive) { scaleY = flick; rotationZ = sway }
                },
        ) {
            val w = size.width
            val h = size.height
            // soft glow behind
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(outer.copy(alpha = glowAlpha), Color.Transparent),
                    center = Offset(w / 2f, h * 0.62f),
                    radius = w * 0.75f,
                ),
                radius = w * 0.75f,
                center = Offset(w / 2f, h * 0.62f),
            )
            val body = flamePath(w, h)
            drawPath(
                body,
                brush = Brush.verticalGradient(
                    listOf(inner.copy(alpha = 0.85f), outer, outer),
                    startY = 0f,
                    endY = h,
                ),
            )
            // the bright core
            scale(0.52f, 0.55f, pivot = Offset(w / 2f, h)) {
                drawPath(
                    body,
                    brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.95f), inner), startY = 0f, endY = h),
                )
            }
        }
    }
}

private fun flamePath(w: Float, h: Float): Path = Path().apply {
    fun x(v: Float) = v * w
    fun y(v: Float) = v * h
    moveTo(x(0.50f), y(0.02f))
    cubicTo(x(0.55f), y(0.25f), x(0.92f), y(0.40f), x(0.88f), y(0.68f))
    cubicTo(x(0.86f), y(0.90f), x(0.68f), y(1.00f), x(0.50f), y(1.00f))
    cubicTo(x(0.30f), y(1.00f), x(0.12f), y(0.90f), x(0.12f), y(0.68f))
    cubicTo(x(0.12f), y(0.52f), x(0.22f), y(0.42f), x(0.30f), y(0.34f))
    cubicTo(x(0.32f), y(0.45f), x(0.38f), y(0.50f), x(0.42f), y(0.50f))
    cubicTo(x(0.38f), y(0.30f), x(0.46f), y(0.14f), x(0.50f), y(0.02f))
    close()
}
