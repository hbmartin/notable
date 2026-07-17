@file:Suppress("AvoidVarsExceptWithDelegate", "NoCallbacksInFunctions")

package com.ethran.notable.editor.utils

import android.os.Handler
import android.os.Looper
import com.ethran.notable.data.db.StrokePoint
import kotlin.math.hypot

interface DwellScheduler {
    fun schedule(delayMs: Long, action: () -> Unit)
    fun cancel()
}

class HandlerDwellScheduler : DwellScheduler {
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null

    override fun schedule(delayMs: Long, action: () -> Unit) {
        cancel()
        runnable = Runnable(action).also { handler.postDelayed(it, delayMs) }
    }

    override fun cancel() {
        runnable?.let(handler::removeCallbacks)
        runnable = null
    }
}

class DwellDetector(
    private val tolerancePx: Float = 20f,
    private val scheduler: DwellScheduler = HandlerDwellScheduler(),
    private val onDwell: (List<StrokePoint>) -> Unit,
) {
    private val points = mutableListOf<StrokePoint>()
    private var anchor: StrokePoint? = null
    private var delayMs: Long = 800L
    var recognized: Boolean = false
        private set

    fun start(point: StrokePoint, delayMs: Long) {
        scheduler.cancel()
        points.clear()
        points += point
        anchor = point
        this.delayMs = delayMs.coerceIn(300L, 1500L)
        recognized = false
        schedule()
    }

    fun move(point: StrokePoint) {
        if (recognized) return
        points += point
        val previousAnchor = anchor ?: point
        if (hypot(point.x - previousAnchor.x, point.y - previousAnchor.y) > tolerancePx) {
            anchor = point
            schedule()
        }
    }

    fun markRecognized() {
        recognized = true
        scheduler.cancel()
    }

    fun stop() = scheduler.cancel()

    fun reset() {
        stop()
        points.clear()
        anchor = null
        recognized = false
    }

    private fun schedule() {
        scheduler.schedule(delayMs) {
            if (!recognized) onDwell(points.toList())
        }
    }
}
