package com.ethran.notable.editor.utils

import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], application = android.app.Application::class)
class StrokeSplitterTest {
    private fun stroke(points: List<StrokePoint>) = Stroke(
        size = 2f,
        pen = Pen.BALLPEN,
        top = -2f,
        bottom = 2f,
        left = points.minOf { it.x } - 2f,
        right = points.maxOf { it.x } + 2f,
        points = points,
        pageId = "page",
    )

    @Test
    fun splitsSparseStrokeAndInterpolatesMetadata() {
        val target = stroke(listOf(StrokePoint(0f, 0f, pressure = 0f), StrokePoint(100f, 0f, pressure = 1f)))
        val parts = StrokeSplitter.splitStroke(target, listOf(StrokePoint(50f, 0f)), 8f)
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.points.size >= 2 })
        assertTrue(parts.flattenPoints().any { (it.pressure ?: 0f) in 0.1f..0.9f })
    }

    @Test
    fun returnsOriginalWhenNothingIsErased() {
        val target = stroke(listOf(StrokePoint(0f, 0f), StrokePoint(100f, 0f)))
        assertTrue(StrokeSplitter.splitStroke(target, listOf(StrokePoint(50f, 100f)), 4f).single() === target)
    }

    private fun List<Stroke>.flattenPoints() = flatMap { it.points }
}
