/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.media.resolver

import kotlinx.coroutines.CoroutineScope
import me.him188.ani.app.domain.media.player.MediaCacheProgressInfo
import me.him188.ani.app.domain.media.player.data.MediaDataProvider
import me.him188.ani.datasources.api.Media
import me.him188.ani.datasources.api.topic.ResourceLocation
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData

class HttpStreamingMediaResolver : MediaResolver {
    override fun supports(media: Media): Boolean {
        return media.download is ResourceLocation.HttpStreamingFile
    }

    override suspend fun resolve(media: Media, episode: EpisodeMetadata): MediaDataProvider<*> {
        if (!supports(media)) throw UnsupportedMediaException(media)
        return HttpStreamingMediaDataProvider(
            media.download.uri,
            media.originalTitle,
            emptyMap(),
            media.extraFiles.toMediampMediaExtraFiles(),
        )
    }
}

class HttpStreamingMediaDataProvider(
    val uri: String,
    val originalTitle: String,
    private val headers: Map<String, String> = emptyMap(),
    override val extraFiles: MediaExtraFiles = MediaExtraFiles.EMPTY,
) : MediaDataProvider<UriMediaData> {
    override suspend fun open(scopeForCleanup: CoroutineScope): UriMediaData = UriMediaData(uri, headers, extraFiles)
    override fun toString(): String = "HttpStreamingVideoSource(uri='$uri')"
}

interface PikPakBackedMediaDataProvider {
    val pikPakMediaId: String
}

class PikPakStreamingMediaDataProvider(
    val uri: String,
    val originalTitle: String,
    private val headers: Map<String, String> = emptyMap(),
    override val extraFiles: MediaExtraFiles = MediaExtraFiles.EMPTY,
    val mediaId: String,
    private val providerFileId: String?,
    private val fileSize: Long?,
    private val contentType: String?,
    private val playbackCoordinator: PikPakPlaybackCoordinator? = null,
) : MediaDataProvider<UriMediaData>, PikPakBackedMediaDataProvider, ManagedMediaDataProvider {
    override val pikPakMediaId: String get() = mediaId
    private var transportSession: PikPakPlaybackTransportSession? = null

    override suspend fun open(scopeForCleanup: CoroutineScope): UriMediaData {
        val coordinator = playbackCoordinator
        if (coordinator == null) return UriMediaData(uri, headers, extraFiles)
        transportSession?.close()
        return createPikPakPlaybackTransportSession(
            uri = uri,
            headers = headers,
            extraFiles = extraFiles,
            cacheKey = providerFileId ?: mediaId,
            contentLength = fileSize,
            contentType = contentType,
            onTraffic = { speed, total -> coordinator.updateTraffic(mediaId, speed, total) },
            onCacheProgress = { progress -> coordinator.updateCacheProgress(mediaId, progress) },
        ).also { transportSession = it }.mediaData
    }

    fun updatePlaybackWindow(positionMillis: Long, durationMillis: Long) {
        transportSession?.updatePlaybackWindow(positionMillis, durationMillis)
    }

    override fun closeProvider() {
        transportSession?.close()
        transportSession = null
        playbackCoordinator?.reset(mediaId)
    }

    override fun toString(): String = "PikPakStreamingVideoSource(mediaId='$mediaId')"
}

interface ManagedMediaDataProvider {
    fun closeProvider()
}

interface PikPakPlaybackTransportSession : AutoCloseable {
    val mediaData: UriMediaData

    /** Updates the byte prefetch target using the player's real media timeline. */
    fun updatePlaybackWindow(positionMillis: Long, durationMillis: Long)
}

internal expect fun createPikPakPlaybackTransportSession(
    uri: String,
    headers: Map<String, String>,
    extraFiles: MediaExtraFiles,
    cacheKey: String,
    contentLength: Long?,
    contentType: String?,
    onTraffic: (bytesPerSecond: Long, downloadedBytes: Long) -> Unit,
    onCacheProgress: (MediaCacheProgressInfo) -> Unit,
): PikPakPlaybackTransportSession
