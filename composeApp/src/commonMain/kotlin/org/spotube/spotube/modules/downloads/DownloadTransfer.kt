package org.spotube.spotube.modules.downloads

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import org.spotube.spotube.core.i18n.getString
import spotube.composeapp.generated.resources.*

internal data class DownloadRange(val start: Long, val end: Long, val total: Long)
internal fun parseDownloadRange(value: String?): DownloadRange? {
    val parts = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
        .matchEntire(value?.trim().orEmpty())?.groupValues ?: return null
    val start = parts[1].toLongOrNull() ?: return null
    val end = parts[2].toLongOrNull() ?: return null
    val total = parts[3].toLongOrNull() ?: return null
    return DownloadRange(start, end, total).takeIf { start <= end && end < total }
}

/** Parallel parts of ONE song; never trust Accept-Ranges or buffer entire songs in RAM. */
internal class DownloadTransfer(private val http: HttpClient, private val fs: FileSystem) {
    private val logger = Logger.withTag("DownloadTransfer")

    suspend fun download(url: String, output: Path, onRestart: () -> Unit, onChunk: (Long, Long) -> Unit) {
        val total = try {
            withTimeoutOrNull(4_000) {
                http.prepareGet(url) { headers { append(HttpHeaders.Range, "bytes=0-0") } }.execute {
                    val range = parseDownloadRange(it.headers[HttpHeaders.ContentRange])
                    if (it.status == HttpStatusCode.PartialContent && range?.start == 0L && range.end == 0L) {
                        range.total
                    } else null
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }

        if (total != null && total >= 2 * 1024 * 1024) {
            try {
                segmented(url, output, total, onChunk)
                return
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                logger.w(e) { "Range transfer failed; retrying this song once as a single stream" }
                onRestart()
            }
        }
        http.prepareGet(url).execute { response ->
            check(response.status.isSuccess()) { getString(Res.string.downloads_error_http, response.status.value) }
            checkAudioType(response.headers[HttpHeaders.ContentType])
            val size = response.contentLength() ?: 0L
            fs.sink(output).buffer().use { sink ->
                copyDownloadBody(response.bodyAsChannel(), sink, size) { onChunk(it, size) }
            }
        }
    }

    private suspend fun segmented(url: String, output: Path, total: Long, onChunk: (Long, Long) -> Unit) {
        val count = 4
        val files = (0 until count).map { output.parent!! / "${output.name}.segment-$it" }
        try {
            coroutineScope {
                files.mapIndexed { index, file -> async {
                    val start = total * index / count
                    val end = total * (index + 1) / count - 1
                    http.prepareGet(url) {
                        headers { append(HttpHeaders.Range, "bytes=$start-$end") }
                    }.execute { response ->
                        check(response.status == HttpStatusCode.PartialContent &&
                            parseDownloadRange(response.headers[HttpHeaders.ContentRange]) == DownloadRange(start, end, total)) {
                            "Server ignored or changed the requested audio range"
                        }
                        checkAudioType(response.headers[HttpHeaders.ContentType])
                        fs.sink(file).buffer().use { sink ->
                            copyDownloadBody(response.bodyAsChannel(), sink, end - start + 1) { onChunk(it, total) }
                        }
                    }
                } }.awaitAll()
            }
            fs.sink(output).buffer().use { sink ->
                files.forEach { file -> fs.source(file).buffer().use { sink.writeAll(it) } }
            }
        } finally {
            files.forEach { runCatching { fs.delete(it, mustExist = false) } }
        }
    }

    private suspend fun checkAudioType(type: String?) {
        check(type?.startsWith("video/", true) != true) { getString(Res.string.downloads_error_video_only) }
        check(type?.startsWith("text/", true) != true && type?.contains("json", true) != true &&
            type?.contains("mpegurl", true) != true && type?.contains("dash+xml", true) != true) {
            getString(Res.string.downloads_error_not_audio)
        }
    }
}
