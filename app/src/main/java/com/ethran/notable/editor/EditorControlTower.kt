package com.ethran.notable.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.toOffset
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.CanvasLink
import com.ethran.notable.data.db.CanvasText
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.drawing.CanvasTextRenderer
import com.ethran.notable.editor.state.ClipboardStore
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.state.Operation
import com.ethran.notable.editor.state.PlacementMode
import com.ethran.notable.editor.state.SelectionState
import com.ethran.notable.editor.utils.offsetStroke
import com.ethran.notable.editor.utils.refreshScreen
import com.ethran.notable.editor.utils.selectImagesAndStrokes
import com.ethran.notable.editor.utils.SelectionTransformer
import com.ethran.notable.editor.utils.SpatialKind
import com.ethran.notable.editor.utils.offsetImage
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.UUID

sealed interface CanvasContentDraft {
    data class Text(val original: CanvasText?, val value: CanvasText) : CanvasContentDraft
    data class Link(val original: CanvasLink?, val value: CanvasLink) : CanvasContentDraft
}

class EditorControlTower(
    private val scope: CoroutineScope,
    val page: PageView,
    private var history: History,
    private val viewModel: EditorViewModel,
    private val clipboardStore: ClipboardStore,
) {
    private val _contentDraft = MutableStateFlow<CanvasContentDraft?>(null)
    val contentDraft = _contentDraft.asStateFlow()
    private val _linkActivations = MutableSharedFlow<CanvasLink>(extraBufferCapacity = 1)
    val linkActivations = _linkActivations.asSharedFlow()
    private var scrollInProgress = Mutex()
    private val logEditorControlTower = ShipBook.getLogger("EditorControlTower")
    private var changePageObserverJob: Job? = null

    // Accumulated, not-yet-rendered scroll delta in screen coordinates. Input events add
    // into this; a single consumer coroutine drains and renders it. StateFlow conflation
    // means a burst of input collapses to one render pass per frame the renderer can keep
    // up with.
    private val pendingScroll = MutableStateFlow(Offset.Zero)
    private var scrollConsumerJob: Job? = null

    fun registerObservers() {
        startScrollConsumer()
        if (changePageObserverJob?.isActive == true) return

        changePageObserverJob = scope.launch {
            CanvasEventBus.changePage.collect { pageId ->
                logEditorControlTower.d("Change to page $pageId")

                // Switch to Main thread for Compose state mutations
                withContext(Dispatchers.Main) {
                    viewModel.changePage(pageId)
                    history.cleanHistory()
                }
                // no need for this, we are listening for change of current page,
                // in EditorView
//                page.changePage(pageId)
                refreshScreen()
            }
        }
    }

    // TODO: remove it, change to proper solution
    fun unregisterObservers() {
        changePageObserverJob?.cancel()
        changePageObserverJob = null
        scrollConsumerJob?.cancel()
        scrollConsumerJob = null
    }

    /**
     * Submit a scroll/drag delta (screen coordinates) for rendering. Non-blocking: the
     * delta is accumulated and consumed by [startScrollConsumer]; bursts coalesce automatically.
     */
    fun requestScroll(delta: Offset) {
        if (delta == Offset.Zero) return
        if (!page.isTransformationAllowed) return
        pendingScroll.update { it + delta }
    }

    /**
     * Single consumer that drains [pendingScroll] and performs the actual bitmap shift.
     * Draining atomically resets the accumulator to zero, so any input that arrives while
     * a render is in flight piles onto a fresh zero and is picked up on the next pass —
     * coalescing a flood of touch samples into one shift per render.
     */
    private fun startScrollConsumer() {
        if (scrollConsumerJob?.isActive == true) return
        scrollConsumerJob = scope.launch(Dispatchers.Main.immediate) {
            pendingScroll.collect {
                val delta = pendingScroll.getAndUpdate { Offset.Zero }
                if (delta == Offset.Zero) return@collect
                scrollInProgress.withLock {
                    if (viewModel.toolbarState.value.mode == Mode.Select &&
                        viewModel.selectionState.firstPageCut != null
                    ) {
                        onOpenPageCut(delta / page.zoomLevel.value)
                    } else {
                        onPageScroll(-delta)
                    }
                }
                CanvasEventBus.refreshUiImmediately.emit(Unit)
            }
        }
    }


    fun setIsDrawing(value: Boolean) {
        if (viewModel.toolbarState.value.isDrawing == value) {
            logEditorControlTower.w("IsDrawing already set to $value")
            return
        }
        scope.launch { CanvasEventBus.isDrawing.emit(value) }
    }

    fun toggleTool() {
        val mode = viewModel.toolbarState.value.mode
        viewModel.onToolbarAction(ToolbarAction.ChangeMode(if (mode == Mode.Draw) Mode.Erase else Mode.Draw))
    }

    fun toggleZen() {
        viewModel.onToolbarAction(ToolbarAction.ToggleToolbar)
    }

    fun requestContentAtScreen(position: Offset, stylus: Boolean) {
        val pagePosition = position / page.zoomLevel.value + page.scroll
        val linkId = page.pageDataManager.hitTestSpatial(
            page.currentPageId, pagePosition.x, pagePosition.y, 8f / page.zoomLevel.value,
            setOf(SpatialKind.LINK),
        )?.id
        val link = linkId?.let { id -> page.links.firstOrNull { it.id == id } }
        when (viewModel.toolbarState.value.mode) {
            Mode.Text -> {
                val textId = page.pageDataManager.hitTestSpatial(
                    page.currentPageId, pagePosition.x, pagePosition.y, 8f / page.zoomLevel.value,
                    setOf(SpatialKind.TEXT),
                )?.id
                val existing = textId?.let { id -> page.texts.firstOrNull { it.id == id } }
                val value = existing ?: CanvasText(
                    pageId = page.currentPageId,
                    markdown = "",
                    x = pagePosition.x,
                    y = pagePosition.y,
                    width = 420f / page.zoomLevel.value,
                    height = 140f / page.zoomLevel.value,
                )
                _contentDraft.value = CanvasContentDraft.Text(existing, value)
            }

            Mode.Link -> {
                val value = link ?: CanvasLink(
                    pageId = page.currentPageId,
                    label = "Link",
                    target = "https://",
                    targetType = com.ethran.notable.data.db.LinkTargetType.URL,
                    x = pagePosition.x,
                    y = pagePosition.y,
                    width = 300f / page.zoomLevel.value,
                    height = 52f / page.zoomLevel.value,
                )
                _contentDraft.value = CanvasContentDraft.Link(link, value)
            }

            Mode.Select -> if (stylus && link != null) _linkActivations.tryEmit(link)
            else -> if (!stylus && link != null) _linkActivations.tryEmit(link)
        }
        if (_contentDraft.value != null) setIsDrawing(false)
    }

    fun cancelContentEdit() {
        _contentDraft.value = null
        viewModel.updateDrawingState()
    }

    fun commitText(original: CanvasText?, value: CanvasText) {
        val now = Date()
        val committed = value.copy(
            height = value.height.coerceAtLeast(48f),
            width = value.width.coerceAtLeast(80f),
            updatedAt = now,
        )
        CanvasTextRenderer.invalidate(committed.id)
        if (original == null) {
            page.addTexts(listOf(committed))
            history.addOperationsToHistory(listOf(Operation.DeleteText(listOf(committed.id))))
        } else {
            page.updateTexts(listOf(committed))
            history.addOperationsToHistory(listOf(Operation.ReplaceText(listOf(committed), listOf(original))))
        }
        _contentDraft.value = null
        refreshContentBounds(original?.let(::textRect), textRect(committed))
        viewModel.updateDrawingState()
    }

    fun commitLink(original: CanvasLink?, value: CanvasLink) {
        val committed = value.copy(
            label = value.label.ifBlank { value.target },
            width = value.width.coerceAtLeast(80f),
            height = value.height.coerceAtLeast(32f),
            updatedAt = Date(),
        )
        if (original == null) {
            page.addLinks(listOf(committed))
            history.addOperationsToHistory(listOf(Operation.DeleteLink(listOf(committed.id))))
        } else {
            page.updateLinks(listOf(committed))
            history.addOperationsToHistory(listOf(Operation.ReplaceLink(listOf(committed), listOf(original))))
        }
        _contentDraft.value = null
        refreshContentBounds(original?.let(::linkRect), linkRect(committed))
        viewModel.updateDrawingState()
    }

    fun deleteText(item: CanvasText) {
        page.removeTexts(listOf(item.id))
        history.addOperationsToHistory(listOf(Operation.AddText(listOf(item))))
        _contentDraft.value = null
        refreshContentBounds(null, textRect(item))
        viewModel.updateDrawingState()
    }

    fun deleteLink(item: CanvasLink) {
        page.removeLinks(listOf(item.id))
        history.addOperationsToHistory(listOf(Operation.AddLink(listOf(item))))
        _contentDraft.value = null
        refreshContentBounds(null, linkRect(item))
        viewModel.updateDrawingState()
    }

    private fun refreshContentBounds(old: Rect?, new: Rect) {
        val union = Rect(new)
        old?.let(union::union)
        page.drawAreaPageCoordinates(union)
        scope.launch { CanvasEventBus.refreshUi.emit(Unit) }
    }

    private fun textRect(item: CanvasText) = Rect(
        item.x.toInt(), item.y.toInt(), (item.x + item.width).toInt(), (item.y + item.height).toInt()
    )

    private fun linkRect(item: CanvasLink) = Rect(
        item.x.toInt(), item.y.toInt(), (item.x + item.width).toInt(), (item.y + item.height).toInt()
    )

    fun getSnapshotOfSelectionState(): SelectionState {
        return viewModel.selectionState
    }

    fun getSelectedBitmap(): Bitmap {
        return requireNotNull(viewModel.selectionState.selectedBitmap)
    }

    fun goToNextPage() {
        logEditorControlTower.i("Going to next page")
        viewModel.goToNextPage()
        history.cleanHistory()
    }

    fun goToPreviousPage() {
        logEditorControlTower.i("Going to previous page")
        viewModel.goToPreviousPage()
        history.cleanHistory()
    }

    fun undo() {
        scope.launch {
            logEditorControlTower.i("Undo called")
            history.undo()
//            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    fun redo() {
        scope.launch {
            logEditorControlTower.i("Redo called")
            history.redo()
//            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    fun onPinchToZoom(delta: Float, center: Offset?) {
        if (!page.isTransformationAllowed) return
        if (viewModel.toolbarState.value.mode == Mode.Select)
            return
        scope.launch {
            scrollInProgress.withLock {
                if (GlobalAppSettings.current.simpleRendering || !GlobalAppSettings.current.continuousZoom)
                    page.simpleUpdateZoom(delta)
                else
                    page.updateZoom(delta, center)
            }
            CanvasEventBus.refreshUiImmediately.emit(Unit)
        }
    }

    fun resetZoomAndScroll() {
        scope.launch {
            page.scroll = Offset(0f, page.scroll.y)
            page.applyZoomAndRedraw(1f)
            // Request UI update
            CanvasEventBus.refreshUiImmediately.emit(Unit)
        }
    }

    private fun onOpenPageCut(offset: Offset) {
        val cutLine = viewModel.selectionState.firstPageCut ?: return
        val result = page.applyPageCutOffset(cutLine, offset) ?: return

        // commit to history
        history.addOperationsToHistory(
            listOf(
                Operation.DeleteStroke(result.movedStrokes.map { it.id }),
                Operation.AddStroke(result.previousStrokes)
            )
        )

        viewModel.selectionState.reset()
    }

    private suspend fun onPageScroll(dragDelta: Offset) {
        // scroll is in Page coordinates
        if (GlobalAppSettings.current.simpleRendering)
            page.simpleUpdateScroll(dragDelta)
        else
            page.updateScroll(dragDelta)
    }


    // when selection is moved, we need to redraw canvas
    fun applySelectionDisplace() {
        viewModel.selectionState.applySelectionDisplaceAndCommit(page, history)
        scope.launch {
            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    fun deleteSelection() {
        viewModel.selectionState.deleteSelectionAndCommit(page, history)
        setIsDrawing(true)
        scope.launch {
            CanvasEventBus.refreshUi.emit(Unit)
        }
    }

    fun changeSizeOfSelection(scale: Int) {
        val factor = (1f + scale / 100f).coerceAtLeast(0.05f)
        viewModel.selectionState.previewScaleX *= factor
        viewModel.selectionState.previewScaleY *= factor
    }

    fun commitPreviewTransform() {
        commitSelectionTransform(
            scaleX = viewModel.selectionState.previewScaleX,
            scaleY = viewModel.selectionState.previewScaleY,
            rotation = viewModel.selectionState.previewRotation,
        )
    }

    fun rotateSelection(degrees: Float) = commitSelectionTransform(rotation = degrees)

    fun flipSelection(horizontal: Boolean) = commitSelectionTransform(
        scaleX = if (horizontal) -1f else 1f,
        scaleY = if (horizontal) 1f else -1f,
        flipHorizontal = horizontal,
        flipVertical = !horizontal,
    )

    private fun commitSelectionTransform(
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        rotation: Float = 0f,
        flipHorizontal: Boolean = false,
        flipVertical: Boolean = false,
    ) {
        val state = viewModel.selectionState
        val originalStrokes = state.selectedStrokes.orEmpty()
        val originalImages = state.selectedImages.orEmpty()
        if (originalStrokes.isEmpty() && originalImages.isEmpty()) return
        val offset = state.selectionDisplaceOffset?.toOffset() ?: Offset.Zero
        val positionedStrokes = originalStrokes.map { offsetStroke(it, offset) }
        val positionedImages = originalImages.map { offsetImage(it, offset) }
        val bounds = SelectionTransformer.selectionBounds(positionedStrokes, positionedImages)
        val matrix = Matrix().apply {
            postScale(scaleX, scaleY, bounds.centerX(), bounds.centerY())
            postRotate(rotation, bounds.centerX(), bounds.centerY())
        }
        val transformedStrokes = positionedStrokes.map { SelectionTransformer.transformStroke(it, matrix) }
        val transformedImages = positionedImages.map {
            SelectionTransformer.transformImage(it, matrix, flipHorizontal, flipVertical)
        }
        val operations = mutableListOf<Operation>()
        if (originalStrokes.isNotEmpty()) {
            page.replaceStrokes(originalStrokes, transformedStrokes)
            operations += Operation.ReplaceStrokes(transformedStrokes, originalStrokes)
        }
        if (originalImages.isNotEmpty()) {
            page.replaceImages(originalImages, transformedImages)
            operations += Operation.ReplaceImages(transformedImages, originalImages)
        }
        history.addOperationsToHistory(operations)
        state.reset()
        selectImagesAndStrokes(scope, page, viewModel, transformedImages, transformedStrokes)
        scope.launch { CanvasEventBus.refreshUi.emit(Unit) }
    }

    fun duplicateSelection() {
        // finish ongoing movement
        applySelectionDisplace()
        viewModel.selectionState.duplicateSelection()

    }

    fun cutSelectionToClipboard(context: Context) {
        clipboardStore.set(viewModel.selectionState.selectionToClipboard(page.scroll, context))
        deleteSelection()
        showHint("Content cut to clipboard")
    }

    fun copySelectionToClipboard(context: Context) {
        clipboardStore.set(viewModel.selectionState.selectionToClipboard(page.scroll, context))
    }


    fun pasteFromClipboard() {
        // finish ongoing movement
        applySelectionDisplace()

        val (strokes, images) = clipboardStore.get() ?: return

        val now = Date()
        val scrollPos = page.scroll

        val pastedStrokes = strokes.map {
            offsetStroke(it, offset = scrollPos).copy(
                // change the pasted strokes' ids - it's a copy
                id = UUID
                    .randomUUID()
                    .toString(),
                createdAt = now,
                // set the pageId to the current page
                pageId = this.page.currentPageId
            )
        }

        val pastedImages = images.map {
            it.copy(
                // change the pasted images' ids - it's a copy
                id = UUID
                    .randomUUID()
                    .toString(),
                x = it.x + scrollPos.x.toInt(),
                y = it.y + scrollPos.y.toInt(),
                createdAt = now,
                // set the pageId to the current page
                pageId = this.page.currentPageId
            )
        }

        selectImagesAndStrokes(
            scope = scope,
            page = page,
            viewModel = viewModel,
            imagesToSelect = pastedImages,
            strokesToSelect = pastedStrokes
        )
        viewModel.selectionState.placementMode = PlacementMode.Paste

        showHint("Pasted content from clipboard")
    }

    fun showHint(text: String) = viewModel.showHint(text)
}
