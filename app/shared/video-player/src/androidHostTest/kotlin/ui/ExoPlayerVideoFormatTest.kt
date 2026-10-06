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
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(UnstableApi::class)
class ExoPlayerVideoFormatTest {
    @Test
    fun `PQ and HLG are HDR and absent linear or unspecified transfer are unknown`() {
        for (transfer in listOf(C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG)) {
            assertEquals("4K HDR", format(transfer).toPlaybackVideoFormat()?.label)
        }
        for (transfer in listOf(Format.NO_VALUE, C.COLOR_TRANSFER_LINEAR)) {
            assertEquals(VideoDynamicRange.Unknown, format(transfer).toPlaybackVideoFormat()?.dynamicRange)
            assertEquals("4K", format(transfer).toPlaybackVideoFormat()?.label)
        }
        assertEquals(VideoDynamicRange.Sdr, format(C.COLOR_TRANSFER_SDR).toPlaybackVideoFormat()?.dynamicRange)
        assertEquals(VideoDynamicRange.Unknown, Format.Builder().setWidth(3840).setHeight(2160).build().toPlaybackVideoFormat()?.dynamicRange)
    }

    @Test
    fun `format dimensions are source dimensions independent of rotation and invalid dimensions are absent`() {
        val rotated = format(C.COLOR_TRANSFER_ST2084).buildUpon().setRotationDegrees(90).build()
        assertEquals("4K HDR", rotated.toPlaybackVideoFormat()?.label)
        assertNull(Format.Builder().build().toPlaybackVideoFormat())
        assertNull(Format.Builder().setWidth(0).setHeight(1080).build().toPlaybackVideoFormat())
    }

    private fun format(transfer: Int): Format = Format.Builder().setWidth(3840).setHeight(2160)
        .setColorInfo(ColorInfo.Builder().setColorTransfer(transfer).build()).build()
}
