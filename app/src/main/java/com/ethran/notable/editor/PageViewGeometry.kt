package com.ethran.notable.editor

import android.graphics.Rect
import androidx.compose.ui.unit.IntOffset
import com.ethran.notable.gestures.ZOOM_SNAP_THRESHOLD
import io.shipbook.shipbooksdk.ShipBook
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure viewport geometry used by [PageView].
 *
 * Extracted from PageView as a first step of splitting its responsibilities
 * (see docs/architecture.md): everything here is stateless math on the
 * zoom/scroll viewport, independent of bitmaps, the database, and the UI,
 * so it can be reasoned about and tested in isolation.
 */
object PageViewGeometry {
    private val log = ShipBook.getLogger("PageViewGeometry")

    /**
     * Computes the zoom level that a pinch [scaleDelta] should produce.
     *
     * In discrete mode the zoom snaps to either 1.0 or the screen's portrait
     * ratio; in continuous mode the delta is applied incrementally and snapped
     * to whichever of the two targets is within [ZOOM_SNAP_THRESHOLD].
     */
    fun calculateZoomLevel(
        scaleDelta: Float,
        currentZoom: Float,
        screenWidth: Int,
        screenHeight: Int,
        continuousZoom: Boolean,
    ): Float {
        // TODO: Better snapping logic
        val portraitRatio = screenWidth.toFloat() / screenHeight

        return if (!continuousZoom) {
            // Discrete zoom mode - snap to either 1.0 or screen ratio
            if (scaleDelta <= 1.0f) {
                if (screenHeight > screenWidth) portraitRatio else 1.0f
            } else {
                if (screenHeight > screenWidth) 1.0f else portraitRatio
            }
        } else {
            // Continuous zoom mode with snap behavior
            val newZoom = (scaleDelta / 3 + currentZoom).coerceIn(0.1f, 10.0f)

            // Snap to either 1.0 or screen ratio depending on which is closer
            val snapTarget = if (abs(newZoom - 1.0f) < abs(newZoom - portraitRatio)) {
                1.0f
            } else {
                portraitRatio
            }

            if (abs(newZoom - snapTarget) < ZOOM_SNAP_THRESHOLD) {
                log.d("Zoom snap to $snapTarget")
                snapTarget
            } else {
                log.d("Left zoom as is. $newZoom")
                newZoom
            }
        }
    }

    /**
     * After the screen bitmap is shifted by [movement], returns the part of the
     * screen still covered by valid (already drawn) content.
     */
    fun alreadyDrawnRectAfterShift(
        movement: IntOffset,
        screenW: Int,
        screenH: Int
    ): Rect {
        val dx = -movement.x
        val dy = -movement.y
        val left = max(0, dx)
        val top = max(0, dy)
        val right = min(screenW, dx + screenW)
        val bottom = min(screenH, dy + screenH)
        return Rect(left, top, right, bottom)
    }

    /**
     * Screen bands NOT covered by [dstRect] (the already-valid content region),
     * expanded by [scaledOverlap] so neighbouring redraws overlap slightly.
     * These are the areas that must be redrawn after a shift or zoom-out.
     */
    fun uncoveredBands(
        dstRect: Rect,
        screenW: Int,
        screenH: Int,
        scaledOverlap: Int
    ): List<Rect> {
        val bands = mutableListOf<Rect>()

        // Uncovered top band
        if (dstRect.top > 0) {
            bands.add(
                Rect(
                    0,
                    0,
                    screenW,
                    (dstRect.top + scaledOverlap).coerceAtMost(screenH)
                )
            )
        }
        // Uncovered bottom band
        if (dstRect.bottom < screenH) {
            bands.add(
                Rect(
                    0, (dstRect.bottom - scaledOverlap).coerceAtLeast(0), screenW, screenH
                )
            )
        }
        // Uncovered left band
        if (dstRect.left > 0) {
            bands.add(
                Rect(
                    0,
                    (dstRect.top - scaledOverlap).coerceAtLeast(0),
                    (dstRect.left + scaledOverlap).coerceAtMost(screenW),
                    (dstRect.bottom + scaledOverlap).coerceAtMost(screenH)
                )
            )
        }
        // Uncovered right band
        if (dstRect.right < screenW) {
            bands.add(
                Rect(
                    (dstRect.right - scaledOverlap).coerceAtLeast(0),
                    (dstRect.top - scaledOverlap).coerceAtLeast(0),
                    screenW,
                    (dstRect.bottom + scaledOverlap).coerceAtMost(screenH)
                )
            )
        }

        return bands.filterNot { it.isEmpty }
    }
}
