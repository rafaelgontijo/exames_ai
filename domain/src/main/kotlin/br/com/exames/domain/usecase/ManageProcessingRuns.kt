package br.com.exames.domain.usecase

import br.com.exames.domain.validation.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant

interface RunWorkScheduler {
    suspend fun enqueue(runId: String)
    suspend fun cancel(runId: String)
    suspend fun activeRunIds(): Set<String>
}
data class ProcessingEnvironment(val deviceModel: String, val androidVersion: String)
data class ReconciliationCounts(val scheduled: Int, val cancelled: Int, val unsupported: Int)

/** Durable state precedes scheduling; reconciliation repairs interruption between those systems. */
class ManageProcessingRuns(private val repository: ValidationRepository, private val scheduler: RunWorkScheduler,
    private val pages: RunPageProcessor, private val environment: ProcessingEnvironment,
    private val clock: Clock = Clock.systemUTC()) {
    private val gate = Mutex()
    suspend fun cancel(runId: String): ProcessingRun? = gate.withLock {
        val result = repository.stopRunIfActive(runId, RunState.CANCELLED)
        withContext(NonCancellable) { scheduler.cancel(runId) }
        result
    }

    suspend fun fail(runId: String, failure: RunFailure): ProcessingRun? =
        repository.stopRunIfActive(runId, RunState.FAILED, failure)

    /** A new run reprocesses the source, without copying previous pages or counting reused AI as new inference. */
    suspend fun retry(parentRunId: String, retryRunId: String): ProcessingRun = gate.withLock {
        require(ProcessingRunIds.isOpaque(retryRunId)) { "RETRY_ID_INVALID" }
        require(parentRunId != retryRunId) { "RETRY_ID_REUSED" }
        val parent = requireNotNull(repository.findRun(parentRunId)) { "RETRY_PARENT_UNAVAILABLE" }
        require(parent.state in setOf(RunState.PARTIAL, RunState.FAILED, RunState.CANCELLED)) { "RETRY_PARENT_NOT_RETRYABLE" }
        val document = requireNotNull(repository.find(parent.documentId)) { "RETRY_SOURCE_UNAVAILABLE" }
        require(document.hash == parent.documentSha256) { "RETRY_SOURCE_MISMATCH" }
        val proposed = parent.copy(runId = retryRunId, parentRunId = parentRunId, state = RunState.QUEUED,
            startedAt = Instant.now(clock).toString(), deviceModel = environment.deviceModel, androidVersion = environment.androidVersion,
            inferenceOutcome = InferenceOutcome.NOT_ATTEMPTED, failure = null)
        val existing = repository.findRun(retryRunId)
        val child = if (existing == null) {
            require(pages.supports(proposed)) { "RETRY_CONFIGURATION_UNSUPPORTED" }
            repository.createRun(proposed)
            proposed
        } else {
            require(existing.copy(state = proposed.state, startedAt = proposed.startedAt, inferenceOutcome = proposed.inferenceOutcome,
                deviceModel = proposed.deviceModel, androidVersion = proposed.androidVersion, failure = null) == proposed) { "RETRY_ID_CONFLICT" }
            existing
        }
        if (child.state in setOf(RunState.QUEUED, RunState.RUNNING)) scheduler.enqueue(child.runId)
        child
    }

    suspend fun reconcile(): ReconciliationCounts = gate.withLock {
        var cancelled = 0; var scheduled = 0; var unsupported = 0
        for (id in scheduler.activeRunIds()) {
            if (repository.findRun(id)?.state !in setOf(RunState.QUEUED, RunState.RUNNING)) {
                scheduler.cancel(id); cancelled++
            }
        }
        for (run in repository.recoverableRuns()) {
            if (!pages.supports(run)) {
                repository.stopRunIfActive(run.runId, RunState.FAILED, RunFailure.UNSUPPORTED_CONFIGURATION)
                scheduler.cancel(run.runId); unsupported++
            } else {
                // KEEP does not replace an already enqueued/running job. A concurrent cancel is still a durable write barrier.
                scheduler.enqueue(run.runId); scheduled++
            }
        }
        ReconciliationCounts(scheduled, cancelled, unsupported)
    }
}
