package com.llawsxx.uvclivestreaming.recording

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One pending request, one published immutable table. Never runs GL or waits on a frame. */
internal class VideoColorLutWorker(
    initialSettings: VideoColorGradeSettings = VideoColorGradeSettings(),
    private val onError: (Throwable) -> Unit = {},
    private val baker: (VideoColorGradeSettings, () -> Boolean) -> BakedVideoColorLut? = VideoColorLutBaker::bake,
) : AutoCloseable {
    private val wake = ArrayBlockingQueue<Unit>(1)
    private val closed = AtomicBoolean()
    private val stopped = CountDownLatch(1)
    @Volatile private var requested = initialSettings.sanitized()
    @Volatile var current: BakedVideoColorLut? = null
        private set
    @Volatile var failure: Throwable? = null
        private set
    val ready: Boolean get() = !requested.active || current?.settings == requested
    private val thread = Thread({
        try {
            while (!closed.get()) {
                wake.take()
                if (closed.get()) break
                val request = requested
                if (!request.active) { current = null; continue }
                val result = baker(request) { closed.get() || requested !== request }
                // Synchronize only publication/settings changes, never table generation.
                synchronized(this) {
                    if (!closed.get() && requested === request) current = result
                }
            }
        } catch (_: InterruptedException) {
            // Normal close; cancellation also checked at each blue slice.
        } catch (error: Throwable) {
            if (!closed.get()) { failure = error; onError(error) }
        } finally { stopped.countDown() }
    }, "usb-color-lut").apply { isDaemon = true; start() }

    init { wake.offer(Unit) }

    @Synchronized
    fun update(settings: VideoColorGradeSettings) {
        if (closed.get() || settings == requested) return
        val next = settings.sanitized()
        if (next == requested) return
        requested = next
        if (!next.active) current = null
        wake.offer(Unit)
    }

    override fun close() {
        synchronized(this) { closed.set(true); current = null }
        thread.interrupt()
    }

    fun awaitStopped(timeoutMs: Long): Boolean = stopped.await(timeoutMs, TimeUnit.MILLISECONDS)
}
