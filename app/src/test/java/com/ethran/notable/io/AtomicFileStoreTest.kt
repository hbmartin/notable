package com.ethran.notable.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class AtomicFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writeReplacesExistingFile() {
        val target = temporaryFolder.newFile("preview.webp")
        target.writeText("old")

        AtomicFileStore.write(target) { it.write("new".toByteArray()) }

        assertEquals("new", target.readText())
        assertFalse(safeListFiles(target.parentFile!!).any { it.name.contains(".notable-") })
    }

    @Test
    fun failedWriterPreservesExistingFileAndCleansTemporaryFile() {
        val target = temporaryFolder.newFile("sync.bin")
        target.writeText("complete")

        try {
            AtomicFileStore.write(target) {
                it.write("partial".toByteArray())
                throw IOException("interrupted")
            }
        } catch (_: IOException) {
            // Expected.
        }

        assertEquals("complete", target.readText())
        assertFalse(safeListFiles(target.parentFile!!).any { it.name.contains(".notable-tmp-") })
    }

    @Test
    fun recoveryRestoresBackupAndDeletesStaleTemporaryFile() {
        val directory = temporaryFolder.newFolder("recover")
        val backup = java.io.File(directory, "page.webp.notable-backup")
        backup.writeText("recovered")
        val staleTemp = java.io.File(directory, ".page.webp.notable-tmp-stale")
        staleTemp.writeText("partial")
        staleTemp.setLastModified(0L)

        AtomicFileStore.recoverStaleFiles(directory)

        assertEquals("recovered", java.io.File(directory, "page.webp").readText())
        assertFalse(backup.exists())
        assertFalse(staleTemp.exists())
        assertTrue(java.io.File(directory, "page.webp").isFile)
    }

    @Test
    fun safeLeafNameRejectsTraversalAndNormalizesRemotePaths() {
        assertEquals("image.png", safeLeafName("images/image.png"))
        assertEquals("image.png", safeLeafName("images\\image.png"))
        assertNull(safeLeafName(".."))
        assertNull(safeLeafName("folder/.."))
    }
}
