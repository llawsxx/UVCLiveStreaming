package com.llawsxx.uvclivestreaming.recording

import android.hardware.camera2.CameraCharacteristics
import android.media.MediaCodecInfo
import android.media.MediaRecorder
import android.media.MediaFormat
import android.util.Range
import android.util.Size
import java.io.Serializable

enum class RecordingMode(val label: String) : Serializable {
    AUDIO("录音"), VIDEO("录像"), AUDIO_VIDEO("录音 + 录像")
}

enum class UsbVideoInputFormat(val label: String, val nativeValue: Int) : Serializable {
    AUTO("自动", 0), MJPG("MJPG", 1), YUYV("YUYV", 2),
    UYVY("UYVY", 3), RGB("RGB", 4), NV12("NV12", 5), I420("I420", 6),
    P010("P010", 7), H264("H.264", 8), BGR("BGR", 9)
}

enum class ContainerFormat(val label: String, val extension: String) : Serializable {
    MP4("MP4", "mp4"), MPEG_TS("MPEG-TS", "ts")
}

enum class VideoCodec(val label: String, val mediaRecorderValue: Int) : Serializable {
    H264("H.264 / AVC", MediaRecorder.VideoEncoder.H264),
    H265("H.265 / HEVC", MediaRecorder.VideoEncoder.HEVC)
}

enum class VideoBitrateMode(val label: String, val mediaFormatValue: Int?) : Serializable {
    DEFAULT("编码器默认", null),
    VBR("VBR 可变码率", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR),
    CBR("CBR 固定码率", MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR),
}

enum class AudioAacProfile(
    val label: String,
    val mediaCodecValue: Int,
    val mediaRecorderValue: Int,
) : Serializable {
    LC("AAC-LC", MediaCodecInfo.CodecProfileLevel.AACObjectLC, MediaRecorder.AudioEncoder.AAC),
    HE("HE-AAC", MediaCodecInfo.CodecProfileLevel.AACObjectHE, MediaRecorder.AudioEncoder.HE_AAC),
    ELD("AAC-ELD", MediaCodecInfo.CodecProfileLevel.AACObjectELD, MediaRecorder.AudioEncoder.AAC_ELD),
}

enum class AudioInputSource(val label: String, val mediaRecorderValue: Int) : Serializable {
    DEFAULT("DEFAULT（系统默认）", MediaRecorder.AudioSource.DEFAULT),
    MIC("MIC（标准麦克风）", MediaRecorder.AudioSource.MIC),
    CAMCORDER("CAMCORDER（摄像机调优）", MediaRecorder.AudioSource.CAMCORDER),
    VOICE_RECOGNITION("VOICE_RECOGNITION（语音识别）", MediaRecorder.AudioSource.VOICE_RECOGNITION),
    VOICE_COMMUNICATION("VOICE_COMMUNICATION（语音通信）", MediaRecorder.AudioSource.VOICE_COMMUNICATION),
    UNPROCESSED("UNPROCESSED（未经处理）", MediaRecorder.AudioSource.UNPROCESSED),
    VOICE_PERFORMANCE("VOICE_PERFORMANCE（实时演唱）", MediaRecorder.AudioSource.VOICE_PERFORMANCE),
}

enum class VideoDynamicRange(
    val label: String,
    val cameraProfile: Long,
    val is10Bit: Boolean,
) : Serializable {
    SDR("SDR 8-bit", 1L, false),
    HLG10("HLG HDR 10-bit", 2L, true),
    HDR10("HDR10 10-bit", 4L, true),
    HDR10_PLUS("HDR10+ 10-bit", 8L, true),
}

enum class VideoColorRange(
    val label: String,
    val mediaFormatValue: Int?,
    val vuiFullRange: Int,
) : Serializable {
    DEFAULT("编码器默认", null, -1),
    LIMITED("TV / Limited range", MediaFormat.COLOR_RANGE_LIMITED, 0),
    FULL("PC / Full range", MediaFormat.COLOR_RANGE_FULL, 1),
}

