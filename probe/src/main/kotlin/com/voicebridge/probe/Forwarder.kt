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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Hands a request on to YMusic V4: a YouTube link opens there; words go to its play-from-search;
 * a Spotify id is turned into its title (Spotify's public oEmbed) and searched for.
 */
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

    /** What a Spotify uri/link asks for: words to search, or an item link whose title is looked up. */
    class SpotifyRequest(val searchText: String?, val itemLink: String?) {
        override fun toString() = searchText?.let { "search \"$it\"" } ?: "item $itemLink"
    }

    private val SPOTIFY_TYPES = setOf("track", "album", "artist", "playlist", "show", "episode")
    private val SPOTIFY_HOSTS = setOf("open.spotify.com", "spotify.link", "spotify.app.link")

    fun spotifyRequest(uri: Uri?): SpotifyRequest? {
        if (uri == null) return null
        if (uri.scheme == "spotify") {
            val parts = uri.schemeSpecificPart.split(':').filter { it.isNotEmpty() }
            if (parts.firstOrNull() == "search") {
                val text = URLDecoder.decode(parts.drop(1).joinToString(":"), "UTF-8").trim()
                return text.takeIf { it.isNotEmpty() }?.let { SpotifyRequest(it, null) }
            }
            val typeIndex = parts.indexOfFirst { it in SPOTIFY_TYPES }
            val id = parts.getOrNull(typeIndex + 1) ?: return null
            return if (typeIndex >= 0) SpotifyRequest(null, "https://open.spotify.com/${parts[typeIndex]}/$id") else null
        }
        val host = uri.host?.lowercase() ?: return null
        if (uri.scheme !in setOf("http", "https") || host !in SPOTIFY_HOSTS) return null
        if (host != "open.spotify.com") return SpotifyRequest(null, uri.toString()) // short link
        val segments = uri.pathSegments.filterNot { it.startsWith("intl-") }
        if (segments.firstOrNull() == "search") {
            return segments.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { SpotifyRequest(it, null) }
        }
        val type = segments.getOrNull(0)?.takeIf { it in SPOTIFY_TYPES } ?: return null
        val id = segments.getOrNull(1) ?: return null
        return SpotifyRequest(null, "https://open.spotify.com/$type/$id")
    }

    /**
     * Resolves [request] to words off the main thread, then plays them in YMusic through its
     * session ([viaSession], for a service) or its intent (for an activity). [onDone] runs on the
     * main thread with whether a play request went out.
     */
    fun playSpotify(context: Context, request: SpotifyRequest, viaSession: Boolean, onDone: (Boolean) -> Unit = {}) {
        val handler = Handler(Looper.getMainLooper())
        Thread {
            val text = request.searchText ?: request.itemLink?.let { spotifyTitle(context, it) }
            handler.post {
                if (text.isNullOrBlank()) {
                    ProbeLog.add(context, "Spotify: could not name $request")
                    onDone(false)
                } else {
                    ProbeLog.add(context, "Spotify: $request → \"$text\"")
                    if (viaSession) {
                        searchViaSession(context, text)
                        onDone(true)
                    } else {
                        onDone(searchViaIntent(context, text))
                    }
                }
            }
        }.start()
    }

    private fun spotifyTitle(context: Context, link: String): String? {
        val url = URL("https://open.spotify.com/oembed?url=" + URLEncoder.encode(link, "UTF-8"))
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                ProbeLog.add(context, "Spotify oEmbed HTTP ${connection.responseCode} for $link")
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optString("title").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            ProbeLog.add(context, "Spotify oEmbed failed for $link: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }
}
