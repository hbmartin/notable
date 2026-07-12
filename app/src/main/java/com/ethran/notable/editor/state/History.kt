package com.ethran.notable.editor.state

import android.graphics.Rect
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.CanvasLink
import com.ethran.notable.data.db.CanvasText
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.utils.imageBoundsInt
import com.ethran.notable.editor.utils.strokeBounds
import com.ethran.notable.utils.logCallStack
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CompletableDeferred


sealed class Operation {
    data class DeleteStroke(val strokeIds: List<String>) : Operation()
    data class AddStroke(val strokes: List<Stroke>) : Operation()
    data class AddImage(val images: List<Image>) : Operation()
    data class DeleteImage(val imageIds: List<String>) : Operation()
    data class ReplaceStrokes(val before: List<Stroke>, val after: List<Stroke>) : Operation()
    data class ReplaceImages(val before: List<Image>, val after: List<Image>) : Operation()
    data class AddText(val items: List<CanvasText>) : Operation()
    data class DeleteText(val ids: List<String>) : Operation()
    data class ReplaceText(val before: List<CanvasText>, val after: List<CanvasText>) : Operation()
    data class AddLink(val items: List<CanvasLink>) : Operation()
    data class DeleteLink(val ids: List<String>) : Operation()
    data class ReplaceLink(val before: List<CanvasLink>, val after: List<CanvasLink>) : Operation()
}

typealias OperationBlock = List<Operation>
typealias OperationList = MutableList<OperationBlock>

enum class UndoRedoType {
    Undo,
    Redo
}

sealed class HistoryBusActions {
    data class RegisterHistoryOperationBlock(val operationBlock: OperationBlock) :
        HistoryBusActions()

    data class MoveHistory(val type: UndoRedoType) : HistoryBusActions()
}

