package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class YuvEncoderTransformTest {
    private fun apply(coefficients: IntArray, input: IntArray): IntArray = IntArray(3) { row ->
        val total = coefficients[row * 4 + 3].toLong() + input.indices.sumOf {
            coefficients[row * 4 + it].toLong() * input[it]
        }
        ((total + YuvEncoderTransform.SCALE / 2) / YuvEncoderTransform.SCALE).toInt().coerceIn(0, 255)
    }

    @Test fun jpeg601FullRangeBecomesActual709LimitedValues() {
        val transform = YuvEncoderTransform.coefficients(UsbYuvMatrix.BT601, true, UsbYuvMatrix.BT709, false)
        assertArrayEquals(intArrayOf(16, 128, 128), apply(transform, intArrayOf(0, 128, 128)))
        assertArrayEquals(intArrayOf(235, 128, 128), apply(transform, intArrayOf(255, 128, 128)))
        val red = apply(transform, intArrayOf(76, 85, 255))
        assertEquals(63.0, red[0].toDouble(), 1.0)
        assertEquals(102.0, red[1].toDouble(), 1.0)
        assertEquals(240.0, red[2].toDouble(), 1.0)
        assertTrue(red[0] < 76)
    }

    @Test fun sameMatrixAndRangePreserveSamples() {
        for (matrix in UsbYuvMatrix.entries) for (fullRange in listOf(true, false)) {
            val transform = YuvEncoderTransform.coefficients(matrix, fullRange, matrix, fullRange)
            for (sample in listOf(intArrayOf(16, 128, 128), intArrayOf(235, 90, 240), intArrayOf(73, 199, 31)))
                assertArrayEquals(sample, apply(transform, sample))
        }
    }

    @Test fun tenBitSourceNormalizesWithoutUsingHighByteOnly() {
        val transform = YuvEncoderTransform.coefficients(UsbYuvMatrix.BT709, false, UsbYuvMatrix.BT709, false, 10)
        assertArrayEquals(intArrayOf(16, 128, 128), apply(transform, intArrayOf(64, 512, 512)))
        assertArrayEquals(intArrayOf(235, 128, 128), apply(transform, intArrayOf(940, 512, 512)))
        assertArrayEquals(intArrayOf(17, 129, 127), apply(transform, intArrayOf(67, 515, 509)))
    }

    @Test fun rgbInputMatches709Reference() {
        val transform = YuvEncoderTransform.coefficients(UsbYuvMatrix.BT601, true, UsbYuvMatrix.BT709, false, rgb = true)
        for (rgb in listOf(intArrayOf(0, 0, 0), intArrayOf(255, 255, 255), intArrayOf(255, 0, 0),
            intArrayOf(0, 255, 0), intArrayOf(0, 0, 255), intArrayOf(40, 100, 180))) {
            val luma = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]
            val expected = intArrayOf((16 + luma * 219 / 255).roundToInt(),
                (128 + (rgb[2] - luma) / (2 * (1 - 0.0722)) * 224 / 255).roundToInt(),
                (128 + (rgb[0] - luma) / (2 * (1 - 0.2126)) * 224 / 255).roundToInt())
            assertArrayEquals(expected, apply(transform, rgb))
        }
    }

    @Test fun incompatibleModesDoNotSilentlyBypassGradingOrHdr() {
        val config = RecordingConfig(usbYuvEncoderInput = true)
        assertNull(YuvEncoderPolicy.rejection(config, false, false))
        assertNotNull(YuvEncoderPolicy.rejection(config, false, true))
        assertNotNull(YuvEncoderPolicy.rejection(config, true, false))
        assertNotNull(YuvEncoderPolicy.rejection(config.copy(width = 1919), false, false))
        assertNotNull(YuvEncoderPolicy.rejection(config.copy(dynamicRange = VideoDynamicRange.HLG10), false, false))
        assertNotNull(YuvEncoderPolicy.rejection(config.copy(colorStandard = VideoColorStandard.BT2020), false, false))
        assertNotNull(YuvEncoderPolicy.rejection(config.copy(colorTransfer = VideoColorTransfer.ST2084), false, false))
    }
}
