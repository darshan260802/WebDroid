package com.webdroid.app.player

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.webdroid.app.R
import com.webdroid.app.data.AppSettings
import com.webdroid.app.data.FitMode
import com.webdroid.app.util.Net
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

enum class GestureKind { SEEK, BRIGHTNESS, VOLUME, SPEED, ZOOM, SEEK_BACK, SEEK_FORWARD }

data class GestureHint(val kind: GestureKind, val fraction: Float, val text: String)

data class TrackOption(val label: String, val selected: Boolean, val select: () -> Unit)

data class TrackSection(val title: String, val options: List<TrackOption>)

class PlayerUiState {
    var visible by mutableStateOf(false)
    var request by mutableStateOf<VideoRequest?>(null)
    var isPlaying by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var ended by mutableStateOf(false)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var bufferedMs by mutableLongStateOf(0L)
    var controlsVisible by mutableStateOf(true)
    var locked by mutableStateOf(false)
    var fitMode by mutableStateOf(FitMode.FIT)
    var speed by mutableFloatStateOf(1f)
    var videoAspect by mutableFloatStateOf(16f / 9f)
    var hint by mutableStateOf<GestureHint?>(null)
    var inPip by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var resumedFrom by mutableStateOf<Long?>(null)
    var landscapeLocked by mutableStateOf<Boolean?>(null)
    var zoomScale by mutableFloatStateOf(1f)
    var tracksVersion by mutableStateOf(0)
}

/**
 * Drives the native player for one web app window: ExoPlayer state, UC-style
 * gestures (brightness / volume / seek / zoom), tracks, speed, PiP and resume.
 */
