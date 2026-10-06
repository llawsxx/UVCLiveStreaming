package com.llawsxx.uvclivestreaming.recording

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class WriteTimeoutOutputStreamTest {
    private class BlockingOutput(private val blockFlush: Boolean = false) : OutputStream() {
        val started = CountDownLatch(1)
        private val stopped = CountDownLatch(1)
        private fun block() {
            started.countDown()
            if (!stopped.await(3, TimeUnit.SECONDS)) throw AssertionError("Write was never aborted")
            throw IOException("Socket closed")
        }
        override fun write(value: Int) { if (!blockFlush) block() }
        override fun flush() { if (blockFlush) block() }
        override fun close() { stopped.countDown() }
    }

    private fun verifyBlockedOperation(blockFlush: Boolean) {
        val output = BlockingOutput(blockFlush)
        val executor = Executors.newSingleThreadExecutor()
        val aborted = CountDownLatch(1)
        try {
            WriteTimeoutOutputStream(output, 100) { output.close(); aborted.countDown() }.use { stream ->
                val writing = executor.submit<IOException> {
                    try {
                        if (blockFlush) stream.flush() else stream.write(byteArrayOf(1, 2, 3))
                        throw AssertionError("Blocked operation should time out")
                    } catch (error: IOException) { error }
                }
                assertTrue(output.started.await(1, TimeUnit.SECONDS))
                assertTrue(aborted.await(2, TimeUnit.SECONDS))
                val error = writing.get(1, TimeUnit.SECONDS)
                assertTrue(error is SocketTimeoutException)
                assertTrue(error.message!!.contains("RTMP 发送超时"))
                assertTrue(error.cause is IOException)
            }
        } finally { output.close(); executor.shutdownNow() }
    }

    @Test fun stalledWriteIsAbortedAndReportedAsSendTimeout() = verifyBlockedOperation(false)
    @Test fun stalledFlushIsAlsoAborted() = verifyBlockedOperation(true)

    @Test fun idleConnectionDoesNotTimeOutAndCanSendAgain() {
        val output = ByteArrayOutputStream()
        val aborted = CountDownLatch(1)
        WriteTimeoutOutputStream(output, 50) { aborted.countDown() }.use { stream ->
            stream.write(byteArrayOf(1, 2))
            assertFalse(aborted.await(200, TimeUnit.MILLISECONDS))
            stream.write(3)
            stream.flush()
            assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        }
        assertFalse(aborted.await(100, TimeUnit.MILLISECONDS))
    }

    @Test fun normalCloseUnblocksWriteWithoutTriggeringWatchdog() {
        val output = BlockingOutput()
        val executor = Executors.newSingleThreadExecutor()
        val aborted = CountDownLatch(1)
        val stream = WriteTimeoutOutputStream(output, 500) { output.close(); aborted.countDown() }
        try {
            val writing = executor.submit<IOException> {
                try { stream.write(1); throw AssertionError("Closed write should fail") }
                catch (error: IOException) { error }
            }
            assertTrue(output.started.await(1, TimeUnit.SECONDS))
            stream.close()
            assertFalse(writing.get(1, TimeUnit.SECONDS) is SocketTimeoutException)
            assertFalse(aborted.await(600, TimeUnit.MILLISECONDS))
        } finally { stream.close(); executor.shutdownNow() }
    }
}
