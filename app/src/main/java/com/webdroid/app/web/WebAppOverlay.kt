package com.webdroid.app.web

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.webdroid.app.data.VideoMode
import com.webdroid.app.data.WebApp
import com.webdroid.app.ui.components.AppGlyph
import com.webdroid.app.ui.components.BadgePill
import com.webdroid.app.ui.components.ClayButton
import com.webdroid.app.ui.components.ClayButtonStyle
import com.webdroid.app.ui.components.ClayHills
import com.webdroid.app.ui.components.ClaySegmented
import com.webdroid.app.ui.components.ClaySwitchRow
import com.webdroid.app.ui.components.ClayTextField
import com.webdroid.app.ui.components.HairlineDivider
import com.webdroid.app.ui.components.SectionLabel
import com.webdroid.app.ui.components.pressScale
import com.webdroid.app.ui.components.rememberHaptics
import com.webdroid.app.ui.theme.CardColor
import com.webdroid.app.ui.theme.Clay
import com.webdroid.app.ui.theme.ClayMotion
import com.webdroid.app.ui.theme.ClayShapes
import com.webdroid.app.ui.theme.ClayType
import com.webdroid.app.util.UrlUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

interface WebAppActions {
    fun back()
    fun forward()
    fun reload()
    fun home()
    fun share()
    fun copyLink()
    fun openInBrowser()
    fun closeApp()
    fun addToHomeScreen()
    fun editApp()
    fun clearSiteData()
    fun updateSettings(transform: (com.webdroid.app.data.WebAppSettings) -> com.webdroid.app.data.WebAppSettings)
    fun openLink(url: String)
    fun openLinkExternally(url: String)
    fun copyText(text: String)
    fun shareText(text: String)
    fun download(url: String)
    fun answerPermission(allow: Boolean)
    fun answerHttpAuth(username: String?, password: String?)
    fun openNotificationSettings()
    fun setNotificationsAllowed(allowed: Boolean)
    fun answerJs(confirmed: Boolean, text: String?)
    fun retry()
    fun playOffer()
    fun dismissOffer()
    fun unlock()
    fun closePopup()
}

const val PULL_REFRESH_DP = 72
const val PULL_MENU_DP = 150

@Composable
fun WebAppOverlay(
    app: WebApp,
    icon: Bitmap?,
    state: WebAppUiState,
    actions: WebAppActions,
    playerVisible: Boolean,
    isolatedSupported: Boolean,
    lockSupported: Boolean,
    player: @Composable () -> Unit,
    popup: @Composable () -> Unit,
) {
    val cardColor = CardColor.of(app.cardColor)
    Box(Modifier.fillMaxSize()) {
        if (!playerVisible) {
            PullIndicator(state)
            if (app.settings.miniToolbar && !state.siteFullscreen) MiniToolbar(state, actions)
            VideoOfferPill(state, actions)
            ErrorView(state, actions)
        }

        Splash(app, icon, cardColor, state.splashVisible)

        // Popup windows (e.g. sign-in) slide up as a card.
        AnimatedVisibility(
            visible = state.popupOpen,
            enter = slideInVertically(ClayMotion.offsetBouncy) { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize().background(Clay.Scrim)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(top = 24.dp)
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(Clay.Canvas)
                        .navigationBarsPadding()
                        .imePadding(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Lock, null, tint = Clay.Muted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(state.popupTitle, style = ClayType.TitleSm, color = Clay.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        SmallRound(Icons.Rounded.Close, "Close") { actions.closePopup() }
                    }
                    Box(Modifier.fillMaxSize()) { popup() }
                }
            }
        }

        MenuSheet(app, icon, state, actions)
        SettingsSheet(app, state, actions, isolatedSupported, lockSupported)
        LinkSheet(state, actions)
        PermissionDialog(state, actions)
        JsDialogView(state, actions)
        HttpAuthDialog(state, actions)

        if (playerVisible) player()

        Toast(state)
        LockScreen(app, icon, cardColor, state, actions)
    }
}

