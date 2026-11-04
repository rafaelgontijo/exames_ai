package br.com.exames.domain.validation

import br.com.exames.domain.model.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class IsolatedEvaluatorMeasurementTest {
    private val json = ValidationJson.format
    private val evaluator = IsolatedEvaluatorMeasurement(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
    private fun fixtures(): List<GroundTruthVersion> = json.decodeFromString(
        javaClass.getResourceAsStream("/validation/truth-v2-scenarios.json")!!.bufferedReader().use { it.readText() })

    @Test fun allNinetyDeclaredInputsMatchIndependentExpectedStatusesAndReasons() {
        val fixtures = fixtures(); assertEquals(18, fixtures.size)
        for (truth in fixtures) {
            val roundTrip = json.decodeFromString<GroundTruthVersion>(json.encodeToString(truth))
            assertEquals(truth, roundTrip)
            val report = evaluator.measure(truth)
            assertEquals(5, report.evaluated)
            assertEquals(MetricFraction(5, 5), report.statusAccuracy)
            assertEquals(MetricFraction(5, 5), report.reasonAccuracy)
        }
    }
    @Test fun legacyTruthIsReadableAndIsolatedAccuracyIsUnavailable() {
        val truth = json.decodeFromString<GroundTruthVersion>(javaClass.getResourceAsStream("/validation/truth.json")!!.bufferedReader().use { it.readText() })
        assertTrue(truth.observations.all { it.evaluationInput == null })
        assertFalse(json.encodeToString(truth).contains("evaluationInput"))
        val report = evaluator.measure(truth)
        assertEquals(0, report.evaluated); assertNull(report.statusAccuracy.ratio)
        assertTrue(report.observations.all { it.unavailable == IsolationUnavailable.LEGACY_INPUT })
    }
    @Test fun changingOnlyExpectedAnswersCannotChangeEvaluatorOutputs() {
        val truth = fixtures().first()
        val changed = truth.copy(version = "changed", observations = truth.observations.map {
            it.copy(expectedStatus = ExpectedStatus.ABOVE_RANGE, expectedReason = "deliberately-wrong")
        })
        val first = evaluator.measure(truth); val second = evaluator.measure(changed)
        assertEquals(first.observations.map { it.assessment }, second.observations.map { it.assessment })
        assertNotEquals(first.reasonAccuracy, second.reasonAccuracy)
    }
    @Test fun unresolvedReferencesNeverSelectOneCandidateAndPendingTruthNeverRuns() {
        val truth = fixtures().first { it.observations.first().evaluationInput!!.referenceSelection == ReferenceSelection.UNRESOLVED }
        val report = evaluator.measure(truth)
        assertTrue(report.observations.all { it.assessment?.status == AssessmentStatus.NEEDS_REVIEW })
        val pending = evaluator.measure(truth.copy(reviewState = ReviewState.PENDING))
        assertEquals(0, pending.evaluated); assertTrue(pending.observations.all { it.unavailable == IsolationUnavailable.UNREVIEWED_TRUTH })
    }
    @Test fun unsupportedRulesAndMissingCanonicalReferenceDoNotUseDefaults() {
        val truth = fixtures().first()
        val changed = truth.copy(observations = truth.observations.map { it.copy(evaluationInput = it.evaluationInput!!.copy(ruleVersion = "future-rule")) })
        assertTrue(evaluator.measure(changed).observations.all { it.unavailable == IsolationUnavailable.UNSUPPORTED_RULE })
        assertThrows(IllegalArgumentException::class.java) { truth.observations.first().evaluationInput!!.copy(selectedReference = null) }
        assertThrows(IllegalArgumentException::class.java) { truth.observations.first().evaluationInput!!.copy(referenceSelection = ReferenceSelection.UNRESOLVED, selectedReference = null) }
    }
    @Test fun versionAndTechnicalBoundsNeverBecomeImplicitCanonicalDefaults() {
        val truth = fixtures().first()
        assertTrue(evaluator.measure(truth.copy(schemaVersion = "future")).observations.all { it.unavailable == IsolationUnavailable.UNSUPPORTED_SCHEMA })
        assertTrue(evaluator.measure(truth.copy(schemaVersion = "1.0")).observations.all { it.unavailable == IsolationUnavailable.INVALID_INPUT })
        val input = truth.observations.first().evaluationInput!!; val metadata = input.selectedReference!!
        assertEquals(20, metadata.copy(sourcePage = 20).sourcePage)
        assertThrows(IllegalArgumentException::class.java) { metadata.copy(sourcePage = 0) }
        assertThrows(IllegalArgumentException::class.java) { metadata.copy(sourcePage = 21) }
        val candidate = CanonicalReferenceCandidate("10 a 20 mg/dL", "10", "20", metadata)
        assertEquals(20, input.copy(candidates = List(20) { candidate }).candidates.size)
        assertThrows(IllegalArgumentException::class.java) { input.copy(candidates = List(21) { candidate }) }
        val missingPage = truth.copy(observations = truth.observations.map { it.copy(evidence = emptyList()) })
        assertTrue(evaluator.measure(missingPage).observations.all { it.unavailable == IsolationUnavailable.INVALID_INPUT })
    }
}
