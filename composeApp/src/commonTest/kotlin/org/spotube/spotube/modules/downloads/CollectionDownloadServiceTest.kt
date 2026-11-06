/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.modules.downloads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionDownloadServiceTest {
    private fun track(id: String) = MetadataTrack(
        id, id, 100_000, null, null, emptyList(), null, null, null, null, null, null)

    private fun item(id: String, status: DownloadStatus, progress: Float = 0f) = DownloadItem(
        id = "download-$id", title = id, artists = "", album = null,
        track = track(id), status = status, progress = progress)

    private fun page(vararg ids: String) = PaginationResult(ids.map(::track), ids.size, null)

    /** Models the manager's atomic bulk admission and independent, verified file catalog. */
    private inner class Queue(initial: List<DownloadItem> = emptyList()) {
        val rows = MutableStateFlow(initial)
        val catalog = MutableStateFlow(initial.filter { it.status == DownloadStatus.Completed }
            .map { it.track!!.id }.toSet())
        val availableFiles = catalog.value.toMutableSet()
        val admissions = mutableListOf<List<String>>()
        val transfers = mutableListOf<String>()

        suspend fun enqueueAll(tracks: List<MetadataTrack>): Set<String> {
            admissions += tracks.map { it.id }
            val verified = catalog.value.intersect(availableFiles)
            val next = rows.value.associateBy { it.track!!.id }.toMutableMap()
            tracks.forEach { song ->
                val old = next[song.id]
                if (old?.status == DownloadStatus.Queued || old?.status == DownloadStatus.Downloading) return@forEach
                next[song.id] = if (song.id in verified) item(song.id, DownloadStatus.Completed, 1f)
                else {
                    transfers += song.id
                    item(song.id, DownloadStatus.Queued)
                }
            }
            rows.value = next.values.toList()
            catalog.value = verified
            return verified.intersect(tracks.map { it.id }.toSet())
        }

        fun publish(vararg items: DownloadItem) {
            rows.value = items.toList()
            val completed = items.filter { it.status == DownloadStatus.Completed }.map { it.track!!.id }
            availableFiles += completed
            catalog.value += completed
        }

        fun service(scope: CoroutineScope) = CollectionDownloadService(rows, ::enqueueAll, scope, catalog)
    }

    @Test fun countsCompletedAndPartialTracksWithEqualWeight() {
        val state = collectionDownloadProgress(listOf(
            CollectionDownloadTrack(DownloadStatus.Completed, 0f),
            CollectionDownloadTrack(DownloadStatus.Downloading, 0.5f),
            CollectionDownloadTrack(DownloadStatus.Queued, 1f)))
        assertEquals(0.5f, state.progress)
        assertEquals(50, state.percent)
        assertEquals(1, state.completedCount)
        assertEquals(2, state.activeCount)
        assertTrue(state.isBusy)
    }

    @Test fun failedAndCancelledSongsNeverContributeCompletedBytes() {
        val state = collectionDownloadProgress(listOf(
            CollectionDownloadTrack(DownloadStatus.Completed, 1f),
            CollectionDownloadTrack(DownloadStatus.Failed("tagging failed"), 1f),
            CollectionDownloadTrack(DownloadStatus.Cancelled, 0.99f)))
        assertEquals(33, state.percent)
        assertEquals(1, state.failedCount)
        assertEquals(1, state.cancelledCount)
        assertFalse(state.isBusy)
    }

    @Test fun onlyCompletedSongsMayReportOneHundredPercent() {
        assertEquals(99, collectionDownloadProgress(listOf(
            CollectionDownloadTrack(DownloadStatus.Downloading, 1f))).percent)
        val completed = collectionDownloadProgress(listOf(CollectionDownloadTrack(DownloadStatus.Completed)))
        assertEquals(100, completed.percent)
        assertFalse(completed.isBusy)
        assertEquals(0, collectionDownloadProgress(emptyList()).percent)
        assertEquals(0, collectionDownloadProgress(listOf(
            CollectionDownloadTrack(DownloadStatus.Downloading, Float.NaN))).percent)
    }

    @Test fun followsAllPagesAndDeduplicatesAcrossPageBoundaries() = runTest {
        val requested = mutableListOf<PaginationStrategy?>()
        val second = PaginationStrategy.Offset(2, 2)
        val third = PaginationStrategy.Cursor("last", 2)
        val tracks = loadAllDownloadTracks { pagination ->
            requested += pagination
            when (pagination) {
                null -> PaginationResult(listOf(track("a"), track("b")), 4, second)
                second -> PaginationResult(listOf(track("b"), track("c")), 4, third)
                third -> page("d")
                else -> error("Unexpected page")
            }
        }
        assertEquals(listOf(null, second, third), requested)
        assertEquals(listOf("a", "b", "c", "d"), tracks.map { it.id })
    }

    @Test fun rejectsMissingOrRepeatingPages() = runTest {
        assertFailsWith<IllegalStateException> { loadAllDownloadTracks { null } }
        val repeated = PaginationStrategy.Continuation("again")
        assertFailsWith<IllegalStateException> {
            loadAllDownloadTracks { PaginationResult(listOf(track("a")), 10, repeated) }
        }
    }

    @Test fun progressContinuesAfterBulkAdmissionReturnsAndObserversLeave() = runTest {
        val queue = Queue()
        val service = queue.service(backgroundScope)
        val observer = backgroundScope.launch { service.observe("favorites").collect() }
        service.downloadAll("favorites") { page("a", "b") }
        runCurrent()
        assertFalse(service.currentProgress("favorites").isPreparing)
        assertTrue(service.currentProgress("favorites").isBusy)
        observer.cancel()
        queue.publish(item("a", DownloadStatus.Completed), item("b", DownloadStatus.Downloading, 0.5f))
        runCurrent()
        assertEquals(75, service.currentProgress("favorites").percent)
        queue.publish(item("a", DownloadStatus.Completed), item("b", DownloadStatus.Completed))
        runCurrent()
        assertEquals(100, service.currentProgress("favorites").percent)
        assertFalse(service.currentProgress("favorites").isBusy)
    }

    @Test fun overlappingCollectionsAdmitOneBulkPerCollectionAndShareTrackProgress() = runTest {
        val queue = Queue(listOf(item("shared", DownloadStatus.Downloading, 0.5f)))
        val service = queue.service(backgroundScope)
        service.downloadAll("playlist") { page("shared", "a", "a") }
        service.downloadAll("favorites") { page("shared", "a", "b") }
        runCurrent()
        assertEquals(listOf(listOf("shared", "a"), listOf("shared", "a", "b")), queue.admissions)
        assertEquals(listOf("a", "b"), queue.transfers)
        assertEquals(2, service.currentProgress("playlist").totalCount)
        assertEquals(3, service.currentProgress("favorites").totalCount)
        queue.publish(item("shared", DownloadStatus.Completed), item("a", DownloadStatus.Completed), item("b", DownloadStatus.Completed))
        runCurrent()
        assertEquals(100, service.currentProgress("playlist").percent)
        assertEquals(100, service.currentProgress("favorites").percent)
    }

    @Test fun durableCatalogCountsCompletedTracksAfterHistoryWasCleared() = runTest {
        val queue = Queue(listOf(item("saved", DownloadStatus.Completed)))
        queue.rows.value = emptyList()
        val service = queue.service(backgroundScope)
        service.downloadAll("favorites") { page("saved", "new") }
        runCurrent()
        // Even catalog hits must reach bulk admission, which validates actual files.
        assertEquals(listOf(listOf("saved", "new")), queue.admissions)
        assertEquals(listOf("new"), queue.transfers)
        assertEquals(50, service.currentProgress("favorites").percent)
        queue.publish(item("new", DownloadStatus.Completed))
        runCurrent()
        queue.rows.value = emptyList()
        runCurrent()
        assertEquals(2, service.currentProgress("favorites").completedCount)
        assertEquals(100, service.currentProgress("favorites").percent)
    }

    @Test fun externallyDeletedFileIsRevalidatedAndQueuedOnTheNextDownloadAll() = runTest {
        val queue = Queue(listOf(item("a", DownloadStatus.Completed)))
        val service = queue.service(backgroundScope)
        service.downloadAll("favorites") { page("a") }
        runCurrent()
        assertEquals(100, service.currentProgress("favorites").percent)
        queue.availableFiles.remove("a") // UI history/catalog are now stale.
        service.downloadAll("favorites") { page("a") }
        runCurrent()
        assertEquals(2, queue.admissions.size)
        assertEquals(listOf("a"), queue.transfers)
        assertEquals(0, service.currentProgress("favorites").percent)
        assertEquals(0, service.currentProgress("favorites").completedCount)
        assertTrue(service.currentProgress("favorites").isBusy)
        queue.publish(item("a", DownloadStatus.Completed))
        runCurrent()
        assertEquals(100, service.currentProgress("favorites").percent)
    }

    @Test fun completedTracksAreNotPinnedAfterCatalogEvictionAndRetry() = runTest {
        val queue = Queue(listOf(item("a", DownloadStatus.Completed)))
        val service = queue.service(backgroundScope)
        service.downloadAll("favorites") { page("a") }
        runCurrent()
        queue.catalog.value = emptySet()
        queue.rows.value = listOf(item("a", DownloadStatus.Downloading, 0.5f))
        runCurrent()
        assertEquals(50, service.currentProgress("favorites").percent)
        assertEquals(0, service.currentProgress("favorites").completedCount)
        assertTrue(service.currentProgress("favorites").isBusy)
    }

    @Test fun staleCompletedHistoryWithoutCatalogConfirmationCannotReportSuccess() = runTest {
        val rows = MutableStateFlow(listOf(item("a", DownloadStatus.Completed)))
        val service = CollectionDownloadService(rows, { emptySet() }, backgroundScope, MutableStateFlow(emptySet()))
        service.downloadAll("playlist") { page("a") }
        runCurrent()
        assertEquals(0, service.currentProgress("playlist").percent)
        assertTrue(service.currentProgress("playlist").isBusy)
    }

    @Test fun failureOnLaterPageAdmitsNothingAndAllowsRetry() = runTest {
        val queue = Queue()
        val service = queue.service(backgroundScope)
        service.downloadAll("favorites") { pagination ->
            if (pagination == null) PaginationResult(listOf(track("a")), 2, PaginationStrategy.Offset(1, 1))
            else error("Network failed")
        }
        runCurrent()
        assertTrue(queue.admissions.isEmpty())
        assertTrue(service.currentProgress("favorites").preparationFailed)
        assertFalse(service.currentProgress("favorites").isBusy)
        service.downloadAll("favorites") { page("a", "b") }
        runCurrent()
        assertFalse(service.currentProgress("favorites").preparationFailed)
        assertEquals(listOf("a", "b"), queue.transfers)
    }

    @Test fun repeatedTapsCannotRestartPreparationOrAnActiveBatch() = runTest {
        val queue = Queue()
        val service = queue.service(backgroundScope)
        var pageCalls = 0
        val loader: suspend (PaginationStrategy?) -> PaginationResult<MetadataTrack>? = { pageCalls++; page("a") }
        service.downloadAll("favorites", loader)
        service.downloadAll("favorites", loader)
        assertTrue(service.currentProgress("favorites").isPreparing)
        runCurrent()
        service.downloadAll("favorites", loader)
        runCurrent()
        assertEquals(1, pageCalls)
        assertEquals(1, queue.admissions.size)
    }

    @Test fun laggingFlowDoesNotMistakeOldFailureForTheNewAttempt() = runTest {
        val rows = MutableStateFlow(listOf(item("a", DownloadStatus.Failed("old failure"))))
        val service = CollectionDownloadService(rows, { emptySet() }, backgroundScope, MutableStateFlow(emptySet()))
        service.downloadAll("playlist") { page("a") }
        runCurrent()
        rows.value += item("unrelated", DownloadStatus.Queued)
        runCurrent()
        assertTrue(service.currentProgress("playlist").isBusy)
        assertEquals(0, service.currentProgress("playlist").failedCount)
        rows.value = listOf(item("a", DownloadStatus.Queued))
        runCurrent()
        rows.value = listOf(item("a", DownloadStatus.Failed("new failure")))
        runCurrent()
        assertFalse(service.currentProgress("playlist").isBusy)
        assertEquals(1, service.currentProgress("playlist").failedCount)
    }

    @Test fun removingAnActiveQueueItemDoesNotLeaveTheBatchBusyForever() = runTest {
        val queue = Queue(listOf(item("a", DownloadStatus.Downloading, 0.9f)))
        val service = queue.service(backgroundScope)
        service.downloadAll("playlist") { page("a") }
        runCurrent()
        queue.rows.value = emptyList()
        runCurrent()
        assertEquals(1, service.currentProgress("playlist").cancelledCount)
        assertEquals(0, service.currentProgress("playlist").percent)
        assertFalse(service.currentProgress("playlist").isBusy)
    }

    @Test fun bulkAdmissionFailureDoesNotLeaveAllSongsLoadingForever() = runTest {
        val service = CollectionDownloadService(MutableStateFlow(emptyList()), { error("Journal unavailable") },
            backgroundScope, MutableStateFlow(emptySet()))
        service.downloadAll("favorites") { page("a", "b") }
        runCurrent()
        assertEquals(2, service.currentProgress("favorites").failedCount)
        assertEquals(0, service.currentProgress("favorites").percent)
        assertFalse(service.currentProgress("favorites").isBusy)
    }
}
