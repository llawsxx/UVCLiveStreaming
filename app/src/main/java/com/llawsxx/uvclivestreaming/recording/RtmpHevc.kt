package com.llawsxx.uvclivestreaming.recording

import java.io.ByteArrayOutputStream

/** ISO/IEC 14496-15 hvcC and four-byte length-prefixed HEVC for Enhanced RTMP. */
internal object RtmpHevc {
    fun sourceLengthSize(csd: ByteArray): Int =
        if (csd.size >= 23 && csd[0] == 1.toByte()) (csd[21].toInt() and 3) + 1 else 4

    fun configuration(csd0: ByteArray, csd1: ByteArray? = null): ByteArray {
        if (csd0.size >= 23 && csd0[0] == 1.toByte()) {
            val nals = readConfigurationArrays(csd0)
            requireParameterSets(nals)
            // Every outgoing coded frame is normalized to four-byte NAL lengths.
            return csd0.copyOf().also { it[21] = (it[21].toInt() or 3).toByte() }
        }
        val nals = annexB(csd0) + (csd1?.let(::annexB) ?: emptyList())
        requireParameterSets(nals)
        val sps = nals.first { type(it) == 33 }
        val bits = Bits(rbsp(sps.copyOfRange(2, sps.size)))
        bits.read(4) // sps_video_parameter_set_id
        val subLayers = bits.read(3)
        require(subLayers <= 6) { "Invalid HEVC temporal layers" }
        val nested = bits.read(1)
        // profile/tier, compatibility (4), constraints (6), level: exactly 12 bytes.
        val profileTierLevel = ByteArray(12) { bits.read(8).toByte() }
        val profiles = BooleanArray(subLayers)
        val levels = BooleanArray(subLayers)
        repeat(subLayers) { profiles[it] = bits.read(1) != 0; levels[it] = bits.read(1) != 0 }
        if (subLayers > 0) repeat(8 - subLayers) { bits.read(2) }
        repeat(subLayers) { if (profiles[it]) bits.skip(88); if (levels[it]) bits.skip(8) }
        bits.ue() // sps_seq_parameter_set_id
        val chroma = bits.ue()
        require(chroma in 0..3) { "Invalid HEVC chroma format" }
        if (chroma == 3) bits.read(1)
        require(bits.ue() > 0 && bits.ue() > 0) { "Invalid HEVC SPS dimensions" }
        if (bits.read(1) != 0) repeat(4) { bits.ue() }
        val lumaDepth = bits.ue()
        val chromaDepth = bits.ue()
        require(lumaDepth in 0..7 && chromaDepth in 0..7) { "Unsupported HEVC bit depth" }
        val groups = listOf(32, 33, 34).map { t -> t to nals.filter { type(it) == t } }
        val header = ByteArray(23)
        header[0] = 1
        profileTierLevel.copyInto(header, 1)
        header[13] = 0xf0.toByte() // reserved + unknown min_spatial_segmentation_idc
        header[15] = 0xfc.toByte() // unknown parallelismType
        header[16] = (0xfc or chroma).toByte()
        header[17] = (0xf8 or lumaDepth).toByte()
        header[18] = (0xf8 or chromaDepth).toByte()
        header[21] = (((subLayers + 1) shl 3) or (nested shl 2) or 3).toByte()
        header[22] = groups.size.toByte()
        return ByteArrayOutputStream().also { out ->
            out.write(header)
            for ((t, values) in groups) {
                require(values.size <= 65535) { "Too many HEVC parameter sets" }
                out.write(0x80 or t)
                out.write(values.size ushr 8); out.write(values.size)
                for (nal in values) {
                    require(nal.size <= 65535) { "HEVC parameter set too large" }
                    out.write(nal.size ushr 8); out.write(nal.size); out.write(nal)
                }
            }
        }.toByteArray()
    }

    fun codedFrame(data: ByteArray, inputLengthSize: Int = 4): ByteArray {
        // Validate lengths first: a length such as 00 00 01 40 must not be mistaken for Annex B.
        val nals = lengthPrefixed(data, inputLengthSize) ?: annexB(data)
        require(nals.isNotEmpty()) { "HEVC frame has no valid NAL units" }
        return ByteArrayOutputStream().also { out ->
            for (nal in nals) {
                out.write(RtmpAmf.uint32Bytes(nal.size.toLong()))
                out.write(nal)
            }
        }.toByteArray()
    }

