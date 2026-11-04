@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package br.com.exames.featureimport

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import br.com.exames.domain.model.Document
import br.com.exames.domain.usecase.*
import br.com.exames.domain.validation.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

class ImportViewModelTest {
    private val store = ViewModelStore()
    private val intake = mockk<DocumentIntake>()
    private val starter = mockk<StartProcessingRun>()
    private val controls = mockk<ManageProcessingRuns>()
    private val repository = mockk<ValidationRepository>()
    private val uri = mockk<Uri>()
    private val document = Document("doc", "artifact:original", "b".repeat(64), "application/pdf", Instant.EPOCH, 2)
    private val runs = MutableStateFlow<List<ProcessingRun>>(emptyList())
    private val checkpoints = MutableStateFlow<List<PageCheckpoint>>(emptyList())
    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        coEvery { intake.import(uri) } returns document
        coEvery { starter.start("doc", any(), any()) } answers {
            val run = ProcessingRun(secondArg(), "doc", document.hash, null, ExtractionMode.BASELINE, "extractor", "parser", "rule",
                RunState.QUEUED, Instant.EPOCH.toString(), "synthetic", "36")
            runs.value = listOf(run); StartedProcessingRun(run, true)
        }
        every { repository.observeRuns("doc") } returns runs
        every { repository.observeCheckpoints(any()) } returns checkpoints
        coEvery { repository.find("doc") } returns document
    }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private val corpus = mockk<CorpusIntake>()
    private val preparation = mockk<br.com.exames.domain.inference.PrepareLocalInference>()
    private fun model() = ImportViewModel(intake, starter, controls, repository, corpus, preparation).also { store.put("model", it) }

    @Test fun rapidSelectionCreatesOnePrivateImportAndProgressTracksPartialPages() = runTest {
        val model = model()
        model.select(uri); model.select(uri)
        assertTrue(model.state.value.importing)
        advanceUntilIdle()
        coVerify(exactly = 1) { intake.import(uri) }
        val id = model.state.value.runId!!
        assertTrue(ProcessingRunIds.isOpaque(id))
        checkpoints.value = listOf(PageCheckpoint(id, 1, ExtractionMode.BASELINE, PageState.COMPLETE, 1),
            PageCheckpoint(id, 2, ExtractionMode.BASELINE, PageState.FAILED, 1))
        runs.value = listOf(runs.value.single().copy(state = RunState.PARTIAL))
        advanceUntilIdle()
        assertEquals(RunState.PARTIAL, model.state.value.runState)
        assertEquals(1, model.state.value.completedPages); assertEquals(1, model.state.value.failedPages)
        assertEquals(2, model.state.value.totalPages)
    }

    @Test fun unavailableSchedulingRetainsSavedIdentityAndReschedulesWithoutReimport() = runTest {
        coEvery { starter.start("doc", any(), any()) } answers {
            val run = ProcessingRun(secondArg(), "doc", document.hash, null, ExtractionMode.BASELINE, "extractor", "parser", "rule",
                RunState.QUEUED, Instant.EPOCH.toString(), "synthetic", "36")
            runs.value = listOf(run); StartedProcessingRun(run, false)
        }
        coEvery { controls.reconcile() } returns ReconciliationCounts(1, 0, 0)
        val model = model(); model.select(uri); advanceUntilIdle()
        val id = model.state.value.runId
        assertNotNull(model.state.value.message)
        model.reschedule(); advanceUntilIdle()
        assertEquals(id, model.state.value.runId)
        coVerify(exactly = 1) { intake.import(uri) }
        coVerify(exactly = 1) { controls.reconcile() }
    }

    @Test fun failedRunCreationRetainsImportedDocumentAndExceptionBodyIsNotExposed() = runTest {
        coEvery { starter.start("doc", any(), any()) } throws IllegalStateException("PRIVATE_EXCEPTION_CANARY")
        val model = model(); model.select(uri); advanceUntilIdle()
        assertEquals("doc", model.state.value.documentId); assertNull(model.state.value.runId)
        assertFalse(model.state.value.importing)
        assertFalse(model.state.value.toString().contains("PRIVATE_EXCEPTION_CANARY"))
    }

    @Test fun downstreamProtectedReadFailureAndDeletionClearVisibleExecutionIdentity() = runTest {
        val model = model(); model.select(uri); advanceUntilIdle()
        coEvery { repository.find("doc") } throws IllegalStateException("PRIVATE_STORAGE_BODY")
        runs.value = listOf(runs.value.single().copy(state = RunState.RUNNING))
        advanceUntilIdle()
        assertNull(model.state.value.runId); assertFalse(model.state.value.toString().contains("PRIVATE_STORAGE_BODY"))
        coEvery { repository.find("doc") } returns document
        model.select(uri); advanceUntilIdle()
        runs.value = emptyList(); advanceUntilIdle()
        assertNull(model.state.value.documentId); assertNull(model.state.value.runId)
    }
    @Test fun packageImportSchedulesEachValidatedCaseWithoutSendingTruthToTheProcessor() = runTest {
        coEvery { corpus.import(uri, any()) } coAnswers {
            val callback = secondArg<suspend (Document) -> Unit>(); callback(document); callback(document); 2
        }
        val model=model();model.selectPackage(uri);model.selectPackage(uri);advanceUntilIdle()
        assertFalse(model.state.value.importing);assertEquals(2,model.state.value.importedCases)
        coVerify(exactly=1) { corpus.import(uri,any()) }
        coVerify(exactly=2) { starter.start("doc",any(),any()) }
        coVerify(exactly=0) { intake.import(any()); repository.groundTruth(any()) }
    }
    @Test fun interruptedPackageKeepsCompletedCasesVisibleWithoutClaimingCompletion() = runTest {
        coEvery { corpus.import(uri, any()) } coAnswers {
            secondArg<suspend (Document) -> Unit>()(document);throw IllegalArgumentException("PRIVATE_BODY")
        }
        val model=model();model.selectPackage(uri);advanceUntilIdle()
        assertEquals(1,model.state.value.importedCases);assertFalse(model.state.value.importing)
        assertTrue(model.state.value.message!!.contains("interrompida"));assertFalse(model.state.value.message!!.contains("PRIVATE_BODY"))
    }

    @Test fun assistedModeWaitsForVerifiedPreparationBeforeAcceptingAnySource() = runTest {
        val pending=kotlinx.coroutines.CompletableDeferred<br.com.exames.domain.inference.ModelReadiness>()
        coEvery { preparation.prepare() } coAnswers { pending.await() }
        val model=model();model.selectMode(ExtractionMode.ASSISTED);runCurrent()
        model.select(uri);model.selectPackage(uri);advanceUntilIdle()
        coVerify(exactly=0) { intake.import(any());corpus.import(any(),any()) }
        assertEquals(br.com.exames.domain.inference.ModelReadiness.PREPARING,model.state.value.modelReadiness)
        pending.complete(br.com.exames.domain.inference.ModelReadiness.READY);advanceUntilIdle()
        model.select(uri);advanceUntilIdle()
        coVerify(exactly=1) { starter.start("doc",any(),ExtractionMode.ASSISTED) }
    }
    @Test fun unsupportedOrCorruptModelLeavesBaselineAvailableWithoutSilentModeSwitch() = runTest {
        coEvery { preparation.prepare() } returns br.com.exames.domain.inference.ModelReadiness.CORRUPT
        val model=model();model.selectMode(ExtractionMode.ASSISTED);advanceUntilIdle();model.select(uri);advanceUntilIdle()
        assertEquals(ExtractionMode.ASSISTED,model.state.value.mode)
        coVerify(exactly=0) { intake.import(any()) }
        model.selectMode(ExtractionMode.BASELINE);model.select(uri);advanceUntilIdle()
        coVerify(exactly=1) { starter.start("doc",any(),ExtractionMode.BASELINE) }
    }

}
