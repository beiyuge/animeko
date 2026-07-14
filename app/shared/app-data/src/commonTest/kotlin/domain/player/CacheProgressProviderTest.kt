/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.domain.player

import androidx.collection.floatListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.media.player.ChunkState
import me.him188.ani.app.domain.media.player.MediaCacheProgressInfo
import me.him188.ani.app.domain.media.resolver.PikPakPlaybackState
import org.openani.mediamp.source.UriMediaData
import org.openani.mediamp.test.TestMediampPlayer
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CacheProgressProviderTest {
    @Test
    fun `non torrent PikPak playback exposes its local cached ranges`() = runTest {
        val player = TestMediampPlayer(UnconfinedTestDispatcher(testScheduler))
        val cachedRange = MediaCacheProgressInfo(
            chunkWeights = floatListOf(0.4f, 0.6f),
            chunkStates = listOf(ChunkState.DONE, ChunkState.NONE),
        )
        val pikPakState = MutableStateFlow(
            PikPakPlaybackState(
                mediaId = "episode-1",
                status = PikPakPlaybackState.Status.Playing,
                cacheProgressInfo = cachedRange,
            ),
        )
        val provider = CacheProgressProvider(
            playerState = player,
            flowScope = backgroundScope,
            pikPakPlaybackState = pikPakState,
        )

        player.setMediaData(UriMediaData("http://127.0.0.1/pikpak-media"))

        assertEquals(cachedRange, provider.cacheProgressInfoFlow.first { !it.isEmpty() })
    }
}
