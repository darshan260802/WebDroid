package com.webdroid.app.vpn

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.torproject.jni.TorService
import java.io.File

/**
 * Runs Tor (Guardian Project's tor-android) inside WebDroid. It starts the
 * first time a web app routed through Tor opens and then stays up for the
 * life of the process (tor can only run once per process); when no app
 * needs it any more its network is simply switched off.
 *
 * Exit countries are chosen per app by the VPN core, which steers each
 * app's streams through Tor's control socket (see tools/vpncore/tor.go), so
 * tor itself runs without ExitNodes.
 */
object TorController {
    enum class Phase { OFF, STARTING, ON, ERROR }

    data class Status(val phase: Phase, val progress: Int = 0, val summary: String = "", val socksPort: Int = 0)

    private val _status = MutableStateFlow(Status(Phase.OFF))
    val status: StateFlow<Status> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var service: TorService? = null
    private var bound = false
    private var binding = false
    private var poller: Job? = null
    private var networkOn = false

    /** Called with Tor's SOCKS port once it's listening. */
    var onSocksPort: ((Int) -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? TorService.LocalBinder)?.service
            startPolling()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _status.value = Status(Phase.OFF)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TorService.ACTION_STATUS -> when (intent.getStringExtra(TorService.EXTRA_STATUS)) {
                    TorService.STATUS_ON -> _status.value = _status.value.copy(phase = Phase.ON, progress = 100, summary = "Connected")
                    TorService.STATUS_STARTING -> if (_status.value.phase != Phase.ON) {
                        _status.value = _status.value.copy(phase = Phase.STARTING)
                    }
                    TorService.STATUS_OFF, TorService.STATUS_STOPPING -> _status.value = Status(Phase.OFF)
                }
                TorService.ACTION_ERROR -> _status.value = _status.value.copy(
                    phase = Phase.ERROR,
                    summary = intent.getStringExtra(Intent.EXTRA_TEXT) ?: "Tor stopped",
                )
            }
        }
    }

    val hasGeoIp: Boolean
        get() = geoIpAvailable

    @Volatile private var geoIpAvailable = false

    /** Starts Tor (or turns its network back on). Safe to call repeatedly. */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        if (bound || binding) {
            if (bound && !networkOn) setNetwork(true)
            return
        }
        binding = true
        _status.value = Status(Phase.STARTING, summary = "Starting Tor")
        // torrc and the GeoIP copy (several MB) are written off the main thread.
        scope.launch {
            writeTorrc(app)
            android.os.Handler(android.os.Looper.getMainLooper()).post { bind(app) }
        }
    }

    @Synchronized
    private fun bind(app: Context) {
        binding = false
        LocalBroadcastManager.getInstance(app).registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(TorService.ACTION_STATUS)
                addAction(TorService.ACTION_ERROR)
            },
        )
        bound = app.bindService(Intent(app, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
        networkOn = bound
        if (!bound) _status.value = Status(Phase.ERROR, summary = "Couldn't start Tor")
    }

    /** No app needs Tor right now: stop using the network but keep tor ready. */
    fun idle() {
        if (bound && networkOn) setNetwork(false)
    }

    private fun setNetwork(on: Boolean) {
        networkOn = on
        scope.launch {
            runCatching { service?.torControlConnection?.setConf("DisableNetwork", if (on) "0" else "1") }
        }
    }

    /** The default exit country changed: apps that follow it move over. */
    fun applySettings(context: Context, @Suppress("UNUSED_PARAMETER") settings: TorSettings) {
        VpnManager.torDefaultChanged(context)
    }

    /**
     * Tor's control socket and the IPv4 GeoIP file, for the VPN core to steer
     * exit countries; null until Tor runs.
     */
    fun controlPaths(context: Context): Pair<String, String>? {
        if (!bound) return null
        val socket = File(context.getDir("TorService", Context.MODE_PRIVATE), "data/ControlSocket")
        val geoIp = File(context.filesDir, "tor-geoip/geoip")
        return socket.absolutePath to (if (geoIpAvailable) geoIp.absolutePath else "")
    }

    private fun writeTorrc(context: Context) {
        val geoip = installGeoIp(context)
        val lines = buildList {
            add("ClientOnly 1")
            add("AvoidDiskWrites 1")
            add("SafeSocks 0")
            if (geoip != null) {
                add("GeoIPFile ${geoip.first.absolutePath}")
                add("GeoIPv6File ${geoip.second.absolutePath}")
            }
        }
        runCatching { TorService.getTorrc(context).writeText(lines.joinToString("\n", postfix = "\n")) }
    }

    /** Copies Tor's GeoIP databases (bundled at build time) so exit countries work. */
    private fun installGeoIp(context: Context): Pair<File, File>? {
        val dir = File(context.filesDir, "tor-geoip").apply { mkdirs() }
        val v4 = File(dir, "geoip")
        val v6 = File(dir, "geoip6")
        val ok = runCatching {
            listOf("tor/geoip" to v4, "tor/geoip6" to v6).forEach { (asset, file) ->
                if (!file.exists() || file.length() == 0L) {
                    context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
                }
            }
            true
        }.getOrDefault(false)
        geoIpAvailable = ok
        return if (ok) v4 to v6 else null
    }

    private fun startPolling() {
        poller?.cancel()
        poller = scope.launch {
            var reportedPort = 0
            while (isActive) {
                val svc = service ?: break
                val port = runCatching { svc.getSocksPort() }.getOrDefault(-1)
                if (port > 0 && port != reportedPort) {
                    reportedPort = port
                    _status.value = _status.value.copy(socksPort = port)
                    onSocksPort?.invoke(port)
                }
                if (_status.value.phase != Phase.ON) {
                    val phase = runCatching { svc.getInfo("status/bootstrap-phase") }.getOrNull().orEmpty()
                    val progress = Regex("PROGRESS=(\\d+)").find(phase)?.groupValues?.get(1)?.toIntOrNull()
                    val summary = Regex("SUMMARY=\"([^\"]*)\"").find(phase)?.groupValues?.get(1)
                    if (progress != null) {
                        _status.value = _status.value.copy(
                            phase = if (progress >= 100) Phase.ON else Phase.STARTING,
                            progress = progress,
                            summary = summary ?: _status.value.summary,
                        )
                    }
                    delay(700)
                } else {
                    delay(5_000)
                }
            }
        }
    }
}
