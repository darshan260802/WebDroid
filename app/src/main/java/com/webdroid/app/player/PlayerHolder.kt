package com.webdroid.app.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.webdroid.app.util.Net
import com.webdroid.app.web.MediaSniffer

/** What to play, and how to fetch it the way the page would. */
data class VideoRequest(
    val url: String,
    val kind: MediaSniffer.Kind?,
    val title: String,
    val pageUrl: String,
    val userAgent: String,
    val cookies: String?,
    val posterUrl: String?,
    val appId: String,
    val appName: String,
    /** id of the <video> element in its frame, to sync back on close. */
    val frameVideoId: Int?,
    val startPositionMs: Long = 0L,
) {
    val isProgressive: Boolean
        get() = kind == MediaSniffer.Kind.PROGRESSIVE || (kind == null && !url.contains(".m3u8") && !url.contains(".mpd"))

    /** Stable key for resume positions (signed CDN query strings change). */
    val positionKey: String
        get() = pageUrl.substringBefore('#') + "|" + url.substringBefore('?')
}

/**
 * One process-wide ExoPlayer, shared by the in-app player UI and
 * [PlaybackService] (notification, lock screen, background audio).
 */
object PlayerHolder {
    private var player: ExoPlayer? = null

    var current: VideoRequest? = null
        private set

    /** Brings the owning web app back when the media notification is tapped. */
    var sessionActivity: PendingIntent? = null
        private set

    fun obtain(context: Context): ExoPlayer = player ?: ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()
        .also { player = it }

    fun play(context: Context, request: VideoRequest, activityIntent: Intent) {
        val exo = obtain(context)
        current = request
        sessionActivity = PendingIntent.getActivity(
            context, request.appId.hashCode(), activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val headers = buildMap {
            put("Referer", request.pageUrl)
            Uri.parse(request.pageUrl).let { page ->
                if (page.scheme != null && page.host != null) put("Origin", "${page.scheme}://${page.host}")
            }
            request.cookies?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        }
        val dataSource = OkHttpDataSource.Factory(Net.client)
            .setUserAgent(request.userAgent)
            .setDefaultRequestProperties(headers)
        val mime = when (request.kind) {
            MediaSniffer.Kind.HLS -> MimeTypes.APPLICATION_M3U8
            MediaSniffer.Kind.DASH -> MimeTypes.APPLICATION_MPD
            else -> when {
                request.url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                request.url.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
                else -> null
            }
        }
        val item = MediaItem.Builder()
            .setUri(request.url)
            .setMimeType(mime)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(request.title.ifBlank { request.appName })
                    .setArtist(request.appName)
                    .setArtworkUri(request.posterUrl?.takeIf { it.startsWith("http") }?.let(Uri::parse))
                    .build()
            )
            .build()
        val source = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource).createMediaSource(item)
        exo.setMediaSource(source, request.startPositionMs)
        exo.prepare()
        exo.playWhenReady = true
    }

    fun stop() {
        player?.run {
            stop()
            clearMediaItems()
        }
        current = null
    }

    fun release() {
        player?.release()
        player = null
        current = null
    }
}
