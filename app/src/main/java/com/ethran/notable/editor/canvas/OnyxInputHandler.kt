@file:Suppress("AvoidVarsExceptWithDelegate")

package com.ethran.notable.editor.canvas

import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toRect
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.EditorViewModel
import com.ethran.notable.editor.state.Mode
import com.ethran.notable.editor.PageView
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.editor.utils.DwellDetector
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.editor.utils.calculateBoundingBox
import com.ethran.notable.editor.utils.copyInput
import com.ethran.notable.editor.utils.copyInputToSimplePointF
import com.ethran.notable.editor.utils.enableNativeEraser
import com.ethran.notable.editor.utils.getModifiedStrokeEndpoints
import com.ethran.notable.editor.utils.handleDraw
import com.ethran.notable.editor.utils.handleErase
import com.ethran.notable.editor.utils.handleScribbleToErase
import com.ethran.notable.editor.utils.handleSelect
import com.ethran.notable.editor.utils.onSurfaceInit
import com.ethran.notable.editor.utils.PenSetting
import com.ethran.notable.editor.utils.penToStroke
import com.ethran.notable.editor.utils.setupSurface
import com.ethran.notable.editor.utils.transformToLine
import com.ethran.notable.editor.utils.withPressureSettings
import com.ethran.notable.editor.utils.OnyxCapabilities
import com.ethran.notable.editor.utils.ShapeRecognitionResult
import com.ethran.notable.editor.utils.ShapeRecognizer
import com.ethran.notable.editor.utils.strokeBounds
import com.ethran.notable.editor.drawing.drawStroke
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.toRect
import com.ethran.notable.ui.convertDpToPixel
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.device.Device
import com.onyx.android.sdk.extension.isNullOrEmpty
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class OnyxInputHandler(
    private val drawCanvas: DrawCanvas,
    private val page: PageView,
    private val viewModel: EditorViewModel,
    private val history: History,
    private val coroutineScope: CoroutineScope,
    private val strokeHistoryBatch: MutableList<String>,
) {
    var isErasing: Boolean = false
    var lastStrokeEndTime: Long = 0
    private val log = ShipBook.getLogger("DrawCanvas")
    private val toolbarState get() = viewModel.toolbarState.value

    // Settings for the active pen. penSettings may lack an entry for the current pen
    // (e.g. empty or partially restored map), which used to crash with an NPE — fall
    // back to the defaults, then to a sane hardcoded setting.
    private val currentPenSetting: PenSetting
        get() = toolbarState.penSettings[toolbarState.pen.penName]
            ?: EditorViewModel.DEFAULT_PEN_SETTINGS[toolbarState.pen.penName]
            ?: PenSetting(strokeSize = 5f, color = Color.BLACK)

    private var recognizedShape: ShapeRecognitionResult? = null
    private val dwellDetector = DwellDetector { points -> onShapeDwell(points) }

    companion object {
        // The Onyx firmware drives a single raw-input surface at a time, so device-wide
        // coordination is needed: track which handler claimed the surface last, and let a
        // handler ask whether it is still the owner (see DrawCanvas surfaceDestroyed).
        // Kept private to this class instead of a global mutable variable.
        @Volatile
        private var rawInputSurfaceOwner: WeakReference<OnyxInputHandler>? = null
    }

    /** True while this handler is the last one to have claimed the raw-input surface. */
    fun ownsRawInputSurface(): Boolean = rawInputSurfaceOwner?.get() === this

    /**
     * Drops this handler's claim on the raw-input surface (no-op if a newer handler
     * already claimed it). Called when the surface is destroyed so the companion
     * reference doesn't keep the whole canvas/page graph alive after the editor closes.
     */
    fun releaseRawInputSurface() {
        if (rawInputSurfaceOwner?.get() === this) rawInputSurfaceOwner = null
    }

    // TODO: As OnyxInput is not done by lazy, which forces evaluation of the touchHelper
    //       lazy during DrawCanvas construction.
    val touchHelper by lazy {
        val helper = if (DeviceCompat.isOnyxDevice) {
            try {
                val createdHelper = TouchHelper.create(drawCanvas, inputCallback)
                if (createdHelper != null) rawInputSurfaceOwner = WeakReference(this)
                createdHelper
            } catch (t: Throwable) {
                Log.w("OnyxInputHandler", "TouchHelper.create failed: ${t.message}")
                null
            }
        } else null
        helper
    }

    @Suppress("RedundantOverride")
    private val inputCallback: RawInputCallback = object : RawInputCallback() {
        // Documentation: https://github.com/onyx-intl/OnyxAndroidDemo/blob/d3a1ffd3af231fe4de60a2a0da692c17cb35ce31/doc/Onyx-Pen-SDK.md#L40-L62
        // - pen : `onBeginRawDrawing()` -> `onRawDrawingTouchPointMoveReceived()` -> `onRawDrawingTouchPointListReceived()` -> `onEndRawDrawing()`
        // - erase :  `onBeginRawErasing()` -> `onRawErasingTouchPointMoveReceived()` -> `onRawErasingTouchPointListReceived()` -> `onEndRawErasing()`

        override fun onBeginRawDrawing(p0: Boolean, p1: TouchPoint?) {
            recognizedShape = null
            if (p1 != null && shapePerfectionAllowed()) {
                val point = rawShapePoint(p1)
                dwellDetector.start(point, GlobalAppSettings.current.shapePerfectionDelayMs)
            } else {
                dwellDetector.reset()
            }
        }

        override fun onEndRawDrawing(p0: Boolean, p1: TouchPoint?) {
            dwellDetector.stop()
        }

        override fun onRawDrawingTouchPointMoveReceived(p0: TouchPoint?) {
            if (p0 != null && shapePerfectionAllowed()) {
                dwellDetector.move(rawShapePoint(p0))
            }
        }

        override fun onRawDrawingTouchPointListReceived(plist: TouchPointList) =
            onRawDrawingList(plist)


        // Handle button/eraser tip of the pen:
        override fun onBeginRawErasing(p0: Boolean, p1: TouchPoint?) {
            if (touchHelper == null) return
            // Re-assert the native eraser indicator because setRawDrawingEnabled(true) (called
            // on every resume) resets it to disabled internally. See docs/onyx-sdk/onyx-native-eraser-indicator.md.
            enableNativeEraser(touchHelper)
            applyEraserIndicatorStyle()
            isErasing = true
        }

        override fun onEndRawErasing(p0: Boolean, p1: TouchPoint?) {
            updatePenAndStroke()
        }

        override fun onRawErasingTouchPointListReceived(plist: TouchPointList?) =
            onRawErasingList(plist)

        override fun onRawErasingTouchPointMoveReceived(p0: TouchPoint?) {
        }

        override fun onPenUpRefresh(refreshRect: RectF?) {
            super.onPenUpRefresh(refreshRect)
            if (!GlobalAppSettings.current.autoSyncEinkBuffer || refreshRect == null) return
            val dirty = Rect()
            refreshRect.roundOut(dirty)
            runCatching { EpdController.handwritingRepaint(drawCanvas, dirty) }
                .onFailure { log.w("Targeted handwriting repaint failed: ${it.message}") }
        }

        override fun onPenActive(point: TouchPoint?) {
            super.onPenActive(point)
        }
    }

    fun updatePenAndStroke() {
        if(touchHelper == null) return
        // it takes around 11 ms to run on Note 4c.
        log.i("Update pen and stroke")
        when (toolbarState.mode) {
            // we need to change size according to zoom level before drawing on screen
            Mode.Draw, Mode.Line -> {
                val penSetting = currentPenSetting
                touchHelper!!.setStrokeStyle(penToStroke(toolbarState.pen))
                    ?.setStrokeWidth(penSetting.strokeSize * page.zoomLevel.value)
                    ?.setStrokeColor(penSetting.color)
            }

            Mode.Erase -> applyEraserIndicatorStyle(penEraserColor = Color.GRAY)

            Mode.Select -> touchHelper?.setStrokeStyle(penToStroke(Pen.BALLPEN))?.setStrokeWidth(3f)
                ?.setStrokeColor(Color.GRAY)

            Mode.Text, Mode.Link -> touchHelper?.setStrokeStyle(penToStroke(Pen.BALLPEN))
                ?.setStrokeWidth(1f)
                ?.setStrokeColor(Color.TRANSPARENT)
        }
    }

    /**
     * Configures the helper's stroke so the eraser feedback matches the active eraser type:
     * a marker for the pen eraser, and a dashed line for the lasso / select eraser. Shared
     * by the hand eraser (Mode.Erase in [updatePenAndStroke]) and the pen side-button
     * eraser ([onBeginRawErasing], native indicator).
     *
     * @param penEraserColor colour for the [Eraser.PEN] marker. Hand-erase uses grey; the
     * native button-erase indicator uses black (matches the user's preference and is more
     * visible against ink).
     */
    private fun applyEraserIndicatorStyle(penEraserColor: Int = Color.BLACK) {
        if (touchHelper == null) return
        when (toolbarState.eraser) {
            Eraser.PARTIAL -> touchHelper!!.setStrokeStyle(penToStroke(Pen.MARKER))
                ?.setStrokeWidth(
                    convertDpToPixel(
                        GlobalAppSettings.current.partialEraserDiameterDp.dp,
                        drawCanvas.context,
                    )
                )
                ?.setStrokeColor(penEraserColor)

            Eraser.PEN -> touchHelper!!.setStrokeStyle(penToStroke(Pen.MARKER))
                ?.setStrokeWidth(30f)
                ?.setStrokeColor(penEraserColor)

            Eraser.SELECT -> {
                val dashStyleID = penToStroke(Pen.DASHED)
                touchHelper!!.setStrokeStyle(dashStyleID)
                    ?.setStrokeWidth(3f)
                    ?.setStrokeColor(Color.BLACK)
                val params = FloatArray(4)
                params[0] = 5f // thickness
                params[1] = 9f // no idea
                params[2] = 9f // no idea
                params[3] = 0f // no idea
                Device.currentDevice().setStrokeParameters(dashStyleID, params)
            }
        }
    }

    suspend fun updateIsDrawing() {
        if(touchHelper == null) return
        log.i("Update is drawing: $toolbarState.isDrawing")
        if (toolbarState.isDrawing) {
            touchHelper!!.setRawDrawingEnabled(true)
        } else {
            // Check if drawing is completed
            CanvasEventBus.waitForDrawing()
            // draw to view, before showing drawing, avoid stutter
            drawCanvas.refreshManager.drawCanvasToView(null)
            touchHelper!!.setRawDrawingEnabled(false)
        }
    }

    fun updateActiveSurface() {
        // Takes at least 50ms on Note 4c,
        // and I don't think that we need it immediately
        log.i("Update editable surface")
        // (Re)claim the raw-input surface: this runs whenever our canvas's surface is
        // (re)created, e.g. after a release on surfaceDestroyed during sleep/rotation.
        if (touchHelper != null) rawInputSurfaceOwner = WeakReference(this)
        coroutineScope.launch {
            onSurfaceInit(drawCanvas)
            val toolbarHeight =
                if (toolbarState.isToolbarOpen) convertDpToPixel(40.dp, drawCanvas.context).toInt() else 0
            setupSurface(
                drawCanvas,
                touchHelper,
                toolbarHeight
            )
        }
    }

    private fun configuredStrokeEndpoints(points: List<TouchPoint>): Pair<StrokePoint, StrokePoint> {
        val (rawStart, rawEnd) = getModifiedStrokeEndpoints(
            points,
            page.scroll,
            page.zoomLevel.value,
        )
        val setting = currentPenSetting.takeIf { toolbarState.pen.supportsPressure }
            ?: return rawStart to rawEnd
        val maxPressure = OnyxCapabilities.current.maxTouchPressure
        return rawStart.withPressureSettings(setting, maxPressure) to
                rawEnd.withPressureSettings(setting, maxPressure)
    }

    private fun configuredStrokePoints(points: List<TouchPoint>): List<StrokePoint> = copyInput(
        touchPoints = points,
        scroll = page.scroll,
        scale = page.zoomLevel.value,
        pressureSetting = currentPenSetting.takeIf { toolbarState.pen.supportsPressure },
        maxPressure = OnyxCapabilities.current.maxTouchPressure,
    )

    private fun rawShapePoint(point: TouchPoint): StrokePoint = copyInput(
        touchPoints = listOf(point),
        scroll = Offset.Zero,
        scale = 1f,
        pressureSetting = currentPenSetting.takeIf { toolbarState.pen.supportsPressure },
        maxPressure = OnyxCapabilities.current.maxTouchPressure,
    ).first()

    private fun shapePerfectionAllowed(): Boolean =
        GlobalAppSettings.current.shapePerfectionEnabled &&
                toolbarState.mode == Mode.Draw &&
                toolbarState.pen != Pen.MARKER

    private fun onShapeDwell(rawPoints: List<StrokePoint>) {
        if (!shapePerfectionAllowed()) return
        val rawResult = ShapeRecognizer.recognize(rawPoints) ?: return
        val zoom = page.zoomLevel.value
        val pagePoints = rawResult.points.map { point ->
            point.copy(
                x = point.x / zoom + page.scroll.x,
                y = point.y / zoom + page.scroll.y,
            )
        }
        val result = rawResult.copy(points = pagePoints)
        recognizedShape = result
        dwellDetector.markRecognized()

        val setting = currentPenSetting
        val bounds = calculateBoundingBox(pagePoints) { it.x to it.y }.apply {
            inset(-setting.strokeSize, -setting.strokeSize)
        }
        val preview = Stroke(
            size = setting.strokeSize,
            pen = toolbarState.pen,
            color = setting.color,
            maxPressure = OnyxCapabilities.current.maxTouchPressure.toInt(),
            top = bounds.top,
            bottom = bounds.bottom,
            left = bounds.left,
            right = bounds.right,
            points = pagePoints,
            pageId = page.currentPageId,
        )
        page.drawAreaPageCoordinates(bounds.toRect())
        drawStroke(page.windowedCanvas, preview, -page.scroll)
        drawCanvas.refreshManager.previewShape(page.toScreenCoordinates(bounds.toRect()))
    }

    @Suppress("LongMethod")
    private fun onRawDrawingList(plist: TouchPointList) {
        if (touchHelper == null) return
        val currentLastStrokeEndTime = lastStrokeEndTime
        lastStrokeEndTime = System.currentTimeMillis()
        val startTime = System.currentTimeMillis()

        when (toolbarState.mode) {
            Mode.Erase -> onRawErasingList(plist)
            Mode.Select -> {
                thread {
                    val points =
                        copyInputToSimplePointF(plist.points, page.scroll, page.zoomLevel.value)
                    handleSelect(
                        scope = coroutineScope,
                        page = drawCanvas.page,
                        viewModel = viewModel,
                        points = points
                    )
                    val boundingBox = calculateBoundingBox(points) { Pair(it.x, it.y) }.toRect()
                    val padding = 10
                    val dirtyRect = Rect(
                        boundingBox.left - padding,
                        boundingBox.top - padding,
                        boundingBox.right + padding,
                        boundingBox.bottom + padding
                    )
                    drawCanvas.refreshManager.refreshUi(dirtyRect)
                }
            }

            Mode.Text, Mode.Link -> {
                plist.points.firstOrNull()?.let { point ->
                    CanvasEventBus.emitContentTap(
                        CanvasEventBus.ContentTap(Offset(point.x, point.y), stylus = true)
                    )
                }
            }

            Mode.Line -> {
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    CanvasEventBus.drawingInProgress.withLock {
                        val lock = System.currentTimeMillis()
                        log.d("lock obtained in ${lock - startTime} ms")


                        val (startPoint, endPoint) = configuredStrokeEndpoints(plist.points)
                        val linePoints = transformToLine(startPoint, endPoint)

                        val penSetting = currentPenSetting
                        handleDraw(
                            drawCanvas.page,
                            strokeHistoryBatch,
                            penSetting.strokeSize,
                            penSetting.color,
                            toolbarState.pen,
                            linePoints
                        )

                        coroutineScope.launch(Dispatchers.Default) {
                            val dirtyRect = Rect(
                                min(startPoint.x, endPoint.x).toInt(),
                                min(startPoint.y, endPoint.y).toInt(),
                                max(startPoint.x, endPoint.x).toInt(),
                                max(startPoint.y, endPoint.y).toInt()
                            )
                            drawCanvas.refreshManager.refreshUi(dirtyRect)
                            CanvasEventBus.commitHistorySignal.emit(Unit)
                        }
                    }

                }
            }

            Mode.Draw -> {
                coroutineScope.launch(Dispatchers.Main.immediate) {
                    CanvasEventBus.drawingInProgress.withLock {
                        val lock = System.currentTimeMillis()
                        log.d("lock obtained in ${lock - startTime} ms")

                        val perfected = recognizedShape
                        val scaledPoints = perfected?.points ?: configuredStrokePoints(plist.points)
                        val firstPointTime = plist.points.first().timestamp
                        val erasedByScribbleDirtyRect = if (perfected == null) {
                            handleScribbleToErase(
                                page,
                                scaledPoints,
                                history,
                                toolbarState.pen,
                                currentLastStrokeEndTime,
                                firstPointTime
                            )
                        } else null
                        if (erasedByScribbleDirtyRect.isNullOrEmpty()) {
                            log.d("Drawing...")
                            // draw the stroke
                            val penSetting = currentPenSetting
                            handleDraw(
                                drawCanvas.page,
                                strokeHistoryBatch,
                                penSetting.strokeSize,
                                penSetting.color,
                                toolbarState.pen,
                                scaledPoints
                            )
                            if (perfected != null) {
                                val pageBounds = calculateBoundingBox(scaledPoints) { it.x to it.y }.toRect()
                                drawCanvas.refreshManager.refreshUi(page.toScreenCoordinates(pageBounds))
                            }
                        } else {
                            log.d("Erased by scribble, $erasedByScribbleDirtyRect")
                            // Union the scribble track (firmware screen coords) with the erased
                            // strokes' bounds so commitErase overwrites both in one pass while
                            // still frozen. Scribble is not drawn into the page bitmap — we only
                            // need the region to cover the firmware's live track.
                            // See docs/onyx-sdk/onyx-scribble-to-erase.md.
                            val padding = 10
                            val trackBox =
                                calculateBoundingBox(plist.points) { Pair(it.x, it.y) }.toRect()
                            val dirty = Rect(
                                trackBox.left - padding,
                                trackBox.top - padding,
                                trackBox.right + padding,
                                trackBox.bottom + padding
                            )
                            erasedByScribbleDirtyRect.let { dirty.union(it) }
                            // Use areaErase=true for the longer 500ms settle (scribble is a large gesture).
                            drawCanvas.refreshManager.commitErase(dirty, areaErase = true)
                        }

                        recognizedShape = null
                        dwellDetector.reset()

                    }
                    coroutineScope.launch(Dispatchers.Default) {
                        CanvasEventBus.commitHistorySignal.emit(Unit)
                    }
                }
            }
        }
    }

    private fun onRawErasingList(plist: TouchPointList?) {
        isErasing = false

        if (plist == null) return
        val points = copyInputToSimplePointF(plist.points, page.scroll, page.zoomLevel.value)

        val padding = 10
        val boundingBox = (calculateBoundingBox(plist.points) { Pair(it.x, it.y) }).toRect()
        val strokeArea = Rect(
            boundingBox.left - padding,
            boundingBox.top - padding,
            boundingBox.right + padding,
            boundingBox.bottom + padding
        )
        val zoneEffected = handleErase(
            drawCanvas.page,
            history,
            points,
            eraser = toolbarState.eraser,
            partialRadius = convertDpToPixel(
                GlobalAppSettings.current.partialEraserDiameterDp.dp,
                drawCanvas.context,
            ) / 2f / page.zoomLevel.value,
        )

        // Single atomic commit of the whole touched region: the native eraser indicator
        // track spans strokeArea, the erased strokes' bounds are zoneEffected, so repainting
        // their union both wipes the indicator and shows the erased result in one pass.
        // commitErase blocks input, draws synchronously, then drops the firmware overlay so
        // indicator + strokes disappear together (no double refresh, no gap to draw into).
        // See docs/onyx-sdk/onyx-pen-up-refresh-and-screen-freeze.md.
        val dirty = Rect(strokeArea)
        if (zoneEffected != null) dirty.union(zoneEffected)
        // Area (lasso/select) erase needs the longer 500ms settle the official app uses; the
        // pen/marker erase uses the 150ms stroke settle.
        drawCanvas.refreshManager.commitErase(dirty, areaErase = toolbarState.eraser == Eraser.SELECT)
    }

}
