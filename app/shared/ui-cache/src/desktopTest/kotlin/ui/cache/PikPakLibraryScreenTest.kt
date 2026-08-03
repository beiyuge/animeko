/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.ui.cache

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.torrent.offline.OfflineDownloadLibraryEntry
import me.him188.ani.torrent.offline.OfflineDownloadLibraryManifest
import me.him188.ani.torrent.offline.OfflineDownloadLibraryState
import me.him188.ani.torrent.offline.OfflineDownloadUnmatchedResource
import kotlin.test.Test

class PikPakLibraryScreenTest {
    @Test
    fun `groups episodes shows unmatched and long press asks before deletion`() = runAniComposeUiTest {
        setContent {
            ProvideCompositionLocalsForPreview {
                MaterialTheme {
                    PikPakLibraryScreen(
                        state = OfflineDownloadLibraryState(
                            status = OfflineDownloadLibraryState.Status.Ready,
                            manifest = OfflineDownloadLibraryManifest(
                                entries = listOf(entry("ep-1", "1"), entry("ep-2", "2")),
                            ),
                            unmatchedResources = listOf(
                                OfflineDownloadUnmatchedResource("legacy", "旧季度合集", true),
                            ),
                        ),
                        onRefresh = {},
                        onPlay = { _, _ -> },
                        onDeleteEpisode = { _, _ -> },
                        onDeleteUnmatched = {},
                    )
                }
            }
        }

        onNodeWithText("测试动画").assertExists()
        onNodeWithText("未匹配资源").assertExists()
        onNodeWithText("旧季度合集").assertExists()
        onNodeWithTag("PikPakEpisode:100:ep-1").performTouchInput { longClick() }
        onNodeWithText("删除这集的 PikPak 缓存？").assertExists()
    }

    private fun entry(episodeId: String, number: String) = OfflineDownloadLibraryEntry(
        entryId = "100:$episodeId:file-$episodeId",
        subjectId = "100",
        subjectName = "测试动画",
        episodeId = episodeId,
        episodeNumber = number,
        episodeTitle = "第 $number 集",
        sourceKey = "source",
        sourcePayload = "{}",
        resourceRootId = "root",
        providerFileId = "file-$episodeId",
        providerFileName = "$number.mkv",
        createdAt = 1,
        updatedAt = 1,
    )
}
