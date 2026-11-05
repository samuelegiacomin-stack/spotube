/* Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.audioplayer

import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import kotlin.test.*

class NextTrackPrefetcherTest {
    private fun stream(id: String, url: String = "http://127.0.0.1:14769/stream/$id") = QueueEntry.StreamingTrack(
        MetadataTrack(id, id, 100000, null, null, emptyList(), null, null, null, null, null, null), url)
    @Test fun selectsOnlyImmediateNextProxyTrack() {
        val a = stream("a"); val b = stream("b"); val c = stream("c")
        assertEquals("b", nextTrackForPrefetch(true, a, listOf(a, b, c))?.id)
        assertNull(nextTrackForPrefetch(false, a, listOf(a, b)))
        assertNull(nextTrackForPrefetch(true, b, listOf(a, b)))
    }
    @Test fun doesNotLookPastLocalOrDirectRemoteMedia() {
        val a = stream("a"); val b = stream("b")
        val local = QueueEntry.LocalTrack("Own song", emptyList(), 1000, null, url = "/Music/own.m4a")
        assertNull(nextTrackForPrefetch(true, a, listOf(a, local, b)))
        assertNull(nextTrackForPrefetch(true, local, listOf(local, b)))
        assertNull(nextTrackForPrefetch(true, a, listOf(a, stream("b", "https://host.example/jam/b"))))
        assertNull(nextTrackForPrefetch(true, stream("missing"), listOf(a, b)))
    }
}
