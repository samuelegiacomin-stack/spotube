/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.modules.downloads

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack

/** Ready-to-render state shared by playlist and Favorites download controls. */
data class CollectionDownloadProgress(
    val hasBatch: Boolean = false,
    val isPreparing: Boolean = false,
    val preparationFailed: Boolean = false,
    val totalCount: Int = 0,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
    val cancelledCount: Int = 0,
    val activeCount: Int = 0,
    val progress: Float = 0f,
    val percent: Int = 0,
) {
    val isBusy: Boolean get() = isPreparing || activeCount > 0
}

internal data class CollectionDownloadTrack(
    val status: DownloadStatus = DownloadStatus.Queued,
    val progress: Float = 0f,
    val observed: Boolean = false,
    // downloadsFlow may still expose the old snapshot just after bulk admission.
    // Ignore that exact failed/cancelled object until the eager flow catches up.
    val ignoredAttempt: DownloadItem? = null,
)

internal fun collectionDownloadProgress(
    tracks: Collection<CollectionDownloadTrack>,
    isPreparing: Boolean = false,
    preparationFailed: Boolean = false,
): CollectionDownloadProgress {
    val completed = tracks.count { it.status == DownloadStatus.Completed }
    // Each unique song has equal weight. Failed/cancelled transfers contribute zero:
    // bytes left in a partial file are not a successfully downloaded song.
    val sum = tracks.sumOf {
        when (it.status) {
            DownloadStatus.Completed -> 1.0
            DownloadStatus.Downloading -> it.progress.takeIf(Float::isFinite)
                ?.coerceIn(0f, 0.99f)?.toDouble() ?: 0.0
            else -> 0.0
        }
    }
    val progress = when {
        tracks.isEmpty() -> 0f
        completed == tracks.size -> 1f
        else -> (sum / tracks.size).toFloat().coerceAtMost(0.9999f)
    }
    return CollectionDownloadProgress(
        hasBatch = true,
        isPreparing = isPreparing,
        preparationFailed = preparationFailed,
        totalCount = tracks.size,
        completedCount = completed,
        failedCount = tracks.count { it.status is DownloadStatus.Failed },
        cancelledCount = tracks.count { it.status == DownloadStatus.Cancelled },
        activeCount = tracks.count {
            it.status == DownloadStatus.Queued || it.status == DownloadStatus.Downloading
        },
        progress = progress,
        percent = (progress * 100).toInt(),
    )
}

/** Fetch the entire collection before enqueueing, so a later page failure cannot silently
 * turn a partial Favorites list into a successful download-all operation. */
internal suspend fun loadAllDownloadTracks(
    loadPage: suspend (PaginationStrategy?) -> PaginationResult<MetadataTrack>?,
): List<MetadataTrack> {
    val tracks = linkedMapOf<String, MetadataTrack>()
    val visited = mutableSetOf<PaginationStrategy?>()
    var pagination: PaginationStrategy? = null
    do {
        check(visited.add(pagination)) { "Repeated collection pagination" }
        val page = checkNotNull(loadPage(pagination)) { "Collection page unavailable" }
        page.items.forEach { tracks.getOrPut(it.id) { it } }
        pagination = page.nextPagination
    } while (pagination != null)
    return tracks.values.toList()
}

/** Register as an application singleton, never scoped to a screen/ViewModel.
 *
 * Queue admission and overlap deduplication belong to DownloadManager.enqueueAll.
 * Batches retain their own membership when Downloads history is cleared. Completion
 * follows [completedTrackIds], so removed files and retries cannot remain pinned at 100%.
 * Batch membership lives for this app session; completed files live in that catalog.
 */
