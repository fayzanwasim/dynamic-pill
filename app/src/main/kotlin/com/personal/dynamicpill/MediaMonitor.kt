package com.personal.dynamicpill

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock

/**
 * Follows every active media session and reports the most relevant one: the session that is playing,
 * otherwise the first. Reading sessions requires the notification listener component to be enabled.
 */
class MediaMonitor(
    context: Context,
    private val onChange: (MediaState?) -> Unit,
) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, IslandNotificationListener::class.java)
    private val controllers = mutableListOf<MediaController>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { track(it) }

    // Watching every session (not just the chosen one) lets a different app taking over playback be noticed.
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()

        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()

        override fun onSessionDestroyed() = track(manager.getActiveSessions(listenerComponent))
    }

    fun start() {
        manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
        track(manager.getActiveSessions(listenerComponent))
    }

    fun stop() {
        manager.removeOnActiveSessionsChangedListener(sessionsListener)
        controllers.forEach { it.unregisterCallback(callback) }
        controllers.clear()
    }

    private fun track(active: List<MediaController>?) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers.clear()
        active?.forEach {
            controllers += it
            it.registerCallback(callback)
        }
        publish()
    }

    private fun publish() {
        val chosen = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()
        val metadata = chosen?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (chosen == null || title.isNullOrEmpty()) {
            onChange(null)
            return
        }
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val playback = chosen.playbackState
        onChange(
            MediaState(
                key = "${chosen.packageName}|$title|$artist",
                title = title,
                artist = artist,
                art = art,
                isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
                controller = chosen,
                durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
                positionMs = playback?.position ?: 0L,
                speed = playback?.playbackSpeed ?: 0f,
                positionUpdatedAt = playback?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
                customActions = playback?.customActions.orEmpty(),
            ),
        )
    }
}
