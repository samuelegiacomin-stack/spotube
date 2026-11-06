/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.core.playback

import co.touchlab.kermit.Logger
import kotlinx.coroutines.*
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
    private var fillJob: Job? = null

    suspend fun play(
        collection: QueueCollectionEntry,
        shuffle: Boolean,
        startTrack: MetadataTrack? = null,
        fetch: suspend (PaginationStrategy?) -> PaginationResult<MetadataTrack>?,
    ): Boolean {
        if (!queue.supportsProgressiveCollections) return false
        fillJob?.cancel()
        val seed = if (startTrack != null) {
            filterPlayable(listOf(startTrack))
        } else {
            val first = fetch(PaginationStrategy.Offset(0, 100)) ?: return false
            val candidates = if (shuffle && first.totalCount > first.items.size &&
                first.nextPagination is PaginationStrategy.Offset) {
                val offset = kotlin.random.Random.nextInt(first.totalCount)
                filterPlayable(fetch(PaginationStrategy.Offset(offset, 1))?.items.orEmpty())
                    .ifEmpty { filterPlayable(first.items) }
            } else filterPlayable(first.items)
            if (shuffle) candidates.shuffled().take(1) else candidates
        }
        if (seed.isEmpty()) return false
        queue.load(seed, autoPlay = true, collectionEntry = collection)
        // Pagination is independent of the initial play request. A new collection cancels
        // this work; the backend also rejects expansion if the user edited/replaced its queue.
        fillJob = fillScope.launch {
            try {
                val entries = filterPlayable(loadCollectionPages(fetch))
                val expanded = if (shuffle) {
                    val current = seed.first()
                    val remaining = entries.toMutableList()
                    val selectedIndex = remaining.indexOfFirst { it.trackId() == current.trackId() }
                    if (selectedIndex >= 0) remaining.removeAt(selectedIndex)
                    listOf(current) + remaining.shuffled()
                } else entries
                if (expanded.isNotEmpty()) queue.completeCollection(seed, expanded, collection)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Logger.w(error) { "Could not finish loading the playing collection" }
            }
        }
        return true
    }


    private fun QueueEntry.trackId() = (this as? QueueEntry.StreamingTrack)?.track?.id
}
