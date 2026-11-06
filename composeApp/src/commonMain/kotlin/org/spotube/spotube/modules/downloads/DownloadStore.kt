/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.modules.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

@Serializable
internal data class DownloadSnapshot(
    val items: List<DownloadItem> = emptyList(),
    // Independent of history: clearing completed rows must not discard deduplication.
    val catalog: Map<String, String> = emptyMap(),
)

internal data class DownloadAdmission(
    val changes: Map<String, DownloadItem>,
    val catalog: Map<String, String>,
    val completed: Set<String>,
)

/** Single-pass batch admission. The caller holds the queue lock and persists once. */
internal fun admitDownloads(
    snapshot: DownloadSnapshot,
    tracks: List<MetadataTrack>,
    activeIds: Set<String>,
    newId: () -> String,
    verifiedPath: (String?) -> String?,
): DownloadAdmission {
    val byTrack = snapshot.items.associateBy { it.track?.id }
    val changes = mutableMapOf<String, DownloadItem>()
    val catalog = snapshot.catalog.toMutableMap()
    val completed = mutableSetOf<String>()
    tracks.distinctBy { it.id }.forEach { track ->
        val existing = byTrack[track.id]
        if (existing?.id in activeIds || existing?.status == DownloadStatus.Queued || existing?.status == DownloadStatus.Downloading) return@forEach
        val savedPath = verifiedPath(catalog[track.id])
        if (savedPath == null) catalog.remove(track.id) else completed.add(track.id)
        val id = existing?.id ?: newId()
        changes[id] = DownloadItem(id = id, title = track.title,
            artists = track.artists.joinToString(", ") { it.name }, album = track.album?.title, track = track,
            status = if (savedPath != null) DownloadStatus.Completed else DownloadStatus.Queued,
            progress = if (savedPath != null) 1f else 0f, filePath = savedPath)
    }
    return DownloadAdmission(changes, catalog, completed)
}

/** Access is serialized by the manager's queue mutex. Fail closed on journal I/O errors. */
internal class DownloadStore(
    private val fs: FileSystem,
    private val directory: Path,
    private val relocate: (String) -> String = { it },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val journal = directory / "downloads-v1.json"

    fun read(): DownloadSnapshot = if (fs.exists(journal)) {
        fs.read(journal) { json.decodeFromString<DownloadSnapshot>(readUtf8()) }
    } else DownloadSnapshot()

    fun write(snapshot: DownloadSnapshot) {
        fs.createDirectories(directory)
        val temporary = directory / "downloads-v1.tmp"
        try {
            fs.write(temporary) { writeUtf8(json.encodeToString(snapshot)) }
            fs.atomicMove(temporary, journal)
        } finally {
            fs.delete(temporary, mustExist = false)
        }
    }

    fun verifiedPath(path: String?): String? = path?.takeIf {
        val metadata = fs.metadataOrNull(it.toPath())
        metadata?.isRegularFile == true && (metadata.size ?: 0L) > 0L
    }

    fun restore(snapshot: DownloadSnapshot): DownloadSnapshot {
        val catalog = snapshot.catalog.mapValues { relocate(it.value) }.filterValues { verifiedPath(it) != null }
        return DownloadSnapshot(snapshot.items.map { original ->
            val item = original.copy(filePath = original.filePath?.let(relocate))
            when (item.status) {
                DownloadStatus.Downloading -> item.copy(status = DownloadStatus.Queued, progress = 0f, downloadedBytes = 0)
                DownloadStatus.Completed -> if (verifiedPath(item.filePath) != null) item else
                    item.copy(status = DownloadStatus.Queued, progress = 0f, downloadedBytes = 0, filePath = null, transferUrl = null)
                else -> item
            }
        }, catalog)
    }
}

/** Opaque plugin IDs never become path components; the full SHA-256 avoids title collisions. */
internal fun downloadFilename(trackId: String, artists: String, title: String, extension: String, attempt: String? = null): String {
    fun safe(value: String) = value.replace(Regex("[^a-zA-Z0-9.\\-_' ]"), "_").take(55).trim().trimEnd('.')
    val suffix = trackId.encodeUtf8().sha256().hex()
    val ext = extension.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,10}")) } ?: "audio"
    val attemptSuffix = attempt?.let { "-" + safe(it).take(16) }.orEmpty()
    return "${safe(artists)} - ${safe(title)} [$suffix$attemptSuffix].$ext"
}

/** Only Spotube-owned sandbox roots move with an iOS container UUID. External paths stay intact. */
internal fun relocateIosDownloadPath(saved: String, currentDownloads: String): String {
    if ("/Containers/Data/Application/" !in saved) return saved
    val currentRoot = currentDownloads.substringBefore("/Documents/Spotube/")
    for (marker in listOf("/Documents/Spotube/", "/Library/Application Support/Spotube/")) {
        if (marker in saved) return currentRoot + marker + saved.substringAfter(marker)
    }
    return saved
}
