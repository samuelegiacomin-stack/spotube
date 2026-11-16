/*
 * Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */


package org.spotube.spotube.modules.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.spotube.spotube.modules.settings.AppearanceStyle
import spotube.composeapp.generated.resources.Res
import spotube.composeapp.generated.resources.player_berry
import spotube.composeapp.generated.resources.player_berry_scene
import spotube.composeapp.generated.resources.player_forest_scene
import spotube.composeapp.generated.resources.player_fox_hang
import spotube.composeapp.generated.resources.player_fox_peek
import spotube.composeapp.generated.resources.player_fox_rest
import spotube.composeapp.generated.resources.player_fox_sit
import spotube.composeapp.generated.resources.player_frog_hang
import spotube.composeapp.generated.resources.player_frog_jump
import spotube.composeapp.generated.resources.player_frog_peek
import spotube.composeapp.generated.resources.player_frog_rest
import spotube.composeapp.generated.resources.player_pond_scene
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

internal data class IllustratedPlayerArt(
    val style: AppearanceStyle,
    val scene: DrawableResource,
    val peek: DrawableResource,
    val hanging: DrawableResource,
    val rest: DrawableResource,
    val arrival: DrawableResource? = null,
    val landingX: Float = .72f,
    val landingY: Float,
    val hangingHeight: Dp,
    val restWidthFraction: Float,
    val jumpHeight: Dp = 0.dp,
    val ripple: Color = Color.Unspecified,
    val splash: Color = Color.Unspecified,
)

internal fun AppearanceStyle.illustratedPlayerArt(): IllustratedPlayerArt? = when (this) {
    AppearanceStyle.TORMENTIL -> IllustratedPlayerArt(
        this, Res.drawable.player_forest_scene, Res.drawable.player_fox_peek, Res.drawable.player_fox_hang,
        Res.drawable.player_fox_rest, Res.drawable.player_fox_sit,
        landingX = .74f, landingY = .89f, hangingHeight = 150.dp, restWidthFraction = .37f,
    )
    AppearanceStyle.BLUE_POND -> IllustratedPlayerArt(
        this, Res.drawable.player_pond_scene, Res.drawable.player_frog_peek, Res.drawable.player_frog_hang,
        Res.drawable.player_frog_rest, Res.drawable.player_frog_jump,
        landingX = .64f, landingY = .75f, hangingHeight = 98.dp, restWidthFraction = .23f,
        jumpHeight = 32.dp, ripple = Color(0xFF85C8D6),
    )
    AppearanceStyle.STRAWBERRIES -> IllustratedPlayerArt(
        this, Res.drawable.player_berry_scene, Res.drawable.player_berry, Res.drawable.player_berry,
        Res.drawable.player_berry,
        landingX = .67f, landingY = .84f, hangingHeight = 42.dp, restWidthFraction = .16f,
        ripple = Color(0xFFE1A79F), splash = Color(0xFFFFF3DC),
    )
    else -> null
}

internal class IllustratedPlayerMotion(
    val art: IllustratedPlayerArt,
    val progress: () -> Float,
    val sheetOffset: () -> Float,
    val expanded: () -> Boolean,
) {
    var sceneBounds by mutableStateOf(Rect.Zero)
    var peekBounds by mutableStateOf(Rect.Zero)
}

internal val LocalIllustratedPlayer = staticCompositionLocalOf<IllustratedPlayerMotion?> { null }

@Composable
internal expect fun rememberReducePlayerMotion(): Boolean

@Composable
internal fun IllustratedPlayerPeek(motion: IllustratedPlayerMotion) {
    // One anchor for both poses. The foreground layer draws over the dock and the sheet lip.
    Box(Modifier.size(54.dp, 47.dp).onGloballyPositioned { motion.peekBounds = it.illustrationBounds() })
}

@Composable
internal fun IllustratedPlayerScene(motion: IllustratedPlayerMotion, minHeight: Dp) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val height = maxOf(minHeight, (maxWidth * .46f).coerceIn(140.dp, 248.dp))
        val painter = painterResource(motion.art.scene)
        val artHeight = if (motion.art.style == AppearanceStyle.STRAWBERRIES) {
            maxWidth / painter.aspectRatio
        } else height
        Box(Modifier.fillMaxWidth().height(maxOf(height, artHeight))) {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(artHeight)
                    .onGloballyPositioned { motion.sceneBounds = it.illustrationBounds() },
                contentScale = ContentScale.FillBounds,
            )
        }
    }
}

