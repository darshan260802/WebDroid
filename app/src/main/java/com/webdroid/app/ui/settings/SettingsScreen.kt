package com.webdroid.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.webdroid.app.BuildConfig
import com.webdroid.app.data.FitMode
import com.webdroid.app.data.GlobalSettings
import com.webdroid.app.data.ThemeMode
import com.webdroid.app.ui.components.ActionRow
import com.webdroid.app.ui.components.ListRow
import com.webdroid.app.ui.components.ListScreen
import com.webdroid.app.ui.components.ListSection
import com.webdroid.app.ui.components.LocalChromeBackdrop
import com.webdroid.app.ui.components.NavStack
import com.webdroid.app.ui.components.SectionGap
import com.webdroid.app.ui.components.SegmentedControl
import com.webdroid.app.ui.components.ToggleRow
import com.webdroid.app.ui.components.rememberPressState
import com.webdroid.app.ui.components.tappable
import com.webdroid.app.ui.theme.AppColors
import com.webdroid.app.ui.theme.AppType
import com.webdroid.app.ui.theme.Spacing
import com.webdroid.app.ui.update.ReleaseNotes
import com.webdroid.app.ui.update.UpdateActions
import com.webdroid.app.ui.update.UpdatesSection
import com.webdroid.app.ui.update.info
import com.webdroid.app.update.UpdateState

sealed interface SettingsRoute {
    data object Root : SettingsRoute
    data object WhatsNew : SettingsRoute
    data object Licenses : SettingsRoute
}

private val Pink = Color(0xFFFF2D55)
private val Orange = Color(0xFFFF9500)
private val Blue = Color(0xFF1E7BF2)
private val Green = Color(0xFF30B350)
private val Gray = Color(0xFF8E8E93)

/** The Settings tab. */
@Composable
fun SettingsScreen(
    stack: List<SettingsRoute>,
    onPush: (SettingsRoute) -> Unit,
    onPop: () -> Unit,
    settings: GlobalSettings,
    onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit,
    update: UpdateState,
    updateLastChecked: Long,
    updateActions: UpdateActions,
) {
    NavStack(stack, onPop, backdrop = LocalChromeBackdrop.current) { route ->
        when (route) {
            SettingsRoute.Root -> SettingsRoot(settings, onUpdate, update, updateLastChecked, updateActions, onPush)
            SettingsRoute.WhatsNew -> ListScreen("What’s New", largeTitle = false, onBack = onPop) {
                item(key = "notes") {
                    val notes = update.info?.notes.orEmpty()
                    Column(Modifier.padding(horizontal = Spacing.Edge + 4.dp, vertical = 8.dp)) {
                        if (notes.isBlank()) Text("No release notes.", style = AppType.Body, color = AppColors.SecondaryLabel)
                        else ReleaseNotes(notes)
                    }
                }
            }
            SettingsRoute.Licenses -> LicensesScreen(onPop)
        }
    }
}

