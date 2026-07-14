/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.domain.media.resolver

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.him188.ani.app.domain.media.player.MediaCacheProgressInfo
import me.him188.ani.app.domain.torrent.LocalTorrentAccessPolicy
import me.him188.ani.torrent.offline.OfflineDownloadProgress

data class PikPakPlaybackState(
    val mediaId: String? = null,
    val status: Status = Status.Idle,
    val failureCount: Int = 0,
    val cloudCacheHit: Boolean = false,
    val cacheProgressInfo: MediaCacheProgressInfo = MediaCacheProgressInfo.Empty,
    val downloadBytesPerSecond: Long = 0,
    val downloadedBytes: Long = 0,
) {
    sealed interface Status {
        data object Idle : Status
        data class Resolving(val progress: OfflineDownloadProgress) : Status
        data object Playing : Status
        data class Failed(val message: String?) : Status
        data object LocalAnitorrentFallback : Status
    }
}

/** Playback-page state and commands for PikPak resolution and one-shot fallback. */
class PikPakPlaybackCoordinator(
    private val torrentAccessPolicy: LocalTorrentAccessPolicy,
) {
    private val _state = MutableStateFlow(PikPakPlaybackState())
    val state: StateFlow<PikPakPlaybackState> = _state.asStateFlow()
    private val localFallbackRequests = mutableSetOf<String>()

    fun begin(mediaId: String) {
        _state.update { previous ->
            PikPakPlaybackState(
                mediaId = mediaId,
                status = PikPakPlaybackState.Status.Resolving(OfflineDownloadProgress.Authenticating),
                failureCount = if (previous.mediaId == mediaId) previous.failureCount else 0,
            )
        }
    }

    fun updateProgress(mediaId: String, progress: OfflineDownloadProgress) {
        _state.update { current ->
            if (current.mediaId != mediaId) current
            else current.copy(
                status = PikPakPlaybackState.Status.Resolving(progress),
                cloudCacheHit = current.cloudCacheHit || progress is OfflineDownloadProgress.ResolvingCachedStreamUrl,
            )
        }
    }

    fun playing(mediaId: String, cloudCacheHit: Boolean = false) {
        _state.update { current ->
            if (current.mediaId != mediaId) current
            else current.copy(
                status = PikPakPlaybackState.Status.Playing,
                cloudCacheHit = cloudCacheHit,
            )
        }
    }

    fun failed(mediaId: String, cause: Throwable) {
        _state.update { current ->
            val failures = if (current.mediaId == mediaId) current.failureCount + 1 else 1
            current.copy(
                mediaId = mediaId,
                status = PikPakPlaybackState.Status.Failed(cause.message),
                failureCount = failures,
                downloadBytesPerSecond = 0,
            )
        }
    }

    fun updateTraffic(mediaId: String, bytesPerSecond: Long, downloadedBytes: Long) {
        _state.update { current ->
            if (current.mediaId != mediaId || current.status !is PikPakPlaybackState.Status.Playing) current
            else current.copy(
                downloadBytesPerSecond = bytesPerSecond.coerceAtLeast(0),
                downloadedBytes = downloadedBytes.coerceAtLeast(0),
            )
        }
    }

    fun updateCacheProgress(mediaId: String, progressInfo: MediaCacheProgressInfo) {
        _state.update { current ->
            if (current.mediaId != mediaId || current.status !is PikPakPlaybackState.Status.Playing) current
            else current.copy(cacheProgressInfo = progressInfo)
        }
    }

    fun requestLocalFallback(mediaId: String) {
        torrentAccessPolicy.allowForCurrentPlayback(mediaId)
        synchronized(localFallbackRequests) { localFallbackRequests += mediaId }
        _state.update {
            it.copy(mediaId = mediaId, status = PikPakPlaybackState.Status.LocalAnitorrentFallback)
        }
    }

    fun consumeLocalFallback(mediaId: String): Boolean = synchronized(localFallbackRequests) {
        localFallbackRequests.remove(mediaId)
    }

    fun localFallbackStarted(mediaId: String) {
        _state.update {
            it.copy(mediaId = mediaId, status = PikPakPlaybackState.Status.LocalAnitorrentFallback)
        }
    }

    fun reset(mediaId: String? = null) {
        val current = _state.value
        if (mediaId != null && current.mediaId != mediaId) return
        current.mediaId?.let { currentMediaId ->
            synchronized(localFallbackRequests) { localFallbackRequests.remove(currentMediaId) }
            torrentAccessPolicy.revokeTemporaryAccess(currentMediaId)
        }
        _state.value = PikPakPlaybackState()
    }
}
