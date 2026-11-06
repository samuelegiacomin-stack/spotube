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

package org.spotube.spotube.modules.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioSource
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioStream
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import org.spotube.spotube.core.audioplayer.AudioPlayerInterface
import org.spotube.spotube.core.audioplayer.AudioPlayerQueue
import org.spotube.spotube.core.audioplayer.QueueEntry
import org.spotube.spotube.core.server.CacheEntry
import org.spotube.spotube.core.server.CacheManager
import org.spotube.spotube.core.server.StreamingUrlRepository
import org.spotube.spotube.core.server.TrackSourceRepository
import org.spotube.spotube.core.server.normalizeManifestUrl
import org.spotube.spotube.core.server.selectPreferredAudioStream
import org.spotube.spotube.modules.plugin.AudioPluginSource
import org.spotube.spotube.modules.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.compose.resources.StringResource
import spotube.composeapp.generated.resources.Res
import spotube.composeapp.generated.resources.player_track_details_load_failed
import spotube.composeapp.generated.resources.player_track_details_no_cached_source
import spotube.composeapp.generated.resources.player_track_details_streams_not_cached
import org.spotube.spotube.core.ui.component.TrackOptionsAction
import org.spotube.spotube.core.ui.component.TrackOptionsContext
import org.spotube.spotube.core.ui.component.TrackOptionsState
import org.spotube.spotube.modules.library.LibraryRepository
import org.spotube.spotube.modules.saved_tracks.SavedTracksRepository
import org.spotube.spotube.modules.blacklist.BlacklistRepository
import org.spotube.spotube.modules.plugin.PluginManager
import org.spotube.spotube.modules.downloads.DownloadManager
import org.spotube.spotube.core.remote.RemotePlaybackController
import org.spotube.spotube.core.share.ShareService
import org.spotube.spotube.core.jam.JamRoomService
import org.spotube.spotube.core.jam.JamRole
import org.spotube.spotube.core.navigation.NavigationCommands
import org.spotube.spotube.core.navigation.Routes
import org.spotube.spotube.core.i18n.getString
import spotube.composeapp.generated.resources.player_track_action_failed
import spotube.composeapp.generated.resources.player_playlist_added
import spotube.composeapp.generated.resources.playlist_write_failed
import spotube.composeapp.generated.resources.player_radio_unavailable
import spotube.composeapp.generated.resources.player_radio_started
import spotube.composeapp.generated.resources.search_error_no_metadata_plugin
import org.spotube.spotube.modules.downloads.DownloadItem
import org.spotube.spotube.modules.downloads.DownloadStatus
import spotube.composeapp.generated.resources.player_download_already_downloaded
import spotube.composeapp.generated.resources.player_download_retrying
import spotube.composeapp.generated.resources.player_download_started

data class PlayerTrackDetails(
    val track: MetadataTrack,
    val matchedSource: AudioSource.Basic? = null,
    val streams: List<AudioStream> = emptyList(),
    /** Stream picked by the current streaming format/quality preference. */
    val preferredStream: AudioStream? = null,
    /** URL currently used for playback, read from the playback URL cache. */
    val activeStreamUrl: String? = null,
    /** Whether the active URL matches what the current preferences select. */
    val activeStreamMatchesPreference: Boolean? = null,
    val localCacheEntry: CacheEntry? = null,
    val isLoading: Boolean = false,
    /** Raw error message (e.g. from an exception); takes precedence over [errorRes]. */
    val error: String? = null,
    /** Localized error to show when [error] is null. */
    val errorRes: StringResource? = null)

data class PlayerOptionsUiState(
    val currentDownload: DownloadItem? = null,
    val currentTrack: MetadataTrack? = null,
    val trackOptionsState: TrackOptionsState = TrackOptionsState(),
    val isInJam: Boolean = false,
    val isJamGuest: Boolean = false,
    val playlistTrack: MetadataTrack? = null,
    val isAddingToPlaylist: Boolean = false,
    val playlistError: String? = null,
    val isStartingRadio: Boolean = false,
    val isMoreOptionsMenuOpen: Boolean = false,
    val isMoreOptionsSheetOpen: Boolean = false,
    val isSleepTimerDialogOpen: Boolean = false,
    val sleepTimerMinutes: String = "30",
    val sleepTimerRemainingMs: Long? = null,
    val trackDetails: PlayerTrackDetails? = null)

