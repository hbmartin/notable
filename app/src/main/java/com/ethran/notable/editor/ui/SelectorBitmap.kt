package com.ethran.notable.editor.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.ethran.notable.R
import com.ethran.notable.data.datastore.BUTTON_SIZE
import com.ethran.notable.editor.EditorControlTower
import com.ethran.notable.editor.ui.toolbar.ToolbarButton
import com.ethran.notable.io.shareBitmap
import com.ethran.notable.ui.noRippleClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clipboard
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Scissors
import compose.icons.feathericons.Share2

val strokeStyle = Stroke(
    width = 2f,
    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
)

@Composable
@Suppress("LongMethod")
fun SelectedBitmap(
    context: Context,
    controlTower: EditorControlTower
) {
    val selectionState = controlTower.getSnapshotOfSelectionState()
    if (selectionState.selectedBitmap == null) return

    var selectionDisplaceOffset =
        controlTower.page.applyZoom(selectionState.selectionDisplaceOffset ?: return)
    val selectionRect =
        controlTower.page.toScreenCoordinates(selectionState.selectionRect ?: return)
    val selectionStartOffset =
        controlTower.page.applyZoom(selectionState.selectionStartOffset ?: IntOffset(0, 0))


    Box(
        Modifier
            .fillMaxSize()
            .noRippleClickable {
                controlTower.commitPreviewTransform()
                selectionState.reset()
                controlTower.setIsDrawing(true)
            }) {
        Image(
            bitmap = selectionState.selectedBitmap!!.asImageBitmap(),
            contentDescription = "Selection bitmap",
            modifier = Modifier
                .offset { selectionStartOffset + selectionDisplaceOffset }
                .graphicsLayer(
                    scaleX = selectionState.previewScaleX,
                    scaleY = selectionState.previewScaleY,
                    rotationZ = selectionState.previewRotation,
                )
                .drawBehind {
                    drawRect(
                        color = Color.Gray,
                        topLeft = Offset(0f, 0f),
                        size = size,
                        style = strokeStyle
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        selectionState.selectionDisplaceOffset =
                            controlTower.page.removeZoom(
                                selectionDisplaceOffset + dragAmount.round()
                            )
                        selectionDisplaceOffset =
                            controlTower.page.applyZoom(
                                selectionState.selectionDisplaceOffset ?: return@detectDragGestures
                            )
                    }
                }
                .combinedClickable(
                    indication = null, interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                    onDoubleClick = { controlTower.duplicateSelection() }
                )
        )

        // TODO: improve this code

        val buttonCount = if (selectionState.isResizable()) 10 else 5
        val toolbarPadding = 4

        // If we can calculate offset of buttons show selection handling tools
        selectionStartOffset.let { startOffset ->
            selectionDisplaceOffset.let { displaceOffset ->
                // TODO: I think the toolbar is still not in the center.
                val xPos = selectionRect.let { rect ->
                    (rect.right - rect.left) / 2 - buttonCount * (BUTTON_SIZE + 5 * toolbarPadding)
                }
                val offset = startOffset + displaceOffset + IntOffset(x = xPos, y = -100)
                // Overlay buttons near the selection box
                Row(
                    modifier = Modifier
                        .offset { offset }
                        .background(Color.White.copy(alpha = 0.8f))
                        .padding(toolbarPadding.dp)
                        .height(BUTTON_SIZE.dp)
                ) {
                    ToolbarButton(
                        vectorIcon = FeatherIcons.Share2,
                        isSelected = false,
                        onSelect = {
                            shareBitmap(context, controlTower.getSelectedBitmap())
                        },
                        modifier = Modifier.height(BUTTON_SIZE.dp)
                    )
                    ToolbarButton(
                        iconId = R.drawable.delete,
                        isSelected = false,
                        onSelect = {
                            controlTower.deleteSelection()
                        },
                        modifier = Modifier.height(BUTTON_SIZE.dp)
                    )
                    if (selectionState.isResizable()) {
                        ToolbarButton(
                            iconId = R.drawable.plus,
                            isSelected = false,
                            onSelect = { controlTower.changeSizeOfSelection(10) },
                            modifier = Modifier.height(BUTTON_SIZE.dp)
                        )
                        ToolbarButton(
                            iconId = R.drawable.ic_rotate_selection,
                            isSelected = false,
                            onSelect = { controlTower.rotateSelection(90f) },
                            modifier = Modifier.height(BUTTON_SIZE.dp),
                            contentDescription = "Rotate selection",
                        )
                        ToolbarButton(
                            iconId = R.drawable.ic_flip_horizontal,
                            isSelected = false,
                            onSelect = { controlTower.flipSelection(horizontal = true) },
                            modifier = Modifier.height(BUTTON_SIZE.dp),
                            contentDescription = "Flip horizontally",
                        )
                        ToolbarButton(
                            iconId = R.drawable.ic_flip_vertical,
                            isSelected = false,
                            onSelect = { controlTower.flipSelection(horizontal = false) },
                            modifier = Modifier.height(BUTTON_SIZE.dp),
                            contentDescription = "Flip vertically",
                        )
                        ToolbarButton(
                            iconId = R.drawable.minus,
                            isSelected = false,
                            onSelect = { controlTower.changeSizeOfSelection(-10) },
                            modifier = Modifier.height(BUTTON_SIZE.dp)
                        )
                    }
                    ToolbarButton(
                        vectorIcon = FeatherIcons.Scissors,
                        isSelected = false,
                        onSelect = { controlTower.cutSelectionToClipboard(context) },
                        modifier = Modifier.height(BUTTON_SIZE.dp)
                    )
                    ToolbarButton(
                        vectorIcon = FeatherIcons.Clipboard,
                        isSelected = false,
                        onSelect = { controlTower.copySelectionToClipboard(context) },
                        modifier = Modifier.height(BUTTON_SIZE.dp)
                    )
                    ToolbarButton(
                        vectorIcon = FeatherIcons.Copy,
                        isSelected = false,
                        onSelect = { controlTower.duplicateSelection() },
                        modifier = Modifier.height(BUTTON_SIZE.dp)
                    )
                }

                val topLeft = startOffset + displaceOffset
                val width = selectionRect.width().coerceAtLeast(1)
                val height = selectionRect.height().coerceAtLeast(1)
                TransformHandle(
                    offset = topLeft + IntOffset(width, height),
                    onDrag = { delta ->
                        val factor = (1f + (delta.x + delta.y) / (width + height)).coerceAtLeast(0.05f)
                        selectionState.previewScaleX *= factor
                        selectionState.previewScaleY *= factor
                    },
                    onCommit = controlTower::commitPreviewTransform,
                )
                TransformHandle(
                    offset = topLeft + IntOffset(width, height / 2),
                    onDrag = { delta ->
                        selectionState.previewScaleX =
                            (selectionState.previewScaleX + delta.x / width).coerceAtLeast(0.05f)
                    },
                    onCommit = controlTower::commitPreviewTransform,
                )
                TransformHandle(
                    offset = topLeft + IntOffset(width / 2, height),
                    onDrag = { delta ->
                        selectionState.previewScaleY =
                            (selectionState.previewScaleY + delta.y / height).coerceAtLeast(0.05f)
                    },
                    onCommit = controlTower::commitPreviewTransform,
                )
                TransformHandle(
                    offset = topLeft + IntOffset(width / 2, -35),
                    color = Color.DarkGray,
                    onDrag = { delta -> selectionState.previewRotation += delta.x * 0.5f },
                    onCommit = controlTower::commitPreviewTransform,
                )
            }
        }

    }
}

@Composable
private fun TransformHandle(
    offset: IntOffset,
    color: Color = Color.White,
    onDrag: (Offset) -> Unit,
    onCommit: () -> Unit,
) {
    Box(
        Modifier
            .offset { offset - IntOffset(10, 10) }
            .size(20.dp)
            .background(color)
            .border(2.dp, Color.Black)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = onCommit,
                    onDragCancel = onCommit,
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            }
    )
}
