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

package org.spotube.spotube.modules.library

import spotube.composeapp.generated.resources.playlist_add_unconfirmed

import org.spotube.plugin_interfaces.plugin_apis.metadata.album.MetadataAlbum
import org.spotube.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import org.spotube.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylist
import org.spotube.plugin_interfaces.plugin_apis.metadata.user.MetadataUser
import org.spotube.spotube.modules.plugin.PluginManager
import io.github.reactivecircus.cache4k.Cache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import spotube.composeapp.generated.resources.playlist_write_confirmed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import spotube.composeapp.generated.resources.playlist_write_pending
import spotube.composeapp.generated.resources.playlist_write_timeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import org.spotube.spotube.core.i18n.getString
import spotube.composeapp.generated.resources.Res
import spotube.composeapp.generated.resources.playlist_write_failed
import spotube.composeapp.generated.resources.playlist_login_required

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepository(
    val pluginManager: PluginManager
) {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val plugin
        get() = pluginManager.selectedMetadataPlugin.value

    private val playlistMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val playlistMessagesFlow = playlistMessages.asSharedFlow()
    private val playlistRevision = MutableStateFlow(0L)
    val playlistRevisionFlow = playlistRevision.asStateFlow()
    private val playlistWriteMutex = Mutex()
    private val pendingPlaylistWrite = MutableStateFlow<String?>(null)
    val pendingPlaylistWriteFlow = pendingPlaylistWrite.asStateFlow()
    private val playlistWriteError = MutableStateFlow<String?>(null)
    val playlistWriteErrorFlow = playlistWriteError.asStateFlow()
    fun clearPlaylistWriteError() { playlistWriteError.value = null }

    private val playlistCache =
        Cache.Builder<PaginationStrategy, PaginationResult<MetadataPlaylist>>().build()
    private val albumCache =
        Cache.Builder<PaginationStrategy, PaginationResult<MetadataAlbum.Detailed>>().build()
    private val artistCache =
        Cache.Builder<PaginationStrategy, PaginationResult<MetadataArtist.Detailed>>().build()

    private val savedPlaylistIds = MutableStateFlow<Set<String>>(emptySet())
    val savedPlaylistIdsFlow: StateFlow<Set<String>> = savedPlaylistIds.asStateFlow()

    private val savedAlbumIds = MutableStateFlow<Set<String>>(emptySet())
    val savedAlbumIdsFlow: StateFlow<Set<String>> = savedAlbumIds.asStateFlow()

    private val savedArtistIds = MutableStateFlow<Set<String>>(emptySet())
    val savedArtistIdsFlow: StateFlow<Set<String>> = savedArtistIds.asStateFlow()

    init {
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

    private val authPlugin get() = plugin?.takeIf { it.loggedInFlow.value }

    fun invalidateCaches() {
        playlistCache.invalidateAll()
        albumCache.invalidateAll()
        artistCache.invalidateAll()
        savedPlaylistIds.value = emptySet()
        savedAlbumIds.value = emptySet()
        savedArtistIds.value = emptySet()
    }

    suspend fun savedPlaylists(paginationStrategy: PaginationStrategy? = null) =
        authPlugin?.let { plugin ->
            playlistCache.get(
                key = paginationStrategy ?: PaginationStrategy.Offset(0, 20)
            ) {
                pluginManager.withScope {
                    plugin.use {
                        val result = metadataPlaylistAPI.savedPlaylists(paginationStrategy)
                        savedPlaylistIds.value += result.items.map { it.id }.toSet()
                        result
                    }
                }
            }
        }

    suspend fun savedAlbums(paginationStrategy: PaginationStrategy? = null) =
        authPlugin?.let { plugin ->
            albumCache.get(
                key = paginationStrategy ?: PaginationStrategy.Offset(0, 20)
            ) {
                pluginManager.withScope {
                    plugin.use {
                        val result = metadataAlbumAPI.savedAlbums(paginationStrategy)
                        savedAlbumIds.value += result.items.map { it.id }.toSet()
                        result
                    }
                }
            }
        }

    suspend fun savedArtists(paginationStrategy: PaginationStrategy? = null) =
        authPlugin?.let { plugin ->
            artistCache.get(
                key = paginationStrategy ?: PaginationStrategy.Offset(0, 20)
            ) {
                pluginManager.withScope {
                    plugin.use {
                        val result = metadataArtistAPI.savedArtists(paginationStrategy)
                        savedArtistIds.value += result.items.map { it.id }.toSet()
                        result
                    }
                }
            }
        }

    suspend fun isSavedPlaylists(ids: List<String>): List<Boolean> {
        val unknownIds = ids.filterNot { savedPlaylistIds.value.contains(it) }

        if (unknownIds.isEmpty()) {
            return ids.map { true }
        }

        val unknownStates = authPlugin?.let { plugin ->
            val savedStates = pluginManager.withScope {
                plugin.use {
                    metadataPlaylistAPI.isSavedPlaylists(unknownIds)
                }
            }
            val savedIds = unknownIds.filterIndexed { index, id ->
                savedStates.getOrNull(index) == true
            }.toSet()
            savedPlaylistIds.value += savedIds
            savedStates
        } ?: unknownIds.map { false }

        return ids.map { id ->
            savedPlaylistIds.value.contains(id) ||
                    unknownStates.getOrNull(unknownIds.indexOf(id)) ?: false
        }
    }

    suspend fun savePlaylists(ids: List<String>) = setPlaylistsSaved(ids, true)

    suspend fun removeSavedPlaylists(ids: List<String>) = setPlaylistsSaved(ids, false)

    private suspend fun setPlaylistsSaved(ids: List<String>, saved: Boolean) {
        if (ids.isEmpty()) return
        val selected = authPlugin ?: error(getString(Res.string.playlist_login_required))
        pluginManager.withScope {
            selected.use {
                if (saved) metadataPlaylistAPI.savePlaylists(ids)
                else metadataPlaylistAPI.removeSavedPlaylists(ids)
                val actual = metadataPlaylistAPI.isSavedPlaylists(ids)
                check(actual.size == ids.size && actual.all { it == saved }) {
                    getString(Res.string.playlist_write_failed)
                }
            }
        }
        playlistCache.invalidateAll()
        savedPlaylistIds.update { if (saved) it + ids.toSet() else it - ids.toSet() }
        playlistRevision.update { it + 1 }
        playlistMessages.tryEmit(getString(Res.string.playlist_write_confirmed))
    }

    suspend fun isSavedAlbums(ids: List<String>): List<Boolean> {
        val unknownIds = ids.filterNot { savedAlbumIds.value.contains(it) }

        if (unknownIds.isEmpty()) {
            return ids.map { true }
        }

        val unknownStates = authPlugin?.let { plugin ->
            val savedStates = pluginManager.withScope {
                plugin.use {
                    metadataAlbumAPI.isSavedAlbums(unknownIds)
                }
            }
            val savedIds = unknownIds.filterIndexed { index, id ->
                savedStates.getOrNull(index) == true
            }.toSet()
            savedAlbumIds.value += savedIds
            savedStates
        } ?: unknownIds.map { false }

        return ids.map { id ->
            savedAlbumIds.value.contains(id) ||
                    unknownStates.getOrNull(unknownIds.indexOf(id)) ?: false
        }
    }

    suspend fun saveAlbums(ids: List<String>) {
        authPlugin?.let { plugin ->
            pluginManager.withScope {
                plugin.use {
                    metadataAlbumAPI.saveAlbums(ids)
                }
            }
            albumCache.invalidateAll()
            savedAlbumIds.value += ids.toSet()
        }
    }

    suspend fun removeSavedAlbums(ids: List<String>) {
        authPlugin?.let { plugin ->
            pluginManager.withScope {
                plugin.use {
                    metadataAlbumAPI.removeSavedAlbums(ids)
                }
            }
            albumCache.invalidateAll()
            savedAlbumIds.value -= ids.toSet()
        }
    }

    suspend fun isSavedArtists(ids: List<String>): List<Boolean> {
        val unknownIds = ids.filterNot { savedArtistIds.value.contains(it) }

        if (unknownIds.isEmpty()) {
            return ids.map { true }
        }

        val unknownStates = authPlugin?.let { plugin ->
            val savedStates = pluginManager.withScope {
                plugin.use {
                    metadataArtistAPI.isSavedArtists(unknownIds)
                }
            }
            val savedIds = unknownIds.filterIndexed { index, id ->
                savedStates.getOrNull(index) == true
            }.toSet()
            savedArtistIds.value += savedIds
            savedStates
        } ?: unknownIds.map { false }

        return ids.map { id ->
            savedArtistIds.value.contains(id) ||
                    unknownStates.getOrNull(unknownIds.indexOf(id)) ?: false
        }
    }

    suspend fun saveArtists(ids: List<String>) {
        authPlugin?.let { plugin ->
            pluginManager.withScope {
                plugin.use {
                    metadataArtistAPI.saveArtists(ids)
                }
            }
            artistCache.invalidateAll()
            savedArtistIds.value += ids.toSet()
        }
    }

    suspend fun removeSavedArtists(ids: List<String>) {
        authPlugin?.let { plugin ->
            pluginManager.withScope {
                plugin.use {
                    metadataArtistAPI.removeSavedArtists(ids)
                }
            }
            artistCache.invalidateAll()
            savedArtistIds.value -= ids.toSet()
        }
    }

    suspend fun currentUser(): MetadataUser? {
        return authPlugin?.let { plugin ->
            pluginManager.withScope {
                plugin.use {
                    metadataUserAPI.getUser("")
                }
            }
        }
    }

    suspend fun createPlaylist(
        name: String,
        description: String?,
        isPublic: Boolean,
        isCollaborating: Boolean,
        imageBase64: String,
        trackIds: List<String>): MetadataPlaylist? {
        return authPlugin?.let { plugin ->
            pluginManager.withScope {
                plugin.use {
                    val result = metadataPlaylistAPI.createPlaylist(
                        name = name,
                        description = description,
                        isPublic = isPublic,
                        isCollaborating = isCollaborating,
                        imageBase64 = imageBase64,
                        trackIds = trackIds)
                    playlistCache.invalidateAll()
                    savedPlaylistIds.value += result.id
                    result
                }
            }
        }
    }

    suspend fun updatePlaylist(
        id: String,
        name: String?,
        description: String?,
        isPublic: Boolean?,
        isCollaborating: Boolean?,
        imageBase64: String?,
        trackIds: List<String>?): MetadataPlaylist? {
        return authPlugin?.let { plugin ->
            pluginManager.withScope {
                plugin.use {
                    val result = metadataPlaylistAPI.updatePlaylist(
                        id = id,
                        name = name,
                        description = description,
                        isPublic = isPublic,
                        isCollaborating = isCollaborating,
                        imageBase64 = imageBase64,
                        trackIds = trackIds)
                    playlistCache.invalidateAll()
                    result
                }
            }
        }
    }

    suspend fun addTracksToPlaylist(playlistId: String, trackIds: List<String>) {
        check(playlistWriteMutex.tryLock()) { getString(Res.string.playlist_write_pending) }
        pendingPlaylistWrite.value = playlistId
        playlistWriteError.value = null
        try {
            check(trackIds.isNotEmpty()) { getString(Res.string.playlist_add_unconfirmed) }
            val plugin = authPlugin ?: error(getString(Res.string.playlist_login_required))
            withTimeout(30_000) {
                pluginManager.withScope {
                    plugin.use {
                        val confirmed = writeAndConfirmPlaylistAddition(
                            trackIds = trackIds,
                            write = { metadataPlaylistAPI.addTracksToPlaylist(playlistId, trackIds) },
                            fetchCount = { metadataPlaylistAPI.getPlaylist(playlistId).trackCount },
                            fetchTrackIds = { offset, limit ->
                                metadataPlaylistAPI.getPlaylistTracks(
                                    playlistId, PaginationStrategy.Offset(offset, limit))
                                    .items.map { it.id }
                            })
                        check(confirmed) { getString(Res.string.playlist_add_unconfirmed) }
                    }
                }
            }
            playlistCache.invalidateAll()
            playlistRevision.update { it + 1 }
            playlistMessages.tryEmit(getString(Res.string.playlist_write_confirmed))
        } catch (timeout: TimeoutCancellationException) {
            // The server may have accepted the write. Never retry a mutation automatically.
            val failure = IllegalStateException(getString(Res.string.playlist_write_timeout))
            playlistWriteError.value = failure.message
            throw failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            playlistWriteError.value = getString(Res.string.playlist_write_failed) +
                (e.message?.let { ": $it" } ?: "")
            throw e
        } finally {
            pendingPlaylistWrite.value = null
            playlistWriteMutex.unlock()
        }
    }

    suspend fun playlistDetails(id: String): MetadataPlaylist? = authPlugin?.let { selected ->
        pluginManager.withScope { selected.use { metadataPlaylistAPI.getPlaylist(id) } }
    }

    suspend fun editablePlaylists(currentUserId: String?): List<MetadataPlaylist> {
        val userId = currentUserId ?: currentUser()?.id ?: return emptyList()
        return org.spotube.spotube.modules.library.playlist.loadOwnedPlaylists(
            userId, ::savedPlaylists,
            fetchDetails = { id -> plugin?.use { metadataPlaylistAPI.getPlaylist(id) } })
    }
}
