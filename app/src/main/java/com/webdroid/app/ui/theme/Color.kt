package com.webdroid.app.ui.theme

import androidx.compose.ui.graphics.Color

/** Clay design tokens. */
object Clay {
    val Primary = Color(0xFF0A0A0A)
    val PrimaryActive = Color(0xFF1F1F1F)
    val PrimaryDisabled = Color(0xFFE5E5E5)

    val Ink = Color(0xFF0A0A0A)
    val Body = Color(0xFF3A3A3A)
    val BodyStrong = Color(0xFF1A1A1A)
    val Muted = Color(0xFF6A6A6A)
    val MutedSoft = Color(0xFF9A9A9A)
    val Hairline = Color(0xFFE5E5E5)
    val HairlineSoft = Color(0xFFF0F0F0)

    val Canvas = Color(0xFFFFFAF0)
    val SurfaceSoft = Color(0xFFFAF5E8)
    val SurfaceCard = Color(0xFFF5F0E0)
    val SurfaceStrong = Color(0xFFEBE6D6)
    val SurfaceDark = Color(0xFF0A1A1A)
    val SurfaceDarkElevated = Color(0xFF1A2A2A)

    val OnPrimary = Color(0xFFFFFFFF)
    val OnDark = Color(0xFFFFFFFF)
    val OnDarkSoft = Color(0xFFA0A0A0)

    val Pink = Color(0xFFFF4D8B)
    val Teal = Color(0xFF1A3A3A)
    val Lavender = Color(0xFFB8A4ED)
    val Peach = Color(0xFFFFB084)
    val Ochre = Color(0xFFE8B94A)
    val Mint = Color(0xFFA4D4C5)
    val Coral = Color(0xFFFF6B5A)

    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFF59E0B)
    val Error = Color(0xFFEF4444)
}

/**
 * The six saturated feature-card surfaces. Cards cycle through them in this
 * order so the same colour never appears twice in a row.
 */
enum class CardColor(val background: Color, val content: Color, val contentSoft: Color) {
    Pink(Clay.Pink, Clay.OnPrimary, Color(0xE6FFFFFF)),
    Teal(Clay.Teal, Clay.OnDark, Color(0xB3FFFFFF)),
    Lavender(Clay.Lavender, Clay.Ink, Color(0xB30A0A0A)),
    Peach(Clay.Peach, Clay.Ink, Color(0xB30A0A0A)),
    Ochre(Clay.Ochre, Clay.Ink, Color(0xB30A0A0A)),
    Cream(Clay.SurfaceCard, Clay.Ink, Clay.Muted);

    companion object {
        fun of(index: Int): CardColor = entries[Math.floorMod(index, entries.size)]
    }
}
