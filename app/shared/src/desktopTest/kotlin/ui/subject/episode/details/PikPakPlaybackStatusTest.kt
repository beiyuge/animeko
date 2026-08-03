/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.ui.subject.episode.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.domain.media.resolver.PikPakPlaybackState
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.assertScreenshot
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.pikpak_playback_active
import me.him188.ani.app.ui.lang.pikpak_playback_cloud_cache_hit
import me.him188.ani.app.ui.lang.pikpak_playback_retry
import me.him188.ani.app.ui.lang.pikpak_playback_use_anitorrent_once
import me.him188.ani.torrent.offline.OfflineEpisodeAvailability
import me.him188.ani.utils.platform.currentPlatformDesktop
import me.him188.ani.utils.platform.isWindows
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

class PikPakPlaybackStatusTest {
    @Test
    fun `trailing gap offers refresh while not aired does not parse`() = runAniComposeUiTest {
        var refreshCount = 0
        setContent {
            ProvideCompositionLocalsForPreview {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.width(520.dp).padding(16.dp)) {
                        PikPakEpisodeAvailabilityStatus(
                            OfflineEpisodeAvailability.TrailingGap,
                            onSelectResource = {},
                            onRefresh = { refreshCount++ },
                        )
                    }
                }
            }
        }
        onNodeWithText("更新").performClick()
        runOnIdle { assertEquals(1, refreshCount) }

        setContent {
            ProvideCompositionLocalsForPreview {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.width(520.dp).padding(16.dp)) {
                        PikPakEpisodeAvailabilityStatus(
                            OfflineEpisodeAvailability.NotAired,
                            onSelectResource = { error("未开播不应解析") },
                            onRefresh = { error("未开播不应更新") },
                        )
                    }
                }
            }
        }
        onNodeWithText("尚未开播", substring = true).assertExists()
        onNodeWithText("更新").assertDoesNotExist()
        onNodeWithText("选择单集资源").assertDoesNotExist()
    }

    @Test
    fun `playing status displays measured download and zero upload`() = runAniComposeUiTest {
        val active = runBlocking { getString(Lang.pikpak_playback_active) }
        setContent {
            ProvideCompositionLocalsForPreview {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.width(520.dp).padding(16.dp).testTag("PikPakPlaybackStatusPreview")) {
                        PikPakPlaybackStatus(
                            PikPakPlaybackState(
                                mediaId = "episode-1",
                                status = PikPakPlaybackState.Status.Playing,
                                downloadBytesPerSecond = 2_048,
                                downloadedBytes = 8_388_608,
                            ),
                            onRetry = {},
                            onUseAnitorrentOnce = {},
                        )
                    }
                }
            }
        }

        onNodeWithText(active).assertExists()
        onNodeWithText("2.0 KB/s", substring = true).assertExists()
        onNodeWithText("8.0 MB", substring = true).assertExists()
        onNodeWithText("0 B/s", substring = true).assertExists()
        if (!currentPlatformDesktop().isWindows()) {
            onNodeWithTag("PikPakPlaybackStatusPreview", useUnmergedTree = true)
                .assertScreenshot("/screenshots/PikPakPlaybackStatusTest.playing.png")
        }
    }

    @Test
    fun `cloud cache hit displays persistent playback notice`() = runAniComposeUiTest {
        val cacheHit = runBlocking { getString(Lang.pikpak_playback_cloud_cache_hit) }
        setContent {
            ProvideCompositionLocalsForPreview {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.width(520.dp).padding(16.dp)) {
                        PikPakPlaybackStatus(
                            PikPakPlaybackState(
                                mediaId = "episode-cached",
                                status = PikPakPlaybackState.Status.Playing,
                                cloudCacheHit = true,
                            ),
                            onRetry = {},
                            onUseAnitorrentOnce = {},
                        )
                    }
                }
            }
        }

        onNodeWithText(cacheHit).assertExists()
    }

    @Test
    fun `second failure exposes retry and one-shot fallback actions`() = runAniComposeUiTest {
        val retry = runBlocking { getString(Lang.pikpak_playback_retry) }
        val fallback = runBlocking { getString(Lang.pikpak_playback_use_anitorrent_once) }
        var retryCount = 0
        var fallbackMediaId: String? = null
        setContent {
            ProvideCompositionLocalsForPreview {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.width(520.dp).padding(16.dp)) {
                        PikPakPlaybackStatus(
                            PikPakPlaybackState(
                                mediaId = "episode-2",
                                status = PikPakPlaybackState.Status.Failed("cloud task timed out"),
                                failureCount = 2,
                            ),
                            onRetry = { retryCount++ },
                            onUseAnitorrentOnce = { fallbackMediaId = it },
                        )
                    }
                }
            }
        }

        onNodeWithText(retry).performClick()
        onNodeWithText(fallback).performClick()
        runOnIdle {
            assertEquals(1, retryCount)
            assertEquals("episode-2", fallbackMediaId)
        }
    }
}
