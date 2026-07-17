@file:Suppress("AvoidVarsExceptWithDelegate")

package com.ethran.notable.editor.utils

import com.ethran.notable.data.db.StrokePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class RecognizedShape {
    LINE,
    CIRCLE,
    TRIANGLE,
    RECTANGLE,
    PENTAGON,
    HEXAGON,
}

data class ShapeRecognitionResult(
    val shape: RecognizedShape,
    val points: List<StrokePoint>,
)

/** Pure competitive-error recognizer adapted from Notate. */
object ShapeRecognizer {
    private const val MIN_POINTS = 10
    private const val LINEARITY = 0.92f
    private const val CLOSURE_RATIO = 0.30f

    fun recognize(points: List<StrokePoint>): ShapeRecognitionResult? {
        if (points.size < MIN_POINTS) return null
        val length = points.zipWithNext().sumOf { (a, b) -> hypot(b.x - a.x, b.y - a.y).toDouble() }.toFloat()
        if (length < 20f) return null
        val start = points.first()
        val end = points.last()
        val direct = hypot(end.x - start.x, end.y - start.y)
        if (direct / length > LINEARITY) {
            return ShapeRecognitionResult(RecognizedShape.LINE, listOf(start, end))
        }
        if (direct / length > CLOSURE_RATIO) return null

        val closeX = (start.x + end.x) / 2f
        val closeY = (start.y + end.y) / 2f
        val closed = points.toMutableList().apply {
            this[0] = first().copy(x = closeX, y = closeY)
            this[lastIndex] = last().copy(x = closeX, y = closeY)
        }
        val cx = closed.map { it.x }.average().toFloat()
        val cy = closed.map { it.y }.average().toFloat()
        val radii = closed.map { hypot(it.x - cx, it.y - cy) }
        val radius = radii.average().toFloat()
        if (radius <= 0f) return null
        val circleError = radii.map { abs(it - radius) }.average().toFloat()
        val simplified = douglasPeucker(closed, radius * 0.18f)
        val vertices = if (samePoint(simplified.first(), simplified.last())) simplified.dropLast(1) else simplified
        val polygonError = polygonError(closed, vertices)

        return when (vertices.size) {
            3 -> if (polygonError < circleError) polygon(RecognizedShape.TRIANGLE, vertices) else circle(cx, cy, radius, start)
            4 -> if (polygonError < circleError) rectangle(vertices) else circle(cx, cy, radius, start)
            5 -> if (polygonError < circleError) regularPolygon(RecognizedShape.PENTAGON, vertices, 5) else circle(cx, cy, radius, start)
            6 -> if (polygonError < circleError) regularPolygon(RecognizedShape.HEXAGON, vertices, 6) else circle(cx, cy, radius, start)
            else -> if (circleError < radius * 0.20f) circle(cx, cy, radius, start) else null
        }
    }

    fun douglasPeucker(points: List<StrokePoint>, epsilon: Float): List<StrokePoint> {
        if (points.size < 3) return points
        var maxDistance = 0f
        var index = 0
        for (i in 1 until points.lastIndex) {
            val distance = distanceToSegment(points[i], points.first(), points.last())
            if (distance > maxDistance) {
                maxDistance = distance
                index = i
            }
        }
        if (maxDistance <= epsilon) return listOf(points.first(), points.last())
        val left = douglasPeucker(points.subList(0, index + 1), epsilon)
        val right = douglasPeucker(points.subList(index, points.size), epsilon)
        return left.dropLast(1) + right
    }

    private fun polygon(shape: RecognizedShape, vertices: List<StrokePoint>): ShapeRecognitionResult =
        ShapeRecognitionResult(shape, vertices + vertices.first())

    private fun regularPolygon(
        shape: RecognizedShape,
        vertices: List<StrokePoint>,
        sides: Int,
    ): ShapeRecognitionResult {
        val cx = vertices.map { it.x }.average().toFloat()
        val cy = vertices.map { it.y }.average().toFloat()
        val radius = vertices.map { hypot(it.x - cx, it.y - cy) }.average().toFloat()
        val angle = atan2(vertices.first().y - cy, vertices.first().x - cx)
        val template = (0 until sides).map { i ->
            val a = angle + i * (2.0 * PI / sides)
            vertices.first().copy(
                x = (cx + radius * cos(a)).toFloat(),
                y = (cy + radius * sin(a)).toFloat(),
            )
        }
        return polygon(shape, template)
    }

    private fun rectangle(vertices: List<StrokePoint>): ShapeRecognitionResult {
        val cx = vertices.map { it.x }.average().toFloat()
        val cy = vertices.map { it.y }.average().toFloat()
        val edgeAngles = vertices.indices.map { i ->
            val a = vertices[i]
            val b = vertices[(i + 1) % vertices.size]
            atan2(b.y - a.y, b.x - a.x)
        }
        val base = atan2(
            edgeAngles.sumOf { sin(4f * it).toDouble() }.toFloat(),
            edgeAngles.sumOf { cos(4f * it).toDouble() }.toFloat(),
        ) / 4f
        val cosA = cos(base)
        val sinA = sin(base)
        val local = vertices.map {
            val dx = it.x - cx
            val dy = it.y - cy
            (dx * cosA + dy * sinA) to (-dx * sinA + dy * cosA)
        }
        val halfWidth = local.maxOf { abs(it.first) }
        val halfHeight = local.maxOf { abs(it.second) }
        val template = listOf(
            -halfWidth to -halfHeight,
            halfWidth to -halfHeight,
            halfWidth to halfHeight,
            -halfWidth to halfHeight,
        ).map { (x, y) ->
            vertices.first().copy(x = cx + x * cosA - y * sinA, y = cy + x * sinA + y * cosA)
        }
        return polygon(RecognizedShape.RECTANGLE, template)
    }

    private fun circle(cx: Float, cy: Float, radius: Float, template: StrokePoint): ShapeRecognitionResult {
        val result = (0..60).map { i ->
            val angle = 2.0 * PI * i / 60.0
            template.copy(
                x = (cx + radius * cos(angle)).toFloat(),
                y = (cy + radius * sin(angle)).toFloat(),
            )
        }
        return ShapeRecognitionResult(RecognizedShape.CIRCLE, result)
    }

    private fun polygonError(points: List<StrokePoint>, vertices: List<StrokePoint>): Float {
        if (vertices.size < 2) return Float.MAX_VALUE
        return points.map { point ->
            vertices.indices.minOf { i -> distanceToSegment(point, vertices[i], vertices[(i + 1) % vertices.size]) }
        }.average().toFloat()
    }

    private fun samePoint(a: StrokePoint, b: StrokePoint) = hypot(a.x - b.x, a.y - b.y) < 0.0001f

    private fun distanceToSegment(point: StrokePoint, start: StrokePoint, end: StrokePoint): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0f) return hypot(point.x - start.x, point.y - start.y)
        val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(point.x - (start.x + t * dx), point.y - (start.y + t * dy))
    }
}
