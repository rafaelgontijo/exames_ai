package br.com.exames.domain.model

import java.time.Instant

data class Assessment(
    val observationId: String,
    val status: AssessmentStatus,
    val reasonCode: ReasonCode,
    val evaluatedAt: Instant,
    val ruleVersion: String,
) {
    val requiresReview: Boolean get() = status == AssessmentStatus.NEEDS_REVIEW
}
enum class ReasonCode {
    AMBIGUOUS_INPUT, UNCONFIRMED, NO_REFERENCE, REFERENCE_MISMATCH, CONTEXT_UNRESOLVED,
    UNIT_UNRESOLVED, NON_NUMERIC, CENSORED_RESULT, INVALID_REFERENCE,
    BELOW_LOWER_BOUND, ABOVE_UPPER_BOUND, INSIDE_REFERENCE,
}
