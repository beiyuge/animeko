/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.torrent.pikpak

import me.him188.ani.torrent.offline.OfflineDownloadNaming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PikPakCloudNamingTest {
    private val naming = OfflineDownloadNaming(
        subjectName = "间谍过家家 第二季",
        episodeTitle = "母亲与妻子",
        episodeNumber = "02",
    )

    @Test
    fun `bucket name is readable while retaining the durable source mapping`() {
        val key = "ABCDEF1234567890ABCDEF1234567890ABCDEF12"
        val name = readableBucketName(key, naming)

        assertTrue(name.startsWith("间谍过家家 第二季"))
        assertEquals(key, bucketSourceKey(name))
    }

    @Test
    fun `invalid cloud filename characters are removed`() {
        val name = readableBucketName(
            sourceKey = "h-0123456789abcdef",
            naming = naming.copy(subjectName = "间谍/过家家:*?"),
        )

        assertFalse(name.contains('/'))
        assertFalse(name.contains(':'))
        assertFalse(name.contains('*'))
        assertFalse(name.contains('?'))
        assertEquals("h-0123456789abcdef", bucketSourceKey(name))
    }

    @Test
    fun `video filename uses Chinese episode label and keeps extension`() {
        assertEquals(
            "第02集 - 母亲与妻子【file-123】.mkv",
            readableVideoFileName(
                originalName = "[Group] Show S02E02.mkv",
                providerFileId = "file-123456789",
                naming = naming,
            ),
        )
    }

    @Test
    fun `special episode filename uses Chinese special label`() {
        assertEquals(
            "特别篇-SP - 特典【special-】.mp4",
            readableVideoFileName(
                originalName = "bonus.mp4",
                providerFileId = "special-id",
                naming = naming.copy(episodeTitle = "特典", episodeNumber = "SP"),
            ),
        )
    }
}
