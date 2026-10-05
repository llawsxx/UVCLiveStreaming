package com.llawsxx.uvclivestreaming.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** AudioRecord only reads and offers PCM; DSP/monitor/encoder run on their own workers. */
internal class SystemAudioCapture private constructor(
    private val record: AudioRecord,
    private val preferredDevice: SystemAudioDevice?,
) : AutoCloseable {
    val sampleRate: Int = record.sampleRate
    val channels: Int = record.channelCount
    private val closed = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val lifecycleLock = Any()
    private var worker: Thread? = null
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null

    fun start(onPcm: (ByteArray, Long) -> Unit, onError: (String) -> Unit, onNotice: (String) -> Unit) {
        synchronized(lifecycleLock) {
            check(!closed.get() && worker == null) { "系统麦克风已启动或释放" }
            val lastRouteId = AtomicInteger(-1)
            fun reportFailure(error: Throwable) {
                if (!closed.get() && failed.compareAndSet(false, true)) {
                    runCatching { record.stop() }
                    onError(error.message ?: "系统麦克风采集失败")
                }
            }
            fun checkRoute() {
                if (closed.get()) return
                val routed = record.routedDevice ?: return
                if (preferredDevice != null && routed.id != preferredDevice.id) {
                    error("系统未采用所选输入设备：${preferredDevice.label}；实际为 ${SystemAudioDevice.from(routed).label}")
                }
                if (lastRouteId.getAndSet(routed.id) != routed.id) {
                    onNotice("系统音频：${SystemAudioDevice.from(routed).label} · $sampleRate Hz · ${channels}声道")
                }
            }
            // Verify the actual route as well as setPreferredDevice's return value.
            routingListener = AudioRouting.OnRoutingChangedListener {
                runCatching { checkRoute() }.onFailure(::reportFailure)
            }.also { record.addOnRoutingChangedListener(it, Handler(Looper.getMainLooper())) }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "系统麦克风未进入采集状态" }
            worker = Thread({
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    val frameBytes = channels * 2
                    val buffer = ByteArray((sampleRate / 100).coerceAtLeast(1) * frameBytes)
                    val clock = SystemAudioPcmClock(sampleRate)
                    val timestamp = AudioTimestamp()
                    var reads = 0
                    while (!closed.get() && !failed.get()) {
                        val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (closed.get() || failed.get()) break
                        check(count > 0) { "系统麦克风读取失败（AudioRecord 返回 $count）" }
                        check(count % frameBytes == 0) { "系统麦克风返回不完整的 PCM 采样帧" }
                        if (reads++ % 100 == 0) checkRoute()
                        val hasTimestamp = record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                        val pts = clock.timestamp(count / frameBytes, System.nanoTime(),
                            if (hasTimestamp) timestamp.framePosition else null,
                            if (hasTimestamp) timestamp.nanoTime else null)
                        onPcm(buffer.copyOf(count), pts)
                    }
                } catch (error: Throwable) {
                    reportFailure(error)
                }
            }, "system-audio-capture").apply { start() }
        }
    }

    override fun close() {
        val thread = synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            routingListener?.let { record.removeOnRoutingChangedListener(it) }
            routingListener = null
            // stop() wakes a blocking read; release only after its worker has exited.
            runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
            worker
        }
        try {
            if (thread != null && thread !== Thread.currentThread()) thread.join()
        } finally {
            record.release()
        }
    }

    companion object {
        @SuppressLint("MissingPermission")
        fun open(context: Context, settings: SystemAudioInputSettings, requestedRate: Int): SystemAudioCapture {
            require(settings.source != AudioInputSource.VOICE_PERFORMANCE || Build.VERSION.SDK_INT >= 29) {
                "VOICE_PERFORMANCE 需要 Android 10 或更高版本"
            }
            val inputs = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
            val preferred = settings.device?.let { selected ->
                val match = resolveSystemAudioDevice(selected, inputs.map(SystemAudioDevice::from))
                    ?: error("所选系统音频输入设备当前不可用：${selected.label}")
                inputs.first { it.id == match.id }
            }
            val rates = if (requestedRate > 0) listOf(requestedRate) else listOf(48_000, 44_100, 32_000, 16_000)
            var lastError: Throwable? = null
            for (rate in rates) for (channelCount in listOf(2, 1)) {
                var candidate: AudioRecord? = null
                try {
                    val mask = if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
                    val minimum = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                    if (minimum <= 0) continue
                    candidate = AudioRecord.Builder().setAudioSource(settings.source.mediaRecorderValue)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(maxOf(minimum * 2, rate * channelCount * 2 / 10)).build()
                    check(candidate.state == AudioRecord.STATE_INITIALIZED) { "系统麦克风初始化失败" }
                    if (preferred != null) check(candidate.setPreferredDevice(preferred)) { "系统拒绝设置所选音频输入设备" }
                    Log.i("SystemAudioCapture", "Prepared source=${settings.source.name} rate=${candidate.sampleRate} " +
                        "channels=${candidate.channelCount} preferred=${preferred?.id ?: "default"}")
                    return SystemAudioCapture(candidate, preferred?.let(SystemAudioDevice::from))
                } catch (error: SecurityException) {
                    candidate?.release()
                    throw error
                } catch (error: Throwable) {
                    candidate?.release()
                    lastError = error
                }
            }
            throw IllegalStateException("无法初始化系统音频输入（${settings.source.label}）：${lastError?.message ?: "不支持所选采样率"}", lastError)
        }
    }
}
