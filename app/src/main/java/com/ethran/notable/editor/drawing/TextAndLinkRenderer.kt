@file:Suppress("AvoidVarsExceptWithDelegate")

package com.ethran.notable.editor.drawing

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import androidx.compose.ui.geometry.Offset
import com.ethran.notable.data.db.CanvasLink
import com.ethran.notable.data.db.CanvasText
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import kotlin.math.ceil

object CanvasTextRenderer {
    private data class CachedLayout(val item: CanvasText, val layout: StaticLayout)
    private val layouts = LruCache<String, CachedLayout>(200)
    @Volatile private var markwon: Markwon? = null

    private fun markwon(context: Context): Markwon = markwon ?: synchronized(this) {
        markwon ?: Markwon.builder(context.applicationContext)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context.applicationContext))
            .usePlugin(TaskListPlugin.create(context.applicationContext))
            .build()
            .also { markwon = it }
    }

    fun invalidate(id: String) {
        layouts.remove(id)
    }

    fun layout(context: Context, item: CanvasText): StaticLayout {
        val cached = layouts[item.id]
        if (cached?.item == item) return cached.layout
        val spanned = markwon(context).toMarkdown(item.markdown)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = item.fontSize
            color = item.color
        }
        val alignment = runCatching { Layout.Alignment.valueOf("ALIGN_${item.alignment}") }
            .getOrDefault(Layout.Alignment.ALIGN_NORMAL)
        val result = StaticLayout.Builder
            .obtain(spanned, 0, spanned.length, paint, ceil(item.width).toInt().coerceAtLeast(1))
            .setAlignment(alignment)
            .setIncludePad(true)
            .build()
        layouts.put(item.id, CachedLayout(item, result))
        return result
    }

    fun draw(canvas: Canvas, item: CanvasText, context: Context, offset: Offset) {
        val layout = layout(context, item)
        canvas.save()
        canvas.translate(item.x + offset.x, item.y + offset.y)
        canvas.clipRect(0f, 0f, item.width, item.height.coerceAtLeast(layout.height.toFloat()))
        if (item.backgroundColor != Color.TRANSPARENT) {
            canvas.drawRect(0f, 0f, item.width, item.height, Paint().apply { color = item.backgroundColor })
        }
        layout.draw(canvas)
        canvas.restore()
    }
}

object CanvasLinkRenderer {
    fun draw(canvas: Canvas, item: CanvasLink, offset: Offset, unavailable: Boolean = false) {
        val left = item.x + offset.x
        val top = item.y + offset.y
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (unavailable) Color.GRAY else item.color
            textSize = item.fontSize
            isUnderlineText = true
        }
        val label = if (unavailable) "${item.label} (unavailable)" else item.label
        canvas.drawText("↗ $label", left, top + item.fontSize, paint)
    }
}
