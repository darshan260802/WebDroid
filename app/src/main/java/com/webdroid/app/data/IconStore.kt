package com.webdroid.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.webdroid.app.R
import com.webdroid.app.ui.theme.CardColor
import com.webdroid.app.ui.theme.ClayPalette
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
     * Launcher-style icon: the site icon centred on its card colour, or the
     * site's initial when it has no usable icon.
     */
    fun renderTile(app: WebApp, sizePx: Int, adaptive: Boolean = false): Bitmap {
        val colors = CardColor.of(app.cardColor)
        // Launcher, Recents and notification icons keep the light Clay look
        // whatever theme WebDroid's own UI is in.
        val background = if (colors == CardColor.Cream) ClayPalette.Light.surfaceCard else colors.background
        val content = if (colors == CardColor.Cream) ClayPalette.Light.ink else colors.content
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.color = background.toArgb()
        if (adaptive) {
            canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), paint)
        } else {
            val r = sizePx * 0.28f
            canvas.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), r, r, paint)
        }
        val icon = load(app.iconFile)
        // Adaptive icons are masked to the central 66%, keep the art inside it.
        val inner = if (adaptive) sizePx * 0.42f else sizePx * 0.58f
        val left = (sizePx - inner) / 2f
        if (icon != null) {
            val plate = RectF(left - inner * 0.08f, left - inner * 0.08f, left + inner * 1.08f, left + inner * 1.08f)
            paint.color = 0xFFFFFAF0.toInt()
            canvas.drawRoundRect(plate, inner * 0.26f, inner * 0.26f, paint)
            canvas.drawBitmap(icon, Rect(0, 0, icon.width, icon.height), RectF(left, left, left + inner, left + inner), paint)
        } else {
            val typeface = ResourcesCompat.getFont(context, R.font.inter_semibold) ?: Typeface.DEFAULT_BOLD
            paint.typeface = typeface
            paint.color = content.toArgb()
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

