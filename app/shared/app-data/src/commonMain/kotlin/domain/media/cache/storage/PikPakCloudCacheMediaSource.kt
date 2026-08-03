/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.cache.storage

import kotlinx.coroutines.flow.asFlow
import me.him188.ani.app.data.persistent.DataStoreJson
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.paging.SinglePagePagedSource
import me.him188.ani.datasources.api.paging.SizedSource
import me.him188.ani.datasources.api.source.ConnectionStatus
import me.him188.ani.datasources.api.source.MatchKind
import me.him188.ani.datasources.api.source.MediaFetchRequest
import me.him188.ani.datasources.api.source.MediaMatch
import me.him188.ani.datasources.api.source.MediaSource
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.source.definitelyMatches
import me.him188.ani.torrent.offline.OfflineDownloadEngine
import me.him188.ani.torrent.offline.OfflineDownloadLibraryState
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * Recreates the last successfully resolved PikPak source from the mapping stored beside the cloud
 * file. It is presented as [MediaSourceKind.LocalCache] so a new episode session can select it
 * before starting ordinary online media-source searches.
 */
const val PIKPAK_CACHE_MEDIA_SOURCE_ID = "pikpak-cloud-cache"

/** Decides whether collecting MediaFetchSession results is allowed to start online sources. */
fun shouldQueryOnlineMediaSources(
    pikPakEnabled: Boolean,
    library: OfflineDownloadLibraryState,
    forced: Boolean,
    subjectId: String,
): Boolean = forced || !pikPakEnabled || library.status == OfflineDownloadLibraryState.Status.Failed ||
        (library.status == OfflineDownloadLibraryState.Status.Ready &&
                library.entries.none { it.subjectId == subjectId })

class PikPakCloudCacheMediaSource(
    private val engine: OfflineDownloadEngine,
) : MediaSource {
    override val mediaSourceId: String = PIKPAK_CACHE_MEDIA_SOURCE_ID
    override val kind: MediaSourceKind = MediaSourceKind.LocalCache
    override val location: MediaSourceLocation = MediaSourceLocation.Online
    override val info: MediaSourceInfo = MediaSourceInfo(
        displayName = "PikPak",
        description = "PikPak 中已缓存的剧集",
        isSpecial = true,
    )

    override suspend fun checkConnection(): ConnectionStatus =
        if (engine.isSupported.value) ConnectionStatus.SUCCESS else ConnectionStatus.FAILED

    override suspend fun fetch(query: MediaFetchRequest): SizedSource<MediaMatch> {
        return SinglePagePagedSource {
            engine.findCachedSources(query.subjectId, query.episodeId)
                .distinctBy { it.sourcePayload }
                .mapNotNull { cachedSource ->
                    val origin = runCatching {
                        DataStoreJson.decodeFromString(DefaultMedia.serializer(), cachedSource.sourcePayload)
                    }.onFailure {
                        logger.warn(it) {
                            "Could not decode PikPak cached source from episode " +
                                    "${cachedSource.episodeId} for episode ${query.episodeId}"
                        }
                    }.getOrNull() ?: return@mapNotNull null

                    MediaMatch(
                        CachedMedia(
                            origin = origin,
                            cacheMediaSourceId = mediaSourceId,
                            download = origin.download,
                            location = MediaSourceLocation.Online,
                        ),
                        if (cachedSource.episodeId == query.episodeId) MatchKind.EXACT else MatchKind.FUZZY,
                    ).takeIf { it.definitelyMatches(query) }
                }
                .asFlow()
        }
    }

    private companion object {
        private val logger = logger<PikPakCloudCacheMediaSource>()
    }
}
