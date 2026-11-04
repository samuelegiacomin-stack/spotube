package org.spotube.spotube.modules.library.local_tracks.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import org.spotube.spotube.modules.settings.UserSettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileSystemMediaScannerTest {
    private fun scenario(test: suspend (Path) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("spotube-local-media").toOkioPath()
        try { test(root) } finally {
            val resolved = java.nio.file.Path.of(root.toString()).toRealPath()
            val temp = java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
            check(resolved.parent == temp && resolved.fileName.toString().startsWith("spotube-local-media"))
            FileSystem.SYSTEM.deleteRecursively(root)
        }
    }

    private fun write(path: Path, body: String = "audio") {
        FileSystem.SYSTEM.createDirectories(path.parent!!)
        FileSystem.SYSTEM.write(path) { writeUtf8(body) }
    }

    private fun track(path: Path) = LocalMediaTrack(path.toString(), "Metadata title", listOf("Me"), 123_000, "My album")

    @Test fun downloadedAndPersonalSongsAreDiscoveredWithoutTemporaryFiles() = scenario { root ->
        write(root / "Downloads" / "Artist - Song.M4A")
        write(root / "Music" / "My song #1.wav")
        write(root / "Downloads" / ".hidden.mp3")
        write(root / "Downloads" / ".transfer.part")
        write(root / "Downloads" / "cover.jpg")
        val scanner = FileSystemMediaScanner(FileSystem.SYSTEM, ::track)
        val folders = scanner.discover(listOf(root.toString()))
        assertEquals(listOf("Downloads", "Music"), folders.map { it.name })
        assertEquals(2, folders.sumOf { it.trackCount })
        assertTrue(folders.flatMap { it.tracks }.all { it.name == "Metadata title" && it.durationMs == 123_000L })
    }

    @Test fun overlappingRootsAndFileURLsDoNotDuplicateSongs() = scenario { root ->
        write(root / "Downloads" / "Song #1.m4a")
        val scanner = FileSystemMediaScanner(FileSystem.SYSTEM, ::track)
        val fileUrl = java.nio.file.Path.of((root / "Downloads").toString()).toUri().toString()
        val folders = scanner.discover(listOf(root.toString(), (root / "Downloads").toString(), fileUrl))
        assertEquals(1, folders.sumOf { it.trackCount })
    }

    @Test fun unreadableMetadataFallsBackToFilenameAndOtherRootsStillWork() = scenario { root ->
        write(root / "Personal song.flac")
        val scanner = FileSystemMediaScanner(FileSystem.SYSTEM, { throw IllegalStateException("No tags") })
        val folders = scanner.discover(listOf((root / "missing").toString(), root.toString()))
        assertEquals("Personal song", folders.single().tracks.single().name)
    }

    @Test fun fileURLWithSpacesAndSpecialCharactersIsDecoded() = scenario { root ->
        val folder = root / "My music #1"
        write(folder / "My song.m4a")
        val fileUrl = java.nio.file.Path.of(folder.toString()).toUri().toString()
        val scanner = FileSystemMediaScanner(FileSystem.SYSTEM, ::track)
        assertEquals(1, scanner.discover(listOf(fileUrl)).sumOf { it.trackCount })
    }

    @Test fun completedDownloadsAppearAndDeletedFilesDisappearOnRefresh() = scenario { root ->
        val scanner = FileSystemMediaScanner(FileSystem.SYSTEM, ::track)
        assertTrue(scanner.discover(listOf(root.toString())).isEmpty())
        val song = root / "download.m4a"
        write(song)
        assertEquals(1, scanner.discover(listOf(root.toString())).single().trackCount)
        FileSystem.SYSTEM.delete(song)
        assertTrue(scanner.discover(listOf(root.toString())).isEmpty())
    }

    @Test fun unchangedSongsReuseMetadataAndModifiedFilesAreReadAgain() = scenario { root ->
        val song = root / "song.wav"
        write(song)
        var reads = 0
        val scanner = FileSystemMediaScanner(FileSystem.SYSTEM, { reads++; track(it) })
        scanner.discover(listOf(root.toString()))
        scanner.discover(listOf(root.toString()))
        assertEquals(1, reads)
        write(song, "different audio length")
        scanner.discover(listOf(root.toString()))
        assertEquals(2, reads)
    }

    @Test fun scanHonoursFileLimit() = scenario { root ->
        repeat(5) { write(root / "song-$it.mp3") }
        val scanner = FileSystemMediaScanner(FileSystem.SYSTEM, ::track, maxFiles = 2)
        assertEquals(2, scanner.discover(listOf(root.toString())).sumOf { it.trackCount })
    }

    @Test fun cancellationIsNotSwallowedAsMissingMetadata() = scenario { root ->
        write(root / "song.wav")
        val scanner = FileSystemMediaScanner(FileSystem.SYSTEM, { throw CancellationException("cancel") })
        assertFailsWith<CancellationException> { scanner.discover(listOf(root.toString())) }
    }

    @Test fun defaultDownloadFolderIsAlwaysIndexed() {
        assertEquals(listOf("/app/Downloads"), configuredLocalMediaRoots(
            UserSettings(enableMusicCaching = false), "/app/Downloads", null))
    }

    @Test fun customDownloadsPersonalFoldersAndEnabledCacheAreDeduplicated() {
        assertEquals(listOf("/default", "/chosen", "/personal", "/cache"), configuredLocalMediaRoots(
            UserSettings(overloadedDownloadFolder = "/chosen/", localMediaFolders = listOf("/chosen", " /personal/ "),
                enableMusicCaching = true, cacheFolder = "/cache"), "/default", "/default-cache"))
    }

    @Test fun disabledCacheIsNotAddedToLocalMusic() {
        assertEquals(listOf("/default"), configuredLocalMediaRoots(
            UserSettings(enableMusicCaching = false, cacheFolder = "/cache", overloadedDownloadFolder = " "),
            "/default", "/default-cache"))
    }

    @Test fun actualWaveFileRetainsItsTagsAndDuration() = scenario { root ->
        val song = root / "My music #1.wav"
        val samples = 16_000
        val wave = java.nio.ByteBuffer.allocate(44 + samples).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(36 + samples).put("WAVEfmt ".toByteArray())
        wave.putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
        wave.put("data".toByteArray()).putInt(samples).put(ByteArray(samples))
        Files.write(java.nio.file.Path.of(song.toString()), wave.array())
        uniffi.compose_app.writeAudioMetadata(song.toString(), "My own song", "Me", "Personal", null, null, null)
        val scanner = FileSystemMediaScanner(FileSystem.SYSTEM, { path ->
            val metadata = uniffi.compose_app.readAudioMetadata(path.toString())
            LocalMediaTrack(path.toString(), metadata.title.orEmpty(), metadata.artists, metadata.durationMs, metadata.album)
        })
        val result = scanner.discover(listOf(root.toString())).single().tracks.single()
        assertEquals("My own song", result.name)
        assertEquals(listOf("Me"), result.artists)
        assertEquals("Personal", result.album)
        assertEquals(1_000L, result.durationMs)
    }
}
