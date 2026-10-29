package com.webdroid.app.vpn

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.webdroid.app.data.WebAppSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
enum class VpnType { WIREGUARD, SOCKS5, HTTP }

/** A user-added way out: a WireGuard config or a proxy. Tor is built in. */
@Serializable
data class VpnProfile(
    val id: String,
    val name: String,
    val type: VpnType,
    /** wg-quick .conf text, or [user:pass@]host:port for proxies. */
    val config: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val kind: String
        get() = when (type) {
            VpnType.WIREGUARD -> "wireguard"
            VpnType.SOCKS5 -> "socks5"
            VpnType.HTTP -> "http"
        }

    val typeLabel: String
        get() = when (type) {
            VpnType.WIREGUARD -> "WireGuard"
            VpnType.SOCKS5 -> "SOCKS5 proxy"
            VpnType.HTTP -> "HTTP proxy"
        }

    /** Endpoint shown in lists (never the keys). */
    val summary: String
        get() = when (type) {
            VpnType.WIREGUARD -> config.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("Endpoint", ignoreCase = true) }
                ?.substringAfter('=')?.trim() ?: "WireGuard"
            else -> config.substringAfterLast('@').trim()
        }
}

@Serializable
data class TorSettings(
    /** ISO country code for exit relays ("" = any country). */
    val exitCountry: String = "",
)

@Serializable
private data class StoredVpn(
    val profiles: List<VpnProfile> = emptyList(),
    val tor: TorSettings = TorSettings(),
)

/** Saved VPN profiles and Tor settings (private app storage). */
class VpnProfiles private constructor(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "vpn.json"))
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(load())
    val profiles: StateFlow<List<VpnProfile>> get() = _profiles
    private val _profiles = MutableStateFlow(_state.value.profiles)
    private val _tor = MutableStateFlow(_state.value.tor)
    val tor: StateFlow<TorSettings> = _tor.asStateFlow()

    private fun load(): StoredVpn = runCatching {
        if (!file.baseFile.exists()) return StoredVpn()
        json.decodeFromString(StoredVpn.serializer(), file.readFully().decodeToString())
    }.getOrDefault(StoredVpn())

    @Synchronized
    private fun save(next: StoredVpn) {
        _state.value = next
        _profiles.value = next.profiles
        _tor.value = next.tor
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(StoredVpn.serializer(), next).encodeToByteArray())
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
        }
    }

    fun get(id: String?): VpnProfile? = id?.let { wanted -> _profiles.value.firstOrNull { it.id == wanted } }

    fun add(name: String, type: VpnType, config: String): VpnProfile {
        val profile = VpnProfile(UUID.randomUUID().toString().take(8), name.trim().ifBlank { type.name }, type, config.trim())
        save(_state.value.copy(profiles = _state.value.profiles + profile))
        return profile
    }

    fun rename(id: String, name: String) {
        save(_state.value.copy(profiles = _state.value.profiles.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it }))
    }

    fun remove(id: String) {
        save(_state.value.copy(profiles = _state.value.profiles.filterNot { it.id == id }))
    }

    fun setTor(settings: TorSettings) {
        save(_state.value.copy(tor = settings))
    }

    /** Display name for a VPN choice (Tor with its default exit country). */
    fun label(id: String?): String? = when (id) {
        null -> null
        TOR_ID -> "Tor" + (_tor.value.exitCountry.takeIf { it.isNotEmpty() }?.let { " · ${it.uppercase()}" } ?: "")
        else -> get(id)?.name
    }

    /** Display name for an app's VPN, with its own Tor exit country. */
    fun labelFor(settings: WebAppSettings): String? = when (settings.vpn) {
        null -> null
        TOR_ID -> "Tor" + (torExitFor(settings).takeIf { it.isNotEmpty() }?.let { " · ${it.uppercase()}" } ?: "")
        else -> get(settings.vpn)?.name
    }

    /** Where an app's Tor traffic leaves: a country code, or "" for anywhere. */
    fun torExitFor(settings: WebAppSettings): String = when (settings.torCountry) {
        "" -> _tor.value.exitCountry
        ANY_COUNTRY -> ""
        else -> settings.torCountry
    }

    companion object {
        const val TOR_ID = "tor"

        /** An app's Tor country when it lets Tor pick any exit. */
        const val ANY_COUNTRY = "any"

        /** Router upstream for Tor leaving in [country] ("" for anywhere). */
        fun torUpstream(country: String): String = if (country.isEmpty()) TOR_ID else "$TOR_ID:$country"

        fun isTor(upstream: String): Boolean = upstream == TOR_ID || upstream.startsWith("$TOR_ID:")

        fun countryName(code: String): String =
            TOR_COUNTRIES.firstOrNull { it.first == code }?.second
                ?: java.util.Locale("", code.uppercase()).displayCountry.ifBlank { code.uppercase() }

        /** Exit countries offered for Tor (relay-rich ones first). */
        val TOR_COUNTRIES = listOf(
            "" to "Any country",
            "us" to "United States", "de" to "Germany", "nl" to "Netherlands", "fr" to "France",
            "gb" to "United Kingdom", "ch" to "Switzerland", "se" to "Sweden", "ca" to "Canada",
            "fi" to "Finland", "at" to "Austria", "ro" to "Romania", "pl" to "Poland",
            "jp" to "Japan", "sg" to "Singapore", "in" to "India", "au" to "Australia",
        )

        @Volatile private var instance: VpnProfiles? = null

        fun get(context: Context): VpnProfiles =
            instance ?: synchronized(this) {
                instance ?: VpnProfiles(context.applicationContext).also { instance = it }
            }
    }
}
