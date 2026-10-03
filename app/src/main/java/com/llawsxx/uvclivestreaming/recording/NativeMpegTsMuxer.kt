package com.llawsxx.uvclivestreaming.recording

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.Closeable
import java.nio.ByteBuffer

internal class NativeMpegTsMuxer(
    videoCodec: VideoCodec?,
    hasAudio: Boolean,
    sampleRate: Int = 48_000,
    channels: Int = 2,
) : Closeable {
    private var handle = nativeCreate(
        when (videoCodec) {
            VideoCodec.H265 -> VIDEO_H265
            VideoCodec.H264 -> VIDEO_H264
            null -> VIDEO_NONE
        },
        hasAudio,
        sampleRate,
        channels,
        AAC_LC_OBJECT_TYPE,
    ).also { check(it != 0L) { "无法创建 native MPEG-TS muxer" } }

    fun setVideoFormat(format: MediaFormat) {
        check(nativeSetVideoConfig(handle, format.csd("csd-0"), format.csd("csd-1"), format.csd("csd-2"))) {
            "无法解析视频编码参数"
        }
    }

    fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo): ByteArray =
        checkNotNull(nativeWriteVideo(handle, buffer.sampleBytes(info), info.presentationTimeUs,
            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)) {
            "native MPEG-TS 视频封装失败"
        }

    fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo): ByteArray =
        checkNotNull(nativeWriteAudio(handle, buffer.sampleBytes(info), info.presentationTimeUs)) {
            "native MPEG-TS 音频封装失败"
        }

    override fun close() {
        val active = handle
        handle = 0L
        if (active != 0L) nativeDestroy(active)
    }

    private fun MediaFormat.csd(key: String): ByteArray? = getByteBuffer(key)?.let { source ->
        ByteArray(source.remaining()).also { source.duplicate().get(it) }
    }

    private fun ByteBuffer.sampleBytes(info: MediaCodec.BufferInfo): ByteArray =
        ByteArray(info.size).also { bytes ->
            duplicate().apply {
                position(info.offset)
                limit(info.offset + info.size)
            }.get(bytes)
        }

    private external fun nativeCreate(
        videoCodec: Int,
        hasAudio: Boolean,
        sampleRate: Int,
        channels: Int,
        aacObjectType: Int,
    ): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetVideoConfig(handle: Long, csd0: ByteArray?, csd1: ByteArray?, csd2: ByteArray?): Boolean
    private external fun nativeWriteVideo(handle: Long, sample: ByteArray, ptsUs: Long, keyFrame: Boolean): ByteArray?
    private external fun nativeWriteAudio(handle: Long, sample: ByteArray, ptsUs: Long): ByteArray?

    private companion object {
        const val VIDEO_H264 = 1
        const val VIDEO_H265 = 2
        const val VIDEO_NONE = 0
        const val AAC_LC_OBJECT_TYPE = 2

        init {
            System.loadLibrary("uvclivestreaming_mpegts")
        }
    }
}
