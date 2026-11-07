/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.modules.playlist

import org.spotube.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlin.time.Instant

data class PlaylistTrackEntry(val track: MetadataTrack, val addedAt: Instant?)

internal fun PaginationResult<MetadataTrack>.withPlaylistDates(): PaginationResult<PlaylistTrackEntry> {
    val dates = itemAddedAt?.takeIf { it.size == items.size }
    return PaginationResult(
        items = items.mapIndexed { index, track ->
            val addedAt = dates?.get(index)?.let { runCatching { Instant.parse(it) }.getOrNull() }
            PlaylistTrackEntry(track, addedAt)
        },
        totalCount = totalCount,
        nextPagination = nextPagination,
    )
}

internal fun List<PlaylistTrackEntry>.recentlyAddedTracks(): List<MetadataTrack>? =
    if (none { it.addedAt != null }) null
    else sortedByDescending { it.addedAt }.map { it.track }