/* -------------------------------------------------------------------------- */

@Composable
private fun Splash(app: WebApp, icon: Bitmap?, cardColor: CardColor, visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(0)),
        exit = fadeOut(tween(280)) + scaleOut(tween(320), targetScale = 1.08f),
    ) {
        val appear = remember { Animatable(0.6f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, ClayMotion.bouncy()) }
        Box(
            Modifier
                .fillMaxSize()
                .background(cardColor.background)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AppGlyph(
                    icon, app.name, cardColor, size = 96.dp,
                    modifier = Modifier.graphicsLayer {
                        scaleX = appear.value; scaleY = appear.value
                        alpha = ((appear.value - 0.6f) / 0.4f).coerceIn(0f, 1f)
                    },
                )
                Spacer(Modifier.height(20.dp))
                Text(app.name, style = ClayType.DisplaySm, color = cardColor.content)
                Spacer(Modifier.height(18.dp))
                LoadingDots(cardColor.content)
            }
        }
    }
}

@Composable
private fun LoadingDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(520, delayMillis = i * 150), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(7.dp).graphicsLayer { this.alpha = alpha }.clip(CircleShape).background(color))
        }
    }
}

/* -------------------------------------------------------------------------- */

@Composable
private fun BoxScope.PullIndicator(state: WebAppUiState) {
    val density = LocalDensity.current
    val refreshPx = with(density) { PULL_REFRESH_DP.dp.toPx() }
    val menuPx = with(density) { PULL_MENU_DP.dp.toPx() }
    val stage by remember {
        derivedStateOf {
            when {
                state.pullPhase == PullPhase.REFRESHING -> 3
                state.pullDistance >= menuPx -> 2
                state.pullDistance >= refreshPx -> 1
                else -> 0
            }
        }
    }
    val visible = state.pullPhase != PullPhase.IDLE || state.pullDistance > 1f
    if (!visible) return
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .graphicsLayer {
                val d = if (state.pullPhase == PullPhase.REFRESHING) refreshPx else state.pullDistance
                translationY = d * 0.72f - 20.dp.toPx()
                val p = (d / refreshPx).coerceIn(0f, 1f)
                alpha = p
                scaleX = 0.6f + 0.4f * p
                scaleY = 0.6f + 0.4f * p
            },
    ) {
        AnimatedContent(
            targetState = stage == 2,
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.8f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.8f)) },
            label = "pull-stage",
        ) { menu ->
            if (menu) {
                Row(
                    Modifier
                        .shadow(10.dp, ClayShapes.Pill)
                        .clip(ClayShapes.Pill)
                        .background(Clay.Ink)
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Menu, null, tint = Clay.OnPrimary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Release for menu", style = ClayType.Button, color = Clay.OnPrimary)
                }
            } else {
                Box(
                    Modifier
                        .size(44.dp)
                        .shadow(10.dp, CircleShape)
                        .clip(CircleShape)
                        .background(if (stage >= 1) Clay.Ink else Clay.Canvas),
                    contentAlignment = Alignment.Center,
                ) {
                    if (stage == 3) {
                        CircularProgressIndicator(color = Clay.OnPrimary, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
                    } else {
                        Icon(
                            Icons.Rounded.Refresh, "Refresh",
                            tint = if (stage >= 1) Clay.OnPrimary else Clay.Ink,
                            modifier = Modifier
                                .size(22.dp)
                                .graphicsLayer { rotationZ = (state.pullDistance / refreshPx).coerceAtMost(1.5f) * 300f },
                        )
                    }
                }
            }
        }
    }
}

/* -------------------------------------------------------------------------- */

