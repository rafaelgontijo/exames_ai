package br.com.exames.datalocal

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.exames.datadocuments.*
import br.com.exames.domain.model.Document
import br.com.exames.fixtures.SyntheticImage
import br.com.exames.fixtures.SyntheticPdf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PrivateImageOcrTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun jpegAndPngAllEightExifOrientationsMatchIndependentCorners() {
        val expected = listOf(
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
            listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
            listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
            listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
            listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
            listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE))
        for (jpeg in listOf(false, true)) for (orientation in 1..8) {
            val owned = SyntheticImage.quadrants(jpeg, orientation)
            BoundedImageDecoder().decodeOwned(owned).use { image ->
                assertTrue(owned.all { it == 0.toByte() })
                val bitmap = image.bitmap
                assertEquals(if (orientation >= 5) 80 else 120, bitmap.width)
                assertEquals(if (orientation >= 5) 120 else 80, bitmap.height)
                val actual = listOf(bitmap.getPixel(bitmap.width / 4, bitmap.height / 4),
                    bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 4),
                    bitmap.getPixel(bitmap.width / 4, bitmap.height * 3 / 4),
                    bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height * 3 / 4))
                actual.zip(expected[orientation - 1]).forEach { (a, e) ->
                    assertTrue(kotlin.math.abs(Color.red(a) - Color.red(e)) <= 8)
                    assertTrue(kotlin.math.abs(Color.green(a) - Color.green(e)) <= 8)
                    assertTrue(kotlin.math.abs(Color.blue(a) - Color.blue(e)) <= 8)
                }
            }
        }
    }
    @Test fun largeImageIsSampledBeforeDecodeAndInvalidInputIsWiped() {
        BoundedImageDecoder().decodeOwned(SyntheticImage.textImage(16384, 128)).use {
            assertEquals(8, it.transform.sampleSize)
            assertEquals(16384, it.transform.encodedWidth)
            assertTrue(it.bitmap.width <= 2048 && it.bitmap.height <= 2048)
            assertTrue(it.bitmap.allocationByteCount <= 2048 * 2048 * 4)
        }
        val invalid = byteArrayOf(1, 2, 3)
        try { BoundedImageDecoder().decodeOwned(invalid); fail("Accepted unsupported image") }
        catch (_: IOException) { assertTrue(invalid.all { it == 0.toByte() }) }
    }
    @Test fun bundledOcrReadsPrivateImageAndScannedPdfWithUnknownConfidence() = runBlocking {
        val suffix = UUID.randomUUID().toString(); val name = "ocr-$suffix.db"; val alias = "ocr-$suffix"
        val factory = ProtectedDatabaseFactory(context, name, AndroidPayloadKeys(alias))
        val repository = ProtectedValidationRepository(factory); val artifacts = EncryptedArtifactStore(context)
        val ids = mutableListOf<String>()
        suspend fun save(bytes: ByteArray, mime: String): String {
            val id = UUID.randomUUID().toString(); ids += id
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            artifacts.write(id, "original", bytes.inputStream()); bytes.fill(0)
            repository.save(Document(id, "artifact:original", hash, mime, Instant.now(), null)); return id
        }
        try {
            val image = save(SyntheticImage.textImage(600, 160), "image/png")
            val pdf = save(SyntheticPdf.hybrid(), "application/pdf")
            val renderer = PdfPreviewRenderer(context, LocalDataModule.pdfSource(artifacts, repository))
            val pages = PrivatePageLoader(repository, artifacts, renderer)
            pages.load(image, 1).use { page ->
                assertEquals("OCR", page.text.method)
                assertTrue(page.text.lines.any { it.raw.contains("Glicose", ignoreCase = true) })
                assertTrue(page.text.lines.all { it.confidence == null && it.page == 1 })
                assertTrue(page.text.fragments.isNotEmpty())
                assertTrue(page.text.fragments.all { it.confidence == null && it.method == "OCR" && it.spaceBefore == true })
                assertTrue(page.text.fragments.any { it.region != null && it.raw.equals("Glicose", ignoreCase = true) })
                assertNotNull(page.imageTransform)
            }
            pages.load(pdf, 1).use { assertEquals("PDF_TEXT", it.text.method) }
            pages.load(pdf, 2).use { page ->
                assertEquals("OCR", page.text.method)
                assertTrue(page.text.lines.isNotEmpty())
                assertTrue(page.text.lines.all { it.confidence == null && it.page == 2 })
                assertNotNull(page.pdfTransform)
            }
        } finally {
            ids.forEach { artifacts.deleteDocument(it) }
            factory.open().close(); context.deleteDatabase(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
