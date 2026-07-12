package com.ethran.notable.editor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkTargetParserTest {
    @Test fun parsesInternalTargets() {
        assertEquals(NotableDeepLinkTarget.Page("p1"), LinkTargetParser.parseNotableUri("notable://page-p1"))
        assertEquals(NotableDeepLinkTarget.Notebook("b1"), LinkTargetParser.parseNotableUri("notable://book-b1"))
        assertNull(LinkTargetParser.parseNotableUri("notable://unknown-x"))
    }

    @Test fun acceptsOnlyHttpWebTargets() {
        assertTrue(LinkTargetParser.isSafeWebUrl("https://example.com/a"))
        assertTrue(LinkTargetParser.isSafeWebUrl("http://localhost"))
        assertFalse(LinkTargetParser.isSafeWebUrl("javascript:alert(1)"))
        assertFalse(LinkTargetParser.isSafeWebUrl("file:///etc/passwd"))
    }
}