@Composable
private fun BoxScope.MiniToolbar(state: WebAppUiState, actions: WebAppActions) {
    val haptics = rememberHaptics()
    LaunchedEffect(state.toolbarExpanded) {
        if (state.toolbarExpanded) {
            delay(6000)
            state.toolbarExpanded = false
        }
    }
    Box(Modifier.align(Alignment.TopCenter).statusBarsPadding()) {
        AnimatedContent(
            targetState = state.toolbarExpanded,
            transitionSpec = {
                (fadeIn() + slideInVertically(ClayMotion.offsetBouncy) { -it / 2 }) togetherWith
                    (fadeOut() + slideOutVertically { -it / 2 })
            },
            label = "toolbar",
        ) { expanded ->
            if (!expanded) {
                Box(
                    Modifier
                        .size(width = 72.dp, height = 22.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dy ->
                                if (dy > 6f && !state.toolbarExpanded) {
                                    haptics.tick()
                                    state.toolbarExpanded = true
                                }
                            }
                        }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            haptics.tick()
                            state.toolbarExpanded = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 38.dp, height = 5.dp)
                            .clip(ClayShapes.Pill)
                            .background(Clay.Black.copy(alpha = 0.32f))
                            .border(0.5.dp, Color.White.copy(alpha = 0.5f), ClayShapes.Pill)
                    )
                }
            } else {
                Row(
                    Modifier
                        .padding(top = 6.dp)
                        .shadow(14.dp, ClayShapes.Pill)
                        .clip(ClayShapes.Pill)
                        .background(Clay.Canvas)
                        .border(1.dp, Clay.Hairline, ClayShapes.Pill)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolbarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", state.canGoBack) { actions.back() }
                    ToolbarIcon(Icons.AutoMirrored.Rounded.ArrowForward, "Forward", state.canGoForward) { actions.forward() }
                    ToolbarIcon(Icons.Rounded.Refresh, "Reload") { actions.reload() }
                    ToolbarIcon(Icons.Rounded.Share, "Share") { actions.share() }
                    ToolbarIcon(Icons.AutoMirrored.Rounded.OpenInNew, "Open in browser") { actions.openInBrowser() }
                    ToolbarIcon(Icons.Rounded.MoreHoriz, "Menu") {
                        state.toolbarExpanded = false
                        state.menuOpen = true
                    }
                    ToolbarIcon(Icons.Rounded.SwapVert, "Hide toolbar") { state.toolbarExpanded = false }
                }
            }
        }
    }
}

@Composable
private fun ToolbarIcon(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .pressScale(interaction, 0.85f)
            .size(42.dp)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                haptics.tap()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (enabled) Clay.Ink else Clay.MutedSoft, modifier = Modifier.size(21.dp))
    }
}

/* -------------------------------------------------------------------------- */

@Composable
private fun BoxScope.VideoOfferPill(state: WebAppUiState, actions: WebAppActions) {
    val offer = state.videoOffer
    LaunchedEffect(offer) {
        if (offer != null) {
            delay(9000)
            if (state.videoOffer === offer) actions.dismissOffer()
        }
    }
    AnimatedVisibility(
        visible = offer != null,
        enter = slideInVertically(ClayMotion.offsetBouncy) { it * 2 } + fadeIn(),
        exit = slideOutVertically { it * 2 } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
    ) {
        val interaction = remember { MutableInteractionSource() }
        Row(
            Modifier
                .pressScale(interaction)
                .shadow(16.dp, ClayShapes.Pill)
                .clip(ClayShapes.Pill)
                .background(Clay.Ink)
                .clickable(interactionSource = interaction, indication = null) { actions.playOffer() }
                .padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Clay.Pink), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Clay.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text("Play in WebDroid player", style = ClayType.Button, color = Clay.OnPrimary)
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(34.dp).clip(CircleShape).clickable { actions.dismissOffer() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, "Dismiss", tint = Clay.OnPrimary.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ErrorView(state: WebAppUiState, actions: WebAppActions) {
    val error = state.error
    AnimatedVisibility(visible = error != null, enter = fadeIn(), exit = fadeOut()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Clay.Canvas)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.fillMaxWidth().height(200.dp).clip(ClayShapes.Xl)) {
                ClayHills(Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(28.dp))
            Icon(Icons.Rounded.WifiOff, null, tint = Clay.Ink, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(12.dp))
            Text("Can't reach ${UrlUtils.displayHost(error?.url)}", style = ClayType.DisplaySm, color = Clay.Ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(error?.description.orEmpty(), style = ClayType.BodyMd, color = Clay.Muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            ClayButton("Try again", onClick = { actions.retry() }, icon = Icons.Rounded.Refresh)
        }
    }
}

/* -------------------------------------------------------------------------- */

@Composable
private fun BoxScope.ClaySheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Clay.Scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(ClayMotion.offsetBouncy) { it },
        exit = slideOutVertically(tween(220)) { it },
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        val scope = rememberCoroutineScope()
        val drag = remember { Animatable(0f) }
        val dismissPx = with(LocalDensity.current) { 110.dp.toPx() }
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .offset { IntOffset(0, drag.value.roundToInt()) }
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Clay.Canvas)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta -> scope.launch { drag.snapTo((drag.value + delta).coerceAtLeast(0f)) } },
                    onDragStopped = { velocity ->
                        if (drag.value > dismissPx || velocity > 1800f) {
                            onDismiss()
                            drag.snapTo(0f)
                        } else {
                            drag.animateTo(0f, ClayMotion.bouncy())
                        }
                    },
                )
                .navigationBarsPadding()
                .heightIn(max = 720.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 8.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 40.dp, height = 5.dp)
                    .clip(ClayShapes.Pill)
                    .background(Clay.SurfaceStrong)
            )
            Column(Modifier.verticalScroll(rememberScrollState())) { content() }
        }
    }
}

