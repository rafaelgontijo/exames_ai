package br.com.exames.domain.usecase

import br.com.exames.domain.validation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant

data class ProcessingVersions(val extractor: String, val parser: String, val rule: String)
data class StartedProcessingRun(val run: ProcessingRun, val scheduled: Boolean)

/** Source identity is read from protected history. Ground truth never participates in processing. */
class StartProcessingRun(private val repository: ValidationRepository, private val scheduler: RunWorkScheduler,
    private val pages: RunPageProcessor, private val versions: ProcessingVersions,
    private val environment: ProcessingEnvironment,
    private val inference: () -> InferenceConfiguration? = { null },
    private val clock: Clock = Clock.systemUTC()) {
    private val gate = Mutex()
    suspend fun start(documentId: String, runId: String, mode: ExtractionMode = ExtractionMode.BASELINE,
        parentRunId: String? = null): StartedProcessingRun = gate.withLock {
        require(ProcessingRunIds.isOpaque(runId)) { "RUN_ID_INVALID" }
        val document = requireNotNull(repository.find(documentId)) { "RUN_SOURCE_UNAVAILABLE" }
        val parent = parentRunId?.let { requireNotNull(repository.findRun(it)) { "RUN_PARENT_UNAVAILABLE" } }
        if (parent != null) require(parent.documentId == documentId && parent.documentSha256 == document.hash &&
            parent.state !in setOf(RunState.QUEUED, RunState.RUNNING)) { "RUN_PARENT_MISMATCH" }
        val configuration = if (mode == ExtractionMode.ASSISTED) requireNotNull(inference()) { "ASSISTED_UNAVAILABLE" } else null
        val proposed = ProcessingRun(runId, documentId, document.hash, parent?.caseId ?: document.corpusCase?.caseId, mode, versions.extractor, versions.parser,
            versions.rule, RunState.QUEUED, Instant.now(clock).toString(), environment.deviceModel, environment.androidVersion,
            configuration, parentRunId = parentRunId)
        val existing = repository.findRun(runId)
        val run = if (existing == null) {
            require(pages.supports(proposed)) { "RUN_CONFIGURATION_UNSUPPORTED" }
            repository.createRun(proposed)
            proposed
        } else {
            require(existing.copy(state = proposed.state, startedAt = proposed.startedAt, deviceModel = proposed.deviceModel,
                androidVersion = proposed.androidVersion, inferenceOutcome = proposed.inferenceOutcome, failure = null) == proposed) {
                "RUN_ID_CONFLICT"
            }
            existing
        }
        if (run.state !in setOf(RunState.QUEUED, RunState.RUNNING)) return@withLock StartedProcessingRun(run, true)
        val scheduled = try { scheduler.enqueue(runId); true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false } // Durable QUEUED run is reconciled without importing the source again.
        StartedProcessingRun(run, scheduled)
    }
}
