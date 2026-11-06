/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.modules.downloads

import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadAdmissionTest {
    private fun track(id: String) = MetadataTrack(id, "Song", 1000, null, null, emptyList(),
        null, null, null, null, null, null)

    @Test fun overlappingPlaylistsCreateOnlyOneTaskPerTrack() {
        var nextId = 0
        val first = admitDownloads(DownloadSnapshot(), listOf(track("a"), track("a"), track("b")), emptySet(),
            newId = { (++nextId).toString() }, verifiedPath = { null })
        assertEquals(2, first.changes.size)
        val next = admitDownloads(DownloadSnapshot(first.changes.values.toList()), listOf(track("a"), track("b"), track("c")),
            emptySet(), newId = { (++nextId).toString() }, verifiedPath = { null })
        assertEquals(listOf("c"), next.changes.values.map { it.track?.id })
        assertEquals(3, nextId)
    }

    @Test fun verifiedCatalogIsReusedEvenWithoutHistoryButDeletedFileIsQueued() {
        val snapshot = DownloadSnapshot(catalog = mapOf("exists" to "/saved.m4a", "deleted" to "/deleted.m4a"))
        var nextId = 0
        val admission = admitDownloads(snapshot, listOf(track("exists"), track("deleted")), emptySet(),
            newId = { (++nextId).toString() }, verifiedPath = { it?.takeIf { path -> path == "/saved.m4a" } })
        assertEquals(setOf("exists"), admission.completed)
        assertEquals(DownloadStatus.Completed, admission.changes.values.first { it.track?.id == "exists" }.status)
        assertEquals(DownloadStatus.Queued, admission.changes.values.first { it.track?.id == "deleted" }.status)
        assertFalse("deleted" in admission.catalog)
    }

    @Test fun staleCompletedRowCannotPinBatchAtOneHundredPercent() {
        val original = DownloadItem("task", "Song", "Artist", null, DownloadStatus.Completed,
            progress = 1f, track = track("deleted"), filePath = "/deleted.m4a")
        val admission = admitDownloads(DownloadSnapshot(listOf(original), mapOf("deleted" to "/deleted.m4a")),
            listOf(track("deleted")), emptySet(), newId = { error("Reuse the existing row") }, verifiedPath = { null })
        val queued = admission.changes.getValue("task")
        assertEquals(DownloadStatus.Queued, queued.status)
        assertEquals(0f, queued.progress)
        assertTrue(admission.completed.isEmpty())
        assertTrue(admission.catalog.isEmpty())
    }

    @Test fun failedTaskStillUnwindingCannotBeReplacedBeforeNativeAcknowledgement() {
        val original = DownloadItem("task", "Song", "Artist", null, DownloadStatus.Failed("network"), track = track("a"))
        val admission = admitDownloads(DownloadSnapshot(listOf(original)), listOf(track("a")), setOf("task"),
            newId = { error("Must not create a duplicate active attempt") }, verifiedPath = { null })
        assertTrue(admission.changes.isEmpty())
    }
}