    private fun requireParameterSets(nals: List<ByteArray>) {
        require(listOf(32, 33, 34).all { t -> nals.any { type(it) == t } }) {
            "HEVC requires complete VPS, SPS and PPS"
        }
    }

    private fun valid(nal: ByteArray): Boolean = nal.size >= 2 &&
        (nal[0].toInt() and 0x80) == 0 && (nal[1].toInt() and 7) != 0
    private fun type(nal: ByteArray): Int = (nal[0].toInt() and 0x7e) ushr 1

    private fun lengthPrefixed(data: ByteArray, size: Int): List<ByteArray>? {
        require(size in 1..4)
        val nals = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            if (data.size - offset < size) return null
            var length = 0L
            repeat(size) { length = (length shl 8) or (data[offset++].toLong() and 255) }
            if (length < 2 || length > data.size - offset) return null
            val nal = data.copyOfRange(offset, offset + length.toInt())
            if (!valid(nal)) return null
            nals += nal
            offset += length.toInt()
        }
        return nals.takeIf { it.isNotEmpty() }
    }

    private fun annexB(data: ByteArray): List<ByteArray> {
        fun start(from: Int): Int {
            for (i in from until data.size - 2) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    (data[i + 2] == 1.toByte() || (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()))) return i
            }
            return -1
        }
        var pos = start(0)
        if (pos < 0 || (0 until pos).any { data[it] != 0.toByte() }) return emptyList()
        val nals = mutableListOf<ByteArray>()
        while (pos >= 0) {
            val begin = pos + if (data[pos + 2] == 1.toByte()) 3 else 4
            val next = start(begin)
            var end = if (next >= 0) next else data.size
            while (end > begin && data[end - 1] == 0.toByte()) end--
            val nal = data.copyOfRange(begin, end)
            if (!valid(nal)) return emptyList()
            nals += nal
            pos = next
        }
        return nals
    }

    private fun readConfigurationArrays(data: ByteArray): List<ByteArray> {
        require((data[13].toInt() and 0xf0) == 0xf0 && (data[15].toInt() and 0xfc) == 0xfc &&
            (data[16].toInt() and 0xfc) == 0xfc && (data[17].toInt() and 0xf8) == 0xf8 &&
            (data[18].toInt() and 0xf8) == 0xf8) { "Invalid hvcC header layout" }
        var pos = 23
        fun u16(): Int {
            require(data.size - pos >= 2) { "Truncated hvcC array" }
            return ((data[pos++].toInt() and 255) shl 8) or (data[pos++].toInt() and 255)
        }
        val nals = mutableListOf<ByteArray>()
        repeat(data[22].toInt() and 255) {
            require(pos < data.size) { "Missing hvcC NAL array" }
            val t = data[pos++].toInt() and 63
            repeat(u16()) {
                val length = u16()
                require(length >= 2 && length <= data.size - pos) { "Invalid hvcC NAL length" }
                val nal = data.copyOfRange(pos, pos + length)
                require(valid(nal) && type(nal) == t) { "Invalid hvcC NAL type" }
                nals += nal
                pos += length
            }
        }
        require(pos == data.size) { "Trailing bytes in hvcC" }
        return nals
    }

    private fun rbsp(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var zeros = 0
        for (b in data) {
            val value = b.toInt() and 255
            if (zeros >= 2 && value == 3) { zeros = 0; continue }
            out.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class Bits(private val data: ByteArray) {
        private var pos = 0
        fun read(count: Int): Int {
            require(count in 0..30 && pos.toLong() + count <= data.size.toLong() * 8) { "Truncated HEVC SPS" }
            var value = 0
            repeat(count) { value = (value shl 1) or ((data[pos / 8].toInt() ushr (7 - pos++ % 8)) and 1) }
            return value
        }
        fun skip(count: Int) { repeat(count) { read(1) } }
        fun ue(): Int {
            var zeros = 0
            while (read(1) == 0) { zeros++; require(zeros < 30) { "Invalid HEVC Exp-Golomb value" } }
            return (1 shl zeros) - 1 + read(zeros)
        }
    }
}
