/*
 * Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.lyrics

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path.Companion.toPath
import org.spotube.plugin_interfaces.plugin_apis.lyrics.LyricsResponse
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import org.spotube.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import org.spotube.spotube.core.audioplayer.QueueEntry
import org.spotube.spotube.core.paths.Paths
import org.spotube.spotube.modules.plugin.PluginManager

class LyricsRepository(private val pluginManager: PluginManager, paths: Paths) {
    private val store = OfflineLyricsStore(paths.getApplicationDataDirPath().toPath() / "lyrics")
    private val logger = Logger.withTag("LyricsRepository")

    suspend fun get(entry: QueueEntry, forceReload: Boolean = false): LyricsResponse? = when (entry) {
        is QueueEntry.StreamingTrack -> get(entry.track, forceReload)
        is QueueEntry.LocalTrack -> readDownloaded(entry) ?: get(MetadataTrack(
            id = "local", title = entry.name, durationMs = entry.duration,
            trackNumber = null, discNumber = null,
            artists = entry.artists.map { MetadataArtist.Basic("", it, emptyList(), null) },
            album = null, thumbnails = null, explicit = null, popularity = null,
            isrcCode = null, externalUri = null), forceReload)
    }

    private suspend fun readDownloaded(entry: QueueEntry.LocalTrack): LyricsResponse? = try {
        store.read(entry.name, entry.artists, entry.duration, entry.url.substringAfterLast('/'))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        logger.w(e) { "Could not read download lyrics" }
        null
    }

    suspend fun bindDownload(track: MetadataTrack, filename: String, lyrics: LyricsResponse?) {
        if (lyrics == null) return
        try {
            store.write(track.title, track.artists.map { it.name }, track.durationMs, lyrics, filename)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logger.w(e) { "Could not link lyrics to downloaded file" }
        }
    }

    suspend fun get(track: MetadataTrack, forceReload: Boolean = false): LyricsResponse? {
        val artists = track.artists.map { it.name }
        val cached = try {
            store.read(track.title, artists, track.durationMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logger.w(e) { "Could not read saved lyrics" }
            null
        }
        if (!forceReload && cached != null) return cached
        val fetched = try {
            withTimeoutOrNull(8_000) {
                pluginManager.selectedLyricsPlugin.value?.use { lyricsAPI.getLyrics(track) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logger.w(e) { "Lyrics unavailable" }
            null
        }
        if (fetched != null) {
            try {
                store.write(track.title, artists, track.durationMs, fetched)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                logger.w(e) { "Could not persist lyrics" }
            }
        }
        return fetched?.takeIf { !it.syncedLyrics.isNullOrEmpty() || !it.plainLyrics.isNullOrBlank() } ?: cached
    }
}
