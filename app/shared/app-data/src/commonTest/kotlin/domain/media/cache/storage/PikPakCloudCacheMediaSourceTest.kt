/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.domain.media.cache.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.persistent.DataStoreJson
import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.source.MatchKind
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.topic.EpisodeRange
import me.him188.ani.torrent.offline.OfflineDownloadCachedSource
import me.him188.ani.torrent.offline.OfflineDownloadEngine
import me.him188.ani.torrent.offline.OfflineDownloadLibraryEntry
import me.him188.ani.torrent.offline.OfflineDownloadLibraryManifest
import me.him188.ani.torrent.offline.OfflineDownloadLibraryState
import me.him188.ani.torrent.offline.OfflineDownloadNaming
import me.him188.ani.torrent.offline.ResolvedMedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PikPakCloudCacheMediaSourceTest {
    @Test
    fun `PikPak library prevents online source collection until explicitly forced`() {
        val state = OfflineDownloadLibraryState(
            status = OfflineDownloadLibraryState.Status.Ready,
            manifest = OfflineDownloadLibraryManifest(
                entries = listOf(
                    OfflineDownloadLibraryEntry(
                        entryId = "123:456:file",
                        subjectId = "123",
                        subjectName = "Test",
                        episodeId = "456",
                        episodeNumber = "1",
                        episodeTitle = "",
                        sourceKey = "source",
                        sourcePayload = "{}",
                        resourceRootId = "file",
                        providerFileId = "file",
                        providerFileName = "1.mkv",
                        createdAt = 1,
                        updatedAt = 1,
                    ),
                ),
            ),
        )

        assertFalse(shouldQueryOnlineMediaSources(true, state, forced = false, subjectId = "123"))
        assertTrue(shouldQueryOnlineMediaSources(true, state, forced = true, subjectId = "123"))
        assertTrue(shouldQueryOnlineMediaSources(true, state, forced = false, subjectId = "other"))
    }

    @Test
    fun `recreates cached media for the same episode`() = runTest {
        val origin = TestMediaList.first()
        val sourcePayload = DataStoreJson.encodeToString(DefaultMedia.serializer(), origin)
        val engine = FakeEngine(
            OfflineDownloadCachedSource(
                subjectId = "123",
                episodeId = "456",
                sourcePayload = sourcePayload,
            ),
        )
        val source = PikPakCloudCacheMediaSource(engine)

        val match = source.fetch(request(subjectId = "123", episodeId = "456"))
            .results.first()

        val cached = assertIs<CachedMedia>(match.media)
        assertEquals(
            sourcePayload,
            DataStoreJson.encodeToString(DefaultMedia.serializer(), assertIs<DefaultMedia>(cached.origin)),
        )
        assertEquals(source.mediaSourceId, cached.mediaSourceId)
        assertEquals(MatchKind.EXACT, match.kind)
    }

    @Test
    fun `reuses a cached season source for another covered episode`() = runTest {
        val origin = TestMediaList.first().copy(
            episodeRange = EpisodeRange.range(1, 12),
        )
        val engine = FakeEngine(
            OfflineDownloadCachedSource(
                subjectId = "123",
                episodeId = "456",
                sourcePayload = DataStoreJson.encodeToString(DefaultMedia.serializer(), origin),
            ),
        )
        val source = PikPakCloudCacheMediaSource(engine)

        val match = source.fetch(
            request(
                subjectId = "123",
                episodeId = "789",
                episodeSort = EpisodeSort(2),
            ),
        ).results.first()

        assertEquals(
            DataStoreJson.encodeToString(DefaultMedia.serializer(), origin),
            DataStoreJson.encodeToString(
                DefaultMedia.serializer(),
                assertIs<DefaultMedia>(assertIs<CachedMedia>(match.media).origin),
            ),
        )
        assertEquals(MatchKind.FUZZY, match.kind)
    }

    @Test
    fun `does not reuse a single episode source for another episode`() = runTest {
        val origin = TestMediaList.first().copy(
            episodeRange = EpisodeRange.single(EpisodeSort(1)),
        )
        val engine = FakeEngine(
            OfflineDownloadCachedSource(
                subjectId = "123",
                episodeId = "456",
                sourcePayload = DataStoreJson.encodeToString(DefaultMedia.serializer(), origin),
            ),
        )
        val source = PikPakCloudCacheMediaSource(engine)

        assertEquals(
            emptyList(),
            source.fetch(
                request(
                    subjectId = "123",
                    episodeId = "789",
                    episodeSort = EpisodeSort(2),
                ),
            ).results.toList(),
        )
    }

    private fun request(
        subjectId: String,
        episodeId: String,
        episodeSort: EpisodeSort = EpisodeSort(1),
    ) = MediaFetchRequest(
        subjectId = subjectId,
        episodeId = episodeId,
        subjectNames = listOf("Test"),
        episodeSort = episodeSort,
        episodeName = "EP1",
    )

    private class FakeEngine(
        private val cachedSource: OfflineDownloadCachedSource?,
    ) : OfflineDownloadEngine {
        override val id: String = "pikpak"
        override val displayName: String = "PikPak"
        override val isSupported: StateFlow<Boolean> = MutableStateFlow(true)

        override suspend fun resolve(
            uri: String,
            pickVideoFile: (List<String>) -> String?,
            naming: OfflineDownloadNaming?,
        ): ResolvedMedia = error("Not needed in test")

        override suspend fun findCachedSource(
            subjectId: String,
            episodeId: String,
        ): OfflineDownloadCachedSource? {
            return cachedSource?.takeIf {
                it.subjectId == subjectId && it.episodeId == episodeId
            }
        }

        override suspend fun findCachedSources(
            subjectId: String,
            episodeId: String,
        ): List<OfflineDownloadCachedSource> {
            return listOfNotNull(cachedSource).filter { it.subjectId == subjectId }
        }
    }
}
