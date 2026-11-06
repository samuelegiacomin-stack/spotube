/*
 * Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.composeunstyled.SheetDetent
import com.composeunstyled.currentWindowWidthBreakpoint
import com.composeunstyled.rememberModalBottomSheetState
import org.spotube.millennium.adaptive.WindowBreakpoints
import org.spotube.millennium.composables.MillenniumModalBottomSheet
import org.spotube.millennium.theme.MillenniumTheme
import org.spotube.millennium.theme.millenniumPanelBrush
import org.spotube.millennium.theme.millenniumSurface
import org.spotube.spotube.core.ui.theming.AppTheme
import org.spotube.spotube.core.ui.component.TrackOptionsAction
import org.spotube.spotube.modules.downloads.DownloadStatus
import org.spotube.spotube.modules.downloads.DownloadProgressIcon
import org.spotube.spotube.resources.iconsax.IconsaxDirectboxReceive
import org.spotube.spotube.resources.iconsax.IconsaxCloseSquare
import org.spotube.spotube.resources.iconsax.IconsaxCheckCircle
import org.spotube.spotube.resources.iconsax.IconsaxRefreshRight
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import org.spotube.millennium.composables.MillenniumIcon
import org.spotube.millennium.composables.MillenniumIconButton
import org.spotube.millennium.composables.MillenniumIconButtonStyle
import org.spotube.spotube.core.ui.component.AdaptiveDropdownBottomSheet
import org.spotube.spotube.core.ui.component.AdaptiveMenuItem
import org.spotube.spotube.core.ui.component.HeaderDisplayMode
import org.spotube.spotube.core.ui.component.TrackOptionsSheetHeader
import org.spotube.spotube.core.ui.component.buildTrackMenuItems
import org.spotube.spotube.core.ui.component.playerPressFeedback
import org.spotube.spotube.resources.iconsax.Iconsax
import org.spotube.spotube.resources.iconsax.Iconsax3DotsMore
import org.spotube.spotube.resources.iconsax.IconsaxInformation
import org.spotube.spotube.resources.iconsax.InconsaxClock
import org.spotube.spotube.resources.iconsax.SwapHorizontal2
import spotube.composeapp.generated.resources.Res
import spotube.composeapp.generated.resources.player_alternative_source
import spotube.composeapp.generated.resources.player_more_options_content_description
import spotube.composeapp.generated.resources.player_sleep_timer
import spotube.composeapp.generated.resources.player_track_details
import spotube.composeapp.generated.resources.player_download
import spotube.composeapp.generated.resources.player_download_status_downloaded
import spotube.composeapp.generated.resources.player_download_status_downloading
import spotube.composeapp.generated.resources.downloads_status_cancelled
import spotube.composeapp.generated.resources.downloads_status_failed
import spotube.composeapp.generated.resources.downloads_status_queued
import spotube.composeapp.generated.resources.common_cancel

/** Shares the list menu's action definitions, then appends player-only tools. */
@Composable
internal fun AppPlayerOptions(
    state: PlayerOptionsUiState,
    viewModel: PlayerOptionsViewModel,
    onAlternativeSource: () -> Unit,
    onGoToAlbum: () -> Unit = {},
    onSleepTimer: () -> Unit = {},
    onOpen: () -> Unit = {},
) {
    val track = state.currentTrack
    val status = state.currentDownload?.status
    val downloadLabel = stringResource(when (status) {
        DownloadStatus.Completed -> Res.string.player_download_status_downloaded
        DownloadStatus.Downloading -> Res.string.player_download_status_downloading
        is DownloadStatus.Failed -> Res.string.downloads_status_failed
        DownloadStatus.Cancelled -> Res.string.downloads_status_cancelled
        DownloadStatus.Queued -> Res.string.downloads_status_queued
        null -> Res.string.player_download
    })
    val downloadItem = AdaptiveMenuItem(
        label = downloadLabel,
        onClick = { track?.let { viewModel.handleTrackAction(it, TrackOptionsAction.Download) } },
        leadingContent = {
            Box {
                if (status == DownloadStatus.Downloading || status == DownloadStatus.Queued) {
                    DownloadProgressIcon(track = track, icon = Iconsax.IconsaxDirectboxReceive,
                        contentDescription = downloadLabel, modifier = Modifier.size(28.dp),
                        tint = AppTheme.colors.onSurface)
                    MillenniumIconButton(
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 16.dp, y = (-12).dp)
                            .size(24.dp).playerPressFeedback(),
                        onClick = viewModel::cancelCurrentDownload,
                        style = MillenniumIconButtonStyle.Quiet) {
                        MillenniumIcon(Iconsax.IconsaxCloseSquare,
                            contentDescription = stringResource(Res.string.common_cancel),
                            modifier = Modifier.size(16.dp))
                    }
                } else {
                    MillenniumIcon(when (status) {
                        DownloadStatus.Completed -> Iconsax.IconsaxCheckCircle
                        is DownloadStatus.Failed -> Iconsax.IconsaxCloseSquare
                        DownloadStatus.Cancelled -> Iconsax.IconsaxRefreshRight
                        else -> Iconsax.IconsaxDirectboxReceive
                    }, contentDescription = downloadLabel, modifier = Modifier.size(28.dp),
                        tint = when (status) {
                            DownloadStatus.Completed -> AppTheme.colors.primary
                            is DownloadStatus.Failed -> AppTheme.colors.error
                            else -> AppTheme.colors.onSurface
                        })
                }
            }
        })
    val trackItems = if (track == null) emptyList() else buildTrackMenuItems(
        track = track,
        state = state.trackOptionsState,
        onAction = { viewModel.handleTrackAction(track, it) },
        onAlbumClick = { viewModel.goToAlbum(track); onGoToAlbum() },
        isInJam = state.isInJam,
        isJamGuest = state.isJamGuest,
        isRadioLoading = state.isStartingRadio,
        downloadItem = downloadItem,
    )
    val items = trackItems + listOf(
            AdaptiveMenuItem(
                icon = Iconsax.SwapHorizontal2,
                label = stringResource(Res.string.player_alternative_source),
                enabled = track != null,
                dividerBefore = true,
                onClick = onAlternativeSource),
            AdaptiveMenuItem(
                icon = Iconsax.InconsaxClock,
                label = stringResource(Res.string.player_sleep_timer),
                onClick = { viewModel.showSleepTimerDialog(); onSleepTimer() }),
            AdaptiveMenuItem(
                icon = Iconsax.IconsaxInformation,
                label = stringResource(Res.string.player_track_details),
                enabled = track != null,
                onClick = viewModel::showTrackDetails),
        )
    if (currentWindowWidthBreakpoint() isAtLeast WindowBreakpoints.Medium) {
        AdaptiveDropdownBottomSheet(
        items = items,
        headerDisplayMode = HeaderDisplayMode.OnlyInBottomSheet,
        header = { track?.let { TrackOptionsSheetHeader(it) } },
        trigger = { open ->
            MillenniumIconButton(
                modifier = Modifier.playerPressFeedback(),
                style = MillenniumIconButtonStyle.Quiet,
                onClick = { open(); onOpen() }) {
                MillenniumIcon(Iconsax.Iconsax3DotsMore,
                    contentDescription = stringResource(Res.string.player_more_options_content_description))
            }
        })
    } else {
        MillenniumIconButton(
            modifier = Modifier.playerPressFeedback(),
            style = MillenniumIconButtonStyle.Quiet,
            onClick = { viewModel.showMoreOptionsSheet(); onOpen() }) {
            MillenniumIcon(Iconsax.Iconsax3DotsMore,
                contentDescription = stringResource(Res.string.player_more_options_content_description))
        }
        if (state.isMoreOptionsSheetOpen) {
            PlayerOptionsGrid(items, viewModel::dismissMoreOptionsSheet)
        }
    }
}

