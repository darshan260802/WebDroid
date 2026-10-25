package com.webdroid.app.adblock

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream

@Serializable
data class FilterListInfo(
    val id: String,
    val title: String,
    val group: String,
    val enabled: Boolean = false,
    val urls: List<String> = emptyList(),
)

@Serializable
data class PslInfo(val urls: List<String> = emptyList())

@Serializable
data class Catalog(
    val engineVersion: Int = 0,
    val builtAt: String = "",
    val blobSize: Int = 0,
    val psl: PslInfo = PslInfo(),
    val lists: List<FilterListInfo> = emptyList(),
)

/** Metadata written next to an on-device compiled engine state. */
@Serializable
data class CompiledMeta(
    val engineVersion: Int,
    val builtAt: String,
    val builtAtMillis: Long,
    val blobSize: Int,
    val lists: List<String>,
    val network: Int = 0,
    val cosmetic: Int = 0,
    val scriptlets: Int = 0,
)

/** A loadable engine state, either bundled in the APK or compiled on device. */
interface EngineSource {
    val label: String
    val blobSize: Int
    fun selfieJson(): String
    fun cosmeticJson(): String
    fun openBlob(): InputStream
}

object AdblockStorage {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private const val ASSET_DIR = "adblock"

    fun root(context: Context) = File(context.filesDir, "adblock").apply { mkdirs() }
    fun activeDir(context: Context) = File(root(context), "active")
    fun stagingDir(context: Context) = File(root(context), "staging")
    fun listCacheDir(context: Context) = File(root(context), "lists").apply { mkdirs() }

    @Volatile private var catalogCache: Catalog? = null

    fun hasBundledEngine(context: Context): Boolean =
        runCatching { context.assets.list(ASSET_DIR)?.contains("engine.js") == true }.getOrDefault(false)

    fun catalog(context: Context): Catalog = catalogCache ?: runCatching {
        json.decodeFromString(Catalog.serializer(), readAsset(context, "catalog.json"))
    }.getOrDefault(Catalog()).also { catalogCache = it }

    fun readAsset(context: Context, name: String): String =
        context.assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }

    fun openAsset(context: Context, name: String): InputStream = context.assets.open("$ASSET_DIR/$name")

    fun engineScript(context: Context): String = readAsset(context, "engine.js")

    fun activeMeta(context: Context): CompiledMeta? = runCatching {
        val f = File(activeDir(context), "meta.json")
        if (!f.exists()) null else json.decodeFromString(CompiledMeta.serializer(), f.readText())
    }.getOrNull()

    private fun bundledSource(context: Context): EngineSource {
        val catalog = catalog(context)
        return object : EngineSource {
            override val label = "bundled ${catalog.builtAt}"
            override val blobSize = catalog.blobSize
            override fun selfieJson() = readAsset(context, "selfie.json")
            override fun cosmeticJson() = readAsset(context, "cosmetic.json")
            override fun openBlob(): InputStream = openAsset(context, "selfie.bin").buffered(1 shl 16)
        }
    }

    private fun filesSource(context: Context, meta: CompiledMeta): EngineSource {
        val dir = activeDir(context)
        return object : EngineSource {
            override val label = "updated ${meta.builtAt}"
            override val blobSize = meta.blobSize
            override fun selfieJson() = File(dir, "selfie.json").readText()
            override fun cosmeticJson() = File(dir, "cosmetic.json").readText()
            override fun openBlob(): InputStream = File(dir, "selfie.bin").inputStream().buffered(1 shl 16)
        }
    }

    /** Ids of the lists the user wants, applying their overrides to the catalog defaults. */
    fun enabledLists(context: Context, overrides: Map<String, Boolean>): List<FilterListInfo> =
        catalog(context).lists.filter { overrides[it.id] ?: it.enabled }

    /**
     * The newest usable state: an on-device compilation when it matches the
     * bundled engine version and is newer than the APK's own lists.
     */
    fun currentSource(context: Context): EngineSource? {
        if (!hasBundledEngine(context)) return null
        val catalog = catalog(context)
        val meta = activeMeta(context)
        val bundledMillis = runCatching { java.time.Instant.parse(catalog.builtAt).toEpochMilli() }.getOrDefault(0L)
        if (meta != null && meta.engineVersion == catalog.engineVersion &&
            File(activeDir(context), "selfie.bin").length() == meta.blobSize.toLong() &&
            meta.builtAtMillis >= bundledMillis
        ) {
            return filesSource(context, meta)
        }
        return bundledSource(context)
    }

    /** True when the bundled defaults no longer match what the user enabled. */
    fun bundledMatches(context: Context, overrides: Map<String, Boolean>): Boolean {
        val catalog = catalog(context)
        return catalog.lists.all { (overrides[it.id] ?: it.enabled) == it.enabled }
    }
}
