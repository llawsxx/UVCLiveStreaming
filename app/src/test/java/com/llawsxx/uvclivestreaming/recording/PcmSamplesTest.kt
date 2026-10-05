package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test

class PcmSamplesTest {
    private fun unpack16(data: ByteArray) = data.toList().chunked(2).map { (a,b) ->
        ((a.toInt() and 255) or (b.toInt() shl 8)).toShort().toInt()
    }
    @Test fun fullScalePacked24And32UseCorrectSignScaleRoundingAndSaturation() {
        val packed24 = listOf(-8388608, -4194304, -256, 0, 256, 4194304, 8388607)
            .flatMap { value -> (0..2).map { (value shr (8 * it)).toByte() } }.toByteArray()
        val packed32 = listOf(Int.MIN_VALUE, -1073741824, -65536, 0, 65536, 1073741824, Int.MAX_VALUE)
            .flatMap { value -> (0..3).map { (value shr (8 * it)).toByte() } }.toByteArray()
        val expected = listOf(-32768, -16384, -1, 0, 1, 16384, 32767)
        assertEquals(expected, unpack16(PcmSamples.toPcm16(packed24, 3)))
        assertEquals(expected, unpack16(PcmSamples.toPcm16(packed32, 4)))
        val original = byteArrayOf(0, -128, -1, 127)
        assertSame(original, PcmSamples.toPcm16(original, 2))
        assertThrows(IllegalArgumentException::class.java) { PcmSamples.toPcm16(byteArrayOf(1, 2), 3) }
    }
}
