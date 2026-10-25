package com.webdroid.app.web

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ProfileStore
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.webdroid.app.MainActivity
import com.webdroid.app.R
import com.webdroid.app.adblock.AdblockEngine
import com.webdroid.app.adblock.RequestFilter
import com.webdroid.app.data.AppSettings
import com.webdroid.app.data.IconStore
import com.webdroid.app.data.SiteMetadataFetcher
import com.webdroid.app.data.VideoMode
import com.webdroid.app.data.WebApp
import com.webdroid.app.data.WebAppRepository
import com.webdroid.app.data.WebAppSettings
import com.webdroid.app.player.PlayerController
import com.webdroid.app.player.PlayerOverlay
import com.webdroid.app.player.VideoRequest
import com.webdroid.app.vpn.TorController
import com.webdroid.app.vpn.VpnManager
import com.webdroid.app.vpn.VpnProfiles
import com.webdroid.app.ui.components.Haptics
import com.webdroid.app.ui.theme.WebDroidTheme
import com.webdroid.app.util.Net
import com.webdroid.app.util.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * One installed-feeling web app. Each app runs as its own document task, so
 * it gets its own card (label, icon, colour) in Android's Recents.
 */
class WebAppActivity : FragmentActivity(), WebAppActions {

    companion object {
        fun intent(context: Context, appId: String): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("webdroid://app/$appId"), context, WebAppActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)

