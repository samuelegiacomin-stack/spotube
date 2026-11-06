/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube

import com.yushosei.newpipe.extractor.NewPipe
import com.yushosei.newpipe.util.DefaultDownloaderImpl
import org.spotube.spotube.core.di.initKoin
import org.spotube.spotube.core.paths.Paths
import org.spotube.spotube.core.startup.StartupCoordinator
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Main-thread startup shared by SwiftUI and a URLSession background launch with no visible view. */
object IosApplicationBootstrap {
    private val coordinator = StartupCoordinator(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        initialize = {
            Paths().prepareUserFolders()
            NewPipe.init(DefaultDownloaderImpl.initDefault())
        },
        startServices = { initKoin() },
        onFailure = { Logger.e(it) { "iOS application initialization failed" } },
    )
    val state = coordinator.state

    fun start() = coordinator.start()
}
