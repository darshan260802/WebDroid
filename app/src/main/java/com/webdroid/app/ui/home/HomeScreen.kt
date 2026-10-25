package com.webdroid.app.ui.home

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.webdroid.app.adblock.AdblockEngine
import com.webdroid.app.data.WebApp
import com.webdroid.app.ui.components.AppGlyph
import com.webdroid.app.ui.components.BadgePill
import com.webdroid.app.ui.components.ClayButton
import com.webdroid.app.ui.components.ClayButtonStyle
import com.webdroid.app.ui.components.ClayHills
import com.webdroid.app.ui.components.ClayIconButton
import com.webdroid.app.ui.components.ClayTextField
import com.webdroid.app.ui.components.SectionLabel
import com.webdroid.app.ui.components.pressScale
import com.webdroid.app.ui.components.rememberHaptics
import com.webdroid.app.ui.theme.CardColor
import com.webdroid.app.ui.theme.Clay
import com.webdroid.app.ui.theme.ClayMotion
import com.webdroid.app.ui.theme.ClayShapes
import com.webdroid.app.ui.theme.ClayType
import com.webdroid.app.ui.update.UpdateActions
import com.webdroid.app.ui.update.UpdateCard
import com.webdroid.app.ui.update.info
import com.webdroid.app.update.UpdateState
import kotlinx.coroutines.delay

data class HomeCallbacks(
    val onOpen: (WebApp, Rect?) -> Unit,
    val onCreate: () -> Unit,
    val onCreateFrom: (String) -> Unit,
    val onEdit: (WebApp) -> Unit,
    val onSettings: () -> Unit,
    val onToggleTheme: () -> Unit,
    val onPin: (WebApp) -> Unit,
    val onDelete: (WebApp) -> Unit,
)

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    apps: List<WebApp>,
    icons: Map<String, Bitmap?>,
    adblockStatus: AdblockEngine.Status,
    adblockEnabled: Boolean,
    update: UpdateState,
    updateActions: UpdateActions,
    callbacks: HomeCallbacks,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
) {
    var query by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<WebApp?>(null) }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter {
            it.name.contains(query, ignoreCase = true) || it.url.contains(query, ignoreCase = true)
        }
    }
    val gridState = rememberLazyGridState()
    val statusPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(Clay.Canvas)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 158.dp),
            state = gridState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = statusPad + 12.dp, bottom = navPad + 120.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Header(adblockStatus, adblockEnabled, apps.size, callbacks.onSettings, callbacks.onToggleTheme, update, updateActions)
            }
            if (apps.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    EmptyState(callbacks.onCreate, callbacks.onCreateFrom)
                }
            } else {
                if (apps.size >= 6) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "search") {
                        ClayTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = "Search your apps",
                            leading = Icons.Rounded.Search,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "label") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        SectionLabel("Your apps")
                        Spacer(Modifier.width(10.dp))
                        BadgePill("${apps.size}")
                    }
                }
                items(filtered, key = { it.id }) { app ->
                    val index = apps.indexOf(app)
                    AppCard(
                        app = app,
                        icon = icons[app.id],
                        index = index,
                        onClick = { bounds -> callbacks.onOpen(app, bounds) },
                        onLongClick = { menuFor = app },
                        modifier = Modifier.animateItem(),
                        sharedScope = sharedScope,
                        animatedScope = animatedScope,
                    )
                }
            }
        }

        // Primary call to action
        AnimatedVisibility(
            visible = apps.isNotEmpty(),
            enter = slideInVertically(ClayMotion.offsetBouncy) { it * 2 } + fadeIn(),
            exit = slideOutVertically { it * 2 } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 22.dp),
        ) {
            with(sharedScope) {
                ClayButton(
                    text = "New app",
                    onClick = callbacks.onCreate,
                    icon = Icons.Rounded.Add,
                    modifier = Modifier
                        .sharedBounds(
                            rememberSharedContentState("editor-new"),
                            animatedVisibilityScope = animatedScope,
                            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                        )
                        .width(200.dp),
                    height = 56.dp,
                )
            }
        }

        AppActionsSheet(
            app = menuFor,
            icon = menuFor?.let { icons[it.id] },
            onDismiss = { menuFor = null },
            onOpen = { app -> menuFor = null; callbacks.onOpen(app, null) },
            onEdit = { app -> menuFor = null; callbacks.onEdit(app) },
            onPin = { app -> menuFor = null; callbacks.onPin(app) },
            onDelete = { app -> menuFor = null; callbacks.onDelete(app) },
        )
    }
}

