@file:Suppress("AvoidVarsExceptWithDelegate", "NoCallbacksInFunctions")

package com.ethran.notable.editor.utils

import com.ethran.notable.data.db.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DwellDetectorTest {
    @Test
    fun movementResetsTimerAndDwellUsesSnapshot() {
        val scheduler = FakeScheduler()
        var captured = emptyList<StrokePoint>()
        val detector = DwellDetector(scheduler = scheduler) { captured = it }
        detector.start(StrokePoint(0f, 0f), 800L)
        detector.move(StrokePoint(5f, 0f))
        assertEquals(1, scheduler.schedules)
        detector.move(StrokePoint(25f, 0f))
        assertEquals(2, scheduler.schedules)
        scheduler.run()
        assertEquals(3, captured.size)
        detector.markRecognized()
        assertTrue(detector.recognized)
    }

    @Test
    fun delayIsClamped() {
        val scheduler = FakeScheduler()
        val detector = DwellDetector(scheduler = scheduler) {}
        detector.start(StrokePoint(0f, 0f), 5_000L)
        assertEquals(1_500L, scheduler.delay)
    }

    private class FakeScheduler : DwellScheduler {
        var action: (() -> Unit)? = null
        var delay = 0L
        var schedules = 0
        override fun schedule(delayMs: Long, action: () -> Unit) {
            delay = delayMs
            this.action = action
            schedules++
        }
        override fun cancel() {
            action = null
        }
        fun run() = action?.invoke()
    }
}
