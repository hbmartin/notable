package com.ethran.notable.editor.drawing

import com.ethran.notable.data.db.CanvasText
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class CanvasTextRendererTest {
    @Test fun markdown_layout_is_measured_cached_and_invalidated() {
        val context = RuntimeEnvironment.getApplication()
        val item = CanvasText(pageId = "p", markdown = "# Title\n\n- [x] task", x = 0f, y = 0f, width = 300f, height = 120f)
        val first = CanvasTextRenderer.layout(context, item)
        assertTrue(first.height > 0)
        assertSame(first, CanvasTextRenderer.layout(context, item))
        CanvasTextRenderer.invalidate(item.id)
        assertNotSame(first, CanvasTextRenderer.layout(context, item))
    }
}