enum class VideoColorStandard(
    val label: String,
    val mediaFormatValue: Int?,
    val vuiPrimaries: Int,
) : Serializable {
    DEFAULT("编码器默认", null, -1),
    BT709("BT.709", MediaFormat.COLOR_STANDARD_BT709, 1),
    BT601_NTSC("BT.601 NTSC", MediaFormat.COLOR_STANDARD_BT601_NTSC, 6),
    BT601_PAL("BT.601 PAL", MediaFormat.COLOR_STANDARD_BT601_PAL, 5),
    BT2020("BT.2020", MediaFormat.COLOR_STANDARD_BT2020, 9),
}

enum class VideoColorMatrix(val label: String, val vuiValue: Int) : Serializable {
    DEFAULT("编码器默认", -1),
    BT709("BT.709", 1),
    BT601("BT.601 / SMPTE 170M", 6),
    SMPTE240M("SMPTE 240M", 7),
    BT2020("BT.2020 non-constant", 9),
    BT2020_CL("BT.2020 constant luminance", 10),
}

enum class VideoColorTransfer(
    val label: String,
    val mediaFormatValue: Int?,
    val vuiValue: Int,
) : Serializable {
    DEFAULT("编码器默认", null, -1),
    BT601("BT.601 / SMPTE 170M", MediaFormat.COLOR_TRANSFER_SDR_VIDEO, 6),
    BT709("BT.709", MediaFormat.COLOR_TRANSFER_SDR_VIDEO, 1),
    BT470M("BT.470 M / Gamma 2.2", MediaFormat.COLOR_TRANSFER_SDR_VIDEO, 4),
    BT470BG("BT.470 B/G / Gamma 2.8", MediaFormat.COLOR_TRANSFER_SDR_VIDEO, 5),
    SMPTE240M("SMPTE 240M", MediaFormat.COLOR_TRANSFER_SDR_VIDEO, 7),
    SRGB("sRGB", MediaFormat.COLOR_TRANSFER_SDR_VIDEO, 13),
    BT2020("BT.2020 10-bit", MediaFormat.COLOR_TRANSFER_SDR_VIDEO, 14),
    BT2020_12("BT.2020 12-bit", MediaFormat.COLOR_TRANSFER_SDR_VIDEO, 15),
    LINEAR("Linear", MediaFormat.COLOR_TRANSFER_LINEAR, 8),
    HLG("HLG", MediaFormat.COLOR_TRANSFER_HLG, 18),
    ST2084("ST 2084 / PQ", MediaFormat.COLOR_TRANSFER_ST2084, 16),
}

enum class RawOutputPreset(
    val label: String,
    val standard: VideoColorStandard,
    val transfer: VideoColorTransfer,
    val range: VideoColorRange,
) : Serializable {
    BT709_REC709_TV(
        "BT.709 / Rec.709 / TV",
        VideoColorStandard.BT709,
        VideoColorTransfer.BT709,
        VideoColorRange.LIMITED,
    ),
    BT2020_HLG_TV(
        "BT.2020 / HLG / TV",
        VideoColorStandard.BT2020,
        VideoColorTransfer.HLG,
        VideoColorRange.LIMITED,
    ),
    BT709_HLG_TV(
        "BT.709 / HLG / TV",
        VideoColorStandard.BT709,
        VideoColorTransfer.HLG,
        VideoColorRange.LIMITED,
    ),
}

enum class RawColorStyle(val label: String) : Serializable {
    STANDARD_DIRECT("标准直出"),
    FAITHFUL("忠实还原"),
    CUSTOM("自定义"),
}

enum class CameraTonemapCurve(val label: String) : Serializable {
    OFF("关闭（相机默认）"),
    LINEAR("线性"),
    BT709("BT.709"),
    HLG("HLG"),
}

enum class ColorCorrectionMode(val label: String, val cameraValue: Int) : Serializable {
    FAST("FAST", CameraCharacteristics.COLOR_CORRECTION_MODE_FAST),
    HIGH_QUALITY("HIGH_QUALITY", CameraCharacteristics.COLOR_CORRECTION_MODE_HIGH_QUALITY),
    TRANSFORM_MATRIX("TRANSFORM_MATRIX", CameraCharacteristics.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX),
}

