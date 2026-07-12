package com.ethran.notable.editor.utils

import android.graphics.Matrix
import com.ethran.notable.data.db.Image
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
class SelectionTransformerTest {
    @Test
    fun uniformScaleTransformsPointsAndWidth() {
        val stroke = stroke()
        val matrix = Matrix().apply { setScale(2f, 2f) }
        val result = SelectionTransformer.transformStroke(stroke, matrix)
        assertEquals(4f, result.size, 0.001f)
        assertEquals(20f, result.points.last().x, 0.001f)
    }

    @Test
    fun nonUniformScaleUsesGeometricMeanForWidth() {
        val result = SelectionTransformer.transformStroke(stroke(), Matrix().apply { setScale(4f, 1f) })
        assertEquals(4f, result.size, 0.001f)
    }

    @Test
    fun imageFlipTogglesPersistedOrientation() {
        val image = Image(x = 0, y = 0, width = 10, height = 20, uri = "file", pageId = "page")
        val result = SelectionTransformer.transformImage(
            image,
            Matrix().apply { setScale(-1f, 1f, 5f, 10f) },
            flipHorizontal = true,
        )
        assertTrue(result.flipHorizontal)
        assertEquals(10, result.width)
    }

    private fun stroke() = Stroke(
        size = 2f,
        pen = Pen.BALLPEN,
        top = -2f,
        bottom = 2f,
        left = -2f,
        right = 12f,
        points = listOf(StrokePoint(0f, 0f), StrokePoint(10f, 0f)),
        pageId = "page",
    )
}