@Composable
private fun Header(
    status: AdblockEngine.Status,
    adblockEnabled: Boolean,
    count: Int,
    onSettings: () -> Unit,
    onToggleTheme: () -> Unit,
    update: UpdateState,
    updateActions: UpdateActions,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("WebDroid", style = ClayType.DisplayLg, color = Clay.Ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (count == 0) "Any website, as an app." else "Tap an app to open it. Hold for options.",
                    style = ClayType.BodyMd, color = Clay.Muted,
                )
            }
            ThemeToggle(onToggleTheme)
            Spacer(Modifier.width(10.dp))
            ClayIconButton(Icons.Rounded.Settings, "Settings", onSettings)
        }
        Spacer(Modifier.height(14.dp))
        val (label, color) = when {
            !adblockEnabled -> "Ad blocker off" to Clay.SurfaceCard
            status.state == AdblockEngine.State.READY -> "uBlock Origin · ${formatCount(status.networkFilters)} filters" to Clay.Mint
            status.state == AdblockEngine.State.FAILED -> "Ad blocker unavailable" to Clay.Peach
            else -> "Ad blocker starting…" to Clay.SurfaceCard
        }
        Row(
            Modifier.clip(ClayShapes.Pill).background(color).padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val content = if (color == Clay.SurfaceCard) Clay.Ink else Clay.Black
            Icon(Icons.Rounded.Shield, null, tint = content, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = ClayType.Caption, color = content)
        }
        // Keep the last update shown while the card animates away.
        val shownUpdate = remember { LastValue(update) }
        if (update.info != null) shownUpdate.value = update
        AnimatedVisibility(
            visible = update.info != null,
            enter = expandVertically(ClayMotion.bouncy()) + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            UpdateCard(shownUpdate.value, updateActions, Modifier.padding(top = 14.dp))
        }
    }
}

private class LastValue<T>(var value: T)

/** Sun/moon button: flips WebDroid between light and dark. */
@Composable
private fun ThemeToggle(onToggle: () -> Unit) {
    val dark = Clay.isDark
    val turn by animateFloatAsState(if (dark) 180f else 0f, ClayMotion.bouncy(), label = "theme-turn")
    ClayIconButton(
        if (dark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
        if (dark) "Switch to light mode" else "Switch to dark mode",
        onToggle,
        modifier = Modifier.graphicsLayer { rotationZ = turn },
    )
}

fun formatCount(n: Int): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000f)
    n >= 1_000 -> "${n / 1000}k"
    else -> n.toString()
}

private val SUGGESTIONS = listOf(
    "YouTube" to "https://m.youtube.com",
    "Instagram" to "https://www.instagram.com",
    "X" to "https://x.com",
    "Reddit" to "https://www.reddit.com",
    "ChatGPT" to "https://chatgpt.com",
    "WhatsApp" to "https://web.whatsapp.com",
)

