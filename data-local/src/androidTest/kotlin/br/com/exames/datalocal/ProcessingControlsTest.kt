package br.com.exames.datalocal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import androidx.work.testing.*
import br.com.exames.datadocuments.PdfPreviewRenderer
import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.inference.*
import br.com.exames.domain.model.*
import br.com.exames.domain.parser.BiochemistryParser
import br.com.exames.domain.usecase.*
import br.com.exames.domain.validation.*
import br.com.exames.fixtures.SyntheticPdf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ProcessingControlsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private inner class Fixture {
        val suffix = UUID.randomUUID().toString(); val database = "controls-$suffix.db"; val alias = "controls-$suffix"
        val factory = ProtectedDatabaseFactory(context, database, AndroidPayloadKeys(alias))
        val repository = ProtectedValidationRepository(factory); val artifacts = EncryptedArtifactStore(context)
        val documentId = UUID.randomUUID().toString(); val runId = UUID.randomUUID().toString()
        val pdf = PdfPreviewRenderer(context, LocalDataModule.pdfSource(artifacts, repository))
        val noModel = object : TextInference {
            override suspend fun generate(mode: ExtractionMode, request: GenerationRequest): GenerationResult = error("BASELINE_INVOKED_MODEL")
        }
        val pages = PrivateRunPages(PrivatePageLoader(repository, artifacts, pdf), pdf, ProcessBiochemistryPage(noModel))
        val executor = ResumeProcessingRun(repository, pages)
        val jobs = mutableSetOf<String>()
        val scheduler = object : RunWorkScheduler {
            override suspend fun enqueue(runId: String) { jobs += runId }
            override suspend fun cancel(runId: String) { jobs -= runId }
            override suspend fun activeRunIds() = jobs.toSet()
        }
        fun controls(scheduler: RunWorkScheduler = this.scheduler) = ManageProcessingRuns(repository, scheduler, pages,
            ProcessingEnvironment("synthetic-emulator", "36"))
        suspend fun prepare() {
            val bytes = SyntheticPdf.biochemistry(false, 3, 2)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            artifacts.write(documentId, "original", bytes.inputStream()); bytes.fill(0)
            repository.save(Document(documentId, "artifact:original", hash, "application/pdf", Instant.EPOCH, null))
            repository.createRun(ProcessingRun(runId, documentId, hash, null, ExtractionMode.BASELINE,
                PrivateRunPages.VERSION, BiochemistryParser.VERSION, DeterministicRangeEvaluator.RULE_VERSION,
                RunState.QUEUED, Instant.EPOCH.toString(), "synthetic-emulator", "36"))
        }
        suspend fun cleanup() {
            artifacts.deleteDocument(documentId); factory.open().close(); context.deleteDatabase(database)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    @Test fun invalidMiddlePagePreservesValidOutputsAndRetryUsesNewIdsWithoutChangingOriginal() = runBlocking {
        val f = Fixture()
        try {
            f.prepare(); f.executor.resume(f.runId)
            assertEquals(RunState.PARTIAL, f.repository.findRun(f.runId)!!.state)
            val original = f.repository.observations(f.runId); val checkpoints = f.repository.checkpoints(f.runId)
            val assessments = f.repository.assessments(f.runId)
            assertEquals(10, original.size); assertEquals(setOf(1, 3), original.map { it.page }.toSet())
            assertEquals(listOf(PageState.COMPLETE, PageState.FAILED, PageState.COMPLETE), checkpoints.map { it.state })
            assertEquals("PAGE_UNSUPPORTED_LAYOUT", checkpoints[1].errorCode)
            assertTrue("UNSUPPORTED_LAYOUT" in checkpoints[1].parseWarnings)
            val id = UUID.randomUUID().toString(); val child = f.controls().retry(f.runId, id)
            assertEquals(f.runId, child.parentRunId); assertEquals(setOf(id), f.jobs)
            f.executor.resume(id)
            // Source bytes remain invalid in page 2; retry must not invent a successful repair.
            assertEquals(RunState.PARTIAL, f.repository.findRun(id)!!.state)
            assertEquals(10, f.repository.observations(id).size)
            assertTrue(f.repository.observations(id).none { row -> original.any { it.id == row.id } })
            f.factory.open().close()
            assertEquals(original, f.repository.observations(f.runId))
            assertEquals(checkpoints, f.repository.checkpoints(f.runId)); assertEquals(assessments, f.repository.assessments(f.runId))
            assertEquals(id, f.controls().retry(f.runId, id).runId)
        } finally { f.cleanup() }
    }

    @Test fun durableCancelRejectsLatePageCommitAndCannotBeReopenedAsRunning() = runBlocking {
        val f = Fixture()
        try {
            f.prepare(); f.repository.updateRunState(f.runId, RunState.RUNNING, InferenceOutcome.NOT_ATTEMPTED)
            val run = f.repository.findRun(f.runId)!!
            val late = f.pages.process(run, 1)
            assertEquals(5, late.observations.size)
            f.controls().cancel(f.runId)
            try { f.repository.commitPage(late); fail("Late write accepted") } catch (_: IllegalArgumentException) { }
            assertTrue(f.repository.observations(f.runId).isEmpty()); assertTrue(f.repository.checkpoints(f.runId).isEmpty())
            f.factory.open().close()
            assertEquals(RunState.CANCELLED, f.repository.findRun(f.runId)!!.state)
            assertEquals(RunState.CANCELLED, f.repository.stopRunIfActive(f.runId, RunState.FAILED, RunFailure.WORKER_FAILURE)!!.state)
            assertTrue(f.repository.recoverableRuns().isEmpty())
            try { f.repository.updateRunState(f.runId, RunState.RUNNING, InferenceOutcome.NOT_ATTEMPTED); fail("Cancelled run reopened") }
            catch (_: IllegalArgumentException) { }
        } finally { f.cleanup() }
    }

    @Test fun actualWorkManagerReconcilesUnscheduledRunAndCancelsDurableCancelledJob() = runBlocking {
        val f = Fixture()
        lateinit var controls: ManageProcessingRuns
        val configuration = Configuration.Builder().setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName == ProcessingWorker::class.java.name) ProcessingWorker(appContext, workerParameters, f.executor, controls) else null
        }).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        val manager = WorkManager.getInstance(context)
        try {
            f.prepare(); controls = f.controls(AndroidRunWorkScheduler(context))
            assertEquals(ReconciliationCounts(1, 0, 0), controls.reconcile())
            withTimeout(15000) { manager.getWorkInfosForUniqueWorkFlow(ProcessingWorker.workName(f.runId)).first { rows -> rows.any { it.state.isFinished } } }
            assertEquals(RunState.PARTIAL, f.repository.findRun(f.runId)!!.state)
            assertEquals(10, f.repository.observations(f.runId).size)
            val child = f.repository.findRun(f.runId)!!.copy(runId = UUID.randomUUID().toString(), state = RunState.QUEUED,
                parentRunId = f.runId)
            f.repository.createRun(child)
            val delayed = OneTimeWorkRequestBuilder<ProcessingWorker>().setInputData(workDataOf(ProcessingWorker.RUN_ID to child.runId))
                .addTag(ProcessingWorker.TAG).addTag(ProcessingWorker.RUN_TAG + child.runId).setInitialDelay(1, TimeUnit.HOURS).build()
            manager.enqueueUniqueWork(ProcessingWorker.workName(child.runId), ExistingWorkPolicy.KEEP, delayed).await()
            assertTrue(child.runId in AndroidRunWorkScheduler(context).activeRunIds())
            controls.cancel(child.runId)
            withTimeout(5000) { manager.getWorkInfoByIdFlow(delayed.id).first { it?.state == WorkInfo.State.CANCELLED } }
            assertEquals(RunState.CANCELLED, f.repository.findRun(child.runId)!!.state)
            assertTrue(f.repository.observations(child.runId).isEmpty())
        } finally {
            manager.cancelAllWork().await()
            f.cleanup()
        }
    }

    @Test fun exhaustedWorkerRetriesBecomeDurableFailureWithoutExceptionBody() = runBlocking {
        val f = Fixture()
        try {
            f.prepare()
            val bad = object : ValidationRepository by f.repository {
                override suspend fun findRun(runId: String): ProcessingRun? = throw IllegalStateException("SENSITIVE_ERROR_BODY")
            }
            val executor = ResumeProcessingRun(bad, f.pages)
            val worker = TestListenableWorkerBuilder<ProcessingWorker>(context).setRunAttemptCount(2)
                .setInputData(workDataOf(ProcessingWorker.RUN_ID to f.runId)).setWorkerFactory(object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                        ProcessingWorker(appContext, workerParameters, executor, f.controls())
                }).build()
            assertEquals(ListenableWorker.Result.failure(workDataOf("errorCode" to "WORKER_FAILURE")), worker.doWork())
            assertEquals(RunFailure.WORKER_FAILURE, f.repository.findRun(f.runId)!!.failure)
            assertEquals(RunState.FAILED, f.repository.findRun(f.runId)!!.state)
            assertTrue(f.repository.recoverableRuns().isEmpty())
        } finally { f.cleanup() }
    }
}
