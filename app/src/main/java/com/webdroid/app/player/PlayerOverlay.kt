package com.webdroid.app.player

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.webdroid.app.data.FitMode
import com.webdroid.app.ui.components.rememberHaptics
import com.webdroid.app.ui.theme.Clay
import com.webdroid.app.ui.theme.ClayShapes
import com.webdroid.app.ui.theme.ClayType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

private val ScrimTop = Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent))
private val ScrimBottom = Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))
private val Accent = Clay.Pink

private enum class DragMode { NONE, SEEK, BRIGHTNESS, VOLUME, PINCH }

private enum class Sheet { NONE, TRACKS, SPEED, FIT }

@Composable
fun PlayerOverlay(
    controller: PlayerController,
    onDownload: (VideoRequest) -> Unit,
    onPlayInPage: (VideoRequest) -> Unit,
) {
    val state = controller.state
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf(Sheet.NONE) }

    // Accumulating double-tap seek feedback.
    var seekBurst by remember { mutableLongStateOf(0L) }
    var seekBurstSide by remember { mutableIntStateOf(0) }
    var seekBurstJob by remember { mutableStateOf<Job?>(null) }
    var dragSeekTarget by remember { mutableLongStateOf(-1L) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // --- Video surface --------------------------------------------------
        val zoom = state.zoomScale
        AndroidView(
            factory = { context ->
                // Inflated for surface_type="texture_view": a TextureView composes like any
                // view (a SurfaceView can show black under overlays) and follows pinch zoom.
                (android.view.LayoutInflater.from(context).inflate(com.webdroid.app.R.layout.player_view, null) as PlayerView).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    subtitleView?.setApplyEmbeddedStyles(true)
                    subtitleView?.setFractionalTextSize(0.0533f * 1.1f)
                }
            },
            update = { view ->
                if (view.player !== controller.exoPlayer) view.player = controller.exoPlayer
                view.resizeMode = when (state.fitMode) {
                    FitMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    FitMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    FitMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            },
            onRelease = { it.player = null },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = zoom; scaleY = zoom },
        )

        if (state.inPip) return@Box

        // --- Gesture layer --------------------------------------------------
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(state.locked) {
                        detectTapGestures(
                            onTap = { controller.toggleControls() },
                            onDoubleTap = { offset ->
                                if (state.locked) return@detectTapGestures
                                val third = widthPx / 3f
                                when {
                                    offset.x < third -> {
                                        haptics.tick()
                                        if (seekBurstSide != -1) seekBurst = 0
                                        seekBurstSide = -1
                                        seekBurst += 10_000
                                        controller.seekBy(-10_000)
                                    }
                                    offset.x > third * 2 -> {
                                        haptics.tick()
                                        if (seekBurstSide != 1) seekBurst = 0
                                        seekBurstSide = 1
                                        seekBurst += 10_000
                                        controller.seekBy(10_000)
                                    }
                                    else -> controller.togglePlay()
                                }
                                seekBurstJob?.cancel()
                                seekBurstJob = scope.launch {
                                    delay(800)
                                    seekBurst = 0
                                    seekBurstSide = 0
                                }
                            },
                            onLongPress = {
                                if (!state.locked && state.isPlaying) {
                                    haptics.longPress()
                                    controller.beginSpeedBoost()
                                }
                            },
                            onPress = {
                                tryAwaitRelease()
                                if (state.hint?.kind == GestureKind.SPEED) controller.endSpeedBoost()
                            },
                        )
                    }
                    .pointerInput(state.locked) {
                        if (state.locked) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var mode = DragMode.NONE
                            var total = Offset.Zero
                            var pinchStart = 0f
                            var startZoom = state.zoomScale
                            val seekStart = state.positionMs
                            var seekDelta = 0L
                            val slop = viewConfiguration.touchSlop
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size >= 2) {
                                    val d = hypot(
                                        pressed[0].position.x - pressed[1].position.x,
                                        pressed[0].position.y - pressed[1].position.y,
                                    )
                                    if (mode != DragMode.PINCH) {
                                        mode = DragMode.PINCH
                                        pinchStart = d
                                        startZoom = state.zoomScale
                                    } else if (pinchStart > 0f) {
                                        state.zoomScale = (startZoom * d / pinchStart).coerceIn(0.6f, 3f)
                                        controller.holdHint(GestureKind.ZOOM, state.zoomScale / 3f, "${(state.zoomScale * 100).toInt()}%")
                                    }
                                    event.changes.forEach { it.consume() }
                                    continue
                                }
                                val change = pressed[0]
                                val delta = change.positionChange()
                                if (mode == DragMode.NONE) {
                                    total += delta
                                    if (hypot(total.x, total.y) > slop * 1.5f) {
                                        mode = when {
                                            abs(total.x) > abs(total.y) -> DragMode.SEEK
                                            down.position.x < widthPx / 2f -> DragMode.BRIGHTNESS
                                            else -> DragMode.VOLUME
                                        }
                                        haptics.tick()
                                    }
                                }
                                when (mode) {
                                    DragMode.SEEK -> {
                                        // One screen width scrubs the whole of a short clip, and up to
                                        // 10 minutes of a long video.
                                        val span = when {
                                            state.durationMs in 1..180_000 -> state.durationMs
                                            state.durationMs > 0 -> (state.durationMs / 4).coerceIn(120_000L, 600_000L)
                                            else -> 90_000L
                                        }
                                        seekDelta += (delta.x / widthPx * span).toLong()
                                        val target = (seekStart + seekDelta).coerceIn(0, state.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
                                        dragSeekTarget = target
                                        val sign = if (target >= seekStart) "+" else "−"
                                        controller.holdHint(
                                            GestureKind.SEEK,
                                            if (state.durationMs > 0) target / state.durationMs.toFloat() else 0f,
                                            "$sign${PlayerController.formatTime(abs(target - seekStart))}  ·  ${PlayerController.formatTime(target)}",
                                        )
                                        change.consume()
                                    }
                                    DragMode.BRIGHTNESS -> {
                                        controller.adjustBrightness(-delta.y / (heightPx * 0.8f))
                                        change.consume()
                                    }
                                    DragMode.VOLUME -> {
                                        controller.adjustVolume(-delta.y / (heightPx * 0.8f))
                                        change.consume()
                                    }
                                    else -> Unit
                                }
                            }
                            when (mode) {
                                DragMode.SEEK -> {
                                    if (dragSeekTarget >= 0) controller.seekTo(dragSeekTarget)
                                    dragSeekTarget = -1
                                    controller.releaseHint()
                                }
                                DragMode.PINCH -> {
                                    val z = state.zoomScale
                                    when {
                                        z > 1.12f && state.fitMode == FitMode.FIT -> controller.setFit(FitMode.ZOOM)
                                        z < 0.9f && state.fitMode != FitMode.FIT -> controller.setFit(FitMode.FIT)
                                        else -> {
                                            state.zoomScale = 1f
                                            controller.releaseHint()
                                        }
                                    }
                                }
                                DragMode.BRIGHTNESS, DragMode.VOLUME -> controller.releaseHint()
                                DragMode.NONE -> Unit
                            }
                        }
                    }
            )
        }

        // --- Double-tap seek ripples ----------------------------------------
        AnimatedVisibility(
            visible = seekBurst > 0 && seekBurstSide != 0,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(if (seekBurstSide < 0) Alignment.CenterStart else Alignment.CenterEnd),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(170.dp)
                    .clip(if (seekBurstSide < 0) ClayShapes.Xl else ClayShapes.Xl)
                    .background(Color.White.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (seekBurstSide < 0) Icons.Rounded.FastRewind else Icons.Rounded.FastForward,
                        null, tint = Color.White, modifier = Modifier.size(36.dp),
                    )
                    Text("${seekBurst / 1000}s", color = Color.White, style = ClayType.TitleSm)
                }
            }
        }

        // --- Gesture hint ----------------------------------------------------
        val hint = state.hint
        AnimatedVisibility(
            visible = hint != null,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.95f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
        ) {
            val current = state.hint ?: GestureHint(GestureKind.SEEK, 0f, "")
            HintPill(current)
        }

        // --- Buffering -------------------------------------------------------
        if (state.buffering && state.error == null) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center).size(52.dp),
            )
        }

        // --- Lock badge ------------------------------------------------------
        if (state.locked) {
            AnimatedVisibility(
                visible = state.controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 20.dp),
            ) {
                RoundControl(Icons.Rounded.Lock, "Unlock", size = 56) {
                    haptics.confirm()
                    controller.toggleLock()
                }
            }
            return@Box
        }

        // --- Controls --------------------------------------------------------
        AnimatedVisibility(
            visible = state.controlsVisible || state.error != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(140.dp).background(ScrimTop).align(Alignment.TopCenter))
                Box(Modifier.fillMaxWidth().height(180.dp).background(ScrimBottom).align(Alignment.BottomCenter))

                // Top bar
                Row(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundControl(Icons.AutoMirrored.Rounded.ArrowBack, "Close player", background = Color.Transparent) {
                        controller.close()
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                        Text(
                            state.request?.title?.ifBlank { null } ?: state.request?.appName.orEmpty(),
                            color = Color.White, style = ClayType.TitleSm, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(state.request?.appName.orEmpty(), color = Color.White.copy(alpha = 0.7f), style = ClayType.Caption, maxLines = 1)
                    }
                    RoundControl(Icons.Rounded.PictureInPictureAlt, "Picture in picture", background = Color.Transparent) {
                        controller.enterPip()
                    }
                    RoundControl(Icons.Rounded.ClosedCaption, "Audio and subtitles", background = Color.Transparent) {
                        sheet = Sheet.TRACKS
                        controller.showControls(autoHide = false)
                    }
                    RoundControl(Icons.Rounded.Speed, "Playback speed", background = Color.Transparent) {
                        sheet = Sheet.SPEED
                        controller.showControls(autoHide = false)
                    }
                }

                // Centre transport
                if (state.error == null) {
                    Row(
                        Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        RoundControl(Icons.Rounded.Replay10, "Back 10 seconds", size = 56) { controller.seekBy(-10_000); controller.showControls() }
                        val playIcon = when {
                            state.ended -> Icons.Rounded.Replay
                            state.isPlaying -> Icons.Rounded.Pause
                            else -> Icons.Rounded.PlayArrow
                        }
                        RoundControl(playIcon, "Play or pause", size = 76, background = Color.White.copy(alpha = 0.18f)) {
                            haptics.tap()
                            controller.togglePlay()
                        }
                        RoundControl(Icons.Rounded.Forward10, "Forward 10 seconds", size = 56) { controller.seekBy(10_000); controller.showControls() }
                    }
                } else {
                    Column(
                        Modifier.align(Alignment.Center).padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(state.error.orEmpty(), color = Color.White, style = ClayType.BodyMd)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PillButton("Retry") {
                                state.error = null
                                state.request?.let { controller.open(it) }
                            }
                            PillButton("Play in page") {
                                state.request?.let(onPlayInPage)
                            }
                        }
                    }
                }

                // Bottom bar
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    val resumed = state.resumedFrom
                    AnimatedVisibility(visible = resumed != null, enter = fadeIn() + slideInVertically { it }, exit = fadeOut()) {
                        Row(
                            Modifier
                                .padding(bottom = 10.dp)
                                .clip(ClayShapes.Pill)
                                .background(Color.White.copy(alpha = 0.16f))
                                .clickable { controller.restartFromBeginning() }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Resumed at ${PlayerController.formatTime(resumed ?: 0)}", color = Color.White, style = ClayType.Caption)
                            Text("  ·  Start over", color = Accent, style = ClayType.Caption.copy(fontWeight = FontWeight.SemiBold))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            PlayerController.formatTime(if (dragSeekTarget >= 0) dragSeekTarget else state.positionMs),
                            color = Color.White, style = ClayType.Caption,
                        )
                        SeekBar(
                            positionMs = if (dragSeekTarget >= 0) dragSeekTarget else state.positionMs,
                            durationMs = state.durationMs,
                            bufferedMs = state.bufferedMs,
                            onScrub = { dragSeekTarget = it; controller.showControls(autoHide = false) },
                            onScrubEnd = {
                                if (dragSeekTarget >= 0) controller.seekTo(dragSeekTarget)
                                dragSeekTarget = -1
                                controller.showControls()
                            },
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        Text(
                            if (state.durationMs > 0) PlayerController.formatTime(state.durationMs) else "LIVE",
                            color = Color.White, style = ClayType.Caption,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row {
                            RoundControl(Icons.Rounded.LockOpen, "Lock controls", background = Color.Transparent) {
                                haptics.confirm()
                                controller.toggleLock()
                            }
                            RoundControl(Icons.Rounded.ScreenRotation, "Rotate", background = Color.Transparent) {
                                controller.toggleRotation()
                            }
                        }
                        Row {
                            if (state.request?.isProgressive == true) {
                                RoundControl(Icons.Rounded.Download, "Download", background = Color.Transparent) {
                                    state.request?.let(onDownload)
                                }
                            }
                            RoundControl(Icons.Rounded.AspectRatio, "Fit", background = Color.Transparent) {
                                sheet = Sheet.FIT
                                controller.showControls(autoHide = false)
                            }
                        }
                    }
                }
            }
        }

        // --- Sheets ----------------------------------------------------------
        if (sheet != Sheet.NONE) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        sheet = Sheet.NONE
                        controller.showControls()
                    }
            )
        }
        AnimatedVisibility(
            visible = sheet != Sheet.NONE,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val dismiss = { sheet = Sheet.NONE; controller.showControls() }
            PlayerSheet {
                when (sheet) {
                    Sheet.TRACKS -> {
                        @Suppress("UNUSED_VARIABLE") val version = state.tracksVersion
                        val sections = remember(state.tracksVersion) { controller.trackSections() }
                        if (sections.isEmpty()) {
                            SheetTitle("Audio & subtitles")
                            Text("This stream has a single audio track and no subtitles.", color = Color.White.copy(alpha = 0.7f), style = ClayType.BodySm)
                        }
                        sections.forEach { section ->
                            SheetTitle(section.title)
                            section.options.forEach { option ->
                                SheetOption(option.label, option.selected) { option.select() }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    Sheet.SPEED -> {
                        SheetTitle("Playback speed")
                        listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f).forEach { speed ->
                            SheetOption(if (speed == 1f) "Normal" else PlayerController.formatSpeed(speed), state.speed == speed) {
                                controller.setSpeed(speed)
                                dismiss()
                            }
                        }
                    }
                    Sheet.FIT -> {
                        SheetTitle("Fit to screen")
                        SheetOption("Fit (original ratio)", state.fitMode == FitMode.FIT) { controller.setFit(FitMode.FIT); dismiss() }
                        SheetOption("Zoom to fill", state.fitMode == FitMode.ZOOM) { controller.setFit(FitMode.ZOOM); dismiss() }
                        SheetOption("Stretch", state.fitMode == FitMode.FILL) { controller.setFit(FitMode.FILL); dismiss() }
                    }
                    Sheet.NONE -> Unit
                }
            }
        }
    }
}

