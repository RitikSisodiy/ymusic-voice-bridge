package com.voicebridge

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/**
 * Starts "play [query]" in the target player, through one of two standard Android entry points:
 *
 * - [BridgeSettings.Method.SESSION]: binds the player's media browser service and calls
 *   playFromSearch on its session, exactly like Android Auto does. No screen opens, so it also
 *   works on the lock screen. Falls back to the intent when the session can't be reached or
 *   nothing starts playing.
 * - [BridgeSettings.Method.INTENT]: sends MEDIA_PLAY_FROM_SEARCH, the intent Google Assistant used.
 *
 * Must be called on the main thread.
 */
class PlayerLauncher(private val context: Context) {

    private val settings = BridgeSettings(context)
    private val handler = Handler(Looper.getMainLooper())
    private var active: SessionAttempt? = null

    fun play(query: String) {
        when (settings.method) {
            BridgeSettings.Method.SESSION -> playViaSession(query)
            BridgeSettings.Method.INTENT -> playViaIntent(query)
        }
    }

    private fun playViaSession(query: String) {
        active?.finish()
        val component = ComponentName(settings.targetPackage, settings.targetService)
        active = SessionAttempt(query, component).also { it.start() }
    }

    fun playViaIntent(query: String): Boolean {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(settings.targetPackage)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, FOCUS_ANY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            log("Intent: sent play-from-search \"$query\" to ${settings.targetPackage}")
            true
        } catch (e: ActivityNotFoundException) {
            log("Intent: ${settings.targetPackage} has no play-from-search screen (is YMusic V4 installed?)")
            false
        } catch (e: SecurityException) {
            log("Intent: blocked by the system: ${e.message}")
            false
        }
    }

    private fun log(message: String) = EventLog.add(context, message)

    private inner class SessionAttempt(private val query: String, private val component: ComponentName) {
        private var browser: MediaBrowser? = null
        private var controller: MediaController? = null
        private var done = false
        private var sawPlayback = false

        private val connectTimeout = Runnable {
            log("Session: no answer from ${component.flattenToShortString()} in ${CONNECT_TIMEOUT_MS / 1000}s")
            fallback()
        }

        private val playbackTimeout = Runnable {
            if (!sawPlayback) {
                log("Session: nothing started playing in ${PLAYBACK_TIMEOUT_MS / 1000}s")
                fallback()
            } else {
                finish()
            }
        }

        private val controllerCallback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                val s = state?.state ?: return
                log("Session: player state ${stateName(s)}")
                if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING) sawPlayback = true
                if (s == PlaybackState.STATE_PLAYING) {
                    handler.removeCallbacks(playbackTimeout)
                    // Give the metadata a moment to arrive for the log, then let go of the session.
                    handler.postDelayed({ finish() }, METADATA_GRACE_MS)
                }
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
                val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
                log("Session: now playing \"$title\"${if (artist.isNotEmpty()) " — $artist" else ""}")
            }
        }

        fun start() {
            log("Session: connecting to ${component.flattenToShortString()} for \"$query\"")
            val callback = object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    if (done) return
                    handler.removeCallbacks(connectTimeout)
                    val b = browser ?: return
                    val c = MediaController(context, b.sessionToken)
                    controller = c
                    c.registerCallback(controllerCallback, handler)
                    c.transportControls.playFromSearch(query, Bundle().apply { putString(MediaStore.EXTRA_MEDIA_FOCUS, FOCUS_ANY) })
                    log("Session: sent playFromSearch \"$query\"")
                    handler.postDelayed(playbackTimeout, PLAYBACK_TIMEOUT_MS)
                }

                override fun onConnectionFailed() {
                    if (done) return
                    handler.removeCallbacks(connectTimeout)
                    log("Session: connection refused (is YMusic V4 installed?)")
                    fallback()
                }

                override fun onConnectionSuspended() {
                    if (done) return
                    log("Session: connection lost")
                    if (!sawPlayback) fallback() else finish()
                }
            }
            try {
                browser = MediaBrowser(context, component, callback, null).also { it.connect() }
                handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                log("Session: could not bind: ${e.message}")
                fallback()
            }
        }

        private fun fallback() {
            if (done) return
            finish()
            log("Session: falling back to the intent")
            playViaIntent(query)
        }

        fun finish() {
            if (done) return
            done = true
            handler.removeCallbacks(connectTimeout)
            handler.removeCallbacks(playbackTimeout)
            controller?.unregisterCallback(controllerCallback)
            try {
                browser?.disconnect()
            } catch (e: Exception) {
                log("Session: disconnect failed: ${e.message}")
            }
            if (active === this) active = null
        }
    }

    companion object {
        /** "Anything": the player picks songs, artists, albums or playlists itself. */
        private const val FOCUS_ANY = "vnd.android.cursor.item/*"
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val PLAYBACK_TIMEOUT_MS = 12_000L
        private const val METADATA_GRACE_MS = 1_500L

        private fun stateName(state: Int) = when (state) {
            PlaybackState.STATE_NONE -> "NONE"
            PlaybackState.STATE_STOPPED -> "STOPPED"
            PlaybackState.STATE_PAUSED -> "PAUSED"
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
            PlaybackState.STATE_REWINDING -> "REWINDING"
            PlaybackState.STATE_BUFFERING -> "BUFFERING"
            PlaybackState.STATE_ERROR -> "ERROR"
            PlaybackState.STATE_CONNECTING -> "CONNECTING"
            PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIPPING_TO_NEXT"
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIPPING_TO_PREVIOUS"
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIPPING_TO_QUEUE_ITEM"
            else -> "UNKNOWN($state)"
        }
    }
}
