package com.llawsxx.uvclivestreaming.recording

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

internal data class RtmpMessage(
    val type: Int, val timestamp: Long, val streamId: Int, val payload: ByteArray,
)

/** One reader per connection. Chunk headers and partial messages belong to each CSID. */
internal class RtmpChunkReader(private val input: InputStream) {
    private class State {
        var initialized = false
        var timestamp = 0L
        var delta = 0L
        var length = 0
        var type = 0
        var streamId = 0
        var extended = false
        var received = 0
        var payload = ByteArray(0)
    }

    private val streams = HashMap<Int, State>()
    var chunkSize = 128
        private set
    var bytesRead = 0L
        private set
    var onChunkRead: (() -> Unit)? = null

    fun read(): RtmpMessage {
        while (true) {
            val basic = byte()
            val fmt = basic ushr 6
            val csid = when (val id = basic and 63) {
                0 -> 64 + byte()
                1 -> 64 + byte() + (byte() shl 8)
                else -> id
            }
            val s = streams.getOrPut(csid) { State() }
            val continuation = s.initialized && s.received < s.length
            require(fmt == 3 || !continuation) { "RTMP chunk interrupted an unfinished message (csid=$csid)" }
            require(fmt == 0 || s.initialized) { "RTMP chunk has no previous header (fmt=$fmt, csid=$csid)" }
            var time = 0L
            when (fmt) {
                0, 1, 2 -> {
                    time = u24().toLong()
                    s.extended = time == 0xFFFFFFL
                    if (fmt <= 1) {
                        s.length = u24()
                        s.type = byte()
                        require(s.length <= 16 * 1024 * 1024) { "RTMP message too large" }
                    }
                    if (fmt == 0) s.streamId = le32()
                }
            }
            if (s.extended) time = u32()
            if (!continuation) {
                when (fmt) {
                    0 -> { s.timestamp = time; s.delta = time }
                    1, 2 -> { s.delta = time; s.timestamp = (s.timestamp + time) and 0xFFFFFFFFL }
                    3 -> { s.timestamp = (s.timestamp + s.delta) and 0xFFFFFFFFL }
                }
                s.received = 0
                s.payload = ByteArray(s.length)
                s.initialized = true
            }
            val count = minOf(chunkSize, s.length - s.received)
            fully(s.payload, s.received, count)
            s.received += count
            if (s.received == s.length) {
                val message = RtmpMessage(s.type, s.timestamp, s.streamId, s.payload)
                when (s.type) {
                    1 -> {
                        require(message.payload.size == 4) { "Invalid RTMP Set Chunk Size" }
                        val size = RtmpAmf.uint32(message.payload)
                        require(size in 1..0x7FFFFFFFL) { "Invalid RTMP chunk size: $size" }
                        chunkSize = size.toInt()
                    }
                    2 -> {
                        require(message.payload.size == 4) { "Invalid RTMP Abort Message" }
                        streams[RtmpAmf.uint32(message.payload).toInt()]?.let { it.received = it.length }
                    }
                }
                onChunkRead?.invoke()
                return message
            }
            onChunkRead?.invoke()
        }
    }

    private fun byte(): Int = input.read().also {
        if (it < 0) throw EOFException("RTMP server closed the connection")
        bytesRead++
    }
    private fun u24(): Int = (byte() shl 16) or (byte() shl 8) or byte()
    private fun le32(): Int = byte() or (byte() shl 8) or (byte() shl 16) or (byte() shl 24)
    private fun u32(): Long = (byte().toLong() shl 24) or (byte().toLong() shl 16) or (byte().toLong() shl 8) or byte().toLong()
    private fun fully(target: ByteArray, offset: Int, size: Int) {
        var read = 0
        while (read < size) {
            val n = input.read(target, offset + read, size - read)
            if (n < 0) throw EOFException("RTMP message truncated")
            read += n
            bytesRead += n
        }
    }
}

/** Serializes complete messages so reader-thread control replies cannot split media chunks. */
internal class RtmpChunkWriter(private val output: OutputStream) {
    private var chunkSize = 128

