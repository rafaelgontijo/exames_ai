package br.com.exames.datadocuments

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ImageOrientationTest {
    private fun tiff(value: Int, little: Boolean = false, tag: Int = 0x112): ByteArray =
        ByteBuffer.allocate(26).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).apply {
            putShort(if (little) 0x4949.toShort() else 0x4d4d.toShort()); putShort(42); putInt(8)
            putShort(1); putShort(tag.toShort()); putShort(3); putInt(1); putShort(value.toShort()); putShort(0); putInt(0)
        }.array()
    private fun jpeg(vararg entries: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream(); val stream = DataOutputStream(bytes)
        stream.writeShort(0xffd8)
        entries.forEach { data ->
            stream.writeShort(0xffe1); stream.writeShort(data.size + 8)
            stream.write(byteArrayOf(69, 120, 105, 102, 0, 0)); stream.write(data)
        }
        stream.writeShort(0xffd9); return bytes.toByteArray()
    }
    @Test fun readsAllEightValuesInBothEndianOrders() {
        for (little in listOf(false, true)) for (value in 1..8)
            assertEquals(value, ImageOrientation.read(jpeg(tiff(value, little))))
    }
    @Test fun missingOrientationIsIdentityAndOtherTagsAreNotDecoded() {
        assertEquals(1, ImageOrientation.read(jpeg()))
        assertEquals(1, ImageOrientation.read(jpeg(tiff(10000, tag = 0x8825))))
    }
    @Test fun rejectsConflictingOrInvalidOrientationsWithoutEchoingMetadata() {
        reject(jpeg(tiff(6), tiff(3)))
        reject(jpeg(tiff(0))); reject(jpeg(tiff(9)))
        assertEquals(6, ImageOrientation.read(jpeg(tiff(6), tiff(6))))
    }
    @Test fun rejectsOverflowOffsetsAndTruncatedSegments() {
        val invalid = tiff(1); for (i in 4..7) invalid[i] = 0xff.toByte()
        reject(jpeg(invalid))
        val full = jpeg(tiff(6))
        for (size in 4 until full.size - 2) reject(full.copyOf(size))
    }
    private fun reject(bytes: ByteArray) {
        try { ImageOrientation.read(bytes); fail("Expected rejection") }
        catch (e: IOException) { assertEquals("IMAGE_ORIENTATION_INVALID", e.message) }
    }
}