class CollectionDownloadService internal constructor(
    private val downloads: StateFlow<List<DownloadItem>>,
    private val enqueueAll: suspend (List<MetadataTrack>) -> Set<String>,
    private val scope: CoroutineScope,
    private val completedTrackIds: StateFlow<Set<String>>,
) {
    constructor(downloadManager: DownloadManager) : this(
        downloadManager.downloadsFlow,
        downloadManager::enqueueAll,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        downloadManager.completedTrackIds,
    )

    private data class Batch(
        val tracks: Map<String, CollectionDownloadTrack> = emptyMap(),
        val isPreparing: Boolean = false,
        val preparationFailed: Boolean = false,
    ) {
        val progress get() = collectionDownloadProgress(tracks.values, isPreparing, preparationFailed)
    }

    private val batches = MutableStateFlow<Map<String, Batch>>(emptyMap())
    private val logger = Logger.withTag("CollectionDownloadService")

    init {
        scope.launch {
            combine(downloads, completedTrackIds) { _, _ -> Unit }
                .collect { refreshProgress() }
        }
    }

    private fun refreshProgress() {
        batches.update { current ->
            // Read the latest sources inside the atomic update, rather than applying an
            // older collected snapshot over membership just installed by bulk admission.
            val completed = completedTrackIds.value
            val byTrack = itemsByTrack(downloads.value)
            current.mapValues { (_, batch) ->
                batch.copy(tracks = batch.tracks.mapValues { (id, previous) ->
                    when {
                        id in completed -> CollectionDownloadTrack(DownloadStatus.Completed, 1f, true)
                        // Completion requires catalog confirmation; stale history is insufficient.
                        byTrack[id]?.status == DownloadStatus.Completed -> CollectionDownloadTrack()
                        byTrack[id] != null && byTrack[id] !== previous.ignoredAttempt ->
                            byTrack.getValue(id).asBatchTrack()
                        byTrack[id] == null && previous.observed -> previous.copy(status = when (previous.status) {
                            DownloadStatus.Completed, DownloadStatus.Queued,
                            DownloadStatus.Downloading -> DownloadStatus.Cancelled
                            else -> previous.status
                        })
                        else -> previous
                    }
                })
            }
        }
    }

    fun currentProgress(collectionId: String): CollectionDownloadProgress =
        batches.value[collectionId]?.progress ?: CollectionDownloadProgress()

    fun observe(collectionId: String): Flow<CollectionDownloadProgress> = batches
        .map { it[collectionId]?.progress ?: CollectionDownloadProgress() }
        .distinctUntilChanged()

    /** Claims synchronously, preventing double taps before the loading coroutine runs. */
    fun downloadAll(
        collectionId: String,
        loadPage: suspend (PaginationStrategy?) -> PaginationResult<MetadataTrack>?,
    ) {
        while (true) {
            val current = batches.value
            if (current[collectionId]?.progress?.isBusy == true) return
            if (batches.compareAndSet(current, current + (collectionId to Batch(isPreparing = true)))) break
        }
        scope.launch {
            val tracks = try {
                loadAllDownloadTracks(loadPage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(e) { "Failed to load collection $collectionId for download" }
                batches.update { it + (collectionId to Batch(preparationFailed = true)) }
                return@launch
            }
            try {
                val beforeAdmission = itemsByTrack(downloads.value)
                // Always admit the entire collection, including catalog hits. The manager
                // validates actual files and deduplicates under one lock/journal write.
                // It publishes verified completedTrackIds before this suspend call returns.
                val completed = enqueueAll(tracks) + completedTrackIds.value
                val existing = itemsByTrack(downloads.value)
                batches.update { it + (collectionId to Batch(tracks.associate { track ->
                    track.id to when {
                        track.id in completed ->
                            CollectionDownloadTrack(DownloadStatus.Completed, 1f, true)
                        existing[track.id]?.status == DownloadStatus.Queued ||
                            existing[track.id]?.status == DownloadStatus.Downloading ->
                            existing.getValue(track.id).asBatchTrack()
                        existing[track.id] != null && existing[track.id]?.status != DownloadStatus.Completed &&
                            existing[track.id] !== beforeAdmission[track.id] ->
                            existing.getValue(track.id).asBatchTrack()
                        else -> CollectionDownloadTrack(ignoredAttempt = existing[track.id])
                    }
                })) }
                // A fast completion can arrive while membership is still being installed.
                refreshProgress()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(e) { "Failed to admit collection $collectionId to the download queue" }
                batches.update { it + (collectionId to Batch(tracks.associate { track ->
                    track.id to CollectionDownloadTrack(DownloadStatus.Failed(e.message.orEmpty()))
                })) }
            }
        }
    }

    private fun DownloadItem.asBatchTrack() = CollectionDownloadTrack(status, progress, true)

    private fun itemsByTrack(items: List<DownloadItem>): Map<String, DownloadItem> = items
        .filter { it.track != null }
        .groupBy { it.track!!.id }
        .mapValues { (_, attempts) ->
            attempts.lastOrNull { it.status == DownloadStatus.Completed }
                ?: attempts.lastOrNull { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Downloading }
                ?: attempts.last()
        }
}
