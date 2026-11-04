package br.com.exames.datalocal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.exames.datadocuments.PdfPreviewRenderer
import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.inference.*
import br.com.exames.domain.model.Document
import br.com.exames.domain.parser.BiochemistryParser
import br.com.exames.domain.usecase.*
import br.com.exames.domain.validation.*
import br.com.exames.fixtures.SyntheticPdf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class InferenceTraceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private class Fixture(val context: Context) {
        val suffix = UUID.randomUUID().toString()
        val name = "inference-trace-$suffix.db"
        val alias = "inference-trace-$suffix"
        val factory = ProtectedDatabaseFactory(context, name, AndroidPayloadKeys(alias))
        val repository = ProtectedValidationRepository(factory)
        val artifacts = EncryptedArtifactStore(context)
        val documentId = UUID.randomUUID().toString()
        val configuration = InferenceConfiguration("synthetic", "revision-$suffix", "c".repeat(64), "synthetic", "runtime-$suffix",
            ProposalProtocol.PROMPT_VERSION, ProposalProtocol.SCHEMA_VERSION, "synthetic", 2048, 1024, null, 120000, emptyMap())
        lateinit var run: ProcessingRun
        suspend fun prepare() {
            val bytes = SyntheticPdf.biochemistry(false)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            artifacts.write(documentId, "original", bytes.inputStream()); bytes.fill(0)
            repository.save(Document(documentId, "artifact:original", hash, "application/pdf", Instant.EPOCH, 1))
            run = ProcessingRun(UUID.randomUUID().toString(), documentId, hash, null, ExtractionMode.ASSISTED,
                PrivateRunPages.VERSION, BiochemistryParser.VERSION, DeterministicRangeEvaluator.RULE_VERSION,
                RunState.RUNNING, Instant.EPOCH.toString(), "synthetic", "36", configuration)
            repository.createRun(run)
        }
        fun pages(answer: suspend () -> GenerationResult): PrivateRunPages {
            val backend = object : TextInference {
                override fun configuration(promptVersion: String, schemaVersion: String) = configuration
                override suspend fun generate(mode: ExtractionMode, request: GenerationRequest) = answer()
            }
            val pdf = PdfPreviewRenderer(context, LocalDataModule.pdfSource(artifacts, repository))
            return PrivateRunPages(PrivatePageLoader(repository, artifacts, pdf), pdf, ProcessBiochemistryPage(backend),
                attempts = repository, assistedCompatible = { true })
        }
        suspend fun close() {
            artifacts.deleteDocument(documentId); factory.open().close(); context.deleteDatabase(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    @Test fun actualPrivatePageFallbackReopensWithIdentityTimingsAndNoResponseOrExceptionBody() = runBlocking {
        val f = Fixture(context)
        try {
            f.prepare()
            val marker = "SYNTHETIC-PRIVATE-RESPONSE-${f.suffix}"
            val result = ResumeProcessingRun(f.repository, f.pages { GenerationResult.UnvalidatedFinal(marker, 12, 34) }).resume(f.run.runId)
            assertEquals(ResumeRunResult.FINISHED, result)
            val checkpoint = f.repository.checkpoints(f.run.runId).single()
            val attempt = checkpoint.inferenceAttempt!!
            assertEquals(ProposalDisposition.INVALID_SCHEMA, attempt.disposition)
            assertEquals(InferenceOutcome.FALLBACK, attempt.outcome); assertTrue(attempt.requestSubmitted)
            assertEquals(f.configuration, attempt.configuration)
            assertEquals(12L, attempt.loadMillis); assertEquals(34L, attempt.generationMillis)
            assertEquals(InferenceAttemptState.COMPLETED, checkpoint.inferenceHistory.single().state)
            assertEquals(attempt, checkpoint.inferenceHistory.single().result)
            assertEquals(5, f.repository.observations(f.run.runId).size)
            assertEquals(InferenceOutcome.FALLBACK, f.repository.findRun(f.run.runId)!!.inferenceOutcome)
            val row = f.factory.open().records().checkpoints(f.run.runId).single()
            assertFalse(row.payload.toString(Charsets.UTF_8).contains(f.configuration.modelRevision))
            f.factory.open().close()
            assertEquals(checkpoint, f.repository.checkpoints(f.run.runId).single())
            assertEquals(f.configuration, f.repository.findRun(f.run.runId)!!.inference)
            for (root in listOf(context.noBackupFilesDir, context.filesDir, context.cacheDir, context.codeCacheDir,
                context.getDatabasePath(f.name).parentFile!!)) {
                for (file in root.walkTopDown().filter { it.isFile })
                    assertFalse("PRIVATE_RESPONSE_PERSISTED", file.readBytes().toString(Charsets.UTF_8).contains(marker))
            }
        } finally { f.close() }
    }

    @Test fun runtimeExceptionIsRecordedAsFixedFailureAndNeverAsExceptionText() = runBlocking {
        val f = Fixture(context)
        try {
            f.prepare()
            ResumeProcessingRun(f.repository, f.pages { throw IllegalStateException("PRIVATE_EXCEPTION_${f.suffix}") }).resume(f.run.runId)
            val attempt = f.repository.checkpoints(f.run.runId).single().inferenceAttempt!!
            assertEquals(InferenceFailure.RUNTIME_FAILURE, attempt.failure)
            assertEquals(ProposalDisposition.ENGINE_FAILURE, attempt.disposition)
            assertTrue(attempt.requestSubmitted); assertNull(attempt.loadMillis); assertNull(attempt.generationMillis)
            assertFalse(ValidationJson.format.encodeToString(attempt).contains("PRIVATE_EXCEPTION"))
            assertEquals(f.configuration, attempt.configuration)
        } finally { f.close() }
    }

    @Test fun mismatchedCheckpointIsRejectedAtomicallyAndOldTraceSurvivesAnotherConfiguration() = runBlocking {
        val f = Fixture(context)
        try {
            f.prepare()
            val attemptId = UUID.randomUUID().toString()
            f.repository.startInferenceAttempt(f.run.runId, 1, attemptId)
            val accepted = PageInferenceAttempt(ProposalDisposition.ACCEPTED, InferenceOutcome.SUCCEEDED,
                loadMillis = 12, generationMillis = 34, configuration = f.configuration, requestSubmitted = true)
            val batch = PageBatch(PageCheckpoint(f.run.runId, 1, ExtractionMode.ASSISTED, PageState.COMPLETE, 100,
                inferenceOutcome = InferenceOutcome.SUCCEEDED, inferenceAttempt = accepted,
                inferenceAttemptId = attemptId), emptyList(), emptyList(), emptyList())
            for (bad in listOf(accepted.copy(configuration = f.configuration.copy(modelSha256 = "d".repeat(64))),
                accepted.copy(configuration = f.configuration.copy(runtimeVersion = "other-runtime")),
                accepted.copy(configuration = f.configuration.copy(promptVersion = "other-prompt")))) {
                try { f.repository.commitPage(batch.copy(checkpoint = batch.checkpoint.copy(inferenceAttempt = bad)))
                    fail("MISMATCHED_ATTEMPT_ACCEPTED") } catch (_: IllegalArgumentException) { }
                assertEquals(InferenceAttemptState.STARTED, f.repository.checkpoints(f.run.runId).single().inferenceHistory.single().state)
                assertTrue(f.repository.observations(f.run.runId).isEmpty())
            }
            try { f.repository.commitPage(batch.copy(checkpoint = batch.checkpoint.copy(inferenceOutcome = InferenceOutcome.FALLBACK)))
                fail("MISMATCHED_OUTCOME_ACCEPTED") } catch (_: IllegalArgumentException) { }
            assertTrue(f.repository.commitPage(batch))
            assertFalse(f.repository.commitPage(batch.copy(checkpoint = batch.checkpoint.copy(durationMillis = 200))))
            val updated = f.run.copy(runId = UUID.randomUUID().toString(), parentRunId = f.run.runId,
                inference = f.configuration.copy(modelRevision = "revision-2", modelSha256 = "e".repeat(64), runtimeVersion = "runtime-2"))
            f.repository.createRun(updated)
            f.factory.open().close()
            assertEquals(accepted, f.repository.checkpoints(f.run.runId).single().inferenceAttempt)
            assertEquals(100L, f.repository.checkpoints(f.run.runId).single().durationMillis)
            assertEquals(InferenceAttemptState.COMPLETED, f.repository.checkpoints(f.run.runId).single().inferenceHistory.single().state)
            assertEquals(f.configuration, f.repository.findRun(f.run.runId)!!.inference)
            assertEquals(updated.inference, f.repository.findRun(updated.runId)!!.inference)
        } finally { f.close() }
    }

    @Test fun durableUnfinishedIntentReopensAndIsPreservedAsInterruptedOnNextSubmission() = runBlocking {
        val f = Fixture(context)
        try {
            f.prepare()
            val firstId = UUID.randomUUID().toString()
            f.repository.startInferenceAttempt(f.run.runId, 1, firstId)
            val before = f.repository.checkpoints(f.run.runId).single()
            f.repository.startInferenceAttempt(f.run.runId, 1, firstId)
            assertEquals(before, f.repository.checkpoints(f.run.runId).single())
            assertNull(before.inferenceAttempt); assertNull(before.inferenceOutcome)
            assertEquals(InferenceAttemptState.STARTED, before.inferenceHistory.single().state)
            f.factory.open().close()
            assertEquals(before, f.repository.checkpoints(f.run.runId).single())
            ResumeProcessingRun(f.repository, f.pages { GenerationResult.Failed(InferenceFailure.MODEL_UNAVAILABLE) }).resume(f.run.runId)
            val history = f.repository.checkpoints(f.run.runId).single().inferenceHistory
            assertEquals(2, history.size)
            assertEquals(firstId, history.first().attemptId)
            assertEquals(InferenceAttemptState.INTERRUPTED, history.first().state)
            assertNull(history.first().result); assertNotNull(history.first().endedAt)
            assertEquals(f.configuration, history.first().configuration)
            assertEquals(InferenceAttemptState.COMPLETED, history.last().state)
            assertEquals(InferenceFailure.MODEL_UNAVAILABLE, history.last().result!!.failure)
            assertEquals(5, f.repository.observations(f.run.runId).size)
        } finally { f.close() }
    }

    @Test fun cancellationPreservesIntentWithoutPublishingPageAndLateCancellationAfterDeletionIsHarmless() = runBlocking {
        val f = Fixture(context)
        try {
            f.prepare()
            val entered = CompletableDeferred<Unit>()
            val never = CompletableDeferred<GenerationResult>()
            val processing = async { ResumeProcessingRun(f.repository, f.pages { entered.complete(Unit); never.await() }).resume(f.run.runId) }
            entered.await()
            val pending = f.repository.checkpoints(f.run.runId).single().inferenceHistory.single()
            assertEquals(InferenceAttemptState.STARTED, pending.state)
            f.repository.stopRunIfActive(f.run.runId, RunState.CANCELLED)
            processing.cancelAndJoin()
            val checkpoint = f.repository.checkpoints(f.run.runId).single()
            assertEquals(PageState.CANCELLED, checkpoint.state)
            assertEquals(InferenceOutcome.CANCELLED, checkpoint.inferenceOutcome)
            assertEquals(InferenceAttemptState.CANCELLED, checkpoint.inferenceHistory.single().state)
            assertNull(checkpoint.inferenceHistory.single().result)
            assertTrue(f.repository.observations(f.run.runId).isEmpty())
            f.repository.deleteCompletely(f.documentId)
            f.repository.cancelInferenceAttempt(f.run.runId, 1, pending.attemptId)
            assertTrue(f.repository.checkpoints(f.run.runId).isEmpty())
            try { f.repository.startInferenceAttempt(f.run.runId, 1, UUID.randomUUID().toString())
                fail("DELETED_ATTEMPT_RECREATED") } catch (_: IllegalStateException) { }
        } finally { f.close() }
    }

    @Test fun exhaustedAttemptHistoryFailsClosedWithoutDiscardingEarlierIntents() = runBlocking {
        val f = Fixture(context)
        try {
            f.prepare()
            repeat(InferenceAttemptRecord.MAX_PER_PAGE) {
                f.repository.startInferenceAttempt(f.run.runId, 1, UUID.randomUUID().toString())
            }
            val before = f.repository.checkpoints(f.run.runId).single()
            try { f.repository.startInferenceAttempt(f.run.runId, 1, UUID.randomUUID().toString())
                fail("ATTEMPT_HISTORY_LIMIT_IGNORED") } catch (_: IllegalArgumentException) { }
            assertEquals(before, f.repository.checkpoints(f.run.runId).single())
            assertEquals(InferenceAttemptRecord.MAX_PER_PAGE, before.inferenceHistory.size)
        } finally { f.close() }
    }

    @Test fun cancellationAfterFinalButBeforeCommitClosesIntentAndRejectsTheLatePage() = runBlocking {
        val f = Fixture(context)
        try {
            f.prepare()
            val batch = f.pages { GenerationResult.Failed(InferenceFailure.LOAD_TIMEOUT) }.process(f.run, 1)
            assertTrue(batch.checkpoint.inferenceAttempt!!.requestSubmitted)
            assertEquals(InferenceAttemptState.STARTED, f.repository.checkpoints(f.run.runId).single().inferenceHistory.single().state)
            f.repository.stopRunIfActive(f.run.runId, RunState.CANCELLED)
            val stopped = f.repository.checkpoints(f.run.runId).single()
            assertEquals(InferenceAttemptState.CANCELLED, stopped.inferenceHistory.single().state)
            assertNull(stopped.inferenceHistory.single().result)
            try { f.repository.commitPage(batch); fail("LATE_PAGE_COMMITTED") } catch (_: IllegalArgumentException) { }
            assertEquals(stopped, f.repository.checkpoints(f.run.runId).single())
            assertTrue(f.repository.observations(f.run.runId).isEmpty())
        } finally { f.close() }
    }
}
