package com.llawsxx.uvclivestreaming.recording

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.security.SecureRandom

/** RTMP transport, independent of Android and media encoding. */
internal class RtmpConnection(
    url: String,
    private val onStatus: (String) -> Unit = {},
) : Closeable {
    private val uri = URI(url)
    private val socket = Socket()
    private lateinit var reader: RtmpChunkReader
    private lateinit var writer: RtmpChunkWriter
    private var receiver: Thread? = null
    private var ackWindow = 0L
    private var lastAck = 0L
    @Volatile private var closed = false
    @Volatile private var receiveFailure: Throwable? = null
    var streamId = 0
        private set

    fun open() {
        try {
            val host = requireNotNull(uri.host) { "RTMP 地址缺少主机名" }
            val parts = uri.path.trim('/').split('/').filter(String::isNotEmpty)
            require(parts.size >= 2) { "RTMP 地址应为 rtmp://主机/应用/串流密钥" }
            val app = parts.first()
            val key = parts.drop(1).joinToString("/") + (uri.rawQuery?.let { "?$it" } ?: "")
            val tcUrl = "${uri.scheme}://${uri.rawAuthority}/$app"
            onStatus("TCP connect")
            socket.tcpNoDelay = true
            socket.soTimeout = 10_000
            socket.connect(InetSocketAddress(host, if (uri.port > 0) uri.port else 1935), 10_000)
            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            onStatus("handshake")
            val c1 = ByteArray(1536).also { SecureRandom().nextBytes(it) }
            ByteBuffer.wrap(c1).putInt((System.currentTimeMillis() / 1000L).toInt()).putInt(0)
            output.write(3); output.write(c1); output.flush()
            val handshake = DataInputStream(input)
            require(handshake.readUnsignedByte() == 3) { "RTMP 版本不受支持" }
            val s1 = ByteArray(1536).also(handshake::readFully)
            output.write(s1); output.flush()
            handshake.readFully(ByteArray(1536))
            reader = RtmpChunkReader(input)
            writer = RtmpChunkWriter(output)
            reader.onChunkRead = ::acknowledgeIfNeeded
            // Until this message is sent the peer expects 128-byte outgoing chunks.
            writer.setChunkSize(4096)
            onStatus("connect")
            command("connect", 1.0, mapOf(
                "app" to app, "type" to "nonprivate", "tcUrl" to tcUrl,
                "flashVer" to "FMLE/3.0 (compatible; FMSc/1.0)", "fpad" to false,
                "capabilities" to 15.0, "audioCodecs" to 4071.0, "videoCodecs" to 252.0,
                "videoFunction" to 1.0,
            ))
            awaitResult(1.0)
            // FMLE/FFmpeg publishing preamble. These replies are optional; createStream
            // is matched by transaction ID even when the server replies to them first.
            command("releaseStream", 2.0, null, key)
            command("FCPublish", 3.0, null, key)
            onStatus("createStream")
            command("createStream", 4.0, null)
            val result = awaitResult(4.0)
            val id = (result.getOrNull(3) as? Number)?.toDouble()
            require(id != null && id.isFinite() && id >= 1 && id <= Int.MAX_VALUE && id == id.toInt().toDouble()) {
                "RTMP createStream 未返回有效的 stream id"
            }
            streamId = id.toInt()
            onStatus("publish")
            writer.write(20, 0L, RtmpAmf.encode("publish", 0.0, null, key, "live"), 8, streamId)
            awaitPublish()
            // No read timeout during publishing: idle servers may send no packets for minutes.
            socket.soTimeout = 0
            onStatus("NetStream.Publish.Start (streamId=$streamId)")
            receiver = Thread({
                try {
                    while (!closed) receiveCommand()
                } catch (error: Throwable) {
                    if (!closed) {
                        receiveFailure = error
                        runCatching { socket.close() }
                    }
                }
            }, "rtmp-receiver").apply { isDaemon = true; start() }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun ensureActive() {
        receiveFailure?.let { throw IOException("RTMP 接收失败：${it.message}", it) }
        check(!closed) { "RTMP connection is closed" }
    }

    fun send(type: Int, timestampMs: Long, payload: ByteArray, csid: Int) {
        ensureActive()
        writer.write(type, timestampMs, payload, csid, streamId)
    }

    private fun command(name: String, transaction: Double, obj: Map<String, Any?>?, vararg tail: String) {
        writer.write(20, 0L, RtmpAmf.encode(name, transaction, obj, *tail), 3, 0)
    }

    private fun awaitResult(transaction: Double): List<Any?> {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            val values = receiveCommand() ?: continue
            if ((values.getOrNull(1) as? Number)?.toDouble() != transaction) continue
            if (values.firstOrNull() == "_error") throw IOException("RTMP command rejected: ${values.lastOrNull()}")
            if (values.firstOrNull() == "_result") return values
        }
        throw IOException("RTMP command response timeout (transaction=$transaction)")
    }

    private fun awaitPublish() {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            val values = receiveCommand() ?: continue
            if (values.firstOrNull() != "onStatus") continue
            val status = values.getOrNull(3) as? Map<*, *> ?: continue
            if (status["code"] == "NetStream.Publish.Start") return
        }
        throw IOException("RTMP publish response timeout")
    }

    private fun receiveCommand(): List<Any?>? {
        val message = reader.read()
        when (message.type) {
            1 -> writer.setChunkSize(reader.chunkSize)
            4 -> {
                val data = message.payload
                require(data.size >= 2) { "Invalid RTMP User Control" }
                val event = ((data[0].toInt() and 255) shl 8) or (data[1].toInt() and 255)
                if (event == 6) {
                    require(data.size == 6) { "Invalid RTMP PingRequest" }
                    writer.write(4, 0L, byteArrayOf(0, 7) + data.copyOfRange(2, 6), 2, 0)
                }
            }
            5 -> {
                require(message.payload.size == 4) { "Invalid RTMP acknowledgement window" }
                val window = RtmpAmf.uint32(message.payload)
                require(window > 0) { "Invalid RTMP acknowledgement window" }
                // Like FFmpeg, report before the peer reaches its complete window.
                ackWindow = (window / 2).coerceAtLeast(1)
                acknowledgeIfNeeded()
            }
            6 -> {
                require(message.payload.size == 5) { "Invalid RTMP peer bandwidth" }
                writer.write(5, 0L, message.payload.copyOfRange(0, 4), 2, 0)
            }
            17, 20 -> {
                val payload = if (message.type == 17) {
                    require(message.payload.firstOrNull() == 0.toByte()) { "Unsupported AMF3 command" }
                    message.payload.copyOfRange(1, message.payload.size)
                } else message.payload
                val values = RtmpAmf.decode(payload)
                if (values.firstOrNull() == "onStatus") {
                    val status = values.getOrNull(3) as? Map<*, *>
                    val code = status?.get("code") as? String
                    if (code != null) onStatus(code)
                    if (status?.get("level") == "error" || code in setOf(
                            "NetStream.Publish.BadName", "NetStream.Publish.Failed",
                            "NetStream.Publish.Denied", "NetStream.Unpublish.Success", "NetConnection.Connect.Closed",
                        )) throw IOException("RTMP $code: ${status?.get("description") ?: "server rejected publishing"}")
                }
                return values
            }
        }
        return null
    }

    private fun acknowledgeIfNeeded() {
        if (ackWindow > 0 && reader.bytesRead - lastAck >= ackWindow) {
            lastAck = reader.bytesRead
            writer.write(3, 0L, RtmpAmf.uint32Bytes(lastAck), 2, 0)
        }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
        if (Thread.currentThread() !== receiver) receiver?.join(1_000)
        receiver = null
    }
}
