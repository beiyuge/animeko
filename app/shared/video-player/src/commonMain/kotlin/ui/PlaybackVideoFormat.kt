/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.source.MediaData
import kotlin.math.max
import kotlin.math.min

/** 当前视频的输入尺寸和动态范围, 不包含窗口缩放、画质增强或屏幕 HDR 能力. */
data class PlaybackVideoFormat(
    val width: Int,
    val height: Int,
    val dynamicRange: VideoDynamicRange = VideoDynamicRange.Unknown,
) {
    init {
        require(width > 0 && height > 0)
    }

    val label: String
        get() = resolutionLabel(width, height) + if (dynamicRange == VideoDynamicRange.Hdr) " HDR" else ""
}

enum class VideoDynamicRange { Unknown, Sdr, Hdr }

internal fun playbackVideoFormatOrNull(
    width: Int?,
    height: Int?,
    dynamicRange: VideoDynamicRange = VideoDynamicRange.Unknown,
): PlaybackVideoFormat? = if (width != null && height != null && width > 0 && height > 0) {
    PlaybackVideoFormat(width, height, dynamicRange)
} else {
    null
}

private fun resolutionLabel(width: Int, height: Int): String {
    val shortSide = min(width, height)
    val longSide = max(width, height)
    // 短边精确匹配, 同时支持竖屏和旋转视频.
    when (shortSide) {
        144, 240, 360, 480, 576, 720, 1080, 1440 -> return "${shortSide}p"
        2160 -> return "4K"
        4320 -> return "8K"
    }
    // 宽银幕裁边视频仍可使用其规格名; 过窄或超出规格高度的画幅显示实际尺寸.
    val nominalHeight = when (longSide) {
        1280 -> 720
        1920 -> 1080
        2560 -> 1440
        3840, 4096 -> 2160
        7680, 8192 -> 4320
        else -> return "${width}×${height}"
    }
    if (shortSide < nominalHeight * 0.65 || shortSide > nominalHeight) return "${width}×${height}"
    return when (nominalHeight) {
        2160 -> "4K"
        4320 -> "8K"
        else -> "${nominalHeight}p"
    }
}

/**
 * 仅在当前媒体 Ready 时订阅原生格式. 加载、停止、错误、结束和释放立即清空;
 * 暂停或同一媒体缓冲时保留, 自适应格式变化由平台观察器更新.
 */
fun playbackVideoFormatFlow(player: MediampPlayer): Flow<PlaybackVideoFormat?> =
    observePlaybackVideoFormat(
        player.state.map { it.mediaStatus },
        player.mediaData,
    ) { observePlatformVideoFormat(player) }.flowOn(player.mainDispatcher)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun observePlaybackVideoFormat(
    statuses: Flow<MediaStatus>,
    media: Flow<MediaData?>,
    observeFormat: () -> Flow<PlaybackVideoFormat?>,
): Flow<PlaybackVideoFormat?> = combine(statuses, media) { status, data ->
    data.takeIf { status == MediaStatus.Ready }
}.distinctUntilChanged().flatMapLatest { data ->
    if (data == null) flowOf(null) else flow<PlaybackVideoFormat?> {
        emit(null)
        emitAll(observeFormat())
    }
}.distinctUntilChanged()

/** 原生后端不提供格式时发出 null, HDR 无可靠依据时使用 Unknown. */
internal expect fun observePlatformVideoFormat(player: MediampPlayer): Flow<PlaybackVideoFormat?>

@Composable
fun rememberPlaybackVideoFormat(player: MediampPlayer): State<PlaybackVideoFormat?> = key(player) {
    produceState<PlaybackVideoFormat?>(null, player) {
        playbackVideoFormatFlow(player).collect { value = it }
    }
}
