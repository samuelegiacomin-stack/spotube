/* Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Source
import okio.Path.Companion.toOkioPath
import okio.buffer
import org.spotube.spotube.core.paths.Paths
import org.spotube.spotube.modules.settings.UserSettings
import org.spotube.spotube.modules.settings.UserSettingsSource
import java.nio.file.Files
import kotlin.test.*

class CachePublicationTest {
    @Test fun changingFolderDuringIndexReadCannotOverwriteAnotherFoldersIndex() = runBlocking {
        val root = Files.createTempDirectory("spotube-cache-folder-race").toOkioPath()
        val first = root / "first"
        val second = root / "second"
        val settings = object : UserSettingsSource {
            override val userSettings = MutableStateFlow(UserSettings(cacheFolder = first.toString(), enableMusicCaching = true))
        }
        var changeOnRead = false
        val fs = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun source(file: Path): Source {
                val source = super.source(file)
                if (changeOnRead && file == first / "cache_index.json") {
                    changeOnRead = false
                    settings.userSettings.value = settings.userSettings.value.copy(cacheFolder = second.toString())
                }
                return source
            }
        }
        val cache = CacheManager(Paths(), settings, fileSystem = fs)
        try {
            cache.writeCacheIndex(CacheIndex(listOf(CacheEntry("old", "old.m4a", 1, 0))), first)
            cache.writeCacheIndex(CacheIndex(listOf(CacheEntry("separate", "separate.m4a", 1, 0))), second)
            val ticket = cache.writeTicket("song")
            val temporary = first / "song.part"
            fs.sink(temporary).buffer().use { it.writeUtf8("audio") }
            changeOnRead = true
            assertTrue(cache.publishCacheFile("song", ticket, temporary, "song.m4a", 5, "audio/mp4"))
            assertEquals(listOf("old", "song"), cache.readCacheIndex(first).entries.map { it.trackId })
            assertEquals(listOf("separate"), cache.readCacheIndex(second).entries.map { it.trackId })
            assertTrue(fs.exists(first / "song.m4a"))
            assertFalse(fs.exists(second / "song.m4a"))
        } finally { fs.deleteRecursively(root) }
    }

    @Test fun changingAudioProviderRejectsOldWarmupAndOldCacheHits() = runBlocking {
        val dir = Files.createTempDirectory("spotube-cache-provider").toOkioPath()
        val settings = object : UserSettingsSource {
            override val userSettings = MutableStateFlow(UserSettings(cacheFolder = dir.toString(), enableMusicCaching = true))
        }
        var provider = "a"
        val cache = CacheManager(Paths(), settings, audioContext = { provider })
        val fs = FileSystem.SYSTEM
        try {
            val ticket = cache.writeTicket("song")
            val temporary = dir / "song.part"
            fs.sink(temporary).buffer().use { it.writeUtf8("audio") }
            provider = "b"
            assertFalse(cache.publishCacheFile("song", ticket, temporary, "song.m4a", 5, "audio/mp4"))
            provider = "a"
            assertTrue(cache.publishCacheFile("song", ticket, temporary, "song.m4a", 5, "audio/mp4"))
            assertNotNull(cache.findCachedEntry("song"))
            provider = "b"
            assertNull(cache.findCachedEntry("song"))
        } finally { fs.deleteRecursively(dir) }
    }

    @Test fun invalidationRejectsInFlightOldSourceEvenWithNoExistingEntry() = runBlocking {
        val dir = Files.createTempDirectory("spotube-cache-publication").toOkioPath()
        val settings = object : UserSettingsSource {
            override val userSettings = MutableStateFlow(UserSettings(cacheFolder = dir.toString(), enableMusicCaching = true))
        }
        val cache = CacheManager(Paths(), settings)
        val fs = FileSystem.SYSTEM
        try {
            val old = cache.writeTicket("song")
            val temporary = dir / "old.part"
            fs.sink(temporary).buffer().use { it.writeUtf8("old source") }
            cache.invalidateCacheEntry("song")
            assertFalse(cache.publishCacheFile("song", old, temporary, "song.m4a", 10, "audio/mp4"))
            assertNull(cache.findCachedEntry("song"))
            assertTrue(fs.exists(temporary)) // Caller owns cleanup; no partially published audio.
            val fresh = cache.writeTicket("song")
            assertTrue(cache.publishCacheFile("song", fresh, temporary, "song.m4a", 10, "audio/mp4"))
            assertEquals(10L, cache.findCachedEntry("song")?.second?.sizeBytes)
            assertFalse(fs.exists(temporary))
            cache.invalidateCacheEntry("song")
            assertFalse(fs.exists(dir / "song.m4a"))
        } finally { fs.deleteRecursively(dir) }
    }

    @Test fun folderChangeOrDisabledCacheRejectsPublication() = runBlocking {
        val dir = Files.createTempDirectory("spotube-cache-folder").toOkioPath()
        val settings = object : UserSettingsSource {
            override val userSettings = MutableStateFlow(UserSettings(cacheFolder = dir.toString(), enableMusicCaching = true))
        }
        val cache = CacheManager(Paths(), settings)
        val fs = FileSystem.SYSTEM
        try {
            val ticket = cache.writeTicket("song")
            val temporary = dir / "song.part"
            fs.sink(temporary).buffer().use { it.writeUtf8("audio") }
            settings.userSettings.value = settings.userSettings.value.copy(cacheFolder = (dir / "new").toString())
            assertFalse(cache.publishCacheFile("song", ticket, temporary, "song.m4a", 5, "audio/mp4"))
            settings.userSettings.value = settings.userSettings.value.copy(cacheFolder = dir.toString(), enableMusicCaching = false)
            assertFalse(cache.publishCacheFile("song", ticket, temporary, "song.m4a", 5, "audio/mp4"))
            assertFalse(fs.exists(dir / "song.m4a"))
        } finally { fs.deleteRecursively(dir) }
    }
}
