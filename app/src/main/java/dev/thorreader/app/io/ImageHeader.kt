package dev.thorreader.app.io

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Reads an image's width/height from its file header only (JPEG, PNG, GIF, BMP, WebP), without
 * decoding pixels. Returns null if the format isn't recognised.
 */
object ImageHeader {

    fun size(input: InputStream): Pair<Int, Int>? = runCatching {
        val s = DataInputStream(input.buffered(8192))
        val head = ByteArray(30)
        s.mark(64)
        val n = s.read(head)
        if (n < 12) return null
        fun u8(i: Int) = head[i].toInt() and 0xFF
        fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun le32(i: Int) = le16(i) or (le16(i + 2) shl 16)
        fun be32(i: Int) = (be16(i) shl 16) or be16(i + 2)
        when {
            // PNG: IHDR width/height at 16..23
            u8(0) == 0x89 && head[1] == 'P'.code.toByte() && n >= 24 -> be32(16) to be32(20)
            // GIF
            head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() -> le16(6) to le16(8)
            // BMP
            head[0] == 'B'.code.toByte() && head[1] == 'M'.code.toByte() && n >= 26 -> le32(18) to kotlin.math.abs(le32(22))
            // WebP
            head[0] == 'R'.code.toByte() && head[8] == 'W'.code.toByte() && n >= 30 -> {
                when (String(head, 12, 4, Charsets.US_ASCII)) {
                    "VP8 " -> (le16(26) and 0x3FFF) to (le16(28) and 0x3FFF)
                    "VP8L" -> {
                        val b = le32(21)
                        ((b and 0x3FFF) + 1) to (((b shr 14) and 0x3FFF) + 1)
                    }
                    "VP8X" -> ((u8(24) or (u8(25) shl 8) or (u8(26) shl 16)) + 1) to
                        ((u8(27) or (u8(28) shl 8) or (u8(29) shl 16)) + 1)
                    else -> null
                }
            }
            // JPEG: walk the markers until a start-of-frame
            u8(0) == 0xFF && u8(1) == 0xD8 -> {
                s.reset()
                s.skipFully(2)
                jpeg(s)
            }
            else -> null
        }?.takeIf { it.first > 0 && it.second > 0 }
    }.getOrNull()

    private fun jpeg(s: DataInputStream): Pair<Int, Int>? {
        while (true) {
            var b = s.readUnsignedByte()
            if (b != 0xFF) continue
            do {
                b = s.readUnsignedByte()
            } while (b == 0xFF)
            val marker = b
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) continue
            if (marker == 0xD9 || marker == 0xDA) return null // end of image / start of scan: no SOF
            val len = s.readUnsignedShort()
            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                s.readUnsignedByte() // precision
                val h = s.readUnsignedShort()
                val w = s.readUnsignedShort()
                return w to h
            }
            s.skipFully(len - 2)
        }
    }

    private fun DataInputStream.skipFully(count: Int) {
        var left = count.toLong()
        while (left > 0) {
            val k = skip(left)
            if (k <= 0) {
                if (read() < 0) throw EOFException()
                left--
            } else {
                left -= k
            }
        }
    }
}