@Composable
private fun PlayerOptionsGrid(items: List<AdaptiveMenuItem>, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(initialDetent = SheetDetent.FullyExpanded)
    LaunchedEffect(sheetState.bottomSheetState.currentDetent) {
        if (sheetState.bottomSheetState.currentDetent == SheetDetent.Hidden) onDismiss()
    }
    MillenniumModalBottomSheet(state = sheetState,
        contentPadding = PaddingValues(vertical = 8.dp), contentSpacing = 4.dp) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)) {
            items(items.size) { index ->
                val item = items[index]
                Box(Modifier.aspectRatio(1f)
                    .millenniumSurface(background = millenniumPanelBrush(MillenniumTheme.colors),
                        shape = RoundedCornerShape(14.dp), borderColor = MillenniumTheme.colors.border,
                        borderWidth = 1.dp, shadowElevation = 2.dp)
                    .playerPressFeedback(item.enabled)
                    .clickable(enabled = item.enabled) { onDismiss(); item.onClick() }
                    .padding(12.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item.leadingContent?.invoke() ?: item.icon?.let {
                            MillenniumIcon(it, contentDescription = null, modifier = Modifier.size(28.dp),
                                tint = AppTheme.colors.onSurface.copy(alpha = if (item.enabled) 1f else 0.38f))
                        }
                        Text(item.label, style = AppTheme.typography.labelSmall,
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = AppTheme.colors.onSurface.copy(alpha = if (item.enabled) 1f else 0.38f))
                    }
                }
            }
        }
    }
}
