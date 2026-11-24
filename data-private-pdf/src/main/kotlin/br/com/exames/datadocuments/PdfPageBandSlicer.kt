package br.com.exames.datadocuments

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import br.com.exames.domain.extraction.VisionPageBand
import br.com.exames.domain.inference.InferencePayloadLimits
import br.com.exames.domain.validation.SourceRegion
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Slices an upright PDF page bitmap into overlapping horizontal bands strictly in memory,
 * skipping blank/empty bands, optionally painting opaque local PII redaction boxes in RAM
 * (for opt-in cloud fallback), and bounding encoded image bytes for safe IPC/HTTPS transport.
 */
object PdfPageBandSlicer {
    const val MAX_BAND_WIDTH_PX = 1240
    const val MAX_IMAGE_BYTES = InferencePayloadLimits.MAX_IMAGE_BYTES
    const val MIN_INK_RATIO = 0.0015
    private const val INK_LUMA_THRESHOLD = 238
    private const val SAMPLE_STEP = 4
    private const val REDACTION_PAD_PX = 2
    private const val REDACTION_FILL_ARGB = 0xFF1A1A1A.toInt()

    internal data class PixelRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

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

    internal fun projectRedactionsToBand(
        regions: List<SourceRegion>,
        page: Int,
        bounds: BandBounds,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        padPx: Int = REDACTION_PAD_PX,
    ): List<PixelRect> {
        if (regions.isEmpty() || sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return emptyList()
        }
        val bandHeight = bounds.height
        if (bandHeight <= 0) return emptyList()
        val scaleX = targetWidth.toDouble() / sourceWidth.toDouble()
        val scaleY = targetHeight.toDouble() / bandHeight.toDouble()
        val out = mutableListOf<PixelRect>()
        for (region in regions) {
            if (region.page != page || region.pageWidth <= 0.0 || region.pageHeight <= 0.0) continue
            val srcLeft = (region.left / region.pageWidth) * sourceWidth
            val srcRight = (region.right / region.pageWidth) * sourceWidth
            val srcTop = (region.top / region.pageHeight) * sourceHeight
            val srcBottom = (region.bottom / region.pageHeight) * sourceHeight
            if (srcBottom <= bounds.top || srcTop >= bounds.bottom) continue
            val clippedTop = max(bounds.top.toDouble(), srcTop) - bounds.top
            val clippedBottom = min(bounds.bottom.toDouble(), srcBottom) - bounds.top
            val left = (floor(srcLeft * scaleX).toInt() - padPx).coerceIn(0, targetWidth)
            val right = (ceil(srcRight * scaleX).toInt() + padPx).coerceIn(0, targetWidth)
            val top = (floor(clippedTop * scaleY).toInt() - padPx).coerceIn(0, targetHeight)
            val bottom = (ceil(clippedBottom * scaleY).toInt() + padPx).coerceIn(0, targetHeight)
            if (right > left && bottom > top) {
                out += PixelRect(left, top, right, bottom)
            }
        }
        return out
    }

    internal fun applyRedactionsToPixels(
        argbPixels: IntArray,
        width: Int,
        height: Int,
        rects: List<PixelRect>,
        fillArgb: Int = REDACTION_FILL_ARGB,
    ) {
        if (rects.isEmpty() || width <= 0 || height <= 0) return
        for (rect in rects) {
            val top = rect.top.coerceIn(0, height)
            val bottom = rect.bottom.coerceIn(0, height)
            val left = rect.left.coerceIn(0, width)
            val right = rect.right.coerceIn(0, width)
            for (y in top until bottom) {
                val rowOffset = y * width
                for (x in left until right) {
                    argbPixels[rowOffset + x] = fillArgb
                }
            }
        }
    }

    fun slice(
        page: Int,
        bitmap: Bitmap,
        redactionRegions: List<SourceRegion> = emptyList(),
    ): List<VisionPageBand> {
        val width = bitmap.width
        val height = bitmap.height
        val boundsList = computeBandBounds(height)
        val result = mutableListOf<VisionPageBand>()

        for (bounds in boundsList) {
            val crop = Bitmap.createBitmap(bitmap, 0, bounds.top, width, bounds.height)
            var scaled: Bitmap? = null
            var redacted: Bitmap? = null
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
                val rects = projectRedactionsToBand(
                    regions = redactionRegions,
                    page = page,
                    bounds = bounds,
                    sourceWidth = width,
                    sourceHeight = height,
                    targetWidth = working.width,
                    targetHeight = working.height,
                )
                val toEncode = if (rects.isNotEmpty()) {
                    val mutableCopy = working.copy(Bitmap.Config.ARGB_8888, true).also { redacted = it }
                    val canvas = Canvas(mutableCopy)
                    val paint = Paint().apply {
                        color = REDACTION_FILL_ARGB
                        style = Paint.Style.FILL
                        isAntiAlias = false
                    }
                    for (r in rects) {
                        canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), paint)
                    }
                    mutableCopy
                } else {
                    working
                }
                val encoded = selectEncodedBand(
                    encodePng = { compress(toEncode, Bitmap.CompressFormat.PNG, 100) },
                    encodeJpeg = { q -> compress(toEncode, Bitmap.CompressFormat.JPEG, q) },
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
                redacted?.recycle()
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
