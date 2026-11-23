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
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.imageResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.spotube.spotube.core.ui.theming.AppTheme
import org.spotube.spotube.modules.settings.AppearanceStyle
import spotube.composeapp.generated.resources.Res
import spotube.composeapp.generated.resources.player_berry
import spotube.composeapp.generated.resources.player_berry_scene
import spotube.composeapp.generated.resources.player_forest_scene
import spotube.composeapp.generated.resources.player_fox_hang
import spotube.composeapp.generated.resources.player_fox_frames
import spotube.composeapp.generated.resources.player_fox_peek
import spotube.composeapp.generated.resources.player_fox_rest
import spotube.composeapp.generated.resources.player_fox_sit
import spotube.composeapp.generated.resources.player_frog_hang
import spotube.composeapp.generated.resources.player_frog_frames
import spotube.composeapp.generated.resources.player_frog_jump
import spotube.composeapp.generated.resources.player_frog_peek
import spotube.composeapp.generated.resources.player_frog_rest
import spotube.composeapp.generated.resources.player_pond_scene
import spotube.composeapp.generated.resources.player_mascot_react
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private const val MascotFramesPerSecond = 24
private const val MascotSheetColumns = 8

internal data class IllustratedMascotFrames(
    val resource: DrawableResource,
    val idleCount: Int,
    val reactionCount: Int,
) {
    val count get() = idleCount + reactionCount
    val idleMillis get() = idleCount * 1_000 / MascotFramesPerSecond
    val reactionMillis get() = reactionCount * 1_000 / MascotFramesPerSecond
}

