package br.com.exames.datadocuments

import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.security.MessageDigest

class ValidatedDocumentInputTest {
    private val pdf = "%PDF-1.4\nsynthetic transport fixture\n%%EOF\n".toByteArray()
    private fun read(bytes: ByteArray = pdf, mime: String? = "application/pdf") =
        ValidatedDocumentInput.read(bytes.inputStream(), mime)

    @Test fun hashesActualBytesAndAcceptsUnknownMime() {
        val result = read(mime = null)
        assertArrayEquals(pdf, result.bytes)
        assertEquals("application/pdf", result.mimeType)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(pdf).joinToString("") { "%02x".format(it) }, result.sha256)
        assertEquals(result.sha256, read(mime = "application/octet-stream").sha256)
    }
    @Test fun rejectsMimeMismatchAndUnsupportedSignatures() {
        reject { read(mime = "image/png") }
        reject { read("not a document".toByteArray()) }
        reject { read(byteArrayOf()) }
    }
    @Test fun rejectsTruncatedPdfAndJpeg() {
        reject { read(pdf.copyOf(pdf.size - 5)) }
        reject { read(byteArrayOf(-1, -40, 0, 0), "image/jpeg") }
        assertEquals("image/jpeg", read(byteArrayOf(-1, -40, -1, -39), "image/jpeg").mimeType)
    }
    @Test fun validatesPngChunkBoundsAndEndMarker() {
        val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        val ihdr = byteArrayOf(0, 0, 0, 13) + "IHDR".toByteArray() + ByteArray(17)
        val end = ByteArray(4) + "IEND".toByteArray() + ByteArray(4)
        val png = signature + ihdr + end
        assertEquals("image/png", read(png, "image/png").mimeType)
        reject { read(png.copyOf(png.size - 1), "image/png") }
        reject { read(signature + byteArrayOf(127, -1, -1, -1) + end, "image/png") }
        reject { read(signature + end, "image/png") }
        reject { read(png + byteArrayOf(0), "image/png") }
    }
    @Test fun boundsActualStreamIncludingOneByteOverLimit() {
        fun stream(size: Int) = object : InputStream() {
            var remaining = size
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val n = minOf(remaining, len)
                b.fill(0, off, off + n); remaining -= n
                return n
            }
        }
        // At the exact limit validation reaches signature checking, rather than size rejection.
        try { ValidatedDocumentInput.read(stream(ValidatedDocumentInput.MAX_BYTES), null); fail() }
        catch (e: Exception) { assertEquals("DOCUMENT_SIGNATURE_INVALID", e.message) }
        try { ValidatedDocumentInput.read(stream(ValidatedDocumentInput.MAX_BYTES + 1), null); fail() }
        catch (e: Exception) { assertEquals("DOCUMENT_TOO_LARGE", e.message) }
    }
    @Test fun propagatesReadFailureAndCancellation() {
        val broken = object : InputStream() { override fun read(): Int = throw java.io.IOException("synthetic") }
        reject { ValidatedDocumentInput.read(broken, null) }
        reject { ValidatedDocumentInput.read(pdf.inputStream(), null) { throw java.util.concurrent.CancellationException() } }
    }
    private fun reject(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: Exception) { }
    }
}
