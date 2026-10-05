package com.llawsxx.uvclivestreaming.recording

import android.media.AudioDeviceInfo
import org.junit.Assert.*
import org.junit.Test

class SystemAudioInputTest {
    @Test fun delayedReadsUseCaptureTimeInsteadOfCallbackTime() {
        val clock = SystemAudioPcmClock(48_000)
        assertEquals(1_000_000_000L, clock.timestamp(480, 1_080_000_000, 480, 1_010_000_000))
        assertEquals(1_010_000_000L, clock.timestamp(480, 1_200_000_000, 960, 1_020_000_000))
    }

    @Test fun unavailableHardwareTimestampsStillAdvanceBySamplesDespiteReadJitter() {
        val clock = SystemAudioPcmClock(48_000)
        assertEquals(1_000_000_000L, clock.timestamp(480, 1_010_000_000))
        assertEquals(1_010_000_000L, clock.timestamp(960, 1_200_000_000))
        assertEquals(1_030_000_000L, clock.timestamp(240, 1_300_000_000))
    }

    @Test fun fallbackContinuesFromLastHardwareAnchor() {
        val clock = SystemAudioPcmClock(48_000)
        clock.timestamp(480, 1_010_000_000)
        assertEquals(1_015_000_000L, clock.timestamp(480, 1_080_000_000, 960, 1_025_000_000))
        assertEquals(1_025_000_000L, clock.timestamp(480, 1_200_000_000))
    }

    @Test fun roundingDoesNotAccumulateWithSmall44100HzPackets() {
        val clock = SystemAudioPcmClock(44_100)
        val start = clock.timestamp(7, 1_000_000_000)
        var frames = 7L
        repeat(10_000) {
            assertEquals(start + frames * 1_000_000_000L / 44_100, clock.timestamp(7, 9_000_000_000))
            frames += 7
        }
    }

    @Test fun reconnectCanChangeIdWithoutLosingTheChosenDevice() {
        val selected = SystemAudioDevice(12, AudioDeviceInfo.TYPE_BUILTIN_MIC, "bottom", "Phone")
        val reconnected = selected.copy(id = 23)
        assertEquals(reconnected, resolveSystemAudioDevice(selected, listOf(reconnected)))
    }

    @Test fun removedOrAmbiguousDevicesDoNotSilentlySelectAnotherMicrophone() {
        val selected = SystemAudioDevice(12, AudioDeviceInfo.TYPE_USB_DEVICE, "card=2", "Capture")
        assertNull(resolveSystemAudioDevice(selected, listOf(selected.copy(type = AudioDeviceInfo.TYPE_BUILTIN_MIC))))
        assertNull(resolveSystemAudioDevice(selected, listOf(selected.copy(name = "Different USB device"))))
        assertNull(resolveSystemAudioDevice(selected, listOf(selected.copy(id = 22), selected.copy(id = 23))))
        assertNull(resolveSystemAudioDevice(selected, emptyList()))
    }
}
