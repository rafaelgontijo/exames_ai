package br.com.exames.featurehistory

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import br.com.exames.coreui.ExamesTheme
import br.com.exames.domain.model.Document
import br.com.exames.domain.validation.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class HistoryControlsUiTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()
    private val runId = "a".repeat(64)
    private val doc = Document("doc1", "artifact:original", "h".repeat(64), "application/pdf", Instant.EPOCH, 1)
    private fun run(basis: OutputBasis = OutputBasis.AUTOMATIC, id: String = runId, parent: String? = null) =
        ProcessingRun(id, "doc1", "h".repeat(64), null, ExtractionMode.BASELINE, "e", "p", "r", RunState.COMPLETE, "2026-01-01T00:00:00Z", "d", "14",
            outputBasis = basis, parentRunId = parent)
    private fun show(state: HistoryUiState) = compose.setContent {
        ExamesTheme { HistoryContent(state, onBack = { calls += "back" }, onRun = { calls += "run:$it" }, onReload = { calls += "reload" },
            onSelectDocument = { calls += "select:$it" }, onCloseDocument = { calls += "close" }, onProcess = { calls += "process:$it" },
            onProcessAssisted = { calls += "assisted:$it" }, onDelete = { calls += "delete:$it" }) }
    }
    private fun click(text: String) = compose.onNodeWithText(text).performScrollTo().performClick()

    @Test fun listShowsDocumentsAndOpensTheirRuns() {
        show(HistoryUiState(documents = listOf(doc), loading = false))
        compose.onNodeWithTag("document-doc1").assertExists()
        compose.onNodeWithText("Abrir execuções").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("select:doc1"), calls)
    }
    @Test fun deletionRequiresExplicitConfirmationAndCancelKeepsTheDocument() {
        show(HistoryUiState(documents = listOf(doc), loading = false))
        click("Excluir documento")
        compose.onNodeWithText("Excluir documento?").assertExists()
        compose.onNodeWithText("Esta ação não pode ser desfeita.", substring = true).assertExists()
        compose.onNodeWithText("Manter documento").performClick()
        assertEquals(emptyList<String>(), calls)
        click("Excluir documento"); compose.onNodeWithText("Excluir definitivamente").performClick()
        assertEquals(listOf("delete:doc1"), calls)
    }
    @Test fun deleteIsDisabledWhileAnActionIsBusy() {
        show(HistoryUiState(documents = listOf(doc), loading = false, actionBusy = true))
        compose.onNodeWithText("Excluir documento").performScrollTo().assertIsNotEnabled()
    }
    @Test fun selectedDocumentOffersRunsVersionsNewRunsAndDeletionOfDerivatives() {
        val corrected = run(OutputBasis.CORRECTED, "b".repeat(64), runId)
        show(HistoryUiState(documents = listOf(doc), selectedDocumentId = "doc1", runs = listOf(run(), corrected), loading = false))
        compose.onNodeWithText("Reavaliação após correções; sem nova extração").assertExists()
        compose.onNodeWithText("Nova execução vinculada à anterior").assertExists()
        click("Nova execução sem IA"); click("Nova execução assistida")
        compose.onNode(hasText("Abrir resultado e fonte") and hasAnyAncestor(hasTestTag("run-$runId"))).performScrollTo().performClick()
        compose.onNode(hasText("Abrir resultado e fonte") and hasAnyAncestor(hasTestTag("run-${"b".repeat(64)}"))).performScrollTo().performClick()
        assertEquals(listOf("process:doc1", "assisted:doc1", "run:$runId", "run:${"b".repeat(64)}"), calls)
        click("Excluir documento e derivados"); compose.onNodeWithText("Excluir definitivamente").performClick()
        assertEquals("delete:doc1", calls.last())
    }
    @Test fun emptyHistoryStatesThereAreNoDocuments() {
        show(HistoryUiState(loading = false))
        compose.onNodeWithText("Nenhum documento salvo.").assertExists()
    }
    @Test fun unavailableHistoryNeverClaimsEmptyAndCanRetry() {
        show(HistoryUiState(loading = false, unavailable = true, message = "Não foi possível acessar o armazenamento protegido."))
        compose.onAllNodes(hasText("Nenhum documento salvo.")).assertCountEquals(0)
        click("Tentar abrir histórico novamente")
        assertEquals(listOf("reload"), calls)
    }
}
