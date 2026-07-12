package com.ethran.notable.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AtomicFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writeReplacesExistingFile() {
        val target = temporaryFolder.newFile("preview.webp")
        target.writeText("old")

        AtomicFileStore.write(target) { it.write("new".toByteArray()) }

        assertEquals("new", target.readText())
        assertFalse(safeListFiles(requireNotNull(target.parentFile)).any { it.name.contains(".notable-") })
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
        assertFalse(safeListFiles(requireNotNull(target.parentFile)).any { it.name.contains(".notable-tmp-") })
    }

    @Test
    fun writesToDifferentTargetsDoNotBlockEachOthersWriters() {
        val directory = temporaryFolder.newFolder("parallel")
        val firstTarget = java.io.File(directory, "first.bin")
        val secondTarget = java.io.File(directory, "second.bin")
        val firstWriterStarted = CountDownLatch(1)
        val releaseFirstWriter = CountDownLatch(1)
        val secondWriteCompleted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit {
                AtomicFileStore.write(firstTarget) { output ->
                    firstWriterStarted.countDown()
                    check(releaseFirstWriter.await(5, TimeUnit.SECONDS))
                    output.write("first".toByteArray())
                }
            }
            assertTrue(firstWriterStarted.await(5, TimeUnit.SECONDS))

            val second = executor.submit {
                AtomicFileStore.write(secondTarget) { output ->
                    output.write("second".toByteArray())
                }
                secondWriteCompleted.countDown()
            }

            assertTrue(secondWriteCompleted.await(5, TimeUnit.SECONDS))
            releaseFirstWriter.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
        } finally {
            releaseFirstWriter.countDown()
            executor.shutdownNow()
        }

        assertEquals("first", firstTarget.readText())
        assertEquals("second", secondTarget.readText())
    }

    @Test
    fun writeDoesNotScanDirectoryForStaleFiles() {
        val directory = temporaryFolder.newFolder("no-hot-path-recovery")
        val staleTemp = java.io.File(directory, ".old.notable-tmp-stale")
        staleTemp.writeText("partial")
        staleTemp.setLastModified(0L)

        AtomicFileStore.write(java.io.File(directory, "current.bin")) {
            it.write("complete".toByteArray())
        }

        assertTrue(staleTemp.exists())
    }

    @Test
    fun writeSucceedsWhenCompletedWriteBackupCannotBeDeleted() {
        val directory = temporaryFolder.newFolder("stuck-backup")
        val target = java.io.File(directory, "notes.bin")
        target.writeText("old")
        // A non-empty directory at the backup path makes backup.delete() fail.
        val backup = java.io.File(directory, "notes.bin.notable-backup")
        check(backup.mkdirs())
        check(java.io.File(backup, "occupant").createNewFile())

        AtomicFileStore.write(target) { it.write("new".toByteArray()) }

        assertEquals("new", target.readText())
        assertTrue(backup.exists())
    }

    @Test
    fun recoveryContinuesWhenStaleBackupCannotBeDeleted() {
        val directory = temporaryFolder.newFolder("stuck-recovery")
        java.io.File(directory, "notes.bin").writeText("current")
        val stuckBackup = java.io.File(directory, "notes.bin.notable-backup")
        check(stuckBackup.mkdirs())
        check(java.io.File(stuckBackup, "occupant").createNewFile())
        val restorableBackup = java.io.File(directory, "other.bin.notable-backup")
        restorableBackup.writeText("restored")

        AtomicFileStore.recoverStaleFiles(directory)

        assertEquals("restored", java.io.File(directory, "other.bin").readText())
        assertFalse(restorableBackup.exists())
        assertTrue(stuckBackup.exists())
    }

    @Test
    fun recoveryRestoresBackupAndDeletesStaleTemporaryFile() {
        val directory = temporaryFolder.newFolder("recover")
        val backup = java.io.File(directory, "page.webp.notable-backup")
        backup.writeText("recovered")
        val staleTemp = java.io.File(directory, ".page.webp.notable-tmp-stale")
        val unrelatedTemp = java.io.File(directory, "user-document.tmp")
        staleTemp.writeText("partial")
        unrelatedTemp.writeText("keep me")
        staleTemp.setLastModified(0L)
        unrelatedTemp.setLastModified(0L)

        AtomicFileStore.recoverStaleFiles(directory)

        assertEquals("recovered", java.io.File(directory, "page.webp").readText())
        assertFalse(backup.exists())
        assertFalse(staleTemp.exists())
        assertEquals("keep me", unrelatedTemp.readText())
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
