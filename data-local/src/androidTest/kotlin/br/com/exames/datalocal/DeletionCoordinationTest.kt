package br.com.exames.datalocal

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.exames.datadocuments.PdfPreviewRenderer
import br.com.exames.domain.evaluator.DeterministicRangeEvaluator
import br.com.exames.domain.inference.*
import br.com.exames.domain.model.*
import br.com.exames.domain.parser.BiochemistryParser
import br.com.exames.domain.usecase.*
import br.com.exames.domain.validation.*
import br.com.exames.fixtures.SyntheticPdf
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DeletionCoordinationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private inner class Fixture {
        val suffix = UUID.randomUUID().toString(); val name = "delete-$suffix.db"; val alias = "delete-$suffix"
        val keys = AndroidPayloadKeys(alias); val factory = ProtectedDatabaseFactory(context, name, keys)
        val artifacts = EncryptedArtifactStore(context)
        val documentId = UUID.randomUUID().toString(); val runId = UUID.randomUUID().toString()
        val jobs = mutableSetOf(runId)
        var failCancellation = false
        var cancelEvent: (() -> Unit)? = null
        val scheduler = object : RunWorkScheduler {
            override suspend fun enqueue(runId: String) { jobs += runId }
            override suspend fun cancel(runId: String) {
                assertNull(factory.open().records().document(documentId))
                assertNotNull(factory.open().records().tombstone(documentId))
                cancelEvent?.invoke()
                if (failCancellation) throw IOException("SYNTHETIC_SCHEDULER_FAILURE")
                jobs -= runId
            }
            override suspend fun activeRunIds() = jobs.toSet()
        }
        val repository = ProtectedValidationRepository(factory, cancelWork = { ids -> ids.forEach { scheduler.cancel(it) } })
        lateinit var document: Document
        lateinit var run: ProcessingRun
        val directory get() = File(context.noBackupFilesDir, "artifacts/$documentId")
        suspend fun prepare() {
            val bytes = SyntheticPdf.biochemistry(false)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            artifacts.write(documentId, "original", bytes.inputStream()); bytes.fill(0)
            document = Document(documentId, "artifact:original", hash, "application/pdf", Instant.EPOCH, 1)
            repository.save(document)
            run = ProcessingRun(runId, documentId, hash, null, ExtractionMode.BASELINE, PrivateRunPages.VERSION,
                BiochemistryParser.VERSION, DeterministicRangeEvaluator.RULE_VERSION, RunState.RUNNING,
                Instant.EPOCH.toString(), "synthetic", "36")
            repository.createRun(run)
        }
        fun pages(): PrivateRunPages {
            val pdf = PdfPreviewRenderer(context, LocalDataModule.pdfSource(artifacts, repository))
            val inference = object : TextInference {
                override suspend fun generate(mode: ExtractionMode, request: GenerationRequest): GenerationResult = error("BASELINE_MODEL_CALL")
            }
            return PrivateRunPages(PrivatePageLoader(repository, artifacts, pdf), pdf, ProcessBiochemistryPage(inference))
        }
        suspend fun cleanup() {
            artifacts.deleteDocument(documentId); factory.open().close(); context.deleteDatabase(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    @Test fun deletingDuringInFlightPageHidesImmediatelyCancelsAndRejectsLateCallbacksAfterAcknowledgement() = runBlocking {
        val f = Fixture()
        try {
            f.prepare(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val actual = f.pages()
            val delayed = object : RunPageProcessor by actual {
                override suspend fun process(run: ProcessingRun, page: Int): PageBatch {
                    val batch = actual.process(run, page); entered.complete(Unit); release.await(); return batch
                }
            }
            val processing = async { ResumeProcessingRun(f.repository, delayed).resume(f.runId) }
            entered.await(); f.repository.deleteCompletely(f.documentId)
            assertNull(f.repository.find(f.documentId)); assertTrue(f.jobs.isEmpty())
            assertEquals(listOf(f.runId), f.factory.open().records().deletionRunIds(f.documentId))
            assertEquals(1, ProtectedDeletionCleanup(f.factory, f.artifacts, f.scheduler).run())
            release.complete(Unit)
            assertEquals(ResumeRunResult.UNAVAILABLE, processing.await())
            assertFalse(f.directory.exists()); assertTrue(f.repository.observations(f.runId).isEmpty())
            assertTrue(f.factory.open().records().pendingDeletions().isEmpty())
            assertNotNull(f.factory.open().records().tombstone(f.documentId))
            f.factory.open().close()
            try { f.repository.save(f.document); fail("Late document resurrected") } catch (_: IllegalStateException) { }
            try { EncryptedArtifactStore(context).write(f.documentId, "late-ocr", "synthetic".byteInputStream()); fail("Late artifact resurrected") }
            catch (_: IOException) { }
        } finally { f.cleanup() }
    }

    @Test fun crashAfterFileCleanupRetainsOutboxAndPermanentBarriersSurviveDatabaseReopen() = runBlocking {
        val f = Fixture()
        try {
            f.prepare(); f.repository.deleteCompletely(f.documentId)
            try {
                ProtectedDeletionCleanup(f.factory, f.artifacts, f.scheduler) { throw IllegalStateException("SIMULATED_PROCESS_DEATH") }.run()
                fail("Interruption not injected")
            } catch (_: IllegalStateException) { }
            assertFalse(f.directory.exists()); assertEquals(1, f.factory.open().records().pendingDeletions().size)
            f.factory.open().close()
            val reopened = ProtectedDatabaseFactory(context, f.name, f.keys)
            try {
                assertEquals(1, ProtectedDeletionCleanup(reopened, EncryptedArtifactStore(context), f.scheduler).run())
                assertTrue(reopened.open().records().pendingDeletions().isEmpty())
                assertTrue(reopened.open().records().deletionRunIds(f.documentId).isEmpty())
                assertNotNull(reopened.open().records().tombstone(f.documentId))
                try { ProtectedValidationRepository(reopened).save(f.document); fail("Tombstone removed with outbox") } catch (_: IllegalStateException) { }
            } finally { reopened.open().close() }
        } finally { f.cleanup() }
    }

    @Test fun cancellationFailureNeverAcknowledgesOutboxAndRetryFinishesSafely() = runBlocking {
        val f = Fixture()
        try {
            f.prepare(); f.failCancellation = true; f.repository.deleteCompletely(f.documentId)
            assertNull(f.repository.find(f.documentId)); assertTrue(f.directory.exists())
            try { ProtectedDeletionCleanup(f.factory, f.artifacts, f.scheduler).run(); fail("Cancellation failure ignored") } catch (_: IOException) { }
            assertEquals(1, f.factory.open().records().pendingDeletions().size)
            f.failCancellation = false
            ProtectedDeletionCleanup(f.factory, f.artifacts, f.scheduler).run()
            assertFalse(f.directory.exists()); assertTrue(f.jobs.isEmpty())
        } finally { f.cleanup() }
    }

    @Test fun cleanupWaitsForActiveArtifactWriterAndFutureWritersArePermanentlyBlocked() = runBlocking {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            f.prepare()
            val input = object : InputStream() {
                var sent = false
                override fun read(): Int = error("UNEXPECTED_SINGLE_READ")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (sent) return -1
                    entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); sent = true
                    buffer[offset] = 42; return 1
                }
            }
            val writer = async(Dispatchers.IO) { EncryptedArtifactStore(context).write(f.documentId, "ocr", input) }
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            f.repository.deleteCompletely(f.documentId)
            val cancelling = CompletableDeferred<Unit>(); f.cancelEvent = { cancelling.complete(Unit) }
            val cleaning = async(Dispatchers.IO) { ProtectedDeletionCleanup(f.factory, f.artifacts, f.scheduler).run() }
            cancelling.await()
            assertEquals(1, f.factory.open().records().pendingDeletions().size)
            release.countDown(); writer.await(); cleaning.await()
            assertFalse(f.directory.exists()); assertTrue(f.factory.open().records().pendingDeletions().isEmpty())
            try { f.artifacts.write(f.documentId, "late", byteArrayOf(1).inputStream()); fail("Writer entered after cleanup") } catch (_: IOException) { }
        } finally { release.countDown(); f.cleanup() }
    }

    @Test fun migrationFromProtectedV2PreservesCiphertextAndBackfillsPendingTombstones() = runBlocking {
        val f = Fixture()
        try {
            f.prepare()
            val before = f.factory.open().records().document(f.documentId)!!.payload
            f.factory.open().close()
            // v2 tables/payloads are unchanged. Remove only v3 additions and restore the exported v2 identity.
            SQLiteDatabase.openDatabase(context.getDatabasePath(f.name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TABLE deletion_work"); db.execSQL("DROP TABLE document_tombstones")
                db.execSQL("INSERT INTO pending_deletions(documentId) VALUES (?)", arrayOf("synthetic-old-pending"))
                db.execSQL("UPDATE room_master_table SET identity_hash = ? WHERE id = 42", arrayOf("a9a3318f1d04a2a5274e75db6ed5219b"))
                db.version = 2
            }
            val upgraded = ProtectedDatabaseFactory(context, f.name, f.keys)
            try {
                val db = upgraded.open()
                assertEquals(3, db.openHelper.readableDatabase.version)
                assertArrayEquals(before, db.records().document(f.documentId)!!.payload)
                assertEquals(f.document, ProtectedValidationRepository(upgraded).find(f.documentId))
                assertNotNull(db.records().tombstone("synthetic-old-pending"))
                assertEquals(f.runId, db.records().runs(f.documentId).single().id)
            } finally { upgraded.open().close() }
        } finally { f.cleanup() }
    }

    @Test fun deletingWhileSimulatedInferenceIsWaitingPreventsItsFinalFromCreatingOutputs() = runBlocking {
        val f = Fixture()
        try {
            f.prepare(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val configuration = InferenceConfiguration("synthetic", "synthetic", "b".repeat(64), "synthetic", "synthetic",
                ProposalProtocol.PROMPT_VERSION, ProposalProtocol.SCHEMA_VERSION, "synthetic", 2048, 1024, null, 1000, emptyMap())
            val run = f.run.copy(runId = UUID.randomUUID().toString(), mode = ExtractionMode.ASSISTED, inference = configuration)
            f.repository.createRun(run); f.jobs += run.runId
            val simulated = object : TextInference {
                override fun configuration(promptVersion: String, schemaVersion: String) = configuration
                override suspend fun generate(mode: ExtractionMode, request: GenerationRequest): GenerationResult {
                    entered.complete(Unit); release.await()
                    return GenerationResult.UnvalidatedFinal("""{"schema":"biochemistry-fields/1","observations":[]}""", 0, 0)
                }
            }
            val pdf = PdfPreviewRenderer(context, LocalDataModule.pdfSource(f.artifacts, f.repository))
            val pages = PrivateRunPages(PrivatePageLoader(f.repository, f.artifacts, pdf), pdf,
                ProcessBiochemistryPage(simulated), attempts = f.repository, assistedCompatible = { true })
            val processing = async { ResumeProcessingRun(f.repository, pages).resume(run.runId) }
            entered.await(); f.repository.deleteCompletely(f.documentId)
            ProtectedDeletionCleanup(f.factory, f.artifacts, f.scheduler).run()
            release.complete(Unit)
            assertEquals(ResumeRunResult.UNAVAILABLE, processing.await())
            assertTrue(f.repository.observations(run.runId).isEmpty()); assertTrue(f.repository.assessments(run.runId).isEmpty())
            assertTrue(f.jobs.isEmpty()); assertFalse(f.directory.exists())
        } finally { f.cleanup() }
    }

    @Test fun corruptedArtifactGuardFailsClosed() {
        val id = UUID.randomUUID().toString(); val store = EncryptedArtifactStore(context)
        try {
            store.deleteDocument(id)
            File(context.noBackupFilesDir, "artifact-guards/$id").writeBytes(byteArrayOf(0))
            try { store.write(id, "original", byteArrayOf(1).inputStream()); fail("Invalid barrier accepted") } catch (_: IOException) { }
        } finally { store.deleteDocument(id) }
    }
}
