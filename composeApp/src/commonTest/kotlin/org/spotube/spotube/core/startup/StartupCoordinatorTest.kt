/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.core.startup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class StartupCoordinatorTest {
    @Test fun concurrentForegroundAndBackgroundStartsWaitForDependencyBeforeServices() = runTest {
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val startup = StartupCoordinator(backgroundScope, {
            calls += "dependency"
            gate.await()
            calls += "initialized"
        }, { calls += "services" })
        repeat(30) { backgroundScope.launch { startup.start() } }
        runCurrent()
        assertEquals(listOf("dependency"), calls)
        assertEquals(StartupState.Loading, startup.state.value)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("dependency", "initialized", "services"), calls)
        assertEquals(StartupState.Ready, startup.state.value)
        startup.start()
        runCurrent()
        assertEquals(3, calls.size)
    }

    @Test fun dependencyFailureAllowsRetryWithoutStartingServicesEarly() = runTest {
        var attempts = 0
        var services = 0
        val startup = StartupCoordinator(backgroundScope,
            initialize = { if (++attempts == 1) error("dependency unavailable") },
            startServices = { services++ })
        startup.start(); runCurrent()
        assertEquals(StartupState.Failed(true), startup.state.value)
        assertEquals(0, services)
        startup.start(); runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, services)
        assertEquals(StartupState.Ready, startup.state.value)
    }

    @Test fun partiallyStartedServicesCannotBeDuplicatedByRetry() = runTest {
        var services = 0
        val startup = StartupCoordinator(backgroundScope, {}, { services++; error("service failure") })
        startup.start(); runCurrent()
        assertEquals(StartupState.Failed(false), startup.state.value)
        repeat(10) { startup.start() }
        runCurrent()
        assertEquals(1, services)
    }

    @Test fun cancelledInitializationCannotLeavePermanentLoadingOrStartServices() = runTest {
        var attempts = 0
        var services = 0
        val startup = StartupCoordinator(backgroundScope,
            initialize = { if (++attempts == 1) throw CancellationException("initialization cancelled") },
            startServices = { services++ })
        startup.start(); runCurrent()
        assertEquals(StartupState.Idle, startup.state.value)
        assertEquals(0, services)
        startup.start(); runCurrent()
        assertEquals(StartupState.Ready, startup.state.value)
        assertEquals(1, services)
    }
}
