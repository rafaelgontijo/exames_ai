package br.com.exames.domain.validation

import br.com.exames.domain.model.*
import br.com.exames.domain.usecase.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class ResumeProcessingRunTest {
    private class Fixture(val count: Int = 3, mode: ExtractionMode = ExtractionMode.BASELINE) {
        val repository = mockk<ValidationRepository>()
        var run = ProcessingRun("run", "doc", "a".repeat(64), null, mode, "extractor/1", "parser/1", "rule/1",
            RunState.QUEUED, Instant.EPOCH.toString(), "synthetic", "synthetic")
        var document = Document("doc", "artifact:original", run.documentSha256, "application/pdf", Instant.EPOCH, null)
        val checkpoints = linkedMapOf<Int, PageCheckpoint>()
        val calls = mutableListOf<Int>()
        var process: suspend (Int) -> PageBatch = { page -> batch(page) }
        var commit: (PageBatch) -> Unit = { checkpoints[it.checkpoint.page] = it.checkpoint }
        var compatible = true
        val pages = object : RunPageProcessor {
            override fun supports(run: ProcessingRun) = compatible
            override suspend fun pageCount(document: Document) = count
            override suspend fun process(run: ProcessingRun, page: Int): PageBatch { calls += page; return process(page) }
        }
        init {
            coEvery { repository.findRun("run") } answers { run }
            coEvery { repository.find("doc") } answers { document }
            coEvery { repository.save(any()) } answers { document = firstArg() }
            coEvery { repository.checkpoints("run") } answers { checkpoints.values.toList() }
            coEvery { repository.updateRunState("run", any(), any()) } answers {
                run = run.copy(state = secondArg(), inferenceOutcome = thirdArg())
            }
            coEvery { repository.commitPage(any()) } answers { commit(firstArg()); true }
        }
        fun batch(page: Int, outcome: InferenceOutcome = if (run.mode == ExtractionMode.BASELINE)
            InferenceOutcome.NOT_ATTEMPTED else InferenceOutcome.SUCCEEDED) =
            PageBatch(PageCheckpoint("run", page, run.mode, PageState.COMPLETE, 12, inferenceOutcome = outcome),
                emptyList(), emptyList(), emptyList())
        fun executor() = ResumeProcessingRun(repository, pages)
    }

    @Test fun committedPagesSurviveAnUncertainCommitAndNewExecutorDoesNotProcessThemAgain() = runTest {
        val f = Fixture()
        f.commit = { batch -> f.checkpoints[batch.checkpoint.page] = batch.checkpoint; throw IllegalStateException("SIMULATED_PROCESS_DEATH") }
        try { f.executor().resume("run"); fail("Crash not simulated") } catch (_: IllegalStateException) { }
        assertEquals(listOf(1), f.calls); assertEquals(RunState.RUNNING, f.run.state)
        f.commit = { f.checkpoints[it.checkpoint.page] = it.checkpoint }
        assertEquals(ResumeRunResult.FINISHED, f.executor().resume("run"))
        assertEquals(listOf(1, 2, 3), f.calls)
        assertEquals(RunState.COMPLETE, f.run.state); assertEquals(3, f.checkpoints.size)
        assertEquals(ResumeRunResult.ALREADY_TERMINAL, f.executor().resume("run"))
        assertEquals(listOf(1, 2, 3), f.calls)
    }

    @Test fun interruptionAfterLastPageBeforeFinalStateNeedsNoReprocessing() = runTest {
        val f = Fixture(1)
        coEvery { f.repository.updateRunState("run", RunState.COMPLETE, any()) } throws IllegalStateException("INTERRUPTED")
        try { f.executor().resume("run"); fail("Interruption not simulated") } catch (_: IllegalStateException) { }
        assertEquals(1, f.checkpoints.size)
        coEvery { f.repository.updateRunState("run", RunState.COMPLETE, any()) } answers { f.run = f.run.copy(state = RunState.COMPLETE) }
        f.executor().resume("run")
        assertEquals(listOf(1), f.calls); assertEquals(RunState.COMPLETE, f.run.state)
    }

    @Test fun concurrentWorkersOnSingletonExecutorDoNotDuplicateProcessing() = runTest {
        val f = Fixture(1); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.process = { page -> entered.complete(Unit); release.await(); f.batch(page) }
        val executor = f.executor()
        val first = async { executor.resume("run") }; entered.await()
        val second = async { executor.resume("run") }; yield()
        assertEquals(listOf(1), f.calls)
        release.complete(Unit)
        assertEquals(ResumeRunResult.FINISHED, first.await()); assertEquals(ResumeRunResult.ALREADY_TERMINAL, second.await())
        assertEquals(listOf(1), f.calls)
    }

    @Test fun latePageAfterCancellationCannotCommitAndExecutionRemainsResumable() = runTest {
        val f = Fixture(1); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.process = { page -> withContext(NonCancellable) { entered.complete(Unit); release.await(); f.batch(page) } }
        val job = launch { f.executor().resume("run") }
        entered.await(); job.cancel(); release.complete(Unit); job.join()
        assertTrue(f.checkpoints.isEmpty()); assertEquals(RunState.RUNNING, f.run.state)
        f.process = { f.batch(it) }; f.executor().resume("run")
        assertEquals(RunState.COMPLETE, f.run.state)
    }

    @Test fun failedMiddlePagePreservesValidPagesAndDoesNotExposeExceptionContent() = runTest {
        val f = Fixture()
        f.process = { if (it == 2) throw IllegalArgumentException("SENSITIVE_SOURCE_MARKER") else f.batch(it) }
        f.executor().resume("run")
        assertEquals(RunState.PARTIAL, f.run.state)
        assertEquals(listOf(1, 2, 3), f.calls)
        assertEquals(PageState.FAILED, f.checkpoints.getValue(2).state)
        assertEquals("PAGE_PROCESSING_FAILED", f.checkpoints.getValue(2).errorCode)
        assertFalse(f.checkpoints.toString().contains("SENSITIVE_SOURCE_MARKER"))
        val allFailed = Fixture(2); allFailed.process = { throw IllegalStateException() }; allFailed.executor().resume("run")
        assertEquals(RunState.FAILED, allFailed.run.state)
    }

    @Test fun checkpointModeConfigurationAndSourceCannotBeMixedOnResume() = runTest {
        val wrongMode = Fixture(1)
        wrongMode.checkpoints[1] = wrongMode.batch(1).checkpoint.copy(mode = ExtractionMode.ASSISTED)
        try { wrongMode.executor().resume("run"); fail("Mixed checkpoint accepted") } catch (_: IllegalArgumentException) { }
        assertTrue(wrongMode.calls.isEmpty())
        val wrongHash = Fixture(1); wrongHash.document = wrongHash.document.copy(hash = "b".repeat(64))
        try { wrongHash.executor().resume("run"); fail("Different source accepted") } catch (_: IllegalArgumentException) { }
        assertTrue(wrongHash.calls.isEmpty())
        val unsupported = Fixture(1); unsupported.compatible = false
        assertEquals(ResumeRunResult.UNSUPPORTED_CONFIGURATION, unsupported.executor().resume("run"))
        assertTrue(unsupported.calls.isEmpty())
    }

    @Test fun invalidPageCountsAndForeignPageBatchAreRejectedBeforeCommit() = runTest {
        for (count in listOf(0, 21)) {
            val f = Fixture(count)
            try { f.executor().resume("run"); fail("Page limit ignored") } catch (_: IllegalArgumentException) { }
            coVerify(exactly = 0) { f.repository.save(any()); f.repository.commitPage(any()) }
        }
        val f = Fixture(1); f.process = { f.batch(it).let { b -> b.copy(checkpoint = b.checkpoint.copy(runId = "foreign")) } }
        try { f.executor().resume("run"); fail("Foreign batch accepted") } catch (_: IllegalArgumentException) { }
        assertTrue(f.checkpoints.isEmpty())
    }

    @Test fun resumedAssistedSuccessRequiresExplicitSuccessOnEveryPageIncludingOldCheckpoints() = runTest {
        val success = Fixture(2, ExtractionMode.ASSISTED); success.executor().resume("run")
        assertEquals(InferenceOutcome.SUCCEEDED, success.run.inferenceOutcome)
        val legacy = Fixture(2, ExtractionMode.ASSISTED)
        legacy.checkpoints[1] = legacy.batch(1).checkpoint.copy(inferenceOutcome = null)
        legacy.executor().resume("run")
        assertEquals(listOf(2), legacy.calls); assertEquals(InferenceOutcome.FALLBACK, legacy.run.inferenceOutcome)
        val fallback = Fixture(1, ExtractionMode.ASSISTED); fallback.process = { fallback.batch(it, InferenceOutcome.FALLBACK) }
        fallback.executor().resume("run"); assertEquals(InferenceOutcome.FALLBACK, fallback.run.inferenceOutcome)
    }
}
