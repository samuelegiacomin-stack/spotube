/* Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.server

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Tracks real upstream readers, including concurrent range requests still draining to VLC. */
internal class PlaybackNetworkActivity {
    private val mutex = Mutex()
    private val counts = mutableMapOf<String, Int>()
    private val _active = MutableStateFlow<Set<String>>(emptySet())
    val active = _active.asStateFlow()

    suspend fun <T> withRequest(trackId: String, block: suspend () -> T): T {
        mutex.withLock {
            counts[trackId] = (counts[trackId] ?: 0) + 1
            _active.value = counts.keys.toSet()
        }
        try { return block() }
        finally { withContext(NonCancellable) {
            mutex.withLock {
                val remaining = (counts[trackId] ?: 1) - 1
                if (remaining <= 0) counts.remove(trackId) else counts[trackId] = remaining
                _active.value = counts.keys.toSet()
            }
        } }
    }
}
