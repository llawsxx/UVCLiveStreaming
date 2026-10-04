package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbAudioPeakMeterTest {
    private fun pcm(vararg samples: Int) = samples.flatMap {
        listOf(it.toByte(), (it shr 8).toByte())
    }.toByteArray()

    @Test fun capturesShortTransientInEitherChannelIncludingNegativeFullScale() {
        val meter = UsbAudioPeakMeter(48000, 2)
        meter.offer(pcm(0, 0, 0, -32768, 0, 0), 0)
        assertEquals(0f, meter.levelDb(100_000_000), 0f)
    }

    @Test fun largestPeakExpiresAndRevealsSmallerRecentPeak() {
        val meter = UsbAudioPeakMeter(48000, 1)
        meter.offer(pcm(32767), 0)
        meter.offer(pcm(16384), 500_000_000)
        assertEquals(0f, meter.levelDb(900_000_000), 0.001f)
        assertEquals(-6.0206f, meter.levelDb(1_010_000_000), 0.001f)
        assertEquals(-60f, meter.levelDb(1_510_000_000), 0f)
    }

    @Test fun silenceCannotHideAPeakAndPeakExpiresWithoutMorePcm() {
        val meter = UsbAudioPeakMeter(48000, 1)
        meter.offer(pcm(16384), 0)
        meter.offer(pcm(0, 0), 500_000_000)
        assertEquals(-6.0206f, meter.levelDb(900_000_000), 0.001f)
        assertEquals(-60f, meter.levelDb(1_010_000_000), 0f)
    }

    @Test fun multiplePacketsInOneBucketKeepMaximumUntilBucketExpires() {
        val meter = UsbAudioPeakMeter(48000, 1)
        meter.offer(pcm(16384), 0)
        meter.offer(pcm(8192), 1_000_000)
        meter.offer(pcm(24576), 2_000_000)
        meter.offer(pcm(8192), 3_000_000)
        assertEquals(-2.4988f, meter.levelDb(900_000_000), 0.001f)
        assertEquals(-60f, meter.levelDb(1_010_000_000), 0f)
    }

    @Test fun longPacketPlacesPeaksInTheirSourceTimeBuckets() {
        val meter = UsbAudioPeakMeter(1000, 1)
        val samples = IntArray(1100)
        samples[0] = 32767
        samples[1000] = 8192
        meter.offer(pcm(*samples), 0)
        assertEquals(-12.0412f, meter.levelDb(1_100_000_000), 0.001f)
    }

    @Test fun backwardsSourceClockStartsFreshHistory() {
        val meter = UsbAudioPeakMeter(48000, 1)
        meter.offer(pcm(32767), 5_000_000_000)
        meter.offer(pcm(8192), 1_000_000_000)
        assertEquals(-12.0412f, meter.levelDb(1_100_000_000), 0.001f)
    }
}
