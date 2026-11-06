/*
 * Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.modules.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PlaylistAdditionVerificationTest {
    @Test fun silentPluginNoOpIsNotConfirmedAndIsNeverRetried() = runTest {
        var writes = 0
        var reads = 0
        val confirmed = writeAndConfirmPlaylistAddition(listOf("new"), { writes++ },
            { reads++; 1 }, { _, _ -> listOf("old") })
        assertFalse(confirmed)
        assertEquals(1, writes, "Adding again would risk duplicate songs")
        assertEquals(3, reads)
    }

    @Test fun appendedTrackIsVerifiedAtTailOfLargePlaylist() = runTest {
        val pages = mutableListOf<Pair<Int, Int>>()
        val confirmed = writeAndConfirmPlaylistAddition(listOf("new"), {}, { 5001 },
            { offset, limit -> pages += offset to limit; listOf("new") })
        assertTrue(confirmed)
        assertEquals(listOf(4951 to 50), pages)
    }

    @Test fun eventuallyVisibleAddRetriesOnlyReads() = runTest {
        var writes = 0
        var reads = 0
        assertTrue(writeAndConfirmPlaylistAddition(listOf("new"), { writes++ },
            { 2 }, { _, _ -> reads++; if (reads < 3) listOf("old") else listOf("old", "new") }))
        assertEquals(1, writes)
        assertEquals(3, reads)
    }

    @Test fun wholeBatchMustBePresentAcrossReadPages() = runTest {
        val tracks = (1..250).map { "track$it" }
        var reads = 0
        assertTrue(writeAndConfirmPlaylistAddition(tracks, {}, { 1000 }, { offset, limit ->
            reads++
            tracks.drop(offset - 750).take(limit)
        }))
        assertEquals(3, reads)
        assertFalse(writeAndConfirmPlaylistAddition(listOf("one", "two"), {}, { 1 },
            { _, _ -> listOf("one") }))
    }

    @Test fun mutationFailurePreventsReadbackAndPropagatesRootCause() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            writeAndConfirmPlaylistAddition(listOf("new"), { error("NotAuthorized") },
                { error("Must not read after failed mutation") }, { _, _ -> emptyList() })
        }
        assertEquals("NotAuthorized", error.message)
    }

    @Test fun cancelledOrFailedReadDoesNotClaimSuccessOrRepeatTheWrite() = runTest {
        var writes = 0
        assertFailsWith<CancellationException> {
            writeAndConfirmPlaylistAddition(listOf("new"), { writes++ }, { 1 },
                { _, _ -> throw CancellationException("cancelled") })
        }
        assertEquals(1, writes)
    }

    @Test fun emptyRequestDoesNoNetworkWork() = runTest {
        assertTrue(writeAndConfirmPlaylistAddition(emptyList(), { error("write") },
            { error("count") }, { _, _ -> error("page") }))
    }
}
