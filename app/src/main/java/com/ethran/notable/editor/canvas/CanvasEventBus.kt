package com.ethran.notable.editor.canvas

import android.graphics.Rect
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.measureTimeMillis

object CanvasEventBus {
    data class ContentTap(val screenPosition: Offset, val stylus: Boolean)

    val forceUpdate = MutableSharedFlow<Rect?>() // null for full redraw
    val refreshUi = MutableSharedFlow<Unit>()
    val refreshUiImmediately = MutableSharedFlow<Unit>(
        replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val reinitSignal = MutableSharedFlow<Unit>()
    val reloadFromDb = MutableSharedFlow<Unit>()


    val isDrawing = MutableSharedFlow<Boolean>()

    // used for managing drawing state on regain focus
    val onFocusChange = MutableSharedFlow<Boolean>()

    // before undo we need to commit changes
    val commitHistorySignal = MutableSharedFlow<Unit>()
    val commitHistorySignalImmediately = MutableSharedFlow<Unit>()

    // used for checking if commit was completed
    var commitCompletion = CompletableDeferred<Unit>()

    // It might be bad idea, but plan is to insert graphic in this, and then take it from it
    // There is probably better way
    val addImageByUri = MutableStateFlow<Uri?>(null)
    val rectangleToSelectByGesture = MutableStateFlow<Rect?>(null)
    val drawingInProgress = Mutex()

    // For cleaning whole page, activated from toolbar menu
    val clearPageSignal = MutableSharedFlow<Unit>()

    // Signal to UI to close any open menus/modals,
    // observed in EditorView
    val closeMenusSignal = MutableSharedFlow<Unit>()


    // For QuickNav scrolling with previews
    val saveCurrent = MutableSharedFlow<Unit>()

    val isScrubbing = MutableStateFlow<Boolean> (false)
    val previewPage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val restoreCanvas = MutableSharedFlow<Unit>(extraBufferCapacity = 1)


    val changePage = MutableSharedFlow<String>(extraBufferCapacity = 1)

    // Hardware page-turn requests (volume / page-turn buttons), forwarded from
    // MainActivity key events. true = next page, false = previous page. Collected by
    // EditorView while an editor is open; MainActivity checks subscriptionCount to
    // know whether to consume the key event.
    val hardwarePageTurn = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    private val _contentTap = MutableSharedFlow<ContentTap>(extraBufferCapacity = 1)
    val contentTap = _contentTap.asSharedFlow()

    fun emitContentTap(tap: ContentTap) {
        _contentTap.tryEmit(tap)
    }


    suspend fun waitForDrawing() {
        Log.d(
            "DrawCanvas.waitForDrawing", "waiting"
        )
        val elapsed = measureTimeMillis {
            withTimeoutOrNull(3000) {
                // Just to make sure wait 1ms before checking lock.
                delay(1)
                // Wait until drawingInProgress is unlocked before proceeding
                while (drawingInProgress.isLocked) {
                    delay(5)
                }
            } ?: Log.e(
                "DrawCanvas.waitForDrawing",
                "Timeout while waiting for drawing lock. Potential deadlock."
            )

        }
        when {
            elapsed > 3000 -> Log.e(
                "DrawCanvas.waitForDrawing", "Exceeded timeout ($elapsed ms)"
            )

            elapsed > 100 -> Log.w("DrawCanvas.waitForDrawing", "Took too long: $elapsed ms")
            else -> Log.d("DrawCanvas.waitForDrawing", "Finished waiting in $elapsed ms")
        }

    }

}
