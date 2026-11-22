package br.com.exames.datalocal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.exames.datadocuments.*
import br.com.exames.domain.model.Document
import br.com.exames.fixtures.SyntheticPdf
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExtractionLimitsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun renderer(owned: ByteArray) = PdfPreviewRenderer(context, AuthenticatedPdfSource { owned })

    @Test fun acceptsTwentyPagesAndRejectsTwentyOneBeforeRendering() = runBlocking {
        val accepted = SyntheticPdf.pages(20, 300, 400)
        renderer(accepted).open("synthetic-20").use { pdf ->
            assertEquals(20, pdf.pageCount)
            pdf.readPage(20).use { assertEquals(20, it.page) }
        }
        assertTrue(accepted.all { it == 0.toByte() })
        val rejected = SyntheticPdf.pages(21, 300, 400)
        try { renderer(rejected).open("synthetic-21"); fail("Accepted page overflow") }
        catch (e: IOException) { assertEquals("PDF_PAGE_LIMIT", e.message) }
        assertTrue(rejected.all { it == 0.toByte() })
    }
    @Test fun rejectsEncodedOverflowAndBoundsHugePdfRaster() = runBlocking {
        val oversized = ByteArray(DocumentLimits.MAX_BYTES + 1)
        try { renderer(oversized).open("synthetic-too-large"); fail("Accepted byte overflow") }
        catch (e: IOException) { assertEquals("PDF_SIZE_LIMIT", e.message) }
        assertTrue(oversized.all { it == 0.toByte() })
        try { BoundedImageDecoder().decodeOwned(oversized); fail("Accepted image byte overflow") }
        catch (e: IOException) { assertEquals("IMAGE_SIZE_LIMIT", e.message) }
        renderer(SyntheticPdf.pages(1, 1000000, 1000000)).open("synthetic-huge-page").use { pdf ->
            pdf.readPage(1).use { page ->
                assertEquals(2048, page.bitmap.width)
                assertEquals(2048, page.bitmap.height)
                assertTrue(page.bitmap.allocationByteCount <= 2048 * 2048 * 4)
            }
        }
    }
    @Test fun queuedCancellationAndPageFailureReleaseSharedPageOwnership() = runBlocking {
        val suffix = UUID.randomUUID().toString(); val name = "limits-$suffix.db"; val alias = "limits-$suffix"
        val factory = ProtectedDatabaseFactory(context, name, AndroidPayloadKeys(alias))
        val repository = ProtectedValidationRepository(factory); val artifacts = EncryptedArtifactStore(context)
        val bytes = SyntheticPdf.hybrid()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        try {
            artifacts.write(suffix, "original", bytes.inputStream())
            repository.save(Document(suffix, "artifact:original", hash, "application/pdf", Instant.now(), null))
            val pages = PrivatePageLoader(repository, artifacts,
                PdfPreviewRenderer(context, LocalDataModule.pdfSource(artifacts, repository)))
            val first = pages.load(suffix, 1)
            try {
                val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { pages.load(suffix, 1).use { } }
                cancelled.cancelAndJoin()
                val waiting = async(start = CoroutineStart.UNDISPATCHED) { pages.load(suffix, 1) }
                val early = withTimeoutOrNull(100) { waiting.await() }
                early?.close()
                assertNull(early)
                first.close()
                withTimeout(5000) { waiting.await() }.use { assertEquals("PDF_TEXT", it.text.method) }
            } finally { first.close() }
            try { pages.load(suffix, 0); fail("Accepted invalid page") }
            catch (e: IOException) { assertEquals("PDF_PAGE_INDEX", e.message) }
            withTimeout(5000) { pages.load(suffix, 1) }.use { assertFalse(it.bitmap.isRecycled) }
        } finally {
            bytes.fill(0); artifacts.deleteDocument(suffix); factory.open().close(); context.deleteDatabase(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
