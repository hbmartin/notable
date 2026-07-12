package com.ethran.notable.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.editor.utils.OnyxCapabilities


@Composable
fun GeneralSettings(
    settings: AppSettings, onSettingsChange: (AppSettings) -> Unit
) {
    Column {
        SelectorRow(
            label = stringResource(R.string.default_page_background_template), options = listOf(
                "blank" to stringResource(R.string.blank_page),
                "dotted" to stringResource(R.string.dot_grid),
                "lined" to stringResource(R.string.lines),
                "squared" to stringResource(R.string.small_squares_grid),
                "hexed" to stringResource(R.string.hexagon_grid),
            ), value = settings.defaultNativeTemplate, onValueChange = {
                onSettingsChange(settings.copy(defaultNativeTemplate = it))
            })
        SelectorRow(
            label = stringResource(R.string.toolbar_position), options = listOf(
                AppSettings.Position.Top to stringResource(R.string.toolbar_position_top),
                AppSettings.Position.Bottom to stringResource(
                    R.string.toolbar_position_bottom
                )
            ), value = settings.toolbarPosition, onValueChange = { newPosition ->
                onSettingsChange(settings.copy(toolbarPosition = newPosition))
            })

        SettingToggleRow(
            label = stringResource(R.string.use_onyx_neotools_may_cause_crashes),
            value = settings.neoTools,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(neoTools = isChecked))
            })

        SettingToggleRow(
            label = stringResource(R.string.enable_scribble_to_erase),
            value = settings.scribbleToEraseEnabled,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(scribbleToEraseEnabled = isChecked))
            })

        if (settings.scribbleToEraseEnabled) {
            SettingToggleRow(
                label = stringResource(R.string.scribble_to_erase_bounding_box),
                value = settings.scribbleToEraseBoundingBox,
                onToggle = { isChecked ->
                    onSettingsChange(settings.copy(scribbleToEraseBoundingBox = isChecked))
                })
        }

        SettingToggleRow(
            label = stringResource(R.string.enable_smooth_scrolling),
            value = settings.smoothScroll,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(smoothScroll = isChecked))
            })

        SettingToggleRow(
            label = stringResource(R.string.continuous_zoom),
            value = settings.continuousZoom,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(continuousZoom = isChecked))
            })
        SettingToggleRow(
            label = stringResource(R.string.continuous_stroke_slider),
            value = settings.continuousStrokeSlider,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(continuousStrokeSlider = isChecked))
            })
        SettingToggleRow(
            label = stringResource(R.string.monochrome_mode) + " " + stringResource(R.string.work_in_progress),
            value = settings.monochromeMode,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(monochromeMode = isChecked))
            })

        OnyxDeviceSettings(settings, onSettingsChange)

        SettingToggleRow(
            label = stringResource(R.string.rename_on_create),
            value = settings.renameOnCreate,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(renameOnCreate = isChecked))
            })

        SettingToggleRow(
            label = stringResource(R.string.paginate_pdf),
            value = settings.paginatePdf,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(paginatePdf = isChecked))
            })

        SettingToggleRow(
            label = stringResource(R.string.preview_pdf_pagination),
            value = settings.visualizePdfPagination,
            onToggle = { isChecked ->
                onSettingsChange(settings.copy(visualizePdfPagination = isChecked))
            })
    }
}

@Composable
private fun OnyxDeviceSettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    val capabilities = OnyxCapabilities.current
    if (capabilities.isOnyxDevice) {
        val displayProfiles = buildList {
            add(AppSettings.DisplayProfile.System to stringResource(R.string.display_profile_system))
            add(AppSettings.DisplayProfile.Document to stringResource(R.string.display_profile_document))
            if (capabilities.isColorDevice) {
                add(AppSettings.DisplayProfile.ColorInk to stringResource(R.string.display_profile_color_ink))
            }
            add(AppSettings.DisplayProfile.Grayscale to stringResource(R.string.display_profile_grayscale))
            if (capabilities.supportsNightMode) {
                add(AppSettings.DisplayProfile.Night to stringResource(R.string.display_profile_night))
            }
        }
        SelectorRow(
            label = stringResource(R.string.display_profile),
            options = displayProfiles,
            value = settings.displayProfile,
            onValueChange = { onSettingsChange(settings.copy(displayProfile = it)) },
        )
        SettingToggleRow(
            label = stringResource(R.string.adaptive_eink_refresh),
            value = settings.adaptiveEinkRefresh,
            onToggle = { onSettingsChange(settings.copy(adaptiveEinkRefresh = it)) },
        )
        SettingToggleRow(
            label = stringResource(R.string.auto_sync_eink_buffer),
            value = settings.autoSyncEinkBuffer,
            onToggle = { onSettingsChange(settings.copy(autoSyncEinkBuffer = it)) },
        )
    }

    if (capabilities.supportsActivePen) ActivePenSettings(settings, onSettingsChange)
}

@Composable
private fun ActivePenSettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
) {
    SettingToggleRow(
        label = stringResource(R.string.active_pen_low_battery_warning),
        value = settings.activePenLowBatteryWarning,
        onToggle = { onSettingsChange(settings.copy(activePenLowBatteryWarning = it)) },
    )
    SettingToggleRow(
        label = stringResource(R.string.active_pen_haptics),
        value = settings.activePenHaptics,
        onToggle = { onSettingsChange(settings.copy(activePenHaptics = it)) },
    )
    if (!settings.activePenHaptics) return

    SelectorRow(
        label = stringResource(R.string.active_pen_haptic_strength),
        options = listOf(
            0 to stringResource(R.string.haptic_strength_low),
            1 to stringResource(R.string.haptic_strength_medium),
            2 to stringResource(R.string.haptic_strength_high),
        ),
        value = settings.activePenHapticStrength,
        onValueChange = { onSettingsChange(settings.copy(activePenHapticStrength = it)) },
    )
    SelectorRow(
        label = stringResource(R.string.active_pen_haptic_type),
        options = listOf(0 to "Type 0", 1 to "Type 1", 2 to "Type 2"),
        value = settings.activePenHapticType,
        onValueChange = { onSettingsChange(settings.copy(activePenHapticType = it)) },
    )
}
