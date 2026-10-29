package com.webdroid.app.ui.apps

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.zIndex
import com.webdroid.app.data.WebApp
import com.webdroid.app.ui.components.AppButton
import com.webdroid.app.ui.components.AppIcon
import com.webdroid.app.ui.components.AppTextField
import com.webdroid.app.ui.components.ButtonStyle
import com.webdroid.app.ui.components.ConfirmationSheet
import com.webdroid.app.ui.components.ContextMenu
import com.webdroid.app.ui.components.GlassIconButton
import com.webdroid.app.ui.components.LargeTitle
import com.webdroid.app.ui.components.MenuAction
import com.webdroid.app.ui.components.Overlay
import com.webdroid.app.ui.components.rememberSnapshotBackdrop
import com.webdroid.app.ui.components.TabBarClearance
import com.webdroid.app.ui.components.TopChrome
import com.webdroid.app.ui.components.TrackScrollAll
import com.webdroid.app.ui.components.rememberCollapse
import com.webdroid.app.ui.components.rememberHaptics
import com.webdroid.app.ui.components.rememberPressState
import com.webdroid.app.ui.components.tappable
import com.webdroid.app.ui.components.topBarHeight
import com.webdroid.app.ui.glass.glassSource
import com.webdroid.app.ui.glass.rememberGlassBackdrop
import com.webdroid.app.ui.theme.AppColors
import com.webdroid.app.ui.theme.AppType
import com.webdroid.app.ui.theme.Motion
import com.webdroid.app.ui.theme.Spacing
import com.webdroid.app.ui.theme.TileColor
import com.webdroid.app.ui.update.UpdateActions
import com.webdroid.app.ui.update.UpdateBanner
import com.webdroid.app.ui.update.info
import com.webdroid.app.update.UpdateState
import kotlinx.coroutines.launch

data class AppsCallbacks(
    val onOpen: (WebApp, Rect?) -> Unit,
    val onAdd: () -> Unit,
    val onAddFrom: (String) -> Unit,
    val onEdit: (WebApp) -> Unit,
    val onPin: (WebApp) -> Unit,
    val onDelete: (WebApp) -> Unit,
    val onReorder: (List<String>) -> Unit,
    val onToggleTheme: () -> Unit,
)

private val SUGGESTIONS = listOf(
    "YouTube" to "https://m.youtube.com",
    "Instagram" to "https://www.instagram.com",
    "X" to "https://x.com",
    "Reddit" to "https://www.reddit.com",
    "ChatGPT" to "https://chatgpt.com",
    "WhatsApp" to "https://web.whatsapp.com",
    "Spotify" to "https://open.spotify.com",
    "Wikipedia" to "https://www.wikipedia.org",
)

/**
 * The Apps tab: a home-screen grid of web apps. Tap to open; touch and hold
 * to lift an app and show its menu; keep holding and drag to rearrange.
 */
