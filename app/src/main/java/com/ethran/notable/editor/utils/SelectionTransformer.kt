package com.ethran.notable.editor.utils

import android.graphics.Matrix
import android.graphics.RectF
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import java.util.Date
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

object SelectionTransformer {
    fun transformStroke(stroke: Stroke, matrix: Matrix): Stroke {
        val coordinates = FloatArray(stroke.points.size * 2)
        stroke.points.forEachIndexed { index, point ->
            coordinates[index * 2] = point.x
            coordinates[index * 2 + 1] = point.y
        }
        matrix.mapPoints(coordinates)
        val points = stroke.points.mapIndexed { index, point ->
            point.copy(x = coordinates[index * 2], y = coordinates[index * 2 + 1])
        }
        val values = FloatArray(9).also(matrix::getValues)
        val scaleX = hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y])
        val scaleY = hypot(values[Matrix.MSKEW_X], values[Matrix.MSCALE_Y])
        val widthScale = sqrt(abs(scaleX * scaleY)).coerceAtLeast(0.05f)
        val size = stroke.size * widthScale
        val bounds = calculateBoundingBox(points) { it.x to it.y }.apply { inset(-size, -size) }
        return stroke.copy(
            size = size,
            points = points,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            updatedAt = Date(),
        )
    }

    fun transformImage(
        image: Image,
        matrix: Matrix,
        flipHorizontal: Boolean = false,
        flipVertical: Boolean = false,
    ): Image {
        val corners = floatArrayOf(
            image.x.toFloat(), image.y.toFloat(),
            (image.x + image.width).toFloat(), image.y.toFloat(),
            (image.x + image.width).toFloat(), (image.y + image.height).toFloat(),
            image.x.toFloat(), (image.y + image.height).toFloat(),
        )
        matrix.mapPoints(corners)
        val xs = corners.filterIndexed { index, _ -> index % 2 == 0 }
        val ys = corners.filterIndexed { index, _ -> index % 2 == 1 }
        val values = FloatArray(9).also(matrix::getValues)
        val rotationDelta = Math.toDegrees(atan2(values[Matrix.MSKEW_Y], values[Matrix.MSCALE_X]).toDouble()).toFloat()
        val left = floor(xs.min()).toInt()
        val top = floor(ys.min()).toInt()
        return image.copy(
            x = left,
            y = top,
            width = ceil(xs.max() - xs.min()).toInt().coerceAtLeast(1),
            height = ceil(ys.max() - ys.min()).toInt().coerceAtLeast(1),
            rotation = when {
                flipHorizontal && flipVertical -> image.rotation + 180f
                flipHorizontal || flipVertical -> -image.rotation
                else -> image.rotation + rotationDelta
            },
            flipHorizontal = image.flipHorizontal xor flipHorizontal,
            flipVertical = image.flipVertical xor flipVertical,
            updatedAt = Date(),
        )
    }

    fun selectionBounds(strokes: List<Stroke>, images: List<Image>): RectF {
        val bounds = RectF()
        strokes.forEach { bounds.union(it.left, it.top, it.right, it.bottom) }
        images.forEach { bounds.union(it.x.toFloat(), it.y.toFloat(), (it.x + it.width).toFloat(), (it.y + it.height).toFloat()) }
        return bounds
    }
}
