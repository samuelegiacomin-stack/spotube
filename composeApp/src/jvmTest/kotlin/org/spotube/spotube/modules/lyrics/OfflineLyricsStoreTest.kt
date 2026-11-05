/*
 * Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.lyrics

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.spotube.plugin_interfaces.plugin_apis.lyrics.LyricsLine
import org.spotube.plugin_interfaces.plugin_apis.lyrics.LyricsResponse
import kotlin.test.*

class OfflineLyricsStoreTest {
    private val lyrics = LyricsResponse(listOf(LyricsLine(1500, "Test line")), "Test line")
    private fun scenario(test: suspend (okio.Path) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("spotube-lyrics-test-").toOkioPath()
        try { test(root) } finally {
            val absolute = root.toNioPath().toAbsolutePath().normalize()
            check(absolute.startsWith(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            check(absolute.fileName.toString().startsWith("spotube-lyrics-test-"))
            FileSystem.SYSTEM.deleteRecursively(root)
        }
    }
    @Test fun survivesRestartAndLocalMetadataDurationRounding() = scenario { root ->
        OfflineLyricsStore(root).write("Song", listOf("Singer"), 180450, lyrics)
        assertEquals(lyrics, OfflineLyricsStore(root).read(" song ", listOf("singer"), 180000))
    }
    @Test fun doesNotConfuseArtistOrDifferentVersion() = scenario { root ->
        val store = OfflineLyricsStore(root)
        store.write("Song", listOf("Singer"), 180000, lyrics)
        assertNull(store.read("Song", listOf("Other singer"), 180000))
        assertNull(store.read("Song", listOf("Singer"), 300000))
    }
    @Test fun absentAndCorruptFilesAreOptional() = scenario { root ->
        val store = OfflineLyricsStore(root)
        assertNull(store.read("Missing", emptyList(), 0))
        store.write("Song", emptyList(), 0, lyrics)
        val path = FileSystem.SYSTEM.list(root).single()
        FileSystem.SYSTEM.write(path) { writeUtf8("not json") }
        assertNull(store.read("Song", emptyList(), 0))
    }
    @Test fun missingLyricsNeverEraseExistingText() = scenario { root ->
        val store = OfflineLyricsStore(root)
        store.write("Song", emptyList(), 0, lyrics)
        store.write("Song", emptyList(), 0, LyricsResponse(emptyList(), ""))
        assertEquals(lyrics, store.read("Song", emptyList(), 0))
        assertTrue(FileSystem.SYSTEM.list(root).none { it.name.endsWith(".tmp") })
    }
    @Test fun plainOnlyTextIsPreserved() = scenario { root ->
        val plain = LyricsResponse(null, "Only text")
        OfflineLyricsStore(root).write("Song", emptyList(), 1000, plain)
        assertEquals(plain, OfflineLyricsStore(root).read("Song", emptyList(), 1000))
    }
}
