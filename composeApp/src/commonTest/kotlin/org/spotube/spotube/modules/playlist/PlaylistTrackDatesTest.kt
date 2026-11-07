/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.modules.playlist

import kotlinx.serialization.json.Json
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlin.test.*

class PlaylistTrackDatesTest {
    private fun track(id: String) =
        MetadataTrack(id, id, 100_000, null, null, emptyList(), null, null, null, null, null, null)

    @Test fun sortsBySpotifyTimestampEvenWhenPlaylistOrderWasEdited() {
        val repeated = track("repeated")
        val tracks = listOf(repeated, track("newest"), track("unknown"), repeated, track("tie"))
        val dates = listOf("2025-01-01T00:00:00Z", "2026-11-07T12:00:00Z", "invalid",
            "2026-09-07T12:00:00Z", "2026-09-07T14:00:00+02:00")
        val page = PaginationResult(tracks, tracks.size, null, itemAddedAt = dates).withPlaylistDates()
        assertEquals(listOf("newest", "repeated", "tie", "repeated", "unknown"),
            page.items.recentlyAddedTracks()?.map { it.id })
        assertEquals(tracks, page.items.map { it.track })
    }

    @Test fun oldPluginPayloadAndMisalignedDatesKeepOriginalOrderAvailable() {
        val legacy = Json.decodeFromString<PaginationResult<Int>>(
            """{"items":[1],"totalCount":1,"nextPagination":null}""")
        assertNull(legacy.itemAddedAt)
        val tracks = listOf(track("a"), track("b"))
        val incomplete = PaginationResult(tracks, 2, null, itemAddedAt = listOf("2026-01-01T00:00:00Z"))
        val page = incomplete.withPlaylistDates()
        assertNull(page.items.recentlyAddedTracks())
        assertEquals(tracks, page.items.map { it.track })
    }
}
