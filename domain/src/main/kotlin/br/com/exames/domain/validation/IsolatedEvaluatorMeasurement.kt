package br.com.exames.domain.validation

import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.model.*
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant

enum class IsolationUnavailable { LEGACY_INPUT, UNREVIEWED_TRUTH, UNSUPPORTED_SCHEMA, UNSUPPORTED_RULE, INVALID_INPUT }
data class IsolatedObservationMeasurement(val observationId: String, val assessment: Assessment?,
    val unavailable: IsolationUnavailable?, val statusMatches: Boolean?, val reasonMatches: Boolean?)
data class IsolatedEvaluatorReport(val ruleVersion: String, val observations: List<IsolatedObservationMeasurement>) {
    val evaluated: Int get() = observations.count { it.assessment != null }
    val statusAccuracy: MetricFraction get() = MetricFraction(observations.count { it.statusMatches == true }.toLong(), evaluated.toLong())
    val reasonAccuracy: MetricFraction get() = MetricFraction(observations.count { it.reasonMatches == true }.toLong(), evaluated.toLong())
}

fun GroundTruthVersion.hasReviewedMetadata(): Boolean = reviewState == ReviewState.REVIEWED && !reviewerId.isNullOrBlank() &&
    reviewedAt?.let { try { Instant.parse(it); true } catch (_: Exception) { false } } == true

/** Constructs input from canonical declarations only; expected answers are read after evaluation. */
class IsolatedEvaluatorMeasurement(clock: Clock = Clock.systemUTC()) {
    private val evaluator = DeterministicRangeEvaluator(clock)
    fun measure(truth: GroundTruthVersion): IsolatedEvaluatorReport {
        require(truth.observations.map { it.observationId }.distinct().size == truth.observations.size)
        val rows = truth.observations.map { expected ->
            fun unavailable(reason: IsolationUnavailable) = IsolatedObservationMeasurement(expected.observationId, null, reason, null, null)
            val input = expected.evaluationInput
            when {
                !truth.hasReviewedMetadata() -> unavailable(IsolationUnavailable.UNREVIEWED_TRUTH)
                truth.schemaVersion !in setOf("1.0", "1.1") -> unavailable(IsolationUnavailable.UNSUPPORTED_SCHEMA)
                truth.schemaVersion == "1.0" && input != null -> unavailable(IsolationUnavailable.INVALID_INPUT)
                input == null -> unavailable(IsolationUnavailable.LEGACY_INPUT)
                input.ruleVersion != DeterministicRangeEvaluator.RULE_VERSION -> unavailable(IsolationUnavailable.UNSUPPORTED_RULE)
                else -> try {
                    val (observation, reference) = declaredInput(truth.caseId, expected, input)
                    val actual = evaluator.evaluate(observation, reference)
                    IsolatedObservationMeasurement(expected.observationId, actual, null,
                        actual.status.name == expected.expectedStatus.name, actual.reasonCode.name == expected.expectedReason)
                } catch (_: IllegalArgumentException) { unavailable(IsolationUnavailable.INVALID_INPUT) }
            }
        }
        return IsolatedEvaluatorReport(DeterministicRangeEvaluator.RULE_VERSION, rows)
    }
    private fun declaredInput(caseId: String, expected: ExpectedObservation, input: CanonicalEvaluationInput): Pair<Observation, ReferenceRange?> {
        if (input.referenceSelection == ReferenceSelection.ABSENT)
            require(expected.referenceRaw == null && expected.low == null && expected.high == null)
        if (input.referenceSelection == ReferenceSelection.UNRESOLVED) require(expected.low == null && expected.high == null)
        fun reference(raw: String, low: String?, high: String?, metadata: CanonicalReferenceMetadata) = ReferenceRange(
            metadata.observationId, raw, metadata.unit, metadata.sourcePage, decimal(low), decimal(high), metadata.comparator,
            metadata.lowInclusive, metadata.highInclusive, metadata.condition, metadata.applicability)
        val candidates = input.candidates.map { reference(it.referenceRaw, it.low, it.high, it.metadata) }
        val selected = input.selectedReference?.let { reference(requireNotNull(expected.referenceRaw), expected.low, expected.high, it) }
        val page = requireNotNull(expected.evidence.filter { it.field == "analyteRaw" }
            .map { it.region.page }.distinct().singleOrNull())
        val observation = Observation(expected.observationId, caseId, expected.analyteRaw, expected.resultRaw,
            expected.unitRaw, page, "", numericValue = decimal(expected.numericValue),
            resultComparator = input.resultComparator, ambiguities = input.ambiguities, confirmed = false,
            fieldEvidence = expected.evidence, referenceCandidates = candidates, referenceRaw = expected.referenceRaw)
        return observation to selected
    }
    private fun decimal(raw: String?): BigDecimal? = raw?.let {
        require(it.matches(Regex("[+-]?[0-9]+(?:\\.[0-9]+)?")))
        BigDecimal(it)
    }
    companion object { const val VERSION = "declared-canonical-input/1" }
}
