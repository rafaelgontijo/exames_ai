package br.com.exames.coreui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Crisp 2px-stroke offline vector icons matching the Meu Exame Stitch visual system. */
object ExamesIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.strokePath(width: Float = 2f, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    val Flask: ImageVector by lazy {
        icon("Flask") {
            strokePath {
                moveTo(9f, 3f); lineTo(15f, 3f)
                moveTo(10f, 3f); lineTo(10f, 9f); lineTo(5f, 18f)
                curveTo(4.3f, 19.3f, 5.2f, 21f, 6.7f, 21f)
                lineTo(17.3f, 21f)
                curveTo(18.8f, 21f, 19.7f, 19.3f, 19f, 18f)
                lineTo(14f, 9f); lineTo(14f, 3f)
                moveTo(7.5f, 15f); lineTo(16.5f, 15f)
            }
        }
    }

    val HelpCircle: ImageVector by lazy {
        icon("HelpCircle") {
            strokePath {
                moveTo(12f, 3f); arcTo(9f, 9f, 0f, true, false, 12f, 21f); arcTo(9f, 9f, 0f, true, false, 12f, 3f)
                moveTo(9.5f, 9f); curveTo(9.5f, 7.6f, 10.6f, 6.5f, 12f, 6.5f); curveTo(13.4f, 6.5f, 14.5f, 7.6f, 14.5f, 9.5f)
                curveTo(14.5f, 11f, 12f, 11.5f, 12f, 13f)
                moveTo(12f, 17f); lineTo(12.01f, 17f)
            }
        }
    }

    val ChevronLeft: ImageVector by lazy {
        icon("ChevronLeft") {
            strokePath(2.2f) {
                moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f)
            }
        }
    }

    val Home: ImageVector by lazy {
        icon("Home") {
            strokePath {
                moveTo(4f, 11f); lineTo(12f, 4f); lineTo(20f, 11f); lineTo(20f, 20f); lineTo(4f, 20f); close()
            }
        }
    }

    val Document: ImageVector by lazy {
        icon("Document") {
            strokePath {
                moveTo(7f, 3f); lineTo(14f, 3f); lineTo(18f, 7f); lineTo(18f, 21f); lineTo(7f, 21f); close()
                moveTo(14f, 3f); lineTo(14f, 7f); lineTo(18f, 7f)
                moveTo(10f, 12f); lineTo(15f, 12f)
                moveTo(10f, 16f); lineTo(15f, 16f)
            }
        }
    }

    val Folder: ImageVector by lazy {
        icon("Folder") {
            strokePath {
                moveTo(3f, 7f); lineTo(9f, 7f); lineTo(11f, 9f); lineTo(21f, 9f); lineTo(21f, 19f); lineTo(3f, 19f); close()
            }
        }
    }

    val ShieldLock: ImageVector by lazy {
        icon("ShieldLock") {
            strokePath {
                moveTo(12f, 3f); lineTo(19f, 6f); lineTo(19f, 12f)
                curveTo(19f, 16.5f, 16f, 19.5f, 12f, 21f)
                curveTo(8f, 19.5f, 5f, 16.5f, 5f, 12f); lineTo(5f, 6f); close()
                moveTo(9.5f, 12f); lineTo(11.3f, 13.8f); lineTo(15f, 10f)
            }
        }
    }

    val Check: ImageVector by lazy {
        icon("Check") {
            strokePath(2.4f) {
                moveTo(5f, 12f); lineTo(10f, 17f); lineTo(19f, 7f)
            }
        }
    }

    val AlertCircle: ImageVector by lazy {
        icon("AlertCircle") {
            strokePath(2.2f) {
                moveTo(12f, 7f); lineTo(12f, 13f)
                moveTo(12f, 17f); lineTo(12.01f, 17f)
            }
        }
    }

    val WarningTriangle: ImageVector by lazy {
        icon("WarningTriangle") {
            strokePath(2.2f) {
                moveTo(12f, 3f); lineTo(21f, 19f); lineTo(3f, 19f); close()
                moveTo(12f, 10f); lineTo(12f, 14f)
                moveTo(12f, 17f); lineTo(12.01f, 17f)
            }
        }
    }

    val EyeDocument: ImageVector by lazy {
        icon("EyeDocument") {
            strokePath {
                moveTo(2.5f, 12f); curveTo(5f, 7.5f, 8.5f, 6f, 12f, 6f); curveTo(15.5f, 6f, 19f, 7.5f, 21.5f, 12f)
                curveTo(19f, 16.5f, 15.5f, 18f, 12f, 18f); curveTo(8.5f, 18f, 5f, 16.5f, 2.5f, 12f); close()
                moveTo(12f, 9.5f); arcTo(2.5f, 2.5f, 0f, true, false, 12f, 14.5f); arcTo(2.5f, 2.5f, 0f, true, false, 12f, 9.5f)
            }
        }
    }

    val Trash: ImageVector by lazy {
        icon("Trash") {
            strokePath {
                moveTo(4f, 7f); lineTo(20f, 7f)
                moveTo(9f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 7f)
                moveTo(6f, 7f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 7f)
                moveTo(10f, 11f); lineTo(10f, 16f)
                moveTo(14f, 11f); lineTo(14f, 16f)
            }
        }
    }

    val Clock: ImageVector by lazy {
        icon("Clock") {
            strokePath {
                moveTo(12f, 3f); arcTo(9f, 9f, 0f, true, false, 12f, 21f); arcTo(9f, 9f, 0f, true, false, 12f, 3f)
                moveTo(12f, 7.5f); lineTo(12f, 12f); lineTo(15.5f, 14f)
            }
        }
    }
}
