package com.webdroid.app.web

import android.webkit.WebResourceRequest

/**
 * Remembers recent media manifests and files a page requested, so a video
 * that plays through Media Source Extensions (a `blob:` URL) can still be
 * opened in the native player from the HLS/DASH manifest it was built from.
 *
 * Two sources feed it: request URLs seen by the WebView client, and
 * manifests the page script recognised by content type or content (which
 * also catches manifests whose URLs don't end in .m3u8/.mpd).
 */
class MediaSniffer {
    enum class Kind { HLS, DASH, PROGRESSIVE }

    data class Stream(
        val url: String,
        val kind: Kind,
        val seenAt: Long,
        val referer: String?,
        val fromPage: Boolean = false,
        val master: Boolean = false,
        val live: Boolean = false,
    )

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

    private fun add(stream: Stream) = synchronized(recent) {
        val existing = recent.indexOfFirst { it.url == stream.url }
        if (existing >= 0) {
            val old = recent.removeAt(existing)
            // Keep what the page told us; refresh the time.
            recent.addLast(
                stream.copy(
                    fromPage = old.fromPage || stream.fromPage,
                    master = old.master || stream.master,
                    live = old.live || stream.live,
                    referer = stream.referer ?: old.referer,
                )
            )
        } else {
            recent.addLast(stream)
        }
        while (recent.size > 60) recent.removeFirst()
    }

    fun observe(request: WebResourceRequest) {
        val kind = kindOf(request) ?: return
        add(Stream(request.url.toString(), kind, System.currentTimeMillis(), request.requestHeaders["Referer"]))
    }

    /** A manifest the page script recognised ([kind] is "hls" or "dash"). */
    fun observePage(url: String, kind: String, master: Boolean, live: Boolean, pageUrl: String?) {
        val k = when (kind) {
            "hls" -> Kind.HLS
            "dash" -> Kind.DASH
            else -> return
        }
        add(Stream(url, k, System.currentTimeMillis(), pageUrl, fromPage = true, master = master, live = live))
    }

    fun clear() = synchronized(recent) { recent.clear() }

    /**
     * Best manifest for a video that started playing now: the newest master
     * playlist (players load it first, it carries every quality and audio
     * track), else the first manifest of the most recent burst.
     */
    fun bestManifest(maxAgeMs: Long = 90_000): Stream? = synchronized(recent) {
        val now = System.currentTimeMillis()
        val manifests = recent.filter { it.kind != Kind.PROGRESSIVE && now - it.seenAt < maxAgeMs }
        if (manifests.isEmpty()) return null
        manifests.lastOrNull { it.master }?.let { return it }
        manifests.lastOrNull { it.url.contains("master", ignoreCase = true) && now - it.seenAt < 60_000 }?.let { return it }
        val latest = manifests.last()
        manifests.first { latest.seenAt - it.seenAt < 8_000 }
    }

    fun latestProgressive(maxAgeMs: Long = 60_000): Stream? = synchronized(recent) {
        val now = System.currentTimeMillis()
        recent.lastOrNull { it.kind == Kind.PROGRESSIVE && now - it.seenAt < maxAgeMs }
    }

    fun find(url: String): Stream? = synchronized(recent) { recent.lastOrNull { it.url == url } }
}
