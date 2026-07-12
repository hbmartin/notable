package com.ethran.notable.io

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

class ExportOutputStreamTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writerCloseDoesNotCloseExporterOwnedFile() {
        val file = temporaryFolder.newFile("prepared-export.bin")

        FileOutputStream(file).use { ownedOutput ->
            NonClosingOutputStream(ownedOutput).use { writerOutput ->
                writerOutput.write("payload".toByteArray())
            }

            ownedOutput.fd.sync()
            ownedOutput.write("-synced".toByteArray())
        }

        assertEquals("payload-synced", file.readText())
    }

    @Test
    fun bulkWritesDelegateWithoutFallingBackToSingleByteWrites() {
        val ownedOutput = RecordingOutputStream()

        NonClosingOutputStream(ownedOutput).write(ByteArray(8 * 1024))

        assertEquals(1, ownedOutput.bulkWriteCount.get())
        assertEquals(0, ownedOutput.singleByteWriteCount.get())
    }

    private class RecordingOutputStream : OutputStream() {
        val bulkWriteCount = AtomicInteger()
        val singleByteWriteCount = AtomicInteger()

        override fun write(value: Int) {
            singleByteWriteCount.incrementAndGet()
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            bulkWriteCount.incrementAndGet()
        }
    }
}
