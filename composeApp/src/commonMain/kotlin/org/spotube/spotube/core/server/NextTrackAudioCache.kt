/* Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import okio.buffer
import okio.use
import org.spotube.plugin_interfaces.plugin_apis.audio.StreamProtocol
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import org.spotube.spotube.core.network.HttpClientFactory
import org.spotube.spotube.modules.settings.SettingsRepository

/** Prepare actual audio, rather than only a short-lived source URL, for the immediate next item. */
class NextTrackAudioCache(
    private val repository: StreamingUrlRepository,
    private val cache: CacheManager,
    private val settings: SettingsRepository,
    httpClientFactory: HttpClientFactory
) {
    internal val cacheChanges = combine(cache.changes, cache.playbackNetwork.active) { revision, active -> revision to active }

    internal fun canPrepare(currentTrackId: String, hasBufferHeadroom: Boolean): Boolean =
        hasBufferHeadroom || (currentTrackId !in cache.playbackNetwork.active.value && cache.findCachedEntry(currentTrackId) != null)

    private val http = httpClientFactory.create {
        install(HttpTimeout) { connectTimeoutMillis = 5_000; requestTimeoutMillis = 15_000 }
    }

    suspend fun prepare(track: MetadataTrack) = withContext(Dispatchers.IO) {
        if (!settings.userSettings.value.enableMusicCaching || cache.findCachedEntry(track.id) != null) return@withContext
        cache.writers.withLease(track.id) { ownsLease ->
            if (!ownsLease || cache.findCachedEntry(track.id) != null) return@withLease
            val ticket = cache.writeTicket(track.id)
            val info = repository.resolveStreamInfo(track) ?: return@withLease
            if (info.cacheContext != ticket.context) return@withLease
            if (info.protocol != StreamProtocol.PROGRESSIVE) return@withLease
            val dir = ticket.directory
            val fs = FileSystem.SYSTEM
            fs.createDirectories(dir)
            // Track IDs may contain slashes; use a content-independent, filesystem-safe identity.
            val temporaryName = cache.resolveCacheFilename(track, null) + ".warming"
            val temporary = dir / temporaryName
            try {
                val prepared = transferPreparedAudio(http, info.url, fs, temporary) ?: return@withLease
                // A folder/settings change while downloading must not publish into the wrong cache.
                if (!settings.userSettings.value.enableMusicCaching || dir != cache.resolveCacheDir()) return@withLease
                val filename = cache.resolveCacheFilename(track, prepared.contentType)
                if (cache.publishCacheFile(track.id, ticket, temporary, filename, prepared.sizeBytes, prepared.contentType)) {
                    cache.evictIfNeeded()
                }
            } finally {
                fs.delete(temporary, mustExist = false)
            }
        }
    }
}

internal data class PreparedAudio(val sizeBytes: Long, val contentType: String)

/** Fixed memory, finite disk budget; never publish a partial file or cache a manifest as audio. */
internal suspend fun transferPreparedAudio(
    http: HttpClient,
    url: String,
    fs: FileSystem,
    target: Path,
    maximumBytes: Long = 24L * 1024 * 1024
): PreparedAudio? {
    var result: PreparedAudio? = null
    http.prepareGet(url).execute { response ->
        if (response.status != HttpStatusCode.OK) return@execute
        val type = response.headers[HttpHeaders.ContentType]?.substringBefore(';')?.lowercase() ?: "application/octet-stream"
        if (type.startsWith("text/") || type.startsWith("video/") ||
            listOf("json", "xml", "mpegurl", "dash").any { it in type }) return@execute
        val expected = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (expected != null && (expected <= 0 || expected > maximumBytes)) return@execute
        var bytes = 0L
        fs.sink(target).buffer().use { sink ->
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                if (read == 0) continue
                bytes += read
                if (bytes > maximumBytes) return@execute
                sink.write(buffer, 0, read)
            }
        }
        if (bytes > 0 && (expected == null || expected == bytes)) result = PreparedAudio(bytes, type)
    }
    return result
}
