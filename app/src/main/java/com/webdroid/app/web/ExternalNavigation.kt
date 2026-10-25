package com.webdroid.app.web

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import com.webdroid.app.data.WebApp
import com.webdroid.app.util.UrlUtils

/** Decides which navigations stay inside a web app and which leave it. */
object ExternalNavigation {
    enum class Decision { IN_APP, EXTERNAL, HANDLED }

    // Sign-in providers must stay in-app or OAuth flows break.
    private val AUTH_SITES = setOf(
        "google.com", "youtube.com", "apple.com", "microsoftonline.com", "live.com", "microsoft.com",
        "facebook.com", "github.com", "twitter.com", "x.com", "discord.com", "auth0.com", "okta.com",
        "yahoo.com", "atlassian.com", "slack.com", "spotify.com", "linkedin.com", "salesforce.com",
        "amazon.com", "paypal.com", "stripe.com", "twitch.tv", "reddit.com", "instagram.com",
    )
    private val AUTH_PREFIXES = listOf("accounts.", "auth.", "login.", "signin.", "sso.", "oauth.", "id.", "secure.")

    fun isAuthHost(url: String): Boolean {
        val host = UrlUtils.hostOf(url)?.lowercase() ?: return false
        if (AUTH_PREFIXES.any { host.startsWith(it) }) return true
        val site = UrlUtils.siteOf(url) ?: return false
        val path = Uri.parse(url).path.orEmpty().lowercase()
        return site in AUTH_SITES && (path.contains("login") || path.contains("oauth") || path.contains("signin") ||
            path.contains("auth") || path.contains("account") || path.contains("sso") || path.contains("consent"))
    }

    fun decide(app: WebApp, request: WebResourceRequest, currentUrl: String?): Decision {
        val url = request.url.toString()
        val scheme = request.url.scheme?.lowercase().orEmpty()
        if (scheme == "http" || scheme == "https") {
            if (!request.isForMainFrame) return Decision.IN_APP
            if (UrlUtils.sameSite(url, app.url) || UrlUtils.sameSite(url, currentUrl)) return Decision.IN_APP
            if (isAuthHost(url)) return Decision.IN_APP
            if (request.isRedirect || !request.hasGesture()) return Decision.IN_APP
            return if (app.settings.openLinksExternally) Decision.EXTERNAL else Decision.IN_APP
        }
        return when (scheme) {
            "about", "javascript", "data", "blob", "file" -> Decision.IN_APP
            else -> Decision.HANDLED
        }
    }

    /** Opens [url] with another app. Returns false when nothing can handle it. */
    fun open(activity: Activity, url: String): Boolean {
        val uri = Uri.parse(url)
        val intent = if (uri.scheme == "intent") {
            val parsed = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return false
            // Never let pages target arbitrary components.
            parsed.addCategory(Intent.CATEGORY_BROWSABLE)
            parsed.component = null
            parsed.selector = null
            try {
                activity.startActivity(parsed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                val fallback = parsed.getStringExtra("browser_fallback_url")
                return if (!fallback.isNullOrBlank() && fallback.startsWith("http")) open(activity, fallback) else false
            } catch (e: SecurityException) {
                return false
            }
        } else {
            Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        return try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
