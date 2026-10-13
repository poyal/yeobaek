package com.poyal.yeobaek.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.poyal.yeobaek.R
import com.poyal.yeobaek.data.*

val Serif = FontFamily(Font(R.font.noto_serif_kr))
data class PaperColors(val surface: Color, val alt: Color, val raised: Color, val ink: Color, val muted: Color, val accent: Color, val onAccent: Color, val soft: Color, val line: Color, val control: Color, val danger: Color)
val LightPaper = PaperColors(Color(0xFFF8F5EF),Color(0xFFEEEEE4),Color(0xFFFFFDF8),Color(0xFF30372F),Color(0xFF5F6958),Color(0xFF536348),Color(0xFFFFFDF8),Color(0xFFE3E8D7),Color(0xFFD6D8CA),Color(0xFF7B846E),Color(0xFFA23F32))
val DarkPaper = PaperColors(Color(0xFF1D211C),Color(0xFF292F25),Color(0xFF2B3028),Color(0xFFE9E7DD),Color(0xFFA4AD9D),Color(0xFFB4C4A1),Color(0xFF20271B),Color(0xFF333E2C),Color(0xFF41493A),Color(0xFF899780),Color(0xFFF0AD9E))
val SepiaPaper = LightPaper.copy(surface = Color(0xFFF2E7D4), ink = Color(0xFF504536), muted = Color(0xFF70604B), accent = Color(0xFF645535))
val LocalPaper = staticCompositionLocalOf { LightPaper }

@Composable fun YeobaekTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val dark = theme == AppTheme.DARK || theme == AppTheme.SYSTEM && isSystemInDarkTheme()
    val p = if (dark) DarkPaper else LightPaper
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.soft, onPrimaryContainer = p.ink,
        secondary = p.accent, onSecondary = p.onAccent, secondaryContainer = p.soft, onSecondaryContainer = p.ink,
        tertiary = p.accent, onTertiary = p.onAccent, tertiaryContainer = p.soft, onTertiaryContainer = p.ink,
        surface = p.surface, onSurface = p.ink, onSurfaceVariant = p.muted, surfaceVariant = p.alt,
        surfaceContainer = p.alt, surfaceContainerHigh = p.raised, outline = p.control, outlineVariant = p.line,
        error = p.danger, background = p.surface, onBackground = p.ink)
    CompositionLocalProvider(LocalPaper provides p) { MaterialTheme(colorScheme = scheme, content = content) }
}
fun readerColors(paper: TxtPaper, app: PaperColors): PaperColors = when (paper) {
    TxtPaper.FOLLOW_APP -> app; TxtPaper.LIGHT -> LightPaper; TxtPaper.DARK -> DarkPaper; TxtPaper.SEPIA -> SepiaPaper
}
