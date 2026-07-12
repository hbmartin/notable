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
    if (!adaptive) return UpdateMode.GC

    val cleaningRefreshDue = partialRefreshCount >= 40 || fullRefreshCount % 6 == 0
    return if (cleaningRefreshDue) UpdateMode.GC else UpdateMode.REGAL
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
            UpdateMode.GC
        } else {
            selectFullRefreshMode(
                capabilities = OnyxCapabilities.current,
                partialRefreshCount = partialRefreshes.get(),
                fullRefreshCount = fullCount,
                adaptive = adaptive,
            )
        }

        val fallbacks = when (requested) {
            UpdateMode.REGAL -> listOf(UpdateMode.REGAL, UpdateMode.GC)
            else -> listOf(requested, UpdateMode.GC)
        }.distinct()

        // repaintEveryThing returns void, so unsupported modes that are silently ignored cannot
        // be distinguished from success. Cleaning refreshes therefore request universally
        // supported GC directly; only REGAL uses an exception-triggered fallback.
        for (mode in fallbacks) {
            if (runCatching { EpdController.repaintEveryThing(mode) }.isSuccess) {
                // Plain REGAL preserves ghosting, so only a cleaning waveform pays down
                // the debt the partial-refresh counter is tracking.
                if (mode != UpdateMode.REGAL) partialRefreshes.set(0)
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

        // Take ownership before touching the device so a partially applied profile is
        // still torn down by clearOwnedProfile when a later SDK call throws.
        appliedProfile = profile
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
            fullRefresh(adaptive = false, forceClean = true)
        }.onFailure {
            log.w("Unable to apply display profile $profile: ${it.message}")
            clearOwnedProfile()
        }
    }

    /**
     * Remove only the modes Notable may have enabled. Color adjust and night mode are
     * device-global, so this runs whenever the editor leaves the foreground or composition.
     */
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
