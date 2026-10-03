package com.llawsxx.uvclivestreaming.recording

import android.media.MediaFormat
import android.os.Build
import java.nio.ByteBuffer

/** Rewrites encoder CSD and in-band SPS once, before the shared output router. */
internal class H26xVuiRewriter(private val config: RecordingConfig) {
    private var nalLengthSize = 4

    fun rewriteFormat(format: MediaFormat): MediaFormat {
        format.getByteBuffer("csd-0")?.let { csd ->
            val bytes = csd.bytes()
            nalLengthSize = when {
                config.videoCodec == VideoCodec.H264 && bytes.size >= 7 && bytes[0] == 1.toByte() ->
                    (bytes[4].toInt() and 3) + 1
                config.videoCodec == VideoCodec.H265 && bytes.size >= 23 && bytes[0] == 1.toByte() ->
                    (bytes[21].toInt() and 3) + 1
                else -> 4
            }
        }
        for (key in listOf("csd-0", "csd-1", "csd-2")) {
            format.getByteBuffer(key)?.let { source ->
                format.setByteBuffer(key, ByteBuffer.wrap(rewrite(source.bytes())))
            }
        }
        // Android's color-standard combines primaries/matrix, and SDR transfer
        // collapses several ISO values. Let the rewritten SPS describe color
        // rather than copy inaccurate encoder tags into the MP4 container.
        for (key in listOf(MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_TRANSFER)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) format.removeKey(key)
            else format.setInteger(key, 0) // Unspecified; removeKey requires Android 10.
        }
        return format
    }

    fun rewrite(input: ByteArray): ByteArray {
        if (input.isEmpty()) return input
        return checkNotNull(nativeRewrite(input,
            if (config.videoCodec == VideoCodec.H265) 2 else 1, nalLengthSize,
            config.rewriteColorRange.vuiFullRange, config.rewriteColorStandard.vuiPrimaries,
            config.rewriteColorTransfer.vuiValue, config.rewriteColorMatrix.vuiValue,
        )) { "${config.videoCodec.label} SPS/VUI 颜色元数据重写失败" }
    }

    private fun ByteBuffer.bytes(): ByteArray = ByteArray(remaining()).also { duplicate().get(it) }

    private external fun nativeRewrite(input: ByteArray, codec: Int, nalLengthSize: Int,
                                       fullRange: Int, colourPrimaries: Int,
                                       transferCharacteristics: Int, matrixCoefficients: Int): ByteArray?

    companion object {
        init { System.loadLibrary("uvclivestreaming_mpegts") }
    }
}
