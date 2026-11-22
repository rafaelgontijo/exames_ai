package br.com.exames.coreui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalExamesPalette = staticCompositionLocalOf { ExamesTokens.lightPalette }

object ExamesTheme {
    val palette: ExamesPalette @Composable @ReadOnlyComposable get() = LocalExamesPalette.current
}

@Composable
fun ExamesTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExamesPalette provides if (darkTheme) ExamesTokens.darkPalette else ExamesTokens.lightPalette) {
        MaterialTheme(
            colorScheme = if (darkTheme) ExamesTokens.darkScheme else ExamesTokens.lightScheme,
            typography = examesTypography(),
            shapes = ExamesShapes,
            content = content,
        )
    }
}
