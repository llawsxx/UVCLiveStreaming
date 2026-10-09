package com.llawsxx.uvclivestreaming.recording

import org.junit.Assert.*
import org.junit.Test

class PreviewZoomTest {
    @Test fun letterboxedPreviewUsesTheWholeScreenAndCentersAxesThatStillFit() {
        val viewport = PreviewViewport.forAspectRatio(2f, .5f)
        assertEquals(PreviewViewport(1f, 4f), viewport)
        val zoom = PreviewZoom().doubleTap(.5f, .5f, viewport)
            .transform(.5f, .5f, .1f, .1f, 1f, viewport)
        assertEquals(.45f, zoom.centerX, .0001f)
        assertEquals(.5f, zoom.centerY, .0001f)
        val region = zoom.visibleRegion(viewport)
        assertEquals(.5f, region.right - region.left, .0001f)
        assertEquals(0f, region.top, .0001f)
        assertEquals(1f, region.bottom, .0001f)
    }

    @Test fun largerZoomCanPanIntoFormerBlackBarsAndRotationRechecksBounds() {
        val portrait = PreviewViewport.forAspectRatio(2f, .5f)
        val zoom = PreviewZoom(8f).transform(.5f, .5f, 10f, -10f, 1f, portrait)
        val region = zoom.visibleRegion(portrait)
        assertEquals(0f, region.left, .0001f)
        assertEquals(1f, region.bottom, .0001f)
        assertEquals(.5f, region.bottom - region.top, .0001f)
        val landscape = PreviewViewport.forAspectRatio(2f, 4f)
        val rotated = zoom.constrained(landscape).visibleRegion(landscape)
        assertEquals(0f, rotated.left, .0001f)
        assertEquals(.25f, rotated.right, .0001f)
        assertTrue(rotated.bottom <= 1f)
    }

    @Test fun doubleTapKeepsTappedSourcePointInPlaceAndNextTapRestoresWholeImage() {
        val zoom = PreviewZoom().doubleTap(.8f, .2f)
        assertEquals(2f, zoom.scale, .0001f)
        assertEquals(.8f, zoom.left + .8f / zoom.scale, .0001f)
        assertEquals(.2f, zoom.top + .2f / zoom.scale, .0001f)
        assertEquals(PreviewZoom(), zoom.doubleTap(.1f, .9f))
    }

    @Test fun pinchAndPanKeepTheSourceUnderTheMovingFingers() {
        val before = PreviewZoom(2f, .5f, .5f)
        val after = before.transform(.6f, .4f, .05f, -.05f, 1.5f)
        assertEquals(before.left + .6f / before.scale, after.left + .65f / after.scale, .0001f)
        assertEquals(before.top + .4f / before.scale, after.top + .35f / after.scale, .0001f)
    }

    @Test fun draggingCannotMoveTheCropOutsideTheSource() {
        val zoom = PreviewZoom(4f).transform(.5f, .5f, 10f, -10f, 1f)
        assertEquals(0f, zoom.left, .0001f)
        assertEquals(1f, zoom.bottom, .0001f)
        assertEquals(.25f, zoom.right - zoom.left, .0001f)
        assertEquals(.25f, zoom.bottom - zoom.top, .0001f)
    }

    @Test fun zoomIsLimitedAndShrinkingToOneResetsPosition() {
        val maximum = PreviewZoom().transform(1f, 0f, 0f, 0f, 100f)
        assertEquals(8f, maximum.scale, .0001f)
        assertEquals(1f, maximum.right, .0001f)
        assertEquals(0f, maximum.top, .0001f)
        assertEquals(PreviewZoom(), maximum.transform(.5f, .5f, .2f, .3f, .001f))
    }

    @Test fun unzoomedDragAndInvalidGesturesLeaveTheWholeSourceVisible() {
        val original = PreviewZoom()
        assertEquals(original, original.transform(.5f, .5f, .2f, .1f, 1f))
        assertEquals(original, original.transform(Float.NaN, .5f, 0f, 0f, 2f))
        assertEquals(original, original.transform(.5f, .5f, 0f, 0f, Float.POSITIVE_INFINITY))
        assertEquals(original, original.transform(.5f, .5f, 0f, 0f, 0f))
    }
}
