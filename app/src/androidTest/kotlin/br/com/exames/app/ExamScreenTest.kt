package br.com.exames.app

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.exames.coreui.ChipKind
import br.com.exames.coreui.ExamesTheme
import br.com.exames.coreui.screens.*
import br.com.exames.datalocal.ExamHistoryEntry
import br.com.exames.domain.extraction.*
import br.com.exames.domain.validation.SourceRegion
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExamScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeDisplaysBrandHeroGuidanceAndImportNotice() {
        var selected = 0
        var openedSend = 0
        compose.setContent {
            ExamesTheme {
                HomeScreen(
                    recentExams = emptyList(),
                    notice = "Selecione um arquivo PDF digital.",
                    onSelectPdf = { selected++ },
                    onOpenSend = { openedSend++ },
                    onOpenExam = {},
                    onOpenHistory = {},
                    onOpenHelp = {},
                    onDeleteExam = {},
                )
            }
        }
        compose.onNodeWithText("Meu Exame").assertIsDisplayed()
        compose.onNodeWithText("Selecione um arquivo PDF digital.").assertIsDisplayed()
        compose.onNodeWithText("Selecionar PDF").assertHasClickAction().performClick()
        compose.onNodeWithText("Opções de envio e dicas").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, selected)
            assertEquals(1, openedSend)
        }
    }

    @Test fun sendScreenShowsPdfActionHistoryAndPrivacyNotice() {
        var picked = 0
        var history = 0
        compose.setContent {
            ExamesTheme {
                SendExamScreen(
                    notice = "O arquivo selecionado está vazio.",
                    onBack = {},
                    onSelectPdf = { picked++ },
                    onOpenHistory = { history++ },
                    onOpenHelp = {},
                )
            }
        }
        compose.onNodeWithText("O arquivo selecionado está vazio.").assertIsDisplayed()
        compose.onNodeWithText("Selecionar PDF digital").performClick()
        compose.onNodeWithText("Abrir histórico local").performScrollTo().performClick()
        compose.onNodeWithText("Dicas para uma leitura fiel").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, picked)
            assertEquals(1, history)
        }
    }

    @Test fun processingShowsPageProgressAndOffersCancellation() {
        var cancelled = 0
        compose.setContent {
            ExamesTheme {
                ProcessingScreen(
                    state = ProcessingUiState(
                        message = "Extraindo com IA",
                        page = 2,
                        totalPages = 6,
                        block = 1,
                        totalBlocks = 3,
                        pages = listOf(
                            PageProgressUi(1, PageProgressStatus.COMPLETED, 4),
                            PageProgressUi(2, PageProgressStatus.IN_PROGRESS, 0),
                            PageProgressUi(3, PageProgressStatus.PENDING, 0),
                        ),
                        cancellable = true,
                    ),
                    onCancel = { cancelled++ },
                )
            }
        }
        compose.onNodeWithText("Offline no aparelho").assertIsDisplayed()
        compose.onNodeWithText("Página 2 de 6 · Bloco 1 de 3").assertIsDisplayed()
        compose.onNodeWithText("4 itens verificados").assertIsDisplayed()
        compose.onNodeWithText("Cancelar leitura").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test fun resultsSupportStatusFiltersAndPartialExtractionNotices() {
        val region = SourceRegion(1, 10.0, 20.0, 80.0, 30.0, 500.0, 800.0)
        val extraction = ExamExtraction(
            listOf(
                ExamPageOutcome(
                    page = 1,
                    observations = listOf(
                        ExamObservation(ExamCitation("n1", "Item A", listOf(region)), ExamCitation("v1", "118 mg/dL", listOf(region)), "118", "mg/dL", ExamCitation("f1", "70 a 99 mg/dL", listOf(region)), false),
                        ExamObservation(ExamCitation("n2", "Item B", listOf(region)), ExamCitation("v2", "14,2 g/dL", listOf(region)), "14,2", "g/dL", ExamCitation("f2", "13,5 a 17,5 g/dL", listOf(region)), false),
                    ),
                    issues = setOf(ExamIssue.INSUFFICIENT_POSITIONED_TEXT),
                    inferenceFailures = emptySet(),
                ),
            ),
        )
        val assessed = ExamSummaryProjection.project(extraction)
        val entry = ExamHistoryEntry("exam-1", Instant.parse("2026-10-12T12:00:00Z").toEpochMilli(), 1, 2, 1, 1, 0, true)
        var filter by mutableStateOf(ResultFilterOption.ALL)

        compose.setContent {
            ExamesTheme {
                ResultsScreen(
                    state = ExamUiMapper.toResultsUi(entry, assessed, filter, null, ZoneOffset.UTC),
                    onBack = {},
                    onSelectFilter = { filter = it },
                    onOpenEvidence = {},
                    onOpenHelp = {},
                    onSelectAnotherPdf = {},
                    onDeleteExam = {},
                )
            }
        }

        compose.onNodeWithText("1 item pede conferência").assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Revisar (1)"))
        compose.onNodeWithText("Revisar (1)").performClick()
        compose.waitForIdle()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Item A"))
        assertEquals(1, compose.onAllNodesWithText("Item A").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Item B").fetchSemanticsNodes().size)
    }

    @Test fun evidenceScreenTogglesCropFullPageAndFieldHighlightsAtLargeText() {
        var openedPage = 0
        val state = EvidenceUiState(
            examId = "opaque",
            page = 2,
            totalPages = 6,
            itemName = "Item de contrato",
            valueWithUnit = "162 mg/dL",
            statusLabel = "Revisar",
            statusKind = ChipKind.REVIEW,
            rawReference = "Desejável: < 130 mg/dL",
            documentaryNote = "Referência com múltiplos critérios ou condicional — confira no laudo",
            resultBoxes = listOf(EvidenceBoxUi(12f, 12f, 30f, 22f, 100f, 200f)),
            referenceBoxes = listOf(EvidenceBoxUi(40f, 12f, 90f, 28f, 100f, 200f)),
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                ExamesTheme {
                    EvidenceScreen(
                        state = state,
                        loadPage = { page ->
                            openedPage = page
                            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
                        },
                        onBack = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Página 2 de 6").assertIsDisplayed()
        compose.onNodeWithContentDescription("Recorte da página 2 com destaque nos trechos confirmados").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Página inteira").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Página 2 inteira com destaque nos trechos confirmados").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Referência").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, openedPage) }
    }

    @Test fun helpScreenShowsLegendAndNonDiagnosticBoundary() {
        compose.setContent {
            ExamesTheme {
                HelpScreen(onBack = null, onOpenExams = {})
            }
        }
        compose.onNodeWithText("Ajuda e revisão").assertIsDisplayed()
        compose.onNodeWithText("O que significa cada marcação").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Este app não faz diagnóstico").performScrollTo().assertIsDisplayed()
    }
}
