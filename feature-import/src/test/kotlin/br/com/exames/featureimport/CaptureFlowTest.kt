@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package br.com.exames.featureimport

import androidx.lifecycle.ViewModelStore
import br.com.exames.datadocuments.CaptureRejected
import br.com.exames.datadocuments.CapturedPage
import br.com.exames.domain.model.Document
import br.com.exames.domain.usecase.*
import br.com.exames.domain.validation.*
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

/** Camera flow state machine with synthetic pages: no Android camera, files or real pixels involved. */
class CaptureFlowTest {
    private class FakePage(bytes: Int = 16) : CapturedPage {
        override val jpeg = ByteArray(bytes) { 7 }
        override val width = 10; override val height = 20
        var wiped = 0
        override fun wipe() { wiped++; jpeg.fill(0) }
    }
    private val store = ViewModelStore()
    private val intake = mockk<DocumentIntake>(relaxed = true)
    private val starter = mockk<StartProcessingRun>()
    private val controls = mockk<ManageProcessingRuns>(relaxed = true)
    private val repository = mockk<ValidationRepository>()
    private val corpus = mockk<CorpusIntake>(relaxed = true)
    private val preparation = mockk<br.com.exames.domain.inference.PrepareLocalInference>(relaxed = true)
    private val captureIntake = mockk<CaptureIntake>()
    private val document = Document("doc", "artifact:original", "c".repeat(64), "image/jpeg", Instant.EPOCH, null)
    private val runs = MutableStateFlow<List<ProcessingRun>>(emptyList())
    private val checkpoints = MutableStateFlow<List<PageCheckpoint>>(emptyList())

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        coEvery { starter.start("doc", any(), any()) } answers {
            val run = ProcessingRun(secondArg(), "doc", document.hash, null, thirdArg(), "e", "p", "r", RunState.QUEUED, Instant.EPOCH.toString(), "d", "14")
            runs.value = listOf(run); StartedProcessingRun(run, true)
        }
        every { repository.observeRuns("doc") } returns runs
        every { repository.observeCheckpoints(any()) } returns checkpoints
        coEvery { repository.find("doc") } returns document
    }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private fun model() = ImportViewModel(intake, starter, controls, repository, corpus, preparation, captureIntake).also { store.put("m", it) }
    private fun source(vararg pages: CapturedPage): PageCaptureSource {
        val queue = pages.toMutableList()
        return PageCaptureSource { queue.removeAt(0) }
    }

    @Test fun permissionDeniedCreatesNothingAndKeepsThePickerAvailable() = runTest {
        val m = model(); m.onCameraPermission(false); advanceUntilIdle()
        assertEquals(CapturePhase.NONE, m.state.value.capture)
        assertTrue(m.state.value.message!!.contains("selecionar um PDF ou imagem"))
        assertFalse(m.state.value.importing)
        coVerify(exactly = 0) { captureIntake.import(any()) }; coVerify(exactly = 0) { starter.start(any(), any(), any()) }
    }

    @Test fun captureReviewAndUseImportsThenStartsTheSelectedMode() = runTest {
        val page = FakePage(); coEvery { captureIntake.import(page) } returns document
        val m = model(); m.selectMode(ExtractionMode.BASELINE); m.onCameraPermission(true)
        m.attachCaptureSource(source(page)); m.takePhoto(); advanceUntilIdle()
        assertEquals(CapturePhase.REVIEW, m.state.value.capture)
        assertArrayEquals(ByteArray(16) { 7 }, m.previewBytes())
        coVerify(exactly = 0) { captureIntake.import(any()) } // nothing is persisted before "usar"
        m.useCapture(); advanceUntilIdle()
        coVerify(exactly = 1) { captureIntake.import(page) }
        coVerify(exactly = 1) { starter.start("doc", any(), ExtractionMode.BASELINE) }
        assertEquals("doc", m.state.value.documentId); assertEquals(CapturePhase.NONE, m.state.value.capture)
        assertEquals(1, page.wiped); assertNull(m.previewBytes())
        assertNull("no clinical-value confirmation or consent state", m.state.value.message)
    }

    @Test fun retakeDiscardsTheFirstCandidateAndOnlyTheSecondIsEverImported() = runTest {
        val first = FakePage(); val second = FakePage(); coEvery { captureIntake.import(second) } returns document
        val m = model(); m.onCameraPermission(true); m.attachCaptureSource(source(first, second))
        m.takePhoto(); advanceUntilIdle(); m.retake(); assertEquals(CapturePhase.CAMERA, m.state.value.capture)
        assertEquals(1, first.wiped); assertNull(m.previewBytes())
        m.takePhoto(); advanceUntilIdle(); m.useCapture(); advanceUntilIdle()
        coVerify(exactly = 0) { captureIntake.import(first) }; coVerify(exactly = 1) { captureIntake.import(second) }
    }

    @Test fun cancelWipesTheCandidateAndPublishesNothing() = runTest {
        val page = FakePage(); val m = model(); m.onCameraPermission(true); m.attachCaptureSource(source(page))
        m.takePhoto(); advanceUntilIdle(); m.cancelCapture()
        assertEquals(CapturePhase.NONE, m.state.value.capture); assertEquals(1, page.wiped); assertNull(m.previewBytes())
        m.useCapture(); advanceUntilIdle()
        coVerify(exactly = 0) { captureIntake.import(any()) }
    }

    @Test fun lateCaptureAfterLeavingTheScreenIsWipedAndNeverShownOrImported() = runTest {
        val gate = CompletableDeferred<CapturedPage>(); val late = FakePage()
        val m = model(); m.onCameraPermission(true); m.attachCaptureSource(PageCaptureSource { gate.await() })
        m.takePhoto(); advanceUntilIdle()
        m.endCapture(); gate.complete(late); advanceUntilIdle()
        assertEquals(CapturePhase.NONE, m.state.value.capture); assertNull(m.previewBytes())
        coVerify(exactly = 0) { captureIntake.import(any()) }
        // A new session afterwards must not receive the previous result either.
        m.onCameraPermission(true); advanceUntilIdle()
        assertEquals(CapturePhase.CAMERA, m.state.value.capture); assertNull(m.previewBytes())
    }

    @Test fun lateCaptureAfterRetakeOrANewSessionIsDropped() = runTest {
        val gate = CompletableDeferred<CapturedPage>(); val late = FakePage()
        val m = model(); m.onCameraPermission(true); m.attachCaptureSource(PageCaptureSource { gate.await() })
        m.takePhoto(); advanceUntilIdle()
        m.beginCapture() // a new session starts while the old capture is still pending
        gate.complete(late); advanceUntilIdle()
        assertEquals(CapturePhase.CAMERA, m.state.value.capture); assertNull(m.previewBytes())
    }

    @Test fun lateCaptureAfterPermissionLossIsDropped() = runTest {
        val gate = CompletableDeferred<CapturedPage>(); val late = FakePage()
        val m = model(); m.onCameraPermission(true); m.attachCaptureSource(PageCaptureSource { gate.await() })
        m.takePhoto(); advanceUntilIdle()
        m.endCapture("A permissão da câmera foi revogada. A foto foi descartada."); gate.complete(late); advanceUntilIdle()
        assertEquals(CapturePhase.NONE, m.state.value.capture); assertNull(m.previewBytes())
        assertTrue(m.state.value.message!!.contains("revogada"))
        coVerify(exactly = 0) { captureIntake.import(any()) }
    }

    @Test fun permissionRevokedWhileReviewingDiscardsTheCandidate() = runTest {
        val page = FakePage(); val m = model(); m.onCameraPermission(true); m.attachCaptureSource(source(page))
        m.takePhoto(); advanceUntilIdle(); assertEquals(CapturePhase.REVIEW, m.state.value.capture)
        m.endCapture("A permissão da câmera foi revogada. A foto foi descartada.")
        assertEquals(1, page.wiped); assertNull(m.previewBytes()); assertEquals(CapturePhase.NONE, m.state.value.capture)
    }

    @Test fun processDeathBeforeConfirmationDiscardsThePreview() = runTest {
        val page = FakePage(); val m = model(); m.onCameraPermission(true); m.attachCaptureSource(source(page))
        m.takePhoto(); advanceUntilIdle()
        store.clear() // ViewModel is cleared as it is when the process or session ends; nothing was saved anywhere
        assertEquals(1, page.wiped)
        coVerify(exactly = 0) { captureIntake.import(any()) }
    }

    @Test fun rejectedCaptureShowsAStableMessageAndStaysInTheCameraWithoutLeakingDetails() = runTest {
        val m = model(); m.onCameraPermission(true)
        m.attachCaptureSource(PageCaptureSource { throw CaptureRejected("CAPTURE_TOO_LARGE") }); m.takePhoto(); advanceUntilIdle()
        assertEquals(CapturePhase.CAMERA, m.state.value.capture); assertTrue(m.state.value.message!!.contains("20 MiB"))
        m.attachCaptureSource(PageCaptureSource { error("PRIVATE_CANARY_BODY") }); m.takePhoto(); advanceUntilIdle()
        assertFalse(m.state.value.message!!.contains("PRIVATE_CANARY_BODY"))
        coVerify(exactly = 0) { captureIntake.import(any()) }
    }

    @Test fun missingCameraSourceIsRecoverable() = runTest {
        val m = model(); m.onCameraPermission(true); m.takePhoto(); advanceUntilIdle()
        assertTrue(m.state.value.message!!.contains("seletor")); assertEquals(CapturePhase.CAMERA, m.state.value.capture)
        m.onCameraFailure("CAMERA_UNAVAILABLE"); assertEquals(CapturePhase.NONE, m.state.value.capture)
    }

    @Test fun persistenceFailureAfterConfirmationReportsNothingCreatedAndStillWipesThePage() = runTest {
        val page = FakePage(); coEvery { captureIntake.import(page) } throws IllegalStateException("PRIVATE_DB_BODY")
        val m = model(); m.onCameraPermission(true); m.attachCaptureSource(source(page)); m.takePhoto(); advanceUntilIdle()
        m.useCapture(); advanceUntilIdle()
        assertNull(m.state.value.documentId); assertFalse(m.state.value.importing)
        assertTrue(m.state.value.message!!.contains("Nada foi criado")); assertFalse(m.state.value.message!!.contains("PRIVATE_DB_BODY"))
        assertEquals(1, page.wiped)
        coVerify(exactly = 0) { starter.start(any(), any(), any()) }
    }

    @Test fun useIsIdempotentAndIgnoredOutsideReview() = runTest {
        val page = FakePage(); coEvery { captureIntake.import(page) } returns document
        val m = model(); m.useCapture(); advanceUntilIdle()
        m.onCameraPermission(true); m.attachCaptureSource(source(page)); m.takePhoto(); advanceUntilIdle()
        m.useCapture(); m.useCapture(); advanceUntilIdle()
        coVerify(exactly = 1) { captureIntake.import(page) }
    }

    @Test fun assistedModeRequiresAPreparedModelBeforeTheCameraOpens() = runTest {
        val m = model(); m.selectMode(ExtractionMode.ASSISTED)
        m.onCameraPermission(true); assertEquals(CapturePhase.NONE, m.state.value.capture)
    }
}
