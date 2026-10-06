/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.mpv.MPVHandle
import kotlin.time.Duration.Companion.milliseconds

internal actual fun observePlatformVideoFormat(player: MediampPlayer): Flow<PlaybackVideoFormat?> {
    val handle = player.impl as? MPVHandle ?: return player.mediaProperties.map {
        playbackVideoFormatOrNull(it?.videoWidth, it?.videoHeight)
    }
    return flow {
        while (true) {
            emit(runCatching {
                // video-dec-params 是滤镜和用户色彩/尺寸覆盖之前的解码器元数据.
                playbackVideoFormatOrNull(
                    handle.getPropertyInt("video-dec-params/w"),
                    handle.getPropertyInt("video-dec-params/h"),
                    mpvVideoDynamicRange(handle.getPropertyString("video-dec-params/gamma")),
                )
            }.getOrNull())
            delay(250.milliseconds)
        }
    }.distinctUntilChanged()
}

internal fun mpvVideoDynamicRange(gamma: String?): VideoDynamicRange = when (gamma) {
    "pq", "hlg" -> VideoDynamicRange.Hdr
    "bt.1886", "srgb", "gamma1.8", "gamma2.0", "gamma2.2", "gamma2.4", "gamma2.6", "gamma2.8" -> VideoDynamicRange.Sdr
    else -> VideoDynamicRange.Unknown
}
