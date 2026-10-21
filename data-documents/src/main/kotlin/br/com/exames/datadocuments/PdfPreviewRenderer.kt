package br.com.exames.datadocuments

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** PdfRenderer renders pixels; it does not extract the embedded PDF text layer. */
class PdfPreviewRenderer(private val resolver: ContentResolver) {
    suspend fun pageCount(uri: Uri): Int = withContext(Dispatchers.IO) {
        requireNotNull(resolver.openFileDescriptor(uri, "r")).use { fd ->
            PdfRenderer(fd).use { it.pageCount }
        }
    }
    /** Caller owns the bitmap and must release it after use. Zero-based page index. */
    suspend fun render(uri: Uri, pageIndex: Int): Bitmap = withContext(Dispatchers.IO) {
        requireNotNull(resolver.openFileDescriptor(uri, "r")).use { fd ->
            PdfRenderer(fd).use { renderer ->
                require(pageIndex in 0 until renderer.pageCount)
                renderer.openPage(pageIndex).use { page ->
                    val scale = minOf(2f, 2048f / maxOf(page.width, page.height))
                    val bitmap = Bitmap.createBitmap(maxOf(1, (page.width * scale).toInt()),
                        maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    } catch (e: Exception) { bitmap.recycle(); throw e }
                }
            }
        }
    }
}
