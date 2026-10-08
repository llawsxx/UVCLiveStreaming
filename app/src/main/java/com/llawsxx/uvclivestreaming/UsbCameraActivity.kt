package com.llawsxx.uvclivestreaming

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.llawsxx.uvclivestreaming.recording.ConfigPreferences
import com.llawsxx.uvclivestreaming.recording.validHttpUploadUrl
import com.llawsxx.uvclivestreaming.recording.ContainerFormat
import com.llawsxx.uvclivestreaming.recording.NativeUsbCapture
import com.llawsxx.uvclivestreaming.recording.MjpegDecodePool
import com.llawsxx.uvclivestreaming.recording.MjpegChromaGeometry
import com.llawsxx.uvclivestreaming.recording.RawVideoConverter
import com.llawsxx.uvclivestreaming.recording.encoderLabel
import com.llawsxx.uvclivestreaming.recording.encoderTransferOptions
import com.llawsxx.uvclivestreaming.recording.GpuVideoFrame
import com.llawsxx.uvclivestreaming.recording.GpuVideoRenderer
import com.llawsxx.uvclivestreaming.recording.RecorderController
import com.llawsxx.uvclivestreaming.recording.RecorderState
import com.llawsxx.uvclivestreaming.recording.RecordingConfig
import com.llawsxx.uvclivestreaming.recording.AudioDspSettings
import com.llawsxx.uvclivestreaming.recording.VideoColorGradeSettings
import com.llawsxx.uvclivestreaming.recording.UsbAudioPipeline
import com.llawsxx.uvclivestreaming.recording.RecordingMode
import com.llawsxx.uvclivestreaming.recording.UsbCaptureCallback
import com.llawsxx.uvclivestreaming.recording.CapturedVideoBuffer
import com.llawsxx.uvclivestreaming.recording.UsbYuvMatrix
import com.llawsxx.uvclivestreaming.recording.UsbSourceRange
import com.llawsxx.uvclivestreaming.recording.UsbRecorderEngine
import com.llawsxx.uvclivestreaming.recording.UsbReceiveRate
import com.llawsxx.uvclivestreaming.recording.UsbAudioMonitor
import com.llawsxx.uvclivestreaming.recording.UsbVideoInputFormat
import com.llawsxx.uvclivestreaming.recording.UsbAudioDevice
import com.llawsxx.uvclivestreaming.recording.UsbAudioBitDepth
import com.llawsxx.uvclivestreaming.recording.UsbUacCapture
import com.llawsxx.uvclivestreaming.recording.usbAudioInputDevices
import com.llawsxx.uvclivestreaming.recording.resolveUsbAudioDevice
import com.llawsxx.uvclivestreaming.recording.UsbAudioInput
import com.llawsxx.uvclivestreaming.recording.AudioInputSource
import com.llawsxx.uvclivestreaming.recording.SystemAudioInputSettings
import com.llawsxx.uvclivestreaming.recording.SystemAudioDevice
import com.llawsxx.uvclivestreaming.recording.SystemAudioCapture
import com.llawsxx.uvclivestreaming.recording.resolveSystemAudioDevice
import com.llawsxx.uvclivestreaming.recording.TestCardSettings
import com.llawsxx.uvclivestreaming.recording.TestCardPattern
import com.llawsxx.uvclivestreaming.recording.runTestCardFrames
import com.llawsxx.uvclivestreaming.recording.videoSmoothingFrameRate
import com.llawsxx.uvclivestreaming.recording.VideoBitrateMode
import com.llawsxx.uvclivestreaming.recording.VideoCodec
import com.llawsxx.uvclivestreaming.recording.VideoColorRange
import com.llawsxx.uvclivestreaming.recording.VideoColorStandard
import com.llawsxx.uvclivestreaming.recording.VideoColorMatrix
import com.llawsxx.uvclivestreaming.recording.VideoColorTransfer
import com.llawsxx.uvclivestreaming.recording.usbPcmLevelDb
import com.llawsxx.uvclivestreaming.ui.theme.UVCLiveStreamingTheme
import java.util.concurrent.CountDownLatch
import java.io.Serializable
import java.util.Locale
import kotlinx.coroutines.delay
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Semaphore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

class UsbCameraActivity : ComponentActivity() {
    fun setUsbFullscreen(landscape: Boolean) {
        window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    fun exitUsbFullscreen() {
        window.decorView.systemUiVisibility = 0
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (UsbUiPreferences.load(this).keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        setContent {
            UVCLiveStreamingTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    UsbCameraScreen()
                }
            }
        }
    }
}

private enum class UsbAction { NONE, PREVIEW, RECORD, STREAM, RTMP }
private enum class UsbStopAction(val label: String) {
    RECORDING("录像"), HTTP("HTTP 串流"), RTMP("RTMP 推流")
}

private val usbPreviewGate = Semaphore(1)

private data class UsbVideoMode(
    val label: String, val width: Int, val height: Int, val fps: Double,
    val inputFormat: UsbVideoInputFormat?, val detail: String,
    val custom: Boolean = false,
) : Serializable {
    val canRecord: Boolean get() = inputFormat != null && width in 1..3840 &&
        height in 1..2160 && fps.isFinite() && fps in 1.0..240.0
    val display: String get() = "$label · ${width}×$height · ${fps.toString().removeSuffix(".0")} fps" +
        (if (detail.isBlank()) "" else " ($detail)") +
        (if (canRecord) "" else " · 暂不可录制")
}

private fun parseUsbVideoMode(raw: String): UsbVideoMode? {
    val parts = raw.split('|')
    if (parts.size != 6) return null
    val value = parts[4].toIntOrNull()
    return UsbVideoMode(parts[0], parts[1].toIntOrNull() ?: return null,
        parts[2].toIntOrNull() ?: return null, parts[3].toDoubleOrNull() ?: return null,
        UsbVideoInputFormat.entries.firstOrNull { it.nativeValue == value }, parts[5])
}

private fun UsbCustomVideoMode.asVideoMode() =
    UsbVideoMode(format.label, width, height, fps, format, "自定义", custom = true)

