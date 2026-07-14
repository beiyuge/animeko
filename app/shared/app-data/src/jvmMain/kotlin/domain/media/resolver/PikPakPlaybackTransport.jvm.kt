/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.domain.media.resolver

import me.him188.ani.app.domain.media.player.MediaCacheProgressInfo
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.ceil

private val DefaultPikPakPlaybackCacheStore by lazy(PikPakPlaybackCacheStore::default)

internal actual fun createPikPakPlaybackTransportSession(
    uri: String,
    headers: Map<String, String>,
    extraFiles: MediaExtraFiles,
    cacheKey: String,
    contentLength: Long?,
    contentType: String?,
    onTraffic: (bytesPerSecond: Long, downloadedBytes: Long) -> Unit,
    onCacheProgress: (MediaCacheProgressInfo) -> Unit,
): PikPakPlaybackTransportSession = createJvmPikPakPlaybackTransportSession(
    uri = uri,
    headers = headers,
    extraFiles = extraFiles,
    cacheKey = cacheKey,
    contentLength = contentLength,
    contentType = contentType,
    onTraffic = onTraffic,
    onCacheProgress = onCacheProgress,
    cacheStore = DefaultPikPakPlaybackCacheStore,
)

internal fun createJvmPikPakPlaybackTransportSession(
    uri: String,
    headers: Map<String, String>,
    extraFiles: MediaExtraFiles,
    cacheKey: String,
    contentLength: Long?,
    contentType: String?,
    onTraffic: (bytesPerSecond: Long, downloadedBytes: Long) -> Unit,
    onCacheProgress: (MediaCacheProgressInfo) -> Unit = {},
    cacheStore: PikPakPlaybackCacheStore,
): PikPakPlaybackTransportSession = JvmPikPakPlaybackTransportSession(
    upstreamUri = uri,
    upstreamHeaders = headers,
    extraFiles = extraFiles,
    cacheKey = cacheKey,
    contentLength = contentLength,
    contentType = contentType,
    onTraffic = onTraffic,
    onCacheProgress = onCacheProgress,
    cacheStore = cacheStore,
)

