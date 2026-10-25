package com.webdroid.app.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.webdroid.app.data.AppSettings
import com.webdroid.app.data.ThemeMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

object ClayShapes {
    val Xs = RoundedCornerShape(6.dp)
    val Sm = RoundedCornerShape(8.dp)
    val Md = RoundedCornerShape(12.dp)
    val Lg = RoundedCornerShape(16.dp)
    val Xl = RoundedCornerShape(24.dp)
    val Pill = RoundedCornerShape(percent = 50)
}

object ClaySpacing {
    val Xxs = 4.dp
    val Xs = 8.dp
    val Sm = 12.dp
    val Md = 16.dp
    val Lg = 24.dp
    val Xl = 32.dp
    val Xxl = 48.dp
}

/** Shared spring specs so every surface moves with the same physics. */
object ClayMotion {
    fun <T> bouncy() = spring<T>(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
    fun <T> snappy() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
    fun <T> gentle() = spring<T>(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow)
    val offsetBouncy = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1))
}

private fun colorSchemeFor(p: ClayPalette): ColorScheme {
    val base = if (p.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.primary,
        onPrimary = p.onPrimary,
        primaryContainer = p.surfaceCard,
        onPrimaryContainer = p.ink,
        secondary = p.teal,
        onSecondary = Clay.OnDark,
        tertiary = Clay.Pink,
        onTertiary = Clay.White,
        background = p.canvas,
        onBackground = p.ink,
        surface = p.canvas,
        onSurface = p.ink,
        surfaceVariant = p.surfaceCard,
        onSurfaceVariant = p.muted,
        surfaceContainer = p.surfaceSoft,
        surfaceContainerHigh = p.surfaceCard,
        surfaceContainerHighest = p.surfaceStrong,
        surfaceContainerLow = p.surfaceSoft,
        surfaceContainerLowest = p.canvas,
        inverseSurface = p.ink,
        inverseOnSurface = p.canvas,
        outline = p.hairline,
        outlineVariant = p.hairlineSoft,
        error = if (p.isDark) Color(0xFFFF6B6B) else Color(0xFFEF4444),
        onError = Clay.White,
        scrim = Clay.Black,
    )
}

private val LightScheme = colorSchemeFor(ClayPalette.Light)
private val DarkScheme = colorSchemeFor(ClayPalette.Dark)

private val ClayTypography = Typography(
    displayLarge = ClayType.DisplayLg,
    displayMedium = ClayType.DisplayMd,
    displaySmall = ClayType.DisplaySm,
    headlineLarge = ClayType.DisplaySm,
    headlineMedium = ClayType.TitleLg,
    headlineSmall = ClayType.TitleLg,
    titleLarge = ClayType.TitleLg,
    titleMedium = ClayType.TitleMd,
    titleSmall = ClayType.TitleSm,
    bodyLarge = ClayType.BodyMd,
    bodyMedium = ClayType.BodySm,
    bodySmall = ClayType.Caption,
    labelLarge = ClayType.Button,
    labelMedium = ClayType.Caption,
    labelSmall = ClayType.CaptionUpper,
)

private val ClayMaterialShapes = Shapes(
    extraSmall = ClayShapes.Xs,
    small = ClayShapes.Sm,
    medium = ClayShapes.Md,
    large = ClayShapes.Lg,
    extraLarge = ClayShapes.Xl,
)

object ClayTheme {
    fun isDark(context: Context, mode: ThemeMode = AppSettings.get(context).state.value.themeMode): Boolean = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** Sets the palette before the first frame so a screen never flashes the wrong theme. */
    fun prime(context: Context) {
        Clay.palette = if (isDark(context)) ClayPalette.Dark else ClayPalette.Light
    }
}

/**
 * WebDroid's Clay theme in light or dark, following the user's choice in
 * Settings (System / Light / Dark). Only WebDroid's own UI changes; web pages
 * keep following the system setting.
 */
@Composable
fun WebDroidTheme(content: @Composable () -> Unit) {
    val settings = AppSettings.get(LocalContext.current)
    val modeFlow = remember(settings) { settings.state.map { it.themeMode }.distinctUntilChanged() }
    val mode by modeFlow.collectAsState(initial = settings.state.value.themeMode)
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val palette = if (dark) ClayPalette.Dark else ClayPalette.Light
    SideEffect { Clay.palette = palette }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = ClayTypography,
        shapes = ClayMaterialShapes,
        content = content,
    )
}
