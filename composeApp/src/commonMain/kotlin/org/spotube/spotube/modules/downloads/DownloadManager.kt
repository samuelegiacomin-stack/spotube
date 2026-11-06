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

package org.spotube.spotube.modules.downloads

import org.spotube.spotube.core.network.HttpClientFactory
import co.touchlab.kermit.Logger
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import org.spotube.spotube.core.paths.Paths
import org.spotube.spotube.PlatformType
import org.spotube.spotube.getPlatform
import org.spotube.spotube.core.server.StreamInfo
import org.spotube.spotube.core.server.StreamRequest
import org.spotube.spotube.core.server.StreamingUrlRepository
import org.spotube.spotube.core.server.urlSummary
import org.spotube.plugin_interfaces.plugin_apis.audio.StreamProtocol
import org.spotube.spotube.modules.settings.SettingsRepository
import org.spotube.spotube.modules.library.local_tracks.media.LocalMediaLibraryCoordinator
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.spotube.spotube.core.audioplayer.AudioPlayerInterface
import org.spotube.spotube.core.audioplayer.PlayerState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import okio.use
import kotlin.time.Clock
import kotlin.random.Random
import org.spotube.spotube.core.i18n.getString
import spotube.composeapp.generated.resources.*

@Serializable
data class DownloadItem(
    val id: String,
    val title: String,
    val artists: String,
    val album: String?,
    val status: DownloadStatus = DownloadStatus.Queued,
    val progress: Float = 0f,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val errorMessage: String? = null,
    val track: MetadataTrack? = null,
    val filePath: String? = null,
    // Resolved only for an active transfer; URLs are refreshed on explicit retry.
    val transferUrl: String? = null)

@Serializable
sealed interface DownloadStatus {
    @Serializable
    data object Queued : DownloadStatus
    @Serializable
    data object Downloading : DownloadStatus
    @Serializable
    data object Completed : DownloadStatus
    @Serializable
    data class Failed(val error: String) : DownloadStatus
    @Serializable
    data object Cancelled : DownloadStatus
}

