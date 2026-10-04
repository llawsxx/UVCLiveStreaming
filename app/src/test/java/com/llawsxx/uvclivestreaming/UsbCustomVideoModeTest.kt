package com.llawsxx.uvclivestreaming

import com.llawsxx.uvclivestreaming.recording.UsbVideoInputFormat
import org.junit.Assert.*
import org.junit.Test

class UsbCustomVideoModeTest {
    @Test fun preservesFractionalFpsAndRejectsInvalidCaptureParameters() {
        val mode = UsbCustomVideoMode(1920, 1080, 59.94, UsbVideoInputFormat.NV12)
        assertTrue(mode.valid)
        assertEquals(59.94, mode.fps, 0.0)
        assertFalse(mode.copy(width = 0).valid)
        assertFalse(mode.copy(width = 3841).valid)
        assertFalse(mode.copy(height = 2161).valid)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 240.001).forEach {
            assertFalse(mode.copy(fps = it).valid)
        }
        assertTrue(mode.copy(fps = 1.0).valid)
        assertTrue(mode.copy(fps = 240.0).valid)
    }

    @Test fun packedRawAlignmentAndUnsupportedBitstreamFormatsAreRejected() {
        UsbCustomVideoMode.evenDimensionFormats.forEach {
            assertFalse(UsbCustomVideoMode(721, 480, 60.0, it).valid)
            assertFalse(UsbCustomVideoMode(720, 481, 60.0, it).valid)
        }
        assertTrue(UsbCustomVideoMode(721, 481, 60.0, UsbVideoInputFormat.MJPG).valid)
        assertTrue(UsbCustomVideoMode(721, 481, 60.0, UsbVideoInputFormat.I420).valid)
        assertFalse(UsbCustomVideoMode(format = UsbVideoInputFormat.AUTO).valid)
        assertFalse(UsbCustomVideoMode(format = UsbVideoInputFormat.H264).valid)
    }
}
