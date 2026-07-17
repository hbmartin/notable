package com.ethran.notable.ui.viewmodels

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.KvProxy
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.sync.ConnectionTestResult
import com.ethran.notable.sync.ConnectivityChecker
import com.ethran.notable.sync.ConnectivityStatus
import com.ethran.notable.sync.SyncLogger
import com.ethran.notable.sync.SyncOrchestrator
import com.ethran.notable.sync.SyncProgressReporter
import com.ethran.notable.sync.SyncScheduler
import com.ethran.notable.sync.SyncSettings
import com.ethran.notable.sync.SyncState
import com.ethran.notable.sync.WebDAVClient
import com.ethran.notable.sync.RemoteSyncProviderFactoryPort
import com.ethran.notable.sync.SyncProviderType
import com.ethran.notable.sync.DriveAuthorizationManager
import com.ethran.notable.sync.DriveAuthorizationResult
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.isLatestVersion
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class GestureRowModel(
    val titleRes: Int,
    val currentValue: AppSettings.GestureAction,
    val onUpdate: (AppSettings.GestureAction) -> Unit
)

data class SyncSettingsUiState(
    val syncSettings: SyncSettings = SyncSettings(),
    val lastSavedSettings: SyncSettings = SyncSettings(),
    val isPasswordSaved: Boolean = false,
    val passwordVisible: Boolean = false,
    val testingConnection: Boolean = false,
    val connectionStatus: AppResult<ConnectionTestResult, DomainError>? = null,
    val connectivityStatus: ConnectivityStatus = ConnectivityStatus(),
    val syncLogs: List<SyncLogger.LogEntry> = emptyList(),
    val syncState: SyncState = SyncState.Idle,
    val showForceUploadConfirm: Boolean = false,
    val showForceDownloadConfirm: Boolean = false,
    val driveAuthorizationPendingIntent: PendingIntent? = null,
) {
    val credentialsDirty: Boolean
        get() = syncSettings.providerType != lastSavedSettings.providerType ||
                syncSettings.googleDriveFolderId != lastSavedSettings.googleDriveFolderId ||
                syncSettings.googleAccountName != lastSavedSettings.googleAccountName ||
                syncSettings.serverUrl != lastSavedSettings.serverUrl ||
                syncSettings.username != lastSavedSettings.username ||
                syncSettings.password.isNotEmpty()
}