class PlayerController(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val sessionIntent: () -> Intent,
    /** The web app's cookies, so the stream is fetched signed in like the page. */
    private val cookieJar: () -> CookieJar,
    /** HTTP client for this app's streams (routes through its VPN when it has one). */
    private val httpClient: () -> OkHttpClient = { Net.client },
) {
    val state = PlayerUiState()

    var onVisibilityChanged: ((Boolean) -> Unit)? = null
    var onClosed: ((VideoRequest, Long) -> Unit)? = null
    var onPipParamsChanged: (() -> Unit)? = null
    /** The stream failed before playing anything: the page should play it instead. */
    var onCannotPlay: ((VideoRequest) -> Unit)? = null

    /** Whether the current stream has shown a frame or played audio yet. */
    private var started = false

    private var player: ExoPlayer? = null
    private var ticker: Job? = null
    private var hideJob: Job? = null
    private var hintJob: Job? = null
    private val settings get() = AppSettings.get(activity).state.value
    private val positions get() = VideoPositionStore.get(activity)
    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var volumeAccumulator = 0f
    private var savedSpeed = 1f

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            state.isPlaying = isPlaying
            if (isPlaying) started = true
            if (isPlaying) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                scheduleHide()
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onPipParamsChanged?.invoke()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            state.buffering = playbackState == Player.STATE_BUFFERING
            state.ended = playbackState == Player.STATE_ENDED
            if (state.ended) showControls(autoHide = false)
            refreshProgress()
        }

        override fun onRenderedFirstFrame() {
            started = true
        }

        override fun onPlayerError(error: PlaybackException) {
            val request = state.request
            val offline = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            if (!started && !offline && request != null && onCannotPlay != null) {
                // Nothing played yet (wrong guess at the stream, refused, DRM...):
                // quietly give the video back to the page rather than a black screen.
                closeInternal(notify = false)
                onCannotPlay?.invoke(request)
                return
            }
            state.error = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The site refused this stream (it may need the page's own player)."
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Network problem while loading the video."
                PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
                PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED -> "This video is DRM protected."
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This video format isn't supported."
                else -> "Couldn't play this video (${error.errorCodeName})."
            }
            showControls(autoHide = false)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                state.videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                applyOrientation()
                onPipParamsChanged?.invoke()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            state.tracksVersion++
        }
    }

    val exoPlayer: ExoPlayer? get() = player

    fun open(request: VideoRequest) {
        if (state.visible) closeInternal(notify = true)
        val resume = if (settings.rememberPosition) positions.get(request.positionKey) else null
        val start = resume ?: request.startPositionMs
        started = false
        val exo = PlayerHolder.obtain(activity)
        exo.addListener(listener)
        PlayerHolder.play(activity, request.copy(startPositionMs = start), sessionIntent(), cookieJar(), httpClient())
        exo.setPlaybackSpeed(1f)
        player = exo
        state.request = request
        state.error = null
        state.ended = false
        state.resumedFrom = resume?.takeIf { it > 0 }
        state.fitMode = settings.defaultFit
        state.speed = 1f
        state.locked = false
        state.zoomScale = 1f
        state.landscapeLocked = null
        state.positionMs = start
        state.durationMs = 0
        state.buffering = true
        state.visible = true
        showControls()
        runCatching { activity.startService(Intent(activity, PlaybackService::class.java)) }
        ticker?.cancel()
        ticker = scope.launch {
            var saveCounter = 0
            while (isActive) {
                refreshProgress()
                if (++saveCounter % 20 == 0) savePosition()
                delay(if (state.controlsVisible) 250 else 1000)
            }
        }
        onVisibilityChanged?.invoke(true)
        if (state.resumedFrom != null) {
            scope.launch { delay(6000); state.resumedFrom = null }
        }
    }

    private fun refreshProgress() {
        val exo = player ?: return
        state.positionMs = exo.currentPosition.coerceAtLeast(0)
        state.durationMs = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        state.bufferedMs = exo.bufferedPosition.coerceAtLeast(0)
        state.isPlaying = exo.isPlaying
    }

    private fun savePosition() {
        val request = state.request ?: return
        if (!settings.rememberPosition) return
        val exo = player ?: return
        val duration = exo.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        positions.save(request.positionKey, exo.currentPosition, duration)
    }

    fun close() = closeInternal(notify = true)

    private fun closeInternal(notify: Boolean) {
        val request = state.request
        val position = player?.currentPosition ?: 0L
        savePosition()
        ticker?.cancel()
        hideJob?.cancel()
        player?.removeListener(listener)
        player = null
        PlayerHolder.stop()
        runCatching { activity.stopService(Intent(activity, PlaybackService::class.java)) }
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        restoreBrightness()
        state.visible = false
        state.request = null
        state.hint = null
        onVisibilityChanged?.invoke(false)
        if (notify && request != null) onClosed?.invoke(request, position)
    }

    fun togglePlay() {
        val exo = player ?: return
        if (state.ended) {
            exo.seekTo(0)
            exo.play()
        } else if (exo.isPlaying) {
            exo.pause()
        } else {
            if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
            exo.play()
        }
        showControls()
    }

    fun seekTo(positionMs: Long) {
        val exo = player ?: return
        val duration = state.durationMs
        exo.seekTo(if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0))
        state.positionMs = exo.currentPosition
        state.ended = false
    }

    fun seekBy(deltaMs: Long) = seekTo((player?.currentPosition ?: 0L) + deltaMs)

    fun restartFromBeginning() {
        seekTo(0)
        state.resumedFrom = null
    }

    fun setSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
        state.speed = speed
    }

    fun setFit(mode: FitMode) {
        state.fitMode = mode
        state.zoomScale = 1f
        showHint(GestureKind.ZOOM, 0f, when (mode) {
            FitMode.FIT -> "Fit to screen"
            FitMode.FILL -> "Stretch"
            FitMode.ZOOM -> "Zoom to fill"
        })
    }

    fun cycleFit() = setFit(FitMode.entries[(state.fitMode.ordinal + 1) % FitMode.entries.size])

    fun toggleLock() {
        state.locked = !state.locked
        showControls()
    }

    fun toggleRotation() {
        val landscapeNow = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        state.landscapeLocked = !landscapeNow
        applyOrientation()
    }

    fun applyOrientation() {
        if (!state.visible || state.inPip) return
        val locked = state.landscapeLocked
        activity.requestedOrientation = when {
            locked == true -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            locked == false -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            !settings.autoRotate -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            state.videoAspect >= 1.05f -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    fun showControls(autoHide: Boolean = true) {
        state.controlsVisible = true
        if (autoHide) scheduleHide() else hideJob?.cancel()
    }

    fun toggleControls() {
        if (state.controlsVisible) {
            state.controlsVisible = false
            hideJob?.cancel()
        } else {
            showControls()
        }
    }

    private fun scheduleHide() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(3500)
            if (state.isPlaying && state.error == null) state.controlsVisible = false
        }
    }

    fun showHint(kind: GestureKind, fraction: Float, text: String, durationMs: Long = 700) {
        state.hint = GestureHint(kind, fraction, text)
        hintJob?.cancel()
        hintJob = scope.launch {
            delay(durationMs)
            state.hint = null
        }
    }

    fun holdHint(kind: GestureKind, fraction: Float, text: String) {
        hintJob?.cancel()
        state.hint = GestureHint(kind, fraction, text)
    }

    fun releaseHint() = showHint(state.hint?.kind ?: GestureKind.SEEK, state.hint?.fraction ?: 0f, state.hint?.text ?: "", 350)

    // --- Brightness / volume -------------------------------------------------

    private var originalBrightness: Float? = null

    fun currentBrightness(): Float {
        val attr = activity.window.attributes.screenBrightness
        if (attr >= 0f) return attr
        return runCatching {
            Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f)
    }

    fun adjustBrightness(delta: Float) {
        if (originalBrightness == null) originalBrightness = activity.window.attributes.screenBrightness
        val value = (currentBrightness() + delta).coerceIn(0.01f, 1f)
        val attrs = activity.window.attributes
        attrs.screenBrightness = value
        activity.window.attributes = attrs
        holdHint(GestureKind.BRIGHTNESS, value, "${(value * 100).toInt()}%")
    }

    private fun restoreBrightness() {
        val original = originalBrightness ?: return
        val attrs = activity.window.attributes
        attrs.screenBrightness = original
        activity.window.attributes = attrs
        originalBrightness = null
    }

    fun adjustVolume(delta: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeAccumulator += delta * max
        val steps = volumeAccumulator.toInt()
        if (steps != 0) {
            volumeAccumulator -= steps
            val target = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, max)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        holdHint(GestureKind.VOLUME, now / max.toFloat(), "${(now * 100f / max).toInt()}%")
    }

    fun beginSpeedBoost() {
        savedSpeed = state.speed
        setSpeed(2f)
        holdHint(GestureKind.SPEED, 1f, "2× speed")
    }

    fun endSpeedBoost() {
        setSpeed(savedSpeed)
        showHint(GestureKind.SPEED, 1f, "${formatSpeed(savedSpeed)} speed", 400)
    }

    // --- Tracks ---------------------------------------------------------------

    fun trackSections(): List<TrackSection> {
        val exo = player ?: return emptyList()
        val tracks = exo.currentTracks
        val params = exo.trackSelectionParameters
        val sections = mutableListOf<TrackSection>()

        fun options(type: Int, label: (androidx.media3.common.Format, Int) -> String): List<TrackOption> {
            val out = mutableListOf<TrackOption>()
            tracks.groups.filter { it.type == type }.forEach { group ->
                for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) continue
                    val format = group.getTrackFormat(i)
                    out += TrackOption(label(format, out.size), group.isTrackSelected(i) && params.overrides.containsKey(group.mediaTrackGroup) ||
                        (group.isTrackSelected(i) && type != C.TRACK_TYPE_VIDEO)) {
                        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(type, false)
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                            .build()
                        state.tracksVersion++
                    }
                }
            }
            return out
        }

        val video = options(C.TRACK_TYPE_VIDEO) { f, _ ->
            buildString {
                if (f.height > 0) append("${f.height}p") else append("Video")
                if (f.frameRate > 30f) append(" ${f.frameRate.toInt()}fps")
                if (f.bitrate > 0) append(" · ${"%.1f".format(Locale.US, f.bitrate / 1_000_000f)} Mbps")
            }
        }.sortedByDescending { it.label.substringBefore('p').toIntOrNull() ?: 0 }
        if (video.size > 1) {
            val auto = TrackOption("Auto", !params.overrides.keys.any { it.type == C.TRACK_TYPE_VIDEO }) {
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
                state.tracksVersion++
            }
            sections += TrackSection("Quality", listOf(auto) + video)
        }

        val audioOptions = options(C.TRACK_TYPE_AUDIO) { f, index -> describeLanguage(f, "Audio ${index + 1}") }
        if (audioOptions.size > 1) sections += TrackSection("Audio", audioOptions)

        val textOptions = options(C.TRACK_TYPE_TEXT) { f, index -> describeLanguage(f, "Subtitles ${index + 1}") }
        if (textOptions.isNotEmpty()) {
            val disabled = params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            val off = TrackOption("Off", disabled || textOptions.none { it.selected }) {
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                state.tracksVersion++
            }
            sections += TrackSection("Subtitles", listOf(off) + textOptions.map { it.copy(selected = it.selected && !disabled) })
        }
        return sections
    }

    private fun describeLanguage(format: androidx.media3.common.Format, fallback: String): String {
        val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { Locale.forLanguageTag(it).getDisplayName(Locale.getDefault()).ifBlank { it } }
        val label = format.label?.takeIf { it.isNotBlank() }
        return listOfNotNull(label, language?.takeIf { it != label }).joinToString(" · ").ifBlank { fallback }
    }

    // --- Picture in picture ---------------------------------------------------

    fun pipParams(): PictureInPictureParams? {
        if (!state.visible) return null
        val aspect = state.videoAspect.coerceIn(1f / 2.39f, 2.39f)
        val ratio = Rational((aspect * 1000).toInt(), 1000)
        val builder = PictureInPictureParams.Builder().setAspectRatio(ratio)
        val action = RemoteAction(
            Icon.createWithResource(activity, if (state.isPlaying) R.drawable.ic_pip_pause else R.drawable.ic_pip_play),
            if (state.isPlaying) "Pause" else "Play",
            if (state.isPlaying) "Pause" else "Play",
            PendingIntent.getBroadcast(
                activity, 7, Intent(ACTION_PIP_TOGGLE).setPackage(activity.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        builder.setActions(listOf(action))
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(state.isPlaying)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    fun enterPip(): Boolean {
        val params = pipParams() ?: return false
        return runCatching { activity.enterPictureInPictureMode(params) }.getOrDefault(false)
    }

    /** Activity stopped (screen off, app left, or PiP window closed). */
    fun onActivityStopped() {
        if (!state.visible) return
        savePosition()
        if (!settings.backgroundPlay) player?.pause()
    }

    fun onPipModeChanged(inPip: Boolean) {
        state.inPip = inPip
        if (!inPip) applyOrientation()
    }

    companion object {
        const val ACTION_PIP_TOGGLE = "com.webdroid.app.PIP_TOGGLE"

        fun formatTime(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0)
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
        }

        fun formatSpeed(speed: Float): String =
            if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "${speed}×"
    }
}
