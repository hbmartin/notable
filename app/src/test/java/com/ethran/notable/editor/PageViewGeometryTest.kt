package com.ethran.notable.editor

import android.graphics.Rect
import androidx.compose.ui.unit.IntOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageViewGeometryTest {

    @Test
    fun calculateZoomLevel_discreteModeSnapsBetweenPortraitRatioAndOne() {
        assertEquals(
            0.5f,
            PageViewGeometry.calculateZoomLevel(
                scaleDelta = 1.0f,
                currentZoom = 1.0f,
                screenWidth = 100,
                screenHeight = 200,
                continuousZoom = false
            )
        )
        assertEquals(
            1.0f,
            PageViewGeometry.calculateZoomLevel(
                scaleDelta = 1.1f,
                currentZoom = 0.5f,
                screenWidth = 100,
                screenHeight = 200,
                continuousZoom = false
            )
        )
    }

    @Test
    fun calculateZoomLevel_continuousModeSnapsOnlyWithinThreshold() {
        assertEquals(
            1.0f,
            PageViewGeometry.calculateZoomLevel(
                scaleDelta = 0.0f,
                currentZoom = 0.99f,
                screenWidth = 100,
                screenHeight = 200,
                continuousZoom = true
            )
        )
        assertEquals(
            0.5f,
            PageViewGeometry.calculateZoomLevel(
                scaleDelta = 0.0f,
                currentZoom = 0.49f,
                screenWidth = 100,
                screenHeight = 200,
                continuousZoom = true
            )
        )
        assertEquals(
            0.97f,
            PageViewGeometry.calculateZoomLevel(
                scaleDelta = 0.0f,
                currentZoom = 0.97f,
                screenWidth = 100,
                screenHeight = 200,
                continuousZoom = true
            )
        )
    }

    @Test
    fun alreadyDrawnRectAfterShift_handlesZeroAndDirectionalMovement() {
        assertRectEquals(
            rect(0, 0, 100, 80),
            PageViewGeometry.alreadyDrawnRectAfterShift(
                movement = IntOffset.Zero,
                screenW = 100,
                screenH = 80
            )
        )
        assertRectEquals(
            rect(0, 0, 70, 60),
            PageViewGeometry.alreadyDrawnRectAfterShift(
                movement = IntOffset(30, 20),
                screenW = 100,
                screenH = 80
            )
        )
        assertRectEquals(
            rect(30, 20, 100, 80),
            PageViewGeometry.alreadyDrawnRectAfterShift(
                movement = IntOffset(-30, -20),
                screenW = 100,
                screenH = 80
            )
        )
    }

    @Test
    fun alreadyDrawnRectAfterShift_allowsDegenerateRectWhenMovementExceedsScreen() {
        val rect = PageViewGeometry.alreadyDrawnRectAfterShift(
            movement = IntOffset(150, 0),
            screenW = 100,
            screenH = 80
        )

        assertTrue(rect.right <= rect.left)
    }

    @Test
    fun uncoveredBands_returnsEmptyWhenDestinationCoversWholeScreen() {
        assertEquals(
            emptyList<Rect>(),
            PageViewGeometry.uncoveredBands(
                dstRect = rect(0, 0, 100, 80),
                screenW = 100,
                screenH = 80,
                scaledOverlap = 5
            )
        )
    }

    @Test
    fun uncoveredBands_returnsExpandedBandsAroundDestination() {
        val bands = PageViewGeometry.uncoveredBands(
            dstRect = rect(20, 30, 80, 70),
            screenW = 100,
            screenH = 80,
            scaledOverlap = 5
        )

        assertEquals(4, bands.size)
        assertRectEquals(rect(0, 0, 100, 35), bands[0])
        assertRectEquals(rect(0, 65, 100, 80), bands[1])
        assertRectEquals(rect(0, 25, 25, 75), bands[2])
        assertRectEquals(rect(75, 25, 100, 75), bands[3])
    }

    @Test
    fun uncoveredBands_filtersDegenerateSideBands() {
        val bands = PageViewGeometry.uncoveredBands(
            dstRect = rect(0, 0, 0, 0),
            screenW = 100,
            screenH = 80,
            scaledOverlap = 0
        )

        assertEquals(1, bands.size)
        assertRectEquals(rect(0, 0, 100, 80), bands[0])
    }

    private fun rect(left: Int, top: Int, right: Int, bottom: Int): Rect {
        return Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
    }

    private fun assertRectEquals(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left)
        assertEquals(expected.top, actual.top)
        assertEquals(expected.right, actual.right)
        assertEquals(expected.bottom, actual.bottom)
    }
}
