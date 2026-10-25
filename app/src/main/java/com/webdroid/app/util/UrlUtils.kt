package com.webdroid.app.util

import android.net.Uri
import android.util.Patterns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object UrlUtils {
    /** Turns user input like "youtube.com" into "https://youtube.com/". */
    fun normalize(input: String): String? {
        var text = input.trim()
        if (text.isEmpty()) return null
        if (!text.contains("://")) {
            if (text.contains(' ') || !text.contains('.')) return null
            text = "https://$text"
        }
        val url = text.toHttpUrlOrNull() ?: return null
        if (url.host.isBlank()) return null
        return url.toString()
    }

    fun looksLikeUrl(input: String): Boolean {
        val text = input.trim()
        return text.isNotEmpty() && (normalize(text) != null) &&
            (text.contains("://") || Patterns.WEB_URL.matcher(text).matches())
    }

    /** Registrable domain ("eTLD+1"), e.g. m.youtube.com -> youtube.com. */
    fun siteOf(url: String?): String? {
        val parsed = url?.toHttpUrlOrNull() ?: return null
        return parsed.topPrivateDomain() ?: parsed.host
    }

    fun sameSite(a: String?, b: String?): Boolean {
        val sa = siteOf(a) ?: return false
        return sa == siteOf(b)
    }

    fun hostOf(url: String?): String? = runCatching { Uri.parse(url).host }.getOrNull()

    fun displayHost(url: String?): String = hostOf(url)?.removePrefix("www.") ?: url.orEmpty()
}
