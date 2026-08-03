/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.player.extension

import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import me.him188.ani.app.data.persistent.DataStoreJson
import me.him188.ani.app.data.repository.user.SettingsRepository
import me.him188.ani.app.domain.episode.EpisodeSession
import me.him188.ani.app.domain.media.cache.storage.PIKPAK_CACHE_MEDIA_SOURCE_ID
import me.him188.ani.app.domain.media.selector.MediaSelector
import me.him188.ani.app.domain.media.selector.MediaSelectorAutoSelectUseCase
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.torrent.offline.OfflineDownloadLibrary
import me.him188.ani.torrent.offline.OfflineDownloadLibraryState
import org.koin.core.Koin

/**
 * 自动选择数据源
 *
 * @see MediaSelector
 */
class AutoSelectExtension(
    private val context: PlayerExtensionContext,
    koin: Koin
) : PlayerExtension("AutoSelect") {
    private val mediaSelectorAutoSelectUseCase: MediaSelectorAutoSelectUseCase by koin.inject()
    private val settingsRepository = koin.getOrNull<SettingsRepository>()
    private val offlineDownloadLibrary = koin.getOrNull<OfflineDownloadLibrary>()

    override fun onStart(
        episodeSession: EpisodeSession,
        backgroundTaskScope: ExtensionBackgroundTaskScope
    ) {
        backgroundTaskScope.launch("AutoSelect") {
            context.sessionFlow.flatMapLatest { session ->
                combine(
                    session.fetchSelectFlow,
                    session.infoBundleFlow.filterNotNull(),
                    ::Pair,
                )
            }.collectLatest { (fetchSelect, info) ->
                if (fetchSelect == null) return@collectLatest
                val settings = settingsRepository
                val library = offlineDownloadLibrary
                if (settings != null && library != null && settings.pikpakConfig.flow.first().enabled) {
                    if (library.libraryState.value.status == OfflineDownloadLibraryState.Status.Idle) {
                        library.sync()
                    }
                    val subjectEntries = library.libraryState.value.entries
                        .filter { it.subjectId == info.subjectId.toString() }
                    val targetEntry = subjectEntries
                        .filter { it.episodeId == info.episodeId.toString() }
                        .maxWithOrNull(compareBy({ it.preferred }, { it.updatedAt }))
                    if (targetEntry != null) {
                        val origin = runCatching {
                            DataStoreJson.decodeFromString(DefaultMedia.serializer(), targetEntry.sourcePayload)
                        }.getOrNull()
                        if (origin != null) {
                            fetchSelect.mediaSelector.select(
                                CachedMedia(
                                    origin = origin,
                                    cacheMediaSourceId = PIKPAK_CACHE_MEDIA_SOURCE_ID,
                                    download = origin.download,
                                    location = MediaSourceLocation.Online,
                                ),
                            )
                            return@collectLatest
                        }
                    }
                    // Once a title has become a PikPak library, missing episodes are user-driven:
                    // the page distinguishes middle/tail/not-aired and opens one explicit selector.
                    if (subjectEntries.isNotEmpty()) return@collectLatest
                }
                mediaSelectorAutoSelectUseCase(fetchSelect.mediaFetchSession, fetchSelect.mediaSelector)
            }
        }
    }

    companion object : EpisodePlayerExtensionFactory<AutoSelectExtension> {
        override fun create(context: PlayerExtensionContext, koin: Koin): AutoSelectExtension {
            return AutoSelectExtension(context, koin)
        }
    }
}
