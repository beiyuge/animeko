/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.ui.update

import me.him188.ani.app.data.network.protocol.ReleaseClass
import me.him188.ani.utils.platform.Arch
import me.him188.ani.utils.platform.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerTest {
    @Test
    fun `xuanyu release versions sort by upstream stage then fork revision`() {
        val alpha = AnimekoReleaseVersion.parse("6.0.0-alpha02.xuanyu.9")!!
        val beta1 = AnimekoReleaseVersion.parse("6.0.0-beta02.xuanyu.1")!!
        val beta2 = AnimekoReleaseVersion.parse("6.0.0-beta02.xuanyu.2")!!
        assertTrue(alpha < beta1)
        assertTrue(beta1 < beta2)
        assertEquals(beta1.withoutFork(), AnimekoReleaseVersion.parse("v6.0.0-beta02"))
    }

    @Test
    fun `fork checker selects compatible newer release and current Android ABI`() {
        val update = selectForkUpdate(
            releases = listOf(
                release("6.0.0-alpha03.xuanyu.9", asset("alpha-universal.apk")),
                release(
                    "6.0.0-beta02.xuanyu.2",
                    asset("ani-6.0.0-beta02.xuanyu.2-universal.apk"),
                    asset("ani-6.0.0-beta02.xuanyu.2-arm64-v8a.apk"),
                ),
            ),
            currentVersion = "6.0.0-beta02.xuanyu.1",
            releaseClass = ReleaseClass.BETA,
            platform = Platform.Android(Arch.ARMV8A),
        )
        assertNotNull(update)
        assertEquals("6.0.0-beta02.xuanyu.2", update.name)
        assertTrue(update.downloadUrlAlternatives.first().contains("arm64-v8a"))
    }

    @Test
    fun `upstream notice is emitted once per newer compatible tag`() {
        val releases = listOf(
            release("v6.0.0-beta03"),
            release("v6.0.0-alpha04"),
        )
        val first = selectUpstreamNotice(
            releases,
            currentVersion = "6.0.0-beta02.xuanyu.1",
            releaseClass = ReleaseClass.BETA,
            lastNotifiedTag = "",
        )
        assertEquals("v6.0.0-beta03", first?.tag)
        assertNull(
            selectUpstreamNotice(
                releases,
                currentVersion = "6.0.0-beta02.xuanyu.1",
                releaseClass = ReleaseClass.BETA,
                lastNotifiedTag = "v6.0.0-beta03",
            ),
        )
    }

    private fun release(tag: String, vararg assets: GitHubAsset) = GitHubRelease(
        tagName = tag,
        htmlUrl = "https://github.com/example/releases/tag/$tag",
        assets = assets.toList(),
    )

    private fun asset(name: String) = GitHubAsset(name, "https://example.invalid/$name")
}
