package com.llawsxx.uvclivestreaming.recording

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** One owner at a time: enqueue transfers ownership; close returns the native storage. */
class CapturedVideoBuffer internal constructor(
    val buffer: ByteBuffer,
    private val release: () -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    val size: Int get() = buffer.limit()

    // Called by JNI. The DirectByteBuffer exposes only the valid frame bytes.
    internal constructor(buffer: ByteBuffer, handle: Long) : this(buffer, {
        NativeUsbCapture.nativeReleaseVideoFrame(handle)
    })

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}
