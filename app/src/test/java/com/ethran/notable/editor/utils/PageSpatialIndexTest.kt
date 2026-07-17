package com.ethran.notable.editor.utils

import android.graphics.RectF
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], application = android.app.Application::class)
class PageSpatialIndexTest {
    @Test
    fun growsAndRetrievesOnlyIntersectingEntries() {
        val near = stroke("near", 0f, 0f, 10f, 10f)
        val far = stroke("far", 50_000f, 50_000f, 50_010f, 50_010f)
        val index = PageSpatialIndex().apply { replaceAll(listOf(near, far), emptyList()) }
        assertEquals(listOf("near"), index.query(RectF(-1f, -1f, 20f, 20f), SpatialKind.STROKE).map { it.id })
        assertEquals(listOf("far"), index.query(RectF(49_999f, 49_999f, 50_020f, 50_020f)).map { it.id })
    }

    @Test
    fun hitTestPrefersNewerEntry() {
        val old = stroke("old", 0f, 0f, 10f, 10f)
        val newest = stroke("new", 0f, 0f, 10f, 10f)
        val index = PageSpatialIndex().apply { replaceAll(listOf(old, newest), emptyList()) }
        assertEquals("new", index.hitTest(5f, 5f, 1f)?.id)
        assertNull(index.hitTest(100f, 100f, 1f))
        assertTrue(index.query(RectF(-1f, -1f, 11f, 11f)).size == 2)
    }

    private fun stroke(id: String, left: Float, top: Float, right: Float, bottom: Float) = Stroke(
        id = id,
        size = 1f,
        pen = Pen.BALLPEN,
        top = top,
        bottom = bottom,
        left = left,
        right = right,
        points = listOf(StrokePoint(left, top), StrokePoint(right, bottom)),
        pageId = "page",
    )
}
