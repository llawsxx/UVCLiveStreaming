package com.llawsxx.uvclivestreaming.recording

import android.content.Context
import android.content.SharedPreferences
import android.hardware.camera2.CameraCharacteristics

object ConfigPreferences {
    private const val NAME = "recording_config"
    private val RAW_COLOR_LUT_SIZES = setOf(17, 33, 65)

    fun load(context: Context): RecordingConfig = load(
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE),
    )

    internal fun load(p: SharedPreferences): RecordingConfig {
        return RecordingConfig(
            mode = enumValue(p.getString("mode", null), RecordingMode.AUDIO_VIDEO),
            cameraId = p.getString("cameraId", "").orEmpty(),
            testCard = TestCardPreferences.load(p),
            usbVideoInputFormat = enumValue(p.getString("usbVideoInputFormat", null), UsbVideoInputFormat.AUTO),
            usbCustomVideoMode = p.getBoolean("usbCustomVideoMode", false),
            usbAudioSampleRate = p.getInt("usbAudioSampleRate", 0).coerceAtLeast(0),
            usbAudioInput = enumValue(p.getString("usbAudioInput", null), UsbAudioInput.USB),
            usbAudioDevice = UsbAudioDevicePreferences.load(p),
            usbAudioBitDepth = UsbAudioDevicePreferences.bitDepth(p),
            systemAudioInput = SystemAudioInputPreferences.load(p),
            usbVideoBufferFrames = p.getInt("usbVideoBufferFrames", 2).coerceIn(1, 30),
            usbReceiveTransferCount = p.getInt("usbReceiveTransferCount", 64).coerceIn(8, 512),
            usbYuvMatrix = enumValue(p.getString("usbYuvMatrix", null), UsbYuvMatrix.BT601),
            usbSourceRange = enumValue(p.getString("usbSourceRange", null), UsbSourceRange.AUTO),
            usbYuvEncoderInput = p.getBoolean("usbYuvEncoderInput", false),
            usbTimestampSmoothingEnabled = p.getBoolean("usbTimestampSmoothingEnabled", true),
            usbTimestampSmoothingNtscEnabled = p.getBoolean("usbTimestampSmoothingNtscEnabled", false),
            usbTimestampSmoothingMaxDeltaSeconds = p.getString("usbTimestampSmoothingMaxDeltaSeconds", "0.1")
                ?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: 0.1,
            width = p.getInt("width", 1920),
            height = p.getInt("height", 1080),
            cropEnabled = p.getBoolean("cropEnabled", false),
            cropWidth = p.getInt("cropWidth", 1920),
            cropHeight = p.getInt("cropHeight", 1080),
            resizeEnabled = p.getBoolean("resizeEnabled", false),
            recordWidth = p.getInt("recordWidth", 1920),
            recordHeight = p.getInt("recordHeight", 1080),
            scalingAlgorithm = enumValue(
                p.getString("scalingAlgorithm", null),
                VideoScalingAlgorithm.BILINEAR,
            ),
            fps = (p.getString("fpsExact", null)?.toDoubleOrNull()
                ?: runCatching { p.getFloat("fps", 30f).toDouble() }
                    .getOrElse { p.getInt("fps", 30).toDouble() }).coerceIn(1.0, 240.0),
            experimentalCameraAccess = p.getBoolean("experimentalCameraAccess", false),
            experimentalUnadvertisedFps = p.getBoolean("experimentalUnadvertisedFps", false),
            sensorFrameDurationAutoTune = p.getBoolean("sensorFrameDurationAutoTune", false),
            sensorFrameDurationTuneStepNs = p.getLong("sensorFrameDurationTuneStepNs", 3_000L)
                .coerceIn(1_000L, 30_000L),
            targetPtsCorrectionEnabled = p.getBoolean("targetPtsCorrectionEnabled", false),
            targetPtsCorrectionMaxFrameDelta = p.getFloat("targetPtsCorrectionMaxFrameDelta", 0.8f)
                .coerceIn(0.6f, 5f),
            audioPtsCorrectionEnabled = p.getBoolean("audioPtsCorrectionEnabled", false),
            audioPtsCorrectionMaxFrameDelta = p.getFloat("audioPtsCorrectionMaxFrameDelta", 0.8f)
                .coerceIn(0.6f, 5f),
            mediaCodecMode = p.getBoolean("mediaCodecMode", false),
            rawProcessingEnabled = p.getBoolean("rawProcessingEnabled", false),
            rawWidth = p.getInt("rawWidth", 0).coerceAtLeast(0),
            rawHeight = p.getInt("rawHeight", 0).coerceAtLeast(0),
            rawScalingQuality = enumValue(
                p.getString("rawScalingQuality", null),
                RawScalingQuality.HIGH_QUALITY,
            ),
            rawDemosaicAlgorithm = enumValue(
                p.getString("rawDemosaicAlgorithm", null),
                RawDemosaicAlgorithm.HIGH_QUALITY,
            ),
            rawPboEnabled = p.getBoolean("rawPboEnabled", false),
            rawColorLutEnabled = p.getBoolean(
                "rawColorLutEnabled",
                p.getBoolean("rawTransferLutEnabled", false),
            ),
            rawColorLutSize = p.getInt("rawColorLutSize", 33)
                .takeIf { it in RAW_COLOR_LUT_SIZES } ?: 33,
            rawFrameBufferCapacity = p.getInt("rawFrameBufferCapacity", 2).coerceIn(1, 6),
            rawThreeAAuxiliaryYuvEnabled = p.getBoolean("rawThreeAAuxiliaryYuvEnabled", true),
            rawLensShadingCorrectionEnabled = p.getBoolean("rawLensShadingCorrectionEnabled", true),
            cameraShadingMode = p.getInt(
                "cameraShadingMode",
                when {
                    p.contains("cameraShadingEnabled") && p.getBoolean("cameraShadingEnabled", false) ->
                        CameraCharacteristics.SHADING_MODE_HIGH_QUALITY
                    p.contains("cameraShadingEnabled") -> CameraCharacteristics.SHADING_MODE_OFF
                    else -> CameraCharacteristics.SHADING_MODE_FAST
                },
            ),
            cameraTonemapCurve = enumValue(
                p.getString("cameraTonemapCurve", null),
                if (p.getBoolean("linearTonemapCurveEnabled", false)) {
                    CameraTonemapCurve.LINEAR
                } else {
                    CameraTonemapCurve.OFF
                },
            ),
            rawSharpeningEnabled = p.getBoolean("rawSharpeningEnabled", false),
            rawSharpeningStrength = p.getFloat("rawSharpeningStrength", 0.32f).coerceIn(0f, 1f),
            rawColorStyle = enumValue(p.getString("rawColorStyle", null), RawColorStyle.STANDARD_DIRECT),
            rawCustomSaturation = p.getFloat("rawCustomSaturation", 1.08f).coerceIn(0f, 2f),
            rawShadowLiftEnabled = p.getBoolean("rawShadowLiftEnabled", false),
            rawShadowLiftKnee = p.getFloat("rawShadowLiftKnee", 0.65f).coerceIn(0.1f, 0.9f),
            rawShadowLiftTarget = p.getFloat("rawShadowLiftTarget", 0.80f).coerceIn(0.1f, 1f),
            rawShadowLiftSmoothness = p.getFloat("rawShadowLiftSmoothness", 0.50f).coerceIn(0f, 1f),
            videoBitrate = p.getInt("videoBitrate", 12_000_000),
            videoBitrateMode = enumValue(p.getString("videoBitrateMode", null), VideoBitrateMode.DEFAULT),
            // The previous version stored whole seconds as Int under the same key.
            videoKeyFrameIntervalSeconds = (p.all["videoKeyFrameIntervalSeconds"] as? Number)
                ?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(0f, 60f) ?: 2f,
            videoMaxBFrames = p.getInt("videoMaxBFrames", 0).coerceIn(0, 4),
            audioBitrate = p.getInt("audioBitrate", 192_000),
            audioDelayMs = p.getInt("audioDelayMs", 0).coerceIn(-500, 500),
            muxingQueueSize = p.getInt("muxingQueueSize", 64).coerceIn(0, 1024),
            audioAacProfile = enumValue(p.getString("audioAacProfile", null), AudioAacProfile.LC),
            audioSampleRate = p.getInt("audioSampleRate", 48_000),
            audioChannelCount = p.getInt("audioChannelCount", 2).coerceIn(1, 2),
            audioAutomaticGainControl = p.getBoolean("audioAutomaticGainControl", false),
            audioDisableNoiseSuppressor = p.getBoolean("audioDisableNoiseSuppressor", false),
            audioDisableEchoCanceler = p.getBoolean("audioDisableEchoCanceler", false),
            audioInputSource = enumValue(p.getString("audioInputSource", null), AudioInputSource.CAMCORDER),
            audioFloatSidecarEnabled = p.getBoolean("audioFloatSidecarEnabled", false),
            audioInputDeviceId = p.getInt("audioInputDeviceId", -1).takeIf { it >= 0 },
            videoCodec = enumValue(p.getString("videoCodec", null), VideoCodec.H264),
            dynamicRange = enumValue(p.getString("dynamicRange", null), VideoDynamicRange.SDR),
            highSpeedMode = p.getBoolean("highSpeedMode", false),
            colorRange = enumValue(p.getString("colorRange", null), VideoColorRange.DEFAULT),
            colorStandard = enumValue(p.getString("colorStandard", null), VideoColorStandard.DEFAULT),
            colorTransfer = videoColorTransfer(p.getString("colorTransfer", null)),
            rewriteColorRange = enumValue(
                p.getString("rewriteColorRange", null),
                if (p.getBoolean("forceSpsVui", false)) {
                    enumValue(p.getString("colorRange", null), VideoColorRange.DEFAULT)
                } else VideoColorRange.DEFAULT,
            ),
            rewriteColorStandard = enumValue(
                p.getString("rewriteColorStandard", null),
                if (p.getBoolean("forceSpsVui", false)) {
                    enumValue(p.getString("colorStandard", null), VideoColorStandard.DEFAULT)
                } else VideoColorStandard.DEFAULT,
            ),
            rewriteColorMatrix = enumValue(
                p.getString("rewriteColorMatrix", null),
                VideoColorMatrix.DEFAULT,
            ),
            rewriteColorTransfer = videoColorTransfer(
                p.getString("rewriteColorTransfer", null)
                    ?: p.getString("colorTransfer", null).takeIf { p.getBoolean("forceSpsVui", false) },
            ),
            forceSpsVui = p.getBoolean("forceSpsVui", false),
            container = enumValue(p.getString("container", null), ContainerFormat.MP4),
            segmentMinutes = p.getInt("segmentMinutes", 10),
            orientation = enumValue(p.getString("orientation", null), OrientationMode.FOLLOW_SENSOR),
            rotateImagePixels = p.getBoolean("rotateImagePixels", false),
            previewMode = enumValue(p.getString("previewMode", null), PreviewMode.FULL),
            permanentPreviewSurface = p.getBoolean("permanentPreviewSurface", false),
            previewLayout = enumValue(p.getString("previewLayout", null), PreviewLayout.STACKED),
            previewWidth = p.getInt("previewWidth", 0).coerceAtLeast(0),
            previewHeight = p.getInt("previewHeight", 0).coerceAtLeast(0),
            photoFormat = enumValue(p.getString("photoFormat", null), PhotoFormat.JPEG),
            photoJpegQuality = p.getInt("photoJpegQuality", 95).coerceIn(1, 100),
            manualExposure = p.getBoolean("manualExposure", false),
            customExposureEnabled = p.getBoolean("customExposureEnabled", false),
            customExposureMetering = enumValue(p.getString("customExposureMetering", null), CustomExposureMetering.CENTER),
            customExposureTarget = p.getFloat("customExposureTarget", 0.18f).coerceIn(0.02f, 0.95f),
            customExposureMinIso = p.getInt("customExposureMinIso", 100).coerceAtLeast(1),
            customExposureMaxIso = p.getInt("customExposureMaxIso", 1600).coerceAtLeast(1),
            customExposureMinNs = p.getLong("customExposureMinNs", 100_000L).coerceAtLeast(1L),
            customExposureMaxNs = p.getLong("customExposureMaxNs", 33_333_333L).coerceAtLeast(1L),
            customExposureSpeed = p.getFloat("customExposureSpeed", 0.25f).coerceIn(0.02f, 1f),
            customExposureDeadbandEv = p.getFloat("customExposureDeadbandEv", 0.04f).coerceIn(0f, 1f),
            customExposureUpdatesPerSecond = p.getInt("customExposureUpdatesPerSecond", 3).coerceIn(0, 10),
            customExposureSettleFrames = p.getInt("customExposureSettleFrames", 3).coerceIn(1, 6),
            iso = p.getInt("iso", 400),
            exposureNs = p.getLong("exposureNs", 10_000_000L),
            aperture = p.getString("aperture", null)?.toFloatOrNull(),
            exposureCompensation = p.getInt("exposureCompensation", 0),
            antibandingMode = p.getInt(
                "antibandingMode",
                android.hardware.camera2.CameraCharacteristics.CONTROL_AE_ANTIBANDING_MODE_AUTO,
            ),
            awbMode = p.getInt("awbMode", android.hardware.camera2.CameraCharacteristics.CONTROL_AWB_MODE_AUTO),
            manualWhiteBalance = p.getBoolean("manualWhiteBalance", false),
            whiteBalanceTemperature = p.getInt("whiteBalanceTemperature", 5_500).coerceIn(2_000, 10_000),
            whiteBalanceTint = p.getInt("whiteBalanceTint", 0).coerceIn(-100, 100),
            advancedWhiteBalance = p.getBoolean("advancedWhiteBalance", false),
            splitWhiteBalanceGreen = p.getBoolean("splitWhiteBalanceGreen", false),
            whiteBalanceRedGain = p.getFloat("whiteBalanceRedGain", 1f).coerceIn(1f, 8f),
            whiteBalanceGreenEvenGain = p.getFloat("whiteBalanceGreenEvenGain", 1f).coerceIn(1f, 8f),
            whiteBalanceGreenOddGain = p.getFloat("whiteBalanceGreenOddGain", 1f).coerceIn(1f, 8f),
            whiteBalanceBlueGain = p.getFloat("whiteBalanceBlueGain", 1f).coerceIn(1f, 8f),
            colorCorrectionMode = enumValue(
                p.getString("colorCorrectionMode", null),
                ColorCorrectionMode.TRANSFORM_MATRIX,
            ),
            whiteBalanceTransformMode = enumValue(p.getString("whiteBalanceTransformMode", null), WhiteBalanceTransformMode.LATEST),
            focusMode = enumValue(p.getString("focusMode", null), FocusMode.CONTINUOUS),
            focusDistanceDiopters = p.getFloat("focusDistanceDiopters", 0f).coerceAtLeast(0f),
            touchFocusEnabled = p.getBoolean("touchFocusEnabled", false),
            isoPresets = p.getString("isoPresets", null)
                ?.split(',')?.mapNotNull(String::toIntOrNull)?.filter { it > 0 }?.distinct()
                ?: emptyList(),
            shutterPresets = p.getString("shutterPresets", null)
                ?.split(',')?.map(String::trim)?.filter { parseShutterExposureNs(it) != null }?.distinct()
                ?: emptyList(),
            focusDistancePresets = p.getString("focusDistancePresets", null)
                ?.split(',')?.mapNotNull(::decodeFocusDistancePreset)?.distinct()
                ?: emptyList(),
            unrestrictedIso = p.getBoolean("unrestrictedIso", false),
            unrestrictedExposure = p.getBoolean("unrestrictedExposure", false),
            unrestrictedFocus = p.getBoolean("unrestrictedFocus", false),
            mfAssistMagnifications = p.getString("mfAssistMagnifications", null)
                ?.split(',')?.mapNotNull { it.toIntOrNull() }?.filter { it >= 2 }?.distinct()
                ?.sorted() ?: listOf(2, 4, 8),
            mfAssistMagnification = p.getInt("mfAssistMagnification", 1),
            mfAssistCenterX = p.getFloat("mfAssistCenterX", 0.5f),
            mfAssistCenterY = p.getFloat("mfAssistCenterY", 0.5f),
            opticalStabilization = p.getBoolean("opticalStabilization", true),
            noiseReductionMode = p.getInt("noiseReductionMode", android.hardware.camera2.CameraCharacteristics.NOISE_REDUCTION_MODE_FAST),
            edgeMode = p.getInt("edgeMode", android.hardware.camera2.CameraCharacteristics.EDGE_MODE_FAST),
            hotPixelMode = p.getInt("hotPixelMode", CameraCharacteristics.HOT_PIXEL_MODE_FAST),
            aberrationCorrectionMode = p.getInt(
                "aberrationCorrectionMode",
                CameraCharacteristics.COLOR_CORRECTION_ABERRATION_MODE_FAST,
            ),
            distortionCorrectionMode = p.getInt(
                "distortionCorrectionMode",
                CameraCharacteristics.DISTORTION_CORRECTION_MODE_OFF,
            ),
            httpStreamEnabled = p.getBoolean("httpStreamEnabled", false),
            httpStreamPort = p.getInt("httpStreamPort", 8080).coerceIn(1, 65535),
            httpBufferSeconds = p.getInt("httpBufferSeconds", 30).coerceIn(1, 300),
            httpUploadEnabled = p.getBoolean("httpUploadEnabled", false),
            httpUploadUrl = p.getString("httpUploadUrl", "").orEmpty(),
            httpUploadChunkSeconds = p.getInt("httpUploadChunkSeconds", 1).coerceIn(1, 5),
            httpUploadCacheSeconds = p.getInt("httpUploadCacheSeconds", 60).coerceIn(30, 300),
            rtmpEnabled = p.getBoolean("rtmpEnabled", false),
            rtmpUrl = p.getString("rtmpUrl", "").orEmpty(),
            rtmpBufferMs = p.getInt("rtmpBufferMs", 5_000).coerceIn(100, 30_000),
            rtmpSendTimeoutSeconds = p.getInt("rtmpSendTimeoutSeconds", 10).coerceIn(3, 30),
            rtmpReconnectDelaySeconds = p.getInt("rtmpReconnectDelaySeconds", 3).coerceIn(1, 30),
            outputTreeUri = p.getString("outputTreeUri", null),
        )
    }

    fun save(context: Context, c: RecordingConfig) = save(
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE),
        c,
    )

    internal fun save(p: SharedPreferences, c: RecordingConfig) {
        p.edit()
            .putString("mode", c.mode.name)
            .putString("cameraId", c.cameraId)
            .also { TestCardPreferences.save(it, c.testCard) }
            .putString("usbVideoInputFormat", c.usbVideoInputFormat.name)
            .putBoolean("usbCustomVideoMode", c.usbCustomVideoMode)
            .putInt("usbAudioSampleRate", c.usbAudioSampleRate.coerceAtLeast(0))
            .putString("usbAudioInput", c.usbAudioInput.name)
            .also { UsbAudioDevicePreferences.save(it, c.usbAudioDevice) }
            .putString("uacBitDepth", c.usbAudioBitDepth.name)
            .also { SystemAudioInputPreferences.save(it, c.systemAudioInput) }
            .putInt("usbVideoBufferFrames", c.usbVideoBufferFrames.coerceIn(1, 30))
            .putInt("usbReceiveTransferCount", c.usbReceiveTransferCount.coerceIn(8, 512))
            .putString("usbYuvMatrix", c.usbYuvMatrix.name)
            .putString("usbSourceRange", c.usbSourceRange.name)
            .putBoolean("usbYuvEncoderInput", c.usbYuvEncoderInput)
            .putBoolean("usbTimestampSmoothingEnabled", c.usbTimestampSmoothingEnabled)
            .putBoolean("usbTimestampSmoothingNtscEnabled", c.usbTimestampSmoothingNtscEnabled)
            .putString("usbTimestampSmoothingMaxDeltaSeconds", c.usbTimestampSmoothingMaxDeltaSeconds.toString())
            .putInt("width", c.width)
            .putInt("height", c.height)
            .putBoolean("cropEnabled", c.cropEnabled)
            .putInt("cropWidth", c.cropWidth)
            .putInt("cropHeight", c.cropHeight)
            .putBoolean("resizeEnabled", c.resizeEnabled)
            .putInt("recordWidth", c.recordWidth)
            .putInt("recordHeight", c.recordHeight)
            .putString("scalingAlgorithm", c.scalingAlgorithm.name)
            .putString("fpsExact", c.fps.coerceIn(1.0, 240.0).toString())
            .putBoolean("experimentalCameraAccess", c.experimentalCameraAccess)
            .putBoolean("experimentalUnadvertisedFps", c.experimentalUnadvertisedFps)
            .putBoolean("sensorFrameDurationAutoTune", c.sensorFrameDurationAutoTune)
            .putLong("sensorFrameDurationTuneStepNs", c.sensorFrameDurationTuneStepNs.coerceIn(1_000L, 30_000L))
            .putBoolean("targetPtsCorrectionEnabled", c.targetPtsCorrectionEnabled)
            .putFloat("targetPtsCorrectionMaxFrameDelta", c.targetPtsCorrectionMaxFrameDelta.coerceIn(0.6f, 5f))
            .putBoolean("audioPtsCorrectionEnabled", c.audioPtsCorrectionEnabled)
            .putFloat("audioPtsCorrectionMaxFrameDelta", c.audioPtsCorrectionMaxFrameDelta.coerceIn(0.6f, 5f))
            .putBoolean("mediaCodecMode", c.mediaCodecMode)
            .putBoolean("rawProcessingEnabled", c.rawProcessingEnabled)
            .putInt("rawWidth", c.rawWidth)
            .putInt("rawHeight", c.rawHeight)
            .putString("rawScalingQuality", c.rawScalingQuality.name)
            .putString("rawDemosaicAlgorithm", c.rawDemosaicAlgorithm.name)
            .putBoolean("rawPboEnabled", c.rawPboEnabled)
            .putBoolean("rawColorLutEnabled", c.rawColorLutEnabled)
            .putInt(
                "rawColorLutSize",
                c.rawColorLutSize.takeIf { it in RAW_COLOR_LUT_SIZES } ?: 33,
            )
            .putInt("rawFrameBufferCapacity", c.rawFrameBufferCapacity.coerceIn(1, 6))
            .putBoolean("rawThreeAAuxiliaryYuvEnabled", c.rawThreeAAuxiliaryYuvEnabled)
            .putBoolean("rawLensShadingCorrectionEnabled", c.rawLensShadingCorrectionEnabled)
            .putInt("cameraShadingMode", c.cameraShadingMode)
            .putString("cameraTonemapCurve", c.cameraTonemapCurve.name)
            .putBoolean("rawSharpeningEnabled", c.rawSharpeningEnabled)
            .putFloat("rawSharpeningStrength", c.effectiveRawSharpeningStrength)
            .putString("rawColorStyle", c.rawColorStyle.name)
            .putFloat("rawCustomSaturation", c.rawCustomSaturation.coerceIn(0f, 2f))
            .putBoolean("rawShadowLiftEnabled", c.rawShadowLiftEnabled)
            .putFloat("rawShadowLiftKnee", c.effectiveRawShadowLiftKnee)
            .putFloat("rawShadowLiftTarget", c.effectiveRawShadowLiftTarget)
            .putFloat("rawShadowLiftSmoothness", c.effectiveRawShadowLiftSmoothness)
            .putInt("videoBitrate", c.videoBitrate)
            .putString("videoBitrateMode", c.videoBitrateMode.name)
            .putFloat("videoKeyFrameIntervalSeconds", c.videoKeyFrameIntervalSeconds)
            .putInt("videoMaxBFrames", c.videoMaxBFrames)
            .putInt("audioBitrate", c.audioBitrate)
            .putInt("audioDelayMs", c.audioDelayMs.coerceIn(-500, 500))
            .putInt("muxingQueueSize", c.muxingQueueSize.coerceIn(0, 1024))
            .putString("audioAacProfile", c.audioAacProfile.name)
            .putInt("audioSampleRate", c.audioSampleRate)
            .putInt("audioChannelCount", c.audioChannelCount)
            .putBoolean("audioAutomaticGainControl", c.audioAutomaticGainControl)
            .putBoolean("audioDisableNoiseSuppressor", c.audioDisableNoiseSuppressor)
            .putBoolean("audioDisableEchoCanceler", c.audioDisableEchoCanceler)
            .putString("audioInputSource", c.audioInputSource.name)
            .putBoolean("audioFloatSidecarEnabled", c.audioFloatSidecarEnabled)
            .putInt("audioInputDeviceId", c.audioInputDeviceId ?: -1)
            .putString("videoCodec", c.videoCodec.name)
            .putString("dynamicRange", c.dynamicRange.name)
            .putBoolean("highSpeedMode", c.highSpeedMode)
            .putString("colorRange", c.colorRange.name)
            .putString("colorStandard", c.colorStandard.name)
            .putString("colorTransfer", c.colorTransfer.name)
            .putString("rewriteColorRange", c.rewriteColorRange.name)
            .putString("rewriteColorStandard", c.rewriteColorStandard.name)
            .putString("rewriteColorMatrix", c.rewriteColorMatrix.name)
            .putString("rewriteColorTransfer", c.rewriteColorTransfer.name)
            .putBoolean("forceSpsVui", c.forceSpsVui)
            .putString("container", c.container.name)
            .putInt("segmentMinutes", c.segmentMinutes)
            .putString("orientation", c.orientation.name)
            .putBoolean("rotateImagePixels", c.rotateImagePixels)
            .putString("previewMode", c.previewMode.name)
            .putBoolean("permanentPreviewSurface", c.permanentPreviewSurface)
            .putString("previewLayout", c.previewLayout.name)
            .putInt("previewWidth", c.previewWidth)
            .putInt("previewHeight", c.previewHeight)
            .putString("photoFormat", c.photoFormat.name)
            .putInt("photoJpegQuality", c.photoJpegQuality.coerceIn(1, 100))
            .putBoolean("manualExposure", c.manualExposure)
            .putBoolean("customExposureEnabled", c.customExposureEnabled)
            .putString("customExposureMetering", c.customExposureMetering.name)
            .putFloat("customExposureTarget", c.customExposureTarget.coerceIn(0.02f, 0.95f))
            .putInt("customExposureMinIso", c.customExposureMinIso.coerceAtLeast(1))
            .putInt("customExposureMaxIso", c.customExposureMaxIso.coerceAtLeast(1))
            .putLong("customExposureMinNs", c.customExposureMinNs.coerceAtLeast(1L))
            .putLong("customExposureMaxNs", c.customExposureMaxNs.coerceAtLeast(1L))
            .putFloat("customExposureSpeed", c.customExposureSpeed.coerceIn(0.02f, 1f))
            .putFloat("customExposureDeadbandEv", c.customExposureDeadbandEv.coerceIn(0f, 1f))
            .putInt("customExposureUpdatesPerSecond", c.customExposureUpdatesPerSecond.coerceIn(0, 10))
            .putInt("customExposureSettleFrames", c.customExposureSettleFrames.coerceIn(1, 6))
            .putInt("iso", c.iso)
            .putLong("exposureNs", c.exposureNs)
            .putString("aperture", c.aperture?.toString())
            .putInt("exposureCompensation", c.exposureCompensation)
            .putInt("antibandingMode", c.antibandingMode)
            .putInt("awbMode", c.awbMode)
            .putBoolean("manualWhiteBalance", c.manualWhiteBalance)
            .putInt("whiteBalanceTemperature", c.whiteBalanceTemperature)
            .putInt("whiteBalanceTint", c.whiteBalanceTint)
            .putBoolean("advancedWhiteBalance", c.advancedWhiteBalance)
            .putBoolean("splitWhiteBalanceGreen", c.splitWhiteBalanceGreen)
            .putFloat("whiteBalanceRedGain", c.whiteBalanceRedGain)
            .putFloat("whiteBalanceGreenEvenGain", c.whiteBalanceGreenEvenGain)
            .putFloat("whiteBalanceGreenOddGain", c.whiteBalanceGreenOddGain)
            .putFloat("whiteBalanceBlueGain", c.whiteBalanceBlueGain)
            .putString("colorCorrectionMode", c.colorCorrectionMode.name)
            .putString("whiteBalanceTransformMode", c.whiteBalanceTransformMode.name)
            .putString("focusMode", c.focusMode.name)
            .putFloat("focusDistanceDiopters", c.focusDistanceDiopters)
            .putBoolean("touchFocusEnabled", c.touchFocusEnabled)
            .putString("isoPresets", c.isoPresets.joinToString(","))
            .putString("shutterPresets", c.shutterPresets.joinToString(","))
            .putString(
                "focusDistancePresets",
                c.focusDistancePresets.joinToString(",") { "${it.valueText}:${it.unit.name}" },
            )
            .putBoolean("unrestrictedIso", c.unrestrictedIso)
            .putBoolean("unrestrictedExposure", c.unrestrictedExposure)
            .putBoolean("unrestrictedFocus", c.unrestrictedFocus)
            .putString("mfAssistMagnifications", c.mfAssistMagnifications.joinToString(","))
            .putInt("mfAssistMagnification", c.mfAssistMagnification)
            .putFloat("mfAssistCenterX", c.mfAssistCenterX)
            .putFloat("mfAssistCenterY", c.mfAssistCenterY)
            .putBoolean("opticalStabilization", c.opticalStabilization)
            .putInt("noiseReductionMode", c.noiseReductionMode)
            .putInt("edgeMode", c.edgeMode)
            .putInt("hotPixelMode", c.hotPixelMode)
            .putInt("aberrationCorrectionMode", c.aberrationCorrectionMode)
            .putInt("distortionCorrectionMode", c.distortionCorrectionMode)
            .putBoolean("httpStreamEnabled", c.httpStreamEnabled)
            .putInt("httpStreamPort", c.httpStreamPort.coerceIn(1, 65535))
            .putInt("httpBufferSeconds", c.httpBufferSeconds.coerceIn(1, 300))
            .putBoolean("httpUploadEnabled", c.httpUploadEnabled)
            .putString("httpUploadUrl", c.httpUploadUrl)
            .putInt("httpUploadChunkSeconds", c.httpUploadChunkSeconds.coerceIn(1, 5))
            .putInt("httpUploadCacheSeconds", c.httpUploadCacheSeconds.coerceIn(30, 300))
            .putBoolean("rtmpEnabled", c.rtmpEnabled)
            .putString("rtmpUrl", c.rtmpUrl)
            .putInt("rtmpBufferMs", c.rtmpBufferMs.coerceIn(100, 30_000))
            .putInt("rtmpSendTimeoutSeconds", c.rtmpSendTimeoutSeconds.coerceIn(3, 30))
            .putInt("rtmpReconnectDelaySeconds", c.rtmpReconnectDelaySeconds.coerceIn(1, 30))
            .putString("outputTreeUri", c.outputTreeUri)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private fun videoColorTransfer(value: String?): VideoColorTransfer = when (value) {
        "SDR" -> VideoColorTransfer.BT601
        else -> enumValue(value, VideoColorTransfer.DEFAULT)
    }

    private fun decodeFocusDistancePreset(value: String): FocusDistancePreset? {
        val separator = value.lastIndexOf(':')
        if (separator <= 0) return null
        val text = value.substring(0, separator).trim()
        val unit = enumValue(value.substring(separator + 1), FocusDistanceUnit.M)
        return FocusDistancePreset(text, unit).takeIf {
            parseFocusDistanceDiopters(it.valueText, it.unit) != null
        }
    }
}
