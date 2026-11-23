/* Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.ui.component

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.spotube.millennium.composables.MillenniumIcon
import org.spotube.spotube.PlatformType
import org.spotube.spotube.core.ui.theming.AppTheme
import org.spotube.spotube.getPlatform
import org.spotube.spotube.resources.iconsax.Iconsax
import org.spotube.spotube.resources.iconsax.IconsaxAddSquare

@Composable
internal fun QueueSwipeRow(
    onEnqueue: (() -> Unit)?,
    enabled: Boolean = getPlatform().type == PlatformType.IOS,
    content: @Composable () -> Unit,
) {
    if (onEnqueue == null || !enabled) {
        content()
        return
    }
    val distance = remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.fillMaxWidth().clipToBounds()
            .mediaSwipe(distance, onRight = onEnqueue),
    ) {
        Box(Modifier.matchParentSize().graphicsLayer {
            translationX = distance.floatValue - 72.dp.toPx()
            alpha = (distance.floatValue / 55.dp.toPx()).coerceIn(0f, 1f)
        }) {
            Box(
                Modifier.width(72.dp).fillMaxHeight().background(AppTheme.colors.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                MillenniumIcon(Iconsax.IconsaxAddSquare, contentDescription = null,
                    tint = AppTheme.colors.onPrimaryContainer)
            }
        }
        Box(Modifier.graphicsLayer { translationX = distance.floatValue }) { content() }
    }
}

@Composable
internal fun Modifier.coverTrackSwipe(onLeft: () -> Unit, onRight: () -> Unit): Modifier {
    val distance = remember { mutableFloatStateOf(0f) }
    return mediaSwipe(distance, onLeft, onRight).graphicsLayer {
        translationX = distance.floatValue * .26f
    }
}

@Composable
private fun Modifier.mediaSwipe(
    distance: MutableFloatState,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
): Modifier {
    val latestLeft = rememberUpdatedState(onLeft)
    val latestRight = rememberUpdatedState(onRight)
    val leftEnabled = onLeft != null
    val rightEnabled = onRight != null
    return pointerInput(leftEnabled, rightEnabled) {
        val threshold = 56.dp.toPx()
        val limit = 72.dp.toPx()
        coroutineScope {
            var settle: Job? = null
            var drag = 0f
            fun reset() {
                settle = launch {
                    animate(distance.floatValue, 0f) { value, _ -> distance.floatValue = value }
                }
            }
            detectHorizontalDragGestures(
                onDragStart = {
                    settle?.cancel()
                    drag = 0f
                    distance.floatValue = 0f
                },
                onHorizontalDrag = { _, dx ->
                    drag += dx
                    distance.floatValue = drag.coerceIn(
                        if (leftEnabled) -limit else 0f,
                        if (rightEnabled) limit else 0f,
                    )
                },
                onDragEnd = {
                    when {
                        drag >= threshold -> latestRight.value?.invoke()
                        drag <= -threshold -> latestLeft.value?.invoke()
                    }
                    reset()
                },
                onDragCancel = { reset() },
            )
        }
    }
}
