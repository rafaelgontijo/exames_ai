package br.com.exames.domain.validation

import br.com.exames.domain.model.Document
import br.com.exames.domain.usecase.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.*
import java.util.UUID

class ManageProcessingRunsTest {
    private class Fixture {
        val parentId = UUID.randomUUID().toString()
        val childId = UUID.randomUUID().toString()
        val repository = mockk<ValidationRepository>()
        val parent = ProcessingRun(parentId, "document", "a".repeat(64), null, ExtractionMode.BASELINE,
            "extractor/1", "parser/1", "rule/1", RunState.PARTIAL, Instant.EPOCH.toString(), "old-device", "old-api")
        val runs = mutableMapOf(parentId to parent)
        val document = Document("document", "artifact:original", parent.documentSha256, "application/pdf", Instant.EPOCH, 3)
        val jobs = mutableSetOf<String>()
        val events = mutableListOf<String>()
        var failScheduling = false
        var compatible = true
        val scheduler = object : RunWorkScheduler {
            override suspend fun enqueue(runId: String) {
                events += "enqueue:$runId"
                if (failScheduling) throw IllegalStateException("SCHEDULER_UNAVAILABLE")
                jobs += runId
            }
            override suspend fun cancel(runId: String) { events += "cancel:$runId"; jobs -= runId }
            override suspend fun activeRunIds() = jobs.toSet()
        }
        val pages = object : RunPageProcessor {
            override fun supports(run: ProcessingRun) = compatible
            override suspend fun pageCount(document: Document): Int = error("UNEXPECTED_SOURCE_READ")
            override suspend fun process(run: ProcessingRun, page: Int): PageBatch = error("UNEXPECTED_EXTRACTION")
        }
        init {
            coEvery { repository.findRun(any()) } answers { runs[firstArg()] }
            coEvery { repository.find("document") } returns document
            coEvery { repository.createRun(any()) } answers {
                val run = firstArg<ProcessingRun>(); check(run.runId !in runs); runs[run.runId] = run
                events += "create:${run.runId}"
            }
            coEvery { repository.stopRunIfActive(any(), any(), any()) } answers {
                val id = firstArg<String>(); events += "stop:$id"
                runs[id]?.let { run -> if (run.state !in setOf(RunState.QUEUED, RunState.RUNNING)) run else
                    run.copy(state = secondArg(), failure = thirdArg()).also { runs[id] = it } }
            }
            coEvery { repository.recoverableRuns() } answers { runs.values.filter { it.state in setOf(RunState.QUEUED, RunState.RUNNING) } }
        }
        fun controls() = ManageProcessingRuns(repository, scheduler, pages, ProcessingEnvironment("current-device", "current-api"),
            Clock.fixed(Instant.parse("2026-10-27T00:00:00Z"), ZoneOffset.UTC))
    }

    @Test fun retryCreatesImmutableChildAndNeverReadsOrWritesOriginalPageOutputs() = runTest {
        val f = Fixture()
        val child = f.controls().retry(f.parentId, f.childId)
        assertEquals(f.parent, f.runs[f.parentId]); assertEquals(f.parentId, child.parentRunId)
        assertEquals(RunState.QUEUED, child.state); assertEquals(f.parent.documentSha256, child.documentSha256)
        assertEquals("current-device", child.deviceModel); assertEquals("current-api", child.androidVersion)
        assertEquals(listOf("create:${f.childId}", "enqueue:${f.childId}"), f.events)
        assertEquals(setOf(f.childId), f.jobs)
        coVerify(exactly = 0) { f.repository.commitPage(any()); f.repository.observations(any());
            f.repository.assessments(any()); f.repository.groundTruth(any()); f.repository.appendComparison(any()) }
    }

    @Test fun sameRetryIdIsIdempotentAndTerminalChildDoesNotGetRescheduled() = runTest {
        val f = Fixture(); val controls = f.controls()
        val a = controls.retry(f.parentId, f.childId); val b = controls.retry(f.parentId, f.childId)
        assertEquals(a, b); assertEquals(2, f.runs.size)
        coVerify(exactly = 1) { f.repository.createRun(any()) }
        f.runs[f.childId] = a.copy(state = RunState.COMPLETE); f.jobs.clear(); f.events.clear()
        assertEquals(RunState.COMPLETE, controls.retry(f.parentId, f.childId).state)
        assertTrue(f.jobs.isEmpty()); assertTrue(f.events.isEmpty())
    }

