// the people on the developer page and the top half of each page: banner, avatar, name, role and a line about them

package com.vantra.music.ui.screens.settings.devspace

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.vantra.music.R
import com.vantra.music.ui.utils.SoftBlurTransformation

internal data class DevPerson(
    val name: String,
    val role: String,
    val bio: String,
    @DrawableRes val avatar: Int,
)

/**
 * The people shown on the developer page, in picker order.
 * To use a real portrait, drop a square image into res/drawable-nodpi with the same file name
 * as the placeholder (dev_avatar_kynx / dev_avatar_kenn) or point `avatar` at a new resource.
 */
internal val DevPeople = listOf(
    DevPerson(
        name = "Kyn-X",
        role = "UI/UX & Product Design",
        bio = "Crafting thoughtful interfaces and immersive visual experiences.",
        avatar = R.drawable.dev_avatar_kynx,
    ),
    DevPerson(
        name = "Kenn",
        role = "Application Development",
        bio = "Building refined software experiences through engineering and creativity.",
        avatar = R.drawable.dev_avatar_kenn,
    ),
)

/** someone's picture, round, on the mock's light grey */
@Composable
internal fun DevAvatar(person: DevPerson, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(person.avatar),
        contentDescription = person.name,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(CircleShape).background(Color(0xFFD9D9D9)),
    )
}

private val AvatarSize = 84.dp
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** one person's whole page, its sections rise in one after another the first time it shows */
@Composable
internal fun DevProfilePage(
    person: DevPerson,
    scrollState: ScrollState,
    bannerHeight: Dp,
    modifier: Modifier = Modifier,
    below: @Composable ColumnScope.() -> Unit,
) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(700, easing = Emphasized)) }
    // each section starts a little after the one above it
    fun Modifier.rise(step: Int): Modifier = graphicsLayer {
        // text and lines only, so fading each part on its own skips an offscreen buffer per section
        compositingStrategy = CompositingStrategy.ModulateAlpha
        val p = ((reveal.value - step * 0.08f) / 0.6f).coerceIn(0f, 1f)
        alpha = p
        translationY = (1f - p) * 26.dp.toPx()
    }

    Column(modifier.fillMaxSize().verticalScroll(scrollState)) {
        ProfileHeader(person, scrollState, bannerHeight)

        Column(Modifier.padding(horizontal = DevSideInset)) {
            Text(
                person.name,
                style = devText(40f, tracking = -1.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp).rise(0),
            )
            Text(
                person.role,
                style = devText(14f, FontWeight.Medium, DevMuted, -0.2f),
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp).rise(1),
            )
            Column(Modifier.rise(1)) {
                Rule(Modifier.padding(top = 18.dp))
                Text(
                    person.bio,
                    style = devText(15f, FontWeight.SemiBold, DevLinkText, -0.4f),
                    modifier = Modifier.padding(vertical = 22.dp),
                )
                Rule()
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.rise(2).padding(top = 22.dp),
                content = below,
            )
        }
    }
}

/** the banner is the person's own picture, blurred and dimmed, so every page gets its own colour */
@Composable
private fun ProfileHeader(person: DevPerson, scrollState: ScrollState, bannerHeight: Dp) {
    val context = LocalContext.current
    // blurred once, small and off the main thread, so it costs the same on every phone
    val banner = remember(person.avatar) {
        ImageRequest.Builder(context)
            .data(person.avatar)
            .size(192)
            .crossfade(true)
            .transformations(SoftBlurTransformation())
            .build()
    }
    Box(Modifier.fillMaxWidth().height(bannerHeight + AvatarSize / 2)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(bannerHeight)
                .graphicsLayer {
                    translationY = scrollState.value * 0.45f
                    alpha = 1f - (scrollState.value / bannerHeight.toPx()).coerceIn(0f, 1f) * 0.6f
                }
                .background(Color(0xFF4A4A4A)),
        ) {
            AsyncImage(
                model = banner,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        // built once instead of on every scroll frame
                        val melt = Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black)
                        onDrawWithContent {
                            drawContent()
                            drawRect(Color.Black.copy(alpha = 0.32f))
                            // melts into the black page so the avatar's lower half sits on one colour
                            drawRect(melt)
                        }
                    },
            )
        }
        DevAvatar(
            person,
            Modifier
                .align(Alignment.BottomStart)
                .offset(x = DevSideInset - 2.dp)
                .size(AvatarSize)
                .graphicsLayer {
                    val p = (scrollState.value / bannerHeight.toPx()).coerceIn(0f, 1f)
                    val s = 1f - 0.18f * p
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(0f, 1f)
                },
        )
    }
}

@Composable
private fun Rule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(DevRule))
}
