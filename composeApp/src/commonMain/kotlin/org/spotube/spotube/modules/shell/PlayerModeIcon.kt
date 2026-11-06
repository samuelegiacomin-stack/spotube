/*
 * Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import org.jetbrains.compose.resources.stringResource
import org.spotube.millennium.composables.MillenniumIcon
import org.spotube.spotube.core.ui.theming.AppTheme
import spotube.composeapp.generated.resources.Res
import spotube.composeapp.generated.resources.player_repeat_one_badge

/** Draws selection inside the existing button bounds without changing its measured size. */
@Composable
internal fun Modifier.playerModeSelection(active: Boolean): Modifier {
    val accent = AppTheme.colors.primary
    return semantics { selected = active }.drawBehind {
        if (active) drawCircle(accent.copy(alpha = 0.16f), radius = size.minDimension / 2)
    }
}

@Composable
internal fun PlayerModeIcon(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    repeatOne: Boolean = false,
) {
    val tint = if (active) AppTheme.colors.primary else AppTheme.colors.onSurfaceVariant
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        MillenniumIcon(icon, contentDescription = contentDescription, tint = tint,
            modifier = Modifier.size(24.dp))
        if (repeatOne) {
            Text(stringResource(Res.string.player_repeat_one_badge),
                color = tint, fontWeight = FontWeight.Bold,
                style = AppTheme.typography.labelSmall.copy(fontSize = 10.sp),
                modifier = Modifier.background(AppTheme.colors.surface, CircleShape))
        }
        if (active) {
            Box(Modifier.align(Alignment.BottomCenter).offset(y = 6.dp)
                .size(4.dp).background(tint, CircleShape))
        }
    }
}
