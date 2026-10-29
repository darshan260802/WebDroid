package com.webdroid.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.LinearGradient
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import com.webdroid.app.ui.theme.TileColor
import java.io.File
import java.io.FileOutputStream

/** Site icons, stored as PNG files in app storage. */
class IconStore(private val context: Context) {
    private val dir = File(context.filesDir, "icons").apply { mkdirs() }

    fun file(name: String): File = File(dir, name)

    fun load(name: String?): Bitmap? {
        if (name == null) return null
        val f = file(name)
        if (!f.exists()) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    fun save(appId: String, bitmap: Bitmap): String {
        val name = "$appId-${System.currentTimeMillis()}.png"
        FileOutputStream(file(name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Drop previous icons of the same app.
        dir.listFiles { f -> f.name.startsWith("$appId-") && f.name != name }?.forEach { it.delete() }
        return name
    }

    fun delete(appId: String) {
        dir.listFiles { f -> f.name.startsWith("$appId-") }?.forEach { it.delete() }
    }

    /**
     * Launcher-style icon, matching the Apps grid: the site's icon on a light
     * plate, or its initial on the app's colour.
     */
    fun renderTile(app: WebApp, sizePx: Int, adaptive: Boolean = false): Bitmap {
        val tile = TileColor.of(app.cardColor)
        val icon = load(app.iconFile)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val (top, bottom) = if (icon != null) 0xFFFFFFFF.toInt() to 0xFFF2F2F7.toInt() else tile.top.toArgb() to tile.bottom.toArgb()
        paint.shader = LinearGradient(0f, 0f, 0f, sizePx.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        if (adaptive) {
            canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), paint)
        } else {
            val r = sizePx * 0.225f
            canvas.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), r, r, paint)
        }
        paint.shader = null
        // Adaptive icons are masked to the central 66%, keep the art inside it.
        val inner = if (adaptive) sizePx * 0.42f else sizePx * 0.62f
        val left = (sizePx - inner) / 2f
        if (icon != null) {
            val r = inner * 0.18f
            canvas.save()
            canvas.clipPath(android.graphics.Path().apply { addRoundRect(RectF(left, left, left + inner, left + inner), r, r, android.graphics.Path.Direction.CW) })
            canvas.drawBitmap(icon, Rect(0, 0, icon.width, icon.height), RectF(left, left, left + inner, left + inner), paint)
            canvas.restore()
        } else {
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.color = tile.glyph.toArgb()
            paint.textSize = inner * 0.9f
            paint.textAlign = Paint.Align.CENTER
            val letter = app.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "W"
            val bounds = Rect()
            paint.getTextBounds(letter, 0, letter.length, bounds)
            canvas.drawText(letter, sizePx / 2f, sizePx / 2f + bounds.height() / 2f, paint)
        }
        return bitmap
    }

    companion object {
        @Volatile private var instance: IconStore? = null

        fun get(context: Context): IconStore =
            instance ?: synchronized(this) {
                instance ?: IconStore(context.applicationContext).also { instance = it }
            }
    }
}

