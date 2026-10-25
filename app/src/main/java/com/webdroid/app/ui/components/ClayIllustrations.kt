package com.webdroid.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import com.webdroid.app.ui.theme.Clay

private fun lighten(c: Color, f: Float) = lerp(c, Color.White, f)
private fun darken(c: Color, f: Float) = lerp(c, Color.Black, f)

/** Fills [path] with a soft, clay-like volume: lit top-left, shaded bottom-right. */
private fun DrawScope.clay(path: Path, base: Color, bounds: Rect, shine: Float = 0.35f) {
    // Contact shadow
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(Color.Black.copy(alpha = 0.16f), Color.Transparent),
            center = Offset(bounds.center.x, bounds.bottom),
            radius = bounds.width * 0.55f,
        ),
        topLeft = Offset(bounds.left - bounds.width * 0.05f, bounds.bottom - bounds.height * 0.08f),
        size = Size(bounds.width * 1.1f, bounds.height * 0.16f),
    )
    drawPath(
        path,
        brush = Brush.linearGradient(
            colors = listOf(lighten(base, 0.28f), base, darken(base, 0.18f)),
            start = bounds.topLeft,
            end = bounds.bottomRight,
        ),
    )
    // Specular highlight
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = shine), Color.Transparent),
            center = Offset(bounds.left + bounds.width * 0.38f, bounds.top + bounds.height * 0.28f),
            radius = bounds.width * 0.32f,
        ),
        topLeft = Offset(bounds.left + bounds.width * 0.1f, bounds.top + bounds.height * 0.05f),
        size = Size(bounds.width * 0.56f, bounds.height * 0.46f),
    )
}

private fun mountain(left: Float, right: Float, peakX: Float, peakY: Float, baseY: Float): Path = Path().apply {
    moveTo(left, baseY)
    cubicTo(left + (peakX - left) * 0.45f, baseY, peakX - (peakX - left) * 0.35f, peakY, peakX, peakY)
    cubicTo(peakX + (right - peakX) * 0.35f, peakY, right - (right - peakX) * 0.45f, baseY, right, baseY)
    close()
}

private fun DrawScope.tile(center: Offset, size: Float, color: Color, angle: Float) {
    rotate(angle, center) {
        val rect = Rect(center.x - size / 2, center.y - size / 2, center.x + size / 2, center.y + size / 2)
        val path = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(rect, CornerRadius(size * 0.3f)))
        }
        clay(path, color, rect, shine = 0.45f)
        // little "window" on the tile
        drawRoundRect(
            color = Clay.Canvas.copy(alpha = 0.9f),
            topLeft = Offset(rect.left + size * 0.22f, rect.top + size * 0.24f),
            size = Size(size * 0.56f, size * 0.14f),
            cornerRadius = CornerRadius(size * 0.07f),
        )
        drawRoundRect(
            color = Clay.Canvas.copy(alpha = 0.6f),
            topLeft = Offset(rect.left + size * 0.22f, rect.top + size * 0.48f),
            size = Size(size * 0.36f, size * 0.14f),
            cornerRadius = CornerRadius(size * 0.07f),
        )
    }
}

/**
 * Hand-made-looking clay landscape: rolling peach/ochre/lavender hills, a pink
 * sun and a couple of floating app tiles. Used for empty states and headers.
 */
@Composable
fun ClayHills(modifier: Modifier = Modifier, animated: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "clay")
    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bob",
    )
    val spin by transition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Reverse),
        label = "spin",
    )
    val phase = if (animated) bob else 0.5f
    val angle = if (animated) spin else 0f

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFFFFF3DD), Color(0xFFFCE7CF))))

        // Sun
        val sunR = h * 0.13f
        val sunC = Offset(w * 0.74f, h * 0.27f - phase * h * 0.03f)
        drawCircle(Brush.radialGradient(listOf(Clay.Pink.copy(alpha = 0.25f), Color.Transparent), sunC, sunR * 2.4f), sunR * 2.4f, sunC)
        val sunPath = Path().apply { addOval(Rect(sunC, sunR)) }
        clay(sunPath, Clay.Pink, Rect(sunC, sunR), shine = 0.5f)

        // Back hill (lavender)
        val back = mountain(-w * 0.15f, w * 0.75f, w * 0.28f, h * 0.30f, h * 0.86f)
        clay(back, Clay.Lavender, Rect(-w * 0.15f, h * 0.30f, w * 0.75f, h * 0.86f))
        // Right hill (peach)
        val right = mountain(w * 0.35f, w * 1.2f, w * 0.82f, h * 0.42f, h * 0.88f)
        clay(right, Clay.Peach, Rect(w * 0.35f, h * 0.42f, w * 1.2f, h * 0.88f))
        // Front hill (ochre) with a cream cap
        val front = mountain(w * 0.05f, w * 0.85f, w * 0.48f, h * 0.52f, h * 0.95f)
        clay(front, Clay.Ochre, Rect(w * 0.05f, h * 0.52f, w * 0.85f, h * 0.95f))
        val cap = Path().apply {
            moveTo(w * 0.40f, h * 0.585f)
            cubicTo(w * 0.44f, h * 0.53f, w * 0.52f, h * 0.53f, w * 0.56f, h * 0.585f)
            cubicTo(w * 0.53f, h * 0.61f, w * 0.50f, h * 0.575f, w * 0.48f, h * 0.60f)
            cubicTo(w * 0.46f, h * 0.575f, w * 0.43f, h * 0.61f, w * 0.40f, h * 0.585f)
            close()
        }
        clay(cap, Clay.Canvas, Rect(w * 0.40f, h * 0.53f, w * 0.56f, h * 0.61f), shine = 0.2f)

        // Mint ground
        val ground = Path().apply {
            moveTo(0f, h * 0.9f)
            cubicTo(w * 0.3f, h * 0.84f, w * 0.7f, h * 0.86f, w, h * 0.82f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        clay(ground, Clay.Mint, Rect(0f, h * 0.82f, w, h), shine = 0.25f)

        // Floating app tiles
        translate(top = -phase * h * 0.04f) {
            tile(Offset(w * 0.2f, h * 0.24f), h * 0.16f, Clay.Teal, angle - 8f)
        }
        translate(top = (phase - 0.5f) * h * 0.05f) {
            tile(Offset(w * 0.5f, h * 0.16f), h * 0.12f, Clay.Coral, -angle + 10f)
        }
    }
}

/** Small clay "brick" stack used as a decorative mark. */
@Composable
fun ClayMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        tile(Offset(s * 0.36f, s * 0.6f), s * 0.46f, Clay.Pink, -10f)
        tile(Offset(s * 0.64f, s * 0.42f), s * 0.42f, Clay.Lavender, 12f)
    }
}
