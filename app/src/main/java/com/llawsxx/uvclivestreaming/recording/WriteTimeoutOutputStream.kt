package com.llawsxx.uvclivestreaming.recording

import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Watches actual socket writes, without timing idle reads or a writer's monitor acquisition. */
internal class WriteTimeoutOutputStream(
    private val output: OutputStream,
    private val timeoutMs: Long,
    private val abortWrite: () -> Unit,
) : OutputStream() {
    private val monitor = Any()
    private var writeStartedNs: Long? = null
    private var timeoutError: SocketTimeoutException? = null
    private var closed = false
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "rtmp-write-timeout").apply { isDaemon = true }
    }

    init {
        require(timeoutMs > 0)
        watchdog.scheduleAtFixedRate(::checkTimeout, timeoutMs.coerceAtMost(250),
            timeoutMs.coerceAtMost(250), TimeUnit.MILLISECONDS)
    }

    private fun checkTimeout() = synchronized(monitor) {
        if (closed || timeoutError != null) return@synchronized
        val started = writeStartedNs ?: return@synchronized
        if (System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(timeoutMs)) {
            timeoutError = SocketTimeoutException("RTMP 发送超时（${timeoutMs / 1_000.0} 秒）")
            writeStartedNs = null
            // Only close the socket here: never join the writer/receiver from the watchdog.
            runCatching(abortWrite)
        }
    }

    private fun writeWithTimeout(write: () -> Unit) {
        synchronized(monitor) {
            timeoutError?.let { throw it }
            if (closed) throw IOException("RTMP output is closed")
            writeStartedNs = System.nanoTime()
        }
        try {
            write()
        } catch (error: IOException) {
            val timeout = synchronized(monitor) { timeoutError }
            if (timeout != null) throw SocketTimeoutException(timeout.message).apply { initCause(error) }
            throw error
        } finally {
            synchronized(monitor) { writeStartedNs = null }
        }
        synchronized(monitor) { timeoutError?.let { throw it } }
    }

    override fun write(value: Int) = writeWithTimeout { output.write(value) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) =
        writeWithTimeout { output.write(bytes, offset, length) }
    override fun flush() = writeWithTimeout { output.flush() }

    override fun close() {
        synchronized(monitor) {
            if (closed) return
            closed = true
            writeStartedNs = null
        }
        watchdog.shutdownNow()
        output.close()
    }
}
