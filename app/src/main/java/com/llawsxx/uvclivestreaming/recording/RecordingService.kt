package com.llawsxx.uvclivestreaming.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.llawsxx.uvclivestreaming.MainActivity
import com.llawsxx.uvclivestreaming.R

/** Foreground lifetime owner for USB UVC/UAC capture. */
class RecordingService : Service() {
    private var engine: UsbRecorderEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureGeneration = 0L
    private var notificationContent = ""

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "USB 录像串流", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> readConfig(intent)?.let { startCapture(it) } ?: fail("缺少录像配置")
            ACTION_START_HTTP -> readConfig(intent)?.let {
                startCapture(it.copy(httpStreamEnabled = true, httpServiceOnly = true))
            } ?: fail("缺少 HTTP 配置")
            ACTION_START_RECORDING -> engine?.startRecording(
                runCatching { ContainerFormat.valueOf(intent.getStringExtra(EXTRA_CONTAINER).orEmpty()) }
                    .getOrDefault(ContainerFormat.MP4))
            ACTION_STOP_RECORDING -> engine?.stopRecording()
            ACTION_START_HTTP_OUTPUT -> engine?.startHttp()
            ACTION_STOP_HTTP_OUTPUT -> engine?.stopHttp()
            ACTION_START_RTMP_OUTPUT -> engine?.startRtmp(intent.getStringExtra(EXTRA_RTMP_URL).orEmpty())
            ACTION_STOP_RTMP_OUTPUT -> engine?.stopRtmp()
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(config: RecordingConfig) {
        if (engine != null) return
        if (!config.cameraId.startsWith(UsbRecorderEngine.USB_CAMERA_PREFIX)) {
            fail("当前版本只支持 USB 摄像头")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            fail("USB 录像需要 Android 8.0 或更高版本")
            return
        }
        startAsForeground("正在准备 USB 摄像头", config)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:usb-capture")
            .apply { acquire() }
        val outputStore = RecordingOutputStore(this, config.outputTreeUri)
        val generation = ++captureGeneration
        val newEngine = UsbRecorderEngine(
            context = this, config = config, outputStore = outputStore,
            onStarted = { stats -> postStats(generation, stats) },
            onStats = { stats -> postStats(generation, stats) },
            onNotice = RecorderController::notice,
            onError = { message -> mainHandler.post { if (generation == captureGeneration) fail(message) } },
            onOutputsEmpty = { mainHandler.post {
                if (generation == captureGeneration && engine?.hasActiveOutputs() == false) stopCapture()
            } },
        )
        engine = newEngine
        RecorderController.previewUpdater = newEngine::updatePreview
        RecorderController.audioPreviewUpdater = newEngine::updateAudioPreview
        RecorderController.audioDspUpdater = newEngine::updateAudioDsp
        RecorderController.colorGradeUpdater = newEngine::updateColorGrade
        RecorderController.audioPeakReader = newEngine::recentAudioPeakDb
        newEngine.start(RecorderController.previewSurface, RecorderController.previewEnabled,
            RecorderController.previewRotationDegrees)
    }

    private fun postStats(generation: Long, stats: RecordingStats) {
        mainHandler.post {
            if (generation != captureGeneration || engine == null) return@post
            RecorderController.update(RecorderState.Recording(stats))
            val modes = buildList {
                if (stats.fileRecording) add("录像")
                if (stats.httpStreaming) add("HTTP")
                if (stats.rtmpStreaming) add("RTMP")
            }.joinToString(" + ")
            val content = "运行中 · $modes" + (stats.outputPath?.let { " · ${it.substringAfterLast('/')}" } ?: "")
            if (content != notificationContent) { notificationContent = content; updateNotification(content) }
        }
    }

    private fun stopCapture(error: String? = null) {
        val old = engine ?: run { finish(error); return }
        captureGeneration++
        engine = null
        RecorderController.previewUpdater = null
        RecorderController.audioPreviewUpdater = null
        RecorderController.audioDspUpdater = null
        RecorderController.colorGradeUpdater = null
        RecorderController.audioPeakReader = null
        RecorderController.update(RecorderState.Stopping())
        old.stop { mainHandler.post { finish(error) } }
    }

    private fun fail(message: String) {
        stopCapture(message)
    }

    private fun finish(error: String?) {
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        RecorderController.update(error?.let(RecorderState::Error) ?: RecorderState.Idle)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground(content: String, config: RecordingConfig) {
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (if (config.hasVideo) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0) or
                (if (config.hasAudio) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(content), serviceType)
    }

    private fun updateNotification(content: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(content))

    private fun notification(content: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_name).setContentTitle(getString(R.string.app_name))
            .setContentText(content).setContentIntent(open).setOngoing(true).setSilent(true)
            .addAction(0, "停止", stop).build()
    }

    private fun readConfig(intent: Intent): RecordingConfig? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getSerializableExtra(EXTRA_CONFIG, RecordingConfig::class.java)
    } else {
        @Suppress("DEPRECATION") intent.getSerializableExtra(EXTRA_CONFIG) as? RecordingConfig
    }

    override fun onDestroy() {
        captureGeneration++
        RecorderController.audioPreviewUpdater = null
        RecorderController.audioDspUpdater = null
        RecorderController.colorGradeUpdater = null
        RecorderController.audioPeakReader = null
        engine?.forceRelease(); engine = null
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.llawsxx.uvclivestreaming.action.START"
        const val ACTION_START_HTTP = "com.llawsxx.uvclivestreaming.action.START_HTTP"
        const val ACTION_STOP = "com.llawsxx.uvclivestreaming.action.STOP"
        const val ACTION_START_RECORDING = "com.llawsxx.uvclivestreaming.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.llawsxx.uvclivestreaming.action.STOP_RECORDING"
        const val ACTION_START_HTTP_OUTPUT = "com.llawsxx.uvclivestreaming.action.START_HTTP_OUTPUT"
        const val ACTION_STOP_HTTP_OUTPUT = "com.llawsxx.uvclivestreaming.action.STOP_HTTP_OUTPUT"
        const val ACTION_START_RTMP_OUTPUT = "com.llawsxx.uvclivestreaming.action.START_RTMP_OUTPUT"
        const val ACTION_STOP_RTMP_OUTPUT = "com.llawsxx.uvclivestreaming.action.STOP_RTMP_OUTPUT"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_CONTAINER = "container"
        const val EXTRA_RTMP_URL = "rtmpUrl"
        private const val CHANNEL_ID = "usb_capture"
        private const val NOTIFICATION_ID = 4102
    }
}
