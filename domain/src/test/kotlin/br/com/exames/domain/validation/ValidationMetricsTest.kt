package br.com.exames.domain.validation

import br.com.exames.domain.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ValidationMetricsTest {
    private val calculator = ValidationMetricsCalculator()
    private fun expected(id: String, status: ExpectedStatus, index: Int): ExpectedObservation {
        val raw = when (status) { ExpectedStatus.ABOVE_RANGE -> "21"; ExpectedStatus.BELOW_RANGE -> "9"; else -> "15" }
        return ExpectedObservation(id, Analyte.GLUCOSE, "Glicose", raw, "mg/dL", "10 a 20 mg/dL", raw, "10", "20", status,
            "synthetic", true, listOf(ExtractedFieldEvidence("analyteRaw", "Glicose",
                SourceRegion(1, 25.0, 100.0 + index * 40, 90.0, 115.0 + index * 40, 600.0, 800.0), "span-$id", "SYNTHETIC", "test/1")))
    }
    private fun actual(truth: ExpectedObservation, id: String = "a-${truth.observationId}") = Observation(id, "document", truth.analyteRaw,
        truth.resultRaw, truth.unitRaw, 1, "synthetic", numericValue = truth.numericValue?.toBigDecimal(),
        fieldEvidence = truth.evidence, referenceRaw = truth.referenceRaw)
    private fun truth(values: List<ExpectedObservation>, state: ReviewState = ReviewState.REVIEWED) = GroundTruthVersion(
        caseId = "case", version = "1", reviewState = state, reviewerId = "synthetic", reviewedAt = "2026-10-27T00:00:00Z", observations = values)
    private fun assessment(id: String, status: AssessmentStatus) = Assessment(id, status, ReasonCode.INSIDE_REFERENCE, Instant.EPOCH, "synthetic")

    @Test fun emptyAndPendingTruthHaveNoAccuracyOrCoveragePercentage() {
        val empty = calculator.compute(truth(emptyList()), emptyList(), emptyMap(), emptyMap(), true)
        assertNull(empty.numericCoverage.ratio); assertNull(empty.rawAccuracy.ratio)
        val expected = expected("e", ExpectedStatus.WITHIN_RANGE, 1); val observed = actual(expected)
        val pending = calculator.compute(truth(listOf(expected), ReviewState.PENDING), listOf(observed), emptyMap(),
            mapOf(observed.id to assessment(observed.id, AssessmentStatus.WITHIN_RANGE)), true)
        assertEquals(1, pending.pendingExpected); assertNull(pending.classificationAccuracy.ratio)
        assertNull(pending.comparison); assertEquals(0, pending.extra)
    }
    @Test fun invalidReviewMetadataDoesNotAuthorizeDenominators() {
        val values = listOf(expected("e", ExpectedStatus.WITHIN_RANGE, 1))
        for (invalid in listOf(truth(values).copy(reviewerId = null), truth(values).copy(reviewedAt = "bad-date"))) {
            val result = calculator.compute(invalid, emptyList(), emptyMap(), emptyMap(), true)
            assertEquals(0, result.reviewedExpected); assertEquals(1, result.pendingExpected)
        }
    }
    @Test fun zeroCoverageIsZeroAndAllAbstentionsRemainVisible() {
        val expected = listOf(expected("e1", ExpectedStatus.ABOVE_RANGE, 1), expected("e2", ExpectedStatus.BELOW_RANGE, 2))
        val actual = expected.map { actual(it) }
        val result = calculator.compute(truth(expected), actual, emptyMap(), actual.associate { it.id to assessment(it.id, AssessmentStatus.NEEDS_REVIEW) }, true)
        assertEquals(MetricFraction(0, 2), result.numericCoverage); assertEquals(0.0, result.numericCoverage.ratio)
        assertEquals(2, result.matchedStatusCounts[AssessmentStatus.NEEDS_REVIEW]); assertEquals(2, result.abnormal.notSignaled)
        assertEquals(MetricFraction(0, 2), result.classificationAccuracy)
    }
    @Test fun explicitDenominatorsIncludeMissingAndExcludeExtrasFromNumerators() {
        val statuses = listOf(ExpectedStatus.WITHIN_RANGE, ExpectedStatus.ABOVE_RANGE, ExpectedStatus.BELOW_RANGE,
            ExpectedStatus.ABOVE_RANGE, ExpectedStatus.ABOVE_RANGE, ExpectedStatus.BELOW_RANGE, ExpectedStatus.ABOVE_RANGE, ExpectedStatus.BELOW_RANGE)
        val expected = statuses.mapIndexed { index, status -> expected("e$index", status, index) }
        val actual = expected.filterIndexed { index, _ -> index != 3 }.map { actual(it) } +
            listOf(actual(expected("extra1", ExpectedStatus.WITHIN_RANGE, 13)), actual(expected("extra2", ExpectedStatus.WITHIN_RANGE, 14)))
        val output = mapOf(0 to AssessmentStatus.WITHIN_RANGE, 1 to AssessmentStatus.WITHIN_RANGE,
            2 to AssessmentStatus.NEEDS_REVIEW, 4 to AssessmentStatus.NOT_CLASSIFIABLE,
            6 to AssessmentStatus.BELOW_RANGE, 7 to AssessmentStatus.BELOW_RANGE)
        val assessments = output.map { (index, status) -> "a-e$index" to assessment("a-e$index", status) }.toMap() +
            actual.takeLast(2).associate { it.id to assessment(it.id, AssessmentStatus.WITHIN_RANGE) }
        val result = calculator.compute(truth(expected), actual, emptyMap(), assessments, false)
        assertEquals(8, result.reviewedExpected); assertEquals(7, result.matched); assertEquals(1, result.missing); assertEquals(2, result.extra)
        assertFalse(result.processingComplete)
        assertEquals(MetricFraction(4, 8), result.numericCoverage); assertEquals(MetricFraction(2, 8), result.classificationAccuracy)
        assertEquals(MetricFraction(28, 32), result.rawAccuracy)
        assertEquals(AbnormalBreakdown(7, 1, 1, 1, 1, 1, 1, 1), result.abnormal)
        assertEquals(MetricFraction(1, 7), result.abnormal.shownWithinFraction)
        assertEquals(MetricFraction(5, 7), result.abnormal.notSignaledFraction)
        assertEquals(2, result.extraStatusCounts[AssessmentStatus.WITHIN_RANGE]); assertEquals(1, result.missingAssessments)
    }
    @Test fun duplicatesDoNotRaiseCoverageEvenWhenTheirValuesAreCorrect() {
        val expected = expected("e", ExpectedStatus.WITHIN_RANGE, 1)
        val actual = listOf(actual(expected, "a1"), actual(expected, "a2"))
        val result = calculator.compute(truth(listOf(expected)), actual, emptyMap(), actual.associate { it.id to assessment(it.id, AssessmentStatus.WITHIN_RANGE) }, true)
        assertEquals(MetricFraction(0, 1), result.numericCoverage); assertEquals(1, result.unresolvedExpected)
        assertEquals(2, result.extra); assertEquals(1, result.missing)
    }
    @Test fun fractionsRejectInflatedNumeratorsAndForeignAssessmentOwnership() {
        assertThrows(IllegalArgumentException::class.java) { MetricFraction(2, 1) }
        val expected = expected("e", ExpectedStatus.WITHIN_RANGE, 1); val observed = actual(expected)
        assertThrows(IllegalArgumentException::class.java) {
            calculator.compute(truth(listOf(expected)), listOf(observed), emptyMap(), mapOf(observed.id to assessment("foreign", AssessmentStatus.WITHIN_RANGE)), true)
        }
    }
    @Test fun entirelyMissingOutputHasZeroPerFieldScoresAndEmptyTruthStillReportsExtras() {
        val expected = expected("e", ExpectedStatus.ABOVE_RANGE, 1)
        val missing = calculator.compute(truth(listOf(expected)), emptyList(), emptyMap(), emptyMap(), false)
        assertEquals(11, missing.fieldAccuracy.size)
        assertTrue(missing.fieldAccuracy.values.all { it == MetricFraction(0, 1) })
        assertEquals(MetricFraction(0, 4), missing.rawAccuracy)
        assertEquals(1, missing.abnormal.missingObservation)
        val observed = actual(expected)
        val empty = calculator.compute(truth(emptyList()), listOf(observed), emptyMap(),
            mapOf(observed.id to assessment(observed.id, AssessmentStatus.WITHIN_RANGE)), true)
        assertEquals(1, empty.extra); assertNull(empty.numericCoverage.ratio)
    }
}
