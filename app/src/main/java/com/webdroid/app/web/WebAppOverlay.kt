package com.webdroid.app.web

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewDay
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.Wifi
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.webdroid.app.data.VideoMode
import com.webdroid.app.data.WebApp
import com.webdroid.app.ui.components.ActionRow
import com.webdroid.app.ui.components.ActionSheet
import com.webdroid.app.ui.components.AlertAction
import com.webdroid.app.ui.components.AppButton
import com.webdroid.app.ui.components.AppIcon
import com.webdroid.app.ui.components.AppTextField
import com.webdroid.app.ui.components.ButtonStyle
import com.webdroid.app.ui.components.GlassAlert
import com.webdroid.app.ui.components.GlassToast
import com.webdroid.app.ui.components.ListRow
import com.webdroid.app.ui.components.ListSection
import com.webdroid.app.ui.components.MenuAction
import com.webdroid.app.ui.components.ModalSheet
import com.webdroid.app.ui.components.SectionGap
import com.webdroid.app.ui.components.SegmentedControl
import com.webdroid.app.ui.components.SheetSize
import com.webdroid.app.ui.components.SheetToolbar
import com.webdroid.app.ui.components.ToggleRow
import com.webdroid.app.ui.components.ToolbarTextButton
import com.webdroid.app.ui.components.pressScale
import com.webdroid.app.ui.components.rememberHaptics
import com.webdroid.app.ui.components.rememberPressState
import com.webdroid.app.ui.components.rememberSnapshotBackdrop
import com.webdroid.app.ui.components.tappable
import com.webdroid.app.ui.editor.VpnChoices
import com.webdroid.app.ui.glass.Glass
import com.webdroid.app.ui.glass.GlassStyle
import com.webdroid.app.ui.theme.AppColors
import com.webdroid.app.ui.theme.AppShapes
import com.webdroid.app.ui.theme.AppType
import com.webdroid.app.ui.theme.Motion
import com.webdroid.app.ui.theme.TileColor
import com.webdroid.app.util.UrlUtils
import com.webdroid.app.vpn.VpnProfiles
import kotlinx.coroutines.delay

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

private val Blue = Color(0xFF1E7BF2)
private val Green = Color(0xFF30B350)
private val Gray = Color(0xFF8E8E93)
private val Indigo = Color(0xFF4B4FE0)
private val Red = Color(0xFFFF3B30)
private val Orange = Color(0xFFFF9500)
private val Pink = Color(0xFFFF2D55)
private val Teal = Color(0xFF30B0C7)

/**
 * Everything WebDroid draws over a web app: the launch screen, pull-to-refresh
 * and pull-for-menu, the optional mini toolbar, sheets, alerts and the player.
 *
 * A web page can't be recorded live, so glass here either uses a snapshot of
 * the window (modal surfaces, which freeze the page anyway) or is frosted.
 */
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
    Box(Modifier.fillMaxSize()) {
        if (!playerVisible) {
            PullIndicator(state)
            if (app.settings.miniToolbar && !state.siteFullscreen) MiniToolbar(state, actions)
            VideoOfferPill(state, actions)
            ErrorView(state, actions)
        }

        Splash(app, icon, state.splashVisible)
        VpnStatusPill(state)

        // Popup windows (sign-in with…) open as a sheet over the app.
        ModalSheet(
            visible = state.popupOpen,
            onDismissRequest = { actions.closePopup() },
            surface = AppColors.Background,
            handleBack = false, // Back steps through the popup's own pages first
        ) {
            SheetToolbar(
                state.popupTitle,
                leading = { Icon(Icons.Rounded.Lock, null, tint = AppColors.SecondaryLabel, modifier = Modifier.padding(start = 8.dp).size(16.dp)) },
                trailing = { ToolbarTextButton("Done", { actions.closePopup() }) },
            )
            Box(Modifier.fillMaxWidth().weight(1f).navigationBarsPadding()) { popup() }
        }

        MenuSheet(app, icon, state, actions)
        SettingsSheet(app, state, actions, isolatedSupported, lockSupported)
        LinkSheet(state, actions)
        PermissionAlert(state, actions)
        JsAlert(state, actions)
        HttpAuthAlert(state, actions)

        if (playerVisible) player()

        Toast(state)
        LockScreen(app, icon, state, actions)
    }
}

