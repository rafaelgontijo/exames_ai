package br.com.exames.uichecks

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import br.com.exames.coreui.ChipKind
import br.com.exames.coreui.ExamesTheme
import br.com.exames.coreui.screens.*
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unified Stitch screens x light/dark x font 1x/2x: accessibility checks, >= 48 dp touch targets,
 * reachability by scrolling, and synthetic captures (no clinical data).
 */
class PrototypeScreensTest {
    @get:Rule val compose = createComposeRule()
    private val outDir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }

    private val syntheticExams = listOf(
        ExamCardItemUi("doc-sintetico-1", "Laudo laboratorial · PDF", "12 de outubro · 6 páginas", "2 para revisar", ChipKind.REVIEW),
        ExamCardItemUi("doc-sintetico-2", "Laudo laboratorial · PDF", "3 de agosto · 2 páginas", "Na referência", ChipKind.INFO),
    )

    private val syntheticProcessing = ProcessingUiState(
        message = "Analisando laudo em PDF",
        page = 3,
        totalPages = 6,
        block = 2,
        totalBlocks = 4,
        pages = listOf(
            PageProgressUi(1, PageProgressStatus.COMPLETED, 21),
            PageProgressUi(2, PageProgressStatus.COMPLETED, 3),
            PageProgressUi(3, PageProgressStatus.IN_PROGRESS, 0),
            PageProgressUi(4, PageProgressStatus.PENDING, 0),
            PageProgressUi(5, PageProgressStatus.PENDING, 0),
            PageProgressUi(6, PageProgressStatus.PENDING, 0),
        ),
        cancellable = true,
    )

    private val syntheticResults = ResultsUiState(
        examId = "doc-sintetico-1",
        dateLabel = "12 out",
        pageCount = 6,
        totalCount = 3,
        reviewCount = 2,
        partial = false,
        activeFilter = ResultFilterOption.ALL,
        filters = listOf(
            ResultFilterTabUi(ResultFilterOption.ALL, "Todos", 3),
            ResultFilterTabUi(ResultFilterOption.REVIEW, "Revisar", 2),
            ResultFilterTabUi(ResultFilterOption.WITHIN_RANGE, "Na referência", 1),
        ),
        pages = listOf(
            ResultPageGroupUi(
                page = 1,
                issueMessage = null,
                items = listOf(
                    ResultItemUi("v1", "Item sintético A", "118 mg/dL", "70 a 99 mg/dL", "Revisar", ChipKind.REVIEW, "Acima da faixa informada no documento", 1, true),
                    ResultItemUi("v2", "Item sintético B", "14,2 g/dL", "13,5 a 17,5 g/dL", "Na referência", ChipKind.INFO, "Dentro da referência informada no documento", 1, true),
                ),
            ),
            ResultPageGroupUi(
                page = 2,
                issueMessage = null,
                items = listOf(
                    ResultItemUi("v3", "Item sintético C", "162 mg/dL", "Desejável: < 130 mg/dL", "Revisar", ChipKind.REVIEW, "Referência com múltiplos critérios ou condicional — confira no laudo", 2, true),
                ),
            ),
        ),
        primaryEvidenceItemId = "v1",
    )

    private val syntheticEvidence = EvidenceUiState(
        examId = "doc-sintetico-1",
        page = 2,
        totalPages = 6,
        itemName = "Item sintético C",
        valueWithUnit = "162 mg/dL",
        statusLabel = "Revisar",
        statusKind = ChipKind.REVIEW,
        rawReference = "Desejável: < 130 mg/dL · Risco alto: < 70 mg/dL",
        documentaryNote = "Referência com múltiplos critérios ou condicional — confira no laudo",
        resultBoxes = listOf(EvidenceBoxUi(20f, 120f, 220f, 150f, 500f, 800f)),
        referenceBoxes = listOf(EvidenceBoxUi(260f, 120f, 470f, 175f, 500f, 800f)),
    )

    private val screens: Map<String, @Composable () -> Unit> = mapOf(
        "inicio-vazio" to { HomeScreen(emptyList(), null, {}, {}, {}, {}, {}, {}) },
        "inicio-com-exame" to { HomeScreen(syntheticExams, null, {}, {}, {}, {}, {}, {}) },
        "exames" to { ExamsHistoryScreen(syntheticExams, null, {}, {}, {}, {}) },
        "enviar" to { SendExamScreen(null, {}, {}, {}, {}) },
        "processando" to { ProcessingScreen(syntheticProcessing, {}) },
        "resultado" to { ResultsScreen(syntheticResults, {}, {}, {}, {}, {}, {}) },
        "evidencia" to {
            EvidenceScreen(
                state = syntheticEvidence,
                loadPage = { Bitmap.createBitmap(500, 800, Bitmap.Config.ARGB_8888) },
                onBack = {},
            )
        },
        "ajuda" to { HelpScreen(onBack = null, onOpenExams = {}) },
    )

    private data class Variant(val name: String, val dark: Boolean, val fontScale: Float)
    private var variant by mutableStateOf(Variant("inicio-vazio", false, 1f))
    private var started = false

    private fun render(name: String, dark: Boolean, fontScale: Float) {
        if (!started) {
            started = true
            compose.setContent {
                val v = variant
                key(v) {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, v.fontScale)) {
                        ExamesTheme(darkTheme = v.dark) {
                            Surface(color = MaterialTheme.colorScheme.background) { screens.getValue(v.name)() }
                        }
                    }
                }
            }
        }
        compose.runOnIdle { variant = Variant(name, dark, fontScale) }
        compose.waitForIdle()
    }

    private fun verify(name: String, dark: Boolean, fontScale: Float) {
        render(name, dark, fontScale)
        val clickable = compose.onAllNodes(hasClickAction())
        val count = clickable.fetchSemanticsNodes().size
        assertTrue("$name has no actions", count > 0)
        val density = compose.density.density
        for (i in 0 until count) {
            val semanticsNode = clickable[i].fetchSemanticsNode()
            val size = semanticsNode.size
            assertTrue("$name dark=$dark font=$fontScale control $i too short: ${size.height / density}dp", size.height / density >= 47.5f)
            assertTrue("$name dark=$dark font=$fontScale control $i too narrow: ${size.width / density}dp", size.width / density >= 47.5f)
            if (name != "resultado") {
                val hasScrollParent = generateSequence(semanticsNode.parent) { it.parent }
                    .any { SemanticsActions.ScrollBy in it.config }
                if (hasScrollParent) clickable[i].performScrollTo().assertIsDisplayed()
                else clickable[i].assertIsDisplayed()
            }
        }
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    private fun capture(name: String, dark: Boolean, fontScale: Float) {
        render(name, dark, fontScale)
        val image = if (android.os.Build.VERSION.SDK_INT >= 26) compose.onRoot().captureToImage().asAndroidBitmap()
        else requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val file = File(outDir, "$name-${if (dark) "escuro" else "claro"}-${(fontScale * 100).toInt()}.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun allScreensPassAccessibilityChecksAndTouchTargetsInBothThemesAndFontScales() {
        compose.enableAccessibilityChecks()
        for (name in screens.keys) for (dark in listOf(false, true)) for (scale in listOf(1f, 2f)) verify(name, dark, scale)
    }

    @Test fun capturesSyntheticScreensForTheDocumentation() {
        for (name in screens.keys) for (dark in listOf(false, true)) for (scale in listOf(1f, 2f)) capture(name, dark, scale)
        assertEquals(screens.size * 4, outDir.listFiles().orEmpty().count { it.extension == "png" })
    }
}
