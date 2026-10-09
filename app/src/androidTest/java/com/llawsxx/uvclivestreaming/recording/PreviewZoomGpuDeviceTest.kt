package com.llawsxx.uvclivestreaming.recording

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class PreviewZoomGpuDeviceTest {
    private val width = 64
    private val height = 64

    private fun pixels(reader: ImageReader): IntArray {
        val deadline = SystemClock.elapsedRealtime() + 2_000
        while (SystemClock.elapsedRealtime() < deadline) {
            reader.acquireLatestImage()?.let { image ->
                return image.use {
                    val plane = it.planes[0]
                    IntArray(it.width * it.height) { i ->
                        val offset = i / it.width * plane.rowStride + i % it.width * plane.pixelStride
                        ((plane.buffer.get(offset).toInt() and 255) shl 16) or
                            ((plane.buffer.get(offset + 1).toInt() and 255) shl 8) or
                            (plane.buffer.get(offset + 2).toInt() and 255)
                    }
                }
            }
            Thread.sleep(5)
        }
        error("No GPU image")
    }

    @Test fun zoomedImageExpandsAcrossFormerBlackBarsOnTheFullSurface() {
        val source = ByteBuffer.allocateDirect(width * height * 3)
        repeat(width * height) { source.put(255.toByte()).put(0).put(0) }
        source.flip()
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).use { encoder ->
            ImageReader.newInstance(width, height * 2, PixelFormat.RGBA_8888, 2).use { preview ->
                GpuVideoRenderer(encoder.surface).use { gpu ->
                    listOf(PreviewZoom(), PreviewZoom(2f), PreviewZoom()).forEachIndexed { index, zoom ->
                        assertTrue(gpu.render(GpuVideoFrame(null, width, height, 1_000_000_000L + index,
                            GpuVideoFrame.RGB, true, source), GpuVideoRenderer.PreviewTarget(preview.surface, 0, zoom = zoom)))
                        val displayed = pixels(preview)
                        pixels(encoder)
                        assertEquals(0xff0000, displayed[64 * width + 32])
                        for (y in listOf(8, 120)) {
                            assertEquals("Former black bar at y=$y, scale=${zoom.scale}",
                                if (zoom.scale > 1f) 0xff0000 else 0, displayed[y * width + 32])
                        }
                    }
                }
            }
        }
    }

    @Test fun previewCropsTheRequestedCornerWhileEncoderAlwaysReceivesAllFourCorners() {
        val colors = intArrayOf(0xff0000, 0x00ff00, 0x0000ff, 0xffffff)
        val source = ByteBuffer.allocateDirect(width * height * 3)
        for (y in 0 until height) for (x in 0 until width) {
            val color = colors[(if (y >= height / 2) 2 else 0) + if (x >= width / 2) 1 else 0]
            source.put((color shr 16).toByte()).put((color shr 8).toByte()).put(color.toByte())
        }
        source.flip()
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).use { encoder ->
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).use { preview ->
                GpuVideoRenderer(encoder.surface).use { gpu ->
                    val crops = listOf(PreviewZoom(2f, .75f, .25f), PreviewZoom(2f, .25f, .75f), PreviewZoom())
                    crops.forEachIndexed { index, zoom ->
                        assertTrue(gpu.render(GpuVideoFrame(null, width, height, 1_000_000_000L + index,
                            GpuVideoFrame.RGB, true, source), GpuVideoRenderer.PreviewTarget(preview.surface, 0, zoom = zoom)))
                        val encoded = pixels(encoder)
                        val displayed = pixels(preview)
                        val points = listOf(8 to 8, 56 to 8, 8 to 56, 56 to 56)
                        points.forEachIndexed { corner, (x, y) ->
                            assertEquals("Encoder corner $corner at draw $index", colors[corner], encoded[y * width + x])
                            assertEquals("Preview corner $corner at draw $index",
                                if (index == 0) colors[1] else if (index == 1) colors[2] else colors[corner],
                                displayed[y * width + x])
                        }
                    }
                }
            }
        }
    }
}