@Composable
fun AppsScreen(
    apps: List<WebApp>,
    icons: Map<String, Bitmap?>,
    update: UpdateState,
    updateActions: UpdateActions,
    callbacks: AppsCallbacks,
) {
    val backdrop = rememberGlassBackdrop()
    val grid = rememberLazyGridState()
    val collapse by rememberCollapse(grid)
    TrackScrollAll(backdrop, grid)
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<Pair<WebApp, Rect>?>(null) }
    var confirmDelete by remember { mutableStateOf<WebApp?>(null) }

    // Local order while rearranging; follows the repository otherwise.
    var order by remember { mutableStateOf(apps.map { it.id }) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    if (draggingId == null) {
        val fresh = apps.map { it.id }
        if (fresh != order) order = fresh
    }
    val byId = apps.associateBy { it.id }
    val visible = if (query.isBlank()) order.mapNotNull { byId[it] } else apps.filter {
        it.name.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true)
    }
    val lift = remember { Animatable(1f) }

    val top = topBarHeight()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + TabBarClearance

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().glassSource(backdrop).background(AppColors.Background)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 82.dp),
                state = grid,
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = top, bottom = bottom + 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "title") {
                    LargeTitle("Apps", horizontalPadding = 12.dp)
                }
                if (update.info != null) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "update") {
                        UpdateBanner(update, updateActions, Modifier.padding(horizontal = 6.dp))
                    }
                }
                if (apps.size >= 9) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "search") {
                        AppTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = "Search",
                            leading = Icons.Rounded.Search,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                        )
                    }
                }
                if (apps.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        EmptyState(callbacks.onAdd, callbacks.onAddFrom)
                    }
                } else if (visible.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "no-results") {
                        Text(
                            "No apps match “$query”",
                            style = AppType.Body,
                            color = AppColors.SecondaryLabel,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                        )
                    }
                }
                items(visible, key = { it.id }) { app ->
                    val isDragging = app.id == draggingId
                    AppTile(
                        app = app,
                        icon = icons[app.id],
                        modifier = if (isDragging) {
                            Modifier
                                .zIndex(10f)
                                .graphicsLayer {
                                    translationX = dragOffset.x
                                    translationY = dragOffset.y
                                    scaleX = lift.value
                                    scaleY = lift.value
                                }
                        } else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = Motion.smooth(IntOffset(1, 1))),
                        dimmed = draggingId != null && !isDragging,
                        onTap = { bounds -> callbacks.onOpen(app, bounds) },
                        onLongPress = { bounds ->
                            haptics.longPress()
                            menuFor = app to bounds
                        },
                        onDragStart = {
                            if (query.isBlank()) {
                                menuFor = null
                                draggingId = app.id
                                dragOffset = Offset.Zero
                                scope.launch { lift.animateTo(1.12f, Motion.smooth()) }
                            }
                        },
                        onDrag = { delta ->
                            if (draggingId == app.id) {
                                dragOffset += delta
                                val info = grid.layoutInfo.visibleItemsInfo
                                val current = info.firstOrNull { it.key == app.id }
                                if (current != null) {
                                    val center = current.offset.toOffset() + Offset(current.size.width / 2f, current.size.height / 2f) + dragOffset
                                    val target = info.firstOrNull { item ->
                                        item.key is String && item.key != app.id && order.contains(item.key) &&
                                            center.x >= item.offset.x && center.x <= item.offset.x + item.size.width &&
                                            center.y >= item.offset.y && center.y <= item.offset.y + item.size.height
                                    }
                                    if (target != null) {
                                        val from = order.indexOf(app.id)
                                        val to = order.indexOf(target.key as String)
                                        order = order.toMutableList().apply { add(to, removeAt(from)) }
                                        // The tile jumps to its new slot; keep it under the finger.
                                        dragOffset += (current.offset - target.offset).toOffset()
                                        haptics.tick()
                                    }
                                }
                            }
                        },
                        onDragEnd = {
                            if (draggingId == app.id) {
                                val finalOrder = order
                                scope.launch {
                                    lift.animateTo(1f, Motion.smooth())
                                }
                                draggingId = null
                                dragOffset = Offset.Zero
                                callbacks.onReorder(finalOrder)
                            }
                        },
                    )
                }
            }
        }

        TopChrome(
            backdrop = backdrop,
            title = "Apps",
            collapse = collapse,
            leading = {
                GlassIconButton(
                    backdrop,
                    if (AppColors.isDark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                    if (AppColors.isDark) "Switch to Light Appearance" else "Switch to Dark Appearance",
                    callbacks.onToggleTheme,
                )
            },
            trailing = {
                GlassIconButton(backdrop, Icons.Rounded.Add, "Add App", callbacks.onAdd, prominent = true)
            },
        )

        // Menus and confirmations sit above the tab bar, on glass made from a
        // snapshot of the screen as they open.
        Overlay {
            val menuBackdrop = rememberSnapshotBackdrop(menuFor != null || confirmDelete != null)
            ContextMenu(
                anchor = menuFor?.second,
                backdrop = menuBackdrop,
                onDismiss = { menuFor = null },
                actions = menuFor?.first?.let { app ->
                    listOf(
                        MenuAction("Open", Icons.AutoMirrored.Rounded.OpenInNew) { callbacks.onOpen(app, null) },
                        MenuAction("Edit App", Icons.Rounded.Edit) { callbacks.onEdit(app) },
                        MenuAction("Add to Home Screen", Icons.Rounded.AddToHomeScreen) { callbacks.onPin(app) },
                        MenuAction("Delete App", Icons.Rounded.Delete, destructive = true, groupStart = true) { confirmDelete = app },
                    )
                } ?: emptyList(),
            )
            // Lifted icon over the dimmed screen while its menu is open.
            val lifted = menuFor
            AnimatedVisibility(lifted != null, enter = fadeIn(Motion.snappy()), exit = fadeOut(Motion.snappy())) {
                val shown = remember { arrayOf<Pair<WebApp, Rect>?>(null) }
                if (lifted != null) shown[0] = lifted
                val (app, rect) = shown[0] ?: return@AnimatedVisibility
                LiftedIcon(app, icons[app.id], rect)
            }
            ConfirmationSheet(
                visible = confirmDelete != null,
                backdrop = menuBackdrop,
                title = "Delete “${confirmDelete?.name.orEmpty()}”?",
                message = "Its sign-ins and site data are removed from WebDroid.",
                confirmTitle = "Delete App",
                onConfirm = {
                    confirmDelete?.let(callbacks.onDelete)
                    confirmDelete = null
                },
                onCancel = { confirmDelete = null },
            )
        }
    }
}

