package com.webdroid.app.ui.theme

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Apple's text styles at the default (Large) Dynamic Type size, set in the
 * phone's own system font. Sizes are in sp, so they follow the system font
 * size; tracking is size-specific (tighter as type grows, near zero for body).
 */
object AppType {
    private fun style(size: Int, leading: Int, weight: FontWeight, tracking: TextUnit) = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = leading.sp,
        letterSpacing = tracking,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

    val LargeTitle = style(34, 41, FontWeight.Bold, (-0.6).sp)
    val Title1 = style(28, 34, FontWeight.Bold, (-0.45).sp)
    val Title2 = style(22, 28, FontWeight.Bold, (-0.3).sp)
    val Title3 = style(20, 25, FontWeight.SemiBold, (-0.2).sp)
    val Headline = style(17, 22, FontWeight.SemiBold, (-0.2).sp)
    val Body = style(17, 22, FontWeight.Normal, (-0.2).sp)
    val Callout = style(16, 21, FontWeight.Normal, (-0.15).sp)
    val Subhead = style(15, 20, FontWeight.Normal, (-0.1).sp)
    val Footnote = style(13, 18, FontWeight.Normal, 0.sp)
    val Caption1 = style(12, 16, FontWeight.Normal, 0.05.sp)
    val Caption2 = style(11, 13, FontWeight.Medium, 0.1.sp)

    /** Button labels: Body weight bumped for presence without extra size. */
    val Button = Headline
    val SectionHeader = style(13, 18, FontWeight.Medium, 0.1.sp)
}
