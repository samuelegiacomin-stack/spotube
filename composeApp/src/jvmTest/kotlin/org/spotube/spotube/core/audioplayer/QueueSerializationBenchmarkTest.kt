/* Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.audioplayer

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.measureTime

/** Local JVM work-count benchmark, not an iPhone startup/playback timing claim. */
class QueueSerializationBenchmarkTest {
    private class EncodingRepository : QueueStateRepository {
        var encodes = 0
        var bytes = 0L
        override suspend fun getPersistedState(): PersistedQueueState? = null
        override suspend fun clearState() {}
        override suspend fun saveState(state: PersistedQueueState) {
            val payload = Json.encodeToString(state)
            bytes += payload.length
            encodes++
        }
    }
    @Test fun repeatedLargeQueueSnapshotsAvoidRepeatedJsonWork() = runBlocking {
        val state = PersistedQueueState((1..5000).map {
            QueueEntry.LocalTrack("Song $it", listOf("Artist"), 180000, "Album", url = "/Music/$it.m4a")
        }, 0)
        Json.encodeToString(state) // Warm serializer initialization outside timing.
        val legacy = EncodingRepository()
        val improved = EncodingRepository()
        val repository = DeduplicatingQueueStateRepository(improved)
        val before = measureTime { repeat(20) { legacy.saveState(state) } }
        val after = measureTime { repeat(20) { repository.saveState(state.copy()) } }
        assertEquals(20, legacy.encodes)
        assertEquals(1, improved.encodes)
        assertEquals(legacy.bytes, improved.bytes * 20)
        println("LOCAL_JVM_JSON: 5000 tracks x 20 repeated snapshots; before=$before encodes=${legacy.encodes} chars=${legacy.bytes}; after=$after encodes=${improved.encodes} chars=${improved.bytes}")
    }
}