enum class WhiteBalanceTransformMode(val label: String) : Serializable {
    LATEST("最新矩阵（原样）"),
    BT2020("最新矩阵转换为 BT.2020"),
}

enum class OrientationMode(val label: String) : Serializable {
    FOLLOW_SENSOR("跟随设备"), LANDSCAPE("固定横屏"), PORTRAIT("固定竖屏")
}

enum class PreviewMode(val label: String) : Serializable {
    FULL("完整预览"), OFF("关闭预览")
}

enum class PreviewLayout(val label: String) : Serializable {
    STACKED("上下布局"), FULLSCREEN("全屏预览")
}

enum class PhotoFormat(val label: String, val extension: String, val mimeType: String) : Serializable {
    JPEG("JPG", "jpg", "image/jpeg"),
    PNG("PNG", "png", "image/png"),
}

enum class FocusMode(val label: String) : Serializable {
    CONTINUOUS("连续自动"), MANUAL("手动")
}

enum class CustomExposureMetering(val label: String) : Serializable {
    AVERAGE("平均测光"), CENTER("中央重点"), SPOT("点测光"),
}

enum class TouchFocusState : Serializable {
    FOCUSING, SUCCESS, FAILED
}

enum class VideoScalingAlgorithm(val label: String) : Serializable {
    NEAREST("最近邻（最快）"),
    BILINEAR("双线性（推荐）"),
    BICUBIC("双三次（更锐利）"),
}

enum class RawScalingQuality(val label: String) : Serializable {
    FAST("快速"),
    HIGH_QUALITY("高质量"),
}

enum class RawDemosaicAlgorithm(val label: String) : Serializable {
    FAST("快速（双线性）"),
    HIGH_QUALITY("高质量（边缘感知）"),
    LMMSE("高质量（5×5 线性滤波）"),
}

