package br.com.exames.datadocuments

import android.graphics.Bitmap
import br.com.exames.domain.model.BoundingBox
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable

/** Portuguese LSTM weights bundled and hashed; no network/telemetry SDK or source-image disk file. */
class LocalTextRecognizer : Closeable {
    private var client: TessBaseAPI?=null
    private val lock=Any()
    suspend fun recognize(bitmap: Bitmap,page: Int): List<SourceText> {
        require(page>=1)
        val caller=currentCoroutineContext();caller.ensureActive()
        // Cancellation never closes JNI state or releases the source bitmap before OCR has returned.
        val result=withContext(NonCancellable+Dispatchers.IO) { synchronized(lock) {
            val api=TessBaseAPI();client=api;api.setDebug(false)
            check(api.init(OcrAssets.prepare().absolutePath,"por",TessBaseAPI.OEM_LSTM_ONLY,
                mapOf("debug_file" to "/dev/null","tessedit_write_images" to "0"))) { "OCR_INITIALIZATION_FAILED" }
            api.setDebug(false);api.setPageSegMode(3);api.setImage(bitmap)
            val text=api.getUTF8Text().orEmpty();check(text.length<=1_048_576) { "OCR_TEXT_LIMIT" }
            val iterator=api.resultIterator ?: return@synchronized emptyList<SourceText>()
            val words=mutableListOf<SourceText>()
            try {
                iterator.begin()
                do {
                    val raw=iterator.getUTF8Text(3)?.trim().orEmpty() // RIL_WORD
                    if(raw.isNotEmpty()) {
                        check(words.size<16384 && raw.length<=4096) { "OCR_TEXT_LIMIT" }
                        val rect=iterator.getBoundingRect(3)
                        val box=rect?.let { BoundingBox(it.left,it.top,it.right,it.bottom) }
                        val word=SourceText(page,raw,box,null)
                        words+=word.copy(elements=listOf(word))
                    }
                } while(iterator.next(3))
            } finally { iterator.delete() }
            // Stable spatial ordering feeds the same source/parser contract used for digital PDF.
            words.sortedWith(compareBy<SourceText> { it.boundingBox?.top ?: Int.MAX_VALUE }.thenBy { it.boundingBox?.left ?: Int.MAX_VALUE })
        } }
        caller.ensureActive();return result
    }
    override fun close() { synchronized(lock) { client?.recycle();client=null } }
}
/** Coordinates refer to the upright rendered bitmap; confidence is not clinically calibrated. */
data class SourceText(val page: Int,val raw: String,val boundingBox: BoundingBox?,val confidence: Float?,
    val elements: List<SourceText> = emptyList())
