package com.llawsxx.uvclivestreaming.recording

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.Surface
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface RecorderMessage { val message: String
    data class Notice(override val message: String) : RecorderMessage
    data class Error(override val message: String) : RecorderMessage
}

object RecorderController {
    private val mutableState = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state = mutableState.asStateFlow()
    private val mutableUsbAudioLevelDb = MutableStateFlow(-60f)
    val usbAudioLevelDb = mutableUsbAudioLevelDb.asStateFlow()
    private val mutableMessages = MutableSharedFlow<RecorderMessage>(extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages = mutableMessages.asSharedFlow()

    @Volatile internal var previewSurface: Surface? = null
    @Volatile internal var previewWidth = 0
    @Volatile internal var previewHeight = 0
    @Volatile internal var previewEnabled = false
    @Volatile internal var previewLowFrameRate = false
    @Volatile internal var previewRotationDegrees = 0
    @Volatile internal var previewUpdater: ((Surface?, Boolean, Int) -> Unit)? = null
    @Volatile internal var usbAudioPreviewEnabled = false
    @Volatile internal var audioPreviewUpdater: ((Boolean) -> Unit)? = null
    @Volatile internal var audioDspUpdater: ((AudioDspSettings) -> Unit)? = null
    @Volatile internal var colorGradeUpdater: ((VideoColorGradeSettings) -> Unit)? = null
    @Volatile internal var audioPeakReader: (() -> Float)? = null

    fun start(context: Context, config: RecordingConfig) {
        mutableState.value = RecorderState.Starting()
        val intent = Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_CONFIG, config)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    fun startHttp(context: Context, config: RecordingConfig) {
        mutableState.value = RecorderState.Starting("正在启动 HTTP MPEG-TS")
        val intent = Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START_HTTP)
            .putExtra(RecordingService.EXTRA_CONFIG, config)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    fun stop(context: Context) {
        mutableState.value = RecorderState.Stopping()
        context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    }

    fun startRecording(context: Context, container: ContainerFormat) = changeOutput(context,
        RecordingService.ACTION_START_RECORDING, RecordingService.EXTRA_CONTAINER, container.name)
    fun stopRecording(context: Context) = changeOutput(context, RecordingService.ACTION_STOP_RECORDING)
    fun startHttpOutput(context: Context) = changeOutput(context, RecordingService.ACTION_START_HTTP_OUTPUT)
    fun stopHttpOutput(context: Context) = changeOutput(context, RecordingService.ACTION_STOP_HTTP_OUTPUT)
    fun startRtmpOutput(context: Context, url: String) = changeOutput(context,
        RecordingService.ACTION_START_RTMP_OUTPUT, RecordingService.EXTRA_RTMP_URL, url)
    fun stopRtmpOutput(context: Context) = changeOutput(context, RecordingService.ACTION_STOP_RTMP_OUTPUT)

    private fun changeOutput(context: Context, action: String, key: String? = null, value: String? = null) {
        val current = mutableState.value as? RecorderState.Recording ?: return
        if (current.stats.outputChangePending) return
        mutableState.value = current.copy(stats = current.stats.copy(outputChangePending = true))
        val intent = Intent(context, RecordingService::class.java).setAction(action)
        if (key != null) intent.putExtra(key, value)
        context.startService(intent)
    }

    fun attachPreview(surface: Surface?, width: Int = 0, height: Int = 0, enabled: Boolean = surface != null,
                      rotationDegrees: Int = 0, lowFrameRate: Boolean = previewLowFrameRate) {
        previewSurface = surface; previewWidth = width; previewHeight = height
        previewEnabled = enabled; previewRotationDegrees = rotationDegrees
        previewLowFrameRate = lowFrameRate
        previewUpdater?.invoke(surface, enabled, rotationDegrees)
    }

    internal fun update(state: RecorderState) { mutableState.value = state }
    internal fun updateUsbAudioPreview(enabled: Boolean) {
        usbAudioPreviewEnabled = enabled
        audioPreviewUpdater?.invoke(enabled)
    }
    internal fun updateUsbAudioDsp(settings: AudioDspSettings) {
        audioDspUpdater?.invoke(settings)
    }
    internal fun updateUsbColorGrade(settings: VideoColorGradeSettings) { colorGradeUpdater?.invoke(settings) }
    internal fun usbAudioRecentPeakDb(): Float = audioPeakReader?.invoke() ?: -60f
    internal fun notice(message: String) { mutableMessages.tryEmit(RecorderMessage.Notice(message)) }
    internal fun updateUsbAudioLevel(levelDb: Float) { mutableUsbAudioLevelDb.value = levelDb.coerceIn(-60f, 0f) }
}
