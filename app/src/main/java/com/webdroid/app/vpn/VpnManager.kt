package com.webdroid.app.vpn

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.webdroid.app.data.WebApp
import com.webdroid.app.util.Net
import com.webdroid.vpncore.Vpncore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Per-site VPN. WebView can only have one proxy for the whole app, so
 * WebDroid points it at its own local router (vpncore) for exactly the
 * domains of open web apps that use a VPN ("reverse bypass"); every other
 * site connects directly. The router then sends each domain to its app's
 * tunnel: Tor, a WireGuard config or a proxy.
 *
 * Domains a VPN app loads from (CDNs, APIs) are learned from its requests
 * and routed before those requests leave, and remembered for next time.
 * Routed domains never fall back to the direct network.
 */
object VpnManager {
    private val main = Handler(Looper.getMainLooper())
    private val direct = Executor { it.run() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var port = 0
    private var appContext: Context? = null

    /** Open VPN apps: app id -> (upstream id, open windows). */
    private val openApps = ConcurrentHashMap<String, Pair<String, Int>>()
    /** Routed domain -> upstream id, for every open VPN app. */
    private val routes = ConcurrentHashMap<String, String>()
    /** Domains each app was seen loading from (persisted). */
    private val learned = ConcurrentHashMap<String, MutableSet<String>>()
    /** Tor upstreams in use ("tor", "tor:de"...), set up once Tor's SOCKS port is known. */
    private val torUpstreams = ConcurrentHashMap.newKeySet<String>()

    private val lock = ReentrantLock()
    private val appliedChanged = lock.newCondition()
    private var wantedGeneration = 0L
    private var appliedGeneration = 0L
    private var applying = false

    private const val MAX_LEARNED = 400
    private const val CHECK_URL = "https://www.cloudflare.com/cdn-cgi/trace"

    /** Known CDNs of popular sites, routed from the first request. */
    private val SEEDS = mapOf(
        "youtube.com" to listOf("googlevideo.com", "ytimg.com", "ggpht.com", "youtube-nocookie.com", "youtu.be"),
        "instagram.com" to listOf("cdninstagram.com", "fbcdn.net"),
        "facebook.com" to listOf("fbcdn.net", "facebook.net", "fbsbx.com"),
        "x.com" to listOf("twimg.com", "twitter.com", "t.co"),
        "twitter.com" to listOf("twimg.com", "x.com", "t.co"),
        "reddit.com" to listOf("redd.it", "redditmedia.com", "redditstatic.com"),
        "netflix.com" to listOf("nflxvideo.net", "nflximg.net", "nflxext.com", "nflxso.net"),
        "tiktok.com" to listOf("tiktokcdn.com", "tiktokv.com", "ttwstatic.com", "tiktokcdn-us.com"),
        "twitch.tv" to listOf("jtvnw.net", "ttvnw.net", "twitchcdn.net"),
        "spotify.com" to listOf("scdn.co", "spotifycdn.com"),
        "primevideo.com" to listOf("aiv-cdn.net", "aiv-delivery.net", "media-amazon.com"),
        "bbc.co.uk" to listOf("bbci.co.uk", "bbc.com"),
        "discord.com" to listOf("discordapp.com", "discordapp.net", "discord.gg", "discord.media"),
        "telegram.org" to listOf("t.me", "telegram.me"),
        "whatsapp.com" to listOf("whatsapp.net"),
    )

    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    private val reverseBypass: Boolean by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE_REVERSE_BYPASS)
    }

    /** The upstream an app routes through, or null for a direct app. */
    fun upstreamFor(context: Context, app: WebApp): String? {
        val choice = app.settings.vpn ?: return null
        val profiles = VpnProfiles.get(context)
        return when {
            choice == VpnProfiles.TOR_ID -> VpnProfiles.torUpstream(profiles.torExitFor(app.settings))
            profiles.get(choice) != null -> "p_$choice"
            else -> null
        }
    }

    /** Domain WebDroid routes for a host: its registrable domain ("www.bbc.co.uk" -> "bbc.co.uk"). */
    fun domainOf(host: String): String? {
        val h = host.lowercase().trimEnd('.').removePrefix("[").removeSuffix("]")
        if (h.isEmpty() || h == "localhost" || h.endsWith(".local")) return null
        if (h.all { it.isDigit() || it == '.' } || h.contains(':')) {
            // IP literals route as themselves, except the local network.
            val local = h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") ||
                Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(h) || h == "::1" || h.startsWith("fe80") || h.startsWith("fd")
            return if (local) null else h
        }
        return "https://$h/".toHttpUrlOrNull()?.topPrivateDomain() ?: h
    }

    private fun ensureRouter(): Boolean {
        if (port > 0) return true
        return runCatching { port = Vpncore.start().toInt() }.isSuccess && port > 0
    }

    /**
     * Before a VPN app loads anything: start its tunnel and route its domains.
     * [onReady] runs on the main thread; false means the page must not load.
     */
    fun attach(context: Context, app: WebApp, onReady: (ok: Boolean, message: String?) -> Unit) {
        val ctx = context.applicationContext
        appContext = ctx
        if (app.settings.vpn == null) return onReady(true, null)
        val upstream = upstreamFor(ctx, app)
            ?: return onReady(false, "This app's VPN profile was deleted. Choose another one in the app's settings.")
        if (!isSupported) {
            return onReady(false, "Android System WebView on this phone can't route sites through a VPN. Update it from the Play Store.")
        }
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                if (!ensureRouter()) return@withContext "The VPN couldn't start on this device."
                configureUpstream(ctx, upstream)
            }
            if (error != null) {
                main.post { onReady(false, error) }
                return@launch
            }
            val previous = openApps[app.id]
            openApps[app.id] = upstream to ((previous?.second ?: 0) + 1)
            loadLearned(ctx, app.id)
            rebuildRoutes()
            val generation = requestApply()
            awaitApplied(generation, 4_000)
            main.post { onReady(true, null) }
        }
    }

    /** A VPN app's window closed. */
    fun detach(app: WebApp) {
        val entry = openApps[app.id] ?: return
        if (entry.second > 1) {
            openApps[app.id] = entry.first to entry.second - 1
            return
        }
        openApps.remove(app.id)
        saveLearned(app.id)
        rebuildRoutes()
        requestApply()
        val stillUsed = openApps.values.map { it.first }.toSet()
        if (entry.first !in stillUsed) release(entry.first)
    }

    /** Closes an upstream no open app uses any more. */
    private fun release(id: String) {
        runCatching { Vpncore.removeUpstream(id) }
        if (VpnProfiles.isTor(id)) {
            torUpstreams.remove(id)
            if (torUpstreams.isEmpty()) TorController.idle()
        }
    }

    /** The app's VPN choice (or Tor exit country) changed while it's open. */
    fun reattach(context: Context, old: WebApp, new: WebApp, onReady: (ok: Boolean, message: String?) -> Unit) {
        val previous = openApps.remove(old.id)
        if (previous != null) {
            rebuildRoutes()
            if (openApps.values.none { it.first == previous.first }) release(previous.first)
        }
        if (new.settings.vpn == null) {
            requestApply()
            onReady(true, null)
        } else {
            attach(context, new, onReady)
        }
    }

    private fun configureUpstream(context: Context, id: String): String? {
        if (VpnProfiles.isTor(id)) {
            torUpstreams.add(id)
            TorController.onSocksPort = { torPort -> applyTorUpstreams(context, torPort) }
            main.post { TorController.start(context) }
            TorController.status.value.socksPort.takeIf { it > 0 }?.let { applyTorUpstreams(context, it) }
            return null
        }
        val profile = VpnProfiles.get(context).get(id.removePrefix("p_")) ?: return "This app's VPN profile was deleted."
        return runCatching { Vpncore.setUpstream(id, profile.kind, profile.config) }
            .exceptionOrNull()?.let { "${profile.name}: ${it.message ?: "invalid configuration"}" }
    }

    /**
     * Points every Tor upstream at Tor's SOCKS port. Upstreams with an exit
     * country have the core steer their streams through Tor's controller.
     */
    private fun applyTorUpstreams(context: Context, torPort: Int) {
        TorController.controlPaths(context)?.let { (socket, geoIp) ->
            runCatching { Vpncore.setTorControl(socket, geoIp) }
        }
        torUpstreams.forEach { id ->
            runCatching { Vpncore.setTorUpstream(id, "127.0.0.1:$torPort", id.substringAfter(':', "")) }
        }
    }

    /** The default Tor exit country changed: move open apps that follow it. */
    fun torDefaultChanged(context: Context) {
        val ctx = context.applicationContext
        val repo = com.webdroid.app.data.WebAppRepository.get(ctx)
        val moved = mutableSetOf<String>()
        openApps.forEach { (appId, entry) ->
            if (!VpnProfiles.isTor(entry.first)) return@forEach
            val app = repo.get(appId) ?: return@forEach
            val next = upstreamFor(ctx, app) ?: return@forEach
            if (next != entry.first) {
                openApps[appId] = next to entry.second
                moved += entry.first
                configureUpstream(ctx, next)
            }
        }
        if (moved.isEmpty()) return
        rebuildRoutes()
        requestApply()
        val used = openApps.values.map { it.first }.toSet()
        moved.filterNot { it in used }.forEach { release(it) }
    }

    /** Re-applies a profile after it was edited. */
    fun refreshProfile(context: Context, profileId: String) {
        val id = "p_$profileId"
        if (openApps.values.any { it.first == id }) scope.launch { configureUpstream(context, id) }
    }

    private fun rebuildRoutes() {
        val next = HashMap<String, String>()
        val ctx = appContext
        // Learned domains first, so an app's own domains win on conflicts.
        openApps.forEach { (appId, entry) ->
            learned[appId]?.forEach { next.putIfAbsent(it, entry.first) }
        }
        openApps.forEach { (appId, entry) ->
            val app = ctx?.let { com.webdroid.app.data.WebAppRepository.get(it).get(appId) } ?: return@forEach
            val host = runCatching { android.net.Uri.parse(app.url).host }.getOrNull() ?: return@forEach
            val domain = domainOf(host) ?: return@forEach
            next[domain] = entry.first
            SEEDS[domain]?.forEach { next[it] = entry.first }
        }
        routes.keys.retainAll(next.keys)
        routes.putAll(next)
        val json = buildJsonObject { next.forEach { (k, v) -> put(k, v) } }.toString()
        runCatching { Vpncore.setRules(json) }
    }

    /**
     * Called for every request of a VPN app, before it goes out (from
     * shouldInterceptRequest). New domains are routed and WebView's proxy
     * updated before the request continues.
     */
    fun observe(app: WebApp, host: String?) {
        val entry = openApps[app.id] ?: return
        val domain = host?.let { domainOf(it) } ?: return
        if (routes.containsKey(domain)) return
        routes[domain] = entry.first
        runCatching { Vpncore.addRule(domain, entry.first) }
        learned.getOrPut(app.id) { ConcurrentHashMap.newKeySet() }.let { set ->
            if (set.size < MAX_LEARNED) set.add(domain)
        }
        awaitApplied(requestApply(), 1_500)
    }

    private fun requestApply(): Long = lock.withLock {
        wantedGeneration++
        if (!applying) {
            applying = true
            main.post { applyProxy() }
        }
        wantedGeneration
    }

    private fun applyProxy() {
        val generation = lock.withLock { wantedGeneration }
        val controller = runCatching { ProxyController.getInstance() }.getOrNull()
        if (controller == null) {
            onApplied(generation)
            return
        }
        val domains = routes.keys.toList()
        if (domains.isEmpty() || port <= 0) {
            runCatching { controller.clearProxyOverride(direct) { onApplied(generation) } }.onFailure { onApplied(generation) }
            return
        }
        val builder = ProxyConfig.Builder().addProxyRule("http://127.0.0.1:$port")
        if (reverseBypass) {
            // Reverse bypass: only these hosts use the proxy.
            domains.forEach { d ->
                builder.addBypassRule(d)
                builder.addBypassRule("*.$d")
            }
            builder.setReverseBypassEnabled(true)
        }
        // Without reverse bypass everything goes to the router, which sends
        // unrouted hosts out directly.
        runCatching { controller.setProxyOverride(builder.build(), direct) { onApplied(generation) } }
            .onFailure { onApplied(generation) }
    }

    private fun onApplied(generation: Long) {
        lock.withLock {
            if (generation > appliedGeneration) appliedGeneration = generation
            applying = false
            appliedChanged.signalAll()
            if (wantedGeneration > appliedGeneration) {
                applying = true
                main.post { applyProxy() }
            }
        }
    }

    private fun awaitApplied(generation: Long, timeoutMs: Long) {
        if (Looper.myLooper() == Looper.getMainLooper()) return
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        lock.withLock {
            while (appliedGeneration < generation && left > 0) {
                left = appliedChanged.awaitNanos(left)
            }
        }
    }

    private fun loadLearned(context: Context, appId: String) {
        if (learned.containsKey(appId)) return
        val stored = context.getSharedPreferences("vpn_domains", Context.MODE_PRIVATE).getStringSet(appId, emptySet()).orEmpty()
        learned[appId] = ConcurrentHashMap.newKeySet<String>().apply { addAll(stored) }
    }

    private fun saveLearned(appId: String) {
        val ctx = appContext ?: return
        val set = learned[appId]?.toSet() ?: return
        ctx.getSharedPreferences("vpn_domains", Context.MODE_PRIVATE).edit { putStringSet(appId, set) }
    }

    /** Forget what an app was seen loading (e.g. when it's deleted). */
    fun forget(context: Context, appId: String) {
        learned.remove(appId)
        context.getSharedPreferences("vpn_domains", Context.MODE_PRIVATE).edit { remove(appId) }
    }

    /** HTTP client for an app's native requests (player, downloads): through its VPN when it has one. */
    fun httpClient(context: Context, app: WebApp?): OkHttpClient {
        val upstream = app?.let { upstreamFor(context, it) } ?: return Net.client
        if (port <= 0 || openApps[app.id] == null) return Net.client
        val routeHosts = Interceptor { chain ->
            val host = chain.request().url.host
            domainOf(host)?.let { domain ->
                if (routes[domain] == null) {
                    routes[domain] = upstream
                    runCatching { Vpncore.addRule(domain, upstream) }
                }
            }
            chain.proceed(chain.request())
        }
        return Net.client.newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .addInterceptor(routeHosts)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    data class CheckResult(val ip: String?, val country: String?, val error: String?)

    /** Where an upstream exits: IP and country via Cloudflare's trace page. */
    suspend fun check(context: Context, choice: String): CheckResult = withContext(Dispatchers.IO) {
        appContext = context.applicationContext
        if (!ensureRouter()) return@withContext CheckResult(null, null, "The VPN couldn't start on this device.")
        val id = if (choice == VpnProfiles.TOR_ID) {
            VpnProfiles.torUpstream(VpnProfiles.get(context).tor.value.exitCountry)
        } else "p_$choice"
        configureUpstream(context.applicationContext, id)?.let { return@withContext CheckResult(null, null, it) }
        try {
            val body = Vpncore.check(id, CHECK_URL, if (VpnProfiles.isTor(id)) 120L else 30L)
            val fields = body.lineSequence().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i > 0) line.substring(0, i) to line.substring(i + 1) else null
            }.toMap()
            CheckResult(fields["ip"], fields["loc"], null)
        } catch (e: Exception) {
            CheckResult(null, null, e.message ?: "Couldn't connect")
        } finally {
            // Keep upstreams only for open apps.
            if (openApps.values.none { it.first == id }) release(id)
        }
    }

    /** Validates a WireGuard config; returns an error message or null. */
    fun validateWireGuard(config: String): String? =
        runCatching { Vpncore.validateWireGuard(config) }.exceptionOrNull()?.message

    /** What's wrong with an open app's VPN right now, if anything. */
    fun problemFor(context: Context, app: WebApp): String? {
        val id = openApps[app.id]?.first ?: upstreamFor(context, app) ?: return null
        val upstream = (statusJson()?.get("upstreams") as? JsonObject)?.get(id) as? JsonObject ?: return null
        val state = (upstream["state"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val error = (upstream["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        return error?.takeIf { state == "error" && it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
    }

    fun statusJson(): JsonObject? = runCatching {
        Json.parseToJsonElement(Vpncore.status()) as? JsonObject
    }.getOrNull()
}
