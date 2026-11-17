package com.voicebridge.probe

import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.service.media.MediaBrowserService

/**
 * A media browser service with a session that accepts every transport command. Each call is
 * logged with the caller; play requests go on to YMusic's own session.
 */
class ProbeMediaService : MediaBrowserService() {

    private lateinit var session: MediaSession

    override fun onCreate() {
        super.onCreate()
        ProbeLog.add(this, "SERVICE created")
        session = MediaSession(this, "YtmProbe").apply {
            setCallback(Callback())
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(ALL_ACTIONS)
                    .setState(PlaybackState.STATE_NONE, 0, 1f)
                    .build(),
            )
            isActive = true
        }
        sessionToken = session.sessionToken
    }

    override fun onBind(intent: Intent?): IBinder? {
        ProbeLog.add(this, "SERVICE bind ${ProbeLog.describe(intent)}")
        return super.onBind(intent)
    }

    override fun onDestroy() {
        session.release()
        ProbeLog.add(this, "SERVICE destroyed")
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): MediaBrowserService.BrowserRoot {
        ProbeLog.add(this, "SERVICE onGetRoot client=$clientPackageName uid=$clientUid hints=${ProbeLog.describe(rootHints)}")
        return MediaBrowserService.BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(parentId: String, result: MediaBrowserService.Result<MutableList<MediaBrowser.MediaItem>>) {
        ProbeLog.add(this, "SERVICE onLoadChildren parent=$parentId")
        result.sendResult(mutableListOf())
    }

    override fun onSearch(query: String, extras: Bundle?, result: MediaBrowserService.Result<MutableList<MediaBrowser.MediaItem>>) {
        ProbeLog.add(this, "SERVICE onSearch \"$query\" extras=${ProbeLog.describe(extras)}")
        result.sendResult(mutableListOf())
    }

    private inner class Callback : MediaSession.Callback() {
        private fun caller(): String {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "?"
            return try {
                session.currentControllerInfo.packageName
            } catch (e: Exception) {
                "?"
            }
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            ProbeLog.add(this@ProbeMediaService, "SESSION playFromSearch from=${caller()} \"$query\" extras=${ProbeLog.describe(extras)}")
            if (!query.isNullOrBlank()) Forwarder.searchViaSession(this@ProbeMediaService, query)
        }

        override fun onPrepareFromSearch(query: String?, extras: Bundle?) {
            ProbeLog.add(this@ProbeMediaService, "SESSION prepareFromSearch from=${caller()} \"$query\" extras=${ProbeLog.describe(extras)}")
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            ProbeLog.add(this@ProbeMediaService, "SESSION playFromMediaId from=${caller()} id=$mediaId extras=${ProbeLog.describe(extras)}")
            playLinkOrId(mediaId?.let(Uri::parse), mediaId)
        }

        override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
            ProbeLog.add(this@ProbeMediaService, "SESSION prepareFromMediaId from=${caller()} id=$mediaId extras=${ProbeLog.describe(extras)}")
        }

        override fun onPlayFromUri(uri: Uri?, extras: Bundle?) {
            ProbeLog.add(this@ProbeMediaService, "SESSION playFromUri from=${caller()} uri=$uri extras=${ProbeLog.describe(extras)}")
            playLinkOrId(uri, uri?.lastPathSegment)
        }

        override fun onPrepareFromUri(uri: Uri?, extras: Bundle?) {
            ProbeLog.add(this@ProbeMediaService, "SESSION prepareFromUri from=${caller()} uri=$uri extras=${ProbeLog.describe(extras)}")
        }

        override fun onPlay() = ProbeLog.add(this@ProbeMediaService, "SESSION play from=${caller()}")
        override fun onPause() = ProbeLog.add(this@ProbeMediaService, "SESSION pause from=${caller()}")
        override fun onStop() = ProbeLog.add(this@ProbeMediaService, "SESSION stop from=${caller()}")
        override fun onSkipToNext() = ProbeLog.add(this@ProbeMediaService, "SESSION next from=${caller()}")
        override fun onSkipToPrevious() = ProbeLog.add(this@ProbeMediaService, "SESSION previous from=${caller()}")

        override fun onCustomAction(action: String, extras: Bundle?) {
            ProbeLog.add(this@ProbeMediaService, "SESSION customAction from=${caller()} $action extras=${ProbeLog.describe(extras)}")
        }

        override fun onCommand(command: String, args: Bundle?, cb: ResultReceiver?) {
            ProbeLog.add(this@ProbeMediaService, "SESSION command from=${caller()} $command args=${ProbeLog.describe(args)}")
        }

        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            ProbeLog.add(this@ProbeMediaService, "SESSION mediaButton from=${caller()} ${ProbeLog.describe(mediaButtonIntent)}")
            return super.onMediaButtonEvent(mediaButtonIntent)
        }

        /** A YouTube link or bare video id: open it in YMusic, else search YMusic for the id. */
        private fun playLinkOrId(uri: Uri?, id: String?) {
            val link = Forwarder.youtubeLink(uri) ?: Forwarder.videoIdLink(id)
            val opened = link != null && Forwarder.openLink(this@ProbeMediaService, link)
            if (!opened && !id.isNullOrBlank()) Forwarder.searchViaSession(this@ProbeMediaService, id)
        }
    }

    companion object {
        private const val ROOT_ID = "root"
        private const val ALL_ACTIONS =
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_PLAY_FROM_SEARCH or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or
                PlaybackState.ACTION_PLAY_FROM_URI or PlaybackState.ACTION_PREPARE or
                PlaybackState.ACTION_PREPARE_FROM_SEARCH or PlaybackState.ACTION_PREPARE_FROM_MEDIA_ID or
                PlaybackState.ACTION_PREPARE_FROM_URI
    }
}
