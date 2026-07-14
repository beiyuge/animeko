/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [pickEvictions] — the slot-eviction policy behind the
 * user-configurable queue length. The function is pure, so failures here
 * translate directly to the wrong user-drive state.
 */
class SlotEvictionPolicyTest {
    @Test
    fun `cloud candidate lookup traverses nested folders without duplicate files`() = runTest {
        val episodeOne = file("Show S01E01.mkv", id = "ep-1")
        val episodeTwo = file("Show S01E02.mkv", id = "ep-2")
        val tree = mapOf(
            "bucket" to listOf(folder("season", id = "season"), episodeOne),
            "season" to listOf(folder("disc", id = "disc"), episodeOne),
            "disc" to listOf(episodeTwo, folder("cycle", id = "bucket")),
        )

        val candidates = collectPikPakFileCandidates("bucket") { tree[it].orEmpty() }

        assertEquals(
            listOf("ep-1", "ep-2"),
            candidates.map(CachedPikPakFile::id),
        )
    }

    @Test
    fun `durable source lookup includes duplicate case variants but never unrelated files`() {
        val upper = bucket("ABCDEF", "2026-01-01T00:00:00Z", id = "upper")
        val lower = bucket("abcdef", "2026-01-02T00:00:00Z", id = "lower")
        val unrelated = bucket("other", "2026-01-03T00:00:00Z", id = "other")
        val sameNameFile = upper.copy(id = "file", kind = FileKind.FILE)

        assertEquals(
            listOf("upper", "lower"),
            findSourceBuckets(listOf(upper, lower, unrelated, sameNameFile), "ABCDEF").map(FileStat::id),
        )
    }

    @Test
    fun `instant complete selects the entry created by the probe before an older bucket entry`() {
        val existing = bucket("existing", "2026-07-14T12:00:00Z", id = "existing")
        val landed = bucket("landed", "2026-07-14T11:00:00Z", id = "landed")

        assertEquals(
            "landed",
            selectInstantCompleteEntry(listOf(existing, landed), setOf("existing"))?.id,
        )
    }

    @Test
    fun `instant complete falls back to newest entry when provider reuses an id`() {
        val older = bucket("older", "2026-07-14T11:00:00Z", id = "older")
        val refreshed = bucket("refreshed", "2026-07-14T12:00:00Z", id = "refreshed")

        assertEquals(
            "refreshed",
            selectInstantCompleteEntry(listOf(older, refreshed), setOf("older", "refreshed"))?.id,
        )
    }

    /** Folder entry on the slot. Only `id`, `name`, `kind`, `createdTime` matter. */
    private fun bucket(name: String, createdTime: String, id: String = "id-$name") = FileStat(
        id = id,
        name = name,
        kind = FileKind.FOLDER,
        createdTime = createdTime,
    )

    private fun folder(name: String, id: String) = FileStat(
        id = id,
        name = name,
        kind = FileKind.FOLDER,
    )

    private fun file(name: String, id: String) = FileStat(
        id = id,
        name = name,
        kind = FileKind.FILE,
    )

    // "current" = the source being resolved right now; must never be evicted.
    private val current = bucket("CURRENT", createdTime = "2026-04-20T00:00:00Z", id = "id-current")

    private val older1 = bucket("OLDER1", createdTime = "2026-04-18T00:00:00Z", id = "id-o1")
    private val older2 = bucket("OLDER2", createdTime = "2026-04-17T00:00:00Z", id = "id-o2")
    private val older3 = bucket("OLDER3", createdTime = "2026-04-16T00:00:00Z", id = "id-o3")
    private val newerNonCurrent = bucket("NEWER", createdTime = "2026-04-19T00:00:00Z", id = "id-n1")

    @Test
    fun `queueLength 1 evicts every non-current bucket`() {
        val entries = listOf(current, older1, older2, older3, newerNonCurrent)
        val evicted = pickEvictions(entries, currentSourceKey = "CURRENT", queueLength = 1)
        assertEquals(
            setOf("id-o1", "id-o2", "id-o3", "id-n1"),
            evicted.toSet(),
        )
    }

