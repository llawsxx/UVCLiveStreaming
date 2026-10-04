package com.llawsxx.uvclivestreaming.recording

import java.io.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

enum class GradeTransfer(val label: String, val referenceWhite: Double = 1.0) : Serializable {
    BT709("BT.709"), SRGB("sRGB"), GAMMA22("Gamma 2.2"), GAMMA24("Gamma 2.4"),
    LINEAR("Linear"), PQ("PQ / ST 2084", 203.0 / 10000.0), HLG("HLG（场景线性）", 0.26496256);

    internal fun decode(value: Double): Double {
        val x = value.coerceIn(0.0, 1.0)
        return when (this) {
            BT709 -> if (x < 0.081) x / 4.5 else ((x + 0.099) / 1.099).pow(1.0 / 0.45)
            SRGB -> if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
            GAMMA22 -> x.pow(2.2)
            GAMMA24 -> x.pow(2.4)
            LINEAR -> x
            PQ -> {
                val p = x.pow(32.0 / 2523.0)
                (max(p - 3424.0 / 4096.0, 0.0) / (2413.0 / 128.0 - 2392.0 / 128.0 * p)).pow(16384.0 / 2610.0)
            }
            HLG -> if (x <= 0.5) x * x / 3.0 else (exp((x - 0.55991073) / 0.17883277) + 0.28466892) / 12.0
        }
    }

    internal fun encode(value: Double): Double {
        val x = value.coerceIn(0.0, 1.0)
        return when (this) {
            BT709 -> if (x < 0.018) 4.5 * x else 1.099 * x.pow(0.45) - 0.099
            SRGB -> if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1.0 / 2.4) - 0.055
            GAMMA22 -> x.pow(1.0 / 2.2)
            GAMMA24 -> x.pow(1.0 / 2.4)
            LINEAR -> x
            PQ -> {
                val p = x.pow(2610.0 / 16384.0)
                ((3424.0 / 4096.0 + 2413.0 / 128.0 * p) / (1.0 + 2392.0 / 128.0 * p)).pow(2523.0 / 32.0)
            }
            HLG -> if (x <= 1.0 / 12.0) sqrt(3.0 * x) else 0.17883277 * ln(12.0 * x - 0.28466892) + 0.55991073
        }.coerceIn(0.0, 1.0)
    }
}

enum class GradePrimaries(val label: String, internal val rgbToXyz: DoubleArray) : Serializable {
    BT709("BT.709 / sRGB", doubleArrayOf(.4123908, .3575843, .1804808, .2126390, .7151687, .0721923, .0193308, .1191948, .9505322)),
    BT2020("BT.2020", doubleArrayOf(.6369580, .1446169, .1688810, .2627002, .6779981, .0593017, .0, .0280727, 1.0609851)),
    SMPTE170M("SMPTE 170M / BT.601", doubleArrayOf(.3935891, .3652497, .1916313, .2124132, .7010437, .0865432, .0187423, .1119313, .9581563)),
}

data class VideoColorGradeSettings(
    val enabled: Boolean = false,
    val exposureEv: Float = 0f,
    val contrast: Float = 1f,
    val temperatureKelvin: Int = 6500,
    val tint: Float = 0f,
    val saturation: Float = 1f,
    val transfer: GradeTransfer = GradeTransfer.BT709,
    val primaries: GradePrimaries = GradePrimaries.BT709,
    val lutSize: Int = 33,
) : Serializable {
    val active get() = enabled && (exposureEv != 0f || contrast != 1f ||
        temperatureKelvin != 6500 || tint != 0f || saturation != 1f)

    fun sanitized(): VideoColorGradeSettings {
        fun Float.safe(default: Float, min: Float, max: Float) = if (isFinite()) coerceIn(min, max) else default
        return copy(exposureEv = exposureEv.safe(0f, -4f, 4f),
            contrast = contrast.safe(1f, 0f, 2f), temperatureKelvin = temperatureKelvin.coerceIn(2000, 12000),
            tint = tint.safe(0f, -100f, 100f), saturation = saturation.safe(1f, 0f, 2f),
            lutSize = if (lutSize == 65) 65 else 33)
    }
}

