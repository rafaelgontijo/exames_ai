package br.com.exames.domain.inference

import br.com.exames.domain.validation.InferenceConfiguration
import br.com.exames.domain.validation.InferenceOutcome
import kotlinx.serialization.Serializable

@Serializable
enum class ProposalDisposition { NOT_REQUESTED, ACCEPTED, ENGINE_FAILURE, NO_FINAL, INVALID_SCHEMA, INVALID_EVIDENCE }

/** Completed attempt metadata only. Source, prompt, response and thought are deliberately absent. */
@Serializable
data class PageInferenceAttempt(
    val disposition: ProposalDisposition,
    val outcome: InferenceOutcome,
    val failure: InferenceFailure? = null,
    val issues: Set<ModelEvidenceIssue> = emptySet(),
    val loadMillis: Long? = null,
    val generationMillis: Long? = null,
    val configuration: InferenceConfiguration? = null,
    /** Adapter invocation, not proof that a native Engine generated a response. */
    val requestSubmitted: Boolean = false,
    val measurements: br.com.exames.domain.validation.RuntimeMeasurements? = null,
) {
    init {
        require(loadMillis == null || loadMillis >= 0)
        require(generationMillis == null || generationMillis >= 0)
        require(requestSubmitted || measurements == null)
        require(requestSubmitted || disposition == ProposalDisposition.NOT_REQUESTED ||
            disposition == ProposalDisposition.ENGINE_FAILURE && failure == InferenceFailure.INPUT_LIMIT)
        require(when (disposition) {
            ProposalDisposition.NOT_REQUESTED -> outcome == InferenceOutcome.NOT_ATTEMPTED && failure == null &&
                issues.isEmpty() && loadMillis == null && generationMillis == null && configuration == null && !requestSubmitted && measurements == null
            ProposalDisposition.ACCEPTED -> outcome == InferenceOutcome.SUCCEEDED && failure == null && issues.isEmpty()
            ProposalDisposition.ENGINE_FAILURE -> outcome == InferenceOutcome.FALLBACK && failure != null && issues.isEmpty()
            ProposalDisposition.NO_FINAL, ProposalDisposition.INVALID_SCHEMA -> outcome == InferenceOutcome.FALLBACK &&
                failure == null && issues.isEmpty()
            ProposalDisposition.INVALID_EVIDENCE -> outcome == InferenceOutcome.FALLBACK && failure == null && issues.isNotEmpty()
        })
    }
}

@Serializable enum class InferenceAttemptState { STARTED, COMPLETED, CANCELLED, INTERRUPTED }

/** Append history owned by the repository; STARTED does not assert that native generation began. */
@Serializable
data class InferenceAttemptRecord(
    val attemptId: String,
    val configuration: InferenceConfiguration,
    val startedAt: String,
    val state: InferenceAttemptState = InferenceAttemptState.STARTED,
    val endedAt: String? = null,
    val result: PageInferenceAttempt? = null,
) {
    init {
        require(br.com.exames.domain.validation.ProcessingRunIds.isOpaque(attemptId))
        java.time.Instant.parse(startedAt)
        endedAt?.let { java.time.Instant.parse(it) }
        require(when (state) {
            InferenceAttemptState.STARTED -> endedAt == null && result == null
            InferenceAttemptState.COMPLETED -> endedAt != null && result != null && result.requestSubmitted &&
                result.configuration == configuration
            InferenceAttemptState.CANCELLED, InferenceAttemptState.INTERRUPTED -> endedAt != null && result == null
        })
    }
    companion object { const val MAX_PER_PAGE = 128 }
}
