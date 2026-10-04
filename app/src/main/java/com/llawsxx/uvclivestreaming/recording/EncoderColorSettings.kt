package com.llawsxx.uvclivestreaming.recording

import android.media.MediaFormat

// SDR ISO transfer values collapse to one MediaCodec value; offer that value once.
internal val encoderTransferOptions = listOf(
    VideoColorTransfer.DEFAULT, VideoColorTransfer.BT709, VideoColorTransfer.LINEAR,
    VideoColorTransfer.ST2084, VideoColorTransfer.HLG,
)

internal fun VideoColorTransfer.encoderLabel(): String =
    if (mediaFormatValue == MediaFormat.COLOR_TRANSFER_SDR_VIDEO) "SDR Video" else label

/** Standard selects both primaries and matrix; these are encoder requests, not SPS overrides. */
internal fun MediaFormat.applyEncoderColorSettings(config: RecordingConfig) {
    config.colorStandard.mediaFormatValue?.let { setInteger(MediaFormat.KEY_COLOR_STANDARD, it) }
    config.colorTransfer.mediaFormatValue?.let { setInteger(MediaFormat.KEY_COLOR_TRANSFER, it) }
    config.colorRange.mediaFormatValue?.let { setInteger(MediaFormat.KEY_COLOR_RANGE, it) }
}
