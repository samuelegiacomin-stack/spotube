/* Copyright (C) 2026 Spotube Contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.spotube.spotube.core.server

import io.ktor.http.*
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.spotube.spotube.core.network.HttpClientFactory
import java.nio.file.Files
import kotlin.test.*

class PreparedAudioTransferTest {
    private fun scenario(test: suspend (String, org.spotube.spotube.core.network.HttpClientFactory, okio.Path) -> Unit) = runBlocking {
        val audio = ByteArray(128 * 1024) { (it % 127).toByte() }
        val bound = startServerWithPortFallback("127.0.0.1", 0) {
            routing {
                get("/audio") { call.respondBytes(audio, ContentType.Audio.MPEG) }
                get("/partial") { call.respondBytes(audio, ContentType.Audio.MPEG, HttpStatusCode.PartialContent) }
                get("/manifest") { call.respondBytes("#EXTM3U".encodeToByteArray(), ContentType.parse("application/vnd.apple.mpegurl")) }
                get("/error") { call.respondBytes("failure".encodeToByteArray(), status = HttpStatusCode.Forbidden) }
                get("/empty") { call.respondBytes(byteArrayOf(), ContentType.Audio.MPEG) }
            }
        }
        val factory = HttpClientFactory()
        val directory = Files.createTempDirectory("spotube-warm-audio").toOkioPath()
        try { test("http://127.0.0.1:${bound.port}", factory, directory) }
        finally {
            factory.close()
            bound.engine.stop(0, 500)
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test fun warmAudioPreservesEveryByte() = scenario { base, factory, dir ->
        val result = transferPreparedAudio(factory.default, "$base/audio", FileSystem.SYSTEM, dir / "song")
        assertEquals(128L * 1024, result?.sizeBytes)
        assertEquals("audio/mpeg", result?.contentType)
        assertContentEquals(ByteArray(128 * 1024) { (it % 127).toByte() }, Files.readAllBytes(java.nio.file.Path.of((dir / "song").toString())))
    }

    @Test fun rejectsPartialManifestErrorEmptyAndOversizedAudio() = scenario { base, factory, dir ->
        listOf("partial", "manifest", "error", "empty").forEach {
            assertNull(transferPreparedAudio(factory.default, "$base/$it", FileSystem.SYSTEM, dir / it))
            assertFalse(FileSystem.SYSTEM.exists(dir / it))
        }
        assertNull(transferPreparedAudio(factory.default, "$base/audio", FileSystem.SYSTEM, dir / "large", maximumBytes = 16))
        assertFalse(FileSystem.SYSTEM.exists(dir / "large"))
    }
}
