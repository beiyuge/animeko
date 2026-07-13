/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.domain.torrent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import me.him188.ani.app.data.models.preference.PikPakConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocalTorrentAccessPolicyTest {
    @Test
    fun `block only applies while PikPak and prevention are enabled`() = runTest {
        val config = MutableStateFlow(PikPakConfig.Default)
        val policy = LocalTorrentAccessPolicy(
            config,
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        )

        assertFalse(policy.isBlocked.value)
        config.value = config.value.copy(preventAnitorrentStart = true)
        advanceUntilIdle()
        assertFalse(policy.isBlocked.value)
        config.value = config.value.copy(enabled = true)
        advanceUntilIdle()
        assertTrue(policy.isBlocked.value)
    }

    @Test
    fun `temporary permission is scoped to one media and revoked`() = runTest {
        val config = MutableStateFlow(PikPakConfig.Default.copy(enabled = true, preventAnitorrentStart = true))
        val policy = LocalTorrentAccessPolicy(
            config,
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()

        assertFalse(policy.isMediaAllowed("episode-1"))
        policy.allowForCurrentPlayback("episode-1")
        advanceUntilIdle()
        assertTrue(policy.isMediaAllowed("episode-1"))
        assertFalse(policy.isMediaAllowed("episode-2"))
        assertIs<TemporaryTorrentPlaybackToken>(policy.requestToken("episode-1", Any()))

        policy.revokeTemporaryAccess("episode-1")
        advanceUntilIdle()
        assertTrue(policy.isBlocked.value)
        assertFalse(policy.isMediaAllowed("episode-1"))
    }
}
