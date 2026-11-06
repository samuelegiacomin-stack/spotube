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

package org.spotube.spotube.core.audioplayer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

actual class AudioPlayer actual constructor(context: Any) : AudioPlayerInterface, SeamlessPlaylistUpdater {
    override suspend fun replacePlaylistKeepingCurrent(expectedUrls: List<String>, playlist: List<MediaItem>) =
        delegate.replacePlaylistKeepingCurrent(expectedUrls, playlist)
    actual val context: Any = context
    override suspend fun addMediaItems(mediaItems: List<MediaItem>) = delegate.addMediaItems(mediaItems)
    override suspend fun continueAfterQueueEnd(expectedUrl: String) = delegate.continueAfterQueueEnd(expectedUrl)
    private val delegate = SingleMediaAudioPlayer(context,
        checkNotNull(NativePlayerRegistry.factory).create(), IosMediaSession())
    actual override suspend fun play() = delegate.play()
    actual override suspend fun pause() = delegate.pause()
    actual override suspend fun stop() = delegate.stop()
    actual override suspend fun seekTo(position: Duration) = delegate.seekTo(position)
    actual override suspend fun loop(state: LoopState) = delegate.loop(state)
    actual override suspend fun shuffle(enabled: Boolean) = delegate.shuffle(enabled)
    actual override suspend fun load(playlist: List<MediaItem>, autoPlay: Boolean, startPosition: Int) = delegate.load(playlist, autoPlay, startPosition)
    actual override suspend fun addMediaItem(mediaItem: MediaItem) = delegate.addMediaItem(mediaItem)
    actual override suspend fun insertMediaItemAtNextIndex(mediaItem: MediaItem) = delegate.insertMediaItemAtNextIndex(mediaItem)
    actual override suspend fun removeMediaItem(mediaItem: MediaItem) = delegate.removeMediaItem(mediaItem)
    actual override suspend fun moveMediaItem(fromIndex: Int, toIndex: Int) = delegate.moveMediaItem(fromIndex, toIndex)
    actual override suspend fun skipToNext() = delegate.skipToNext()
    actual override suspend fun skipToPrevious() = delegate.skipToPrevious()
    actual override suspend fun jumpTo(index: Int) = delegate.jumpTo(index)
    actual override suspend fun setVolume(volume: Float) = delegate.setVolume(volume)
    actual override suspend fun setPlaybackSpeed(speed: Float) = delegate.setPlaybackSpeed(speed)
    actual override fun isDisposed(): Boolean = delegate.isDisposed()
    actual override fun dispose() = delegate.dispose()
    actual override val playerStateFlow: StateFlow<PlayerState> get() = delegate.playerStateFlow
    actual override val currentMediaItemFlow: StateFlow<MediaItem?> get() = delegate.currentMediaItemFlow
    actual override val playlistFlow: StateFlow<List<MediaItem>> get() = delegate.playlistFlow
    actual override val durationFlow: StateFlow<Duration> get() = delegate.durationFlow
    actual override val positionFlow: StateFlow<Duration> get() = delegate.positionFlow
    actual override val bufferingPositionFlow: StateFlow<Duration> get() = delegate.bufferingPositionFlow
    actual override val loopStateFlow: StateFlow<LoopState> get() = delegate.loopStateFlow
    actual override val shuffleModeFlow: StateFlow<Boolean> get() = delegate.shuffleModeFlow
    actual override val playbackSpeedFlow: StateFlow<Float> get() = delegate.playbackSpeedFlow
    actual override val volumeFlow: StateFlow<Float> get() = delegate.volumeFlow
    actual override val completionFlow: Flow<Unit> get() = delegate.completionFlow
    actual override val errorFlow: Flow<Throwable> get() = delegate.errorFlow
}
