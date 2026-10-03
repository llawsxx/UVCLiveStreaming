package com.llawsxx.uvclivestreaming.recording

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import org.junit.Assert.*
import org.junit.Test

class RtmpProtocolTest {
    @Test fun outgoingMessagesUse128ByteChunksUntilNegotiated() {
        val bytes = ByteArrayOutputStream()
        val writer = RtmpChunkWriter(bytes)
        val connect = RtmpAmf.encode("connect", 1.0, mapOf("app" to "live", "padding" to "x".repeat(300)))
        writer.write(20, 0L, connect, 3, 0)
        assertEquals(0xC3, bytes.toByteArray()[12 + 128].toInt() and 255)
        val reader = RtmpChunkReader(ByteArrayInputStream(bytes.toByteArray()))
        assertArrayEquals(connect, reader.read().payload)
    }

    @Test fun negotiatedChunksAndExtendedTimestampsRoundTrip() {
        val bytes = ByteArrayOutputStream()
        val writer = RtmpChunkWriter(bytes)
        writer.setChunkSize(512)
        val media = ByteArray(1800) { it.toByte() }
        writer.write(9, 0xFFFFFFL, media, 6, 7)
        writer.write(9, 0xF1234567L, media, 6, 7)
        val reader = RtmpChunkReader(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(1, reader.read().type)
        assertEquals(512, reader.chunkSize)
        for (time in listOf(0xFFFFFFL, 0xF1234567L)) {
            val message = reader.read()
            assertEquals(time, message.timestamp)
            assertEquals(7, message.streamId)
            assertArrayEquals(media, message.payload)
        }
    }

    @Test fun receiverSupportsAllCompressedHeaderTypes() {
        val bytes = ByteArrayOutputStream()
        RtmpChunkWriter(bytes).write(20, 100L, byteArrayOf(1, 2), 3, 7)
        bytes.write(byteArrayOf(0x43, 0, 0, 10, 0, 0, 2, 20, 3, 4)) // fmt 1
        bytes.write(byteArrayOf(0x83.toByte(), 0, 0, 5, 5, 6)) // fmt 2
        bytes.write(byteArrayOf(0xC3.toByte(), 7, 8)) // fmt 3 starts a new message
        val reader = RtmpChunkReader(ByteArrayInputStream(bytes.toByteArray()))
        for ((time, payload) in listOf(100L to byteArrayOf(1, 2), 110L to byteArrayOf(3, 4),
                115L to byteArrayOf(5, 6), 120L to byteArrayOf(7, 8))) {
            val message = reader.read()
            assertEquals(time, message.timestamp)
            assertEquals(7, message.streamId)
            assertArrayEquals(payload, message.payload)
        }
    }

    @Test fun receiverReassemblesInterleavedChunkStreams() {
        val payload = ByteArray(300) { it.toByte() }
        val longMessage = ByteArrayOutputStream().also { RtmpChunkWriter(it).write(20, 0L, payload, 3, 0) }.toByteArray()
        val ping = ByteArrayOutputStream().also { RtmpChunkWriter(it).write(4, 0L, byteArrayOf(0, 6, 0, 0, 0, 9), 2, 0) }.toByteArray()
        val wire = longMessage.copyOfRange(0, 140) + ping + longMessage.copyOfRange(140, longMessage.size)
        val reader = RtmpChunkReader(ByteArrayInputStream(wire))
        assertEquals(4, reader.read().type)
        assertArrayEquals(payload, reader.read().payload)
        assertEquals(wire.size.toLong(), reader.bytesRead)
    }

    @Test fun largeServerChunkStreamIdsAreSupported() {
        val standard = ByteArrayOutputStream().also { RtmpChunkWriter(it).write(20, 0L, byteArrayOf(1), 3, 0) }.toByteArray()
        for (header in listOf(byteArrayOf(0, 0), byteArrayOf(1, 5, 1))) {
            val wire = header + standard.copyOfRange(1, standard.size)
            assertArrayEquals(byteArrayOf(1), RtmpChunkReader(ByteArrayInputStream(wire)).read().payload)
        }
    }

    @Test fun amfDecodesTransactionsAndStreamIdsStructurally() {
        val info = mapOf("code" to "NetStream.Publish.Start", "level" to "status", "description" to "推流成功")
        assertEquals(listOf("onStatus", 0.0, null, info), RtmpAmf.decode(RtmpAmf.encode("onStatus", 0.0, null, info)))
        assertEquals(listOf("_result", 2.0, null, 7.0), RtmpAmf.decode(RtmpAmf.encode("_result", 2.0, null, 7.0)))
    }

    @Test(expected = EOFException::class) fun truncatedMessagesFailRatherThanReturningPartialCommands() {
        val wire = ByteArrayOutputStream().also { RtmpChunkWriter(it).write(20, 0L, ByteArray(300), 3, 0) }.toByteArray()
        RtmpChunkReader(ByteArrayInputStream(wire.copyOf(wire.size - 1))).read()
    }
}
