package com.webdroid.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** The neutral Clay tokens that change between light and dark mode. */
@Immutable
class ClayPalette(
    val isDark: Boolean,
    val primary: Color,
    val primaryActive: Color,
    val primaryDisabled: Color,
    val onPrimary: Color,
    val ink: Color,
    val body: Color,
    val bodyStrong: Color,
    val muted: Color,
    val mutedSoft: Color,
    val hairline: Color,
    val hairlineSoft: Color,
    val canvas: Color,
    val surfaceSoft: Color,
    val surfaceCard: Color,
    val surfaceStrong: Color,
    val teal: Color,
    val scrim: Color,
) {
    companion object {
        val Light = ClayPalette(
            isDark = false,
            primary = Color(0xFF0A0A0A),
            primaryActive = Color(0xFF1F1F1F),
            primaryDisabled = Color(0xFFE5E5E5),
            onPrimary = Color(0xFFFFFFFF),
            ink = Color(0xFF0A0A0A),
            body = Color(0xFF3A3A3A),
            bodyStrong = Color(0xFF1A1A1A),
            muted = Color(0xFF6A6A6A),
            mutedSoft = Color(0xFF9A9A9A),
            hairline = Color(0xFFE5E5E5),
            hairlineSoft = Color(0xFFF0F0F0),
            canvas = Color(0xFFFFFAF0),
            surfaceSoft = Color(0xFFFAF5E8),
            surfaceCard = Color(0xFFF5F0E0),
            surfaceStrong = Color(0xFFEBE6D6),
            teal = Color(0xFF1A3A3A),
            scrim = Color(0x570A0A0A),
        )

        /** Warm near-black canvas with cream ink: the same Clay voice after dark. */
        val Dark = ClayPalette(
            isDark = true,
            primary = Color(0xFFF7F2E8),
            primaryActive = Color(0xFFE2DCCF),
            primaryDisabled = Color(0xFF2C2925),
            onPrimary = Color(0xFF0A0A0A),
            ink = Color(0xFFF7F2E8),
            body = Color(0xFFCFC8BB),
            bodyStrong = Color(0xFFE9E3D7),
            muted = Color(0xFF9F988C),
            mutedSoft = Color(0xFF6E685F),
            hairline = Color(0xFF34302A),
            hairlineSoft = Color(0xFF26231F),
            canvas = Color(0xFF131210),
            surfaceSoft = Color(0xFF1B1916),
            surfaceCard = Color(0xFF24211D),
            surfaceStrong = Color(0xFF2F2B26),
            teal = Color(0xFF1F4444),
            scrim = Color(0x99000000),
        )
    }
}

/**
 * Clay design tokens. Neutral tokens read the current [palette], which
 * [WebDroidTheme] swaps for light/dark mode; reads are snapshot-observed, so
 * anything drawn with them updates when the theme changes. Brand colours and
 * the fixed [Black]/[White]/[Cream] stay the same in both modes.
 */
object Clay {
    var palette by mutableStateOf(ClayPalette.Light)

    val isDark: Boolean get() = palette.isDark

    val Primary: Color get() = palette.primary
    val PrimaryActive: Color get() = palette.primaryActive
    val PrimaryDisabled: Color get() = palette.primaryDisabled

    val Ink: Color get() = palette.ink
    val Body: Color get() = palette.body
    val BodyStrong: Color get() = palette.bodyStrong
    val Muted: Color get() = palette.muted
    val MutedSoft: Color get() = palette.mutedSoft
    val Hairline: Color get() = palette.hairline
    val HairlineSoft: Color get() = palette.hairlineSoft

    val Canvas: Color get() = palette.canvas
    val SurfaceSoft: Color get() = palette.surfaceSoft
    val SurfaceCard: Color get() = palette.surfaceCard
    val SurfaceStrong: Color get() = palette.surfaceStrong

    val OnPrimary: Color get() = palette.onPrimary
    /** Dim backdrop behind sheets and dialogs. */
    val Scrim: Color get() = palette.scrim

    val OnDark = Color(0xFFFFFFFF)
    val OnDarkSoft = Color(0xFFA0A0A0)

    /** Fixed colours for content on brand surfaces, the player and web content. */
    val Black = Color(0xFF0A0A0A)
    val White = Color(0xFFFFFFFF)
    val Cream = Color(0xFFFFFAF0)

    val Pink = Color(0xFFFF4D8B)
    val Teal: Color get() = palette.teal
    val Lavender = Color(0xFFB8A4ED)
    val Peach = Color(0xFFFFB084)
    val Ochre = Color(0xFFE8B94A)
    val Mint = Color(0xFFA4D4C5)
    val Coral = Color(0xFFFF6B5A)

    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFF59E0B)
    val Error: Color get() = if (palette.isDark) Color(0xFFFF6B6B) else Color(0xFFEF4444)
}

/**
 * The six saturated feature-card surfaces. Cards cycle through them in this
 * order so the same colour never appears twice in a row. Only Cream follows
 * the theme; the saturated ones keep their colour in dark mode.
 */
enum class CardColor {
    Pink, Teal, Lavender, Peach, Ochre, Cream;

    val background: Color
        get() = when (this) {
            Pink -> Clay.Pink
            Teal -> Clay.Teal
            Lavender -> Clay.Lavender
            Peach -> Clay.Peach
            Ochre -> Clay.Ochre
            Cream -> Clay.SurfaceCard
        }

    val content: Color
        get() = when (this) {
            Pink, Teal -> Clay.White
            Cream -> Clay.Ink
            else -> Clay.Black
        }

    val contentSoft: Color
        get() = when (this) {
            Pink -> Color(0xE6FFFFFF)
            Teal -> Color(0xB3FFFFFF)
            Cream -> Clay.Muted
            else -> Color(0xB30A0A0A)
        }

    companion object {
        fun of(index: Int): CardColor = entries[Math.floorMod(index, entries.size)]
    }
}
