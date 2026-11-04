package br.com.exames.coreui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Replaced by the bundled Figtree family in task 1.3; sizes are in sp so they scale to 200%. */
object ExamesFonts { var family: FontFamily = FontFamily.Default }

fun examesTypography(family: FontFamily = ExamesFonts.family): Typography {
    fun s(size: Int, line: Int, weight: FontWeight) = TextStyle(fontFamily = family, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)
    return Typography(
        headlineMedium = s(26, 32, FontWeight.ExtraBold),
        headlineSmall = s(22, 28, FontWeight.Bold),
        titleLarge = s(20, 26, FontWeight.Bold),
        titleMedium = s(17, 24, FontWeight.SemiBold),
        titleSmall = s(15, 22, FontWeight.SemiBold),
        bodyLarge = s(16, 24, FontWeight.Normal),
        bodyMedium = s(14, 21, FontWeight.Normal),
        bodySmall = s(13, 19, FontWeight.Normal),
        labelLarge = s(16, 22, FontWeight.SemiBold),
        labelMedium = s(13, 18, FontWeight.Medium),
        labelSmall = s(12, 16, FontWeight.Medium),
    )
}

val ExamesShapes = Shapes(
    small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp),
)