        // Reopen where the user left off, like a native app would.
        private const val LAST_URL_MAX_AGE_MS = 6 * 60 * 60 * 1000L
    }

    private val repo by lazy { WebAppRepository.get(this) }
    private val json = Json { ignoreUnknownKeys = true }
    private val ui = WebAppUiState()
    private lateinit var player: PlayerController

    private var app by mutableStateOf<WebApp?>(null)
    private var icon by mutableStateOf<Bitmap?>(null)

    private lateinit var root: FrameLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout
    private var webView: WebDroidWebView? = null
    private var popupWebView: WebView? = null

    private val sniffer = MediaSniffer()
    @Volatile private var documentUrl = ""
    @Volatile private var adblockOn = true
    /** This app's sites are routed through its VPN (set once routing is in place). */
    @Volatile private var vpnAttached = false
    private var vpnStatusJob: kotlinx.coroutines.Job? = null
    private val blockedThisPage = AtomicInteger(0)

    private var isolatedSupported = false
    private var lockSupported = false
    private var currentBarColor = Color.WHITE
    private var barAnimator: ValueAnimator? = null
    private var themeFromMeta = false

    private var mainFrameReply: JavaScriptReplyProxy? = null
    private var lastVideoReply: JavaScriptReplyProxy? = null
    private var lastVideoAspect = 16f / 9f
    private var pageMediaPlaying = false

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermissionResult: ((Map<String, Boolean>) -> Unit)? = null
    private val blobDownloads = HashMap<String, Pair<String, ByteArrayOutputStream>>()
    private var unlocking = false
    /** Read from WebView's JavaBridge thread for Notification.permission. */
    @Volatile private var grantedSnapshot: Set<String> = emptySet()
    /** Page notification id -> frame that created it (for click/close events). */
    private val notificationFrames = HashMap<String, JavaScriptReplyProxy>()
    private var lastPullStage = 0

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        pendingPermissionResult?.invoke(grants)
        pendingPermissionResult = null
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PlayerController.ACTION_PIP_TOGGLE) player.togglePlay()
        }
    }

    private val haptics by lazy { Haptics.of(window.decorView) }

    /* ---------------------------------------------------------------------- */
    /* Lifecycle                                                               */
    /* ---------------------------------------------------------------------- */

    override fun onCreate(savedInstanceState: Bundle?) {
        val id = WebApp.idFromUri(intent?.data)
        val loaded = id?.let { repo.get(it) }
        if (loaded?.settings?.forceDark == true) setTheme(R.style.Theme_WebDroid_WebApp_Dark)
        super.onCreate(savedInstanceState)
        if (loaded == null) {
            finishAndRemoveTask()
            return
        }
        app = loaded
        adblockOn = loaded.settings.adBlock && AppSettings.get(this).state.value.adBlockEnabled
        grantedSnapshot = loaded.settings.grantedPermissions
        AdblockEngine.init(this)
        (application as? com.webdroid.app.WebDroidApp)?.ensureWebViewSetup()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false

        isolatedSupported = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        lockSupported = AppLock.isAvailable(this)
        icon = IconStore.get(this).load(loaded.iconFile)
        currentBarColor = loaded.themeColor ?: defaultBarColor()

        player = PlayerController(
            this, lifecycleScope,
            sessionIntent = { ShortcutHelper.launchIntent(this, app!!) },
            cookieJar = { WebViewCookieJar(cookieManager()) },
            httpClient = { VpnManager.httpClient(this, app) },
        )
        player.onVisibilityChanged = { visible -> onPlayerVisibility(visible) }
        player.onClosed = { request, position -> onPlayerClosed(request, position) }
        player.onPipParamsChanged = { updatePipParams() }
        player.onCannotPlay = { request -> onPlayerCannotPlay(request) }

        buildViews()
        applyBarColor(currentBarColor, animate = false)
        updateTaskDescription()

        createWebView()
        if (loaded.settings.vpn != null) {
            // Nothing may load before this app's sites are routed through its VPN.
            watchVpnStatus(loaded)
            VpnManager.attach(this, loaded) { ok, message ->
                if (isDestroyed) {
                    if (ok) VpnManager.detach(loaded)
                    return@attach
                }
                vpnAttached = ok
                if (!ok) {
                    hideSplash()
                    ui.vpnStatus = null
                    ui.error = PageError(loaded.url, message ?: "The VPN isn't available")
                    return@attach
                }
                val restored = savedInstanceState?.let { webView?.restoreState(it) } != null
                if (!restored) webView?.loadUrl(startUrl(loaded))
            }
        } else {
            val restored = savedInstanceState?.let { webView?.restoreState(it) } != null
            if (!restored) webView?.loadUrl(startUrl(loaded))
        }

        if (loaded.settings.appLock && lockSupported) {
            ui.locked = true
            setRecentsScreenshotsAllowed(false)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        ContextCompat.registerReceiver(this, pipReceiver, IntentFilter(PlayerController.ACTION_PIP_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED)

        repo.markOpened(loaded.id)
        ShortcutHelper.reportUsed(this, loaded)

        lifecycleScope.launch {
            repo.apps.collect { list ->
                val updated = list.firstOrNull { it.id == loaded.id }
                if (updated == null) {
                    finishAndRemoveTask()
                } else if (updated != app) {
                    val previous = app
                    app = updated
                    if (previous != null) onSettingsChanged(previous, updated)
                }
            }
        }

        // Fallback if the page never reports a first paint.
        lifecycleScope.launch {
            delay(8000)
            ui.splashVisible = false
        }
    }

    /** Shows Tor's bootstrap progress while a Tor app connects. */
    private fun watchVpnStatus(app: WebApp) {
        vpnStatusJob?.cancel()
        if (app.settings.vpn != VpnProfiles.TOR_ID) {
            ui.vpnStatus = null
            return
        }
        vpnStatusJob = lifecycleScope.launch {
            TorController.status.collect { status ->
                ui.vpnStatus = when (status.phase) {
                    TorController.Phase.ON -> null
                    TorController.Phase.ERROR -> "Tor: ${status.summary}"
                    else -> "Connecting to Tor · ${status.progress}%"
                }
            }
        }
    }

    private fun startUrl(app: WebApp): String {
        val prefs = getSharedPreferences("last_urls", MODE_PRIVATE)
        val last = prefs.getString(app.id, null)
        val at = prefs.getLong("${app.id}_at", 0L)
        return if (last != null && System.currentTimeMillis() - at < LAST_URL_MAX_AGE_MS && UrlUtils.sameSite(last, app.url)) last else app.url
    }

    private fun rememberUrl(url: String) {
        val current = app ?: return
        if (!url.startsWith("http")) return
        getSharedPreferences("last_urls", MODE_PRIVATE).edit()
            .putString(current.id, url)
            .putLong("${current.id}_at", System.currentTimeMillis())
            .apply()
    }

    override fun onStart() {
        super.onStart()
        // Foreground services may only be started while the app is visible.
        app?.let { if (it.settings.keepAlive) KeepAliveService.connect(this, it.id, it.name) }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        if (ui.locked && !unlocking) unlock()
    }

    override fun onPause() {
        super.onPause()
        cookieManager().flush()
        val keepAlive = app?.settings?.keepAlive == true
        if (!keepAlive && !pageMediaPlaying && !player.state.visible) webView?.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val pageId = intent.getStringExtra(WebNotifier.EXTRA_NOTIFICATION) ?: return
        // A notification of this app was tapped: let the page react (open the chat...).
        val frame = notificationFrames.remove(pageId) ?: mainFrameReply
        frame?.let { sendCommand(it, "{\"cmd\":\"notifyClick\",\"id\":${kotlinx.serialization.json.JsonPrimitive(pageId)}}") }
    }

    override fun onStop() {
        super.onStop()
        player.onActivityStopped()
        val current = app
        if (current != null && current.settings.appLock && lockSupported && !isChangingConfigurations &&
            !(Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode)
        ) {
            ui.locked = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipReceiver) }
        if (vpnAttached) {
            vpnAttached = false
            app?.let { VpnManager.detach(it) }
        }
        if (!isChangingConfigurations) app?.let { KeepAliveService.disconnect(this, it.id) }
        if (::player.isInitialized && !isChangingConfigurations && player.state.visible) player.close()
        popupWebView?.destroy()
        popupWebView = null
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && player.state.visible && player.state.isPlaying) player.enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        player.onPipModeChanged(isInPictureInPictureMode)
        if (!isInPictureInPictureMode && lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED) {
            // The PiP window was swiped away: stop, like any video app.
            player.exoPlayer?.pause()
        }
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= 26) {
            val params = player.pipParams() ?: return
            runCatching { setPictureInPictureParams(params) }
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Views                                                                   */
    /* ---------------------------------------------------------------------- */

    private fun buildViews() {
        root = FrameLayout(this)
        webContainer = FrameLayout(this)
        fullscreenContainer = FrameLayout(this).apply { visibility = View.GONE }
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                WebDroidTheme {
                    val current = app
                    if (current != null) {
                        WebAppOverlay(
                            app = current,
                            icon = icon,
                            state = ui,
                            actions = this@WebAppActivity,
                            playerVisible = player.state.visible,
                            isolatedSupported = isolatedSupported,
                            lockSupported = lockSupported,
                            player = {
                                PlayerOverlay(
                                    controller = player,
                                    onDownload = { request -> downloadVideo(request) },
                                    onPlayInPage = { request -> player.close(); ui.showToast("Playing in the page") ; lastVideoReply?.let { sendCommand(it, "{\"cmd\":\"resume\",\"id\":${request.frameVideoId ?: 0}}") } },
                                )
                            },
                            popup = { PopupHost() },
                        )
                    }
                }
            }
        }
        root.addView(webContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(fullscreenContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(composeView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val immersive = player.state.visible || ui.siteFullscreen
            webContainer.updateLayoutParams<FrameLayout.LayoutParams> {
                if (immersive) {
                    setMargins(0, 0, 0, 0)
                } else {
                    setMargins(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
                }
            }
            insets
        }
    }

    @androidx.compose.runtime.Composable
    private fun PopupHost() {
        val popup = popupWebView ?: return
        androidx.compose.ui.viewinterop.AndroidView(
            factory = {
                (popup.parent as? ViewGroup)?.removeView(popup)
                popup
            },
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        )
    }

    private fun applyImmersive(immersive: Boolean) {
        val controller = WindowInsetsControllerCompat(window, root)
        if (immersive) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun defaultBarColor(): Int {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (night || app?.settings?.forceDark == true) 0xFF121212.toInt() else Color.WHITE
    }

    private fun applyBarColor(color: Int, animate: Boolean = true) {
        val target = color or 0xFF000000.toInt()
        barAnimator?.cancel()
        if (!animate) {
            root.setBackgroundColor(target)
        } else {
            barAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentBarColor, target).apply {
                duration = 260
                addUpdateListener { root.setBackgroundColor(it.animatedValue as Int) }
                start()
            }
        }
        currentBarColor = target
        val light = ColorUtils.calculateLuminance(target) > 0.55
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        ui.barColor = ComposeColor(target)
        ui.lightBars = light
    }

    private fun updateTaskDescription() {
        val current = app ?: return
        val tile = IconStore.get(this).renderTile(current, 192)
        val color = CardColorArgb.of(current.cardColor)
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(current.name, tile, color))
    }

    private fun setRecentsScreenshotsAllowed(allowed: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(allowed)
    }

    /* ---------------------------------------------------------------------- */
    /* WebView                                                                 */
    /* ---------------------------------------------------------------------- */

    private fun cookieManager(): CookieManager {
        val current = app
        if (current != null && current.settings.isolatedSession && isolatedSupported) {
            runCatching { return ProfileStore.getInstance().getOrCreateProfile(current.profileName).cookieManager }
        }
        return CookieManager.getInstance()
    }

    private fun userAgent(desktop: Boolean): String {
        val base = WebSettings.getDefaultUserAgent(this)
            .replace("; wv", "")
            .replace(Regex(" Version/\\d+(\\.\\d+)*"), "")
        if (!desktop) return base
        val chrome = Regex("Chrome/[\\d.]+").find(base)?.value ?: "Chrome/141.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun configure(view: WebView, current: WebApp, isPopup: Boolean) {
        if (current.settings.isolatedSession && isolatedSupported) {
            runCatching {
                ProfileStore.getInstance().getOrCreateProfile(current.profileName)
                WebViewCompat.setProfile(view, current.profileName)
            }
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(!isPopup)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgent(current.settings.desktopMode)
            textZoom = 100
            offscreenPreRaster = true
            setGeolocationEnabled(true)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, current.settings.forceDark)
        }
        cookieManager().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.setBackgroundColor(if (current.settings.forceDark) 0xFF121212.toInt() else Color.WHITE)

        val messageName = InjectedScript.messageBridgeName(this)
        view.addJavascriptInterface(
            CosmeticBridge(
                enabled = { adblockOn },
                permissionLookup = { origin -> notificationPermissionFor(origin) },
                vpnActive = { app?.settings?.vpn != null },
            ),
            InjectedScript.cosmeticBridgeName(this),
        )
        // Don't announce "embedded WebView" to sites: Google and others refuse
        // sign-in when they see the X-Requested-With header.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(view.settings, emptySet())
        }
        if (current.settings.keepAlive && Build.VERSION.SDK_INT >= 26) {
            view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(view, messageName, setOf("*")) { _, message, origin, isMainFrame, reply ->
                onPageMessage(message, origin, isMainFrame, reply, isPopup)
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, InjectedScript.get(this), setOf("*"))
        }

        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            onDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun createWebView() {
        val current = app ?: return
        val view = WebDroidWebView(this)
        configure(view, current, isPopup = false)
        view.webViewClient = Client()
        view.webChromeClient = Chrome()
        view.pullCallbacks = PullHandler()
        view.setOnLongClickListener { onLongPress(view) }
        webContainer.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        webView = view
    }

    private fun recreateWebView(reloadUrl: String?) {
        webView?.let {
            webContainer.removeView(it)
            it.destroy()
        }
        webView = null
        createWebView()
        webView?.loadUrl(reloadUrl ?: app?.url ?: return)
    }

    private fun hideSplash() {
        if (ui.splashVisible) ui.splashVisible = false
    }

    private fun updateNavState() {
        val view = webView ?: return
        ui.canGoBack = view.canGoBack()
        ui.canGoForward = view.canGoForward()
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // Route a VPN app's new domains before the request leaves.
            if (vpnAttached) app?.let { VpnManager.observe(it, request.url.host) }
            sniffer.observe(request)
            if (request.isForMainFrame) {
                documentUrl = request.url.toString()
                return null
            }
            if (!adblockOn) return null
            if (!AdblockEngine.isReady && !AdblockEngine.awaitReady(1500)) return null
            val response = RequestFilter.filter(applicationContext, request, documentUrl)
            if (response != null) {
                val count = blockedThisPage.incrementAndGet()
                if (count % 4 == 1) runOnUiThread { ui.blockedCount = blockedThisPage.get() }
            }
            return response
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val current = app ?: return false
            val url = request.url.toString()
            return when (ExternalNavigation.decide(current, request, ui.url)) {
                ExternalNavigation.Decision.IN_APP -> false
                ExternalNavigation.Decision.EXTERNAL -> {
                    if (!ExternalNavigation.open(this@WebAppActivity, url)) view.loadUrl(url)
                    true
                }
                ExternalNavigation.Decision.HANDLED -> {
                    if (!ExternalNavigation.open(this@WebAppActivity, url)) ui.showToast("No app can open this link")
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            ui.url = url
            documentUrl = url
            ui.error = null
            ui.videoOffer = null
            blockedThisPage.set(0)
            ui.blockedCount = 0
            themeFromMeta = false
            mainFrameReply = null
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                view.evaluateJavascript(InjectedScript.get(this@WebAppActivity), null)
            }
            updateNavState()
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            hideSplash()
        }

        override fun onPageFinished(view: WebView, url: String) {
            hideSplash()
            if (ui.pullPhase == PullPhase.REFRESHING) endRefresh()
            updateNavState()
            rememberUrl(url)
            ui.blockedCount = blockedThisPage.get()
            // Persist fresh login cookies right away rather than on the next flush tick.
            cookieManager().flush()
        }

        override fun onReceivedHttpAuthRequest(view: WebView, handler: android.webkit.HttpAuthHandler, host: String, realm: String) {
            if (handler.useHttpAuthUsernamePassword()) {
                @Suppress("DEPRECATION")
                val saved = view.getHttpAuthUsernamePassword(host, realm)
                if (saved != null && saved.size == 2 && saved[0] != null && saved[1] != null) {
                    handler.proceed(saved[0], saved[1])
                    return
                }
            }
            ui.httpAuth = HttpAuthPrompt(host, realm, handler)
            hideSplash()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            ui.url = url
            updateNavState()
            rememberUrl(url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val code = error.errorCode
            if (code == ERROR_HOST_LOOKUP || code == ERROR_CONNECT || code == ERROR_TIMEOUT ||
                code == ERROR_PROXY_AUTHENTICATION || code == ERROR_FAILED_SSL_HANDSHAKE || code == ERROR_IO
            ) {
                ui.error = PageError(
                    request.url.toString(),
                    when (code) {
                        ERROR_HOST_LOOKUP -> "Check your connection and try again."
                        ERROR_TIMEOUT -> "The site took too long to respond."
                        ERROR_FAILED_SSL_HANDSHAKE -> "A secure connection couldn't be established."
                        else -> error.description?.toString().orEmpty()
                    },
                )
                hideSplash()
                if (ui.pullPhase == PullPhase.REFRESHING) endRefresh()
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            val url = ui.url.ifBlank { null }
            if (view === webView) {
                recreateWebView(url)
                ui.showToast("Page reloaded")
            } else {
                closePopup()
            }
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (newProgress >= 85) hideSplash()
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            ui.title = title.orEmpty()
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            enterSiteFullscreen(view, callback)
        }

        override fun onHideCustomView() {
            exitSiteFullscreen(fromPage = true)
        }

        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            return try {
                fileChooser.launch(fileChooserParams.createIntent())
                true
            } catch (e: Exception) {
                try {
                    fileChooser.launch(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"))
                    true
                } catch (e2: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread { handlePermissionRequest(request) }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            val prompt = ui.permissionPrompt
            if (prompt is PermissionPrompt.Media && prompt.request === request) ui.permissionPrompt = null
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            val current = app ?: return callback.invoke(origin, false, false)
            if ("location:$origin" in current.settings.grantedPermissions) {
                withRuntimePermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { granted ->
                    callback.invoke(origin, granted, false)
                }
            } else {
                ui.permissionPrompt = PermissionPrompt.Location(origin, callback)
            }
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            return openPopup(resultMsg)
        }

        override fun onCloseWindow(window: WebView) {
            if (window === popupWebView) closePopup()
        }

        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            ui.jsDialog = JsDialog.Alert(message.orEmpty(), result)
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            ui.jsDialog = JsDialog.Confirm(message.orEmpty(), result)
            return true
        }

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            ui.jsDialog = JsDialog.Prompt(message.orEmpty(), defaultValue.orEmpty(), result)
            return true
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Pull gestures                                                           */
    /* ---------------------------------------------------------------------- */

    private inner class PullHandler : WebDroidWebView.PullCallbacks {
        private val density = resources.displayMetrics.density
        private val refreshPx = PULL_REFRESH_DP * density
        private val menuPx = PULL_MENU_DP * density

        private fun resist(raw: Float): Float {
            val max = 220 * density
            return max * (1f - kotlin.math.exp(-raw / max))
        }

        override fun onPullStart() {
            if (ui.pullPhase == PullPhase.REFRESHING) return
            ui.pullPhase = PullPhase.PULLING
            lastPullStage = 0
        }

        override fun onPull(distancePx: Float) {
            if (ui.pullPhase != PullPhase.PULLING) return
            val d = resist(distancePx)
            ui.pullDistance = d
            val stage = when {
                d >= menuPx -> 2
                d >= refreshPx -> 1
                else -> 0
            }
            if (stage != lastPullStage) {
                if (stage > lastPullStage) haptics.tick() else haptics.tap()
                lastPullStage = stage
            }
        }

        override fun onPullEnd(distancePx: Float) {
            if (ui.pullPhase != PullPhase.PULLING) return
            val d = resist(distancePx)
            when {
                d >= menuPx -> {
                    haptics.confirm()
                    animatePullTo(0f) { ui.pullPhase = PullPhase.IDLE }
                    ui.menuOpen = true
                }
                d >= refreshPx -> {
                    haptics.confirm()
                    ui.pullPhase = PullPhase.REFRESHING
                    ui.pullDistance = refreshPx
                    webView?.reload()
                    lifecycleScope.launch {
                        delay(12_000)
                        if (ui.pullPhase == PullPhase.REFRESHING) endRefresh()
                    }
                }
                else -> animatePullTo(0f) { ui.pullPhase = PullPhase.IDLE }
            }
        }
    }

    private fun animatePullTo(target: Float, end: () -> Unit) {
        ValueAnimator.ofFloat(ui.pullDistance, target).apply {
            duration = 220
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { ui.pullDistance = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = end()
            })
            start()
        }
    }

    private fun endRefresh() {
        animatePullTo(0f) { ui.pullPhase = PullPhase.IDLE }
    }

    /* ---------------------------------------------------------------------- */
    /* Page messages (theme colour, video, blob downloads)                     */
    /* ---------------------------------------------------------------------- */

    private fun onPageMessage(
        message: WebMessageCompat,
        origin: Uri,
        isMainFrame: Boolean,
        reply: JavaScriptReplyProxy,
        fromPopup: Boolean,
    ) {
        val data = message.data ?: return
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        if (isMainFrame && !fromPopup) mainFrameReply = reply
        when (obj.str("t")) {
            "theme" -> if (isMainFrame && !fromPopup) onThemeColor(obj)
            "video" -> if (!fromPopup) onVideoMessage(obj, reply)
            "videoPause" -> pageMediaPlaying = false
            "media" -> obj.str("url")?.let { url ->
                sniffer.observePage(url, obj.str("kind").orEmpty(), obj.bool("master"), obj.bool("live"), obj.str("page"))
            }
            "blob" -> onBlobChunk(obj)
            "notify" -> onPageNotification(obj, reply)
            "notifyClose" -> obj.str("id")?.let { id -> app?.let { WebNotifier.cancel(this, it, id) }; notificationFrames.remove(id) }
            "notifyPermission" -> onNotificationPermissionRequest(obj, reply)
            "share" -> onPageShare(obj, reply)
            "print" -> printPage(obj.str("title"))
            "seeked" -> Unit
        }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull == true
    private fun JsonObject.num(key: String): Double = this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0

    private fun onThemeColor(obj: JsonObject) {
        val meta = obj.bool("meta")
        if (!meta && themeFromMeta) return
        val raw = obj.str("color") ?: return
        if (raw.startsWith("rgba") && raw.replace(" ", "").endsWith(",0)")) return
        val color = SiteMetadataFetcher.parseColor(raw) ?: return
        themeFromMeta = meta
        applyBarColor(color)
        val current = app
        if (meta && current != null && current.themeColor != color) {
            repo.update(current.id) { it.copy(themeColor = color) }
        }
    }

    private fun sendCommand(reply: JavaScriptReplyProxy, command: String) {
        runCatching { reply.postMessage(command) }
    }

    private fun onVideoMessage(obj: JsonObject, reply: JavaScriptReplyProxy) {
        val current = app ?: return
        val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return
        lastVideoReply = reply
        val w = obj.num("w")
        val h = obj.num("h")
        if (w > 0 && h > 0) lastVideoAspect = (w / h).toFloat()
        val event = obj.str("event")
        val fullscreen = event == "fullscreen"
        if (event != "play" && !fullscreen) return
        if (event == "play") pageMediaPlaying = true

        val mode = current.settings.videoMode
        if (mode == VideoMode.OFF || player.state.visible) return
        if (ui.siteFullscreen && !fullscreen) return
        if (!fullscreen) {
            // Only videos the user started: a tap in the page (any frame) shortly
            // before, including a tap that navigated to the video's page.
            val sinceTap = SystemClock.uptimeMillis() - (webView?.lastTapAt ?: 0L)
            val tapped = obj.bool("gesture") || sinceTap < 3_000
            val recentTap = tapped || sinceTap < 10_000
            if (!recentTap) return
            // Muted autoplay is a feed preview unless the user just tapped it.
            if (obj.bool("muted") && !tapped) return
            if (obj.bool("loop") && obj.bool("muted")) return
            val duration = obj.num("dur")
            if (duration > 0 && duration < 4 && obj.bool("loop")) return
            val area = obj.num("area")
            val viewport = obj.num("viewport").coerceAtLeast(1.0)
            if (area > 0 && area / viewport < 0.05) return
        }

        val request = resolveStream(obj, id) ?: return
        when (mode) {
            VideoMode.AUTO -> {
                if (fullscreen) exitSiteFullscreen(fromPage = false)
                sendCommand(reply, "{\"cmd\":\"pause\",\"id\":$id}")
                openPlayer(request)
            }
            VideoMode.BUTTON -> ui.videoOffer = VideoOffer(request, true)
            VideoMode.OFF -> Unit
        }
    }

    /** The native player couldn't open the stream: hand the video back to the page. */
    private fun onPlayerCannotPlay(request: VideoRequest) {
        ui.showToast("Playing in the page instead")
        val id = request.frameVideoId ?: return
        lastVideoReply?.let { sendCommand(it, "{\"cmd\":\"resume\",\"id\":$id}") }
        lastVideoReply?.let { sendCommand(it, "{\"cmd\":\"togglePlay\"}") }
    }

    private fun resolveStream(obj: JsonObject, id: Int): VideoRequest? {
        val current = app ?: return null
        val src = obj.str("src").orEmpty()
        val library = obj.str("lib").orEmpty()
        val pageUrl = obj.str("pageUrl")?.takeIf { it.startsWith("http") } ?: ui.url
        fun kindOfUrl(u: String): MediaSniffer.Kind? {
            val lower = u.lowercase().substringBefore('?')
            return when {
                lower.endsWith(".m3u8") || lower.endsWith(".m3u") -> MediaSniffer.Kind.HLS
                lower.endsWith(".mpd") -> MediaSniffer.Kind.DASH
                else -> sniffer.find(u)?.kind
            }
        }
        var referer: String? = null
        val (url, kind) = when {
            src.startsWith("http") -> src to (kindOfUrl(src) ?: if (src.contains(".m3u8", true)) MediaSniffer.Kind.HLS else null)
            library.startsWith("http") -> library to kindOfUrl(library)
            else -> {
                val stream = sniffer.bestManifest(maxAgeMs = 10 * 60_000L) ?: sniffer.latestProgressive() ?: return null
                referer = stream.referer
                stream.url to stream.kind
            }
        }
        return VideoRequest(
            url = url,
            // null: unknown, the player probes the stream to find out.
            kind = kind,
            referer = referer?.takeIf { it.startsWith("http") },
            title = obj.str("title")?.ifBlank { null } ?: ui.title,
            pageUrl = pageUrl,
            userAgent = webView?.settings?.userAgentString ?: Net.MOBILE_USER_AGENT,
            posterUrl = obj.str("poster"),
            appId = current.id,
            appName = current.name,
            frameVideoId = id,
            startPositionMs = (obj.num("time") * 1000).toLong(),
        )
    }

    private fun openPlayer(request: VideoRequest) {
        ui.videoOffer = null
        if (Build.VERSION.SDK_INT >= 33 && AppSettings.get(this).state.value.backgroundPlay &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("webdroid", MODE_PRIVATE).getBoolean("asked_notifications", false)
        ) {
            getSharedPreferences("webdroid", MODE_PRIVATE).edit().putBoolean("asked_notifications", true).apply()
            withRuntimePermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { }
        }
        player.open(request)
    }

    private fun onPlayerVisibility(visible: Boolean) {
        applyImmersive(visible)
        webView?.pullEnabled = !visible
        if (visible) {
            player.applyOrientation()
            updatePipParams()
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (Build.VERSION.SDK_INT >= 31) {
                // Leaving the app without a video must not shrink the page into PiP.
                runCatching { setPictureInPictureParams(android.app.PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()) }
            }
        }
    }

    private fun onPlayerClosed(request: VideoRequest, positionMs: Long) {
        val id = request.frameVideoId ?: return
        lastVideoReply?.let { sendCommand(it, "{\"cmd\":\"resume\",\"id\":$id,\"time\":${positionMs / 1000.0}}") }
    }

    private fun downloadVideo(request: VideoRequest) {
        onDownload(request.url, request.userAgent, null, "video/mp4")
    }

    /* ---------------------------------------------------------------------- */
    /* Site fullscreen with native gestures                                    */
    /* ---------------------------------------------------------------------- */

    private fun enterSiteFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        // A site going fullscreen is a clear "watch this": offer the native player.
        if (app?.settings?.videoMode != VideoMode.OFF && !player.state.visible) {
            (lastVideoReply ?: mainFrameReply)?.let { sendCommand(it, "{\"cmd\":\"describe\",\"event\":\"fullscreen\"}") }
        }
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        var volumeAcc = 0f
        val layout = FullscreenGestureLayout(this, view, object : FullscreenGestureLayout.Callbacks {
            override fun onBrightness(delta: Float): String {
                val attrs = window.attributes
                val currentValue = if (attrs.screenBrightness >= 0) attrs.screenBrightness else 0.5f
                attrs.screenBrightness = (currentValue + delta).coerceIn(0.01f, 1f)
                window.attributes = attrs
                return "☀  ${(attrs.screenBrightness * 100).toInt()}%"
            }

            override fun onVolume(delta: Float): String {
                val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                volumeAcc += delta * maxVolume
                val steps = volumeAcc.toInt()
                if (steps != 0) {
                    volumeAcc -= steps
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, maxVolume), 0)
                }
                return "🔊  ${audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxVolume}%"
            }

            override fun onSeekPreview(seconds: Int): String =
                (if (seconds >= 0) "+" else "−") + PlayerController.formatTime(kotlin.math.abs(seconds) * 1000L)

            override fun onSeekCommit(seconds: Int) {
                lastVideoReply?.let { sendCommand(it, "{\"cmd\":\"seekBy\",\"delta\":$seconds}") }
            }
        })
        fullscreenContainer.addView(layout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        fullscreenContainer.visibility = View.VISIBLE
        ui.siteFullscreen = true
        webView?.pullEnabled = false
        applyImmersive(true)
        if (AppSettings.get(this).state.value.autoRotate && lastVideoAspect >= 1.05f) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun exitSiteFullscreen(fromPage: Boolean) {
        if (customView == null) return
        fullscreenContainer.removeAllViews()
        fullscreenContainer.visibility = View.GONE
        customView = null
        val callback = customViewCallback
        customViewCallback = null
        if (!fromPage) callback?.onCustomViewHidden()
        ui.siteFullscreen = false
        webView?.pullEnabled = true
        applyImmersive(false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val attrs = window.attributes
        attrs.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attrs
    }

    /* ---------------------------------------------------------------------- */
    /* Popups                                                                  */
    /* ---------------------------------------------------------------------- */

    @SuppressLint("SetJavaScriptEnabled")
    private fun openPopup(resultMsg: Message): Boolean {
        val current = app ?: return false
        closePopup()
        val popup = WebView(this)
        configure(popup, current, isPopup = true)
        var decided = false
        popup.webViewClient = object : WebViewClient() {
            fun decide(url: String): Boolean {
                if (decided) return false
                decided = true
                return when {
                    UrlUtils.sameSite(url, current.url) && !ExternalNavigation.isAuthHost(url) -> {
                        // target=_blank within the same site: stay in the app window, no tabs.
                        webView?.loadUrl(url)
                        closePopup()
                        true
                    }
                    ExternalNavigation.isAuthHost(url) || !current.settings.openLinksExternally -> {
                        ui.popupTitle = UrlUtils.displayHost(url)
                        ui.popupOpen = true
                        false
                    }
                    else -> {
                        if (!ExternalNavigation.open(this@WebAppActivity, url)) {
                            ui.popupTitle = UrlUtils.displayHost(url)
                            ui.popupOpen = true
                            return false
                        }
                        closePopup()
                        true
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (!url.startsWith("http")) {
                    ExternalNavigation.open(this@WebAppActivity, url)
                    return true
                }
                return decide(url)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (url.startsWith("http") && !decided) decide(url)
                if (ui.popupOpen) ui.popupTitle = UrlUtils.displayHost(url)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!adblockOn || request.isForMainFrame || !AdblockEngine.isReady) return null
                return RequestFilter.filter(applicationContext, request, request.requestHeaders["Referer"] ?: documentUrl)
            }
        }
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) = closePopup()
            override fun onReceivedTitle(view: WebView, title: String?) {
                if (!title.isNullOrBlank() && ui.popupOpen) ui.popupTitle = title
            }
        }
        popupWebView = popup
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    override fun closePopup() {
        val popup = popupWebView ?: return
        ui.popupOpen = false
        popupWebView = null
        lifecycleScope.launch {
            delay(350)
            (popup.parent as? ViewGroup)?.removeView(popup)
            popup.destroy()
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Permissions                                                             */
    /* ---------------------------------------------------------------------- */

    private fun withRuntimePermissions(permissions: Array<String>, result: (Boolean) -> Unit) {
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            result(true)
            return
        }
        pendingPermissionResult = { grants -> result(grants.values.any { it }) }
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun handlePermissionRequest(request: PermissionRequest) {
        val current = app ?: return request.deny()
        val resources = request.resources.toSet()
        val camera = PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources
        val mic = PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources
        if (!camera && !mic) {
            if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in resources) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
            } else {
                request.deny()
            }
            return
        }
        val origin = request.origin.toString()
        val granted = (!camera || "camera:$origin" in current.settings.grantedPermissions) &&
            (!mic || "mic:$origin" in current.settings.grantedPermissions)
        if (granted) grantMedia(request, camera, mic) else ui.permissionPrompt = PermissionPrompt.Media(origin, request, camera, mic)
    }

    private fun grantMedia(request: PermissionRequest, camera: Boolean, mic: Boolean) {
        val needed = buildList {
            if (camera) add(Manifest.permission.CAMERA)
            if (mic) add(Manifest.permission.RECORD_AUDIO)
        }.toTypedArray()
        withRuntimePermissions(needed) {
            val allowed = buildList {
                if (camera && ContextCompat.checkSelfPermission(this@WebAppActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                }
                if (mic && ContextCompat.checkSelfPermission(this@WebAppActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                }
            }
            if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
        }
    }

    override fun answerPermission(allow: Boolean) {
        val prompt = ui.permissionPrompt ?: return
        ui.permissionPrompt = null
        val current = app ?: return
        when (prompt) {
            is PermissionPrompt.Media -> {
                if (!allow) {
                    prompt.request.deny()
                    return
                }
                val keys = buildSet {
                    if (prompt.camera) add("camera:${prompt.origin}")
                    if (prompt.mic) add("mic:${prompt.origin}")
                }
                repo.update(current.id) { it.copy(settings = it.settings.copy(grantedPermissions = it.settings.grantedPermissions + keys)) }
                grantMedia(prompt.request, prompt.camera, prompt.mic)
            }
            is PermissionPrompt.Notifications -> {
                setNotificationDecision(prompt.origin, allow) { state ->
                    sendCommand(prompt.reply, "{\"cmd\":\"notifyPermission\",\"state\":\"$state\"}")
                    if (state == "granted" && app?.settings?.keepAlive != true) {
                        ui.showToast("Tip: turn on “Stay connected” in app settings for notifications in the background")
                    }
                }
            }
            is PermissionPrompt.Location -> {
                if (!allow) {
                    prompt.callback.invoke(prompt.origin, false, false)
                    return
                }
                repo.update(current.id) { it.copy(settings = it.settings.copy(grantedPermissions = it.settings.grantedPermissions + "location:${prompt.origin}")) }
                withRuntimePermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { granted ->
                    prompt.callback.invoke(prompt.origin, granted, false)
                }
            }
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Web notifications (WebView has none: the page script routes them here) */
    /* ---------------------------------------------------------------------- */

    private fun notificationPermissionFor(origin: String): String {
        val granted = grantedSnapshot
        return when {
            "notify-block:$origin" in granted -> "denied"
            "notify:$origin" in granted -> {
                // Granted by the user but blocked by Android: let the site ask again.
                val current = app
                if (current != null && WebNotifier.systemAllows(this, current)) "granted" else "default"
            }
            else -> "default"
        }
    }

    private fun onNotificationPermissionRequest(obj: JsonObject, reply: JavaScriptReplyProxy) {
        val origin = obj.str("origin") ?: return
        val state = notificationPermissionFor(origin)
        when {
            state == "denied" -> sendCommand(reply, "{\"cmd\":\"notifyPermission\",\"state\":\"denied\"}")
            "notify:$origin" in grantedSnapshot -> setNotificationDecision(origin, true) { s ->
                sendCommand(reply, "{\"cmd\":\"notifyPermission\",\"state\":\"$s\"}")
            }
            ui.permissionPrompt == null -> ui.permissionPrompt = PermissionPrompt.Notifications(origin, reply)
            else -> sendCommand(reply, "{\"cmd\":\"notifyPermission\",\"state\":\"default\"}")
        }
    }

    /** Records the user's choice and, on allow, asks Android for its permission too. */
    private fun setNotificationDecision(origin: String, allow: Boolean, result: (String) -> Unit) {
        val current = app ?: return result("default")
        repo.update(current.id) {
            val keys = it.settings.grantedPermissions - "notify:$origin" - "notify-block:$origin"
            it.copy(settings = it.settings.copy(grantedPermissions = keys + if (allow) "notify:$origin" else "notify-block:$origin"))
        }
        grantedSnapshot = grantedSnapshot - "notify:$origin" - "notify-block:$origin" + (if (allow) "notify:$origin" else "notify-block:$origin")
        if (!allow) return result("denied")
        val finish = {
            if (WebNotifier.systemAllows(this, current)) {
                result("granted")
            } else {
                ui.showToast("Notifications are turned off for WebDroid in Android settings")
                result("default")
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            withRuntimePermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) { finish() }
        } else {
            finish()
        }
    }

    private fun onPageNotification(obj: JsonObject, reply: JavaScriptReplyProxy) {
        val current = app ?: return
        val origin = obj.str("origin") ?: return
        if (notificationPermissionFor(origin) != "granted") return
        val id = obj.str("id") ?: return
        notificationFrames[id] = reply
        if (notificationFrames.size > 200) notificationFrames.keys.firstOrNull()?.let { notificationFrames.remove(it) }
        WebNotifier.show(
            this, current,
            WebNotification(
                pageId = id,
                title = obj.str("title").orEmpty(),
                body = obj.str("body").orEmpty(),
                tag = obj.str("tag").orEmpty(),
                iconUrl = obj.str("icon")?.takeIf { it.isNotBlank() },
                imageUrl = obj.str("image")?.takeIf { it.isNotBlank() },
                silent = obj.bool("silent"),
                origin = origin,
            ),
        )
    }

    private fun onPageShare(obj: JsonObject, reply: JavaScriptReplyProxy) {
        val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return
        val text = listOf(obj.str("text"), obj.str("url")).filter { !it.isNullOrBlank() }.joinToString("\n")
        val ok = runCatching {
            val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
                .putExtra(Intent.EXTRA_SUBJECT, obj.str("title").orEmpty())
            startActivity(Intent.createChooser(intent, obj.str("title")?.ifBlank { null }))
        }.isSuccess
        sendCommand(reply, "{\"cmd\":\"shareDone\",\"id\":$id,\"ok\":$ok}")
    }

    private fun printPage(title: String?) {
        val view = webView ?: return
        val name = (title?.ifBlank { null } ?: app?.name ?: "WebDroid").take(60)
        runCatching {
            val manager = getSystemService(PRINT_SERVICE) as android.print.PrintManager
            manager.print(name, view.createPrintDocumentAdapter(name), android.print.PrintAttributes.Builder().build())
        }.onFailure { ui.showToast("Printing isn't available") }
    }

    override fun answerHttpAuth(username: String?, password: String?) {
        val prompt = ui.httpAuth ?: return
        ui.httpAuth = null
        if (username == null || password == null) {
            prompt.handler.cancel()
            return
        }
        @Suppress("DEPRECATION")
        webView?.setHttpAuthUsernamePassword(prompt.host, prompt.realm, username, password)
        prompt.handler.proceed(username, password)
    }

    override fun openNotificationSettings() {
        val current = app ?: return
        runCatching { startActivity(WebNotifier.channelSettingsIntent(this, current)) }
    }

    override fun setNotificationsAllowed(allowed: Boolean) {
        val current = app ?: return
        val origins = buildSet {
            current.settings.grantedPermissions.filter { it.startsWith("notify:") || it.startsWith("notify-block:") }
                .forEach { add(it.substringAfter(':')) }
            UrlUtils.hostOf(current.url)?.let { host -> add("${Uri.parse(current.url).scheme}://$host") }
        }
        if (allowed) {
            origins.forEach { origin -> setNotificationDecision(origin, true) { } }
        } else {
            repo.update(current.id) {
                val keys = it.settings.grantedPermissions.filterNot { k -> k.startsWith("notify:") || k.startsWith("notify-block:") }.toSet()
                it.copy(settings = it.settings.copy(grantedPermissions = keys + origins.map { o -> "notify-block:$o" }))
            }
            ui.showToast("Notifications blocked for ${current.name}")
        }
    }

    override fun answerJs(confirmed: Boolean, text: String?) {
        val dialog = ui.jsDialog ?: return
        ui.jsDialog = null
        when (dialog) {
            is JsDialog.Alert -> dialog.result.confirm()
            is JsDialog.Confirm -> if (confirmed) dialog.result.confirm() else dialog.result.cancel()
            is JsDialog.Prompt -> if (confirmed) dialog.result.confirm(text.orEmpty()) else dialog.result.cancel()
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Downloads                                                               */
    /* ---------------------------------------------------------------------- */

    private fun onDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        when {
            url.startsWith("data:") -> {
                val (mime, bytes) = DownloadHelper.decodeDataUrl(url) ?: return
                val name = DownloadHelper.fileName("download", null, mime)
                saveDownloadedBytes(name, mime, bytes)
            }
            url.startsWith("blob:") -> {
                val reply = mainFrameReply ?: lastVideoReply ?: run {
                    ui.showToast("This download can't be saved")
                    return
                }
                val id = System.nanoTime().toString()
                blobDownloads[id] = DownloadHelper.fileName(url, contentDisposition, mimeType) to ByteArrayOutputStream()
                sendCommand(reply, "{\"cmd\":\"blob\",\"url\":${kotlinx.serialization.json.JsonPrimitive(url)},\"id\":\"$id\"}")
                ui.showToast("Preparing download…")
            }
            url.startsWith("http") -> {
                val start = {
                    val current = app
                    if (vpnAttached && current != null) {
                        // The system downloader would bypass this app's VPN.
                        ui.showToast("Downloading through ${VpnProfiles.get(this).label(current.settings.vpn) ?: "the VPN"}…")
                        val client = VpnManager.httpClient(this, current).newBuilder().cookieJar(WebViewCookieJar(cookieManager())).build()
                        val referer = ui.url
                        lifecycleScope.launch {
                            val name = withContext(Dispatchers.IO) {
                                runCatching { DownloadHelper.downloadWith(applicationContext, client, url, userAgent, referer, contentDisposition, mimeType) }.getOrNull()
                            }
                            ui.showToast(if (name != null) "Saved $name to Downloads" else "Download failed")
                        }
                    } else {
                        runCatching {
                            DownloadHelper.enqueue(this, url, userAgent, contentDisposition, mimeType, ui.url, cookieManager())
                        }.onSuccess { ui.showToast("Downloading $it") }
                            .onFailure { ui.showToast("Download failed") }
                    }
                }
                if (Build.VERSION.SDK_INT <= 28) {
                    withRuntimePermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { granted ->
                        if (granted) start() else ui.showToast("Storage permission needed to download")
                    }
                } else {
                    start()
                }
            }
        }
    }

    private fun onBlobChunk(obj: JsonObject) {
        val id = obj.str("id") ?: return
        val entry = blobDownloads[id] ?: return
        if (obj.str("error") != null) {
            blobDownloads.remove(id)
            ui.showToast("Download failed")
            return
        }
        obj.str("chunk")?.let { entry.second.write(Base64.decode(it, Base64.DEFAULT)) }
        if (obj.bool("done")) {
            blobDownloads.remove(id)
            val mime = obj.str("mime")
            var name = entry.first
            if (!name.contains('.') && mime != null) {
                android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { name = "$name.$it" }
            }
            saveDownloadedBytes(name, mime, entry.second.toByteArray())
        }
    }

    private fun saveDownloadedBytes(name: String, mime: String?, bytes: ByteArray) {
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) { runCatching { DownloadHelper.saveBytes(this@WebAppActivity, name, mime, bytes) }.getOrNull() }
            ui.showToast(if (uri != null) "Saved $name to Downloads" else "Download failed")
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Long press                                                              */
    /* ---------------------------------------------------------------------- */

    private fun onLongPress(view: WebView): Boolean {
        val result = view.hitTestResult
        val extra = result.extra ?: return false
        val menu = when (result.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> LinkMenu(extra, null, null)
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val message = Handler(Looper.getMainLooper()).obtainMessage()
                view.requestFocusNodeHref(message)
                val href = message.data.getString("url")?.takeIf { it.isNotBlank() } ?: extra
                LinkMenu(href, extra, message.data.getString("title"))
            }
            WebView.HitTestResult.IMAGE_TYPE -> if (extra.startsWith("http")) LinkMenu(extra, extra, null) else return false
            else -> return false
        }
        haptics.longPress()
        ui.linkMenu = menu
        return true
    }

    /* ---------------------------------------------------------------------- */
    /* Settings changes                                                        */
    /* ---------------------------------------------------------------------- */

    private fun onSettingsChanged(old: WebApp, new: WebApp) {
        val o = old.settings
        val n = new.settings
        val view = webView
        if (o.vpn != n.vpn) {
            vpnAttached = false
            view?.stopLoading()
            if (n.vpn != null) watchVpnStatus(new) else ui.vpnStatus = null
            VpnManager.reattach(this, old, new) { ok, message ->
                vpnAttached = ok && n.vpn != null
                if (!ok) {
                    ui.vpnStatus = null
                    ui.error = PageError(new.url, message ?: "The VPN isn't available")
                } else {
                    ui.error = null
                    ui.showToast(VpnProfiles.get(this).label(n.vpn)?.let { "Now using $it" } ?: "VPN off for ${new.name}")
                    view?.reload()
                }
            }
        }
        if (o.adBlock != n.adBlock) {
            adblockOn = n.adBlock && AppSettings.get(this).state.value.adBlockEnabled
            ui.showToast(if (n.adBlock) "Ad blocking on" else "Ad blocking off")
            view?.reload()
        }
        if (o.desktopMode != n.desktopMode && view != null) {
            view.settings.userAgentString = userAgent(n.desktopMode)
            view.reload()
        }
        if (o.forceDark != n.forceDark) {
            recreate()
            return
        }
        if (o.isolatedSession != n.isolatedSession) ui.showToast("Applies the next time you open ${new.name}")
        if (o.appLock != n.appLock) setRecentsScreenshotsAllowed(!n.appLock)
        grantedSnapshot = n.grantedPermissions
        if (o.keepAlive != n.keepAlive) {
            if (n.keepAlive) {
                KeepAliveService.connect(this, new.id, new.name)
                if (Build.VERSION.SDK_INT >= 26) view?.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            } else {
                KeepAliveService.disconnect(this, new.id)
                if (Build.VERSION.SDK_INT >= 26) view?.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
            }
        }
        if (old.iconFile != new.iconFile) icon = IconStore.get(this).load(new.iconFile)
        if (old.name != new.name || old.cardColor != new.cardColor || old.iconFile != new.iconFile) {
            updateTaskDescription()
            ShortcutHelper.refresh(this, new)
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Back                                                                    */
    /* ---------------------------------------------------------------------- */

    private fun handleBack() {
        when {
            ui.locked -> moveTaskToBack(true)
            player.state.visible -> player.close()
            customView != null -> exitSiteFullscreen(fromPage = false)
            ui.jsDialog != null -> answerJs(false, null)
            ui.httpAuth != null -> answerHttpAuth(null, null)
            ui.permissionPrompt is PermissionPrompt.Notifications -> {
                // Dismissed without choosing: the site may ask again later.
                val prompt = ui.permissionPrompt as PermissionPrompt.Notifications
                ui.permissionPrompt = null
                sendCommand(prompt.reply, "{\"cmd\":\"notifyPermission\",\"state\":\"default\"}")
            }
            ui.permissionPrompt != null -> answerPermission(false)
            ui.linkMenu != null -> ui.linkMenu = null
            ui.settingsOpen -> ui.settingsOpen = false
            ui.menuOpen -> ui.menuOpen = false
            ui.popupOpen -> {
                val popup = popupWebView
                if (popup != null && popup.canGoBack()) popup.goBack() else closePopup()
            }
            ui.toolbarExpanded -> ui.toolbarExpanded = false
            ui.videoOffer != null -> ui.videoOffer = null
            webView?.canGoBack() == true -> webView?.goBack()
            else -> moveTaskToBack(true)
        }
    }

    /* ---------------------------------------------------------------------- */
    /* WebAppActions                                                           */
    /* ---------------------------------------------------------------------- */

    override fun back() {
        webView?.takeIf { it.canGoBack() }?.goBack()
    }

    override fun forward() {
        webView?.takeIf { it.canGoForward() }?.goForward()
    }

    override fun reload() {
        ui.error = null
        webView?.reload()
    }

    override fun home() {
        app?.let { webView?.loadUrl(it.url) }
    }

    override fun share() {
        val text = ui.url.ifBlank { app?.url.orEmpty() }
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_SUBJECT, ui.title)
        startActivity(Intent.createChooser(intent, null))
    }

    override fun copyLink() = copyText(ui.url.ifBlank { app?.url.orEmpty() })

    override fun openInBrowser() {
        if (!ExternalNavigation.open(this, ui.url.ifBlank { app?.url.orEmpty() })) ui.showToast("No browser found")
    }

    override fun closeApp() {
        if (player.state.visible) player.close()
        finishAndRemoveTask()
    }

    override fun addToHomeScreen() {
        val current = app ?: return
        if (!ShortcutHelper.pin(this, current)) ui.showToast("Your launcher doesn't support shortcuts")
    }

    override fun editApp() {
        val current = app ?: return
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_EDIT_APP, current.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    override fun clearSiteData() {
        val current = app ?: return
        val view = webView ?: return
        if (current.settings.isolatedSession && isolatedSupported) {
            runCatching {
                val profile = ProfileStore.getInstance().getOrCreateProfile(current.profileName)
                profile.cookieManager.removeAllCookies(null)
                profile.webStorage.deleteAllData()
            }
        } else {
            val manager = CookieManager.getInstance()
            val site = UrlUtils.siteOf(current.url)
            listOf(current.url, ui.url).filter { it.startsWith("http") }.forEach { url ->
                manager.getCookie(url)?.split(';')?.forEach { cookie ->
                    val name = cookie.substringBefore('=').trim()
                    if (name.isNotEmpty()) {
                        manager.setCookie(url, "$name=; Max-Age=0; Path=/")
                        if (site != null) manager.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=.$site")
                    }
                }
                val uri = Uri.parse(url)
                WebStorage.getInstance().deleteOrigin("${uri.scheme}://${uri.host}")
            }
            manager.flush()
        }
        view.clearCache(true)
        view.clearHistory()
        view.loadUrl(current.url)
        ui.showToast("Site data cleared")
    }

    override fun updateSettings(transform: (WebAppSettings) -> WebAppSettings) {
        val current = app ?: return
        repo.update(current.id) { it.copy(settings = transform(it.settings)) }
    }

    override fun openLink(url: String) {
        webView?.loadUrl(url)
    }

    override fun openLinkExternally(url: String) {
        if (!ExternalNavigation.open(this, url)) ui.showToast("No app can open this link")
    }

    override fun copyText(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("link", text))
        if (Build.VERSION.SDK_INT < 33) ui.showToast("Copied")
    }

    override fun shareText(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }

    override fun download(url: String) {
        onDownload(url, webView?.settings?.userAgentString, null, null)
    }

    override fun retry() {
        ui.error = null
        webView?.reload()
    }

    override fun playOffer() {
        val offer = ui.videoOffer ?: return
        ui.videoOffer = null
        if (ui.siteFullscreen) exitSiteFullscreen(fromPage = false)
        offer.request.frameVideoId?.let { id -> lastVideoReply?.let { sendCommand(it, "{\"cmd\":\"pause\",\"id\":$id}") } }
        openPlayer(offer.request)
    }

    override fun dismissOffer() {
        ui.videoOffer = null
    }

    override fun unlock() {
        val current = app ?: return
        if (unlocking) return
        unlocking = true
        AppLock.prompt(this, current.name) {
            ui.locked = false
            unlocking = false
        }
        lifecycleScope.launch {
            // The prompt may be dismissed without a callback; allow retrying.
            delay(1500)
            unlocking = false
        }
    }
}

/** Card colours as ARGB ints for platform APIs. */
private object CardColorArgb {
    fun of(index: Int): Int = when (Math.floorMod(index, 6)) {
        0 -> 0xFFFF4D8B.toInt()
        1 -> 0xFF1A3A3A.toInt()
        2 -> 0xFFB8A4ED.toInt()
        3 -> 0xFFFFB084.toInt()
        4 -> 0xFFE8B94A.toInt()
        else -> 0xFFF5F0E0.toInt()
    }
}

