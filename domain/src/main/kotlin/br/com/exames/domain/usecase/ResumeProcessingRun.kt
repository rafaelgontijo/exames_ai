package br.com.exames.domain.usecase

import br.com.exames.domain.model.Document
import br.com.exames.domain.validation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Android adapters own page resources; run/truth history never enters the source processor. */
interface RunPageProcessor {
    fun supports(run: ProcessingRun): Boolean
    suspend fun pageCount(document: Document): Int
    suspend fun process(run: ProcessingRun, page: Int): PageBatch
}
enum class ResumeRunResult { FINISHED, ALREADY_TERMINAL, UNAVAILABLE, UNSUPPORTED_CONFIGURATION }

/** One immutable run/configuration. A committed page is never processed twice after restart. */
class ResumeProcessingRun(private val repository: ValidationRepository, private val pages: RunPageProcessor) {
    private val gate = Mutex()
    suspend fun resume(runId: String): ResumeRunResult = gate.withLock {
        var run = repository.findRun(runId) ?: return@withLock ResumeRunResult.UNAVAILABLE
        if (run.state !in setOf(RunState.QUEUED, RunState.RUNNING)) return@withLock ResumeRunResult.ALREADY_TERMINAL
        if (!pages.supports(run)) return@withLock ResumeRunResult.UNSUPPORTED_CONFIGURATION
        val document = repository.find(run.documentId) ?: return@withLock ResumeRunResult.UNAVAILABLE
        require(document.hash == run.documentSha256) { "RUN_SOURCE_MISMATCH" }
        val count = document.pageCount ?: pages.pageCount(document).also {
            require(it in 1..20) { "RUN_PAGE_LIMIT" }
            currentCoroutineContext().ensureActive()
            repository.save(document.copy(pageCount = it))
        }
        require(count in 1..20) { "RUN_PAGE_LIMIT" }
        val checkpoints = repository.checkpoints(runId)
        require(checkpoints.map { it.page }.distinct().size == checkpoints.size && checkpoints.all {
            it.runId == runId && it.mode == run.mode && it.page in 1..count
        }) { "RUN_CHECKPOINT_MISMATCH" }
        if (run.state == RunState.QUEUED) {
            repository.updateRunState(runId, RunState.RUNNING, run.inferenceOutcome)
            run = run.copy(state = RunState.RUNNING)
        }
        val complete = checkpoints.filter { it.state == PageState.COMPLETE }.map { it.page }.toSet()
        for (page in 1..count) {
            currentCoroutineContext().ensureActive()
            if (page in complete) continue
            // A cancelled/deleted execution must not start another expensive OCR/model call.
            val current = repository.findRun(runId) ?: return@withLock ResumeRunResult.UNAVAILABLE
            if (current.state != RunState.RUNNING) return@withLock ResumeRunResult.ALREADY_TERMINAL
            require(current == run) { "RUN_CONFIGURATION_CHANGED" }
            val batch = try { pages.process(run, page) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    PageBatch(PageCheckpoint(runId, page, run.mode, PageState.FAILED, null,
                        "PAGE_PROCESSING_FAILED", if (run.mode == ExtractionMode.ASSISTED) InferenceOutcome.FAILED else InferenceOutcome.NOT_ATTEMPTED),
                        emptyList(), emptyList(), emptyList())
                }
            require(batch.checkpoint.runId == runId && batch.checkpoint.page == page && batch.checkpoint.mode == run.mode &&
                batch.checkpoint.state in setOf(PageState.COMPLETE, PageState.FAILED)) { "PAGE_BATCH_MISMATCH" }
            currentCoroutineContext().ensureActive()
            val eligible = repository.findRun(runId) ?: return@withLock ResumeRunResult.UNAVAILABLE
            if (eligible.state != RunState.RUNNING) return@withLock ResumeRunResult.ALREADY_TERMINAL
            require(eligible == run) { "RUN_CONFIGURATION_CHANGED" }
            // Repository transaction checks tombstone/state and atomically commits all outputs + checkpoint.
            repository.commitPage(batch)
        }
        currentCoroutineContext().ensureActive()
        val final = repository.checkpoints(runId)
        require(final.size == count && final.map { it.page }.toSet() == (1..count).toSet() && final.all {
            it.runId == runId && it.mode == run.mode && it.state in setOf(PageState.COMPLETE, PageState.FAILED)
        }) { "RUN_CHECKPOINT_INCOMPLETE" }
        val completed = final.count { it.state == PageState.COMPLETE }
        val state = when (completed) { count -> RunState.COMPLETE; 0 -> RunState.FAILED; else -> RunState.PARTIAL }
        val outcome = when {
            run.mode == ExtractionMode.BASELINE -> InferenceOutcome.NOT_ATTEMPTED
            completed == 0 -> InferenceOutcome.FAILED
            final.all { it.state == PageState.COMPLETE && it.inferenceOutcome == InferenceOutcome.SUCCEEDED } -> InferenceOutcome.SUCCEEDED
            else -> InferenceOutcome.FALLBACK // Unknown legacy participation can never count as success.
        }
        repository.updateRunState(runId, state, outcome)
        ResumeRunResult.FINISHED
    }
}