/** Complete nonlinear-RGB -> nonlinear-RGB mapping, evaluated only while baking. */
internal class VideoColorTransform(settings: VideoColorGradeSettings) {
    private val s = settings.sanitized()
    private val exposure = 2.0.pow(s.exposureEv.toDouble())
    private val whiteBalance = whiteBalanceMatrix(s)
    private val luma = s.primaries.rgbToXyz.copyOfRange(3, 6)
    private val pivot = .18 * s.transfer.referenceWhite

    fun apply(r: Double, g: Double, b: Double, out: FloatArray, offset: Int = 0) {
        val x = s.transfer.decode(r) * exposure
        val y = s.transfer.decode(g) * exposure
        val z = s.transfer.decode(b) * exposure
        val lr = (whiteBalance[0] * x + whiteBalance[1] * y + whiteBalance[2] * z - pivot) * s.contrast + pivot
        val lg = (whiteBalance[3] * x + whiteBalance[4] * y + whiteBalance[5] * z - pivot) * s.contrast + pivot
        val lb = (whiteBalance[6] * x + whiteBalance[7] * y + whiteBalance[8] * z - pivot) * s.contrast + pivot
        val luminance = lr * luma[0] + lg * luma[1] + lb * luma[2]
        out[offset] = s.transfer.encode(luminance + (lr - luminance) * s.saturation).toFloat()
        out[offset + 1] = s.transfer.encode(luminance + (lg - luminance) * s.saturation).toFloat()
        out[offset + 2] = s.transfer.encode(luminance + (lb - luminance) * s.saturation).toFloat()
    }

    private fun whiteBalanceMatrix(s: VideoColorGradeSettings): DoubleArray {
        if (s.temperatureKelvin == 6500 && s.tint == 0f) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        // Bradford adaptation from the selected illuminant to the 6500 K
        // reference. Higher Kelvin warms the correction; positive tint adds magenta.
        val bradford = doubleArrayOf(.8951, .2664, -.1614, -.7502, 1.7135, .0367, .0389, -.0685, 1.0296)
        val source = multiplyVector(bradford, whiteXyz(s.temperatureKelvin, s.tint.toDouble()))
        val target = multiplyVector(bradford, whiteXyz(6500, 0.0))
        val scale = DoubleArray(9)
        repeat(3) { scale[it * 4] = target[it] / source[it] }
        val adaptation = multiplyMatrix(inverse(bradford), multiplyMatrix(scale, bradford))
        return multiplyMatrix(inverse(s.primaries.rgbToXyz), multiplyMatrix(adaptation, s.primaries.rgbToXyz))
    }

    private fun whiteXyz(kelvin: Int, tint: Double): DoubleArray {
        val t = kelvin.toDouble()
        val x = if (t <= 4000) -.2661239e9 / t.pow(3) - .2343580e6 / t.pow(2) + .8776956e3 / t + .179910
            else -3.0258469e9 / t.pow(3) + 2.1070379e6 / t.pow(2) + .2226347e3 / t + .240390
        val y = (when {
            t <= 2222 -> -1.1063814 * x.pow(3) - 1.34811020 * x.pow(2) + 2.18555832 * x - .20219683
            t <= 4000 -> -.9549476 * x.pow(3) - 1.37418593 * x.pow(2) + 2.09137015 * x - .16748867
            else -> 3.0817580 * x.pow(3) - 5.87338670 * x.pow(2) + 3.75112997 * x - .37001483
        } + tint * .0004).coerceIn(.05, .8)
        return doubleArrayOf(x / y, 1.0, (1.0 - x - y) / y)
    }

