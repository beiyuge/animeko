/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.ui.download

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import me.him188.ani.app.data.models.preference.NsfwMode
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

class PikPakLibraryScreenTest {
    @Test
    fun `shows covered subject cards hides unmatched and long press asks before deletion`() = runAniComposeUiTest {
        setContent {
            ProvideCompositionLocalsForPreview {
                MaterialTheme(colorScheme = lightColorScheme()) {
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
                        subjectCovers = mapOf(
                            "100" to PikPakSubjectCover(TEST_COVER_URL, NsfwMode.DISPLAY),
                        ),
                        onRefresh = {},
                        onPlay = { _, _ -> },
                        onDeleteEpisode = { _, _ -> },
                    )
                }
            }
        }

        onNodeWithText("测试动画").assertExists()
        onNodeWithContentDescription("测试动画").assertExists()
        onNodeWithText("第1集 · 第 1 集").assertExists()
        onNodeWithText("2 集已缓存 · 长按剧集可删除").assertExists()
        onNodeWithText("未匹配资源").assertDoesNotExist()
        onNodeWithText("旧季度合集").assertDoesNotExist()
        if (!currentPlatformDesktop().isWindows()) {
            onNodeWithTag("PikPakLibraryScreen", useUnmergedTree = true)
                .assertScreenshot("/screenshots/PikPakLibraryScreenTest.library.png")
        }
        onNodeWithTag("PikPakEpisode:100:ep-1").performTouchInput { longClick() }
        onNodeWithText("删除这集的 PikPak 缓存？").assertExists()
        onNodeWithText("取消").performClick()
    }

    @Test
    fun `honors hidden NSFW subjects`() = runAniComposeUiTest {
        setContent {
            ProvideCompositionLocalsForPreview {
                MaterialTheme(colorScheme = lightColorScheme()) {
                    PikPakLibraryScreen(
                        state = OfflineDownloadLibraryState(
                            status = OfflineDownloadLibraryState.Status.Ready,
                            manifest = OfflineDownloadLibraryManifest(entries = listOf(entry("ep-1", "1"))),
                        ),
                        subjectCovers = mapOf(
                            "100" to PikPakSubjectCover(TEST_COVER_URL, NsfwMode.HIDE),
                        ),
                        onRefresh = {},
                        onPlay = { _, _ -> },
                        onDeleteEpisode = { _, _ -> },
                    )
                }
            }
        }

        onNodeWithText("测试动画").assertDoesNotExist()
        onNodeWithTag("PikPakEpisode:100:ep-1").assertDoesNotExist()
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

    private companion object {
        const val TEST_COVER_URL = "https://example.invalid/cover.png"
    }
}
