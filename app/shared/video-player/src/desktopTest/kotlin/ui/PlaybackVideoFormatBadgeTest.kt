/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.ProvideCompositionLocalsForPreview
import me.him188.ani.app.ui.framework.runAniComposeUiTest
import me.him188.ani.app.videoplayer.ui.top.PlaybackVideoFormatBadge
import me.him188.ani.app.videoplayer.ui.top.PlayerTopBar
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackVideoFormatBadgeTest {
    @Test
    fun `badge follows control transitions avoids buttons and safe insets and is absent in PiP`() {
        for (width in listOf(360, 960)) runAniComposeUiTest {
            val controls = PlayerControllerState(ControllerVisibility.Visible)
            val format = mutableStateOf<PlaybackVideoFormat?>(PlaybackVideoFormat(3840, 2160, VideoDynamicRange.Hdr))
            val videoOnly = mutableStateOf(false)
            var settingsClicks = 0
            var screenshotClicks = 0
            setContent {
                ProvideCompositionLocalsForPreview {
                    VideoScaffold(
                        expanded = true,
                        maintainAspectRatio = false,
                        modifier = Modifier.size(width.dp, 300.dp).background(Color(0xFF52616B)).testTag("player"),
                        controllerState = controls,
                        contentWindowInsets = WindowInsets(left = 16.dp, top = 24.dp, right = 32.dp, bottom = 0.dp),
                        videoOnly = videoOnly.value,
                        video = { Box(Modifier.size(20.dp).testTag("video")) },
                        topBar = {
                            PlayerTopBar(
                                Modifier.testTag("controls"),
                                title = { Text("Playing", color = Color.White) },
                                actions = {
                                    IconButton({ settingsClicks++ }, Modifier.testTag("settings")) {
                                        Icon(Icons.Rounded.Settings, "Settings", tint = Color.White)
                                    }
                                },
                                color = Color.White,
                                windowInsets = WindowInsets(0),
                            )
                        },
                        rhsButtons = {
                            Column(Modifier.testTag("side-controls")) {
                                IconButton({ screenshotClicks++ }, Modifier.testTag("screenshot")) {
                                    Icon(Icons.Rounded.CameraAlt, "Screenshot", tint = Color.White)
                                }
                                Spacer(Modifier.height(8.dp))
                                IconButton({}) { Icon(Icons.Rounded.Lock, "Lock", tint = Color.White) }
                            }
                        },
                        topEndOverlay = { PlaybackVideoFormatBadge(format.value) },
                    )
                }
            }
            waitForIdle()
            onNodeWithText("4K HDR").assertExists()
            val player = onNodeWithTag("player").getBoundsInRoot()
            val badge = onNodeWithTag("playback-video-format").getBoundsInRoot()
            val topBar = onNodeWithTag("controls").getBoundsInRoot()
            assertTrue(badge.top >= topBar.bottom + 12.dp)
            assertEquals((player.right - 32.dp - 48.dp - 16.dp - 12.dp).value, badge.right.value, 0.5f)
            assertTrue(badge.left >= player.left + 16.dp)
            assertTrue(badge.right <= onNodeWithTag("side-controls").getBoundsInRoot().left - 12.dp)
            onNodeWithTag("settings").performClick()
            onNodeWithTag("screenshot").performClick()
            assertEquals(1, settingsClicks)
            assertEquals(1, screenshotClicks)
            saveEvidence("$width-controls-visible")

            mainClock.autoAdvance = false
            runOnIdle { controls.toggleFullVisible(false) }
            mainClock.advanceTimeBy(50)
            onNodeWithTag("controls").assertExists()
            onNodeWithText("4K HDR").assertExists()
            saveEvidence("$width-controls-hiding")
            mainClock.advanceTimeBy(500)
            onNodeWithTag("controls").assertDoesNotExist()
            onNodeWithTag("playback-video-format").assertDoesNotExist()
            saveEvidence("$width-controls-hidden")

            runOnIdle { format.value = PlaybackVideoFormat(854, 480) }
            onNodeWithTag("playback-video-format").assertDoesNotExist()
            runOnIdle { controls.toggleFullVisible(true) }
            mainClock.advanceTimeBy(50)
            onNodeWithTag("controls").assertExists()
            onNodeWithText("480p").assertExists()
            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithText("480p").assertExists()
            onNodeWithText("4K HDR").assertDoesNotExist()
            format.value = null
            waitForIdle()
            onNodeWithTag("playback-video-format").assertDoesNotExist()
            format.value = PlaybackVideoFormat(1920, 1080)
            videoOnly.value = true
            waitForIdle()
            onNodeWithTag("playback-video-format").assertDoesNotExist()
            onNodeWithTag("video").assertExists()
        }
    }

    @Test
    fun `badge honors initial hidden locked progress only and always on control states`() = runAniComposeUiTest {
        val controls = PlayerControllerState(ControllerVisibility.Invisible)
        val locked = mutableStateOf(false)
        setContent {
            ProvideCompositionLocalsForPreview {
                VideoScaffold(
                    expanded = true,
                    modifier = Modifier.size(360.dp, 300.dp),
                    controllerState = controls,
                    gestureLocked = locked.value,
                    topBar = { Text("Controls", Modifier.testTag("controls")) },
                    topEndOverlay = { PlaybackVideoFormatBadge(PlaybackVideoFormat(1920, 1080)) },
                )
            }
        }
        onNodeWithTag("playback-video-format").assertDoesNotExist()
        val requester = Any()
        controls.setRequestProgressBar(requester)
        waitForIdle()
        onNodeWithTag("playback-video-format").assertDoesNotExist()
        controls.cancelRequestProgressBarVisible(requester)
        controls.setRequestAlwaysOn(requester, true)
        waitForIdle()
        onNodeWithTag("controls").assertExists()
        onNodeWithText("1080p").assertExists()
        locked.value = true
        waitForIdle()
        onNodeWithTag("controls").assertDoesNotExist()
        onNodeWithTag("playback-video-format").assertDoesNotExist()
        locked.value = false
        waitForIdle()
        onNodeWithText("1080p").assertExists()
        controls.setRequestInlineProgressSlider(requester)
        waitForIdle()
        onNodeWithTag("playback-video-format").assertDoesNotExist()
        controls.cancelRequestInlineProgressSlider(requester)
        waitForIdle()
        onNodeWithText("1080p").assertExists()
        controls.setRequestAlwaysOn(requester, false)
        waitForIdle()
        onNodeWithTag("controls").assertDoesNotExist()
        onNodeWithTag("playback-video-format").assertDoesNotExist()
    }

    private fun ComposeUiTest.saveEvidence(name: String) {
        val directory = System.getProperty("ani.ui.evidence.dir") ?: return
        val bitmap = onRoot().captureToImage().asSkiaBitmap()
        Image.makeFromBitmap(bitmap).use { image ->
            image.encodeToData()?.use { data ->
                File(directory).mkdirs()
                File(directory, "video-format-$name.png").writeBytes(data.bytes)
            }
        }
    }
}
