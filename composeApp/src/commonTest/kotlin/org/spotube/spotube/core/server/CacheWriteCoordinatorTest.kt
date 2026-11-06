/* Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.server

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CacheWriteCoordinatorTest {
    @Test fun activeNetworkReaderSurvivesOverlappingRequestAndClearsOnCancellation() = runTest {
        val activity = PlaybackNetworkActivity()
        val entered = CompletableDeferred<Unit>()
        val reader = launch {
            activity.withRequest("song") {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        activity.withRequest("song") { assertEquals(setOf("song"), activity.active.value) }
        assertEquals(setOf("song"), activity.active.value)
        reader.cancelAndJoin()
        assertTrue(activity.active.value.isEmpty())
    }

    @Test fun overlappingPlayerRequestNeverWaitsForWholeSong() = runTest {
        val coordinator = CacheWriteCoordinator()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val first = launch {
            coordinator.withLease("song") { owns ->
                assertTrue(owns)
                entered.complete(Unit)
                finish.await()
            }
        }
        entered.await()
        withTimeout(100) {
            coordinator.withLease("song") { assertFalse(it) }
            coordinator.withLease("other") { assertTrue(it) }
        }
        finish.complete(Unit)
        first.join()
        coordinator.withLease("song") { assertTrue(it) }
    }

    @Test fun cancelledWriterReleasesLeaseAndHeadDoesNotTakeIt() = runTest {
        val coordinator = CacheWriteCoordinator()
        coordinator.withLease("song", eligible = false) { owns ->
            assertFalse(owns)
            coordinator.withLease("song") { assertTrue(it) }
        }
        val entered = CompletableDeferred<Unit>()
        val writer = launch {
            coordinator.withLease("song") {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        writer.cancelAndJoin()
        coordinator.withLease("song") { assertTrue(it) }
    }
}
