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

package org.spotube.spotube.modules.playlist

import org.spotube.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import org.spotube.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylist
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import org.spotube.spotube.modules.library.LibraryRepository
import org.spotube.spotube.modules.plugin.PluginManager
import io.github.reactivecircus.cache4k.Cache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistRepository(
    val pluginManager: PluginManager,
    private val libraryRepository: LibraryRepository) {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val plugin
        get() = pluginManager.selectedMetadataPlugin.value

    private val playlistInfoCache = Cache.Builder<Pair<String, Long>, Pair<MetadataPlaylist, Boolean>>().build()
    private val playlistTracksCache =
        Cache.Builder<Triple<String, Long, PaginationStrategy>, PaginationResult<MetadataTrack>>().build()

    init {
        scope.launch {
            libraryRepository.playlistRevisionFlow.collect { invalidateCaches() }
        }
        scope.launch {
            pluginManager.selectedMetadataPlugin
                .filterNotNull()
                .flatMapLatest { it.loggedInFlow }
                .distinctUntilChanged()
                .collect { loggedIn ->
                    isLoggedIn = loggedIn
                    invalidateCaches()
                }
        }
    }

    private var isLoggedIn: Boolean = false

    fun invalidateCaches() {
        playlistInfoCache.invalidateAll()
        playlistTracksCache.invalidateAll()
    }

    suspend fun getPlaylistInfo(playlistId: String) = plugin?.let { plugin ->
        playlistInfoCache.get(playlistId to libraryRepository.playlistRevisionFlow.value) {
            pluginManager.withScope {
                plugin.use {
                    val playlist = metadataPlaylistAPI.getPlaylist(playlistId)
                    val isSaved = libraryRepository.isSavedPlaylists(listOf(playlistId)).firstOrNull() ?: false
                    playlist to isSaved
                }
            }
        }
    }

    suspend fun getPlaylistTracks(playlistId: String, paginationStrategy: PaginationStrategy? = null) =
        plugin?.let { plugin ->
            // A page requested before a confirmed Spotify write cannot fill the new revision's cache.
            val revision = libraryRepository.playlistRevisionFlow.value
            val key = Triple(playlistId, revision, paginationStrategy ?: PaginationStrategy.Offset(0, 20))
            playlistTracksCache.get(key) {
                pluginManager.withScope {
                    plugin.use {
                        metadataPlaylistAPI.getPlaylistTracks(
                            id = playlistId,
                            pagination = paginationStrategy
                        )
                    }
                }
            }
        }

    suspend fun toggleSavedPlaylist(playlistId: String, currentIsSaved: Boolean) = plugin?.let { plugin ->
        if (currentIsSaved) {
            libraryRepository.removeSavedPlaylists(listOf(playlistId))
        } else {
            libraryRepository.savePlaylists(listOf(playlistId))
        }
        !currentIsSaved
    }
}
