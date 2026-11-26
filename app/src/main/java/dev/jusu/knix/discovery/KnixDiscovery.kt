package dev.jusu.knix.discovery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovery only: no peer is trusted or connected by discovery.
 * The authenticated KNIX handshake must run before any file data is accepted.
 */
class KnixDiscovery(
    private val context: Context,
    private val onPeer: (DiscoveredPeer) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    companion object {
        const val SERVICE_TYPE = "_knix._tcp."
        const val SERVICE_NAME = "Knix"
        val SERVICE_UUID: UUID = UUID.fromString("7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e")
    }

    private val seen = ConcurrentHashMap<String, DiscoveredPeer>()
    private val wifiManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val wifiChannel = wifiManager?.initialize(context, context.mainLooper, null)
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val bluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var nsdDiscovery: NsdManager.DiscoveryListener? = null
    private var nsdRegistration: NsdManager.RegistrationListener? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var wifiReceiver: BroadcastReceiver? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        startWifiDirect()
        startNsd()
        startBle()
    }

    fun stop() {
        if (!started) return
        started = false
        wifiReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: IllegalArgumentException) { }
        }
        wifiReceiver = null
        try { nsdDiscovery?.let(nsd::stopServiceDiscovery) } catch (_: Exception) { }
        nsdDiscovery = null
        try { nsdRegistration?.let(nsd::unregisterService) } catch (_: Exception) { }
        nsdRegistration = null
        if (hasBluetoothScanPermission()) {
            try { scanCallback?.let { scanner?.stopScan(it) } } catch (_: SecurityException) { }
            try { advertiseCallback?.let { bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } } catch (_: SecurityException) { }
        }
        scanner = null
        scanCallback = null
        advertiseCallback = null
        onStatus("Discovery stopped")
    }

    private fun emit(peer: DiscoveredPeer) {
        seen[peer.id] = peer
        onPeer(peer)
    }

    private fun startWifiDirect() {
        val manager = wifiManager ?: return onStatus("Wi-Fi Direct is unavailable on this device")
        val channel = wifiChannel ?: return onStatus("Wi-Fi Direct channel unavailable")
        if (!hasWifiPermission()) return onStatus("Grant Nearby Wi-Fi permission to discover Wi-Fi Direct peers")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) return
                try {
                    manager.requestPeers(channel) { peers ->
                        peers.deviceList.forEach { device ->
                            emit(DiscoveredPeer(
                                id = "p2p:${device.deviceAddress}",
                                name = device.deviceName?.takeIf(String::isNotBlank) ?: "Knix device",
                                address = device.deviceAddress,
                                transport = DiscoveryTransport.WIFI_DIRECT
                            ))
                        }
                    }
                } catch (_: SecurityException) {
                    onStatus("Wi-Fi Direct permission was revoked")
                }
            }
        }
        wifiReceiver = receiver
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        try {
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = onStatus("Wi-Fi Direct peer search started")
                override fun onFailure(reason: Int) = onStatus("Wi-Fi Direct discovery failed: $reason")
            })
        } catch (_: SecurityException) {
            onStatus("Wi-Fi Direct permission was revoked")
        }
    }

    private fun startNsd() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { onStatus("Local-network discovery started") }
            override fun onDiscoveryStopped(serviceType: String) { }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { onStatus("Local-network discovery failed: $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { onStatus("Could not stop local-network discovery: $errorCode") }
            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.contains("_knix")) return
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host: InetAddress = info.host ?: return
                        emit(DiscoveredPeer(
                            id = "nsd:${host.hostAddress}:${info.port}",
                            name = info.serviceName ?: "Knix device",
                            address = host.hostAddress,
                            port = info.port,
                            transport = DiscoveryTransport.LOCAL_NETWORK
                        ))
                    }
                })
            }
            override fun onServiceLost(service: NsdServiceInfo) { }
        }
        nsdDiscovery = listener
        try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (e: Exception) { onStatus("Local-network discovery unavailable: ${e.message}") }

        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { onStatus("Knix is visible on this local network") }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) { onStatus("Could not publish Knix service: $errorCode") }
            override fun onServiceUnregistered(info: NsdServiceInfo) { }
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) { }
        }
        nsdRegistration = registration
        val localService = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = 54321
        }
        try { nsd.registerService(localService, NsdManager.PROTOCOL_DNS_SD, registration) }
        catch (e: Exception) { onStatus("Could not publish local Knix service: ${e.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun startBle() {
        val adapter = bluetoothAdapter ?: return onStatus("Bluetooth LE is unavailable")
        if (!adapter.isEnabled) return onStatus("Turn on Bluetooth to enable nearby discovery")
        if (!hasBluetoothScanPermission()) return onStatus("Grant Nearby Devices permission for Bluetooth discovery")
        val advertiser = adapter.bluetoothLeAdvertiser
        scanner = adapter.bluetoothLeScanner
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()
        try {
            if (advertiser != null) {
                val callback = object : AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { onStatus("Bluetooth LE presence is active") }
                    override fun onStartFailure(errorCode: Int) { onStatus("Bluetooth LE advertising failed: $errorCode") }
                }
                advertiseCallback = callback
                advertiser.startAdvertising(settings, data, callback)
            }
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val record = result.scanRecord ?: return
                    if (record.serviceUuids?.none { it.uuid == SERVICE_UUID } != false) return
                    val device = result.device
                    emit(DiscoveredPeer(
                        id = "ble:${device.address}",
                        name = device.name?.takeIf(String::isNotBlank) ?: "Nearby Knix device",
                        address = device.address,
                        transport = DiscoveryTransport.BLUETOOTH_LE
                    ))
                }
                override fun onScanFailed(errorCode: Int) { onStatus("Bluetooth LE scan failed: $errorCode") }
            }
            scanCallback = callback
            scanner?.startScan(callback)
        } catch (_: SecurityException) {
            onStatus("Bluetooth permission was revoked")
        }
    }

    private fun hasWifiPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothScanPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
