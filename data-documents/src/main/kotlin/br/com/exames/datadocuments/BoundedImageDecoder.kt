package br.com.exames.datadocuments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.Closeable
import java.io.IOException

/** Blocking IO/CPU. Ownership of the authenticated encoded bytes transfers and is wiped on exit. */
class BoundedImageDecoder {
    fun decodeOwned(encoded: ByteArray): DecodedImage {
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        try {
            if (encoded.isEmpty() || encoded.size > DocumentLimits.MAX_BYTES) throw IOException("IMAGE_SIZE_LIMIT")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType !in setOf("image/jpeg", "image/png")) {
                throw IOException("IMAGE_FORMAT_INVALID")
            }
            val orientation = ImageOrientation.read(encoded)
            var sample = 1
            val side = maxOf(bounds.outWidth, bounds.outHeight).toLong()
            while ((side + sample - 1) / sample > DocumentLimits.MAX_RASTER_SIDE) sample *= 2
            decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size,
                BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                ?: throw IOException("IMAGE_DECODE_FAILED")
            if (maxOf(decoded.width, decoded.height) > DocumentLimits.MAX_RASTER_SIDE) throw IOException("IMAGE_RASTER_LIMIT")
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> setValues(floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
                    6 -> setRotate(90f)
                    7 -> setValues(floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f))
                    8 -> setRotate(270f)
                }
            }
            oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (oriented !== decoded) decoded.recycle()
            return DecodedImage(oriented, checkNotNull(bounds.outMimeType), ImageTransform(bounds.outWidth, bounds.outHeight,
                oriented.width, oriented.height, sample, orientation))
        } catch (error: Exception) {
            oriented?.recycle(); decoded?.recycle()
            if (error is IOException) throw error
            throw IOException("IMAGE_DECODE_FAILED")
        } catch (_: OutOfMemoryError) {
            oriented?.recycle(); decoded?.recycle()
            throw IOException("IMAGE_MEMORY_LIMIT")
        } finally { encoded.fill(0) }
    }
}

class DecodedImage internal constructor(val bitmap: Bitmap, val mimeType: String, val transform: ImageTransform) : Closeable {
    override fun close() = bitmap.recycle()
}
data class ImageTransform(val encodedWidth: Int, val encodedHeight: Int,
    val rasterWidth: Int, val rasterHeight: Int, val sampleSize: Int, val exifOrientation: Int)