class DownloadManager(
    private val settingsRepository: SettingsRepository,
    private val paths: Paths,
    private val streamingUrlRepository: StreamingUrlRepository,
    private val httpClientFactory: HttpClientFactory,
    private val localMediaLibrary: LocalMediaLibraryCoordinator,
    private val audioPlayer: AudioPlayerInterface,
    private val lyricsRepository: org.spotube.spotube.modules.lyrics.LyricsRepository,
) {
    companion object {
        const val MAX_CONCURRENT_DOWNLOADS = 3
    }

    private val logger = Logger.withTag("DownloadManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fileSystem = FileSystem.SYSTEM
    private val queueMutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val store = DownloadStore(fileSystem, paths.getApplicationDataDirPath().toPath()) { saved ->
        if (getPlatform().type == PlatformType.IOS) relocateIosDownloadPath(saved, paths.getUserDownloadsDirPath()) else saved
    }
    private var catalog: Map<String, String> = emptyMap()
    private var committedTerminalIds: Set<String> = emptySet()
    private val completedTrackIdsState = MutableStateFlow<Set<String>>(emptySet())
    val completedTrackIds: StateFlow<Set<String>> = completedTrackIdsState.asStateFlow()
    private val priority = DownloadPlaybackPriority()
    private val nativeEngine: NativeDownloadEngine? = if (getPlatform().type == PlatformType.IOS) {
        checkNotNull(NativeDownloadRegistry.engine) { "Native iOS download engine was not registered" }
    } else null

    private val httpClient = httpClientFactory.create {
        install(HttpTimeout) {
            requestTimeoutMillis = 300_000
            connectTimeoutMillis = 30_000
        }
        expectSuccess = false
    }

    private val downloadsMap = MutableStateFlow<Map<String, DownloadItem>>(emptyMap())

    val downloadsFlow: StateFlow<List<DownloadItem>> = downloadsMap
        .map { map -> map.values.toList() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val cachedDownloadFolder = MutableStateFlow<String?>(null)

    /** Where downloads are saved right now: the user's override, else the platform default. */
    val downloadFolder: StateFlow<String> = cachedDownloadFolder
        .map { it?.takeIf(String::isNotBlank) ?: paths.getUserDownloadsDirPath() }
        .stateIn(scope, SharingStarted.Eagerly, paths.getUserDownloadsDirPath())

    private val pausedIds = MutableStateFlow<Set<String>>(emptySet())
    val pausedDownloadIds: StateFlow<Set<String>> = pausedIds

    private val activeJobs = mutableMapOf<String, Job>()
    private val handoffs = mutableMapOf<String, CompletableDeferred<Unit>>()

    init {
        scope.launch {
            settingsRepository.userSettings.collect { settings ->
                cachedDownloadFolder.value = settings.overloadedDownloadFolder
            }
        }
        scope.launch {
            try {
                queueMutex.withLock {
                    val restored = store.restore(store.read())
                    catalog = restored.catalog
                    downloadsMap.value = restored.items.associateBy { it.id }
                    persist()
                    nativeEngine?.reconcile(restored.items.map { it.id })
                    restored.items.forEach { item ->
                        when (item.status) {
                            DownloadStatus.Completed, is DownloadStatus.Failed -> nativeEngine?.acknowledge(item.id)
                            DownloadStatus.Cancelled -> nativeEngine?.cancel(item.id)
                            else -> Unit
                        }
                    }
                }
                ready.complete(Unit)
                processQueue()
            } catch (error: Exception) {
                ready.completeExceptionally(error)
                logger.e(error) { "Could not restore durable downloads; refusing to discard the journal" }
            }
        }
        scope.launch {
            ready.await()
            audioPlayer.playerStateFlow.collect { processQueue() }
        }
        nativeEngine?.setWakeListener(object : NativeDownloadWakeListener {
            override fun onWake() {
                scope.launch {
                    ready.await()
                    processQueue()
                    awaitHandoffs()
                    nativeEngine?.queueReady()
                }
            }
        })
    }

    /** Idempotent by metadata track ID, including after clearing history or restarting. */
    fun enqueue(track: MetadataTrack) {
        scope.launch { enqueueAll(listOf(track)) }
    }

    /** One admission transaction/journal write per playlist, rather than one full rewrite per song. */
    suspend fun enqueueAll(tracks: List<MetadataTrack>): Set<String> {
        ready.await()
        val completed = queueMutex.withLock {
            val admission = admitDownloads(DownloadSnapshot(downloadsMap.value.values.toList(), catalog), tracks, activeJobs.keys,
                newId = { "${Clock.System.now().toEpochMilliseconds()}_${Random.nextLong().toULong().toString(16)}" },
                verifiedPath = store::verifiedPath)
            catalog = admission.catalog
            downloadsMap.update { it + admission.changes }
            persist()
            admission.completed
        }
        processQueue()
        return completed
    }

    /** A catalog hit must be rechecked before a batch labels a track complete. */
    suspend fun validateCompletedTrackIds(trackIds: Set<String>): Set<String> {
        ready.await()
        return queueMutex.withLock {
            val invalid = trackIds.filterTo(mutableSetOf()) { store.verifiedPath(catalog[it]) == null }
            if (invalid.any { it in catalog }) {
                catalog = catalog - invalid
                downloadsMap.update { map -> map.filterValues { it.status != DownloadStatus.Completed || it.track?.id !in invalid } }
                persist()
            }
            trackIds - invalid
        }
    }

    fun cancel(id: String) {
        scope.launch {
            ready.await()
            queueMutex.withLock {
                val item = downloadsMap.value[id] ?: return@withLock
                if (item.status == DownloadStatus.Completed) return@withLock
                downloadsMap.update { it + (id to item.copy(status = DownloadStatus.Cancelled, transferUrl = null)) }
                persist()
                nativeEngine?.cancel(id)
                activeJobs[id]?.cancel()
            }
            processQueue()
        }
    }

    fun retry(id: String) {
        scope.launch {
            ready.await()
            queueMutex.withLock {
                val item = downloadsMap.value[id] ?: return@withLock
                if (id in activeJobs || (item.status !is DownloadStatus.Failed && item.status != DownloadStatus.Cancelled)) return@withLock
                downloadsMap.update { it + (id to item.copy(status = DownloadStatus.Queued, progress = 0f,
                    downloadedBytes = 0L, errorMessage = null, filePath = null, transferUrl = null)) }
                persist()
            }
            processQueue()
        }
    }

    fun remove(id: String) {
        scope.launch {
            ready.await()
            queueMutex.withLock {
                downloadsMap.update { it - id }
                persist()
                nativeEngine?.cancel(id)
                activeJobs[id]?.cancel()
            }
            processQueue()
        }
    }

    fun clearCompleted() {
        scope.launch {
            ready.await()
            queueMutex.withLock {
                downloadsMap.update { map -> map.filter { it.value.status != DownloadStatus.Completed } }
                persist()
            }
        }
    }

    /** Must hold queueMutex; transfer progress is intentionally not journaled per chunk. */
    private fun persist() {
        store.write(DownloadSnapshot(downloadsMap.value.values.toList(), catalog))
        committedTerminalIds = downloadsMap.value.values.filter { it.status == DownloadStatus.Completed ||
            it.status is DownloadStatus.Failed || it.status == DownloadStatus.Cancelled }.mapTo(mutableSetOf()) { it.id }
        completedTrackIdsState.value = catalog.keys.toSet()
    }

    private suspend fun awaitHandoffs() {
        val pending = queueMutex.withLock { handoffs.values.toList() }
        pending.forEach { it.await() }
    }

    private fun updatePriority() {
        val allowed = priority.update(activeJobs.keys.toList(), audioPlayer.playerStateFlow.value)
        pausedIds.value = activeJobs.keys.filterNot { it in allowed }.toSet()
        activeJobs.keys.forEach { nativeEngine?.setSuspended(it, it !in allowed) }
    }

    private suspend fun processQueue() {
        ready.await()
        queueMutex.withLock {
            updatePriority()
            while (activeJobs.size < MAX_CONCURRENT_DOWNLOADS) {
                // Reattach OS-owned transfers before starting any newly queued work.
                val queued = downloadsMap.value.values.filter { it.status == DownloadStatus.Queued && it.id !in activeJobs }
                val restoring = if (nativeEngine != null) queued.firstOrNull { it.transferUrl != null } else null
                val nextItem = restoring ?: if (activeJobs.size < downloadConcurrency(audioPlayer.playerStateFlow.value))
                    queued.firstOrNull() ?: break else break
                val id = nextItem.id
                downloadsMap.update { it + (id to nextItem.copy(status = DownloadStatus.Downloading)) }
                persist() // Persist intent before any network task starts.
                handoffs[id] = CompletableDeferred()
                val job = scope.launch(start = CoroutineStart.LAZY) { executeDownload(nextItem) }
                activeJobs[id] = job
                updatePriority()
                job.invokeOnCompletion {
                    scope.launch {
                        queueMutex.withLock {
                            // Clear the terminal result before this id becomes reusable. Swift
                            // invalidates its queue-cycle marker, preventing an early wake finish.
                            if (id in committedTerminalIds) nativeEngine?.acknowledge(id)
                            if (activeJobs[id] === job) activeJobs.remove(id)
                            handoffs.remove(id)?.complete(Unit)
                            updatePriority()
                        }
                        processQueue()
                        awaitHandoffs()
                        // Completion is durable and replacements have actually reached URLSession.
                        nativeEngine?.queueReady()
                    }
                }
                job.start()
            }
        }
    }

    private suspend fun updateItem(id: String, transform: (DownloadItem) -> DownloadItem) {
        queueMutex.withLock {
            downloadsMap.update { map -> map[id]?.let { map + (id to transform(it)) } ?: map }
            persist()
        }
    }

    private suspend fun executeDownload(item: DownloadItem) {
        val track = item.track ?: run {
            val message = getString(Res.string.downloads_error_no_metadata)
            updateItem(item.id) { it.copy(status = DownloadStatus.Failed(message), errorMessage = message) }
            return
        }
        try {
            if (nativeEngine == null || item.transferUrl == null) priority.await(item.id)
            var finalPath = item.filePath?.toPath()
            var url = item.transferUrl
            if (finalPath == null || url == null) {
                val downloadDir = resolveDownloadDir()
                val settings = settingsRepository.userSettings.value
                val info = streamingUrlRepository.resolveStreamInfo(track, request = StreamRequest(
                    format = settings.downloadMusicFormat, quality = settings.downloadMusicQuality, progressiveOnly = true))
                    ?: throw DownloadException(getString(Res.string.downloads_error_stream_unresolved))
                logResolvedStream(item, info)
                finalPath = downloadDir / buildFilename(item, info)
                url = info.url
                val plannedPath = finalPath.toString()
                val plannedUrl = url
                updateItem(item.id) { it.copy(filePath = plannedPath, transferUrl = plannedUrl) }
            }
            val output = requireNotNull(finalPath)
            val downloadUrl = requireNotNull(url)
            // Enrichment overlaps the transfer instead of adding up to 8 seconds at its tail.
            // It remains structured: cancellation/failure cancels both children and audio is
            // published as completed only after bounded metadata work, never while tagging.
            coroutineScope {
                val artwork = async {
                    if (audioPlayer.playerStateFlow.value != PlayerState.BUFFERING) withTimeoutOrNull(4_000) { fetchCover(item) } else null
                }
                val lyrics = async {
                    if (audioPlayer.playerStateFlow.value != PlayerState.BUFFERING) withTimeoutOrNull(8_000) { lyricsRepository.get(track) } else null
                }
                if (nativeEngine != null) {
                    awaitNativeDownload(item.id, downloadUrl, output.toString())
                } else {
                    queueMutex.withLock { handoffs[item.id]?.complete(Unit) }
                    if (store.verifiedPath(output.toString()) == null) {
                        executeTransfer(downloadUrl, item, output)
                    }
                }
                check(store.verifiedPath(output.toString()) != null) { "Downloaded audio file is missing or empty" }
                applyMetadata(item, output.name, requireNotNull(output.parent), artwork.await())
                lyricsRepository.bindDownload(track, output.name, lyrics.await())
            }
            addToMediaLibrary(output.toString())
            localMediaLibrary.scheduleRefresh(reason = "download_completed")
            queueMutex.withLock {
                // cancel/remove may win the race just before publication; never resurrect them.
                val current = downloadsMap.value[item.id]
                if (current?.status == DownloadStatus.Downloading) {
                    catalog = catalog + (track.id to output.toString())
                    downloadsMap.update { it + (item.id to current.copy(status = DownloadStatus.Completed,
                        progress = 1f, transferUrl = null, downloadedBytes = fileSystem.metadata(output).size ?: current.downloadedBytes)) }
                    persist()
                }
            }
        } catch (e: CancellationException) {
            // User cancel/remove persisted the state before cancelling the coroutine.
            throw e
        } catch (e: Exception) {
            logger.e(e) { "[Download ${item.id}] failed '${item.title}'" }
            val failure = if (e is IncompleteDownloadException) getString(Res.string.downloads_error_range_ignored)
                else e.message ?: getString(Res.string.common_unknown_error)
            updateItem(item.id) { current ->
                if (current.status == DownloadStatus.Cancelled) current else
                    current.copy(status = DownloadStatus.Failed(failure), errorMessage = failure, transferUrl = null)
            }
        }
    }

    private suspend fun awaitNativeDownload(id: String, url: String, destination: String) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val engine = requireNotNull(nativeEngine)
            engine.start(id, url, destination, object : NativeDownloadListener {
                override fun onAttached() {
                    scope.launch { queueMutex.withLock { handoffs[id]?.complete(Unit) } }
                }
                override fun onProgress(downloaded: Long, total: Long) {
                    downloadsMap.update { map -> map[id]?.let { current -> map + (id to current.copy(
                        downloadedBytes = downloaded, totalBytes = total,
                        progress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 0.99f) else 0f)) } ?: map }
                }
                override fun onComplete() { if (continuation.isActive) continuation.resume(Unit) }
                override fun onFailure(message: String) {
                    if (continuation.isActive) continuation.resumeWithException(DownloadException(message))
                }
            })
            continuation.invokeOnCancellation { engine.cancel(id) }
        }
    }

    private suspend fun executeTransfer(url: String, item: DownloadItem, finalPath: Path) {
        val tempFile = requireNotNull(finalPath.parent) / ".${item.id}.part"
        try {
            DownloadTransfer(httpClient, fileSystem).download(url, tempFile,
                onRestart = { downloadsMap.update { map -> map[item.id]?.let { map + (item.id to it.copy(
                    progress = 0f, downloadedBytes = 0L, totalBytes = 0L)) } ?: map } },
                onChunk = { bytes, total -> updateProgress(item.id, bytes, total) },
                awaitPermit = { priority.await(item.id) })
            currentCoroutineContext().ensureActive()
            fileSystem.atomicMove(tempFile, finalPath)
        } finally {
            fileSystem.delete(tempFile, mustExist = false)
        }
    }

    private fun updateProgress(id: String, bytesJustRead: Long, totalSize: Long) {
        downloadsMap.update { map ->
            map[id]?.let { current ->
                val newDownloaded = current.downloadedBytes + bytesJustRead
                val progress = if (totalSize > 0) {
                    (newDownloaded.toFloat() / totalSize.toFloat()).coerceIn(0f, 0.99f)
                } else 0f
                map + (id to current.copy(
                    progress = progress,
                    downloadedBytes = newDownloaded,
                    totalBytes = totalSize))
            } ?: map
        }
    }

    private fun logResolvedStream(item: DownloadItem, info: StreamInfo) {
        val settings = settingsRepository.userSettings.value
        val wanted = settings.downloadMusicFormat
        logger.i {
            "[Download ${item.id}] resolved stream codec=${info.codec} container=${info.container} " +
                "protocol=${info.protocol} ${urlSummary(info.url)}"
        }
        if (info.protocol != StreamProtocol.PROGRESSIVE) {
            logger.w {
                "[Download ${item.id}] stream is a ${info.protocol} MANIFEST; a plain GET saves the manifest, not audio"
            }
        }
        if (!info.codec.equals(wanted.codec, ignoreCase = true) || !info.container.equals(wanted.container, ignoreCase = true)) {
            logger.w {
                "[Download ${item.id}] FORMAT FALLBACK: download setting is ${wanted.codec}/${wanted.container} " +
                    "${settings.downloadMusicQuality}, but the closest downloadable stream offered is ${info.codec}/${info.container}"
            }
        }
    }

    private suspend fun fetchCover(item: DownloadItem): ByteArray? {
        val track = item.track ?: return null
        val thumbnails = track.thumbnails.orEmpty().ifEmpty { track.album?.thumbnails.orEmpty() }
        return try {
            downloadArtwork(httpClient, thumbnails.maxByOrNull { it.width.toLong() * it.height }?.url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "Failed to download cover art for ${item.title}" }
            null
        }
    }

    private suspend fun applyMetadata(item: DownloadItem, filename: String, downloadDir: Path, coverBytes: ByteArray?) {
        val filePath = downloadDir / filename.toPath()
        if (!fileSystem.exists(filePath)) {
            logger.w { "[Download ${item.id}] skipping metadata: $filePath does not exist" }
            return
        }
        logger.d { "[Download ${item.id}] writing metadata to $filePath" }

        val track = item.track ?: return

        try {
            uniffi.compose_app.writeAudioMetadata(
                filePath = filePath.toString(),
                title = item.title,
                artists = item.artists,
                album = item.album,
                trackNumber = track.trackNumber,
                discNumber = track.discNumber,
                coverBytes = coverBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "[Download ${item.id}] failed to write audio metadata for ${item.title}" }
        }
    }

    private suspend fun resolveDownloadDir(): Path {
        val selected = downloadFolder.value
        val resolved = prepareDownloadDirectory(
            selected = selected,
            fallback = paths.getUserDownloadsDirPath(),
            allowFallback = getPlatform().type == PlatformType.IOS
        ) { folder ->
            val directory = folder.toPath()
            fileSystem.createDirectories(directory)
            // Existing File Provider directories may be readable while writes are denied.
            // Check before spending time resolving or downloading a complete audio stream.
            val probe = directory / ".spotube-write-${Random.nextLong().toULong().toString(16)}"
            try {
                fileSystem.sink(probe, mustCreate = true).close()
            } finally {
                runCatching { fileSystem.delete(probe, mustExist = false) }
            }
        }
        if (resolved != selected) {
            logger.w { "Saved iOS download folder is not writable; using app Documents/Spotube/Downloads" }
            val settings = settingsRepository.userSettings.value
            if (settings.overloadedDownloadFolder == selected) {
                settingsRepository.updateSettings(settings.copy(overloadedDownloadFolder = resolved))
                cachedDownloadFolder.value = resolved
            }
        }
        return resolved.toPath()
    }

    private fun buildFilename(item: DownloadItem, info: StreamInfo): String =
        downloadFilename(requireNotNull(item.track).id, item.artists, item.title, fileExtension(info),
            Random.nextLong().toULong().toString(16))

    /** AAC in an MP4 container is audio-only, so give it the audio extension players expect. */
    private fun fileExtension(info: StreamInfo): String =
        if (info.codec.equals("aac", ignoreCase = true) && info.container.equals("mp4", ignoreCase = true)) "m4a"
        else info.container


}

/** A download failure whose [message] is already localized and safe to show the user. */
private class DownloadException(message: String) : Exception(message)
