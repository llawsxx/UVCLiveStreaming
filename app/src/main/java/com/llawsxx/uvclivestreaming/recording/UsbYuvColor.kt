package com.llawsxx.uvclivestreaming.recording

/** Source Y'CbCr coefficients, used for pixel conversion rather than output metadata. */
enum class UsbYuvMatrix(val label: String, private val kr: Float, private val kb: Float) {
    BT601("BT.601", 0.299f, 0.114f),
    BT709("BT.709", 0.2126f, 0.0722f),
    BT2020("BT.2020 NCL", 0.2627f, 0.0593f),
    SMPTE240M("SMPTE 240M", 0.212f, 0.087f);

    /** Column-major matrix for normalized samples, after subtracting bit-depth-specific offsets. */
    internal fun conversionMatrix(fullRange: Boolean, bitDepth: Int = 8): FloatArray {
        require(bitDepth == 8 || bitDepth == 10)
        val kg = 1f - kr - kb
        val maximum = ((1 shl bitDepth) - 1).toFloat()
        val scale = (1 shl (bitDepth - 8)).toFloat()
        val yScale = if (fullRange) 1f else maximum / (219f * scale)
        val cScale = if (fullRange) 1f else maximum / (224f * scale)
        return floatArrayOf(
            yScale, yScale, yScale,
            0f, -2f * kb * (1f - kb) / kg * cScale, 2f * (1f - kb) * cScale,
            2f * (1f - kr) * cScale, -2f * kr * (1f - kr) / kg * cScale, 0f,
        )
    }

    internal fun encodingMatrix(fullRange: Boolean): FloatArray {
        val kg = 1f - kr - kb
        val yScale = if (fullRange) 1f else 219f / 255f
        val cScale = if (fullRange) 1f else 224f / 255f
        return floatArrayOf(
            kr * yScale, -kr / (2f * (1f - kb)) * cScale, 0.5f * cScale,
            kg * yScale, -kg / (2f * (1f - kb)) * cScale, -kg / (2f * (1f - kr)) * cScale,
            kb * yScale, 0.5f * cScale, -kb / (2f * (1f - kr)) * cScale,
        )
    }
}

enum class UsbSourceRange(val label: String) {
    AUTO("自动（按输入格式）"),
    TV("TV / Limited range"),
    FULL("Full range");

    internal fun isFullRange(formatFullRange: Boolean): Boolean = when (this) {
        AUTO -> formatFullRange
        TV -> false
        FULL -> true
    }
}
