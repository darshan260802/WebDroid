package com.webdroid.app.web

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import java.io.File

object DownloadHelper {
    fun fileName(url: String, contentDisposition: String?, mimeType: String?): String =
        URLUtil.guessFileName(url, contentDisposition, mimeType)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")

    /** Hands an http(s) download to the system DownloadManager. */
    fun enqueue(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        referer: String?,
        cookieManager: CookieManager,
        fileName: String = fileName(url, contentDisposition, mimeType),
    ): String {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            val mime = mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase())
            if (mime != null) setMimeType(mime)
            cookieManager.getCookie(url)?.let { addRequestHeader("Cookie", it) }
            if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
            if (!referer.isNullOrBlank()) addRequestHeader("Referer", referer)
            setTitle(fileName)
            setDescription(Uri.parse(url).host)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        return fileName
    }

    /**
     * Downloads through [client] (a web app's VPN) instead of the system
     * DownloadManager, which would bypass it. Streams to Downloads.
     */
    fun downloadWith(
        context: Context,
        client: okhttp3.OkHttpClient,
        url: String,
        userAgent: String?,
        referer: String?,
        contentDisposition: String?,
        mimeType: String?,
    ): String? {
        val request = okhttp3.Request.Builder().url(url).apply {
            if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent)
            if (!referer.isNullOrBlank()) header("Referer", referer)
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val mime = mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: body.contentType()?.let { "${it.type}/${it.subtype}" }
            val name = fileName(url, contentDisposition ?: response.header("Content-Disposition"), mime)
            val saved = saveStream(context, name, mime, body.byteStream()) ?: return null
            return name.takeIf { saved != Uri.EMPTY }
        }
    }

    private fun saveStream(context: Context, fileName: String, mimeType: String?, input: java.io.InputStream): Uri? {
        val mime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out -> input.copyTo(out, 256 * 1024) }
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                return null
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            var target = File(dir, fileName)
            var n = 1
            while (target.exists()) {
                target = File(dir, fileName.substringBeforeLast('.') + " ($n)." + fileName.substringAfterLast('.', "bin"))
                n++
            }
            target.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
            Uri.fromFile(target)
        }
    }

    /** Writes in-memory content (blob:/data: downloads) to Downloads. */
    fun saveBytes(context: Context, fileName: String, mimeType: String?, bytes: ByteArray): Uri? {
        val mime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            var target = File(dir, fileName)
            var n = 1
            while (target.exists()) {
                target = File(dir, fileName.substringBeforeLast('.') + " ($n)." + fileName.substringAfterLast('.', "bin"))
                n++
            }
            target.writeBytes(bytes)
            Uri.fromFile(target)
        }
    }

    fun decodeDataUrl(url: String): Pair<String, ByteArray>? {
        if (!url.startsWith("data:")) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val meta = url.substring(5, comma)
        val data = url.substring(comma + 1)
        val mime = meta.substringBefore(';').ifBlank { "application/octet-stream" }
        val bytes = if (meta.contains(";base64")) {
            android.util.Base64.decode(data, android.util.Base64.DEFAULT)
        } else {
            Uri.decode(data).toByteArray()
        }
        return mime to bytes
    }
}