@Composable
internal fun IllustratedPlayerOverlay(
    motion: IllustratedPlayerMotion,
    dockHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    val art = motion.art
    val isFox = art.style == AppearanceStyle.TORMENTIL
    val isFrog = art.style == AppearanceStyle.BLUE_POND
    val isBerry = art.style == AppearanceStyle.STRAWBERRIES
    val peek = painterResource(art.peek)
    val hanging = painterResource(art.hanging)
    val rest = painterResource(art.rest)
    val arrival = art.arrival?.let { painterResource(it) } ?: rest
    val reduceMotion = rememberReducePlayerMotion()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val inForeground = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val expanded by remember(motion) { derivedStateOf(motion.expanded) }
    val time = remember(motion) { Animatable(0f) }
    LaunchedEffect(motion, expanded, reduceMotion, inForeground) {
        if (!expanded) {
            time.snapTo(0f)
        } else if (reduceMotion || !inForeground) {
            time.snapTo(1f)
        } else if (time.value < 1f) {
            time.animateTo(1f, tween(durationMillis = if (isBerry) 1800 else 1040, easing = LinearEasing))
        }
    }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val sceneVisible by remember(motion) {
        derivedStateOf {
            val bounds = motion.sceneBounds
            bounds.height > 0 && bounds.bottom > viewport.top && bounds.top < viewport.bottom
        }
    }
    val landed by remember(time) { derivedStateOf { time.value == 1f } }
    val idleMotion = if (expanded && sceneVisible && inForeground && !reduceMotion && landed && (isFrog || isFox)) {
        val transition = rememberInfiniteTransition(label = "Resting mascot")
        val animation = if (isFrog) {
            infiniteRepeatable(keyframes {
                durationMillis = 4200
                0f at 390
                1f at 730
                .86f at 870
                0f at 1200
                0f at 2230
                .76f at 2450
                0f at 2730
                0f at 4200
            })
        } else infiniteRepeatable(tween<Float>(1550), RepeatMode.Reverse)
        transition.animateFloat(
            initialValue = 0f,
            targetValue = if (isFrog) 0f else 1f,
            animationSpec = animation,
            label = "Idle pose",
        )
    } else null

    Canvas(modifier = modifier.onGloballyPositioned { viewport = it.illustrationBounds() }) {
        val pull = motion.progress()
        val lift = (pull / .045f).coerceIn(0f, 1f)
        val peekBounds = motion.peekBounds
        if (peekBounds.width <= 0f) return@Canvas
        val gripX = peekBounds.center.x - viewport.left
        val gripOffset = (-7).dp.toPx()
        val sheetY = motion.sheetOffset().takeIf { it.isFinite() } ?: 0f
        val gripY = (sheetY + gripOffset).coerceAtLeast(0f)
        val hangingHeight = art.hangingHeight.toPx()
        if (!expanded) {
            val peekBottom = if (isFox) size.height - dockHeightPx + 8.dp.toPx()
                else peekBounds.bottom - viewport.top + 3.dp.toPx()
            val peekHeight = minOf(peekBounds.height, peekBounds.width / peek.aspectRatio)
            paintPose(peek, gripX, peekBottom, peekHeight, 1f - lift)
            if (lift > 0f && !reduceMotion) {
                val descent = peekHeight + (hangingHeight - peekHeight) * lift
                paintPose(hanging, gripX, gripY + descent, descent, lift)
            }
            return@Canvas
        }
        val scene = motion.sceneBounds
        if (scene.height <= 0f) return@Canvas
        val restX = scene.left - viewport.left + scene.width * art.landingX
        val groundY = scene.top - viewport.top + scene.height * art.landingY
        val restWidth = scene.width.coerceAtMost(520.dp.toPx()) * art.restWidthFraction
        val restHeight = restWidth / rest.aspectRatio
        val arrivalHeight = if (isFox) restHeight * 1.28f else restHeight
        val elapsed = if (reduceMotion) 1f else time.value
        val impact = if (isBerry) .40f else .58f
        val drop = (elapsed / impact).coerceIn(0f, 1f)
        val settle = ((elapsed - .72f) / .28f).coerceIn(0f, 1f)
        if (art.ripple != Color.Unspecified) {
            val ripple = ((elapsed - impact + .04f) / .33f).coerceIn(0f, 1f)
            if (ripple > 0f && ripple < 1f) {
                drawLandingRipple(Offset(restX, groundY), restWidth, ripple, art.ripple, art.splash)
            }
        }
        if (drop < 1f) {
            val x = gripX + (restX - gripX) * drop
            val fromY = gripY + hangingHeight
            val y = fromY + (groundY - fromY) * drop * drop -
                art.jumpHeight.toPx() * sin(PI.toFloat() * drop)
            val changePose = if (isFox) ((drop - .72f) / .28f).coerceIn(0f, 1f)
                else (drop * 2f).coerceIn(0f, 1f)
            val poseHeight = hangingHeight + (arrivalHeight - hangingHeight) * drop
            paintPose(hanging, x, y, poseHeight, 1f - changePose)
            paintPose(arrival, x, y, poseHeight, changePose)
        } else if (isBerry) {
            val dive = ((elapsed - impact) / .16f).coerceIn(0f, 1f)
            val surface = ((elapsed - .61f) / .39f).coerceIn(0f, 1f)
            val rise = surface * surface * (3f - 2f * surface)
            val submerged = (dive - rise).coerceIn(0f, 1f)
            clipRect(bottom = groundY - restHeight * .06f) {
                paintPose(rest, restX, groundY + restHeight * submerged, restHeight, 1f)
            }
        } else {
            val squash = 1f - .19f * sin(PI.toFloat() * ((elapsed - .58f) / .14f).coerceIn(0f, 1f))
            if (settle < 1f) paintPose(arrival, restX, groundY, arrivalHeight * squash, 1f - settle)
            val idle = idleMotion?.value ?: 0f
            if (isFrog && idle > 0f) {
                paintCroakingFrog(rest, restX, groundY, restWidth, restHeight, idle)
            } else {
                val breathScale = if (isFox) 1f + idle * .045f else 1f
                paintPose(rest, restX, groundY, restHeight * breathScale * squash, settle)
            }
        }
    }
}

