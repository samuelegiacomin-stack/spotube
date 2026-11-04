package org.spotube.spotube.modules.downloads

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.spotube.spotube.core.network.HttpClientFactory
import org.spotube.spotube.core.server.startServerWithPortFallback
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadTransferTest {
    private fun scenario(mode: String) = runBlocking {
        val audio = Random(42).nextBytes(2 * 1024 * 1024 + 17)
        val parallel = AtomicInteger()
        val maxParallel = AtomicInteger()
        val ranges = AtomicInteger()
        val full = AtomicInteger()
        val bound = startServerWithPortFallback("127.0.0.1", 0) {
            routing { get("/audio") {
                val rangeHeader = call.request.headers[HttpHeaders.Range]
                val isProbe = rangeHeader == "bytes=0-0"
                if (rangeHeader != null && mode != "no-ranges" && (isProbe || mode != "ignore-parts")) {
                    ranges.incrementAndGet()
                    val limits = rangeHeader.removePrefix("bytes=").split('-').map(String::toInt)
                    val start = limits[0]
                    val end = limits[1]
                    if (!isProbe) {
                        val active = parallel.incrementAndGet()
                        maxParallel.updateAndGet { maxOf(it, active) }
                        delay(50)
                        parallel.decrementAndGet()
                    }
                    val reportedEnd = if (mode == "wrong-range" && !isProbe) end - 1 else end
                    call.response.headers.append(HttpHeaders.ContentRange, "bytes $start-$reportedEnd/${audio.size}")
                    call.respondBytes(audio.copyOfRange(start, end + 1), ContentType.Audio.Any, HttpStatusCode.PartialContent)
                } else {
                    if (rangeHeader == null) full.incrementAndGet()
                    call.respondBytes(audio, ContentType.Audio.Any)
                }
            } }
        }
        val directory = Files.createTempDirectory("spotube-transfer").toOkioPath()
        val output = directory / "song.part"
        val factory = HttpClientFactory()
        var restarts = 0
        var received = 0L
        try {
            DownloadTransfer(factory.default, FileSystem.SYSTEM).download(
                "http://127.0.0.1:${bound.port}/audio", output,
                onRestart = { restarts++; received = 0 }, onChunk = { size, _ -> received += size })
            assertContentEquals(audio, Files.readAllBytes(java.nio.file.Path.of(output.toString())))
            assertEquals(audio.size.toLong(), received)
            assertEquals(listOf(output), FileSystem.SYSTEM.list(directory))
            if (mode == "supported") {
                assertEquals(5, ranges.get())
                assertEquals(0, full.get())
                assertTrue(maxParallel.get() >= 2)
                assertEquals(0, restarts)
            } else {
                assertEquals(1, full.get())
                assertEquals(if (mode == "no-ranges") 0 else 1, restarts)
            }
        } finally {
            factory.close()
            bound.engine.stop(0, 500)
            FileSystem.SYSTEM.list(directory).forEach { FileSystem.SYSTEM.delete(it) }
            FileSystem.SYSTEM.delete(directory)
        }
    }

    @Test fun downloadsPartsConcurrentlyWithoutChangingAudio() = scenario("supported")
    @Test fun fallsBackIfRangesAreIgnoredAfterProbe() = scenario("ignore-parts")
    @Test fun fallsBackIfContentRangeIsWrong() = scenario("wrong-range")
    @Test fun supportsServersWithoutRanges() = scenario("no-ranges")
}
