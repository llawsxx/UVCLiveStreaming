package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test

class MjpegChromaGeometryTest {
    private fun header(y: Int, uv: Int, width: Int = 33, height: Int = 31) =
        byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0, 4, 0, 0,
            0xff.toByte(), 0xc0.toByte(), 0, 17, 8, (height ushr 8).toByte(), height.toByte(),
            (width ushr 8).toByte(), width.toByte(), 3, 1, y.toByte(), 0, 2, uv.toByte(), 1, 3, uv.toByte(), 1)

    @Test fun sourceSamplingAndOddChromaDimensionsArePreserved() {
        for ((sampling, geometry) in listOf(0x22 to (17 to 16), 0x21 to (17 to 31),
            0x11 to (33 to 31), 0x12 to (33 to 16), 0x41 to (9 to 31), 0x42 to (9 to 16))) {
            assertEquals(geometry, MjpegChromaGeometry.read(header(sampling, 0x11), 33, 31))
        }
        assertEquals(17 to 31, MjpegChromaGeometry.read(header(0x42, 0x22), 33, 31))
    }

    @Test fun markerRecoveryDoesNotRequireSoiOrEoi() {
        val original = header(0x21, 0x11)
        for (input in listOf(original, byteArrayOf(1, 2, 3) + original,
            original.copyOfRange(2, original.size), byteArrayOf(1, 2, 3) + original.copyOfRange(2, original.size))) {
            assertEquals(17 to 31, MjpegChromaGeometry.read(input, 33, 31))
        }
    }

    @Test fun unsafeHeadersAndTruncationAreRejected() {
        val original = header(0x21, 0x11)
        for (length in 0 until original.size) assertNull(MjpegChromaGeometry.read(original.copyOf(length), 33, 31))
        assertNull(MjpegChromaGeometry.read(original, 32, 31))
        assertNull(MjpegChromaGeometry.read(original, 33, 30))
        assertNull(MjpegChromaGeometry.read(header(0x20, 0x11), 33, 31))
        assertNull(MjpegChromaGeometry.read(header(0x11, 0x22), 33, 31))
        assertNull(MjpegChromaGeometry.read(original.copyOf().also { it[it.lastIndex - 1] = 0x22 }, 33, 31))
    }

    @Test fun tenBitLimitedMatrixUsesTenBitCodeValues() {
        val matrix = UsbYuvMatrix.BT709.conversionMatrix(false, 10)
        assertEquals(1f, matrix[0] * (940f / 1023f - 64f / 1023f), 1e-6f)
        assertEquals(1f, UsbYuvMatrix.BT709.conversionMatrix(true, 10)[0], 0f)
    }
}
