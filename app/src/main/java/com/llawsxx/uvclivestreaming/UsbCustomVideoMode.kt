package com.llawsxx.uvclivestreaming

import com.llawsxx.uvclivestreaming.recording.UsbVideoInputFormat
import java.io.Serializable

internal data class UsbCustomVideoMode(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Double = 60.0,
    val format: UsbVideoInputFormat = UsbVideoInputFormat.MJPG,
) : Serializable {
    val valid: Boolean get() = width in 1..3840 && height in 1..2160 &&
        fps.isFinite() && fps in 1.0..240.0 && format in formats &&
        (format !in evenDimensionFormats || (width % 2 == 0 && height % 2 == 0))

    companion object {
        // The current render pipeline decodes MJPEG and raw pixels, not UVC H.264.
        val formats = UsbVideoInputFormat.entries.filter { it != UsbVideoInputFormat.AUTO && it != UsbVideoInputFormat.H264 }
        val evenDimensionFormats = setOf(UsbVideoInputFormat.YUYV, UsbVideoInputFormat.UYVY,
            UsbVideoInputFormat.NV12, UsbVideoInputFormat.P010)
    }
}
