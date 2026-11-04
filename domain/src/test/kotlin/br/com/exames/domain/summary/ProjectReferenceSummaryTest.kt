package br.com.exames.domain.summary

import br.com.exames.domain.model.*
import br.com.exames.domain.validation.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class ProjectReferenceSummaryTest {
    private val runId = "a".repeat(64)
    private fun run(state: RunState = RunState.COMPLETE, basis: OutputBasis = OutputBasis.AUTOMATIC, mode: ExtractionMode = ExtractionMode.BASELINE,
        outcome: InferenceOutcome = InferenceOutcome.NOT_ATTEMPTED) =
        ProcessingRun(runId, "doc", "h".repeat(64), null, mode, "ex/1", "p/1", "REF_NUMERIC/2.0.0", state, "2026-01-01T00:00:00Z",
            "dev", "14", inferenceOutcome = outcome, outputBasis = basis)
    private fun obs(id: String, page: Int = 1, ambiguities: Set<Ambiguity> = emptySet(), ref: String? = "70-99", unit: String? = "mg/dL") =
        Observation(id, "doc", "Glicose", "90", unit, page, "snippet", numericValue = BigDecimal("90"), ambiguities = ambiguities, referenceRaw = ref)
    private fun asm(id: String, s: AssessmentStatus, r: ReasonCode) = Assessment(id, s, r, Instant.EPOCH, "REF_NUMERIC/2.0.0")
    private fun cp(page: Int, state: PageState = PageState.COMPLETE, warnings: Set<String> = emptySet()) =
        PageCheckpoint(runId, page, ExtractionMode.BASELINE, state, 1L, parseWarnings = warnings)

    @Test fun countsEveryStatusSeparatelyAndCompleteRunIsComplete() {
        val statuses = listOf(AssessmentStatus.BELOW_RANGE to ReasonCode.BELOW_LOWER_BOUND, AssessmentStatus.WITHIN_RANGE to ReasonCode.INSIDE_REFERENCE,
            AssessmentStatus.ABOVE_RANGE to ReasonCode.ABOVE_UPPER_BOUND, AssessmentStatus.NEEDS_REVIEW to ReasonCode.UNIT_UNRESOLVED,
            AssessmentStatus.NOT_CLASSIFIABLE to ReasonCode.NON_NUMERIC)
        val o = statuses.indices.map { obs("o$it") }
        val s = ProjectReferenceSummary.project(run(), o, statuses.mapIndexed { i, p -> asm("o$i", p.first, p.second) }, listOf(cp(1)), 1)
        assertEquals(SummaryCounts(1, 1, 1, 1, 1), s.counts)
        assertEquals(SummaryCompleteness.COMPLETE, s.completeness)
        assertFalse(SummaryWarning.PAGES_INCOMPLETE in s.warnings)
    }
    @Test fun unitMismatchStaysNeedsReviewWithReason() {
        val s = ProjectReferenceSummary.project(run(), listOf(obs("o1", unit = "mmol/L")),
            listOf(asm("o1", AssessmentStatus.NEEDS_REVIEW, ReasonCode.UNIT_UNRESOLVED)), listOf(cp(1)), 1)
        assertEquals(AssessmentStatus.NEEDS_REVIEW, s.lines.single().status)
        assertEquals(ReasonCode.UNIT_UNRESOLVED, s.lines.single().reason)
        assertEquals("mmol/L", s.lines.single().unitRaw)
    }
    @Test fun censoredResultKeepsRawTextAndReview() {
        val o = obs("o1").copy(resultRaw = "< 0,1", resultComparator = ResultComparator.LT)
        val s = ProjectReferenceSummary.project(run(), listOf(o), listOf(asm("o1", AssessmentStatus.NEEDS_REVIEW, ReasonCode.CENSORED_RESULT)), listOf(cp(1)), 1)
        assertEquals("< 0,1", s.lines.single().resultRaw)
        assertEquals(1, s.counts.needsReview)
    }
    @Test fun missingReferenceIsFlaggedNotInvented() {
        val s = ProjectReferenceSummary.project(run(), listOf(obs("o1", ref = null)),
            listOf(asm("o1", AssessmentStatus.NOT_CLASSIFIABLE, ReasonCode.NO_REFERENCE)), listOf(cp(1)), 1)
        assertTrue(SummaryWarning.REFERENCE_MISSING in s.warnings)
        assertNull(s.lines.single().referenceRaw)
    }
    @Test fun ambiguitiesAreSurfaced() {
        val s = ProjectReferenceSummary.project(run(), listOf(obs("o1", ambiguities = setOf(Ambiguity.UNIT))),
            listOf(asm("o1", AssessmentStatus.NEEDS_REVIEW, ReasonCode.AMBIGUOUS_INPUT)), listOf(cp(1)), 1)
        assertTrue(SummaryWarning.AMBIGUITIES_PRESENT in s.warnings)
        assertEquals(setOf(Ambiguity.UNIT), s.lines.single().ambiguities)
    }
    @Test fun missingAssessmentFallsBackToNeedsReviewNeverWithin() {
        val s = ProjectReferenceSummary.project(run(), listOf(obs("o1")), emptyList(), listOf(cp(1)), 1)
        assertEquals(AssessmentStatus.NEEDS_REVIEW, s.lines.single().status)
        assertNull(s.lines.single().reason)
        assertTrue(SummaryWarning.ASSESSMENT_MISSING in s.warnings)
    }
    @Test fun duplicateAssessmentsAreNotResolvedByPickingOne() {
        val s = ProjectReferenceSummary.project(run(), listOf(obs("o1")), listOf(
            asm("o1", AssessmentStatus.WITHIN_RANGE, ReasonCode.INSIDE_REFERENCE), asm("o1", AssessmentStatus.ABOVE_RANGE, ReasonCode.ABOVE_UPPER_BOUND)), listOf(cp(1)), 1)
        assertEquals(AssessmentStatus.NEEDS_REVIEW, s.lines.single().status)
        assertTrue(SummaryWarning.ASSESSMENT_MISSING in s.warnings)
    }
    @Test fun orphanAssessmentIsFlaggedAndNotCounted() {
        val s = ProjectReferenceSummary.project(run(), listOf(obs("o1")), listOf(
            asm("o1", AssessmentStatus.WITHIN_RANGE, ReasonCode.INSIDE_REFERENCE), asm("zz", AssessmentStatus.ABOVE_RANGE, ReasonCode.ABOVE_UPPER_BOUND)), listOf(cp(1)), 1)
        assertEquals(1, s.counts.total)
        assertTrue(SummaryWarning.ORPHAN_ASSESSMENT in s.warnings)
    }
    @Test fun partialRunNeverReportsComplete() {
        val s = ProjectReferenceSummary.project(run(RunState.PARTIAL), listOf(obs("o1")),
            listOf(asm("o1", AssessmentStatus.WITHIN_RANGE, ReasonCode.INSIDE_REFERENCE)), listOf(cp(1), cp(2, PageState.FAILED)), 2)
        assertEquals(SummaryCompleteness.PARTIAL, s.completeness)
        assertEquals(1, s.pagesComplete)
        assertTrue(SummaryWarning.PAGES_INCOMPLETE in s.warnings && SummaryWarning.RUN_NOT_COMPLETE in s.warnings)
    }
    @Test fun completeRunWithMissingPageCheckpointIsPartialNotComplete() {
        val s = ProjectReferenceSummary.project(run(), listOf(obs("o1")),
            listOf(asm("o1", AssessmentStatus.WITHIN_RANGE, ReasonCode.INSIDE_REFERENCE)), listOf(cp(1)), 2)
        assertEquals(SummaryCompleteness.PARTIAL, s.completeness)
    }
    @Test fun unknownPageCountCannotBeComplete() {
        val s = ProjectReferenceSummary.project(run(), listOf(obs("o1")),
            listOf(asm("o1", AssessmentStatus.WITHIN_RANGE, ReasonCode.INSIDE_REFERENCE)), listOf(cp(1)), null)
        assertEquals(SummaryCompleteness.PARTIAL, s.completeness)
    }
    @Test fun queuedAndRunningAreInProgressAndFailedWithoutPagesUnavailable() {
        assertEquals(SummaryCompleteness.IN_PROGRESS, ProjectReferenceSummary.project(run(RunState.QUEUED), emptyList(), emptyList(), emptyList(), 1).completeness)
        assertEquals(SummaryCompleteness.IN_PROGRESS, ProjectReferenceSummary.project(run(RunState.RUNNING), emptyList(), emptyList(), emptyList(), 1).completeness)
        assertEquals(SummaryCompleteness.UNAVAILABLE, ProjectReferenceSummary.project(run(RunState.FAILED), emptyList(), emptyList(), emptyList(), 1).completeness)
        assertEquals(SummaryCompleteness.UNAVAILABLE, ProjectReferenceSummary.project(run(RunState.CANCELLED), emptyList(), emptyList(), emptyList(), 1).completeness)
    }
    @Test fun unsupportedContentWarningsAreCarriedAndOtherRunCheckpointsIgnored() {
        val other = PageCheckpoint("b".repeat(64), 1, ExtractionMode.BASELINE, PageState.COMPLETE, 1L, parseWarnings = setOf("OTHER"))
        val s = ProjectReferenceSummary.project(run(), listOf(obs("o1")), listOf(asm("o1", AssessmentStatus.WITHIN_RANGE, ReasonCode.INSIDE_REFERENCE)),
            listOf(cp(1, warnings = setOf("TABLE_UNSUPPORTED")), other), 1)
        assertEquals(setOf("TABLE_UNSUPPORTED"), s.parseWarningCodes)
        assertTrue(SummaryWarning.UNSUPPORTED_CONTENT in s.warnings)
    }
    @Test fun emptyOutputFlagsNoObservations() {
        val s = ProjectReferenceSummary.project(run(), emptyList(), emptyList(), listOf(cp(1)), 1)
        assertTrue(SummaryWarning.NO_OBSERVATIONS in s.warnings)
        assertEquals(0, s.counts.total)
    }
    @Test fun identityCarriesModeBasisAndInferenceParticipation() {
        val s = ProjectReferenceSummary.project(run(basis = OutputBasis.CORRECTED, mode = ExtractionMode.ASSISTED, outcome = InferenceOutcome.FALLBACK),
            emptyList(), emptyList(), emptyList(), 1)
        assertEquals(OutputBasis.CORRECTED, s.basis); assertEquals(ExtractionMode.ASSISTED, s.mode)
        assertEquals(InferenceOutcome.FALLBACK, s.inferenceOutcome); assertEquals(ProjectReferenceSummary.VERSION, s.projectionVersion)
    }
    @Test fun linesAreOrderedByPageThenId() {
        val s = ProjectReferenceSummary.project(run(), listOf(obs("b", 2), obs("c", 1), obs("a", 2)), emptyList(), emptyList(), 2)
        assertEquals(listOf("c", "a", "b"), s.lines.map { it.observationId })
    }
}
