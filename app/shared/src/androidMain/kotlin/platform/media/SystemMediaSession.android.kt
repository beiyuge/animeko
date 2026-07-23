/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform.media

import android.app.PendingIntent
import android.content.Context as AndroidContext
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.him188.ani.app.platform.Context
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.exoplayer.ExoPlayerMediampPlayer

actual fun createSystemMediaSessionRegistration(
    context: Context,
    player: MediampPlayer,
): SystemMediaSessionRegistration {
    val exoPlayer = (player as? ExoPlayerMediampPlayer)?.impl
        ?: return UnsupportedAndroidSystemMediaSessionRegistration
    return AndroidSystemMediaSessionRegistration(context.applicationContext, exoPlayer)
}

private object UnsupportedAndroidSystemMediaSessionRegistration : SystemMediaSessionRegistration {
    override val isHdr = MutableStateFlow(false)

    override fun updateMetadata(metadata: SystemMediaMetadata) = Unit

    override fun close() = Unit
}

@OptIn(UnstableApi::class)
private class AndroidSystemMediaSessionRegistration(
    val context: AndroidContext,
    val player: ExoPlayer,
) : SystemMediaSessionRegistration {
    private val playerHandler = Handler(player.applicationLooper)
    private val _isHdr = MutableStateFlow(false)
    override val isHdr = _isHdr.asStateFlow()

    @Volatile
    private var latestMetadata: SystemMediaMetadata? = null

    @Volatile
    private var closed = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateHdrState()
            applyMetadata()
            AndroidMediaSessionCoordinator.ensureServiceStarted(this@AndroidSystemMediaSessionRegistration)
        }
    }

    init {
        runOnPlayerThread {
            if (closed) return@runOnPlayerThread

            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            player.addListener(listener)
            updateHdrState()
            applyMetadata()
        }
        AndroidMediaSessionCoordinator.register(this)
    }

    override fun updateMetadata(metadata: SystemMediaMetadata) {
        latestMetadata = metadata
        runOnPlayerThread(::applyMetadata)
    }

    override fun close() {
        if (closed) return
        closed = true
        AndroidMediaSessionCoordinator.unregister(this)
        runOnPlayerThread {
            player.removeListener(listener)
            _isHdr.value = false
        }
    }

    private fun updateHdrState() {
        _isHdr.value = isHdrVideoFormat(player)
    }

    private fun applyMetadata() {
        if (closed) return
        val metadata = latestMetadata ?: return
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex == C.INDEX_UNSET) return
        val currentItem = player.currentMediaItem ?: return

        val mediaMetadata = currentItem.mediaMetadata.buildUpon()
            .setTitle(metadata.title)
            .setSubtitle(metadata.episodeTitle)
            .setArtist(metadata.sourceName)
            .setArtworkUri(metadata.artworkUri?.takeIf(String::isNotBlank)?.let(Uri::parse))
            .build()
        if (mediaMetadata == currentItem.mediaMetadata) return

        player.replaceMediaItem(
            currentIndex,
            currentItem.buildUpon()
                .setMediaMetadata(mediaMetadata)
                .build(),
        )
    }

    private fun runOnPlayerThread(action: () -> Unit) {
        if (Looper.myLooper() == player.applicationLooper) {
            action()
        } else {
            playerHandler.post(action)
        }
    }
}

@OptIn(UnstableApi::class)
internal fun isHdrVideoFormat(player: Player): Boolean =
    isHdrVideoTracks(player.currentTracks)

@OptIn(UnstableApi::class)
internal fun isHdrVideoTracks(tracks: Tracks): Boolean =
    tracks.groups.any { group ->
        group.type == C.TRACK_TYPE_VIDEO &&
            (0 until group.length).any { trackIndex ->
                group.isTrackSelected(trackIndex) &&
                    ColorInfo.isTransferHdr(group.getTrackFormat(trackIndex).colorInfo)
            }
    }

private object AndroidMediaSessionCoordinator {
    private val logger = logger<AndroidSystemMediaSessionRegistration>()

    private var activeRegistration: AndroidSystemMediaSessionRegistration? = null
    private var service: AniMediaSessionService? = null
    private var serviceStartRequested = false

    @Synchronized
    fun register(registration: AndroidSystemMediaSessionRegistration) {
        activeRegistration = registration
        service?.attachPlayer(registration.player)
        ensureServiceStarted(registration)
    }

    @Synchronized
    fun unregister(registration: AndroidSystemMediaSessionRegistration) {
        if (activeRegistration !== registration) return
        activeRegistration = null
        serviceStartRequested = false
        service?.detachPlayer(registration.player)
        registration.context.stopService(
            Intent(registration.context, AniMediaSessionService::class.java),
        )
    }

    @Synchronized
    fun ensureServiceStarted(registration: AndroidSystemMediaSessionRegistration) {
        if (activeRegistration !== registration || service != null || serviceStartRequested) return
        serviceStartRequested = true
        runCatching {
            registration.context.startService(
                Intent(registration.context, AniMediaSessionService::class.java),
            )
        }.onFailure {
            serviceStartRequested = false
            logger.warn(it) { "Failed to start media session service" }
        }
    }

    @Synchronized
    fun connect(service: AniMediaSessionService) {
        this.service = service
        serviceStartRequested = false
        activeRegistration?.let { service.attachPlayer(it.player) }
    }

    @Synchronized
    fun disconnect(service: AniMediaSessionService) {
        if (this.service !== service) return
        this.service = null
        serviceStartRequested = false
    }
}

/**
 * Hosts the current episode's Media3 session so Android system controls and media buttons can
 * control the same ExoPlayer used by the in-app player.
 */
class AniMediaSessionService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var attachedPlayer: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        AndroidMediaSessionCoordinator.connect(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    internal fun attachPlayer(player: ExoPlayer) {
        if (attachedPlayer === player && mediaSession != null) return
        releaseSession()

        attachedPlayer = player
        mediaSession = MediaSession.Builder(this, player)
            .apply {
                createSessionActivity()?.let(::setSessionActivity)
            }
            .build()
            .also(::addSession)
    }

    internal fun detachPlayer(player: ExoPlayer) {
        if (attachedPlayer !== player) return
        releaseSession()
        stopSelf()
    }

    override fun onDestroy() {
        releaseSession()
        AndroidMediaSessionCoordinator.disconnect(this)
        super.onDestroy()
    }

    private fun releaseSession() {
        mediaSession?.let {
            removeSession(it)
            it.release()
        }
        mediaSession = null
        attachedPlayer = null
    }

    private fun createSessionActivity(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
