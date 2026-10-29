package com.webdroid.app.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.webdroid.app.data.AppSettings
import com.webdroid.app.data.ThemeMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.PI
import kotlin.math.abs

object AppShapes {
    val Small = RoundedCornerShape(8.dp)
    val Medium = RoundedCornerShape(12.dp)
    /** Grouped list sections and cards. */
    val Section = RoundedCornerShape(22.dp)
    val Sheet = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    val Alert = RoundedCornerShape(28.dp)
    val Capsule = RoundedCornerShape(percent = 50)
}

object Spacing {
    /** Screen edge margin, as on iPhone. */
    val Edge = 16.dp
    val SectionGap = 32.dp
    /** Minimum touch target (HIG: 44 pt; Material: 48 dp). */
    val Touch = 48.dp
}

/**
 * Motion vocabulary from Apple's fluid-interface work: springs described by
 * response (seconds to reach the target) and damping ratio. Critically damped
 * by default; bounce only after a gesture that carried momentum.
 */
object Motion {
    /** True when the system's animator scale is 0 ("Remove animations"). */
    @Volatile var reduced: Boolean = false

    private fun stiffness(response: Float): Float {
        val omega = 2f * PI.toFloat() / response
        return omega * omega
    }

    fun <T> spring(response: Float = 0.35f, damping: Float = 1f, visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        if (reduced) tween(durationMillis = 140)
        else androidx.compose.animation.core.spring(damping, stiffness(response), visibilityThreshold)

    /** Default for UI that moves on its own: no overshoot. */
    fun <T> smooth(visibilityThreshold: T? = null) = spring(0.35f, 1f, visibilityThreshold)
    /** Quick state changes (toggles, highlights). */
    fun <T> snappy(visibilityThreshold: T? = null) = spring(0.22f, 1f, visibilityThreshold)
    /** After a flick or a release with velocity. */
    fun <T> momentum(visibilityThreshold: T? = null) = spring(0.4f, 0.8f, visibilityThreshold)
    /** Sheets and drawers released by a gesture. */
    fun <T> sheet(visibilityThreshold: T? = null) = spring(0.3f, 0.8f, visibilityThreshold)

    /** Where a flick would come to rest (Apple's deceleration projection). */
    fun project(velocity: Float, decelerationRate: Float = 0.998f): Float =
        velocity / 1000f * decelerationRate / (1f - decelerationRate)

    /** Progressive resistance past a boundary. */
    fun rubberband(overshoot: Float, dimension: Float, constant: Float = 0.55f): Float {
        if (dimension <= 0f) return 0f
        return (overshoot * dimension * constant) / (dimension + constant * abs(overshoot))
    }

    fun readReduced(context: Context): Boolean = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
}

/** In-app accessibility switches (Android has no system Reduce Transparency). */
val LocalReduceTransparency = staticCompositionLocalOf { false }

object AppTheme {
    fun isDark(context: Context, mode: ThemeMode = AppSettings.get(context).state.value.themeMode): Boolean = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** Sets the palette before the first frame so a screen never flashes the wrong appearance. */
    fun prime(context: Context) {
        AppColors.palette = if (isDark(context)) ColorPalette.Dark else ColorPalette.Light
        Motion.reduced = Motion.readReduced(context)
    }
}

private fun schemeFor(p: ColorPalette): ColorScheme {
    val base = if (p.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.accentFill,
        onPrimary = AppColors.OnAccent,
        secondary = p.accent,
        background = p.grouped,
        onBackground = p.label,
        surface = p.elevated,
        onSurface = p.label,
        surfaceVariant = p.fill,
        onSurfaceVariant = p.secondaryLabel,
        surfaceContainer = p.cell,
        surfaceContainerHigh = p.elevated,
        surfaceContainerHighest = p.elevated,
        surfaceContainerLow = p.cell,
        surfaceContainerLowest = p.background,
        outline = p.separator,
        outlineVariant = p.separator,
        error = p.destructive,
        scrim = p.scrim,
    )
}

private val LightScheme = schemeFor(ColorPalette.Light)
private val DarkScheme = schemeFor(ColorPalette.Dark)

private val MaterialType = Typography(
    displayLarge = AppType.LargeTitle,
    displayMedium = AppType.Title1,
    displaySmall = AppType.Title2,
    headlineLarge = AppType.Title1,
    headlineMedium = AppType.Title2,
    headlineSmall = AppType.Title3,
    titleLarge = AppType.Title3,
    titleMedium = AppType.Headline,
    titleSmall = AppType.Subhead,
    bodyLarge = AppType.Body,
    bodyMedium = AppType.Subhead,
    bodySmall = AppType.Footnote,
    labelLarge = AppType.Headline,
    labelMedium = AppType.Footnote,
    labelSmall = AppType.Caption2,
)

/**
 * WebDroid's appearance: Apple-style system colours in light or dark,
 * following Settings (System / Light / Dark). Web pages keep following the
 * system setting.
 */
@Composable
fun WebDroidTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings = AppSettings.get(context)
    val flow = remember(settings) { settings.state.map { it.themeMode to it.reduceTransparency }.distinctUntilChanged() }
    val prefs by flow.collectAsState(initial = settings.state.value.themeMode to settings.state.value.reduceTransparency)
    val dark = when (prefs.first) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val palette = if (dark) ColorPalette.Dark else ColorPalette.Light
    SideEffect {
        AppColors.palette = palette
        Motion.reduced = Motion.readReduced(context)
    }
    val selection = remember(palette) {
        TextSelectionColors(handleColor = palette.accent, backgroundColor = palette.accent.copy(alpha = 0.3f))
    }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = MaterialType) {
        CompositionLocalProvider(
            LocalTextSelectionColors provides selection,
            LocalReduceTransparency provides prefs.second,
            content = content,
        )
    }
}
