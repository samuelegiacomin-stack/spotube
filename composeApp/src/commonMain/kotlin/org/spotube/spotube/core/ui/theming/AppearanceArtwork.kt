/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.core.ui.theming

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.spotube.spotube.modules.settings.AppearanceStyle
import spotube.composeapp.generated.resources.Res
import spotube.composeapp.generated.resources.theme_blue_pond
import spotube.composeapp.generated.resources.theme_strawberries
import spotube.composeapp.generated.resources.theme_tormentil

private val AppearanceStyle.artwork
    get() = when (this) {
        AppearanceStyle.TORMENTIL -> Res.drawable.theme_tormentil
        AppearanceStyle.BLUE_POND -> Res.drawable.theme_blue_pond
        AppearanceStyle.STRAWBERRIES -> Res.drawable.theme_strawberries
        else -> null
    }

internal val AppearanceStyle.isIllustrated: Boolean get() = artwork != null

@Composable
internal fun Modifier.appearanceBackground(): Modifier {
    val colors = AppTheme.colors
    return if (AppTheme.appearance.isIllustrated) {
        background(Brush.verticalGradient(listOf(
            lerp(colors.background, colors.surfaceContainerHigh, 0.42f),
            colors.background,
        )))
    } else background(colors.background)
}

@Composable
internal fun Modifier.appearanceTransportSurface(): Modifier {
    if (!AppTheme.appearance.isIllustrated) return this
    val colors = AppTheme.colors
    val shape = AppTheme.shapes.large
    return background(colors.surface.copy(alpha = 0.9f), shape)
        .border(1.dp, colors.outline, shape)
        .padding(horizontal = 2.dp, vertical = 4.dp)
}

@Composable
internal fun AppearanceIllustration(
    style: AppearanceStyle,
    modifier: Modifier = Modifier,
) {
    val art = style.artwork ?: return
    Image(
        painter = painterResource(art),
        contentDescription = null,
        // The top of each illustration is transparent. Trim that space consistently
        // at every width while keeping the subject and lower edge in view.
        modifier = modifier.fillMaxWidth().aspectRatio(1.9f),
        alignment = Alignment.BottomCenter,
        contentScale = ContentScale.FillWidth,
    )
}

@Composable
internal fun AppearanceMotif(
    style: AppearanceStyle,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    if (!style.isIllustrated) return
    Canvas(modifier) {
        val unit = size.minDimension
        when (style) {
            AppearanceStyle.TORMENTIL -> {
                repeat(4) { petal ->
                    rotate(petal * 90f) {
                        drawCircle(tint, unit * 0.18f, center.copy(y = center.y - unit * 0.24f))
                    }
                }
                drawCircle(tint, unit * 0.09f, center)
            }
            AppearanceStyle.BLUE_POND -> {
                drawOval(
                    color = tint,
                    topLeft = Offset(center.x - unit * 0.45f, center.y - unit * 0.27f),
                    size = Size(unit * 0.90f, unit * 0.54f),
                    style = Stroke(unit * 0.07f),
                )
                drawOval(
                    color = tint,
                    topLeft = Offset(center.x - unit * 0.24f, center.y - unit * 0.13f),
                    size = Size(unit * 0.48f, unit * 0.26f),
                    style = Stroke(unit * 0.07f),
                )
            }
            AppearanceStyle.STRAWBERRIES -> {
                repeat(8) { petal ->
                    rotate(petal * 45f) {
                        drawOval(
                            color = tint,
                            topLeft = Offset(center.x - unit * 0.10f, center.y - unit * 0.43f),
                            size = Size(unit * 0.20f, unit * 0.24f),
                        )
                    }
                }
                drawCircle(Color(0xFFE7A332), unit * 0.13f, center)
            }
            else -> Unit
        }
    }
}
