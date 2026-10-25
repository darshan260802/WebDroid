package com.webdroid.app.web

import android.content.Context
import android.webkit.JavascriptInterface
import com.webdroid.app.adblock.AdblockEngine
import com.webdroid.app.adblock.AdblockStorage
import com.webdroid.app.data.AppSettings

/** Builds WebDroid's document-start script from assets (cached per process). */
object InjectedScript {
    @Volatile private var cached: String? = null

    fun cosmeticBridgeName(context: Context) = AppSettings.get(context).bridgeToken + "c"
    fun messageBridgeName(context: Context) = AppSettings.get(context).bridgeToken + "m"

    fun get(context: Context): String = cached ?: synchronized(this) {
        cached ?: build(context).also { cached = it }
    }

    private fun build(context: Context): String {
        val template = context.assets.open("js/inject.js").bufferedReader().use { it.readText() }
        val scriptlets = runCatching { AdblockStorage.readAsset(context, "scriptlets.js") }
            .getOrDefault("function __webdroidScriptlets() {}")
        val procedural = runCatching { AdblockStorage.readAsset(context, "procedural.js") }.getOrDefault("")
        return template
            .replace("@@COSM@@", cosmeticBridgeName(context))
            .replace("@@MSG@@", messageBridgeName(context))
            .replace("/*@@SCRIPTLETS@@*/", scriptlets)
            .replace("/*@@PROCEDURAL@@*/", procedural)
    }
}

/**
 * Synchronous bridge used by the document-start script: cosmetic filters and
 * scriptlets must be known before the page's own scripts run.
 */
class CosmeticBridge(private val enabled: () -> Boolean) {
    @JavascriptInterface
    fun get(url: String?): String {
        if (url == null || !enabled()) return ""
        if (!AdblockEngine.awaitReady(1500)) return ""
        return AdblockEngine.cosmetic(url)
    }

    @JavascriptInterface
    fun lowGeneric(keys: String?): String {
        if (keys.isNullOrEmpty() || !enabled()) return "[]"
        return AdblockEngine.lowGeneric(keys)
    }
}
