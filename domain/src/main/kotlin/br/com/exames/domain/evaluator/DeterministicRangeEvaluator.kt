package br.com.exames.domain.evaluator

import br.com.exames.domain.model.*
import br.com.exames.domain.model.AssessmentStatus.*
import br.com.exames.domain.model.ReasonCode.*
import java.time.Clock
import java.time.Instant

/** Rule REF_NUMERIC v2.0.0. Automatic assessment; clinical ambiguity still requires review. */
class DeterministicRangeEvaluator(private val clock: Clock = Clock.systemUTC()) {
    fun evaluate(o: Observation, r: ReferenceRange?): Assessment {
        fun answer(status: AssessmentStatus, reason: ReasonCode) =
            Assessment(o.id, status, reason, Instant.now(clock), RULE_VERSION)
        if (o.ambiguities.isNotEmpty()) return answer(NEEDS_REVIEW, AMBIGUOUS_INPUT)
        if (r == null) return answer(NOT_CLASSIFIABLE, NO_REFERENCE)
        if (r.observationId != o.id) return answer(NEEDS_REVIEW, REFERENCE_MISMATCH)
        if (r.applicability != Applicability.APPLICABLE) return answer(NEEDS_REVIEW, CONTEXT_UNRESOLVED)
        if (r.comparator == ReferenceComparator.UNKNOWN) return answer(NEEDS_REVIEW, INVALID_REFERENCE)
        if (o.resultComparator != ResultComparator.EQ) return answer(NEEDS_REVIEW, CENSORED_RESULT)
        // Compare only explicit identical units. No normalization based on unchecked metadata.
        if (o.unitRaw.isNullOrBlank() || r.unit.isNullOrBlank() || o.unitRaw.trim() != r.unit.trim())
            return answer(NEEDS_REVIEW, UNIT_UNRESOLVED)
        val x = o.numericValue ?: return answer(NOT_CLASSIFIABLE, NON_NUMERIC)
        val parsed = ConservativeNumberParser().parse(o.resultRaw)
        if (parsed.needsReview || parsed.value?.compareTo(x) != 0)
            return answer(NEEDS_REVIEW, AMBIGUOUS_INPUT)
        val low = r.low
        val high = r.high
        val valid = when (r.comparator) {
            ReferenceComparator.RANGE -> low != null && high != null &&
                (low < high || (low.compareTo(high) == 0 && r.lowInclusive && r.highInclusive))
            ReferenceComparator.LT, ReferenceComparator.LTE -> high != null && low == null
            ReferenceComparator.GT, ReferenceComparator.GTE -> low != null && high == null
            ReferenceComparator.UNKNOWN -> false
        }
        if (!valid) return answer(NEEDS_REVIEW, INVALID_REFERENCE)
        val lowIncluded = if (r.comparator == ReferenceComparator.RANGE) r.lowInclusive else r.comparator == ReferenceComparator.GTE
        val highIncluded = if (r.comparator == ReferenceComparator.RANGE) r.highInclusive else r.comparator == ReferenceComparator.LTE
        if (low != null && (x < low || (x.compareTo(low) == 0 && !lowIncluded)))
            return answer(BELOW_RANGE, BELOW_LOWER_BOUND)
        if (high != null && (x > high || (x.compareTo(high) == 0 && !highIncluded)))
            return answer(ABOVE_RANGE, ABOVE_UPPER_BOUND)
        return answer(WITHIN_RANGE, INSIDE_REFERENCE)
    }
    companion object { const val RULE_VERSION = "REF_NUMERIC/2.0.0" }
}
