package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.abs
import kotlin.math.pow

class VideoColorLutTest {
    private fun transform(settings: VideoColorGradeSettings, r: Double, g: Double = r, b: Double = r) =
        FloatArray(3).also { VideoColorTransform(settings).apply(r, g, b, it) }

    @Test fun transferPairsRoundTripAndMatchKnownLinearValues() {
        for (transfer in GradeTransfer.entries) for (code in listOf(0.0, .01, .05, .2, .5, .75, 1.0)) {
            assertEquals("$transfer $code", code, transfer.encode(transfer.decode(code)), .0003)
        }
        assertEquals(.21404114, GradeTransfer.SRGB.decode(.5), 1e-7)
        assertEquals(.1, GradeTransfer.PQ.decode(.7518271), 1e-7) // 1000 nits / 10000
        assertEquals(1.0 / 12, GradeTransfer.HLG.decode(.5), 1e-9)
    }

    @Test fun exposureMultipliesLinearLightIncludingPq() {
        val transfer = GradeTransfer.SRGB
        val code = transfer.encode(.1)
        val exposure = transform(VideoColorGradeSettings(exposureEv = 1f, transfer = transfer), code)
        assertEquals(transfer.encode(.2).toFloat(), exposure[0], 1e-6f)
        val pq = transform(VideoColorGradeSettings(exposureEv = 1f, transfer = GradeTransfer.PQ),
            GradeTransfer.PQ.encode(203.0 / 10000))
        assertEquals(GradeTransfer.PQ.encode(406.0 / 10000).toFloat(), pq[0], 1e-6f)
    }

    @Test fun contrastUsesLinearMiddleGrayAndZeroSaturationKeepsLinearLuminance() {
        val transfer = GradeTransfer.BT709
        val pivot = transfer.encode(.18)
        assertEquals(pivot.toFloat(), transform(VideoColorGradeSettings(contrast = 1.7f), pivot)[0], 1e-6f)
        val out = transform(VideoColorGradeSettings(saturation = 0f, transfer = GradeTransfer.LINEAR), .8, .3, .1)
        val expected = (.2126390 * .8 + .7151687 * .3 + .0721923 * .1).toFloat()
        out.forEach { assertEquals(expected, it, 1e-6f) }
    }

    @Test fun whiteBalanceIsNeutralAt6500AndHasWarmCoolAndTintDirections() {
        val neutral = transform(VideoColorGradeSettings(transfer = GradeTransfer.LINEAR), .3, .4, .5)
        assertArrayEquals(floatArrayOf(.3f, .4f, .5f), neutral, 1e-6f)
        val warm = transform(VideoColorGradeSettings(temperatureKelvin = 10000), .4)
        val cool = transform(VideoColorGradeSettings(temperatureKelvin = 3000), .4)
        assertTrue(warm[0] > warm[2]); assertTrue(cool[2] > cool[0])
        val magenta = transform(VideoColorGradeSettings(tint = 30f), .4)
        assertTrue((magenta[0] + magenta[2]) * .5f > magenta[1])
    }

    @Test fun lutInterpolationTracksFullTransformAndAtlasAddressesMatch() {
        val s = VideoColorGradeSettings(enabled = true, exposureEv = .4f,
            contrast = .85f, temperatureKelvin = 8000, tint = 8f, saturation = 1.2f, transfer = GradeTransfer.SRGB)
        val lut = checkNotNull(VideoColorLutBaker.bake(s))
        val fine = checkNotNull(VideoColorLutBaker.bake(s.copy(lutSize = 65)))
        val random = Random(170)
        var totalError = 0.0
        var totalFineError = 0.0
        var maxError = 0f
        var maxFineError = 0f
        repeat(300) {
            val r = random.nextFloat(); val g = random.nextFloat(); val b = random.nextFloat()
            val expected = transform(s, r.toDouble(), g.toDouble(), b.toDouble())
            val actual = lut.sample(r, g, b)
            val finer = fine.sample(r, g, b)
            repeat(3) { channel ->
                val error = abs(expected[channel] - actual[channel])
                val fineError = abs(expected[channel] - finer[channel])
                totalError += error; totalFineError += fineError
                maxError = maxOf(maxError, error); maxFineError = maxOf(maxFineError, fineError)
            }
        }
        println("LUT accuracy: 33 mean=${totalError/900} max=$maxError; 65 mean=${totalFineError/900} max=$maxFineError")
        // Hard gamut clipping makes the mapping nonsmooth near channel zero.
        // Bounds apply to this fixture, not arbitrary settings. Without a lift,
        // a 33^3 cell crossing the black clipping boundary reaches ~0.042 error.
        // Measure that approximation explicitly, including the finer table.
        assertTrue(totalError / 900 < .0015)
        assertTrue(maxError < .05f)
        assertTrue(maxFineError < .015f)
        assertTrue(totalFineError < totalError)
        assertTrue(maxFineError < maxError)
        val half = lut.halfPixels.duplicate().order(ByteOrder.nativeOrder())
        for (b in listOf(0, 5, 6, 32)) for (g in listOf(0, 32)) for (r in listOf(0, 32)) {
            val pixel = ((b / lut.columns * lut.size + g) * lut.width + b % lut.columns * lut.size + r) * 4
            val index = (b * lut.size * lut.size + g * lut.size + r) * 3
            repeat(3) { c ->
                val bits = half.getShort((pixel + c) * 2).toInt() and 0xffff
                val exponent = bits ushr 10
                val mantissa = bits and 1023
                val decoded = if (exponent == 0) mantissa * 2.0.pow(-24) else (1 + mantissa / 1024.0) * 2.0.pow(exponent - 15)
                assertEquals(lut.values[index + c].toDouble(), decoded, .00025)
            }
        }
    }

    @Test fun neutralSettingsBypassAndInvalidSettingsCannotProduceNonfiniteOutput() {
        assertFalse(VideoColorGradeSettings(enabled = true).active)
        assertFalse(VideoColorGradeSettings(exposureEv = 1f).active)
        val settings = VideoColorGradeSettings(enabled = true,
            exposureEv = Float.POSITIVE_INFINITY, contrast = -5f, temperatureKelvin = 0, tint = Float.NaN,
            saturation = 100f, lutSize = 1).sanitized()
        assertEquals(33, settings.lutSize)
        for (transfer in GradeTransfer.entries) for (primaries in GradePrimaries.entries) {
            transform(settings.copy(transfer = transfer, primaries = primaries), .01, .5, 1.0).forEach {
                assertTrue(it.isFinite() && it in 0f..1f)
            }
        }
        assertNull(VideoColorLutBaker.bake(VideoColorGradeSettings(), cancelled = { true }))
    }
}
