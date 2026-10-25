package com.webdroid.app.adblock

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/** Maps WebView requests onto uBO request types and builds block responses. */
object RequestFilter {
    private val EMPTY = ByteArray(0)
    private val TRANSPARENT_GIF = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2C, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
    )
    private val resourceCache = ConcurrentHashMap<String, ByteArray>()

    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp")
    private val FONT_EXT = setOf("woff", "woff2", "ttf", "otf", "eot")
    private val MEDIA_EXT = setOf("mp4", "webm", "m3u8", "mpd", "ts", "m4s", "mp3", "aac", "m4a", "ogg", "opus", "wav")

    /** Best-effort uBO type; null for types we cannot tell apart (script vs xhr). */
    fun typeOf(request: WebResourceRequest): String? {
        if (request.isForMainFrame) return "main_frame"
        val accept = request.requestHeaders["Accept"] ?: request.requestHeaders["accept"] ?: ""
        val path = request.url.path.orEmpty().lowercase()
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        return when {
            accept.startsWith("text/html") || accept.startsWith("application/xhtml") -> "sub_frame"
            accept.startsWith("text/css") -> "stylesheet"
            accept.startsWith("image/") -> "image"
            accept.startsWith("video/") || accept.startsWith("audio/") -> "media"
            accept.contains("font/") -> "font"
            ext == "js" || ext == "mjs" -> "script"
            ext == "css" -> "stylesheet"
            ext in IMAGE_EXT -> "image"
            ext in FONT_EXT -> "font"
            ext in MEDIA_EXT -> "media"
            accept.contains("application/json") -> "xmlhttprequest"
            else -> null
        }
    }

    fun blockedResponse(type: String): WebResourceResponse {
        val headers = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store")
        return when (type) {
            "image" -> WebResourceResponse("image/gif", null, 200, "OK", headers, ByteArrayInputStream(TRANSPARENT_GIF))
            "sub_frame" -> WebResourceResponse("text/html", "utf-8", 200, "OK", headers, ByteArrayInputStream(EMPTY))
            // Like uBO's cancel: the load fails, so `onerror` handlers fire.
            else -> WebResourceResponse("text/plain", "utf-8", 403, "Blocked", headers, ByteArrayInputStream(EMPTY))
        }
    }

    private fun mimeOf(name: String, type: String): String = when (name.substringAfterLast('.', "")) {
        "js" -> "application/javascript"
        "css" -> "text/css"
        "html" -> "text/html"
        "json" -> "application/json"
        "txt" -> "text/plain"
        "xml" -> "text/xml"
        "gif" -> "image/gif"
        "png" -> "image/png"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        else -> when (type) {
            "script" -> "application/javascript"
            "stylesheet" -> "text/css"
            "sub_frame", "main_frame" -> "text/html"
            "image" -> "image/gif"
            else -> "text/plain"
        }
    }

    /** uBO `$redirect` target from the bundled web_accessible_resources. */
    fun redirectResponse(context: Context, name: String, type: String): WebResourceResponse {
        val bytes = if (name == "empty") {
            EMPTY
        } else {
            resourceCache.getOrPut(name) {
                runCatching { AdblockStorage.openAsset(context, "war/$name").use { it.readBytes() } }.getOrDefault(EMPTY)
            }
        }
        val mime = mimeOf(name, type)
        val headers = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store")
        return WebResourceResponse(mime, if (mime.startsWith("text") || mime.endsWith("javascript") || mime.endsWith("json")) "utf-8" else null, 200, "OK", headers, ByteArrayInputStream(bytes))
    }

    /** Runs a request through uBO. Returns a replacement response, or null to let it load. */
    fun filter(context: Context, request: WebResourceRequest, documentUrl: String): WebResourceResponse? {
        val url = request.url.toString()
        if (!url.startsWith("http")) return null
        if (request.isForMainFrame) return null
        val origin = request.requestHeaders["Referer"]?.takeIf { it.startsWith("http") } ?: documentUrl
        val inferred = typeOf(request)
        val types = if (inferred != null) listOf(inferred) else listOf("script", "xmlhttprequest")
        for (type in types) {
            val verdict = AdblockEngine.match(url, origin, type)
            when {
                verdict == "b" -> return blockedResponse(type)
                verdict.startsWith("r:") -> return redirectResponse(context, verdict.substring(2), type)
                verdict == "a" -> return null
            }
        }
        return null
    }
}
