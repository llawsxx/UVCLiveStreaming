package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test

class RawVideoConverterTest {
    @Test fun formatsKeepTheirSizeLayoutRangeAndTimestamp() {
        RawVideoConverter { _, _, _, _, output ->
            for (i in 0 until output.capacity()) output.put(i, i.toByte())
            true
        }.use { converter ->
            for (format in listOf(2, 3, 5, 6, 7, 4, 9)) {
                val inputSize = when (format) { 2, 3 -> 8; 4, 7, 9 -> 12; else -> 6 }
                converter.convert(ByteArray(inputSize), format, 2, 2, 123L)!!.use {
                    assertNull(it.frame.bytes)
                    assertTrue(it.frame.directBuffer!!.isDirect)
                    assertEquals(if (format == 4 || format == 9) 12 else 6, it.frame.directBuffer.capacity())
                    assertEquals(123L, it.frame.timestampNs)
                    assertEquals(when (format) { 4 -> GpuVideoFrame.RGB; 9 -> GpuVideoFrame.BGR; else -> GpuVideoFrame.I420 }, it.frame.layout)
                    assertEquals(format == 4 || format == 9, it.frame.fullRange)
                }
                assertEquals(0, converter.diagnostics().inUse)
            }
        }
    }

    @Test fun heldFramesCannotBeOverwrittenAndReleasedBuffersAreReused() {
        RawVideoConverter { bytes, _, _, _, output -> output.put(0, bytes[0]); true }.use { converter ->
            val first = converter.convert(ByteArray(6) { 11 }, 6, 2, 2, 1)!!
            val firstBuffer = first.frame.directBuffer!!
            val second = converter.convert(ByteArray(6) { 22 }, 6, 2, 2, 2)!!
            assertNotSame(firstBuffer, second.frame.directBuffer)
            assertEquals(11, firstBuffer.get(0).toInt())
            first.close()
            converter.convert(ByteArray(6) { 33 }, 6, 2, 2, 3)!!.use { third ->
                assertSame(firstBuffer, third.frame.directBuffer)
                assertEquals(33, firstBuffer.get(0).toInt())
                assertEquals(22, second.frame.directBuffer!!.get(0).toInt())
            }
            second.close()
            assertEquals(2L, converter.diagnostics().allocations)
            assertEquals(1L, converter.diagnostics().reuses)
            assertEquals(0, converter.diagnostics().inUse)
            assertEquals(1, converter.diagnostics().cached)
        }
    }

    @Test fun invalidGeometryFormatAndShortInputAreRejectedBeforeAllocation() {
        RawVideoConverter { _, _, _, _, _ -> error("must not convert invalid input") }.use { converter ->
            for (format in listOf(0, 1, 8, 10)) assertNull(converter.convert(ByteArray(12), format, 2, 2, 0))
            for (format in listOf(2, 3, 5, 7)) assertNull(converter.convert(ByteArray(64), format, 3, 2, 0))
            for (format in listOf(2, 3, 4, 5, 6, 7, 9)) assertNull(converter.convert(ByteArray(5), format, 2, 2, 0))
            assertNull(converter.convert(ByteArray(6), 6, 0, 2, 0))
            assertNull(converter.convert(ByteArray(6), 6, 3841, 2, 0))
            assertNull(converter.convert(ByteArray(6), 6, 2, 2161, 0))
            assertEquals(0L, converter.diagnostics().allocations)
        }
    }

    @Test fun failedConversionAndExceptionsReturnTheirLease() {
        RawVideoConverter { bytes, _, _, _, _ ->
            if (bytes[0].toInt() == 1) error("conversion failed")
            false
        }.use { converter ->
            assertNull(converter.convert(ByteArray(6), 6, 2, 2, 0))
            assertEquals(0, converter.diagnostics().inUse)
            try {
                converter.convert(ByteArray(6) { 1 }, 6, 2, 2, 0)
                fail("must propagate converter failure")
            } catch (_: IllegalStateException) {}
            assertEquals(0, converter.diagnostics().inUse)
            assertEquals(1L, converter.diagnostics().allocations)
            assertEquals(1L, converter.diagnostics().reuses)
        }
    }

    @Test fun closingDoesNotInvalidateFrameHeldByRenderer() {
        val converter = RawVideoConverter { _, _, _, _, output -> output.put(0, 42); true }
        val frame = converter.convert(ByteArray(6), 6, 2, 2, 0)!!
        converter.close()
        assertNull(converter.convert(ByteArray(6), 6, 2, 2, 0))
        assertEquals(42, frame.frame.directBuffer!!.get(0).toInt())
        frame.close(); frame.close()
        assertEquals(0, converter.diagnostics().inUse)
        assertEquals(0, converter.diagnostics().cached)
    }
}
