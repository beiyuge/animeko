/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.torrent.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.him188.ani.torrent.pikpak.isAnimekoLibrarySubjectFolder

class OfflineDownloadLibraryTest {
    @Test
    fun `classifies cached gaps not aired and empty library`() {
        assertEquals(
            OfflineEpisodeAvailability.Cached,
            classifyOfflineEpisodeAvailability(2, 4, setOf(1, 2, 4)),
        )
        assertEquals(
            OfflineEpisodeAvailability.MiddleGap,
            classifyOfflineEpisodeAvailability(3, 4, setOf(1, 2, 4)),
        )
        assertEquals(
            OfflineEpisodeAvailability.TrailingGap,
            classifyOfflineEpisodeAvailability(3, 4, setOf(1, 2)),
        )
        assertEquals(
            OfflineEpisodeAvailability.NotAired,
            classifyOfflineEpisodeAvailability(5, 4, setOf(1, 2)),
        )
        assertEquals(
            OfflineEpisodeAvailability.NoPikPakCache,
            classifyOfflineEpisodeAvailability(1, 4, emptySet()),
        )
        assertEquals(
            OfflineEpisodeAvailability.RemoteMissing,
            classifyOfflineEpisodeAvailability(2, 4, setOf(1, 2), setOf(2)),
        )
    }
    @Test
    fun `newer entry wins and deletion wins timestamp ties`() {
        val old = entry(updatedAt = 10)
        val newer = old.copy(providerFileName = "new.mkv", updatedAt = 20)
        val merged = mergeOfflineDownloadLibraryManifests(
            OfflineDownloadLibraryManifest(entries = listOf(old)),
            OfflineDownloadLibraryManifest(entries = listOf(newer)),
            now = 30,
        )
        assertEquals("new.mkv", merged.entries.single().providerFileName)

        val tombstone = newer.copy(deletedAt = 20)
        val deleted = mergeOfflineDownloadLibraryManifests(
            OfflineDownloadLibraryManifest(entries = listOf(newer)),
            OfflineDownloadLibraryManifest(entries = listOf(tombstone)),
            now = 31,
        )
        assertTrue(deleted.entries.single().isDeleted)

        val staleDeviceUpdate = newer.copy(updatedAt = 99, deletedAt = null)
        val deletionStillWins = mergeOfflineDownloadLibraryManifests(
            OfflineDownloadLibraryManifest(entries = listOf(tombstone)),
            OfflineDownloadLibraryManifest(entries = listOf(staleDeviceUpdate)),
            now = 100,
        )
        assertTrue(deletionStillWins.entries.single().isDeleted)
    }

    @Test
    fun `different episode bindings sharing a pack remain distinct`() {
        val first = entry(entryId = "1:11:file-1", episodeId = "11", providerFileId = "file-1")
        val second = entry(entryId = "1:12:file-2", episodeId = "12", providerFileId = "file-2")
        val merged = mergeOfflineDownloadLibraryManifests(
            OfflineDownloadLibraryManifest(entries = listOf(first)),
            OfflineDownloadLibraryManifest(entries = listOf(second)),
            now = 50,
        )
        assertEquals(setOf("11", "12"), merged.entries.map { it.episodeId }.toSet())
        assertTrue(merged.entries.all { it.resourceRootId == "pack-root" })
    }

    @Test
    fun `organized subject folders are not treated as unmatched resources`() {
        assertTrue(isAnimekoLibrarySubjectFolder("动画【12345】"))
        assertFalse(isAnimekoLibrarySubjectFolder("动画】"))
        assertFalse(isAnimekoLibrarySubjectFolder("random-folder"))
    }

    private fun entry(
        entryId: String = "1:11:file-1",
        episodeId: String = "11",
        providerFileId: String = "file-1",
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
        providerFileId = providerFileId,
        providerFileName = "$providerFileId.mkv",
        sharedResource = true,
        createdAt = 1,
        updatedAt = updatedAt,
    )
}
