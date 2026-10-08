package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class RawVideoConverterTest {
    @Test fun capturedFormatsBorrowStorageWithoutCallingCpuConverter() {
        RawVideoConverter { _, _, _, _, _ -> error("must not repack captured buffers") }.use { converter ->
            val layouts = mapOf(2 to GpuVideoFrame.YUYV, 3 to GpuVideoFrame.UYVY, 4 to GpuVideoFrame.RGB,
                5 to GpuVideoFrame.NV12, 6 to GpuVideoFrame.I420, 7 to GpuVideoFrame.P010, 9 to GpuVideoFrame.BGR)
            for ((format, layout) in layouts) {
                val size = when (format) { 2, 3 -> 8; 4, 7, 9 -> 12; else -> 6 }
                val input = ByteBuffer.allocateDirect(size + 4).apply { limit(size); position(2) }
                var released = 0
                val capture = CapturedVideoBuffer(input) { released++ }
                val converted = converter.convert(capture, format, 2, 2, 123)!!
                assertEquals(0, released)
                assertEquals(layout, converted.frame.layout)
                assertEquals(size, converted.frame.byteSize)
                assertEquals(123L, converted.frame.timestampNs)
                assertEquals(format == 4 || format == 9, converted.frame.fullRange)
                val borrowed = converted.frame.directBuffer!!
                assertEquals(0, borrowed.position())
                assertEquals(size, borrowed.limit())
                assertEquals(2, input.position())
                input.put(0, 42)
                assertEquals(42, borrowed.get(0).toInt())
                converted.close(); converted.close()
                assertEquals(1, released)
            }
            assertEquals(0L, converter.diagnostics().allocations)
        }
    }

    @Test fun invalidAndClosedCapturedInputsReleaseExactlyOnce() {
        val converter = RawVideoConverter { _, _, _, _, _ -> error("must not convert") }
        val invalid = listOf(Triple(2, 3, 2), Triple(1, 2, 2), Triple(7, 2, 2), Triple(6, 0, 2))
        var released = 0
        for ((format, width, height) in invalid) {
            val input = ByteBuffer.allocateDirect(64).apply { limit(6) }
            assertNull(converter.convert(CapturedVideoBuffer(input) { released++ }, format, width, height, 0))
        }
        assertEquals(invalid.size, released)
        val held = converter.convert(CapturedVideoBuffer(ByteBuffer.allocateDirect(6)) { released++ }, 6, 2, 2, 0)!!
        converter.close()
        assertEquals(invalid.size, released)
        assertNull(converter.convert(CapturedVideoBuffer(ByteBuffer.allocateDirect(6)) { released++ }, 6, 2, 2, 0))
        held.close()
        assertEquals(invalid.size + 2, released)
        assertEquals(0L, converter.diagnostics().allocations)
        assertNull(GpuVideoFrame.fromRaw(ByteBuffer.allocate(6), 6, 2, 2, 0))
        assertNotNull(GpuVideoFrame.fromRaw(ByteBuffer.allocateDirect(17), 6, 3, 3, 0))
    }

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
                    val expectedSize = when (format) { 2, 3 -> 8; 4, 7, 9 -> 12; else -> 6 }
                    assertEquals(expectedSize, it.frame.directBuffer.capacity())
                    assertEquals(expectedSize, it.frame.byteSize)
                    assertEquals(1, it.frame.chromaWidth)
                    assertEquals(if (format == 2 || format == 3) 2 else 1, it.frame.chromaHeight)
                    assertEquals(123L, it.frame.timestampNs)
                    assertEquals(when (format) { 4 -> GpuVideoFrame.RGB; 9 -> GpuVideoFrame.BGR; 7 -> GpuVideoFrame.YUV10; else -> GpuVideoFrame.I420 }, it.frame.layout)
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
