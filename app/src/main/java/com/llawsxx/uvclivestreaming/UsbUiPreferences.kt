package com.llawsxx.uvclivestreaming

import android.content.Context
import com.llawsxx.uvclivestreaming.recording.AudioDspSettings
import com.llawsxx.uvclivestreaming.recording.VideoColorGradeSettings
import com.llawsxx.uvclivestreaming.recording.GradeTransfer
import com.llawsxx.uvclivestreaming.recording.GradePrimaries
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
import com.llawsxx.uvclivestreaming.recording.UsbAudioInput
import com.llawsxx.uvclivestreaming.recording.SystemAudioInputSettings
import com.llawsxx.uvclivestreaming.recording.SystemAudioInputPreferences
import com.llawsxx.uvclivestreaming.recording.TestCardSettings
import com.llawsxx.uvclivestreaming.recording.TestCardPreferences
import com.llawsxx.uvclivestreaming.recording.UsbAudioDevice
import com.llawsxx.uvclivestreaming.recording.UsbAudioBitDepth
import com.llawsxx.uvclivestreaming.recording.UsbAudioDevicePreferences

internal data class UsbUiSettings(
    val selectedDeviceName: String? = null,
    val testCard: TestCardSettings = TestCardSettings(),
    val selectedModeDisplay: String? = null,
    val customVideoMode: UsbCustomVideoMode = UsbCustomVideoMode(),
    val customVideoModeSelected: Boolean = false,
    val includeAudio: Boolean = true,
    val audioInput: UsbAudioInput = UsbAudioInput.USB,
    val usbAudioDevice: UsbAudioDevice? = null,
    val usbAudioBitDepth: UsbAudioBitDepth = UsbAudioBitDepth.AUTO,
    val systemAudioInput: SystemAudioInputSettings = SystemAudioInputSettings(),
    val audioPreviewEnabled: Boolean = false,
    val audioDsp: AudioDspSettings = AudioDspSettings(),
    val videoColorGrade: VideoColorGradeSettings = VideoColorGradeSettings(),
    val previewEnabled: Boolean = true,
    val lowFrameRatePreview: Boolean = false,
    val container: ContainerFormat = ContainerFormat.MP4,
    val confirmStopOutputs: Boolean = true,
    val rtmpUrl: String = "",
    val rtmpBufferMs: Int = 5_000,
    val rtmpSendTimeoutSeconds: Int = 10,
    val videoBitrateKbps: String = "12000",
    val audioBitrateKbps: String = "192",
    val audioDelayMs: String = "0",
    val muxingQueueSize: String = "64",
    val gopSeconds: String = "2",
    val bFrames: String = "0",
    val videoCodec: VideoCodec = VideoCodec.H264,
    val bitrateMode: VideoBitrateMode = VideoBitrateMode.DEFAULT,
    val audioRate: Int = 0,
    val bufferFrames: Int = 2,
    val receiveTransferCount: Int = 64,
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
)

internal object UsbUiPreferences {
    private const val NAME = "usb_ui_preferences"

