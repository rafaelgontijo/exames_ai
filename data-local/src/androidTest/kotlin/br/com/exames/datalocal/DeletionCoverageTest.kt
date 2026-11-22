package br.com.exames.datalocal

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.exames.domain.model.*
import br.com.exames.domain.inference.*
import br.com.exames.domain.usecase.RunWorkScheduler
import br.com.exames.domain.validation.*
import br.com.exames.fixtures.SyntheticPdf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeletionCoverageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val noJobs = object : RunWorkScheduler {
        override suspend fun enqueue(runId: String) = Unit
        override suspend fun cancel(runId: String) = Unit
        override suspend fun activeRunIds() = emptySet<String>()
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun allOwnedArtifactsAndStructuredDerivativesDisappearWhileOtherDocumentAndWeightsSurvive() = runBlocking {
        val suffix = UUID.randomUUID().toString(); val name = "coverage-$suffix.db"; val alias = "coverage-$suffix"
        val factory = ProtectedDatabaseFactory(context, name, AndroidPayloadKeys(alias))
        val repository = ProtectedValidationRepository(factory); val store = EncryptedArtifactStore(context)
        val documentId = UUID.randomUUID().toString(); val otherId = UUID.randomUUID().toString()
        val runId = UUID.randomUUID().toString(); val observationId = UUID.randomUUID().toString(); val caseId = UUID.randomUUID().toString()
        val marker = "SYNTHETIC-DELETE-CONTENT-$suffix"
        val weights = File(context.noBackupFilesDir, "models/synthetic-$suffix.litertlm")
        try {
            val bytes = SyntheticPdf.biochemistry(false) + "\n%$marker\n".toByteArray()
            val document = Document(documentId, "artifact:original", hash(bytes), "application/pdf", Instant.EPOCH, 1)
            store.write(documentId, "original", bytes.inputStream()); bytes.fill(0); repository.save(document)
            repository.save(document.copy(id = otherId))
            store.write(otherId, "original", "unrelated-synthetic-source".byteInputStream())
            for (id in listOf("preview", "ocr-text", "ocr-spans", "trace-attempt", "trace-final")) {
                store.write(documentId, id, marker.byteInputStream())
                assertEquals(marker, store.read(documentId, id).toString(Charsets.UTF_8))
            }
            val directory = File(context.noBackupFilesDir, "artifacts/$documentId")
            File(directory, "pending-interrupted.enc").writeBytes(File(directory, "ocr-text.enc").readBytes())
            weights.parentFile!!.mkdirs(); weights.writeBytes("synthetic-static-weights".toByteArray())
            val weightsHash = hash(weights.readBytes())
            val inference = InferenceConfiguration("synthetic", "synthetic", "b".repeat(64), "synthetic", "synthetic",
                "synthetic", "synthetic", "synthetic", 2048, 1024, null, 1000, mapOf("synthetic-canary" to marker))
            val run = ProcessingRun(runId, documentId, document.hash, caseId, ExtractionMode.ASSISTED, "synthetic", "synthetic",
                "REF_NUMERIC/2.0.0", RunState.RUNNING, Instant.EPOCH.toString(), "synthetic", "36", inference)
            repository.createRun(run)
            val secondId = UUID.randomUUID().toString()
            repository.createRun(run.copy(runId = secondId, parentRunId = runId, state = RunState.QUEUED))
            val evidence = ExtractedFieldEvidence("resultRaw", marker, SourceRegion(1, 10.0, 10.0, 20.0, 20.0, 600.0, 800.0),
                "synthetic", "SYNTHETIC", "synthetic")
            val reference = ReferenceRange(observationId, marker, "u/L", 1, "10".toBigDecimal(), "20".toBigDecimal(),
                applicability = Applicability.APPLICABLE)
            val observation = Observation(observationId, documentId, "Glicose", "15", "u/L", 1, marker,
                numericValue = "15".toBigDecimal(), ambiguities = setOf(Ambiguity.VALUE), fieldEvidence = listOf(evidence),
                referenceCandidates = listOf(reference), referenceRaw = marker)
            val attempt = PageInferenceAttempt(ProposalDisposition.ENGINE_FAILURE, InferenceOutcome.FALLBACK,
                InferenceFailure.GENERATION_TIMEOUT, configuration = inference, requestSubmitted = true)
            val attemptId = UUID.randomUUID().toString()
            repository.startInferenceAttempt(runId, 1, attemptId)
            val batch = PageBatch(PageCheckpoint(runId, 1, run.mode, PageState.COMPLETE, 1, "ENGINE_FAILURE", InferenceOutcome.FALLBACK,
                inferenceAttempt = attempt, inferenceAttemptId = attemptId),
                listOf(observation), listOf(reference), listOf(Assessment(observationId, AssessmentStatus.NEEDS_REVIEW,
                    ReasonCode.AMBIGUOUS_INPUT, Instant.EPOCH, run.ruleVersion)))
            repository.commitPage(batch)
            assertEquals(attempt, repository.checkpoints(runId).single().inferenceAttempt)
            val correction = ManualCorrection(UUID.randomUUID().toString(), observationId, CorrectionField.UNIT, "u/L", marker, Instant.EPOCH)
            repository.appendCorrection(correction)
            val expected = ExpectedObservation("synthetic-expected", Analyte.GLUCOSE, "Glicose", "15", "u/L", marker,
                "15", "10", "20", ExpectedStatus.NEEDS_REVIEW, "AMBIGUOUS_INPUT", true, listOf(evidence))
            val truth = GroundTruthVersion(caseId = caseId, version = "1", reviewState = ReviewState.REVIEWED,
                reviewerId = "synthetic", reviewedAt = Instant.EPOCH.toString(), observations = listOf(expected))
            val snapshot = GroundTruthSnapshot(hash(ValidationJson.format.encodeToString(truth).toByteArray()), truth)
            repository.appendGroundTruth(documentId, snapshot)
            val comparison = Comparison(UUID.randomUUID().toString(), runId, caseId, "1", snapshot.sha256, Instant.EPOCH.toString(),
                listOf(FieldDifference(expected.observationId, "referenceRaw", marker, marker)), emptyList(), emptyList())
            repository.appendComparison(comparison)
            repository.appendComparison(comparison.copy(comparisonId = UUID.randomUUID().toString(), kind = MeasurementKind.EVALUATOR_ISOLATED))
            assertEquals(2, repository.comparisons(runId).size)
            // Dynamic canary cannot occur in the test APK itself; inspect all app-owned persistent/cache roots.
            fun noPlaintext() {
                for (root in listOf(context.noBackupFilesDir, context.filesDir, context.cacheDir, context.codeCacheDir,
                    context.getDatabasePath(name).parentFile!!)) {
                    for (file in root.walkTopDown().filter { it.isFile })
                        assertFalse("PLAINTEXT_CANARY_FOUND", file.readBytes().toString(Charsets.UTF_8).contains(marker))
                }
            }
            noPlaintext()
            repository.deleteCompletely(documentId)
            ProtectedDeletionCleanup(factory, store, noJobs).run()
            val dao = factory.open().records()
            assertNull(repository.find(documentId)); assertNull(repository.findRun(runId)); assertNull(repository.findRun(secondId))
            assertTrue(dao.runs(documentId).isEmpty()); assertTrue(repository.observations(runId).isEmpty())
            assertNull(repository.reference(observationId)); assertTrue(repository.assessments(runId).isEmpty())
            assertTrue(repository.corrections(observationId).isEmpty()); assertTrue(repository.groundTruth(documentId).isEmpty())
            assertTrue(repository.comparisons(runId).isEmpty()); assertTrue(repository.checkpoints(runId).isEmpty())
            assertTrue(dao.deletionRunIds(documentId).isEmpty()); assertTrue(dao.pendingDeletions().isEmpty())
            assertNotNull(dao.tombstone(documentId)); assertFalse(directory.exists())
            val lateWriters: List<suspend () -> Unit> = listOf(
                { repository.save(document) }, { repository.createRun(run) }, { repository.commitPage(batch); Unit },
                { repository.appendCorrection(correction.copy(id = UUID.randomUUID().toString())) },
                { repository.appendGroundTruth(documentId, snapshot.copy(groundTruth = truth.copy(version = "2"))) },
                { repository.appendComparison(comparison.copy(comparisonId = UUID.randomUUID().toString())) },
                { repository.updateRunState(runId, RunState.COMPLETE, InferenceOutcome.SUCCEEDED) },
                { store.write(documentId, "late-final", marker.byteInputStream()); Unit })
            for (write in lateWriters) {
                try { write(); fail("DELETED_DERIVATIVE_RECREATED") } catch (_: Exception) { }
            }
            factory.open().close()
            assertNotNull(repository.find(otherId)); assertEquals("unrelated-synthetic-source", store.read(otherId, "original").toString(Charsets.UTF_8))
            assertEquals(weightsHash, hash(weights.readBytes())); assertFalse(directory.exists()); noPlaintext()
        } finally {
            store.deleteDocument(documentId); store.deleteDocument(otherId); weights.delete()
            factory.open().close(); context.deleteDatabase(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    @Test fun lostPayloadKeyLeavesPendingDeletionAndEncryptedSourceUntouchedOnReopening() = runBlocking {
        val suffix = UUID.randomUUID().toString(); val name = "lost-key-$suffix.db"; val alias = "lost-key-$suffix"
        val id = UUID.randomUUID().toString(); val keys = AndroidPayloadKeys(alias)
        val factory = ProtectedDatabaseFactory(context, name, keys); val repository = ProtectedValidationRepository(factory)
        val store = EncryptedArtifactStore(context)
        try {
            val bytes = SyntheticPdf.biochemistry(false)
            store.write(id, "original", bytes.inputStream())
            repository.save(Document(id, "artifact:original", hash(bytes), "application/pdf", Instant.EPOCH, 1)); bytes.fill(0)
            repository.deleteCompletely(id); factory.open().close()
            val db = context.getDatabasePath(name); val originalDatabase = hash(db.readBytes())
            val source = File(context.noBackupFilesDir, "artifacts/$id/original.enc"); val originalSource = hash(source.readBytes())
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            try {
                ProtectedDeletionCleanup(ProtectedDatabaseFactory(context, name, keys), store, noJobs).run()
                fail("LOST_KEY_IGNORED")
            } catch (_: PayloadKeyUnavailableException) { }
            assertFalse(keys.exists()); assertEquals(originalDatabase, hash(db.readBytes())); assertEquals(originalSource, hash(source.readBytes()))
            SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
                raw.rawQuery("SELECT COUNT(*) FROM pending_deletions WHERE documentId = ?", arrayOf(id)).use { row ->
                    assertTrue(row.moveToFirst()); assertEquals(1, row.getInt(0))
                }
            }
        } finally {
            store.deleteDocument(id); context.deleteDatabase(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
