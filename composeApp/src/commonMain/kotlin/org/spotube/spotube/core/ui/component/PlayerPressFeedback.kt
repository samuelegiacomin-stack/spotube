/* Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import org.spotube.spotube.core.ui.theming.AppTheme

/** Presentation-only feedback. Observes, but never consumes, a button's gestures. */
fun Modifier.playerPressFeedback(enabled: Boolean = true): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val amount by animateFloatAsState(if (pressed && enabled) 1f else 0f,
        animationSpec = if (pressed && enabled) snap() else tween(140))
    val accent = AppTheme.colors.primary
    this.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        try {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
                pressed = false
            }
        } finally { pressed = false }
    }.graphicsLayer {
        scaleX = 1f - 0.035f * amount
        scaleY = 1f - 0.035f * amount
    }.drawWithContent {
        drawContent()
        if (amount > 0f) drawCircle(accent.copy(alpha = 0.18f * amount), radius = size.minDimension / 2)
    }
}
