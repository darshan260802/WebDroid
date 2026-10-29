package com.webdroid.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.webdroid.app.ui.glass.GlassBackdrop
import com.webdroid.app.ui.glass.rememberGlassBackdrop
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The top layer: menus, alerts and sheets opened from inside a screen render
 * here, above the tab bar, so a modal view covers everything as it should.
 */
class OverlayHost {
    internal val entries = mutableStateListOf<Pair<Any, @Composable BoxScope.() -> Unit>>()
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHost?> { null }

/** Renders [content] in the nearest [OverlayHost] (or in place when there is none). */
@Composable
fun Overlay(content: @Composable BoxScope.() -> Unit) {
    val host = LocalOverlayHost.current
    if (host == null) {
        Box(Modifier.fillMaxSize()) { content() }
        return
    }
    val latest by rememberUpdatedState(content)
    val key = remember { Any() }
    DisposableEffect(host, key) {
        val entry: Pair<Any, @Composable BoxScope.() -> Unit> = key to { latest(this) }
        host.entries.add(entry)
        onDispose { host.entries.removeAll { it.first === key } }
    }
}

@Composable
fun BoxScope.OverlayLayer(host: OverlayHost) {
    for ((_, content) in host.entries) content()
}

/** Captures the window (for glass over things that can't be recorded live). */
object WindowSnapshot {
    const val SCALE = 0.5f

    suspend fun capture(activity: Activity, scale: Float = SCALE): ImageBitmap? {
        if (Build.VERSION.SDK_INT < 31) return null // no blur to show it with
        val view = activity.window?.decorView ?: return null
        val w = (view.width * scale).toInt()
        val h = (view.height * scale).toInt()
        if (w <= 0 || h <= 0) return null
        val bitmap = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        return suspendCancellableCoroutine { cont ->
            runCatching {
                PixelCopy.request(activity.window, bitmap, { result ->
                    if (cont.isActive) cont.resume(if (result == PixelCopy.SUCCESS) bitmap.asImageBitmap() else null)
                }, Handler(Looper.getMainLooper()))
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }
}

fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * A backdrop that holds a snapshot of the window, taken when [active] turns
 * on: glass for modal surfaces (menus, alerts) that sit above a dimming layer.
 */
@Composable
fun rememberSnapshotBackdrop(active: Boolean): GlassBackdrop {
    val backdrop = rememberGlassBackdrop()
    val activity = LocalContext.current.findActivity()
    LaunchedEffect(active) {
        if (active && activity != null) {
            backdrop.snapshotScale = WindowSnapshot.SCALE
            backdrop.snapshot = WindowSnapshot.capture(activity)
        }
    }
    return backdrop
}
