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

package org.spotube.spotube.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.spotube.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylist
import org.spotube.spotube.core.audioplayer.AudioPlayerQueue
import org.spotube.spotube.core.audioplayer.AudioPlayerInterface
import org.spotube.spotube.core.audioplayer.PlayerState
import org.spotube.spotube.core.audioplayer.QueueCollectionEntry
import org.spotube.spotube.core.navigation.NavigationCommands
import org.spotube.spotube.core.navigation.Routes
import org.spotube.spotube.core.playback.CollectionPlaybackHelper
import org.spotube.spotube.core.remote.CollectionPlaybackKey
import org.spotube.spotube.core.remote.PlaybackDestinationAction
import org.spotube.spotube.core.remote.RemoteCollectionType
import org.spotube.spotube.core.remote.RemotePlaybackController
import org.spotube.spotube.core.ui.component.cards.PlayableCard
import org.koin.compose.koinInject

@Composable
fun PlaylistCard(
    playlist: MetadataPlaylist,
    modifier: Modifier = Modifier,
    audioPlayerQueue: AudioPlayerQueue = koinInject(),
    playbackHelper: CollectionPlaybackHelper = koinInject(),
    navigationCommands: NavigationCommands = koinInject(),
    remotePlaybackController: RemotePlaybackController = koinInject(),
    listMode: Boolean = false
) {
    val player: AudioPlayerInterface = koinInject()
    val playerState by player.playerStateFlow.collectAsStateWithLifecycle()
    val currentCollectionEntry by audioPlayerQueue.currentCollectionEntryFlow.collectAsStateWithLifecycle()
    val busyCollections by remotePlaybackController.busyCollections.collectAsStateWithLifecycle()

    PlayableCard(
        title = playlist.title,
        subtitle = playlist.description,
        imageURL = playlist.thumbnails.firstOrNull()?.url,
        isPlaying = currentCollectionEntry is QueueCollectionEntry.Playlist &&
            currentCollectionEntry?.id == playlist.id &&
            (playerState == PlayerState.PLAYING || playerState == PlayerState.BUFFERING),
        onClick = {
            navigationCommands.navigateTo(Routes.Playlist(playlist.id))
        },
        onPlay = {
            remotePlaybackController.togglePlaylistPlayback(playlist.id, playlist.title)
        },
        onAddToQueue = {
            remotePlaybackController.requestCollectionAddToQueue(
                RemoteCollectionType.Playlist,
                playlist.id,
                playlist.title
            )
        },
        modifier = modifier,
        sharedElementKey = "playlist_art_${playlist.id}",
        listMode = listMode,
        playLoading = CollectionPlaybackKey(RemoteCollectionType.Playlist, playlist.id, PlaybackDestinationAction.Play) in busyCollections,
        addToQueueLoading = CollectionPlaybackKey(RemoteCollectionType.Playlist, playlist.id, PlaybackDestinationAction.AddToQueue) in busyCollections
    )
}
