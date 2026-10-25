package com.webdroid.app.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
private data class StoredApps(val version: Int = 1, val apps: List<WebApp> = emptyList())

/**
 * The user's web apps, persisted as a small JSON document. Everything runs in
 * one process, so the in-memory [apps] flow is the source of truth and writes
 * are debounced to disk.
 */
class WebAppRepository private constructor(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "webapps.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val _apps = MutableStateFlow(load())
    val apps: StateFlow<List<WebApp>> = _apps.asStateFlow()

    init {
        @OptIn(FlowPreview::class)
        _apps.drop(1).debounce(250).onEach { save(it) }.launchIn(scope)
    }

    private fun load(): List<WebApp> = runCatching {
        if (!file.baseFile.exists()) return emptyList()
        json.decodeFromString(StoredApps.serializer(), file.readFully().decodeToString()).apps
    }.getOrDefault(emptyList()).sortedBy { it.order }

    private fun save(apps: List<WebApp>) {
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(StoredApps.serializer(), StoredApps(apps = apps)).encodeToByteArray())
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
        }
    }

    /** Persist immediately (e.g. before the process may die). */
    fun flush() = save(_apps.value)

    fun get(id: String): WebApp? = _apps.value.firstOrNull { it.id == id }

    fun nextCardColor(): Int {
        val last = _apps.value.maxByOrNull { it.order }?.cardColor ?: -1
        return (last + 1).mod(6)
    }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    fun upsert(app: WebApp) {
        _apps.update { list ->
            val index = list.indexOfFirst { it.id == app.id }
            if (index == -1) {
                list + app.copy(order = (list.maxOfOrNull { it.order } ?: -1) + 1)
            } else {
                list.toMutableList().also { it[index] = app }
            }
        }
    }

    fun update(id: String, transform: (WebApp) -> WebApp) {
        _apps.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    fun delete(id: String) {
        _apps.update { list -> list.filterNot { it.id == id } }
    }

    fun markOpened(id: String) = update(id) { it.copy(lastOpenedAt = System.currentTimeMillis()) }

    fun move(fromIndex: Int, toIndex: Int) {
        _apps.update { list ->
            if (fromIndex !in list.indices || toIndex !in list.indices) return@update list
            val mutable = list.toMutableList()
            mutable.add(toIndex, mutable.removeAt(fromIndex))
            mutable.mapIndexed { i, app -> app.copy(order = i) }
        }
    }

    companion object {
        @Volatile private var instance: WebAppRepository? = null

        fun get(context: Context): WebAppRepository =
            instance ?: synchronized(this) {
                instance ?: WebAppRepository(context.applicationContext).also { instance = it }
            }
    }
}
