@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package br.com.exames.featureresults

import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

class ResultsViewModelTest {
    private val repository = mockk<ValidationRepository>()
    private val preview = mockk<SourcePreviewLoader>()
    private val store = ViewModelStore()
    private val id = "a".repeat(64)
    private val run = ProcessingRun(id, "doc", "b".repeat(64), null, ExtractionMode.BASELINE,
        "extractor", "parser", "rule", RunState.COMPLETE, Instant.EPOCH.toString(), "synthetic", "36")
    private val runs = MutableStateFlow(listOf(run))
    private val pages = MutableStateFlow(listOf(PageCheckpoint(id, 1, run.mode, PageState.COMPLETE, 1)))
    private val observation = Observation("obs", "doc", "Glicose", "15", "u/L", 1, "SYNTHETIC_PRIVATE_TEXT")
    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        coEvery { repository.findRun(id) } returns run
        coEvery { repository.find("doc") } returns Document("doc", "artifact:original", run.documentSha256, "application/pdf", Instant.EPOCH, 1)
        every { repository.observeRuns("doc") } returns runs
        every { repository.observeCheckpoints(id) } returns pages
        coEvery { repository.observations(id) } returns listOf(observation)
        coEvery { repository.assessments(id) } returns emptyList()
        coEvery { repository.corrections(any()) } returns emptyList()
        coEvery { repository.groundTruth(any()) } returns emptyList()
        coEvery { repository.comparisons(any()) } returns emptyList()
        coEvery { repository.reference(any()) } returns null
    }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private val actions = mockk<ReviewActions>()
    private fun model() = ResultsViewModel(repository, preview, actions).also { store.put("model", it) }

    @Test fun deletionDuringPreviewCannotPublishLateBitmapOrKeepClinicalRows() = runTest {
        val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Bitmap>()
        coEvery { preview.load("doc", 1) } coAnswers { entered.complete(Unit); released.await() }
        val model = model(); model.open(id); advanceUntilIdle()
        assertEquals(1, model.state.value.rows.size)
        model.showPage(1); advanceUntilIdle(); entered.await()
        coEvery { repository.findRun(id) } returns null
        runs.value = emptyList(); advanceUntilIdle()
        released.complete(mockk()); advanceUntilIdle()
        assertTrue(model.state.value.unavailable); assertTrue(model.state.value.rows.isEmpty())
        assertNull(model.state.value.preview)
    }

    @Test fun invalidRoutesAndProtectedReadErrorsDoNotExposeSourceOrKeepStaleResults() = runTest {
        val model = model(); model.open("content://PRIVATE_URI"); advanceUntilIdle()
        assertTrue(model.state.value.unavailable); coVerify(exactly = 0) { repository.findRun(any()) }
        model.open(id); advanceUntilIdle(); assertEquals(1, model.state.value.rows.size)
        coEvery { repository.observations(id) } throws IllegalStateException("PRIVATE_EXCEPTION_BODY")
        pages.value = pages.value + PageCheckpoint(id, 2, run.mode, PageState.FAILED, 1)
        advanceUntilIdle()
        assertTrue(model.state.value.unavailable); assertTrue(model.state.value.rows.isEmpty())
        assertFalse(model.state.value.toString().contains("PRIVATE_EXCEPTION_BODY"))
    }

    @Test fun fieldSelectionKeepsAllCompetingReferencesAndGroundTruthSeparateWithoutChangingAssessment() = runTest {
        val evidence = listOf("first", "second").mapIndexed { index, name -> ExtractedFieldEvidence("referenceRaw", name,
            SourceRegion(1, 10.0, 10.0 + index*20, 50.0, 20.0 + index*20, 600.0, 800.0), name, "SYNTHETIC", "1") }
        val assessment = Assessment("obs", AssessmentStatus.NEEDS_REVIEW, ReasonCode.AMBIGUOUS_INPUT, Instant.EPOCH, "rule")
        val original = observation.copy(referenceRaw = "first / second", fieldEvidence = evidence, ambiguities = setOf(Ambiguity.REFERENCE_ASSOCIATION))
        coEvery { repository.observations(id) } returns listOf(original)
        coEvery { repository.assessments(id) } returns listOf(assessment)
        val expected = ExpectedObservation("expected", Analyte.GLUCOSE, "Glicose", "15", "u/L", "expected reference",
            "15", null, null, ExpectedStatus.NEEDS_REVIEW, "AMBIGUOUS_INPUT", true, listOf(evidence.first().copy(raw = "expected reference")))
        val truth = GroundTruthVersion(caseId = "case", version = "2", reviewState = ReviewState.REVIEWED,
            reviewerId = "synthetic", reviewedAt = Instant.EPOCH.toString(), observations = listOf(expected))
        coEvery { repository.groundTruth("doc") } returns listOf(GroundTruthSnapshot("c".repeat(64), truth))
        coEvery { preview.load("doc", 1) } returns mockk()
        val model = model(); model.open(id); advanceUntilIdle()
        model.showEvidence("obs", "referenceRaw", 1); advanceUntilIdle()
        assertEquals(evidence, model.state.value.selectedEvidence!!.evidence)
        assertEquals(EvidenceOrigin.EXTRACTED, model.state.value.selectedEvidence!!.origin)
        model.showGroundTruthEvidence("case", "2", "expected", "referenceRaw", 1); advanceUntilIdle()
        assertEquals("expected reference", model.state.value.selectedEvidence!!.raw)
        assertEquals(EvidenceOrigin.GROUND_TRUTH, model.state.value.selectedEvidence!!.origin)
        assertEquals(assessment, model.state.value.rows.single().assessment)
        assertEquals(original, model.state.value.rows.single().observation)
        model.closePreview(); assertNull(model.state.value.selectedEvidence)
    }

    @Test fun switchingFieldsDiscardsEvenNonCooperativeLateFailuresFromTheSamePage() = runTest {
        val fields = listOf("resultRaw", "unitRaw").map { name -> ExtractedFieldEvidence(name, "synthetic",
            SourceRegion(1, 10.0, 10.0, 50.0, 20.0, 600.0, 800.0), name, "SYNTHETIC", "1") }
        coEvery { repository.observations(id) } returns listOf(observation.copy(fieldEvidence = fields))
        val release = CompletableDeferred<Unit>(); var calls = 0
        val bitmap = mockk<Bitmap>()
        coEvery { preview.load("doc", 1) } coAnswers {
            if (calls++ == 0) withContext(NonCancellable) { release.await(); error("PRIVATE_STALE_FAILURE") }
            else bitmap
        }
        val model = model(); model.open(id); advanceUntilIdle()
        model.showEvidence("obs", "resultRaw", 1); advanceUntilIdle()
        model.showEvidence("obs", "unitRaw", 1); advanceUntilIdle()
        release.complete(Unit); advanceUntilIdle()
        assertSame(bitmap, model.state.value.preview)
        assertEquals("unitRaw", model.state.value.selectedEvidence!!.evidence.single().field)
        assertNull(model.state.value.message)
        model.showPage(2); advanceUntilIdle()
        assertNull(model.state.value.selectedEvidence); assertNull(model.state.value.preview)
        coVerify(exactly = 0) { preview.load("doc", 2) }
    }
    @Test fun legacyReferenceStoredSeparatelyIsReopenedWithoutInventingFieldRegions() = runTest {
        val reference = ReferenceRange("obs", "10 a 20 u/L", "u/L", 1)
        coEvery { repository.reference("obs") } returns reference
        val model = model(); model.open(id); advanceUntilIdle()
        assertEquals(reference, model.state.value.rows.single().reference)
        assertNull(model.state.value.rows.single().observation.referenceRaw)
        assertTrue(model.state.value.rows.single().observation.fieldEvidence.isEmpty())
        model.showEvidence("obs", "referenceRaw", 1); advanceUntilIdle()
        coVerify(exactly = 0) { preview.load(any(), any()) }
    }
    @Test fun correctionDraftShowsLatestAuditValueAndSaveNeverMutatesOriginalAssessment() = runTest {
        coEvery { repository.corrections("obs") } returns listOf(ManualCorrection("prior", "obs", CorrectionField.RESULT, "15", "16", Instant.EPOCH))
        coEvery { actions.correct(id, "obs", CorrectionField.RESULT, "16", "25", any()) } just Runs
        val model = model(); model.open(id); advanceUntilIdle()
        model.editCorrection("obs", CorrectionField.RESULT)
        assertEquals("16", (model.state.value.draft as ReviewDraft.Correction).old)
        model.updateDraft(androidx.compose.ui.text.input.TextFieldValue("25"))
        model.saveDraft(); model.saveDraft(); advanceUntilIdle()
        coVerify(exactly = 1) { actions.correct(id, "obs", CorrectionField.RESULT, "16", "25", any()) }
        coVerify(exactly = 0) { actions.reevaluate(any(), any()); actions.revise(any(), any(), any()); actions.compare(any(), any()) }
        assertNull(model.state.value.draft)
        assertEquals(observation, model.state.value.rows.single().observation)
    }
    @Test fun failedEditRetainsMemoryDraftAndNeverExposesExceptionBody() = runTest {
        coEvery { actions.correct(any(), any(), any(), any(), any(), any()) } throws IllegalArgumentException("PRIVATE_EXCEPTION_BODY")
        val model = model(); model.open(id); advanceUntilIdle(); model.editCorrection("obs", CorrectionField.RESULT)
        model.updateDraft(androidx.compose.ui.text.input.TextFieldValue("25")); model.saveDraft(); advanceUntilIdle()
        assertFalse(model.state.value.reviewBusy)
        assertEquals("25", model.state.value.draft!!.text.text)
        assertFalse(model.state.value.message!!.contains("PRIVATE_EXCEPTION_BODY"))
        model.open(id); advanceUntilIdle(); assertNull(model.state.value.draft)
    }
    @Test fun activeRunCannotCreateEditsReevaluationOrReextraction() = runTest {
        val active = run.copy(state = RunState.RUNNING)
        coEvery { repository.findRun(id) } returns active; runs.value = listOf(active)
        val model = model(); model.open(id); advanceUntilIdle()
        model.editCorrection("obs", CorrectionField.RESULT); model.reevaluate(); model.reextract(); advanceUntilIdle()
        assertNull(model.state.value.draft)
        coVerify(exactly = 0) { actions.correct(any(), any(), any(), any(), any(), any()); actions.reevaluate(any(), any()); actions.reextract(any(), any(), any()) }
    }
    @Test fun groundTruthRevisionStartsPendingAndNeverReadsOrCopiesAutomaticValues() = runTest {
        val truth = GroundTruthVersion(caseId = "case", version = "1", reviewState = ReviewState.REVIEWED,
            reviewerId = "synthetic", reviewedAt = Instant.EPOCH.toString(), observations = emptyList())
        val hash = "c".repeat(64)
        coEvery { repository.groundTruth("doc") } returns listOf(GroundTruthSnapshot(hash, truth))
        coEvery { actions.revise("doc", hash, any()) } just Runs
        val model = model(); model.open(id); advanceUntilIdle(); model.editGroundTruth(hash)
        val draft = model.state.value.draft as ReviewDraft.Truth
        val decoded = ValidationJson.format.decodeFromString<GroundTruthVersion>(draft.text.text)
        assertEquals(ReviewState.PENDING, decoded.reviewState); assertNull(decoded.reviewerId); assertNull(decoded.reviewedAt)
        assertTrue(decoded.observations.isEmpty()); assertNotEquals(truth.version, decoded.version)
        model.saveDraft(); advanceUntilIdle()
        coVerify(exactly = 1) { actions.revise("doc", hash, draft.text.text) }
        coVerify(exactly = 0) { actions.correct(any(), any(), any(), any(), any(), any()); actions.reevaluate(any(), any()) }
        assertEquals(truth, model.state.value.groundTruth.single().groundTruth)
    }
    @Test fun comparisonsUseExplicitLoadedHashAndDoNotApplyCorrections() = runTest {
        val truth = GroundTruthVersion(caseId = "case", version = "1", reviewState = ReviewState.REVIEWED,
            reviewerId = "synthetic", reviewedAt = Instant.EPOCH.toString(), observations = emptyList())
        val hash = "c".repeat(64)
        coEvery { repository.groundTruth("doc") } returns listOf(GroundTruthSnapshot(hash, truth))
        coEvery { actions.compare(id, hash) } just Runs
        val model = model(); model.open(id); advanceUntilIdle()
        model.compare("d".repeat(64)); advanceUntilIdle(); coVerify(exactly = 0) { actions.compare(any(), any()) }
        model.compare(hash); advanceUntilIdle(); coVerify(exactly = 1) { actions.compare(id, hash) }
        coVerify(exactly = 0) { actions.reevaluate(any(), any()); actions.correct(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun pendingTruthCannotCreateComparisonsOrImplyAnAccuracyDenominator() = runTest {
        val truth = GroundTruthVersion(caseId = "case", version = "1", reviewState = ReviewState.PENDING,
            reviewerId = null, reviewedAt = null, observations = emptyList())
        val hash = "c".repeat(64)
        coEvery { repository.groundTruth("doc") } returns listOf(GroundTruthSnapshot(hash, truth))
        val model = model(); model.open(id); advanceUntilIdle(); model.compare(hash); advanceUntilIdle()
        assertTrue(model.state.value.message!!.contains("excluído dos denominadores"))
        coVerify(exactly = 0) { actions.compare(any(), any()) }
    }

    @Test fun lateReportCannotRepublishAfterTheSourceIsDeleted() = runTest {
        val case=CorpusCase("case","family",CorpusOrigin.SYNTHETIC,CorpusFormat.PDF_TEXT,CorpusPartition.DEVELOPMENT,
            "simple","1","documents/source.pdf",run.documentSha256,1,"truth/source.json","c".repeat(64))
        val truth=GroundTruthVersion(caseId="case",version="1",reviewState=ReviewState.PENDING,reviewerId=null,reviewedAt=null,observations=emptyList())
        coEvery { repository.find("doc") } returns Document("doc","artifact:original",run.documentSha256,"application/pdf",Instant.EPOCH,1,corpusCase=case)
        coEvery { repository.groundTruth("doc") } returns listOf(GroundTruthSnapshot("c".repeat(64),truth))
        val deferred=CompletableDeferred<LocalValidationReport>()
        coEvery { actions.report(id,"c".repeat(64)) } coAnswers { deferred.await() }
        val model=model();model.open(id);advanceUntilIdle();model.showReport("c".repeat(64));advanceUntilIdle()
        assertTrue(model.state.value.reviewBusy)
        coEvery { repository.findRun(id) } returns null;runs.value=emptyList();advanceUntilIdle()
        deferred.complete(LocalValidationReport(identities=emptyList(),rows=emptyList(),publicCoverageAvailable=false));advanceUntilIdle()
        assertTrue(model.state.value.unavailable);assertNull(model.state.value.report);assertTrue(model.state.value.rows.isEmpty())
    }

}