@Composable
private fun EmptyState(onCreate: () -> Unit, onCreateFrom: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(ClayShapes.Xl)
            .background(Clay.SurfaceSoft)
    ) {
        ClayHills(Modifier.fillMaxWidth().aspectRatio(1.35f))
        Column(Modifier.padding(24.dp)) {
            Text("Turn any website into an app", style = ClayType.DisplaySm, color = Clay.Ink)
            Spacer(Modifier.height(10.dp))
            Text(
                "Paste a link and WebDroid gives it its own icon, its own window in Recents, a built-in uBlock Origin ad blocker and a proper video player. No address bar, no clutter.",
                style = ClayType.BodyMd, color = Clay.Body,
            )
            Spacer(Modifier.height(22.dp))
            ClayButton("Create your first app", onClick = onCreate, icon = Icons.Rounded.Add, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            SectionLabel("Or start with")
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val haptics = rememberHaptics()
                SUGGESTIONS.forEachIndexed { i, (label, url) ->
                    val color = CardColor.of(i)
                    Text(
                        label,
                        style = ClayType.NavLink,
                        color = color.content,
                        modifier = Modifier
                            .clip(ClayShapes.Pill)
                            .background(color.background)
                            .clickable { haptics.tap(); onCreateFrom(url) }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
private fun AppCard(
    app: WebApp,
    icon: Bitmap?,
    index: Int,
    onClick: (Rect?) -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
) {
    val colors = CardColor.of(app.cardColor)
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    var bounds by remember { mutableStateOf<Rect?>(null) }

    // Staggered entrance on first composition.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(10) * 45).toLong())
        appear.animateTo(1f, ClayMotion.bouncy())
    }

    with(sharedScope) {
        Column(
            modifier
                .graphicsLayer {
                    alpha = appear.value.coerceIn(0f, 1f)
                    translationY = (1f - appear.value) * 48.dp.toPx()
                    val s = 0.92f + 0.08f * appear.value
                    scaleX = s; scaleY = s
                }
                .pressScale(interaction, 0.95f)
                .sharedBounds(
                    rememberSharedContentState("card-${app.id}"),
                    animatedVisibilityScope = animatedScope,
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                )
                .onGloballyPositioned { bounds = it.boundsInWindow() }
                .clip(ClayShapes.Xl)
                .background(colors.background)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = {
                        haptics.tap()
                        onClick(bounds)
                    },
                    onLongClick = {
                        haptics.longPress()
                        onLongClick()
                    },
                )
                .padding(18.dp)
                .height(132.dp),
        ) {
            AppGlyph(icon, app.name, colors, size = 54.dp)
            Spacer(Modifier.weight(1f))
            Text(app.name, style = ClayType.TitleMd, color = colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.host, style = ClayType.Caption, color = colors.contentSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AppActionsSheet(
    app: WebApp?,
    icon: Bitmap?,
    onDismiss: () -> Unit,
    onOpen: (WebApp) -> Unit,
    onEdit: (WebApp) -> Unit,
    onPin: (WebApp) -> Unit,
    onDelete: (WebApp) -> Unit,
) {
    var confirmDelete by remember(app) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = app != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Clay.Scrim)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
            )
        }
        // Keeps the sheet's content while it animates out.
        val lastShown = remember { arrayOfNulls<WebApp>(1) }
        if (app != null) lastShown[0] = app
        AnimatedVisibility(
            visible = app != null,
            enter = slideInVertically(ClayMotion.offsetBouncy) { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val current = lastShown[0] ?: return@AnimatedVisibility
            val colors = CardColor.of(current.cardColor)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(Clay.Canvas)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .navigationBarsPadding()
                    .padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(64.dp).clip(ClayShapes.Lg).background(colors.background), contentAlignment = Alignment.Center) {
                        AppGlyph(icon, current.name, colors, size = 44.dp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(current.name, style = ClayType.TitleLg, color = Clay.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(current.host, style = ClayType.Caption, color = Clay.Muted)
                    }
                    ClayIconButton(Icons.Rounded.Close, "Close", onDismiss, size = 40.dp, iconSize = 20.dp)
                }
                Spacer(Modifier.height(18.dp))
                if (!confirmDelete) {
                    ActionRow(Icons.Rounded.OpenInNew, "Open") { onOpen(current) }
                    ActionRow(Icons.Rounded.Edit, "Edit app") { onEdit(current) }
                    ActionRow(Icons.Rounded.AddToHomeScreen, "Add to home screen") { onPin(current) }
                    ActionRow(Icons.Rounded.Delete, "Delete", tint = Clay.Error) { confirmDelete = true }
                } else {
                    Text("Delete ${current.name}?", style = ClayType.TitleMd, color = Clay.Ink)
                    Spacer(Modifier.height(6.dp))
                    Text("Its logins and data will be removed from WebDroid.", style = ClayType.BodySm, color = Clay.Muted)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ClayButton("Keep", onClick = { confirmDelete = false }, style = ClayButtonStyle.Secondary, modifier = Modifier.weight(1f))
                        ClayButton("Delete", onClick = { onDelete(current) }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: androidx.compose.ui.graphics.Color = Clay.Ink, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ClayShapes.Md)
            .clickable { haptics.tap(); onClick() }
            .padding(vertical = 14.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Clay.SurfaceCard), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, style = ClayType.TitleSm, color = tint)
    }
}
