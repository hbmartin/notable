@file:Suppress("AvoidVarsExceptWithDelegate")

package com.ethran.notable.editor.utils

import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import java.util.Date
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.hypot

object StrokeSplitter {
    fun splitStroke(target: Stroke, eraserPath: List<StrokePoint>, eraserRadius: Float): List<Stroke> {
        if (target.points.size < 2 || eraserPath.isEmpty() || eraserRadius <= 0f) return listOf(target)
        val threshold = eraserRadius + target.size / 2f
        val samples = subdivide(target.points, (threshold / 2f).coerceAtLeast(0.5f))
        val runs = mutableListOf<MutableList<StrokePoint>>()
        var current = mutableListOf<StrokePoint>()
        samples.forEach { point ->
            if (isErased(point, eraserPath, threshold)) {
                if (current.size >= 2) runs += current
                current = mutableListOf()
            } else {
                current += point
            }
        }
        if (current.size >= 2) runs += current
        if (runs.size == 1 && runs.first().size == samples.size) return listOf(target)
        val now = Date()
        return runs.map { points ->
            val bounds = calculateBoundingBox(points) { it.x to it.y }.apply { inset(-target.size, -target.size) }
            target.copy(
                id = UUID.randomUUID().toString(),
                points = points,
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    private fun subdivide(points: List<StrokePoint>, step: Float): List<StrokePoint> = buildList {
        add(points.first())
        points.zipWithNext().forEach { (start, end) ->
            val distance = hypot(end.x - start.x, end.y - start.y)
            val count = ceil(distance / step).toInt().coerceAtLeast(1)
            for (i in 1 until count) add(interpolate(start, end, i.toFloat() / count))
            add(end)
        }
    }

    private fun interpolate(a: StrokePoint, b: StrokePoint, t: Float) = StrokePoint(
        x = a.x + (b.x - a.x) * t,
        y = a.y + (b.y - a.y) * t,
        pressure = interpolateNullable(a.pressure, b.pressure, t),
        tiltX = interpolateNullable(a.tiltX?.toFloat(), b.tiltX?.toFloat(), t)?.toInt(),
        tiltY = interpolateNullable(a.tiltY?.toFloat(), b.tiltY?.toFloat(), t)?.toInt(),
        dt = interpolateNullable(a.dt?.toFloat(), b.dt?.toFloat(), t)?.toInt()?.coerceIn(0, UShort.MAX_VALUE.toInt())?.toUShort(),
    )

    private fun interpolateNullable(a: Float?, b: Float?, t: Float): Float? = when {
        a != null && b != null -> a + (b - a) * t
        else -> a ?: b
    }

    private fun isErased(point: StrokePoint, path: List<StrokePoint>, threshold: Float): Boolean {
        if (path.size == 1) return hypot(point.x - path[0].x, point.y - path[0].y) <= threshold
        return path.zipWithNext().any { (a, b) -> distanceToSegment(point, a, b) <= threshold }
    }

    private fun distanceToSegment(point: StrokePoint, start: StrokePoint, end: StrokePoint): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0f) return hypot(point.x - start.x, point.y - start.y)
        val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(point.x - (start.x + t * dx), point.y - (start.y + t * dy))
    }
}
