package dev.jusu.knix

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.jusu.knix.discovery.DiscoveredPeer
import dev.jusu.knix.discovery.KnixDiscovery
import dev.jusu.knix.quickconnect.QuickConnectQr
import dev.jusu.knix.quickconnect.QuickConnectTicket
import dev.jusu.knix.transfer.KnixRendezvousServer

class MainActivity : ComponentActivity() {
    private val peers = mutableStateListOf<DiscoveredPeer>()
    private val status = mutableStateOf("Ready to discover nearby devices")
    private val qrBitmap = mutableStateOf<Bitmap?>(null)
    private val qrExpiry = mutableStateOf("")
    private var discovery: KnixDiscovery? = null
    private var rendezvous: KnixRendezvousServer? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isEmpty() || requiredPermissions().none { permission ->
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
            }) startDiscovery()
        else status.value = "Nearby permissions are required to scan for devices"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Knix", style = MaterialTheme.typography.headlineLarge)
                        Text("Offline sharing. Nearby, fast, private.", style = MaterialTheme.typography.bodyLarge)
                        Text(status.value, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = ::requestPermissionsAndStart) { Text("Find devices") }
                            OutlinedButton(onClick = ::stopDiscovery) { Text("Stop scan") }
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("QR Quick Connect", style = MaterialTheme.typography.titleMedium)
                                Text("Show a temporary QR code so another Knix device can find this phone on the local network.")
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(onClick = ::startQuickConnect) { Text("Create receive QR") }
                                    OutlinedButton(onClick = ::stopQuickConnect) { Text("Close QR") }
                                }
                                qrBitmap.value?.let { bitmap ->
                                    Image(bitmap.asImageBitmap(), "Temporary Knix quick connect QR code",
                                        Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                                    Text(qrExpiry.value, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Text("Nearby devices", style = MaterialTheme.typography.titleMedium)
                        if (peers.isEmpty()) Text("No Knix devices found yet. Keep this screen open while scanning.")
                        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(peers, key = { it.id }) { peer ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(peer.name, style = MaterialTheme.typography.titleSmall)
                                        Text(peer.transport.name.replace('_', ' '), style = MaterialTheme.typography.bodySmall)
                                        Text(peer.address ?: "Address available after network connection", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        Text("QR currently establishes a one-time local rendezvous only. Secure pairing and file transfer are still under development.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    private fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        Build.VERSION.SDK_INT >= 31 -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun requestPermissionsAndStart() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startDiscovery() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startDiscovery() {
        if (discovery != null) return
        peers.clear()
        val engine = KnixDiscovery(applicationContext, onPeer = { peer ->
            runOnUiThread {
                val index = peers.indexOfFirst { it.id == peer.id }
                if (index >= 0) peers[index] = peer else peers.add(peer)
            }
        }, onStatus = { message -> runOnUiThread { status.value = message } })
        discovery = engine
        engine.start()
        status.value = "Scanning for Wi-Fi Direct, local-network, and Bluetooth LE peers"
    }

    private fun stopDiscovery() {
        discovery?.stop()
        discovery = null
        status.value = "Discovery stopped"
    }

    private fun startQuickConnect() {
        if (rendezvous != null) return
        val server = KnixRendezvousServer(
            onReady = { ticket ->
                runOnUiThread {
                    qrBitmap.value = QuickConnectQr.render(ticket.encode())
                    qrExpiry.value = "Expires at " + ticket.expiresAtEpochSeconds +
                        ". Keep both phones on the same Wi-Fi or hotspot."
                }
            },
            onStatus = { message -> runOnUiThread { status.value = message } }
        )
        rendezvous = server
        if (!server.start(Build.MODEL ?: "Android device")) {
            rendezvous = null
            status.value = "Could not start Quick Connect"
        } else status.value = "Starting local receiver…"
    }

    private fun stopQuickConnect() {
        rendezvous?.stop()
        rendezvous = null
        qrBitmap.value = null
        qrExpiry.value = ""
        status.value = "Quick Connect closed"
    }

    override fun onDestroy() {
        stopDiscovery()
        stopQuickConnect()
        super.onDestroy()
    }
}
