/*
 * Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.spotube.spotube.modules.library.local_tracks.media

import co.touchlab.kermit.Logger
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.spotube.spotube.core.paths.Paths

class IosLocalMediaDiscoveryService(private val paths: Paths) : LocalMediaDiscoveryService {
    private val logger = Logger.withTag("IosLocalMediaDiscovery")
    private val scanner = FileSystemMediaScanner(FileSystem.SYSTEM, readTrack = { path ->
        val metadata = uniffi.compose_app.readAudioMetadata(path.toString())
        LocalMediaTrack(
            path = path.toString(),
            name = metadata.title?.takeIf { it.isNotBlank() } ?: path.name.substringBeforeLast('.'),
            artists = metadata.artists,
            durationMs = metadata.durationMs,
            album = metadata.album,
            coverBytes = metadata.coverBytes?.let(::iosCoverThumbnail))
    }, onUnreadable = { path, error -> logger.w(error) { "Could not index $path" } })

    override suspend fun discoverFolders(roots: List<String>): List<LocalMediaFolder> {
        val personalMusic = paths.getUserDownloadsDirPath().toPath().parent!! / "Music"
        return scanner.discover(roots + personalMusic.toString())
    }

    override fun observeChanges(
        roots: List<String>,
        onChanged: LocalMediaChangeCallback): LocalMediaObservation? {
        return null
    }
}

/** Keep the persistent JSON index small; the full-resolution cover stays in the music file. */
private fun iosCoverThumbnail(bytes: ByteArray): ByteArray? {
    if (bytes.isEmpty() || bytes.size > 8 * 1024 * 1024) return null
    return runCatching {
        Image.makeFromEncoded(bytes).use { source ->
            val scale = (160f / maxOf(source.width, source.height)).coerceAtMost(1f)
            if (scale == 1f && bytes.size <= 64 * 1024) return@runCatching bytes
            val width = (source.width * scale).toInt().coerceAtLeast(1)
            val height = (source.height * scale).toInt().coerceAtLeast(1)
            Surface.makeRasterN32Premul(width, height).use { surface ->
                surface.canvas.drawImageRect(source,
                    Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
                    Rect.makeWH(width.toFloat(), height.toFloat()),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE), null, true)
                surface.makeImageSnapshot().use { image ->
                    image.encodeToData(EncodedImageFormat.JPEG, 80)?.use { it.bytes }
                        ?.takeIf { it.size <= 64 * 1024 }
                }
            }
        }
    }.getOrNull()
}
