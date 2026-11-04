package org.spotube.spotube.core.server

import io.ktor.http.Url
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.spotube.plugin_interfaces.plugin_apis.audio.StreamProtocol

/** Shared independently of the queue/server, avoiding a dependency cycle during startup. */
class PlaybackProxyEndpoint {
    private val boundPort = MutableStateFlow<Int?>(null)
    val port = boundPort.asStateFlow()

    fun publish(port: Int?) {
        require(port == null || port in 1..65535)
        boundPort.value = port
    }

    suspend fun url(trackId: String, protocol: StreamProtocol): String {
        val readyPort = withTimeout(10_000) { port.filterNotNull().first() }
        val route = if (protocol == StreamProtocol.PROGRESSIVE) "stream" else "manifest"
        return "http://127.0.0.1:$readyPort/$route/${trackId.encodeURLPathPart()}"
    }
}

internal fun isPlaybackProxyUrl(value: String): Boolean = runCatching {
    val url = Url(value)
    url.host in setOf("127.0.0.1", "localhost", "::1") &&
        (url.encodedPath.startsWith("/stream/") || url.encodedPath.startsWith("/manifest/"))
}.getOrDefault(false)
