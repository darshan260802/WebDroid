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
    /** Origins granted camera/mic/location ("camera:https://a.b" style keys). */
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