@Composable
private fun HintPill(hint: GestureHint) {
    val icon: ImageVector = when (hint.kind) {
        GestureKind.BRIGHTNESS -> Icons.Rounded.Brightness6
        GestureKind.VOLUME -> Icons.Rounded.VolumeUp
        GestureKind.SPEED -> Icons.Rounded.FastForward
        GestureKind.ZOOM -> Icons.Rounded.ZoomOutMap
        GestureKind.SEEK_BACK -> Icons.Rounded.FastRewind
        else -> if (hint.text.startsWith("−")) Icons.Rounded.FastRewind else Icons.Rounded.FastForward
    }
    Column(
        Modifier
            .clip(ClayShapes.Lg)
            .background(Color.Black.copy(alpha = 0.62f))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(hint.text, color = Color.White, style = ClayType.TitleSm)
        }
        if (hint.kind == GestureKind.BRIGHTNESS || hint.kind == GestureKind.VOLUME || hint.kind == GestureKind.SEEK) {
            Spacer(Modifier.height(10.dp))
            val fraction by animateFloatAsState(hint.fraction.coerceIn(0f, 1f), label = "hint")
            Box(Modifier.width(150.dp).height(4.dp).clip(ClayShapes.Pill).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(ClayShapes.Pill).background(Accent))
            }
        }
    }
}

