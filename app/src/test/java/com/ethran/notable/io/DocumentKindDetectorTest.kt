package com.ethran.notable.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentKindDetectorTest {
    @Test
    fun pdfSignatureOverridesUnhelpfulMetadata() {
        val result = DocumentKindDetector.classify(
            rawPrefix = "comment\n%PDF-1.7\n".toByteArray(),
            expandedGzipPrefix = null,
            mimeType = "application/octet-stream",
            displayName = "download.bin",
        )

        assertEquals(DocumentKind.PDF, result.kind)
        assertTrue(result.isSupported)
    }

    @Test
    fun pdfMetadataCannotOverrideWrongSignature() {
        val result = DocumentKindDetector.classify(
            rawPrefix = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()),
            expandedGzipPrefix = null,
            mimeType = "application/pdf",
            displayName = "renamed.pdf",
        )

        assertEquals(DocumentKind.UNKNOWN, result.kind)
        assertFalse(result.isSupported)
        assertTrue(result.error!!.contains("signature"))
    }

    @Test
    fun xoppRequiresGzipAndXournalRoot() {
        val result = DocumentKindDetector.classify(
            rawPrefix = byteArrayOf(0x1f, 0x8b.toByte(), 0x08),
            expandedGzipPrefix = "<?xml version=\"1.0\"?><xournal version=\"0.4\">".toByteArray(),
            mimeType = "application/gzip",
            displayName = "notes.xopp",
        )

        assertEquals(DocumentKind.XOPP, result.kind)
    }

    @Test
    fun arbitraryGzipIsNotXopp() {
        val result = DocumentKindDetector.classify(
            rawPrefix = byteArrayOf(0x1f, 0x8b.toByte(), 0x08),
            expandedGzipPrefix = "plain text".toByteArray(),
            mimeType = "application/gzip",
            displayName = "archive.xopp",
        )

        assertEquals(DocumentKind.UNKNOWN, result.kind)
    }
}
