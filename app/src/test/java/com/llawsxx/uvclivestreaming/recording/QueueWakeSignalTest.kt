package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class QueueWakeSignalTest {
    @Test fun signalBeforeAwaitIsNotLost() {
        val wake = QueueWakeSignal()
        val revision = wake.snapshot()
        repeat(3) { wake.signal() }
        assertTrue(wake.awaitChange(revision))
        assertEquals(revision + 3, wake.snapshot())
    }

    @Test fun unchangedRevisionBlocksUntilSignal() {
        val wake = QueueWakeSignal()
        val revision = wake.snapshot()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val changed = AtomicBoolean()
        val worker = Thread {
            started.countDown()
            changed.set(wake.awaitChange(revision))
            finished.countDown()
        }.apply { start() }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            wake.signal()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertTrue(changed.get())
        } finally { wake.close(); worker.join(2_000) }
    }

    @Test fun closeWakesAllConsumersAndRejectsFurtherWaits() {
        val wake = QueueWakeSignal()
        val started = CountDownLatch(3)
        val finished = CountDownLatch(3)
        val changed = AtomicBoolean()
        val revision = wake.snapshot()
        val workers = List(3) {
            Thread {
                started.countDown()
                if (wake.awaitChange(revision)) changed.set(true)
                finished.countDown()
            }.apply { start() }
        }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            wake.close()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertFalse(changed.get())
            wake.signal()
            assertFalse(wake.awaitChange(wake.snapshot()))
        } finally { wake.close(); workers.forEach { it.join(2_000) } }
    }

    @Test fun interruptUnblocksConsumer() {
        val wake = QueueWakeSignal()
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val worker = Thread {
            try {
                val revision = wake.snapshot()
                started.countDown()
                wake.awaitChange(revision)
            } catch (_: InterruptedException) { interrupted.countDown() }
        }.apply { start() }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            worker.interrupt()
            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
        } finally { wake.close(); worker.join(2_000) }
    }
}
