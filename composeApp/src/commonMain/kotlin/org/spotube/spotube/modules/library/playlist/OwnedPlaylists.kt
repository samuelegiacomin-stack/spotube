/*
 * Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.library.playlist

import org.spotube.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import org.spotube.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylist

internal suspend fun loadOwnedPlaylists(
    userId: String,
    fetchPage: suspend (PaginationStrategy?) -> PaginationResult<MetadataPlaylist>?,
    fetchDetails: suspend (String) -> MetadataPlaylist?,
): List<MetadataPlaylist> {
    val result = linkedMapOf<String, MetadataPlaylist>()
    val visited = mutableSetOf<PaginationStrategy>()
    var pagination: PaginationStrategy? = null
    do {
        val page = fetchPage(pagination) ?: break
        for (candidate in page.items) {
            val playlist = if (candidate.owner == null) fetchDetails(candidate.id) ?: candidate else candidate
            if (playlist.owner?.id == userId) result[playlist.id] = playlist
        }
        pagination = page.nextPagination
    } while (pagination != null && visited.add(pagination) && visited.size < 1_000)
    return result.values.toList()
}