@Composable
private fun RoundControl(
    icon: ImageVector,
    description: String,
    size: Int = 44,
    background: Color = Color.Black.copy(alpha = 0.28f),
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size((size * 0.52f).dp))
    }
}

@Composable
private fun PillButton(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = Clay.Black,
        style = ClayType.Button,
        modifier = Modifier
            .clip(ClayShapes.Md)
            .background(Clay.Cream)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

/**
 * Scrubber: press anywhere on it to jump there, drag to scrub (the thumb and
 * a time bubble follow the finger), release to seek. The touch area is much
 * taller than the line, and system back gestures are kept off it.
 */
@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    var scrubbing by remember { mutableStateOf(false) }
    val duration by rememberUpdatedState(durationMs)
    val scrub by rememberUpdatedState(onScrub)
    val scrubEnd by rememberUpdatedState(onScrubEnd)
    val thumb by animateFloatAsState(if (scrubbing) 10f else 7f, label = "thumb")
    val lineHeight by animateFloatAsState(if (scrubbing) 6f else 4f, label = "line")
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    BoxWithConstraints(modifier.height(48.dp)) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        Canvas(
            Modifier
                .fillMaxSize()
                .systemGestureExclusion()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (duration <= 0) return@awaitEachGesture
                        down.consume()
                        fun at(x: Float) = (x.coerceIn(0f, size.width.toFloat()) / size.width * duration).toLong()
                        scrubbing = true
                        haptics.tick()
                        scrub(at(down.position.x))
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            if (change.positionChange() != Offset.Zero) {
                                scrub(at(change.position.x))
                                change.consume()
                            }
                        }
                        scrubbing = false
                        scrubEnd()
                    }
                }
        ) {
            val y = size.height / 2
            val h = lineHeight.dp.toPx()
            drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h))
            drawRoundRect(Color.White.copy(alpha = 0.42f), Offset(0f, y - h / 2), Size(size.width * buffered, h), CornerRadius(h))
            drawRoundRect(Accent, Offset(0f, y - h / 2), Size(size.width * progress, h), CornerRadius(h))
            drawCircle(Color.White, thumb.dp.toPx(), Offset(size.width * progress, y))
        }
        // Time bubble above the thumb while scrubbing.
        if (scrubbing && durationMs > 0) {
            val bubbleWidth = 64.dp
            val x = with(androidx.compose.ui.platform.LocalDensity.current) { (widthPx * progress).toDp() } - bubbleWidth / 2
            Text(
                PlayerController.formatTime(positionMs),
                color = Color.Black,
                style = ClayType.Caption.copy(fontWeight = FontWeight.SemiBold),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .offset(x = x.coerceIn(0.dp, maxWidth - bubbleWidth), y = (-30).dp)
                    .width(bubbleWidth)
                    .clip(ClayShapes.Pill)
                    .background(Color.White)
                    .padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun PlayerSheet(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 460.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(Color(0xFF141414))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 36.dp, height = 4.dp)
                .clip(ClayShapes.Pill)
                .background(Color.White.copy(alpha = 0.25f))
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text.uppercase(),
        color = Color.White.copy(alpha = 0.55f),
        style = ClayType.CaptionUpper,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
    )
}

@Composable
private fun SheetOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ClayShapes.Md)
            .background(if (selected) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, style = ClayType.BodyMd.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Rounded.Check, null, tint = Accent, modifier = Modifier.size(20.dp))
    }
}
