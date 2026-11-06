/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.core.playback

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.*
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import org.spotube.spotube.core.audioplayer.*
import org.spotube.spotube.core.server.FakeStreamingAudioPlayerQueue
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressiveCollectionPlaybackTest {
    private fun track(id: String) = MetadataTrack(id, id, 100000, null, null, emptyList(), null, null, null, null, null, null)
    private fun entries(tracks: List<MetadataTrack>): List<QueueEntry> =
        tracks.map { QueueEntry.StreamingTrack(it, "") }
    private class Queue : AudioPlayerQueue by FakeStreamingAudioPlayerQueue() {
        override val supportsProgressiveCollections = true
        val started = mutableListOf<List<QueueEntry>>()
        val completed = mutableListOf<Pair<QueueCollectionEntry, List<QueueEntry>>>()
        override suspend fun load(entries: List<QueueEntry>, autoPlay: Boolean, startPosition: Int, collectionEntry: QueueCollectionEntry?) {
            assertTrue(autoPlay)
            started += entries
        }
        override suspend fun completeCollection(seed: List<QueueEntry>, entries: List<QueueEntry>, collection: QueueCollectionEntry): Boolean {
            completed += collection to entries
            return true
        }
    }

    @Test fun firstPageStartsBeforeSlowRemainingPagesFinish() = runTest {
        val queue = Queue()
        val loader = ProgressiveCollectionPlayback(queue, ::entries, this)
        val release = CompletableDeferred<Unit>()
        val fetch: suspend (PaginationStrategy?) -> PaginationResult<MetadataTrack>? = {
            val page = it as PaginationStrategy.Offset
            if (page.offset > 0) release.await()
            val end = minOf(page.offset + page.limit, 250)
            PaginationResult((page.offset until end).map { track("$it") }, 250,
                if (end < 250) PaginationStrategy.Offset(end, page.limit) else null)
        }
        assertTrue(loader.play(QueueCollectionEntry.Playlist("main"), false, fetch = fetch))
        assertEquals(100, queue.started.single().size)
        runCurrent()
        assertTrue(queue.completed.isEmpty())
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(250, queue.completed.single().second.size)
        assertEquals(1, queue.started.size, "Completing pages must not issue a second play/load")
    }

    @Test fun selectedTrackStartsWithoutAnyCollectionRead() = runTest {
        val queue = Queue()
        val loader = ProgressiveCollectionPlayback(queue, ::entries, this)
        var reads = 0
        assertTrue(loader.play(QueueCollectionEntry.Playlist("main"), false, track("chosen")) {
            reads++
            PaginationResult(listOf(track("before"), track("chosen"), track("after")), 3, null)
        })
        assertEquals(0, reads)
        assertEquals("chosen", (queue.started.single().single() as QueueEntry.StreamingTrack).track.id)
        advanceUntilIdle()
        assertEquals(3, queue.completed.single().second.size)
    }

    @Test fun newerCollectionCancelsAnObsoleteFill() = runTest {
        val queue = Queue()
        val loader = ProgressiveCollectionPlayback(queue, ::entries, this)
        var cancelled = false
        loader.play(QueueCollectionEntry.Playlist("old"), false, track("old")) {
            try { awaitCancellation() } finally { cancelled = true }
        }
        runCurrent()
        loader.play(QueueCollectionEntry.Playlist("new"), false, track("new")) {
            PaginationResult(listOf(track("new")), 1, null)
        }
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals(listOf(QueueCollectionEntry.Playlist("new")), queue.completed.map { it.first })
    }

    @Test fun unsupportedBackendFallsBackWithoutReadingOrStarting() = runTest {
        val queue = FakeStreamingAudioPlayerQueue()
        val loader = ProgressiveCollectionPlayback(queue, ::entries, this)
        assertFalse(loader.play(QueueCollectionEntry.Playlist("main"), false) { error("No read") })
    }

    @Test fun shuffleKeepsDuplicatePlaylistOccurrencesAndTheSeedFirst() = runTest {
        val queue = Queue()
        val loader = ProgressiveCollectionPlayback(queue, ::entries, this)
        loader.play(QueueCollectionEntry.Playlist("main"), true, track("a")) {
            PaginationResult(listOf(track("a"), track("a"), track("b")), 3, null)
        }
        advanceUntilIdle()
        val ids = queue.completed.single().second.map { (it as QueueEntry.StreamingTrack).track.id }
        assertEquals("a", ids.first())
        assertEquals(listOf("a", "a", "b"), ids.sorted())
    }
}
