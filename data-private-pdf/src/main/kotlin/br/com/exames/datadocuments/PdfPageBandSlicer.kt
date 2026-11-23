package br.com.exames.datadocuments

import android.graphics.Bitmap
import br.com.exames.domain.extraction.VisionPageBand
import br.com.exames.domain.inference.InferencePayloadLimits
import br.com.exames.domain.validation.SourceRegion
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Slices an upright PDF page bitmap into overlapping horizontal bands strictly in memory,
 * skipping blank/empty bands and bounding encoded image bytes for safe IPC transport.
 */
object PdfPageBandSlicer {
    const val MAX_BAND_WIDTH_PX = 1240
    const val MAX_IMAGE_BYTES = InferencePayloadLimits.MAX_IMAGE_BYTES
    const val MIN_INK_RATIO = 0.0015
    private const val INK_LUMA_THRESHOLD = 238
    private const val SAMPLE_STEP = 4

    internal data class BandBounds(
        val bandIndex: Int,
        val top: Int,
        val bottom: Int,
    ) {
        val height: Int get() = bottom - top
    }

    internal data class EncodedBand(
        val bytes: ByteArray,
        val mimeType: String,
    )

    private val bandFractions = listOf(
        0.00 to 0.42,
        0.32 to 0.74,
        0.62 to 1.00,
    )

    internal fun computeBandBounds(height: Int): List<BandBounds> {
        if (height <= 0) return emptyList()
        if (height < 64) return listOf(BandBounds(0, 0, height))
        return bandFractions.mapIndexedNotNull { idx, (startFrac, endFrac) ->
            val top = (height * startFrac).toInt().coerceIn(0, height - 1)
            val bottom = ceil(height * endFrac).toInt().coerceIn(top + 1, height)
            if (bottom > top) BandBounds(idx, top, bottom) else null
        }
    }

    internal fun scaledDimensions(width: Int, height: Int, maxWidth: Int = MAX_BAND_WIDTH_PX): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        if (width <= maxWidth) return width to height
        val scale = maxWidth.toDouble() / width.toDouble()
        val newHeight = max(1, (height * scale).roundToInt())
        return maxWidth to newHeight
    }

    internal fun hasInkContent(argbPixels: IntArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || argbPixels.size < width * height) return false
        var sampled = 0
        var ink = 0
        val stepY = max(1, min(SAMPLE_STEP, height))
        val stepX = max(1, min(SAMPLE_STEP, width))
        var y = 0
        while (y < height) {
            val rowOffset = y * width
            var x = 0
            while (x < width) {
                val pixel = argbPixels[rowOffset + x]
                val a = (pixel ushr 24) and 0xFF
                if (a >= 16) {
                    val r = (pixel ushr 16) and 0xFF
                    val g = (pixel ushr 8) and 0xFF
                    val b = pixel and 0xFF
                    val luma = (r * 299 + g * 587 + b * 114) / 1000
                    if (luma < INK_LUMA_THRESHOLD) ink++
                }
                sampled++
                x += stepX
            }
            y += stepY
        }
        return sampled > 0 && (ink.toDouble() / sampled.toDouble()) >= MIN_INK_RATIO
    }

    internal fun selectEncodedBand(
        encodePng: () -> ByteArray,
        encodeJpeg: (quality: Int) -> ByteArray,
        maxBytes: Int = MAX_IMAGE_BYTES,
    ): EncodedBand? {
        val png = encodePng()
        if (png.isNotEmpty() && png.size <= maxBytes) {
            return EncodedBand(png, "image/png")
        }
        for (quality in intArrayOf(88, 75)) {
            val jpg = encodeJpeg(quality)
            if (jpg.isNotEmpty() && jpg.size <= maxBytes) {
                return EncodedBand(jpg, "image/jpeg")
            }
        }
        return null
    }

    fun slice(page: Int, bitmap: Bitmap): List<VisionPageBand> {
        val width = bitmap.width
        val height = bitmap.height
        val boundsList = computeBandBounds(height)
        val result = mutableListOf<VisionPageBand>()

        for (bounds in boundsList) {
            val crop = Bitmap.createBitmap(bitmap, 0, bounds.top, width, bounds.height)
            var scaled: Bitmap? = null
            try {
                val (targetW, targetH) = scaledDimensions(crop.width, crop.height)
                val working = if (targetW != crop.width || targetH != crop.height) {
                    Bitmap.createScaledBitmap(crop, targetW, targetH, true).also { scaled = it }
                } else {
                    crop
                }
                val pixels = IntArray(working.width * working.height)
                working.getPixels(pixels, 0, working.width, 0, 0, working.width, working.height)
                if (!hasInkContent(pixels, working.width, working.height)) {
                    continue
                }
                val encoded = selectEncodedBand(
                    encodePng = { compress(working, Bitmap.CompressFormat.PNG, 100) },
                    encodeJpeg = { q -> compress(working, Bitmap.CompressFormat.JPEG, q) },
                ) ?: continue
                val region = SourceRegion(
                    page = page,
                    left = 0.0,
                    top = bounds.top.toDouble(),
                    right = width.toDouble(),
                    bottom = bounds.bottom.toDouble(),
                    pageWidth = width.toDouble(),
                    pageHeight = height.toDouble(),
                )
                result += VisionPageBand(
                    page = page,
                    bandIndex = bounds.bandIndex,
                    imageBytes = encoded.bytes,
                    imageMimeType = encoded.mimeType,
                    region = region,
                )
            } finally {
                scaled?.recycle()
                crop.recycle()
            }
        }
        return result
    }

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(format, quality, out)
        return out.toByteArray()
    }
}
