package com.voicebridge.probe

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/** Hands a request on to YMusic V4: a YouTube link opens there; words go to its play-from-search. */
object Forwarder {
    private const val YMUSIC = "io.ymusic.android"
    private val YMUSIC_SESSION = ComponentName(YMUSIC, "io.ymusic.android.PlaybackService")
    private const val FOCUS_ANY = "vnd.android.cursor.item/*"
    private val YOUTUBE_HOSTS = setOf("music.youtube.com", "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** A youtube.com / youtu.be / vnd.youtube link as an https link YMusic opens, or null. */
    fun youtubeLink(uri: Uri?): Uri? {
        if (uri == null) return null
        return when {
            uri.scheme in setOf("http", "https") && uri.host?.lowercase() in YOUTUBE_HOSTS -> uri.buildUpon().scheme("https").build()
            uri.scheme?.startsWith("vnd.youtube") == true -> {
                val id = uri.schemeSpecificPart.trimStart('/').substringBefore('?')
                if (VIDEO_ID.matches(id)) Uri.parse("https://music.youtube.com/watch?v=$id") else null
            }
            else -> null
        }
    }

    /** A bare 11-character YouTube video id (some hosts pass only that as a media id). */
    fun videoIdLink(id: String?): Uri? =
        id?.takeIf { VIDEO_ID.matches(it) }?.let { Uri.parse("https://music.youtube.com/watch?v=$it") }

    /** Opens [link] in YMusic. Returns false when the system refused (e.g. a background start). */
    fun openLink(context: Context, link: Uri): Boolean = start(
        context,
        Intent(Intent.ACTION_VIEW, link).setPackage(YMUSIC).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        "open $link",
    )

    /** Play-from-search through YMusic's screenless voice entry point. */
    fun searchViaIntent(context: Context, query: String): Boolean = start(
        context,
        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(YMUSIC)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, FOCUS_ANY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        "play-from-search \"$query\"",
    )

    /** Play-from-search on YMusic's media session: no activity start, so it works from a service. */
    fun searchViaSession(context: Context, query: String) {
        val handler = Handler(Looper.getMainLooper())
        var browser: MediaBrowser? = null
        val callback = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val b = browser ?: return
                MediaController(context, b.sessionToken).transportControls
                    .playFromSearch(query, Bundle().apply { putString(MediaStore.EXTRA_MEDIA_FOCUS, FOCUS_ANY) })
                ProbeLog.add(context, "→ YMusic session: playFromSearch \"$query\"")
                handler.postDelayed({ b.disconnect() }, 10_000)
            }

            override fun onConnectionFailed() {
                ProbeLog.add(context, "→ YMusic session refused; trying the intent")
                searchViaIntent(context, query)
            }
        }
        try {
            browser = MediaBrowser(context, YMUSIC_SESSION, callback, null).also { it.connect() }
        } catch (e: Exception) {
            ProbeLog.add(context, "→ YMusic session bind failed: ${e.message}")
            searchViaIntent(context, query)
        }
    }

    private fun start(context: Context, intent: Intent, what: String): Boolean = try {
        context.startActivity(intent)
        ProbeLog.add(context, "→ YMusic: $what")
        true
    } catch (e: Exception) {
        ProbeLog.add(context, "→ YMusic $what failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }
}
