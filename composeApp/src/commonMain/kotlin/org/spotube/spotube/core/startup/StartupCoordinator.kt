/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.core.startup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface StartupState {
    data object Idle : StartupState
    data object Loading : StartupState
    data object Ready : StartupState
    data class Failed(val canRetry: Boolean) : StartupState
}

/** Initializes suspended dependencies before any eager service or UI can use them. */
internal class StartupCoordinator(
    private val scope: CoroutineScope,
    private val initialize: suspend () -> Unit,
    private val startServices: () -> Unit,
    private val onFailure: (Exception) -> Unit = {},
) {
    private val _state = MutableStateFlow<StartupState>(StartupState.Idle)
    val state = _state.asStateFlow()

    fun start() {
        while (true) {
            val current = _state.value
            if (current != StartupState.Idle && current != StartupState.Failed(canRetry = true)) return
            if (_state.compareAndSet(current, StartupState.Loading)) break
        }
        scope.launch {
            var servicesAttempted = false
            try {
                initialize()
                servicesAttempted = true
                startServices()
                _state.value = StartupState.Ready
            } catch (cancelled: CancellationException) {
                _state.value = if (servicesAttempted) StartupState.Failed(false) else StartupState.Idle
                throw cancelled
            } catch (error: Exception) {
                // Retrying partly created eager services could start a second download manager.
                _state.value = StartupState.Failed(canRetry = !servicesAttempted)
                onFailure(error)
            }
        }
    }
}
