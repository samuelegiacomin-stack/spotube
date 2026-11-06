/* Copyright (C) 2026 Spotube Contributors. SPDX-License-Identifier: AGPL-3.0-or-later */
package org.spotube.spotube.core.audioplayer

/** Expand a collection after playback starts, without reopening its current audio. */
interface SeamlessPlaylistUpdater {
    suspend fun replacePlaylistKeepingCurrent(expectedUrls: List<String>, playlist: List<MediaItem>): Boolean
}
