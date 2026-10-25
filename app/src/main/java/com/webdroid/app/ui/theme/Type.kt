package com.webdroid.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.webdroid.app.R

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/**
 * Clay type scale. Display styles stand in for "Plain Black": Inter 500 with
 * tight negative tracking, never bolder than 500. Sizes are the mobile steps
 * of the scale.
 */
object ClayType {
    val DisplayLg = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 40.sp, lineHeight = 42.sp, letterSpacing = (-0.045).em)
    val DisplayMd = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.035).em)
    val DisplaySm = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.025).em)
    val TitleLg = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp)
    val TitleMd = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 25.sp)
    val TitleSm = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
    val BodyMd = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
    val BodySm = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp)
    val Caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
    val CaptionUpper = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 1.5.sp)
    val Button = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 15.sp)
    val NavLink = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
}
