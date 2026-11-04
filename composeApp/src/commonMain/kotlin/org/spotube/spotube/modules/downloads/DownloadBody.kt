package org.spotube.spotube.modules.downloads

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.BufferedSink

/** Copy with bounded memory, reporting each chunk rather than buffering a whole song. */
internal suspend fun copyDownloadBody(
    channel: ByteReadChannel,
    sink: BufferedSink,
    expectedBytes: Long,
    onChunk: (Long) -> Unit
) {
    val buffer = ByteArray(256 * 1024)
    var received = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = channel.readAvailable(buffer, 0, buffer.size)
        if (count < 0) break
        if (count == 0) continue
        sink.write(buffer, 0, count)
        received += count
        onChunk(count.toLong())
    }
    if (received == 0L || (expectedBytes > 0 && received != expectedBytes)) {
        throw IncompleteDownloadException()
    }
}

internal class IncompleteDownloadException : Exception()
