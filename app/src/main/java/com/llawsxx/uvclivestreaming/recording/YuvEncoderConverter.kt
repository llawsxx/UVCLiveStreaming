package com.llawsxx.uvclivestreaming.recording

import android.graphics.ImageFormat
import android.media.Image
import java.nio.ByteBuffer

internal class YuvEncoderConverter(private val config: RecordingConfig) {
    private data class Key(val bitDepth: Int, val fullRange: Boolean, val rgb: Boolean)
    private var key: Key? = null
    private var coefficients = IntArray(0)
    private val targetMatrix = when (config.colorStandard) {
        VideoColorStandard.BT601_NTSC, VideoColorStandard.BT601_PAL -> UsbYuvMatrix.BT601
        else -> UsbYuvMatrix.BT709
    }
    private val targetFullRange = config.colorRange == VideoColorRange.FULL

    fun write(frame: GpuVideoFrame, image: Image) {
        require(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3)
        require(image.width >= frame.width && image.height >= frame.height)
        require(image.cropRect.left == 0 && image.cropRect.top == 0)
        val source = checkNotNull(frame.directBuffer).duplicate().apply { position(0); limit(frame.byteSize) }.slice()
        val next = Key(if (frame.sampleBytes == 2) 10 else 8,
            config.usbSourceRange.isFullRange(frame.fullRange), frame.isRgb)
        if (key != next) {
            coefficients = YuvEncoderTransform.coefficients(config.usbYuvMatrix, next.fullRange,
                targetMatrix, targetFullRange, next.bitDepth, next.rgb)
            key = next
        }
        val planes = image.planes
        check(nativeWrite(source, frame.layout, frame.width, frame.height, frame.chromaWidth, frame.chromaHeight,
            coefficients, planes[0].buffer.slice(), planes[0].rowStride, planes[0].pixelStride,
            planes[1].buffer.slice(), planes[1].rowStride, planes[1].pixelStride,
            planes[2].buffer.slice(), planes[2].rowStride, planes[2].pixelStride)) {
            "YUV encoder plane conversion failed"
        }
    }

    companion object {
        init { System.loadLibrary("uvclivestreaming_usb") }

        external fun nativeWrite(source: ByteBuffer, layout: Int, width: Int, height: Int,
            chromaWidth: Int, chromaHeight: Int, coefficients: IntArray,
            y: ByteBuffer, yRowStride: Int, yPixelStride: Int,
            u: ByteBuffer, uRowStride: Int, uPixelStride: Int,
            v: ByteBuffer, vRowStride: Int, vPixelStride: Int): Boolean
    }
}
