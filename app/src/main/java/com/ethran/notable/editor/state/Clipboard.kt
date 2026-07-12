package com.ethran.notable.editor.state

import android.content.ClipboardManager
import android.content.Context
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.io.safeListFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Clipboard payload shared across the editor.
 */
data class ClipboardContent(
    val strokes: List<Stroke> = emptyList(),
    val images: List<Image> = emptyList(),
)

/**
 * Process-wide clipboard store used by the editor.
 */
object ClipboardStore {
    private val _content = MutableStateFlow<ClipboardContent?>(null)
    val content: StateFlow<ClipboardContent?> = _content.asStateFlow()

    fun set(value: ClipboardContent?) {
        _content.value = value
    }

    fun get(): ClipboardContent? = _content.value

    /** Clear editor objects, the Android clipboard, and cached clipboard/share images. */
    fun clear(context: Context): Boolean {
        _content.value = null
        var complete = true
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.clearPrimaryClip()
        }.onFailure { complete = false }

        val imageCache = java.io.File(context.cacheDir, "images")
        safeListFiles(imageCache).forEach { file ->
            if (!file.delete() && file.exists()) complete = false
        }
        return complete
    }
}
