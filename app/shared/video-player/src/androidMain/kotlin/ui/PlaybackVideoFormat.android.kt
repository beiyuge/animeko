/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.openani.mediamp.MediampPlayer
import kotlin.time.Duration.Companion.milliseconds

@OptIn(UnstableApi::class)
internal actual fun observePlatformVideoFormat(player: MediampPlayer): Flow<PlaybackVideoFormat?> {
    val exoPlayer = player.impl as? ExoPlayer ?: return player.mediaProperties.map {
        playbackVideoFormatOrNull(it?.videoWidth, it?.videoHeight)
    }
    return flow {
        while (true) {
            // videoFormat 是当前输入格式, 不是候选轨道的最高规格或视频 Surface 的尺寸.
            val format = exoPlayer.videoFormat.takeIf { exoPlayer.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO) }
            emit(format?.toPlaybackVideoFormat())
            delay(250.milliseconds)
        }
    }.distinctUntilChanged()
}

@OptIn(UnstableApi::class)
internal fun Format.toPlaybackVideoFormat(): PlaybackVideoFormat? = playbackVideoFormatOrNull(
    width, height,
    when (colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG -> VideoDynamicRange.Hdr
        C.COLOR_TRANSFER_SDR -> VideoDynamicRange.Sdr
        else -> VideoDynamicRange.Unknown
    },
)
