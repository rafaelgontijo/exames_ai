package br.com.exames.domain.validation

import br.com.exames.domain.model.Document
import br.com.exames.domain.usecase.*
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class StartProcessingRunTest {
    private class Fixture {
        val repository = mockk<ValidationRepository>()
        val scheduler = mockk<RunWorkScheduler>()
        val pages = mockk<RunPageProcessor>()
        val id = "a".repeat(64)
        val document = Document("doc", "artifact:original", "b".repeat(64), "application/pdf", Instant.EPOCH, null)
        val saved = mutableMapOf<String, ProcessingRun>()
        init {
            coEvery { repository.find("doc") } returns document
            coEvery { repository.findRun(any()) } answers { saved[firstArg()] }
            coEvery { repository.createRun(any()) } answers { val run = firstArg<ProcessingRun>(); saved[run.runId] = run }
            every { pages.supports(any()) } returns true
            coEvery { scheduler.enqueue(any()) } just Runs
        }
        fun starter() = StartProcessingRun(repository, scheduler, pages, ProcessingVersions("extractor/1", "parser/1", "rule/1"),
            ProcessingEnvironment("synthetic", "36"), clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
    }

    @Test fun sourceIsCommittedAsAnImmutableQueuedRunBeforeSchedulingWithoutReadingGroundTruth() = runTest {
        val f = Fixture()
        coEvery { f.scheduler.enqueue(f.id) } answers { assertEquals(RunState.QUEUED, f.saved[f.id]!!.state) }
        val result = f.starter().start("doc", f.id)
        assertTrue(result.scheduled); assertEquals(f.document.hash, result.run.documentSha256)
        assertNull(result.run.inference); assertNull(result.run.caseId)
        assertEquals(ExtractionMode.BASELINE, result.run.mode)
        coVerify(exactly = 0) { f.repository.groundTruth(any()) }
        coVerify(exactly = 0) { f.repository.observations(any()) }
    }

    @Test fun scheduleFailureKeepsOneDurableRunAndReusingTheIdDoesNotImportOrCreateAgain() = runTest {
        val f = Fixture()
        coEvery { f.scheduler.enqueue(f.id) } throws IllegalStateException("SENSITIVE_SCHEDULER_BODY")
        val first = f.starter().start("doc", f.id)
        assertFalse(first.scheduled); assertEquals(RunState.QUEUED, f.saved[f.id]!!.state)
        coEvery { f.scheduler.enqueue(f.id) } just Runs
        assertEquals(first.run, f.starter().start("doc", f.id).run)
        coVerify(exactly = 1) { f.repository.createRun(any()) }
        assertEquals(1, f.saved.size)
    }

    @Test fun unsupportedAssistedModeOrProcessingVersionsDoNotCreateOrEnqueueAnything() = runTest {
        val f = Fixture()
        try { f.starter().start("doc", f.id, ExtractionMode.ASSISTED); fail("ASSISTED_ENABLED") } catch (_: IllegalArgumentException) { }
        every { f.pages.supports(any()) } returns false
        try { f.starter().start("doc", f.id); fail("UNSUPPORTED_CONFIGURATION") } catch (_: IllegalArgumentException) { }
        assertTrue(f.saved.isEmpty()); coVerify(exactly = 0) { f.scheduler.enqueue(any()) }
    }

    @Test fun invalidOpaqueIdentityAndExistingConflictingSourceAreRejected() = runTest {
        val f = Fixture()
        try { f.starter().start("doc", "content://synthetic-private-source"); fail("URI_USED_AS_ID") } catch (_: IllegalArgumentException) { }
        f.starter().start("doc", f.id)
        f.saved[f.id] = f.saved.getValue(f.id).copy(documentSha256 = "c".repeat(64))
        try { f.starter().start("doc", f.id); fail("SOURCE_CONFLICT_IGNORED") } catch (_: IllegalArgumentException) { }
        coVerify(exactly = 1) { f.scheduler.enqueue(any()) }
    }

    @Test fun repeatingTerminalRunNeverSchedulesOrReplacesItsOriginalOutput() = runTest {
        val f = Fixture()
        f.starter().start("doc", f.id)
        val complete = f.saved.getValue(f.id).copy(state = RunState.COMPLETE)
        f.saved[f.id] = complete
        assertEquals(complete, f.starter().start("doc", f.id).run)
        coVerify(exactly = 1) { f.scheduler.enqueue(any()) }
        coVerify(exactly = 1) { f.repository.createRun(any()) }
    }

    @Test fun deletionOrStorageFailureBeforeCommitPreventsScheduling() = runTest {
        val f = Fixture()
        coEvery { f.repository.createRun(any()) } throws IllegalStateException("DOCUMENT_DELETED")
        try { f.starter().start("doc", f.id); fail("FAILED_COMMIT_IGNORED") } catch (_: IllegalStateException) { }
        coVerify(exactly = 0) { f.scheduler.enqueue(any()) }
        coEvery { f.repository.find("doc") } returns null
        try { f.starter().start("doc", f.id); fail("MISSING_SOURCE_IGNORED") } catch (_: IllegalArgumentException) { }
    }

    @Test fun cancellationPropagatesWithoutConvertingItToSchedulingSuccess() = runTest {
        val f = Fixture()
        coEvery { f.scheduler.enqueue(f.id) } throws CancellationException()
        try { f.starter().start("doc", f.id); fail("CANCELLATION_SWALLOWED") } catch (_: CancellationException) { }
        assertEquals(RunState.QUEUED, f.saved[f.id]!!.state)
    }
}
