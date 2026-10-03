package com.llawsxx.uvclivestreaming.recording

import java.io.Serializable

enum class FocusDistanceUnit : Serializable { M, CM, MM, DIOPTER }
data class FocusDistancePreset(val valueText: String, val unit: FocusDistanceUnit) : Serializable

internal fun parseShutterExposureNs(text: String): Long? = text.trim().let {
    when {
        it.endsWith("ms", true) -> it.dropLast(2).trim().toDoubleOrNull()?.times(1_000_000L)?.toLong()
        it.endsWith("us", true) -> it.dropLast(2).trim().toDoubleOrNull()?.times(1_000L)?.toLong()
        else -> it.toLongOrNull()
    }
}

internal fun parseFocusDistanceDiopters(text: String, unit: FocusDistanceUnit): Float? {
    val value = text.trim().toFloatOrNull() ?: return null
    return when (unit) {
        FocusDistanceUnit.M -> if (value > 0f) 1f / value else null
        FocusDistanceUnit.CM -> if (value > 0f) 100f / value else null
        FocusDistanceUnit.MM -> if (value > 0f) 1000f / value else null
        FocusDistanceUnit.DIOPTER -> value.takeIf { it >= 0f }
    }
}