@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val kvProxy: KvProxy,
    private val syncOrchestrator: SyncOrchestrator,
    private val syncProgressReporter: SyncProgressReporter,
    private val syncScheduler: SyncScheduler,
    private val snackDispatcher: SnackDispatcher,
    private val appEventBus: AppEventBus,
    private val providerFactory: RemoteSyncProviderFactoryPort,
    private val driveAuthorizationManager: DriveAuthorizationManager,
    @param:ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    // We use the GlobalAppSettings object directly.
    val settings: AppSettings
        get() = GlobalAppSettings.current

    var isLatestVersion: Boolean by mutableStateOf(true)
        private set

    var syncUiState by mutableStateOf(SyncSettingsUiState())
        private set

    init {
        refreshConnectivityStatus()
        // Observe logs
        viewModelScope.launch {
            SyncLogger.logs.collect { logs ->
                syncUiState = syncUiState.copy(syncLogs = logs)
            }
        }

        // Observe sync engine state
        viewModelScope.launch {
            syncProgressReporter.state.collect { state ->
                syncUiState = syncUiState.copy(syncState = state)
            }
        }

        // Load persisted sync settings from KvProxy.
        viewModelScope.launch(Dispatchers.IO) {
            val persisted = kvProxy.getSyncSettings()
            val hasPassword = persisted.password.isNotEmpty()
            val uiSettings = persisted.copy(password = "")
            withContext(Dispatchers.Main) {
                syncUiState = syncUiState.copy(
                    syncSettings = uiSettings,
                    lastSavedSettings = uiSettings,
                    isPasswordSaved = hasPassword
                )
            }
        }
    }


    /**
     * Checks if the app is the latest version.
     */
    fun checkUpdate(context: Context, force: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            // A forced check here is user-initiated, so failures must be reported.
            val result = isLatestVersion(context, appEventBus, force, notifyOnFailure = force)
            withContext(Dispatchers.Main) {
                if (result != null) {
                    isLatestVersion = result
                }
            }
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        GlobalAppSettings.update(newSettings)
        viewModelScope.launch(Dispatchers.IO) {
            kvProxy.setAppSettings(newSettings)
        }
    }

    // ----------------- //
    // Sync Settings
    // ----------------- //

    /**
     * Universal update function for SyncSettings.
     * @param newSettings The updated settings object.
     * @param saveToDb If true, persists to KvProxy immediately. Set to false for text fields
     * (like username/password) if you want to wait for an explicit "Save" click.
     */
    fun updateSyncSettings(newSettings: SyncSettings, saveToDb: Boolean = true) {
        val oldSettings = syncUiState.syncSettings
        syncUiState = syncUiState.copy(syncSettings = newSettings)

        if (saveToDb) {
            viewModelScope.launch(Dispatchers.IO) {
                // Retrieve password
                val password =
                    newSettings.password.ifBlank {
                        kvProxy.getSyncSettings().password
                    }
                val settingWithPassword = newSettings.copy(password = password)

                try {
                    kvProxy.setSyncSettings(settingWithPassword)

                    // Reconcile schedule only if relevant parameters changed
                    val scheduleChanged =
                        oldSettings.syncEnabled != settingWithPassword.syncEnabled ||
                                oldSettings.autoSync != settingWithPassword.autoSync ||
                                oldSettings.syncInterval != settingWithPassword.syncInterval ||
                                oldSettings.wifiOnly != settingWithPassword.wifiOnly

                    if (scheduleChanged) {
                        syncScheduler.reconcilePeriodicSync(settingWithPassword)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        snackDispatcher.showOrUpdateSnack(SnackConf(text = "Failed to save: ${e.message}"))
                    }
                }
            }
        }
    }

    fun onSaveCredentials() {
        val currentSettings = syncUiState.syncSettings
        updateSyncSettings(currentSettings, saveToDb = true)

        syncUiState = syncUiState.copy(
            lastSavedSettings = currentSettings.copy(password = ""),
            isPasswordSaved = currentSettings.password.isNotEmpty() || syncUiState.isPasswordSaved
        )

        appScope.launch {
            snackDispatcher.showOrUpdateSnack(
                SnackConf(
                    text = "Credentials saved",
                    duration = 3000
                )
            )
        }
    }

    fun onTogglePasswordVisibility() {
        syncUiState = syncUiState.copy(passwordVisible = !syncUiState.passwordVisible)
    }

    fun onProviderSwitch(providerType: SyncProviderType) {
        if (providerType == syncUiState.syncSettings.providerType) return
        viewModelScope.launch(Dispatchers.IO) {
            val old = kvProxy.getSyncSettings()
            if (old.syncEnabled) {
                when (val finalPull = syncOrchestrator.syncAllNotebooks()) {
                    is AppResult.Error -> {
                        withContext(Dispatchers.Main) {
                            snackDispatcher.showOrUpdateSnack(
                                SnackConf(text = "Provider switch stopped: ${finalPull.error.userMessage}", duration = 6000)
                            )
                        }
                        return@launch
                    }
                    is AppResult.Success -> Unit
                }
            }
            val switched = old.copy(
                providerType = providerType,
                syncEnabled = false,
                syncedNotebookIds = emptySet(),
                remoteNamespaceVersion = 2,
            )
            kvProxy.setSyncSettings(switched)
            withContext(Dispatchers.Main) {
                val ui = switched.copy(password = "")
                syncUiState = syncUiState.copy(syncSettings = ui, lastSavedSettings = ui)
                snackDispatcher.showOrUpdateSnack(
                    SnackConf(text = "Provider switched. Configure the new destination, then enable sync.", duration = 6000)
                )
            }
        }
    }

    fun onAuthorizeGoogleDrive() {
        viewModelScope.launch {
            when (val result = driveAuthorizationManager.authorizeSilently(syncUiState.syncSettings.googleAccountName)) {
                is DriveAuthorizationResult.Authorized -> snackDispatcher.showOrUpdateSnack(
                    SnackConf(text = "Google Drive authorized", duration = 3000)
                )
                is DriveAuthorizationResult.NeedsUserAction -> {
                    syncUiState = syncUiState.copy(driveAuthorizationPendingIntent = result.pendingIntent)
                }
                is DriveAuthorizationResult.Unavailable -> snackDispatcher.showOrUpdateSnack(
                    SnackConf(text = result.reason, duration = 5000)
                )
            }
        }
    }

    fun onGoogleDriveAuthorizationResult(data: Intent?) {
        syncUiState = syncUiState.copy(driveAuthorizationPendingIntent = null)
        val result = data?.let(driveAuthorizationManager::consumeAuthorizationResult)
            ?: DriveAuthorizationResult.Unavailable("Authorization cancelled")
        snackDispatcher.showOrUpdateSnack(
            SnackConf(
                text = when (result) {
                    is DriveAuthorizationResult.Authorized -> "Google Drive authorized"
                    is DriveAuthorizationResult.Unavailable -> result.reason
                    is DriveAuthorizationResult.NeedsUserAction -> "Google Drive authorization still needs attention"
                },
                duration = 4000,
            )
        )
    }

    fun onTestConnection() {
        refreshConnectivityStatus()
        val settings = syncUiState.syncSettings
        if (settings.providerType == SyncProviderType.WEBDAV &&
            (settings.serverUrl.isBlank() || settings.username.isBlank())) return
        if (settings.providerType == SyncProviderType.GOOGLE_DRIVE && settings.googleDriveFolderId.isNullOrBlank()) return

        syncUiState = syncUiState.copy(testingConnection = true, connectionStatus = null)
        viewModelScope.launch(Dispatchers.IO) {
            val password =
                settings.password.ifBlank {
                    kvProxy.getSyncSettings().password
                }

            val configured = settings.copy(password = password)
            val result = when (val provider = providerFactory.create(configured)) {
                is AppResult.Success -> provider.data.testConnection()
                is AppResult.Error -> AppResult.Error(provider.error)
            }

            withContext(Dispatchers.Main) {
                syncUiState = syncUiState.copy(testingConnection = false, connectionStatus = result)
            }
        }
    }

    fun onForceUploadRequested(show: Boolean) {
        syncUiState = syncUiState.copy(showForceUploadConfirm = show)
    }

    fun onForceDownloadRequested(show: Boolean) {
        syncUiState = syncUiState.copy(showForceDownloadConfirm = show)
    }

    fun onConfirmForceUpload() {
        syncUiState = syncUiState.copy(showForceUploadConfirm = false)
        runSyncWithSnack(
            textDuring = "Force upload started...", successMessage = "Force upload complete"
        ) { syncOrchestrator.forceUploadAll() }
    }

    fun onConfirmForceDownload() {
        syncUiState = syncUiState.copy(showForceDownloadConfirm = false)
        runSyncWithSnack(
            textDuring = "Force download started...", successMessage = "Force download complete"
        ) { syncOrchestrator.forceDownloadAll() }
    }

    private fun runSyncWithSnack(
        textDuring: String,
        successMessage: String,
        action: suspend () -> AppResult<Unit, DomainError>
    ) {
        appScope.launch {
            val snackId = java.util.UUID.randomUUID().toString()
            snackDispatcher.showOrUpdateSnack(
                SnackConf(id = snackId, text = textDuring, duration = null)
            )
            val message = try {
                when (val result = action()) {
                    is AppResult.Success -> successMessage
                    is AppResult.Error -> "Sync failed: ${result.error.userMessage}"
                }
            } catch (e: Exception) {
                "Sync failed: ${e.message ?: "Unknown"}"
            }
            snackDispatcher.showOrUpdateSnack(
                SnackConf(id = snackId, text = message, duration = 3000)
            )
        }
    }


    fun onClearSyncLogs() {
        SyncLogger.clear()
    }

    fun onManualSync() {
        refreshConnectivityStatus()
        runSyncWithSnack(
            textDuring = "Sync initialized...", successMessage = "Sync completed successfully"
        ) {
            val result = syncOrchestrator.syncAllNotebooks()
            if (result is AppResult.Success) {
                // Save unix timestamp (ms since epoch). UI layer will format it for display.
                updateSyncSettings(
                    syncUiState.syncSettings.copy(lastSyncTime = System.currentTimeMillis()),
                    saveToDb = true
                )
            }
            result
        }
    }

    private fun refreshConnectivityStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val status = ConnectivityChecker(context).currentStatus()
            withContext(Dispatchers.Main) {
                syncUiState = syncUiState.copy(connectivityStatus = status)
            }
        }
    }

    // ----------------- //
    // Gesture Settings
    // ----------------- //

    fun getGestureRows(): List<GestureRowModel> = listOf(
        GestureRowModel(
            R.string.gestures_double_tap_action,
            settings.doubleTapAction,
        ) { a -> updateSettings(settings.copy(doubleTapAction = a)) },
        GestureRowModel(
            (R.string.gestures_two_finger_tap_action),
            settings.twoFingerTapAction,
        ) { a -> updateSettings(settings.copy(twoFingerTapAction = a)) },
        GestureRowModel(
            (R.string.gestures_swipe_left_action),
            settings.swipeLeftAction,
        ) { a -> updateSettings(settings.copy(swipeLeftAction = a)) },
        GestureRowModel(
            (R.string.gestures_swipe_right_action),
            settings.swipeRightAction,
        ) { a -> updateSettings(settings.copy(swipeRightAction = a)) },
        GestureRowModel(
            (R.string.gestures_two_finger_swipe_left_action),
            settings.twoFingerSwipeLeftAction,
        ) { a -> updateSettings(settings.copy(twoFingerSwipeLeftAction = a)) },
        GestureRowModel(
            R.string.gestures_two_finger_swipe_right_action,
            settings.twoFingerSwipeRightAction,
        ) { a -> updateSettings(settings.copy(twoFingerSwipeRightAction = a)) },
    )


    val availableGestures = listOf(
        AppSettings.GestureAction.None to "None",
        AppSettings.GestureAction.Undo to R.string.gesture_action_undo,
        AppSettings.GestureAction.Redo to R.string.gesture_action_redo,
        AppSettings.GestureAction.PreviousPage to R.string.gesture_action_previous_page,
        AppSettings.GestureAction.NextPage to R.string.gesture_action_next_page,
        AppSettings.GestureAction.ChangeTool to R.string.gesture_action_toggle_pen_eraser,
        AppSettings.GestureAction.ToggleZen to R.string.gesture_action_toggle_zen_mode,
        AppSettings.GestureAction.Select to R.string.gesture_action_select,
    )


}
