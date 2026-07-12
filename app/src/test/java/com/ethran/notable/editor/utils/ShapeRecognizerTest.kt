package com.ethran.notable.editor.utils

import com.ethran.notable.data.db.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ShapeRecognizerTest {
    @Test
    fun recognizesLine() {
        val points = (0..20).map { StrokePoint(it * 5f, it * 0.1f) }
        assertEquals(RecognizedShape.LINE, ShapeRecognizer.recognize(points)?.shape)
    }

    @Test
    fun recognizesCircle() {
        val points = (0..80).map { i ->
            val angle = 2.0 * PI * i / 80.0
            StrokePoint((100 + 50 * cos(angle)).toFloat(), (100 + 50 * sin(angle)).toFloat())
        }
        assertEquals(RecognizedShape.CIRCLE, ShapeRecognizer.recognize(points)?.shape)
    }

    @Test
    fun rejectsOpenScribble() {
        val points = (0..20).map { StrokePoint(it * 5f, if (it % 2 == 0) 0f else 30f) }
        assertNull(ShapeRecognizer.recognize(points))
    }

    @Test
    fun douglasPeuckerKeepsCorner() {
        val points = listOf(StrokePoint(0f, 0f), StrokePoint(50f, 0f), StrokePoint(50f, 50f))
        assertEquals(3, ShapeRecognizer.douglasPeucker(points, 2f).size)
    }
}
