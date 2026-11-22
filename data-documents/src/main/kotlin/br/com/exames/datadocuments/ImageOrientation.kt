package br.com.exames.datadocuments

import java.io.IOException

/** Reads only numeric IFD0 Orientation. Never decodes or logs names, GPS, thumbnails or XMP. */
internal object ImageOrientation {
    fun read(bytes: ByteArray): Int {
        val values = mutableSetOf<Int>()
        if (bytes.size >= 2 && u8(bytes, 0) == 255 && u8(bytes, 1) == 216) {
            var position = 2
            while (position < bytes.size) {
                if (u8(bytes, position++) != 255) invalid()
                while (position < bytes.size && u8(bytes, position) == 255) position++
                if (position >= bytes.size) invalid()
                val marker = u8(bytes, position++)
                if (marker == 218 || marker == 217) break
                if (marker in 208..215 || marker == 1) continue
                if (position > bytes.size - 2) invalid()
                val length = (u8(bytes, position) shl 8) or u8(bytes, position + 1)
                if (length < 2 || length > bytes.size - position) invalid()
                val data = position + 2
                if (marker == 225 && length >= 8 && bytes.copyOfRange(data, data + 6).contentEquals(byteArrayOf(69, 120, 105, 102, 0, 0))) {
                    values += tiff(bytes, data + 6, position + length)
                }
                position += length
            }
        } else if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) {
            var position = 8
            while (position <= bytes.size - 12) {
                val size = (0..3).fold(0L) { n, i -> (n shl 8) or u8(bytes, position + i).toLong() }
                if (size > bytes.size.toLong() - position - 12) invalid()
                if (bytes.copyOfRange(position + 4, position + 8).contentEquals(byteArrayOf(101, 88, 73, 102))) {
                    values += tiff(bytes, position + 8, position + 8 + size.toInt())
                }
                position += size.toInt() + 12
            }
        } else throw IOException("IMAGE_FORMAT_INVALID")
        if (values.size > 1) invalid()
        return values.singleOrNull() ?: 1
    }
    private fun tiff(bytes: ByteArray, start: Int, end: Int): Int {
        if (end - start < 8) invalid()
        val little = when {
            bytes[start] == 73.toByte() && bytes[start + 1] == 73.toByte() -> true
            bytes[start] == 77.toByte() && bytes[start + 1] == 77.toByte() -> false
            else -> invalid()
        }
        fun number(offset: Int, size: Int): Long {
            if (offset < start || offset.toLong() + size > end) invalid()
            var result = 0L
            for (index in 0 until size) {
                val at = if (little) offset + size - 1 - index else offset + index
                result = (result shl 8) or u8(bytes, at).toLong()
            }
            return result
        }
        if (number(start + 2, 2) != 42L) invalid()
        val offset = number(start + 4, 4)
        if (offset < 8 || offset > end.toLong() - start - 2) invalid()
        val directory = start + offset.toInt()
        val count = number(directory, 2).toInt()
        if (count > 1024 || directory.toLong() + 2 + count * 12L + 4 > end) invalid()
        val values = mutableSetOf<Int>()
        for (index in 0 until count) {
            val entry = directory + 2 + index * 12
            if (number(entry, 2) == 0x112L) {
                if (number(entry + 2, 2) != 3L || number(entry + 4, 4) != 1L) invalid()
                val value = number(entry + 8, 2).toInt()
                if (value !in 1..8) invalid()
                values += value
            }
        }
        if (values.size > 1) invalid()
        return values.singleOrNull() ?: 1
    }
    private fun u8(bytes: ByteArray, at: Int) = bytes[at].toInt() and 255
    private fun invalid(): Nothing = throw IOException("IMAGE_ORIENTATION_INVALID")
}