    private fun multiplyVector(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { row ->
        a[row * 3] * b[0] + a[row * 3 + 1] * b[1] + a[row * 3 + 2] * b[2]
    }
    private fun multiplyMatrix(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val row = i / 3; val col = i % 3
        a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col]
    }
    private fun inverse(m: DoubleArray): DoubleArray {
        val cofactors = doubleArrayOf(m[4]*m[8]-m[5]*m[7], m[2]*m[7]-m[1]*m[8], m[1]*m[5]-m[2]*m[4],
            m[5]*m[6]-m[3]*m[8], m[0]*m[8]-m[2]*m[6], m[2]*m[3]-m[0]*m[5],
            m[3]*m[7]-m[4]*m[6], m[1]*m[6]-m[0]*m[7], m[0]*m[4]-m[1]*m[3])
        val determinant = m[0]*cofactors[0] + m[1]*cofactors[3] + m[2]*cofactors[6]
        return cofactors.map { it / determinant }.toDoubleArray()
    }
}

internal class BakedVideoColorLut(
    val settings: VideoColorGradeSettings,
    val values: FloatArray,
    val halfPixels: ByteBuffer,
    val bytePixels: ByteBuffer,
) {
    val size = settings.lutSize
    val columns = ceil(sqrt(size.toDouble())).toInt()
    val width = columns * size
    val height = ((size + columns - 1) / columns) * size

    /** CPU reference for the same trilinear interpolation used by the shader. */
    fun sample(r: Float, g: Float, b: Float): FloatArray {
        val p = floatArrayOf(r, g, b).map { it.coerceIn(0f, 1f) * (size - 1) }
        val low = p.map { floor(it).toInt() }
        val high = low.map { min(it + 1, size - 1) }
        val f = p.mapIndexed { i, v -> v - low[i] }
        val out = FloatArray(3)
        for (z in 0..1) for (y in 0..1) for (x in 0..1) {
            val index = ((if (z == 0) low[2] else high[2]) * size * size +
                (if (y == 0) low[1] else high[1]) * size + (if (x == 0) low[0] else high[0])) * 3
            val weight = (if (x == 0) 1-f[0] else f[0]) * (if (y == 0) 1-f[1] else f[1]) * (if (z == 0) 1-f[2] else f[2])
            repeat(3) { out[it] += values[index + it] * weight }
        }
        return out
    }
}

internal object VideoColorLutBaker {
    fun bake(settings: VideoColorGradeSettings, cancelled: () -> Boolean = { false }): BakedVideoColorLut? {
        val s = settings.sanitized()
        val n = s.lutSize
        val columns = ceil(sqrt(n.toDouble())).toInt()
        val width = columns * n
        val height = ((n + columns - 1) / columns) * n
        val values = FloatArray(n * n * n * 3)
        val half = ByteBuffer.allocateDirect(width * height * 8).order(ByteOrder.nativeOrder())
        val bytes = ByteBuffer.allocateDirect(width * height * 4)
        val transform = VideoColorTransform(s)
        for (b in 0 until n) {
            if (cancelled()) return null
            for (g in 0 until n) for (r in 0 until n) {
                val index = (b * n * n + g * n + r) * 3
                transform.apply(r.toDouble() / (n-1), g.toDouble() / (n-1), b.toDouble() / (n-1), values, index)
                val pixel = ((b / columns * n + g) * width + b % columns * n + r) * 4
                repeat(3) { channel ->
                    val value = values[index + channel]
                    half.putShort((pixel + channel) * 2, positiveHalf(value).toShort())
                    bytes.put(pixel + channel, (value * 255f).roundToInt().coerceIn(0, 255).toByte())
                }
                half.putShort((pixel + 3) * 2, 0x3c00.toShort())
                bytes.put(pixel + 3, 255.toByte())
            }
        }
        return if (cancelled()) null else BakedVideoColorLut(s, values, half.asReadOnlyBuffer(), bytes.asReadOnlyBuffer())
    }

    // Values are finite, nonnegative and <=1. Round to nearest, ties to even.
    private fun positiveHalf(value: Float): Int {
        if (value < 0.00006103515625f) return Math.rint(value.toDouble() * 16777216.0).toInt()
        val bits = value.toRawBits()
        return ((bits + 0xfff + ((bits ushr 13) and 1)) ushr 13) - 0x1c000
    }
}
