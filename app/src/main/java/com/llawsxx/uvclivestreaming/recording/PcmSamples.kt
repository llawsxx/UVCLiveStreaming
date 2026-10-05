package com.llawsxx.uvclivestreaming.recording

import kotlin.math.round

/** Signed UAC subslots are little-endian, with valid bits aligned to the most significant bit. */
internal object PcmSamples {
    fun toPcm16(bytes: ByteArray, sampleBytes: Int): ByteArray {
        require(sampleBytes in 2..4 && bytes.size % sampleBytes == 0)
        if (sampleBytes == 2) return bytes
        val output = ByteArray(bytes.size / sampleBytes * 2)
        for (i in 0 until bytes.size / sampleBytes) {
            var raw = 0
            for (b in 0 until sampleBytes) raw = raw or ((bytes[i * sampleBytes + b].toInt() and 255) shl (b * 8))
            if (sampleBytes == 3) raw = (raw shl 8) shr 8
            val normalized = raw / if (sampleBytes == 3) 8388608f else 2147483648f
            val value = round(normalized.coerceIn(-1f, 32767f / 32768f) * 32768f).toInt()
            output[i * 2] = value.toByte()
            output[i * 2 + 1] = (value shr 8).toByte()
        }
        return output
    }
}
