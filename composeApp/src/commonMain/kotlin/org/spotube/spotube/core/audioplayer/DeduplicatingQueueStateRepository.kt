/* Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.audioplayer

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serialize writes and avoid re-encoding/re-writing the same queue from simultaneous observers. */
class DeduplicatingQueueStateRepository(private val delegate: QueueStateRepository) : QueueStateRepository {
    private val mutex = Mutex()
    private var initialized = false
    private var stored: PersistedQueueState? = null
    override suspend fun getPersistedState(): PersistedQueueState? = mutex.withLock {
        delegate.getPersistedState().also { stored = it; initialized = true }
    }
    override suspend fun saveState(state: PersistedQueueState) = mutex.withLock {
        if (!initialized || stored != state) {
            delegate.saveState(state)
            stored = state
            initialized = true
        }
    }
    override suspend fun clearState() = mutex.withLock {
        if (!initialized || stored != null) {
            delegate.clearState()
            stored = null
            initialized = true
        }
    }
}
