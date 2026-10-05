package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test

class UsbWidePcmGpuTest {
    private fun decode(bytes: ByteArray) = bytes.toList().chunked(2).map { (lo,hi) ->
        ((lo.toInt() and 255) or (hi.toInt() shl 8)).toShort().toInt()
    }
    @Test fun nativeDspPreserves24And32BitInputBelowPcm16Resolution() {
        for ((width, lowSample) in listOf(3 to 64, 4 to 16384)) {
            val settings = AudioDspSettings(enabled = true, loudnessEnabled = false, limiterEnabled = true,
                limiterInputDb = 24f, limiterLookaheadMs = 0f)
            NativeAudioDsp.processor(48000, 1, settings).use { dsp ->
                val input = (0 until 100).flatMap { i ->
                    (0 until width).map { ((if (i % 2 == 0) lowSample else -lowSample) shr (8 * it)).toByte() }
                }.toByteArray()
                assertTrue("Signal should vanish if converted before DSP", PcmSamples.toPcm16(input, width).all { it == 0.toByte() })
                val result = decode(dsp.processWide(input, width))
                assertTrue("${width * 8}bit detail was truncated", result.drop(dsp.delayFrames).all { kotlin.math.abs(it) == 4 })
                assertEquals(100, result.size)
            }
        }
    }

    @Test fun nativeWideConversionHasCorrectFullScaleAndMatches16BitRoundTrip() {
        val parameters = AudioDspSettings().nativeParameters()
        val handle = NativeAudioDsp.create(48000, 2, false, false, parameters)
        assertTrue(handle != 0L)
        try {
            for (width in 2..4) {
                val reference = listOf(-32768, -16384, -1, 0, 1, 16384, 32767, 0)
                val input = reference.flatMap { value ->
                    val raw = value shl ((width - 2) * 8)
                    (0 until width).map { (raw shr (8 * it)).toByte() }
                }.toByteArray()
                val copy = input.copyOf()
                assertEquals(reference, decode(NativeAudioDsp.processWide(handle, input, width)))
                assertArrayEquals("JNI must not modify raw input", copy, input)
            }
        } finally { NativeAudioDsp.destroy(handle) }
    }

    @Test fun audioOnlyRejectsInvalidDescriptorWithoutOpeningVideo() {
        repeat(3) { assertThrows(RuntimeException::class.java) { NativeUsbCapture.nativeOpenAudio(-1, 48000, 24) } }
        NativeUsbCapture.nativeClose(0)
    }
}
