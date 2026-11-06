/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.core.playback

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy

/** Fetch known offset pages in bounded batches; preserve order and never return a partial
 * collection after a failed page. Cursor-based plugins retain their sequential contract. */
internal suspend fun <T> loadCollectionPages(
    fetch: suspend (PaginationStrategy?) -> PaginationResult<T>?,
): List<T> {
    val first = fetch(PaginationStrategy.Offset(0, 100)) ?: return emptyList()
    val items = first.items.toMutableList()
    var next = first.nextPagination ?: return items
    if (next is PaginationStrategy.Offset && next.offset > 0 && next.limit in 1..100 &&
        first.totalCount in 1..100_000) {
        // A provider may clamp the requested page size; the first next offset is the
        // actual stride. Respect it instead of skipping items between concurrent pages.
        val limit = minOf(next.limit, next.offset)
        val offsets = (next.offset until first.totalCount step limit).toList()
        for (batch in offsets.chunked(4)) {
            val pages = coroutineScope {
                batch.map { offset -> async {
                    checkNotNull(fetch(PaginationStrategy.Offset(offset, limit))) { "Collection page unavailable" }
                } }.awaitAll()
            }
            pages.forEach { items.addAll(it.items) }
        }
        return items
    }
    val visited = mutableSetOf<PaginationStrategy>()
    while (true) {
        check(visited.add(next) && visited.size <= 1_000) { "Collection pagination did not advance" }
        val page = checkNotNull(fetch(next)) { "Collection page unavailable" }
        items.addAll(page.items)
        next = page.nextPagination ?: return items
    }
}
