/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.mediaselect.summary

import me.him188.ani.app.domain.media.TestMediaList
import me.him188.ani.app.domain.media.fetch.MediaSourceInfoWithId
import me.him188.ani.app.domain.media.selector.MatchMetadata
import me.him188.ani.app.domain.media.selector.MaybeExcludedMedia
import me.him188.ani.datasources.api.CachedMedia
import me.him188.ani.datasources.api.source.MediaSourceInfo
import me.him188.ani.datasources.api.source.MediaSourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSelectorSummaryStateProducerTest {
    @Test
    fun `PikPak cache keeps original source summary and adds provider name`() {
        val origin = TestMediaList.first().copy(mediaSourceId = "mikan")
        val cached = CachedMedia(
            origin = origin,
            cacheMediaSourceId = "pikpak-cloud-cache",
            download = origin.download,
            location = MediaSourceLocation.Online,
        )
        val selected = MaybeExcludedMedia.Included(
            cached,
            MatchMetadata(
                MatchMetadata.SubjectMatchKind.EXACT,
                MatchMetadata.EpisodeMatchKind.SORT,
                similarity = 100,
            ),
        )

        val summary = createSelectedSummary(
            selected,
            listOf(
                MediaSourceInfoWithId(
                    instanceId = "mikan-instance",
                    mediaSourceId = "mikan",
                    info = MediaSourceInfo(displayName = "蜜柑计划 (CN)"),
                ),
                MediaSourceInfoWithId(
                    instanceId = "pikpak-instance",
                    mediaSourceId = "pikpak-cloud-cache",
                    info = MediaSourceInfo(displayName = "PikPak", isSpecial = true),
                ),
            ),
        )

        assertEquals("蜜柑计划 (CN)", summary.source.sourceName)
        assertEquals(origin.originalTitle, summary.mediaTitle)
        assertEquals("PikPak", summary.cacheProviderName)
    }
}
