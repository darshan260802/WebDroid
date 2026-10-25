package com.webdroid.app.web

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.webdroid.app.data.IconStore
import com.webdroid.app.data.WebApp

/** Launcher shortcuts that open a web app inside WebDroid (no separate install). */
object ShortcutHelper {
    private fun shortcutId(app: WebApp) = "app_${app.id}"

    fun launchIntent(context: Context, app: WebApp): Intent =
        Intent(Intent.ACTION_VIEW, app.documentUri, context, WebAppActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)

    private fun build(context: Context, app: WebApp): ShortcutInfoCompat {
        val store = IconStore.get(context)
        val bitmap = store.renderTile(app, 432, adaptive = true)
        return ShortcutInfoCompat.Builder(context, shortcutId(app))
            .setShortLabel(app.name.take(24))
            .setLongLabel(app.name)
            .setIcon(IconCompat.createWithAdaptiveBitmap(bitmap))
            .setIntent(launchIntent(context, app))
            .build()
    }

    fun isPinSupported(context: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    fun pin(context: Context, app: WebApp): Boolean {
        if (!isPinSupported(context)) return false
        return runCatching { ShortcutManagerCompat.requestPinShortcut(context, build(context, app), null) }.getOrDefault(false)
    }

    /** Keeps the launcher's long-press menu showing recently used web apps. */
    fun reportUsed(context: Context, app: WebApp) {
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, build(context, app)) }
    }

    fun refresh(context: Context, app: WebApp) {
        runCatching {
            val shortcut = build(context, app)
            ShortcutManagerCompat.updateShortcuts(context, listOf(shortcut))
        }
    }

    fun remove(context: Context, app: WebApp) {
        runCatching {
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(shortcutId(app)))
            ShortcutManagerCompat.disableShortcuts(context, listOf(shortcutId(app)), "This app was removed from WebDroid")
        }
    }
}
