package br.com.exames.featureimport

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import br.com.exames.coreui.ExamesTheme
import br.com.exames.domain.validation.ExtractionMode
import br.com.exames.domain.validation.RunState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImportControlsUiTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()
    private val runId = "a".repeat(64)
    private fun show(state: ImportUiState) = compose.setContent {
        ExamesTheme { ImportContent(state, onPickDocument = { calls += "pick" }, onPickPackage = { calls += "package" },
            onSelectMode = { calls += "mode:$it" }, onRecheckModel = { calls += "recheck" }, onCancel = { calls += "cancel" },
            onReschedule = { calls += "reschedule" }, onRetry = { calls += "retry" }, onResults = { calls += "results:$it" }, onHistory = { calls += "history" },
            onPhotograph = { calls += "photograph" }, onTakePhoto = { calls += "take" }, onUsePhoto = { calls += "use" }, onRetake = { calls += "retake" },
            onCancelCapture = { calls += "cancelCapture" },
            cameraSlot = { androidx.compose.material3.Text("SLOT_CAMERA", modifier = androidx.compose.ui.Modifier.testTag("camera-preview")) },
            reviewSlot = { androidx.compose.material3.Text("SLOT_REVIEW", modifier = androidx.compose.ui.Modifier.testTag("capture-preview")) }) }
    }
    private fun click(text: String) = compose.onNodeWithText(text).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()

    @Test fun runningProcessingCanBeCancelledAndOpenedForReview() {
        show(ImportUiState(runId = runId, runState = RunState.RUNNING, completedPages = 1, totalPages = 3))
        compose.onNodeWithText("Processando documento").assertExists()
        click("Cancelar processamento"); click("Revisar resultados")
        assertEquals(listOf("cancel", "results:$runId"), calls)
    }
    @Test fun cancelIsDisabledWhileAnotherActionIsBusy() {
        show(ImportUiState(runId = runId, runState = RunState.RUNNING, actionBusy = true))
        compose.onNodeWithText("Cancelar processamento").performScrollTo().assertIsNotEnabled()
    }
    @Test fun queuedRunOffersRescheduleAndStillAllowsCancel() {
        show(ImportUiState(runId = runId, runState = RunState.QUEUED))
        click("Tentar agendamento"); click("Cancelar processamento")
        assertEquals(listOf("reschedule", "cancel"), calls)
    }
    @Test fun partialRunReprocessesAsANewRunWithoutOfferingToDeleteTheOriginal() {
        show(ImportUiState(runId = runId, runState = RunState.PARTIAL))
        click("Reprocessar em nova execução")
        assertEquals(listOf("retry"), calls)
        compose.onAllNodes(hasText("Excluir", substring = true)).assertCountEquals(0)
    }
    @Test fun failedRunReprocesses() { show(ImportUiState(runId = runId, runState = RunState.FAILED)); click("Reprocessar em nova execução"); assertEquals(listOf("retry"), calls) }
    @Test fun cancelledRunReprocesses() { show(ImportUiState(runId = runId, runState = RunState.CANCELLED)); click("Reprocessar em nova execução"); assertEquals(listOf("retry"), calls) }
    @Test fun completedRunOffersNoCancelOrReprocess() {
        show(ImportUiState(runId = runId, runState = RunState.COMPLETE))
        compose.onAllNodes(hasText("Cancelar processamento")).assertCountEquals(0)
        compose.onAllNodes(hasText("Reprocessar em nova execução")).assertCountEquals(0)
    }
    @Test fun sourceChoiceStatesLocalProcessingAndKeepsHistoryAndModeControls() {
        show(ImportUiState())
        compose.onNodeWithText("O processamento acontece neste aparelho", substring = true).assertExists()
        click("Selecionar documento"); click("Importar pacote do corpus"); click("Modo assistido local"); click("Histórico local")
        assertEquals(listOf("pick", "package", "mode:${ExtractionMode.ASSISTED}", "history"), calls)
    }
    @Test fun selectingTheSourceDoesNotAskForClinicalConfirmationOrConsent() {
        show(ImportUiState())
        for (gate in listOf("Autorizo", "Confirmo", "Li e concordo", "consentimento")) compose.onAllNodes(hasText(gate, substring = true, ignoreCase = true)).assertCountEquals(0)
    }

    @Test fun photographIsOfferedNextToThePickerAndRequestsTheCameraOnlyWhenChosen() {
        show(ImportUiState())
        compose.onNodeWithText("Selecionar documento").assertExists()
        compose.onAllNodes(hasTestTag("capture-panel")).assertCountEquals(0)
        assertTrue("nothing requested before the user chooses", calls.isEmpty())
        click("Fotografar uma página")
        assertEquals(listOf("photograph"), calls)
    }
    @Test fun permissionDeniedLeavesThePickerAvailableAndShowsARecoverableMessage() {
        show(ImportUiState(message = "A câmera não foi autorizada. Nenhum documento foi criado; você ainda pode selecionar um PDF ou imagem."))
        compose.onNodeWithText("A câmera não foi autorizada", substring = true).performScrollTo().assertExists()
        click("Selecionar documento"); click("Fotografar uma página")
        assertEquals(listOf("pick", "photograph"), calls)
    }
    @Test fun cameraPhaseGuidesTheShotAndAllowsTakingOrCancelling() {
        show(ImportUiState(capture = CapturePhase.CAMERA))
        compose.onNodeWithTag("camera-preview").performScrollTo().assertExists()
        compose.onNodeWithText("Evite sombra e reflexo", substring = true).assertExists()
        click("Tirar foto"); click("Cancelar foto")
        assertEquals(listOf("take", "cancelCapture"), calls)
        compose.onNodeWithText("Selecionar documento").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Importar pacote do corpus").performScrollTo().assertIsNotEnabled()
    }
    @Test fun reviewPhaseOffersUseRetakeAndCancelAndExplainsThatUseOnlyChoosesTheImage() {
        show(ImportUiState(capture = CapturePhase.REVIEW))
        compose.onNodeWithTag("capture-preview").performScrollTo().assertExists()
        compose.onNodeWithText("apenas escolhe esta imagem", substring = true).assertExists()
        click("Usar esta foto"); click("Refazer"); click("Cancelar foto")
        assertEquals(listOf("use", "retake", "cancelCapture"), calls)
        for (gate in listOf("Autorizo", "Confirmo os valores", "Li e concordo")) compose.onAllNodes(hasText(gate, substring = true, ignoreCase = true)).assertCountEquals(0)
    }
    @Test fun importingAfterConfirmationShowsProtectedCopyProgressWithoutCameraControls() {
        show(ImportUiState(importing = true))
        compose.onNodeWithText("Criando cópia privada protegida…").performScrollTo().assertExists()
        compose.onAllNodes(hasText("Tirar foto")).assertCountEquals(0)
        compose.onNodeWithText("Fotografar uma página").performScrollTo().assertIsNotEnabled()
    }
}
