package br.com.exames.domain.usecase

import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ReviewExtractionTest {
    private val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
    private val evaluator = DeterministicRangeEvaluator(clock)
    private val original = Observation("original", "document", "Glicose", "15", "mg/dL", 1, "synthetic-source",
        numericValue = "15".toBigDecimal(), referenceRaw = "10 a 20 mg/dL")
    private val reference = ReferenceRange(original.id, "10 a 20 mg/dL", "mg/dL", 1,
        "10".toBigDecimal(), "20".toBigDecimal(), applicability = Applicability.APPLICABLE)
    private fun correction(field: CorrectionField, old: String?, value: String?, id: String = "a".repeat(64)) =
        ManualCorrection(id, original.id, field, old, value, Instant.EPOCH)
    private fun project(o: Observation = original, r: ReferenceRange? = reference,
        edits: List<ManualCorrection> = listOf(correction(CorrectionField.RESULT, "15", "25")),
        assessment: Assessment? = evaluator.evaluate(o, r)) = CorrectedObservationProjection().project(o, r, assessment, edits, "child")

    @Test fun numericEditCreatesNewIdentityAndAssessmentWithoutMutatingOrConfirmingSource() {
        for ((raw, status) in listOf("9,99" to AssessmentStatus.BELOW_RANGE, "10" to AssessmentStatus.WITHIN_RANGE,
            "20" to AssessmentStatus.WITHIN_RANGE, "20,01" to AssessmentStatus.ABOVE_RANGE)) {
            val (child, selected) = project(edits = listOf(correction(CorrectionField.RESULT, "15", raw)))
            assertEquals(status, evaluator.evaluate(child, selected).status)
            assertEquals("child", selected!!.observationId)
            assertEquals(original.fieldEvidence, child.fieldEvidence)
            assertEquals(original.sourceSnippet, child.sourceSnippet)
            assertFalse(child.confirmed)
        }
        assertEquals("15", original.resultRaw)
        assertEquals(AssessmentStatus.WITHIN_RANGE, evaluator.evaluate(original, reference).status)
    }
    @Test fun everyExistingAmbiguitySurvivesHumanConfirmationAndNumericEdit() {
        for (ambiguity in Ambiguity.entries) {
            val (child, selected) = project(original.copy(ambiguities = setOf(ambiguity), confirmed = true))
            assertTrue(ambiguity in child.ambiguities)
            assertEquals(AssessmentStatus.NEEDS_REVIEW, evaluator.evaluate(child, selected).status)
        }
    }
    @Test fun censoredAndIncompatibleUnitsRemainReviewEvenWithoutStoredAssessment() {
        val censored = project(original.copy(resultComparator = ResultComparator.LT), assessment = null)
        assertEquals(AssessmentStatus.NEEDS_REVIEW, evaluator.evaluate(censored.first, censored.second).status)
        val mismatched = project(original.copy(unitRaw = "mmol/L"), edits = listOf(
            correction(CorrectionField.UNIT, "mmol/L", "mg/dL")), assessment = null)
        assertEquals(AssessmentStatus.NEEDS_REVIEW, evaluator.evaluate(mismatched.first, mismatched.second).status)
    }
    @Test fun typingApplicabilityOrReferenceCannotEstablishMissingContextOrAssociation() {
        val unknown = project(r = reference.copy(applicability = Applicability.UNKNOWN), edits = listOf(
            correction(CorrectionField.APPLICABILITY, "UNKNOWN", "APPLICABLE")))
        assertEquals(Applicability.APPLICABLE, unknown.second!!.applicability)
        assertEquals(AssessmentStatus.NEEDS_REVIEW, evaluator.evaluate(unknown.first, unknown.second).status)
        val typed = project(r = null, edits = listOf(correction(CorrectionField.REFERENCE, original.referenceRaw, "5 a 30 mg/dL")))
        assertEquals(AssessmentStatus.NEEDS_REVIEW, evaluator.evaluate(typed.first, typed.second).status)
        assertEquals("5 a 30 mg/dL", typed.first.referenceRaw)
    }
    @Test fun competingReferencesAndEvidenceStaySeparate() {
        val candidates = listOf(reference, reference.copy(high = "30".toBigDecimal(), referenceRaw = "10 a 30 mg/dL"))
        val result = project(original.copy(referenceCandidates = candidates))
        assertEquals(2, result.first.referenceCandidates.size)
        assertTrue(result.first.referenceCandidates.all { it.observationId == "child" })
        assertNull(result.second)
        assertEquals(AssessmentStatus.NEEDS_REVIEW, evaluator.evaluate(result.first, result.second).status)
    }
    @Test fun invalidAndCensoredNumbersAndMissingUnitsUseConservativeFallbacks() {
        for (raw in listOf("1.234", "< 15", "1e10", "", "1,2,3")) {
            val result = project(edits = listOf(correction(CorrectionField.RESULT, "15", raw)))
            assertEquals(AssessmentStatus.NEEDS_REVIEW, evaluator.evaluate(result.first, result.second).status, raw)
        }
        val missing = project(edits = listOf(correction(CorrectionField.UNIT, "mg/dL", null)))
        assertEquals(AssessmentStatus.NEEDS_REVIEW, evaluator.evaluate(missing.first, missing.second).status)
        val qualitative = project(edits = listOf(correction(CorrectionField.RESULT, "15", "ausente")))
        assertEquals(AssessmentStatus.NOT_CLASSIFIABLE, evaluator.evaluate(qualitative.first, qualitative.second).status)
    }
    @Test fun changingAnalyteCannotReuseReferenceAsUnambiguousAssociation() {
        for (raw in listOf("Sódio", "unknown")) {
            val result = project(edits = listOf(correction(CorrectionField.ANALYTE, "Glicose", raw)))
            assertEquals(AssessmentStatus.NEEDS_REVIEW, evaluator.evaluate(result.first, result.second).status)
        }
    }
    @Test fun correctionsReplayInAppendOrderAndRejectStaleOrForeignRecords() {
        val first = correction(CorrectionField.RESULT, "15", "25")
        val second = correction(CorrectionField.RESULT, "25", "30", "b".repeat(64))
        assertEquals("30", project(edits = listOf(first, second)).first.resultRaw)
        assertThrows(IllegalArgumentException::class.java) { project(edits = listOf(second, first)) }
        assertThrows(IllegalArgumentException::class.java) { project(edits = listOf(first, first)) }
        assertThrows(IllegalArgumentException::class.java) { project(edits = listOf(first.copy(observationId = "other"))) }
    }

    private val parent = ProcessingRun("a".repeat(64), "document", "c".repeat(64), "case", ExtractionMode.BASELINE,
        "extractor", "parser", DeterministicRangeEvaluator.RULE_VERSION, RunState.COMPLETE,
        Instant.EPOCH.toString(), "old-device", "old-api")
    private fun repository(source: ProcessingRun = parent, pageCount: Int = 1): ValidationRepository = mockk<ValidationRepository>().also { repo ->
        coEvery { repo.findRun(parent.runId) } returns source
        coEvery { repo.find("document") } returns Document("document", "artifact:original", parent.documentSha256,
            "application/pdf", Instant.EPOCH, pageCount)
        coEvery { repo.observations(parent.runId) } returns listOf(original)
        coEvery { repo.assessments(parent.runId) } returns listOf(evaluator.evaluate(original, reference))
        coEvery { repo.reference(original.id) } returns reference
        coEvery { repo.corrections(original.id) } returns emptyList()
        coEvery { repo.checkpoints(parent.runId) } returns listOf(PageCheckpoint(parent.runId, 1, source.mode, PageState.COMPLETE, 123,
            inferenceOutcome = source.inferenceOutcome))
    }
    @Test fun reevaluationCapturesExactCorrectionsAndNeverCountsAnotherModelCall() = runBlocking {
        val source = parent.copy(mode = ExtractionMode.ASSISTED, inferenceOutcome = InferenceOutcome.SUCCEEDED)
        val repo = repository(source); val childId = "b".repeat(64)
        var child: ProcessingRun? = null; var pages = emptyList<PageBatch>()
        coEvery { repo.findRun(childId) } answers { child }
        coEvery { repo.corrections(original.id) } returns listOf(correction(CorrectionField.RESULT, "15", "25"))
        coEvery { repo.commitReevaluation(any(), any()) } coAnswers { child = firstArg(); pages = secondArg(); true }
        val actual = ReevaluateRun(repo, ProcessingEnvironment("device", "api"), clock).reevaluate(source.runId, childId)
        assertEquals(OutputBasis.CORRECTED, actual.outputBasis)
        assertEquals(listOf("a".repeat(64)), actual.correctionIds)
        assertEquals(source.runId, actual.parentRunId)
        assertEquals(InferenceOutcome.NOT_ATTEMPTED, actual.inferenceOutcome)
        assertEquals(ExtractionMode.ASSISTED, actual.mode)
        assertEquals(AssessmentStatus.ABOVE_RANGE, pages.single().assessments.single().status)
        assertNull(pages.single().checkpoint.durationMillis)
        assertNull(pages.single().checkpoint.inferenceAttempt)
        assertEquals(InferenceOutcome.NOT_ATTEMPTED, pages.single().checkpoint.inferenceOutcome)
        assertEquals(actual, ReevaluateRun(repo, ProcessingEnvironment("other", "api"), clock).reevaluate(source.runId, childId))
        coVerify(exactly = 1) { repo.commitReevaluation(any(), any()) }
        coVerify(exactly = 0) { repo.groundTruth(any()); repo.appendComparison(any()); repo.appendCorrection(any()); repo.createRun(any()); repo.commitPage(any()) }
    }
    @Test fun missingPagesAreNotInventedAndReassessmentIsNotAutomaticExtraction() = runBlocking {
        val repo = repository(parent.copy(state = RunState.PARTIAL), 2); val childId = "b".repeat(64)
        var child: ProcessingRun? = null; var pages = emptyList<PageBatch>()
        coEvery { repo.findRun(childId) } answers { child }
        coEvery { repo.commitReevaluation(any(), any()) } coAnswers { child = firstArg(); pages = secondArg(); true }
        val actual = ReevaluateRun(repo, ProcessingEnvironment("device", "api"), clock).reevaluate(parent.runId, childId)
        assertEquals(OutputBasis.REASSESSED, actual.outputBasis)
        assertEquals(RunState.PARTIAL, actual.state)
        assertEquals(PageState.FAILED, pages[1].checkpoint.state)
        assertTrue(pages[1].observations.isEmpty())
        assertEquals("REEVALUATION_SOURCE_PAGE_INCOMPLETE", pages[1].checkpoint.errorCode)
    }
    @Test fun activeSourceAndChangedHashAreRejectedBeforeWriting() = runBlocking {
        for (source in listOf(parent.copy(state = RunState.RUNNING), parent.copy(documentSha256 = "d".repeat(64)))) {
            val repo = repository(source)
            coEvery { repo.findRun("b".repeat(64)) } returns null
            try { ReevaluateRun(repo, ProcessingEnvironment("d", "a"), clock).reevaluate(parent.runId, "b".repeat(64)); fail("Invalid source accepted") }
            catch (_: IllegalArgumentException) { }
            coVerify(exactly = 0) { repo.commitReevaluation(any(), any()) }
        }
    }
    @Test fun saveCorrectionRejectsOversizeBlankUnknownApplicabilityAndActiveRun() = runBlocking {
        val repo = repository(parent.copy(state = RunState.RUNNING))
        val save = SaveExtractionCorrection(repo, clock)
        for ((field, value) in listOf(CorrectionField.RESULT to "", CorrectionField.UNIT to "a".repeat(4097),
            CorrectionField.APPLICABILITY to "yes", CorrectionField.RESULT to "25")) {
            try { save.save(parent.runId, original.id, field, "15", value, "b".repeat(64)); fail("Invalid edit accepted") }
            catch (_: IllegalArgumentException) { }
        }
        coVerify(exactly = 0) { repo.appendCorrection(any()) }
    }
}
