package com.ethran.notable.io

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileUtilsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun isPdfFile_returnsTrueForValidPdfHeader() {
        val file = tempFolder.newFile("valid.pdf")
        file.writeText("%PDF-1.7\nbody")

        assertTrue(isPdfFile(file))
    }

    @Test
    fun isPdfFile_returnsFalseForNonPdfFile() {
        val file = tempFolder.newFile("image.png")
        file.writeText("PNG data")

        assertFalse(isPdfFile(file))
    }

    @Test
    fun isPdfFile_returnsFalseForEmptyFile() {
        val file = tempFolder.newFile("empty.pdf")

        assertFalse(isPdfFile(file))
    }

    @Test
    fun isPdfFile_returnsFalseForDirectory() {
        val directory = tempFolder.newFolder("directory.pdf")

        assertFalse(isPdfFile(directory))
    }

    @Test
    fun isPdfFile_returnsFalseForMissingFile() {
        val missing = File(tempFolder.root, "missing.pdf")

        assertFalse(isPdfFile(missing))
    }
}
