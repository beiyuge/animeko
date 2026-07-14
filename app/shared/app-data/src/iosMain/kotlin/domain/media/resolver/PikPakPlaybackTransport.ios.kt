/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.domain.media.resolver

import me.him188.ani.app.domain.media.player.MediaCacheProgressInfo
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData

internal actual fun createPikPakPlaybackTransportSession(
    uri: String,
    headers: Map<String, String>,
    extraFiles: MediaExtraFiles,
    cacheKey: String,
    contentLength: Long?,
    contentType: String?,
    onTraffic: (bytesPerSecond: Long, downloadedBytes: Long) -> Unit,
    onCacheProgress: (MediaCacheProgressInfo) -> Unit,
): PikPakPlaybackTransportSession = object : PikPakPlaybackTransportSession {
    override val mediaData = UriMediaData(uri, headers, extraFiles)
    override fun updatePlaybackWindow(positionMillis: Long, durationMillis: Long) = Unit
    override fun close() = Unit
}
