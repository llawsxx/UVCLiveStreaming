package com.llawsxx.uvclivestreaming

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.llawsxx.uvclivestreaming.recording.PreviewZoom
import com.llawsxx.uvclivestreaming.recording.PreviewViewport
import java.util.Locale

/** A Compose overlay keeps SurfaceView gestures and controls in the same input hierarchy. */
@Composable
internal fun UsbPreviewGestures(
    zoomEnabled: Boolean,
    sourceAspectRatio: Float,
    zoom: () -> PreviewZoom,
    onZoomChange: (PreviewZoom) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentZoom by rememberUpdatedState(zoom)
    val changeZoom by rememberUpdatedState(onZoomChange)
    val tap by rememberUpdatedState(onTap)
    Box(modifier.testTag("preview-gestures")
        .pointerInput(zoomEnabled, sourceAspectRatio) {
            detectTapGestures(onTap = { tap() }, onDoubleTap = if (zoomEnabled) { position ->
                if (size.width > 0 && size.height > 0) {
                    val viewport = PreviewViewport.forAspectRatio(sourceAspectRatio, size.width.toFloat() / size.height)
                    changeZoom(currentZoom().doubleTap(position.x / size.width, position.y / size.height, viewport))
                }
            } else null)
        }
        .pointerInput(zoomEnabled, sourceAspectRatio) {
            if (zoomEnabled) detectTransformGestures { centroid, pan, scale, _ ->
                if (size.width > 0 && size.height > 0) {
                    val viewport = PreviewViewport.forAspectRatio(sourceAspectRatio, size.width.toFloat() / size.height)
                    changeZoom(currentZoom().transform(centroid.x / size.width, centroid.y / size.height,
                        pan.x / size.width, pan.y / size.height, scale, viewport))
                }
            }
        })
}

/** The outer box is the whole source; the highlighted box is the visible source rectangle. */
@Composable
internal fun UsbPreviewZoomIndicator(zoom: PreviewZoom, aspectRatio: Float, viewport: PreviewViewport,
    modifier: Modifier = Modifier) {
    val mapWidth = minOf(120.dp, 80.dp * aspectRatio)
    val mapHeight = mapWidth / aspectRatio
    Column(modifier.testTag("preview-zoom-indicator")
        .semantics { contentDescription = "预览放大定位框，外框为完整画面，亮色框为当前可见区域" }
        .background(Color.Black.copy(alpha = .7f), RoundedCornerShape(6.dp)).padding(8.dp),
        horizontalAlignment = Alignment.End) {
        Text(String.format(Locale.US, "%.1f×", zoom.scale), color = Color.White,
            style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.size(mapWidth, mapHeight)) {
            val inset = 1.dp.toPx()
            val origin = Offset(inset, inset)
            val area = Size((size.width - inset * 2).coerceAtLeast(1f), (size.height - inset * 2).coerceAtLeast(1f))
            drawRect(Color.White.copy(alpha = .12f), origin, area)
            for (i in 1..2) {
                val x = origin.x + area.width * i / 3f
                val y = origin.y + area.height * i / 3f
                drawLine(Color.White.copy(alpha = .2f), Offset(x, origin.y), Offset(x, origin.y + area.height))
                drawLine(Color.White.copy(alpha = .2f), Offset(origin.x, y), Offset(origin.x + area.width, y))
            }
            drawRect(Color.White.copy(alpha = .75f), origin, area, style = Stroke(inset))
            val region = zoom.visibleRegion(viewport)
            val visibleOrigin = origin + Offset(area.width * region.left, area.height * region.top)
            val visibleSize = Size(area.width * (region.right - region.left), area.height * (region.bottom - region.top))
            val highlight = Color(0xFF5EEAD4)
            drawRect(highlight.copy(alpha = .3f), visibleOrigin, visibleSize)
            drawRect(highlight, visibleOrigin, visibleSize, style = Stroke(2.dp.toPx()))
        }
    }
}
