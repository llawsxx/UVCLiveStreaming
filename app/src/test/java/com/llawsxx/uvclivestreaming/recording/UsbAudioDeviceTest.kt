package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test

class UsbAudioDeviceTest {
    private val mic = UsbAudioDevice("/dev/bus/usb/001/002", 0x1234, 0x5678, "Microphone")
    @Test fun reconnectUsesUniqueMatchingDeviceAndRejectsReusedUsbAddress() {
        val reconnected = mic.copy(deviceName = "/dev/bus/usb/001/005")
        assertEquals(reconnected, resolveUsbAudioDevice(mic, listOf(reconnected)))
        assertNull(resolveUsbAudioDevice(mic, listOf(mic.copy(productId = 0x9999))))
        assertNull(resolveUsbAudioDevice(mic, emptyList()))
    }
    @Test fun multipleIdenticalDevicesRequireAnExplicitAddress() {
        val other = mic.copy(deviceName = "/dev/bus/usb/001/003")
        assertEquals(mic, resolveUsbAudioDevice(mic, listOf(other, mic)))
        assertNull(resolveUsbAudioDevice(mic.copy(deviceName = "/gone"), listOf(mic, other)))
    }
}
