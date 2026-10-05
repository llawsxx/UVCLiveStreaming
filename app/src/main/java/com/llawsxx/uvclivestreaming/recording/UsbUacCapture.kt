package com.llawsxx.uvclivestreaming.recording

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager

/** Owns an independently authorized UAC connection; callbacks only enqueue into the PCM pipeline. */
internal class UsbUacCapture private constructor(
    val device: UsbAudioDevice,
    private val connection: UsbDeviceConnection,
    private var handle: Long,
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
    val sampleBytes: Int,
) : AutoCloseable {
    @Synchronized fun start(callback: UsbCaptureCallback) {
        check(handle != 0L) { "USB 音频设备已关闭" }
        NativeUsbCapture.nativeStart(handle, callback)
    }
    @Synchronized override fun close() {
        val old = handle
        if (old == 0L) return
        handle = 0L
        try { NativeUsbCapture.nativeClose(old) } finally { connection.close() }
    }

    companion object {
        fun open(context: Context, selected: UsbAudioDevice, rate: Int, bits: UsbAudioBitDepth): UsbUacCapture {
            val manager = context.getSystemService(UsbManager::class.java)
            val resolved = requireNotNull(resolveUsbAudioDevice(selected, usbAudioInputDevices(manager))) {
                "所选 USB 音频设备已断开或有多个同型号设备，请重新选择"
            }
            val device = requireNotNull(manager.deviceList[resolved.deviceName]) { "USB 音频设备已断开" }
            check(manager.hasPermission(device)) { "缺少 USB 音频设备访问权限" }
            val connection = requireNotNull(manager.openDevice(device)) { "无法打开 USB 音频设备" }
            var handle = 0L
            try {
                handle = NativeUsbCapture.nativeOpenAudio(connection.fileDescriptor, rate, bits.nativeValue)
                check(handle != 0L) { "无法初始化 USB 音频设备" }
                val format = NativeUsbCapture.nativeFormat(handle)
                check(format[2] > 0 && format[3] in 1..2) { "USB 音频设备没有可用 PCM 输入" }
                return UsbUacCapture(resolved, connection, handle, format[2], format[3], format[4], format[5])
            } catch (error: Throwable) {
                if (handle != 0L) runCatching { NativeUsbCapture.nativeClose(handle) }
                connection.close()
                throw error
            }
        }
    }
}
