/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.domain.media.resolver

import me.him188.ani.app.domain.media.player.ChunkState
import me.him188.ani.app.domain.media.player.MediaCacheProgressInfo
import org.openani.mediamp.source.MediaExtraFiles
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PikPakPlaybackTransportTest {
    @Test
    fun `cached ranges are reported and backward reads do not hit PikPak again`() {
        val payload = ByteArray(16) { it.toByte() }
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val upstream = TestRangeServer(payload, requests)
        val cacheRoot = Files.createTempDirectory("pikpak-visible-cache").toFile()
        val progress = CopyOnWriteArrayList<MediaCacheProgressInfo>()
        val session = createJvmPikPakPlaybackTransportSession(
            uri = upstream.uri,
            headers = mapOf("X-PikPak-Signature" to "signed"),
            extraFiles = MediaExtraFiles.EMPTY,
            cacheKey = "visible-${upstream.uri}",
            contentLength = payload.size.toLong(),
            contentType = "video/mp4",
            onTraffic = { _, _ -> },
            onCacheProgress = progress::add,
            cacheStore = PikPakPlaybackCacheStore(cacheRoot, blockSize = 4),
        )

        try {
            val proxyUri = session.mediaData.uri
            val firstRead = (URI.create(proxyUri).toURL().openConnection() as HttpURLConnection).apply {
                setRequestProperty("Range", "bytes=4-11")
            }
            assertEquals(206, firstRead.responseCode)
            assertContentEquals(payload.copyOfRange(4, 12), firstRead.inputStream.readBytes())

            val visibleProgress = progress.last()
            assertEquals(
                listOf(ChunkState.NONE, ChunkState.DONE, ChunkState.NONE),
                visibleProgress.chunkStates,
            )
            assertEquals(0.25f, visibleProgress.chunkWeights[0], 0.0001f)
            assertEquals(0.5f, visibleProgress.chunkWeights[1], 0.0001f)
            assertEquals(0.25f, visibleProgress.chunkWeights[2], 0.0001f)

            val requestCount = requests.size
            val backwardRead = (URI.create(proxyUri).toURL().openConnection() as HttpURLConnection).apply {
                setRequestProperty("Range", "bytes=4-7")
            }
            assertEquals(206, backwardRead.responseCode)
            assertContentEquals(payload.copyOfRange(4, 8), backwardRead.inputStream.readBytes())
            assertEquals(requestCount, requests.size, "A backward read inside the visible cache must stay local")
        } finally {
            session.close()
            upstream.close()
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun `client reset does not escape proxy connection thread`() {
        val upstream = TestRangeServer(
            payload = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() },
            requests = CopyOnWriteArrayList(),
            bodyChunkDelayMillis = 1,
        )
        val cacheRoot = Files.createTempDirectory("pikpak-reset-cache").toFile()
        val session = createJvmPikPakPlaybackTransportSession(
            upstream.uri,
            mapOf("X-PikPak-Signature" to "signed"),
            MediaExtraFiles.EMPTY,
            cacheKey = "reset-${upstream.uri}",
            contentLength = 4L * 1024L * 1024L,
            contentType = "video/mp4",
            onTraffic = { _, _ -> },
            cacheStore = PikPakPlaybackCacheStore(cacheRoot),
        )
        val uncaught = AtomicReference<Throwable>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { worker, throwable ->
            if (worker.name == "PikPakPlaybackProxy-connection") {
                uncaught.compareAndSet(null, throwable)
            } else {
                previousHandler?.uncaughtException(worker, throwable)
            }
        }

        try {
            val proxyUri = URI.create(session.mediaData.uri)
            Socket(proxyUri.host, proxyUri.port).use { client ->
                client.soTimeout = 5_000
                client.setSoLinger(true, 0)
                client.getOutputStream().write(
                    "GET ${proxyUri.path} HTTP/1.1\r\nHost: ${proxyUri.host}\r\nConnection: close\r\n\r\n"
                        .toByteArray(StandardCharsets.US_ASCII),
                )
                client.getOutputStream().flush()
                assertTrue(client.getInputStream().read() >= 0)
            }

            assertTrue(upstream.requestFinished.await(5, TimeUnit.SECONDS), "Proxy did not release the reset request")
            assertNull(uncaught.get(), "Client disconnect must not escape the proxy connection thread")
        } finally {
            session.close()
            upstream.close()
            cacheRoot.deleteRecursively()
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    @Test
    fun `forwards range and measures bytes actually read from upstream`() {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val upstream = TestRangeServer(payload, requests)
        val traffic = CopyOnWriteArrayList<Pair<Long, Long>>()
        val cacheRoot = Files.createTempDirectory("pikpak-range-cache").toFile()
        val session = createJvmPikPakPlaybackTransportSession(
            upstream.uri,
            mapOf("X-PikPak-Signature" to "signed"),
            MediaExtraFiles.EMPTY,
            cacheKey = "range-${upstream.uri}",
            contentLength = payload.size.toLong(),
            contentType = "video/mp4",
            onTraffic = { speed, total -> traffic += speed to total },
            cacheStore = PikPakPlaybackCacheStore(cacheRoot),
        )

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

            run waitForTraffic@ {
                repeat(30) {
                    if (traffic.any { it.second == payload.size.toLong() }) return@waitForTraffic
                    Thread.sleep(100)
                }
            }
            assertTrue(traffic.any { it.second == payload.size.toLong() }, "traffic=$traffic")
            assertEquals(
                listOf("GET" to "bytes=0-${payload.lastIndex}"),
                requests.toList(),
                "All requested ranges fit in the same cached block and must only read upstream once",
            )
        } finally {
            session.close()
            upstream.close()
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun `preloads only the next fifteen minutes using the real player timeline`() {
        val payload = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val upstream = TestRangeServer(payload, requests)
        val cacheRoot = Files.createTempDirectory("pikpak-prefetch-cache").toFile()
        val cacheKey = "prefetch-${upstream.uri}"
        val store = PikPakPlaybackCacheStore(cacheRoot, blockSize = 64 * 1024)
        val session = createJvmPikPakPlaybackTransportSession(
            uri = upstream.uri,
            headers = mapOf("X-PikPak-Signature" to "signed"),
            extraFiles = MediaExtraFiles.EMPTY,
            cacheKey = cacheKey,
            contentLength = payload.size.toLong(),
            contentType = "video/mp4",
            onTraffic = { _, _ -> },
            cacheStore = store,
        )

        try {
            session.updatePlaybackWindow(positionMillis = 0L, durationMillis = 60L * 60L * 1000L)
            run waitForPrefetch@ {
                repeat(100) {
                    if (store.cachedBytesFor(cacheKey) >= payload.size / 4L) return@waitForPrefetch
                    Thread.sleep(20L)
                }
            }
            assertEquals(payload.size / 4L, store.cachedBytesFor(cacheKey))
            assertEquals(4, requests.size, "A 15-minute window of a 60-minute file is exactly one quarter")

            val requestCount = requests.size
            val cachedRange = (URI.create(session.mediaData.uri).toURL().openConnection() as HttpURLConnection).apply {
                setRequestProperty("Range", "bytes=200000-200999")
            }
            assertEquals(206, cachedRange.responseCode)
            assertContentEquals(payload.copyOfRange(200000, 201000), cachedRange.inputStream.readBytes())
            assertEquals(requestCount, requests.size, "A range inside the preloaded window must not hit PikPak again")
        } finally {
            session.close()
            upstream.close()
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun `oversized episode cache is temporary and removed when playback closes`() {
        val cacheRoot = Files.createTempDirectory("pikpak-oversized-cache").toFile()
        val store = PikPakPlaybackCacheStore(cacheRoot, maxBytes = 8L, blockSize = 4)
        val entry = store.open("oversized", fileSize = 12L, contentType = "video/mp4")!!
        repeat(entry.blockCount) { block ->
            entry.getOrFetch(block) { start, end -> ByteArray((end - start + 1L).toInt()) }
        }
        assertEquals(12L, store.cachedBytesFor("oversized"))

        entry.close()

        assertEquals(0L, store.cachedBytesFor("oversized"))
        cacheRoot.deleteRecursively()
    }

    @Test
    fun `normal episode caches evict least recently used entries at the global limit`() {
        val cacheRoot = Files.createTempDirectory("pikpak-quota-cache").toFile()
        val store = PikPakPlaybackCacheStore(cacheRoot, maxBytes = 8L, blockSize = 4)
        store.open("older", fileSize = 8L, contentType = null)!!.use { entry ->
            repeat(entry.blockCount) { block ->
                entry.getOrFetch(block) { start, end -> ByteArray((end - start + 1L).toInt()) }
            }
        }
        store.open("newer", fileSize = 8L, contentType = null)!!.use { entry ->
            repeat(entry.blockCount) { block ->
                entry.getOrFetch(block) { start, end -> ByteArray((end - start + 1L).toInt()) }
            }
            assertEquals(0L, store.cachedBytesFor("older"))
            assertEquals(8L, store.cachedBytesFor("newer"))
        }
        cacheRoot.deleteRecursively()
    }

    @Test
    fun `normal episode blocks survive store recreation`() {
        val cacheRoot = Files.createTempDirectory("pikpak-persistent-cache").toFile()
        PikPakPlaybackCacheStore(cacheRoot, maxBytes = 16L, blockSize = 4)
            .open("persistent", fileSize = 8L, contentType = "video/mp4")!!
            .use { entry ->
                entry.getOrFetch(0) { start, end -> ByteArray((end - start + 1L).toInt()) { 7 } }
            }

        val reopened = PikPakPlaybackCacheStore(cacheRoot, maxBytes = 16L, blockSize = 4)
            .open("persistent", fileSize = 8L, contentType = "video/mp4")!!
        assertEquals(
            listOf(ChunkState.DONE, ChunkState.NONE),
            reopened.snapshotProgressInfo().chunkStates,
            "Persisted ranges must be visible before the first playback read",
        )
        val cached = reopened.getOrFetch(0) { _, _ -> error("Persistent block should not be downloaded again") }

        assertContentEquals(ByteArray(4) { 7 }, cached)
        reopened.close()
        cacheRoot.deleteRecursively()
    }

    private class TestRangeServer(
        private val payload: ByteArray,
        private val requests: MutableList<Pair<String, String?>>,
        private val bodyChunkDelayMillis: Long = 0,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val requestFinished = CountDownLatch(1)
        private val running = thread(isDaemon = true, name = "PikPak-test-upstream") {
            while (!server.isClosed) {
                runCatching { server.accept() }.getOrNull()?.let { socket ->
                    thread(isDaemon = true) {
                        socket.use {
                            try {
                                val reader = BufferedReader(
                                    InputStreamReader(it.getInputStream(), StandardCharsets.US_ASCII),
                                )
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
                                val end = range?.substringAfter('-')?.takeIf(String::isNotEmpty)?.toInt()
                                    ?: payload.lastIndex
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
                                if (method != "HEAD") {
                                    if (bodyChunkDelayMillis == 0L) {
                                        it.getOutputStream().write(body)
                                    } else {
                                        var offset = 0
                                        while (offset < body.size) {
                                            val count = minOf(DEFAULT_BUFFER_SIZE, body.size - offset)
                                            it.getOutputStream().write(body, offset, count)
                                            it.getOutputStream().flush()
                                            Thread.sleep(bodyChunkDelayMillis)
                                            offset += count
                                        }
                                    }
                                }
                            } catch (_: IOException) {
                                // The proxy is expected to close this upstream request after its client resets.
                            } finally {
                                requestFinished.countDown()
                            }
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