data class RecordingConfig(
    val mode: RecordingMode = RecordingMode.AUDIO_VIDEO,
    val cameraId: String = "",
    val testCard: TestCardSettings = TestCardSettings(),
    val usbVideoInputFormat: UsbVideoInputFormat = UsbVideoInputFormat.AUTO,
    val usbCustomVideoMode: Boolean = false,
    /** Zero selects a supported USB microphone rate automatically. */
    val usbAudioSampleRate: Int = 0,
    val usbAudioInput: UsbAudioInput = UsbAudioInput.USB,
    val usbAudioDevice: UsbAudioDevice? = null,
    val usbAudioBitDepth: UsbAudioBitDepth = UsbAudioBitDepth.AUTO,
    val systemAudioInput: SystemAudioInputSettings = SystemAudioInputSettings(),
    /** Bounded queue of pending USB video frames, including MJPEG decode input. */
    val usbVideoBufferFrames: Int = 2,
    /** Pending USB video receive requests (Bulk/ISO), separate from the frame queue. */
    val usbReceiveTransferCount: Int = 64,
    val usbYuvMatrix: UsbYuvMatrix = UsbYuvMatrix.BT601,
    val usbSourceRange: UsbSourceRange = UsbSourceRange.AUTO,
    val usbTimestampSmoothingEnabled: Boolean = true,
    /** Use 60000/1001 or 30000/1001 when smoothing nominal 60/30 fps USB video. */
    val usbTimestampSmoothingNtscEnabled: Boolean = false,
    /** Maximum difference from the calculated video/PCM timeline, in seconds. */
    val usbTimestampSmoothingMaxDeltaSeconds: Double = 0.1,
    val width: Int = 1920,
    val height: Int = 1080,
    val cropEnabled: Boolean = false,
    val cropWidth: Int = 1920,
    val cropHeight: Int = 1080,
    val resizeEnabled: Boolean = false,
    val recordWidth: Int = 1920,
    val recordHeight: Int = 1080,
    val scalingAlgorithm: VideoScalingAlgorithm = VideoScalingAlgorithm.BILINEAR,
    val fps: Double = 30.0,
    val experimentalCameraAccess: Boolean = false,
    val experimentalUnadvertisedFps: Boolean = false,
    val sensorFrameDurationAutoTune: Boolean = false,
    val sensorFrameDurationTuneStepNs: Long = 3_000L,
    val targetPtsCorrectionEnabled: Boolean = false,
    val targetPtsCorrectionMaxFrameDelta: Float = 0.8f,
    val audioPtsCorrectionEnabled: Boolean = false,
    val audioPtsCorrectionMaxFrameDelta: Float = 0.8f,
    val mediaCodecMode: Boolean = false,
    val rawProcessingEnabled: Boolean = false,
    val rawWidth: Int = 0,
    val rawHeight: Int = 0,
    val rawScalingQuality: RawScalingQuality = RawScalingQuality.HIGH_QUALITY,
    val rawDemosaicAlgorithm: RawDemosaicAlgorithm = RawDemosaicAlgorithm.HIGH_QUALITY,
    val rawPboEnabled: Boolean = false,
    val rawColorLutEnabled: Boolean = false,
    val rawColorLutSize: Int = 33,
    val rawFrameBufferCapacity: Int = 2,
    val rawThreeAAuxiliaryYuvEnabled: Boolean = true,
    val rawLensShadingCorrectionEnabled: Boolean = true,
    val cameraShadingMode: Int = CameraCharacteristics.SHADING_MODE_FAST,
    val cameraTonemapCurve: CameraTonemapCurve = CameraTonemapCurve.OFF,
    val rawSharpeningEnabled: Boolean = false,
    val rawSharpeningStrength: Float = 0.32f,
    val rawColorStyle: RawColorStyle = RawColorStyle.STANDARD_DIRECT,
    val rawCustomSaturation: Float = 1.08f,
    val rawShadowLiftEnabled: Boolean = false,
    val rawShadowLiftKnee: Float = 0.65f,
    val rawShadowLiftTarget: Float = 0.80f,
    val rawShadowLiftSmoothness: Float = 0.50f,
    val videoBitrate: Int = 12_000_000,
    val videoBitrateMode: VideoBitrateMode = VideoBitrateMode.DEFAULT,
    /** Fractional seconds are supported; zero requests every frame as a key frame. */
    val videoKeyFrameIntervalSeconds: Float = 2f,
    val videoMaxBFrames: Int = 0,
    val audioBitrate: Int = 192_000,
    val audioAacProfile: AudioAacProfile = AudioAacProfile.LC,
    val audioSampleRate: Int = 48_000,
    val audioChannelCount: Int = 2,
    val audioAutomaticGainControl: Boolean = false,
    val audioDisableNoiseSuppressor: Boolean = false,
    val audioDisableEchoCanceler: Boolean = false,
    val audioInputSource: AudioInputSource = AudioInputSource.CAMCORDER,
    val audioFloatSidecarEnabled: Boolean = false,
    val audioInputDeviceId: Int? = null,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val dynamicRange: VideoDynamicRange = VideoDynamicRange.SDR,
    val highSpeedMode: Boolean = false,
    val colorRange: VideoColorRange = VideoColorRange.DEFAULT,
    val colorStandard: VideoColorStandard = VideoColorStandard.DEFAULT,
    val colorTransfer: VideoColorTransfer = VideoColorTransfer.DEFAULT,
    val rewriteColorRange: VideoColorRange = VideoColorRange.DEFAULT,
    val rewriteColorStandard: VideoColorStandard = VideoColorStandard.DEFAULT,
    val rewriteColorMatrix: VideoColorMatrix = VideoColorMatrix.DEFAULT,
    val rewriteColorTransfer: VideoColorTransfer = VideoColorTransfer.DEFAULT,
    val forceSpsVui: Boolean = false,
    val container: ContainerFormat = ContainerFormat.MP4,
    val segmentMinutes: Int = 10,
    val orientation: OrientationMode = OrientationMode.FOLLOW_SENSOR,
    val rotateImagePixels: Boolean = false,
    val previewMode: PreviewMode = PreviewMode.FULL,
    val permanentPreviewSurface: Boolean = false,
    val previewLayout: PreviewLayout = PreviewLayout.STACKED,
    /** Explicit preview buffer size; zero means follow the recording size. */
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val photoFormat: PhotoFormat = PhotoFormat.JPEG,
    val photoJpegQuality: Int = 95,
    val manualExposure: Boolean = false,
    val customExposureEnabled: Boolean = false,
    val customExposureMetering: CustomExposureMetering = CustomExposureMetering.CENTER,
    val customExposureTarget: Float = 0.18f,
    val customExposureMinIso: Int = 100,
    val customExposureMaxIso: Int = 1600,
    val customExposureMinNs: Long = 100_000L,
    val customExposureMaxNs: Long = 33_333_333L,
    val customExposureSpeed: Float = 0.25f,
    val customExposureDeadbandEv: Float = 0.04f,
    /** Zero adjusts after every completed capture; positive values are updates per second. */
    val customExposureUpdatesPerSecond: Int = 3,
    /** Completed captures to wait after an exposure request in per-capture mode. */
    val customExposureSettleFrames: Int = 3,
    val iso: Int = 400,
    val exposureNs: Long = 10_000_000L,
    val aperture: Float? = null,
    val exposureCompensation: Int = 0,
    val antibandingMode: Int = CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_AUTO,
    val awbMode: Int = CameraCharacteristics.CONTROL_AWB_MODE_AUTO,
    val manualWhiteBalance: Boolean = false,
    val whiteBalanceTemperature: Int = 5_500,
    val whiteBalanceTint: Int = 0,
    val advancedWhiteBalance: Boolean = false,
    val splitWhiteBalanceGreen: Boolean = false,
    val whiteBalanceRedGain: Float = 1f,
    val whiteBalanceGreenEvenGain: Float = 1f,
    val whiteBalanceGreenOddGain: Float = 1f,
    val whiteBalanceBlueGain: Float = 1f,
    val whiteBalanceColorTransform: List<Int> = emptyList(),
    val colorCorrectionMode: ColorCorrectionMode = ColorCorrectionMode.TRANSFORM_MATRIX,
    val whiteBalanceTransformMode: WhiteBalanceTransformMode = WhiteBalanceTransformMode.LATEST,
    val focusMode: FocusMode = FocusMode.CONTINUOUS,
    val focusDistanceDiopters: Float = 0f,
    val touchFocusEnabled: Boolean = false,
    val touchFocusX: Float? = null,
    val touchFocusY: Float? = null,
    val touchFocusRotationDegrees: Int = 0,
    val touchFocusPreviewWidth: Int = 0,
    val touchFocusPreviewHeight: Int = 0,
    val touchFocusPreviewMirrored: Boolean = false,
    val touchFocusRequestId: Long = 0L,
    val isoPresets: List<Int> = emptyList(),
    val shutterPresets: List<String> = emptyList(),
    val focusDistancePresets: List<FocusDistancePreset> = emptyList(),
    val unrestrictedIso: Boolean = false,
    val unrestrictedExposure: Boolean = false,
    val unrestrictedFocus: Boolean = false,
    val mfAssistMagnifications: List<Int> = listOf(2, 4, 8),
    val mfAssistMagnification: Int = 1,
    val mfAssistCenterX: Float = 0.5f,
    val mfAssistCenterY: Float = 0.5f,
    val opticalStabilization: Boolean = true,
    val noiseReductionMode: Int = CameraCharacteristics.NOISE_REDUCTION_MODE_FAST,
    val edgeMode: Int = CameraCharacteristics.EDGE_MODE_FAST,
    val hotPixelMode: Int = CameraCharacteristics.HOT_PIXEL_MODE_FAST,
    val aberrationCorrectionMode: Int = CameraCharacteristics.COLOR_CORRECTION_ABERRATION_MODE_FAST,
    val distortionCorrectionMode: Int = CameraCharacteristics.DISTORTION_CORRECTION_MODE_OFF,
    val httpStreamEnabled: Boolean = false,
    val httpStreamPort: Int = 8080,
    val httpBufferSeconds: Int = 30,
    /** RTMP is available for USB capture and uses H.264/HEVC + AAC messages. */
    val rtmpEnabled: Boolean = false,
    val rtmpUrl: String = "",
    val rtmpReconnectDelaySeconds: Int = 3,
    /** Start without a file; a recording output can be attached to the same encoder later. */
    val httpServiceOnly: Boolean = false,
    val outputTreeUri: String? = null,
) : Serializable {
    val hasVideo: Boolean get() = mode != RecordingMode.AUDIO
    val hasAudio: Boolean get() = mode != RecordingMode.VIDEO
    val customColorMetadata: Boolean get() = colorRange != VideoColorRange.DEFAULT ||
        colorStandard != VideoColorStandard.DEFAULT || colorTransfer != VideoColorTransfer.DEFAULT
    val customRewriteColorMetadata: Boolean get() = rewriteColorRange != VideoColorRange.DEFAULT ||
        rewriteColorStandard != VideoColorStandard.DEFAULT || rewriteColorMatrix != VideoColorMatrix.DEFAULT ||
        rewriteColorTransfer != VideoColorTransfer.DEFAULT
    val manualSpsVuiRewriteEnabled: Boolean get() = forceSpsVui && customRewriteColorMetadata
    val effectiveRawColorRange: VideoColorRange get() =
        colorRange.takeUnless { it == VideoColorRange.DEFAULT } ?: VideoColorRange.LIMITED
    val effectiveRawColorStandard: VideoColorStandard get() =
        colorStandard.takeUnless { it == VideoColorStandard.DEFAULT } ?: VideoColorStandard.BT709
    val effectiveRawColorTransfer: VideoColorTransfer get() =
        colorTransfer.takeUnless { it == VideoColorTransfer.DEFAULT } ?: VideoColorTransfer.BT709
    val effectiveRawColorMatrix: VideoColorMatrix get() = when (effectiveRawColorStandard) {
        VideoColorStandard.BT2020 -> VideoColorMatrix.BT2020
        else -> VideoColorMatrix.BT709
    }
    val spsVuiRewriteEnabled: Boolean get() = manualSpsVuiRewriteEnabled
    val effectiveVuiColorRange: VideoColorRange get() = rewriteColorRange
    val effectiveVuiColorStandard: VideoColorStandard get() = rewriteColorStandard
    val effectiveVuiColorMatrix: VideoColorMatrix get() = rewriteColorMatrix
    val effectiveVuiColorTransfer: VideoColorTransfer get() = rewriteColorTransfer
    val rawHdrOutput: Boolean get() = rawProcessingEnabled &&
        effectiveRawColorTransfer in setOf(VideoColorTransfer.HLG, VideoColorTransfer.ST2084)
    val effectiveRawSharpeningStrength: Float get() = rawSharpeningStrength.coerceIn(0f, 1f)
    val effectiveRawSaturation: Float get() = when (rawColorStyle) {
        RawColorStyle.STANDARD_DIRECT -> 1.08f
        RawColorStyle.FAITHFUL -> 1f
        RawColorStyle.CUSTOM -> rawCustomSaturation.coerceIn(0f, 3f)
    }
    val effectiveRawShadowLiftKnee: Float get() = rawShadowLiftKnee.coerceIn(0.1f, 0.9f)
    val effectiveRawShadowLiftTarget: Float get() =
        rawShadowLiftTarget.coerceIn(effectiveRawShadowLiftKnee, 1f)
    val effectiveRawShadowLiftSmoothness: Float get() = rawShadowLiftSmoothness.coerceIn(0f, 1f)
    val requires10BitEncoding: Boolean get() = dynamicRange.is10Bit || rawHdrOutput
    val rawOutputPreset: RawOutputPreset? get() = RawOutputPreset.entries.firstOrNull {
        it.standard == effectiveRawColorStandard && it.transfer == effectiveRawColorTransfer &&
            it.range == effectiveRawColorRange
    }
    val rawColorConfigurationSupported: Boolean get() =
        effectiveRawColorRange in setOf(VideoColorRange.LIMITED, VideoColorRange.FULL) &&
            effectiveRawColorStandard in setOf(VideoColorStandard.BT709, VideoColorStandard.BT2020) &&
            effectiveRawColorTransfer in setOf(
                VideoColorTransfer.BT709,
                VideoColorTransfer.HLG,
                VideoColorTransfer.ST2084,
            )
    val videoTransformEnabled: Boolean get() = cropEnabled || resizeEnabled || rotateImagePixels
    val customVideoEncoderParameters: Boolean get() = videoBitrateMode != VideoBitrateMode.DEFAULT ||
        videoKeyFrameIntervalSeconds != 2f || videoMaxBFrames != 0
    val effectiveAudioAacProfile: AudioAacProfile get() =
        if (container == ContainerFormat.MPEG_TS) AudioAacProfile.LC else audioAacProfile
    val audioSessionEffectsRequested: Boolean get() =
        audioAutomaticGainControl || audioDisableNoiseSuppressor || audioDisableEchoCanceler
    val mediaCodecEngineRequested: Boolean get() = mediaCodecMode || targetPtsCorrectionEnabled ||
        (hasAudio && audioPtsCorrectionEnabled && !highSpeedMode) ||
        customVideoEncoderParameters ||
        (hasVideo && hasAudio && (audioSessionEffectsRequested || audioFloatSidecarEnabled)) ||
        customColorMetadata || videoTransformEnabled || rawProcessingEnabled ||
        dynamicRange != VideoDynamicRange.SDR || manualSpsVuiRewriteEnabled
    val transformWidth: Int get() = if (cropEnabled) cropWidth else width
    val transformHeight: Int get() = if (cropEnabled) cropHeight else height
    val outputWidth: Int get() = if (resizeEnabled) recordWidth else transformWidth
    val outputHeight: Int get() = if (resizeEnabled) recordHeight else transformHeight
    val cropSizeValid: Boolean get() = !cropEnabled || (
        cropWidth in 16..width && cropHeight in 16..height && cropWidth % 2 == 0 && cropHeight % 2 == 0
    )
    val resizeSizeValid: Boolean get() = !resizeEnabled || (
        recordWidth in 16..transformWidth && recordHeight in 16..transformHeight &&
            recordWidth % 2 == 0 && recordHeight % 2 == 0 &&
            recordWidth.toLong() * transformHeight == recordHeight.toLong() * transformWidth
    )
    val maximumExposureNs: Long get() = minOf(
        1_000_000_000L,
        (2_000_000_000.0 / fps.coerceAtLeast(1.0)).toLong().coerceAtMost(1_000_000_000L),
    )
    val targetFrameDurationNs: Long get() =
        kotlin.math.round(1_000_000_000.0 / fps.coerceAtLeast(1.0)).toLong().coerceAtLeast(1L)
}

