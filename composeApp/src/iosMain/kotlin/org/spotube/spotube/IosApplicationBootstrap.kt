/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube

import com.yushosei.newpipe.extractor.NewPipe
import com.yushosei.newpipe.util.DefaultDownloaderImpl
import org.spotube.spotube.core.di.initKoin
import org.spotube.spotube.core.paths.Paths

/** Main-thread startup shared by SwiftUI and a URLSession background launch with no visible view. */
object IosApplicationBootstrap {
    private var started = false

    fun start() {
        if (started) return
        Paths().prepareUserFolders()
        NewPipe.init(DefaultDownloaderImpl.initDefault())
        initKoin()
        started = true
    }
}
