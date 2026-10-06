/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class MpvVideoDynamicRangeTest {
    @Test
    fun `only explicit HDR transfer functions claim HDR`() {
        for (gamma in listOf("pq", "hlg")) assertEquals(VideoDynamicRange.Hdr, mpvVideoDynamicRange(gamma))
        assertEquals(VideoDynamicRange.Sdr, mpvVideoDynamicRange("bt.1886"))
        for (gamma in listOf(null, "", "auto", "linear", "unknown", "bt.2020", "yuv420p10")) {
            assertEquals(VideoDynamicRange.Unknown, mpvVideoDynamicRange(gamma))
        }
    }
}