/* -------------------------------------------------------------------------- */
/* Launch and lock                                                             */
/* -------------------------------------------------------------------------- */

@Composable
private fun Splash(app: WebApp, icon: Bitmap?, visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(0)),
        exit = fadeOut(tween(240)) + scaleOut(Motion.smooth(), targetScale = 1.04f),
    ) {
        val appear = remember { Animatable(0.86f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, Motion.spring(0.42f, 0.82f)) }
        Box(
            Modifier
                .fillMaxSize()
                .background(AppColors.Background)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AppIcon(
                    icon, app.name, TileColor.of(app.cardColor), 88.dp,
                    modifier = Modifier.graphicsLayer {
                        scaleX = appear.value
                        scaleY = appear.value
                        alpha = ((appear.value - 0.86f) / 0.14f).coerceIn(0f, 1f)
                    },
                )
                Spacer(Modifier.height(16.dp))
                Text(app.name, style = AppType.Title3, color = AppColors.Label)
                Spacer(Modifier.height(22.dp))
                CircularProgressIndicator(color = AppColors.SecondaryLabel, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun LockScreen(app: WebApp, icon: Bitmap?, state: WebAppUiState, actions: WebAppActions) {
    AnimatedVisibility(visible = state.locked, enter = fadeIn(tween(0)), exit = fadeOut(tween(240)) + scaleOut(Motion.smooth(), targetScale = 1.04f)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(AppColors.Background)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.unlock() },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AppIcon(icon, app.name, TileColor.of(app.cardColor), 88.dp)
                Spacer(Modifier.height(16.dp))
                Text(app.name, style = AppType.Title2, color = AppColors.Label)
                Spacer(Modifier.height(4.dp))
                Text("Locked", style = AppType.Subhead, color = AppColors.SecondaryLabel)
                Spacer(Modifier.height(32.dp))
                AppButton("Unlock", { actions.unlock() }, icon = Icons.Rounded.Fingerprint)
            }
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Floating controls (frosted: the page beneath can't be sampled live)        */
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
            transitionSpec = {
                (fadeIn(Motion.smooth()) + scaleIn(Motion.spring(0.3f, 0.8f), initialScale = 0.85f)) togetherWith
                    (fadeOut(Motion.snappy()) + scaleOut(Motion.snappy(), targetScale = 0.85f))
            },
            label = "pull-stage",
        ) { menu ->
            if (menu) {
                Glass(null, cornerRadius = 22.dp, style = GlassStyle.Regular) {
                    Row(Modifier.height(44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Menu, null, tint = AppColors.Accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Release for Menu", style = AppType.Subhead.copy(fontWeight = FontWeight.SemiBold), color = AppColors.Label)
                    }
                }
            } else {
                Glass(null, Modifier.size(44.dp), cornerRadius = 22.dp, style = GlassStyle.Regular) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (stage == 3) {
                            CircularProgressIndicator(color = AppColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        } else {
                            Icon(
                                Icons.Rounded.Refresh, "Refresh",
                                tint = if (stage >= 1) AppColors.Accent else AppColors.SecondaryLabel,
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
}

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
                (fadeIn(Motion.smooth()) + scaleIn(Motion.spring(0.35f, 0.8f), initialScale = 0.6f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f))) togetherWith
                    (fadeOut(Motion.snappy()) + scaleOut(Motion.snappy(), targetScale = 0.6f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)))
            },
            label = "toolbar",
        ) { expanded ->
            if (!expanded) {
                Box(
                    Modifier
                        .size(width = 88.dp, height = 24.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dy ->
                                if (dy > 6f && !state.toolbarExpanded) {
                                    haptics.tick()
                                    state.toolbarExpanded = true
                                }
                            }
                        }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "Show toolbar") {
                            haptics.tick()
                            state.toolbarExpanded = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 5.dp)
                            .clip(AppShapes.Capsule)
                            .background(Color.Black.copy(alpha = 0.3f))
                    )
                }
            } else {
                Glass(null, Modifier.padding(top = 6.dp), cornerRadius = 26.dp, style = GlassStyle.Regular) {
                    Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        ToolbarIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", state.canGoBack) { actions.back() }
                        ToolbarIcon(Icons.AutoMirrored.Rounded.ArrowForward, "Forward", state.canGoForward) { actions.forward() }
                        ToolbarIcon(Icons.Rounded.Refresh, "Reload") { actions.reload() }
                        ToolbarIcon(Icons.Rounded.Share, "Share") { actions.share() }
                        ToolbarIcon(Icons.Rounded.MoreHoriz, "Menu") {
                            state.toolbarExpanded = false
                            state.menuOpen = true
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarIcon(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val press = rememberPressState()
    Box(
        Modifier
            .size(44.dp)
            .pressScale(press, 0.86f)
            .clip(CircleShape)
            .tappable(press, enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (enabled) AppColors.Label else AppColors.TertiaryLabel, modifier = Modifier.size(21.dp))
    }
}

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
        enter = slideInVertically(Motion.spring(0.4f, 0.8f)) { it * 2 } + fadeIn(Motion.smooth()),
        exit = slideOutVertically(Motion.smooth()) { it * 2 } + fadeOut(Motion.snappy()),
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
    ) {
        val press = rememberPressState()
        Glass(null, Modifier.pressScale(press, 0.96f), cornerRadius = 26.dp, style = GlassStyle.Regular) {
            Row(
                Modifier.height(52.dp).padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.tappable(press) { actions.playOffer() }.padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(AppColors.AccentFill), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("Play in WebDroid", style = AppType.Subhead.copy(fontWeight = FontWeight.SemiBold), color = AppColors.Label)
                }
                ToolbarIcon(Icons.Rounded.Close, "Dismiss") { actions.dismissOffer() }
            }
        }
    }
}

/** Tor bootstrap progress (or a VPN error) while the page waits for it. */
@Composable
private fun BoxScope.VpnStatusPill(state: WebAppUiState) {
    val status = state.vpnStatus
    // Keep the last text while the pill animates away.
    val shown = remember { arrayOf("") }
    if (status != null) shown[0] = status
    AnimatedVisibility(
        visible = status != null,
        enter = slideInVertically(Motion.spring(0.4f, 0.8f)) { -it } + fadeIn(Motion.smooth()),
        exit = slideOutVertically(Motion.smooth()) { -it } + fadeOut(Motion.snappy()),
        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
    ) {
        Glass(null, cornerRadius = 20.dp, style = GlassStyle.Regular) {
            Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.VpnLock, null, tint = AppColors.Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(shown[0], style = AppType.Footnote.copy(fontWeight = FontWeight.Medium), color = AppColors.Label)
            }
        }
    }
}

@Composable
private fun BoxScope.Toast(state: WebAppUiState) {
    LaunchedEffect(state.toastSerial) {
        if (state.toast == null) return@LaunchedEffect
        delay(2400)
        state.toast = null
    }
    GlassToast(null, state.toast, bottomPadding = 28.dp)
}

@Composable
private fun ErrorView(state: WebAppUiState, actions: WebAppActions) {
    val error = state.error
    val shown = remember { arrayOf<PageError?>(null) }
    if (error != null) shown[0] = error
    AnimatedVisibility(visible = error != null, enter = fadeIn(Motion.smooth()), exit = fadeOut(Motion.snappy())) {
        Column(
            Modifier
                .fillMaxSize()
                .background(AppColors.Background)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.WifiOff, null, tint = AppColors.SecondaryLabel, modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(18.dp))
            Text(
                "Can’t Reach ${UrlUtils.displayHost(shown[0]?.url)}",
                style = AppType.Title2, color = AppColors.Label, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                shown[0]?.description.orEmpty(),
                style = AppType.Body, color = AppColors.SecondaryLabel, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            AppButton("Try Again", { actions.retry() }, icon = Icons.Rounded.Refresh)
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Menu                                                                        */
/* -------------------------------------------------------------------------- */

@Composable
private fun BoxScope.MenuSheet(app: WebApp, icon: Bitmap?, state: WebAppUiState, actions: WebAppActions) {
    val close = { state.menuOpen = false }
    ModalSheet(
        visible = state.menuOpen,
        onDismissRequest = close,
        sizes = listOf(SheetSize.Medium, SheetSize.Large),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(icon, app.name, TileColor.of(app.cardColor), 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(state.title.ifBlank { app.name }, style = AppType.Headline, color = AppColors.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(UrlUtils.displayHost(state.url.ifBlank { app.url }), style = AppType.Footnote, color = AppColors.SecondaryLabel, maxLines = 1)
            }
            CloseButton(close)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", state.canGoBack, Modifier.weight(1f)) { close(); actions.back() }
                QuickAction(Icons.AutoMirrored.Rounded.ArrowForward, "Forward", state.canGoForward, Modifier.weight(1f)) { close(); actions.forward() }
                QuickAction(Icons.Rounded.Refresh, "Reload", true, Modifier.weight(1f)) { close(); actions.reload() }
                QuickAction(Icons.Rounded.Home, "Home", true, Modifier.weight(1f)) { close(); actions.home() }
            }
            SectionGap(24.dp)
            ListSection {
                row { ListRow("Share", icon = Icons.Rounded.Share, iconBackground = Blue, onClick = { close(); actions.share() }) }
                row { ListRow("Copy Link", icon = Icons.Rounded.ContentCopy, iconBackground = Gray, onClick = { close(); actions.copyLink() }) }
                row { ListRow("Open in Browser", icon = Icons.AutoMirrored.Rounded.OpenInNew, iconBackground = Green, onClick = { close(); actions.openInBrowser() }) }
            }
            SectionGap(24.dp)
            val vpnLabel = VpnProfiles.get(LocalContext.current).label(app.settings.vpn)
            ListSection {
                row {
                    ToggleRow(
                        "Desktop Site", app.settings.desktopMode,
                        { enabled -> actions.updateSettings { it.copy(desktopMode = enabled) } },
                        icon = Icons.Rounded.DesktopWindows, iconBackground = Indigo,
                    )
                }
                row {
                    ToggleRow(
                        "Block Ads", app.settings.adBlock,
                        { enabled -> actions.updateSettings { it.copy(adBlock = enabled) } },
                        subtitle = if (state.blockedCount > 0) "${state.blockedCount} blocked on this visit" else null,
                        icon = Icons.Rounded.Shield, iconBackground = Red,
                    )
                }
                row {
                    ListRow(
                        "VPN",
                        icon = Icons.Rounded.VpnLock, iconBackground = Blue,
                        subtitle = state.vpnStatus,
                        value = vpnLabel ?: "Off",
                        chevron = true,
                        onClick = { state.menuOpen = false; state.settingsOpen = true },
                    )
                }
            }
            SectionGap(24.dp)
            ListSection(header = "Videos") {
                row {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        SegmentedControl(
                            listOf(VideoMode.AUTO to "Player", VideoMode.BUTTON to "Ask", VideoMode.OFF to "In Page"),
                            app.settings.videoMode,
                            { mode -> actions.updateSettings { it.copy(videoMode = mode) } },
                        )
                    }
                }
            }
            SectionGap(24.dp)
            ListSection {
                row {
                    ListRow(
                        "App Settings", icon = Icons.Rounded.Settings, iconBackground = Gray, chevron = true,
                        onClick = { state.menuOpen = false; state.settingsOpen = true },
                    )
                }
                row { ListRow("Add to Home Screen", icon = Icons.Rounded.AddToHomeScreen, iconBackground = Orange, onClick = { close(); actions.addToHomeScreen() }) }
            }
            SectionGap(24.dp)
            ListSection {
                row(16.dp) { ActionRow("Close App", { close(); actions.closeApp() }, destructive = true) }
            }
        }
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit) {
    val press = rememberPressState()
    Box(
        Modifier
            .size(44.dp)
            .pressScale(press, 0.88f)
            .tappable(press, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(AppColors.Fill), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Close", tint = AppColors.SecondaryLabel, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val press = rememberPressState()
    Column(
        modifier
            .pressScale(press, 0.94f)
            .clip(AppShapes.Medium)
            .background(if (press.pressed && enabled) AppColors.CellPressed else AppColors.Cell)
            .tappable(press, enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (enabled) AppColors.Accent else AppColors.TertiaryLabel, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = AppType.Caption1, color = if (enabled) AppColors.Label else AppColors.TertiaryLabel)
    }
}

/* -------------------------------------------------------------------------- */
/* App settings                                                                */
/* -------------------------------------------------------------------------- */

@Composable
private fun BoxScope.SettingsSheet(
    app: WebApp,
    state: WebAppUiState,
    actions: WebAppActions,
    isolatedSupported: Boolean,
    lockSupported: Boolean,
) {
    val close = { state.settingsOpen = false }
    var confirmClear by remember { mutableStateOf(false) }
    val s = app.settings
    ModalSheet(visible = state.settingsOpen, onDismissRequest = close) {
        SheetToolbar(app.name, trailing = { ToolbarTextButton("Done", close, prominent = true) })
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(top = 8.dp, bottom = 32.dp),
        ) {
            ListSection(header = "Browsing") {
                row {
                    ToggleRow(
                        "Mini Toolbar", s.miniToolbar, { v -> actions.updateSettings { it.copy(miniToolbar = v) } },
                        subtitle = "Back, reload and share in a bar you pull down",
                        icon = Icons.Rounded.ViewDay, iconBackground = Gray,
                    )
                }
                row {
                    ToggleRow(
                        "Force Dark", s.forceDark, { v -> actions.updateSettings { it.copy(forceDark = v) } },
                        subtitle = "Darken sites without a dark theme",
                        icon = Icons.Rounded.DarkMode, iconBackground = Indigo,
                    )
                }
                row {
                    ToggleRow(
                        "Open Other Sites in Browser", s.openLinksExternally, { v -> actions.updateSettings { it.copy(openLinksExternally = v) } },
                        icon = Icons.AutoMirrored.Rounded.OpenInNew, iconBackground = Green,
                    )
                }
            }
            SectionGap()
            ListSection(header = "Privacy & Security") {
                row {
                    ToggleRow(
                        "Separate Login", s.isolatedSession, { v -> actions.updateSettings { it.copy(isolatedSession = v) } },
                        subtitle = if (isolatedSupported) "Its own cookies · applies next time you open it" else "Needs a newer Android System WebView",
                        icon = Icons.Rounded.People, iconBackground = Orange, enabled = isolatedSupported,
                    )
                }
                row {
                    ToggleRow(
                        "Fingerprint Lock", s.appLock, { v -> actions.updateSettings { it.copy(appLock = v) } },
                        subtitle = if (lockSupported) null else "Set up a screen lock first",
                        icon = Icons.Rounded.Fingerprint, iconBackground = Pink, enabled = lockSupported,
                    )
                }
            }
            SectionGap()
            val notifyGranted = s.grantedPermissions.any { it.startsWith("notify:") }
            val notifyBlocked = !notifyGranted && s.grantedPermissions.any { it.startsWith("notify-block:") }
            ListSection(
                header = "Notifications",
                footer = "Stay Connected keeps the app running after you leave it so chats can notify you. It shows a quiet status notification and uses more battery.",
            ) {
                row {
                    ToggleRow(
                        "Allow Notifications", notifyGranted, { v -> actions.setNotificationsAllowed(v) },
                        subtitle = when {
                            notifyGranted -> null
                            notifyBlocked -> "Blocked"
                            else -> "The site hasn’t asked yet"
                        },
                        icon = Icons.Rounded.Notifications, iconBackground = Red,
                    )
                }
                row {
                    ToggleRow(
                        "Stay Connected", s.keepAlive, { v -> actions.updateSettings { it.copy(keepAlive = v) } },
                        icon = Icons.Rounded.Wifi, iconBackground = Teal,
                    )
                }
                row {
                    ListRow(
                        "Sound & Style", icon = Icons.Rounded.Tune, iconBackground = Gray, chevron = true,
                        onClick = { close(); actions.openNotificationSettings() },
                    )
                }
            }
            SectionGap()
            VpnChoices(s.vpn) { choice -> actions.updateSettings { it.copy(vpn = choice) } }
            SectionGap()
            ListSection {
                row { ListRow("Edit Name, Icon & Color", icon = Icons.Rounded.Edit, iconBackground = Blue, chevron = true, onClick = { close(); actions.editApp() }) }
            }
            SectionGap()
            ListSection(footer = "Signs you out of ${UrlUtils.displayHost(app.url)} and removes what it saved on this device.") {
                row(16.dp) { ActionRow("Clear Website Data", { confirmClear = true }, destructive = true) }
            }
        }
    }
    val backdrop = rememberSnapshotBackdrop(confirmClear)
    GlassAlert(
        visible = confirmClear,
        backdrop = backdrop,
        title = "Clear data for ${UrlUtils.displayHost(app.url)}?",
        message = "You’ll be signed out.",
        onDismiss = { confirmClear = false },
        actions = listOf(
            AlertAction("Cancel", ButtonStyle.Tinted) { confirmClear = false },
            AlertAction("Clear", ButtonStyle.Destructive) {
                confirmClear = false
                close()
                actions.clearSiteData()
            },
        ),
    )
}

/* -------------------------------------------------------------------------- */
/* Links, permissions and the page's own dialogs                               */
/* -------------------------------------------------------------------------- */

@Composable
private fun BoxScope.LinkSheet(state: WebAppUiState, actions: WebAppActions) {
    val menu = state.linkMenu
    val last = remember { arrayOf<LinkMenu?>(null) }
    if (menu != null) last[0] = menu
    val current = last[0]
    val backdrop = rememberSnapshotBackdrop(menu != null)
    val close = { state.linkMenu = null }
    ActionSheet(
        visible = menu != null,
        backdrop = backdrop,
        title = current?.title?.takeIf { it.isNotBlank() } ?: current?.let { UrlUtils.displayHost(it.url) },
        message = current?.url,
        onDismiss = close,
        actions = if (current == null) emptyList() else listOf(
            MenuAction("Open") { actions.openLink(current.url) },
            MenuAction("Open in Browser") { actions.openLinkExternally(current.url) },
            MenuAction("Copy Link") { actions.copyText(current.url) },
            MenuAction("Share Link") { actions.shareText(current.url) },
            if (current.imageUrl != null) MenuAction("Save Image") { actions.download(current.imageUrl) }
            else MenuAction("Download Linked File") { actions.download(current.url) },
        ),
    )
}

@Composable
private fun BoxScope.PermissionAlert(state: WebAppUiState, actions: WebAppActions) {
    val prompt = state.permissionPrompt
    val last = remember { arrayOf<PermissionPrompt?>(null) }
    if (prompt != null) last[0] = prompt
    val current = last[0]
    val backdrop = rememberSnapshotBackdrop(prompt != null)
    val (icon, what) = when (current) {
        is PermissionPrompt.Location -> Icons.Rounded.LocationOn to "Use Your Location"
        is PermissionPrompt.Notifications -> Icons.Rounded.NotificationsActive to "Send You Notifications"
        is PermissionPrompt.Media -> Icons.Rounded.CameraAlt to when {
            current.camera && current.mic -> "Use Your Camera and Microphone"
            current.camera -> "Use Your Camera"
            else -> "Use Your Microphone"
        }
        null -> Icons.Rounded.Lock to ""
    }
    GlassAlert(
        visible = prompt != null,
        backdrop = backdrop,
        icon = icon,
        title = "“${UrlUtils.displayHost(current?.origin)}” Would Like to $what",
        actions = listOf(
            AlertAction("Don’t Allow", ButtonStyle.Tinted) { actions.answerPermission(false) },
            AlertAction("Allow", ButtonStyle.Prominent) { actions.answerPermission(true) },
        ),
    )
}

@Composable
private fun BoxScope.JsAlert(state: WebAppUiState, actions: WebAppActions) {
    val dialog = state.jsDialog
    val last = remember { arrayOf<JsDialog?>(null) }
    if (dialog != null) last[0] = dialog
    val current = last[0]
    var text by remember(current) { mutableStateOf((current as? JsDialog.Prompt)?.defaultValue.orEmpty()) }
    val backdrop = rememberSnapshotBackdrop(dialog != null)
    GlassAlert(
        visible = dialog != null,
        backdrop = backdrop,
        title = UrlUtils.displayHost(state.url),
        onDismiss = if (current is JsDialog.Alert) ({ actions.answerJs(true, null) }) else ({ actions.answerJs(false, null) }),
        extra = {
            Text(
                current?.message.orEmpty(),
                style = AppType.Subhead,
                color = AppColors.Label,
                textAlign = TextAlign.Center,
                modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
            )
            if (current is JsDialog.Prompt) {
                Spacer(Modifier.height(14.dp))
                AppTextField(text, { text = it }, background = AppColors.Fill)
            }
        },
        actions = if (current is JsDialog.Alert) {
            listOf(AlertAction("OK", ButtonStyle.Prominent) { actions.answerJs(true, null) })
        } else {
            listOf(
                AlertAction("Cancel", ButtonStyle.Tinted) { actions.answerJs(false, null) },
                AlertAction("OK", ButtonStyle.Prominent) { actions.answerJs(true, text) },
            )
        },
    )
}

@Composable
private fun BoxScope.HttpAuthAlert(state: WebAppUiState, actions: WebAppActions) {
    val prompt = state.httpAuth
    val last = remember { arrayOf<HttpAuthPrompt?>(null) }
    if (prompt != null) last[0] = prompt
    val current = last[0]
    var user by remember(current) { mutableStateOf("") }
    var pass by remember(current) { mutableStateOf("") }
    val backdrop = rememberSnapshotBackdrop(prompt != null)
    GlassAlert(
        visible = prompt != null,
        backdrop = backdrop,
        icon = Icons.Rounded.Lock,
        title = "Sign In to ${current?.host.orEmpty()}",
        message = current?.realm?.takeIf { it.isNotBlank() },
        onDismiss = { actions.answerHttpAuth(null, null) },
        extra = {
            AppTextField(user, { user = it }, placeholder = "Username", background = AppColors.Fill)
            Spacer(Modifier.height(8.dp))
            AppTextField(
                pass, { pass = it }, placeholder = "Password", background = AppColors.Fill,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
            )
        },
        actions = listOf(
            AlertAction("Cancel", ButtonStyle.Tinted) { actions.answerHttpAuth(null, null) },
            AlertAction("Sign In", ButtonStyle.Prominent) { if (user.isNotBlank()) actions.answerHttpAuth(user, pass) },
        ),
    )
}
