package com.llawsxx.uvclivestreaming.recording

/** Retain the capture clock (and any smoothing correction) with a constant offset before AAC encoding. */
internal fun pcmTimestampWithDelayNs(timestampNs: Long, delayMs: Int): Long =
    timestampNs + delayMs.coerceIn(-500, 500) * 1_000_000L
