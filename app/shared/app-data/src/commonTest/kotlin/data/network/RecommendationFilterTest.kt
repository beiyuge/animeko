/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.data.network

import me.him188.ani.client.models.AniSubjectRecommendation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecommendationFilterTest {
    @Test
    fun `keeps only internal recommendations with valid subject ids`() {
        assertTrue(recommendation(subjectId = 1, uri = null).isInternalSubjectRecommendation())
        assertFalse(recommendation(subjectId = 1, uri = "https://ad.example").isInternalSubjectRecommendation())
        assertFalse(recommendation(subjectId = null, uri = null).isInternalSubjectRecommendation())
        assertFalse(recommendation(subjectId = 0, uri = null).isInternalSubjectRecommendation())
    }

    private fun recommendation(subjectId: Long?, uri: String?) = AniSubjectRecommendation(
        subjectName = "name",
        subjectNameCn = "中文名",
        imageUrl = "https://example.com/image.jpg",
        desc1 = "",
        desc2 = "",
        subjectId = subjectId,
        uri = uri,
    )
}
