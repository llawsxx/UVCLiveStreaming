package com.llawsxx.uvclivestreaming.recording

import android.media.MediaFormat

/** MediaCodec defines interval 0 as an independently encoded key frame for every frame. */
internal fun MediaFormat.applyEncoderGopSettings(config: RecordingConfig) {
    val interval = config.videoKeyFrameIntervalSeconds.takeIf { it.isFinite() }?.coerceIn(0f, 30f) ?: 2f
    setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, interval)
    if (interval == 0f || config.videoMaxBFrames > 0) {
        setInteger(MediaFormat.KEY_MAX_B_FRAMES, if (interval == 0f) 0 else config.videoMaxBFrames.coerceIn(0, 4))
    }
}
