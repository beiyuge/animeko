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
import me.him188.ani.app.ui.lang.pikpak_playback_retry
import me.him188.ani.app.ui.lang.pikpak_playback_use_anitorrent_once
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals

class PikPakPlaybackStatusTest {
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
        onNodeWithTag("PikPakPlaybackStatusPreview", useUnmergedTree = true)
            .assertScreenshot("/screenshots/PikPakPlaybackStatusTest.playing.png")
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
