package com.webdroid.app.web

import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.webdroid.app.player.VideoRequest

enum class PullPhase { IDLE, PULLING, REFRESHING }

data class LinkMenu(val url: String, val imageUrl: String?, val title: String?)

sealed interface PermissionPrompt {
    val origin: String

    data class Media(override val origin: String, val request: PermissionRequest, val camera: Boolean, val mic: Boolean) : PermissionPrompt
    data class Location(override val origin: String, val callback: GeolocationPermissions.Callback) : PermissionPrompt
    data class Notifications(override val origin: String, val reply: androidx.webkit.JavaScriptReplyProxy) : PermissionPrompt
}

/** A site behind HTTP authentication (basic/digest). */
data class HttpAuthPrompt(val host: String, val realm: String, val handler: android.webkit.HttpAuthHandler)

sealed interface JsDialog {
    val message: String

    data class Alert(override val message: String, val result: JsResult) : JsDialog
    data class Confirm(override val message: String, val result: JsResult) : JsDialog
    data class Prompt(override val message: String, val defaultValue: String, val result: JsPromptResult) : JsDialog
}

data class PageError(val url: String, val description: String)

data class VideoOffer(val request: VideoRequest, val replyToFrame: Boolean)

/** Everything the Compose overlay above the WebView renders. */
class WebAppUiState {
    var splashVisible by mutableStateOf(true)
    var barColor by mutableStateOf(Color.White)
    var lightBars by mutableStateOf(true)
    var title by mutableStateOf("")
    var url by mutableStateOf("")
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)

    var pullDistance by mutableFloatStateOf(0f)
    var pullPhase by mutableStateOf(PullPhase.IDLE)

    var menuOpen by mutableStateOf(false)
    var settingsOpen by mutableStateOf(false)
    var toolbarExpanded by mutableStateOf(false)
    var linkMenu by mutableStateOf<LinkMenu?>(null)
    var permissionPrompt by mutableStateOf<PermissionPrompt?>(null)
    var jsDialog by mutableStateOf<JsDialog?>(null)
    var httpAuth by mutableStateOf<HttpAuthPrompt?>(null)
    var error by mutableStateOf<PageError?>(null)
    var videoOffer by mutableStateOf<VideoOffer?>(null)
    var locked by mutableStateOf(false)
    var siteFullscreen by mutableStateOf(false)
    var popupOpen by mutableStateOf(false)
    var popupTitle by mutableStateOf("")

    var toast by mutableStateOf<String?>(null)
    var toastSerial by mutableIntStateOf(0)
    var blockedCount by mutableIntStateOf(0)
    /** VPN connection progress shown over the page (e.g. Tor bootstrapping). */
    var vpnStatus by mutableStateOf<String?>(null)

    fun showToast(message: String) {
        toast = message
        toastSerial++
    }

    val anySheetOpen: Boolean
        get() = menuOpen || settingsOpen || linkMenu != null || permissionPrompt != null || jsDialog != null || httpAuth != null
}
