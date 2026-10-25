package com.webdroid.app.player

import android.content.Context
import androidx.core.content.edit

/** Remembers where each video was left (bounded, least recently used out). */
class VideoPositionStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("video_positions", Context.MODE_PRIVATE)

    fun get(key: String): Long? {
        val value = prefs.getString(key, null) ?: return null
        return value.substringBefore('|').toLongOrNull()
    }

    fun save(key: String, positionMs: Long, durationMs: Long) {
        // Finished (or barely started) videos start from the beginning next time.
        if (durationMs > 0 && (positionMs < 5_000 || durationMs - positionMs < 15_000)) {
            prefs.edit { remove(key) }
            return
        }
        prefs.edit { putString(key, "$positionMs|$durationMs|${System.currentTimeMillis()}") }
        if (prefs.all.size > MAX_ENTRIES) trim()
    }

    private fun trim() {
        val entries = prefs.all.mapNotNull { (k, v) ->
            (v as? String)?.split('|')?.getOrNull(2)?.toLongOrNull()?.let { k to it }
        }.sortedBy { it.second }
        prefs.edit {
            entries.take(entries.size - MAX_ENTRIES + 50).forEach { remove(it.first) }
        }
    }

    companion object {
        private const val MAX_ENTRIES = 400
        @Volatile private var instance: VideoPositionStore? = null

        fun get(context: Context): VideoPositionStore =
            instance ?: synchronized(this) {
                instance ?: VideoPositionStore(context.applicationContext).also { instance = it }
            }
    }
}