class History @AssistedInject constructor(
    @Assisted private val pageView: PageView,
    private val appEventBus: AppEventBus
) {
    private var undoList: OperationList = mutableListOf()
    private var redoList: OperationList = mutableListOf()
    private val pageModel = pageView

    suspend fun handleHistoryBusActions(actions: HistoryBusActions) {
        when (actions) {
            is HistoryBusActions.MoveHistory -> {
                // Wait for commit to history to complete
                if (actions.type == UndoRedoType.Undo) {
                    CanvasEventBus.commitCompletion = CompletableDeferred()
                    CanvasEventBus.commitHistorySignalImmediately.emit(Unit)
                    CanvasEventBus.commitCompletion.await()
                }
                val zoneAffected = undoRedo(type = actions.type)
                if (zoneAffected != null) {
                    pageModel.drawAreaPageCoordinates(zoneAffected)
                    //moved to refresh after drawing
                    CanvasEventBus.refreshUi.emit(Unit)
                } else {
                    val message = when (actions.type) {
                        UndoRedoType.Undo -> "Nothing to undo"
                        UndoRedoType.Redo -> "Nothing to redo"
                    }
                    appEventBus.emit(AppEvent.ActionHint(message, 3000))
                }
            }

            is HistoryBusActions.RegisterHistoryOperationBlock -> {
                addOperationsToHistory(actions.operationBlock)
            }

        }
    }

    suspend fun undo() {
        handleHistoryBusActions(HistoryBusActions.MoveHistory(UndoRedoType.Undo))
    }

    suspend fun redo() {
        handleHistoryBusActions(HistoryBusActions.MoveHistory(UndoRedoType.Redo))
    }


    fun cleanHistory() {
        undoList.clear()
        redoList.clear()
    }

    private fun treatOperation(operation: Operation): Pair<Operation, Rect> {
        when (operation) {
            is Operation.AddStroke -> {
                pageModel.addStrokes(operation.strokes)
                return Operation.DeleteStroke(strokeIds = operation.strokes.map { it.id }) to strokeBounds(
                    operation.strokes
                )
            }

            is Operation.DeleteStroke -> {
                val strokes = pageModel.getStrokes(operation.strokeIds).filterNotNull()
                pageModel.removeStrokes(operation.strokeIds)
                return Operation.AddStroke(strokes = strokes) to strokeBounds(strokes)
            }

            is Operation.AddImage -> {
                pageModel.addImage(operation.images)
                return Operation.DeleteImage(imageIds = operation.images.map { it.id }) to imageBoundsInt(
                    operation.images
                )
            }

            is Operation.DeleteImage -> {
                val images = pageModel.getImages(operation.imageIds).filterNotNull()
                pageModel.removeImages(operation.imageIds)
                return Operation.AddImage(images = images) to imageBoundsInt(images)
            }

            is Operation.ReplaceStrokes -> {
                pageModel.replaceStrokes(operation.before, operation.after)
                val bounds = strokeBounds(operation.before)
                bounds.union(strokeBounds(operation.after))
                return Operation.ReplaceStrokes(operation.after, operation.before) to bounds
            }

            is Operation.ReplaceImages -> {
                pageModel.replaceImages(operation.before, operation.after)
                val bounds = imageBoundsInt(operation.before)
                bounds.union(imageBoundsInt(operation.after))
                return Operation.ReplaceImages(operation.after, operation.before) to bounds
            }

            is Operation.AddText -> {
                pageModel.addTexts(operation.items)
                return Operation.DeleteText(operation.items.map { it.id }) to textBounds(operation.items)
            }

            is Operation.DeleteText -> {
                val items = pageModel.getTexts(operation.ids)
                pageModel.removeTexts(operation.ids)
                return Operation.AddText(items) to textBounds(items)
            }

            is Operation.ReplaceText -> {
                pageModel.updateTexts(operation.after)
                val bounds = textBounds(operation.before)
                bounds.union(textBounds(operation.after))
                return Operation.ReplaceText(operation.after, operation.before) to bounds
            }

            is Operation.AddLink -> {
                pageModel.addLinks(operation.items)
                return Operation.DeleteLink(operation.items.map { it.id }) to linkBounds(operation.items)
            }

            is Operation.DeleteLink -> {
                val items = pageModel.getLinks(operation.ids)
                pageModel.removeLinks(operation.ids)
                return Operation.AddLink(items) to linkBounds(items)
            }

            is Operation.ReplaceLink -> {
                pageModel.updateLinks(operation.after)
                val bounds = linkBounds(operation.before)
                bounds.union(linkBounds(operation.after))
                return Operation.ReplaceLink(operation.after, operation.before) to bounds
            }
        }
    }

    // internal (not private) so instrumented tests can drive the stack logic
    // directly, without the CanvasEventBus commit handshake in MoveHistory.
    internal fun undoRedo(type: UndoRedoType): Rect? {
        val originList =
            if (type == UndoRedoType.Undo) undoList else redoList
        val targetList =
            if (type == UndoRedoType.Undo) redoList else undoList

        if (originList.isEmpty()) return null

        val operationBlock = originList.removeAt(originList.lastIndex)
        val revertOperations = mutableListOf<Operation>()
        val zoneAffected = Rect()
        for (operation in operationBlock) {
            val (cancelOperation, thisZoneAffected) = treatOperation(operation = operation)
            revertOperations.add(cancelOperation)
            zoneAffected.union(thisZoneAffected)
        }
        targetList.add(revertOperations.reversed())

        // update the affected zone
        return zoneAffected
    }

    fun addOperationsToHistory(operations: OperationBlock) {
        if (operations.isEmpty()) {
            logCallStack("History: No operations to add to history")
            return
        }
        undoList.add(operations)
        if (undoList.size > 5) undoList.removeAt(0)
        redoList.clear()
    }

    @AssistedFactory
    interface Factory {
        fun create(pageView: PageView): History
    }
}

private fun textBounds(items: List<CanvasText>): Rect = itemBounds(items.map {
    Rect(it.x.toInt(), it.y.toInt(), (it.x + it.width).toInt(), (it.y + it.height).toInt())
})

private fun linkBounds(items: List<CanvasLink>): Rect = itemBounds(items.map {
    Rect(it.x.toInt(), it.y.toInt(), (it.x + it.width).toInt(), (it.y + it.height).toInt())
})

private fun itemBounds(rects: List<Rect>): Rect {
    val result = Rect()
    rects.forEach(result::union)
    return result
}
