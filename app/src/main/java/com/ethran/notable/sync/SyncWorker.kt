package com.ethran.notable.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import dagger.hilt.android.EntryPointAccessors
import io.shipbook.shipbooksdk.Log
import java.net.URI

/**
 * Background worker for WebDAV synchronization.
 * Runs via WorkManager. Emits success/error data via WorkManager Results.
 */
class SyncWorker(
    context: Context, params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "SyncWorker started")

        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, SyncOrchestratorEntryPoint::class.java
        )

        val kvProxy = entryPoint.kvProxy()
        val syncSettings = kvProxy.getSyncSettings()

        // 1. Dynamic Checks. Remote servers require Android's validated-internet signal;
        // local WebDAV servers are still allowed on a Wi-Fi LAN without internet access.
        val connectivityChecker = ConnectivityChecker(applicationContext)
        val requireValidated = !isLikelyLocalServerUrl(syncSettings.serverUrl)
        if (!connectivityChecker.isNetworkAvailable(requireValidated = requireValidated)) {
            Log.i(TAG, "No usable network available, will retry later")
            return Result.retry()
        }

        if (!syncSettings.syncEnabled) {
            Log.i(TAG, "Sync disabled in settings, skipping")
            return Result.success(workDataOf(OUTPUT_KEY_SKIPPED to true))
        }

        if (syncSettings.wifiOnly && !connectivityChecker.isUnmeteredConnected()) {
            Log.i(TAG, "WiFi-only sync enabled but not on unmetered network, skipping")
            return Result.success(workDataOf(OUTPUT_KEY_SKIPPED to true))
        }

        if (syncSettings.username.isBlank() || syncSettings.password.isBlank()) {
            Log.w(TAG, "No credentials stored, skipping sync")
            return Result.success(workDataOf(OUTPUT_KEY_SKIPPED to true))
        }

        // 2. Parse Input
        val syncRequest = SyncRequest.fromData(inputData)
            ?: return Result.failure(workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to "INVALID_INPUT"))

        val syncTrigger = inputData.getString(KEY_SYNC_TRIGGER) ?: SYNC_TRIGGER_PERIODIC
        val isPeriodicSync = syncTrigger == SYNC_TRIGGER_PERIODIC

        // 3. Execute Sync
        return try {
            val result = when (syncRequest) {
                SyncRequest.SyncAll -> entryPoint.syncOrchestrator().syncAllNotebooks()
                SyncRequest.ForceUpload -> entryPoint.syncOrchestrator().forceUploadAll()
                SyncRequest.ForceDownload -> entryPoint.syncOrchestrator().forceDownloadAll()
                is SyncRequest.UploadDeletion -> entryPoint.syncOrchestrator().uploadDeletion(syncRequest.notebookId)
                is SyncRequest.SyncNotebook -> entryPoint.syncOrchestrator().syncNotebook(syncRequest.notebookId)
                is SyncRequest.SyncFromPageId -> {
                    entryPoint.syncOrchestrator().syncFromPageId(syncRequest.pageId)
                    AppResult.Success(Unit)
                }
            }

            // 4. Handle Results
            when (result) {
                is AppResult.Success -> {
                    Log.i(TAG, "Sync $syncRequest completed successfully")
                    Result.success(
                        workDataOf(
                            OUTPUT_KEY_SUCCESS to true,
                            OUTPUT_KEY_IS_PERIODIC to isPeriodicSync
                        )
                    )
                }

                is AppResult.Error -> {
                    val error = result.error
                    val errorStr = error.javaClass.simpleName
                    val failureMessage = error.userMessage

                    when (error) {
                        is DomainError.SyncInProgress -> {
                            Log.i(TAG, "Sync already in progress, skipping this run")
                            // Returning success so it doesn't log as a strict failure, but marking success as false
                            Result.success(workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to errorStr))
                        }

                        is DomainError.NetworkError -> {
                            Log.e(TAG, "Network error during sync: $failureMessage")
                            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                                Result.retry()
                            } else {
                                Result.failure(workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to failureMessage))
                            }
                        }

                        is DomainError.SyncAuthError,
                        is DomainError.SyncConfigError,
                        is DomainError.SyncClockSkew,
                        is DomainError.SyncWifiRequired,
                        is DomainError.SyncConflict -> {
                            Log.w(TAG, "Sync failed (non-retryable): $failureMessage")
                            // These are hard failures, mark them as such
                            Result.failure(workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to failureMessage))
                        }

                        else -> {
                            Log.e(TAG, "Sync failed: $failureMessage")
                            if (runAttemptCount < MAX_RETRY_ATTEMPTS && error.recoverable) {
                                Result.retry()
                            } else {
                                Result.failure(workDataOf(OUTPUT_KEY_SUCCESS to false, OUTPUT_KEY_ERROR to failureMessage))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in SyncWorker: ${e.message}")
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(
                        OUTPUT_KEY_SUCCESS to false,
                        OUTPUT_KEY_ERROR to (e.localizedMessage ?: "UNKNOWN_EXCEPTION")
                    )
                )
            }
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val MAX_RETRY_ATTEMPTS = 3

        const val KEY_SYNC_TRIGGER = "sync_trigger"
        const val SYNC_TRIGGER_PERIODIC = "periodic"
        const val SYNC_TRIGGER_IMMEDIATE = "immediate"

        // Output keys for the UI to observe
        const val OUTPUT_KEY_SUCCESS = "success"
        const val OUTPUT_KEY_ERROR = "error"
        const val OUTPUT_KEY_IS_PERIODIC = "is_periodic"
        const val OUTPUT_KEY_SKIPPED = "skipped"

        const val SYNC_WORK_TAG = "sync-work"

        /**
         * Unique work name for periodic sync.
         */
        const val WORK_NAME = "notable-periodic-sync"
    }
}

internal fun isLikelyLocalServerUrl(rawUrl: String): Boolean {
    // URI only populates host when a scheme is present; tolerate entries
    // like "192.168.1.5:8080/dav".
    val candidate = if ("://" in rawUrl) rawUrl else "http://$rawUrl"
    val host = runCatching { URI(candidate).host?.lowercase() }.getOrNull() ?: return false
    if (host == "localhost" || host.endsWith(".local")) return true

    // URI keeps the brackets around literal IPv6 hosts.
    val bareHost = host.removeSurrounding("[", "]")
    if (bareHost.contains(':')) return isLocalIpv6Address(bareHost)

    val octets = host.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] == 127 ||
            octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 169 && octets[1] == 254) ||
            // Carrier-grade NAT range, also used by tailnet-style VPNs such as Tailscale.
            (octets[0] == 100 && octets[1] in 64..127)
}

/** Loopback, unique-local (fc00::/7), and link-local (fe80::/10) IPv6 addresses. */
private fun isLocalIpv6Address(address: String): Boolean {
    if (address == "::1") return true
    val firstGroup = address.substringBefore(':')
    return firstGroup.startsWith("fc") || firstGroup.startsWith("fd") ||
            (firstGroup.length == 4 && firstGroup.startsWith("fe") && firstGroup[2] in "89ab")
}
