package br.com.exames.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import br.com.exames.coreui.ExamesTokens
import org.junit.Assert.assertTrue
import org.junit.Test

/** Measures the real token pairs: text >= 4.5:1, interactive controls/borders >= 3:1. */
class ThemeContrastTest {
    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance(); val y = b.luminance(); return (maxOf(x, y) + .05f) / (minOf(x, y) + .05f)
    }
    private fun text(name: String, fg: Color, bg: Color) = assertTrue("TEXT_CONTRAST_BELOW_4_5 $name=${contrast(fg, bg)}", contrast(fg, bg) >= 4.5f)
    private fun control(name: String, fg: Color, bg: Color) = assertTrue("CONTROL_CONTRAST_BELOW_3 $name=${contrast(fg, bg)}", contrast(fg, bg) >= 3f)

    @Test fun lightAndDarkTextPairsMeetFourPointFive() {
        for ((mode, c, p) in listOf(Triple("light", ExamesTokens.lightScheme, ExamesTokens.lightPalette), Triple("dark", ExamesTokens.darkScheme, ExamesTokens.darkPalette))) {
            text("$mode body/background", c.onBackground, c.background)
            text("$mode body/surface", c.onSurface, c.surface)
            text("$mode secondary/background", p.secondaryText, c.background)
            text("$mode secondary/surface", p.secondaryText, c.surface)
            text("$mode variant/surfaceVariant", c.onSurfaceVariant, c.surfaceVariant)
            text("$mode title/background", p.title, c.background)
            text("$mode title/surface", p.title, c.surface)
            text("$mode primary text/surface", c.primary, c.surface)
            text("$mode primary text/background", c.primary, c.background)
            text("$mode onPrimary/primary", c.onPrimary, c.primary)
            text("$mode onPrimaryContainer", c.onPrimaryContainer, c.primaryContainer)
            text("$mode error/surface", c.error, c.surface)
            text("$mode onError/error", c.onError, c.error)
            text("$mode info chip", p.onInfoContainer, p.infoContainer)
            text("$mode review chip", p.onReviewContainer, p.reviewContainer)
            text("$mode alert chip", p.onAlertContainer, p.alertContainer)
            text("$mode hero title", p.onHeroContainer, p.heroContainer)
            text("$mode hero subtext", p.onHeroSubtext, p.heroContainer)
            text("$mode hero action", p.onHeroActionContainer, p.heroActionContainer)
        }
    }
    @Test fun controlBordersAndFocusableFillsMeetThree() {
        for ((mode, c, p) in listOf(Triple("light", ExamesTokens.lightScheme, ExamesTokens.lightPalette), Triple("dark", ExamesTokens.darkScheme, ExamesTokens.darkPalette))) {
            control("$mode controlBorder/surface", p.controlBorder, c.surface)
            control("$mode controlBorder/background", p.controlBorder, c.background)
            control("$mode outline/surface", c.outline, c.surface)
            control("$mode primary fill/surface", c.primary, c.surface)
        }
    }
    @Test fun decorativeBordersAreNeverTheControlBorder() {
        for (p in listOf(ExamesTokens.lightPalette, ExamesTokens.darkPalette)) assertTrue(p.decorativeBorder != p.controlBorder)
    }
}
