package com.llawsxx.uvclivestreaming.recording

import java.nio.ByteBuffer
import kotlin.math.log10
import kotlin.math.sqrt

/** RMS level of signed, little-endian PCM16 samples, in dBFS. */
internal fun usbPcmLevelDb(bytes: ByteArray): Float {
    val samples = bytes.size / 2
    if (samples == 0) return -60f
    var squares = 0.0
    for (index in 0 until samples) {
        val offset = index * 2
        val sample = ((bytes[offset + 1].toInt() shl 8) or
            (bytes[offset].toInt() and 0xff)).toShort().toInt()
        squares += sample.toDouble() * sample
    }
    val rms = sqrt(squares / samples) / 32768.0
    return (20.0 * log10(rms.coerceAtLeast(0.001))).toFloat().coerceIn(-60f, 0f)
}

internal interface UsbCaptureCallback {
    /** Raw UVC frame: 1 MJPG, 2 YUYV, 3 UYVY, 4 RGB. */
    fun onUsbVideoFrame(bytes: ByteArray, format: Int, width: Int, height: Int, timestampNs: Long)
    /** Signed 16-bit little-endian, interleaved PCM at the rate reported by nativeFormat. */
    fun onUsbAudioPcm(bytes: ByteArray, timestampNs: Long)
    /** Signed LE PCM in 2/3/4-byte subslots; valid bits are left-aligned per UAC. */
    fun onUsbAudioPcmRaw(bytes: ByteArray, timestampNs: Long, sampleBytes: Int) {
        onUsbAudioPcm(PcmSamples.toPcm16(bytes, sampleBytes), timestampNs)
    }
}

internal object NativeUsbCapture {
    init { System.loadLibrary("uvclivestreaming_usb") }

    external fun nativeOpen(
        fd: Int, width: Int, height: Int, fps: Double, videoFormat: Int, audio: Boolean, audioRate: Int,
        customVideoMode: Boolean = false, audioBitDepth: Int = 0, bulkTransferCount: Int = 64,
    ): Long
    /** Independent UAC-only handle; no UVC negotiation or video interface claim. */
    external fun nativeOpenAudio(fd: Int, audioRate: Int, audioBitDepth: Int = 0): Long
    /** Format label, width, height, fps, input format value, and interval description. */
    external fun nativeListVideoModes(fd: Int): Array<String>
    /** Decode MJPEG or repack raw YUV into I420; never performs YUV-to-RGB conversion. */
    external fun nativeDecodeToI420(bytes: ByteArray, format: Int, width: Int, height: Int): ByteArray?
    /** Writes MJPEG I420 directly into an exclusively leased direct buffer (offset zero). */
    external fun nativeDecodeMjpegToI420(bytes: ByteArray, width: Int, height: Int, destination: ByteBuffer): Boolean
    /** Writes original JPEG Y/U/V planes, without subsampling; verifies supplied chroma geometry. */
    external fun nativeDecodeMjpegToYuv(bytes: ByteArray, width: Int, height: Int, chromaWidth: Int, chromaHeight: Int, destination: ByteBuffer): Boolean
    fun decodeMjpegToGpuBuffer(bytes: ByteArray, width: Int, height: Int, destination: ByteBuffer): Boolean {
        val geometry = MjpegChromaGeometry.read(bytes, width, height) ?: ((width + 1) / 2 to (height + 1) / 2)
        return nativeDecodeMjpegToYuv(bytes, width, height, geometry.first, geometry.second, destination)
    }
    /** Repack raw YUV into planar samples, preserving 4:2:2 / 10-bit, or copy RGB/BGR. */
    external fun nativeConvertRawToGpuBuffer(bytes: ByteArray, format: Int, width: Int, height: Int, destination: ByteBuffer): Boolean
    /** [video width, video height, audio sample rate, channels, valid bits, subslot bytes]. */
    external fun nativeFormat(handle: Long): IntArray
    external fun nativeStart(handle: Long, callback: UsbCaptureCallback)
    /** Received video endpoint bytes including UVC headers; excludes audio and bus overhead. */
    external fun nativeReceivedVideoBytes(handle: Long): Long
    external fun nativeClose(handle: Long)
}
