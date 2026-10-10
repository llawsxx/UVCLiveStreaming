package com.llawsxx.uvclivestreaming.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

internal val VideoCodec.encoderMime: String get() =
    if (this == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

internal fun encoderBitrateMode(config: RecordingConfig, supports: (Int) -> Boolean): Int? {
    config.videoBitrateMode.mediaFormatValue?.let { return it }
    if (!config.httpUploadEnabled || !config.httpAutoBitrateEnabled) return null
    return listOf(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR).firstOrNull(supports)
}

internal data class VideoEncoderCapabilities(
    val name: String,
    val complexityRange: IntRange,
    val profileLevels: List<Pair<Int, Int>>,
) {
    val profiles: List<Int> get() = profileLevels.map { it.first }.distinct().sorted()

    fun levels(codec: VideoCodec, profile: Int): List<Int> {
        val advertised = profileLevels.filter { it.first == profile }.map { it.second }
        val maximum = advertised.maxOrNull() ?: return emptyList()
        val constants = codec.levelConstants()
        val highTierSupported = advertised.any { constants[it]?.startsWith("HEVCHighTier") == true }
        return (constants.filter { (value, name) ->
            value <= maximum && (!name.startsWith("HEVCHighTier") || highTierSupported)
        }.keys + advertised).distinct().sorted()
    }

    fun requestedValues(config: RecordingConfig, automaticLevel: Int? = null): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        config.videoEncoderComplexity?.let {
            require(it in complexityRange) { "编码复杂度 $it 不在 $name 支持的范围 ${complexityRange.first}～${complexityRange.last} 内" }
            result[MediaFormat.KEY_COMPLEXITY] = it
        }
        val profile = config.videoEncoderProfile
        require(profile != null || config.videoEncoderLevel == null) { "设置编码 Level 前请先选择 Profile" }
        if (profile != null) {
            val maximum = profileLevels.filter { it.first == profile }.maxOfOrNull { it.second }
            require(maximum != null) { "$name 不支持 ${config.videoCodec.encoderProfileLabel(profile)} Profile" }
            val level = config.videoEncoderLevel ?: automaticLevel ?: maximum
            require(level in levels(config.videoCodec, profile)) { "$name 不支持所选 Profile／Level 组合" }
            result[MediaFormat.KEY_PROFILE] = profile
            // Android requires an explicit level with a video profile.
            result[MediaFormat.KEY_LEVEL] = level
        }
        return result
    }

    companion object {
        fun from(info: MediaCodecInfo, mime: String): VideoEncoderCapabilities {
            val caps = info.getCapabilitiesForType(mime)
            val range = checkNotNull(caps.encoderCapabilities).complexityRange
            return VideoEncoderCapabilities(info.name, range.lower..range.upper,
                caps.profileLevels.map { it.profile to it.level })
        }
    }
}

internal fun readVideoEncoderCapabilities(codec: VideoCodec): VideoEncoderCapabilities {
    val encoder = MediaCodec.createEncoderByType(codec.encoderMime)
    try { return VideoEncoderCapabilities.from(encoder.codecInfo, codec.encoderMime) }
    finally { encoder.release() }
}

internal fun MediaFormat.applyEncoderAdvancedSettings(config: RecordingConfig, caps: VideoEncoderCapabilities) {
    val profile = config.videoEncoderProfile
    val automaticLevel = if (profile != null && config.videoEncoderLevel == null) {
        // Find the lowest advertised level whose standard limits cover the actual output mode.
        // Unsupported/vendor profiles fall back to their advertised maximum.
        caps.levels(config.videoCodec, profile).firstOrNull { level ->
            runCatching {
                val video = MediaCodecInfo.CodecCapabilities.createFromProfileLevel(config.videoCodec.encoderMime,
                    profile, level)?.videoCapabilities ?: return@runCatching false
                video.areSizeAndRateSupported(getInteger(MediaFormat.KEY_WIDTH), getInteger(MediaFormat.KEY_HEIGHT),
                    getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()) &&
                    video.bitrateRange.contains(getInteger(MediaFormat.KEY_BIT_RATE))
            }.getOrDefault(false)
        }
    } else null
    caps.requestedValues(config, automaticLevel).forEach { (key, value) -> setInteger(key, value) }
}

private fun codecConstants(prefixes: List<String>): Map<Int, String> = MediaCodecInfo.CodecProfileLevel::class.java.fields
    .filter { field -> field.type == Int::class.javaPrimitiveType && prefixes.any { field.name.startsWith(it) } }
    .associate { it.getInt(null) to it.name }

private fun VideoCodec.levelConstants(): Map<Int, String> = codecConstants(
    if (this == VideoCodec.H264) listOf("AVCLevel") else listOf("HEVCMainTierLevel", "HEVCHighTierLevel"))

internal fun VideoCodec.encoderProfileLabel(value: Int): String {
    val prefix = if (this == VideoCodec.H264) "AVCProfile" else "HEVCProfile"
    return codecConstants(listOf(prefix))[value]?.removePrefix(prefix) ?: "Profile $value"
}

internal fun VideoCodec.encoderLevelLabel(value: Int): String {
    val name = levelConstants()[value] ?: return "Level $value"
    val high = name.startsWith("HEVCHighTier")
    val number = name.substringAfter("Level")
    val label = if (number.length == 2 && number[1].isDigit()) "${number[0]}.${number[1]}" else number
    return if (this == VideoCodec.H264) label else "${if (high) "High" else "Main"} Tier $label"
}
