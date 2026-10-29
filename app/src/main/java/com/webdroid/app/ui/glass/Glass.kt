package com.webdroid.app.ui.glass

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.webdroid.app.ui.theme.AppColors
import com.webdroid.app.ui.theme.LocalReduceTransparency
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * WebDroid's Liquid Glass.
 *
 * Glass belongs only to the floating functional layer (tab bar, top bars,
 * floating buttons, menus, sheets' toolbars): it blurs and brightens the
 * content beneath it, bends that content at its rounded edges like a lens,
 * and catches light on its rim.
 *
 * - Android 13+: real refraction with an AGSL shader over a blurred backdrop.
 * - Android 12: frosted blur with a saturation boost.
 * - Older Android, or Reduce Transparency: an opaque surface.
 *
 * The backdrop is either live Compose content ([glassSource]) or a still
 * snapshot of the window ([GlassBackdrop.snapshot], used over web pages,
 * which can't be recorded).
 */
enum class GlassStyle {
    /** Most bars and controls. */
    Regular,
    /** Over photos and video: more see-through. */
    Clear,
    /** Text-heavy surfaces: menus, sheets, alerts. */
    Thick,
    /** Scroll edge: content blurs and fades where it meets a top bar. */
    Edge,
}

@Stable
class GlassPanelState internal constructor() {
    /** Bounds in window coordinates. */
    var bounds by mutableStateOf(Rect.Zero)
    var cornerRadiusPx by mutableFloatStateOf(0f)
    var style by mutableStateOf(GlassStyle.Regular)
    /** Visual scale around the centre ("liquid" press response). */
    var scale by mutableFloatStateOf(1f)
    var alpha by mutableFloatStateOf(1f)
    var shadow by mutableStateOf(true)
    /** Where the panel is in the layout; resolved against the source at draw time. */
    internal var coordinates: LayoutCoordinates? = null
    internal var layer: GraphicsLayer? = null
}

@Stable
class GlassBackdrop internal constructor() {
    internal val panels = mutableStateListOf<GlassPanelState>()
    internal var sourceOrigin by mutableStateOf(Offset.Zero)
    internal var hasSource by mutableStateOf(false)
    internal var sourceCoordinates: LayoutCoordinates? = null

    /**
     * What moves the live content under the glass (scroll positions, screen
     * transitions). Every provider is read by the source on each draw, so the
     * glass is redrawn in the same frame the content moves.
     */
    internal val versionProviders = mutableStateMapOf<Any, () -> Any?>()

    fun track(key: Any, provider: () -> Any?) {
        versionProviders[key] = provider
    }

    fun untrack(key: Any) {
        versionProviders.remove(key)
    }

    /** A still image of the window to use as backdrop (when there's no live source). */
    var snapshot by mutableStateOf<ImageBitmap?>(null)
    /** Snapshot pixels per window pixel. */
    var snapshotScale by mutableFloatStateOf(1f)
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop = remember { GlassBackdrop() }

/** Whether glass panels show the backdrop (vs an opaque fallback). */
internal fun glassLive(backdrop: GlassBackdrop?, reduceTransparency: Boolean): Boolean =
    backdrop != null && !reduceTransparency && Build.VERSION.SDK_INT >= 31 &&
        (backdrop.hasSource || backdrop.snapshot != null)

/**
 * Marks the content glass floats over. Every frame the content changes it is
 * recorded once and every registered glass panel is drawn from it, in the
 * same frame (no lag). [invalidate] should read the state that moves content
 * under the glass, e.g. scroll positions.
 */
fun Modifier.glassSource(backdrop: GlassBackdrop, invalidate: () -> Any? = { null }): Modifier = composed {
    val layer = rememberGraphicsLayer()
    val reduce = LocalReduceTransparency.current
    DisposableEffect(backdrop) {
        backdrop.hasSource = true
        onDispose { backdrop.hasSource = false }
    }
    this
        .onGloballyPositioned {
            backdrop.sourceCoordinates = it
            backdrop.sourceOrigin = it.positionInWindow()
        }
        .drawWithContent {
            invalidate()
            for (provider in backdrop.versionProviders.values) provider()
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            if (!reduce && Build.VERSION.SDK_INT >= 31) {
                val origin = backdrop.sourceOrigin
                val source = backdrop.sourceCoordinates
                for (panel in backdrop.panels) {
                    // Positions relative to the source, so a transform on a common
                    // ancestor (the app receding behind a sheet) keeps them aligned.
                    val coordinates = panel.coordinates
                    val relative = if (source != null && coordinates != null && source.isAttached && coordinates.isAttached) {
                        Rect(source.localPositionOf(coordinates, Offset.Zero), coordinates.size.toSize())
                    } else null
                    drawGlassPanel(panel, origin, layer, null, 1f, relative = relative)
                }
            }
        }
}

/**
 * A glass surface. Registers itself with [backdrop] (which draws the
 * refraction beneath it) and draws its tint, rim and [content].
 */
@Composable
fun Glass(
    backdrop: GlassBackdrop?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    style: GlassStyle = GlassStyle.Regular,
    tint: Color? = null,
    shadow: Boolean = true,
    scale: Float = 1f,
    alpha: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    val panel = remember { GlassPanelState() }
    val layer = rememberGraphicsLayer()
    panel.layer = layer
    SideEffect {
        panel.style = style
        panel.shadow = shadow
        panel.scale = scale
        panel.alpha = alpha
    }
    DisposableEffect(backdrop) {
        backdrop?.panels?.add(panel)
        onDispose { backdrop?.panels?.remove(panel) }
    }
    val reduce = LocalReduceTransparency.current
    Box(
        modifier
            .onGloballyPositioned {
                panel.coordinates = it
                val p = it.positionInWindow()
                panel.bounds = Rect(p, it.size.toSize())
            }
            // The refraction (drawn by the backdrop) scales by panel.scale; the surface follows.
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .drawBehind {
                val live = glassLive(backdrop, reduce)
                val r = min(cornerRadius.toPx(), min(size.width, size.height) / 2f)
                panel.cornerRadiusPx = r
                // A still snapshot is drawn here; a live source draws its own panels.
                if (live && backdrop?.hasSource == false && backdrop.snapshot != null) {
                    drawGlassPanel(panel, panel.bounds.topLeft, null, backdrop.snapshot, backdrop.snapshotScale, applyScale = false)
                }
                drawGlassSurface(live, r, style, tint, shadow && !live)
            },
        content = content,
    )
}

/** Tint, rim light and (for opaque fallbacks) a soft shadow, over the refraction. */
private fun DrawScope.drawGlassSurface(live: Boolean, radius: Float, style: GlassStyle, tint: Color?, shadow: Boolean) {
    val corner = CornerRadius(radius)
    if (style == GlassStyle.Edge) {
        val base = if (live) AppColors.GlassTint else AppColors.GlassSolid
        drawRect(Brush.verticalGradient(0f to base, 0.7f to base.copy(alpha = base.alpha * 0.85f), 1f to base.copy(alpha = 0f)))
        return
    }
    if (shadow) drawSoftShadow(Rect(Offset.Zero, size), radius, AppColors.GlassShadow)
    val fill = tint ?: when {
        !live -> AppColors.GlassSolid
        style == GlassStyle.Thick -> AppColors.GlassTintStrong
        style == GlassStyle.Clear -> Color.Black.copy(alpha = 0.35f)
        else -> AppColors.GlassTint
    }
    drawRoundRect(fill, cornerRadius = corner)
    if (live && GlassEffects.lens) return // the lens shader lights the rim itself
    // Light catching the edge: bright on top, fading down.
    val rim = AppColors.GlassRim
    drawRoundRect(
        brush = Brush.verticalGradient(0f to rim, 0.5f to rim.copy(alpha = rim.alpha * 0.25f), 1f to rim.copy(alpha = rim.alpha * 0.55f)),
        cornerRadius = corner,
        style = Stroke(width = 1.dp.toPx()),
    )
}

private fun DrawScope.drawSoftShadow(rect: Rect, radius: Float, color: Color) {
    if (Build.VERSION.SDK_INT < 28) return
    drawIntoCanvas { canvas ->
        val paint = Paint().asFrameworkPaint().apply {
            isAntiAlias = true
            this.color = android.graphics.Color.TRANSPARENT
            setShadowLayer(18.dp.toPx(), 0f, 6.dp.toPx(), color.toArgb())
        }
        canvas.nativeCanvas.drawRoundRect(rect.left, rect.top, rect.right, rect.bottom, radius, radius, paint)
    }
}

/**
 * Draws one panel's refracted backdrop. [origin] is the window position of
 * this DrawScope's (0,0). The backdrop is either [content] (a layer in this
 * scope's coordinates) or [snapshot] (an image of the whole window, scaled
 * by [snapshotScale]).
 */
private fun DrawScope.drawGlassPanel(
    panel: GlassPanelState,
    origin: Offset,
    content: GraphicsLayer?,
    snapshot: ImageBitmap?,
    snapshotScale: Float,
    applyScale: Boolean = true,
    relative: Rect? = null,
) {
    val layer = panel.layer ?: return
    if (panel.alpha <= 0.01f) return
    val bounds = panel.bounds // read even when [relative] is given: it redraws the glass when the panel moves
    if (bounds.width < 1f || bounds.height < 1f) return
    // Panel rect in this scope, scaled around its centre.
    val local = (relative ?: bounds.translate(-origin.x, -origin.y)).let { r ->
        if (!applyScale || panel.scale == 1f) r else Rect(
            r.center.x - r.width * panel.scale / 2f, r.center.y - r.height * panel.scale / 2f,
            r.center.x + r.width * panel.scale / 2f, r.center.y + r.height * panel.scale / 2f,
        )
    }
    val style = panel.style
    val margin = if (style == GlassStyle.Edge) 0f else 24.dp.toPx()
    val w = (local.width + 2 * margin).roundToInt().coerceAtLeast(1)
    val h = (local.height + 2 * margin).roundToInt().coerceAtLeast(1)
    val left = local.left - margin
    val top = local.top - margin
    val radius = min(panel.cornerRadiusPx * (if (applyScale) panel.scale else 1f), min(local.width, local.height) / 2f)

    layer.record(size = IntSize(w, h)) {
        if (snapshot != null) {
            // The snapshot is in window pixels × snapshotScale; this panel sits at bounds (window).
            val srcLeft = ((bounds.left - margin) * snapshotScale).roundToInt()
            val srcTop = ((bounds.top - margin) * snapshotScale).roundToInt()
            val srcW = (w * snapshotScale).roundToInt().coerceAtLeast(1)
            val srcH = (h * snapshotScale).roundToInt().coerceAtLeast(1)
            val clampedLeft = srcLeft.coerceIn(0, max(0, snapshot.width - 1))
            val clampedTop = srcTop.coerceIn(0, max(0, snapshot.height - 1))
            val clampedW = min(srcW, snapshot.width - clampedLeft).coerceAtLeast(1)
            val clampedH = min(srcH, snapshot.height - clampedTop).coerceAtLeast(1)
            drawImage(
                snapshot,
                srcOffset = IntOffset(clampedLeft, clampedTop),
                srcSize = IntSize(clampedW, clampedH),
                dstOffset = IntOffset(((clampedLeft - srcLeft) / snapshotScale).roundToInt(), ((clampedTop - srcTop) / snapshotScale).roundToInt()),
                dstSize = IntSize((clampedW / snapshotScale).roundToInt(), (clampedH / snapshotScale).roundToInt()),
            )
        } else if (content != null) {
            translate(-left, -top) { drawLayer(content) }
        }
    }
    layer.renderEffect = GlassEffects.effect(w, h, margin, local.size, radius, style, AppColors.isDark, density)
    layer.alpha = panel.alpha

    if (panel.shadow && style != GlassStyle.Edge) {
        drawSoftShadow(local, radius, AppColors.GlassShadow.copy(alpha = AppColors.GlassShadow.alpha * panel.alpha))
    }
    translate(left, top) {
        when {
            style == GlassStyle.Edge -> {
                // Blurred content fading out towards the bottom of the bar.
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(Rect(0f, 0f, w.toFloat(), h.toFloat()), Paint())
                    drawLayer(layer)
                    drawRect(
                        Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                    canvas.restore()
                }
            }
            GlassEffects.lens -> drawLayer(layer) // the shader cuts the shape out itself
            else -> {
                val path = Path().apply {
                    addRoundRect(RoundRect(Rect(Offset(margin, margin), local.size), CornerRadius(radius)))
                }
                clipPath(path) { drawLayer(layer) }
            }
        }
    }
}

/** Builds (and caches) the RenderEffect for a panel. */
internal object GlassEffects {
    val lens: Boolean get() = Build.VERSION.SDK_INT >= 33

    private data class Key(
        val w: Int, val h: Int, val margin: Int, val gw: Int, val gh: Int, val radius: Int,
        val style: GlassStyle, val dark: Boolean,
    )

    private val cache = object : LinkedHashMap<Key, androidx.compose.ui.graphics.RenderEffect>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, androidx.compose.ui.graphics.RenderEffect>?) = size > 48
    }

    fun effect(
        w: Int, h: Int, margin: Float, glass: Size, radius: Float,
        style: GlassStyle, dark: Boolean, density: Float,
    ): androidx.compose.ui.graphics.RenderEffect? {
        if (Build.VERSION.SDK_INT < 31) return null
        val key = Key(w, h, margin.roundToInt(), glass.width.roundToInt(), glass.height.roundToInt(), radius.roundToInt(), style, dark)
        synchronized(cache) { cache[key]?.let { return it } }
        val built = if (lens && style != GlassStyle.Edge) {
            Lens.effect(w, h, margin, glass, radius, style, dark, density)
        } else {
            frosted(style, density)
        }
        synchronized(cache) { cache[key] = built }
        return built
    }

    /** Frosted blur (Android 12): the blur does all the work. */
    private fun blurRadius(style: GlassStyle, density: Float): Float = density * when (style) {
        GlassStyle.Regular -> 14f
        GlassStyle.Clear -> 5f
        GlassStyle.Thick -> 24f
        GlassStyle.Edge -> 10f
    }

    /**
     * Liquid Glass (Android 13+) blurs only lightly: the lens is what reads
     * as glass, and content beneath stays recognisable through it. Menus and
     * alerts blur more so their text stays legible.
     */
    private fun lensBlur(style: GlassStyle, density: Float): Float = density * when (style) {
        GlassStyle.Regular -> 4f
        GlassStyle.Clear -> 1.5f
        GlassStyle.Thick -> 10f
        GlassStyle.Edge -> 10f
    }

    @RequiresApi(31)
    private fun frosted(style: GlassStyle, density: Float): androidx.compose.ui.graphics.RenderEffect {
        val r = blurRadius(style, density)
        val blur = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
        val saturate = ColorMatrix().apply { setSaturation(if (style == GlassStyle.Clear) 1.25f else 1.5f) }
        return RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(saturate), blur).asComposeRenderEffect()
    }

    @RequiresApi(33)
    private object Lens {
        private val shader by lazy { RuntimeShader(LENS_SHADER) }

        fun effect(
            w: Int, h: Int, margin: Float, glass: Size, radius: Float,
            style: GlassStyle, dark: Boolean, density: Float,
        ): androidx.compose.ui.graphics.RenderEffect = synchronized(this) {
            val short = min(glass.width, glass.height)
            // The curved rim: wide enough that a button is a drop of glass and
            // a bar has a thick, rounded edge; strongest on bars and buttons.
            val (bandDp, bendDp, zoom) = when (style) {
                GlassStyle.Regular -> Triple(24f, 24f, 0.06f)
                GlassStyle.Clear -> Triple(20f, 20f, 0.05f)
                GlassStyle.Thick -> Triple(16f, 14f, 0.025f)
                GlassStyle.Edge -> Triple(0f, 0f, 0f)
            }
            val band = min(density * bandDp, short / 2f)
            val bend = min(density * bendDp, short * 0.45f)
            shader.setFloatUniform("layerSize", w.toFloat(), h.toFloat())
            shader.setFloatUniform("origin", margin, margin)
            shader.setFloatUniform("size", glass.width, glass.height)
            shader.setFloatUniform("radius", radius)
            shader.setFloatUniform("band", band)
            shader.setFloatUniform("bend", bend)
            shader.setFloatUniform("zoom", zoom)
            shader.setFloatUniform("dispersion", if (style == GlassStyle.Thick) 0.12f else 0.28f)
            shader.setFloatUniform("saturation", if (style == GlassStyle.Clear) 1.25f else 1.4f)
            shader.setFloatUniform("rim", if (dark) 0.8f else 0.6f)
            shader.setFloatUniform("rimWidth", 1.5f * density)
            shader.setFloatUniform("glow", 0.45f)
            shader.setFloatUniform("shade", if (dark) 0.3f else 0.14f)
            shader.setFloatUniform("light", -0.6f, -0.8f)
            val lensEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")
            val r = lensBlur(style, density)
            val blur = RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)
            // Blur lightly first, then refract.
            RenderEffect.createChainEffect(lensEffect, blur).asComposeRenderEffect()
        }
    }

    /**
     * A slab of glass with a rounded, curved rim. Through the flat middle the
     * content is slightly magnified; across the rim it bends steeply, pulling
     * in what lies just outside the shape, with a little chromatic dispersion.
     * Light from the top left catches the rim as a thin highlight (and its
     * reflection on the opposite side) and glows softly inside the lit edge,
     * while the far edge falls into shade, giving the glass its thickness.
     */
    private const val LENS_SHADER = """
uniform shader content;
uniform float2 layerSize;
uniform float2 origin;
uniform float2 size;
uniform float radius;
uniform float band;
uniform float bend;
uniform float zoom;
uniform float dispersion;
uniform float saturation;
uniform float rim;
uniform float rimWidth;
uniform float glow;
uniform float shade;
uniform float2 light;

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
}

half4 sampleAt(float2 c) {
    return content.eval(clamp(c, float2(0.5), layerSize - float2(0.5)));
}

half4 main(float2 coord) {
    float2 hs = size * 0.5;
    float2 center = origin + hs;
    float2 p = coord - center;
    float r = min(radius, min(hs.x, hs.y));
    float d = sdRoundRect(p, hs, r);
    if (d > 1.0) {
        return half4(0.0);
    }
    float e = 0.75;
    float2 n = float2(
        sdRoundRect(p + float2(e, 0.0), hs, r) - sdRoundRect(p - float2(e, 0.0), hs, r),
        sdRoundRect(p + float2(0.0, e), hs, r) - sdRoundRect(p - float2(0.0, e), hs, r));
    float nl = length(n);
    n = nl > 0.0001 ? n / nl : float2(0.0);

    // 0 in the flat middle, 1 at the very edge; the rim's height falls off
    // like a quarter circle, so the bending grows steeply towards the edge.
    float t = 1.0 - clamp(-d / max(band, 1.0), 0.0, 1.0);
    float profile = 1.0 - sqrt(max(1.0 - t * t, 0.0));
    float2 base = center + p * (1.0 - zoom);
    float2 offset = n * bend * profile;

    half4 col = sampleAt(base + offset);
    if (dispersion > 0.0) {
        float k = dispersion * profile;
        col.r = sampleAt(base + offset * (1.0 + k)).r;
        col.b = sampleAt(base + offset * (1.0 - k)).b;
    }

    half lum = dot(col.rgb, half3(0.2126, 0.7152, 0.0722));
    col.rgb = clamp(mix(half3(lum), col.rgb, half(saturation)), half3(0.0), half3(1.0));

    float2 l = normalize(light);
    float facing = dot(n, l);
    float edge = d > 0.5 ? 0.0 : 1.0 - clamp(-d / max(rimWidth, 0.5), 0.0, 1.0);
    float spec = edge * (0.2 + 0.8 * facing * facing);
    float t2 = t * t;
    float lit = t2 * t2 * max(facing, 0.0);
    float dim = t2 * t * max(-facing, 0.0);
    col.rgb = col.rgb * half(1.0 - shade * dim) + half3(rim * (spec + glow * lit)) * col.a;
    col.rgb = clamp(col.rgb, half3(0.0), half3(col.a));

    float aa = clamp(0.5 - d, 0.0, 1.0);
    return col * half(aa);
}
"""
}
