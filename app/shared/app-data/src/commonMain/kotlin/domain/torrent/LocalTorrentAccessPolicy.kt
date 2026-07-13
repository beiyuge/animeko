/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束.
 */

package me.him188.ani.app.domain.torrent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.him188.ani.app.data.models.preference.PikPakConfig
import me.him188.ani.app.data.repository.user.SettingsRepository

/** Coordinates the persistent PikPak block and a playback-scoped override. */
class LocalTorrentAccessPolicy(
    pikPakConfig: Flow<PikPakConfig>,
    scope: CoroutineScope,
) {
    constructor(settingsRepository: SettingsRepository, scope: CoroutineScope) : this(
        settingsRepository.pikpakConfig.flow,
        scope,
    )

    private val temporaryMediaId = MutableStateFlow<String?>(null)

    /** Persistent global block. A temporary token bypasses it only for its media. */
    val isBlocked: StateFlow<Boolean> = pikPakConfig
        .map { config -> config.enabled && config.preventAnitorrentStart }
        .stateIn(scope, SharingStarted.Eagerly, false)

    fun isMediaAllowed(mediaId: String): Boolean =
        !isBlocked.value || temporaryMediaId.value == mediaId

    fun allowForCurrentPlayback(mediaId: String) {
        temporaryMediaId.value = mediaId
    }

    fun revokeTemporaryAccess(mediaId: String? = null) {
        if (mediaId == null || temporaryMediaId.value == mediaId) {
            temporaryMediaId.value = null
        }
    }

    fun requestToken(mediaId: String, owner: Any): Any =
        if (temporaryMediaId.value == mediaId) TemporaryTorrentPlaybackToken(mediaId, owner) else owner
}

data class TemporaryTorrentPlaybackToken(
    val mediaId: String,
    val owner: Any,
)

class LocalTorrentBlockedException : IllegalStateException(
    "Anitorrent is blocked while PikPak is enabled",
)
