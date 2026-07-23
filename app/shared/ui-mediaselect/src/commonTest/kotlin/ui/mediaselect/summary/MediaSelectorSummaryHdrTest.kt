/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.mediaselect.summary

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import kotlin.test.Test

class MediaSelectorSummaryHdrTest {
    private val selected = MediaSelectorSummary.Selected(
        source = MediaSelectorSourceSummary("蜜柑计划 (CN)", ""),
        mediaTitle = "Episode 03",
        isPerfectMatch = false,
    )

    @Test
    fun `selected HDR source shows badge after source name`() = runAniComposeUiTest {
        setContent {
            ProvideCompositionLocalsForPreview {
                MaterialTheme {
                    MediaSelectorSummaryBanner(
                        summary = selected,
                        onClickSwitchSource = {},
                        modifier = Modifier.width(360.dp),
                        isHdr = true,
                    )
                }
            }
        }

        onNodeWithText("蜜柑计划 (CN)").assertExists()
        onNodeWithText("HDR").assertExists()
    }

    @Test
    fun `selected SDR source does not show badge`() = runAniComposeUiTest {
        setContent {
            ProvideCompositionLocalsForPreview {
                MaterialTheme {
                    MediaSelectorSummaryBanner(
                        summary = selected,
                        onClickSwitchSource = {},
                        modifier = Modifier.width(360.dp),
                    )
                }
            }
        }

        onNodeWithText("HDR").assertDoesNotExist()
    }
}
