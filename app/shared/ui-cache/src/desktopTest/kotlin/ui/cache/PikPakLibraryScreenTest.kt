/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.ui.cache

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import me.him188.ani.app.data.persistent.DataStoreJson
import me.him188.ani.app.domain.media.createTestDefaultMedia
import me.him188.ani.app.domain.media.createTestMediaProperties
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.assertScreenshot
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.datasources.api.DefaultMedia
import me.him188.ani.datasources.api.source.MediaSourceKind
import me.him188.ani.datasources.api.source.MediaSourceLocation
import me.him188.ani.datasources.api.topic.ResourceLocation
import me.him188.ani.torrent.offline.OfflineDownloadLibraryEntry
import me.him188.ani.torrent.offline.OfflineDownloadLibraryManifest
import me.him188.ani.torrent.offline.OfflineDownloadLibraryState
import me.him188.ani.torrent.offline.OfflineDownloadUnmatchedResource
import me.him188.ani.utils.platform.currentPlatformDesktop
import me.him188.ani.utils.platform.isWindows
import kotlin.test.Test
import kotlin.test.assertEquals

class PikPakLibraryScreenTest {
    @Test
    fun `groups episodes shows unmatched and long press asks before deletion`() = runAniComposeUiTest {
        var rematched: Triple<String, Int, Int>? = null
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
                        onRematchUnmatched = { providerFileId, subjectId, episodeId ->
                            rematched = Triple(providerFileId, subjectId, episodeId)
                        },
                    )
                }
            }
        }

        onNodeWithText("测试动画").assertExists()
        onNodeWithText("未匹配资源").assertExists()
        onNodeWithText("旧季度合集").assertExists()
        if (!currentPlatformDesktop().isWindows()) {
            onNodeWithTag("PikPakLibraryScreen", useUnmergedTree = true)
                .assertScreenshot("/screenshots/PikPakLibraryScreenTest.library.png")
        }
        onNodeWithTag("PikPakEpisode:100:ep-1").performTouchInput { longClick() }
        onNodeWithText("删除这集的 PikPak 缓存？").assertExists()
        onNodeWithText("取消").performClick()

        onNodeWithTag("PikPakUnmatched:legacy").performClick()
        onNodeWithTag("PikPakRematchSubjectId").performTextInput("100")
        onNodeWithTag("PikPakRematchEpisodeId").performTextInput("200")
        onNodeWithText("选择资源").performClick()
        runOnIdle { assertEquals(Triple("legacy", 100, 200), rematched) }
    }

    private fun entry(episodeId: String, number: String) = OfflineDownloadLibraryEntry(
        entryId = "100:$episodeId:file-$episodeId",
        subjectId = "100",
        subjectName = "测试动画",
        episodeId = episodeId,
        episodeNumber = number,
        episodeTitle = "第 $number 集",
        sourceKey = "source",
        sourcePayload = DataStoreJson.encodeToString(
            DefaultMedia.serializer(),
            createTestDefaultMedia(
                mediaId = "media-$episodeId",
                mediaSourceId = "test",
                originalUrl = "https://example.invalid/$episodeId",
                download = ResourceLocation.MagnetLink("magnet:?xt=urn:btih:$episodeId"),
                originalTitle = "测试动画 第 $number 集",
                publishedTime = 1,
                properties = createTestMediaProperties(subjectName = "测试动画"),
                episodeRange = null,
                location = MediaSourceLocation.Online,
                kind = MediaSourceKind.BitTorrent,
            ),
        ),
        resourceRootId = "root",
        providerFileId = "file-$episodeId",
        providerFileName = "$number.mkv",
        createdAt = 1,
        updatedAt = 1,
    )
}
