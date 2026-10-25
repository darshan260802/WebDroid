package com.webdroid.app.web

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Lets native requests (the video player, downloads) share a web app's
 * WebView cookies, both ways, for whichever hosts they reach, so signed-in
 * streams and token cookies set by a manifest response keep working.
 */
class WebViewCookieJar(private val cookies: CookieManager) : CookieJar {

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = runCatching { cookies.getCookie(url.toString()) }.getOrNull()
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty()) null else Cookie.parse(url, trimmed)
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        runCatching { cookies.forEach { this.cookies.setCookie(url.toString(), it.toString()) } }
    }
}