    fun load(context: Context): UsbUiSettings {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return UsbUiSettings(
            selectedDeviceName = p.getString("selectedDeviceName", null),
            testCard = TestCardPreferences.load(p),
            selectedModeDisplay = p.getString("selectedModeDisplay", null),
            customVideoMode = UsbCustomVideoMode(
                p.getInt("customVideoWidth", 1280), p.getInt("customVideoHeight", 720),
                p.getString("customVideoFps", "60")?.toDoubleOrNull() ?: 60.0,
                enumValue(p.getString("customVideoFormat", null), com.llawsxx.uvclivestreaming.recording.UsbVideoInputFormat.MJPG),
            ).takeIf { it.valid } ?: UsbCustomVideoMode(),
            customVideoModeSelected = p.getBoolean("customVideoModeSelected", false),
            includeAudio = p.getBoolean("includeAudio", true),
            audioInput = enumValue(p.getString("audioInput", null), UsbAudioInput.USB),
            usbAudioDevice = UsbAudioDevicePreferences.load(p),
            usbAudioBitDepth = UsbAudioDevicePreferences.bitDepth(p),
            systemAudioInput = SystemAudioInputPreferences.load(p),
            audioPreviewEnabled = p.getBoolean("audioPreviewEnabled", false),
            videoColorGrade = VideoColorGradeSettings(
                enabled = p.getBoolean("videoColorGradeEnabled", false),
                exposureEv = p.getFloat("videoGradeExposureEv", 0f),
                contrast = p.getFloat("videoGradeContrast", 1f),
                temperatureKelvin = p.getInt("videoGradeTemperature", 6500),
                tint = p.getFloat("videoGradeTint", 0f),
                saturation = p.getFloat("videoGradeSaturation", 1f),
                transfer = enumValue(p.getString("videoGradeTransfer", null), GradeTransfer.BT709),
                primaries = enumValue(p.getString("videoGradePrimaries", null), GradePrimaries.BT709),
                lutSize = p.getInt("videoGradeLutSize", 33),
            ).sanitized(),
            audioDsp = AudioDspSettings(
                enabled = p.getBoolean("audioDspEnabled", false),
                loudnessEnabled = p.getBoolean("audioLoudnessEnabled", true),
                limiterEnabled = p.getBoolean("audioLimiterEnabled", true),
                targetLufs = p.getFloat("audioTargetLufs", -16f),
                loudnessRangeLu = p.getFloat("audioLoudnessRangeLu", 7f),
                loudnessPeakDb = p.getFloat("audioLoudnessPeakDb", -1f),
                loudnessLookaheadMs = p.getFloat("audioLoudnessLookaheadMs", 5f),
                loudnessUpdateMs = p.getFloat("audioLoudnessUpdateMs", 1000f),
                boostOnly = p.getBoolean("audioBoostOnly", false),
                limiterInputDb = p.getFloat("audioLimiterInputDb", 0f),
                limiterThresholdDb = p.getFloat("audioLimiterThresholdDb", -0.5f),
                limiterCeilingDb = p.getFloat("audioLimiterCeilingDb", -0.5f),
                limiterReleaseMs = p.getFloat("audioLimiterReleaseMs", 80f),
                limiterLookaheadMs = p.getFloat("audioLimiterLookaheadMs", 1f),
                adaptiveRelease = p.getBoolean("audioAdaptiveRelease", false),
            ).sanitized(),
            previewEnabled = p.getBoolean("previewEnabled", true),
            lowFrameRatePreview = p.getBoolean("lowFrameRatePreview", false),
            container = enumValue(p.getString("container", null), ContainerFormat.MP4),
            confirmStopOutputs = p.getBoolean("confirmStopOutputs", true),
            rtmpUrl = p.getString("rtmpUrl", "").orEmpty(),
            rtmpBufferMs = p.getInt("rtmpBufferMs", 5_000).coerceIn(100, 30_000),
            rtmpSendTimeoutSeconds = p.getInt("rtmpSendTimeoutSeconds", 10).coerceIn(3, 30),
            videoBitrateKbps = p.getString("videoBitrateKbps", null)
                ?: ((p.getString("videoBitrate", "12000000")?.toLongOrNull() ?: 12_000_000L) / 1_000L).toString(),
            audioBitrateKbps = p.getString("audioBitrateKbps", null)
                ?: (ConfigPreferences.load(context).audioBitrate / 1_000).coerceIn(16, 512).toString(),
            gopSeconds = p.getString("gopSeconds", "2").orEmpty(),
            audioDelayMs = p.getString("audioDelayMs", "0").orEmpty(),
            muxingQueueSize = p.getString("muxingQueueSize", "64").orEmpty(),
            bFrames = p.getString("bFrames", "0").orEmpty(),
            videoCodec = enumValue(p.getString("videoCodec", null), VideoCodec.H264),
            bitrateMode = enumValue(p.getString("bitrateMode", null), VideoBitrateMode.DEFAULT),
            audioRate = p.getInt("audioRate", 0),
            bufferFrames = p.getInt("bufferFrames", 2).coerceIn(1, 30),
            receiveTransferCount = p.getInt("receiveTransferCount",
                p.getInt("bulkTransferCount", 64)).coerceIn(8, 512),
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
        )
    }

