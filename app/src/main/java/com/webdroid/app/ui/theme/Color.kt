package com.webdroid.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Semantic colours for one appearance. Values follow Apple's system palette,
 * adjusted so every text colour meets 4.5:1 on the surface it sits on
 * (secondary label #6C6C70 on white 5.2:1, on grouped #F2F2F7 4.7:1; dark
 * #A1A1A6 on #1C1C1E 6.6:1; accent #4B4FE0 on white 6.0:1, #8E91FF on
 * #1C1C1E 6.2:1).
 */
@Immutable
class ColorPalette(
    val isDark: Boolean,
    /** Plain screen background (the Apps grid). */
    val background: Color,
    /** Grouped list background (Privacy, Settings). */
    val grouped: Color,
    /** Cells of a grouped list, cards. */
    val cell: Color,
    /** Highlight of a pressed cell. */
    val cellPressed: Color,
    /** Sheets and alerts drawn without glass. */
    val elevated: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    /** Fill of text fields, segmented controls and secondary buttons. */
    val fill: Color,
    /** Accent for text and symbols. */
    val accent: Color,
    /** Background of prominent (filled) buttons. */
    val accentFill: Color,
    val destructive: Color,
    val success: Color,
    val warning: Color,
    val toggleOn: Color,
    val scrim: Color,
    /** Glass: translucent tint over the blurred backdrop, rim light and shadow. */
    val glassTint: Color,
    val glassTintStrong: Color,
    val glassRim: Color,
    val glassShadow: Color,
    /** Glass when the backdrop can't be blurred (old Android, Reduce Transparency). */
    val glassSolid: Color,
) {
    companion object {
        val Light = ColorPalette(
            isDark = false,
            background = Color(0xFFFFFFFF),
            grouped = Color(0xFFF2F2F7),
            cell = Color(0xFFFFFFFF),
            cellPressed = Color(0xFFE5E5EA),
            elevated = Color(0xFFFFFFFF),
            label = Color(0xFF000000),
            secondaryLabel = Color(0xFF6C6C70),
            tertiaryLabel = Color(0xFF8A8A8E),
            separator = Color(0xFFC6C6C8),
            fill = Color(0xFFEBEBF0),
            accent = Color(0xFF4B4FE0),
            accentFill = Color(0xFF4B4FE0),
            destructive = Color(0xFFD70015),
            success = Color(0xFF1F7A35),
            warning = Color(0xFFB25000),
            toggleOn = Color(0xFF34C759),
            scrim = Color(0x40000000),
            glassTint = Color(0x52FFFFFF),
            glassTintStrong = Color(0xC7FAFAFC),
            glassRim = Color(0xB3FFFFFF),
            glassShadow = Color(0x24000000),
            glassSolid = Color(0xF5F9F9FB),
        )

        val Dark = ColorPalette(
            isDark = true,
            background = Color(0xFF000000),
            grouped = Color(0xFF000000),
            cell = Color(0xFF1C1C1E),
            cellPressed = Color(0xFF2C2C2E),
            elevated = Color(0xFF1C1C1E),
            label = Color(0xFFFFFFFF),
            secondaryLabel = Color(0xFFA1A1A6),
            tertiaryLabel = Color(0xFF7C7C80),
            separator = Color(0xFF38383A),
            fill = Color(0xFF2C2C2F),
            accent = Color(0xFF8E91FF),
            accentFill = Color(0xFF5E62F0),
            destructive = Color(0xFFFF6961),
            success = Color(0xFF30D158),
            warning = Color(0xFFFF9F0A),
            toggleOn = Color(0xFF30D158),
            scrim = Color(0x80000000),
            glassTint = Color(0x4D3A3A3C),
            glassTintStrong = Color(0xB81C1C1E),
            glassRim = Color(0x33FFFFFF),
            glassShadow = Color(0x66000000),
            glassSolid = Color(0xF51C1C1E),
        )
    }
}

/**
 * The current appearance's colours. [palette] is snapshot state swapped by
 * [WebDroidTheme], so anything drawn with these updates when the theme changes.
 */
object AppColors {
    var palette by mutableStateOf(ColorPalette.Light)

    val isDark: Boolean get() = palette.isDark
    val Background: Color get() = palette.background
    val Grouped: Color get() = palette.grouped
    val Cell: Color get() = palette.cell
    val CellPressed: Color get() = palette.cellPressed
    val Elevated: Color get() = palette.elevated
    val Label: Color get() = palette.label
    val SecondaryLabel: Color get() = palette.secondaryLabel
    val TertiaryLabel: Color get() = palette.tertiaryLabel
    val Separator: Color get() = palette.separator
    val Fill: Color get() = palette.fill
    val Accent: Color get() = palette.accent
    val AccentFill: Color get() = palette.accentFill
    val OnAccent: Color = Color.White
    val Destructive: Color get() = palette.destructive
    val Success: Color get() = palette.success
    val Warning: Color get() = palette.warning
    val ToggleOn: Color get() = palette.toggleOn
    val Scrim: Color get() = palette.scrim
    val GlassTint: Color get() = palette.glassTint
    val GlassTintStrong: Color get() = palette.glassTintStrong
    val GlassRim: Color get() = palette.glassRim
    val GlassShadow: Color get() = palette.glassShadow
    val GlassSolid: Color get() = palette.glassSolid
}

/**
 * App icon colours (letter icons, launcher shortcuts, Recents). A soft
 * top-to-bottom gradient like a home-screen icon; the same in both
 * appearances because an app's icon is part of its identity.
 */
enum class TileColor(val top: Color, val bottom: Color, val glyph: Color) {
    Pink(Color(0xFFFF6482), Color(0xFFFF2D55), Color.White),
    Teal(Color(0xFF3FC7D6), Color(0xFF0A8EA6), Color.White),
    Purple(Color(0xFFC07CF2), Color(0xFF8E44D9), Color.White),
    Orange(Color(0xFFFFB14E), Color(0xFFFF7A1A), Color.White),
    Yellow(Color(0xFFFFE066), Color(0xFFFFC21A), Color(0xFF3D2E00)),
    Graphite(Color(0xFF8E8E93), Color(0xFF48484A), Color.White),
    Blue(Color(0xFF5AC8FA), Color(0xFF1E7BF2), Color.White),
    Green(Color(0xFF6EE08A), Color(0xFF28B14C), Color.White),
    Indigo(Color(0xFF8A8DFF), Color(0xFF4B4FE0), Color.White);

    /** Representative flat colour (Recents, notification accents). */
    val solid: Color get() = bottom

    companion object {
        fun of(index: Int): TileColor = entries[Math.floorMod(index, entries.size)]
    }
}
