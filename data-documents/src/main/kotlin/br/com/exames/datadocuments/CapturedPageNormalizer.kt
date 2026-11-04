package br.com.exames.datadocuments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Rejection with a stable code only; messages never carry image content, metadata or paths. */
class CaptureRejected(val code: String) : IOException(code)

/** An oriented, metadata-free JPEG of one page. [jpeg] is owned by the receiver, which wipes it after use. */
class NormalizedPage internal constructor(val jpeg: ByteArray, val width: Int, val height: Int) {
    fun wipe() = jpeg.fill(0)
}

/**
 * Orientation is applied exactly once, here, from the camera's rotation. The result is re-encoded without EXIF/GPS, so the
 * existing pipeline reads orientation 1 and never rotates again. Preview, OCR and regions all use this persisted image.
 */
object CapturedPageNormalizer {
    const val JPEG_QUALITY = 92
    private const val MAX_DECODE_SIDE = 4096

    fun normalize(jpeg: ByteArray, rotationDegrees: Int): NormalizedPage {
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        try {
            if (jpeg.isEmpty()) throw CaptureRejected("CAPTURE_EMPTY")
            if (jpeg.size > DocumentLimits.MAX_BYTES) throw CaptureRejected("CAPTURE_TOO_LARGE")
            if (rotationDegrees !in setOf(0, 90, 180, 270)) throw CaptureRejected("CAPTURE_ROTATION_INVALID")
            if (jpeg.size < 4 || jpeg[0] != 0xff.toByte() || jpeg[1] != 0xd8.toByte()) throw CaptureRejected("CAPTURE_FORMAT_INVALID")
            // Embedded orientation, when present, must agree with the camera rotation; otherwise we cannot know the true up.
            val exif = try { ImageOrientation.read(jpeg) } catch (_: IOException) { throw CaptureRejected("CAPTURE_ORIENTATION_INCONSISTENT") }
            val expected = when (rotationDegrees) { 0 -> 1; 90 -> 6; 180 -> 3; else -> 8 }
            if (exif != 1 && exif != expected) throw CaptureRejected("CAPTURE_ORIENTATION_INCONSISTENT")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType != "image/jpeg") throw CaptureRejected("CAPTURE_FORMAT_INVALID")
            var sample = 1
            val side = maxOf(bounds.outWidth, bounds.outHeight).toLong()
            while ((side + sample - 1) / sample > MAX_DECODE_SIDE) sample *= 2
            decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
                BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                ?: throw CaptureRejected("CAPTURE_DECODE_FAILED")
            val longest = maxOf(decoded.width, decoded.height)
            val scale = if (longest > DocumentLimits.MAX_RASTER_SIDE) DocumentLimits.MAX_RASTER_SIDE.toFloat() / longest else 1f
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()); if (scale < 1f) postScale(scale, scale) }
            oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (maxOf(oriented.width, oriented.height) > DocumentLimits.MAX_RASTER_SIDE) throw CaptureRejected("CAPTURE_RASTER_LIMIT")
            val out = ByteArrayOutputStream()
            if (!oriented.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) throw CaptureRejected("CAPTURE_ENCODE_FAILED")
            val bytes = out.toByteArray()
            if (bytes.size > DocumentLimits.MAX_BYTES) { bytes.fill(0); throw CaptureRejected("CAPTURE_TOO_LARGE") }
            return NormalizedPage(bytes, oriented.width, oriented.height)
        } catch (rejected: CaptureRejected) {
            throw rejected
        } catch (_: OutOfMemoryError) {
            throw CaptureRejected("CAPTURE_MEMORY_LIMIT")
        } catch (_: Exception) {
            throw CaptureRejected("CAPTURE_DECODE_FAILED")
        } finally {
            jpeg.fill(0)
            if (oriented !== decoded) oriented?.recycle()
            decoded?.recycle()
        }
    }
}

/** What the camera hands back, reduced to what normalization needs, so closing is testable without hardware. */
interface CapturedFrame {
    val isJpeg: Boolean
    val rotationDegrees: Int
    /** Copy of the encoded bytes; rejects frames above the byte limit before copying. */
    fun readJpeg(): ByteArray
    fun close()
}

object PageFrameProcessor {
    /** The frame is closed on every path, including rejection and unexpected failures. */
    fun process(frame: CapturedFrame): NormalizedPage = try {
        if (!frame.isJpeg) throw CaptureRejected("CAPTURE_FORMAT_INVALID")
        CapturedPageNormalizer.normalize(frame.readJpeg(), frame.rotationDegrees)
    } catch (rejected: CaptureRejected) {
        throw rejected
    } catch (_: Exception) {
        throw CaptureRejected("CAPTURE_DECODE_FAILED")
    } finally {
        frame.close()
    }
}

object CapturedDocuments {
    /**
     * Same signature, truncation and hash validation as any imported JPEG, over a copy of the bytes. The page is not
     * consumed here: the importer wipes it after persistence, whatever the outcome.
     */
    fun validate(page: NormalizedPage): ValidatedDocument {
        val validated = ValidatedDocumentInput.read(java.io.ByteArrayInputStream(page.jpeg), "image/jpeg")
        check(validated.mimeType == "image/jpeg") { "CAPTURE_MIME_INVALID" }
        return validated
    }
}
