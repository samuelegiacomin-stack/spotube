/*
 * Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.library.playlist

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.spotube.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylist
import org.spotube.spotube.modules.library.LibraryRepository

data class PlaylistPickerState(
    val playlists: List<MetadataPlaylist> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class PlaylistPickerViewModel(private val repository: LibraryRepository) : ViewModel() {
    val query = TextFieldState()
    private val state = MutableStateFlow(PlaylistPickerState())
    val uiState = combine(state, snapshotFlow { query.text.toString() }, repository.playlistWriteErrorFlow) {
        state, query, writeError ->
        state.copy(playlists = state.playlists.filter {
            query.isBlank() || it.title.contains(query, true) || it.description?.contains(query, true) == true
        }, error = writeError ?: state.error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistPickerState())

    fun load(userId: String?) {
        viewModelScope.launch {
            repository.clearPlaylistWriteError()
            state.value = PlaylistPickerState()
            try {
                state.value = PlaylistPickerState(repository.editablePlaylists(userId), loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                state.value = PlaylistPickerState(loading = false, error = e.message)
            }
        }
    }
}
