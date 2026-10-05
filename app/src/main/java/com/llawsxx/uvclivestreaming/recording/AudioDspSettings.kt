package com.llawsxx.uvclivestreaming.recording

import java.io.Serializable

data class AudioDspSettings(
    val enabled: Boolean = false,
    val loudnessEnabled: Boolean = true,
    val limiterEnabled: Boolean = true,
    val targetLufs: Float = -16f,
    val loudnessRangeLu: Float = 7f,
    val loudnessPeakDb: Float = -1f,
    val loudnessLookaheadMs: Float = 5f,
    val loudnessUpdateMs: Float = 1000f,
    val boostOnly: Boolean = false,
    val limiterInputDb: Float = 0f,
    val limiterThresholdDb: Float = -0.5f,
    val limiterCeilingDb: Float = -0.5f,
    val limiterReleaseMs: Float = 80f,
    val limiterLookaheadMs: Float = 1f,
    val adaptiveRelease: Boolean = false,
) : Serializable {
    val active get() = enabled && (loudnessEnabled || limiterEnabled)

    fun sanitized(): AudioDspSettings {
        fun Float.safe(default: Float, min: Float, max: Float) =
            if (isFinite()) coerceIn(min, max) else default
        return copy(
            targetLufs = targetLufs.safe(-16f, -70f, -5f),
            loudnessRangeLu = loudnessRangeLu.safe(7f, 1f, 50f),
            loudnessPeakDb = loudnessPeakDb.safe(-1f, -9f, 0f),
            loudnessLookaheadMs = loudnessLookaheadMs.safe(5f, 5f, 50f),
            loudnessUpdateMs = loudnessUpdateMs.safe(1000f, 100f, 3000f),
            limiterInputDb = limiterInputDb.safe(0f, -24f, 24f),
            limiterThresholdDb = limiterThresholdDb.safe(-0.5f, -30f, 0f),
            limiterCeilingDb = limiterCeilingDb.safe(-0.5f, -30f, 0f),
            limiterReleaseMs = limiterReleaseMs.safe(80f, 10f, 10000f),
            limiterLookaheadMs = limiterLookaheadMs.safe(1f, 0f, 50f),
        )
    }

    internal fun nativeParameters() = floatArrayOf(
        limiterInputDb, limiterThresholdDb, limiterReleaseMs, limiterCeilingDb,
        limiterLookaheadMs, if (adaptiveRelease) 1f else 0f,
        targetLufs, loudnessRangeLu, loudnessPeakDb, if (boostOnly) 1f else 0f,
        loudnessLookaheadMs, loudnessUpdateMs,
    )
}

internal interface PcmDsp : AutoCloseable {
    val delayFrames: Int
    /** Owns and modifies the packet before it is shared with any output. */
    fun process(bytes: ByteArray)
    /** Test processors may use this fallback; the native processor consumes full precision directly. */
    fun processWide(bytes: ByteArray, sampleBytes: Int): ByteArray =
        PcmSamples.toPcm16(bytes, sampleBytes).also(::process)
    fun updateSettings(settings: AudioDspSettings): Boolean = false
}

internal object NativeAudioDsp {
    init { System.loadLibrary("uvclivestreaming_usb") }
    external fun create(rate: Int, channels: Int, loudness: Boolean, limiter: Boolean, parameters: FloatArray): Long
    external fun delayFrames(handle: Long): Int
    external fun update(handle: Long, parameters: FloatArray)
    external fun process(handle: Long, bytes: ByteArray)
    external fun processWide(handle: Long, bytes: ByteArray, sampleBytes: Int): ByteArray
    external fun destroy(handle: Long)

    fun processor(rate: Int, channels: Int, settings: AudioDspSettings): PcmDsp {
        val handle = create(rate, channels, settings.loudnessEnabled, settings.limiterEnabled, settings.nativeParameters())
        check(handle != 0L) { "无法初始化音频 DSP（采样率 $rate Hz）" }
        return object : PcmDsp {
            private var current = settings
            override val delayFrames = delayFrames(handle)
            override fun process(bytes: ByteArray) = process(handle, bytes)
            override fun processWide(bytes: ByteArray, sampleBytes: Int) = processWide(handle, bytes, sampleBytes)
            override fun close() = destroy(handle)
            override fun updateSettings(settings: AudioDspSettings): Boolean {
                if (!settings.active || settings.loudnessEnabled != current.loudnessEnabled ||
                    settings.limiterEnabled != current.limiterEnabled ||
                    settings.loudnessLookaheadMs != current.loudnessLookaheadMs ||
                    settings.limiterLookaheadMs != current.limiterLookaheadMs) return false
                update(handle, settings.nativeParameters())
                current = settings
                return true
            }
        }
    }
}
