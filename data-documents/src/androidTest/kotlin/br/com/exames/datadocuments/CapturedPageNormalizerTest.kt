package br.com.exames.datadocuments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class CapturedPageNormalizerTest {
    /** 200x100 white page with a red block in the top-left corner: orientation is visible in the pixels. */
    private fun page(width: Int = 200, height: Int = 100): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.WHITE); drawRect(0f, 0f, width * .2f, height * .4f, Paint().apply { color = Color.RED }) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray().also { bitmap.recycle() }
    }
    /** Inserts a minimal EXIF APP1 (big-endian) with an orientation tag and optional fake GPS/identity text. */
    private fun withExif(jpeg: ByteArray, orientation: Int, extra: String = ""): ByteArray {
        val tiff = byteArrayOf(0x4d, 0x4d, 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, 0, 0, 0, 0) + extra.toByteArray()
        val body = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
        val length = body.size + 2
        return byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), (length shr 8).toByte(), length.toByte()) + body + jpeg.copyOfRange(2, jpeg.size)
    }
    private fun decode(page: NormalizedPage) = BitmapFactory.decodeByteArray(page.jpeg, 0, page.jpeg.size)
    private fun isRed(color: Int) = Color.red(color) > 180 && Color.green(color) < 90 && Color.blue(color) < 90
    private fun isWhite(color: Int) = Color.red(color) > 200 && Color.green(color) > 200 && Color.blue(color) > 200
    private fun code(block: () -> Unit): String = try { block(); "NO_ERROR" } catch (e: CaptureRejected) { e.code }

    @Test fun rotationIsAppliedExactlyOnceAndMovesTheMarkerToTheExpectedCorner() {
        // (rotation, expected corner of the red block, expected width, height)
        val cases = listOf(Triple(0, "TL", 200 to 100), Triple(90, "TR", 100 to 200), Triple(180, "BR", 200 to 100), Triple(270, "BL", 100 to 200))
        for ((rotation, corner, size) in cases) {
            val result = CapturedPageNormalizer.normalize(page(), rotation)
            assertEquals("size@$rotation", size, result.width to result.height)
            val bitmap = decode(result)
            assertEquals(size, bitmap.width to bitmap.height)
            fun at(fx: Float, fy: Float) = bitmap.getPixel((bitmap.width * fx).toInt(), (bitmap.height * fy).toInt())
            val corners = mapOf("TL" to at(.05f, .05f), "TR" to at(.95f, .05f), "BL" to at(.05f, .95f), "BR" to at(.95f, .95f))
            for ((name, color) in corners) if (name == corner) assertTrue("$name red @$rotation", isRed(color)) else assertTrue("$name white @$rotation", isWhite(color))
            bitmap.recycle()
        }
    }

    @Test fun outputHasNoMetadataAndThePipelineDoesNotRotateItAgain() {
        val marker = "GPSLatitude=FAKE;Patient=SYNTHETIC-NAME"
        val result = CapturedPageNormalizer.normalize(withExif(page(), 6, marker), 90)
        val text = String(result.jpeg, Charsets.ISO_8859_1)
        assertFalse(text.contains("Exif")); assertFalse(text.contains("GPS")); assertFalse(text.contains("SYNTHETIC-NAME"))
        assertEquals(1, ImageOrientation.read(result.jpeg))
        // Same dimensions, sampling 1 and orientation 1 in the existing decoder: no second rotation, preview == OCR image.
        val (w, h) = result.width to result.height
        val decoded = BoundedImageDecoder().decodeOwned(result.jpeg.copyOf())
        assertEquals(w to h, decoded.bitmap.width to decoded.bitmap.height)
        assertEquals(1, decoded.transform.exifOrientation); assertEquals(1, decoded.transform.sampleSize)
        decoded.close()
        assertEquals(100 to 200, w to h)
    }

    @Test fun embeddedOrientationMustAgreeWithTheCameraRotation() {
        assertEquals("NO_ERROR", code { CapturedPageNormalizer.normalize(withExif(page(), 6), 90) })
        assertEquals("NO_ERROR", code { CapturedPageNormalizer.normalize(withExif(page(), 3), 180) })
        assertEquals("NO_ERROR", code { CapturedPageNormalizer.normalize(page(), 270) }) // no EXIF: rotation alone decides
        assertEquals("CAPTURE_ORIENTATION_INCONSISTENT", code { CapturedPageNormalizer.normalize(withExif(page(), 6), 0) })
        assertEquals("CAPTURE_ORIENTATION_INCONSISTENT", code { CapturedPageNormalizer.normalize(withExif(page(), 3), 90) })
        assertEquals("CAPTURE_ORIENTATION_INCONSISTENT", code { CapturedPageNormalizer.normalize(withExif(page(), 9), 0) })
    }

    @Test fun invalidContentIsRejectedWithStableCodesWithoutDetails() {
        assertEquals("CAPTURE_EMPTY", code { CapturedPageNormalizer.normalize(ByteArray(0), 0) })
        assertEquals("CAPTURE_ROTATION_INVALID", code { CapturedPageNormalizer.normalize(page(), 45) })
        assertEquals("CAPTURE_FORMAT_INVALID", code { CapturedPageNormalizer.normalize(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10, 0, 0), 0) })
        assertEquals("CAPTURE_FORMAT_INVALID", code { CapturedPageNormalizer.normalize("not an image at all".toByteArray(), 0) })
        val truncated = page().let { it.copyOf(it.size / 4) }
        val outcome = code { CapturedPageNormalizer.normalize(truncated, 0) }
        // Unreadable structure fails closed (orientation cannot be trusted); a partially decodable JPEG must still never crash.
        assertTrue(outcome, outcome in setOf("CAPTURE_ORIENTATION_INCONSISTENT", "CAPTURE_DECODE_FAILED", "NO_ERROR"))
        assertNotEquals("NO_ERROR", code { CapturedPageNormalizer.normalize(page().let { it.copyOf(40) }, 0) })
        for (rejected in listOf("CAPTURE_EMPTY", "CAPTURE_FORMAT_INVALID")) assertFalse(rejected.contains(" "))
    }

    @Test fun inputAboveTwentyMiBIsRejectedBeforeDecoding() {
        val oversized = ByteArray(DocumentLimits.MAX_BYTES + 1).also { it[0] = 0xff.toByte(); it[1] = 0xd8.toByte() }
        assertEquals("CAPTURE_TOO_LARGE", code { CapturedPageNormalizer.normalize(oversized, 0) })
        assertTrue("input wiped", oversized.all { it == 0.toByte() })
    }

    @Test fun largeCapturesAreScaledToTheRasterLimitKeepingAspectAndByteBudget() {
        val result = CapturedPageNormalizer.normalize(page(2600, 1950), 90) // larger than the raster limit, small enough for API 24 heaps
        assertTrue(maxOf(result.width, result.height) <= DocumentLimits.MAX_RASTER_SIDE)
        assertEquals(1950f / 2600f, result.width.toFloat() / result.height, .02f) // rotated: width/height = 1950/2600
        assertTrue(result.jpeg.size <= DocumentLimits.MAX_BYTES)
    }

    @Test fun inputBytesAreWipedAndOutputPassesTheExistingTransportValidation() {
        val input = page()
        val result = CapturedPageNormalizer.normalize(input, 0)
        assertTrue("input wiped", input.all { it == 0.toByte() })
        val validated = ValidatedDocumentInput.read(ByteArrayInputStream(result.jpeg), "image/jpeg")
        assertEquals("image/jpeg", validated.mimeType)
        result.wipe(); assertTrue(result.jpeg.all { it == 0.toByte() })
    }

    @Test fun normalizationCreatesNoFileInPrivateOrPublicAppDirectories() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roots = listOfNotNull(context.cacheDir, context.filesDir, context.noBackupFilesDir, context.codeCacheDir,
            context.externalCacheDir, context.getExternalFilesDir(null))
        fun snapshot() = roots.associateWith { dir -> dir.walkTopDown().filter { it.isFile }.map { it.path to it.length() }.toSet() }
        val before = snapshot()
        CapturedPageNormalizer.normalize(page(), 90).wipe()
        runCatching { CapturedPageNormalizer.normalize(ByteArray(8), 0) }
        assertEquals(before, snapshot())
    }

    private class FakeFrame(override val isJpeg: Boolean = true, override val rotationDegrees: Int = 0,
        private val bytes: () -> ByteArray) : CapturedFrame {
        var closed = 0
        override fun readJpeg() = bytes()
        override fun close() { closed++ }
    }

    @Test fun theFrameIsClosedExactlyOnceOnEveryPath() {
        val ok = FakeFrame { page() }
        PageFrameProcessor.process(ok).wipe(); assertEquals(1, ok.closed)
        val notJpeg = FakeFrame(isJpeg = false) { page() }
        assertEquals("CAPTURE_FORMAT_INVALID", code { PageFrameProcessor.process(notJpeg) }); assertEquals(1, notJpeg.closed)
        val unreadable = FakeFrame { throw IllegalStateException("BOOM_WITH_PRIVATE_DETAILS") }
        val failure = runCatching { PageFrameProcessor.process(unreadable) }.exceptionOrNull()
        assertTrue(failure is CaptureRejected); assertFalse(failure!!.message.orEmpty().contains("BOOM"))
        assertEquals(1, unreadable.closed)
        val rejected = FakeFrame(rotationDegrees = 45) { page() }
        assertEquals("CAPTURE_ROTATION_INVALID", code { PageFrameProcessor.process(rejected) }); assertEquals(1, rejected.closed)
        val tooBig = FakeFrame { throw CaptureRejected("CAPTURE_TOO_LARGE") }
        assertEquals("CAPTURE_TOO_LARGE", code { PageFrameProcessor.process(tooBig) }); assertEquals(1, tooBig.closed)
    }
}
