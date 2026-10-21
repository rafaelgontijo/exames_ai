package br.com.exames.datadocuments

import android.graphics.Bitmap
import br.com.exames.domain.model.BoundingBox
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.Closeable

/** Bundled Latin model: com.google.mlkit:text-recognition, no Play Services download. */
class LocalTextRecognizer : Closeable {
    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    suspend fun recognize(bitmap: Bitmap, page: Int): List<SourceText> {
        require(page >= 1)
        val result = client.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks.flatMap { block -> block.lines.map { line ->
            SourceText(page, line.text, line.boundingBox?.let {
                BoundingBox(it.left, it.top, it.right, it.bottom)
            }, null) // No calibrated field confidence available; never fabricate one.
        } }
    }
    override fun close() { client.close() }
}
/** Coordinates refer to rendered bitmap pixels, not PDF points. Pages are one-based. */
data class SourceText(val page: Int, val raw: String, val boundingBox: BoundingBox?, val confidence: Float?)