class PlayerOptionsViewModel(
    private val audioPlayer: AudioPlayerInterface,
    private val audioPlayerQueue: AudioPlayerQueue,
    private val matchedTracksRepository: TrackSourceRepository,
    private val streamingUrlRepository: StreamingUrlRepository,
    private val cacheManager: CacheManager,
    private val settingsRepository: SettingsRepository,
    private val pluginManager: AudioPluginSource,
    private val metadataPlugins: PluginManager,
    private val libraryRepository: LibraryRepository,
    private val savedTracksRepository: SavedTracksRepository,
    private val blacklistRepository: BlacklistRepository,
    private val downloadManager: DownloadManager,
    private val remotePlaybackController: RemotePlaybackController,
    private val shareService: ShareService,
    private val jamRoomService: JamRoomService,
    private val navigationCommands: NavigationCommands) : ViewModel() {
    private val _uiState = MutableStateFlow(PlayerOptionsUiState())
    val uiState: StateFlow<PlayerOptionsUiState> = _uiState.asStateFlow()
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(audioPlayerQueue.currentQueueEntryFlow, downloadManager.downloadsFlow) { entry, downloads ->
                val trackId = (entry as? QueueEntry.StreamingTrack)?.track?.id
                downloads.lastOrNull { trackId != null && it.track?.id == trackId }
            }.collect { download -> _uiState.update { it.copy(currentDownload = download) } }
        }
        viewModelScope.launch {
            val context = combine(
                audioPlayerQueue.queueFlow,
                savedTracksRepository.savedTracksIdsFlow,
                blacklistRepository.blacklistedTracks,
                blacklistRepository.blacklistedArtists,
            ) { queue, saved, tracks, artists ->
                TrackOptionsContext(
                    queueTrackIds = queue.filterIsInstance<QueueEntry.StreamingTrack>()
                        .map { it.track.id }.toSet(),
                    savedTrackIds = saved,
                    blacklistedTrackIds = tracks.map { it.id }.toSet(),
                    blacklistedArtistIds = artists.map { it.id }.toSet())
            }
            combine(context, audioPlayerQueue.currentQueueEntryFlow, jamRoomService.role) {
                context, entry, role ->
                val track = (entry as? QueueEntry.StreamingTrack)?.track
                Triple(track, track?.let {
                    context.copy(currentTrackId = it.id).stateFor(it)
                } ?: TrackOptionsState(), role)
            }.collect { (track, state, role) ->
                _uiState.update {
                    it.copy(currentTrack = track, trackOptionsState = state,
                        isInJam = role != null, isJamGuest = role == JamRole.Guest)
                }
            }
        }
        viewModelScope.launch {
            audioPlayerQueue.currentQueueEntryFlow.collect { entry ->
                val track = (entry as? QueueEntry.StreamingTrack)?.track ?: return@collect
                try {
                    savedTracksRepository.isSavedTracks(listOf(track.id))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Favorite lookup must not prevent opening the rest of the menu.
                }
            }
        }
    }

    fun handleTrackAction(track: MetadataTrack, action: TrackOptionsAction) {
        if (action == TrackOptionsAction.AddToPlaylist) {
            if (_uiState.value.isAddingToPlaylist) return
            libraryRepository.clearPlaylistWriteError()
            _uiState.update { it.copy(playlistTrack = track, playlistError = null) }
            return
        }
        if (_uiState.value.isJamGuest && action in listOf(
                TrackOptionsAction.StartRadio, TrackOptionsAction.PlayNext,
                TrackOptionsAction.AddToQueue, TrackOptionsAction.RemoveFromQueue)) return
        if (action == TrackOptionsAction.StartRadio) {
            if (_uiState.value.isStartingRadio) return
            _uiState.update { it.copy(isStartingRadio = true) }
        }
        viewModelScope.launch {
            try {
                when (action) {
                    TrackOptionsAction.StartRadio -> startRadio(track)
                    TrackOptionsAction.PlayNext -> remotePlaybackController.requestTrackPlayNext(track)
                    TrackOptionsAction.AddToQueue -> remotePlaybackController.requestTrackAddToQueue(track)
                    TrackOptionsAction.RemoveFromQueue -> audioPlayerQueue.queueFlow.value
                        .filterIsInstance<QueueEntry.StreamingTrack>()
                        .firstOrNull { it.track.id == track.id }
                        ?.let { audioPlayerQueue.removeFromQueue(it) }
                    TrackOptionsAction.AddToJam -> remotePlaybackController.addTrackToJam(track)
                    TrackOptionsAction.ToggleFavorite -> {
                        if (track.id in savedTracksRepository.savedTracksIdsFlow.value) {
                            savedTracksRepository.removeSavedTracks(listOf(track.id))
                        } else savedTracksRepository.saveTracks(listOf(track.id))
                    }
                    TrackOptionsAction.Download -> {
                        val download = downloadManager.downloadsFlow.value.lastOrNull { it.track?.id == track.id }
                        when (download?.status) {
                            DownloadStatus.Completed -> messages.send(getString(Res.string.player_download_already_downloaded))
                            is DownloadStatus.Failed, DownloadStatus.Cancelled -> {
                                download?.id?.let(downloadManager::retry)
                                messages.send(getString(Res.string.player_download_retrying))
                            }
                            DownloadStatus.Downloading, DownloadStatus.Queued -> Unit
                            null -> {
                                downloadManager.enqueue(track)
                                messages.send(getString(Res.string.player_download_started, track.title))
                            }
                        }
                    }
                    TrackOptionsAction.ToggleBlacklist -> blacklistRepository.toggleTrack(track)
                    TrackOptionsAction.Share -> track.externalUri?.takeIf { it.isNotBlank() }
                        ?.let { shareService.share(it, track.title) }
                    TrackOptionsAction.AddToPlaylist -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                messages.send(getString(Res.string.player_track_action_failed, error.message.orEmpty()))
            } finally {
                if (action == TrackOptionsAction.StartRadio) {
                    _uiState.update { it.copy(isStartingRadio = false) }
                }
            }
        }
    }

    private suspend fun startRadio(track: MetadataTrack) {
        val plugin = metadataPlugins.selectedMetadataPlugin.value
            ?: error(getString(Res.string.search_error_no_metadata_plugin))
        val recommendations = metadataPlugins.withScope {
            plugin.use { metadataTrackAPI.recommendationsBasedOnTracks(listOf(track.id), limit = 30) }
        }
        val blockedTracks = blacklistRepository.blacklistedTracks.first().map { it.id }.toSet()
        val blockedArtists = blacklistRepository.blacklistedArtists.first().map { it.id }.toSet()
        val tracks = recommendations.distinctBy { it.id }.filter { candidate ->
            candidate.id != track.id && candidate.id !in blockedTracks &&
                candidate.artists.none { it.id in blockedArtists }
        }
        check(tracks.isNotEmpty()) { getString(Res.string.player_radio_unavailable) }
        // A guest must never replace the shared queue, including after a slow API response.
        if (jamRoomService.role.value == JamRole.Guest) return
        audioPlayerQueue.load((listOf(track) + tracks).map {
            QueueEntry.StreamingTrack(track = it, url = "")
        }, autoPlay = true, collectionEntry = null)
        messages.send(getString(Res.string.player_radio_started, track.title))
    }

    fun goToAlbum(track: MetadataTrack) {
        track.album?.id?.let { navigationCommands.navigateTo(Routes.Album(it)) }
    }

    fun dismissPlaylistPicker() {
        if (!_uiState.value.isAddingToPlaylist) {
            _uiState.update { it.copy(playlistTrack = null, playlistError = null) }
        }
    }

    fun dismissPlaylistError() {
        _uiState.update { it.copy(playlistError = null) }
    }

    fun cancelCurrentDownload() {
        _uiState.value.currentDownload?.let { downloadManager.cancel(it.id) }
    }

    fun addCurrentTrackToPlaylist(playlistId: String) {
        val track = _uiState.value.playlistTrack ?: return
        if (_uiState.value.isAddingToPlaylist) return
        _uiState.update { it.copy(isAddingToPlaylist = true, playlistError = null) }
        viewModelScope.launch {
            try {
                libraryRepository.addTracksToPlaylist(playlistId, listOf(track.id))
                _uiState.update { it.copy(playlistTrack = null) }
                messages.send(getString(Res.string.player_playlist_added, track.title))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = libraryRepository.playlistWriteErrorFlow.value
                    ?: (getString(Res.string.playlist_write_failed) + ": " + error.message.orEmpty())
                _uiState.update { it.copy(playlistError = message) }
                messages.send(message)
            } finally {
                _uiState.update { it.copy(isAddingToPlaylist = false) }
            }
        }
    }

    private var sleepTimerJob: Job? = null
    private var trackDetailsJob: Job? = null

    fun showMoreOptionsMenu() {
        _uiState.update {
            it.copy(isMoreOptionsMenuOpen = true, isMoreOptionsSheetOpen = false)
        }
    }

    fun dismissMoreOptionsMenu() {
        _uiState.update { it.copy(isMoreOptionsMenuOpen = false) }
    }

    fun showMoreOptionsSheet() {
        _uiState.update {
            it.copy(isMoreOptionsSheetOpen = true, isMoreOptionsMenuOpen = false)
        }
    }

    fun dismissMoreOptionsSheet() {
        _uiState.update { it.copy(isMoreOptionsSheetOpen = false) }
    }

    fun showSleepTimerDialog() {
        _uiState.update { it.copy(isSleepTimerDialogOpen = true) }
    }

    fun dismissSleepTimerDialog() {
        _uiState.update { it.copy(isSleepTimerDialogOpen = false) }
    }

    fun updateSleepTimerMinutes(minutes: String) {
        if (minutes.all(Char::isDigit)) {
            _uiState.update { it.copy(sleepTimerMinutes = minutes) }
        }
    }

    fun startSleepTimer() {
        val minutes = _uiState.value.sleepTimerMinutes.toLongOrNull()
            ?.takeIf { it in 1..600 } ?: return
        val durationMs = minutes * MILLIS_PER_MINUTE

        sleepTimerJob?.cancel()
        sleepTimerJob = viewModelScope.launch {
            var remainingMs = durationMs
            while (remainingMs > 0L) {
                _uiState.update { it.copy(sleepTimerRemainingMs = remainingMs) }
                val tickMs = minOf(remainingMs, MILLIS_PER_SECOND)
                delay(tickMs.milliseconds)
                remainingMs -= tickMs
            }
            runCatching { audioPlayer.pause() }
            _uiState.update { it.copy(sleepTimerRemainingMs = null, isSleepTimerDialogOpen = false) }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _uiState.update { it.copy(sleepTimerRemainingMs = null) }
    }

    fun showTrackDetails() {
        val track = (audioPlayerQueue.currentQueueEntryFlow.value as? QueueEntry.StreamingTrack)
            ?.track ?: return
        trackDetailsJob?.cancel()
        _uiState.update {
            it.copy(
                trackDetails = PlayerTrackDetails(track = track, isLoading = true))
        }
        trackDetailsJob = viewModelScope.launch {
            runCatching {
                val settings = settingsRepository.userSettings.value
                withContext(Dispatchers.IO) {
                    val pluginId = pluginManager.selectedAudioPlugin.value?.pluginId
                    val matchedSource = pluginId?.let { matchedTracksRepository.getTrackSource(track, it) }
                    val cachedStream = streamingUrlRepository.getCachedStreamUrlEntry(track.id)
                    val localCacheEntry = if (settings.enableMusicCaching) {
                        cacheManager.findCachedEntry(track.id)?.second
                    } else {
                        null
                    }
                    val cachedStreamSource = cachedStream?.source?.takeIf {
                        matchedSource == null || it.id == matchedSource.id
                    }

                    val source = matchedSource ?: cachedStreamSource?.toBasic()
                    val streams = cachedStreamSource?.streams.orEmpty()
                    val preferredStream = selectPreferredAudioStream(
                        streams = streams,
                        preferredFormat = settings.streamingMusicFormat,
                        preferredQuality = settings.streamingMusicQuality)
                    val activeStreamUrl = if (cachedStreamSource != null) cachedStream.url else null
                    val activeStreamMatchesPreference = when {
                        activeStreamUrl == null || preferredStream == null -> null
                        else -> activeStreamUrl == normalizeManifestUrl(
                            url = preferredStream.url,
                            protocol = preferredStream.protocol)
                    }

                    PlayerTrackDetails(
                        track = track,
                        matchedSource = source,
                        streams = streams,
                        preferredStream = preferredStream,
                        activeStreamUrl = activeStreamUrl,
                        activeStreamMatchesPreference = activeStreamMatchesPreference,
                        localCacheEntry = localCacheEntry,
                        errorRes = when {
                            source == null && localCacheEntry == null ->
                                Res.string.player_track_details_no_cached_source
                            streams.isEmpty() && localCacheEntry == null ->
                                Res.string.player_track_details_streams_not_cached
                            else -> null
                        })
                }
            }.onSuccess { details ->
                _uiState.update { current ->
                    if (current.trackDetails?.track?.id == track.id) {
                        current.copy(trackDetails = details)
                    } else current
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update { current ->
                    if (current.trackDetails?.track?.id == track.id) {
                        current.copy(
                            trackDetails = PlayerTrackDetails(
                                track = track,
                                isLoading = false,
                                error = error.message,
                                errorRes = Res.string.player_track_details_load_failed))
                    } else current
                }
            }
        }
    }

    fun dismissTrackDetails() {
        trackDetailsJob?.cancel()
        trackDetailsJob = null
        _uiState.update { it.copy(trackDetails = null) }
    }

    override fun onCleared() {
        sleepTimerJob?.cancel()
        trackDetailsJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
        private const val MILLIS_PER_MINUTE = 60_000L
    }
}
