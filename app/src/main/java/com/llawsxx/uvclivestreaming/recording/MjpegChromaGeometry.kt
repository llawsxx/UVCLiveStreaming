package com.llawsxx.uvclivestreaming.recording

import java.nio.ByteBuffer

/** Reads only the bounded JPEG marker header, so decoded buffers keep the source sampling. */
internal object MjpegChromaGeometry {
    fun read(data: ByteArray, width: Int, height: Int): Pair<Int, Int>? = read(data.size, width, height) { data[it] }
    fun read(data: ByteBuffer, width: Int, height: Int): Pair<Int, Int>? = read(data.limit(), width, height) { data.get(it) }

    private fun read(size: Int, width: Int, height: Int, byteAt: (Int) -> Byte): Pair<Int, Int>? {
        fun u8(i: Int) = byteAt(i).toInt() and 255
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        var pos = -1
        for (i in 0 until size - 1) {
            if (u8(i) == 255 && u8(i + 1) == 216) { pos = i + 2; break }
        }
        if (pos < 0) {
            // The native repair path may restore a missing SOI after a short transport prefix.
            pos = (0..minOf(32, size - 2)).firstOrNull {
                u8(it) == 255 && (u8(it + 1) in 224..239 || u8(it + 1) in listOf(219, 192, 196, 254))
            } ?: return null
        }
        while (pos < size) {
            if (u8(pos++) != 255) return null
            while (pos < size && u8(pos) == 255) pos++
            if (pos >= size) return null
            val marker = u8(pos++)
            if (marker == 218 || marker == 217 || size - pos < 2) return null
            val length = u16(pos)
            if (length < 2 || length > size - pos) return null
            if (marker in listOf(192, 193, 194)) {
                if (length < 8 || u8(pos + 2) != 8 || u16(pos + 3) != height || u16(pos + 5) != width) return null
                val count = u8(pos + 7)
                if (count != 1 && count != 3 || length != 8 + 3 * count) return null
                if (count == 1) return (width + 1) / 2 to (height + 1) / 2
                val sampling = IntArray(3) { u8(pos + 9 + 3 * it) }
                val hs = sampling.map { it ushr 4 }; val vs = sampling.map { it and 15 }
                if (hs.any { it !in 1..4 } || vs.any { it !in 1..4 } ||
                    hs[0] != hs.max() || vs[0] != vs.max() || hs[1] != hs[2] || vs[1] != vs[2]) return null
                return (width * hs[1] + hs[0] - 1) / hs[0] to (height * vs[1] + vs[0] - 1) / vs[0]
            }
            pos += length
        }
        return null
    }
}
