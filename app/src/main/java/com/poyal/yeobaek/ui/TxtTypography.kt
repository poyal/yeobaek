package com.poyal.yeobaek.ui

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.sp
import com.poyal.yeobaek.data.TxtFont
import com.poyal.yeobaek.data.TxtPreferences
import com.poyal.yeobaek.txt.sha256

val TxtFont.family: FontFamily get() = when (this) {
    TxtFont.NOTO_SERIF -> Serif
    TxtFont.SYSTEM_SERIF -> FontFamily.Serif
    TxtFont.SYSTEM_MONOSPACE -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}

fun TxtPreferences.textStyle() = TextStyle(
    fontFamily = font.family, fontWeight = FontWeight(font.weight),
    fontSize = size.sp, lineHeight = (size * line).sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    lineBreak = LineBreak.Simple, hyphens = Hyphens.None, textDirection = TextDirection.ContentOrLtr,
)

fun TxtPreferences.layoutKey(width: Int, density: Float, fontScale: Float): String =
    sha256("layout-2|$width|${font.name}|${font.weight}|$size|$line|$density|$fontScale".toByteArray()).take(20)
