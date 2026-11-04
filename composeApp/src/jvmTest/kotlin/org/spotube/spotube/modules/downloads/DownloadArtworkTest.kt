package org.spotube.spotube.modules.downloads

import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.spotube.spotube.core.network.HttpClientFactory
import org.spotube.spotube.core.server.startServerWithPortFallback
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadArtworkTest {
    private val image = ByteArray(128) { it.toByte() }

    private fun scenario(test: suspend (String, HttpClient, CompletableDeferred<Unit>) -> Unit) = runBlocking {
        val slowStarted = CompletableDeferred<Unit>()
        val bound = startServerWithPortFallback("127.0.0.1", 0) {
            routing {
                get("/cover") { call.respondBytes(image, ContentType.Image.PNG) }
                get("/missing") { call.respondBytes(image, status = HttpStatusCode.NotFound) }
                get("/slow") {
                    slowStarted.complete(Unit)
                    delay(5_000)
                    call.respondBytes(image, ContentType.Image.PNG)
                }
            }
        }
        val factory = HttpClientFactory()
        try {
            test("http://127.0.0.1:${bound.port}", factory.default, slowStarted)
        } finally {
            factory.close()
            bound.engine.stop(0, 500)
        }
    }

    @Test fun preservesArtworkBytes() = scenario { base, client, _ ->
        assertContentEquals(image, downloadArtwork(client, "$base/cover"))
    }

    @Test fun missingArtworkIsOptional() = scenario { base, client, _ ->
        assertNull(downloadArtwork(client, "$base/missing"))
        assertNull(downloadArtwork(client, null))
    }

    @Test fun rejectsOversizedArtwork() = scenario { base, client, _ ->
        assertNull(downloadArtwork(client, "$base/cover", maxBytes = 32))
    }

    @Test fun slowArtworkCannotBlockTheQueueIndefinitely() = scenario { base, client, _ ->
        assertNull(withTimeout(2_000) {
            downloadArtwork(client, "$base/slow", timeoutMillis = 250)
        })
    }

    @Test fun cancellingDownloadAlsoCancelsArtwork() = scenario { base, client, started ->
        coroutineScope {
            val request = async { downloadArtwork(client, "$base/slow") }
            withTimeout(2_000) { started.await() }
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
        }
    }
}