    fun save(context: Context, settings: UsbUiSettings) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("selectedDeviceName", settings.selectedDeviceName)
            .also { TestCardPreferences.save(it, settings.testCard) }
            .putString("selectedModeDisplay", settings.selectedModeDisplay)
            .putInt("customVideoWidth", settings.customVideoMode.width)
            .putInt("customVideoHeight", settings.customVideoMode.height)
            .putString("customVideoFps", settings.customVideoMode.fps.toString())
            .putString("customVideoFormat", settings.customVideoMode.format.name)
            .putBoolean("customVideoModeSelected", settings.customVideoModeSelected)
            .putBoolean("includeAudio", settings.includeAudio)
            .putString("audioInput", settings.audioInput.name)
            .also { UsbAudioDevicePreferences.save(it, settings.usbAudioDevice) }
            .putString("uacBitDepth", settings.usbAudioBitDepth.name)
            .also { SystemAudioInputPreferences.save(it, settings.systemAudioInput) }
            .putBoolean("audioPreviewEnabled", settings.audioPreviewEnabled)
            .putBoolean("videoColorGradeEnabled", settings.videoColorGrade.enabled)
            .remove("videoGradeBrightness")
            .putFloat("videoGradeExposureEv", settings.videoColorGrade.exposureEv)
            .putFloat("videoGradeContrast", settings.videoColorGrade.contrast)
            .putInt("videoGradeTemperature", settings.videoColorGrade.temperatureKelvin)
            .putFloat("videoGradeTint", settings.videoColorGrade.tint)
            .putFloat("videoGradeSaturation", settings.videoColorGrade.saturation)
            .putString("videoGradeTransfer", settings.videoColorGrade.transfer.name)
            .putString("videoGradePrimaries", settings.videoColorGrade.primaries.name)
            .putInt("videoGradeLutSize", settings.videoColorGrade.lutSize)
            .putBoolean("audioDspEnabled", settings.audioDsp.enabled)
            .putBoolean("audioLoudnessEnabled", settings.audioDsp.loudnessEnabled)
            .putBoolean("audioLimiterEnabled", settings.audioDsp.limiterEnabled)
            .putFloat("audioTargetLufs", settings.audioDsp.targetLufs)
            .putFloat("audioLoudnessRangeLu", settings.audioDsp.loudnessRangeLu)
            .putFloat("audioLoudnessPeakDb", settings.audioDsp.loudnessPeakDb)
            .putFloat("audioLoudnessLookaheadMs", settings.audioDsp.loudnessLookaheadMs)
            .putFloat("audioLoudnessUpdateMs", settings.audioDsp.loudnessUpdateMs)
            .putBoolean("audioBoostOnly", settings.audioDsp.boostOnly)
            .putFloat("audioLimiterInputDb", settings.audioDsp.limiterInputDb)
            .putFloat("audioLimiterThresholdDb", settings.audioDsp.limiterThresholdDb)
            .putFloat("audioLimiterCeilingDb", settings.audioDsp.limiterCeilingDb)
            .putFloat("audioLimiterReleaseMs", settings.audioDsp.limiterReleaseMs)
            .putFloat("audioLimiterLookaheadMs", settings.audioDsp.limiterLookaheadMs)
            .putBoolean("audioAdaptiveRelease", settings.audioDsp.adaptiveRelease)
            .putBoolean("previewEnabled", settings.previewEnabled)
            .putBoolean("lowFrameRatePreview", settings.lowFrameRatePreview)
            .putString("container", settings.container.name)
            .putBoolean("confirmStopOutputs", settings.confirmStopOutputs)
            .putString("rtmpUrl", settings.rtmpUrl)
            .putInt("rtmpBufferMs", settings.rtmpBufferMs.coerceIn(100, 30_000))
            .putInt("rtmpSendTimeoutSeconds", settings.rtmpSendTimeoutSeconds.coerceIn(3, 30))
            .putString("videoBitrateKbps", settings.videoBitrateKbps)
            .putString("audioBitrateKbps", settings.audioBitrateKbps)
            .putString("audioDelayMs", settings.audioDelayMs)
            .putString("muxingQueueSize", settings.muxingQueueSize)
            .remove("videoBitrate")
            .putString("gopSeconds", settings.gopSeconds)
            .putString("bFrames", settings.bFrames)
            .putString("videoCodec", settings.videoCodec.name)
            .putString("bitrateMode", settings.bitrateMode.name)
            .putInt("audioRate", settings.audioRate)
            .putInt("bufferFrames", settings.bufferFrames.coerceIn(1, 30))
            .putInt("receiveTransferCount", settings.receiveTransferCount.coerceIn(8, 512))
            .remove("bulkTransferCount")
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
            .remove("scrollOffset")
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
}