data class HighSpeedVideoMode(
    val width: Int,
    val height: Int,
    val minFps: Int,
    val maxFps: Int,
) {
    val label: String get() = "${width}×${height}  ${if (minFps == maxFps) maxFps else "$minFps–$maxFps"} fps"
}

data class RawSensorInfo(
    val cfa: Int?,
    /** Black levels in top-left, top-right, bottom-left, bottom-right CFA order. */
    val staticBlackLevels: List<Int>,
    val whiteLevel: Int?,
    val dynamicBlackLevelAvailable: Boolean,
    val dynamicWhiteLevelAvailable: Boolean,
    val pixelArraySize: Size?,
    val preCorrectionActiveArraySize: Size?,
    val activeArraySize: Size?,
    val opticalBlackRegionCount: Int,
    val perFrameColorTransformAvailable: Boolean,
    val staticColorTransformCount: Int,
    val forwardMatrixCount: Int,
    val calibrationTransformCount: Int,
) {
    val estimatedBitDepth: Int? get() = whiteLevel?.takeIf { it > 0 }?.let {
        Int.SIZE_BITS - Integer.numberOfLeadingZeros(it)
    }
}

data class CameraInfo(
    val id: String,
    val displayName: String,
    val lensFacing: Int,
    val sizes: List<Size>,
    val previewSizes: List<Size>,
    val surfaceViewSizes: List<Size>,
    val fpsRanges: List<Range<Int>>,
    val estimatedMaxFpsBySize: Map<String, Int>,
    val experimentalCandidate: Boolean,
    val manualSensorAvailable: Boolean,
    val isoRange: Range<Int>?,
    val exposureRange: Range<Long>?,
    val apertures: List<Float>,
    val exposureCompensationRange: Range<Int>?,
    val exposureCompensationStep: android.util.Rational?,
    val antibandingModes: List<Int>,
    val awbModes: List<Int>,
    val supportsManualWhiteBalance: Boolean,
    val oisAvailable: Boolean,
    val noiseReductionModes: List<Int>,
    val edgeModes: List<Int>,
    val hotPixelModes: List<Int>,
    val aberrationCorrectionModes: List<Int>,
    val distortionCorrectionModes: List<Int>,
    val shadingModes: List<Int>,
    val customTonemapCurveAvailable: Boolean,
    val maximumTonemapCurvePoints: Int,
    val afModes: List<Int>,
    val maxAfRegions: Int,
    val minimumFocusDistance: Float,
    val sensorOrientation: Int,
    val highSpeedModes: List<HighSpeedVideoMode>,
    val dynamicRanges: List<VideoDynamicRange>,
    val rawSizes: List<Size>,
    val rawEstimatedMaxFpsBySize: Map<String, Int>,
    val rawLensShadingCorrectionAvailable: Boolean,
    val rawSensorInfo: RawSensorInfo?,
)

