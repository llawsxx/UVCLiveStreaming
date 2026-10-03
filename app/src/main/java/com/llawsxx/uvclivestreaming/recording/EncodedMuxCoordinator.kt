package com.llawsxx.uvclivestreaming.recording

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

internal interface EncodedMuxCoordinator {
    fun setVideoFormat(format: MediaFormat)
    fun setAudioFormat(format: MediaFormat)
    fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
    fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
    fun finish()
}

internal class MediaMuxCoordinator(
    private val muxer: android.media.MediaMuxer,
    private val needsAudio: Boolean,
    private val onStarted: () -> Unit,
) : EncodedMuxCoordinator {
    private val lock = Any()
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var finished = false
    private val pending = mutableListOf<PendingEncodedSample>()

    override fun setVideoFormat(format: MediaFormat) = synchronized(lock) {
        if (videoTrack < 0) videoTrack = muxer.addTrack(format)
        startIfReady()
    }

    override fun setAudioFormat(format: MediaFormat) = synchronized(lock) {
        if (audioTrack < 0) audioTrack = muxer.addTrack(format)
        startIfReady()
    }

    override fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = write(true, buffer, info)
    override fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = write(false, buffer, info)

    private fun write(video: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        if (finished) return
        val copy = ByteArray(info.size)
        buffer.duplicate().apply { position(info.offset); limit(info.offset + info.size) }.get(copy)
        val saved = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
        if (!started) pending += PendingEncodedSample(video, copy, saved)
        else writeNow(video, ByteBuffer.wrap(copy), saved)
    }

    private fun startIfReady() {
        if (started || videoTrack < 0 || (needsAudio && audioTrack < 0)) return
        muxer.start(); started = true
        pending.sortedBy { it.info.presentationTimeUs }.forEach { writeNow(it.video, ByteBuffer.wrap(it.data), it.info) }
        pending.clear(); onStarted()
    }

    private fun writeNow(video: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) =
        muxer.writeSampleData(if (video) videoTrack else audioTrack, buffer, info)

    override fun finish() {
        synchronized(lock) {
            if (finished) return
            finished = true; pending.clear()
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}

internal class NativeTsMuxCoordinator(
    private val muxer: NativeMpegTsMuxer,
    private val output: NativeTsOutput,
    private val needsAudio: Boolean,
    private val needsVideo: Boolean = true,
    private val onStarted: () -> Unit,
) : EncodedMuxCoordinator {
    private val lock = Any()
    private var videoReady = false
    private var audioReady = false
    private var started = false
    private var finished = false
    private val pending = mutableListOf<PendingEncodedSample>()

    override fun setVideoFormat(format: MediaFormat) = synchronized(lock) {
        if (!videoReady) { muxer.setVideoFormat(format); videoReady = true }
        startIfReady()
    }
    override fun setAudioFormat(format: MediaFormat) = synchronized(lock) { audioReady = true; startIfReady() }
    override fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = write(true, buffer, info)
    override fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = write(false, buffer, info)

    private fun write(video: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        if (finished) return
        val copy = ByteArray(info.size)
        buffer.duplicate().apply { position(info.offset); limit(info.offset + info.size) }.get(copy)
        val saved = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
        if (!started) pending += PendingEncodedSample(video, copy, saved)
        else writeNow(video, ByteBuffer.wrap(copy), saved)
    }

    private fun startIfReady() {
        if (started || (needsVideo && !videoReady) || (needsAudio && !audioReady)) return
        started = true
        pending.sortedBy { it.info.presentationTimeUs }.forEach { writeNow(it.video, ByteBuffer.wrap(it.data), it.info) }
        pending.clear(); onStarted()
    }

    private fun writeNow(video: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val keyFrame = video && info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val packets = if (video) muxer.writeVideo(buffer, info) else muxer.writeAudio(buffer, info)
        output.write(packets, info.presentationTimeUs, keyFrame)
    }

    override fun finish() {
        synchronized(lock) {
            if (finished) return
            finished = true; pending.clear(); muxer.close()
        }
    }
}

private data class PendingEncodedSample(val video: Boolean, val data: ByteArray, val info: MediaCodec.BufferInfo)
