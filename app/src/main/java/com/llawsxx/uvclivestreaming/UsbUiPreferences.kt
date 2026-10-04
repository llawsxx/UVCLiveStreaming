package com.llawsxx.uvclivestreaming

import android.content.Context
import com.llawsxx.uvclivestreaming.recording.ConfigPreferences
import com.llawsxx.uvclivestreaming.recording.ContainerFormat
import com.llawsxx.uvclivestreaming.recording.VideoBitrateMode
import com.llawsxx.uvclivestreaming.recording.VideoCodec
import com.llawsxx.uvclivestreaming.recording.UsbYuvMatrix
import com.llawsxx.uvclivestreaming.recording.UsbSourceRange
import com.llawsxx.uvclivestreaming.recording.VideoColorRange
import com.llawsxx.uvclivestreaming.recording.VideoColorStandard
import com.llawsxx.uvclivestreaming.recording.VideoColorMatrix
import com.llawsxx.uvclivestreaming.recording.VideoColorTransfer

internal data class UsbUiSettings(
    val selectedDeviceName: String? = null,
    val selectedModeDisplay: String? = null,
    val includeAudio: Boolean = true,
    val previewEnabled: Boolean = true,
    val lowFrameRatePreview: Boolean = false,
    val container: ContainerFormat = ContainerFormat.MP4,
    val rtmpUrl: String = "",
    val videoBitrateKbps: String = "12000",
    val audioBitrateKbps: String = "192",
    val gopSeconds: String = "2",
    val bFrames: String = "0",
    val videoCodec: VideoCodec = VideoCodec.H264,
    val bitrateMode: VideoBitrateMode = VideoBitrateMode.DEFAULT,
    val audioRate: Int = 0,
    val bufferFrames: Int = 2,
    val yuvMatrix: UsbYuvMatrix = UsbYuvMatrix.BT601,
    val sourceRange: UsbSourceRange = UsbSourceRange.AUTO,
    val encoderColorStandard: VideoColorStandard = VideoColorStandard.DEFAULT,
    val encoderColorTransfer: VideoColorTransfer = VideoColorTransfer.DEFAULT,
    val encoderColorRange: VideoColorRange = VideoColorRange.DEFAULT,
    val forceSpsVui: Boolean = false,
    val rewriteColorRange: VideoColorRange = VideoColorRange.LIMITED,
    val rewriteColorStandard: VideoColorStandard = VideoColorStandard.BT709,
    val rewriteColorMatrix: VideoColorMatrix = VideoColorMatrix.BT709,
    val rewriteColorTransfer: VideoColorTransfer = VideoColorTransfer.BT709,
    val timestampSmoothingEnabled: Boolean = true,
    val timestampSmoothingNtscEnabled: Boolean = false,
    val timestampSmoothingMaxDeltaSeconds: String = "0.1",
    val keepScreenOn: Boolean = true,
    val scrollOffset: Int = 0,
)

internal object UsbUiPreferences {
    private const val NAME = "usb_ui_preferences"

