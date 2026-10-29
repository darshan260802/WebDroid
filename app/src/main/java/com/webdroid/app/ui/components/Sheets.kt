package com.webdroid.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.webdroid.app.ui.glass.Glass
import com.webdroid.app.ui.glass.GlassBackdrop
import com.webdroid.app.ui.glass.GlassStyle
import com.webdroid.app.ui.theme.AppColors
import com.webdroid.app.ui.theme.AppShapes
import com.webdroid.app.ui.theme.AppType
import com.webdroid.app.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/* -------------------------------------------------------------------------- */
/* Modal sheet                                                                 */
/* -------------------------------------------------------------------------- */

enum class SheetSize { Medium, Large }

/**
 * How far sheets are presented, for the screen beneath to recede (scale back
 * with rounded corners) as a large sheet rises over it.
 */
class SheetPresentation {
    internal val progress = mutableStateMapOf<Any, Float>()

    /** 0 with no sheet up, 1 with a sheet fully raised. */
    val value: Float get() = progress.values.maxOrNull() ?: 0f
}

val LocalSheetPresentation = staticCompositionLocalOf<SheetPresentation?> { null }

/**
 * Modal sheet with a grabber and detents. It tracks the finger 1:1, rubber-
 * bands above its top detent, and on release projects the flick's momentum to
 * pick a detent (or dismiss), handing the finger's velocity to the spring. The
 * predictive back gesture lowers it with the finger.
 *
 * When [canDismiss] is false (unsaved changes) a swipe down springs back and
 * [onDismissRequest] lets the caller confirm.
 */
@Composable
fun BoxScope.ModalSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sizes: List<SheetSize> = listOf(SheetSize.Large),
    canDismiss: Boolean = true,
    surface: Color = AppColors.Grouped,
    onProgress: (Float) -> Unit = {},
    /** False when the content handles Back itself (e.g. a web page with history). */
    handleBack: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    var shown by remember { mutableStateOf(visible) }
    if (visible) shown = true
    val presentation = LocalSheetPresentation.current
    val key = remember { Any() }
    DisposableEffect(presentation) { onDispose { presentation?.progress?.remove(key) } }
    if (!shown) {
        SideEffect {
            onProgress(0f)
            presentation?.progress?.remove(key)
        }
        return
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    BoxWithConstraints(modifier.fillMaxSize()) {
        val full = constraints.maxHeight.toFloat()
        val largeTop = with(density) { (topInset + 10.dp).toPx() }
        val mediumTop = full * 0.48f
        val detentTops = sizes.map { if (it == SheetSize.Large) largeTop else mediumTop }.sorted()
        val restTop = detentTops.last() // the lowest detent (first shown)
        val highest = detentTops.first()
        val y = remember { Animatable(full) }
        val progress = ((full - y.value) / (full - highest)).coerceIn(0f, 1f)
        val recede = sizes.contains(SheetSize.Large)
        SideEffect {
            onProgress(progress)
            if (recede) presentation?.progress?.set(key, progress)
        }

        LaunchedEffect(visible) {
            if (visible) {
                y.animateTo(restTop, Motion.smooth())
            } else {
                y.animateTo(full, Motion.smooth())
                shown = false
            }
        }

        fun settle(velocity: Float) {
            val projected = y.value + Motion.project(velocity, 0.99f)
            val options = if (canDismiss) detentTops + full else detentTops
            var target = options.minByOrNull { abs(it - projected) } ?: restTop
            if (!canDismiss && projected > restTop + with(density) { 80.dp.toPx() }) onDismissRequest()
            if (target == full) {
                onDismissRequest()
                target = full
            }
            scope.launch { y.animateTo(target, Motion.sheet(), initialVelocity = velocity) }
        }

        fun dragBy(delta: Float) {
            val raw = y.value + delta
            val next = if (raw < highest) highest - Motion.rubberband(highest - raw, full) else raw
            scope.launch { y.snapTo(next) }
        }

        val nested = remember(detentTops, canDismiss) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    // Pulling up while the sheet is below its top detent expands it first.
                    if (available.y < 0 && y.value > highest + 0.5f && source == NestedScrollSource.UserInput) {
                        dragBy(available.y)
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    // Content is at its top: keep pulling and the sheet follows.
                    if (available.y > 0 && source == NestedScrollSource.UserInput) {
                        dragBy(available.y)
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    val off = y.value - (detentTops.firstOrNull { abs(it - y.value) < 1f } ?: Float.NaN)
                    if (y.value > highest + 1f && (off.isNaN() || abs(off) > 1f)) {
                        settle(available.y)
                        return available
                    }
                    return Velocity.Zero
                }
            }
        }

        // Scrim: dims with the sheet's progress; tap to dismiss.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress }
                .background(AppColors.Scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismissRequest() }
        )

        PredictiveBackHandler(enabled = visible && handleBack) { events ->
            val start = y.value
            try {
                events.collect { e -> y.snapTo(start + (full - start) * e.progress * 0.5f) }
                onDismissRequest()
                if (!canDismiss) y.animateTo(start, Motion.sheet())
            } catch (e: CancellationException) {
                y.animateTo(start, Motion.sheet())
            }
        }

        Column(
            Modifier
                .offset { IntOffset(0, y.value.roundToInt()) }
                .align(Alignment.TopCenter)
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .height(with(density) { (full - highest).toDp() })
                .clip(AppShapes.Sheet)
                .background(surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .nestedScroll(nested)
                .imePadding(),
        ) {
            // Grabber: drag (or tap to cycle detents).
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { dragBy(it) },
                        onDragStopped = { settle(it) },
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "Resize",
                    ) {
                        if (detentTops.size > 1) {
                            val next = if (abs(y.value - highest) < 1f) restTop else highest
                            scope.launch { y.animateTo(next, Motion.smooth()) }
                        }
                    }
                    .semantics { contentDescription = "Sheet grabber" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 36.dp, height = 5.dp).clip(AppShapes.Capsule).background(AppColors.TertiaryLabel.copy(alpha = 0.5f)))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { dragBy(it) },
                        onDragStopped = { settle(it) },
                    ),
                content = content,
            )
        }
    }
}

