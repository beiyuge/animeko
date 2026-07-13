/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.domain.media.resolver

import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

internal actual fun createPikPakPlaybackTransportSession(
    uri: String,
    headers: Map<String, String>,
    extraFiles: MediaExtraFiles,
    onTraffic: (bytesPerSecond: Long, downloadedBytes: Long) -> Unit,
): PikPakPlaybackTransportSession = JvmPikPakPlaybackTransportSession(uri, headers, extraFiles, onTraffic)

private class JvmPikPakPlaybackTransportSession(
    private val upstreamUri: String,
    private val upstreamHeaders: Map<String, String>,
    extraFiles: MediaExtraFiles,
    private val onTraffic: (Long, Long) -> Unit,
) : PikPakPlaybackTransportSession {
    private val closed = AtomicBoolean(false)
    private val downloadedBytes = AtomicLong(0)
    private val activeConnections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

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
                thread(name = "PikPakPlaybackProxy-connection", isDaemon = true) {
                    try {
                        socket.use(::serve)
                    } catch (_: IOException) {
                        // Players cancel in-flight range requests when seeking or recreating the video surface.
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

        val connection = (URI.create(upstreamUri).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept-Encoding", "identity")
            requestHeaders["range"]?.let { setRequestProperty("Range", it) }
            requestHeaders["if-range"]?.let { setRequestProperty("If-Range", it) }
            upstreamHeaders.forEach(::setRequestProperty)
        }
        activeConnections += connection
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
            if (input != null) {
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
            output.flush()
        } finally {
            activeConnections -= connection
            connection.disconnect()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.close()
        activeConnections.forEach(HttpURLConnection::disconnect)
        acceptThread.interrupt()
        statsThread.interrupt()
    }

    private fun simpleResponse(status: Int): String =
        "HTTP/1.1 $status Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

    companion object {
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
