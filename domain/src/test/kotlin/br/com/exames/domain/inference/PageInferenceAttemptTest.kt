package br.com.exames.domain.inference

import br.com.exames.domain.validation.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PageInferenceAttemptTest {
    private val configuration = InferenceConfiguration("synthetic", "revision-1", "a".repeat(64), "synthetic",
        "runtime-1", ProposalProtocol.PROMPT_VERSION, ProposalProtocol.SCHEMA_VERSION, "synthetic", 2048, 1024,
        null, 120000, mapOf("loadTimeoutMillis" to "120000"))
    private val json = ValidationJson.format

    @Test fun completedOutcomesKeepConfigurationFailureEvidenceAndTimingsAcrossSerialization() {
        val cases = listOf(
            PageInferenceAttempt(ProposalDisposition.NOT_REQUESTED, InferenceOutcome.NOT_ATTEMPTED),
            PageInferenceAttempt(ProposalDisposition.ACCEPTED, InferenceOutcome.SUCCEEDED, loadMillis = 12,
                generationMillis = 34, configuration = configuration, requestSubmitted = true),
            PageInferenceAttempt(ProposalDisposition.INVALID_SCHEMA, InferenceOutcome.FALLBACK, loadMillis = 12,
                generationMillis = 34, configuration = configuration, requestSubmitted = true),
            PageInferenceAttempt(ProposalDisposition.INVALID_EVIDENCE, InferenceOutcome.FALLBACK,
                issues = setOf(ModelEvidenceIssue.UNKNOWN_SPAN, ModelEvidenceIssue.DIVERGENCE), configuration = configuration,
                requestSubmitted = true),
            PageInferenceAttempt(ProposalDisposition.NO_FINAL, InferenceOutcome.FALLBACK, configuration = configuration,
                requestSubmitted = true),
        ) + InferenceFailure.entries.map { reason -> PageInferenceAttempt(ProposalDisposition.ENGINE_FAILURE,
            InferenceOutcome.FALLBACK, failure = reason, configuration = configuration, requestSubmitted = true) }
        for (attempt in cases) {
            val checkpoint = PageCheckpoint("run", 1,
                if (attempt.outcome == InferenceOutcome.NOT_ATTEMPTED) ExtractionMode.BASELINE else ExtractionMode.ASSISTED,
                PageState.COMPLETE, 100, inferenceOutcome = attempt.outcome, inferenceAttempt = attempt)
            assertEquals(checkpoint, json.decodeFromString<PageCheckpoint>(json.encodeToString(checkpoint)))
        }
    }

    @Test fun legacyCheckpointDoesNotGainInventedAttemptOrParticipation() {
        val legacy = """{"runId":"run","page":1,"mode":"ASSISTED","state":"COMPLETE","durationMillis":1}"""
        val checkpoint = json.decodeFromString<PageCheckpoint>(legacy)
        assertNull(checkpoint.inferenceAttempt); assertNull(checkpoint.inferenceOutcome)
        val withOutcomeOnly = json.decodeFromString<PageCheckpoint>(legacy.dropLast(1) + ",\"inferenceOutcome\":\"FALLBACK\"}")
        assertNull(withOutcomeOnly.inferenceAttempt)
        assertEquals(InferenceOutcome.FALLBACK, withOutcomeOnly.inferenceOutcome)
    }

    @Test fun impossibleSuccessFailureAndNegativeDurationsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PageInferenceAttempt(ProposalDisposition.ACCEPTED, InferenceOutcome.SUCCEEDED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PageInferenceAttempt(ProposalDisposition.ENGINE_FAILURE, InferenceOutcome.SUCCEEDED, InferenceFailure.LOAD_TIMEOUT,
                requestSubmitted = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PageInferenceAttempt(ProposalDisposition.ACCEPTED, InferenceOutcome.SUCCEEDED, issues = setOf(ModelEvidenceIssue.DIVERGENCE),
                requestSubmitted = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PageInferenceAttempt(ProposalDisposition.NOT_REQUESTED, InferenceOutcome.NOT_ATTEMPTED, configuration = configuration)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PageInferenceAttempt(ProposalDisposition.ACCEPTED, InferenceOutcome.SUCCEEDED, loadMillis = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PageInferenceAttempt(ProposalDisposition.INVALID_EVIDENCE, InferenceOutcome.FALLBACK, requestSubmitted = true)
        }
    }

    @Test fun startedInterruptedCancelledAndCompletedRecordsRetainTheirDistinctMeaning() {
        val started = InferenceAttemptRecord("a".repeat(64), configuration, "2026-10-30T00:00:00Z")
        val accepted = PageInferenceAttempt(ProposalDisposition.ACCEPTED, InferenceOutcome.SUCCEEDED,
            configuration = configuration, requestSubmitted = true)
        val ended = "2026-10-30T00:00:01Z"
        for (record in listOf(started, started.copy(state = InferenceAttemptState.INTERRUPTED, endedAt = ended),
            started.copy(state = InferenceAttemptState.CANCELLED, endedAt = ended),
            started.copy(state = InferenceAttemptState.COMPLETED, endedAt = ended, result = accepted)))
            assertEquals(record, json.decodeFromString<InferenceAttemptRecord>(json.encodeToString(record)))
        assertThrows(IllegalArgumentException::class.java) { started.copy(state = InferenceAttemptState.COMPLETED, endedAt = ended) }
        assertThrows(IllegalArgumentException::class.java) { started.copy(state = InferenceAttemptState.INTERRUPTED, endedAt = ended, result = accepted) }
        assertThrows(IllegalArgumentException::class.java) { started.copy(state = InferenceAttemptState.COMPLETED,
            endedAt = ended, result = accepted.copy(configuration = configuration.copy(modelRevision = "other"))) }
        assertThrows(IllegalArgumentException::class.java) { started.copy(attemptId = "content://private-source") }
    }
}
