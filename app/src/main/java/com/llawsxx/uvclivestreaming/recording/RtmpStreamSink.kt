package com.llawsxx.uvclivestreaming.recording

import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.withLock

/**
 * RTMP publisher used by the USB encoder. RtmpConnection handles the RTMP
 * handshake, commands and control messages; this class queues FLV media.
 *
 * H.264 uses the legacy AVC FLV header. HEVC uses the Enhanced RTMP
 * ExVideoTagHeader (`hvc1`) supported by current FFmpeg/RTMP servers.
 */
internal class RtmpStreamSink(
    private val config: RecordingConfig,
    private val onNotice: (String) -> Unit,
) : Closeable {
    private data class Packet(val type: Int, val timestampMs: Int, val payload: ByteArray)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val queue = ArrayDeque<Packet>()
    private var queuedBytes = 0L
    private var videoConfig: ByteArray? = null
    private var videoCodec = VideoCodec.H264
    private var hevcInputLengthSize = 4
    private var audioConfig: ByteArray? = null
    private var width = 0
    private var height = 0
    private var fps = 30.0
    private var audioRate = 48_000
    private var audioChannels = 2
    @Volatile private var running = false
    @Volatile private var videoReady = false
    @Volatile private var audioReady = false
    private var worker: Thread? = null
    @Volatile private var connection: RtmpConnection? = null
    private val sentBytes = AtomicLong()

    val bytesSent: Long get() = sentBytes.get()

    fun start() {
        if (running) return
        check(config.rtmpUrl.startsWith("rtmp://", ignoreCase = true)) { "RTMP 地址必须以 rtmp:// 开头" }
        if (config.videoCodec !in setOf(VideoCodec.H264, VideoCodec.H265)) {
            onNotice("RTMP 仅支持 H.264 或 H.265 视频")
            return
        }
        running = true
        worker = Thread(::runLoop, "rtmp-publisher").apply {
            isDaemon = true
            start()
        }
    }

    fun setVideoFormat(format: MediaFormat) {
        videoCodec = when (format.getString(MediaFormat.KEY_MIME)) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> VideoCodec.H264
            MediaFormat.MIMETYPE_VIDEO_HEVC -> VideoCodec.H265
            else -> { onNotice("RTMP 需要 H.264/AVC 或 H.265/HEVC 输出"); return }
        }
        width = format.getInteger(MediaFormat.KEY_WIDTH, 0)
        height = format.getInteger(MediaFormat.KEY_HEIGHT, 0)
        fps = format.getInteger(MediaFormat.KEY_FRAME_RATE, 30).toDouble()
        val csd0 = format.getByteBuffer("csd-0")?.let(::copyBuffer)
        val csd1 = format.getByteBuffer("csd-1")?.let(::copyBuffer)
        hevcInputLengthSize = RtmpHevc.sourceLengthSize(csd0 ?: ByteArray(0))
        videoConfig = if (videoCodec == VideoCodec.H265)
            runCatching { RtmpHevc.configuration(csd0 ?: ByteArray(0), csd1) }
                .onFailure { onNotice("RTMP HEVC 编码配置无效：${it.message}"); Log.e(TAG, "Invalid HEVC CSD", it) }
                .getOrNull()
        else makeAvcDecoderConfiguration(csd0 ?: ByteArray(0), csd1)
        videoReady = videoConfig?.isNotEmpty() == true
        lock.withLock { changed.signalAll() }
    }

    fun setAudioFormat(format: MediaFormat) {
        if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) return
        audioRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, audioRate)
        audioChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, audioChannels)
        audioConfig = format.getByteBuffer("csd-0")?.let(::copyBuffer)
        audioReady = audioConfig?.isNotEmpty() == true
        lock.withLock { changed.signalAll() }
    }

    fun writeVideo(data: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        if (!running || !videoReady || data.isEmpty()) return
        val avcc = if (videoCodec == VideoCodec.H265) {
            runCatching { RtmpHevc.codedFrame(data, hevcInputLengthSize) }
                .getOrElse { Log.e(TAG, "Invalid HEVC frame framing", it); return }
        } else annexBToLengthPrefixed(data)
        if (avcc.isEmpty()) return
        val payload = if (videoCodec == VideoCodec.H265) {
            ByteArray(8 + avcc.size).also {
                // ExVideoTagHeader: coded frames, hvc1, zero composition time.
                it[0] = (0x80 or 0x01 or if (keyFrame) 0x10 else 0x20).toByte()
                it[1] = 'h'.code.toByte(); it[2] = 'v'.code.toByte()
                it[3] = 'c'.code.toByte(); it[4] = '1'.code.toByte()
                it[5] = 0; it[6] = 0; it[7] = 0
                avcc.copyInto(it, 8)
            }
        } else {
            ByteArray(5 + avcc.size).also {
                it[0] = if (keyFrame) 0x17 else 0x27
                it[1] = 1; it[2] = 0; it[3] = 0; it[4] = 0
                avcc.copyInto(it, 5)
            }
        }
        enqueue(Packet(9, (ptsUs / 1_000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), payload))
    }

    fun writeAudio(data: ByteArray, ptsUs: Long) {
        if (!running || data.isEmpty()) return
        val payload = ByteArray(2 + data.size)
        payload[0] = 0xAF.toByte()
        payload[1] = 1
        data.copyInto(payload, 2)
        enqueue(Packet(8, (ptsUs / 1_000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(), payload))
    }

    private fun enqueue(packet: Packet) {
        lock.withLock {
            if (!running) return
            val maxBytes = ((config.videoBitrate.coerceAtLeast(100_000).toLong() + config.audioBitrate) / 8L) * 5L
            while (queuedBytes + packet.payload.size > maxBytes && queue.isNotEmpty()) {
                queuedBytes -= queue.removeFirst().payload.size
            }
            queue.addLast(packet)
            queuedBytes += packet.payload.size
            changed.signalAll()
        }
    }

    private fun runLoop() {
        var nextRetryNs = 0L
        while (running) {
            try {
                if (connection == null) {
                    if (!videoReady || (config.hasAudio && !audioReady) || System.nanoTime() < nextRetryNs) {
                        lock.withLock { changed.await(250, TimeUnit.MILLISECONDS) }
                        continue
                    }
                    val session = RtmpConnection(config.rtmpUrl) { Log.i(TAG, it) }
                    connection = session // Also closes an in-progress handshake when stopping.
                    session.open()
                    if (!running) break
                    sendInitialMessages(session)
                    onNotice("RTMP 推流已连接")
                }
                connection!!.ensureActive()
                val nextPacket: Packet? = lock.withLock {
                    if (running && queue.isEmpty()) changed.await(250, TimeUnit.MILLISECONDS)
                    if (!running || queue.isEmpty()) null else queue.removeFirst().also { queuedBytes -= it.payload.size }
                }
                if (nextPacket == null) continue
                val packet = nextPacket
                sendMessage(connection!!, packet.type, packet.timestampMs, packet.payload, if (packet.type == 9) 6 else 4)
            } catch (error: Throwable) {
                if (running) {
                    Log.e(TAG, "RTMP connection failed", error)
                    onNotice("RTMP 连接中断：${error.message ?: "网络错误"}，正在重连")
                }
                closeSocket()
                nextRetryNs = System.nanoTime() + config.rtmpReconnectDelaySeconds.coerceIn(1, 30) * 1_000_000_000L
            }
        }
        closeSocket()
    }

    private fun sendInitialMessages(session: RtmpConnection) {
        val metadata = RtmpAmf.encode("@setDataFrame", "onMetaData", mapOf(
            "width" to width.toDouble(), "height" to height.toDouble(), "framerate" to fps,
            "videocodecid" to if (videoCodec == VideoCodec.H265) 1_752_589_105.0 else 7.0,
            "audiosamplerate" to audioRate.toDouble(), "audiosamplesize" to 16.0,
            "stereo" to (audioReady && audioChannels > 1), "duration" to 0.0,
        ))
        sendMessage(session, 18, 0, metadata, 5)
        videoConfig?.let {
            val sequence = if (videoCodec == VideoCodec.H265)
                byteArrayOf(0x90.toByte(), 'h'.code.toByte(), 'v'.code.toByte(), 'c'.code.toByte(), '1'.code.toByte()) + it
            else byteArrayOf(0x17, 0, 0, 0, 0) + it
            sendMessage(session, 9, 0, sequence, 6)
        }
        if (audioReady) audioConfig?.let { sendMessage(session, 8, 0, byteArrayOf(0xAF.toByte(), 0) + it, 4) }
    }

    private fun sendMessage(session: RtmpConnection, type: Int, timestampMs: Int, payload: ByteArray, csid: Int) {
        session.send(type, timestampMs.toLong(), payload, csid)
        sentBytes.addAndGet(payload.size.toLong())
    }

    private fun closeSocket() {
        val session = connection
        connection = null
        runCatching { session?.close() }
    }

    override fun close() {
        if (!running) return
        running = false
        lock.withLock { changed.signalAll() }
        closeSocket() // Unblock both handshake reads and socket writes before joining.
        worker?.interrupt()
        worker?.join(2_000)
        worker = null
        lock.withLock { queue.clear(); queuedBytes = 0 }
        closeSocket()
    }

    private fun copyBuffer(buffer: ByteBuffer): ByteArray = buffer.duplicate().let {
        val bytes = ByteArray(it.remaining()); it.get(bytes); bytes
    }

    private companion object {
        const val TAG = "RtmpStreamSink"

        fun makeAvcDecoderConfiguration(csd0: ByteArray, csd1: ByteArray?): ByteArray {
            if (csd0.size >= 7 && csd0[0].toInt() == 1) return csd0
            val nals = extractNals(csd0) + (csd1?.let(::extractNals) ?: emptyList())
            val sps = nals.firstOrNull { it.isNotEmpty() && it[0].toInt() and 0x1f == 7 } ?: return ByteArray(0)
            val pps = nals.firstOrNull { it.isNotEmpty() && it[0].toInt() and 0x1f == 8 } ?: return ByteArray(0)
            return byteArrayOf(1, sps[1], sps[2], sps[3], 0xff.toByte(), 0xe1.toByte(),
                (sps.size ushr 8).toByte(), sps.size.toByte()) + sps + byteArrayOf(1,
                (pps.size ushr 8).toByte(), pps.size.toByte()) + pps
        }

        fun annexBToLengthPrefixed(data: ByteArray): ByteArray {
            val nals = extractNals(data)
            if (nals.isEmpty()) return data
            val out = ByteArrayOutputStream()
            nals.forEach { out.write(byteArrayOf((it.size ushr 24).toByte(), (it.size ushr 16).toByte(), (it.size ushr 8).toByte(), it.size.toByte())); out.write(it) }
            return out.toByteArray()
        }

        fun extractNals(data: ByteArray): List<ByteArray> {
            val result = mutableListOf<ByteArray>(); var start = findStartCode(data, 0)
            if (start < 0) return emptyList()
            while (start >= 0) {
                val code = if (data[start + 2].toInt() == 1) 3 else 4
                val nalStart = start + code; val next = findStartCode(data, nalStart)
                val end = if (next >= 0) next else data.size
                if (end > nalStart) result += data.copyOfRange(nalStart, end)
                start = next
            }
            return result
        }

        fun findStartCode(data: ByteArray, from: Int): Int {
            for (i in from until data.size - 3) if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                (data[i + 2].toInt() == 1 || (data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1))) return i
            return -1
        }
    }
}
