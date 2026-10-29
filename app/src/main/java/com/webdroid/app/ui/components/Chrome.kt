package com.webdroid.app.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.webdroid.app.ui.glass.Glass
import com.webdroid.app.ui.glass.GlassBackdrop
import com.webdroid.app.ui.glass.GlassStyle
import com.webdroid.app.ui.theme.AppColors
import com.webdroid.app.ui.theme.AppType
import com.webdroid.app.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/* -------------------------------------------------------------------------- */
/* Tab bar                                                                     */
/* -------------------------------------------------------------------------- */

data class TabItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/** Height the tab bar occupies above the navigation bar (for content padding). */
val TabBarClearance = 96.dp

/**
 * Floating Liquid Glass tab bar. The selection is a lighter drop of glass that
 * springs between tabs; drag across the bar and it follows the finger, then
 * lands on the tab the drag was heading for.
 */
@Composable
fun BoxScope.GlassTabBar(
    backdrop: GlassBackdrop,
    items: List<TabItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onReselect: (Int) -> Unit = {},
) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val itemWidth = 82.dp
    val density = LocalDensity.current
    val itemPx = with(density) { itemWidth.toPx() }
    val position = remember { Animatable(selected.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    var stretch by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(selected) {
        if (!dragging) position.animateTo(selected.toFloat(), Motion.spring(0.35f, 0.82f))
    }
    val stretchAnimated by animateFloatAsState(stretch, Motion.snappy(), label = "tab-stretch")
    // While a finger slides across the bar, the selection lifts like a lens
    // and magnifies the tab beneath it.
    val lens by animateFloatAsState(if (dragging) 1f else 0f, Motion.spring(0.3f, 0.8f), label = "tab-lens")

    Glass(
        backdrop = backdrop,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 12.dp)
            .height(64.dp)
            .width(itemWidth * items.size + 8.dp)
            .pointerInput(items.size) {
                // Slide across the bar to scrub between tabs.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    var moved = 0f
                    var started = false
                    val startIndex = position.value
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) break
                        moved += change.positionChange().x
                        if (!started && abs(moved) > 10.dp.toPx()) {
                            started = true
                            dragging = true
                        }
                        if (started) {
                            change.consume()
                            val target = (startIndex + moved / itemPx).coerceIn(-0.25f, items.size - 0.75f)
                            scope.launch { position.snapTo(target) }
                            stretch = (abs(change.positionChange().x) / 12f).coerceAtMost(1f)
                        }
                    }
                    if (started) {
                        val velocity = tracker.calculateVelocity().x
                        val projected = position.value + Motion.project(velocity, 0.99f) / itemPx
                        val index = projected.roundToInt().coerceIn(0, items.size - 1)
                        stretch = 0f
                        dragging = false
                        if (index != selected) {
                            haptics.tick()
                            onSelect(index)
                        }
                        scope.launch {
                            position.animateTo(index.toFloat(), Motion.momentum(), initialVelocity = velocity / itemPx)
                        }
                    }
                }
            },
        cornerRadius = 32.dp,
        style = GlassStyle.Regular,
    ) {
        // Selection drop.
        Box(
            Modifier
                .padding(4.dp)
                .fillMaxHeight()
                .width(itemWidth)
                .graphicsLayer {
                    translationX = position.value * itemPx
                    scaleX = (1f + 0.18f * stretchAnimated) * (1f + 0.1f * lens)
                    scaleY = (1f - 0.06f * stretchAnimated) * (1f + 0.1f * lens)
                }
                .drawBehind {
                    val corner = CornerRadius(size.height / 2)
                    drawRoundRect(
                        if (AppColors.isDark) Color.White.copy(alpha = 0.14f + 0.06f * lens) else Color.Black.copy(alpha = 0.07f + 0.03f * lens),
                        cornerRadius = corner,
                    )
                    // Its own rim of light, like a drop of glass on the bar.
                    drawRoundRect(
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = if (AppColors.isDark) 0.28f else 0.7f), Color.White.copy(alpha = 0.06f)),
                        ),
                        cornerRadius = corner,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                    )
                }
        )
        Row(Modifier.fillMaxSize().padding(4.dp)) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selected
                val tint = if (isSelected) AppColors.Accent else AppColors.Label
                val press = rememberPressState()
                Column(
                    Modifier
                        .width(itemWidth)
                        .fillMaxHeight()
                        .graphicsLayer {
                            val near = (1f - abs(position.value - index)).coerceIn(0f, 1f)
                            val s = 1f + 0.16f * near * lens
                            scaleX = s
                            scaleY = s
                        }
                        .pressScale(press, 0.9f)
                        .trackPress(press)
                        .semantics {
                            role = Role.Tab
                            this.selected = isSelected
                            contentDescription = item.label
                        }
                        .clickableNoIndication {
                            if (isSelected) onReselect(index) else {
                                haptics.tick()
                                onSelect(index)
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(if (isSelected) item.selectedIcon else item.icon, null, tint = tint, modifier = Modifier.size(25.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(item.label, style = AppType.Caption2, color = tint, maxLines = 1)
                }
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Top bar with scroll edge effect                                             */
/* -------------------------------------------------------------------------- */

/** 0 at rest, 1 once the large title has scrolled under the bar. */
@Composable
fun rememberCollapse(state: LazyListState, titleIndex: Int = 0): State<Float> {
    val density = LocalDensity.current
    val distance = with(density) { 44.dp.toPx() }
    return remember(state) {
        derivedStateOf {
            when {
                state.firstVisibleItemIndex > titleIndex -> 1f
                else -> (state.firstVisibleItemScrollOffset / distance).coerceIn(0f, 1f)
            }
        }
    }
}

@Composable
fun rememberCollapse(state: LazyGridState): State<Float> {
    val density = LocalDensity.current
    val distance = with(density) { 44.dp.toPx() }
    return remember(state) {
        derivedStateOf {
            when {
                state.firstVisibleItemIndex > 0 -> 1f
                else -> (state.firstVisibleItemScrollOffset / distance).coerceIn(0f, 1f)
            }
        }
    }
}

/** The backdrop of the app's floating chrome (tab bar), for screens to report scrolling to. */
val LocalChromeBackdrop = androidx.compose.runtime.staticCompositionLocalOf<GlassBackdrop?> { null }

/** Reports [list] scrolling to the screen's own glass and to the tab bar's. */
@Composable
fun TrackScrollAll(screen: GlassBackdrop, list: LazyListState) {
    TrackScroll(screen, list)
    LocalChromeBackdrop.current?.let { TrackScroll(it, list) }
}

@Composable
fun TrackScrollAll(screen: GlassBackdrop, grid: LazyGridState) {
    TrackScroll(screen, grid)
    LocalChromeBackdrop.current?.let { TrackScroll(it, grid) }
}

/** Lets the glass over a screen refresh as [list] scrolls. */
@Composable
fun TrackScroll(backdrop: GlassBackdrop, list: LazyListState) {
    DisposableEffect(backdrop, list) {
        backdrop.track(list) { list.firstVisibleItemIndex * 100_000 + list.firstVisibleItemScrollOffset }
        onDispose { backdrop.untrack(list) }
    }
}

@Composable
fun TrackScroll(backdrop: GlassBackdrop, grid: LazyGridState) {
    DisposableEffect(backdrop, grid) {
        backdrop.track(grid) { grid.firstVisibleItemIndex * 100_000 + grid.firstVisibleItemScrollOffset }
        onDispose { backdrop.untrack(grid) }
    }
}

/** Status bar height plus the bar's row. */
@Composable
fun topBarHeight(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp

/**
 * Top bar floating over a scrolling screen: glass buttons always, and once
 * the content scrolls beneath it ([collapse] > 0) a scroll edge effect plus
 * the inline title.
 */
@Composable
fun BoxScope.TopChrome(
    backdrop: GlassBackdrop?,
    title: String,
    collapse: Float,
    alwaysShowTitle: Boolean = false,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val height = topBarHeight()
    val titleAlpha = if (alwaysShowTitle) 1f else ((collapse - 0.5f) * 2f).coerceIn(0f, 1f)
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(height + 18.dp)) {
        if (collapse > 0.01f) {
            Glass(
                backdrop = backdrop,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 0.dp,
                style = GlassStyle.Edge,
                shadow = false,
                alpha = collapse,
            ) {}
        }
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = height - 56.dp)
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = leading)
            Text(
                title,
                style = AppType.Headline,
                color = AppColors.Label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(2f)
                    .graphicsLayer { alpha = titleAlpha }
                    .semantics { heading() },
            )
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

/** Glass back button for pushed screens. */
@Composable
fun BackButton(backdrop: GlassBackdrop?, onBack: () -> Unit) {
    GlassIconButton(backdrop, Icons.AutoMirrored.Rounded.ArrowBackIos, "Back", onBack, size = 44.dp)
}

/* -------------------------------------------------------------------------- */
/* Push navigation with predictive back                                        */
/* -------------------------------------------------------------------------- */

/** One entry in a navigation stack: [depth] orders pushes and pops. */
data class StackEntry<T>(val depth: Int, val screen: T)

/**
 * A navigation stack. Pushed screens slide in from the trailing edge over a
 * parallaxed, dimmed parent; Android's predictive back gesture scrubs the pop
 * 1:1 with the finger, and cancelling it springs back.
 */
@OptIn(androidx.compose.animation.core.ExperimentalTransitionApi::class)
@Composable
fun <T> NavStack(
    stack: List<T>,
    onPop: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = null,
    content: @Composable (T) -> Unit,
) {
    val entries = stack.mapIndexed { i, s -> StackEntry(i, s) }
    val top = entries.last()
    val transitionState = remember { SeekableTransitionState(top) }
    LaunchedEffect(top) {
        if (transitionState.currentState != top) transitionState.animateTo(top)
    }
    var seeking by remember { mutableStateOf(false) }
    PredictiveBackHandler(enabled = entries.size > 1) { progress ->
        val previous = entries[entries.size - 2]
        try {
            seeking = true
            progress.collect { event -> transitionState.seekTo(event.progress, previous) }
            seeking = false
            onPop()
        } catch (e: CancellationException) {
            seeking = false
            transitionState.animateTo(top)
        }
    }
    val transition = rememberTransition(transitionState, label = "nav")
    if (backdrop != null) {
        // The glass over the stack refreshes while screens slide.
        DisposableEffect(backdrop, transition) {
            backdrop.track(transition) { transition.isRunning to transitionState.fraction }
            onDispose { backdrop.untrack(transition) }
        }
    }
    transition.AnimatedContent(
        modifier = modifier,
        transitionSpec = {
            val push = targetState.depth > initialState.depth
            navTransform(push)
        },
        contentKey = { it.depth to it.screen },
    ) { entry ->
        content(entry.screen)
    }
}

private fun navTransform(push: Boolean): ContentTransform {
    if (Motion.reduced) return fadeIn(tween(150)) togetherWith fadeOut(tween(150))
    val spec = Motion.smooth<androidx.compose.ui.unit.IntOffset>(androidx.compose.ui.unit.IntOffset(1, 1))
    return if (push) {
        slideInHorizontally(spec) { it } togetherWith
            (slideOutHorizontally(spec) { -it / 3 } + fadeOut(Motion.smooth(), targetAlpha = 0.6f))
    } else {
        (slideInHorizontally(spec) { -it / 3 } + fadeIn(Motion.smooth(), initialAlpha = 0.6f)) togetherWith
            slideOutHorizontally(spec) { it }
    }
}

/* -------------------------------------------------------------------------- */
/* Toast                                                                       */
/* -------------------------------------------------------------------------- */

/** Brief, non-blocking status message on glass, above the tab bar. */
@Composable
fun BoxScope.GlassToast(backdrop: GlassBackdrop?, message: String?, bottomPadding: Dp = TabBarClearance + 8.dp) {
    val shown = remember { arrayOf("") }
    if (message != null) shown[0] = message
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically(Motion.smooth()) { it / 2 } + fadeIn(Motion.smooth()) + scaleIn(Motion.smooth(), initialScale = 0.92f),
        exit = slideOutVertically(Motion.smooth()) { it / 2 } + fadeOut(Motion.smooth()) + scaleOut(Motion.smooth(), targetScale = 0.92f),
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = bottomPadding, start = 24.dp, end = 24.dp),
    ) {
        Glass(backdrop, cornerRadius = 22.dp, style = GlassStyle.Thick) {
            Text(
                shown[0],
                style = AppType.Subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                color = AppColors.Label,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}

/** clickable() without ripple or press indication (callers draw their own). */
fun Modifier.clickableNoIndication(role: Role = Role.Button, onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        role = role,
        onClick = onClick,
    )
}
