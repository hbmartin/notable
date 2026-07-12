package com.ethran.notable.editor.utils

import com.onyx.android.sdk.api.device.epd.UpdateMode
import org.junit.Assert.assertEquals
import org.junit.Test

class OnyxDisplayPolicyTest {
    private val regalDevice = OnyxDeviceCapabilities(supportsRegal = true)

    @Test
    fun selectFullRefreshMode_fallsBackToGcWithoutRegal() {
        assertEquals(
            UpdateMode.GC,
            selectFullRefreshMode(
                capabilities = OnyxDeviceCapabilities(supportsRegal = false),
                partialRefreshCount = 100,
                fullRefreshCount = 6,
                adaptive = true,
            )
        )
    }

    @Test
    fun selectFullRefreshMode_usesRegalPlusWhenAdaptiveModeIsDisabled() {
        assertEquals(
            UpdateMode.REGAL_PLUS,
            selectFullRefreshMode(regalDevice, 0, 1, adaptive = false)
        )
    }

    @Test
    fun selectFullRefreshMode_schedulesPeriodicAndGhostingCleaningRefreshes() {
        assertEquals(UpdateMode.REGAL, selectFullRefreshMode(regalDevice, 0, 1, true))
        assertEquals(UpdateMode.REGAL_PLUS, selectFullRefreshMode(regalDevice, 0, 6, true))
        assertEquals(UpdateMode.REGAL_PLUS, selectFullRefreshMode(regalDevice, 40, 1, true))
    }
}
