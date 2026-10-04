package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

class RtmpHevcTest {
    private fun sample(name: String) = checkNotNull(javaClass.getResourceAsStream("/rtmp/$name.hevc")).use { it.readBytes() }
    private val start = byteArrayOf(0, 0, 0, 1)
    private fun type(nal: ByteArray) = (nal[0].toInt() and 0x7e) ushr 1
    private fun nals(input: ByteArray): List<ByteArray> {
        // Test fixtures use Annex B. Split the start delimiters independently of production code.
        val starts = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i + 2 < input.size) {
            if (input[i] == 0.toByte() && input[i + 1] == 0.toByte() && input[i + 2] == 1.toByte()) {
                starts += i to 3; i += 3
            } else if (i + 3 < input.size && input[i] == 0.toByte() && input[i + 1] == 0.toByte() &&
                input[i + 2] == 0.toByte() && input[i + 3] == 1.toByte()) {
                starts += i to 4; i += 4
            } else i++
        }
        return starts.mapIndexed { index, (offset, length) ->
            var end = starts.getOrNull(index + 1)?.first ?: input.size
            while (end > offset + length && input[end - 1] == 0.toByte()) end--
            input.copyOfRange(offset + length, end)
        }
    }
    private fun annex(nals: List<ByteArray>) = ByteArrayOutputStream().also { out ->
        nals.forEach { out.write(start); out.write(it) }
    }.toByteArray()
    private fun parameterSets(name: String = "hevc-main") = nals(sample(name)).filter { type(it) in 32..34 }

    @Test fun standardOffsetsAndParameterArraysMatchRealSps() {
        val parameters = parameterSets()
        val config = RtmpHevc.configuration(annex(parameters))
        assertEquals(1, config[0].toInt())
        assertEquals(1, config[1].toInt() and 31) // Main
        assertTrue(config[12].toInt() and 255 > 0) // Actual level, not a shifted placeholder.
        assertEquals(0xf0, config[13].toInt() and 255)
        assertEquals(0xfd, config[16].toInt() and 255) // 4:2:0
        assertEquals(0xf8, config[17].toInt() and 255)
        assertEquals(4, RtmpHevc.sourceLengthSize(config))
        assertEquals(3, config[22].toInt())
        var offset = 23
        for (nal in parameters.sortedBy(::type)) {
            assertEquals(0x80 or type(nal), config[offset++].toInt() and 255)
            assertEquals(0, config[offset++].toInt()); assertEquals(1, config[offset++].toInt())
            val length = ((config[offset++].toInt() and 255) shl 8) or (config[offset++].toInt() and 255)
            assertEquals(nal.size, length)
            assertArrayEquals(nal, config.copyOfRange(offset, offset + length))
            offset += length
        }
        assertEquals(config.size, offset)
        assertArrayEquals(config, RtmpHevc.configuration(config))
    }

    @Test fun main10ProfileAndBitDepthComeFromSps() {
        val config = RtmpHevc.configuration(annex(parameterSets("hevc-main10")))
        assertEquals(2, config[1].toInt() and 31)
        assertEquals(2, config[17].toInt() and 7)
        assertEquals(2, config[18].toInt() and 7)
    }

    @Test fun splitCsdAndDifferentNalLengthSizesAreNormalized() {
        val parameters = parameterSets()
        val reference = RtmpHevc.configuration(annex(parameters))
        assertArrayEquals(reference, RtmpHevc.configuration(annex(parameters.filter { type(it) != 34 }),
            annex(parameters.filter { type(it) == 34 })))
        val nal = byteArrayOf(0x02, 0x01, 0x55, 0x66)
        for (size in 1..4) {
            val inputConfig = reference.copyOf().also { it[21] = ((it[21].toInt() and 0xfc) or (size - 1)).toByte() }
            assertEquals(size, RtmpHevc.sourceLengthSize(inputConfig))
            assertArrayEquals(reference, RtmpHevc.configuration(inputConfig))
            val frame = ByteArray(size) { if (it == size - 1) nal.size.toByte() else 0 } + nal
            assertArrayEquals(RtmpAmf.uint32Bytes(nal.size.toLong()) + nal, RtmpHevc.codedFrame(frame, size))
        }
    }

    @Test fun nalLengthThatLooksLikeStartCodeIsNotMisidentified() {
        val nal = ByteArray(257) { 0x55 }.also { it[0] = 0x02; it[1] = 0x01 }
        val frame = RtmpAmf.uint32Bytes(nal.size.toLong()) + nal
        assertArrayEquals(frame, RtmpHevc.codedFrame(frame))
        assertArrayEquals(frame, RtmpHevc.codedFrame(byteArrayOf(0, 0, 1) + nal))
    }

    @Test fun incompleteShiftedAndTruncatedConfigurationsAreRejected() {
        val parameters = parameterSets()
        val valid = RtmpHevc.configuration(annex(parameters))
        rejected { RtmpHevc.configuration(valid.copyOfRange(0, 12) + byteArrayOf(0) + valid.copyOfRange(12, valid.size)) }
        rejected { RtmpHevc.configuration(valid.copyOf(valid.size - 1)) }
        rejected { RtmpHevc.configuration(annex(parameters.filter { type(it) != 32 })) }
        rejected { RtmpHevc.configuration(annex(parameters.filter { type(it) != 33 })) }
        rejected { RtmpHevc.configuration(annex(parameters.filter { type(it) != 34 })) }
        rejected { RtmpHevc.configuration(annex(parameters.map { if (type(it) == 33) it.copyOf(4) else it })) }
        rejected { RtmpHevc.codedFrame(byteArrayOf(0, 0, 0, 20, 0x02, 0x01)) }
    }

    @Test fun exportEnhancedFlvForFfmpegDecoderRegression() {
        val source = sample("hevc-main")
        val units = nals(source)
        val config = RtmpHevc.configuration(annex(units.filter { type(it) in 32..34 }))
        val frames = mutableListOf<MutableList<ByteArray>>()
        units.forEach { nal ->
            if (type(nal) == 35) frames += mutableListOf<ByteArray>()
            frames.last().add(nal)
        }
        assertEquals(6, frames.size)
        val folder = File("../build/perf-diagnostics/rtmp-hevc").apply { mkdirs() }
        fun writeFlv(name: String, header: ByteArray) {
            DataOutputStream(File(folder, name).outputStream()).use { out ->
                out.write(byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 1, 1, 0, 0, 0, 9))
                out.writeInt(0)
                fun tag(time: Int, payload: ByteArray) {
                    out.writeByte(9)
                    out.writeByte(payload.size ushr 16); out.writeByte(payload.size ushr 8); out.writeByte(payload.size)
                    out.writeByte(time ushr 16); out.writeByte(time ushr 8); out.writeByte(time); out.writeByte(time ushr 24)
                    out.write(byteArrayOf(0, 0, 0)); out.write(payload); out.writeInt(11 + payload.size)
                }
                tag(0, byteArrayOf(0x90.toByte(), 'h'.code.toByte(), 'v'.code.toByte(), 'c'.code.toByte(), '1'.code.toByte()) + header)
                frames.forEachIndexed { index, frame ->
                    val key = frame.any { type(it) in 16..23 }
                    val payload = byteArrayOf(if (key) 0x91.toByte() else 0xa1.toByte(),
                        'h'.code.toByte(), 'v'.code.toByte(), 'c'.code.toByte(), '1'.code.toByte(), 0, 0, 0) +
                        RtmpHevc.codedFrame(annex(frame))
                    tag(index * 1000 / 30, payload)
                }
            }
        }
        writeFlv("fixed.flv", config)
        writeFlv("shifted-header.flv", config.copyOfRange(0, 12) + byteArrayOf(0) + config.copyOfRange(12, config.size))
    }

    private fun rejected(action: () -> Unit) {
        try { action(); fail("Invalid HEVC data was accepted") } catch (_: IllegalArgumentException) {}
    }
}
