package dev.jusu.knix.discovery

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Negotiates a real Wi-Fi Direct group and exposes the receiving phone's
 * P2P interface address. The QR listener must bind on this address before
 * publishing a ticket so subsequent encrypted TCP pairing uses the P2P link.
 */
class KnixWifiDirectConnection(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onConnected: (WifiP2pInfo, String?) -> Unit
) {
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = manager?.initialize(context, context.mainLooper, null)
    private var receiver: BroadcastReceiver? = null
    @Volatile private var connected = false

    fun connect(deviceAddress: String) {
        if (!hasPermission()) {
            onStatus("Grant Nearby Wi-Fi permission before connecting")
            return
        }
        val p2p = manager ?: return onStatus("Wi-Fi Direct is unavailable")
        val ch = channel ?: return onStatus("Wi-Fi Direct channel is unavailable")
        if (!deviceAddress.matches(Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$"))) {
            onStatus("This peer does not expose a valid Wi-Fi Direct address")
            return
        }
        registerReceiver()
        val config = WifiP2pConfig().apply { deviceAddress.also { this.deviceAddress = it } }
        try {
            p2p.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { onStatus("Wi-Fi Direct connection negotiation started…") }
                override fun onFailure(reason: Int) { onStatus("Wi-Fi Direct connection failed: $reason") }
            })
        } catch (_: SecurityException) {
            onStatus("Wi-Fi Direct permission was revoked")
        }
    }

    fun disconnect() {
        val p2p = manager
        val ch = channel
        if (p2p != null && ch != null && hasPermission()) {
            try {
                p2p.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { onStatus("Wi-Fi Direct group disconnected") }
                    override fun onFailure(reason: Int) { onStatus("Could not disconnect Wi-Fi Direct group: $reason") }
                })
            } catch (_: SecurityException) { onStatus("Wi-Fi Direct permission was revoked") }
        }
        connected = false
        unregisterReceiver()
    }

    private fun registerReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                if (!hasPermission()) return onStatus("Wi-Fi Direct permission was revoked")
                val network = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO) as? NetworkInfo
                if (network?.isConnected != true) {
                    connected = false
                    onStatus("Wi-Fi Direct group disconnected")
                    return
                }
                val p2p = manager ?: return
                val ch = channel ?: return
                try {
                    p2p.requestConnectionInfo(ch) { info ->
                        if (info.groupFormed) {
                            connected = true
                            val local = localP2pIpv4()
                            onConnected(info, local)
                            onStatus(if (info.isGroupOwner) "Wi-Fi Direct connected. This phone is group owner."
                                else "Wi-Fi Direct connected. This phone is a group client.")
                        }
                    }
                } catch (_: SecurityException) { onStatus("Wi-Fi Direct permission was revoked") }
            }
        }
        receiver = r
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(r, filter)
    }

    private fun unregisterReceiver() {
        receiver?.let { try { context.unregisterReceiver(it) } catch (_: IllegalArgumentException) { } }
        receiver = null
    }

    private fun localP2pIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.name.contains("p2p", ignoreCase = true) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    } catch (_: Exception) { null }

    private fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
            else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
