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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackException
import org.openani.mediamp.source.MediaData
import org.openani.mediamp.source.MediaExtraFiles
import org.openani.mediamp.source.UriMediaData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackVideoFormatTest {
    @Test
    fun `common resolutions rotated videos cropped cinema and nonstandard frames`() {
        val cases = mapOf(
            (256 to 144) to "144p", (426 to 240) to "240p", (640 to 360) to "360p",
            (854 to 480) to "480p", (720 to 576) to "576p", (1280 to 720) to "720p",
            (1920 to 1080) to "1080p", (2560 to 1440) to "1440p", (3840 to 2160) to "4K",
            (4096 to 2160) to "4K", (3840 to 1600) to "4K", (1920 to 800) to "1080p",
            (7680 to 4320) to "8K", (1080 to 1080) to "1080p", (1000 to 600) to "1000×600",
            (1920 to 1200) to "1920×1200", (3840 to 400) to "3840×400",
        )
        for ((size, expected) in cases) {
            assertEquals(expected, PlaybackVideoFormat(size.first, size.second).label)
            val rotated = PlaybackVideoFormat(size.second, size.first).label
            assertEquals(if ('×' in expected) "${size.second}×${size.first}" else expected, rotated)
        }
    }

    @Test
    fun `HDR is independent of resolution and unknown does not claim HDR`() {
        assertEquals("4K", PlaybackVideoFormat(3840, 2160, VideoDynamicRange.Unknown).label)
        assertEquals("4K", PlaybackVideoFormat(3840, 2160, VideoDynamicRange.Sdr).label)
        assertEquals("4K HDR", PlaybackVideoFormat(3840, 2160, VideoDynamicRange.Hdr).label)
        assertEquals("1080p HDR", PlaybackVideoFormat(1920, 1080, VideoDynamicRange.Hdr).label)
    }

    @Test
    fun `missing invalid or audio-only dimensions are absent`() {
        for (size in listOf(null to 720, 1280 to null, 0 to 0, -1 to 720, 1280 to -1)) {
            assertNull(playbackVideoFormatOrNull(size.first, size.second))
        }
    }

    @Test
    fun `format follows adaptive changes and clears immediately on every unloaded state`() = runTest {
        val status = MutableStateFlow<MediaStatus>(MediaStatus.Ready)
        val media = MutableStateFlow<MediaData?>(testMedia("first"))
        val format = MutableStateFlow<PlaybackVideoFormat?>(PlaybackVideoFormat(1280, 720))
        var latest: PlaybackVideoFormat? = null
        backgroundScope.launch { observePlaybackVideoFormat(status, media) { format }.collect { latest = it } }
        runCurrent()
        assertEquals("720p", latest?.label)
        format.value = PlaybackVideoFormat(3840, 2160, VideoDynamicRange.Hdr)
        runCurrent()
        assertEquals("4K HDR", latest?.label)
        format.value = null
        runCurrent()
        assertNull(latest)
        for (unloaded in listOf(
            MediaStatus.Opening, MediaStatus.Idle, MediaStatus.Ended, MediaStatus.Released,
            MediaStatus.Error(PlaybackException(PlaybackErrorCode.IO, "Test error")),
        )) {
            status.value = MediaStatus.Ready
            format.value = PlaybackVideoFormat(1920, 1080)
            runCurrent()
            assertEquals("1080p", latest?.label)
            status.value = unloaded
            runCurrent()
            assertNull(latest)
        }
        status.value = MediaStatus.Ready
        media.value = null
        runCurrent()
        assertNull(latest)
    }

    @Test
    fun `switching media clears old format while new metadata is pending and cancels old observer`() = runTest {
        val status = MutableStateFlow<MediaStatus>(MediaStatus.Ready)
        val media = MutableStateFlow<MediaData?>(testMedia("first"))
        var cancelled = 0
        var subscriptions = 0
        val values = mutableListOf<PlaybackVideoFormat?>()
        val observation = observePlaybackVideoFormat(status, media) {
            flow {
                val index = ++subscriptions
                try {
                    delay(100)
                    emit(PlaybackVideoFormat(if (index == 1) 1280 else 1920, if (index == 1) 720 else 1080))
                    delay(10_000)
                } finally {
                    cancelled++
                }
            }
        }
        val job = backgroundScope.launch { observation.collect { values.add(it) } }
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals("720p", values.last()?.label)
        media.value = testMedia("second")
        runCurrent()
        assertNull(values.last())
        assertEquals(1, cancelled)
        advanceTimeBy(100)
        runCurrent()
        assertEquals("1080p", values.last()?.label)
        job.cancel()
        runCurrent()
        assertEquals(2, cancelled)
    }

    private fun testMedia(name: String): MediaData = UriMediaData("file:///$name.mp4", emptyMap(), MediaExtraFiles())
}
