package com.ethran.notable.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ethran.notable.editor.utils.DeviceCompat
import com.onyx.android.sdk.api.utils.NetworkUtil

data class ConnectivityStatus(
    val connected: Boolean = false,
    val validated: Boolean = false,
    val unmetered: Boolean = false,
    val wifi: Boolean = false,
    val mobile: Boolean = false,
    val wifiSignalLevel: Int? = null,
    val weakWifiSignal: Boolean = false,
)

/**
 * Checks network connectivity status for sync operations.
 */
class ConnectivityChecker(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Check if network is available and connected.
     * @return true if internet connection is available
     */
    fun isNetworkAvailable(requireValidated: Boolean = true): Boolean {
        val status = currentStatus()
        return status.connected && (!requireValidated || status.validated)
    }

    /**
     * Check if on an unmetered connection (WiFi or ethernet, not metered mobile data).
     * Mirrors WorkManager's NetworkType.UNMETERED so the in-process check stays consistent
     * with the WorkManager constraint used in SyncScheduler.
     */
    fun isUnmeteredConnected(): Boolean {
        return currentStatus().unmetered
    }

    fun currentStatus(): ConnectivityStatus {
        val network = connectivityManager.activeNetwork ?: return ConnectivityStatus()
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return ConnectivityStatus()
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val signalLevel = if (wifi && DeviceCompat.isOnyxDevice) {
            runCatching { NetworkUtil.getWifiSignalLevel(context) }.getOrNull()
        } else null
        val signalPasses = if (wifi && DeviceCompat.isOnyxDevice) {
            runCatching { NetworkUtil.isWifiSignalStrengthPass(context) }
                .getOrDefault(true)
        } else true

        return ConnectivityStatus(
            // A Wi-Fi LAN can host WebDAV without advertising internet capability.
            connected = hasInternet || wifi,
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            wifi = wifi,
            mobile = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            wifiSignalLevel = signalLevel,
            weakWifiSignal = wifi && !signalPasses,
        )
    }
}
