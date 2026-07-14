/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.domain.media.resolver

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.app.domain.torrent.LocalTorrentAccessPolicy
import me.him188.ani.torrent.offline.OfflineDownloadProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PikPakPlaybackCoordinatorTest {
    @Test
    fun `cached stream refresh marks the hit before playback starts`() = runTest {
        val policy = LocalTorrentAccessPolicy(
            MutableStateFlow(PikPakConfig.Default.copy(enabled = true)),
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        )
        val coordinator = PikPakPlaybackCoordinator(policy)

        coordinator.begin("episode-1")
        coordinator.updateProgress("episode-1", OfflineDownloadProgress.CheckingCloudCache)
        assertFalse(coordinator.state.value.cloudCacheHit)

        coordinator.updateProgress("episode-1", OfflineDownloadProgress.ResolvingCachedStreamUrl)
        assertTrue(coordinator.state.value.cloudCacheHit)
        assertEquals(
            PikPakPlaybackState.Status.Resolving(OfflineDownloadProgress.ResolvingCachedStreamUrl),
            coordinator.state.value.status,
        )
    }

    @Test
    fun `retry count survives full re-resolution and fallback is one shot`() = runTest {
        val policy = LocalTorrentAccessPolicy(
            MutableStateFlow(PikPakConfig.Default.copy(enabled = true, preventAnitorrentStart = true)),
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        )
        val coordinator = PikPakPlaybackCoordinator(policy)

        coordinator.begin("episode-1")
        coordinator.failed("episode-1", IllegalStateException("first"))
        coordinator.begin("episode-1")
        coordinator.failed("episode-1", IllegalStateException("second"))
        assertEquals(2, coordinator.state.value.failureCount)

        coordinator.requestLocalFallback("episode-1")
        assertIs<PikPakPlaybackState.Status.LocalAnitorrentFallback>(coordinator.state.value.status)
        assertTrue(policy.isMediaAllowed("episode-1"))
        assertFalse(policy.isMediaAllowed("episode-2"))
        assertTrue(coordinator.consumeLocalFallback("episode-1"))
        assertFalse(coordinator.consumeLocalFallback("episode-1"))

        coordinator.reset("episode-1")
        assertFalse(policy.isMediaAllowed("episode-1"))
        assertEquals(PikPakPlaybackState(), coordinator.state.value)
    }
}
