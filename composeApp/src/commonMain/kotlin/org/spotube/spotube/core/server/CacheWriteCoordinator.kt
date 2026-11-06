/* Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.server

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A busy cache writer must never make a player request wait for the entire song. */
internal class CacheWriteCoordinator {
    private val mutex = Mutex()
    private val writers = mutableSetOf<String>()

    suspend fun <T> withLease(trackId: String, eligible: Boolean = true, block: suspend (Boolean) -> T): T {
        val ownsLease = eligible && mutex.withLock { writers.add(trackId) }
        try {
            return block(ownsLease)
        } finally {
            if (ownsLease) withContext(NonCancellable) {
                mutex.withLock { writers.remove(trackId) }
            }
        }
    }
}
