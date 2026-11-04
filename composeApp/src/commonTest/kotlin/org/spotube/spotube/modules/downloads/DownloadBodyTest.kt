package org.spotube.spotube.modules.downloads

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DownloadBodyTest {
    @Test fun copiesInChunksAndReportsProgressBeforeCompletion() = runTest {
        val audio = ByteArray(800_000) { (it % 251).toByte() }
        val target = Buffer()
        val chunks = mutableListOf<Long>()
        copyDownloadBody(ByteReadChannel(audio), target, audio.size.toLong()) { chunks += it }
        assertContentEquals(audio, target.readByteArray())
        assertEquals(audio.size.toLong(), chunks.sum())
        assertTrue(chunks.size >= 4)
        assertTrue(chunks.all { it in 1..262_144 })
    }

    @Test fun acceptsUnknownContentLength() = runTest {
        val target = Buffer()
        copyDownloadBody(ByteReadChannel(byteArrayOf(1, 2, 3)), target, 0) {}
        assertEquals(3L, target.size)
    }

    @Test fun rejectsTruncatedAudio() = runTest {
        assertFailsWith<IncompleteDownloadException> {
            copyDownloadBody(ByteReadChannel(byteArrayOf(1, 2)), Buffer(), 10) {}
        }
    }

    @Test fun rejectsEmptyResponse() = runTest {
        assertFailsWith<IncompleteDownloadException> {
            copyDownloadBody(ByteReadChannel(byteArrayOf()), Buffer(), 0) {}
        }
    }
}
