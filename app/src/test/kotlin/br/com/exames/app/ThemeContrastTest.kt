package br.com.exames.app

import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    private fun contrast(a: Color,b: Color): Float {
        val x=a.luminance();val y=b.luminance();return (maxOf(x,y)+.05f)/(minOf(x,y)+.05f)
    }
    @Test fun lightAndDarkTextAndControlsHaveExplicitContrastWithoutRelyingOnStatusColor() {
        for(c in listOf(lightColorScheme(),darkColorScheme())) {
            for((text,background) in listOf(c.onSurface to c.surface,c.onSurfaceVariant to c.surfaceVariant,
                c.primary to c.surface,c.onPrimary to c.primary,c.error to c.surface,c.onError to c.error))
                assertTrue("TEXT_CONTRAST_BELOW_4_5",contrast(text,background)>=4.5f)
            assertTrue("CONTROL_BORDER_CONTRAST_BELOW_3",contrast(c.outline,c.surface)>=3f)
        }
        assertTrue(contrast(Color(0xFF005EA8),Color.White)>=3f)
    }
}
