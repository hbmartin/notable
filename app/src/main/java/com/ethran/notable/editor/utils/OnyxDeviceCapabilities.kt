package com.ethran.notable.editor.utils

import android.os.Build
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.utils.FirmwareUtils
import com.onyx.android.sdk.device.Device
import io.shipbook.shipbooksdk.ShipBook

/**
 * Stable, exception-safe description of the BOOX features Notable can use.
 *
 * Onyx exposes many APIs on every SDK class even when the running firmware does not implement
 * them. Reading those APIs throughout the app makes compatibility decisions inconsistent, so all
 * feature probing is centralized here.
 */
data class OnyxDeviceCapabilities(
    val isOnyxDevice: Boolean = false,
    val boardPlatform: String? = null,
    val firmwareVersion: String? = null,
    val firmwareBuildId: Int? = null,
    val isColorDevice: Boolean = false,
    val supportsRegal: Boolean = false,
    val supportsNightMode: Boolean = false,
    val supportsActivePen: Boolean = false,
    val supportsWirelessCharging: Boolean = false,
    val supportsChargingControl: Boolean = false,
    val maxTouchPressure: Float = 4096f,
)

object OnyxCapabilities {
    private val log = ShipBook.getLogger("OnyxCapabilities")

    val current: OnyxDeviceCapabilities by lazy { detect() }

    private fun detect(): OnyxDeviceCapabilities {
        if (!DeviceCompat.isOnyxDevice) return OnyxDeviceCapabilities()

        val base = runCatching { Device.currentDevice() }.getOrElse {
            log.w("Unable to obtain Onyx device implementation: ${it.message}")
            return OnyxDeviceCapabilities(isOnyxDevice = true)
        }

        fun <T> safe(name: String, default: T, call: () -> T): T = runCatching(call)
            .onFailure { log.w("$name is unavailable: ${it.message}") }
            .getOrDefault(default)

        val buildId = safe("firmware build id", -1) {
            FirmwareUtils.getBuildIdFromFingerprint(Build.FINGERPRINT)
        }.takeIf { it >= 0 }

        return OnyxDeviceCapabilities(
            isOnyxDevice = true,
            boardPlatform = safe("board platform", "") { Device.getBoardPlatform() }
                .takeIf { it.isNotBlank() },
            firmwareVersion = safe("firmware version", "") {
                FirmwareUtils.getSimpleVersionFromBuildFingerprint(Build.FINGERPRINT)
            }.takeIf { it.isNotBlank() },
            firmwareBuildId = buildId,
            isColorDevice = DeviceCompat.isColorDevice(),
            supportsRegal = safe("REGAL support", false) { EpdController.supportRegal() },
            supportsNightMode = safe("night mode support", false) { base.isSupportNightMode() },
            supportsActivePen = safe("active pen support", false) { base.supportActivePen() },
            supportsWirelessCharging = safe("wireless charging support", false) {
                base.supportWirelessCharging()
            },
            supportsChargingControl = safe("charging control support", false) {
                base.isSupportChargingControl()
            },
            maxTouchPressure = safe("maximum touch pressure", 4096f) {
                EpdController.getMaxTouchPressure()
            }.takeIf { it > 0f } ?: 4096f,
        )
    }
}

/** Keep identifiers useful for local diagnosis without exposing a complete hardware identifier. */
fun redactDeviceIdentifier(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val visible = value.filterNot { it == ':' || it == '-' }.takeLast(4)
    return if (visible.isBlank()) "redacted" else "••••$visible"
}
