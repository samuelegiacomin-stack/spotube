/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.modules.downloads

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadStoreTest {
    private val fs = FileSystem.SYSTEM

    private fun scenario(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("spotube-download-store").toOkioPath()
        try { test(directory) } finally { fs.deleteRecursively(directory) }
    }

    private fun track(id: String) = MetadataTrack(id, "same title", 1000, null, null, emptyList(),
        null, null, null, null, null, null)

    private fun item(id: String, state: DownloadStatus, file: Path? = null) = DownloadItem(
        id, "same title", "same artist", null, status = state, track = track(id), filePath = file?.toString())

    @Test fun catalogSurvivesRestartAndClearedHistory() = scenario { directory ->
        val audio = directory / "published.m4a"
        fs.write(audio) { writeUtf8("published audio") }
        val firstProcess = DownloadStore(fs, directory)
        firstProcess.write(DownloadSnapshot(listOf(item("track", DownloadStatus.Completed, audio)), mapOf("track" to audio.toString())))
        // Clearing UI rows keeps the durable track ID -> path catalog.
        firstProcess.write(firstProcess.read().copy(items = emptyList()))
        val secondProcess = DownloadStore(fs, directory)
        val restored = secondProcess.restore(secondProcess.read())
        assertTrue(restored.items.isEmpty())
        assertEquals(mapOf("track" to audio.toString()), restored.catalog)
        assertEquals(audio.toString(), secondProcess.verifiedPath(restored.catalog["track"]))
        assertFalse(fs.exists(directory / "downloads-v1.tmp"))
    }

    @Test fun missingEmptyAndDirectoryEntriesDoNotClaimCompletion() = scenario { directory ->
        val missing = directory / "deleted.m4a"
        val empty = directory / "empty.m4a"
        fs.write(empty) {}
        val store = DownloadStore(fs, directory)
        val restored = store.restore(DownloadSnapshot(
            listOf(item("deleted", DownloadStatus.Completed, missing)),
            mapOf("deleted" to missing.toString(), "empty" to empty.toString(), "directory" to directory.toString())))
        assertTrue(restored.catalog.isEmpty())
        assertEquals(DownloadStatus.Queued, restored.items.single().status)
        assertNull(restored.items.single().filePath)
    }

    @Test fun transferIntentRestoresWithStableNativeIdAndPlan() = scenario { directory ->
        val pending = item("native-id", DownloadStatus.Downloading, directory / "unique-attempt.m4a")
            .copy(transferUrl = "https://example.invalid/temporary", downloadedBytes = 250, progress = 0.4f)
        DownloadStore(fs, directory).write(DownloadSnapshot(listOf(pending)))
        val store = DownloadStore(fs, directory)
        val restored = store.restore(store.read()).items.single()
        assertEquals(pending.id, restored.id)
        assertEquals(pending.filePath, restored.filePath)
        assertEquals(pending.transferUrl, restored.transferUrl)
        assertEquals(DownloadStatus.Queued, restored.status)
        assertEquals(0L, restored.downloadedBytes)
    }

    @Test fun corruptJournalIsNotSilentlyReplaced() = scenario { directory ->
        val journal = directory / "downloads-v1.json"
        fs.write(journal) { writeUtf8("{corrupt") }
        assertFails { DownloadStore(fs, directory).read() }
        assertEquals("{corrupt", fs.read(journal) { readUtf8() })
    }

    @Test fun ownIosPathsRelocateButExternalSelectionsRemainUntouched() {
        val oldRoot = "/var/mobile/Containers/Data/Application/OLD"
        val newRoot = "/var/mobile/Containers/Data/Application/NEW"
        val current = "$newRoot/Documents/Spotube/Downloads"
        assertEquals("$newRoot/Documents/Spotube/Downloads/song.m4a",
            relocateIosDownloadPath("$oldRoot/Documents/Spotube/Downloads/song.m4a", current))
        assertEquals("$newRoot/Library/Application Support/Spotube/song.m4a",
            relocateIosDownloadPath("$oldRoot/Library/Application Support/Spotube/song.m4a", current))
        for (external in listOf("/private/iCloud/Documents/song.m4a", "$oldRoot/Documents/UserChosen/song.m4a")) {
            assertEquals(external, relocateIosDownloadPath(external, current))
        }
    }

    @Test fun equalTitlesOpaqueIdsAndUnicodeCannotCollideOrExceedFilenameByteLimit() {
        val title = "🎵é音".repeat(500)
        val first = downloadFilename("provider/a", title, title, "m4a", "1234567890abcdef")
        val second = downloadFilename("provider:a", title, title, "m4a", "1234567890abcdef")
        assertNotEquals(first, second)
        assertTrue(first.encodeToByteArray().size <= 255)
        assertFalse(first.contains('/'))
        assertFalse(first.contains('\\'))
        assertTrue(downloadFilename("track", "artist", "title", "../../bad").endsWith(".audio"))
        assertNotEquals(downloadFilename("track", "artist", "title", "m4a", "attempt1"),
            downloadFilename("track", "artist", "title", "m4a", "attempt2"))
    }
}