    @Test fun orphanCreatedBeforeSchedulingFailureIsRecoveredAfterNewControllerAndRepeatedReconciliation() = runTest {
        val f = Fixture(); f.failScheduling = true
        try { f.controls().retry(f.parentId, f.childId); fail("Scheduling failure hidden") } catch (_: IllegalStateException) { }
        assertEquals(RunState.QUEUED, f.runs.getValue(f.childId).state)
        f.failScheduling = false
        assertEquals(ReconciliationCounts(1, 0, 0), f.controls().reconcile())
        f.controls().reconcile()
        assertEquals(setOf(f.childId), f.jobs); assertEquals(2, f.runs.size)
        assertEquals(f.parent, f.runs[f.parentId])
    }

    @Test fun cancelIsDurableBeforeSchedulerAndDoesNotTurnCompletedRunIntoCancelled() = runTest {
        val f = Fixture(); f.runs[f.parentId] = f.parent.copy(state = RunState.RUNNING); f.jobs += f.parentId
        assertEquals(RunState.CANCELLED, f.controls().cancel(f.parentId)!!.state)
        assertEquals(listOf("stop:${f.parentId}", "cancel:${f.parentId}"), f.events)
        assertTrue(f.jobs.isEmpty())
        f.runs[f.parentId] = f.parent.copy(state = RunState.COMPLETE)
        assertEquals(RunState.COMPLETE, f.controls().cancel(f.parentId)!!.state)
        assertEquals(RunState.COMPLETE, f.runs.getValue(f.parentId).state)
        assertNull(f.controls().cancel(UUID.randomUUID().toString()))
    }

    @Test fun reconciliationCancelsMissingOrTerminalJobsAndStopsUnsupportedConfigurations() = runTest {
        val f = Fixture(); val missing = UUID.randomUUID().toString(); f.jobs += setOf(f.parentId, missing)
        val child = f.parent.copy(runId = f.childId, state = RunState.RUNNING); f.runs[f.childId] = child
        f.compatible = false
        assertEquals(ReconciliationCounts(0, 2, 1), f.controls().reconcile())
        assertTrue(f.jobs.isEmpty()); assertEquals(RunState.FAILED, f.runs.getValue(f.childId).state)
        assertEquals(RunFailure.UNSUPPORTED_CONFIGURATION, f.runs.getValue(f.childId).failure)
        assertEquals(f.parent, f.runs[f.parentId])
    }

    @Test fun retryRejectsActiveOrCompleteParentForeignIdsChangedSourceAndUnsupportedConfiguration() = runTest {
        for (state in listOf(RunState.RUNNING, RunState.QUEUED, RunState.COMPLETE)) {
            val f = Fixture(); f.runs[f.parentId] = f.parent.copy(state = state)
            try { f.controls().retry(f.parentId, f.childId); fail("Invalid parent accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(1, f.runs.size)
        }
        val f = Fixture(); f.compatible = false
        try { f.controls().retry(f.parentId, f.childId); fail("Unsupported run accepted") } catch (_: IllegalArgumentException) { }
        f.compatible = true; f.runs[f.childId] = f.parent.copy(runId = f.childId)
        try { f.controls().retry(f.parentId, f.childId); fail("Foreign child accepted") } catch (_: IllegalArgumentException) { }
        try { f.controls().retry(f.parentId, "content://source"); fail("URI accepted") } catch (_: IllegalArgumentException) { }
        coEvery { f.repository.find("document") } returns f.document.copy(hash = "b".repeat(64))
        try { f.controls().retry(f.parentId, UUID.randomUUID().toString()); fail("Changed source accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun lateProcessingAfterExplicitCancelCannotReachCommit() = runTest {
        val f = Fixture(); f.runs[f.parentId] = f.parent.copy(state = RunState.RUNNING)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val pages = object : RunPageProcessor {
            override fun supports(run: ProcessingRun) = true
            override suspend fun pageCount(document: Document) = 3
            override suspend fun process(run: ProcessingRun, page: Int): PageBatch {
                entered.complete(Unit); release.await()
                return PageBatch(PageCheckpoint(run.runId, page, run.mode, PageState.COMPLETE, 1), emptyList(), emptyList(), emptyList())
            }
        }
        coEvery { f.repository.checkpoints(f.parentId) } returns emptyList()
        val processing = async { ResumeProcessingRun(f.repository, pages).resume(f.parentId) }
        entered.await(); f.controls().cancel(f.parentId); release.complete(Unit)
        assertEquals(ResumeRunResult.ALREADY_TERMINAL, processing.await())
        coVerify(exactly = 0) { f.repository.commitPage(any()) }
    }
}
