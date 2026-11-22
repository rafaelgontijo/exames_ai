package br.com.exames.datalocal

import android.content.Context
import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import br.com.exames.domain.model.Document
import br.com.exames.domain.summary.SummaryText
import br.com.exames.domain.summary.SummaryTextResult
import br.com.exames.domain.validation.*
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ResultShareStoreTest {
    private class Fixture {
        val id = UUID.randomUUID().toString()
        val base: Context = ApplicationProvider.getApplicationContext()
        val root = File(base.noBackupFilesDir, id).apply { mkdir() }
        val context = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = root }
        val factory = ProtectedDatabaseFactory(base, "$id.db", AndroidPayloadKeys("db-$id"))
        val artifacts = EncryptedArtifactStore(context, "file-$id")
        var now = 1000L
        fun session() = ResultShareStore(context, factory, artifacts) { now }
        val store = session()
        val repo = ProtectedValidationRepository(factory, deletionBarrier = store::deleting)
        val doc = Document(id, "artifact:original", "a".repeat(64), "application/pdf", Instant.EPOCH, 1)
        val run = ProcessingRun(UUID.randomUUID().toString(), id, doc.hash, null, ExtractionMode.BASELINE,
            "ocr", "parser", "rule", RunState.RUNNING, Instant.EPOCH.toString(), "synthetic", "24")
        fun summary(text: String) = SummaryTextResult.Rendered(text, text.toByteArray().size, 0, "synthetic")
        suspend fun issue(text: String) = store.prepare(id, run.runId, summary(text))
        suspend fun cleanup() {
            factory.open().close()
            base.deleteDatabase("$id.db")
            root.deleteRecursively()
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for (key in listOf("file-$id", "db-$id")) if (keys.containsAlias(key)) keys.deleteEntry(key)
        }
    }
    private fun test(body: suspend Fixture.() -> Unit) = runBlocking {
        val f = Fixture()
        try { f.repo.save(f.doc); f.repo.createRun(f.run); f.body() }
        finally { f.cleanup() }
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block() } catch (_: Exception) { return }
        fail("Unexpected access")
    }

    @Test fun exactUtf8LimitCiphertextAndMonotonicExpiry() = test {
        val text = "ç".repeat(SummaryText.LIMIT_BYTES / 2)
        val uri = issue(text)
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(store.open(uri)).use { it.readBytes() }
        assertEquals(text, bytes.toString(Charsets.UTF_8))
        assertEquals(2, artifacts.exportArtifacts().size)
        assertFalse(root.walk().filter { it.isFile }.any { it.readBytes().toString(Charsets.UTF_8).contains(text.take(32)) })
        rejected { issue(text + "x") }
        rejected { store.prepare("other", run.runId, summary("marker")) }
        rejected { store.size(uri.buildUpon().appendPath("extra").build()) }
        now += ResultShareStore.TTL - 1
        assertEquals(bytes.size, store.size(uri))
        now++
        rejected { store.open(uri) }
        assertTrue(artifacts.exportArtifacts().isEmpty())
    }

    @Test fun newSessionDeniesOldUriAndDeletesOnlyEncryptedExports() = test {
        artifacts.write(id, "original", "original synthetic".byteInputStream())
        val uri = issue("synthetic summary")
        val dir = File(root, "artifacts/$id")
        val ciphertext = dir.listFiles()!!.first { it.name.startsWith("share-text-") }
        ciphertext.copyTo(File(dir, ciphertext.nameWithoutExtension + ".pending.enc"))
        assertEquals(3, artifacts.exportArtifacts().size)
        val restarted = session()
        rejected { restarted.open(uri) }
        restarted.reconcile()
        assertTrue(artifacts.exportArtifacts().isEmpty())
        assertEquals("original synthetic", artifacts.read(id, "original").decodeToString())
    }

    @Test fun deletionClosesWritersAndBlocksLateRequestsThroughOutbox() = test {
        val uri = issue("a".repeat(SummaryText.LIMIT_BYTES))
        val pipe = store.open(uri)
        repo.deleteCompletely(id)
        rejected { store.open(uri) }
        rejected { issue("late") }
        withTimeout(3000) { withContext(Dispatchers.IO) {
            // The kernel may already have received bytes. After deletion the reader reaches EOF.
            ParcelFileDescriptor.AutoCloseInputStream(pipe).use { assertTrue(it.readBytes().size <= SummaryText.LIMIT_BYTES) }
        } }
        val dao = factory.open().records()
        assertNotNull(dao.tombstone(id))
        assertTrue(dao.pendingDeletions().any { it.documentId == id })
        val weights = File(root, "models/synthetic.litertlm").apply {
            parentFile!!.mkdirs()
            writeText("synthetic-weight-marker")
        }
        val noJobs = object : br.com.exames.domain.usecase.RunWorkScheduler {
            override suspend fun enqueue(runId: String) = Unit
            override suspend fun cancel(runId: String) = Unit
            override suspend fun activeRunIds() = emptySet<String>()
        }
        rejected {
            ProtectedDeletionCleanup(factory, artifacts, noJobs) { throw java.io.IOException("synthetic interruption") }.run()
        }
        assertTrue(dao.pendingDeletions().any { it.documentId == id })
        factory.open().close()
        assertEquals(1, ProtectedDeletionCleanup(factory, artifacts, noJobs).run())
        assertTrue(factory.open().records().pendingDeletions().isEmpty())
        assertTrue(artifacts.exportArtifacts().isEmpty())
        assertEquals("synthetic-weight-marker", weights.readText())
        rejected { session().open(uri) }
    }

    @Test fun failedFilePublicationOrLostKeyNeverServesPlaintext() = test {
        val dir = File(root, "artifacts/$id")
        dir.parentFile!!.mkdirs()
        dir.writeText("synthetic write obstruction")
        rejected { issue("PRIVATE-SYNTHETIC-MARKER") }
        dir.delete()
        val uri = issue("PRIVATE-SYNTHETIC-MARKER")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("file-$id") }
        rejected { store.open(uri) }
        rejected { issue("PRIVATE-SYNTHETIC-MARKER") }
        assertFalse(root.walk().filter { it.isFile }.any {
            it.readBytes().toString(Charsets.UTF_8).contains("PRIVATE-SYNTHETIC-MARKER")
        })
    }
}
