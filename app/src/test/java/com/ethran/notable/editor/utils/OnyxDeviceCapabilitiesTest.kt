package com.ethran.notable.editor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnyxDeviceCapabilitiesTest {
    @Test
    fun redactDeviceIdentifier_hidesFullHardwareIdentifier() {
        assertEquals("••••4455", redactDeviceIdentifier("00:11:22:33:44:55"))
        assertEquals("••••cdef", redactDeviceIdentifier("abcdef"))
    }

    @Test
    fun redactDeviceIdentifier_handlesMissingValues() {
        assertNull(redactDeviceIdentifier(null))
        assertNull(redactDeviceIdentifier(""))
    }
}
