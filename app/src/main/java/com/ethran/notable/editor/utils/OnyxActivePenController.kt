package com.ethran.notable.editor.utils

import com.ethran.notable.data.datastore.AppSettings
import com.onyx.android.sdk.device.Device
import io.shipbook.shipbooksdk.ShipBook

object OnyxActivePenController {
    private val log = ShipBook.getLogger("OnyxActivePen")

    fun applyPreferences(settings: AppSettings) {
        if (!OnyxCapabilities.current.supportsActivePen) return
        runCatching {
            val device = Device.currentDevice()
            device.penHapticEnabled(settings.activePenHaptics)
            if (settings.activePenHaptics) {
                device.setActivePenHapticStrength(settings.activePenHapticStrength)
                device.setActivePenHapticType(settings.activePenHapticType)
            }
        }.onFailure { log.w("Unable to apply active-pen preferences: ${it.message}") }
    }

    fun batteryLevel(): Int? {
        if (!OnyxCapabilities.current.supportsActivePen) return null
        return runCatching { Device.currentDevice().getActivePenBatteryLevel() }
            .onFailure { log.w("Unable to read active-pen battery: ${it.message}") }
            .getOrNull()
            ?.takeIf { it in 0..100 }
    }
}
