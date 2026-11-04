package br.com.exames.coreui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Palette from the approved prototype (light) with explicit dark equivalents. Decorative borders are NOT
 * used for interactive controls; those use [ExamesPalette.controlBorder] (>= 3:1). Status is always paired
 * with text/icon, and red never means urgency.
 */
data class ExamesPalette(
    val title: Color,
    val secondaryText: Color,
    val decorativeBorder: Color,
    val controlBorder: Color,
    val infoContainer: Color, val onInfoContainer: Color,
    val reviewContainer: Color, val onReviewContainer: Color,
    val alertContainer: Color, val onAlertContainer: Color,
)

object ExamesTokens {
    val lightScheme: ColorScheme = lightColorScheme(
        primary = Color(0xFF1F5FBF), onPrimary = Color.White,
        primaryContainer = Color(0xFFDCEAFB), onPrimaryContainer = Color(0xFF0F3A7A),
        background = Color(0xFFF4F8FD), onBackground = Color(0xFF13233A),
        surface = Color.White, onSurface = Color(0xFF13233A),
        surfaceVariant = Color(0xFFE8F1FC), onSurfaceVariant = Color(0xFF4A5B72),
        outline = Color(0xFF6B7F99), outlineVariant = Color(0xFFD6E6F8),
        error = Color(0xFFB3261E), onError = Color.White,
    )
    val darkScheme: ColorScheme = darkColorScheme(
        primary = Color(0xFF8DB8FF), onPrimary = Color(0xFF06214A),
        primaryContainer = Color(0xFF1B3A6B), onPrimaryContainer = Color(0xFFD6E6FF),
        background = Color(0xFF0E1726), onBackground = Color(0xFFE6EEF9),
        surface = Color(0xFF16233A), onSurface = Color(0xFFE6EEF9),
        surfaceVariant = Color(0xFF1F2F49), onSurfaceVariant = Color(0xFFB4C4DA),
        outline = Color(0xFF8499B6), outlineVariant = Color(0xFF2C3E5B),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    )
    val lightPalette = ExamesPalette(
        title = Color(0xFF0F3A7A), secondaryText = Color(0xFF4A5B72),
        decorativeBorder = Color(0xFFD6E6F8), controlBorder = Color(0xFF6B7F99),
        infoContainer = Color(0xFFDCEAFB), onInfoContainer = Color(0xFF0F3A7A),
        reviewContainer = Color(0xFFFFF2D9), onReviewContainer = Color(0xFF5C3600),
        alertContainer = Color(0xFFFDE4E1), onAlertContainer = Color(0xFF8C1D18),
    )
    val darkPalette = ExamesPalette(
        title = Color(0xFFC9DCFF), secondaryText = Color(0xFFB4C4DA),
        decorativeBorder = Color(0xFF2C3E5B), controlBorder = Color(0xFF8499B6),
        infoContainer = Color(0xFF1B3A6B), onInfoContainer = Color(0xFFD6E6FF),
        reviewContainer = Color(0xFF4A3A00), onReviewContainer = Color(0xFFFFE08A),
        alertContainer = Color(0xFF5C1A17), onAlertContainer = Color(0xFFFFD9D5),
    )
}