@Composable
private fun UsbCameraScreen() {
    val context = LocalContext.current
    val activity = context as? UsbCameraActivity
    val uiSettings = remember { UsbUiPreferences.load(context) }
    val manager = remember { context.getSystemService(UsbManager::class.java) }
    val state by RecorderController.state.collectAsState()
    val recording = state is RecorderState.Starting || state is RecorderState.Recording || state is RecorderState.Stopping
    val streaming = (state as? RecorderState.Recording)?.stats?.let { it.httpStreaming || it.rtmpStreaming } == true
    val fileRecording = (state as? RecorderState.Recording)?.stats?.fileRecording == true
    val httpStreaming = (state as? RecorderState.Recording)?.stats?.httpStreaming == true
    val rtmpStreaming = (state as? RecorderState.Recording)?.stats?.rtmpStreaming == true
    val outputControlsEnabled = state !is RecorderState.Starting && state !is RecorderState.Stopping &&
        (state as? RecorderState.Recording)?.stats?.outputChangePending != true
    var devices by remember { mutableStateOf(usbVideoDevices(manager)) }
    var selectedName by rememberSaveable { mutableStateOf(uiSettings.selectedDeviceName?.takeIf { name ->
        name == TestCardSettings.DEVICE_ID || devices.any { it.deviceName == name }
    } ?: devices.firstOrNull()?.deviceName ?: TestCardSettings.DEVICE_ID) }
    val selected = devices.firstOrNull { it.deviceName == selectedName }
    val testCardSelected = selectedName == TestCardSettings.DEVICE_ID
    val hasVideoInput = selected != null || testCardSelected
    var testCardSettings by rememberSaveable { mutableStateOf(uiSettings.testCard) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var surfaceRevision by remember { mutableStateOf(0) }
    var idlePreview by remember { mutableStateOf<UsbIdlePreview?>(null) }
    var pendingAction by remember { mutableStateOf(UsbAction.NONE) }
    var pendingStop by remember { mutableStateOf<UsbStopAction?>(null) }
    var confirmStopOutputs by rememberSaveable { mutableStateOf(uiSettings.confirmStopOutputs) }

    fun stopOutput(target: UsbStopAction) {
        val stats = (RecorderController.state.value as? RecorderState.Recording)?.stats ?: return
        if (stats.outputChangePending) return
        when (target) {
            UsbStopAction.RECORDING -> if (stats.fileRecording) RecorderController.stopRecording(context)
            UsbStopAction.HTTP -> if (stats.httpStreaming) RecorderController.stopHttpOutput(context)
            UsbStopAction.RTMP -> if (stats.rtmpStreaming) RecorderController.stopRtmpOutput(context)
        }
    }

    fun requestStop(target: UsbStopAction) {
        if (confirmStopOutputs) pendingStop = target else stopOutput(target)
    }
    // Do not issue repeated requests while the system USB service is deciding
    // (some vendor builds return permission=false without showing the dialog).
    var usbPermissionRequestPending by remember { mutableStateOf(false) }
    var usbPermissionEpoch by remember { mutableStateOf(0) }
    var runtimePermissionEpoch by remember { mutableStateOf(0) }
    var includeAudio by rememberSaveable { mutableStateOf(uiSettings.includeAudio) }
    var audioInput by rememberSaveable { mutableStateOf(uiSettings.audioInput) }
    var systemAudioInput by rememberSaveable { mutableStateOf(uiSettings.systemAudioInput) }
    val systemAudioDevices = rememberSystemAudioInputDevices()
    var uacDevices by remember { mutableStateOf(usbAudioInputDevices(manager)) }
    var uacDevice by rememberSaveable { mutableStateOf(uiSettings.usbAudioDevice) }
    var uacBitDepth by rememberSaveable { mutableStateOf(uiSettings.usbAudioBitDepth) }
    val resolvedUacDevice = uacDevice?.let { resolveUsbAudioDevice(it, uacDevices) }
    val uacNeedsCameraPermission = audioInput == UsbAudioInput.USB && includeAudio && resolvedUacDevice?.let { audio ->
        manager.deviceList[audio.deviceName]?.let { device -> (0 until device.interfaceCount).any {
            device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO
        } } == true
    } == true
    val captureAudioEnabled = includeAudio && (!testCardSelected || audioInput == UsbAudioInput.SYSTEM || uacDevice != null)
    var audioPreviewEnabled by rememberSaveable { mutableStateOf(uiSettings.audioPreviewEnabled) }
    var audioDsp by remember { mutableStateOf(uiSettings.audioDsp) }
    var videoColorGrade by remember { mutableStateOf(uiSettings.videoColorGrade) }
    var previewEnabled by rememberSaveable { mutableStateOf(uiSettings.previewEnabled) }
    var lowFrameRatePreview by rememberSaveable { mutableStateOf(uiSettings.lowFrameRatePreview) }
    var keepScreenOn by rememberSaveable { mutableStateOf(uiSettings.keepScreenOn) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var container by rememberSaveable { mutableStateOf(uiSettings.container) }
    var httpUploadEnabled by rememberSaveable { mutableStateOf(uiSettings.httpUploadEnabled) }
    var httpUploadUrl by rememberSaveable { mutableStateOf(uiSettings.httpUploadUrl) }
    var httpUploadChunkSeconds by rememberSaveable { mutableStateOf(uiSettings.httpUploadChunkSeconds) }
    var httpUploadCacheSeconds by rememberSaveable { mutableStateOf(uiSettings.httpUploadCacheSeconds) }
    var rtmpUrl by rememberSaveable { mutableStateOf(uiSettings.rtmpUrl) }
    var rtmpBufferMs by rememberSaveable { mutableStateOf(uiSettings.rtmpBufferMs) }
    var rtmpSendTimeoutSeconds by rememberSaveable { mutableStateOf(uiSettings.rtmpSendTimeoutSeconds) }
    var videoBitrateKbps by rememberSaveable { mutableStateOf(uiSettings.videoBitrateKbps) }
    var audioBitrateKbps by rememberSaveable { mutableStateOf(uiSettings.audioBitrateKbps) }
    val audioBitrateValue = audioBitrateKbps.toIntOrNull()?.takeIf { it in 16..512 }
    var audioDelayMs by rememberSaveable { mutableStateOf(uiSettings.audioDelayMs) }
    val audioDelayValue = audioDelayMs.toIntOrNull()?.takeIf { it in -500..500 }
    var muxingQueueSize by rememberSaveable { mutableStateOf(uiSettings.muxingQueueSize) }
    val muxingQueueValue = muxingQueueSize.toIntOrNull()?.takeIf { it in 0..1024 }
    var gopSeconds by rememberSaveable { mutableStateOf(uiSettings.gopSeconds) }
    val gopSecondsValue = gopSeconds.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..30f }
    var bFrames by rememberSaveable { mutableStateOf(uiSettings.bFrames) }
    var videoCodec by rememberSaveable { mutableStateOf(uiSettings.videoCodec) }
    var bitrateMode by rememberSaveable { mutableStateOf(uiSettings.bitrateMode) }
    var yuvMatrix by rememberSaveable { mutableStateOf(uiSettings.yuvMatrix) }
    var sourceRange by rememberSaveable { mutableStateOf(uiSettings.sourceRange) }
    var encoderColorStandard by rememberSaveable { mutableStateOf(uiSettings.encoderColorStandard) }
    var encoderColorTransfer by rememberSaveable { mutableStateOf(uiSettings.encoderColorTransfer) }
    var encoderColorRange by rememberSaveable { mutableStateOf(uiSettings.encoderColorRange) }
    var forceSpsVui by rememberSaveable { mutableStateOf(uiSettings.forceSpsVui) }
    var rewriteColorRange by rememberSaveable { mutableStateOf(uiSettings.rewriteColorRange) }
    var rewriteColorStandard by rememberSaveable { mutableStateOf(uiSettings.rewriteColorStandard) }
    var rewriteColorMatrix by rememberSaveable { mutableStateOf(uiSettings.rewriteColorMatrix) }
    var rewriteColorTransfer by rememberSaveable { mutableStateOf(uiSettings.rewriteColorTransfer) }
    var modes by remember { mutableStateOf<List<UsbVideoMode>>(emptyList()) }
    var customVideoMode by rememberSaveable { mutableStateOf(uiSettings.customVideoMode) }
    var customModeDialog by remember { mutableStateOf(false) }
    var selectedMode by rememberSaveable {
        mutableStateOf<UsbVideoMode?>(uiSettings.customVideoMode.takeIf { uiSettings.customVideoModeSelected && it.valid }?.asVideoMode())
    }
    val effectiveMode = if (testCardSelected) UsbVideoMode("RGB", testCardSettings.width, testCardSettings.height,
        testCardSettings.fps, UsbVideoInputFormat.RGB, "测试卡") else selectedMode
    var audioRate by rememberSaveable { mutableStateOf(uiSettings.audioRate) }
    var bufferFrames by rememberSaveable { mutableStateOf(uiSettings.bufferFrames) }
    var receiveTransferCount by rememberSaveable { mutableStateOf(uiSettings.receiveTransferCount) }
    var previewRequestRevision by remember { mutableStateOf(0L) }
    var timestampSmoothingEnabled by rememberSaveable { mutableStateOf(uiSettings.timestampSmoothingEnabled) }
    var timestampSmoothingNtscEnabled by rememberSaveable { mutableStateOf(uiSettings.timestampSmoothingNtscEnabled) }
    var timestampSmoothingMaxDeltaSeconds by rememberSaveable { mutableStateOf(uiSettings.timestampSmoothingMaxDeltaSeconds) }
    val timestampSmoothingDelta = timestampSmoothingMaxDeltaSeconds.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0 }
    var previewRequested by rememberSaveable { mutableStateOf(false) }
    var foregroundEpoch by remember { mutableStateOf(0) }
    var idleAudioLevelDb by remember { mutableStateOf(-60f) }
    var recentAudioPeakDb by remember { mutableStateOf(-60f) }
    var idleUsbReceiveRate by remember { mutableStateOf<Double?>(null) }
    val recordingAudioLevelDb by RecorderController.usbAudioLevelDb.collectAsState()
    var modesReadyKey by remember { mutableStateOf<Pair<String?, Int>?>(null) }
    var message by remember { mutableStateOf("选择 USB 摄像头并授权后即可预览或录像") }
    var devicesExpanded by remember { mutableStateOf(false) }
    var modesExpanded by remember { mutableStateOf(false) }
    val audioRates = listOf(0, 16_000, 32_000, 44_100, 48_000, 96_000)
    val bufferOptions = (1..30).toList()

    LaunchedEffect(keepScreenOn) {
        activity?.window?.let { window ->
            if (keepScreenOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    DisposableEffect(activity) {
        val owner = activity ?: return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    idlePreview?.updateSurface(null)
                    RecorderController.attachPreview(null, selectedMode?.width ?: 1280,
                        selectedMode?.height ?: 720, enabled = false)
                }
                Lifecycle.Event.ON_RESUME -> foregroundEpoch++
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(
        selectedName, selectedMode?.display, customVideoMode, testCardSettings, includeAudio, audioInput, uacDevice, uacBitDepth, systemAudioInput, audioPreviewEnabled, audioDsp, videoColorGrade, previewEnabled, lowFrameRatePreview, keepScreenOn,
        container, confirmStopOutputs, httpUploadEnabled, httpUploadUrl, httpUploadChunkSeconds, httpUploadCacheSeconds,
        rtmpUrl, rtmpBufferMs, rtmpSendTimeoutSeconds, videoBitrateKbps, audioBitrateKbps, audioDelayMs, muxingQueueSize, gopSeconds, bFrames, videoCodec, bitrateMode, audioRate,
        bufferFrames, receiveTransferCount, yuvMatrix, sourceRange, timestampSmoothingEnabled, timestampSmoothingNtscEnabled,
        encoderColorStandard, encoderColorTransfer, encoderColorRange,
        timestampSmoothingMaxDeltaSeconds,
        forceSpsVui, rewriteColorRange, rewriteColorStandard, rewriteColorMatrix, rewriteColorTransfer,
    ) {
        UsbUiPreferences.save(context, UsbUiSettings(
            selectedDeviceName = selectedName,
            testCard = testCardSettings,
            selectedModeDisplay = selectedMode?.display,
            customVideoMode = customVideoMode,
            customVideoModeSelected = selectedMode?.custom == true,
            includeAudio = includeAudio,
            audioInput = audioInput,
            usbAudioDevice = uacDevice,
            usbAudioBitDepth = uacBitDepth,
            systemAudioInput = systemAudioInput,
            audioPreviewEnabled = audioPreviewEnabled,
            audioDsp = audioDsp,
            videoColorGrade = videoColorGrade,
            previewEnabled = previewEnabled,
            lowFrameRatePreview = lowFrameRatePreview,
            container = container,
            confirmStopOutputs = confirmStopOutputs,
            httpUploadEnabled = httpUploadEnabled,
            httpUploadUrl = httpUploadUrl.trim(),
            httpUploadChunkSeconds = httpUploadChunkSeconds,
            httpUploadCacheSeconds = httpUploadCacheSeconds,
            rtmpUrl = rtmpUrl,
            rtmpBufferMs = rtmpBufferMs,
            rtmpSendTimeoutSeconds = rtmpSendTimeoutSeconds,
            videoBitrateKbps = videoBitrateKbps,
            audioBitrateKbps = audioBitrateKbps,
            audioDelayMs = audioDelayMs,
            muxingQueueSize = muxingQueueSize,
            gopSeconds = gopSeconds,
            bFrames = bFrames,
            videoCodec = videoCodec,
            bitrateMode = bitrateMode,
            audioRate = audioRate,
            bufferFrames = bufferFrames,
            receiveTransferCount = receiveTransferCount,
            yuvMatrix = yuvMatrix,
            sourceRange = sourceRange,
            encoderColorStandard = encoderColorStandard,
            encoderColorTransfer = encoderColorTransfer,
            encoderColorRange = encoderColorRange,
            forceSpsVui = forceSpsVui,
            rewriteColorRange = rewriteColorRange,
            rewriteColorStandard = rewriteColorStandard,
            rewriteColorMatrix = rewriteColorMatrix,
            rewriteColorTransfer = rewriteColorTransfer,
            timestampSmoothingEnabled = timestampSmoothingEnabled,
            timestampSmoothingNtscEnabled = timestampSmoothingNtscEnabled,
            timestampSmoothingMaxDeltaSeconds = timestampSmoothingMaxDeltaSeconds,
            keepScreenOn = keepScreenOn,
        ))
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        runtimePermissionEpoch++
        val cameraGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!cameraGranted && (!testCardSelected || uacNeedsCameraPermission)) {
            pendingAction = UsbAction.NONE
            previewRequested = false
            message = "未获得相机权限，无法录像或串流"
        } else if (includeAudio && it.containsKey(Manifest.permission.RECORD_AUDIO) &&
            it[Manifest.permission.RECORD_AUDIO] != true
        ) {
            // 音频是可选通道：拒绝麦克风权限时仍允许视频继续，
            // 同时关闭音频，避免录像/串流整个流程被中止。
            includeAudio = false
            message = "未获得麦克风权限，已关闭音频，继续视频"
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    USB_PERMISSION_ACTION -> {
                        usbPermissionRequestPending = false
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            usbPermissionEpoch++
                        } else {
                            pendingAction = UsbAction.NONE
                            // Stop the auto-preview effect from immediately
                            // requesting again after an explicit denial.
                            previewRequested = false
                            message = "USB 设备访问权限被拒绝，请重新点击预览授权"
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        devices = usbVideoDevices(manager)
                        uacDevices = usbAudioInputDevices(manager)
                        if (includeAudio && audioInput == UsbAudioInput.USB && uacDevice != null &&
                            resolveUsbAudioDevice(checkNotNull(uacDevice), uacDevices) == null) {
                            idlePreview?.stop(); idlePreview = null; previewRequested = false
                            pendingAction = UsbAction.NONE
                            if (RecorderController.state.value is RecorderState.Recording ||
                                RecorderController.state.value is RecorderState.Starting) RecorderController.stop(context)
                            message = "所选 USB 音频设备已断开，请重新连接或选择音频设备"
                        }
                        if (selectedName != TestCardSettings.DEVICE_ID && devices.none { it.deviceName == selectedName }) {
                            idlePreview?.stop()
                            idlePreview = null
                            if (RecorderController.state.value is RecorderState.Recording ||
                                RecorderController.state.value is RecorderState.Starting
                            ) RecorderController.stop(context)
                            selectedName = devices.firstOrNull()?.deviceName ?: TestCardSettings.DEVICE_ID
                            selectedMode = null
                            previewRequested = false
                            usbPermissionRequestPending = false
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(USB_PERMISSION_ACTION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // The PendingIntent is package-scoped and the result is delivered back
        // to this app. Keep the receiver private; exporting it can make some
        // vendor USB permission managers reject the request outright.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            context.unregisterReceiver(receiver)
            idlePreview?.stop()
            RecorderController.attachPreview(null)
        }
    }

    LaunchedEffect(selectedName, usbPermissionEpoch) {
        val scanKey = selectedName to usbPermissionEpoch
        modesReadyKey = null
        modes = emptyList()
        if (testCardSelected) {
            modes = listOf(Triple(720,480,60.0), Triple(1280,720,30.0), Triple(1280,720,60.0),
                Triple(1920,1080,30.0), Triple(1920,1080,60.0), Triple(1920,1080,59.94),
                Triple(1920,1080,29.97), Triple(3840,2160,30.0), Triple(3840,2160,60.0)).map { (w,h,fps) ->
                UsbVideoMode("RGB", w,h,fps,UsbVideoInputFormat.RGB,"测试卡")
            }
            modesReadyKey = scanKey
            return@LaunchedEffect
        }
        val device = selected
        if (device == null || !manager.hasPermission(device)) {
            modesReadyKey = scanKey
            return@LaunchedEffect
        }
        try {
            val found = withContext(Dispatchers.IO) {
                usbPreviewGate.acquire()
                try {
                val connection = checkNotNull(manager.openDevice(device)) { "无法打开 USB 摄像头" }
                try { NativeUsbCapture.nativeListVideoModes(connection.fileDescriptor)
                    .mapNotNull(::parseUsbVideoMode).distinct() }
                finally { connection.close() }
                } finally { usbPreviewGate.release() }
            }
            modes = found
            if (selectedMode == null && uiSettings.selectedModeDisplay != null) {
                selectedMode = found.firstOrNull { it.display == uiSettings.selectedModeDisplay }
            }
            if (selectedMode != null && selectedMode?.custom != true && selectedMode !in found) selectedMode = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            message = "读取 USB 视频格式失败：${error.message}"
        } finally {
            modesReadyKey = scanKey
        }
    }

    LaunchedEffect(surface, surfaceRevision, recording, previewEnabled, lowFrameRatePreview, foregroundEpoch) {
        idlePreview?.updateSurface(surface)
        RecorderController.attachPreview(surface, effectiveMode?.width ?: 1280, effectiveMode?.height ?: 720,
            enabled = recording && previewEnabled && surface?.isValid == true, lowFrameRate = lowFrameRatePreview)
    }

    LaunchedEffect(idlePreview, yuvMatrix, sourceRange) {
        idlePreview?.updateColorSettings(yuvMatrix, sourceRange)
    }

    LaunchedEffect(idlePreview, lowFrameRatePreview) {
        idlePreview?.updateLowFrameRate(lowFrameRatePreview)
    }

    LaunchedEffect(audioPreviewEnabled, includeAudio, idlePreview) {
        val enabled = audioPreviewEnabled && includeAudio
        RecorderController.updateUsbAudioPreview(enabled)
        idlePreview?.updateAudioPreview(enabled)
    }

    LaunchedEffect(includeAudio, audioInput, systemAudioInput, audioRate, uacDevice, uacBitDepth) {
        if (previewRequested && !recording && idlePreview != null && pendingAction == UsbAction.NONE)
            pendingAction = UsbAction.PREVIEW
    }

    LaunchedEffect(testCardSettings, timestampSmoothingNtscEnabled) {
        if (testCardSelected && previewRequested && !recording && idlePreview != null && pendingAction == UsbAction.NONE)
            pendingAction = UsbAction.PREVIEW
    }
    LaunchedEffect(uacDevices) {
        if (!recording) uacDevice?.let { saved ->
            resolveUsbAudioDevice(saved, uacDevices)?.takeIf { it != saved }?.let { uacDevice = it }
        }
    }
    LaunchedEffect(systemAudioDevices) {
        if (!recording) systemAudioInput.device?.let { selectedDevice ->
            resolveSystemAudioDevice(selectedDevice, systemAudioDevices)?.takeIf { it != selectedDevice }?.let {
                systemAudioInput = systemAudioInput.copy(device = it)
            }
        }
    }

    LaunchedEffect(audioDsp, idlePreview) {
        RecorderController.updateUsbAudioDsp(audioDsp)
        idlePreview?.updateAudioDsp(audioDsp)
    }
    LaunchedEffect(videoColorGrade, idlePreview) {
        RecorderController.updateUsbColorGrade(videoColorGrade)
        idlePreview?.updateColorGrade(videoColorGrade)
    }

    LaunchedEffect(idlePreview, recording, includeAudio) {
        recentAudioPeakDb = -60f
        if (!includeAudio) return@LaunchedEffect
        while (true) {
            recentAudioPeakDb = if (recording) RecorderController.usbAudioRecentPeakDb()
                else idlePreview?.recentAudioPeakDb() ?: -60f
            delay(100)
        }
    }

    LaunchedEffect(idlePreview, recording) {
        idleUsbReceiveRate = null
        val preview = idlePreview?.takeIf { !recording } ?: return@LaunchedEffect
        while (true) {
            idleUsbReceiveRate = preview.usbVideoReceiveBitsPerSecond
            delay(1_000)
        }
    }

    LaunchedEffect(surface, recording, previewRequested, modesReadyKey) {
        if (previewRequested && !recording && surface?.isValid == true && idlePreview == null &&
            pendingAction == UsbAction.NONE && modesReadyKey == (selectedName to usbPermissionEpoch)) {
            pendingAction = UsbAction.PREVIEW
        }
    }

    LaunchedEffect(pendingAction, selectedName, usbPermissionEpoch, runtimePermissionEpoch, modesReadyKey, uacDevices) {
        if (pendingAction == UsbAction.NONE) return@LaunchedEffect
        val device = selected
        if (device == null && !testCardSelected) {
            pendingAction = UsbAction.NONE
            message = "请选择 USB 摄像头或测试卡"
            return@LaunchedEffect
        }
        val audioDevice = if (captureAudioEnabled && audioInput == UsbAudioInput.USB && uacDevice != null) {
            val resolved = resolveUsbAudioDevice(checkNotNull(uacDevice), usbAudioInputDevices(manager))
            val found = resolved?.let { manager.deviceList[it.deviceName] }
            if (found == null) {
                pendingAction = UsbAction.NONE; previewRequested = false
                message = "所选 USB 音频设备不可用，请重新连接或选择设备"
                return@LaunchedEffect
            }
            found
        } else null
        val captureUacDevice = audioDevice?.let(UsbAudioDevice::from)
        // Android P+ requires CAMERA runtime permission before USB permission
        // can be granted for a USB video-class device. Request it first;
        // requesting USB permission before CAMERA yields permission=false with
        // no dialog on several devices.
        if (pendingAction == UsbAction.PREVIEW ||
            pendingAction == UsbAction.RECORD || pendingAction == UsbAction.STREAM ||
            pendingAction == UsbAction.RTMP
        ) {
            val needed = buildList {
                if (!testCardSelected || uacNeedsCameraPermission) add(Manifest.permission.CAMERA)
                if (captureAudioEnabled && (pendingAction != UsbAction.PREVIEW || audioInput == UsbAudioInput.SYSTEM)) {
                    add(Manifest.permission.RECORD_AUDIO)
                }
                if (pendingAction != UsbAction.PREVIEW && Build.VERSION.SDK_INT >= 33) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isNotEmpty()) {
                permissionLauncher.launch(needed.toTypedArray())
                return@LaunchedEffect
            }
        }
        val permissionDevice = listOfNotNull(device, audioDevice).distinctBy { it.deviceName }
            .firstOrNull { !manager.hasPermission(it) }
        if (permissionDevice != null) {
            if (usbPermissionRequestPending) return@LaunchedEffect
            usbPermissionRequestPending = true
            // The USB service appends EXTRA_DEVICE and
            // EXTRA_PERMISSION_GRANTED to this PendingIntent, so it must be
            // mutable on Android 12+.
            val intent = Intent(USB_PERMISSION_ACTION).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
            val requestCode = permissionDevice.deviceId.coerceAtLeast(1)
            manager.requestPermission(permissionDevice, PendingIntent.getBroadcast(context, requestCode, intent, flags))
            return@LaunchedEffect
        }
        usbPermissionRequestPending = false
        if (modesReadyKey != (selectedName to usbPermissionEpoch)) return@LaunchedEffect
        val requested = pendingAction
        pendingAction = UsbAction.NONE
        if (requested == UsbAction.PREVIEW) {
            val requestRevision = ++previewRequestRevision
            val target = surface?.takeIf { it.isValid }
            if (target == null) {
                message = "预览画面尚未准备好"
            } else {
                val previous = idlePreview
                idlePreview = null
                val width = effectiveMode?.width ?: 1280
                val height = effectiveMode?.height ?: 720
                val fps = effectiveMode?.fps ?: 30.0
                val videoFormat = effectiveMode?.inputFormat ?: UsbVideoInputFormat.AUTO
                val beginPreview: () -> Unit = {
                    if (previewRequested && requestRevision == previewRequestRevision &&
                        selectedName == (device?.deviceName ?: TestCardSettings.DEVICE_ID) && !recording && target.isValid) {
                        UsbIdlePreview(context, manager, device, target, width, height, fps, videoFormat,
                            bufferFrames, captureAudioEnabled, audioRate, yuvMatrix, sourceRange, lowFrameRatePreview,
                            testCard = testCardSettings.takeIf { testCardSelected }?.copy(
                                fps = videoSmoothingFrameRate(fps, timestampSmoothingNtscEnabled)),
                            audioInput = audioInput, systemAudioInput = systemAudioInput,
                            uacDevice = captureUacDevice, uacBitDepth = uacBitDepth,
                            receiveTransferCount = receiveTransferCount,
                            initialAudioDsp = audioDsp,
                            initialColorGrade = videoColorGrade,
                            customVideoMode = selectedMode?.custom == true,
                            onMessage = { if (previewRequested) message = it },
                            onAudioLevel = { if (previewRequested) idleAudioLevelDb = it }).also {
                            idlePreview = it
                            it.start()
                        }
                    }
                }
                previous?.stop(beginPreview) ?: beginPreview()
            }
        } else {
            if (gopSecondsValue == null) {
                message = "GOP 请输入 0～30 秒，可使用小数；0 表示每帧关键帧"
                return@LaunchedEffect
            }
            if (includeAudio && audioBitrateValue == null) {
                message = "音频码率请输入 16～512 kbps 的整数"
                return@LaunchedEffect
            }
            if (includeAudio && audioDelayValue == null) {
                message = "音频延迟请输入 -500～500 毫秒的整数，负数提前、正数延迟"
                return@LaunchedEffect
            }
            if (muxingQueueValue == null) {
                message = "封装排序缓存请输入 0～1024 个包的整数"
                return@LaunchedEffect
            }
            if (timestampSmoothingEnabled && timestampSmoothingDelta == null) {
                message = "时间戳平滑最大偏差请输入大于或等于 0 的秒数"
                return@LaunchedEffect
            }
            previewRequested = false
            val previous = idlePreview
            idlePreview = null
            val config = usbRecordingConfig(
                context, device, effectiveMode, testCardSettings, captureAudioEnabled, audioInput, systemAudioInput, captureUacDevice, uacBitDepth, audioRate, bufferFrames, receiveTransferCount, container,
                requested == UsbAction.STREAM, httpUploadEnabled, httpUploadUrl.trim(), httpUploadChunkSeconds,
                httpUploadCacheSeconds, requested == UsbAction.RTMP, rtmpUrl, rtmpBufferMs, rtmpSendTimeoutSeconds,
                videoCodec, bitrateMode,
                (videoBitrateKbps.toIntOrNull()?.coerceIn(100, 100_000) ?: 12_000) * 1_000,
                (audioBitrateValue ?: 192) * 1_000,
                audioDelayValue ?: 0, muxingQueueValue,
                gopSecondsValue,
                if (gopSecondsValue == 0f) 0 else bFrames.toIntOrNull()?.coerceIn(0, 4) ?: 0,
                timestampSmoothingEnabled, timestampSmoothingNtscEnabled, timestampSmoothingDelta ?: 0.1,
                yuvMatrix, sourceRange,
                encoderColorStandard, encoderColorTransfer, encoderColorRange,
                forceSpsVui, rewriteColorRange, rewriteColorStandard, rewriteColorMatrix, rewriteColorTransfer,
            )
            ConfigPreferences.save(context, config)
            previous?.stop {
                if (requested == UsbAction.STREAM) RecorderController.startHttp(context, config)
                else RecorderController.start(context, config)
            } ?: if (requested == UsbAction.STREAM) RecorderController.startHttp(context, config)
                else RecorderController.start(context, config)
        }
    }

    if (customModeDialog) UsbCustomVideoModeDialog(if (testCardSelected) UsbCustomVideoMode(
        testCardSettings.width, testCardSettings.height, testCardSettings.fps, UsbVideoInputFormat.RGB) else customVideoMode,
        onDismiss = { customModeDialog = false },
        onApply = {
            if (testCardSelected) testCardSettings = testCardSettings.copy(width = it.width, height = it.height, fps = it.fps)
            else { customVideoMode = it; selectedMode = it.asVideoMode() }
            customModeDialog = false
            if (previewRequested && !recording) pendingAction = UsbAction.PREVIEW
        }, virtual = testCardSelected)
    val stopTarget = pendingStop
    val stopTargetActive = when (stopTarget) {
        UsbStopAction.RECORDING -> fileRecording
        UsbStopAction.HTTP -> httpStreaming
        UsbStopAction.RTMP -> rtmpStreaming
        null -> false
    }
    LaunchedEffect(stopTarget, stopTargetActive) {
        if (!stopTargetActive) pendingStop = null
    }
    if (stopTarget != null && stopTargetActive) {
        AlertDialog(
            onDismissRequest = { pendingStop = null },
            title = { Text("停止${stopTarget.label}") },
            text = { Text("确定要停止${stopTarget.label}吗？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingStop = null
                    stopOutput(stopTarget)
                }, enabled = outputControlsEnabled) { Text("确认停止") }
            },
            dismissButton = {
                TextButton(onClick = { pendingStop = null }) { Text("取消") }
            },
        )
    }
    BackHandler(enabled = fullscreen) { fullscreen = false; activity?.exitUsbFullscreen() }
    UsbCameraWorkspace(
        aspectRatio = (effectiveMode?.width ?: 1280).toFloat() / (effectiveMode?.height ?: 720),
        includeAudio = captureAudioEnabled,
        fullscreen = fullscreen,
        onExitFullscreen = { fullscreen = false; activity?.exitUsbFullscreen() },
        lowFrameRatePreview = lowFrameRatePreview,
        onLowFrameRatePreviewChange = { lowFrameRatePreview = it },
        preview = { modifier, onTap ->
            AndroidView(
                factory = { viewContext -> SurfaceView(viewContext).apply {
                    setOnClickListener { onTap() }
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) { surface = holder.surface; surfaceRevision++ }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            surface = holder.surface; surfaceRevision++
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) { if (surface === holder.surface) surface = null }
                    })
                } },
                modifier = modifier,
            )
        },
        fullscreenControls = {
            Button(onClick = { fullscreen = true; activity?.setUsbFullscreen(false) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = .65f),
                    contentColor = androidx.compose.ui.graphics.Color.White)) { Text("竖屏全屏", style = MaterialTheme.typography.labelMedium) }
            Button(onClick = { fullscreen = true; activity?.setUsbFullscreen(true) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = .65f),
                    contentColor = androidx.compose.ui.graphics.Color.White)) { Text("横屏全屏", style = MaterialTheme.typography.labelMedium) }
        },
        audioMeter = {
            UsbCompactAudioMeter(if (recording) recordingAudioLevelDb else idleAudioLevelDb, recentAudioPeakDb)
        },
        actions = {
            val stats = (state as? RecorderState.Recording)?.stats
            val statusText = if (stats != null) buildList {
                if (stats.fileRecording) add("录像中")
                if (stats.httpStreaming) add("HTTP")
                if (stats.rtmpStreaming) add("RTMP · 重连 ${stats.rtmpReconnectCount} 次")
                add(stats.recentFps?.let { String.format(Locale.US, "%.1f fps", it) } ?: "统计中")
                if (streaming) add(String.format(Locale.US, "%.0f kbps", stats.streamBitrateBitsPerSecond / 1000.0))
                if (stats.outputChangePending) add("切换中…")
            }.joinToString(" · ") else when (state) {
                is RecorderState.Starting -> "正在启动…"
                is RecorderState.Stopping -> "正在停止…"
                is RecorderState.Error -> (state as RecorderState.Error).message
                else -> message
            }
            Text(statusText, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = if (state is RecorderState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            val hasPreview = previewRequested || idlePreview != null || pendingAction == UsbAction.PREVIEW
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (hasPreview) {
                        previewRequestRevision++
                        previewRequested = false
                        if (pendingAction == UsbAction.PREVIEW) pendingAction = UsbAction.NONE
                        idlePreview?.stop()
                        idlePreview = null
                        idleAudioLevelDb = -60f
                        recentAudioPeakDb = -60f
                        message = "预览已停止"
                    } else { previewRequested = true; pendingAction = UsbAction.PREVIEW }
                }, enabled = !recording && (hasPreview || hasVideoInput), modifier = Modifier.weight(1f)) {
                    Text(if (hasPreview) "停止预览" else "预览")
                }
                Button(onClick = {
                    if (fileRecording) requestStop(UsbStopAction.RECORDING)
                    else if (recording) RecorderController.startRecording(context, container)
                    else { previewRequested = false; pendingAction = UsbAction.RECORD }
                }, enabled = hasVideoInput && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    outputControlsEnabled, modifier = Modifier.weight(1f)) {
                    Text(if (fileRecording) "停止录制" else "开始录制")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (httpStreaming) requestStop(UsbStopAction.HTTP)
                    else if (recording) RecorderController.startHttpOutput(context)
                    else { previewRequested = false; pendingAction = UsbAction.STREAM }
                }, enabled = hasVideoInput && (httpStreaming || !httpUploadEnabled || validHttpUploadUrl(httpUploadUrl.trim())) &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    outputControlsEnabled, modifier = Modifier.weight(1f)) {
                    Text(if (httpStreaming) "停止 HTTP" else if (httpUploadEnabled) "HTTP 上传" else "HTTP 串流")
                }
                OutlinedButton(onClick = {
                    if (rtmpStreaming) requestStop(UsbStopAction.RTMP)
                    else if (recording) RecorderController.startRtmpOutput(context, rtmpUrl, rtmpBufferMs, rtmpSendTimeoutSeconds)
                    else { previewRequested = false; pendingAction = UsbAction.RTMP }
                }, enabled = hasVideoInput && rtmpUrl.startsWith("rtmp://") &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && outputControlsEnabled,
                    modifier = Modifier.weight(1f)) {
                    Text(if (rtmpStreaming) "停止 RTMP" else "RTMP 推流")
                }
            }
        },
    ) { tab ->
        when (tab) {
            UsbSettingsTab.DEVICE -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { devicesExpanded = true }, enabled = !recording,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(if (testCardSelected) "测试卡（虚拟设备）" else selected?.productName?.takeIf { it.isNotBlank() } ?: selected?.deviceName ?: "选择摄像头",
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = devicesExpanded, onDismissRequest = { devicesExpanded = false }) {
                            DropdownMenuItem(text = { Text("测试卡（虚拟设备）") }, onClick = {
                                idlePreview?.stop(); idlePreview = null; previewRequested = false
                                selectedName = TestCardSettings.DEVICE_ID; devicesExpanded = false
                            })
                            devices.forEach { device ->
                                DropdownMenuItem(text = { Text("${device.productName ?: "USB 摄像头"} · ${device.deviceName}") },
                                    onClick = {
                                        idlePreview?.stop()
                                        idlePreview = null
                                        previewRequested = false
                                        selectedMode = null
                                        selectedName = device.deviceName
                                        devicesExpanded = false
                                    })
                            }
                        }
                    }
                    OutlinedButton(onClick = {
                        devices = usbVideoDevices(manager)
                        if (!testCardSelected && devices.none { it.deviceName == selectedName }) selectedName = devices.firstOrNull()?.deviceName ?: TestCardSettings.DEVICE_ID
                    }) { Text("刷新") }
                }
                Box {
                    OutlinedButton(onClick = { modesExpanded = true }, enabled = !recording,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(effectiveMode?.display ?: "自动 · 1280×720 · 30 fps")
                    }
                    DropdownMenu(expanded = modesExpanded, onDismissRequest = { modesExpanded = false }) {
                        DropdownMenuItem(text = { Text(if (testCardSelected) "默认 · 1920×1080 · 60 fps" else "自动 · 1280×720 · 30 fps") }, onClick = {
                            if (testCardSelected) testCardSettings = TestCardSettings(pattern = testCardSettings.pattern) else selectedMode = null
                            modesExpanded = false
                        })
                        DropdownMenuItem(text = { Text(if (testCardSelected) "自定义分辨率／帧率…" else "自定义分辨率／帧率／格式…") }, onClick = {
                            selectedMode?.let { mode ->
                                mode.inputFormat?.takeIf { it in UsbCustomVideoMode.formats }?.let { format ->
                                    customVideoMode = UsbCustomVideoMode(mode.width, mode.height, mode.fps, format)
                                }
                            }
                            customModeDialog = true; modesExpanded = false
                        })
                        modes.forEach { mode ->
                            DropdownMenuItem(text = { Text(mode.display) }, enabled = mode.canRecord,
                                onClick = {
                                    if (testCardSelected) testCardSettings = testCardSettings.copy(width = mode.width, height = mode.height, fps = mode.fps)
                                    else selectedMode = mode
                                    modesExpanded = false
                                })
                        }
                    }
                }
                if (testCardSelected) {
                    UsbSettingChoice("测试卡样式", TestCardPattern.entries, testCardSettings.pattern, !recording,
                        { it.label }) { testCardSettings = testCardSettings.copy(pattern = it) }
                    Text("GPU 生成 RGB；W/H 为像素尺寸，FPS 为源帧率，F 为帧号，T 为秒表。",
                        style = MaterialTheme.typography.bodySmall)
                    if (includeAudio && audioInput == UsbAudioInput.USB && uacDevice == null) Text("测试卡只提供视频；如需音频请选择 USB 音频设备或系统麦克风。")
                } else if (devices.isEmpty()) Text("没有检测到 UVC 视频接口；可使用虚拟测试卡。")
                UsbSettingChoice("YUV → RGB 矩阵", UsbYuvMatrix.entries, yuvMatrix, !recording && !testCardSelected,
                    { it.label }) { yuvMatrix = it }
                UsbSettingChoice("源范围", UsbSourceRange.entries, sourceRange, !recording && !testCardSelected,
                    { it.label }) { sourceRange = it }
                UsbSettingChoice("USB 接收队列", listOf(8, 16, 32, 64, 128, 256, 512), receiveTransferCount,
                    !recording && !previewRequested && !testCardSelected,
                    { if (it == 64) "$it 个请求（默认）" else "$it 个请求" }) { receiveTransferCount = it }
                Text("如有丢帧可增大该值；用于 USB 视频接收（Bulk／ISO 共用）；停止采集后可修改，重新预览或录像／推流时生效。",
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeAudio, onCheckedChange = { includeAudio = it }, enabled = !recording)
                    Text("启用音频")
                }
                UsbSettingChoice("音频采集", UsbAudioInput.entries, audioInput, !recording && includeAudio,
                    { it.label }) { audioInput = it }
                if (audioInput == UsbAudioInput.USB) {
                    UsbSettingChoice("USB 音频设备", listOf<UsbAudioDevice?>(null) + uacDevices +
                        listOfNotNull(uacDevice?.takeIf { resolveUsbAudioDevice(it, uacDevices) == null }),
                        uacDevice, !recording && includeAudio, { option ->
                            option?.let { it.label + if (resolveUsbAudioDevice(it, uacDevices) == null) "（未连接）" else "" }
                                ?: "跟随视频设备"
                        }) { uacDevice = it }
                    OutlinedButton(onClick = { uacDevices = usbAudioInputDevices(manager) }, enabled = !recording) { Text("刷新音频设备") }
                    UsbSettingChoice("UAC 输入位深", UsbAudioBitDepth.entries, uacBitDepth, !recording && includeAudio,
                        { it.label }) { uacBitDepth = it }
                    Text("24／32 bit PCM 直接转换为 float 进入 DSP；播放和 AAC 输入转换为 16 bit。",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (audioInput == UsbAudioInput.SYSTEM) {
                    val sources = AudioInputSource.entries.filter { it != AudioInputSource.VOICE_PERFORMANCE || Build.VERSION.SDK_INT >= 29 }
                    UsbSettingChoice("音频输入源", sources, systemAudioInput.source, !recording && includeAudio,
                        { it.label }) { systemAudioInput = systemAudioInput.copy(source = it) }
                    val selectedDevice = systemAudioInput.device
                    val resolved = selectedDevice?.let { resolveSystemAudioDevice(it, systemAudioDevices) }
                    val options = listOf<SystemAudioDevice?>(null) + systemAudioDevices +
                        listOfNotNull(selectedDevice?.takeIf { it !in systemAudioDevices })
                    UsbSettingChoice("音频输入设备", options, selectedDevice, !recording && includeAudio,
                        { it?.let { device -> device.label + if (resolveSystemAudioDevice(device, systemAudioDevices) == null) "（当前不可用）" else "" }
                            ?: "系统默认" }) { systemAudioInput = systemAudioInput.copy(device = it) }
                    if (selectedDevice != null && resolved == null) Text("所选输入设备当前不可用，请重新选择。")
                    Text("输入源决定系统采集策略，设备决定实际输入；电话音频等输入是否可用由系统决定。",
                        style = MaterialTheme.typography.bodySmall)
                }
                UsbSettingChoice("源采样率", audioRates, audioRate, !recording && includeAudio,
                    { if (it == 0) "自动" else "$it Hz" }) { audioRate = it }
                OutlinedTextField(
                    value = audioDelayMs,
                    onValueChange = { audioDelayMs = it.filter { char -> char.isDigit() || char == '-' || char == '+' } },
                    enabled = !recording && includeAudio,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    isError = includeAudio && audioDelayValue == null,
                    label = { Text("音频延迟（毫秒）") },
                    supportingText = { Text(if (audioDelayValue == null) "请输入 -500～500 的整数"
                        else "负数提前，正数延迟，0 不偏移；调整 PCM 时间戳，下次启动录像／串流生效。") },
                )
            }
            UsbSettingsTab.COLOR -> {
                VideoColorGradePanel(videoColorGrade) { videoColorGrade = it }
            }
            UsbSettingsTab.DSP -> {
                AudioDspPanel(audioDsp, enabled = includeAudio, onChange = { audioDsp = it })
            }
            UsbSettingsTab.ENCODING -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) {
                        UsbSettingChoice("编码", VideoCodec.entries, videoCodec, !recording,
                            { it.label }) { videoCodec = it }
                    }
                    Box(Modifier.weight(1f)) {
                        UsbSettingChoice("码率模式", VideoBitrateMode.entries, bitrateMode, !recording,
                            { it.label }) { bitrateMode = it }
                    }
                }
                OutlinedTextField(videoBitrateKbps, { videoBitrateKbps = it.filter(Char::isDigit) }, enabled = !recording,
                    singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("视频码率 kbps") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(gopSeconds, { gopSeconds = it.filter { char -> char.isDigit() || char == '.' } }, enabled = !recording,
                        singleLine = true, modifier = Modifier.weight(1f), label = { Text("GOP 秒") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = gopSecondsValue == null)
                    OutlinedTextField(if (gopSecondsValue == 0f) "0" else bFrames, { bFrames = it.filter(Char::isDigit) }, enabled = !recording && gopSecondsValue != 0f,
                        singleLine = true, modifier = Modifier.weight(1f), label = { Text("B 帧") })
                }
                Text(if (gopSecondsValue == null) "GOP 请输入 0～30 秒，可使用小数。"
                    else if (gopSecondsValue == 0f) "GOP 0：每帧关键帧，自动禁用 B 帧；同码率下画质可能降低。"
                    else "GOP 支持小数（如 0.5 秒）；设为 0 表示每帧关键帧。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = audioBitrateKbps,
                    onValueChange = { audioBitrateKbps = it.filter(Char::isDigit) },
                    enabled = !recording && includeAudio,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = includeAudio && audioBitrateValue == null,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("音频码率 kbps") },
                    supportingText = { Text(if (audioBitrateValue == null) "请输入 16～512 kbps 的整数"
                        else "AAC 编码码率；录像与串流共用，默认 192 kbps。") },
                )
                Text("编码目标颜色", style = MaterialTheme.typography.titleMedium)
                UsbSettingChoice("颜色标准（Primaries / RGB → YUV 矩阵）",
                    VideoColorStandard.entries, encoderColorStandard, !recording,
                    { if (it == VideoColorStandard.BT2020) "BT.2020 / NCL 矩阵" else it.label }) { encoderColorStandard = it }
                UsbSettingChoice("编码 Transfer（传递函数）",
                    encoderTransferOptions, encoderColorTransfer, !recording,
                    { it.encoderLabel() }) { encoderColorTransfer = it }
                UsbSettingChoice("编码 Range（范围）",
                    VideoColorRange.entries, encoderColorRange, !recording,
                    { it.label }) { encoderColorRange = it }
                Text("颜色标准同时选择色域和 RGB → YUV 矩阵；下次启动录像或串流生效。",
                    style = MaterialTheme.typography.bodySmall)
                Text("Transfer 设置不自动转换输入画面为 HDR；设备可能调整所请求的颜色参数。",
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = forceSpsVui, onCheckedChange = { forceSpsVui = it }, enabled = !recording)
                    Text("重写编码后 H.26x 颜色元数据")
                }
                if (forceSpsVui) {
                    Text("以下设置重写输出颜色标记，不转换像素；请与编码目标匹配。",
                        style = MaterialTheme.typography.bodySmall)
                    UsbSettingChoice("Range（范围）", VideoColorRange.entries, rewriteColorRange, !recording,
                        { if (it == VideoColorRange.DEFAULT) "保持原值" else it.label }) { rewriteColorRange = it }
                    UsbSettingChoice("Primaries（色域）", VideoColorStandard.entries, rewriteColorStandard, !recording,
                        { if (it == VideoColorStandard.DEFAULT) "保持原值" else it.label }) { rewriteColorStandard = it }
                    UsbSettingChoice("Transfer（传递函数）", VideoColorTransfer.entries, rewriteColorTransfer, !recording,
                        { if (it == VideoColorTransfer.DEFAULT) "保持原值" else it.label }) { rewriteColorTransfer = it }
                    UsbSettingChoice("Matrix（矩阵系数）", VideoColorMatrix.entries, rewriteColorMatrix, !recording,
                        { if (it == VideoColorMatrix.DEFAULT) "保持原值" else it.label }) { rewriteColorMatrix = it }
                }
            }
            UsbSettingsTab.OUTPUT -> {
                OutlinedTextField(
                    value = muxingQueueSize,
                    onValueChange = { muxingQueueSize = it.filter(Char::isDigit) },
                    enabled = !recording,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = muxingQueueValue == null,
                    label = { Text("封装排序缓存（包）") },
                    supportingText = { Text(if (muxingQueueValue == null) "请输入 0～1024 的整数"
                        else "默认 64，0 立即写入；缓存音视频包后按最终时间戳交错写入。数量越大，排序窗口和输出延迟越大；下次启动生效。") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = confirmStopOutputs, onCheckedChange = { confirmStopOutputs = it })
                    Text("停止前确认")
                }
                Text("停止录像、RTMP 推流或 HTTP 串流前弹出确认框。",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("录像格式")
                    ContainerFormat.entries.forEach { format ->
                        OutlinedButton(onClick = { container = format }, enabled = !fileRecording && outputControlsEnabled) {
                            Text(if (container == format) "✓ ${format.label}" else format.label)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = httpUploadEnabled, onCheckedChange = { httpUploadEnabled = it },
                        enabled = !recording && outputControlsEnabled)
                    Text("HTTP 远程分块上传")
                }
                Text("勾选后 HTTP 按钮上传到延迟服务端；未勾选时仍提供本机 HTTP 串流。设置在下次启动采集时生效。",
                    style = MaterialTheme.typography.bodySmall)
                if (httpUploadEnabled) {
                    OutlinedTextField(value = httpUploadUrl, onValueChange = { httpUploadUrl = it },
                        enabled = !recording && outputControlsEnabled, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), label = { Text("HTTP 上传地址") },
                        placeholder = { Text("http://服务器:8080/upload/live") },
                        isError = !validHttpUploadUrl(httpUploadUrl.trim()))
                    UsbSettingChoice("上传分块时长", listOf(1, 2, 3, 5), httpUploadChunkSeconds,
                        !recording && outputControlsEnabled, { "$it 秒" }) { httpUploadChunkSeconds = it }
                    UsbSettingChoice("断线补传缓存", listOf(30, 60, 90, 120, 180, 300), httpUploadCacheSeconds,
                        !recording && outputControlsEnabled, { "$it 秒" }) { httpUploadCacheSeconds = it }
                    Text("默认 1 秒一块、60 秒内存缓存（最多 256 MiB）。缓存满或块过期时淘汰最旧块，继续串流；停止串流会立即取消上传并清空缓存。服务端默认延迟 10 秒、待播上限 20 秒，TS 原样透传。",
                        style = MaterialTheme.typography.bodySmall)
                    if (validHttpUploadUrl(httpUploadUrl.trim())) Text(
                        "播放地址：${httpUploadUrl.trim().replace("/upload/", "/live/")}.ts",
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = rtmpUrl,
                    onValueChange = { rtmpUrl = it },
                    enabled = !rtmpStreaming && outputControlsEnabled,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("RTMP 地址（可选）") },
                    placeholder = { Text("rtmp://服务器/app/串流密钥") },
                )
                UsbSettingChoice("RTMP 推流缓存", listOf(100, 250, 500, 1_000, 2_000, 3_000, 5_000, 10_000, 15_000, 30_000),
                    rtmpBufferMs, !rtmpStreaming && outputControlsEnabled,
                    { "${it / 1_000.0} 秒" }) { rtmpBufferMs = it }
                Text("默认 5 秒，按编码码率估算缓存容量；较小可减少网络拥堵时的积压，较大可缓冲网络波动。启动 RTMP 时生效；断线重连会丢弃旧缓存。",
                    style = MaterialTheme.typography.bodySmall)
                UsbSettingChoice("RTMP 发送超时", listOf(3, 5, 10, 15, 30),
                    rtmpSendTimeoutSeconds, !rtmpStreaming && outputControlsEnabled,
                    { "$it 秒" }) { rtmpSendTimeoutSeconds = it }
                Text("默认 10 秒；发送阻塞超时后自动重连，从最新关键帧恢复。启动 RTMP 时生效。",
                    style = MaterialTheme.typography.bodySmall)
                val usbReceiveRate = if (recording)
                    (state as? RecorderState.Recording)?.stats?.usbVideoReceiveBitsPerSecond else idleUsbReceiveRate
                if (recording || idlePreview != null) {
                    Text("USB 视频接收：" + (usbReceiveRate?.let {
                        String.format(Locale.US, "%.1f Mbps · %.2f MB/s", it / 1_000_000.0, it / 8_000_000.0)
                    } ?: "统计中"), style = MaterialTheme.typography.bodySmall)
                }
                Text(message, style = MaterialTheme.typography.bodySmall)
                if (state is RecorderState.Error) Text((state as RecorderState.Error).message, color = MaterialTheme.colorScheme.error)
                if (state is RecorderState.Recording) {
                    val stats = (state as RecorderState.Recording).stats
                    val streamRate = stats.streamBitrateBitsPerSecond / 1000.0
                    val modes = buildList {
                        if (stats.fileRecording) add("录像中")
                        if (stats.httpStreaming) add("HTTP 串流中")
                        if (stats.rtmpStreaming) add("RTMP 推流中")
                    }.joinToString(" + ")
                    val recentFpsText = stats.recentFps?.let { String.format(Locale.US, "%.1f fps", it) } ?: "统计中"
                    Text("$modes · 近期（5秒）$recentFpsText · 平均 ${String.format(Locale.US, "%.1f", stats.averageFps)} fps" +
                        if (streaming) " · 串流 ${String.format(Locale.US, "%.0f", streamRate)} kbps" else "")
                    if (stats.rtmpStreaming) Text("RTMP 重连尝试：${stats.rtmpReconnectCount} 次（不含首次连接）",
                        style = MaterialTheme.typography.bodySmall)
                    stats.httpUploadStats?.let { upload ->
                        Text("HTTP 会话 ID：${upload.sessionId}", style = MaterialTheme.typography.bodySmall)
                        Text("序号 · 最新生成：${upload.latestSequence ?: "—"} · 上传中：${upload.uploadingSequence ?: "—"} · 已确认：${upload.acknowledgedSequence ?: "—"}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("待上传：${upload.pendingUploadBlocks} 块 · 待补传：${upload.pendingRetryBlocks} 块（含在途未确认块）",
                            style = MaterialTheme.typography.bodySmall)
                        Text("累计淘汰：${upload.droppedBlocks} 块", style = MaterialTheme.typography.bodySmall)
                        Text(String.format(Locale.US, "缓存数据：%.1f KiB / %.2f 秒 · 缓存占用：%.1f KiB",
                            upload.cachedDataBytes / 1024.0, upload.cachedDurationUs / 1_000_000.0,
                            upload.cacheAllocatedBytes / 1024.0), style = MaterialTheme.typography.bodySmall)
                        Text(String.format(Locale.US, "已组块队列：%.1f KiB / %.2f 秒 · 上限 %d 秒 / %.0f MiB",
                            upload.queuedBytes / 1024.0, upload.queuedDurationUs / 1_000_000.0,
                            upload.cacheLimitSeconds, upload.cacheLimitBytes / (1024.0 * 1024.0)),
                            style = MaterialTheme.typography.bodySmall)
                        Text(String.format(Locale.US, "正在组块：%.1f KiB / %.2f 秒 · 累计确认：%.1f KiB",
                            upload.assemblingBytes / 1024.0, upload.assemblingDurationUs / 1_000_000.0,
                            upload.acknowledgedBytes / 1024.0), style = MaterialTheme.typography.bodySmall)
                    }
                    stats.outputPath?.let { Text("文件：$it", style = MaterialTheme.typography.bodySmall) }
                    if (stats.outputChangePending) Text("正在切换输出…", style = MaterialTheme.typography.bodySmall)
                }
            }
            UsbSettingsTab.TIMING -> {
                UsbSettingChoice("视频缓存", bufferOptions, bufferFrames, !recording,
                    { "$it 帧" }) { bufferFrames = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = timestampSmoothingEnabled,
                        onCheckedChange = { timestampSmoothingEnabled = it }, enabled = !recording)
                    Text("音视频时间戳平滑", modifier = Modifier.padding(top = 12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = timestampSmoothingNtscEnabled,
                        onCheckedChange = { timestampSmoothingNtscEnabled = it },
                        enabled = !recording && timestampSmoothingEnabled)
                    Text("按 NTSC 帧率平滑视频时间戳", modifier = Modifier.padding(top = 12.dp))
                }
                Text("开启后：60 → 59.94 fps，30 → 29.97 fps；音频仍按源采样率计算。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = timestampSmoothingMaxDeltaSeconds,
                    onValueChange = { timestampSmoothingMaxDeltaSeconds = it.filter { char -> char.isDigit() || char == '.' } },
                    enabled = !recording && timestampSmoothingEnabled,
                    singleLine = true,
                    isError = timestampSmoothingEnabled && timestampSmoothingDelta == null,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("时间戳最大偏差（秒）") },
                    supportingText = { Text(if (timestampSmoothingDelta == null) "请输入大于或等于 0 的秒数"
                        else "偏差不超过此值时使用计算时间戳；超过时跟随实际时间戳。") },
                )
            }
            UsbSettingsTab.DISPLAY -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = previewEnabled, onCheckedChange = { previewEnabled = it })
                    Text("录制时预览", modifier = Modifier.padding(top = 12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = audioPreviewEnabled, onCheckedChange = { audioPreviewEnabled = it },
                        enabled = includeAudio)
                    Text("音频预览（监听）", modifier = Modifier.padding(top = 12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = keepScreenOn, onCheckedChange = { keepScreenOn = it })
                    Text("屏幕常亮", modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun <T> UsbSettingChoice(label: String, values: List<T>, selected: T, enabled: Boolean,
                                     valueLabel: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("$label：${valueLabel(selected)}")
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            values.forEach { value ->
                DropdownMenuItem(text = { Text(valueLabel(value)) }, onClick = {
                    onSelect(value); expanded = false
                })
            }
        }
    }
}

private fun usbVideoDevices(manager: UsbManager): List<UsbDevice> = manager.deviceList.values
    .filter { device -> (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO } }
    .sortedBy { it.deviceName }

private fun usbRecordingConfig(context: Context, device: UsbDevice?, mode: UsbVideoMode?,
                               testCard: TestCardSettings, audio: Boolean,
                               audioInput: UsbAudioInput, systemAudioInput: SystemAudioInputSettings,
                               uacDevice: UsbAudioDevice?, uacBitDepth: UsbAudioBitDepth,
                               audioRate: Int, bufferFrames: Int, receiveTransferCount: Int, container: ContainerFormat,
                               httpEnabled: Boolean, httpUploadEnabled: Boolean, httpUploadUrl: String,
                               httpUploadChunkSeconds: Int, httpUploadCacheSeconds: Int,
                               rtmpEnabled: Boolean, rtmpUrl: String, rtmpBufferMs: Int,
                               rtmpSendTimeoutSeconds: Int, videoCodec: VideoCodec,
                               bitrateMode: VideoBitrateMode, videoBitrate: Int, audioBitrate: Int,
                               audioDelayMs: Int, muxingQueueSize: Int,
                               gopSeconds: Float, bFrames: Int,
                               timestampSmoothingEnabled: Boolean, timestampSmoothingNtscEnabled: Boolean,
                               timestampSmoothingMaxDeltaSeconds: Double,
                               yuvMatrix: UsbYuvMatrix, sourceRange: UsbSourceRange,
                               encoderColorStandard: VideoColorStandard, encoderColorTransfer: VideoColorTransfer,
                               encoderColorRange: VideoColorRange,
                               forceSpsVui: Boolean, rewriteColorRange: VideoColorRange,
                               rewriteColorStandard: VideoColorStandard, rewriteColorMatrix: VideoColorMatrix,
                               rewriteColorTransfer: VideoColorTransfer): RecordingConfig {
    val saved = ConfigPreferences.load(context)
    return RecordingConfig(
        mode = if (audio) RecordingMode.AUDIO_VIDEO else RecordingMode.VIDEO,
        cameraId = device?.let { UsbRecorderEngine.USB_CAMERA_PREFIX + it.deviceName } ?: TestCardSettings.DEVICE_ID,
        testCard = testCard,
        width = mode?.width ?: 1280,
        height = mode?.height ?: 720,
        fps = mode?.fps ?: 30.0,
        usbVideoInputFormat = mode?.inputFormat ?: UsbVideoInputFormat.AUTO,
        usbCustomVideoMode = mode?.custom == true,
        usbAudioSampleRate = audioRate,
        usbAudioInput = audioInput,
        usbAudioDevice = uacDevice,
        usbAudioBitDepth = uacBitDepth,
        systemAudioInput = systemAudioInput,
        usbVideoBufferFrames = bufferFrames,
        usbReceiveTransferCount = receiveTransferCount,
        usbYuvMatrix = yuvMatrix,
        usbSourceRange = sourceRange,
        colorStandard = encoderColorStandard,
        colorTransfer = encoderColorTransfer,
        colorRange = encoderColorRange,
        forceSpsVui = forceSpsVui,
        rewriteColorRange = rewriteColorRange,
        rewriteColorStandard = rewriteColorStandard,
        rewriteColorMatrix = rewriteColorMatrix,
        rewriteColorTransfer = rewriteColorTransfer,
        usbTimestampSmoothingEnabled = timestampSmoothingEnabled,
        usbTimestampSmoothingNtscEnabled = timestampSmoothingNtscEnabled,
        usbTimestampSmoothingMaxDeltaSeconds = timestampSmoothingMaxDeltaSeconds,
        videoCodec = videoCodec,
        videoBitrate = videoBitrate,
        videoBitrateMode = bitrateMode,
        videoKeyFrameIntervalSeconds = gopSeconds,
        videoMaxBFrames = bFrames,
        audioBitrate = audioBitrate,
        audioDelayMs = audioDelayMs.coerceIn(-500, 500),
        muxingQueueSize = muxingQueueSize.coerceIn(0, 1024),
        // Preserve the chosen recording container when starting a stream-only encoder session.
        container = container,
        httpStreamPort = saved.httpStreamPort,
        httpBufferSeconds = saved.httpBufferSeconds,
        httpStreamEnabled = httpEnabled,
        httpUploadEnabled = httpUploadEnabled,
        httpUploadUrl = httpUploadUrl,
        httpUploadChunkSeconds = httpUploadChunkSeconds.coerceIn(1, 5),
        httpUploadCacheSeconds = httpUploadCacheSeconds.coerceIn(30, 300),
        httpServiceOnly = httpEnabled || rtmpEnabled,
        rtmpEnabled = rtmpEnabled,
        rtmpUrl = rtmpUrl,
        rtmpBufferMs = rtmpBufferMs.coerceIn(100, 30_000),
        rtmpSendTimeoutSeconds = rtmpSendTimeoutSeconds.coerceIn(3, 30),
        outputTreeUri = saved.outputTreeUri,
    )
}

private class UsbIdlePreview(
    private val context: Context,
    private val manager: UsbManager,
    private val device: UsbDevice?,
    initialSurface: Surface,
    private val width: Int,
    private val height: Int,
    private val fps: Double,
    private val videoFormat: UsbVideoInputFormat,
    bufferFrames: Int,
    private val audioEnabled: Boolean,
    private val audioRate: Int,
    initialMatrix: UsbYuvMatrix,
    initialSourceRange: UsbSourceRange,
    initialLowFrameRate: Boolean,
    initialAudioDsp: AudioDspSettings,
    initialColorGrade: VideoColorGradeSettings,
    private val customVideoMode: Boolean,
    private val testCard: TestCardSettings?,
    private val audioInput: UsbAudioInput,
    private val uacDevice: UsbAudioDevice?,
    private val uacBitDepth: UsbAudioBitDepth,
    private val receiveTransferCount: Int,
    private val systemAudioInput: SystemAudioInputSettings,
    private val onMessage: (String) -> Unit,
    private val onAudioLevel: (Float) -> Unit,
) : UsbCaptureCallback {
    @Volatile var usbVideoReceiveBitsPerSecond: Double? = null
        private set
    @Volatile private var surface: Surface? = initialSurface
    @Volatile private var colorSettings = initialMatrix to initialSourceRange
    @Volatile private var lowFrameRate = initialLowFrameRate
    @Volatile private var audioPreviewEnabled = false
    @Volatile private var audioMonitor: UsbAudioMonitor? = null
    @Volatile private var audioPipeline: UsbAudioPipeline? = null
    @Volatile private var audioDspSettings = initialAudioDsp
    @Volatile private var colorGradeSettings = initialColorGrade
    private val audioMonitorLock = Any()
    private data class Frame(
        val bytes: CapturedVideoBuffer,
        val format: Int,
        val width: Int,
        val height: Int,
        val timestampNs: Long,
    )
    private val frameQueue = ArrayBlockingQueue<Frame>(bufferFrames.coerceIn(1, 30))
    private val rawVideoConverter = RawVideoConverter()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopped = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val finished = CountDownLatch(1)
    private val closed = CountDownLatch(1)
    private var lastAudioLevelNs = 0L
    private var renderThread: Thread? = null
    private val previewRevision = AtomicLong()
    private val mjpegDecodePool = MjpegDecodePool(
        decoder = { bytes, format, frameWidth, frameHeight, destination ->
            format == 1 && NativeUsbCapture.decodeMjpegToGpuBuffer(bytes, frameWidth, frameHeight, destination)
        },
        capacity = bufferFrames.coerceIn(1, 30),
        chromaGeometry = MjpegChromaGeometry::read,
    )

    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (testCard == null) mjpegDecodePool.start()
        Thread({
            var handle = 0L
            var connection: android.hardware.usb.UsbDeviceConnection? = null
            var gateAcquired = false
            var systemCapture: SystemAudioCapture? = null
            var uacCapture: UsbUacCapture? = null
            try {
                if (testCard == null) { usbPreviewGate.acquire(); gateAcquired = true }
                if (stopped.get()) return@Thread
                if (testCard == null) {
                    connection = manager.openDevice(checkNotNull(device))
                    checkNotNull(connection) { "无法打开 USB 摄像头" }
                    handle = NativeUsbCapture.nativeOpen(checkNotNull(connection).fileDescriptor, width, height, fps,
                        videoFormat.nativeValue, audioEnabled && audioInput == UsbAudioInput.USB &&
                            (uacDevice == null || uacDevice.deviceName == device?.deviceName), audioRate, customVideoMode,
                        uacBitDepth.nativeValue, receiveTransferCount.coerceIn(8, 512))
                    check(handle != 0L) { "无法初始化 USB 摄像头" }
                }
                if (stopped.get()) return@Thread
                val format = if (handle != 0L) NativeUsbCapture.nativeFormat(handle) else intArrayOf(width, height, 0, 0)
                if (audioEnabled && audioInput == UsbAudioInput.SYSTEM) {
                    systemCapture = SystemAudioCapture.open(context, systemAudioInput, audioRate)
                }
                if (audioEnabled && audioInput == UsbAudioInput.USB && uacDevice != null &&
                    (testCard != null || uacDevice.deviceName != device?.deviceName)) {
                    uacCapture = UsbUacCapture.open(context, uacDevice, audioRate, uacBitDepth)
                }
                val pcmRate = systemCapture?.sampleRate ?: uacCapture?.sampleRate ?: format[2]
                val pcmChannels = systemCapture?.channels ?: uacCapture?.channels ?: format[3]
                if (audioEnabled && pcmRate > 0 && pcmChannels in 1..2) {
                    val monitor = UsbAudioMonitor(pcmRate, pcmChannels, onError = {
                        mainHandler.post { if (!stopped.get()) onMessage("音频预览已停止：$it") }
                    })
                    synchronized(audioMonitorLock) {
                        audioMonitor = monitor
                        monitor.setEnabled(audioPreviewEnabled)
                        audioPipeline = UsbAudioPipeline(pcmRate, pcmChannels, audioDspSettings,
                            onPcm = ::onProcessedAudioPcm,
                            onError = { error -> mainHandler.post {
                                if (!stopped.get()) onMessage("音频 DSP 已停止：$error")
                            } })
                    }
                }
                val pcmBits = uacCapture?.bitDepth ?: if (systemCapture != null) 16 else format.getOrNull(4) ?: 0
                val audioDescription = if (pcmRate > 0 && pcmChannels > 0) " · 音频 $pcmRate Hz / $pcmChannels 声道 / $pcmBits bit" else ""
                mainHandler.post { onMessage("${if (testCard != null) "测试卡" else "USB"}预览：${format[0]}×${format[1]}$audioDescription") }
                renderThread = Thread({ renderFrames() }, "usb-preview-render").apply { start() }
                if (handle != 0L) NativeUsbCapture.nativeStart(handle, this)
                systemCapture?.start(onPcm = ::onUsbAudioPcm,
                    onError = { error ->
                        mainHandler.post { onMessage("系统音频采集失败：$error") }
                        stop()
                    }, onNotice = { notice -> mainHandler.post { if (!stopped.get()) onMessage(notice) } })
                uacCapture?.start(this)
                val receiveRate = UsbReceiveRate()
                if (handle != 0L) receiveRate.sample(NativeUsbCapture.nativeReceivedVideoBytes(handle)) else null
                while (!finished.await(1, TimeUnit.SECONDS)) {
                    usbVideoReceiveBitsPerSecond = if (handle != 0L) receiveRate.sample(NativeUsbCapture.nativeReceivedVideoBytes(handle)) else null
                }
            } catch (error: Throwable) {
                if (!stopped.get()) mainHandler.post { onMessage("${if (testCard != null) "测试卡" else "USB"}预览失败：${error.message}") }
            } finally {
                usbVideoReceiveBitsPerSecond = null
                runCatching { systemCapture?.close() }
                runCatching { uacCapture?.close() }
                if (handle != 0L) runCatching { NativeUsbCapture.nativeClose(handle) }
                val pipeline = audioPipeline.also { audioPipeline = null }
                pipeline?.let { it.close(); runCatching { it.awaitStopped() } }
                val monitor = synchronized(audioMonitorLock) { audioMonitor.also { audioMonitor = null } }
                monitor?.let { it.close(); runCatching { it.awaitStopped(500) } }
                stopped.set(true)
                while (true) (frameQueue.poll() ?: break).bytes.close()
                rawVideoConverter.close()
                mjpegDecodePool.close()
                surface = null
                renderThread?.interrupt()
                runCatching { renderThread?.join(2_000) }
                connection?.close()
                if (gateAcquired) usbPreviewGate.release()
                closed.countDown()
            }
        }, "usb-idle-preview").start()
    }

    fun updateSurface(newSurface: Surface?) {
        surface = newSurface
        previewRevision.incrementAndGet()
    }

    fun updateColorSettings(matrix: UsbYuvMatrix, range: UsbSourceRange) {
        colorSettings = matrix to range
    }
    fun updateColorGrade(settings: VideoColorGradeSettings) { colorGradeSettings = settings.sanitized() }

    fun updateLowFrameRate(enabled: Boolean) { lowFrameRate = enabled }
    fun recentAudioPeakDb(): Float = if (stopped.get()) -60f else audioPipeline?.recentPeakDb() ?: -60f
    fun updateAudioDsp(settings: AudioDspSettings) {
        synchronized(audioMonitorLock) {
            audioDspSettings = settings
            audioPipeline?.updateSettings(settings)
        }
    }
    fun updateAudioPreview(enabled: Boolean) {
        synchronized(audioMonitorLock) {
            audioPreviewEnabled = enabled
            audioMonitor?.setEnabled(enabled)
        }
    }

    fun stop(onStopped: (() -> Unit)? = null) {
        stopped.set(true)
        finished.countDown()
        if (onStopped != null) Thread({
            if (closed.await(10, TimeUnit.SECONDS)) mainHandler.post(onStopped)
            else mainHandler.post { onMessage("USB 预览尚未释放，请稍后重试录制") }
        }, "usb-preview-handoff").start()
    }

    override fun onUsbVideoFrame(bytes: CapturedVideoBuffer, format: Int, width: Int, height: Int, timestampNs: Long) {
        if (stopped.get() || surface?.isValid != true) { bytes.close(); return }
        if (format == 1) {
            mjpegDecodePool.offer(bytes, format, width, height, timestampNs)
            return
        }
        val frame = Frame(bytes, format, width, height, timestampNs)
        if (!frameQueue.offer(frame)) {
            frameQueue.poll()?.bytes?.close()
            if (!frameQueue.offer(frame)) bytes.close()
        }
    }

    private fun renderFrames() {
        try {
            GpuVideoRenderer(initialColorGrade = colorGradeSettings).use { gpu ->
                var reportStartedNs = System.nanoTime()
                var renderedFrames = 0
                var timestampOriginNs = Long.MIN_VALUE
                var wallOriginNs = 0L
                var boundRevision = -1L
                if (testCard != null) {
                    runTestCardFrames(testCard, System.nanoTime(), { !stopped.get() }) { frame ->
                        gpu.setColorGrade(colorGradeSettings)
                        gpu.render(frame, GpuVideoRenderer.PreviewTarget(surface, previewRevision.get(), lowFrameRate))
                    }
                    return@use
                }
                while (!stopped.get()) {
                    val decoded = mjpegDecodePool.poll(5)
                    var converted: RawVideoConverter.ConvertedFrame? = null
                    try {
                        val frame = if (decoded != null) {
                            GpuVideoFrame.fromDecoded(decoded) ?: continue
                        } else {
                            val raw = frameQueue.poll(5, TimeUnit.MILLISECONDS) ?: continue
                            converted = raw.bytes.use {
                                rawVideoConverter.convert(it, raw.format, raw.width, raw.height, raw.timestampNs)
                            }
                            converted?.frame ?: continue
                        }
                        val target = GpuVideoRenderer.PreviewTarget(surface, previewRevision.get(), lowFrameRate)
                        val settings = colorSettings
                        gpu.setColorSettings(settings.first, settings.second)
                        gpu.setColorGrade(colorGradeSettings)
                        if (target.surface?.isValid != true) continue
                        val nowNs = System.nanoTime()
                        if (timestampOriginNs == Long.MIN_VALUE || boundRevision != target.revision) {
                            timestampOriginNs = frame.timestampNs
                            wallOriginNs = nowNs
                            boundRevision = target.revision
                        }
                        val targetNs = wallOriginNs + frame.timestampNs - timestampOriginNs
                        if (nowNs - targetNs > 100_000_000L) {
                            timestampOriginNs = frame.timestampNs
                            wallOriginNs = nowNs
                        } else if (targetNs > nowNs) {
                            val waitNs = targetNs - nowNs
                            Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
                        }
                        if (stopped.get()) break
                        if (gpu.render(frame, target)) renderedFrames++
                        val reportNs = System.nanoTime()
                        val elapsedNs = reportNs - reportStartedNs
                        if (elapsedNs >= 1_000_000_000L) {
                            val currentFps = renderedFrames * 1_000_000_000.0 / elapsedNs
                            mainHandler.post {
                                if (!stopped.get()) onMessage("USB 预览：${frame.width}×${frame.height} · " +
                                    String.format(Locale.US, "%.1f fps", currentFps))
                            }
                            renderedFrames = 0
                            reportStartedNs = reportNs
                        }
                    } finally {
                        converted?.close()
                        decoded?.close()
                    }
                }
            }
        } catch (_: InterruptedException) {
            // Normal preview shutdown.
        } catch (error: Throwable) {
            if (!stopped.get()) {
                mainHandler.post { onMessage("${if (testCard != null) "测试卡" else "USB"} GPU 预览失败：${error.message}") }
                stop()
            }
        } finally {
            rawVideoConverter.close()
        }
    }

    override fun onUsbAudioPcm(bytes: ByteArray, timestampNs: Long) {
        if (stopped.get()) return
        audioPipeline?.offer(bytes, timestampNs)
    }

    override fun onUsbAudioPcmRaw(bytes: ByteArray, timestampNs: Long, sampleBytes: Int) {
        if (!stopped.get()) audioPipeline?.offer(bytes, timestampNs, sampleBytes)
    }

    private fun onProcessedAudioPcm(bytes: ByteArray, timestampNs: Long) {
        if (stopped.get()) return
        audioMonitor?.offer(bytes)
        if (timestampNs - lastAudioLevelNs < 100_000_000L) return
        lastAudioLevelNs = timestampNs
        val level = usbPcmLevelDb(bytes)
        mainHandler.post { if (!stopped.get()) onAudioLevel(level) }
    }
}

private const val USB_PERMISSION_ACTION = "com.llawsxx.uvclivestreaming.USB_PERMISSION"
