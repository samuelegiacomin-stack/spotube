/*
 * Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.library.playlist

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
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
    val pendingPlaylistId: String? = null,
    val counts: Map<String, Int> = emptyMap(),
    val error: String? = null,
)

class PlaylistPickerViewModel(private val repository: LibraryRepository) : ViewModel() {
    val query = TextFieldState()
    private val state = MutableStateFlow(PlaylistPickerState())
    private var loadJob: Job? = null
    val uiState = combine(state, snapshotFlow { query.text.toString() }, repository.playlistWriteErrorFlow, repository.pendingPlaylistWriteFlow) {
        state, query, writeError, pending ->
        state.copy(playlists = state.playlists.filter {
            query.isBlank() || it.title.contains(query, true) || it.description?.contains(query, true) == true
        }, error = writeError ?: state.error, pendingPlaylistId = pending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistPickerState())

    fun load(userId: String?) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            repository.clearPlaylistWriteError()
            state.value = PlaylistPickerState()
            try {
                val playlists = repository.editablePlaylists(userId)
                state.value = PlaylistPickerState(playlists, loading = false,
                    counts = playlists.filter { it.trackCount > 0 }.associate { it.id to it.trackCount })
                // Library summaries can omit counts. Show the picker immediately and hydrate
                // unknown counts with bounded parallel reads instead of displaying a fake zero.
                val permits = Semaphore(3)
                coroutineScope {
                    playlists.filter { it.trackCount <= 0 }.map { playlist ->
                        async {
                            permits.withPermit {
                                try {
                                    val details = withTimeoutOrNull(8_000) { repository.playlistDetails(playlist.id) }
                                    if (details != null) state.update { current ->
                                        current.copy(counts = current.counts + (playlist.id to details.trackCount))
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) { /* A missing count must not block adding. */ }
                            }
                        }
                    }.awaitAll()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                state.value = PlaylistPickerState(loading = false, error = e.message)
            }
        }
    }
}
