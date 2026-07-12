package com.ethran.notable.io

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileOutputStream

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
}
