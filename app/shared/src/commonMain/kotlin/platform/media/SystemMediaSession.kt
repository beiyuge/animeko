/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform.media

import kotlinx.coroutines.flow.StateFlow
import me.him188.ani.app.platform.Context
import org.openani.mediamp.MediampPlayer

/**
 * Metadata advertised to platform media controls for the current episode.
 */
data class SystemMediaMetadata(
    val title: String,
    val episodeTitle: String,
    val sourceName: String?,
    val artworkUri: String?,
)

/**
 * A platform media-session attachment whose lifetime matches one episode player.
 */
interface SystemMediaSessionRegistration {
    /**
     * Whether the video format currently selected by the playback backend is HDR.
     */
    val isHdr: StateFlow<Boolean>

    fun updateMetadata(metadata: SystemMediaMetadata)

    fun close()
}

expect fun createSystemMediaSessionRegistration(
    context: Context,
    player: MediampPlayer,
): SystemMediaSessionRegistration
