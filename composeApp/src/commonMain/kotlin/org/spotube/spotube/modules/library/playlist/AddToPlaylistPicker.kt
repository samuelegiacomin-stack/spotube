/*
 * Copyright (C) 2026 Kingmor Roy Tirtho and Spotube Contributors
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

package org.spotube.spotube.modules.library.playlist


import org.spotube.spotube.core.ui.misc.itemsWithUniqueKeys
import org.spotube.spotube.core.ui.component.AdaptiveDialogBottomSheet
import org.spotube.millennium.composables.MillenniumIcon
import org.spotube.millennium.composables.MillenniumButton
import org.spotube.millennium.composables.MillenniumButtonStyle
import org.spotube.millennium.composables.MillenniumDialog
import org.spotube.millennium.composables.MillenniumModalBottomSheet
import org.spotube.millennium.composables.MillenniumTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape

import org.spotube.spotube.core.ui.theming.AppTheme
import com.composeunstyled.SheetDetent
import com.composeunstyled.Text
import com.composeunstyled.rememberModalBottomSheetState
import com.composeunstyled.currentWindowWidthBreakpoint
import org.spotube.millennium.adaptive.WindowBreakpoints
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.spotube.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylist
import org.spotube.spotube.modules.library.LibraryRepository
import org.spotube.spotube.resources.iconsax.Iconsax
import org.spotube.spotube.resources.iconsax.IconsaxFilterSearch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import spotube.composeapp.generated.resources.Res
import spotube.composeapp.generated.resources.library_add_to_playlist_empty
import spotube.composeapp.generated.resources.library_add_to_playlist_loading
import spotube.composeapp.generated.resources.library_add_to_playlist_search_content_description
import spotube.composeapp.generated.resources.library_add_to_playlist_search_placeholder
import spotube.composeapp.generated.resources.library_add_to_playlist_title
import spotube.composeapp.generated.resources.library_local_tracks_count
import spotube.composeapp.generated.resources.common_cancel
import spotube.composeapp.generated.resources.playlist_write_pending
import spotube.composeapp.generated.resources.playlist_count_unknown

@Composable
fun AddToPlaylistPicker(
    visible: Boolean,
    currentUserId: String?,
    onDismiss: () -> Unit,
    onPlaylistSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    libraryRepository: LibraryRepository = koinInject()) {
    if (!visible) return

    val isLargeScreen = currentWindowWidthBreakpoint() isAtLeast WindowBreakpoints.Medium

    val viewModel: PlaylistPickerViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(visible, currentUserId) {
        viewModel.load(currentUserId)
    }

    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxSize()) {
            state.error?.let { Text(it, color = AppTheme.colors.error) }
            if (state.pendingPlaylistId != null) Text(stringResource(Res.string.playlist_write_pending))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MillenniumIcon(
                    imageVector = Iconsax.IconsaxFilterSearch,
                    contentDescription = stringResource(Res.string.library_add_to_playlist_search_content_description))
                MillenniumTextField(
                    state = viewModel.query,
                    
                    modifier = Modifier.weight(1f),
                    placeholder = stringResource(Res.string.library_add_to_playlist_search_placeholder))
            }

            PlaylistPickerContent(
                playlists = state.playlists,
                isLoading = state.loading,
                counts = state.counts,
                enabled = state.pendingPlaylistId == null,
                onPlaylistSelected = { playlistId ->
                    onPlaylistSelected(playlistId)
                })
        }
    }

    val pickerTitle = stringResource(Res.string.library_add_to_playlist_title)
    AdaptiveDialogBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        dialogTitle = pickerTitle,
        title = { Text(pickerTitle, style = AppTheme.typography.headlineSmall) },
        actions = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End) {
                MillenniumButton(
                    onClick = onDismiss,
                    style = MillenniumButtonStyle.Secondary) {
                    Text(stringResource(Res.string.common_cancel))
                }
            }
        }) {
        Box(modifier = Modifier.fillMaxWidth().height(400.dp)) {
            content()
        }
    }
}

@Composable
private fun PlaylistPickerContent(
    playlists: List<MetadataPlaylist>,
    isLoading: Boolean,
    counts: Map<String, Int>,
    enabled: Boolean,
    onPlaylistSelected: (String) -> Unit) {
    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.library_add_to_playlist_loading))
        }
    } else if (playlists.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.library_add_to_playlist_empty))
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsWithUniqueKeys(playlists, id = { it.id }) { playlist ->
                PlaylistPickerRow(
                    playlist = playlist,
                    count = counts[playlist.id],
                    enabled = enabled,
                    onClick = { onPlaylistSelected(playlist.id) })
            }
        }
    }
}

@Composable
private fun PlaylistPickerRow(
    playlist: MetadataPlaylist,
    count: Int?,
    enabled: Boolean,
    onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val imageUrl = playlist.thumbnails.firstOrNull()?.url
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = playlist.title,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop)
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center) {
                Text(
                    text = playlist.title.take(1).uppercase(),
                    style = AppTheme.typography.titleMedium)
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.title,
                style = AppTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(
                text = if (count != null) pluralStringResource(Res.plurals.library_local_tracks_count, count, count)
                    else stringResource(Res.string.playlist_count_unknown),
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant)
        }
    }
}
