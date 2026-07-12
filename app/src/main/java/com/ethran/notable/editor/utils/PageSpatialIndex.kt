@file:Suppress("AvoidVarsExceptWithDelegate", "NoVarsInConstructor")

package com.ethran.notable.editor.utils

import android.graphics.RectF
import com.ethran.notable.data.db.CanvasLink
import com.ethran.notable.data.db.CanvasText
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import kotlin.math.hypot

enum class SpatialKind {
    STROKE,
    IMAGE,
    TEXT,
    LINK,
}

data class SpatialEntry(
    val id: String,
    val kind: SpatialKind,
    val bounds: RectF,
    val order: Long,
    val zIndex: Float = 0f,
) {
    fun distanceToPoint(x: Float, y: Float): Float {
        if (bounds.contains(x, y)) return 0f
        val dx = maxOf(bounds.left - x, 0f, x - bounds.right)
        val dy = maxOf(bounds.top - y, 0f, y - bounds.bottom)
        return hypot(dx, dy)
    }
}

/** In-memory, auto-growing quadtree. Callers synchronize access through [PageSpatialIndex]. */
private class Quadtree(
    private var level: Int,
    private val bounds: RectF,
) {
    companion object {
        const val MAX_OBJECTS = 10
        const val MAX_LEVELS = 20
    }

    private val entries = ArrayList<SpatialEntry>()
    private val nodes = arrayOfNulls<Quadtree>(4)

    fun insert(entry: SpatialEntry): Quadtree {
        if (!bounds.contains(entry.bounds)) return grow(entry.bounds).insert(entry)
        insertInternal(entry)
        return this
    }

    fun retrieve(viewport: RectF, result: MutableList<SpatialEntry>) {
        if (!RectF.intersects(bounds, viewport)) return
        val index = quadrant(viewport)
        if (nodes[0] != null) {
            if (index >= 0) nodes[index]?.retrieve(viewport, result)
            else nodes.filterNotNull().filter { RectF.intersects(it.bounds, viewport) }
                .forEach { it.retrieve(viewport, result) }
        }
        entries.filterTo(result) { RectF.intersects(it.bounds, viewport) }
    }

    private fun insertInternal(entry: SpatialEntry) {
        if (nodes[0] != null) {
            val index = quadrant(entry.bounds)
            if (index >= 0) {
                nodes[index]?.insertInternal(entry)
                return
            }
        }
        entries += entry
        if (entries.size > MAX_OBJECTS && level < MAX_LEVELS) {
            if (nodes[0] == null) split()
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val existing = iterator.next()
                val index = quadrant(existing.bounds)
                if (index >= 0) {
                    iterator.remove()
                    nodes[index]?.insertInternal(existing)
                }
            }
        }
    }

    private fun split() {
        val halfWidth = bounds.width() / 2f
        val halfHeight = bounds.height() / 2f
        nodes[0] = Quadtree(level + 1, RectF(bounds.left, bounds.top, bounds.left + halfWidth, bounds.top + halfHeight))
        nodes[1] = Quadtree(level + 1, RectF(bounds.left + halfWidth, bounds.top, bounds.right, bounds.top + halfHeight))
        nodes[2] = Quadtree(level + 1, RectF(bounds.left, bounds.top + halfHeight, bounds.left + halfWidth, bounds.bottom))
        nodes[3] = Quadtree(level + 1, RectF(bounds.left + halfWidth, bounds.top + halfHeight, bounds.right, bounds.bottom))
    }

    private fun quadrant(rect: RectF): Int {
        val middleX = bounds.centerX()
        val middleY = bounds.centerY()
        val top = rect.bottom < middleY
        val bottom = rect.top > middleY
        val left = rect.right < middleX
        val right = rect.left > middleX
        return when {
            top && left -> 0
            top && right -> 1
            bottom && left -> 2
            bottom && right -> 3
            else -> -1
        }
    }

    private fun grow(target: RectF): Quadtree {
        val growRight = target.centerX() > bounds.centerX()
        val growBottom = target.centerY() > bounds.centerY()
        val newLeft = if (growRight) bounds.left else bounds.left - bounds.width()
        val newTop = if (growBottom) bounds.top else bounds.top - bounds.height()
        val root = Quadtree(level - 1, RectF(newLeft, newTop, newLeft + bounds.width() * 2f, newTop + bounds.height() * 2f))
        root.split()
        val oldRootIndex = when {
            growRight && growBottom -> 0
            !growRight && growBottom -> 1
            growRight && !growBottom -> 2
            else -> 3
        }
        root.nodes[oldRootIndex] = this
        level = root.level + 1
        return root
    }
}

class PageSpatialIndex {
    private val lock = Any()
    private var root = newRoot()
    private val entries = LinkedHashMap<String, SpatialEntry>()

    fun replaceAll(
        strokes: List<Stroke>,
        images: List<Image>,
        texts: List<CanvasText> = emptyList(),
        links: List<CanvasLink> = emptyList(),
    ) = synchronized(lock) {
        root = newRoot()
        entries.clear()
        var order = 0L
        strokes.forEach { add(SpatialEntry(it.id, SpatialKind.STROKE, RectF(it.left, it.top, it.right, it.bottom), order++)) }
        images.forEach { add(SpatialEntry(it.id, SpatialKind.IMAGE, RectF(it.x.toFloat(), it.y.toFloat(), (it.x + it.width).toFloat(), (it.y + it.height).toFloat()), order++)) }
        texts.forEach { add(SpatialEntry(it.id, SpatialKind.TEXT, RectF(it.x, it.y, it.x + it.width, it.y + it.height), order++)) }
        links.forEach { add(SpatialEntry(it.id, SpatialKind.LINK, RectF(it.x, it.y, it.x + it.width, it.y + it.height), order++)) }
    }

    fun query(bounds: RectF, kind: SpatialKind? = null): List<SpatialEntry> = synchronized(lock) {
        buildList { root.retrieve(bounds, this) }
            .filter { kind == null || it.kind == kind }
            .sortedBy { it.order }
    }

    fun hitTest(x: Float, y: Float, tolerance: Float, kinds: Set<SpatialKind>? = null): SpatialEntry? {
        val area = RectF(x - tolerance, y - tolerance, x + tolerance, y + tolerance)
        return query(area)
            .asSequence()
            .filter { kinds == null || it.kind in kinds }
            .map { it to it.distanceToPoint(x, y) }
            .filter { it.second <= tolerance }
            .sortedWith(compareBy<Pair<SpatialEntry, Float>> { it.second }
                .thenByDescending { it.first.zIndex }
                .thenByDescending { it.first.order })
            .firstOrNull()?.first
    }

    private fun add(entry: SpatialEntry) {
        entries[entry.id] = entry
        root = root.insert(entry)
    }

    private fun newRoot() = Quadtree(0, RectF(-4096f, -4096f, 4096f, 4096f))
}
