package com.llawsxx.uvclivestreaming.recording

import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** A lease stays exclusive until decode, ordered delivery and GL upload finish. */
internal class DirectVideoBufferPool(
    private val maxCachedBuffers: Int,
    private val maxCachedBytes: Long = 32L * 1024 * 1024,
) : AutoCloseable {
    class Lease internal constructor(
        val buffer: ByteBuffer,
        private val owner: DirectVideoBufferPool,
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)
        val size: Int get() = buffer.capacity()
        override fun close() {
            if (released.compareAndSet(false, true)) owner.recycle(buffer)
        }
    }

    data class Diagnostics(val allocations: Long, val reuses: Long, val inUse: Int, val cached: Int)
    private val idle = ArrayDeque<ByteBuffer>()
    private var cachedBytes = 0L
    private var closed = false
    private var allocations = 0L
    private var reuses = 0L
    private var inUse = 0

    @Synchronized fun acquire(size: Int): Lease? {
        require(size > 0)
        if (closed) return null
        // Do not retain large buffers after a resolution change.
        var buffer: ByteBuffer? = null
        while (idle.isNotEmpty()) {
            val candidate = idle.removeFirst()
            cachedBytes -= candidate.capacity()
            if (candidate.capacity() == size) { buffer = candidate; break }
        }
        val result = buffer?.also { reuses++ } ?: ByteBuffer.allocateDirect(size).also { allocations++ }
        result.clear()
        inUse++
        return Lease(result, this)
    }

    @Synchronized private fun recycle(buffer: ByteBuffer) {
        inUse--
        if (!closed && idle.size < maxCachedBuffers && cachedBytes + buffer.capacity() <= maxCachedBytes) {
            buffer.clear()
            idle.addLast(buffer)
            cachedBytes += buffer.capacity()
        }
    }

    @Synchronized fun diagnostics() = Diagnostics(allocations, reuses, inUse, idle.size)

    @Synchronized override fun close() {
        closed = true
        idle.clear()
        cachedBytes = 0
        // Outstanding leases still own their memory; their later release cannot re-cache it.
    }
}
