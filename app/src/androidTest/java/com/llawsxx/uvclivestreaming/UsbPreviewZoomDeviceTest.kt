package com.llawsxx.uvclivestreaming

import android.view.SurfaceView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import com.llawsxx.uvclivestreaming.recording.PreviewZoom
import com.llawsxx.uvclivestreaming.recording.PreviewViewport
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UsbPreviewZoomDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun surfacePreviewHandlesDoubleTapDragControlsAndFullscreenExit() {
        val fullscreen = mutableStateOf(true)
        var zoom = PreviewZoom()
        compose.setContent {
            MaterialTheme {
                UsbCameraWorkspace(16f / 9f, false, fullscreen.value, { fullscreen.value = false },
                    false, {}, { zoom = it },
                    preview = { modifier -> AndroidView(factory = { SurfaceView(it) }, modifier = modifier) },
                    fullscreenControls = {}, audioMeter = {}, actions = {}, settings = { Text(it.label) })
            }
        }
        val gestures = compose.onNodeWithTag("preview-gestures")
        gestures.performTouchInput { doubleClick(center) }
        compose.runOnIdle { assertEquals(2f, zoom.scale, .001f) }
        compose.onNodeWithTag("preview-zoom-indicator").assertIsDisplayed()
        val screen = compose.onNodeWithTag("preview-region").fetchSemanticsNode().boundsInRoot
        val indicator = compose.onNodeWithTag("preview-zoom-indicator").fetchSemanticsNode().boundsInRoot
        val input = gestures.fetchSemanticsNode().boundsInRoot
        assertEquals(screen, input)
        assertTrue("Indicator must be near the screen's right edge", screen.right - indicator.right < screen.width * .1f)
        assertTrue("Indicator must be near the screen's bottom edge", screen.bottom - indicator.bottom < screen.height * .1f)
        compose.onNodeWithText("退出全屏").assertDoesNotExist()

        gestures.performTouchInput { swipe(center, Offset(width * .8f, height * .7f)) }
        compose.runOnIdle {
            val viewport = PreviewViewport.forAspectRatio(16f / 9f, screen.width / screen.height)
            assertEquals(zoom, zoom.constrained(viewport))
            if (viewport.spanX < 2f) assertTrue(zoom.centerX < .5f) else assertEquals(.5f, zoom.centerX, .001f)
            if (viewport.spanY < 2f) assertTrue(zoom.centerY < .5f) else assertEquals(.5f, zoom.centerY, .001f)
        }
        gestures.performTouchInput { click(center) }
        compose.waitUntil(1_500) { compose.onAllNodesWithText("退出全屏").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("退出全屏").assertIsDisplayed()
        compose.onNodeWithText("退出全屏").performClick()
        compose.runOnIdle { assertEquals(PreviewZoom(), zoom) }
        compose.onNodeWithTag("preview-zoom-indicator").assertDoesNotExist()

        // The same gesture layer remains tappable in the normal preview but cannot zoom it.
        gestures.performTouchInput { doubleClick(center) }
        compose.runOnIdle { assertEquals(PreviewZoom(), zoom) }
    }

    @Test fun pinchingZoomsInAndOutAndDoubleTapRestoresTheWholeImage() {
        var zoom = PreviewZoom()
        compose.setContent {
            MaterialTheme {
                UsbCameraWorkspace(16f / 9f, false, true, {}, false, {}, { zoom = it },
                    preview = { modifier -> AndroidView(factory = { SurfaceView(it) }, modifier = modifier) },
                    fullscreenControls = {}, audioMeter = {}, actions = {}, settings = {})
            }
        }
        val gestures = compose.onNodeWithTag("preview-gestures")
        gestures.performTouchInput {
            pinch(start0 = Offset(width * .35f, center.y), end0 = Offset(width * .1f, center.y),
                start1 = Offset(width * .65f, center.y), end1 = Offset(width * .9f, center.y))
        }
        var enlarged = 1f
        compose.runOnIdle { enlarged = zoom.scale; assertTrue("Pinch did not enlarge: $zoom", enlarged > 2f) }
        gestures.performTouchInput {
            pinch(start0 = Offset(width * .1f, center.y), end0 = Offset(width * .25f, center.y),
                start1 = Offset(width * .9f, center.y), end1 = Offset(width * .75f, center.y))
        }
        compose.runOnIdle {
            assertTrue("Inward pinch returned to 1x from $enlarged: $zoom", zoom.scale > 1f)
            assertTrue("Inward pinch did not shrink from $enlarged: $zoom", zoom.scale < enlarged)
        }
        gestures.performTouchInput { doubleClick(center) }
        compose.runOnIdle { assertEquals(PreviewZoom(), zoom) }
        compose.onNodeWithTag("preview-zoom-indicator").assertDoesNotExist()
    }
}
