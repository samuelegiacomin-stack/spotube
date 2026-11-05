/* Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.audioplayer

/** The system player extrapolates elapsed time; publish changes and at most one forward tick/second. */
internal class NowPlayingUpdateGate {
    private var item: MediaItem? = null
    private var position = Double.NaN
    private var duration = Double.NaN
    private var playing = false
    fun shouldPublish(next: MediaItem?, seconds: Double, length: Double, isPlaying: Boolean): Boolean {
        val changed = next !== item || isPlaying != playing || length != duration ||
            !position.isFinite() || seconds < position || seconds - position >= 1.0
        if (changed) {
            item = next; position = seconds; duration = length; playing = isPlaying
        }
        return changed
    }
    fun reset() { item = null; position = Double.NaN }
}
