package com.ethran.notable.data.datastore

import com.ethran.notable.editor.utils.PenSetting
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsCompatibilityTest {
    @Test
    fun oldAppSettingsJson_receivesSafeOnyxDefaults() {
        val settings = Json.decodeFromString<AppSettings>("{\"version\":1}")

        assertEquals(AppSettings.DisplayProfile.System, settings.displayProfile)
        assertTrue(settings.adaptiveEinkRefresh)
        assertFalse(settings.autoSyncEinkBuffer)
        assertFalse(settings.activePenHaptics)
        assertEquals(
            AppSettings.LibraryFolderDisplayMode.Grouped,
            settings.libraryFolderDisplayMode,
        )
    }

    @Test
    fun oldPenSettingJson_receivesLinearPressureDefaults() {
        val setting = Json.decodeFromString<PenSetting>(
            "{\"strokeSize\":5.0,\"color\":-16777216}"
        )

        assertEquals(1f, setting.pressureSensitivity, 0f)
        assertEquals(0f, setting.minimumPressureRatio, 0f)
    }
}
