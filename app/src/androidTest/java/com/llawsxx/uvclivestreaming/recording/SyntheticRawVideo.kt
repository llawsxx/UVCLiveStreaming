package com.llawsxx.uvclivestreaming.recording

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal class SyntheticRawVideo(val format: Int, val width: Int, val height: Int) {
    val colors = arrayOf(
        intArrayOf(255, 255, 255), intArrayOf(255, 255, 0), intArrayOf(0, 255, 255), intArrayOf(0, 255, 0),
        intArrayOf(255, 0, 255), intArrayOf(255, 0, 0), intArrayOf(0, 0, 255), intArrayOf(0, 0, 0),
        intArrayOf(0, 0, 0), intArrayOf(32, 32, 32), intArrayOf(128, 128, 128), intArrayOf(235, 235, 235),
    )
    private val samples = colors.map { rgb ->
        val red = rgb[0] / 255.0
        val green = rgb[1] / 255.0
        val blue = rgb[2] / 255.0
        val luma = 0.2126 * red + 0.7152 * green + 0.0722 * blue
        intArrayOf((16 + 219 * luma).roundToInt(),
            (128 + 224 * (blue - luma) / (2 * (1 - 0.0722))).roundToInt(),
            (128 + 224 * (red - luma) / (2 * (1 - 0.2126))).roundToInt())
    }
    val size = when (format) { 2, 3 -> width * height * 2; 4, 7, 9 -> width * height * 3; else -> width * height * 3 / 2 }
    val template: ByteBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN).apply {
        if (format == 2 || format == 3) {
            for (row in 0 until height) for (column in 0 until width step 2) {
                val first = samples[colorIndex(column, row)]
                val second = samples[colorIndex(column + 1, row)]
                if (format == 2) {
                    put(first[0].toByte()); put(first[1].toByte())
                    put(second[0].toByte()); put(first[2].toByte())
                } else {
                    put(first[1].toByte()); put(first[0].toByte())
                    put(first[2].toByte()); put(second[0].toByte())
                }
            }
        } else if (format == 4 || format == 9) {
            for (row in 0 until height) for (column in 0 until width) {
                val color = colors[colorIndex(column, row)]
                put(color[if (format == 4) 0 else 2].toByte())
                put(color[1].toByte())
                put(color[if (format == 4) 2 else 0].toByte())
            }
        } else {
            for (row in 0 until height) for (column in 0 until width) {
                val luma = samples[colorIndex(column, row)][0]
                if (format == 7) putShort((luma shl 8).toShort()) else put(luma.toByte())
            }
            if (format == 6) {
                for (component in 1..2) for (row in 0 until height step 2) for (column in 0 until width step 2)
                    put(samples[colorIndex(column, row)][component].toByte())
            } else {
                for (row in 0 until height step 2) for (column in 0 until width step 2) {
                    val color = samples[colorIndex(column, row)]
                    for (component in 1..2) {
                        if (format == 7) putShort((color[component] shl 8).toShort()) else put(color[component].toByte())
                    }
                }
            }
        }
        flip()
    }

    private fun colorIndex(column: Int, row: Int): Int = when {
        column < 256 && (row < 16 || row >= height - 16) -> 7
        row < height / 2 -> column * 8 / width
        else -> 8 + column * 4 / width
    }

    fun mark(buffer: ByteBuffer, frameId: Int) {
        for (row in (0 until 16) + (height - 16 until height)) {
            for (column in 0 until 256) {
                val white = frameId and (1 shl (column / 16)) != 0
                val pixel = row * width + column
                when (format) {
                    2, 3 -> buffer.put(pixel * 2 + if (format == 2) 0 else 1, (if (white) 235 else 16).toByte())
                    4, 9 -> for (component in 0..2) buffer.put(pixel * 3 + component, (if (white) 255 else 0).toByte())
                    7 -> buffer.putShort(pixel * 2, (((if (white) 235 else 16) shl 8) or ((frameId and 3) shl 6)).toShort())
                    else -> buffer.put(pixel, (if (white) 235 else 16).toByte())
                }
            }
        }
    }
}
