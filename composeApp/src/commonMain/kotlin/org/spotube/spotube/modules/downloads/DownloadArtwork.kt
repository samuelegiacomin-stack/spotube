package org.spotube.spotube.modules.downloads

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray

/** Optional metadata must not buffer an unbounded response or hold the download queue indefinitely. */
internal suspend fun downloadArtwork(
    client: HttpClient,
    url: String?,
    timeoutMillis: Long = 8_000,
    maxBytes: Int = 8 * 1024 * 1024,
): ByteArray? {
    if (url.isNullOrBlank()) return null
    return withTimeoutOrNull(timeoutMillis) {
        client.prepareGet(url).execute { response ->
            if (!response.status.isSuccess()) return@execute null
            val body = response.bodyAsChannel().readRemaining(maxBytes.toLong() + 1)
            try {
                body.readByteArray().takeIf { it.isNotEmpty() && it.size <= maxBytes }
            } finally {
                body.close()
            }
        }
    }
}
