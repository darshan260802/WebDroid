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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

/** What to play, and how to fetch it the way the page would. */
data class VideoRequest(
    val url: String,
    val kind: MediaSniffer.Kind?,
    val title: String,
    val pageUrl: String,
    val userAgent: String,
    val posterUrl: String?,
    val appId: String,
    val appName: String,
    /** id of the <video> element in its frame, to sync back on close. */
    val frameVideoId: Int?,
    val startPositionMs: Long = 0L,
    /** Referer the page used for the stream, when it differs from [pageUrl]. */
    val referer: String? = null,
) {
    val isProgressive: Boolean
        get() = kind == MediaSniffer.Kind.PROGRESSIVE

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var prepareJob: Job? = null

    /**
     * Starts [request]. Requests go out like the page's own: its cookies (from
     * the app's WebView cookie store, for every host the stream touches), its
     * Referer/Origin and user agent. A stream of unknown type is probed first,
     * since HLS/DASH URLs often lack a telling extension.
     */
    fun play(context: Context, request: VideoRequest, activityIntent: Intent, cookieJar: CookieJar, baseClient: OkHttpClient = Net.client) {
        val exo = obtain(context)
        current = request
        sessionActivity = PendingIntent.getActivity(
            context, request.appId.hashCode(), activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val referer = request.referer ?: request.pageUrl
        val headers = buildMap {
            put("Referer", referer)
            Uri.parse(referer).let { page ->
                if (page.scheme != null && page.host != null) put("Origin", "${page.scheme}://${page.host}")
            }
        }
        val client = baseClient.newBuilder().cookieJar(cookieJar).build()
        val dataSource = OkHttpDataSource.Factory(client)
            .setUserAgent(request.userAgent)
            .setDefaultRequestProperties(headers)
        prepareJob?.cancel()
        exo.stop()
        exo.clearMediaItems()
        val known = request.kind ?: kindFromUrl(request.url)
        if (known != null) {
            prepare(context, exo, request, known, dataSource)
        } else {
            prepareJob = scope.launch {
                val probed = withContext(Dispatchers.IO) { probe(client, request, headers) }
                if (current === request) prepare(context, exo, request, probed, dataSource)
            }
        }
    }

    private fun kindFromUrl(url: String): MediaSniffer.Kind? {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> MediaSniffer.Kind.HLS
            path.endsWith(".mpd") -> MediaSniffer.Kind.DASH
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mkv") || path.endsWith(".mov") ||
                path.endsWith(".m4v") || path.endsWith(".mp3") || path.endsWith(".m4a") -> MediaSniffer.Kind.PROGRESSIVE
            else -> null
        }
    }

    /** Looks at the first bytes (and type) of a stream to tell HLS/DASH from a plain file. */
    private fun probe(client: OkHttpClient, request: VideoRequest, headers: Map<String, String>): MediaSniffer.Kind {
        return runCatching {
            val builder = Request.Builder().url(request.url)
                .header("User-Agent", request.userAgent)
                .header("Range", "bytes=0-4095")
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                val type = response.header("Content-Type").orEmpty().lowercase()
                when {
                    "mpegurl" in type -> return MediaSniffer.Kind.HLS
                    "dash+xml" in type -> return MediaSniffer.Kind.DASH
                    type.startsWith("video/") || type.startsWith("audio/") -> return MediaSniffer.Kind.PROGRESSIVE
                }
                val bytes = ByteArray(4096)
                var read = 0
                response.body?.byteStream()?.use { input ->
                    while (read < bytes.size) {
                        val n = input.read(bytes, read, bytes.size - read)
                        if (n < 0) break
                        read += n
                    }
                }
                val head = String(bytes, 0, read, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
                when {
                    head.startsWith("#EXTM3U") -> MediaSniffer.Kind.HLS
                    head.contains("<MPD") -> MediaSniffer.Kind.DASH
                    else -> MediaSniffer.Kind.PROGRESSIVE
                }
            }
        }.getOrDefault(MediaSniffer.Kind.PROGRESSIVE)
    }

    private fun prepare(context: Context, exo: ExoPlayer, request: VideoRequest, kind: MediaSniffer.Kind, dataSource: OkHttpDataSource.Factory) {
        val mime = when (kind) {
            MediaSniffer.Kind.HLS -> MimeTypes.APPLICATION_M3U8
            MediaSniffer.Kind.DASH -> MimeTypes.APPLICATION_MPD
            MediaSniffer.Kind.PROGRESSIVE -> null
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
        prepareJob?.cancel()
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