    @Test
    fun `current bucket is never evicted even when it's the oldest`() {
        // Make current the oldest of all entries.
        val ancientCurrent = bucket("CURRENT", createdTime = "2020-01-01T00:00:00Z", id = "id-current")
        val entries = listOf(ancientCurrent, older1, older2)
        val evicted = pickEvictions(entries, currentSourceKey = "CURRENT", queueLength = 1)
        assertTrue("id-current" !in evicted, "current bucket must survive eviction; got $evicted")
    }

    @Test
    fun `queueLength N keeps the N-1 newest non-current buckets`() {
        val entries = listOf(current, older1, older2, older3, newerNonCurrent)
        val evicted = pickEvictions(entries, currentSourceKey = "CURRENT", queueLength = 3)
        // queueLength = 3 → keep current + 2 newest others (NEWER + OLDER1);
        // evict OLDER2 + OLDER3.
        assertEquals(setOf("id-o2", "id-o3"), evicted.toSet())
    }

    @Test
    fun `queueLength equals total bucket count evicts nothing`() {
        val entries = listOf(current, older1, older2)
        val evicted = pickEvictions(entries, currentSourceKey = "CURRENT", queueLength = 3)
        assertTrue(evicted.isEmpty(), "should evict nothing when queue fits everyone; got $evicted")
    }

    @Test
    fun `queueLength unlimited evicts nothing regardless of count`() {
        val entries = listOf(current, older1, older2, older3, newerNonCurrent)
        val evicted = pickEvictions(
            entries,
            currentSourceKey = "CURRENT",
            queueLength = PikPakOfflineDownloadEngine.SLOT_QUEUE_UNLIMITED_SENTINEL,
        )
        assertTrue(evicted.isEmpty(), "unlimited must skip eviction; got $evicted")
    }

    @Test
    fun `unknown current key falls through — nothing is 'protected', still honours queueLength`() {
        // User resolves a source whose bucket doesn't exist yet.
        val entries = listOf(older1, older2, older3)
        val evicted = pickEvictions(entries, currentSourceKey = "NEW-KEY-NOT-PRESENT", queueLength = 2)
        // queueLength = 2 → keep 1 newest other (OLDER1), evict OLDER2 + OLDER3.
        assertEquals(setOf("id-o2", "id-o3"), evicted.toSet())
    }

    @Test
    fun `empty slot yields empty eviction list`() {
        val evicted = pickEvictions(emptyList(), currentSourceKey = "ANYTHING", queueLength = 1)
        assertTrue(evicted.isEmpty())
    }

    @Test
    fun `ordering is newest first — tie-breaker not relied on`() {
        // Two entries with identical createdTime shouldn't crash; either may
        // be dropped. Just assert the set of *dropped* ids has the right size.
        val a = bucket("A", createdTime = "2026-04-15T00:00:00Z", id = "id-a")
        val b = bucket("B", createdTime = "2026-04-15T00:00:00Z", id = "id-b")
        val evicted = pickEvictions(listOf(a, b), currentSourceKey = "OTHER", queueLength = 2)
        // queueLength=2 → keep 1 non-current, evict 1.
        assertEquals(1, evicted.size)
    }

    @Test
    fun `queueLength less than 1 is coerced like the engine does upstream`() {
        // The engine coerces user input with coerceAtLeast(1) before calling
        // this, so raw 0 never reaches pickEvictions in production; documenting
        // behaviour if someone passes it anyway: treated identically to 1.
        val entries = listOf(current, older1)
        val atZero = pickEvictions(entries, currentSourceKey = "CURRENT", queueLength = 0)
        val atOne = pickEvictions(entries, currentSourceKey = "CURRENT", queueLength = 1)
        assertEquals(atOne.toSet(), atZero.toSet())
    }
}
