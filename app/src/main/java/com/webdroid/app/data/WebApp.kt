package com.webdroid.app.data

import android.net.Uri
import kotlinx.serialization.Serializable

/** How the native player reacts when a page starts a video. */
@Serializable
enum class VideoMode { AUTO, BUTTON, OFF }

/** Per-app behaviour, chosen when the app is created and editable later. */
@Serializable
data class WebAppSettings(
    val videoMode: VideoMode = VideoMode.AUTO,
    /** Pull-down mini toolbar in addition to the gesture controls. */
    val miniToolbar: Boolean = false,
    val adBlock: Boolean = true,
    /** Separate cookie/storage profile (needs WebView multi-profile support). */
    val isolatedSession: Boolean = false,
    val desktopMode: Boolean = false,
    val forceDark: Boolean = false,
    val appLock: Boolean = false,
    /** Off-site links the user taps open in the default browser. */
    val openLinksExternally: Boolean = true,
    /**
     * Keep the page alive in the background (foreground service + important
     * renderer) so chat sites keep their connection and can notify.
     */
    val keepAlive: Boolean = false,
    /**
     * Per-origin decisions: "camera:", "mic:", "location:" and "notify:" grants,
     * "notify-block:" denials, followed by the origin.
     */
    val grantedPermissions: Set<String> = emptySet(),
)

@Serializable
data class WebApp(
    val id: String,
    val name: String,
    val url: String,
    /** File name inside [IconStore]'s directory, or null for a letter icon. */
    val iconFile: String? = null,
    /** theme-color advertised by the site (ARGB), used for system bars. */
    val themeColor: Int? = null,
    /** Index into the Clay card palette. */
    val cardColor: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = 0L,
    val order: Int = 0,
    val settings: WebAppSettings = WebAppSettings(),
) {
    val host: String
        get() = runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: url

    val profileName: String
        get() = "app_$id"

    val documentUri: Uri
        get() = Uri.parse("webdroid://app/$id")

    companion object {
        fun idFromUri(uri: Uri?): String? =
            uri?.takeIf { it.scheme == "webdroid" && it.host == "app" }?.lastPathSegment
    }
}
