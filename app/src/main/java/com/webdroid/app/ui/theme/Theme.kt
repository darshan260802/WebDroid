package com.webdroid.app.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

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

private val ClayColorScheme = lightColorScheme(
    primary = Clay.Primary,
    onPrimary = Clay.OnPrimary,
    primaryContainer = Clay.SurfaceCard,
    onPrimaryContainer = Clay.Ink,
    secondary = Clay.Teal,
    onSecondary = Clay.OnDark,
    tertiary = Clay.Pink,
    onTertiary = Clay.OnPrimary,
    background = Clay.Canvas,
    onBackground = Clay.Ink,
    surface = Clay.Canvas,
    onSurface = Clay.Ink,
    surfaceVariant = Clay.SurfaceCard,
    onSurfaceVariant = Clay.Muted,
    surfaceContainer = Clay.SurfaceSoft,
    surfaceContainerHigh = Clay.SurfaceCard,
    surfaceContainerHighest = Clay.SurfaceStrong,
    surfaceContainerLow = Clay.SurfaceSoft,
    surfaceContainerLowest = Clay.Canvas,
    outline = Clay.Hairline,
    outlineVariant = Clay.HairlineSoft,
    error = Clay.Error,
    onError = Clay.OnPrimary,
    scrim = Clay.Ink,
)

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

@Composable
fun WebDroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ClayColorScheme,
        typography = ClayTypography,
        shapes = ClayMaterialShapes,
        content = content,
    )
}
