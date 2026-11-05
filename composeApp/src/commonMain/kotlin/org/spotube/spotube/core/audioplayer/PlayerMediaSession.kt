/* Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.audioplayer

interface PlayerMediaSession {
    fun configure(onPlay: () -> Unit, onPause: () -> Unit, onSeek: (Double) -> Unit, onNext: () -> Unit, onPrevious: () -> Unit)
    fun activate()
    fun deactivate()
    fun update(item: MediaItem?, positionSeconds: Double, durationSeconds: Double, isPlaying: Boolean)
    fun clear()
}
