package com.webdroid.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.webdroid.app.util.Net
import com.webdroid.app.util.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class SiteMetadata(
    val url: String,
    val title: String?,
    val themeColor: Int?,
    val icon: Bitmap?,
)

/**
 * Reads what a site says about itself (web app manifest, theme-color, icons)
 * to pre-fill a new app.
 */
object SiteMetadataFetcher {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class IconCandidate(val url: String, val size: Int, val priority: Int)

    suspend fun fetch(rawUrl: String): SiteMetadata? = withContext(Dispatchers.IO) {
        val url = UrlUtils.normalize(rawUrl)?.toHttpUrlOrNull() ?: return@withContext null
        runCatching { fetchInternal(url) }.getOrNull()
    }

    private fun fetchInternal(url: HttpUrl): SiteMetadata {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Net.MOBILE_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
        val (finalUrl, html) = Net.client.newCall(request).execute().use { response ->
            val body = response.body
            val text = body?.source()?.let { source ->
                source.request(2L * 1024 * 1024)
                source.buffer.clone().readUtf8()
            }.orEmpty()
            response.request.url to text
        }
        val doc = Jsoup.parse(html, finalUrl.toString())

        var title: String? = null
        var themeColor: Int? = parseColor(themeColorFrom(doc))
        val candidates = mutableListOf<IconCandidate>()

        doc.selectFirst("link[rel=manifest]")?.absUrl("href")?.takeIf { it.isNotBlank() }?.let { manifestUrl ->
            runCatching { readManifest(manifestUrl) }.getOrNull()?.let { manifest ->
                title = manifest.string("short_name") ?: manifest.string("name")
                if (themeColor == null) themeColor = parseColor(manifest.string("theme_color"))
                manifest["icons"]?.jsonArray?.forEach { element ->
                    val icon = element.jsonObject
                    val src = icon.string("src") ?: return@forEach
                    val abs = manifestUrl.toHttpUrlOrNull()?.resolve(src)?.toString() ?: return@forEach
                    if (abs.endsWith(".svg", ignoreCase = true)) return@forEach
                    val purpose = icon.string("purpose").orEmpty()
                    val size = parseSizes(icon.string("sizes"))
                    val priority = when {
                        purpose.contains("any") || purpose.isBlank() -> 3
                        purpose.contains("maskable") -> 2
                        else -> 1
                    }
                    candidates += IconCandidate(abs, size, priority)
                }
            }
        }

        if (title.isNullOrBlank()) {
            title = doc.selectFirst("meta[property=og:site_name]")?.attr("content")
                ?: doc.selectFirst("meta[name=application-name]")?.attr("content")
                ?: doc.selectFirst("meta[name=apple-mobile-web-app-title]")?.attr("content")
                ?: cleanTitle(doc.title())
        }

        doc.select("link[rel~=(?i)apple-touch-icon]").forEach {
            val href = it.absUrl("href")
            if (href.isNotBlank()) candidates += IconCandidate(href, parseSizes(it.attr("sizes")).takeIf { s -> s > 0 } ?: 180, 2)
        }
        doc.select("link[rel~=(?i)^(shortcut )?icon$], link[rel=icon]").forEach {
            val href = it.absUrl("href")
            if (href.isNotBlank() && !href.endsWith(".svg", ignoreCase = true)) {
                candidates += IconCandidate(href, parseSizes(it.attr("sizes")).takeIf { s -> s > 0 } ?: 32, 1)
            }
        }
        candidates += IconCandidate(finalUrl.resolve("/favicon.ico").toString(), 32, 0)

        // Prefer large "any" icons around 192-512px.
        val ordered = candidates.distinctBy { it.url }.sortedWith(
            compareByDescending<IconCandidate> { if (it.size >= 96) 1 else 0 }
                .thenByDescending { it.priority }
                .thenBy { if (it.size >= 192) it.size - 192 else 10_000 - it.size }
        )
        var icon: Bitmap? = null
        for (candidate in ordered.take(6)) {
            icon = downloadIcon(candidate.url)
            if (icon != null && icon.width >= 48) break
        }
        if (icon == null || icon.width < 48) {
            icon = downloadIcon("https://www.google.com/s2/favicons?sz=256&domain_url=${finalUrl.host}") ?: icon
        }

        return SiteMetadata(
            url = finalUrl.toString(),
            title = title?.trim()?.take(40)?.takeIf { it.isNotBlank() },
            themeColor = themeColor,
            icon = icon?.let { scaleIcon(it) },
        )
    }

    private fun readManifest(url: String): JsonObject? {
        val request = Request.Builder().url(url).header("User-Agent", Net.MOBILE_USER_AGENT).build()
        return Net.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
        }
    }

    private fun downloadIcon(url: String): Bitmap? = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", Net.MOBILE_USER_AGENT).build()
        Net.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }.getOrNull()

    private fun scaleIcon(bitmap: Bitmap): Bitmap {
        val target = 192
        if (bitmap.width == target && bitmap.height == target) return bitmap
        return Bitmap.createScaledBitmap(bitmap, target, target, true)
    }

    private fun themeColorFrom(doc: Document): String? {
        val metas = doc.select("meta[name=theme-color]")
        return metas.firstOrNull { !it.attr("media").contains("dark") }?.attr("content")
            ?: metas.firstOrNull()?.attr("content")
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun parseSizes(sizes: String?): Int {
        if (sizes.isNullOrBlank()) return 0
        if (sizes.equals("any", ignoreCase = true)) return 512
        return sizes.split(' ').mapNotNull { it.lowercase().substringBefore('x').toIntOrNull() }.maxOrNull() ?: 0
    }

    private fun cleanTitle(title: String): String? {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(" | ", " - ", " – ", " — ", " · ", ": ").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.minByOrNull { it.length }?.takeIf { it.length >= 2 } ?: trimmed
    }

    fun parseColor(value: String?): Int? {
        val v = value?.trim()?.lowercase() ?: return null
        if (v.isEmpty()) return null
        return runCatching {
            when {
                v.startsWith("#") && v.length == 4 -> {
                    val r = v[1].digitToInt(16); val g = v[2].digitToInt(16); val b = v[3].digitToInt(16)
                    Color.rgb(r * 17, g * 17, b * 17)
                }
                v.startsWith("#") && (v.length == 7 || v.length == 9) -> Color.parseColor(v.take(7))
                v.startsWith("rgb") -> {
                    val nums = v.substringAfter('(').substringBefore(')').split(',', ' ', '/')
                        .filter { it.isNotBlank() }.map { it.trim().removeSuffix("%").toFloat() }
                    Color.rgb(nums[0].toInt(), nums[1].toInt(), nums[2].toInt())
                }
                else -> Color.parseColor(v)
            }
        }.getOrNull()
    }
}