    fun load(context: Context): UsbUiSettings {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return UsbUiSettings(
            selectedDeviceName = p.getString("selectedDeviceName", null),
            selectedModeDisplay = p.getString("selectedModeDisplay", null),
            includeAudio = p.getBoolean("includeAudio", true),
            previewEnabled = p.getBoolean("previewEnabled", true),
            lowFrameRatePreview = p.getBoolean("lowFrameRatePreview", false),
            container = enumValue(p.getString("container", null), ContainerFormat.MP4),
            rtmpUrl = p.getString("rtmpUrl", "").orEmpty(),
            videoBitrateKbps = p.getString("videoBitrateKbps", null)
                ?: ((p.getString("videoBitrate", "12000000")?.toLongOrNull() ?: 12_000_000L) / 1_000L).toString(),
            audioBitrateKbps = p.getString("audioBitrateKbps", null)
                ?: (ConfigPreferences.load(context).audioBitrate / 1_000).coerceIn(16, 512).toString(),
            gopSeconds = p.getString("gopSeconds", "2").orEmpty(),
            bFrames = p.getString("bFrames", "0").orEmpty(),
            videoCodec = enumValue(p.getString("videoCodec", null), VideoCodec.H264),
            bitrateMode = enumValue(p.getString("bitrateMode", null), VideoBitrateMode.DEFAULT),
            audioRate = p.getInt("audioRate", 0),
            bufferFrames = p.getInt("bufferFrames", 2).coerceIn(1, 30),
            yuvMatrix = enumValue(p.getString("yuvMatrix", null), UsbYuvMatrix.BT601),
            sourceRange = enumValue(p.getString("sourceRange", null), UsbSourceRange.AUTO),
            encoderColorStandard = enumValue(p.getString("encoderColorStandard", null), VideoColorStandard.DEFAULT),
            encoderColorTransfer = enumValue(p.getString("encoderColorTransfer", null), VideoColorTransfer.DEFAULT),
            encoderColorRange = enumValue(p.getString("encoderColorRange", null), VideoColorRange.DEFAULT),
            forceSpsVui = p.getBoolean("forceSpsVui", false),
            rewriteColorRange = enumValue(p.getString("rewriteColorRange", null), VideoColorRange.LIMITED),
            rewriteColorStandard = enumValue(p.getString("rewriteColorStandard", null), VideoColorStandard.BT709),
            rewriteColorMatrix = enumValue(p.getString("rewriteColorMatrix", null), VideoColorMatrix.BT709),
            rewriteColorTransfer = enumValue(p.getString("rewriteColorTransfer", null), VideoColorTransfer.BT709),
            timestampSmoothingEnabled = p.getBoolean("timestampSmoothingEnabled", true),
            timestampSmoothingNtscEnabled = p.getBoolean("timestampSmoothingNtscEnabled", false),
            timestampSmoothingMaxDeltaSeconds = p.getString("timestampSmoothingMaxDeltaSeconds", "0.1").orEmpty(),
            keepScreenOn = p.getBoolean("keepScreenOn", true),
            scrollOffset = p.getInt("scrollOffset", 0).coerceAtLeast(0),
        )
    }

    fun save(context: Context, settings: UsbUiSettings) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("selectedDeviceName", settings.selectedDeviceName)
            .putString("selectedModeDisplay", settings.selectedModeDisplay)
            .putBoolean("includeAudio", settings.includeAudio)
            .putBoolean("previewEnabled", settings.previewEnabled)
            .putBoolean("lowFrameRatePreview", settings.lowFrameRatePreview)
            .putString("container", settings.container.name)
            .putString("rtmpUrl", settings.rtmpUrl)
            .putString("videoBitrateKbps", settings.videoBitrateKbps)
            .putString("audioBitrateKbps", settings.audioBitrateKbps)
            .remove("videoBitrate")
            .putString("gopSeconds", settings.gopSeconds)
            .putString("bFrames", settings.bFrames)
            .putString("videoCodec", settings.videoCodec.name)
            .putString("bitrateMode", settings.bitrateMode.name)
            .putInt("audioRate", settings.audioRate)
            .putInt("bufferFrames", settings.bufferFrames.coerceIn(1, 30))
            .putString("yuvMatrix", settings.yuvMatrix.name)
            .putString("sourceRange", settings.sourceRange.name)
            .putString("encoderColorStandard", settings.encoderColorStandard.name)
            .putString("encoderColorTransfer", settings.encoderColorTransfer.name)
            .putString("encoderColorRange", settings.encoderColorRange.name)
            .putBoolean("forceSpsVui", settings.forceSpsVui)
            .putString("rewriteColorRange", settings.rewriteColorRange.name)
            .putString("rewriteColorStandard", settings.rewriteColorStandard.name)
            .putString("rewriteColorMatrix", settings.rewriteColorMatrix.name)
            .putString("rewriteColorTransfer", settings.rewriteColorTransfer.name)
            .putBoolean("timestampSmoothingEnabled", settings.timestampSmoothingEnabled)
            .putBoolean("timestampSmoothingNtscEnabled", settings.timestampSmoothingNtscEnabled)
            .putString("timestampSmoothingMaxDeltaSeconds", settings.timestampSmoothingMaxDeltaSeconds)
            .putBoolean("keepScreenOn", settings.keepScreenOn)
            .putInt("scrollOffset", settings.scrollOffset.coerceAtLeast(0))
            .apply()
    }

    fun saveScrollOffset(context: Context, offset: Int) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putInt("scrollOffset", offset.coerceAtLeast(0))
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
}
