package br.com.exames.datalocal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.exames.datadocuments.CapturedPageNormalizer
import br.com.exames.datadocuments.NormalizedPage
import br.com.exames.datadocuments.SafDocumentReader
import br.com.exames.domain.model.Document
import br.com.exames.domain.repository.DocumentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Confirmed camera captures enter the same protected path as picked files. Synthetic pixels only. */
@RunWith(AndroidJUnit4::class)
class CapturedImportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val artifacts = EncryptedArtifactStore(context)
    private class Repository : DocumentRepository {
        val rows = mutableMapOf<String, Document>()
        var rejectSave = false
        var unavailable = false
        var onSave: (() -> Unit)? = null
        override fun observeDocuments() = MutableStateFlow(rows.values.toList())
        override suspend fun find(id: String): Document? { check(!unavailable) { "SYNTHETIC_DB_UNAVAILABLE" }; return rows[id] }
        override suspend fun save(document: Document) {
            onSave?.invoke()
            check(!rejectSave) { "SYNTHETIC_SAVE_FAILED" }; rows[document.id] = document
        }
        override suspend fun deleteCompletely(id: String) { rows.remove(id) }
    }
    private val repository = Repository()
    private fun importer() = ProtectedDocumentImporter(context, SafDocumentReader(context.contentResolver), artifacts, repository)
    private val journal = File(context.noBackupFilesDir, "pending-imports")

    /** A page whose pixels are noisy enough that a 256-byte slice of the JPEG identifies it. */
    private fun page(seed: Int = 7): NormalizedPage {
        val bitmap = Bitmap.createBitmap(160, 220, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(seed.toLong())
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            repeat(300) { drawRect(random.nextInt(150).toFloat(), random.nextInt(210).toFloat(), 10f + random.nextInt(150), 10f + random.nextInt(210),
                Paint().apply { color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }) }
        }
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        bitmap.recycle()
        return CapturedPageNormalizer.normalize(jpeg, 0)
    }
    private fun fingerprint(page: NormalizedPage) = page.jpeg.copyOfRange(page.jpeg.size / 2, page.jpeg.size / 2 + 256)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun roots() = listOfNotNull(context.filesDir, context.cacheDir, context.noBackupFilesDir, context.externalCacheDir, context.getExternalFilesDir(null))
    private fun containsPlain(needle: ByteArray) = roots().any { dir ->
        dir.walkTopDown().filter { it.isFile && it.length() >= needle.size }.any { String(it.readBytes(), Charsets.ISO_8859_1).contains(String(needle, Charsets.ISO_8859_1)) }
    }
    private fun pendingMarkers() = journal.listFiles().orEmpty().toList()

    @After fun cleanup() {
        (repository.rows.keys).forEach { artifacts.deleteDocument(it) }
        journal.listFiles()?.forEach { artifacts.deleteDocument(it.name); it.delete() }
    }

    @Test fun confirmedCaptureIsHashedEncryptedAndReopensAfterReconcileWithoutPlaintext() = runBlocking {
        val sample = page(); val bytes = sample.jpeg.copyOf(); val print = fingerprint(sample)
        val document = importer().importCapture(sample)
        assertEquals("image/jpeg", document.mimeType)
        assertEquals("artifact:original", document.uriLocal)
        assertEquals(sha(bytes), document.hash)
        assertTrue("page wiped", sample.jpeg.all { it == 0.toByte() })
        // New importer and store instances stand in for a restart.
        importer().reconcile()
        assertEquals(document, repository.find(document.id))
        assertArrayEquals(bytes, EncryptedArtifactStore(context).read(document.id, "original"))
        assertTrue(pendingMarkers().isEmpty())
        assertFalse("PLAINTEXT_CAPTURE_ON_DISK", containsPlain(print))
    }

    @Test fun persistenceFailurePublishesNothingAndLeavesNoPlaintextOrJournal() = runBlocking {
        val sample = page(); val print = fingerprint(sample)
        repository.rejectSave = true
        try { importer().importCapture(sample); fail("Expected failure") } catch (_: IllegalStateException) { }
        assertTrue(repository.rows.isEmpty())
        assertTrue(pendingMarkers().isEmpty())
        assertTrue(sample.jpeg.all { it == 0.toByte() })
        assertFalse(containsPlain(print))
        assertTrue(File(context.noBackupFilesDir, "artifacts").listFiles().orEmpty().none { File(it, "original.enc").exists() && it.name !in repository.rows })
    }

    @Test fun unavailableDatabaseKeepsEncryptedSourceAndJournalForRecoveryWithoutDuplication() = runBlocking {
        val sample = page()
        repository.onSave = { repository.unavailable = true; throw IllegalStateException("SYNTHETIC_SAVE_FAILED") }
        try { importer().importCapture(sample); fail("Expected failure") } catch (_: IllegalStateException) { }
        assertEquals("encrypted source retained for retry", 1, pendingMarkers().size)
        val id = pendingMarkers().single().name
        assertTrue(File(context.noBackupFilesDir, "artifacts/$id/original.enc").exists())
        repository.unavailable = false; repository.onSave = null
        importer().reconcile() // DB reachable again: the uncommitted orphan is reclaimed
        assertTrue(pendingMarkers().isEmpty()); assertFalse(File(context.noBackupFilesDir, "artifacts/$id").exists())
        assertTrue(repository.rows.isEmpty())
        val again = importer().importCapture(page(8))
        assertEquals(1, repository.rows.size); assertEquals(again.id, repository.rows.keys.single())
    }

    @Test fun cancelledBeforeStartPublishesNothing() = runBlocking {
        val sample = page()
        val job = launch(start = CoroutineStart.LAZY) { importer().importCapture(sample) }
        job.cancelAndJoin()
        assertTrue(repository.rows.isEmpty()); assertTrue(pendingMarkers().isEmpty())
        sample.wipe()
    }

    @Test fun cancellationDuringCommitNeverLeavesAPartialOrDuplicatedDocument() = runBlocking {
        val sample = page(); val bytes = sample.jpeg.copyOf()
        lateinit var job: kotlinx.coroutines.Job
        repository.onSave = { job.cancel() } // caller cancelled exactly while metadata is being published
        job = launch { try { importer().importCapture(sample) } catch (_: CancellationException) { } }
        job.join()
        // The commit section is not cancellable: the document is either fully published or absent, never twice or partial.
        assertEquals(1, repository.rows.size)
        val document = repository.rows.values.single()
        assertArrayEquals(bytes, artifacts.read(document.id, "original"))
        assertTrue(pendingMarkers().isEmpty())
        importer().reconcile()
        assertEquals(1, repository.rows.size)
    }

    @Test fun invalidBytesAreRejectedBeforeAnyJournalOrArtifact() = runBlocking {
        // A page whose bytes are not a complete JPEG cannot be built through the normalizer, so corrupt a real one.
        val sample = page()
        val broken = NormalizedPageForTest.truncated(sample)
        try { importer().importCapture(broken); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        assertTrue(repository.rows.isEmpty()); assertTrue(pendingMarkers().isEmpty())
        sample.wipe()
    }

    @Test fun twoCapturesBecomeTwoIndependentDocumentsWithTheirOwnHashes() = runBlocking {
        val a = importer().importCapture(page(1)); val b = importer().importCapture(page(2))
        assertNotEquals(a.id, b.id); assertNotEquals(a.hash, b.hash)
        assertEquals(2, repository.rows.size)
    }
}

/** Test-only access to a NormalizedPage with a damaged payload through the public normalizer output. */
private object NormalizedPageForTest {
    fun truncated(source: NormalizedPage): NormalizedPage {
        val constructor = NormalizedPage::class.java.getDeclaredConstructor(ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        constructor.isAccessible = true
        return constructor.newInstance(source.jpeg.copyOf(source.jpeg.size - 8), source.width, source.height)
    }
}
