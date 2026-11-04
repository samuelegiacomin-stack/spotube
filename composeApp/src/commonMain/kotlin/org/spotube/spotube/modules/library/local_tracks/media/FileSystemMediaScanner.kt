/* Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.library.local_tracks.media

import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** Bounded, cancellable traversal shared with JVM tests; native metadata decoding is injected. */
internal class FileSystemMediaScanner(
    private val fileSystem: FileSystem,
    private val readTrack: suspend (Path) -> LocalMediaTrack?,
    private val maxFiles: Int = 100_000,
    private val onUnreadable: (String, Exception) -> Unit = { _, _ -> },
) {
    private data class CachedTrack(val size: Long?, val modified: Long?, val track: LocalMediaTrack)
    private var previous = emptyMap<String, CachedTrack>()
    private val mutex = Mutex()

    suspend fun discover(roots: List<String>): List<LocalMediaFolder> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pending = ArrayDeque<Pair<Path, Int>>()
            roots.forEach { raw ->
                if (raw.isBlank()) return@forEach
                try {
                    val path = if (raw.trim().startsWith("file://")) {
                        val decoded = Url(raw.trim()).encodedPath.decodeURLPart()
                        val nativePath = if (Path.DIRECTORY_SEPARATOR == "\\" &&
                            decoded.length >= 3 && decoded[0] == '/' && decoded[2] == ':') {
                            decoded.drop(1)
                        } else decoded
                        nativePath.toPath(normalize = true)
                    } else raw.trim().toPath(normalize = true)
                    pending.add(fileSystem.canonicalize(path) to 0)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    onUnreadable(raw, error)
                }
            }
            val visited = mutableSetOf<Path>()
            val refreshed = mutableMapOf<String, CachedTrack>()
            val folders = linkedMapOf<Path, MutableList<LocalMediaTrack>>()
            while (pending.isNotEmpty() && refreshed.size < maxFiles && visited.size < 400_000) {
                currentCoroutineContext().ensureActive()
                val (path, depth) = pending.removeFirst()
                if (!visited.add(path)) continue
                try {
                    val info = fileSystem.metadataOrNull(path) ?: continue
                    if (info.symlinkTarget != null) continue
                    if (info.isDirectory) {
                        if (depth < 32) fileSystem.list(path).forEach {
                            if (!it.name.startsWith('.')) pending.add(it to depth + 1)
                        }
                        continue
                    }
                    if (!info.isRegularFile || path.name.startsWith('.') ||
                        path.name.substringAfterLast('.', "").lowercase() !in AUDIO_EXTENSIONS) continue
                    val key = path.toString()
                    val cached = previous[key]?.takeIf {
                        it.size == info.size && it.modified == info.lastModifiedAtMillis
                    }
                    val track = cached?.track ?: try {
                        readTrack(path)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        onUnreadable(key, error)
                        null
                    } ?: LocalMediaTrack(
                        path = key, name = path.name.substringBeforeLast('.'),
                        artists = emptyList(), durationMs = 0)
                    refreshed[key] = CachedTrack(info.size, info.lastModifiedAtMillis, track)
                    path.parent?.let { folders.getOrPut(it) { mutableListOf() }.add(track) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    onUnreadable(path.toString(), error)
                }
            }
            previous = refreshed
            folders.map { (path, tracks) ->
                LocalMediaFolder(path.toString(), path.name, tracks.sortedBy { it.name.lowercase() })
            }.sortedBy { it.name.lowercase() }
        }
    }

    companion object {
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "aif", "aiff")
    }
}
