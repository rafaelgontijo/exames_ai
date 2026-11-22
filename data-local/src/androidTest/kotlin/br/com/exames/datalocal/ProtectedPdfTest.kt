package br.com.exames.datalocal

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.exames.datadocuments.AuthenticatedPdfSource
import br.com.exames.datadocuments.PdfPreviewRenderer
import br.com.exames.domain.model.Document
import br.com.exames.fixtures.SyntheticPdf
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ProtectedPdfTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = EncryptedArtifactStore(context)
    private fun withSource(body: suspend (Document, ProtectedValidationRepository, PdfPreviewRenderer) -> Unit) = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val name = "pdf-$suffix.db"
        val alias = "pdf-$suffix"
        val factory = ProtectedDatabaseFactory(context, name, AndroidPayloadKeys(alias))
        val repository = ProtectedValidationRepository(factory)
        val bytes = SyntheticPdf.hybrid()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val document = Document(suffix, "artifact:original", hash, "application/pdf", Instant.now(), null)
        try {
            store.write(document.id, "original", bytes.inputStream())
            repository.save(document)
            val reader = PdfPreviewRenderer(context, LocalDataModule.pdfSource(store, repository))
            body(document, repository, reader)
        } finally {
            bytes.fill(0)
            store.deleteDocument(document.id)
            factory.open().close()
            context.deleteDatabase(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }

    @Test fun privateHybridAndRotatedPagesKeepPreviewAndEvidenceAligned() = withSource { document, _, reader ->
        reader.open(document.id).use { pdf ->
            assertEquals(3, pdf.pageCount)
            for (pageNumber in 1..3) {
                pdf.readPage(pageNumber).use { page ->
                    assertEquals(pageNumber, page.page)
                    assertNull(page.confidence)
                    assertTrue(page.transform.rasterWidth <= 2048 && page.transform.rasterHeight <= 2048)
                    assertTrue(hasInk(page.bitmap, 0, 0, page.bitmap.width, page.bitmap.height))
                    when (pageNumber) {
                        1 -> assertTrue(page.rawText.contains("Sódio") && page.rawText.contains("Glicose"))
                        2 -> { assertEquals("", page.rawText); assertTrue(page.spans.isEmpty()) }
                        3 -> { assertTrue(page.rawText.contains("Potássio")); assertEquals(90, page.transform.originalRotationDegrees) }
                    }
                    val ids = page.spans.map { it.spanId }
                    assertEquals(ids.size, ids.toSet().size)
                    page.spans.filter { it.raw.any { c -> !c.isWhitespace() && !c.isISOControl() } }.forEach { span ->
                        val box = checkNotNull(span.boundingBox)
                        assertTrue(hasInk(page.bitmap, maxOf(0, box.left - 1), maxOf(0, box.top - 1),
                            minOf(page.bitmap.width, box.right + 1), minOf(page.bitmap.height, box.bottom + 1)))
                    }
                }
            }
        }
        // Reopening needs only the private source, never an external URI grant.
        reader.open(document.id).use { pdf -> pdf.readPage(1).use { assertTrue(it.rawText.contains("Glicose")) } }
    }

    @Test fun closesBitmapsAndPermitsOnlyOneActivePage() = withSource { document, _, reader ->
        val session = reader.open(document.id)
        val frame = session.readPage(1)
        val bitmap = frame.bitmap
        try { session.readPage(2); fail("Accepted a second active page") }
        catch (expected: IOException) { assertEquals("PDF_PAGE_STILL_ACTIVE", expected.message) }
        frame.close()
        assertTrue(bitmap.isRecycled)
        session.readPage(2).use { assertEquals(2, it.page) }
        session.close()
        session.close() // Close is idempotent.
        try { session.readPage(1); fail("Read after close") }
        catch (expected: IOException) { assertEquals("PDF_CLOSED", expected.message) }
    }

    @Test fun rejectsTamperingAndDeletedDocumentsBeforeNativeDecode() = withSource { document, repository, reader ->
        val file = File(context.noBackupFilesDir, "artifacts/${document.id}/original.enc")
        val encrypted = file.readBytes()
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        file.writeBytes(encrypted)
        try { reader.open(document.id); fail("Accepted tampering") } catch (_: javax.crypto.AEADBadTagException) { }
        repository.deleteCompletely(document.id)
        try { reader.open(document.id); fail("Reopened a deleted document") }
        catch (expected: IOException) { assertEquals("PDF_UNAVAILABLE", expected.message) }
    }

    @Test fun cancellationBeforeOpeningWipesOwnedPlaintextBuffer() = runBlocking {
        val owned = SyntheticPdf.hybrid()
        val reader = PdfPreviewRenderer(context, AuthenticatedPdfSource {
            currentCoroutineContext().cancel()
            owned
        })
        val job = launch { reader.open("synthetic-id") }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(owned.all { it == 0.toByte() })
    }

    private fun hasInk(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        for (y in top until bottom) for (x in left until right)
            if ((bitmap.getPixel(x, y) and 0xffffff) < 0xdddddd) return true
        return false
    }
}
