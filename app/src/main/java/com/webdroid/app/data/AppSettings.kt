package com.webdroid.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class FitMode { FIT, FILL, ZOOM }

/** WebDroid's own UI theme. Websites keep following the system setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class GlobalSettings(
    val adBlockEnabled: Boolean = true,
    /** Filter list ids explicitly toggled away from the catalog default. */
    val listOverrides: Map<String, Boolean> = emptyMap(),
    val backgroundPlay: Boolean = true,
    val rememberPosition: Boolean = true,
    val autoRotate: Boolean = true,
    val defaultFit: FitMode = FitMode.FIT,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Opaque bars and sheets instead of glass. */
    val reduceTransparency: Boolean = false,
    /** Look for new WebDroid releases on GitHub at launch and once a day. */
    val autoUpdate: Boolean = true,
    /** Also download updates over metered connections. */
    val updateOnMobileData: Boolean = false,
)

/** App-wide preferences backed by SharedPreferences. */
class AppSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("webdroid", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<GlobalSettings> = _state.asStateFlow()

    private fun read() = GlobalSettings(
        adBlockEnabled = prefs.getBoolean("adblock", true),
        listOverrides = prefs.getStringSet("list_overrides", emptySet()).orEmpty()
            .mapNotNull { entry ->
                val i = entry.lastIndexOf('=')
                if (i <= 0) null else entry.substring(0, i) to (entry.substring(i + 1) == "1")
            }.toMap(),
        backgroundPlay = prefs.getBoolean("bg_play", true),
        rememberPosition = prefs.getBoolean("resume", true),
        autoRotate = prefs.getBoolean("auto_rotate", true),
        defaultFit = runCatching { FitMode.valueOf(prefs.getString("fit", null) ?: "FIT") }.getOrDefault(FitMode.FIT),
        themeMode = runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
        reduceTransparency = prefs.getBoolean("reduce_transparency", false),
        autoUpdate = prefs.getBoolean("auto_update", true),
        updateOnMobileData = prefs.getBoolean("update_mobile_data", false),
    )

    fun update(transform: (GlobalSettings) -> GlobalSettings) {
        val next = transform(_state.value)
        prefs.edit {
            putBoolean("adblock", next.adBlockEnabled)
            putStringSet("list_overrides", next.listOverrides.map { "${it.key}=${if (it.value) 1 else 0}" }.toSet())
            putBoolean("bg_play", next.backgroundPlay)
            putBoolean("resume", next.rememberPosition)
            putBoolean("auto_rotate", next.autoRotate)
            putString("fit", next.defaultFit.name)
            putString("theme", next.themeMode.name)
            putBoolean("reduce_transparency", next.reduceTransparency)
            putBoolean("auto_update", next.autoUpdate)
            putBoolean("update_mobile_data", next.updateOnMobileData)
        }
        _state.value = next
    }

    /**
     * Random, per-install names for the JavaScript bridges so pages cannot
     * fingerprint WebDroid by probing for fixed global names.
     */
    val bridgeToken: String by lazy {
        prefs.getString("bridge_token", null) ?: ("_" + UUID.randomUUID().toString().replace("-", "").take(10))
            .also { prefs.edit { putString("bridge_token", it) } }
    }

    companion object {
        @Volatile private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }
    }
}
