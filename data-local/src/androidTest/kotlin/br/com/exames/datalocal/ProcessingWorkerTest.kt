package br.com.exames.datalocal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import br.com.exames.datadocuments.PdfPreviewRenderer
import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.inference.*
import br.com.exames.domain.model.*
import br.com.exames.domain.parser.BiochemistryParser
import br.com.exames.domain.usecase.*
import br.com.exames.domain.validation.*
import br.com.exames.fixtures.SyntheticPdf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.io.File
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class ProcessingWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun executor(repository: ValidationRepository, artifacts: EncryptedArtifactStore): ResumeProcessingRun {
        val pdf = PdfPreviewRenderer(context, LocalDataModule.pdfSource(artifacts, repository))
        val inference = object : TextInference {
            override suspend fun generate(mode: ExtractionMode, request: GenerationRequest): GenerationResult = error("BASELINE_INVOKED_MODEL")
        }
        return ResumeProcessingRun(repository, PrivateRunPages(PrivatePageLoader(repository, artifacts, pdf), pdf,
            ProcessBiochemistryPage(inference)))
    }
    private fun worker(executor: ResumeProcessingRun, id: String): ProcessingWorker =
        TestListenableWorkerBuilder<ProcessingWorker>(context).setInputData(workDataOf(ProcessingWorker.RUN_ID to id))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    ProcessingWorker(appContext, workerParameters, executor)
            }).build()

    @Test fun actualWorkerResumesPrivateTwoPagePdfAfterUncertainCommitAndDatabaseReopen() = runBlocking {
        val suffix = UUID.randomUUID().toString(); val name = "worker-$suffix.db"; val alias = "worker-$suffix"
        val factory = ProtectedDatabaseFactory(context, name, AndroidPayloadKeys(alias))
        val repository = ProtectedValidationRepository(factory); val artifacts = EncryptedArtifactStore(context)
        val documentId = UUID.randomUUID().toString(); val runId = UUID.randomUUID().toString()
        try {
            val bytes = SyntheticPdf.biochemistry(false, 2)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            artifacts.write(documentId, "original", bytes.inputStream()); bytes.fill(0)
            repository.save(Document(documentId, "artifact:original", hash, "application/pdf", Instant.EPOCH, null))
            repository.createRun(ProcessingRun(runId, documentId, hash, null, ExtractionMode.BASELINE,
                PrivateRunPages.VERSION, BiochemistryParser.VERSION, DeterministicRangeEvaluator.RULE_VERSION,
                RunState.QUEUED, Instant.EPOCH.toString(), "synthetic-emulator", "36"))
            val uncertain = object : ValidationRepository by repository {
                override suspend fun commitPage(batch: PageBatch): Boolean {
                    repository.commitPage(batch)
                    throw IllegalStateException("SIMULATED_PROCESS_EXIT_AFTER_COMMIT")
                }
            }
            assertEquals(ListenableWorker.Result.retry(), worker(executor(uncertain, artifacts), runId).doWork())
            val originals = repository.observations(runId)
            assertEquals(5, originals.size)
            assertEquals(RunState.RUNNING, repository.findRun(runId)!!.state)
            assertEquals(2, repository.find(documentId)!!.pageCount)
            factory.open().close()
            val reopened = ProtectedValidationRepository(factory)
            assertEquals(ListenableWorker.Result.success(), worker(executor(reopened, artifacts), runId).doWork())
            assertEquals(RunState.COMPLETE, reopened.findRun(runId)!!.state)
            val observations = reopened.observations(runId)
            assertEquals(10, observations.size); assertEquals(10, observations.map { it.id }.distinct().size)
            assertEquals(originals, observations.filter { it.page == 1 })
            assertTrue(reopened.assessments(runId).all { it.status == AssessmentStatus.WITHIN_RANGE })
            assertTrue(reopened.checkpoints(runId).all { it.state == PageState.COMPLETE && it.durationMillis!! >= 0 &&
                it.mode == ExtractionMode.BASELINE && it.inferenceOutcome == InferenceOutcome.NOT_ATTEMPTED })
            assertEquals(ListenableWorker.Result.success(), worker(executor(reopened, artifacts), runId).doWork())
            assertEquals(observations, reopened.observations(runId))
        } finally {
            artifacts.deleteDocument(documentId); factory.open().close(); context.deleteDatabase(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    @Test fun schedulingContainsOnlyOpaqueRunIdAndNoNetworkConstraint() {
        val id = UUID.randomUUID().toString()
        val request = ProcessingWorker.request(id)
        assertEquals(mapOf(ProcessingWorker.RUN_ID to id), request.workSpec.input.keyValueMap)
        assertEquals(NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
        try { ProcessingWorker.request("content://sensitive-document"); fail("URI accepted") } catch (_: IllegalArgumentException) { }
    }

    /** Host invokes prepare, force-stops this test package, then invokes resume without reinstalling. */
    @Test fun processDeathRecoveryAcrossSeparateInstrumentationInvocations() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("recoveryPhase")
        assumeTrue(phase in listOf("prepare", "resume"))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("recoveryPhase", phase); putInt("recoveryPid", android.os.Process.myPid())
        })
        val marker = File(context.noBackupFilesDir, "synthetic-worker-recovery.json")
        val metadata = if (phase == "prepare") {
            check(!marker.exists()) { "RECOVERY_FIXTURE_ALREADY_EXISTS" }
            JSONObject().put("documentId", UUID.randomUUID().toString()).put("runId", UUID.randomUUID().toString())
                .put("database", "recovery-${UUID.randomUUID()}.db").put("alias", "recovery-${UUID.randomUUID()}")
        } else JSONObject(marker.readText())
        val factory = ProtectedDatabaseFactory(context, metadata.getString("database"), AndroidPayloadKeys(metadata.getString("alias")))
        val repository = ProtectedValidationRepository(factory); val artifacts = EncryptedArtifactStore(context)
        val documentId = metadata.getString("documentId"); val runId = metadata.getString("runId")
        var retain = false
        try {
            if (phase == "prepare") {
                val bytes = SyntheticPdf.biochemistry(false, 2)
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                artifacts.write(documentId, "original", bytes.inputStream()); bytes.fill(0)
                repository.save(Document(documentId, "artifact:original", hash, "application/pdf", Instant.EPOCH, null))
                repository.createRun(ProcessingRun(runId, documentId, hash, null, ExtractionMode.BASELINE,
                    PrivateRunPages.VERSION, BiochemistryParser.VERSION, DeterministicRangeEvaluator.RULE_VERSION,
                    RunState.QUEUED, Instant.EPOCH.toString(), "synthetic-emulator", "36"))
                val interrupted = object : ValidationRepository by repository {
                    override suspend fun commitPage(batch: PageBatch): Boolean {
                        repository.commitPage(batch); throw IllegalStateException("SIMULATED_INTERRUPTION_AFTER_COMMIT")
                    }
                }
                assertEquals(ListenableWorker.Result.retry(), worker(executor(interrupted, artifacts), runId).doWork())
                val originalIds = repository.observations(runId).map { it.id }.sorted()
                assertEquals(5, originalIds.size)
                metadata.put("firstPageIds", originalIds.joinToString(",")).put("preparePid", android.os.Process.myPid())
                marker.outputStream().use { it.write(metadata.toString().toByteArray()); it.fd.sync() }
                retain = true
            } else {
                assertNotEquals(metadata.getInt("preparePid"), android.os.Process.myPid())
                val originalIds = repository.observations(runId).filter { it.page == 1 }.map { it.id }.sorted()
                assertEquals(metadata.getString("firstPageIds"), originalIds.joinToString(","))
                assertEquals(ListenableWorker.Result.success(), worker(executor(repository, artifacts), runId).doWork())
                assertEquals(RunState.COMPLETE, repository.findRun(runId)!!.state)
                assertEquals(10, repository.observations(runId).size)
                assertEquals(originalIds, repository.observations(runId).filter { it.page == 1 }.map { it.id }.sorted())
                assertEquals(2, repository.checkpoints(runId).size)
            }
        } finally {
            factory.open().close()
            if (!retain) {
                artifacts.deleteDocument(documentId); context.deleteDatabase(metadata.getString("database"))
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(metadata.getString("alias")) }
                marker.delete()
            }
        }
    }
}