@Composable
private fun SmallRound(icon: ImageVector, description: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Clay.SurfaceCard)
            .clickable { haptics.tap(); onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = Clay.Ink, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .pressScale(interaction, 0.92f)
            .clip(ClayShapes.Lg)
            .background(Clay.SurfaceCard)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) { haptics.tap(); onClick() }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (enabled) Clay.Ink else Clay.MutedSoft, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = ClayType.Caption, color = if (enabled) Clay.Ink else Clay.MutedSoft)
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, subtitle: String? = null, tint: Color = Clay.Ink, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ClayShapes.Md)
            .clickable { haptics.tap(); onClick() }
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = ClayType.TitleSm, color = tint)
            if (subtitle != null) Text(subtitle, style = ClayType.BodySm, color = Clay.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BoxScope.MenuSheet(app: WebApp, icon: Bitmap?, state: WebAppUiState, actions: WebAppActions) {
    val close = { state.menuOpen = false }
    ClaySheet(visible = state.menuOpen, onDismiss = close) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            AppGlyph(icon, app.name, CardColor.of(app.cardColor), size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(state.title.ifBlank { app.name }, style = ClayType.TitleMd, color = Clay.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(UrlUtils.displayHost(state.url.ifBlank { app.url }), style = ClayType.Caption, color = Clay.Muted, maxLines = 1)
            }
            SmallRound(Icons.Rounded.Close, "Close menu") { close() }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", state.canGoBack, { close(); actions.back() }, Modifier.weight(1f))
            QuickAction(Icons.AutoMirrored.Rounded.ArrowForward, "Forward", state.canGoForward, { close(); actions.forward() }, Modifier.weight(1f))
            QuickAction(Icons.Rounded.Refresh, "Reload", true, { close(); actions.reload() }, Modifier.weight(1f))
            QuickAction(Icons.Rounded.Home, "Home", true, { close(); actions.home() }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        MenuRow(Icons.Rounded.Share, "Share page") { close(); actions.share() }
        MenuRow(Icons.Rounded.ContentCopy, "Copy link") { close(); actions.copyLink() }
        MenuRow(Icons.AutoMirrored.Rounded.OpenInNew, "Open in browser") { close(); actions.openInBrowser() }
        HairlineDivider(Modifier.padding(vertical = 6.dp))
        ClaySwitchRow(
            "Desktop site", null, app.settings.desktopMode,
            { enabled -> actions.updateSettings { it.copy(desktopMode = enabled) } },
            icon = Icons.Rounded.DesktopWindows,
        )
        ClaySwitchRow(
            "Block ads", if (state.blockedCount > 0) "${state.blockedCount} blocked on this visit · uBlock Origin" else "uBlock Origin engine",
            app.settings.adBlock,
            { enabled -> actions.updateSettings { it.copy(adBlock = enabled) } },
            icon = Icons.Rounded.Shield,
        )
        Spacer(Modifier.height(8.dp))
        SectionLabel("Video player")
        Spacer(Modifier.height(8.dp))
        ClaySegmented(
            options = listOf(VideoMode.AUTO to "Auto", VideoMode.BUTTON to "Button", VideoMode.OFF to "Off"),
            selected = app.settings.videoMode,
            onSelect = { mode -> actions.updateSettings { it.copy(videoMode = mode) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        HairlineDivider(Modifier.padding(vertical = 6.dp))
        MenuRow(Icons.Rounded.Settings, "App settings") { state.menuOpen = false; state.settingsOpen = true }
        MenuRow(Icons.Rounded.AddToHomeScreen, "Add to home screen") { close(); actions.addToHomeScreen() }
        MenuRow(Icons.Rounded.PowerSettingsNew, "Close app", tint = Clay.Error) { close(); actions.closeApp() }
    }
}

@Composable
private fun BoxScope.SettingsSheet(
    app: WebApp,
    state: WebAppUiState,
    actions: WebAppActions,
    isolatedSupported: Boolean,
    lockSupported: Boolean,
) {
    val close = { state.settingsOpen = false }
    ClaySheet(visible = state.settingsOpen, onDismiss = close) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Text("${app.name} settings", style = ClayType.TitleLg, color = Clay.Ink, modifier = Modifier.weight(1f))
            SmallRound(Icons.Rounded.Close, "Close") { close() }
        }
        Spacer(Modifier.height(4.dp))
        ClaySwitchRow(
            "Mini toolbar", "Pull-down bar with back, reload and share",
            app.settings.miniToolbar, { v -> actions.updateSettings { it.copy(miniToolbar = v) } },
            icon = Icons.Rounded.Menu,
        )
        ClaySwitchRow(
            "Force dark mode", "Darken sites that have no dark theme",
            app.settings.forceDark, { v -> actions.updateSettings { it.copy(forceDark = v) } },
            icon = Icons.Rounded.DarkMode,
        )
        ClaySwitchRow(
            "Open other sites in browser", "Links to other websites leave the app",
            app.settings.openLinksExternally, { v -> actions.updateSettings { it.copy(openLinksExternally = v) } },
            icon = Icons.AutoMirrored.Rounded.OpenInNew,
        )
        ClaySwitchRow(
            "Separate login",
            if (isolatedSupported) "Own cookies and storage · applies on next launch" else "Needs a newer Android System WebView",
            app.settings.isolatedSession, { v -> actions.updateSettings { it.copy(isolatedSession = v) } },
            icon = Icons.Rounded.Security, enabled = isolatedSupported,
        )
        ClaySwitchRow(
            "Fingerprint lock", if (lockSupported) "Unlock with fingerprint or screen lock" else "Set up a screen lock first",
            app.settings.appLock, { v -> actions.updateSettings { it.copy(appLock = v) } },
            icon = Icons.Rounded.Fingerprint, enabled = lockSupported,
        )
        HairlineDivider(Modifier.padding(vertical = 6.dp))
        val notifyGranted = app.settings.grantedPermissions.any { it.startsWith("notify:") }
        val notifyBlocked = !notifyGranted && app.settings.grantedPermissions.any { it.startsWith("notify-block:") }
        ClaySwitchRow(
            "Notifications",
            when {
                notifyGranted -> "Allowed · the site can notify you"
                notifyBlocked -> "Blocked"
                else -> "The site hasn't asked yet"
            },
            notifyGranted, { v -> actions.setNotificationsAllowed(v) },
            icon = Icons.Rounded.Notifications,
        )
        ClaySwitchRow(
            "Stay connected in background",
            "Keeps the app running so chats can notify you when it's closed. Shows a quiet status notification.",
            app.settings.keepAlive, { v -> actions.updateSettings { it.copy(keepAlive = v) } },
            icon = Icons.Rounded.Wifi,
        )
        MenuRow(Icons.Rounded.Settings, "Sound & notification style") { close(); actions.openNotificationSettings() }
        HairlineDivider(Modifier.padding(vertical = 6.dp))
        MenuRow(Icons.Rounded.Edit, "Edit name, icon and colour") { close(); actions.editApp() }
        MenuRow(Icons.Rounded.DeleteSweep, "Clear site data", "Signs you out of ${UrlUtils.displayHost(app.url)}", tint = Clay.Error) {
            close(); actions.clearSiteData()
        }
    }
}

@Composable
private fun BoxScope.LinkSheet(state: WebAppUiState, actions: WebAppActions) {
    val menu = state.linkMenu
    val close = { state.linkMenu = null }
    ClaySheet(visible = menu != null, onDismiss = close) {
        val current = menu ?: return@ClaySheet
        Text(
            current.title?.takeIf { it.isNotBlank() } ?: UrlUtils.displayHost(current.url),
            style = ClayType.TitleMd, color = Clay.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(current.url, style = ClayType.Caption, color = Clay.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        MenuRow(Icons.Rounded.Link, "Open link") { close(); actions.openLink(current.url) }
        MenuRow(Icons.AutoMirrored.Rounded.OpenInNew, "Open in browser") { close(); actions.openLinkExternally(current.url) }
        MenuRow(Icons.Rounded.ContentCopy, "Copy link") { close(); actions.copyText(current.url) }
        MenuRow(Icons.Rounded.Share, "Share link") { close(); actions.shareText(current.url) }
        if (current.imageUrl != null) {
            MenuRow(Icons.Rounded.Download, "Save image") { close(); actions.download(current.imageUrl) }
        } else {
            MenuRow(Icons.Rounded.Download, "Download link") { close(); actions.download(current.url) }
        }
    }
}

@Composable
private fun DialogCard(content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Clay.Scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(28.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(ClayShapes.Xl)
                .background(Clay.Canvas)
                .padding(24.dp),
            content = content,
        )
    }
}

@Composable
private fun PermissionDialog(state: WebAppUiState, actions: WebAppActions) {
    val prompt = state.permissionPrompt
    AnimatedVisibility(visible = prompt != null, enter = fadeIn() + scaleIn(initialScale = 0.94f), exit = fadeOut()) {
        val current = state.permissionPrompt ?: return@AnimatedVisibility
        DialogCard {
            val (icon, what) = when (current) {
                is PermissionPrompt.Location -> Icons.Rounded.LocationOn to "wants to use your location"
                is PermissionPrompt.Notifications -> Icons.Rounded.NotificationsActive to "wants to send you notifications"
                is PermissionPrompt.Media -> Icons.Rounded.CameraAlt to when {
                    current.camera && current.mic -> "wants to use your camera and microphone"
                    current.camera -> "wants to use your camera"
                    else -> "wants to use your microphone"
                }
            }
            Box(Modifier.size(52.dp).clip(ClayShapes.Lg).background(Clay.Lavender), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Clay.Black, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("${UrlUtils.displayHost(current.origin)} $what", style = ClayType.TitleLg, color = Clay.Ink)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ClayButton("Block", onClick = { actions.answerPermission(false) }, style = ClayButtonStyle.Secondary, modifier = Modifier.weight(1f))
                ClayButton("Allow", onClick = { actions.answerPermission(true) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun JsDialogView(state: WebAppUiState, actions: WebAppActions) {
    val dialog = state.jsDialog
    AnimatedVisibility(visible = dialog != null, enter = fadeIn() + scaleIn(initialScale = 0.94f), exit = fadeOut()) {
        val current = state.jsDialog ?: return@AnimatedVisibility
        var text by remember(current) { mutableStateOf((current as? JsDialog.Prompt)?.defaultValue.orEmpty()) }
        DialogCard {
            BadgePill(UrlUtils.displayHost(state.url))
            Spacer(Modifier.height(14.dp))
            Text(current.message, style = ClayType.BodyMd, color = Clay.Ink, modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()))
            if (current is JsDialog.Prompt) {
                Spacer(Modifier.height(14.dp))
                ClayTextField(value = text, onValueChange = { text = it }, keyboardOptions = KeyboardOptions.Default)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (current !is JsDialog.Alert) {
                    ClayButton("Cancel", onClick = { actions.answerJs(false, null) }, style = ClayButtonStyle.Secondary, modifier = Modifier.weight(1f))
                }
                ClayButton("OK", onClick = { actions.answerJs(true, text) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HttpAuthDialog(state: WebAppUiState, actions: WebAppActions) {
    val prompt = state.httpAuth
    AnimatedVisibility(visible = prompt != null, enter = fadeIn() + scaleIn(initialScale = 0.94f), exit = fadeOut()) {
        val current = state.httpAuth ?: return@AnimatedVisibility
        var user by remember(current) { mutableStateOf("") }
        var pass by remember(current) { mutableStateOf("") }
        DialogCard {
            Box(Modifier.size(52.dp).clip(ClayShapes.Lg).background(Clay.Peach), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Lock, null, tint = Clay.Black, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("Sign in to ${current.host}", style = ClayType.TitleLg, color = Clay.Ink)
            if (current.realm.isNotBlank()) {
                Text(current.realm, style = ClayType.BodySm, color = Clay.Muted)
            }
            Spacer(Modifier.height(16.dp))
            ClayTextField(value = user, onValueChange = { user = it }, placeholder = "Username")
            Spacer(Modifier.height(10.dp))
            ClayTextField(
                value = pass, onValueChange = { pass = it }, placeholder = "Password",
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ClayButton("Cancel", onClick = { actions.answerHttpAuth(null, null) }, style = ClayButtonStyle.Secondary, modifier = Modifier.weight(1f))
                ClayButton("Sign in", onClick = { actions.answerHttpAuth(user, pass) }, enabled = user.isNotBlank(), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BoxScope.Toast(state: WebAppUiState) {
    val lastText = remember { mutableStateOf("") }
    LaunchedEffect(state.toastSerial) {
        val message = state.toast ?: return@LaunchedEffect
        lastText.value = message
        delay(2400)
        state.toast = null
    }
    AnimatedVisibility(
        visible = state.toast != null,
        enter = slideInVertically(ClayMotion.offsetBouncy) { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp, start = 24.dp, end = 24.dp),
    ) {
        Text(
            state.toast ?: lastText.value,
            style = ClayType.Button,
            color = Clay.OnPrimary,
            modifier = Modifier
                .shadow(12.dp, ClayShapes.Pill)
                .clip(ClayShapes.Pill)
                .background(Clay.Ink)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun LockScreen(app: WebApp, icon: Bitmap?, cardColor: CardColor, state: WebAppUiState, actions: WebAppActions) {
    AnimatedVisibility(visible = state.locked, enter = fadeIn(tween(0)), exit = fadeOut(tween(260)) + scaleOut(targetScale = 1.05f)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(cardColor.background)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.unlock() },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AppGlyph(icon, app.name, cardColor, size = 88.dp)
                Spacer(Modifier.height(20.dp))
                Text(app.name, style = ClayType.DisplaySm, color = cardColor.content)
                Spacer(Modifier.height(6.dp))
                Text("Locked", style = ClayType.BodyMd, color = cardColor.contentSoft)
                Spacer(Modifier.height(32.dp))
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(Clay.Canvas).clickable { actions.unlock() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Fingerprint, "Unlock", tint = Clay.Ink, modifier = Modifier.size(36.dp))
                }
            }
        }
    }
}

@Suppress("unused")
private val BlockIcon = Icons.Rounded.Block
