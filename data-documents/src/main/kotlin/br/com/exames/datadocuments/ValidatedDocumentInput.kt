package br.com.exames.datadocuments

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** Transport validation only. PDF/image decoding can still report malformed content later. */
internal object ValidatedDocumentInput {
    val MAX_BYTES = DocumentLimits.MAX_BYTES
    private val png = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)

    fun read(input: InputStream, declaredMime: String?, checkActive: () -> Unit = {}): ValidatedDocument {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            checkActive()
            // Read at most one byte beyond the limit, including for untrusted provider sizes.
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - total + 1))
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= MAX_BYTES) { "DOCUMENT_TOO_LARGE" }
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        val mime = when {
            bytes.size >= 8 && bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray()) -> {
                val tail = bytes.copyOfRange(maxOf(0, bytes.size - 1024), bytes.size).toString(Charsets.ISO_8859_1)
                require(tail.trimEnd().endsWith("%%EOF")) { "DOCUMENT_TRUNCATED" }
                "application/pdf"
            }
            bytes.size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> {
                require(bytes[bytes.size - 2] == 0xff.toByte() && bytes.last() == 0xd9.toByte()) { "DOCUMENT_TRUNCATED" }
                "image/jpeg"
            }
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(png) -> {
                validatePng(bytes)
                "image/png"
            }
            else -> error("DOCUMENT_SIGNATURE_INVALID")
        }
        require(declaredMime == null || declaredMime == "application/octet-stream" || declaredMime == mime) {
            "DOCUMENT_MIME_MISMATCH"
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return ValidatedDocument(bytes, mime, hash)
    }

    private fun validatePng(bytes: ByteArray) {
        var offset = 8
        var first = true
        while (offset <= bytes.size - 12) {
            val length = (0..3).fold(0L) { n, i -> (n shl 8) or (bytes[offset + i].toLong() and 255) }
            require(length <= bytes.size.toLong() - offset - 12) { "DOCUMENT_TRUNCATED" }
            val type = bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            if (first) require(type == "IHDR" && length == 13L) { "DOCUMENT_SIGNATURE_INVALID" }
            first = false
            offset += length.toInt() + 12
            if (type == "IEND") {
                require(length == 0L && offset == bytes.size) { "DOCUMENT_SIGNATURE_INVALID" }
                return
            }
        }
        error("DOCUMENT_TRUNCATED")
    }
}

/** Bytes are authenticated by the private artifact store on subsequent reads, not by this hash. */
data class ValidatedDocument(val bytes: ByteArray, val mimeType: String, val sha256: String)
