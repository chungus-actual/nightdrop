package io.github.chungus_actual.nightdrop

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * What is playing, from the active media sessions (Spotify publishes title, artist, album and the art
 * as a bitmap). Needs notification-listener access for MediaListener; without it `granted` is false
 * and the track card says how to fix that. No network: art the session does not carry as a bitmap is
 * simply not shown.
 */
class NowPlaying(private val ctx: Context, private val onChange: (Track?) -> Unit) {

    data class Track(val title: String, val artist: String, val album: String,
                     val art: Bitmap?, val playing: Boolean, val app: String)

    var granted = true
        private set

    private val main = Handler(Looper.getMainLooper())
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(ctx, MediaListener::class.java)
    private var controller: MediaController? = null
    private var last: Track? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = push()
        override fun onPlaybackStateChanged(state: PlaybackState?) = push()
        override fun onSessionDestroyed() = pick(null)
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    fun start() {
        try {
            msm.addOnActiveSessionsChangedListener(sessions, component, main)
            granted = true
            pick(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            granted = false
            Log.w(AudioTap.TAG, "no notification-listener access: $e")
            onChange(null)
        }
    }

    fun stop() {
        try { msm.removeOnActiveSessionsChangedListener(sessions) } catch (_: Throwable) {}
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun pick(list: List<MediaController>?) {
        val all = list ?: try { msm.getActiveSessions(component) } catch (_: SecurityException) { emptyList() }
        // the one that is playing, else the most recent
        val c = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()
        if (c?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = c
            c?.registerCallback(callback, main)
        }
        push()
    }

    private fun push() {
        val c = controller
        val m = c?.metadata
        val t = if (m == null) null else Track(
            title = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
            artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
            album = m.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
            app = c.packageName ?: "")
        if (t != last) {
            if (m != null && t?.title != last?.title) {
                // what the playing app actually publishes (art as a bitmap, or only as a URI?)
                Log.i(AudioTap.TAG, "now playing from ${c.packageName}: keys=${m.keySet()} " +
                    "artUri=${m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)} " +
                    "iconUri=${m.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)} bitmap=${t?.art != null}")
            }
            last = t
            onChange(t)
        }
    }
}