internal data class IllustratedPlayerArt(
    val style: AppearanceStyle,
    val scene: DrawableResource,
    val peek: DrawableResource,
    val hanging: DrawableResource,
    val rest: DrawableResource,
    val arrival: DrawableResource? = null,
    val frames: IllustratedMascotFrames? = null,
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
        frames = IllustratedMascotFrames(Res.drawable.player_fox_frames, idleCount = 60, reactionCount = 48),
        landingX = .74f, landingY = .89f, hangingHeight = 150.dp, restWidthFraction = .37f,
    )
    AppearanceStyle.BLUE_POND -> IllustratedPlayerArt(
        this, Res.drawable.player_pond_scene, Res.drawable.player_frog_peek, Res.drawable.player_frog_hang,
        Res.drawable.player_frog_rest, Res.drawable.player_frog_jump,
        frames = IllustratedMascotFrames(Res.drawable.player_frog_frames, idleCount = 24, reactionCount = 24),
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
    val expanded: () -> Boolean,
    val settledExpanded: () -> Boolean = expanded,
) {
    var sheetTopInRoot by mutableStateOf(Float.NaN)
    var sceneBounds by mutableStateOf(Rect.Zero)
    var peekBounds by mutableStateOf(Rect.Zero)
    var reactionRequests by mutableIntStateOf(0)
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
internal fun IllustratedPlayerScene(
    motion: IllustratedPlayerMotion,
    minHeight: Dp,
    continuation: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val width = maxWidth
        val height = maxOf(minHeight, (width * .46f).coerceIn(140.dp, 248.dp))
        val artwork = imageResource(motion.art.scene)
        val painter = remember(artwork) { BitmapPainter(artwork) }
        val artHeight = if (motion.art.style == AppearanceStyle.STRAWBERRIES) {
            width / painter.aspectRatio
        } else height
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(maxOf(height, artHeight))) {
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(artHeight)
                        .onGloballyPositioned { motion.sceneBounds = it.illustrationBounds() },
                    contentScale = ContentScale.FillBounds,
                )
                if (!rememberReducePlayerMotion()) {
                    val art = motion.art
                    val label = stringResource(Res.string.player_mascot_react)
                    val hitWidth = width.coerceAtMost(520.dp) * art.restWidthFraction + 12.dp
                    val hitHeight = (hitWidth * .9f).coerceAtLeast(44.dp)
                    Box(
                        Modifier.align(Alignment.TopStart)
                            .offset(
                                x = width * art.landingX - hitWidth / 2,
                                y = maxOf(height, artHeight) - artHeight * (1f - art.landingY) - hitHeight,
                            )
                            .size(hitWidth, hitHeight)
                            .semantics { contentDescription = label }
                            .clickable(
                                enabled = motion.expanded(),
                                role = Role.Button,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { motion.reactionRequests++ },
                    )
                }
            }
            if (continuation != null) {
                val page = AppTheme.colors.background
                Box(
                    Modifier.fillMaxWidth().drawWithCache {
                        val sourceHeight = (artwork.height * .38f).roundToInt().coerceAtLeast(1)
                        val sourceOffset = IntOffset(0, artwork.height - sourceHeight)
                        val sourceSize = IntSize(artwork.width, sourceHeight)
                        val stripHeight = (artHeight.toPx() * sourceHeight / artwork.height)
                            .roundToInt().coerceAtLeast(1)
                        val stripSize = IntSize(size.width.roundToInt(), stripHeight)
                        val strips = (size.height.roundToInt() + stripHeight - 1) / stripHeight
                        val shade = Brush.verticalGradient(
                            0f to Color.Transparent,
                            .30f to page.copy(alpha = .38f),
                            .90f to page.copy(alpha = .78f),
                            1f to page,
                            endY = size.height,
                        )
                        onDrawBehind {
                            // Mirror the lower texture so each join uses the same row of pixels.
                            // Keep the strip at the scale it had above the lyrics.
                            clipRect {
                                repeat(strips) { index ->
                                    val y = index * stripHeight
                                    scale(1f, if (index % 2 == 0) -1f else 1f,
                                        pivot = Offset(0f, y + stripHeight / 2f)) {
                                        drawImage(
                                            image = artwork,
                                            srcOffset = sourceOffset,
                                            srcSize = sourceSize,
                                            dstOffset = IntOffset(0, y),
                                            dstSize = stripSize,
                                        )
                                    }
                                }
                            }
                            drawRect(shade)
                        }
                    }
                ) {
                    continuation()
                }
            }
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
    val frameSequence = art.frames
    val restFrames = frameSequence?.let { frames ->
        val bitmap = imageResource(frames.resource)
        remember(bitmap, frames) {
            val rows = (frames.count + MascotSheetColumns - 1) / MascotSheetColumns
            val tile = IntSize(bitmap.width / MascotSheetColumns, bitmap.height / rows)
            List(frames.count) { index ->
                val offset = IntOffset(index % MascotSheetColumns * tile.width, index / MascotSheetColumns * tile.height)
                BitmapPainter(bitmap, offset, tile)
            }
        }
    }
    val reduceMotion = rememberReducePlayerMotion()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val inForeground = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val expanded by remember(motion) { derivedStateOf(motion.expanded) }
    // The settled detent remains expanded during a downward drag, even when the
    // sheet is no longer idle. Keep the character in its scene until the player closes.
    val keepMascotInScene by remember(motion) { derivedStateOf(motion.settledExpanded) }
    val time = remember(motion) { Animatable(0f) }
    LaunchedEffect(motion, keepMascotInScene, expanded, reduceMotion, inForeground) {
        if (!keepMascotInScene) {
            time.snapTo(0f)
        } else if (!expanded || reduceMotion || !inForeground) {
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
    val sceneActive = expanded && sceneVisible && inForeground && !reduceMotion
    val canAnimate = sceneActive && landed
    val reaction = remember(motion) { Animatable(1f) }
    val request = motion.reactionRequests
    var lastReaction by remember(motion) { mutableIntStateOf(0) }
    LaunchedEffect(motion, sceneActive, canAnimate, request) {
        if (!canAnimate) {
            if (!sceneActive) lastReaction = request
            reaction.snapTo(1f)
        } else if (request != lastReaction) {
            lastReaction = request
            reaction.snapTo(0f)
            reaction.animateTo(
                1f, tween(frameSequence?.reactionMillis ?: 1480, easing = LinearEasing)
            )
        }
    }
    val idleMotion = if (canAnimate && frameSequence != null) {
        val transition = rememberInfiniteTransition(label = "Resting mascot")
        val animation = if (isFrog) {
            infiniteRepeatable(keyframes {
                val pulseStart = 850
                val pulseEnd = pulseStart + frameSequence.idleMillis
                durationMillis = pulseEnd + 1500
                0f at pulseStart using LinearEasing
                1f at pulseEnd
                0f at (pulseEnd + 1)
            })
        } else infiniteRepeatable(tween(frameSequence.idleMillis, easing = LinearEasing))
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
        if (peekBounds.width <= 0f || viewport.height <= 0f) return@Canvas
        val gripX = peekBounds.center.x - viewport.left
        // The sheet state exposes the revealed height. The visible edge and this canvas
        // are both measured in root coordinates so the mascot follows the rendered sheet.
        val gripY = motion.sheetTopInRoot - viewport.top - 7.dp.toPx()
        val hangingHeight = art.hangingHeight.toPx()
        if (!expanded) {
            val peekBottom = if (isFox) size.height - dockHeightPx + 8.dp.toPx()
                else peekBounds.bottom - viewport.top + 3.dp.toPx()
            val peekHeight = minOf(peekBounds.height, peekBounds.width / peek.aspectRatio)
            paintPose(peek, gripX, peekBottom, peekHeight, 1f - lift)
            if (!keepMascotInScene) {
                if (lift > 0f && !reduceMotion && gripY.isFinite()) {
                    val descent = peekHeight + (hangingHeight - peekHeight) * lift
                    paintPose(hanging, gripX, gripY + descent, descent, lift)
                }
                return@Canvas
            }
        }
        val scene = motion.sceneBounds
        if (scene.height <= 0f || scene.top >= viewport.bottom || !gripY.isFinite()) return@Canvas
        val restX = scene.left - viewport.left + scene.width * art.landingX
        val groundY = scene.top - viewport.top + scene.height * art.landingY
        val restWidth = scene.width.coerceAtMost(520.dp.toPx()) * art.restWidthFraction
        val restHeight = restWidth / rest.aspectRatio
        val arrivalHeight = if (isFox) restHeight * 1.28f else restHeight
        val elapsed = if (!expanded || reduceMotion) 1f else time.value
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
            val touched = reaction.value < 1f
            val splashTime = if (touched) reaction.value else elapsed
            val diveStart = if (touched) .12f else impact
            val surfaceStart = if (touched) .42f else .61f
            val dive = ((splashTime - diveStart) / .16f).coerceIn(0f, 1f)
            val surface = ((splashTime - surfaceStart) / (1f - surfaceStart)).coerceIn(0f, 1f)
            val rise = surface * surface * (3f - 2f * surface)
            val submerged = (dive - rise).coerceIn(0f, 1f)
            if (touched) {
                val ripple = ((splashTime - .16f) / .44f).coerceIn(0f, 1f)
                if (ripple > 0f && ripple < 1f) {
                    drawLandingRipple(Offset(restX, groundY), restWidth, ripple, art.ripple, art.splash)
                }
            }
            clipRect(bottom = groundY - restHeight * .06f) {
                paintPose(rest, restX, groundY + restHeight * submerged, restHeight, 1f)
            }
        } else {
            val squash = 1f - .19f * sin(PI.toFloat() * ((elapsed - .58f) / .14f).coerceIn(0f, 1f))
            if (settle < 1f) paintPose(arrival, restX, groundY, arrivalHeight * squash, 1f - settle)
            if (restFrames != null && frameSequence != null) {
                val phase = reaction.value
                val frame = if (phase < 1f) frameSequence.idleCount + frameAt(phase, frameSequence.reactionCount)
                    else frameAt(idleMotion?.value ?: 0f, frameSequence.idleCount)
                val pose = restFrames[frame]
                val frameWidth = restWidth * if (isFrog) 1.14f else 1.10f
                val frameCenter = restX - if (isFrog) frameWidth * .014f else frameWidth * .02f
                paintPose(pose, frameCenter, groundY + 3.dp.toPx(), frameWidth / pose.aspectRatio, settle)
            }
        }
    }
}

private fun frameAt(phase: Float, count: Int): Int =
    (phase * count).toInt().coerceIn(0, count - 1)

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
