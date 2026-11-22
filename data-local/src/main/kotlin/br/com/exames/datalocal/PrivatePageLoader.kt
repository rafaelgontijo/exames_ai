package br.com.exames.datalocal

import android.graphics.Bitmap
import br.com.exames.datadocuments.*
import br.com.exames.domain.model.Document
import br.com.exames.domain.repository.DocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest

/** Authentication/hash precede decode. No external URI survives import. Caller owns returned bytes. */
internal suspend fun readOwnedDocument(id: String, documents: DocumentRepository,
    artifacts: EncryptedArtifactStore): Pair<Document, ByteArray> {
    val document = documents.find(id) ?: throw IOException("DOCUMENT_UNAVAILABLE")
    val bytes = artifacts.read(id, "original")
    try {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (hash != document.hash) throw IOException("DOCUMENT_SOURCE_MISMATCH")
        currentCoroutineContext().ensureActive()
        return document to bytes
    } catch (error: Exception) { bytes.fill(0); throw error }
}

/** One decoded page (including its preview/OCR work) is owned at a time per loader. */
class PrivatePageLoader(private val documents: DocumentRepository,
    private val artifacts: EncryptedArtifactStore, private val pdf: PdfPreviewRenderer,
    private val images: BoundedImageDecoder = BoundedImageDecoder(),
    private val text: PageTextExtractor = PageTextExtractor()) {
    private val gate = Mutex()
    suspend fun load(documentId: String, page: Int): PrivateDocumentPage {
        val owner = Any()
        gate.lock(owner)
        var result: PrivateDocumentPage? = null
        var resource: Closeable? = null
        try {
            return withContext(Dispatchers.IO) {
                val document = documents.find(documentId) ?: throw IOException("DOCUMENT_UNAVAILABLE")
                if (document.mimeType == "application/pdf") {
                    val session = pdf.open(documentId)
                    resource = session
                    val frame = session.readPage(page)
                    val extracted = text.fromPdf(frame)
                    result = PrivateDocumentPage(frame.bitmap, extracted, frame.transform, null) {
                        try { session.close() } finally { gate.unlock(owner) }
                    }
                } else {
                    if (page != 1) throw IOException("DOCUMENT_PAGE_INDEX")
                    if (document.mimeType !in setOf("image/jpeg", "image/png")) throw IOException("DOCUMENT_FORMAT_INVALID")
                    val (_, bytes) = readOwnedDocument(documentId, documents, artifacts)
                    val image = images.decodeOwned(bytes)
                    resource = image
                    if (image.mimeType != document.mimeType) throw IOException("DOCUMENT_FORMAT_MISMATCH")
                    currentCoroutineContext().ensureActive()
                    val extracted = text.fromImage(image)
                    result = PrivateDocumentPage(image.bitmap, extracted, null, image.transform) {
                        try { image.close() } finally { gate.unlock(owner) }
                    }
                }
                currentCoroutineContext().ensureActive()
                checkNotNull(result)
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                if (result != null) result!!.close()
                else try { resource?.close() } finally { gate.unlock(owner) }
            }
            if (error is OutOfMemoryError) throw IOException("DOCUMENT_MEMORY_LIMIT")
            throw error
        }
    }
}

class PrivateDocumentPage internal constructor(val bitmap: Bitmap, val text: ExtractedPageText,
    val pdfTransform: PdfRasterTransform?, val imageTransform: ImageTransform?,
    private val release: () -> Unit) : Closeable {
    private var closed = false
    @Synchronized override fun close() { if (!closed) { closed = true; release() } }
}