/** Toolbar at the top of a sheet: Cancel on the leading edge, the action on the trailing edge. */
@Composable
fun SheetToolbar(
    title: String,
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = leading)
        Text(title, style = AppType.Headline, color = AppColors.Label, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(2f))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** Text button for toolbars ("Cancel", "Done"). */
@Composable
fun ToolbarTextButton(text: String, onClick: () -> Unit, prominent: Boolean = false, enabled: Boolean = true) {
    if (prominent) {
        AppButton(text, onClick, enabled = enabled, height = 36.dp, modifier = Modifier.heightIn(min = 36.dp))
    } else {
        val press = rememberPressState()
        Text(
            text,
            style = AppType.Body,
            color = if (enabled) AppColors.Accent else AppColors.TertiaryLabel,
            modifier = Modifier
                .graphicsLayer { alpha = if (press.pressed) 0.5f else 1f }
                .clip(AppShapes.Capsule)
                .tappable(press, enabled, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 12.dp),
        )
    }
}

/* -------------------------------------------------------------------------- */
/* Alerts                                                                      */
/* -------------------------------------------------------------------------- */

data class AlertAction(val title: String, val style: ButtonStyle = ButtonStyle.Tinted, val onClick: () -> Unit)

/**
 * Centered alert on glass. Used sparingly: destructive confirmations and
 * questions from the page (permissions, JavaScript dialogs, sign-in).
 */
