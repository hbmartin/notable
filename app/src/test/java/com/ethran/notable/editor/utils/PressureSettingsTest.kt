package com.ethran.notable.editor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PressureSettingsTest {
    @Test
    fun remapPressure_preservesMaximumAndAppliesMinimum() {
        assertEquals(4096f, remapPressure(4096f, 4096f, 1f, 0.25f), 0.01f)
        assertEquals(1024f, remapPressure(0f, 4096f, 1f, 0.25f), 0.01f)
    }

    @Test
    fun remapPressure_higherSensitivityRespondsMoreToLightPressure() {
        val linear = remapPressure(1024f, 4096f, 1f, 0f)
        val sensitive = remapPressure(1024f, 4096f, 2f, 0f)

        assertTrue(sensitive > linear)
    }

    @Test
    fun remapPressure_handlesUnknownMaximumWithoutChangingInput() {
        assertEquals(321f, remapPressure(321f, 0f, 2f, 0.2f), 0f)
    }
}
