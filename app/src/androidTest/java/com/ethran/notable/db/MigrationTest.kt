package com.ethran.notable.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ethran.notable.data.db.AppDatabase
import com.ethran.notable.data.db.MIGRATION_22_23
import com.ethran.notable.data.db.MIGRATION_32_33
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @Test(timeout = 60000)
    fun migrate34To35_preservesContentAndCreatesCanvasTables() {
        val dbName = "migration-test-34-to-35"
        helper.createDatabase(dbName, 34).apply {
            execSQL(
                "INSERT INTO Page (id, scroll, notebookId, background, backgroundType, parentFolderId, createdAt, updatedAt) " +
                    "VALUES ('page35', 0, NULL, 'blank', 'native', NULL, 1, 2)"
            )
            execSQL(
                "INSERT INTO Image (id, x, y, height, width, uri, pageId, createdAt, updatedAt) " +
                    "VALUES ('image35', 1, 2, 30, 40, NULL, 'page35', 1, 2)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 35, true)
        db.query("SELECT rotation, flipHorizontal, flipVertical FROM Image WHERE id='image35'").use {
            assertTrue(it.moveToFirst())
            assertEquals(0f, it.getFloat(0))
            assertEquals(0, it.getInt(1))
            assertEquals(0, it.getInt(2))
        }
        listOf("CanvasText", "CanvasLink", "Attachment", "AttachmentBinding").forEach { table ->
            db.query("SELECT COUNT(*) FROM $table").use { assertTrue("missing $table", it.moveToFirst()) }
        }
        db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    @Test(timeout = 10000)
    fun simpleTest() {
        assertTrue(true)
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        listOf(), // Add AutoMigrationSpecs here if any
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate30To31_autoMigration() {
        val dbName = "migration-test"

        // 1. Create DB with version 30 schema
        val db = helper.createDatabase(dbName, 30)

// Insert required parent data first
        db.execSQL(
            """
    INSERT INTO Notebook (
        id,
        title,
        openPageId,
        pageIds,
        parentFolderId,
        defaultNativeTemplate,
        createdAt,
        updatedAt
    ) VALUES (
        'notebook1',
        'Test Notebook',
        NULL,
        '[]',
        NULL,
        'blank',
        1620000000000,
        1620000000000
    )
    """.trimIndent()
        )


        db.execSQL(
            """
    INSERT INTO Folder (id, title, createdAt, updatedAt)
    VALUES ('TEST_FOLDER_ID', 'Test Folder', 1620000000, 1620000000)
    """.trimIndent()
        )

        // Insert with column name from version 30: 'nativeTemplate'
        db.execSQL(
            """
    INSERT INTO Page (
        id,
        notebookId,
        nativeTemplate,
        parentFolderId,
        scroll,
        createdAt,
        updatedAt
    ) VALUES (
        'page1',
        'notebook1',
        'grid',
        'TEST_FOLDER_ID',
        0.0,
        1620000000,
        1620000000
    )
    """.trimIndent()
        )


        db.close()

        // 2. Reopen DB with version 31 (latest AppDatabase version) to trigger migration
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        val migratedDb = roomDb.openHelper.writableDatabase

        // 3. Verify renamed column exists with expected data
        val cursor = migratedDb.query("SELECT background FROM Page WHERE id = 'page1'")
        cursor.use {
            assertTrue(it.moveToFirst())
            val background = it.getString(it.getColumnIndexOrThrow("background"))
            assertEquals("grid", background)
        }

        roomDb.close()
    }

    /**
     * Runs the entire migration chain from v19 (the oldest version with a
     * complete path: 18->19 has no migration) to the current version, through
     * every auto-migration, both column renames, the orphaned-page cleanup
     * (22->23) and the stroke-table swap (32->33). Verifies user data survives.
     */
    @Test(timeout = 120000)
    @Throws(IOException::class)
    fun migrate19ToLatest_fullChain_preservesData() {
        val dbName = "migration-test-19-to-latest"

        helper.createDatabase(dbName, 19).apply {
            execSQL(
                """
                INSERT INTO Notebook (id, title, openPageId, pageIds, createdAt, updatedAt)
                VALUES ('notebook1', 'Chain Notebook', NULL, '["page1"]', 1620000000000, 1620000000000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO Page (id, scroll, notebookId, nativeTemplate, createdAt, updatedAt)
                VALUES ('page1', 0, 'notebook1', 'grid', 1620000000000, 1620000000000)
                """.trimIndent()
            )
            // Quick page (no notebook) must survive the 22->23 orphan cleanup.
            execSQL(
                """
                INSERT INTO Page (id, scroll, notebookId, nativeTemplate, createdAt, updatedAt)
                VALUES ('quickpage1', 0, NULL, 'blank', 1620000000000, 1620000000000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO Stroke (id, size, pen, top, bottom, left, right, points, pageId, createdAt, updatedAt)
                VALUES ('stroke1', 10.0, 'BALLPEN', 0.0, 10.0, 0.0, 10.0, '[]', 'page1', 1620000000000, 1620000000000)
                """.trimIndent()
            )
            close()
        }

        // Opening at the current version replays the whole chain; the manual
        // migrations must be registered exactly as DatabaseModule does.
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_22_23, MIGRATION_32_33)
            .build()
        val db = roomDb.openHelper.writableDatabase

        db.query("SELECT background, notebookId FROM Page WHERE id = 'page1'").use {
            assertTrue("page1 lost during migration", it.moveToFirst())
            assertEquals("grid", it.getString(it.getColumnIndexOrThrow("background")))
            assertEquals("notebook1", it.getString(it.getColumnIndexOrThrow("notebookId")))
        }
        db.query("SELECT id FROM Page WHERE id = 'quickpage1'").use {
            assertTrue("quick page lost during migration", it.moveToFirst())
        }
        db.query("SELECT title, defaultBackground FROM Notebook WHERE id = 'notebook1'").use {
            assertTrue("notebook lost during migration", it.moveToFirst())
            assertEquals("Chain Notebook", it.getString(it.getColumnIndexOrThrow("title")))
            assertEquals("blank", it.getString(it.getColumnIndexOrThrow("defaultBackground")))
        }
        // 32->33 moves legacy strokes to stroke_old for lazy re-encoding;
        // the row must not be dropped.
        db.query("SELECT COUNT(*) AS c FROM stroke_old").use {
            assertTrue(it.moveToFirst())
            assertEquals("legacy stroke lost during migration", 1, it.getInt(0))
        }
        db.query("PRAGMA foreign_key_check").use {
            assertFalse("foreign key violations after migration", it.moveToFirst())
        }

        roomDb.close()
    }

    /** 22->23 deletes pages whose notebook no longer exists, keeping quick pages. */
    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate22To23_dropsOrphanedPagesOnly() {
        val dbName = "migration-test-22-to-23"

        helper.createDatabase(dbName, 22).apply {
            execSQL(
                """
                INSERT INTO Notebook (id, title, openPageId, pageIds, parentFolderId, createdAt, updatedAt)
                VALUES ('notebook1', 'Test', NULL, '["pageValid"]', NULL, 1620000000000, 1620000000000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO Page (id, scroll, notebookId, nativeTemplate, parentFolderId, createdAt, updatedAt)
                VALUES ('pageValid', 0, 'notebook1', 'blank', NULL, 1620000000000, 1620000000000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO Page (id, scroll, notebookId, nativeTemplate, parentFolderId, createdAt, updatedAt)
                VALUES ('pageOrphan', 0, 'ghost-notebook', 'blank', NULL, 1620000000000, 1620000000000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO Page (id, scroll, notebookId, nativeTemplate, parentFolderId, createdAt, updatedAt)
                VALUES ('pageQuick', 0, NULL, 'blank', NULL, 1620000000000, 1620000000000)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 23, false, MIGRATION_22_23)

        db.query("SELECT id FROM Page ORDER BY id").use {
            val ids = mutableListOf<String>()
            while (it.moveToNext()) ids.add(it.getString(0))
            assertEquals(listOf("pageQuick", "pageValid"), ids)
        }
    }

    /**
     * 32->33 renames the legacy TEXT-points table to stroke_old and creates a
     * fresh BLOB-points Stroke table with its pageId index.
     */
    @Test(timeout = 60000)
    @Throws(IOException::class)
    fun migrate32To33_swapsStrokeTablePreservingLegacyRows() {
        val dbName = "migration-test-32-to-33"

        helper.createDatabase(dbName, 32).apply {
            execSQL(
                """
                INSERT INTO Notebook (id, title, openPageId, pageIds, parentFolderId, defaultBackground, defaultBackgroundType, createdAt, updatedAt)
                VALUES ('notebook1', 'Test', NULL, '["page1"]', NULL, 'blank', 'native', 1620000000000, 1620000000000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO Page (id, scroll, notebookId, background, backgroundType, parentFolderId, createdAt, updatedAt)
                VALUES ('page1', 0, 'notebook1', 'blank', 'native', NULL, 1620000000000, 1620000000000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO Stroke (id, size, pen, color, top, bottom, left, right, points, pageId, createdAt, updatedAt)
                VALUES ('stroke1', 10.0, 'BALLPEN', -16777216, 0.0, 10.0, 0.0, 10.0, '[]', 'page1', 1620000000000, 1620000000000)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 33, false, MIGRATION_32_33)

        db.query("SELECT COUNT(*) FROM stroke_old").use {
            assertTrue(it.moveToFirst())
            assertEquals("legacy stroke missing from stroke_old", 1, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM Stroke").use {
            assertTrue(it.moveToFirst())
            assertEquals("new Stroke table should start empty", 0, it.getInt(0))
        }
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_Stroke_pageId' AND tbl_name = 'Stroke'"
        ).use {
            assertTrue("index_Stroke_pageId missing on new Stroke table", it.moveToFirst())
        }
    }
}
