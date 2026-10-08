package com.llawsxx.uvclivestreaming.recording

import java.io.DataInputStream
import java.io.File

internal class SyntheticMjpegVideo(file: File, width: Int, height: Int, count: Int) {
    val frames: Array<ByteArray>
    val size: Int

    init {
        DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == 0x4d4a5047)
            require(input.readInt() == width && input.readInt() == height)
            val available = input.readInt()
            require(available in count..10_000)
            frames = Array(available) {
                val length = input.readInt()
                require(length in 4..(width * height * 4))
                ByteArray(length).also { input.readFully(it) }
            }
            size = frames.maxOf { it.size }
        }
    }
}