data class RecordingStats(
    val elapsedMs: Long = 0L,
    val averageFps: Double = 0.0,
    val sensorFps: Double = 0.0,
    val averageBitrateBitsPerSecond: Double = 0.0,
    val droppedFrames: Long = 0L,
    val ptsCorrectionDroppedFrames: Long = 0L,
    val segment: Int = 0,
    val outputPath: String? = null,
    val fileRecording: Boolean = false,
    val bytesStreamed: Long = 0L,
    val httpStreaming: Boolean = false,
    val rtmpStreaming: Boolean = false,
    val outputChangePending: Boolean = false,
    val streamBitrateBitsPerSecond: Double = 0.0,
    val audioLevelDb: Float = -60f,
    val rawFrameBufferUsed: Int = 0,
    val rawFrameBufferCapacity: Int = 0,
    /** Encoded video FPS over the most recent approximately five-second window. */
    val recentFps: Double? = null,
    /** Recent USB video endpoint throughput; includes UVC headers, excludes audio/bus overhead. */
    val usbVideoReceiveBitsPerSecond: Double? = null,
)

data class CameraExposureState(
    val cameraId: String,
    val iso: Int?,
    val exposureNs: Long?,
    val aperture: Float?,
    val focusDistanceDiopters: Float?,
    val whiteBalanceRedGain: Float? = null,
    val whiteBalanceGreenEvenGain: Float? = null,
    val whiteBalanceGreenOddGain: Float? = null,
    val whiteBalanceBlueGain: Float? = null,
    val whiteBalanceColorTransform: List<Int>? = null,
    val touchFocusRequestId: Long = 0L,
    val touchFocusState: TouchFocusState? = null,
)

sealed interface RecorderState {
    data object Idle : RecorderState
    data class Starting(val message: String = "正在启动") : RecorderState
    data class Recording(val stats: RecordingStats) : RecorderState
    data class Stopping(val message: String = "正在安全结束文件") : RecorderState
    data class Error(val message: String) : RecorderState
}

fun awbLabel(mode: Int): String = when (mode) {
    CameraCharacteristics.CONTROL_AWB_MODE_AUTO -> "自动"
    CameraCharacteristics.CONTROL_AWB_MODE_INCANDESCENT -> "白炽灯"
    CameraCharacteristics.CONTROL_AWB_MODE_FLUORESCENT -> "荧光灯"
    CameraCharacteristics.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "暖荧光灯"
    CameraCharacteristics.CONTROL_AWB_MODE_DAYLIGHT -> "日光"
    CameraCharacteristics.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "阴天"
    CameraCharacteristics.CONTROL_AWB_MODE_TWILIGHT -> "黄昏"
    CameraCharacteristics.CONTROL_AWB_MODE_SHADE -> "阴影"
    else -> "模式 $mode"
}
