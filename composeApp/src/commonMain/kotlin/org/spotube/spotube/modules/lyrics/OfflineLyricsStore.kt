/*
 * Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.SYSTEM
import okio.Path
import org.spotube.plugin_interfaces.plugin_apis.lyrics.LyricsResponse
import kotlin.math.abs

/** Durable app data, independent of the disposable audio/image cache. */
internal class OfflineLyricsStore(private val directory: Path, private val fs: FileSystem = FileSystem.SYSTEM) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Record(val durationMs: Long, val lyrics: LyricsResponse)

    private fun key(title: String, artists: List<String>) =
        (title.trim().lowercase() + "\u0000" + artists.joinToString(", ") { it.trim().lowercase() })
            .encodeUtf8().sha256().hex()

    suspend fun read(title: String, artists: List<String>, durationMs: Long): LyricsResponse? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val path = directory / (key(title, artists) + ".json")
                if ((fs.metadataOrNull(path)?.size ?: return@withLock null) > MAX_BYTES) return@withLock null
                val record = runCatching { fs.read(path) { json.decodeFromString<Record>(readUtf8()) } }.getOrNull()
                    ?: return@withLock null
                record.lyrics.takeIf { durationMs <= 0 || record.durationMs <= 0 || abs(durationMs - record.durationMs) <= 2_000 }
            }
        }

    suspend fun write(title: String, artists: List<String>, durationMs: Long, lyrics: LyricsResponse) =
        withContext(Dispatchers.IO) {
            if (lyrics.syncedLyrics.isNullOrEmpty() && lyrics.plainLyrics.isNullOrBlank()) return@withContext
            val data = json.encodeToString(Record(durationMs, lyrics))
            if (data.encodeUtf8().size > MAX_BYTES) return@withContext
            mutex.withLock {
                fs.createDirectories(directory)
                val target = directory / (key(title, artists) + ".json")
                val temp = directory / (key(title, artists) + ".tmp")
                try {
                    fs.write(temp) { writeUtf8(data) }
                    fs.atomicMove(temp, target)
                } finally {
                    fs.delete(temp, mustExist = false)
                }
            }
        }

    private companion object { const val MAX_BYTES = 1_048_576L }
}
