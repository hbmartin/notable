package com.ethran.notable.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.ethran.notable.editor.utils.DeviceCompat
import com.onyx.android.sdk.api.utils.NetworkUtil
import java.net.URI

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

    fun currentStatus(): ConnectivityStatus {
        val network = connectivityManager.activeNetwork ?: return ConnectivityStatus()
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return ConnectivityStatus()
        val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val signalLevel = if (wifi && DeviceCompat.isOnyxDevice) {
            runCatching { NetworkUtil.getWifiSignalLevel(context) }.getOrNull()
        } else null
        val signalPasses = if (wifi && DeviceCompat.isOnyxDevice) {
            runCatching { NetworkUtil.isWifiSignalStrengthPass(context) }
                .getOrDefault(true)
        } else true

        return ConnectivityStatus(
            // Reaching this point means Android has an active network. It may be a local-only
            // Wi-Fi, Ethernet, or VPN transport without INTERNET; validation is tracked separately.
            connected = true,
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            wifi = wifi,
            mobile = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            wifiSignalLevel = signalLevel,
            weakWifiSignal = wifi && !signalPasses,
        )
    }
}

internal fun isNetworkUsableForServer(
    status: ConnectivityStatus,
    serverUrl: String,
): Boolean = status.connected && (status.validated || isLikelyLocalServerUrl(serverUrl))

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
