/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.domain.media.resolver

import org.openani.mediamp.source.MediaExtraFiles
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PikPakPlaybackTransportTest {
    @Test
    fun `forwards range and measures bytes actually read from upstream`() {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val upstream = TestRangeServer(payload, requests)
        val traffic = CopyOnWriteArrayList<Pair<Long, Long>>()
        val session = createPikPakPlaybackTransportSession(
            upstream.uri,
            mapOf("X-PikPak-Signature" to "signed"),
            MediaExtraFiles.EMPTY,
        ) { speed, total -> traffic += speed to total }

        try {
            val proxyUri = session.mediaData.uri
            val ranged = (URI.create(proxyUri).toURL().openConnection() as HttpURLConnection).apply {
                setRequestProperty("Range", "bytes=100-299")
            }
            assertEquals(206, ranged.responseCode)
            assertEquals("bytes 100-299/4096", ranged.getHeaderField("Content-Range"))
            assertContentEquals(payload.copyOfRange(100, 300), ranged.inputStream.readBytes())

            val head = (URI.create(proxyUri).toURL().openConnection() as HttpURLConnection).apply { requestMethod = "HEAD" }
            assertEquals(200, head.responseCode)
            assertEquals(payload.size.toString(), head.getHeaderField("Content-Length"))

            val concurrentResults = arrayOfNulls<ByteArray>(2)
            listOf(300..399, 400..549).mapIndexed { index, range ->
                thread {
                    val connection = (URI.create(proxyUri).toURL().openConnection() as HttpURLConnection).apply {
                        setRequestProperty("Range", "bytes=${range.first}-${range.last}")
                    }
                    assertEquals(206, connection.responseCode)
                    concurrentResults[index] = connection.inputStream.readBytes()
                }
            }.forEach(Thread::join)
            assertContentEquals(payload.copyOfRange(300, 400), concurrentResults[0])
            assertContentEquals(payload.copyOfRange(400, 550), concurrentResults[1])

            run waitForTraffic@{
                repeat(30) {
                    if (traffic.any { it.second == 450L }) return@waitForTraffic
                    Thread.sleep(100)
                }
            }
            assertTrue(traffic.any { it.second == 450L }, "traffic=$traffic")
            assertEquals(4, requests.size)
            assertTrue(requests.contains("GET" to "bytes=100-299"))
            assertTrue(requests.contains("HEAD" to null))
            assertTrue(requests.contains("GET" to "bytes=300-399"))
            assertTrue(requests.contains("GET" to "bytes=400-549"))
        } finally {
            session.close()
            upstream.close()
        }
    }

    private class TestRangeServer(
        private val payload: ByteArray,
        private val requests: MutableList<Pair<String, String?>>,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val running = thread(isDaemon = true, name = "PikPak-test-upstream") {
            while (!server.isClosed) {
                runCatching { server.accept() }.getOrNull()?.let { socket ->
                    thread(isDaemon = true) {
                        socket.use {
                            val reader = BufferedReader(InputStreamReader(it.getInputStream(), StandardCharsets.US_ASCII))
                            val method = reader.readLine().substringBefore(' ')
                            var range: String? = null
                            var signature: String? = null
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                val name = line.substringBefore(':')
                                val value = line.substringAfter(':').trim()
                                if (name.equals("Range", true)) range = value
                                if (name.equals("X-PikPak-Signature", true)) signature = value
                            }
                            check(signature == "signed")
                            requests += method to range
                            val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toInt() ?: 0
                            val end = range?.substringAfter('-')?.takeIf(String::isNotEmpty)?.toInt() ?: payload.lastIndex
                            val body = payload.copyOfRange(start, end + 1)
                            val status = if (range == null) "200 OK" else "206 Partial Content"
                            val headers = buildString {
                                append("HTTP/1.1 $status\r\n")
                                append("Content-Length: ${body.size}\r\n")
                                append("Accept-Ranges: bytes\r\n")
                                if (range != null) append("Content-Range: bytes $start-$end/${payload.size}\r\n")
                                append("Connection: close\r\n\r\n")
                            }
                            it.getOutputStream().write(headers.toByteArray(StandardCharsets.US_ASCII))
                            if (method != "HEAD") it.getOutputStream().write(body)
                        }
                    }
                }
            }
        }

        val uri: String = "http://127.0.0.1:${server.localPort}/video"

        override fun close() {
            server.close()
            running.interrupt()
        }
    }
}
