/*
 * Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.library.playlist

import kotlinx.coroutines.test.runTest
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.PaginationResult
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.PaginationStrategy
import org.spotube.plugin_interfaces.plugin_apis.metadata.playlist.MetadataPlaylist
import org.spotube.plugin_interfaces.plugin_apis.metadata.user.MetadataUser
import kotlin.test.*

class OwnedPlaylistsTest {
    private fun playlist(id: String, owner: String? = "me") = MetadataPlaylist(
        id, id, null, emptyList(), 0, null,
        owner?.let { MetadataUser(it, it, it, emptyList(), null) })

    @Test fun findsOwnPlaylistBeyondFirstTwentyAndFiltersOthers() = runTest {
        val next = PaginationStrategy.Offset(20, 20)
        val result = loadOwnedPlaylists("me", { page ->
            if (page == null) PaginationResult((1..20).map { playlist("$it", "other") }, 21, next)
            else PaginationResult(listOf(playlist("mine")), 21, null)
        }, { error("Complete ownership should not need extra requests") })
        assertEquals(listOf("mine"), result.map { it.id })
    }
    @Test fun missingSummaryOwnerIsResolvedFromDetails() = runTest {
        val result = loadOwnedPlaylists("me", {
            PaginationResult(listOf(playlist("mine", null), playlist("other", null)), 2, null)
        }, { id -> playlist(id, if (id == "mine") "me" else "other") })
        assertEquals(listOf("mine"), result.map { it.id })
    }
    @Test fun repeatedPaginationTerminatesAndDeduplicates() = runTest {
        var calls = 0
        val next = PaginationStrategy.Cursor("same", 20)
        val result = loadOwnedPlaylists("me", {
            calls++
            PaginationResult(listOf(playlist("mine")), 1, next)
        }, { null })
        assertEquals(2, calls)
        assertEquals(1, result.size)
    }
    @Test fun failedFetchIsNotReportedAsSuccessfulEmptyList() = runTest {
        assertFailsWith<IllegalStateException> {
            loadOwnedPlaylists("me", { error("Session expired") }, { null })
        }
    }
}
