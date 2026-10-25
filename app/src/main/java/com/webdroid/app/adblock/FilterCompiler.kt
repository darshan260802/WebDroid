package com.webdroid.app.adblock

import android.content.Context
import android.util.Log
import app.cash.zipline.QuickJs
import com.webdroid.app.util.Net
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Downloads the user's filter lists and compiles them on device with the same
 * uBO engine bundle, producing a new engine state for [AdblockEngine].
 *
 * Compilation runs in a separate, short-lived QuickJS runtime on its own
 * big-stack thread, so filtering keeps working while it runs.
 */
object FilterCompiler {
    private const val TAG = "FilterCompiler"
    private const val CHUNK = 1 shl 20

    data class Result(val ok: Boolean, val message: String)

    private fun download(urls: List<String>, cache: File): String? {
        for (url in urls) {
            try {
                val request = Request.Builder().url(url)
                    .header("User-Agent", "WebDroid (uBlock Origin engine)")
                    .build()
                Net.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val text = response.body?.string().orEmpty()
                    if (text.length < 64) return@use null
                    cache.writeText(text)
                    text
                }?.let { return it }
            } catch (e: Exception) {
                Log.w(TAG, "download failed $url: ${e.message}")
            }
        }
        return cache.takeIf { it.exists() }?.readText()
    }

    /** Blocking; call from a background worker. */
    fun run(context: Context, overrides: Map<String, Boolean>, progress: (String) -> Unit): Result {
        val catalog = AdblockStorage.catalog(context)
        val lists = AdblockStorage.enabledLists(context, overrides)
        val cacheDir = AdblockStorage.listCacheDir(context)

        progress("Downloading public suffix list")
        val psl = download(catalog.psl.urls, File(cacheDir, "psl.dat"))
            ?: runCatching { AdblockStorage.readAsset(context, "psl.dat") }.getOrNull()
            ?: return Result(false, "Public suffix list unavailable")

        val raw = LinkedHashMap<String, String>()
        lists.forEachIndexed { i, list ->
            progress("Downloading ${list.title} (${i + 1}/${lists.size})")
            download(list.urls, File(cacheDir, "${list.id}.txt"))?.let { raw[list.id] = it }
        }
        if (raw.isEmpty() && lists.isNotEmpty()) return Result(false, "No filter list could be downloaded")

        val thread = Executors.newSingleThreadExecutor { r -> Thread(null, r, "ubo-compiler", 64L shl 20) }
        try {
            return thread.submit(Callable { compile(context, catalog, psl, raw, progress) }).get()
        } finally {
            thread.shutdown()
        }
    }

    private fun compile(
        context: Context,
        catalog: Catalog,
        psl: String,
        lists: Map<String, String>,
        progress: (String) -> Unit,
    ): Result {
        val staging = AdblockStorage.stagingDir(context)
        staging.deleteRecursively()
        staging.mkdirs()
        val js = QuickJs.create()
        try {
            js.maxStackSize = 48L shl 20
            js.evaluate(AdblockStorage.engineScript(context), "engine.js")
            js.evaluate("WD.begin()", "begin.js")
            js.evaluate(JsLiteral.call("WD.setPSL", psl), "psl.js")
            var index = 0
            for ((id, text) in lists) {
                index++
                progress("Compiling $id ($index/${lists.size})")
                js.evaluate(JsLiteral.call("WD.addList", id, text), "list.js")
            }
            progress("Optimising")
            val totals = AdblockStorage.json.parseToJsonElement(js.evaluate("WD.finish()", "finish.js") as String).jsonObject

            progress("Saving")
            File(staging, "selfie.json").writeText(js.evaluate("WD.exportSelfie()", "export.js") as String)
            val size = (js.evaluate("WD.exportBlobSize()", "size.js") as Number).toInt()
            File(staging, "selfie.bin").outputStream().buffered(1 shl 16).use { out ->
                var begin = 0
                val bytes = ByteArray(CHUNK)
                while (begin < size) {
                    val end = minOf(size, begin + CHUNK)
                    val chunk = js.evaluate("WD.exportBlobChunk($begin,$end)", "chunk.js") as String
                    for (i in chunk.indices) bytes[i] = chunk[i].code.toByte()
                    out.write(bytes, 0, chunk.length)
                    begin = end
                }
            }
            File(staging, "cosmetic.json").writeText(js.evaluate("WD.exportCosmetic()", "cosmetic.js") as String)
            js.evaluate("WD.releaseExport()", "release.js")

            val now = Instant.now()
            val meta = CompiledMeta(
                engineVersion = catalog.engineVersion,
                builtAt = now.toString(),
                builtAtMillis = now.toEpochMilli(),
                blobSize = size,
                lists = lists.keys.toList(),
                network = totals["network"]?.jsonPrimitive?.int ?: 0,
                cosmetic = totals["cosmetic"]?.jsonPrimitive?.int ?: 0,
                scriptlets = totals["scriptlets"]?.jsonPrimitive?.int ?: 0,
            )
            File(staging, "meta.json").writeText(AdblockStorage.json.encodeToString(CompiledMeta.serializer(), meta))

            val active = AdblockStorage.activeDir(context)
            val old = File(AdblockStorage.root(context), "old")
            old.deleteRecursively()
            if (active.exists() && !active.renameTo(old)) active.deleteRecursively()
            if (!staging.renameTo(active)) return Result(false, "Could not activate the new filters")
            old.deleteRecursively()
            return Result(true, "${meta.network} network + ${meta.cosmetic} cosmetic filters")
        } catch (t: Throwable) {
            Log.e(TAG, "compile failed", t)
            return Result(false, t.message ?: t.toString())
        } finally {
            js.close()
        }
    }
}
