package com.webdroid.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.webdroid.app.BuildConfig
import com.webdroid.app.adblock.AdblockEngine
import com.webdroid.app.adblock.FilterListInfo
import com.webdroid.app.data.FitMode
import com.webdroid.app.data.GlobalSettings
import com.webdroid.app.data.ThemeMode
import com.webdroid.app.ui.components.ClayButton
import com.webdroid.app.ui.components.ClayButtonStyle
import com.webdroid.app.ui.components.ClayIconButton
import com.webdroid.app.ui.components.ClaySegmented
import com.webdroid.app.ui.components.ClaySwitchRow
import com.webdroid.app.ui.components.HairlineDivider
import com.webdroid.app.ui.components.SectionLabel
import com.webdroid.app.ui.home.formatCount
import com.webdroid.app.ui.theme.Clay
import com.webdroid.app.ui.theme.ClayShapes
import com.webdroid.app.ui.theme.ClayType
import com.webdroid.app.ui.update.UpdateActions
import com.webdroid.app.ui.update.UpdateSettingsSection
import com.webdroid.app.update.UpdateState

@Composable
fun SettingsScreen(
    settings: GlobalSettings,
    status: AdblockEngine.Status,
    lists: List<FilterListInfo>,
    lastUpdated: Long,
    onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit,
    onUpdateFilters: () -> Unit,
    update: UpdateState,
    updateLastChecked: Long,
    updateActions: UpdateActions,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val grouped = remember(lists) { lists.groupBy { it.group } }
    val pendingChanges = lists.any { list -> settings.listOverrides[list.id] != null && settings.listOverrides[list.id] != list.enabled }

    Box(Modifier.fillMaxSize().background(Clay.Canvas)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ClayIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(14.dp))
                Text("Settings", style = ClayType.TitleLg, color = Clay.Ink)
            }

            // Ad blocker hero card (teal = featured)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(ClayShapes.Xl)
                    .background(Clay.Teal)
                    .padding(24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(ClayShapes.Lg).background(Clay.Mint), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Shield, null, tint = Clay.Black, modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Ad blocker", style = ClayType.TitleLg, color = Clay.OnDark)
                        Text("Powered by uBlock Origin", style = ClayType.Caption, color = Clay.OnDarkSoft)
                    }
                }
                Spacer(Modifier.height(18.dp))
                val line = when (status.state) {
                    AdblockEngine.State.READY ->
                        "${formatCount(status.networkFilters)} network · ${formatCount(status.cosmeticFilters)} cosmetic · ${formatCount(status.scriptletFilters)} scriptlet filters"
                    AdblockEngine.State.LOADING, AdblockEngine.State.IDLE -> "Loading filters…"
                    AdblockEngine.State.FAILED -> "Couldn't load filters: ${status.lastError.orEmpty()}"
                    AdblockEngine.State.UNAVAILABLE -> status.lastError ?: "Unavailable in this build"
                }
                Text(line, style = ClayType.BodySm, color = Clay.OnDark)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(if (lastUpdated > 0) "Lists updated ${relative(lastUpdated)}" else "Using the lists built into this version")
                        if (AdblockEngine.blockedTotal.get() > 0) append(" · ${AdblockEngine.blockedTotal.get()} blocked this session")
                    },
                    style = ClayType.Caption, color = Clay.OnDarkSoft,
                )
                AnimatedVisibility(visible = status.updating, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Clay.Mint, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(status.updateProgress ?: "Updating…", style = ClayType.Caption, color = Clay.OnDark)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ClayButton(
                        if (pendingChanges) "Apply list changes" else "Update lists now",
                        onClick = onUpdateFilters,
                        icon = Icons.Rounded.Sync,
                        style = ClayButtonStyle.OnColor,
                        enabled = !status.updating,
                        height = 46.dp,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().clip(ClayShapes.Lg).background(Clay.SurfaceSoft).padding(horizontal = 16.dp, vertical = 4.dp)) {
                ClaySwitchRow(
                    "Block ads everywhere", "Master switch. Each app can also turn it off.",
                    settings.adBlockEnabled, { v -> onUpdate { it.copy(adBlockEnabled = v) } },
                )
            }

            Spacer(Modifier.height(26.dp))
            SectionLabel("Appearance")
            Spacer(Modifier.height(8.dp))
            ClaySegmented(
                options = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"),
                selected = settings.themeMode,
                onSelect = { mode -> onUpdate { it.copy(themeMode = mode) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DarkMode, null, tint = Clay.Muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Applies to WebDroid's own screens and menus. Websites follow your phone's setting; turn on “Force dark” in an app's settings to darken one.",
                    style = ClayType.Caption, color = Clay.Muted,
                )
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("Filter lists")
            Text(
                "Same lists uBlock Origin uses. They refresh in the background every few days; changes apply after an update.",
                style = ClayType.Caption, color = Clay.Muted, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            grouped.forEach { (group, items) ->
                Text(group, style = ClayType.TitleSm, color = Clay.Ink, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                Column(Modifier.fillMaxWidth().clip(ClayShapes.Lg).background(Clay.SurfaceSoft).padding(horizontal = 16.dp)) {
                    items.forEachIndexed { i, list ->
                        val enabled = settings.listOverrides[list.id] ?: list.enabled
                        ClaySwitchRow(
                            list.title, if (list.enabled) "Default" else null, enabled,
                            { v ->
                                onUpdate { s ->
                                    val overrides = s.listOverrides.toMutableMap()
                                    if (v == list.enabled) overrides.remove(list.id) else overrides[list.id] = v
                                    s.copy(listOverrides = overrides)
                                }
                            },
                        )
                        if (i != items.lastIndex) HairlineDivider()
                    }
                }
            }

            Spacer(Modifier.height(26.dp))
            SectionLabel("Video player")
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().clip(ClayShapes.Lg).background(Clay.SurfaceSoft).padding(horizontal = 16.dp, vertical = 4.dp)) {
                ClaySwitchRow(
                    "Background play", "Keep listening with the screen off, with media controls",
                    settings.backgroundPlay, { v -> onUpdate { it.copy(backgroundPlay = v) } }, icon = Icons.Rounded.VolumeUp,
                )
                ClaySwitchRow(
                    "Remember position", "Continue videos where you stopped",
                    settings.rememberPosition, { v -> onUpdate { it.copy(rememberPosition = v) } }, icon = Icons.Rounded.History,
                )
                ClaySwitchRow(
                    "Auto-rotate", "Turn to landscape for wide videos",
                    settings.autoRotate, { v -> onUpdate { it.copy(autoRotate = v) } }, icon = Icons.Rounded.ScreenRotation,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text("Default fit", style = ClayType.TitleSm, color = Clay.Ink)
            Spacer(Modifier.height(8.dp))
            ClaySegmented(
                options = listOf(FitMode.FIT to "Fit", FitMode.ZOOM to "Zoom", FitMode.FILL to "Stretch"),
                selected = settings.defaultFit,
                onSelect = { mode -> onUpdate { it.copy(defaultFit = mode) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PictureInPicture, null, tint = Clay.Muted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Gestures: left side brightness, right side volume, swipe to seek, double-tap ±10s, pinch to zoom, hold for 2×.",
                    style = ClayType.Caption, color = Clay.Muted,
                )
            }

            Spacer(Modifier.height(26.dp))
            SectionLabel("VPN")
            Text(
                "Send chosen web apps through Tor, a WireGuard config or a proxy. Only those apps' sites use it; pick one per app in its settings.",
                style = ClayType.Caption, color = Clay.Muted, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            VpnSettingsSection()

            Spacer(Modifier.height(26.dp))
            SectionLabel("Background & notifications")
            Spacer(Modifier.height(8.dp))
            val power = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            var unrestricted by remember { androidx.compose.runtime.mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
            androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
                unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
                onPauseOrDispose { }
            }
            Column(Modifier.fillMaxWidth().clip(ClayShapes.Lg).background(Clay.SurfaceSoft).padding(16.dp)) {
                Text(
                    if (unrestricted) "Battery use: unrestricted" else "Battery use: optimised",
                    style = ClayType.TitleSm, color = Clay.Ink,
                )
                Text(
                    "Web apps with “Stay connected” keep receiving notifications more reliably when Android doesn't put WebDroid to sleep.",
                    style = ClayType.BodySm, color = Clay.Muted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                if (!unrestricted) {
                    ClayButton(
                        "Allow background activity",
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                                )
                            }
                        },
                        style = ClayButtonStyle.Secondary,
                        height = 44.dp,
                    )
                }
            }

            Spacer(Modifier.height(26.dp))
            SectionLabel("Updates")
            Spacer(Modifier.height(8.dp))
            UpdateSettingsSection(update, updateLastChecked, settings, onUpdate, updateActions)

            Spacer(Modifier.height(26.dp))
            SectionLabel("About")
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(ClayShapes.Xl)
                    .background(Clay.Lavender)
                    .padding(22.dp),
            ) {
                Text("WebDroid ${BuildConfig.VERSION_NAME}", style = ClayType.TitleMd, color = Clay.Black)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Free software under the GNU GPL v3. Ad blocking uses uBlock Origin's engine by Raymond Hill and contributors; filter lists by the uBlock Origin, EasyList, Peter Lowe and malware-filter teams.",
                    style = ClayType.BodySm, color = Clay.Black,
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .clip(ClayShapes.Pill)
                        .background(Clay.Canvas)
                        .clickable {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/darshan260802/WebDroid")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Code, null, tint = Clay.Ink, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Source code & releases", style = ClayType.Caption, color = Clay.Ink)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("Made with", style = ClayType.Caption, color = Clay.MutedSoft)
                Icon(Icons.Rounded.Favorite, null, tint = Clay.Pink, modifier = Modifier.padding(horizontal = 4.dp).size(14.dp))
                Text("· Jay Shree Ganesha", style = ClayType.Caption, color = Clay.MutedSoft)
            }
        }
    }
}

private fun relative(time: Long): String {
    val minutes = (System.currentTimeMillis() - time) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        else -> "${minutes / (60 * 24)} days ago"
    }
}
