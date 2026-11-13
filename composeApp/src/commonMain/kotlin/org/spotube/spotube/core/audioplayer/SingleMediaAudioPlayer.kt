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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * iOS player. Playback is done by MobileVLCKit through [NativePlayerEngine]; AVPlayer misreports the
 * duration of some fragmented MP4 streams (doubling it, which leaves the second half silent).
 * The engine plays a single URL, so the playlist, loop, shuffle and auto-advance live here.
 */
class SingleMediaAudioPlayer(
    val context: Any,
    private val engine: NativePlayerEngine,
    private val mediaSession: PlayerMediaSession,
) : AudioPlayerInterface, SeamlessPlaylistUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    @kotlin.concurrent.Volatile
    private var disposed = false
    @kotlin.concurrent.Volatile
    private var generation = 0L
    @kotlin.concurrent.Volatile
    private var completionHandled = false

    private val originalPlaylist = mutableListOf<MediaItem>()
    private val currentPlaylist = mutableListOf<MediaItem>()
    private var currentIndex = -1
    private var shuffleEnabled = false

    // The engine holds media only after the first play(); until then a requested start position
    // is kept here and handed over with the media.
    private var engineHasMedia = false
    private var pendingStartMs = 0L
    private var pendingSkip: Job? = null

    private val _playerState = MutableStateFlow(PlayerState.IDLE)
    private val _currentMediaItem = MutableStateFlow<MediaItem?>(null)
    private val _playlist = MutableStateFlow<List<MediaItem>>(emptyList())
    private val _duration = MutableStateFlow(Duration.ZERO)
    private val _position = MutableStateFlow(Duration.ZERO)
    private val _bufferingPosition = MutableStateFlow(Duration.ZERO)
    private val _loopState = MutableStateFlow(LoopState.NONE)
    private val _shuffleMode = MutableStateFlow(false)
    private val _playbackSpeed = MutableStateFlow(1.0f)
    private val _volume = MutableStateFlow(1.0f)
    private val _completion = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _error = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)

    override val playerStateFlow: StateFlow<PlayerState> = _playerState.asStateFlow()
    override val currentMediaItemFlow: StateFlow<MediaItem?> = _currentMediaItem.asStateFlow()
    override val playlistFlow: StateFlow<List<MediaItem>> = _playlist.asStateFlow()
    override val durationFlow: StateFlow<Duration> = _duration.asStateFlow()
    override val positionFlow: StateFlow<Duration> = _position.asStateFlow()
    override val bufferingPositionFlow: StateFlow<Duration> = _bufferingPosition.asStateFlow()
    override val loopStateFlow: StateFlow<LoopState> = _loopState.asStateFlow()
    override val shuffleModeFlow: StateFlow<Boolean> = _shuffleMode.asStateFlow()
    override val playbackSpeedFlow: StateFlow<Float> = _playbackSpeed.asStateFlow()
    override val volumeFlow: StateFlow<Float> = _volume.asStateFlow()
    override val completionFlow: Flow<Unit> = _completion.asSharedFlow()
    override val errorFlow: Flow<Throwable> = _error.asSharedFlow()

    private fun listenerFor(token: Long) = object : NativePlayerListener {
        override fun onState(state: NativePlayerState) {
            if (disposed || token != generation) return
            when (state) {
                NativePlayerState.IDLE -> if (!completionHandled) _playerState.tryEmit(PlayerState.IDLE)
                NativePlayerState.OPENING,
                NativePlayerState.BUFFERING -> _playerState.tryEmit(PlayerState.BUFFERING)
                NativePlayerState.PLAYING -> {
                    _playerState.tryEmit(PlayerState.PLAYING)
                    mediaSession.update(
                        _currentMediaItem.value,
                        _position.value.inWholeMilliseconds / 1000.0,
                        _duration.value.inWholeMilliseconds / 1000.0,
                        true)
                }
                NativePlayerState.PAUSED -> {
                    _playerState.tryEmit(PlayerState.PAUSED)
                    mediaSession.update(
                        _currentMediaItem.value,
                        _position.value.inWholeMilliseconds / 1000.0,
                        _duration.value.inWholeMilliseconds / 1000.0,
                        false)
                }
                NativePlayerState.ENDED -> scope.launch {
                    lock.withLock {
                        if (!disposed && token == generation && !completionHandled) {
                            completionHandled = true
                            onMediaEndedLocked()
                        }
                    }
                }
            }
        }

        override fun onTime(positionMs: Long) {
            if (disposed || token != generation || positionMs < 0) return
            _position.tryEmit(positionMs.milliseconds)
            mediaSession.update(
                _currentMediaItem.value,
                positionMs / 1000.0,
                _duration.value.inWholeMilliseconds / 1000.0,
                _playerState.value == PlayerState.PLAYING)
        }

        override fun onDuration(durationMs: Long) {
            if (disposed || token != generation || durationMs <= 0) return
            _duration.tryEmit(durationMs.milliseconds)
        }

        override fun onError(message: String) {
            scope.launch {
                lock.withLock {
                    if (disposed || token != generation) return@withLock

                    // Retire the failed load before exposing Idle so Play creates new native media.
                    generation++
                    engineHasMedia = false
                    pendingStartMs = _position.value.inWholeMilliseconds
                    _bufferingPosition.value = Duration.ZERO
                    mediaSession.update(
                        _currentMediaItem.value,
                        pendingStartMs / 1000.0,
                        _duration.value.inWholeMilliseconds / 1000.0,
                        false)
                    _playerState.value = PlayerState.IDLE
                    _error.tryEmit(IllegalStateException("VLC playback error: $message"))
                }
            }
        }
    }

    init {
        engine.setListener(listenerFor(generation))
        mediaSession.configure(
            onPlay = { scope.launch { play() } },
            onPause = { scope.launch { pause() } },
            onSeek = { seconds -> scope.launch { seekTo(seconds.seconds) } },
            onNext = { scope.launch { skipToNext() } },
            onPrevious = { scope.launch { skipToPrevious() } })
    }

    /** Loads [currentPlaylist] at [currentIndex] into the engine. Caller holds [lock]. */
    private fun loadCurrentIntoEngineLocked(autoPlay: Boolean) {
        cancelPendingSkipLocked()
        val item = currentPlaylist.getOrNull(currentIndex) ?: return
        generation++
        completionHandled = false
        engine.setListener(listenerFor(generation))
        val startMs = pendingStartMs
        pendingStartMs = 0L
        engineHasMedia = true
        _playerState.tryEmit(PlayerState.BUFFERING)
        _position.tryEmit(startMs.milliseconds)
        _bufferingPosition.tryEmit(Duration.ZERO)
        _duration.tryEmit(item.duration)
        engine.load(item.url, autoPlay, startMs)
    }

    /** Switches to [index] and starts it. Caller holds [lock]. */
    private fun playIndexLocked(index: Int) {
        currentIndex = index
        _playerState.tryEmit(PlayerState.BUFFERING)
        _currentMediaItem.tryEmit(currentPlaylist[index])
        pendingStartMs = 0L
        loadCurrentIntoEngineLocked(autoPlay = true)
    }

    private fun cancelPendingSkipLocked() {
        pendingSkip?.cancel()
        pendingSkip = null
    }

    /** Count each tap immediately and open only the last track of a fast sequence. */
    private fun skipToIndexLocked(index: Int) {
        if (pendingSkip == null) {
            generation++
            engineHasMedia = false
            engine.stop()
        }
        currentIndex = index
        val item = currentPlaylist[index]
        pendingStartMs = 0L
        _position.value = Duration.ZERO
        _bufferingPosition.value = Duration.ZERO
        _duration.value = item.duration
        _currentMediaItem.value = item
        _playerState.value = PlayerState.BUFFERING
        mediaSession.update(item, 0.0, item.duration.inWholeMilliseconds / 1000.0, false)

        cancelPendingSkipLocked()
        pendingSkip = scope.launch {
            delay(120)
            lock.withLock {
                if (disposed || pendingSkip != coroutineContext[Job]) return@withLock
                pendingSkip = null
                loadCurrentIntoEngineLocked(autoPlay = true)
            }
        }
    }

    private fun onMediaEndedLocked() {
        if (disposed || currentPlaylist.isEmpty()) return
        _completion.tryEmit(Unit)
        when {
            _loopState.value == LoopState.ONE -> playIndexLocked(currentIndex.coerceAtLeast(0))
            currentIndex < currentPlaylist.lastIndex -> playIndexLocked(currentIndex + 1)
            _loopState.value == LoopState.ALL -> playIndexLocked(0)
            else -> {
                _playerState.tryEmit(PlayerState.COMPLETED)
                engineHasMedia = false
            }
        }
    }

    override suspend fun play() {
        lock.withLock {
            if (disposed || currentPlaylist.isEmpty()) return
            mediaSession.activate()
            if (currentIndex !in currentPlaylist.indices) {
                currentIndex = 0
                _currentMediaItem.tryEmit(currentPlaylist[0])
            }
            if (engineHasMedia) engine.play() else loadCurrentIntoEngineLocked(autoPlay = true)
        }
    }

    override suspend fun pause() {
        lock.withLock {
            if (disposed) return
            if (pendingSkip != null || _playerState.value == PlayerState.COMPLETED) {
                cancelPendingSkipLocked()
                _playerState.tryEmit(PlayerState.PAUSED)
                return
            }
            engine.pause()
        }
    }

    override suspend fun stop() {
        lock.withLock {
            if (disposed) return
            cancelPendingSkipLocked()
            generation++
            engine.stop()
            mediaSession.deactivate()
            mediaSession.update(
                _currentMediaItem.value,
                0.0,
                _duration.value.inWholeMilliseconds / 1000.0,
                false)
            engineHasMedia = false
            pendingStartMs = 0L
            _position.tryEmit(Duration.ZERO)
            _playerState.tryEmit(PlayerState.IDLE)
        }
    }

    override suspend fun seekTo(position: Duration) {
        lock.withLock {
            if (disposed) return
            val maxMs = _duration.value.inWholeMilliseconds
            val targetMs = if (maxMs > 0) {
                position.inWholeMilliseconds.coerceIn(0, maxMs)
            } else {
                position.inWholeMilliseconds.coerceAtLeast(0)
            }
            if (engineHasMedia) engine.seekTo(targetMs) else pendingStartMs = targetMs
            _position.tryEmit(targetMs.milliseconds)
        }
    }

    override suspend fun loop(state: LoopState) {
        lock.withLock {
            if (disposed) return
            _loopState.tryEmit(state)
        }
    }

    override suspend fun shuffle(enabled: Boolean) {
        lock.withLock {
            if (disposed || currentPlaylist.isEmpty() || shuffleEnabled == enabled) return

            val currentItem = _currentMediaItem.value
            val rebuilt = if (enabled) {
                if (currentItem != null) {
                    val remaining = originalPlaylist.toMutableList()
                    val index = remaining.indexOfFirst { it === currentItem }.takeIf { it >= 0 }
                        ?: remaining.indexOfFirst { it.url == currentItem.url }
                    if (index >= 0) remaining.removeAt(index)
                    listOf(currentItem) + remaining.shuffled()
                } else {
                    originalPlaylist.shuffled()
                }
            } else {
                originalPlaylist.toList()
            }

            currentPlaylist.clear()
            currentPlaylist.addAll(rebuilt)
            shuffleEnabled = enabled
            _shuffleMode.tryEmit(enabled)
            _playlist.tryEmit(currentPlaylist.toList())

            currentIndex = if (currentItem == null) 0 else {
                currentPlaylist.indexOfFirst { it === currentItem }.takeIf { it >= 0 }
                    ?: currentPlaylist.indexOfFirst { it.url == currentItem.url }.coerceAtLeast(0)
            }
            _currentMediaItem.tryEmit(currentPlaylist.getOrNull(currentIndex))
        }
    }

    override suspend fun replacePlaylistKeepingCurrent(
        expectedUrls: List<String>,
        playlist: List<MediaItem>,
    ): Boolean = lock.withLock {
        if (disposed) return@withLock false
        val currentUrls = currentPlaylist.map { it.url }
        if (currentUrls != expectedUrls &&
            (!shuffleEnabled || currentUrls.sorted() != expectedUrls.sorted())) return@withLock false
        val current = _currentMediaItem.value ?: return@withLock false
        if (playlist.none { it.url == current.url }) return@withLock false
        originalPlaylist.clear()
        originalPlaylist.addAll(playlist)
        val expanded = if (shuffleEnabled) {
            val remaining = playlist.toMutableList()
            remaining.removeAt(remaining.indexOfFirst { it.url == current.url })
            listOf(current) + remaining.shuffled()
        } else playlist
        currentPlaylist.clear()
        currentPlaylist.addAll(expanded)
        currentIndex = currentPlaylist.indexOfFirst { it.url == current.url }
        // Do not touch engine, generation, position or playback state.
        _playlist.value = currentPlaylist.toList()
        // A very short seed may end before pagination finishes. Explicit pause/stop
        // change the state, so only a natural queue end can resume the new successor.
        if (_playerState.value == PlayerState.COMPLETED && currentIndex < currentPlaylist.lastIndex) {
            playIndexLocked(currentIndex + 1)
        }
        true
    }

    override suspend fun load(
        playlist: List<MediaItem>,
        autoPlay: Boolean,
        startPosition: Int
    ) {
        lock.withLock {
            if (disposed) return

            cancelPendingSkipLocked()
            originalPlaylist.clear()
            generation++
            originalPlaylist.addAll(playlist)
            currentPlaylist.clear()
            currentPlaylist.addAll(playlist)
            shuffleEnabled = false
            _shuffleMode.tryEmit(false)
            _playlist.tryEmit(currentPlaylist.toList())

            engine.stop()
            engineHasMedia = false
            pendingStartMs = 0L
            _position.tryEmit(Duration.ZERO)
            _bufferingPosition.tryEmit(Duration.ZERO)

            if (currentPlaylist.isEmpty()) {
                currentIndex = -1
                _currentMediaItem.tryEmit(null)
                _playerState.tryEmit(PlayerState.IDLE)
                _duration.tryEmit(Duration.ZERO)
                mediaSession.update(null, 0.0, 0.0, false)
                return
            }

            currentIndex = startPosition.coerceIn(0, currentPlaylist.lastIndex)
            _currentMediaItem.tryEmit(currentPlaylist[currentIndex])
            _duration.tryEmit(currentPlaylist[currentIndex].duration)
            mediaSession.update(
                _currentMediaItem.value,
                0.0,
                _duration.value.inWholeMilliseconds / 1000.0,
                false)

            if (autoPlay) {
                loadCurrentIntoEngineLocked(autoPlay = true)
            } else {
                _playerState.tryEmit(PlayerState.PAUSED)
            }
        }
    }

    override suspend fun addMediaItem(mediaItem: MediaItem) {
        lock.withLock {
            if (disposed) return
            originalPlaylist.add(mediaItem)
            currentPlaylist.add(mediaItem)
            _playlist.tryEmit(currentPlaylist.toList())
            if (currentIndex == -1) {
                currentIndex = 0
                _currentMediaItem.tryEmit(currentPlaylist.firstOrNull())
            }
        }
    }

    override suspend fun addMediaItems(mediaItems: List<MediaItem>) {
        lock.withLock {
            if (disposed || mediaItems.isEmpty()) return
            originalPlaylist.addAll(mediaItems)
            currentPlaylist.addAll(mediaItems)
            _playlist.tryEmit(currentPlaylist.toList())
            if (currentIndex == -1) {
                currentIndex = 0
                _currentMediaItem.tryEmit(currentPlaylist.firstOrNull())
            }
        }
    }

    override suspend fun insertMediaItemAtNextIndex(mediaItem: MediaItem) {
        lock.withLock {
            if (disposed) return
            val insertIndex = if (currentIndex >= 0) currentIndex + 1 else 0
            originalPlaylist.add(insertIndex.coerceAtMost(originalPlaylist.size), mediaItem)
            currentPlaylist.add(insertIndex, mediaItem)
            _playlist.tryEmit(currentPlaylist.toList())
            if (currentIndex == -1) {
                currentIndex = 0
                _currentMediaItem.tryEmit(currentPlaylist.firstOrNull())
            }
        }
    }

    override suspend fun removeMediaItem(mediaItem: MediaItem) {
        lock.withLock {
            if (disposed || currentPlaylist.isEmpty()) return

            originalPlaylist.removeAll { it.url == mediaItem.url }
            val currentUrl = _currentMediaItem.value?.url
            val removedCurrent = currentUrl == mediaItem.url
            if (removedCurrent) {
                cancelPendingSkipLocked()
                generation++
            }
            val removalIndices = currentPlaylist.withIndex()
                .filter { it.value.url == mediaItem.url }
                .map { it.index }
            if (removalIndices.isEmpty()) return

            // Items before the playing one shift it down, so keep the index on the same item.
            val removedBefore = removalIndices.count { it < currentIndex }
            removalIndices.asReversed().forEach { currentPlaylist.removeAt(it) }

            if (currentPlaylist.isEmpty()) {
                currentIndex = -1
                _currentMediaItem.tryEmit(null)
                engine.stop()
                engineHasMedia = false
                _playerState.tryEmit(PlayerState.IDLE)
            } else if (removedCurrent) {
                currentIndex = currentIndex.coerceIn(0, currentPlaylist.lastIndex)
                _currentMediaItem.tryEmit(currentPlaylist[currentIndex])
                engine.stop()
                engineHasMedia = false
                _playerState.tryEmit(PlayerState.IDLE)
            } else {
                currentIndex = (currentIndex - removedBefore).coerceIn(0, currentPlaylist.lastIndex)
            }

            _playlist.tryEmit(currentPlaylist.toList())
        }
    }

    override suspend fun moveMediaItem(fromIndex: Int, toIndex: Int) {
        lock.withLock {
            if (disposed) return
            if (fromIndex !in currentPlaylist.indices || toIndex !in currentPlaylist.indices || fromIndex == toIndex) return

            val item = currentPlaylist.removeAt(fromIndex)
            currentPlaylist.add(toIndex, item)

            if (!shuffleEnabled && fromIndex in originalPlaylist.indices && toIndex in originalPlaylist.indices) {
                originalPlaylist.add(toIndex, originalPlaylist.removeAt(fromIndex))
            }

            val currentUrl = _currentMediaItem.value?.url
            currentIndex = currentUrl?.let { url ->
                currentPlaylist.indexOfFirst { it.url == url }.takeIf { it >= 0 }
            } ?: currentIndex.coerceIn(0, currentPlaylist.lastIndex)

            _playlist.tryEmit(currentPlaylist.toList())
            _currentMediaItem.tryEmit(currentPlaylist.getOrNull(currentIndex))
        }
    }

    override suspend fun skipToNext() {
        lock.withLock {
            if (disposed || currentPlaylist.isEmpty()) return
            skipToIndexLocked((currentIndex + 1).coerceAtMost(currentPlaylist.lastIndex))
        }
    }

    override suspend fun continueAfterQueueEnd(expectedUrl: String) {
        lock.withLock {
            if (!disposed && _playerState.value == PlayerState.COMPLETED &&
                _currentMediaItem.value?.url == expectedUrl && currentIndex < currentPlaylist.lastIndex) {
                playIndexLocked(currentIndex + 1)
            }
        }
    }

    override suspend fun skipToPrevious() {
        lock.withLock {
            if (disposed || currentPlaylist.isEmpty()) return
            skipToIndexLocked((currentIndex - 1).coerceAtLeast(0))
        }
    }

    override suspend fun jumpTo(index: Int) {
        lock.withLock {
            if (disposed || index !in currentPlaylist.indices) return
            playIndexLocked(index)
        }
    }

    override suspend fun setVolume(volume: Float) {
        lock.withLock {
            if (disposed) return
            val clamped = volume.coerceIn(0f, 1f)
            engine.setVolume(clamped)
            _volume.tryEmit(clamped)
        }
    }

    override suspend fun setPlaybackSpeed(speed: Float) {
        lock.withLock {
            if (disposed) return
            val clamped = speed.coerceIn(0.25f, 4f)
            engine.setRate(clamped)
            _playbackSpeed.tryEmit(clamped)
        }
    }

    override fun isDisposed(): Boolean = disposed

    override fun dispose() {
        if (disposed) return
        disposed = true
        engine.setListener(null)
        engine.destroy()
        mediaSession.deactivate()
        mediaSession.clear()
        scope.cancel()

        originalPlaylist.clear()
        currentPlaylist.clear()
        currentIndex = -1
        engineHasMedia = false

        _playerState.tryEmit(PlayerState.IDLE)
        _currentMediaItem.tryEmit(null)
        _playlist.tryEmit(emptyList())
        _duration.tryEmit(Duration.ZERO)
        _position.tryEmit(Duration.ZERO)
        _bufferingPosition.tryEmit(Duration.ZERO)
    }
}