private class JvmPikPakPlaybackTransportSession(
    private val upstreamUri: String,
    private val upstreamHeaders: Map<String, String>,
    extraFiles: MediaExtraFiles,
    cacheKey: String,
    private val contentLength: Long?,
    private val contentType: String?,
    private val onTraffic: (Long, Long) -> Unit,
    private val onCacheProgress: (MediaCacheProgressInfo) -> Unit,
    cacheStore: PikPakPlaybackCacheStore,
) : PikPakPlaybackTransportSession {
    private val closed = AtomicBoolean(false)
    private val downloadedBytes = AtomicLong(0)
    private val activeConnections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val cacheEntry = contentLength?.let { cacheStore.open(cacheKey, it, contentType) }
    private var publishedCacheProgressVersion = Long.MIN_VALUE
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val prefetchStart = AtomicLong(-1L)
    private val prefetchEndExclusive = AtomicLong(-1L)
    private val prefetchGeneration = AtomicLong(0L)
    private val prefetchSignal = Semaphore(0)

    init {
        publishCacheProgress()
    }

    override val mediaData = UriMediaData(
        "http://127.0.0.1:${server.localPort}/pikpak-media",
        emptyMap(),
        extraFiles,
    )

    private val acceptThread = thread(
        name = "PikPakPlaybackProxy-${server.localPort}",
        isDaemon = true,
    ) {
        while (!closed.get()) {
            try {
                val socket = server.accept()
                activeSockets += socket
                if (closed.get()) {
                    activeSockets -= socket
                    socket.close()
                    continue
                }
                thread(name = "PikPakPlaybackProxy-connection", isDaemon = true) {
                    try {
                        socket.use(::serve)
                    } catch (_: IOException) {
                        // Players cancel in-flight range requests when seeking or recreating the video surface.
                    } catch (e: IllegalStateException) {
                        if (!closed.get()) throw e
                    } finally {
                        activeSockets -= socket
                    }
                }
            } catch (e: SocketException) {
                if (!closed.get()) throw e
            }
        }
    }

    private val statsThread = thread(
        name = "PikPakPlaybackProxy-stats-${server.localPort}",
        isDaemon = true,
    ) {
        var previous = 0L
        while (!closed.get()) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                break
            }
            val total = downloadedBytes.get()
            onTraffic((total - previous).coerceAtLeast(0), total)
            previous = total
        }
    }

    private val prefetchThread = thread(
        name = "PikPakPlaybackProxy-prefetch-${server.localPort}",
        isDaemon = true,
    ) {
        runPrefetchLoop()
    }

    override fun updatePlaybackWindow(positionMillis: Long, durationMillis: Long) {
        val size = contentLength ?: return
        if (size <= 0L || durationMillis <= 0L || closed.get()) return
        val position = positionMillis.coerceIn(0L, durationMillis)
        val endPosition = (position + PRELOAD_WINDOW_MILLIS).coerceAtMost(durationMillis)
        val startByte = ((size.toDouble() * position) / durationMillis).toLong().coerceIn(0L, size - 1L)
        val endByte = ceil((size.toDouble() * endPosition) / durationMillis)
            .toLong()
            .coerceIn(startByte + 1L, size)
        prefetchStart.set(startByte)
        prefetchEndExclusive.set(endByte)
        prefetchGeneration.incrementAndGet()
        prefetchSignal.release()
    }

    private fun runPrefetchLoop() {
        val entry = cacheEntry ?: return
        var completedGeneration = -1L
        while (!closed.get()) {
            val generation = prefetchGeneration.get()
            val start = prefetchStart.get()
            val endExclusive = prefetchEndExclusive.get()
            if (generation == completedGeneration || start < 0L || endExclusive <= start) {
                try {
                    prefetchSignal.tryAcquire(1, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }

            var block = (start / entry.blockSize).toInt()
            while (!closed.get() && block < entry.blockCount && entry.blockStart(block) < endExclusive) {
                if (prefetchGeneration.get() != generation) break
                try {
                    entry.getOrFetch(block, ::fetchBlock)
                    publishCacheProgress()
                } catch (_: InterruptedException) {
                    return
                } catch (_: IOException) {
                    // Playback remains authoritative. A later timeline update retries preloading.
                    break
                }
                block++
            }
            if (prefetchGeneration.get() == generation) completedGeneration = generation
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 30_000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
        val requestLine = reader.readLine() ?: return
        val method = requestLine.substringBefore(' ').uppercase()
        val requestHeaders = buildMap {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) put(line.substring(0, separator).trim().lowercase(), line.substring(separator + 1).trim())
            }
        }
        if (method != "GET" && method != "HEAD") {
            socket.getOutputStream().write(simpleResponse(405).toByteArray(StandardCharsets.US_ASCII))
            return
        }

        val entry = cacheEntry
        if (entry == null) {
            serveUncached(socket, method, requestHeaders)
        } else {
            serveCached(socket, method, requestHeaders, entry)
        }
    }

    private fun serveCached(
        socket: Socket,
        method: String,
        requestHeaders: Map<String, String>,
        entry: PikPakPlaybackCacheStore.Entry,
    ) {
        val rangeHeader = requestHeaders["range"]
        val range = parseRange(rangeHeader, entry.fileSize)
        if (range == null) {
            socket.getOutputStream().write(rangeNotSatisfiable(entry.fileSize).toByteArray(StandardCharsets.US_ASCII))
            return
        }
        val partial = rangeHeader != null
        val output = socket.getOutputStream()
        val responseHeaders = buildString {
            append("HTTP/1.1 ").append(if (partial) "206 Partial Content" else "200 OK").append("\r\n")
            append("Content-Type: ").append(safeContentType(contentType)).append("\r\n")
            append("Content-Length: ").append(range.length).append("\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (partial) {
                append("Content-Range: bytes ${range.start}-${range.endInclusive}/${entry.fileSize}\r\n")
            }
            append("Cache-Control: private, max-age=3600\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(responseHeaders.toByteArray(StandardCharsets.US_ASCII))
        if (method == "HEAD") {
            output.flush()
            return
        }

        var position = range.start
        while (position <= range.endInclusive && !closed.get()) {
            val blockIndex = (position / entry.blockSize).toInt()
            val block = entry.getOrFetch(blockIndex, ::fetchBlock)
            publishCacheProgress()
            val offset = (position - entry.blockStart(blockIndex)).toInt()
            val count = minOf(block.size - offset, (range.endInclusive - position + 1L).toInt())
            output.write(block, offset, count)
            position += count
        }
        output.flush()
    }

    @Synchronized
    private fun publishCacheProgress() {
        val entry = cacheEntry
        if (entry == null) {
            if (publishedCacheProgressVersion == Long.MIN_VALUE) {
                publishedCacheProgressVersion = 0L
                onCacheProgress(MediaCacheProgressInfo.Empty)
            }
            return
        }
        val version = entry.progressVersion
        if (publishedCacheProgressVersion == version) return
        onCacheProgress(entry.snapshotProgressInfo())
        publishedCacheProgressVersion = version
    }

    private fun serveUncached(socket: Socket, method: String, requestHeaders: Map<String, String>) {
        val connection = openUpstream(method, requestHeaders["range"], requestHeaders["if-range"])
        try {
            val status = connection.responseCode
            val output = socket.getOutputStream()
            val responseHeaders = buildString {
                append("HTTP/1.1 ").append(status).append(' ')
                    .append(connection.responseMessage ?: "Response").append("\r\n")
                for (name in FORWARDED_RESPONSE_HEADERS) {
                    connection.getHeaderField(name)?.let {
                        append(name).append(": ").append(it).append("\r\n")
                    }
                }
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(responseHeaders.toByteArray(StandardCharsets.US_ASCII))
            if (method == "HEAD") {
                output.flush()
                return
            }
            val input = if (status >= 400) connection.errorStream else connection.inputStream
            if (input != null) copyAndCount(input, output)
            output.flush()
        } finally {
            closeUpstream(connection)
        }
    }

    private fun fetchBlock(start: Long, endInclusive: Long): ByteArray {
        val connection = openUpstream("GET", "bytes=$start-$endInclusive", null)
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_PARTIAL && status != HttpURLConnection.HTTP_OK) {
                throw IOException("PikPak upstream returned HTTP $status for bytes=$start-$endInclusive")
            }
            val input = connection.inputStream
            if (status == HttpURLConnection.HTTP_OK && start > 0L) {
                skipAndCount(input, start)
            }
            val expected = (endInclusive - start + 1L).toInt()
            val output = ByteArrayOutputStream(expected)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (output.size() < expected && !closed.get()) {
                val count = input.read(buffer, 0, minOf(buffer.size, expected - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
                downloadedBytes.addAndGet(count.toLong())
            }
            if (output.size() != expected) {
                throw IOException("PikPak upstream ended at ${output.size()} of $expected bytes")
            }
            return output.toByteArray()
        } finally {
            closeUpstream(connection)
        }
    }

    private fun openUpstream(method: String, range: String?, ifRange: String?): HttpURLConnection {
        if (closed.get()) throw IOException("PikPak playback session is closed")
        val connection = (URI.create(upstreamUri).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept-Encoding", "identity")
            range?.let { setRequestProperty("Range", it) }
            ifRange?.let { setRequestProperty("If-Range", it) }
            upstreamHeaders.forEach(::setRequestProperty)
            activeConnections += this
        }
        if (closed.get()) {
            closeUpstream(connection)
            throw IOException("PikPak playback session is closed")
        }
        return connection
    }

    private fun closeUpstream(connection: HttpURLConnection) {
        activeConnections -= connection
        connection.disconnect()
    }

    private fun copyAndCount(input: InputStream, output: OutputStream) {
        input.use {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (!closed.get()) {
                val count = it.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                downloadedBytes.addAndGet(count.toLong())
            }
        }
    }

    private fun skipAndCount(input: InputStream, byteCount: Long) {
        var remaining = byteCount
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0L) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw IOException("PikPak upstream ended while skipping to byte $byteCount")
            downloadedBytes.addAndGet(count.toLong())
            remaining -= count
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.close()
        activeSockets.forEach { runCatching { it.close() } }
        activeConnections.forEach(HttpURLConnection::disconnect)
        prefetchSignal.release()
        acceptThread.interrupt()
        statsThread.interrupt()
        prefetchThread.interrupt()
        cacheEntry?.close()
    }

    private fun simpleResponse(status: Int): String =
        "HTTP/1.1 $status Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

    private fun rangeNotSatisfiable(fileSize: Long): String =
        "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$fileSize\r\n" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n"

    private fun safeContentType(value: String?): String = value
        ?.takeIf { it.isNotBlank() && '\r' !in it && '\n' !in it }
        ?: "application/octet-stream"

    private data class RequestedRange(val start: Long, val endInclusive: Long) {
        val length: Long get() = endInclusive - start + 1L
    }

    private fun parseRange(value: String?, fileSize: Long): RequestedRange? {
        if (fileSize <= 0L) return null
        if (value == null) return RequestedRange(0L, fileSize - 1L)
        if (!value.startsWith("bytes=") || ',' in value) return null
        val raw = value.removePrefix("bytes=")
        val separator = raw.indexOf('-')
        if (separator < 0) return null
        val startText = raw.substring(0, separator)
        val endText = raw.substring(separator + 1)
        if (startText.isEmpty()) {
            val suffixLength = endText.toLongOrNull()?.takeIf { it > 0L } ?: return null
            val start = (fileSize - suffixLength).coerceAtLeast(0L)
            return RequestedRange(start, fileSize - 1L)
        }
        val start = startText.toLongOrNull()?.takeIf { it in 0 until fileSize } ?: return null
        val end = endText.toLongOrNull()?.coerceAtMost(fileSize - 1L) ?: (fileSize - 1L)
        if (end < start) return null
        return RequestedRange(start, end)
    }

    companion object {
        private const val PRELOAD_WINDOW_MILLIS = 15L * 60L * 1000L
        private val FORWARDED_RESPONSE_HEADERS = listOf(
            "Content-Type",
            "Content-Length",
            "Content-Range",
            "Accept-Ranges",
            "ETag",
            "Last-Modified",
        )
    }
}
