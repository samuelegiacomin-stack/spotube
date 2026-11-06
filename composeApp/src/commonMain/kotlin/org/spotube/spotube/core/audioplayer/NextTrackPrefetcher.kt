/* Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.audioplayer

import co.touchlab.kermit.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import org.spotube.spotube.core.server.StreamingUrlRepository
import org.spotube.spotube.core.server.NextTrackAudioCache
import org.spotube.spotube.core.server.isPlaybackProxyUrl

/** Resolve only the next streaming URL in the last 15 seconds, within its 30-second cache TTL. */
class NextTrackPrefetcher(player: AudioPlayerInterface, queue: AudioPlayerQueue, repository: StreamingUrlRepository, audioCache: NextTrackAudioCache) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    init {
        // Elapsed playback alone does not prove buffer headroom. Require actual buffered time
        // or a complete cache for the current track before spending bandwidth on its successor.
        val canWarm = combine(player.positionFlow, player.playerStateFlow, player.loopStateFlow) { position, state, loop ->
            state == PlayerState.PLAYING && loop != LoopState.ONE && position.inWholeMilliseconds >= 3_000
        }.distinctUntilChanged()
        val hasHeadroom = combine(player.bufferingPositionFlow, player.positionFlow) { buffered, position ->
            (buffered - position).inWholeMilliseconds >= 10_000
        }.distinctUntilChanged()
        scope.launch {
            combine(canWarm, queue.currentQueueEntryFlow, queue.queueFlow, hasHeadroom, audioCache.cacheChanges) { ready, current, entries, headroom, _ ->
                val next = nextTrackForPrefetch(ready, current, entries)
                val currentId = (current as? QueueEntry.StreamingTrack)?.track?.id
                next?.takeIf { currentId != null && audioCache.canPrepare(currentId, headroom) }
            }.distinctUntilChangedBy { it?.id }.collectLatest { track ->
                if (track != null) try {
                    withTimeoutOrNull(15_000) { audioCache.prepare(track) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) { Logger.w(e) { "Next-track audio preparation unavailable" } }
            }
        }
        val nearEnd = combine(player.positionFlow, player.durationFlow, player.playerStateFlow, player.loopStateFlow) {
            position, duration, state, loop ->
            val remaining = (duration - position).inWholeMilliseconds
            state == PlayerState.PLAYING && loop != LoopState.ONE && duration.inWholeMilliseconds > 0 && remaining in 0..15_000
        }.distinctUntilChanged()
        scope.launch {
            combine(nearEnd, queue.currentQueueEntryFlow, queue.queueFlow) { ready, current, entries ->
                nextTrackForPrefetch(ready, current, entries)
            }.distinctUntilChangedBy { it?.id }.collectLatest { track ->
                if (track != null) try {
                    withTimeoutOrNull(10_000) { repository.resolveStreamInfo(track) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) { Logger.w(e) { "Next-track preparation unavailable" } }
            }
        }
    }
}

internal fun nextTrackForPrefetch(ready: Boolean, current: QueueEntry?, entries: List<QueueEntry>): MetadataTrack? {
    if (!ready || current !is QueueEntry.StreamingTrack) return null
    val index = entries.indexOfFirst { it.url == current.url }
    if (index < 0) return null
    val next = entries.getOrNull(index + 1) as? QueueEntry.StreamingTrack ?: return null
    // Explicit remote/Jam URLs already identify their media; only warm our own proxy path.
    return next.track.takeIf { isPlaybackProxyUrl(next.url) }
}
