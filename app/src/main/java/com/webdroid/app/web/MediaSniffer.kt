package com.webdroid.app.web

import android.webkit.WebResourceRequest

/**
 * Remembers recent media manifests and files a page requested, so a video
 * that plays through Media Source Extensions (a `blob:` URL) can still be
 * opened in the native player from the HLS/DASH manifest it was built from.
 */
class MediaSniffer {
    enum class Kind { HLS, DASH, PROGRESSIVE }

    data class Stream(val url: String, val kind: Kind, val seenAt: Long, val referer: String?)

    private val recent = ArrayDeque<Stream>()

    private fun kindOf(request: WebResourceRequest): Kind? {
        val url = request.url
        val path = url.path.orEmpty().lowercase()
        val accept = request.requestHeaders["Accept"].orEmpty().lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") || accept.contains("mpegurl") -> Kind.HLS
            path.endsWith(".mpd") || accept.contains("dash+xml") -> Kind.DASH
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mkv") || path.endsWith(".mov") ||
                path.endsWith(".m4v") -> if (request.requestHeaders["Range"] != null || accept.startsWith("video/")) Kind.PROGRESSIVE else null
            else -> null
        }
    }

    fun observe(request: WebResourceRequest) {
        val kind = kindOf(request) ?: return
        val url = request.url.toString()
        synchronized(recent) {
            if (recent.any { it.url == url }) return
            recent.addLast(Stream(url, kind, System.currentTimeMillis(), request.requestHeaders["Referer"]))
            while (recent.size > 40) recent.removeFirst()
        }
    }

    fun clear() = synchronized(recent) { recent.clear() }

    /**
     * Best manifest for a video that started playing now: the first manifest
     * of the most recent burst (players load the master playlist first).
     */
    fun bestManifest(maxAgeMs: Long = 90_000): Stream? = synchronized(recent) {
        val now = System.currentTimeMillis()
        val manifests = recent.filter { it.kind != Kind.PROGRESSIVE && now - it.seenAt < maxAgeMs }
        if (manifests.isEmpty()) return null
        manifests.firstOrNull { it.url.contains("master", ignoreCase = true) && now - it.seenAt < 30_000 }?.let { return it }
        val latest = manifests.last()
        manifests.first { latest.seenAt - it.seenAt < 8_000 }
    }

    fun latestProgressive(maxAgeMs: Long = 60_000): Stream? = synchronized(recent) {
        val now = System.currentTimeMillis()
        recent.lastOrNull { it.kind == Kind.PROGRESSIVE && now - it.seenAt < maxAgeMs }
    }
}
