package com.ethran.notable.editor

import android.graphics.Color as AndroidColor
import android.widget.TextView
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Slider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.data.db.CanvasLink
import com.ethran.notable.data.db.CanvasText
import com.ethran.notable.data.db.LinkTargetType
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import kotlinx.coroutines.launch

@Composable
fun CanvasContentEditor(controlTower: EditorControlTower, page: PageView, viewModel: EditorViewModel) {
    val draft by controlTower.contentDraft.collectAsStateWithLifecycle()
    when (val edit = draft) {
        is CanvasContentDraft.Text -> TextEditor(edit, controlTower, page)
        is CanvasContentDraft.Link -> LinkEditor(edit, controlTower, page, viewModel)
        null -> Unit
    }
}

@Composable
private fun TextEditor(edit: CanvasContentDraft.Text, controlTower: EditorControlTower, page: PageView) {
    var value by remember(edit.value.id) { mutableStateOf(edit.value) }
    var rendered by remember(edit.value.id) { mutableStateOf(false) }
    val position = screenPosition(value.x, value.y, page)
    Column(
        Modifier.offset { position }
            .width(440.dp)
            .background(Color.White)
            .border(1.dp, Color.Black)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (rendered) {
            AndroidView(
                factory = { context -> TextView(context) },
                update = { view ->
                    val markwon = Markwon.builder(view.context)
                        .usePlugin(StrikethroughPlugin.create())
                        .usePlugin(TablePlugin.create(view.context))
                        .usePlugin(TaskListPlugin.create(view.context))
                        .build()
                    markwon.setMarkdown(view, value.markdown)
                    view.textSize = value.fontSize
                    view.setTextColor(value.color)
                    view.setBackgroundColor(value.backgroundColor)
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            )
        } else {
            OutlinedTextField(
                value = value.markdown,
                onValueChange = { value = value.copy(markdown = it) },
                label = { Text("Markdown") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { rendered = !rendered }) { Text(if (rendered) "Source" else "Render") }
            Button(onClick = { value = value.copy(fontSize = (value.fontSize - 2f).coerceAtLeast(10f)) }) { Text("A−") }
            Button(onClick = { value = value.copy(fontSize = (value.fontSize + 2f).coerceAtMost(96f)) }) { Text("A+") }
            Button(onClick = { value = value.copy(alignment = nextAlignment(value.alignment)) }) { Text(value.alignment.lowercase()) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(AndroidColor.BLACK, AndroidColor.RED, AndroidColor.BLUE).forEach { color ->
                Button(onClick = { value = value.copy(color = color) }) { Text("●", color = Color(color)) }
            }
            Button(onClick = {
                value = value.copy(
                    backgroundColor = if (value.backgroundColor == AndroidColor.TRANSPARENT) 0x22FFFF00 else AndroidColor.TRANSPARENT
                )
            }) { Text("Background") }
        }
        Text("Box width ${value.width.toInt()}")
        Slider(
            value = value.width.coerceIn(80f, 800f),
            onValueChange = { value = value.copy(width = it) },
            valueRange = 80f..800f,
        )
        PositionControls(value.x, value.y) { x, y -> value = value.copy(x = x, y = y) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { controlTower.commitText(edit.original, value) }) { Text("Done") }
            Button(onClick = controlTower::cancelContentEdit) { Text("Cancel") }
            edit.original?.let { original ->
                Button(onClick = { controlTower.deleteText(original) }) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun LinkEditor(
    edit: CanvasContentDraft.Link,
    controlTower: EditorControlTower,
    page: PageView,
    viewModel: EditorViewModel,
) {
    var value by remember(edit.value.id) { mutableStateOf(edit.value) }
    var selectedPdf by remember(edit.value.id) { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedPdf = uri
    }
    val position = screenPosition(value.x, value.y, page)
    Column(
        Modifier.offset { position }
            .width(420.dp)
            .background(Color.White)
            .border(1.dp, Color.Black)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Link")
        OutlinedTextField(
            value = value.label,
            onValueChange = { value = value.copy(label = it) },
            label = { Text("Visible label") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (value.targetType == LinkTargetType.PDF_ATTACHMENT) {
            Button(onClick = { pickPdf.launch(arrayOf("application/pdf")) }) { Text("Choose PDF") }
            selectedPdf?.let { uri ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val attachment = viewModel.importPdfAttachment(page.currentPageId, uri, copy = true)
                            value = value.copy(target = attachment.id, label = attachment.displayName)
                            selectedPdf = null
                        }
                    }) { Text("Copy") }
                    Button(onClick = {
                        scope.launch {
                            val attachment = viewModel.importPdfAttachment(page.currentPageId, uri, copy = false)
                            value = value.copy(target = attachment.id, label = attachment.displayName)
                            selectedPdf = null
                        }
                    }) { Text("Observe") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LinkTargetType.entries.forEach { type ->
                Button(onClick = { value = value.copy(targetType = type) }) {
                    Text(if (value.targetType == type) "✓ ${type.name}" else type.name)
                }
            }
        }
        OutlinedTextField(
            value = value.target,
            onValueChange = { value = value.copy(target = it) },
            label = { Text(targetHint(value.targetType)) },
            modifier = Modifier.fillMaxWidth(),
        )
        PositionControls(value.x, value.y) { x, y -> value = value.copy(x = x, y = y) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { controlTower.commitLink(edit.original, value) }) { Text("Done") }
            Button(onClick = controlTower::cancelContentEdit) { Text("Cancel") }
            edit.original?.let { original ->
                Button(onClick = { controlTower.deleteLink(original) }) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun PositionControls(x: Float, y: Float, onChange: (Float, Float) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Move")
        Button(onClick = { onChange(x - 20f, y) }) { Text("←") }
        Button(onClick = { onChange(x + 20f, y) }) { Text("→") }
        Button(onClick = { onChange(x, y - 20f) }) { Text("↑") }
        Button(onClick = { onChange(x, y + 20f) }) { Text("↓") }
    }
}

private fun screenPosition(x: Float, y: Float, page: PageView): IntOffset = IntOffset(
    ((x - page.scroll.x) * page.zoomLevel.value).toInt().coerceAtLeast(0),
    ((y - page.scroll.y) * page.zoomLevel.value).toInt().coerceAtLeast(0),
)

private fun nextAlignment(current: String): String = when (current) {
    "NORMAL" -> "CENTER"
    "CENTER" -> "OPPOSITE"
    else -> "NORMAL"
}

private fun targetHint(type: LinkTargetType): String = when (type) {
    LinkTargetType.PAGE -> "Page ID"
    LinkTargetType.NOTEBOOK -> "Notebook ID"
    LinkTargetType.URL -> "https:// URL"
    LinkTargetType.PDF_ATTACHMENT -> "PDF attachment ID"
}
