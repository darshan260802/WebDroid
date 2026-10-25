package com.webdroid.app

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Looper
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.webdroid.app.adblock.AdblockEngine
import com.webdroid.app.adblock.AdblockStorage
import com.webdroid.app.adblock.FilterUpdateWorker
import com.webdroid.app.data.AppSettings
import com.webdroid.app.data.IconStore
import com.webdroid.app.data.WebApp
import com.webdroid.app.data.WebAppRepository
import com.webdroid.app.ui.editor.EditorResult
import com.webdroid.app.ui.editor.EditorScreen
import com.webdroid.app.ui.home.HomeCallbacks
import com.webdroid.app.ui.home.HomeScreen
import com.webdroid.app.ui.settings.SettingsScreen
import com.webdroid.app.ui.theme.Clay
import com.webdroid.app.ui.theme.WebDroidTheme
import com.webdroid.app.web.AppLock
import com.webdroid.app.web.ShortcutHelper
import com.webdroid.app.web.WebAppActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class Editor(val appId: String?, val prefillUrl: String? = null) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_EDIT_APP = "edit_app"
    }

    private val repo by lazy { WebAppRepository.get(this) }
    private val icons by lazy { IconStore.get(this) }
    private var screen by mutableStateOf<Screen>(Screen.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Clay.Canvas.toArgb(), Clay.Canvas.toArgb()),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        // Checked lazily: querying WebView features loads Chromium.
        val isolatedSupported by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }
        val lockSupported by lazy { AppLock.isAvailable(this) }

        setContent {
            WebDroidTheme {
                val apps by repo.apps.collectAsStateWithLifecycle()
                val status by AdblockEngine.status.collectAsStateWithLifecycle()
                val settingsState by AppSettings.get(this).state.collectAsStateWithLifecycle()
                val iconCache = remember { mutableStateMapOf<String, Bitmap?>() }
                LaunchedEffect(apps) {
                    val wanted = apps.associate { it.id to it.iconFile }
                    withContext(Dispatchers.IO) {
                        wanted.forEach { (id, file) ->
                            val key = "$id:$file"
                            if (iconCache[key] == null && file != null) {
                                val bitmap = icons.load(file)
                                withContext(Dispatchers.Main) {
                                    iconCache[key] = bitmap
                                }
                            }
                        }
                    }
                }
                val iconsById = apps.associate { it.id to iconCache["${it.id}:${it.iconFile}"] }

                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

                SharedTransitionLayout {
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            when {
                                targetState is Screen.Settings ->
                                    (slideInHorizontally(tween(340)) { it / 3 } + fadeIn(tween(240))) togetherWith
                                        (slideOutHorizontally(tween(340)) { -it / 6 } + fadeOut(tween(200)))
                                initialState is Screen.Settings ->
                                    (slideInHorizontally(tween(340)) { -it / 6 } + fadeIn(tween(240))) togetherWith
                                        (slideOutHorizontally(tween(340)) { it / 3 } + fadeOut(tween(200)))
                                else -> (fadeIn(tween(260, delayMillis = 60)) + scaleIn(tween(320), initialScale = 0.97f)) togetherWith
                                    (fadeOut(tween(180)) + scaleOut(tween(320), targetScale = 1.02f))
                            }
                        },
                        label = "screens",
                    ) { target ->
                        when (target) {
                            Screen.Home -> HomeScreen(
                                apps = apps,
                                icons = iconsById,
                                adblockStatus = status,
                                adblockEnabled = settingsState.adBlockEnabled,
                                callbacks = HomeCallbacks(
                                    onOpen = { app, bounds -> openApp(app, bounds) },
                                    onCreate = { screen = Screen.Editor(null) },
                                    onCreateFrom = { url -> screen = Screen.Editor(null, url) },
                                    onEdit = { screen = Screen.Editor(it.id) },
                                    onSettings = { screen = Screen.Settings },
                                    onPin = { app ->
                                        if (!ShortcutHelper.pin(this@MainActivity, app)) {
                                            android.widget.Toast.makeText(this@MainActivity, "Your launcher doesn't support shortcuts", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onDelete = { deleteApp(it) },
                                ),
                                sharedScope = this@SharedTransitionLayout,
                                animatedScope = this@AnimatedContent,
                            )
                            is Screen.Editor -> {
                                val existing = target.appId?.let { id -> apps.firstOrNull { it.id == id } }
                                EditorScreen(
                                    existing = existing,
                                    prefillUrl = target.prefillUrl,
                                    existingIcon = existing?.let { iconsById[it.id] },
                                    defaultCardColor = repo.nextCardColor(),
                                    isolatedSupported = isolatedSupported,
                                    lockSupported = lockSupported,
                                    onSave = { result -> save(existing, result) },
                                    onCancel = { screen = Screen.Home },
                                    onDelete = { deleteApp(it) },
                                    sharedScope = this@SharedTransitionLayout,
                                    animatedScope = this@AnimatedContent,
                                )
                            }
                            Screen.Settings -> SettingsScreen(
                                settings = settingsState,
                                status = status,
                                lists = remember { AdblockStorage.catalog(this@MainActivity).lists },
                                lastUpdated = getSharedPreferences("webdroid", MODE_PRIVATE).getLong("filters_updated_at", 0L),
                                onUpdate = { transform -> AppSettings.get(this@MainActivity).update(transform) },
                                onUpdateFilters = { FilterUpdateWorker.updateNow(this@MainActivity) },
                                onBack = { screen = Screen.Home },
                            )
                        }
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

    private fun handleIntent(intent: Intent?) {
        val editId = intent?.getStringExtra(EXTRA_EDIT_APP) ?: return
        if (repo.get(editId) != null) screen = Screen.Editor(editId)
        intent.removeExtra(EXTRA_EDIT_APP)
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
            screen = Screen.Home
            if (existing == null) {
                // Open the new app right away, like installing it.
                kotlinx.coroutines.delay(380)
                openApp(updated, null)
            }
        }
    }

    private fun deleteApp(app: WebApp) {
        repo.delete(app.id)
        ShortcutHelper.remove(this, app)
        lifecycleScope.launch(Dispatchers.IO) {
            icons.delete(app.id)
            getSharedPreferences("last_urls", MODE_PRIVATE).edit().remove(app.id).apply()
        }
        if (app.settings.isolatedSession && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching { ProfileStore.getInstance().deleteProfile(app.profileName) }
                .onFailure { WebDroidApp.queueProfileDeletion(this, app.profileName) }
        }
        screen = Screen.Home
    }
}

