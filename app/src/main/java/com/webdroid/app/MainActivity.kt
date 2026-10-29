package com.webdroid.app

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.webdroid.app.adblock.AdblockEngine
import com.webdroid.app.adblock.AdblockStorage
import com.webdroid.app.adblock.FilterUpdateWorker
import com.webdroid.app.data.AppSettings
import com.webdroid.app.data.IconStore
import com.webdroid.app.data.ThemeMode
import com.webdroid.app.data.WebApp
import com.webdroid.app.data.WebAppRepository
import com.webdroid.app.ui.apps.AppsCallbacks
import com.webdroid.app.ui.apps.AppsScreen
import com.webdroid.app.ui.components.GlassTabBar
import com.webdroid.app.ui.components.LocalChromeBackdrop
import com.webdroid.app.ui.components.LocalOverlayHost
import com.webdroid.app.ui.components.LocalSheetPresentation
import com.webdroid.app.ui.components.OverlayHost
import com.webdroid.app.ui.components.OverlayLayer
import com.webdroid.app.ui.components.SheetPresentation
import com.webdroid.app.ui.components.TabItem
import com.webdroid.app.ui.editor.EditorRequest
import com.webdroid.app.ui.editor.EditorResult
import com.webdroid.app.ui.editor.EditorSheet
import com.webdroid.app.ui.glass.glassSource
import com.webdroid.app.ui.glass.rememberGlassBackdrop
import com.webdroid.app.ui.privacy.PrivacyRoute
import com.webdroid.app.ui.privacy.PrivacyScreen
import com.webdroid.app.ui.settings.SettingsRoute
import com.webdroid.app.ui.settings.SettingsScreen
import com.webdroid.app.ui.theme.AppColors
import com.webdroid.app.ui.theme.AppTheme
import com.webdroid.app.ui.theme.ColorPalette
import com.webdroid.app.ui.theme.Motion
import com.webdroid.app.ui.theme.WebDroidTheme
import com.webdroid.app.ui.update.UpdateActions
import com.webdroid.app.ui.update.info
import com.webdroid.app.update.AppUpdater
import com.webdroid.app.update.UpdateState
import com.webdroid.app.web.AppLock
import com.webdroid.app.web.ShortcutHelper
import com.webdroid.app.web.WebAppActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAB_APPS = 0
private const val TAB_PRIVACY = 1
private const val TAB_SETTINGS = 2

