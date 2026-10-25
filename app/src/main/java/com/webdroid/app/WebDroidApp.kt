package com.webdroid.app

import android.app.Application
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.core.content.edit
import androidx.webkit.ProfileStore
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import com.webdroid.app.adblock.AdblockEngine
import com.webdroid.app.adblock.FilterUpdateWorker
import com.webdroid.app.adblock.RequestFilter
import com.webdroid.app.data.AppSettings
import com.webdroid.app.ui.theme.ClayTheme
import com.webdroid.app.update.UpdateWorker

class WebDroidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Start loading uBO's engine immediately, off the main thread.
        AdblockEngine.init(this)
        FilterUpdateWorker.schedule(this)
        UpdateWorker.schedule(this)
        ClayTheme.prime(this)
    }

    @Volatile private var webViewSetupDone = false

    /**
     * WebView-dependent setup, deferred until a web app first opens so that
     * launching WebDroid itself doesn't pay for Chromium's initialisation.
     */
    fun ensureWebViewSetup() {
        if (webViewSetupDone) return
        webViewSetupDone = true
        installServiceWorkerFilter()
        deletePendingProfiles()
    }

    /** Requests made by service workers bypass the WebView client; filter them too. */
    private fun installServiceWorkerFilter() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)
        ) return
        val app = this
        runCatching {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    if (!AppSettings.get(app).state.value.adBlockEnabled || !AdblockEngine.isReady) return null
                    val referer = request.requestHeaders["Referer"] ?: request.url.toString()
                    return RequestFilter.filter(app, request, referer)
                }
            })
        }
    }

    private fun deletePendingProfiles() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        val prefs = getSharedPreferences("webdroid", MODE_PRIVATE)
        val pending = prefs.getStringSet("profiles_to_delete", emptySet()).orEmpty()
        if (pending.isEmpty()) return
        val remaining = pending.filterNot { name ->
            runCatching { ProfileStore.getInstance().deleteProfile(name) }.isSuccess
        }.toSet()
        prefs.edit { putStringSet("profiles_to_delete", remaining) }
    }

    companion object {
        fun queueProfileDeletion(context: Context, name: String) {
            val prefs = context.getSharedPreferences("webdroid", MODE_PRIVATE)
            val pending = prefs.getStringSet("profiles_to_delete", emptySet()).orEmpty() + name
            prefs.edit { putStringSet("profiles_to_delete", pending) }
        }
    }
}
