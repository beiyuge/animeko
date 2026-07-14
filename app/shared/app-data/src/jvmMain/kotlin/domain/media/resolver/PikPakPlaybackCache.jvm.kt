/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.domain.media.resolver

import androidx.collection.MutableFloatList
import me.him188.ani.app.domain.media.player.ChunkState
import me.him188.ani.app.domain.media.player.MediaCacheProgressInfo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.EOFException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * Persistent block cache shared by Android and desktop PikPak playback.
 *
 * The cache stores completed fixed-size blocks in one sparse data file per
 * provider file id. A byte-per-block bitmap is deliberately simple: a crash
 * can at worst leave an unmarked partial block, which is downloaded again.
 */
internal class PikPakPlaybackCacheStore(
    private val root: File,
    private val maxBytes: Long = MAX_CACHE_BYTES,
    internal val blockSize: Int = DEFAULT_BLOCK_SIZE,
) {
    private val monitor = Any()
    private val activeEntries = mutableMapOf<String, Int>()

    init {
        root.mkdirs()
        cleanupAbandonedTemporaryEntries()
        enforceQuota()
    }

    fun open(cacheKey: String, fileSize: Long, contentType: String?): Entry? {
        if (fileSize <= 0L) return null
        val id = sha256(cacheKey)
        synchronized(monitor) {
            val directory = File(root, id)
            val metadataFile = File(directory, METADATA_FILE)
            val old = readMetadata(metadataFile)
            if (old != null && (old.fileSize != fileSize || old.blockSize != blockSize)) {
                directory.deleteRecursively()
            }
            directory.mkdirs()
            writeMetadata(
                metadataFile,
                Metadata(
                    fileSize = fileSize,
                    blockSize = blockSize,
                    contentType = contentType.orEmpty(),
                    temporary = fileSize > maxBytes,
                ),
            )
            directory.setLastModified(System.currentTimeMillis())
            activeEntries[id] = activeEntries.getOrDefault(id, 0) + 1
            return Entry(this, id, directory, fileSize, contentType)
        }
    }

    internal fun cachedBytesFor(cacheKey: String): Long {
        val directory = File(root, sha256(cacheKey))
        val metadata = readMetadata(File(directory, METADATA_FILE)) ?: return 0L
        return cachedBytes(directory, metadata)
    }

    private fun onBlockStored() {
        enforceQuota()
    }

    private fun close(entry: Entry) {
        synchronized(monitor) {
            val remaining = (activeEntries.getOrDefault(entry.id, 1) - 1).coerceAtLeast(0)
            if (remaining == 0) activeEntries.remove(entry.id) else activeEntries[entry.id] = remaining
            entry.directory.setLastModified(System.currentTimeMillis())
            if (entry.temporary && remaining == 0) {
                entry.directory.deleteRecursively()
            }
        }
        enforceQuota()
    }

    private fun cleanupAbandonedTemporaryEntries() {
        root.listFiles().orEmpty().filter(File::isDirectory).forEach { directory ->
            val metadata = readMetadata(File(directory, METADATA_FILE)) ?: return@forEach
            if (metadata.temporary) directory.deleteRecursively()
        }
    }

    private fun enforceQuota() {
        synchronized(monitor) {
            val entries = root.listFiles().orEmpty()
                .filter(File::isDirectory)
                .mapNotNull { directory ->
                    val metadata = readMetadata(File(directory, METADATA_FILE)) ?: return@mapNotNull null
                    CachedDirectory(directory, metadata, cachedBytes(directory, metadata))
                }
            var total = entries.sumOf(CachedDirectory::cachedBytes)
            if (total <= maxBytes) return
            entries.asSequence()
                .filterNot { activeEntries.containsKey(it.directory.name) }
                .sortedBy { it.directory.lastModified() }
                .forEach { cached ->
                    if (total <= maxBytes) return@forEach
                    if (cached.directory.deleteRecursively()) total -= cached.cachedBytes
                }
        }
    }

    private fun cachedBytes(directory: File, metadata: Metadata): Long {
        val bitmap = File(directory, BITMAP_FILE)
        if (!bitmap.isFile) return 0L
        var total = 0L
        FileInputStream(bitmap).use { input ->
            var block = 0L
            while (true) {
                val marked = input.read()
                if (marked < 0) break
                if (marked != 0) {
                    val start = block * metadata.blockSize
                    total += minOf(metadata.blockSize.toLong(), metadata.fileSize - start).coerceAtLeast(0L)
                }
                block++
            }
        }
        return total
    }

    private data class CachedDirectory(
        val directory: File,
        val metadata: Metadata,
        val cachedBytes: Long,
    )

    internal class Entry internal constructor(
        private val store: PikPakPlaybackCacheStore,
        internal val id: String,
        internal val directory: File,
        val fileSize: Long,
        val contentType: String?,
    ) : AutoCloseable {
        private val dataFile = File(directory, DATA_FILE)
        private val bitmapFile = File(directory, BITMAP_FILE)
        private val blockLocks = ConcurrentHashMap<Int, Any>()
        private val lifecycleLock = ReentrantLock()
        private val operationsDrained = lifecycleLock.newCondition()
        private val storedBlockVersion = AtomicLong(0L)
        private var closed = false
        private var activeOperations = 0

        val temporary: Boolean get() = fileSize > store.maxBytes
        val blockSize: Int get() = store.blockSize
        val blockCount: Int get() = ((fileSize + blockSize - 1L) / blockSize).toInt()

        fun blockStart(blockIndex: Int): Long = blockIndex.toLong() * blockSize

        fun blockLength(blockIndex: Int): Int =
            minOf(blockSize.toLong(), fileSize - blockStart(blockIndex)).coerceAtLeast(0L).toInt()

        val progressVersion: Long get() = storedBlockVersion.get()

        /** Returns compact byte-weighted runs matching the blocks persisted in this entry. */
        fun snapshotProgressInfo(): MediaCacheProgressInfo {
            if (blockCount == 0) return MediaCacheProgressInfo.Empty

            val storedBlocks = ByteArray(blockCount)
            if (bitmapFile.isFile) {
                FileInputStream(bitmapFile).use { input ->
                    var offset = 0
                    while (offset < storedBlocks.size) {
                        val count = input.read(storedBlocks, offset, storedBlocks.size - offset)
                        if (count < 0) break
                        offset += count
                    }
                }
            }

            val weights = MutableFloatList()
            val states = ArrayList<ChunkState>()
            var runStored = storedBlocks[0].toInt() == 1
            var runBytes = blockLength(0).toLong()

            fun appendRun() {
                weights.add(runBytes.toFloat() / fileSize.toFloat())
                states += if (runStored) ChunkState.DONE else ChunkState.NONE
            }

            for (blockIndex in 1 until blockCount) {
                val stored = storedBlocks[blockIndex].toInt() == 1
                if (stored == runStored) {
                    runBytes += blockLength(blockIndex)
                } else {
                    appendRun()
                    runStored = stored
                    runBytes = blockLength(blockIndex).toLong()
                }
            }
            appendRun()
            return MediaCacheProgressInfo(weights, states)
        }

        fun getOrFetch(blockIndex: Int, fetch: (start: Long, endInclusive: Long) -> ByteArray): ByteArray {
            require(blockIndex in 0 until blockCount)
            beginOperation()
            try {
                val lock = blockLocks.computeIfAbsent(blockIndex) { Any() }
                synchronized(lock) {
                    readBlock(blockIndex)?.let {
                        touch()
                        return it
                    }
                    val start = blockStart(blockIndex)
                    val expected = blockLength(blockIndex)
                    val bytes = fetch(start, start + expected - 1L)
                    require(bytes.size == expected) {
                        "PikPak upstream returned ${bytes.size} bytes for block $blockIndex; expected $expected"
                    }
                    RandomAccessFile(dataFile, "rw").use { data ->
                        data.seek(start)
                        data.write(bytes)
                    }
                    RandomAccessFile(bitmapFile, "rw").use { bitmap ->
                        bitmap.seek(blockIndex.toLong())
                        bitmap.write(1)
                    }
                    storedBlockVersion.incrementAndGet()
                    touch()
                    store.onBlockStored()
                    return bytes
                }
            } finally {
                endOperation()
            }
        }

        private fun beginOperation() {
            lifecycleLock.lock()
            try {
                check(!closed) { "PikPak cache entry is closed" }
                activeOperations++
            } finally {
                lifecycleLock.unlock()
            }
        }

        private fun endOperation() {
            lifecycleLock.lock()
            try {
                activeOperations--
                if (activeOperations == 0) operationsDrained.signalAll()
            } finally {
                lifecycleLock.unlock()
            }
        }

        private fun awaitOperationsAndClose(): Boolean {
            var interrupted = false
            lifecycleLock.lock()
            try {
                if (closed) return false
                closed = true
                while (activeOperations > 0) {
                    try {
                        operationsDrained.await()
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                return true
            } finally {
                lifecycleLock.unlock()
                if (interrupted) Thread.currentThread().interrupt()
            }
        }

        private fun readBlock(blockIndex: Int): ByteArray? {
            if (!bitmapFile.isFile || !dataFile.isFile) return null
            val marked = RandomAccessFile(bitmapFile, "r").use { bitmap ->
                if (bitmap.length() <= blockIndex) return@use false
                bitmap.seek(blockIndex.toLong())
                bitmap.read() == 1
            }
            if (!marked) return null
            val bytes = ByteArray(blockLength(blockIndex))
            return RandomAccessFile(dataFile, "r").use { data ->
                data.seek(blockStart(blockIndex))
                try {
                    data.readFully(bytes)
                    bytes
                } catch (_: EOFException) {
                    null
                }
            }
        }

        private fun touch() {
            directory.setLastModified(System.currentTimeMillis())
        }

        override fun close() {
            if (!awaitOperationsAndClose()) return
            store.close(this)
        }
    }

    private data class Metadata(
        val fileSize: Long,
        val blockSize: Int,
        val contentType: String,
        val temporary: Boolean,
    )

    private fun readMetadata(file: File): Metadata? = runCatching {
        val properties = Properties()
        FileInputStream(file).use(properties::load)
        Metadata(
            fileSize = properties.getProperty("fileSize").toLong(),
            blockSize = properties.getProperty("blockSize").toInt(),
            contentType = properties.getProperty("contentType").orEmpty(),
            temporary = properties.getProperty("temporary").toBoolean(),
        )
    }.getOrNull()

    private fun writeMetadata(file: File, metadata: Metadata) {
        val properties = Properties().apply {
            setProperty("fileSize", metadata.fileSize.toString())
            setProperty("blockSize", metadata.blockSize.toString())
            setProperty("contentType", metadata.contentType)
            setProperty("temporary", metadata.temporary.toString())
        }
        FileOutputStream(file).use { properties.store(it, null) }
    }

    companion object {
        const val MAX_CACHE_BYTES: Long = 2L * 1024L * 1024L * 1024L
        const val DEFAULT_BLOCK_SIZE: Int = 256 * 1024
        private const val METADATA_FILE = "metadata.properties"
        private const val BITMAP_FILE = "blocks.bitmap"
        private const val DATA_FILE = "data.bin"

        fun default(): PikPakPlaybackCacheStore = PikPakPlaybackCacheStore(
            File(System.getProperty("java.io.tmpdir"), "animeko-pikpak-playback-cache-v1"),
        )

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
