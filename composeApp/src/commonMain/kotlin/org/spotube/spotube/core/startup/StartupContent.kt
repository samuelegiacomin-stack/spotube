/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.core.startup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import com.composeunstyled.Text
import org.spotube.millennium.composables.MillenniumButton
import org.spotube.spotube.core.ui.theming.SpotubeTheme
import org.spotube.spotube.core.ui.theming.AppTheme
import org.spotube.spotube.modules.settings.UserSettings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import spotube.composeapp.generated.resources.*

/** The error screen supplies its own theme and does not require Koin. */
@Composable
internal fun StartupContent(state: StartupState, onRetry: () -> Unit, content: @Composable () -> Unit) {
    when (state) {
        StartupState.Ready -> content()
        is StartupState.Failed -> SpotubeTheme(UserSettings()) {
            Column(Modifier.fillMaxSize().background(AppTheme.colors.background).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                Text(stringResource(Res.string.startup_failed), color = AppTheme.colors.onSurface)
                if (state.canRetry) MillenniumButton(onClick = onRetry) {
                    Text(stringResource(Res.string.common_retry))
                } else Text(stringResource(Res.string.startup_restart), color = AppTheme.colors.onSurface)
            }
        }
        StartupState.Idle, StartupState.Loading -> Unit
    }
}
