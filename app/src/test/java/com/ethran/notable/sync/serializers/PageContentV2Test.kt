package com.ethran.notable.sync.serializers

import com.ethran.notable.data.db.Attachment
import com.ethran.notable.data.db.AttachmentStorageMode
import com.ethran.notable.data.db.CanvasLink
import com.ethran.notable.data.db.CanvasText
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.LinkTargetType
import com.ethran.notable.data.db.Page
import com.ethran.notable.utils.AppResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageContentV2Test {
    @Test
    fun roundTrip_preserves_canvas_content_and_excludes_device_uri_bindings() {
        val page = Page(id = "p", notebookId = null)
        val content = PageContent(
            page = page,
            strokes = emptyList(),
            images = listOf(Image(id = "i", x = 1, y = 2, width = 3, height = 4, rotation = 45f, flipHorizontal = true, pageId = page.id)),
            texts = listOf(CanvasText(id = "t", pageId = page.id, markdown = "**hello**", x = 1f, y = 2f, width = 200f, height = 80f)),
            links = listOf(CanvasLink(id = "l", pageId = page.id, label = "site", target = "https://example.com", targetType = LinkTargetType.URL, x = 3f, y = 4f, width = 100f, height = 30f)),
            attachments = listOf(Attachment(id = "a", pageId = page.id, displayName = "local.pdf", storageMode = AttachmentStorageMode.OBSERVED)),
        )

        val json = NotebookSerializer.serializePage(content)
        assertFalse(json.contains("content://device-only"))
        assertTrue(json.contains("\"version\": 2"))
        val restored = (NotebookSerializer.deserializePage(json) as? AppResult.Success)?.data
            ?: error("Expected successful v2 round trip")
        assertEquals("**hello**", restored.texts.single().markdown)
        assertEquals(LinkTargetType.URL, restored.links.single().targetType)
        assertEquals(AttachmentStorageMode.OBSERVED, restored.attachments.single().storageMode)
        assertEquals(45f, restored.images.single().rotation)
        assertTrue(restored.images.single().flipHorizontal)
    }
}
