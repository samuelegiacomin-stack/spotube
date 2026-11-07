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

/** Prepare the immediate successor during listening, including manual skips early in a song. */
class NextTrackPrefetcher(player: AudioPlayerInterface, queue: AudioPlayerQueue, repository: StreamingUrlRepository, audioCache: NextTrackAudioCache) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    init {
        // Elapsed playback alone does not prove buffer headroom. Require actual buffered time
        // or a complete cache for the current track before spending bandwidth on its successor.
        val canWarm = combine(player.positionFlow, player.playerStateFlow, player.loopStateFlow) { position, state, loop ->
            state == PlayerState.PLAYING && loop != LoopState.ONE && position.inWholeMilliseconds >= 3_000
        }.distinctUntilChanged()
        // Resolving a URL is lighter than fetching audio. Start when playback begins
        // so an early manual skip can benefit too.
        val canResolve = combine(player.playerStateFlow, player.loopStateFlow) { state, loop ->
            state == PlayerState.PLAYING && loop != LoopState.ONE
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
        scope.launch {
            combine(canResolve, queue.currentQueueEntryFlow, queue.queueFlow) { ready, current, entries ->
                nextTrackForPrefetch(ready, current, entries)
            }.distinctUntilChangedBy { it?.id }.collectLatest { track ->
                if (track != null) refreshNextTrackUrl { forceRefresh ->
                    repository.resolveStreamInfo(track, forceRefresh = forceRefresh)
                }
            }
        }
    }
}

/** Refresh before the URL cache's 30s TTL; cancellation stops an obsolete successor. */
internal suspend fun refreshNextTrackUrl(resolve: suspend (forceRefresh: Boolean) -> Unit) {
    var forceRefresh = false
    while (currentCoroutineContext().isActive) {
        try {
            withTimeoutOrNull(10_000) { resolve(forceRefresh) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Logger.w(error) { "Next-track preparation unavailable" }
        }
        forceRefresh = true
        delay(20_000)
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
