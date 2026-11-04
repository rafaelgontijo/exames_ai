package br.com.exames.datadocuments

import android.content.Context
import android.graphics.Bitmap
import br.com.exames.domain.model.BoundingBox
import br.com.exames.pdflocal.PrivatePdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.Closeable

/** Supplies an independently authenticated private source, never an external URI. Ownership transfers. */
fun interface AuthenticatedPdfSource {
    suspend fun read(documentId: String): ByteArray
}

/** No plaintext temporary files; all blocking native work runs outside the UI thread. */
class PdfPreviewRenderer(context: Context, private val source: AuthenticatedPdfSource) {
    private val appContext = context.applicationContext
    suspend fun open(documentId: String): PdfDocumentSession {
        var opened: PdfDocumentSession? = null
        try {
            return withContext(Dispatchers.IO) {
                val bytes = source.read(documentId)
                try {
                    currentCoroutineContext().ensureActive()
                    PdfDocumentSession(PrivatePdf(appContext, bytes)).also {
                        opened = it
                        currentCoroutineContext().ensureActive()
                    }
                } catch (error: Throwable) {
                    opened?.close()
                    bytes.fill(0)
                    if (error is OutOfMemoryError) throw IOException("PDF_MEMORY_LIMIT")
                    throw error
                }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { opened?.close() }
            if (error is OutOfMemoryError) throw IOException("PDF_MEMORY_LIMIT")
            throw error
        }
    }
}

class PdfDocumentSession internal constructor(private val pdf: PrivatePdf) : Closeable {
    val pageCount: Int get() = pdf.pageCount
    suspend fun readPage(page: Int): PdfPageSnapshot {
        var allocated: PrivatePdf.Page? = null
        try {
            return withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val result = pdf.readPage(page)
                allocated = result
                currentCoroutineContext().ensureActive()
                PdfPageSnapshot(result)
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { allocated?.close() }
            if (error is OutOfMemoryError) throw IOException("PDF_MEMORY_LIMIT")
            throw error
        }
    }
    /** Blocking close; use from an IO scope (e.g. worker use/finally). */
    override fun close() = pdf.close()
    suspend fun closeAsync() = withContext(NonCancellable + Dispatchers.IO) { close() }
}

/** Every bound is in pixels of this exact upright preview; do not apply PDF rotation again. */
class PdfPageSnapshot internal constructor(private val native: PrivatePdf.Page) : Closeable {
    val page: Int = native.page
    val rawText: String = native.raw
    val transform = PdfRasterTransform(native.widthPoints, native.heightPoints,
        native.image.width, native.image.height, native.rotationDegrees)
    val spans: List<PdfTextSpan> = native.glyphs.map { glyph ->
        PdfTextSpan("pdf-$page-${glyph.sourceIndex}", glyph.sourceIndex, glyph.raw,
            glyph.bounds?.let { BoundingBox(it[0], it[1], it[2], it[3]) })
    }
    val bitmap: Bitmap get() {
        check(!native.image.isRecycled) { "PDF_PAGE_CLOSED" }
        return native.image
    }
    val method: String = "PDF_TEXT"
    val extractorVersion: String = PrivatePdf.VERSION
    val confidence: Float? = null
    override fun close() = native.close()
}

data class PdfTextSpan(val spanId: String, val sourceIndex: Int, val raw: String, val boundingBox: BoundingBox?)
/** Point dimensions are already oriented by PDFium; glyph bounds come from its page-to-device map. */
data class PdfRasterTransform(val orientedWidthPoints: Int, val orientedHeightPoints: Int,
    val rasterWidth: Int, val rasterHeight: Int, val originalRotationDegrees: Int) {
    val scaleX: Double get() = rasterWidth.toDouble() / orientedWidthPoints
    val scaleY: Double get() = rasterHeight.toDouble() / orientedHeightPoints
}
