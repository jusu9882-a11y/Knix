package dev.jusu.knix

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.app.Activity
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Environment
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.CaptureActivity
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
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
import dev.jusu.knix.transfer.KnixSecurePairing
import dev.jusu.knix.transfer.KnixEncryptedTransfer
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val peers = mutableStateListOf<DiscoveredPeer>()
    private val status = mutableStateOf("Ready to discover nearby devices")
    private val qrBitmap = mutableStateOf<Bitmap?>(null)
    private val qrExpiry = mutableStateOf("")
    private val transferProgress = mutableStateOf("")
    private val incomingRequest = mutableStateOf(false)
    private val incomingFingerprint = mutableStateOf("")
    private var pendingApproval: CountDownLatch? = null
    private var pendingApprovalChoice: AtomicBoolean? = null
    private var discovery: KnixDiscovery? = null
    private var rendezvous: KnixRendezvousServer? = null
    private var pairedSessionKey: ByteArray? = null
    private var pairedSocket: Socket? = null
    private val transferExecutor = Executors.newSingleThreadExecutor()

    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sendSelectedFile(uri)
    }
    private val qrScanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val raw = result.data?.getStringExtra("SCAN_RESULT")
            if (raw.isNullOrBlank()) status.value = "No QR code was read" else connectFromQr(raw)
        } else status.value = "QR scan cancelled"
    }
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
                        if (transferProgress.value.isNotBlank()) Text(transferProgress.value, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = ::requestPermissionsAndStart) { Text("Find devices") }
                            OutlinedButton(onClick = ::stopDiscovery) { Text("Stop scan") }
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("QR Quick Connect", style = MaterialTheme.typography.titleMedium)
                                Text("Create a temporary receive code or scan another Knix phone. Both phones must be on the same Wi-Fi or hotspot.")
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(onClick = ::startQuickConnect) { Text("Receive") }
                                    OutlinedButton(onClick = ::scanQuickConnect) { Text("Scan QR") }
                                }
                                OutlinedButton(onClick = ::stopQuickConnect) { Text("Close connection") }
                                qrBitmap.value?.let { bitmap ->
                                    Image(bitmap.asImageBitmap(), "Temporary Knix quick connect QR code",
                                        Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                                    Text(qrExpiry.value, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (incomingRequest.value) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Incoming transfer request", style = MaterialTheme.typography.titleMedium)
                                    Text("Compare this fingerprint with the sender before accepting: ${incomingFingerprint.value}")
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(onClick = {
                                            pendingApprovalChoice?.set(true)
                                            pendingApproval?.countDown()
                                            incomingRequest.value = false
                                        }) { Text("Accept") }
                                        OutlinedButton(onClick = {
                                            pendingApprovalChoice?.set(false)
                                            pendingApproval?.countDown()
                                            incomingRequest.value = false
                                        }) { Text("Reject") }
                                    }
                                }
                            }
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Send files", style = MaterialTheme.typography.titleMedium)
                                Button(onClick = { openFileLauncher.launch(arrayOf("*/*")) }) {
                                    Text("Choose file to send")
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

    private fun scanQuickConnect() {
        val intent = IntentIntegrator(this).apply {
            setCaptureActivity(CaptureActivity::class.java)
            setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            setPrompt("Scan the receiver's Knix QR code")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }.createScanIntent()
        qrScanLauncher.launch(intent)
    }

    private fun connectFromQr(raw: String) {
        val ticket = try { QuickConnectTicket.parse(raw) }
        catch (e: Exception) {
            status.value = "Invalid Knix QR: " + (e.message ?: "cannot read ticket")
            return
        }
        status.value = "Connecting securely to " + ticket.deviceName + "…"
        transferExecutor.execute {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ticket.host, ticket.port), 5000)
                socket.soTimeout = 0
                val paired = KnixSecurePairing.initiate(socket, ticket.challenge)
                socket.soTimeout = 0
                runOnUiThread {
                    closePairedConnection()
                    pairedSocket = socket
                    pairedSessionKey = paired.sessionKey.copyOf()
                    paired.sessionKey.fill(0)
                    status.value = "Securely connected to " + ticket.deviceName +
                        ". Fingerprint: " + paired.peerFingerprint + ". Choose a file to send."
                }
            } catch (e: Exception) {
                runOnUiThread { status.value = "Secure pairing failed: " + (e.message ?: "network or protocol error") }
            }
        }
    }

    private fun sendSelectedFile(uri: Uri) {
        val socket = pairedSocket ?: run { status.value = "Connect to a receiver first"; return }
        val key = pairedSessionKey?.copyOf() ?: run { status.value = "Secure session key is unavailable"; return }
        status.value = "Preparing selected file…"
        transferExecutor.execute {
            var temp: File? = null
            try {
                val name = queryDisplayName(uri) ?: "Knix-file"
                temp = File(cacheDir, "knix-send-${System.nanoTime()}.tmp")
                contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Cannot open selected file")
                val result = KnixEncryptedTransfer.sendFile(socket, key, temp, name) { progress ->
                    runOnUiThread { transferProgress.value = "${progress.transferred} / ${progress.total} bytes" }
                }
                runOnUiThread {
                    status.value = "Transfer complete. Sent ${result.bytes} bytes; SHA-256 verified by receiver."
                    transferProgress.value = ""
                    closePairedConnection()
                }
            } catch (e: Exception) {
                try { socket.close() } catch (_: Exception) { }
                runOnUiThread {
                    closePairedConnection()
                    status.value = "Send failed: ${e.message ?: "connection error"}. Reconnect to resume the saved partial file."
                }
            } finally {
                key.fill(0)
                temp?.delete()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun startQuickConnect() {
        if (rendezvous != null) return
        closePairedConnection()
        val server = KnixRendezvousServer(
            onReady = { ticket ->
                runOnUiThread {
                    qrBitmap.value = QuickConnectQr.render(ticket.encode())
                    qrExpiry.value = "Expires at ${ticket.expiresAtEpochSeconds}. Keep both phones on the same Wi-Fi or hotspot."
                }
            },
            onStatus = { message -> runOnUiThread { status.value = message } },
            onPaired = { socket, paired ->
                socket.soTimeout = 0
                val transferKey = paired.sessionKey.copyOf()
                pairedSessionKey = transferKey.copyOf()
                paired.sessionKey.fill(0)
                pairedSocket = socket
                val approval = CountDownLatch(1)
                val approved = AtomicBoolean(false)
                pendingApproval = approval
                pendingApprovalChoice = approved
                runOnUiThread {
                    incomingFingerprint.value = paired.peerFingerprint
                    incomingRequest.value = true
                    status.value = "A nearby sender is requesting permission to transfer a file."
                }
                val folder = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir, "Knix Received")
                try {
                    if (!approval.await(60, TimeUnit.SECONDS) || !approved.get()) {
                        throw SecurityException("Incoming transfer was not approved")
                    }
                    runOnUiThread {
                        incomingRequest.value = false
                        status.value = "Transfer approved. Receiving encrypted file…"
                    }
                    val result = KnixEncryptedTransfer.receiveFile(socket, transferKey, folder) { progress ->
                        runOnUiThread { transferProgress.value = "${progress.transferred} / ${progress.total} bytes" }
                    }
                    runOnUiThread {
                        status.value = "Received ${result.file.name} (${result.bytes} bytes). SHA-256 verified. Saved in: ${folder.absolutePath}"
                        transferProgress.value = ""
                        closePairedConnection()
                    }
                } catch (e: Exception) {
                    try { socket.close() } catch (_: Exception) { }
                    pairedSessionKey?.fill(0)
                    pairedSessionKey = null
                    pairedSocket = null
                    runOnUiThread {
                        status.value = "Receive failed: ${e.message ?: "connection error"}. Partial data is kept for resume."
                        transferProgress.value = ""
                    }
                } finally {
                    transferKey.fill(0)
                    pendingApproval = null
                    pendingApprovalChoice = null
                    runOnUiThread { incomingRequest.value = false }
                }
            }
        )
        rendezvous = server
        if (!server.start(Build.MODEL ?: "Android device")) {
            rendezvous = null
            status.value = "Could not start Quick Connect"
        } else status.value = "Starting local receiver…"
    }

    private fun closePairedConnection() {
        try { pairedSocket?.close() } catch (_: Exception) { }
        pairedSocket = null
        pairedSessionKey?.fill(0)
        pairedSessionKey = null
    }

    private fun stopQuickConnect() {
        pendingApprovalChoice?.set(false)
        pendingApproval?.countDown()
        incomingRequest.value = false
        rendezvous?.stop()
        rendezvous = null
        closePairedConnection()
        qrBitmap.value = null
        qrExpiry.value = ""
        transferProgress.value = ""
        status.value = "Quick Connect closed"
    }

    override fun onDestroy() {
        stopDiscovery()
        stopQuickConnect()
        transferExecutor.shutdownNow()
        super.onDestroy()
    }
}
