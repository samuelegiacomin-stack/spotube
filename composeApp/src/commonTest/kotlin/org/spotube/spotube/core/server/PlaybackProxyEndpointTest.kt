package org.spotube.spotube.core.server

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.spotube.plugin_interfaces.plugin_apis.audio.StreamProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackProxyEndpointTest {
    @Test fun waitsForTheSocketToBeBound() = runTest {
        val endpoint = PlaybackProxyEndpoint()
        val result = async { endpoint.url("track", StreamProtocol.PROGRESSIVE) }
        runCurrent()
        assertFalse(result.isCompleted)
        endpoint.publish(24001)
        assertEquals("http://127.0.0.1:24001/stream/track", result.await())
    }

    @Test fun usesNewPortAfterRestartAndManifestRoute() = runTest {
        val endpoint = PlaybackProxyEndpoint()
        endpoint.publish(14769)
        endpoint.publish(null)
        val result = async { endpoint.url("track", StreamProtocol.HLS) }
        runCurrent()
        assertFalse(result.isCompleted)
        endpoint.publish(24002)
        assertEquals("http://127.0.0.1:24002/manifest/track", result.await())
    }

    @Test fun onlyRewritesOurLoopbackRoutes() {
        assertTrue(isPlaybackProxyUrl("http://127.0.0.1:9999/stream/track"))
        assertTrue(isPlaybackProxyUrl("http://localhost:9999/manifest/track"))
        assertFalse(isPlaybackProxyUrl("https://example.com/stream/track"))
        assertFalse(isPlaybackProxyUrl("file:///music/track.m4a"))
    }
}