@Composable
fun BoxScope.GlassAlert(
    visible: Boolean,
    backdrop: GlassBackdrop?,
    title: String,
    message: String? = null,
    actions: List<AlertAction>,
    onDismiss: (() -> Unit)? = null,
    icon: ImageVector? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    BackHandler(enabled = visible && onDismiss != null) { onDismiss?.invoke() }
    AnimatedVisibility(visible = visible, enter = fadeIn(Motion.smooth()), exit = fadeOut(Motion.snappy()), modifier = Modifier.matchParentSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(AppColors.Scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss?.invoke() }
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.smooth()) + scaleIn(Motion.smooth(), initialScale = 1.12f),
        exit = fadeOut(Motion.snappy()) + scaleOut(Motion.snappy(), targetScale = 0.96f),
        modifier = Modifier.align(Alignment.Center).imePadding(),
    ) {
        Glass(
            backdrop = backdrop,
            modifier = Modifier.padding(32.dp).widthIn(max = 320.dp).fillMaxWidth(),
            cornerRadius = 30.dp,
            style = GlassStyle.Thick,
        ) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (icon != null) {
                    Icon(icon, null, tint = AppColors.Accent, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.height(10.dp))
                }
                Text(title, style = AppType.Headline, color = AppColors.Label, textAlign = TextAlign.Center)
                if (message != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(message, style = AppType.Subhead, color = AppColors.Label, textAlign = TextAlign.Center)
                }
                if (extra != null) {
                    Spacer(Modifier.height(14.dp))
                    extra()
                }
                Spacer(Modifier.height(18.dp))
                if (actions.size == 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        actions.forEach { action ->
                            AppButton(action.title, action.onClick, style = action.style, height = 46.dp, modifier = Modifier.weight(1f))
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        actions.forEach { action ->
                            AppButton(action.title, action.onClick, style = action.style, height = 46.dp, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Context menus                                                               */
/* -------------------------------------------------------------------------- */

data class MenuAction(
    val title: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    /** Starts a new visual group (divider above). */
    val groupStart: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Glass menu that grows out of the element that opened it ([anchor], in
 * window coordinates) and dismisses back into it.
 */
@Composable
fun BoxScope.ContextMenu(
    anchor: Rect?,
    backdrop: GlassBackdrop?,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
    width: Dp = 250.dp,
    dimBackground: Boolean = true,
    header: (@Composable ColumnScope.() -> Unit)? = null,
) {
    BackHandler(enabled = anchor != null) { onDismiss() }
    val last = remember { arrayOf<Rect?>(null) }
    if (anchor != null) last[0] = anchor
    val rect = anchor ?: last[0]
    var origin by remember { mutableStateOf(Offset.Zero) }
    if (dimBackground) {
        AnimatedVisibility(anchor != null, enter = fadeIn(Motion.smooth()), exit = fadeOut(Motion.snappy()), modifier = Modifier.matchParentSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(AppColors.Scrim)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
            )
        }
    } else if (anchor != null) {
        Box(
            Modifier
                .matchParentSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
        )
    }
    if (rect == null) return
    BoxWithConstraints(Modifier.matchParentSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val density = LocalDensity.current
        val widthPx = with(density) { width.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        val local = rect.translate(-origin.x, -origin.y)
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val left = (local.center.x - widthPx / 2f).coerceIn(margin, max(margin, maxW - widthPx - margin))
        val estimatedHeight = with(density) { (actions.size * 50 + 16 + (if (header != null) 64 else 0)).dp.toPx() }
        val below = local.bottom + margin + estimatedHeight < maxH - with(density) { 32.dp.toPx() }
        val top = if (below) local.bottom + margin else max(margin, local.top - margin - estimatedHeight)
        val pivotX = ((local.center.x - left) / widthPx).coerceIn(0f, 1f)
        AnimatedVisibility(
            visible = anchor != null,
            enter = fadeIn(Motion.smooth()) + scaleIn(Motion.spring(0.35f, 0.82f), initialScale = 0.4f, transformOrigin = TransformOrigin(pivotX, if (below) 0f else 1f)),
            exit = fadeOut(Motion.snappy()) + scaleOut(Motion.snappy(), targetScale = 0.4f, transformOrigin = TransformOrigin(pivotX, if (below) 0f else 1f)),
            modifier = Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) },
        ) {
            Glass(backdrop, Modifier.width(width), cornerRadius = 22.dp, style = GlassStyle.Thick) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    if (header != null) header()
                    actions.forEachIndexed { index, action ->
                        if (action.groupStart && index > 0) {
                            Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(6.dp).background(AppColors.Separator.copy(alpha = 0.35f)))
                        }
                        MenuItem(action) {
                            onDismiss()
                            action.onClick()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(action: MenuAction, onClick: () -> Unit) {
    val press = rememberPressState()
    val color = when {
        !action.enabled -> AppColors.TertiaryLabel
        action.destructive -> AppColors.Destructive
        else -> AppColors.Label
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (press.pressed && action.enabled) AppColors.Label.copy(alpha = 0.08f) else Color.Transparent)
            .tappable(press, action.enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(action.title, style = AppType.Body, color = color, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action.icon != null) {
            Spacer(Modifier.width(12.dp))
            Icon(action.icon, null, tint = color, modifier = Modifier.size(20.dp))
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Confirmation (action sheet)                                                 */
/* -------------------------------------------------------------------------- */

/** Bottom confirmation for destructive actions: title, actions and a separate Cancel. */
@Composable
fun BoxScope.ConfirmationSheet(
    visible: Boolean,
    backdrop: GlassBackdrop?,
    title: String,
    message: String? = null,
    confirmTitle: String,
    destructive: Boolean = true,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(enabled = visible) { onCancel() }
    AnimatedVisibility(visible, enter = fadeIn(Motion.smooth()), exit = fadeOut(Motion.snappy()), modifier = Modifier.matchParentSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(AppColors.Scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onCancel() }
        )
    }
    AnimatedVisibility(
        visible,
        enter = androidx.compose.animation.slideInVertically(Motion.smooth()) { it } + fadeIn(Motion.smooth()),
        exit = androidx.compose.animation.slideOutVertically(Motion.smooth()) { it } + fadeOut(Motion.snappy()),
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(10.dp),
    ) {
        Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Glass(backdrop, Modifier.fillMaxWidth(), cornerRadius = 28.dp, style = GlassStyle.Thick) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, style = AppType.Footnote.copy(fontWeight = FontWeight.SemiBold), color = AppColors.SecondaryLabel, textAlign = TextAlign.Center)
                    if (message != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(message, style = AppType.Footnote, color = AppColors.SecondaryLabel, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(16.dp))
                    AppButton(confirmTitle, onConfirm, style = if (destructive) ButtonStyle.Destructive else ButtonStyle.Prominent, modifier = Modifier.fillMaxWidth())
                }
            }
            Glass(backdrop, Modifier.fillMaxWidth(), cornerRadius = 28.dp, style = GlassStyle.Thick) {
                AppButton("Cancel", onCancel, style = ButtonStyle.Plain, modifier = Modifier.fillMaxWidth().padding(4.dp))
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Action sheet                                                                */
/* -------------------------------------------------------------------------- */

/**
 * A group of choices about one thing (a link, an image) rising from the
 * bottom, with Cancel set apart beneath it.
 */
@Composable
fun BoxScope.ActionSheet(
    visible: Boolean,
    backdrop: GlassBackdrop?,
    title: String?,
    message: String?,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = visible) { onDismiss() }
    AnimatedVisibility(visible, enter = fadeIn(Motion.smooth()), exit = fadeOut(Motion.snappy()), modifier = Modifier.matchParentSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(AppColors.Scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
        )
    }
    AnimatedVisibility(
        visible,
        enter = androidx.compose.animation.slideInVertically(Motion.smooth()) { it } + fadeIn(Motion.smooth()),
        exit = androidx.compose.animation.slideOutVertically(Motion.smooth()) { it } + fadeOut(Motion.snappy()),
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(10.dp),
    ) {
        Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Glass(backdrop, Modifier.fillMaxWidth(), cornerRadius = 28.dp, style = GlassStyle.Thick) {
                Column {
                    if (title != null || message != null) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (title != null) {
                                Text(
                                    title, style = AppType.Footnote.copy(fontWeight = FontWeight.SemiBold), color = AppColors.SecondaryLabel,
                                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (message != null) {
                                Text(
                                    message, style = AppType.Footnote, color = AppColors.SecondaryLabel,
                                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(AppColors.Separator))
                    }
                    actions.forEachIndexed { index, action ->
                        if (index > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(AppColors.Separator))
                        ActionSheetItem(action) {
                            onDismiss()
                            action.onClick()
                        }
                    }
                }
            }
            Glass(backdrop, Modifier.fillMaxWidth(), cornerRadius = 28.dp, style = GlassStyle.Thick) {
                ActionSheetItem(MenuAction("Cancel") {}, bold = true, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun ActionSheetItem(action: MenuAction, bold: Boolean = false, onClick: () -> Unit) {
    val press = rememberPressState()
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(if (press.pressed && action.enabled) AppColors.Label.copy(alpha = 0.08f) else Color.Transparent)
            .tappable(press, action.enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            action.title,
            style = if (bold) AppType.Headline.copy(fontSize = AppType.Body.fontSize) else AppType.Body.copy(fontSize = AppType.Body.fontSize),
            color = when {
                !action.enabled -> AppColors.TertiaryLabel
                action.destructive -> AppColors.Destructive
                else -> AppColors.Accent
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
