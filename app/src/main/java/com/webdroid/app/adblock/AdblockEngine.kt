package com.webdroid.app.adblock

import android.content.Context
import android.util.Log
import android.util.LruCache
import app.cash.zipline.QuickJs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Hosts uBlock Origin's filtering engine (tools/adblock/src/engine.js) in an
 * embedded QuickJS interpreter.
 *
 * QuickJS is single threaded, so every call is funnelled through one
 * dedicated thread with a large stack. Callers on WebView IO threads wait a
 * bounded time and fail open, so a slow or reloading engine can delay a
 * request by milliseconds but never break a page.
 */
object AdblockEngine {
    private const val TAG = "AdblockEngine"

    enum class State { IDLE, LOADING, READY, FAILED, UNAVAILABLE }

    data class Status(
        val state: State = State.IDLE,
        val networkFilters: Int = 0,
        val cosmeticFilters: Int = 0,
        val scriptletFilters: Int = 0,
        val source: String = "",
        val updating: Boolean = false,
        val updateProgress: String? = null,
        val lastError: String? = null,
        val loadMillis: Long = 0,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Requests blocked since the process started. */
    val blockedTotal = AtomicLong(0)

    private lateinit var appContext: Context

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "ubo-engine", 64L shl 20).apply { isDaemon = true }
    }

    // Owned by the engine thread.
    private var js: QuickJs? = null

    @Volatile private var ready = false
    @Volatile private var loading = false
    @Volatile private var readyLatch = CountDownLatch(1)

    private val matchCache = object : LruCache<String, String>(4096) {}

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        reload()
    }

    val isReady: Boolean get() = ready

    /** (Re)loads the newest engine state; matching fails open meanwhile. */
    fun reload() {
        loading = true
        _status.update { it.copy(state = State.LOADING) }
        executor.execute { loadOnEngineThread() }
    }

    private fun newRuntime(): QuickJs = QuickJs.create().apply {
        // The thread has a 64 MiB stack; keep QuickJS's own guard below it.
        maxStackSize = 48L shl 20
    }

    private fun loadOnEngineThread() {
        val started = System.currentTimeMillis()
        try {
            val source = AdblockStorage.currentSource(appContext)
            if (source == null) {
                ready = false
                _status.update { it.copy(state = State.UNAVAILABLE, lastError = "Ad-block assets missing from this build") }
                readyLatch.countDown()
                return
            }
            ready = false
            js?.close()
            js = null
            matchCache.evictAll()
            val runtime = newRuntime()
            js = runtime
            runtime.evaluate(AdblockStorage.engineScript(appContext), "engine.js")
            source.openBlob().use { QuickJsBlob.inject(runtime, it, source.blobSize) }
            val script = StringBuilder(4 shl 20)
            script.append("WD.importSelfie(")
            JsLiteral.append(script, source.selfieJson())
            script.append(',')
            JsLiteral.append(script, source.cosmeticJson())
            script.append(')')
            val result = runtime.evaluate(script.toString(), "import.js") as? String
            val obj = AdblockStorage.json.parseToJsonElement(result ?: "{}").jsonObject
            if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                error("import failed: $result")
            }
            runtime.gc()
            ready = true
            val elapsed = System.currentTimeMillis() - started
            Log.i(TAG, "engine ready from ${source.label} in $elapsed ms")
            _status.update {
                it.copy(
                    state = State.READY,
                    networkFilters = obj["network"]?.jsonPrimitive?.int ?: 0,
                    cosmeticFilters = obj["cosmetic"]?.jsonPrimitive?.int ?: 0,
                    scriptletFilters = obj["scriptlets"]?.jsonPrimitive?.int ?: 0,
                    source = source.label,
                    lastError = null,
                    loadMillis = elapsed,
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "engine load failed", t)
            ready = false
            _status.update { it.copy(state = State.FAILED, lastError = t.message ?: t.toString()) }
        } finally {
            loading = false
            readyLatch.countDown()
        }
    }

    /** Waits (bounded) for the first load so early page requests are filtered. */
    fun awaitReady(timeoutMs: Long): Boolean {
        if (ready) return true
        if (!::appContext.isInitialized) return false
        readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return ready
    }

    private fun <T> call(timeoutMs: Long, fallback: T, block: (QuickJs) -> T): T {
        if (!ready || loading) return fallback
        val future = executor.submit(java.util.concurrent.Callable<T> {
            val runtime = js
            if (runtime == null || !ready) fallback else block(runtime)
        })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            future.cancel(false)
            fallback
        }
    }

    /**
     * uBO verdict for a request: "" (no match), "a" (allowed by exception),
     * "b" (block) or "r:<resource>" (block, serve neutered replacement).
     */
    fun match(url: String, origin: String, type: String): String {
        val key = "$type|$origin|$url"
        matchCache.get(key)?.let { return it }
        val result = call(400, "") { runtime ->
            runtime.evaluate(JsLiteral.call("WD.match", url, origin, type), "m") as? String ?: ""
        }
        if (ready) matchCache.put(key, result)
        if (result.startsWith("b") || result.startsWith("r:")) blockedTotal.incrementAndGet()
        return result
    }

    /** Cosmetic + scriptlet payload for a document (JSON), "" when unavailable. */
    fun cosmetic(url: String): String = call(300, "") { runtime ->
        runtime.evaluate(JsLiteral.call("WD.cosmetic", url), "c") as? String ?: ""
    }

    /** Generic selectors for the class/id keys seen in a page (JSON array). */
    fun lowGeneric(keys: String): String = call(200, "[]") { runtime ->
        runtime.evaluate(JsLiteral.call("WD.lowGeneric", keys), "g") as? String ?: "[]"
    }

    internal fun setUpdating(updating: Boolean, progress: String? = null, error: String? = null) {
        _status.update { it.copy(updating = updating, updateProgress = progress, lastError = error ?: it.lastError) }
    }
}
