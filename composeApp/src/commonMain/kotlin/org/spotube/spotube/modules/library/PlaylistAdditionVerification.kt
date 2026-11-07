/*
 * Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.library

import kotlinx.coroutines.delay

/** Old plugins may return Unit after an empty/error mutation response. Read the uncached
 * playlist tail before showing success. Retry reads only: repeating an add can duplicate songs.
 */
internal suspend fun writeAndConfirmPlaylistAddition(
    trackIds: List<String>,
    write: suspend () -> Unit,
    fetchCount: suspend () -> Int,
    fetchTrackIds: suspend (offset: Int, limit: Int) -> List<String>,
): Boolean {
    if (trackIds.isEmpty()) return true
    val countBefore = fetchCount().coerceAtLeast(0)
    write()
    val expected = trackIds.groupingBy { it }.eachCount()
    repeat(3) { attempt ->
        if (attempt > 0) delay(300L)
        val count = fetchCount().coerceAtLeast(0)
        // An old occurrence alone cannot prove that this mutation added anything.
        if (count < countBefore + trackIds.size) return@repeat
        val missing = expected.toMutableMap()
        // Spotify appends at the bottom. Read only the new positions so a
        // preexisting copy cannot confirm an unrelated change in the count.
        var offset = countBefore
        while (offset < count && missing.isNotEmpty()) {
            val limit = minOf(100, count - offset)
            for (id in fetchTrackIds(offset, limit)) {
                val remaining = missing[id] ?: continue
                if (remaining == 1) missing.remove(id) else missing[id] = remaining - 1
            }
            offset += limit
        }
        if (missing.isEmpty()) return true
    }
    return false
}
