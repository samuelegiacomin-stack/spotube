/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.modules.downloads

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.spotube.spotube.core.audioplayer.PlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadPlaybackPriorityTest {
    @Test fun bufferingStopsAllThreeAndPlayingResumesTwo() = runTest {
        val priority = DownloadPlaybackPriority()
        val ids = listOf("first", "second", "third")
        val passed = mutableSetOf<String>()
        priority.update(ids, PlayerState.BUFFERING)
        val jobs = ids.map { id -> launch { priority.await(id); passed.add(id) } }
        runCurrent()
        assertTrue(passed.isEmpty())
        priority.update(ids, PlayerState.PLAYING)
        runCurrent()
        assertEquals(setOf("first", "second"), passed)
        priority.update(ids, PlayerState.PAUSED)
        runCurrent()
        assertEquals(ids.toSet(), passed)
        jobs.forEach { it.join() }
    }

    @Test fun pausedDownloadWaitIsCancellableAndDoesNotHoldTheNextSlot() = runTest {
        val priority = DownloadPlaybackPriority()
        priority.update(listOf("cancelled", "next"), PlayerState.BUFFERING)
        val cancelled = launch { priority.await("cancelled"); error("Cancelled download resumed") }
        var resumed = false
        val next = launch { priority.await("next"); resumed = true }
        runCurrent()
        cancelled.cancel()
        priority.update(listOf("next"), PlayerState.PLAYING)
        runCurrent()
        assertTrue(cancelled.isCancelled)
        assertTrue(resumed)
        next.join()
        assertEquals(3, downloadConcurrency(PlayerState.IDLE))
    }
}