@Composable
private fun LiftedIcon(app: WebApp, icon: Bitmap?, rect: Rect) {
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val grow by animateFloatAsState(1.08f, Motion.spring(0.3f, 0.75f), label = "lift")
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInWindow().topLeft }) {
        val size = with(density) { rect.width.toDp() }
        Column(
            Modifier
                .offset { IntOffset((rect.left - origin.x).toInt(), (rect.top - origin.y).toInt()) }
                .width(size)
                .graphicsLayer { scaleX = grow; scaleY = grow },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(icon, app.name, TileColor.of(app.cardColor), 62.dp, Modifier.padding(top = 4.dp).shadow(16.dp, RoundedCornerShape(14.dp)))
        }
    }
}

/**
 * One app on the grid. Highlights on touch-down; a tap opens it; holding past
 * the long-press timeout lifts it (menu); moving after that drags it.
 */
@Composable
private fun AppTile(
    app: WebApp,
    icon: Bitmap?,
    modifier: Modifier,
    dimmed: Boolean,
    onTap: (Rect?) -> Unit,
    onLongPress: (Rect) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    val press = rememberPressState()
    var iconBounds by remember { mutableStateOf<Rect?>(null) }
    var tileBounds by remember { mutableStateOf<Rect?>(null) }
    val scale by animateFloatAsState(if (press.pressed) 0.9f else 1f, Motion.snappy(), label = "tile-press")
    val alpha by animateFloatAsState(if (dimmed) 0.55f else 1f, Motion.smooth(), label = "tile-dim")
    Column(
        modifier
            .onGloballyPositioned { tileBounds = it.boundsInWindow() }
            .graphicsLayer { this.alpha = alpha }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = "Open") { onTap(iconBounds); true }
                customActions = listOf(
                    CustomAccessibilityAction("Show options") { tileBounds?.let(onLongPress); true },
                )
            }
            .pointerInput(app.id) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    press.pressed = true
                    var tapped = false
                    var cancelled = false
                    val longPressed = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                cancelled = true
                                break
                            }
                            if (change.changedToUp()) {
                                tapped = true
                                break
                            }
                            if (change.isConsumed || (change.position - down.position).getDistance() > slop) {
                                cancelled = true
                                break
                            }
                        }
                    } == null
                    press.pressed = false
                    when {
                        tapped -> onTap(iconBounds)
                        cancelled || !longPressed -> Unit
                        else -> {
                            iconBounds?.let(onLongPress)
                            var dragging = false
                            var travelled = Offset.Zero
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val delta = change.positionChange()
                                travelled += delta
                                if (!dragging && travelled.getDistance() > slop) {
                                    dragging = true
                                    onDragStart()
                                }
                                if (dragging) {
                                    change.consume()
                                    onDrag(delta)
                                }
                            }
                            if (dragging) onDragEnd()
                        }
                    }
                }
            }
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(
            icon, app.name, TileColor.of(app.cardColor), 62.dp,
            Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .onGloballyPositioned { iconBounds = it.boundsInWindow() },
        )
        Spacer(Modifier.height(7.dp))
        Text(
            app.name,
            style = AppType.Caption1,
            color = AppColors.Label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** First run: what WebDroid does, one clear action, and quick starts. */
@Composable
private fun EmptyState(onAdd: () -> Unit, onAddFrom: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.Edge, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(84.dp).clip(RoundedCornerShape(20.dp)).background(AppColors.Accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Apps, null, tint = AppColors.Accent, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("No Apps Yet", style = AppType.Title2, color = AppColors.Label, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Add any website and it opens like an app: full screen, ad-free, with its own icon and its own window in Recents.",
            style = AppType.Body,
            color = AppColors.SecondaryLabel,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        AppButton("Add a Website", onAdd, icon = Icons.Rounded.Add)
        Spacer(Modifier.height(36.dp))
        Text(
            "Or start with one of these",
            style = AppType.SectionHeader,
            color = AppColors.SecondaryLabel,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 12.dp),
        )
        val rows = SUGGESTIONS.chunked(4)
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                row.forEach { (label, url) ->
                    val press = rememberPressState()
                    val scale by animateFloatAsState(if (press.pressed) 0.9f else 1f, Motion.snappy(), label = "suggest")
                    Column(
                        Modifier
                            .weight(1f)
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                            .tappable(press) { onAddFrom(url) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AppIcon(null, label, TileColor.of(SUGGESTIONS.indexOf(label to url)), 56.dp)
                        Spacer(Modifier.height(6.dp))
                        Text(label, style = AppType.Caption1, color = AppColors.Label, maxLines = 1)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        AppButton("Add Other Website", onAdd, style = ButtonStyle.Plain)
    }
}
