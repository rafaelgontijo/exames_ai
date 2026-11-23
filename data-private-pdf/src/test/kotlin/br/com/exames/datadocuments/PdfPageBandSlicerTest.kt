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
}
