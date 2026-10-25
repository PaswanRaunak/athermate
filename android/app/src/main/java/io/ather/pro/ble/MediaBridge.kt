package io.ather.pro.ble

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import io.ather.pro.appContainer

/**
 * Media bridge — the counterpart of the real app's MediaManager:
 *
 *  - "transport"-category (MediaStyle) notifications are matched to the active
 *    MediaSession by track title via MediaSessionManager.getActiveSessions(the
 *    notification-listener component); full metadata (artist/album/position/
 *    duration) is read from the session and pushed to the dash as t=2.
 *  - Dash media buttons (prev / play-pause / next) drive the session's transport
 *    controls, falling back to firing the notification's own action
 *    PendingIntents — exactly the two mechanisms the original uses.
 *  - Dash volume buttons adjust the media stream (the original drives its voice
 *    chime volume; AtherMate has no chime channel yet).
 */
object MediaBridge {

    /** Set by WhatsAppNotificationListener.onListenerConnected. */
    @Volatile var listenerComponent: ComponentName? = null

    private var lastController: MediaController? = null
    private var lastActions: Array<Notification.Action>? = null
    private var lastTrack: String = ""
    private var lastState: Int = -1

    /** Call for every "transport"-category notification. */
    fun onTransportNotification(context: Context, sbn: StatusBarNotification) {
        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        if (title.isNullOrBlank()) return

        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val component = listenerComponent ?: return
        val sessions = runCatching { msm?.getActiveSessions(component) }.getOrNull() ?: return

        // Real MediaManager: match the active session whose metadata title equals
        // the notification title.
        val controller = sessions.firstOrNull { s ->
            s.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) == title
        } ?: sessions.firstOrNull { it.metadata != null && isPlaying(it) }
        if (controller == null) return
        lastController = controller
        lastActions = n.actions

        val metadata = controller.metadata ?: return
        val track = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { title }
        val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val subtitle = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · ")

        // Play state from the notification's own actions: a Pause action means
        // it is playing; a Play action means paused (original MediaManager logic).
        var state = 0
        n.actions?.forEach { action ->
            val t = action.title?.toString()?.lowercase() ?: return@forEach
            when {
                t.contains("pause") -> state = 1
                t.contains("play") && !t.contains("playlist") -> if (state != 1) state = 0
            }
        }

        val durationSec = (metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) / 1000L).toInt()
        val playback = controller.playbackState
        val posSec: Int = if (playback != null && playback.state == android.media.session.PlaybackState.STATE_PLAYING) {
            // Project the position using the playback speed since lastPositionUpdateTime.
            val speed = if (playback.playbackSpeed > 0f) playback.playbackSpeed else 1f
            ((playback.position + (SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime) * speed) / 1000.0).toInt()
        } else {
            ((playback?.position ?: 0L) / 1000L).toInt()
        }

        // Dedup: only push on track or play-state change.
        if (track == lastTrack && state == lastState) return
        lastTrack = track; lastState = state

        context.appContainer.vehicleLink.sendMedia(track, subtitle, state == 1, posSec, durationSec)
    }

    /** Dash command bytes -> phone media. cmd = 1 prev, 2 toggle, 3 next, 4 vol+, 5 vol-. */
    fun sendCommand(context: Context, cmd: Int) {
        when (cmd) {
            BleVehicleProfile.CMD_VOLUME_UP, BleVehicleProfile.CMD_VOLUME_DOWN -> {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (cmd == BleVehicleProfile.CMD_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
                return
            }
        }

        val controller = activeController(context) ?: lastController ?: run {
            fireActionFallback(cmd, null); return
        }
        val controls = controller.transportControls
        when (cmd) {
            BleVehicleProfile.CMD_MEDIA_PREVIOUS -> controls.skipToPrevious()
            BleVehicleProfile.CMD_MEDIA_TOGGLE -> {
                val playing = controller.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                if (playing) controls.pause() else controls.play()
            }
            BleVehicleProfile.CMD_MEDIA_NEXT -> controls.skipToNext()
            else -> fireActionFallback(cmd, controller)
        }
    }

    private fun activeController(context: Context): MediaController? {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val component = listenerComponent ?: return null
        return runCatching {
            msm?.getActiveSessions(component)?.firstOrNull { it.metadata != null && isPlaying(it) }
                ?: msm?.getActiveSessions(component)?.firstOrNull { it.metadata != null }
        }.getOrNull()
    }

    private fun isPlaying(c: MediaController): Boolean =
        c.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING

    /** Original fallback: fire the notification's own action PendingIntents. */
    private fun fireActionFallback(cmd: Int, controller: MediaController?) {
        val actions = lastActions ?: return
        val wanted = when (cmd) {
            BleVehicleProfile.CMD_MEDIA_PREVIOUS -> listOf("previous", "prev")
            BleVehicleProfile.CMD_MEDIA_TOGGLE -> listOf("pause", "play")
            BleVehicleProfile.CMD_MEDIA_NEXT -> listOf("next")
            else -> return
        }
        actions.firstOrNull { action ->
            val t = action.title?.toString()?.lowercase() ?: return@firstOrNull false
            wanted.any { t.contains(it) }
        }?.let { action ->
            runCatching { action.actionIntent?.send() }
        }
    }

    /**
     * Poll the active MediaSession directly and push now-playing to the dash.
     * Works for players that never post MediaStyle notifications (e.g. Bitchord).
     */
    fun pollAndPush(context: Context) {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val component = listenerComponent ?: return
        val sessions = runCatching { msm?.getActiveSessions(component) }.getOrNull() ?: return
        val controller = sessions.firstOrNull { it.metadata != null && isPlaying(it) }
            ?: sessions.firstOrNull { it.metadata != null }
            ?: return
        val metadata = controller.metadata ?: return
        val track = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (track.isBlank()) return
        val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val subtitle = listOf(artist, album).filter { it.isNotBlank() }.joinToString(" · ")
        val playing = isPlaying(controller)
        val durationSec = (metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) / 1000L).toInt()
        val playback = controller.playbackState
        val posSec: Int = if (playing && playback != null) {
            val speed = if (playback.playbackSpeed > 0f) playback.playbackSpeed else 1f
            ((playback.position + (SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime) * speed) / 1000.0).toInt()
        } else ((playback?.position ?: 0L) / 1000L).toInt()

        if (track == lastTrack && playing == (lastState == 1)) return
        lastTrack = track; lastState = if (playing) 1 else 0
        lastController = controller
        context.appContainer.vehicleLink.sendMedia(track, subtitle, playing, posSec, durationSec)
    }

    fun reset() {
        lastController = null; lastActions = null; lastTrack = ""; lastState = -1
    }
}
