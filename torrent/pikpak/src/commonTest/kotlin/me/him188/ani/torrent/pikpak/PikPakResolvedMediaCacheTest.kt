/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.torrent.pikpak

import kotlinx.coroutines.test.runTest
import me.him188.ani.torrent.offline.ResolvedMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PikPakResolvedMediaCacheTest {
    @Test
    fun `sole cached file is reused without filename parsing`() = runTest {
        val cache = PikPakResolvedMediaCache()
        cache.replace(
            sourceKey = "source-1",
            candidates = listOf(CachedPikPakFile(id = "file-1", name = "opaque-name.bin")),
        )
        var pickerCalled = false

        val resolved = cache.resolve(
            sourceKey = "source-1",
            pickVideoFile = {
                pickerCalled = true
                null
            },
            refresh = { ResolvedMedia(streamUrl = "https://cdn.example/fresh") },
        )

        assertEquals("https://cdn.example/fresh", resolved?.streamUrl)
        assertFalse(pickerCalled)
    }

    @Test
    fun `switching episode selects the requested file from cached season pack`() = runTest {
        val cache = PikPakResolvedMediaCache()
        cache.replace(
            sourceKey = "season-pack",
            candidates = listOf(
                CachedPikPakFile(id = "episode-1", name = "Show S01E01.mkv"),
                CachedPikPakFile(id = "episode-2", name = "Show S01E02.mkv"),
            ),
        )
        val refreshedIds = mutableListOf<String>()

        val firstEpisode = cache.resolve(
            sourceKey = "season-pack",
            pickVideoFile = { names -> names.single { it.contains("E01") } },
            refresh = { file ->
                refreshedIds += file.id
                ResolvedMedia(streamUrl = "https://cdn.example/${file.id}")
            },
        )
        val nextEpisode = cache.resolve(
            sourceKey = "season-pack",
            pickVideoFile = { names -> names.single { it.contains("E02") } },
            refresh = { file ->
                refreshedIds += file.id
                ResolvedMedia(streamUrl = "https://cdn.example/${file.id}")
            },
        )

        assertEquals("https://cdn.example/episode-1", firstEpisode?.streamUrl)
        assertEquals("https://cdn.example/episode-2", nextEpisode?.streamUrl)
        assertEquals(listOf("episode-1", "episode-2"), refreshedIds)
    }

    @Test
    fun `deleted remote file invalidates the cache entry`() = runTest {
        val cache = PikPakResolvedMediaCache()
        cache.replace(
            sourceKey = "source-1",
            candidates = listOf(CachedPikPakFile(id = "deleted", name = "Show E01.mkv")),
        )
        var refreshCalls = 0

        val deleted = cache.resolve("source-1", pickVideoFile = { it.single() }) {
            refreshCalls++
            null
        }
        val afterInvalidation = cache.resolve("source-1", pickVideoFile = { it.single() }) {
            error("Invalidated entry must not be refreshed again")
        }

        assertNull(deleted)
        assertNull(afterInvalidation)
        assertEquals(1, refreshCalls)
    }
}
