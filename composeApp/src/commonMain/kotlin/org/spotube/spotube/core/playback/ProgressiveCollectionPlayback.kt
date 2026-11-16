/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.core.playback

import co.touchlab.kermit.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import org.spotube.spotube.core.audioplayer.*

/** Start from a known track/first page; expand the queue without reopening its audio. */
internal class ProgressiveCollectionPlayback(
    private val queue: AudioPlayerQueue,
    private val filterPlayable: suspend (List<MetadataTrack>) -> List<QueueEntry>,
    private val fillScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val requestNumber = MutableStateFlow(0L)
    private val startLock = Mutex()
    private var fillJob: Job? = null

    suspend fun play(
        collection: QueueCollectionEntry,
        shuffle: Boolean,
        startTrack: MetadataTrack? = null,
        fetch: suspend (PaginationStrategy?) -> PaginationResult<MetadataTrack>?,
    ): Boolean {
        if (!queue.supportsProgressiveCollections) return false
        val request = requestNumber.updateAndGet { it + 1 }
        val first = if (startTrack == null) fetch(PaginationStrategy.Offset(0, 100)) else null
        val seed = filterPlayable(startTrack?.let(::listOf) ?: first?.items.orEmpty()).toMutableList()
        var selectedIndex = 0
        if (shuffle && first != null && seed.isNotEmpty()) {
            val randomIndex = Random.nextInt(maxOf(first.totalCount, first.items.size))
            val selected = first.items.getOrNull(randomIndex) ?: if (first.nextPagination is PaginationStrategy.Offset) {
                try {
                    fetch(PaginationStrategy.Offset(randomIndex, 1))?.items?.firstOrNull()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Logger.w(error) { "Could not load the randomly selected track; using the first page" }
                    null
                }
            } else null
            val inSeed = seed.indexOfFirst { (it as? QueueEntry.StreamingTrack)?.track?.id == selected?.id }
            if (inSeed >= 0) {
                selectedIndex = inSeed
            } else {
                val extra = selected?.let { filterPlayable(listOf(it)).firstOrNull() }
                if (extra != null) {
                    selectedIndex = seed.size
                    seed.add(extra)
                } else {
                    selectedIndex = Random.nextInt(seed.size)
                }
            }
        }

        return startLock.withLock {
            if (request != requestNumber.value) return@withLock true
            if (seed.isEmpty()) return@withLock false
            fillJob?.cancelAndJoin()
            val expanding = startTrack != null || first?.nextPagination != null
            try {
                queue.loadCollection(seed, selectedIndex, collection, shuffle, expanding)
            } catch (error: Throwable) {
                queue.finishCollectionExpansion(seed)
                throw error
            }
            if (expanding) {
                fillJob = fillScope.launch {
                    try {
                        val entries = filterPlayable(loadCollectionPages(first, fetch))
                        if (entries.isNotEmpty()) queue.completeCollection(seed, entries, collection)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Logger.w(error) { "Could not finish loading the playing collection" }
                    }
                }.also { job -> job.invokeOnCompletion { queue.finishCollectionExpansion(seed) } }
            }
            true
        }
    }
}
