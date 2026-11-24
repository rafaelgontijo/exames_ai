package br.com.exames.datadocuments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageBandSlicerTest {
    @Test fun computesThreeOverlappingHorizontalBandsCoveringFullPageHeight() {
        val bands = PdfPageBandSlicer.computeBandBounds(1000)
        assertEquals(3, bands.size)
        assertEquals(0, bands[0].top)
        assertEquals(420, bands[0].bottom)
        assertEquals(320, bands[1].top)
        assertEquals(740, bands[1].bottom)
        assertEquals(620, bands[2].top)
        assertEquals(1000, bands[2].bottom)
        assertTrue(bands[1].top < bands[0].bottom)
        assertTrue(bands[2].top < bands[1].bottom)
    }

    @Test fun skipsBlankWhiteBandAndDetectsBandWithInkPixels() {
        val w = 100
        val h = 100
        val white = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        assertFalse(PdfPageBandSlicer.hasInkContent(white, w, h))

        val withText = white.copyOf()
        // Draw a small dark block (~1% of pixels) simulating printed text
        for (y in 20 until 30) {
            for (x in 10 until 25) {
                withText[y * w + x] = 0xFF111111.toInt()
            }
        }
        assertTrue(PdfPageBandSlicer.hasInkContent(withText, w, h))
    }

    @Test fun boundsWidthProportionallyAndFallsBackFromOversizedPngToJpeg() {
        assertEquals(1240 to 620, PdfPageBandSlicer.scaledDimensions(2480, 1240))
        assertEquals(800 to 400, PdfPageBandSlicer.scaledDimensions(800, 400))

        val pngFits = PdfPageBandSlicer.selectEncodedBand(
            encodePng = { ByteArray(100) },
            encodeJpeg = { error("JPEG should not be called") },
            maxBytes = 512,
        )
        assertNotNull(pngFits)
        assertEquals("image/png", pngFits!!.mimeType)

        val qualities = mutableListOf<Int>()
        val jpegFallback = PdfPageBandSlicer.selectEncodedBand(
            encodePng = { ByteArray(600) },
            encodeJpeg = { q ->
                qualities += q
                if (q == 88) ByteArray(550) else ByteArray(400)
            },
            maxBytes = 512,
        )
        assertNotNull(jpegFallback)
        assertEquals("image/jpeg", jpegFallback!!.mimeType)
        assertEquals(listOf(88, 75), qualities)

        val stillTooLarge = PdfPageBandSlicer.selectEncodedBand(
            encodePng = { ByteArray(600) },
            encodeJpeg = { ByteArray(600) },
            maxBytes = 512,
        )
        assertNull(stillTooLarge)
    }

    @Test fun projectsRedactionRegionsIntoBandAndPaintsOpaqueBoxesWithoutTouchingOutsidePixels() {
        val bounds = PdfPageBandSlicer.BandBounds(bandIndex = 0, top = 0, bottom = 420)
        val piiOnPage1 = br.com.exames.domain.validation.SourceRegion(
            page = 1,
            left = 100.0,
            top = 40.0,
            right = 300.0,
            bottom = 60.0,
            pageWidth = 1000.0,
            pageHeight = 1000.0,
        )
        val piiBelowBand = br.com.exames.domain.validation.SourceRegion(
            page = 1,
            left = 100.0,
            top = 500.0,
            right = 300.0,
            bottom = 520.0,
            pageWidth = 1000.0,
            pageHeight = 1000.0,
        )
        val piiOtherPage = piiOnPage1.copy(page = 2)

        val rects = PdfPageBandSlicer.projectRedactionsToBand(
            regions = listOf(piiOnPage1, piiBelowBand, piiOtherPage),
            page = 1,
            bounds = bounds,
            sourceWidth = 1000,
            sourceHeight = 1000,
            targetWidth = 500,
            targetHeight = 210,
            padPx = 2,
        )
        assertEquals(1, rects.size)
        val r = rects.single()
        assertEquals(48, r.left)
        assertEquals(18, r.top)
        assertEquals(152, r.right)
        assertEquals(32, r.bottom)

        val w = 200
        val h = 100
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        val fill = 0xFF1A1A1A.toInt()
        PdfPageBandSlicer.applyRedactionsToPixels(pixels, w, h, rects, fill)
        // Inside redaction box -> opaque fill
        assertEquals(fill, pixels[25 * w + 100])
        // Outside redaction box (clinical values region) -> untouched white
        assertEquals(0xFFFFFFFF.toInt(), pixels[80 * w + 100])
        assertEquals(0xFFFFFFFF.toInt(), pixels[10 * w + 10])
    }
}
