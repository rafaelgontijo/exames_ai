package br.com.exames.app

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.exames.coreui.ExamesTheme
import br.com.exames.domain.extraction.ExamCitation
import br.com.exames.domain.extraction.ExamExtraction
import br.com.exames.domain.extraction.ExamIssue
import br.com.exames.domain.extraction.ExamObservation
import br.com.exames.domain.extraction.ExamPageOutcome
import br.com.exames.domain.validation.SourceRegion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExamScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun entryHasOneSelectionActionAndShowsAnImportFailure() {
        var selected = 0
        content(ExamScreenState.Entry("Selecione um arquivo PDF digital."), select = { selected++ })
        assertEquals(1, compose.onAllNodesWithText("Selecionar PDF").fetchSemanticsNodes().size)
        compose.onNodeWithText("Selecionar PDF").assertHasClickAction().performClick()
        compose.onNodeWithText("Selecione um arquivo PDF digital.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, selected) }
    }

    @Test fun emptyInputHasItsOwnMessage() {
        content(ExamScreenState.Entry("O arquivo selecionado está vazio."))
        compose.onNodeWithText("O arquivo selecionado está vazio.").assertIsDisplayed()
        compose.onNodeWithText("Selecionar PDF").assertHasClickAction()
    }

    @Test fun workingAnnouncesPageAndOffersCancellation() {
        var cancelled = 0
        content(ExamScreenState.Working("Extraindo com IA", 2, 6, 1, 3), cancel = { cancelled++ })
        compose.onNodeWithText("Página 2 de 6").assertIsDisplayed()
        compose.onNodeWithText("Bloco 1 de 3").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test fun noVerifiedDataShowsEmptyResultAndPageFailure() {
        val outcome = ExamPageOutcome(1, emptyList(), setOf(ExamIssue.PAGE_UNREADABLE), emptySet())
        content(ExamScreenState.Finished("opaque", ExamExtraction(listOf(outcome))))
        compose.onNodeWithText("Nenhum resultado confirmado pela IA.").assertIsDisplayed()
        compose.onNodeWithText("Não foi possível ler esta página.").assertIsDisplayed()
    }

    @Test fun largerTextCanScrollToSourcesAndExposeAccessibleHighlight() {
        val region = SourceRegion(2, 12.0, 12.0, 30.0, 22.0, 100.0, 200.0)
        val row = ExamObservation(
            ExamCitation("n", "Item de contrato", listOf(region)),
            ExamCitation("v", "X", listOf(region)),
            "X", null, null, true,
        )
        var openedPage = 0
        val outcome = ExamPageOutcome(2, listOf(row), setOf(ExamIssue.EVIDENCE_NEEDS_REVIEW), emptySet())
        content(ExamScreenState.Finished("opaque", ExamExtraction(listOf(outcome))), largeText = true, preview = { _, page ->
            openedPage = page
            Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        })
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(5)
        compose.onNodeWithContentDescription("Ver Item de contrato no laudo").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("Recorte da página 2 com destaque nos trechos confirmados").assertIsDisplayed()
        compose.onNodeWithText("Mostrar página inteira").performClick()
        compose.onNodeWithContentDescription("Página 2 inteira com destaque nos trechos confirmados").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, openedPage) }
    }

    private fun content(
        state: ExamScreenState,
        select: () -> Unit = {},
        cancel: () -> Unit = {},
        largeText: Boolean = false,
        preview: suspend (String, Int) -> Bitmap = { _, _ -> error("unexpected preview") },
    ) = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 1.8f else 1f)) {
            ExamesTheme { ExamScreen(state, select, cancel, discard = {}, preview = preview) }
        }
    }
}