@Composable
private fun SettingsRoot(
    settings: GlobalSettings,
    onUpdate: ((GlobalSettings) -> GlobalSettings) -> Unit,
    update: UpdateState,
    updateLastChecked: Long,
    updateActions: UpdateActions,
    onPush: (SettingsRoute) -> Unit,
) {
    val context = LocalContext.current
    val power = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    val actions = UpdateActions(
        install = updateActions.install,
        download = updateActions.download,
        checkNow = updateActions.checkNow,
        later = updateActions.later,
        showNotes = { onPush(SettingsRoute.WhatsNew) },
    )

    ListScreen("Settings", largeTitle = true) {
        item(key = "appearance") {
            ListSection(
                header = "Appearance",
                footer = "Applies to WebDroid’s own screens. Websites follow your phone’s setting; turn on Force Dark in an app’s settings to darken one.",
            ) {
                row(16.dp) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        SegmentedControl(
                            listOf(ThemeMode.SYSTEM to "Automatic", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark"),
                            settings.themeMode,
                            { mode -> onUpdate { it.copy(themeMode = mode) } },
                        )
                    }
                }
                row {
                    ToggleRow(
                        "Reduce Transparency", settings.reduceTransparency,
                        { v -> onUpdate { it.copy(reduceTransparency = v) } },
                        subtitle = "Solid bars and menus instead of glass",
                        icon = Icons.Rounded.Contrast, iconBackground = Gray,
                    )
                }
            }
        }
        item(key = "player") {
            SectionGap()
            ListSection(header = "Video Player", footer = "Gestures: left side for brightness, right side for volume, swipe across to seek, double-tap to skip 10 seconds, pinch to zoom, hold for 2× speed.") {
                row { ToggleRow("Background Play", settings.backgroundPlay, { v -> onUpdate { it.copy(backgroundPlay = v) } }, subtitle = "Keep listening with the screen off", icon = Icons.Rounded.VolumeUp, iconBackground = Pink) }
                row { ToggleRow("Remember Position", settings.rememberPosition, { v -> onUpdate { it.copy(rememberPosition = v) } }, icon = Icons.Rounded.History, iconBackground = Orange) }
                row { ToggleRow("Auto-Rotate", settings.autoRotate, { v -> onUpdate { it.copy(autoRotate = v) } }, subtitle = "Turn to landscape for wide videos", icon = Icons.Rounded.ScreenRotation, iconBackground = Blue) }
                row(16.dp) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("Default Fit", style = AppType.Body, color = AppColors.Label, modifier = Modifier.padding(bottom = 10.dp))
                        SegmentedControl(
                            listOf(FitMode.FIT to "Fit", FitMode.ZOOM to "Zoom", FitMode.FILL to "Stretch"),
                            settings.defaultFit,
                            { mode -> onUpdate { it.copy(defaultFit = mode) } },
                        )
                    }
                }
            }
        }
        item(key = "background") {
            SectionGap()
            ListSection(
                header = "Background",
                footer = "Apps with Stay Connected keep delivering notifications more reliably when Android doesn’t put WebDroid to sleep.",
            ) {
                row {
                    ListRow(
                        "Battery Use",
                        icon = Icons.Rounded.BatteryChargingFull, iconBackground = Green,
                        value = if (unrestricted) "Unrestricted" else "Optimized",
                        chevron = !unrestricted,
                        onClick = if (unrestricted) null else {
                            {
                                runCatching {
                                    context.startActivity(
                                        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
        item(key = "updates") {
            SectionGap()
            UpdatesSection(update, updateLastChecked, settings, onUpdate, actions)
        }
        item(key = "about") {
            SectionGap()
            ListSection(header = "About") {
                row(16.dp) { ListRow("Version", value = BuildConfig.VERSION_NAME) }
                row(16.dp) {
                    ListRow("Source Code & Releases", onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/darshan260802/WebDroid")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }, trailing = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = AppColors.TertiaryLabel, modifier = Modifier.size(18.dp)) })
                }
                row(16.dp) { ListRow("Licenses", chevron = true, onClick = { onPush(SettingsRoute.Licenses) }) }
            }
        }
        item(key = "credit") {
            Credit()
        }
    }
}

/** "Made with ♥ Darshan Patel", the name linking to his LinkedIn profile. */
@Composable
private fun Credit() {
    val context = LocalContext.current
    val press = rememberPressState()
    Row(
        Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Made with", style = AppType.Footnote, color = AppColors.SecondaryLabel)
        Icon(Icons.Rounded.Favorite, "love", tint = Color(0xFFFF2D55), modifier = Modifier.padding(horizontal = 4.dp).size(14.dp))
        Text(
            "Darshan Patel",
            style = AppType.Footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            color = AppColors.Accent,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .tappable(press, role = Role.Button) {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.linkedin.com/in/darshan-patel-2608")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                .padding(vertical = 12.dp, horizontal = 2.dp),
        )
    }
}

@Composable
private fun LicensesScreen(onBack: () -> Unit) {
    val entries = listOf(
        "WebDroid" to "GNU General Public License v3.0",
        "uBlock Origin (engine and filter lists)" to "GPL v3 · Raymond Hill and contributors",
        "EasyList, Peter Lowe’s list, malware filters" to "Their respective licenses (CC BY-SA, MIT…)",
        "Tor (tor-android)" to "BSD 3-Clause · The Tor Project, Guardian Project",
        "WireGuard (wireguard-go)" to "MIT · Jason A. Donenfeld",
        "gVisor netstack" to "Apache 2.0 · Google",
        "AndroidX, Jetpack Compose, Media3" to "Apache 2.0 · Google",
        "OkHttp" to "Apache 2.0 · Square",
        "Zipline (QuickJS)" to "Apache 2.0 · Cash App; QuickJS MIT",
        "jsoup" to "MIT · Jonathan Hedley",
    )
    ListScreen("Licenses", largeTitle = false, onBack = onBack) {
        item(key = "licenses") {
            ListSection(footer = "WebDroid is free software: you can redistribute it and modify it under the terms of the GNU GPL v3.") {
                entries.forEach { (name, license) ->
                    row(16.dp) { ListRow(name, subtitle = license) }
                }
            }
        }
    }
}
