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
import me.him188.ani.torrent.offline.OfflineDownloadEngine
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn

/**
 * Recreates the last successfully resolved PikPak source from the mapping stored beside the cloud
 * file. It is presented as [MediaSourceKind.LocalCache] so a new episode session can select it
 * before starting ordinary online media-source searches.
 */
class PikPakCloudCacheMediaSource(
    private val engine: OfflineDownloadEngine,
) : MediaSource {
    override val mediaSourceId: String = "pikpak-cloud-cache"
    override val kind: MediaSourceKind = MediaSourceKind.LocalCache
    override val location: MediaSourceLocation = MediaSourceLocation.Online
    override val info: MediaSourceInfo = MediaSourceInfo(
        displayName = "PikPak 云端缓存",
        description = "PikPak 中已缓存的剧集",
        isSpecial = true,
    )

    override suspend fun checkConnection(): ConnectionStatus =
        if (engine.isSupported.value) ConnectionStatus.SUCCESS else ConnectionStatus.FAILED

    override suspend fun fetch(query: MediaFetchRequest): SizedSource<MediaMatch> {
        return SinglePagePagedSource {
            val cachedSource = engine.findCachedSource(query.subjectId, query.episodeId)
                ?: return@SinglePagePagedSource emptyList<MediaMatch>().asFlow()
            val origin = runCatching {
                DataStoreJson.decodeFromString(DefaultMedia.serializer(), cachedSource.sourcePayload)
            }.onFailure {
                logger.warn(it) { "Could not decode PikPak cached source for episode ${query.episodeId}" }
            }.getOrNull() ?: return@SinglePagePagedSource emptyList<MediaMatch>().asFlow()

            listOf(
                MediaMatch(
                    CachedMedia(
                        origin = origin,
                        cacheMediaSourceId = mediaSourceId,
                        download = origin.download,
                        location = MediaSourceLocation.Online,
                    ),
                    MatchKind.EXACT,
                ),
            ).asFlow()
        }
    }

    private companion object {
        private val logger = logger<PikPakCloudCacheMediaSource>()
    }
}