private val Tabs = listOf(
    TabItem("Apps", Icons.Outlined.GridView, Icons.Rounded.GridView),
    TabItem("Privacy", Icons.Outlined.Shield, Icons.Rounded.Shield),
    TabItem("Settings", Icons.Outlined.Settings, Icons.Rounded.Settings),
)

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_EDIT_APP = "edit_app"
    }

    private val repo by lazy { WebAppRepository.get(this) }
    private val icons by lazy { IconStore.get(this) }

    private var tab by mutableIntStateOf(TAB_APPS)
    private val privacyStack = mutableStateListOf<PrivacyRoute>(PrivacyRoute.Root)
    private val settingsStack = mutableStateListOf<SettingsRoute>(SettingsRoute.Root)
    private var editor by mutableStateOf<EditorRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.prime(this)
        applyWindowTheme(AppColors.isDark)
        super.onCreate(savedInstanceState)
        AppUpdater.restore(this)
        handleIntent(intent)
        AppUpdater.checkIfDue(this)

        // Checked lazily: querying WebView features loads Chromium.
        val isolatedSupported by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }
        val lockSupported by lazy { AppLock.isAvailable(this) }

        setContent {
            WebDroidTheme {
                val dark = AppColors.isDark
                LaunchedEffect(dark) { applyWindowTheme(dark) }
                val update by AppUpdater.state.collectAsStateWithLifecycle()
                val snoozeTick by AppUpdater.snoozeTick.collectAsStateWithLifecycle()
                val homeUpdate = remember(update, snoozeTick) {
                    val info = update.info
                    if (info != null && !AppUpdater.isSnoozed(this@MainActivity, info)) update else UpdateState.Idle
                }
                val updateActions = remember {
                    UpdateActions(
                        install = { AppUpdater.install(this@MainActivity) },
                        download = { AppUpdater.download(this@MainActivity) },
                        checkNow = { AppUpdater.checkNow(this@MainActivity) },
                        later = { AppUpdater.snooze(this@MainActivity) },
                        showNotes = { showReleaseNotes() },
                    )
                }
                val apps by repo.apps.collectAsStateWithLifecycle()
                val status by AdblockEngine.status.collectAsStateWithLifecycle()
                val settings by AppSettings.get(this).state.collectAsStateWithLifecycle()
                val iconCache = remember { mutableStateMapOf<String, Bitmap?>() }
                LaunchedEffect(apps) {
                    val wanted = apps.associate { it.id to it.iconFile }
                    withContext(Dispatchers.IO) {
                        wanted.forEach { (id, file) ->
                            val key = "$id:$file"
                            if (iconCache[key] == null && file != null) {
                                val bitmap = icons.load(file)
                                withContext(Dispatchers.Main) { iconCache[key] = bitmap }
                            }
                        }
                    }
                }
                val iconsById = apps.associate { it.id to iconCache["${it.id}:${it.iconFile}"] }

                val chrome = rememberGlassBackdrop()
                val overlays = remember { OverlayHost() }
                val presentation = remember { SheetPresentation() }
                val tabStates = rememberSaveableStateHolder()
                val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

                // Back from another tab's first screen returns to Apps.
                BackHandler(enabled = tab != TAB_APPS) { tab = TAB_APPS }

                CompositionLocalProvider(
                    LocalOverlayHost provides overlays,
                    LocalChromeBackdrop provides chrome,
                    LocalSheetPresentation provides presentation,
                ) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        // The app recedes behind a large sheet, like a card pushed back.
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val p = if (Motion.reduced) 0f else presentation.value
                                    if (p > 0f) {
                                        val scale = 1f - 0.07f * p
                                        scaleX = scale
                                        scaleY = scale
                                        translationY = topInset.toPx() * 0.6f * p
                                        shape = RoundedCornerShape((28f * p).dp)
                                        clip = true
                                    } else {
                                        scaleX = 1f
                                        scaleY = 1f
                                        translationY = 0f
                                        clip = false
                                    }
                                },
                        ) {
                            Box(Modifier.fillMaxSize().glassSource(chrome).background(AppColors.Background)) {
                                tabStates.SaveableStateProvider(tab) {
                                    when (tab) {
                                        TAB_APPS -> AppsScreen(
                                            apps = apps,
                                            icons = iconsById,
                                            update = homeUpdate,
                                            updateActions = updateActions,
                                            callbacks = AppsCallbacks(
                                                onOpen = { app, bounds -> openApp(app, bounds) },
                                                onAdd = { editor = EditorRequest(null, null, null, repo.nextTileColor()) },
                                                onAddFrom = { url -> editor = EditorRequest(null, null, url, repo.nextTileColor()) },
                                                onEdit = { app -> editor = EditorRequest(app, iconsById[app.id], null, app.cardColor) },
                                                onPin = { app ->
                                                    if (!ShortcutHelper.pin(this@MainActivity, app)) {
                                                        android.widget.Toast.makeText(this@MainActivity, "Your launcher doesn’t support shortcuts", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                onDelete = { deleteApp(it) },
                                                onReorder = { ids -> repo.reorder(ids) },
                                                onToggleTheme = {
                                                    val nowDark = AppColors.isDark
                                                    AppSettings.get(this@MainActivity).update {
                                                        it.copy(themeMode = if (nowDark) ThemeMode.LIGHT else ThemeMode.DARK)
                                                    }
                                                },
                                            ),
                                        )
                                        TAB_PRIVACY -> PrivacyScreen(
                                            stack = privacyStack.toList(),
                                            onPush = { privacyStack.add(it) },
                                            onPop = { if (privacyStack.size > 1) privacyStack.removeAt(privacyStack.lastIndex) },
                                            settings = settings,
                                            status = status,
                                            lists = remember { AdblockStorage.catalog(this@MainActivity).lists },
                                            lastUpdated = getSharedPreferences("webdroid", MODE_PRIVATE).getLong("filters_updated_at", 0L),
                                            onUpdate = { transform -> AppSettings.get(this@MainActivity).update(transform) },
                                            onUpdateFilters = { FilterUpdateWorker.updateNow(this@MainActivity) },
                                        )
                                        else -> SettingsScreen(
                                            stack = settingsStack.toList(),
                                            onPush = { settingsStack.add(it) },
                                            onPop = { if (settingsStack.size > 1) settingsStack.removeAt(settingsStack.lastIndex) },
                                            settings = settings,
                                            onUpdate = { transform -> AppSettings.get(this@MainActivity).update(transform) },
                                            update = update,
                                            updateLastChecked = remember(update) { AppUpdater.lastCheckedAt(this@MainActivity) },
                                            updateActions = updateActions,
                                        )
                                    }
                                }
                            }
                            GlassTabBar(
                                backdrop = chrome,
                                items = Tabs,
                                selected = tab,
                                onSelect = { tab = it },
                                onReselect = { popToRoot(it) },
                            )
                        }

                        Box(Modifier.fillMaxSize()) { OverlayLayer(overlays) }

                        EditorSheet(
                            request = editor,
                            isolatedSupported = { isolatedSupported },
                            lockSupported = { lockSupported },
                            onSave = { existing, result -> save(existing, result) },
                            onDelete = { deleteApp(it) },
                            onDismiss = { editor = null },
                        )
                    }
                }
            }
        }

        // Warm up WebView's Chromium so the first app opens instantly.
        Looper.myQueue().addIdleHandler {
            runCatching { WebView(this).destroy() }
            false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppUpdater.onResume(this)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(AppUpdater.EXTRA_INSTALL_UPDATE, false)) {
            intent.removeExtra(AppUpdater.EXTRA_INSTALL_UPDATE)
            AppUpdater.install(this)
        }
        val editId = intent.getStringExtra(EXTRA_EDIT_APP) ?: return
        intent.removeExtra(EXTRA_EDIT_APP)
        val app = repo.get(editId) ?: return
        tab = TAB_APPS
        lifecycleScope.launch {
            val icon = withContext(Dispatchers.IO) { icons.load(app.iconFile) }
            editor = EditorRequest(app, icon, null, app.cardColor)
        }
    }

    private fun popToRoot(index: Int) {
        when (index) {
            TAB_PRIVACY -> while (privacyStack.size > 1) privacyStack.removeAt(privacyStack.lastIndex)
            TAB_SETTINGS -> while (settingsStack.size > 1) settingsStack.removeAt(settingsStack.lastIndex)
        }
    }

    private fun showReleaseNotes() {
        tab = TAB_SETTINGS
        if (settingsStack.lastOrNull() != SettingsRoute.WhatsNew) {
            popToRoot(TAB_SETTINGS)
            settingsStack.add(SettingsRoute.WhatsNew)
        }
    }

    /** System bar icons, window background and next launch's splash follow WebDroid's theme. */
    private fun applyWindowTheme(dark: Boolean) {
        val background = (if (dark) ColorPalette.Dark else ColorPalette.Light).background.toArgb()
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
            navigationBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
        )
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        window.setBackgroundDrawable(ColorDrawable(background))
        if (Build.VERSION.SDK_INT >= 33) {
            val splash = when (AppSettings.get(this).state.value.themeMode) {
                ThemeMode.LIGHT -> R.style.Theme_WebDroid_Light
                ThemeMode.DARK -> R.style.Theme_WebDroid_Dark
                ThemeMode.SYSTEM -> R.style.Theme_WebDroid
            }
            runCatching { splashScreen.setSplashScreenTheme(splash) }
        }
    }

    private fun openApp(app: WebApp, bounds: Rect?) {
        val intent = WebAppActivity.intent(this, app.id)
        val view = window.decorView
        val options = if (bounds != null) {
            ActivityOptions.makeScaleUpAnimation(
                view, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt(),
            ).toBundle()
        } else null
        startActivity(intent, options)
    }

    private fun save(existing: WebApp?, result: EditorResult) {
        lifecycleScope.launch {
            val id = existing?.id ?: repo.newId()
            val base = existing ?: WebApp(id = id, name = result.name, url = result.url, cardColor = result.cardColor)
            var iconFile = base.iconFile
            if (result.iconChanged) {
                iconFile = withContext(Dispatchers.IO) {
                    if (result.icon != null) icons.save(id, result.icon) else {
                        icons.delete(id)
                        null
                    }
                }
            }
            val updated = base.copy(
                name = result.name,
                url = result.url,
                iconFile = iconFile,
                themeColor = result.themeColor ?: base.themeColor,
                cardColor = result.cardColor,
                settings = result.settings,
            )
            repo.upsert(updated)
            if (existing != null) ShortcutHelper.refresh(this@MainActivity, updated)
            editor = null
            if (existing == null) {
                // Open the new app once the sheet has gone, like installing it.
                kotlinx.coroutines.delay(420)
                openApp(updated, null)
            }
        }
    }

    private fun deleteApp(app: WebApp) {
        editor = null
        repo.delete(app.id)
        ShortcutHelper.remove(this, app)
        com.webdroid.app.web.WebNotifier.deleteChannel(this, app)
        com.webdroid.app.vpn.VpnManager.forget(this, app.id)
        lifecycleScope.launch(Dispatchers.IO) {
            icons.delete(app.id)
            getSharedPreferences("last_urls", MODE_PRIVATE).edit().remove(app.id).apply()
        }
        if (app.settings.isolatedSession && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching { ProfileStore.getInstance().deleteProfile(app.profileName) }
                .onFailure { WebDroidApp.queueProfileDeletion(this, app.profileName) }
        }
    }
}
