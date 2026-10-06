/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.openani.mediamp.MediampPlayer
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.currentItem
import platform.AVFoundation.presentationSize
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalForeignApi::class)
internal actual fun observePlatformVideoFormat(player: MediampPlayer): Flow<PlaybackVideoFormat?> {
    val avPlayer = player.impl as? AVPlayer ?: return player.mediaProperties.map {
        playbackVideoFormatOrNull(it?.videoWidth, it?.videoHeight)
    }
    return flow {
        while (true) {
            // presentationSize 跟随当前呈现的视频, 包含像素比例和旋转, 不受视图尺寸影响.
            // AVKit 接口未提供当前 HLS 变体的色彩传递信息, 不使用 asset 候选轨道推断 HDR.
            emit(avPlayer.currentItem?.presentationSize?.useContents {
                if (width.isFinite() && height.isFinite()) {
                    playbackVideoFormatOrNull(width.roundToInt(), height.roundToInt())
                } else null
            })
            delay(250.milliseconds)
        }
    }.distinctUntilChanged()
}
