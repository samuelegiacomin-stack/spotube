/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.modules.downloads

/** Main-thread confined by Swift. UIKit completion requires every independent barrier. */
class NativeDownloadWakeGate {
    private var eventsDelivered = false
    private var queueCycleReady = false
    private val unacknowledged = mutableSetOf<String>()

    val canFinish: Boolean get() = eventsDelivered && queueCycleReady && unacknowledged.isEmpty()

    fun beginWake() { queueCycleReady = false }
    fun didDeliverEvents() { eventsDelivered = true }
    fun resultPersisted(id: String) { unacknowledged.add(id); queueCycleReady = false }
    fun acknowledge(id: String) { unacknowledged.remove(id); queueCycleReady = false }
    fun queueReady() { queueCycleReady = true }
    // Unacknowledged results survive deadline expiration and are replayed on the next wake.
    fun finishWake() { eventsDelivered = false; queueCycleReady = false }
}
