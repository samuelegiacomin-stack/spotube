/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.modules.downloads

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeDownloadWakeGateTest {
    @Test fun delegateDrainedCannotFinishBeforeMetadataCommitAndReplacementHandoff() {
        val gate = NativeDownloadWakeGate()
        gate.beginWake()
        gate.resultPersisted("first")
        gate.didDeliverEvents()
        gate.queueReady() // Initial reattachment is not acknowledgement of Kotlin postprocessing.
        assertFalse(gate.canFinish)
        gate.acknowledge("first") // Kotlin committed, but replacement URL is still resolving.
        assertFalse(gate.canFinish)
        gate.queueReady() // Replacement has reached URLSession and its mapping is persisted.
        assertTrue(gate.canFinish)
    }

    @Test fun allCompletionsMustBeAcknowledgedAndNewResultInvalidatesEarlierQueueMarker() {
        val gate = NativeDownloadWakeGate()
        gate.beginWake()
        gate.resultPersisted("a")
        gate.resultPersisted("b")
        gate.didDeliverEvents()
        gate.acknowledge("a")
        gate.queueReady()
        assertFalse(gate.canFinish)
        gate.acknowledge("b")
        assertFalse(gate.canFinish)
        gate.queueReady()
        assertTrue(gate.canFinish)
        gate.resultPersisted("c")
        assertFalse(gate.canFinish)
    }

    @Test fun deadlineFinishRetainsUnacknowledgedResultForNextLaunch() {
        val gate = NativeDownloadWakeGate()
        gate.beginWake()
        gate.resultPersisted("audio-already-moved")
        gate.didDeliverEvents()
        gate.finishWake() // UIKit time expired: journals remain; this is not successful drain.
        gate.beginWake()
        gate.didDeliverEvents()
        gate.queueReady()
        assertFalse(gate.canFinish)
        gate.acknowledge("audio-already-moved")
        gate.queueReady()
        assertTrue(gate.canFinish)
    }

    @Test fun earlyQueueReadyStillWaitsForFinalDelegateEvent() {
        val gate = NativeDownloadWakeGate()
        gate.beginWake()
        gate.queueReady()
        assertFalse(gate.canFinish)
        gate.didDeliverEvents()
        assertTrue(gate.canFinish)
    }
}
