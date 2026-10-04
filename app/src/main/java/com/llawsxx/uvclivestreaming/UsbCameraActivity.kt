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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.llawsxx.uvclivestreaming.recording.UsbYuvMatrix
import com.llawsxx.uvclivestreaming.recording.UsbSourceRange
import com.llawsxx.uvclivestreaming.recording.UsbRecorderEngine
import com.llawsxx.uvclivestreaming.recording.UsbReceiveRate
import com.llawsxx.uvclivestreaming.recording.UsbAudioMonitor
import com.llawsxx.uvclivestreaming.recording.UsbVideoInputFormat
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

private val usbPreviewGate = Semaphore(1)

private data class UsbVideoMode(
    val label: String, val width: Int, val height: Int, val fps: Int,
    val inputFormat: UsbVideoInputFormat?, val detail: String,
) : Serializable {
    val canRecord: Boolean get() = inputFormat != null && width in 1..3840 &&
        height in 1..2160 && fps in 1..240
    val display: String get() = "$label · ${width}×$height · $fps fps" +
        (if (detail.isBlank()) "" else " ($detail)") +
        (if (canRecord) "" else " · 暂不可录制")
}

private fun parseUsbVideoMode(raw: String): UsbVideoMode? {
    val parts = raw.split('|')
    if (parts.size != 6) return null
    val value = parts[4].toIntOrNull()
    return UsbVideoMode(parts[0], parts[1].toIntOrNull() ?: return null,
        parts[2].toIntOrNull() ?: return null, parts[3].toIntOrNull() ?: return null,
        UsbVideoInputFormat.entries.firstOrNull { it.nativeValue == value }, parts[5])
}

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
    var selectedName by rememberSaveable { mutableStateOf(uiSettings.selectedDeviceName ?: devices.firstOrNull()?.deviceName) }
    val selected = devices.firstOrNull { it.deviceName == selectedName }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var surfaceRevision by remember { mutableStateOf(0) }
    var idlePreview by remember { mutableStateOf<UsbIdlePreview?>(null) }
    var pendingAction by remember { mutableStateOf(UsbAction.NONE) }
    // Do not issue repeated requests while the system USB service is deciding
    // (some vendor builds return permission=false without showing the dialog).
    var usbPermissionRequestPending by remember { mutableStateOf(false) }
    var usbPermissionEpoch by remember { mutableStateOf(0) }
    var runtimePermissionEpoch by remember { mutableStateOf(0) }
    var includeAudio by rememberSaveable { mutableStateOf(uiSettings.includeAudio) }
    var audioPreviewEnabled by rememberSaveable { mutableStateOf(uiSettings.audioPreviewEnabled) }
    var audioDsp by remember { mutableStateOf(uiSettings.audioDsp) }
    var videoColorGrade by remember { mutableStateOf(uiSettings.videoColorGrade) }
    var previewEnabled by rememberSaveable { mutableStateOf(uiSettings.previewEnabled) }
    var lowFrameRatePreview by rememberSaveable { mutableStateOf(uiSettings.lowFrameRatePreview) }
    var keepScreenOn by rememberSaveable { mutableStateOf(uiSettings.keepScreenOn) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var container by rememberSaveable { mutableStateOf(uiSettings.container) }
    var rtmpUrl by rememberSaveable { mutableStateOf(uiSettings.rtmpUrl) }
    var videoBitrateKbps by rememberSaveable { mutableStateOf(uiSettings.videoBitrateKbps) }
    var audioBitrateKbps by rememberSaveable { mutableStateOf(uiSettings.audioBitrateKbps) }
    val audioBitrateValue = audioBitrateKbps.toIntOrNull()?.takeIf { it in 16..512 }
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
    var selectedMode by rememberSaveable { mutableStateOf<UsbVideoMode?>(null) }
    var audioRate by rememberSaveable { mutableStateOf(uiSettings.audioRate) }
    var bufferFrames by rememberSaveable { mutableStateOf(uiSettings.bufferFrames) }
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
        selectedName, selectedMode?.display, includeAudio, audioPreviewEnabled, audioDsp, videoColorGrade, previewEnabled, lowFrameRatePreview, keepScreenOn,
        container, rtmpUrl, videoBitrateKbps, audioBitrateKbps, gopSeconds, bFrames, videoCodec, bitrateMode, audioRate,
        bufferFrames, yuvMatrix, sourceRange, timestampSmoothingEnabled, timestampSmoothingNtscEnabled,
        encoderColorStandard, encoderColorTransfer, encoderColorRange,
        timestampSmoothingMaxDeltaSeconds,
        forceSpsVui, rewriteColorRange, rewriteColorStandard, rewriteColorMatrix, rewriteColorTransfer,
    ) {
        UsbUiPreferences.save(context, UsbUiSettings(
            selectedDeviceName = selectedName,
            selectedModeDisplay = selectedMode?.display,
            includeAudio = includeAudio,
            audioPreviewEnabled = audioPreviewEnabled,
            audioDsp = audioDsp,
            videoColorGrade = videoColorGrade,
            previewEnabled = previewEnabled,
            lowFrameRatePreview = lowFrameRatePreview,
            container = container,
            rtmpUrl = rtmpUrl,
            videoBitrateKbps = videoBitrateKbps,
            audioBitrateKbps = audioBitrateKbps,
            gopSeconds = gopSeconds,
            bFrames = bFrames,
            videoCodec = videoCodec,
            bitrateMode = bitrateMode,
            audioRate = audioRate,
            bufferFrames = bufferFrames,
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
        if (!cameraGranted) {
            pendingAction = UsbAction.NONE
            previewRequested = false
            message = "未获得相机权限，无法录像或串流"
        } else if (includeAudio && it.containsKey(Manifest.permission.RECORD_AUDIO) &&
            it[Manifest.permission.RECORD_AUDIO] != true
        ) {
            // USB 音频是可选通道：拒绝麦克风权限时仍允许视频继续，
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
                            message = "USB 权限被系统拒绝，请拔插设备后重新点击预览"
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        devices = usbVideoDevices(manager)
                        if (devices.none { it.deviceName == selectedName }) {
                            idlePreview?.stop()
                            idlePreview = null
                            if (RecorderController.state.value is RecorderState.Recording ||
                                RecorderController.state.value is RecorderState.Starting
                            ) RecorderController.stop(context)
                            selectedName = devices.firstOrNull()?.deviceName
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
            if (selectedMode != null && selectedMode !in found) selectedMode = null
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
        RecorderController.attachPreview(surface, selectedMode?.width ?: 1280, selectedMode?.height ?: 720,
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

    LaunchedEffect(pendingAction, selectedName, usbPermissionEpoch, runtimePermissionEpoch, modesReadyKey) {
        if (pendingAction == UsbAction.NONE) return@LaunchedEffect
        val device = selected ?: run {
            pendingAction = UsbAction.NONE
            message = "没有可用的 USB 摄像头"
            return@LaunchedEffect
        }
        // Android P+ requires CAMERA runtime permission before USB permission
        // can be granted for a USB video-class device. Request it first;
        // requesting USB permission before CAMERA yields permission=false with
        // no dialog on several devices.
        if (pendingAction == UsbAction.PREVIEW ||
            pendingAction == UsbAction.RECORD || pendingAction == UsbAction.STREAM ||
            pendingAction == UsbAction.RTMP
        ) {
            val needed = buildList {
                add(Manifest.permission.CAMERA)
                if (pendingAction != UsbAction.PREVIEW && includeAudio) {
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
        if (!manager.hasPermission(device)) {
            if (usbPermissionRequestPending) return@LaunchedEffect
            usbPermissionRequestPending = true
            // The USB service appends EXTRA_DEVICE and
            // EXTRA_PERMISSION_GRANTED to this PendingIntent, so it must be
            // mutable on Android 12+.
            val intent = Intent(USB_PERMISSION_ACTION).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
            val requestCode = device.deviceId.coerceAtLeast(1)
            manager.requestPermission(device, PendingIntent.getBroadcast(context, requestCode, intent, flags))
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
                val width = selectedMode?.width ?: 1280
                val height = selectedMode?.height ?: 720
                val fps = selectedMode?.fps ?: 30
                val videoFormat = selectedMode?.inputFormat ?: UsbVideoInputFormat.AUTO
                val beginPreview: () -> Unit = {
                    if (previewRequested && requestRevision == previewRequestRevision &&
                        selectedName == device.deviceName && !recording && target.isValid) {
                        UsbIdlePreview(manager, device, target, width, height, fps, videoFormat,
                            bufferFrames, includeAudio, audioRate, yuvMatrix, sourceRange, lowFrameRatePreview,
                            initialAudioDsp = audioDsp,
                            initialColorGrade = videoColorGrade,
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
            if (timestampSmoothingEnabled && timestampSmoothingDelta == null) {
                message = "时间戳平滑最大偏差请输入大于或等于 0 的秒数"
                return@LaunchedEffect
            }
            previewRequested = false
            val previous = idlePreview
            idlePreview = null
            val config = usbRecordingConfig(
                context, device, selectedMode, includeAudio, audioRate, bufferFrames, container,
                requested == UsbAction.STREAM, requested == UsbAction.RTMP, rtmpUrl,
                videoCodec, bitrateMode,
                (videoBitrateKbps.toIntOrNull()?.coerceIn(100, 100_000) ?: 12_000) * 1_000,
                (audioBitrateValue ?: 192) * 1_000,
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

    BackHandler(enabled = fullscreen) { fullscreen = false; activity?.exitUsbFullscreen() }
    UsbCameraWorkspace(
        aspectRatio = (selectedMode?.width ?: 1280).toFloat() / (selectedMode?.height ?: 720),
        includeAudio = includeAudio,
        fullscreen = fullscreen,
        onExitFullscreen = { fullscreen = false; activity?.exitUsbFullscreen() },
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
                if (stats.rtmpStreaming) add("RTMP")
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
                }, enabled = !recording && (hasPreview || selected != null), modifier = Modifier.weight(1f)) {
                    Text(if (hasPreview) "停止预览" else "预览")
                }
                Button(onClick = {
                    if (fileRecording) RecorderController.stopRecording(context)
                    else if (recording) RecorderController.startRecording(context, container)
                    else { previewRequested = false; pendingAction = UsbAction.RECORD }
                }, enabled = selected != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    outputControlsEnabled, modifier = Modifier.weight(1f)) {
                    Text(if (fileRecording) "停止录制" else "开始录制")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (httpStreaming) RecorderController.stopHttpOutput(context)
                    else if (recording) RecorderController.startHttpOutput(context)
                    else { previewRequested = false; pendingAction = UsbAction.STREAM }
                }, enabled = selected != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    outputControlsEnabled, modifier = Modifier.weight(1f)) {
                    Text(if (httpStreaming) "停止 HTTP" else "HTTP 串流")
                }
                OutlinedButton(onClick = {
                    if (rtmpStreaming) RecorderController.stopRtmpOutput(context)
                    else if (recording) RecorderController.startRtmpOutput(context, rtmpUrl)
                    else { previewRequested = false; pendingAction = UsbAction.RTMP }
                }, enabled = selected != null && rtmpUrl.startsWith("rtmp://") &&
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
                            Text(selected?.productName?.takeIf { it.isNotBlank() } ?: selected?.deviceName ?: "选择摄像头",
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = devicesExpanded, onDismissRequest = { devicesExpanded = false }) {
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
                        if (devices.none { it.deviceName == selectedName }) selectedName = devices.firstOrNull()?.deviceName
                    }) { Text("刷新") }
                }
                Box {
                    OutlinedButton(onClick = { modesExpanded = true }, enabled = !recording,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(selectedMode?.display ?: "自动 · 1280×720 · 30 fps")
                    }
                    DropdownMenu(expanded = modesExpanded, onDismissRequest = { modesExpanded = false }) {
                        DropdownMenuItem(text = { Text("自动 · 1280×720 · 30 fps") }, onClick = {
                            selectedMode = null; modesExpanded = false
                        })
                        modes.forEach { mode ->
                            DropdownMenuItem(text = { Text(mode.display) }, enabled = mode.canRecord,
                                onClick = { selectedMode = mode; modesExpanded = false })
                        }
                    }
                }
                if (devices.isEmpty()) Text("没有检测到 UVC 视频接口；请连接 USB 摄像头。")
                UsbSettingChoice("YUV → RGB 矩阵", UsbYuvMatrix.entries, yuvMatrix, !recording,
                    { it.label }) { yuvMatrix = it }
                UsbSettingChoice("源范围", UsbSourceRange.entries, sourceRange, !recording,
                    { it.label }) { sourceRange = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeAudio, onCheckedChange = { includeAudio = it }, enabled = !recording)
                    Text("USB 麦克风")
                }
                UsbSettingChoice("源采样率", audioRates, audioRate, !recording && includeAudio,
                    { if (it == 0) "自动" else "$it Hz" }) { audioRate = it }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("录像格式")
                    ContainerFormat.entries.forEach { format ->
                        OutlinedButton(onClick = { container = format }, enabled = !fileRecording && outputControlsEnabled) {
                            Text(if (container == format) "✓ ${format.label}" else format.label)
                        }
                    }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = previewEnabled, onCheckedChange = { previewEnabled = it })
                    Text("录制时预览")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = audioPreviewEnabled, onCheckedChange = { audioPreviewEnabled = it },
                        enabled = includeAudio)
                    Text("音频预览（监听）", modifier = Modifier.padding(top = 12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = lowFrameRatePreview, onCheckedChange = { lowFrameRatePreview = it })
                    Text("低帧率预览（5 fps）", modifier = Modifier.padding(top = 12.dp))
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

private fun usbRecordingConfig(context: Context, device: UsbDevice, mode: UsbVideoMode?, audio: Boolean,
                               audioRate: Int, bufferFrames: Int, container: ContainerFormat,
                               httpEnabled: Boolean, rtmpEnabled: Boolean, rtmpUrl: String, videoCodec: VideoCodec,
                               bitrateMode: VideoBitrateMode, videoBitrate: Int, audioBitrate: Int,
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
        cameraId = UsbRecorderEngine.USB_CAMERA_PREFIX + device.deviceName,
        width = mode?.width ?: 1280,
        height = mode?.height ?: 720,
        fps = (mode?.fps ?: 30).toDouble(),
        usbVideoInputFormat = mode?.inputFormat ?: UsbVideoInputFormat.AUTO,
        usbAudioSampleRate = audioRate,
        usbVideoBufferFrames = bufferFrames,
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
        // Preserve the chosen recording container when starting a stream-only encoder session.
        container = container,
        httpStreamPort = saved.httpStreamPort,
        httpBufferSeconds = saved.httpBufferSeconds,
        httpStreamEnabled = httpEnabled,
        httpServiceOnly = httpEnabled || rtmpEnabled,
        rtmpEnabled = rtmpEnabled,
        rtmpUrl = rtmpUrl,
        outputTreeUri = saved.outputTreeUri,
    )
}

private class UsbIdlePreview(
    private val manager: UsbManager,
    private val device: UsbDevice,
    initialSurface: Surface,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val videoFormat: UsbVideoInputFormat,
    bufferFrames: Int,
    private val audioEnabled: Boolean,
    private val audioRate: Int,
    initialMatrix: UsbYuvMatrix,
    initialSourceRange: UsbSourceRange,
    initialLowFrameRate: Boolean,
    initialAudioDsp: AudioDspSettings,
    initialColorGrade: VideoColorGradeSettings,
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
        val bytes: ByteArray,
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
        mjpegDecodePool.start()
        Thread({
            var handle = 0L
            var connection: android.hardware.usb.UsbDeviceConnection? = null
            var gateAcquired = false
            try {
                usbPreviewGate.acquire()
                gateAcquired = true
                if (stopped.get()) return@Thread
                connection = manager.openDevice(device)
                checkNotNull(connection) { "无法打开 USB 摄像头" }
                handle = NativeUsbCapture.nativeOpen(checkNotNull(connection).fileDescriptor, width, height, fps,
                    videoFormat.nativeValue, audioEnabled, audioRate)
                if (stopped.get()) return@Thread
                val format = NativeUsbCapture.nativeFormat(handle)
                if (audioEnabled && format[2] > 0 && format[3] in 1..2) {
                    val monitor = UsbAudioMonitor(format[2], format[3], onError = {
                        mainHandler.post { if (!stopped.get()) onMessage("音频预览已停止：$it") }
                    })
                    synchronized(audioMonitorLock) {
                        audioMonitor = monitor
                        monitor.setEnabled(audioPreviewEnabled)
                        audioPipeline = UsbAudioPipeline(format[2], format[3], audioDspSettings,
                            onPcm = ::onProcessedAudioPcm,
                            onError = { error -> mainHandler.post {
                                if (!stopped.get()) onMessage("音频 DSP 已停止：$error")
                            } })
                    }
                }
                mainHandler.post { onMessage("USB 预览：${format[0]}×${format[1]}") }
                renderThread = Thread({ renderFrames() }, "usb-preview-render").apply { start() }
                NativeUsbCapture.nativeStart(handle, this)
                val receiveRate = UsbReceiveRate()
                receiveRate.sample(NativeUsbCapture.nativeReceivedVideoBytes(handle))
                while (!finished.await(1, TimeUnit.SECONDS)) {
                    usbVideoReceiveBitsPerSecond = receiveRate.sample(NativeUsbCapture.nativeReceivedVideoBytes(handle))
                }
            } catch (error: Throwable) {
                if (!stopped.get()) mainHandler.post { onMessage("USB 预览失败：${error.message}") }
            } finally {
                usbVideoReceiveBitsPerSecond = null
                if (handle != 0L) runCatching { NativeUsbCapture.nativeClose(handle) }
                val pipeline = audioPipeline.also { audioPipeline = null }
                pipeline?.let { it.close(); runCatching { it.awaitStopped() } }
                val monitor = synchronized(audioMonitorLock) { audioMonitor.also { audioMonitor = null } }
                monitor?.let { it.close(); runCatching { it.awaitStopped(500) } }
                stopped.set(true)
                frameQueue.clear()
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

    override fun onUsbVideoFrame(bytes: ByteArray, format: Int, width: Int, height: Int, timestampNs: Long) {
        if (stopped.get() || surface?.isValid != true) return
        if (format == 1) {
            mjpegDecodePool.offer(bytes, format, width, height, timestampNs)
            return
        }
        val frame = Frame(bytes, format, width, height, timestampNs)
        if (!frameQueue.offer(frame)) {
            frameQueue.poll()
            frameQueue.offer(frame)
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
                while (!stopped.get()) {
                    val decoded = mjpegDecodePool.poll(5)
                    var converted: RawVideoConverter.ConvertedFrame? = null
                    try {
                        val frame = if (decoded != null) {
                            GpuVideoFrame.fromDecoded(decoded) ?: continue
                        } else {
                            val raw = frameQueue.poll(5, TimeUnit.MILLISECONDS) ?: continue
                            converted = rawVideoConverter.convert(raw.bytes, raw.format, raw.width, raw.height, raw.timestampNs)
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
                mainHandler.post { onMessage("USB GPU 预览失败：${error.message}") }
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
