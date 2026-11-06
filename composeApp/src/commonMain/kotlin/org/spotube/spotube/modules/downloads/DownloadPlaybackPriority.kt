/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.modules.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.spotube.spotube.core.audioplayer.PlayerState

/** Stop reading download sockets during buffering; TCP backpressure leaves bandwidth to playback. */
internal class DownloadPlaybackPriority {
    private val allowed = MutableStateFlow<Set<String>>(emptySet())

    fun update(active: List<String>, state: PlayerState): Set<String> {
        val permitted = active.take(downloadConcurrency(state)).toSet()
        allowed.value = permitted
        return permitted
    }

    suspend fun await(id: String) { allowed.first { id in it } }
}

internal fun downloadConcurrency(state: PlayerState): Int = when (state) {
    PlayerState.BUFFERING -> 0
    PlayerState.PLAYING -> 2
    else -> DownloadManager.MAX_CONCURRENT_DOWNLOADS
}
