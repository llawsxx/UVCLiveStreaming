package com.llawsxx.uvclivestreaming.recording

import kotlin.math.roundToInt

internal object YuvEncoderTransform {
    const val SCALE = 16384

    fun coefficients(source: UsbYuvMatrix, sourceFullRange: Boolean,
        target: UsbYuvMatrix, targetFullRange: Boolean, bitDepth: Int = 8,
        rgb: Boolean = false): IntArray {
        require(bitDepth == 8 || bitDepth == 10)
        require(!rgb || bitDepth == 8)
        val maximum = ((1 shl bitDepth) - 1).toFloat()
        val sampleScale = (1 shl (bitDepth - 8)).toFloat()
        val sourceMatrix = if (rgb) {
            val scale = if (sourceFullRange) 1f else 255f / 219f
            floatArrayOf(scale, 0f, 0f, 0f, scale, 0f, 0f, 0f, scale)
        } else source.conversionMatrix(sourceFullRange, bitDepth)
        val targetMatrix = target.encodingMatrix(targetFullRange)
        val sourceOffsets = if (rgb) FloatArray(3) { if (sourceFullRange) 0f else 16f }
            else floatArrayOf(if (sourceFullRange) 0f else 16f * sampleScale, 128f * sampleScale, 128f * sampleScale)
        val targetOffsets = floatArrayOf(if (targetFullRange) 0f else 16f, 128f, 128f)
        return IntArray(12).also { result ->
            for (row in 0..2) {
                var bias = targetOffsets[row]
                for (column in 0..2) {
                    var coefficient = 0f
                    for (component in 0..2) coefficient +=
                        targetMatrix[component * 3 + row] * sourceMatrix[column * 3 + component]
                    coefficient *= 255f / maximum
                    result[row * 4 + column] = (coefficient * SCALE).roundToInt()
                    bias -= coefficient * sourceOffsets[column]
                }
                result[row * 4 + 3] = (bias * SCALE).roundToInt()
            }
        }
    }
}
