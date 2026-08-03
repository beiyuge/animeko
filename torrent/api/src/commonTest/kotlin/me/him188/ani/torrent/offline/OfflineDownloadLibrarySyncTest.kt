/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.torrent.offline

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineDownloadLibrarySyncTest {
    @Test
    fun `recheck preserves an entry uploaded concurrently by another device`() = runBlocking {
        val localEntry = entry("local", episodeId = "11")
        val remoteEntry = entry("remote", episodeId = "12")
        var cloud = OfflineDownloadLibraryManifest()
        var uploadCount = 0

        val converged = convergeOfflineDownloadLibraryManifest(
            desired = OfflineDownloadLibraryManifest(entries = listOf(localEntry)),
            loadRemote = { cloud },
            upload = { uploaded ->
                uploadCount++
                cloud = if (uploadCount == 1) {
                    // Simulate another device replacing the just-uploaded manifest before our re-read.
                    OfflineDownloadLibraryManifest(entries = listOf(remoteEntry))
                } else {
                    uploaded
                }
            },
            now = { uploadCount.toLong() },
        )

        assertEquals(setOf("local", "remote"), converged.entries.mapTo(mutableSetOf()) { it.entryId })
        assertEquals(setOf("local", "remote"), cloud.entries.mapTo(mutableSetOf()) { it.entryId })
        assertEquals(2, uploadCount)
    }

    @Test
    fun `delete tombstone wins a later conflicting live copy during convergence`() = runBlocking {
        val live = entry("shared", updatedAt = 99)
        val tombstone = live.copy(updatedAt = 20, deletedAt = 20)
        var cloud = OfflineDownloadLibraryManifest(entries = listOf(live))

        val converged = convergeOfflineDownloadLibraryManifest(
            desired = OfflineDownloadLibraryManifest(entries = listOf(tombstone)),
            loadRemote = { cloud },
            upload = { uploaded -> cloud = uploaded },
            now = { 100 },
        )

        assertTrue(converged.entries.single().isDeleted)
        assertTrue(cloud.entries.single().isDeleted)
    }

    private fun entry(
        entryId: String,
        episodeId: String = "11",
        updatedAt: Long = 10,
    ) = OfflineDownloadLibraryEntry(
        entryId = entryId,
        subjectId = "1",
        subjectName = "动画",
        episodeId = episodeId,
        episodeNumber = episodeId,
        episodeTitle = "",
        sourceKey = "source",
        sourcePayload = "{}",
        resourceRootId = "pack-root",
        providerFileId = "file-$episodeId",
        providerFileName = "$episodeId.mkv",
        sharedResource = true,
        createdAt = 1,
        updatedAt = updatedAt,
    )
}
