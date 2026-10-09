package com.llawsxx.uvclivestreaming.recording

import java.io.Serializable

/** Source coordinates start at the top left; scale is relative to fitting the whole image. */
internal data class PreviewZoom(
    val scale: Float = 1f,
    val centerX: Float = .5f,
    val centerY: Float = .5f,
) : Serializable {
    val left: Float get() = centerX - .5f / scale
    val top: Float get() = centerY - .5f / scale
    val right: Float get() = centerX + .5f / scale
    val bottom: Float get() = centerY + .5f / scale

    /** Focus and pan are normalized to the full preview surface, including letterboxing. */
    fun transform(focusX: Float, focusY: Float, panX: Float, panY: Float, zoom: Float,
        viewport: PreviewViewport = PreviewViewport()): PreviewZoom {
        if (!listOf(focusX, focusY, panX, panY, zoom).all { it.isFinite() } || zoom <= 0f) return this
        val nextScale = (scale * zoom).coerceIn(1f, 8f)
        if (nextScale == 1f) return PreviewZoom()
        val focusShift = 1f / scale - 1f / nextScale
        return PreviewZoom(nextScale,
            centerX + viewport.spanX * ((focusX - .5f) * focusShift - panX / nextScale),
            centerY + viewport.spanY * ((focusY - .5f) * focusShift - panY / nextScale)).constrained(viewport)
    }

    fun doubleTap(x: Float, y: Float, viewport: PreviewViewport = PreviewViewport()): PreviewZoom =
        if (scale > 1f) PreviewZoom() else transform(x, y, 0f, 0f, 2f, viewport)

    fun constrained(viewport: PreviewViewport): PreviewZoom {
        fun center(value: Float, span: Float): Float {
            val half = .5f * span / scale
            return if (half >= .5f) .5f else value.coerceIn(half, 1f - half)
        }
        return copy(centerX = center(centerX, viewport.spanX), centerY = center(centerY, viewport.spanY))
    }

    fun visibleRegion(viewport: PreviewViewport): PreviewRegion = PreviewRegion(
        (centerX - .5f * viewport.spanX / scale).coerceIn(0f, 1f),
        (centerY - .5f * viewport.spanY / scale).coerceIn(0f, 1f),
        (centerX + .5f * viewport.spanX / scale).coerceIn(0f, 1f),
        (centerY + .5f * viewport.spanY / scale).coerceIn(0f, 1f))
}

internal data class PreviewRegion(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Surface spans measured in source coordinates at 1x; black bars make one span exceed 1. */
internal data class PreviewViewport(val spanX: Float = 1f, val spanY: Float = 1f) {
    companion object {
        fun forAspectRatio(source: Float, surface: Float): PreviewViewport =
            PreviewViewport(maxOf(1f, surface / source), maxOf(1f, source / surface))
    }
}
