package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test

class DirectVideoBufferPoolTest {
    @Test fun leasesAreExclusiveAndDuplicateReleaseDoesNotDuplicateBuffer() {
        DirectVideoBufferPool(3).use { pool ->
            val first = pool.acquire(6)!!
            val second = pool.acquire(6)!!
            assertTrue(first.buffer.isDirect)
            assertNotSame(first.buffer, second.buffer)
            first.close(); first.close()
            val third = pool.acquire(6)!!
            val fourth = pool.acquire(6)!!
            assertSame(first.buffer, third.buffer)
            assertNotSame(third.buffer, fourth.buffer)
            second.close(); third.close(); fourth.close()
            assertEquals(0, pool.diagnostics().inUse)
            assertEquals(3, pool.diagnostics().cached)
        }
    }

    @Test fun cacheIsBoundedAndOldResolutionIsDiscarded() {
        DirectVideoBufferPool(2, 12).use { pool ->
            val leases = List(4) { pool.acquire(6)!! }
            leases.forEach { it.close() }
            assertEquals(2, pool.diagnostics().cached)
            pool.acquire(3)!!.use { assertEquals(3, it.buffer.capacity()) }
            assertEquals(1, pool.diagnostics().cached)
        }
    }

    @Test fun closingPoolDoesNotInvalidateOutstandingBuffer() {
        val pool = DirectVideoBufferPool(2)
        val held = pool.acquire(6)!!
        held.buffer.put(0, 42)
        pool.close()
        assertNull(pool.acquire(6))
        assertEquals(42, held.buffer.get(0).toInt())
        held.close()
        assertEquals(0, pool.diagnostics().inUse)
        assertEquals(0, pool.diagnostics().cached)
    }
}