    @Synchronized fun setChunkSize(size: Int) {
        require(size > 0)
        write(1, 0L, RtmpAmf.uint32Bytes(size.toLong()), 2, 0)
        chunkSize = size
    }

    @Synchronized fun write(type: Int, timestamp: Long, payload: ByteArray, csid: Int, streamId: Int) {
        require(csid in 2..63)
        require(payload.size <= 0xFFFFFF)
        val time = timestamp.coerceAtLeast(0L) and 0xFFFFFFFFL
        val extended = time >= 0xFFFFFF
        var offset = 0
        do {
            output.write(csid or if (offset == 0) 0 else 0xC0)
            if (offset == 0) {
                u24(if (extended) 0xFFFFFF else time.toInt())
                u24(payload.size)
                output.write(type)
                repeat(4) { output.write(streamId ushr (it * 8)) }
            }
            if (extended) output.write(RtmpAmf.uint32Bytes(time))
            val count = minOf(chunkSize, payload.size - offset)
            output.write(payload, offset, count)
            offset += count
        } while (offset < payload.size)
        output.flush()
    }

    private fun u24(value: Int) {
        output.write(value ushr 16); output.write(value ushr 8); output.write(value)
    }
}

internal object RtmpAmf {
    fun encode(vararg values: Any?): ByteArray = ByteArrayOutputStream().also { bytes ->
        val out = DataOutputStream(bytes)
        values.forEach { writeValue(out, it) }
    }.toByteArray()

    private fun writeValue(out: DataOutputStream, value: Any?) {
        when (value) {
            null -> out.writeByte(5)
            is String -> { out.writeByte(2); writeString(out, value) }
            is Number -> { out.writeByte(0); out.writeDouble(value.toDouble()) }
            is Boolean -> { out.writeByte(1); out.writeByte(if (value) 1 else 0) }
            is Map<*, *> -> {
                out.writeByte(3)
                value.forEach { (key, item) -> writeString(out, key.toString()); writeValue(out, item) }
                out.writeShort(0); out.writeByte(9)
            }
            else -> error("Unsupported AMF value")
        }
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65535)
        out.writeShort(bytes.size); out.write(bytes)
    }

    fun decode(bytes: ByteArray): List<Any?> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val values = mutableListOf<Any?>()
        while (input.available() > 0) values += readValue(input, input.readUnsignedByte(), 0)
        return values
    }

    private fun readValue(input: DataInputStream, marker: Int, depth: Int): Any? {
        require(depth < 32) { "AMF nesting too deep" }
        return when (marker) {
            0 -> input.readDouble()
            1 -> input.readUnsignedByte() != 0
            2 -> readString(input, input.readUnsignedShort())
            3, 8 -> {
                if (marker == 8) input.readInt() // ECMA array count is advisory.
                val map = linkedMapOf<String, Any?>()
                while (true) {
                    val length = input.readUnsignedShort()
                    val key = readString(input, length)
                    val next = input.readUnsignedByte()
                    if (length == 0 && next == 9) break
                    map[key] = readValue(input, next, depth + 1)
                }
                map
            }
            5, 6 -> null
            10 -> {
                val size = input.readInt()
                require(size in 0..input.available()) { "Invalid AMF array length" }
                List(size) { readValue(input, input.readUnsignedByte(), depth + 1) }
            }
            11 -> input.readDouble().also { input.readShort() }
            12 -> readString(input, input.readInt())
            else -> error("Unsupported AMF marker: $marker")
        }
    }

    private fun readString(input: DataInputStream, size: Int): String {
        require(size in 0..input.available()) { "Invalid AMF string length" }
        return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
    }

    fun uint32(bytes: ByteArray, offset: Int = 0): Long {
        require(offset >= 0 && offset + 4 <= bytes.size)
        return (0..3).fold(0L) { value, i -> (value shl 8) or (bytes[offset + i].toLong() and 255) }
    }

    fun uint32Bytes(value: Long): ByteArray = ByteArray(4) { (value ushr (24 - it * 8)).toByte() }
}
