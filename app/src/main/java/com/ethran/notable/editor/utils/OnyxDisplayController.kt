package com.ethran.notable.editor.utils

import com.ethran.notable.data.datastore.AppSettings
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.device.Device
import io.shipbook.shipbooksdk.ShipBook
import java.util.concurrent.atomic.AtomicInteger

/** Pure policy used by the refresh controller and unit tests. */
fun selectFullRefreshMode(
    capabilities: OnyxDeviceCapabilities,
    partialRefreshCount: Int,
    fullRefreshCount: Int,
    adaptive: Boolean,
): UpdateMode {
    if (!capabilities.supportsRegal) return UpdateMode.GC
    if (!adaptive) return UpdateMode.REGAL_PLUS

    val cleaningRefreshDue = partialRefreshCount >= 40 || fullRefreshCount % 6 == 0
    return if (cleaningRefreshDue) UpdateMode.REGAL_PLUS else UpdateMode.REGAL
}

/** Coordinates EPD refreshes and temporary color adjustments owned by Notable. */
object OnyxDisplayController {
    private val log = ShipBook.getLogger("OnyxDisplayController")
    private val partialRefreshes = AtomicInteger(0)
    private val fullRefreshes = AtomicInteger(0)

    @Volatile
    private var appliedProfile: AppSettings.DisplayProfile = AppSettings.DisplayProfile.System

    fun recordPartialRefresh() {
        partialRefreshes.incrementAndGet()
    }

    fun fullRefresh(adaptive: Boolean = true, forceClean: Boolean = false) {
        if (!OnyxCapabilities.current.isOnyxDevice) return

        val fullCount = fullRefreshes.incrementAndGet()
        val requested = if (forceClean) {
            if (OnyxCapabilities.current.supportsRegal) UpdateMode.REGAL_PLUS else UpdateMode.GC
        } else {
            selectFullRefreshMode(
                capabilities = OnyxCapabilities.current,
                partialRefreshCount = partialRefreshes.get(),
                fullRefreshCount = fullCount,
                adaptive = adaptive,
            )
        }

        val fallbacks = when (requested) {
            UpdateMode.REGAL_PLUS -> listOf(UpdateMode.REGAL_PLUS, UpdateMode.REGAL, UpdateMode.GC)
            UpdateMode.REGAL -> listOf(UpdateMode.REGAL, UpdateMode.GC)
            else -> listOf(requested, UpdateMode.GC)
        }.distinct()

        for (mode in fallbacks) {
            if (runCatching { EpdController.repaintEveryThing(mode) }.isSuccess) {
                partialRefreshes.set(0)
                log.d("Full refresh completed with $mode")
                return
            }
            log.w("Full refresh mode $mode failed; trying fallback")
        }
    }

    /** Apply an explicit, editor-scoped display profile. System leaves device settings untouched. */
    @Synchronized
    fun applyProfile(profile: AppSettings.DisplayProfile) {
        if (!OnyxCapabilities.current.isOnyxDevice || appliedProfile == profile) return
        clearOwnedProfile()
        if (profile == AppSettings.DisplayProfile.System) return

        runCatching {
            when (profile) {
                AppSettings.DisplayProfile.System -> Unit
                AppSettings.DisplayProfile.Document -> {
                    EpdController.enableColorAdjust()
                    Device.currentDevice().applySaturationFactor(1.0f)
                    Device.currentDevice().applyNoiseStrength(0.05f)
                    Device.currentDevice().applyDitherFilterTolerance(0.25f)
                }

                AppSettings.DisplayProfile.ColorInk -> {
                    EpdController.enableColorAdjust()
                    Device.currentDevice().applySaturationFactor(1.2f)
                    Device.currentDevice().applyNoiseStrength(0.10f)
                    Device.currentDevice().applyDitherFilterTolerance(0.15f)
                }

                AppSettings.DisplayProfile.Grayscale -> {
                    EpdController.enableColorAdjust()
                    Device.currentDevice().applySaturationFactor(0f)
                    Device.currentDevice().applyNoiseStrength(0.05f)
                    Device.currentDevice().applyDitherFilterTolerance(0.35f)
                }

                AppSettings.DisplayProfile.Night -> {
                    if (OnyxCapabilities.current.supportsNightMode) EpdController.enableNightMode()
                }
            }
            appliedProfile = profile
            fullRefresh(adaptive = false, forceClean = true)
        }.onFailure {
            log.w("Unable to apply display profile $profile: ${it.message}")
            clearOwnedProfile()
        }
    }

    /** Remove only the modes Notable may have enabled; called when the editor leaves composition. */
    @Synchronized
    fun clearOwnedProfile() {
        if (!OnyxCapabilities.current.isOnyxDevice) return
        val previous = appliedProfile
        runCatching {
            when (previous) {
                AppSettings.DisplayProfile.Night -> EpdController.disableNightMode()
                AppSettings.DisplayProfile.Document,
                AppSettings.DisplayProfile.ColorInk,
                AppSettings.DisplayProfile.Grayscale -> EpdController.disableColorAdjust()
                AppSettings.DisplayProfile.System -> Unit
            }
        }.onFailure { log.w("Unable to clear display profile $previous: ${it.message}") }
        appliedProfile = AppSettings.DisplayProfile.System
    }

    fun configureAutoSyncBuffer(enabled: Boolean) {
        if (!OnyxCapabilities.current.isOnyxDevice) return
        runCatching { EpdController.setAutoSyncBufEnable(enabled) }
            .onFailure { log.w("Auto-sync buffer is unavailable: ${it.message}") }
    }
}
