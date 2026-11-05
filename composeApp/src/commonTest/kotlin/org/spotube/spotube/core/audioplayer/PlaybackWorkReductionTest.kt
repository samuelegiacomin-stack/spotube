/* Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.audioplayer

import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class PlaybackWorkReductionTest {
    private fun item(url: String) = MediaItem(url, "Artist", "Album", 100.seconds, "", url)
    @Test fun nowPlayingPublishesChangesWithoutRebuildingEveryPositionTick() {
        val gate = NowPlayingUpdateGate()
        val song = item("a")
        var publications = 0
        repeat(1000) { tick -> if (gate.shouldPublish(song, tick * .025, 100.0, true)) publications++ }
        println("NOW_PLAYING: 1000 position events -> $publications system metadata publications")
        assertEquals(25, publications)
        assertTrue(gate.shouldPublish(song, 24.975, 100.0, false))
        assertTrue(gate.shouldPublish(song, 4.0, 100.0, false))
        assertTrue(gate.shouldPublish(item("b"), 0.0, 100.0, true))
        gate.reset()
        assertTrue(gate.shouldPublish(song, 0.0, 100.0, true))
    }
    @Test fun concurrentIdenticalQueueSavesWriteOnlyOnceAndClearIsDurable() = runBlocking {
        var writes = 0
        var clears = 0
        val base = object : QueueStateRepository {
            override suspend fun getPersistedState(): PersistedQueueState? = null
            override suspend fun saveState(state: PersistedQueueState) { delay(2); writes++ }
            override suspend fun clearState() { clears++ }
        }
        val repository = DeduplicatingQueueStateRepository(base)
        val state = PersistedQueueState(emptyList(), 0)
        coroutineScope { repeat(100) { launch { repository.saveState(state.copy()) } } }
        assertEquals(1, writes)
        println("QUEUE_PERSISTENCE: 100 identical concurrent requests -> $writes delegate save/serialization call (not physical disk writes)")
        repository.clearState(); repository.clearState()
        assertEquals(1, clears)
        repository.saveState(state)
        assertEquals(2, writes)
    }
    @Test fun failedWriteRemainsRetryableAndRealChangesAreNotDropped() = runBlocking {
        var attempts = 0
        val base = object : QueueStateRepository {
            override suspend fun getPersistedState(): PersistedQueueState? = null
            override suspend fun saveState(state: PersistedQueueState) {
                attempts++
                if (attempts == 1) error("Disk temporarily unavailable")
            }
            override suspend fun clearState() {}
        }
        val repository = DeduplicatingQueueStateRepository(base)
        val first = PersistedQueueState(emptyList(), 0)
        assertFailsWith<IllegalStateException> { repository.saveState(first) }
        repository.saveState(first)
        repository.saveState(first.copy(currentIndex = 1))
        assertEquals(3, attempts)
    }
}