private fun LayoutCoordinates.illustrationBounds() =
    Rect(positionInRoot(), Size(size.width.toFloat(), size.height.toFloat()))

private val Painter.aspectRatio: Float
    get() = intrinsicSize.width / intrinsicSize.height

private fun DrawScope.paintPose(painter: Painter, x: Float, ground: Float, height: Float, alpha: Float) {
    if (alpha <= 0f || height <= 0f) return
    val width = height * painter.aspectRatio
    translate(left = x - width / 2f, top = ground - height) {
        with(painter) { draw(Size(width, height), alpha = alpha) }
    }
}

private fun DrawScope.paintCroakingFrog(
    painter: Painter, x: Float, ground: Float, width: Float, height: Float, strength: Float,
) {
    // Warp the cream throat in the resting pose. The eye, outline of the back and feet
    // retain their original position. The right edge of each band stays anchored to the chest.
    val throatTop = height * .30f
    val throatBottom = height * .77f
    val throatRight = width * .47f
    val clipLeft = -width * .15f
    val bands = 14
    val bandHeight = (throatBottom - throatTop) / bands
    val poseSize = Size(width, height)
    translate(x - width / 2f, ground - height) {
        clipRect(clipLeft, throatTop, throatRight, throatBottom, ClipOp.Difference) {
            with(painter) { draw(poseSize) }
        }
        for (band in 0 until bands) {
            val top = throatTop + band * bandHeight
            val profile = sin(PI.toFloat() * band / (bands - 1))
            val stretch = 1f + .44f * strength * profile * profile
            clipRect(clipLeft, top, throatRight, top + bandHeight) {
                scale(stretch, 1f, pivot = Offset(throatRight, top)) {
                    with(painter) { draw(poseSize) }
                }
            }
        }
    }
}

private fun DrawScope.drawLandingRipple(center: Offset, width: Float, age: Float, tint: Color, droplets: Color) {
    val radius = width * (.28f + age * .55f)
    val opacity = (1f - age) * .85f
    drawOval(
        color = tint.copy(alpha = opacity),
        topLeft = center - Offset(radius, radius * .22f),
        size = Size(radius * 2f, radius * .44f),
        style = Stroke(max(1f, 2.dp.toPx() * (1f - age))),
    )
    if (droplets != Color.Unspecified) {
        val splash = Offset(radius * .8f, -sin(age * PI.toFloat()) * width * .28f)
        drawCircle(droplets.copy(alpha = opacity), 3.dp.toPx(), center + splash)
        drawCircle(droplets.copy(alpha = opacity), 2.dp.toPx(), center + Offset(-splash.x, splash.y))
    }
}
