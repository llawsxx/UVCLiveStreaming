package com.llawsxx.uvclivestreaming.recording

internal class QueueWakeSignal : AutoCloseable {
    private val monitor = Object()
    private var revision = 0L
    private var closed = false

    fun snapshot(): Long = synchronized(monitor) { revision }

    fun signal() = synchronized(monitor) {
        if (!closed) {
            revision++
            monitor.notifyAll()
        }
    }

    fun awaitChange(observed: Long): Boolean = synchronized(monitor) {
        while (!closed && revision == observed) monitor.wait()
        !closed
    }

    override fun close() = synchronized(monitor) {
        closed = true
        monitor.notifyAll()
    }
}
