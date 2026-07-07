package com.ethran.notable.editor

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.events.DefaultAppEventBus
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Operation
import com.ethran.notable.editor.state.UndoRedoType
import com.ethran.notable.editor.utils.Pen
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the undo/redo stack logic directly (History.undoRedo is internal for
 * this purpose), with a mocked PageView so no canvas or database is involved.
 *
 * Semantics under test: the operations registered with History are the actions
 * that *revert* a user action (drawing stroke S registers DeleteStroke(S.id)),
 * and each undo produces the inverse block on the redo stack.
 */
@RunWith(AndroidJUnit4::class)
class HistoryTest {

    private lateinit var pageView: PageView
    private lateinit var history: History

    private fun stroke(id: String) = Stroke(
        id = id,
        size = 10f,
        pen = Pen.BALLPEN,
        top = 0f,
        bottom = 10f,
        left = 0f,
        right = 10f,
        points = listOf(StrokePoint(1f, 1f), StrokePoint(2f, 2f)),
        pageId = "page-1"
    )

    @Before
    fun setUp() {
        pageView = mockk(relaxed = true)
        history = History(pageView, DefaultAppEventBus())
    }

    @Test
    fun undoOnEmptyHistoryReturnsNull() {
        assertNull(history.undoRedo(UndoRedoType.Undo))
        assertNull(history.undoRedo(UndoRedoType.Redo))
    }

    @Test
    fun undoRevertsDrawnStrokeAndRedoRestoresIt() {
        val s = stroke("s1")
        every { pageView.getStrokes(listOf("s1")) } returns listOf(s)

        // Drawing s1 registers its deletion as the undo action.
        history.addOperationsToHistory(listOf(Operation.DeleteStroke(listOf("s1"))))

        assertNotNull(history.undoRedo(UndoRedoType.Undo))
        verify(exactly = 1) { pageView.removeStrokes(listOf("s1")) }

        assertNotNull(history.undoRedo(UndoRedoType.Redo))
        verify(exactly = 1) { pageView.addStrokes(listOf(s)) }
    }

    @Test
    fun redoStackClearedByNewOperation() {
        val s = stroke("s1")
        every { pageView.getStrokes(listOf("s1")) } returns listOf(s)

        history.addOperationsToHistory(listOf(Operation.DeleteStroke(listOf("s1"))))
        assertNotNull(history.undoRedo(UndoRedoType.Undo))

        // A new user action invalidates the redo stack.
        history.addOperationsToHistory(listOf(Operation.DeleteStroke(listOf("s2"))))
        assertNull(history.undoRedo(UndoRedoType.Redo))
    }

    @Test
    fun undoDepthIsCappedAtFive() {
        every { pageView.getStrokes(any()) } answers {
            firstArg<List<String>>().map { stroke(it) }
        }

        repeat(7) { i ->
            history.addOperationsToHistory(listOf(Operation.DeleteStroke(listOf("s$i"))))
        }

        var undone = 0
        while (history.undoRedo(UndoRedoType.Undo) != null) undone++
        assertEquals(5, undone)
    }

    @Test
    fun undoingBlockRevertsAllOperationsInIt() {
        val s1 = stroke("s1")
        val s2 = stroke("s2")
        every { pageView.getStrokes(listOf("s1")) } returns listOf(s1)
        every { pageView.getStrokes(listOf("s2")) } returns listOf(s2)

        history.addOperationsToHistory(
            listOf(
                Operation.DeleteStroke(listOf("s1")),
                Operation.DeleteStroke(listOf("s2"))
            )
        )

        assertNotNull(history.undoRedo(UndoRedoType.Undo))
        verify(exactly = 1) { pageView.removeStrokes(listOf("s1")) }
        verify(exactly = 1) { pageView.removeStrokes(listOf("s2")) }
    }
}
