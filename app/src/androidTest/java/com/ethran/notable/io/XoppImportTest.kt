package com.ethran.notable.io

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.data.events.DefaultAppEventBus
import com.ethran.notable.data.db.*
import com.ethran.notable.testing.TestDatabaseFactory
import com.ethran.notable.utils.AppResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class XoppImportTest {

    private lateinit var db: AppDatabase
    private lateinit var importEngine: ImportEngine

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // XoppFile scales by A4_WIDTH / SCREEN_WIDTH; these globals are only set
        // by MainActivity, which never runs here. Give them a realistic e-ink
        // resolution so import/export use a finite scale factor.
        SCREEN_WIDTH = 1404
        SCREEN_HEIGHT = 1872

        db = TestDatabaseFactory.createInMemory(context)

        // Manual DI for a focused integration test
        val pageRepo = PageRepository(db.pageDao())
        val bookRepo = BookRepository(db.notebookDao(), db.pageDao())
        val strokeRepo = StrokeRepository(db.strokeDao())
        val imageRepo = ImageRepository(db.ImageDao())
        val appEventBus = DefaultAppEventBus()

        val xoppFile = XoppFile(context, pageRepo, bookRepo, appEventBus)

        importEngine = ImportEngine(
            context, pageRepo, bookRepo, strokeRepo, imageRepo, appEventBus
        ).apply {
            this.xoppFile = xoppFile
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test(timeout = 60000)
    fun importNotableXopp_fromAssets_createsNotebookAndStrokes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        
        // 1. Extract asset to a temporary file to get a Uri
        val testFile = File(context.cacheDir, "test_notebook.xopp")
        testContext.assets.open("test_notebook.xopp").use { input ->
            testFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        val uri = Uri.fromFile(testFile)

        // 2. Perform import (passing .xopp title helps recognition if mime-type is unknown)
        val result = importEngine.import(uri, ImportOptions(bookTitle = "test_notebook.xopp"))

        // 3. Assertions
        assertTrue("Import failed: $result", result is AppResult.Success)
        val importedPageIds = (result as AppResult.Success).data
        assertTrue("No pages imported", importedPageIds.isNotEmpty())

        val notebooks = db.notebookDao().getAll()
        assertEquals("Should create one notebook", 1, notebooks.size)
        
        val book = notebooks.first()
        // ImportEngine should have stripped .xopp if it was present in the name or detected by type
        assertEquals("test notebook", book.title)
        assertEquals("Page count mismatch", importedPageIds.size, book.pageIds.size)
        
        // Verify strokes were imported for at least one page
        var strokesFound = false
        for (pageId in importedPageIds) {
            val pageWithData = db.pageDao().getPageWithDataById(pageId)
            if (pageWithData?.strokes?.isNotEmpty() == true) {
                strokesFound = true
                break
            }
        }
        assertTrue("No strokes found in any of the imported pages", strokesFound)
        
        // Detailed check on the first notebook entry
        assertNotNull("Book ID should not be null", book.id)
        assertEquals("Notebook ID mismatch in page", book.id, db.pageDao().getById(importedPageIds.first())?.notebookId)
    }
    
    @Test(timeout = 60000)
    fun importNotableXopp_withExplicitTitle_stripsExtension() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        
        val testFile = File(context.cacheDir, "Notable_Title.xopp")
        testContext.assets.open("test_notebook.xopp").use { input ->
            testFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        val uri = Uri.fromFile(testFile)

        // Pass a title WITH extension explicitly
        val result = importEngine.import(uri, ImportOptions(bookTitle = "ManualTitle.xopp"))
        assertTrue("Import failed: $result", result is AppResult.Success)
        
        val book = db.notebookDao().getAll().first { it.title == "ManualTitle" }
        assertNotNull("Should find notebook with stripped title", book)
    }
    
    @Test(timeout = 60000)
    fun importNotableXopp_verifiesDataIntegrity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        
        val testFile = File(context.cacheDir, "Notable_Integrity.xopp")
        testContext.assets.open("test_notebook.xopp").use { input ->
            testFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        val uri = Uri.fromFile(testFile)

        val result = importEngine.import(uri, ImportOptions(bookTitle = "IntegrityCheck.xopp"))
        assertTrue("Import failed: $result", result is AppResult.Success)
        val importedPageIds = (result as AppResult.Success).data
        
        val book = db.notebookDao().getAll().first { it.title == "IntegrityCheck" }
        
        // Check that page order in notebook matches imported order
        assertEquals("Page sequence mismatch", importedPageIds, book.pageIds)
        
        // Check that each page actually belongs to the notebook
        importedPageIds.forEach { pageId ->
            val page = db.pageDao().getById(pageId)
            assertEquals("Page $pageId does not point to correct notebook", book.id, page?.notebookId)
        }
    }

    @Test(timeout = 60000)
    fun importCorruptXopp_reportsError() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val testFile = File(context.cacheDir, "corrupt.xopp")
        testFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x42, 0x42, 0x42))
        val uri = Uri.fromFile(testFile)

        val result = importEngine.import(uri, ImportOptions(bookTitle = "Corrupt.xopp"))

        assertTrue(
            "Corrupt file must fail the import, got: $result",
            result is AppResult.Error
        )
    }

    // Two full imports plus an export and complete stroke read-backs of both
    // notebooks; measured ~2 min on the CI emulator, so give it generous room.
    @Test(timeout = 300000)
    fun exportImportRoundTrip_preservesPagesStrokesAndBackground() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testContext = InstrumentationRegistry.getInstrumentation().context

        // 1. Import the fixture to get a real notebook into the database.
        val sourceFile = File(context.cacheDir, "roundtrip_source.xopp")
        testContext.assets.open("test_notebook.xopp").use { input ->
            sourceFile.outputStream().use { input.copyTo(it) }
        }
        val firstResult = importEngine.import(
            Uri.fromFile(sourceFile), ImportOptions(bookTitle = "RoundTripSource.xopp")
        )
        assertTrue("Fixture import failed: $firstResult", firstResult is AppResult.Success)
        val firstPageIds = (firstResult as AppResult.Success).data
        val sourceBook = db.notebookDao().getAll().first { it.title == "RoundTripSource" }

        // Give the first page a native background style so the round trip exercises it.
        val firstPage = db.pageDao().getById(firstPageIds.first())!!
        db.pageDao().update(firstPage.copy(background = "lined", backgroundType = "native"))

        // The exporter skips degenerate strokes (fewer than 3 points), so the
        // round trip should preserve exactly the exportable strokes.
        val exportableStrokeCounts = firstPageIds.map { pageId ->
            db.pageDao().getPageWithDataById(pageId)?.strokes?.count { it.points.size >= 3 } ?: 0
        }

        // 2. Export the notebook to a .xopp file.
        val exportedFile = File(context.cacheDir, "roundtrip_exported.xopp")
        val xoppFile = XoppFile(
            context,
            PageRepository(db.pageDao()),
            BookRepository(db.notebookDao(), db.pageDao()),
            DefaultAppEventBus()
        )
        exportedFile.outputStream().use { out ->
            xoppFile.writeToXoppStream(ExportTarget.Book(sourceBook.id), out)
        }
        assertTrue("Export produced an empty file", exportedFile.length() > 0)

        // 3. Import the exported file and compare.
        val secondResult = importEngine.import(
            Uri.fromFile(exportedFile), ImportOptions(bookTitle = "RoundTripCopy.xopp")
        )
        assertTrue("Re-import failed: $secondResult", secondResult is AppResult.Success)
        val secondPageIds = (secondResult as AppResult.Success).data

        assertEquals("Page count changed in round trip", firstPageIds.size, secondPageIds.size)

        val roundTrippedCounts = secondPageIds.map { pageId ->
            db.pageDao().getPageWithDataById(pageId)?.strokes?.size ?: 0
        }
        assertEquals(
            "Exportable stroke counts changed in round trip",
            exportableStrokeCounts,
            roundTrippedCounts
        )

        val roundTrippedFirstPage = db.pageDao().getById(secondPageIds.first())!!
        assertEquals("Background style lost in round trip", "lined", roundTrippedFirstPage.background)
        assertEquals("native", roundTrippedFirstPage.backgroundType)
    }
}
