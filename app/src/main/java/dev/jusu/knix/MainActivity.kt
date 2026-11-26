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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.jusu.knix.data.TransferHistoryEntry
import dev.jusu.knix.data.TransferHistoryStore
import dev.jusu.knix.discovery.DiscoveredPeer
import dev.jusu.knix.discovery.KnixDiscovery
import dev.jusu.knix.discovery.KnixWifiDirectConnection
import dev.jusu.knix.quickconnect.QuickConnectQr
import dev.jusu.knix.quickconnect.QuickConnectTicket
import dev.jusu.knix.transfer.KnixTransferForegroundService
import dev.jusu.knix.transfer.KnixRendezvousServer
import dev.jusu.knix.transfer.KnixSecurePairing
import dev.jusu.knix.transfer.KnixEncryptedTransfer
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val peers = mutableStateListOf<DiscoveredPeer>()
    private val history = mutableStateListOf<TransferHistoryEntry>()
    private val queuedUris = mutableStateListOf<Uri>()
    private lateinit var historyStore: TransferHistoryStore
    private val selectedTab = mutableStateOf("Home")
    private val status = mutableStateOf("Ready to discover nearby devices")
    private val qrBitmap = mutableStateOf<Bitmap?>(null)
    private val qrExpiry = mutableStateOf("")
    private val transferProgress = mutableStateOf("")
    private val incomingRequest = mutableStateOf(false)
    private val incomingFingerprint = mutableStateOf("")
    private var pendingApproval: CountDownLatch? = null
    private var pendingApprovalChoice: AtomicBoolean? = null
    private var discovery: KnixDiscovery? = null
    private var wifiDirect: KnixWifiDirectConnection? = null
    private var p2pLocalAddress: String? = null
    private val p2pStatus = mutableStateOf("Wi-Fi Direct not connected")
    private var rendezvous: KnixRendezvousServer? = null
    private var pairedSessionKey: ByteArray? = null
    private var pairedSocket: Socket? = null
    private val transferExecutor = Executors.newSingleThreadExecutor()

    private val openFilesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: Exception) { }
            }
            queuedUris.clear()
            queuedUris.addAll(uris.distinct())
            status.value = "${queuedUris.size} file(s) added to the send queue"
        }
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
        historyStore = TransferHistoryStore(applicationContext)
        history.addAll(historyStore.read())
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF20231D),
                    onPrimary = Color.White,
                    secondary = Color(0xFFB8F818),
                    onSecondary = Color(0xFF20231D),
                    background = Color(0xFFF6F7F4),
                    surface = Color.White,
                    onSurface = Color(0xFF20231D),
                    onSurfaceVariant = Color(0xFF6B7068)
                )
            ) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(
                            containerColor = Color.White,
                            tonalElevation = 8.dp
                        ) {
                            listOf("Home", "Send", "Receive", "Devices", "Activity").forEach { tab ->
                                val symbol = when (tab) {
                                    "Home" -> "⌂"
                                    "Send" -> "↗"
                                    "Receive" -> "⇩"
                                    "Devices" -> "⌕"
                                    else -> "◷"
                                }
                                NavigationBarItem(
                                    selected = selectedTab.value == tab,
                                    onClick = { selectedTab.value = tab },
                                    icon = { Text(symbol, style = MaterialTheme.typography.titleMedium) },
                                    label = { Text(tab, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Column(
                        Modifier.fillMaxSize().padding(innerPadding)
                            .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("KNIX", style = MaterialTheme.typography.headlineMedium)
                                Text("OFFLINE  •  PRIVATE  •  DIRECT", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("●", color = Color(0xFF78A900), style = MaterialTheme.typography.titleLarge)
                        }
                        when (selectedTab.value) {
                            "Home" -> {
                                Text("Your sharing space", style = MaterialTheme.typography.titleLarge)
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("Ready to share?", style = MaterialTheme.typography.titleMedium)
                                        Text("Send files directly between nearby phones. No cloud account or internet upload.")
                                        Text(status.value, style = MaterialTheme.typography.bodySmall)
                                        if (transferProgress.value.isNotBlank()) Text(transferProgress.value)
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Button(onClick = { selectedTab.value = "Send" }) { Text("Send files") }
                                            OutlinedButton(onClick = { selectedTab.value = "Receive" }) { Text("Receive") }
                                        }
                                    }
                                }
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Nearby devices", style = MaterialTheme.typography.titleMedium)
                                        Text(if (peers.isEmpty()) "No devices discovered yet." else "${peers.size} device(s) discovered")
                                        OutlinedButton(onClick = { selectedTab.value = "Devices" }) { Text("View devices") }
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
                            }
                            "Send" -> {
                                Text("Send files", style = MaterialTheme.typography.titleLarge)
                                Text("Connect to another Knix phone, then choose one or more files to send.")
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("1. Connect to receiver", style = MaterialTheme.typography.titleMedium)
                                        Text(if (pairedSocket != null) "Secure connection established." else "Scan the receiver's temporary QR code. Both phones must use the same Wi-Fi or hotspot.")
                                        Button(onClick = ::scanQuickConnect) { Text("Scan receiver QR") }
                                        Text("2. Select a file", style = MaterialTheme.typography.titleMedium)
                                        Button(onClick = { openFilesLauncher.launch(arrayOf("*/*")) }, enabled = pairedSocket != null) {
                                            Text("Choose multiple files")
                                        }
                                        if (queuedUris.isNotEmpty()) {
                                            Text("${queuedUris.size} file(s) queued")
                                            OutlinedButton(onClick = { queuedUris.clear(); status.value = "Send queue cleared" }) { Text("Clear queue") }
                                            Button(onClick = ::sendQueuedFiles, enabled = pairedSocket != null) { Text("Send queued files") }
                                        }
                                        Text(status.value, style = MaterialTheme.typography.bodySmall)
                                        if (transferProgress.value.isNotBlank()) Text(transferProgress.value)
                                    }
                                }
                            }
                            "Receive" -> {
                                Text("Receive files", style = MaterialTheme.typography.titleLarge)
                                Text(if (p2pLocalAddress != null) "Wi-Fi Direct link ready. Create a QR code to transfer over this direct connection." else "Create a one-time QR code. For Wi-Fi Direct, connect to a discovered peer first; otherwise use the same Wi-Fi or hotspot.")
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(onClick = ::startQuickConnect) { Text("Create receive QR") }
                                        qrBitmap.value?.let { bitmap ->
                                            Image(bitmap.asImageBitmap(), "Temporary Knix quick connect QR code",
                                                Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                                            Text(qrExpiry.value, style = MaterialTheme.typography.bodySmall)
                                        }
                                        if (incomingRequest.value) {
                                            Text("Incoming transfer request", style = MaterialTheme.typography.titleMedium)
                                            Text("Sender fingerprint: ${incomingFingerprint.value}")
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
                                        OutlinedButton(onClick = ::stopQuickConnect) { Text("Close receiver") }
                                        Text(status.value, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            "Devices" -> {
                                Text("Nearby devices", style = MaterialTheme.typography.titleLarge)
                                Text("Discover Knix phones on the local network and nearby radios.")
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(onClick = ::requestPermissionsAndStart) { Text("Find devices") }
                                    OutlinedButton(onClick = ::stopDiscovery) { Text("Stop scan") }
                                }
                                Text(p2pStatus.value, style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = ::disconnectWifiDirect) { Text("Disconnect Wi-Fi Direct") }
                                Text(status.value, style = MaterialTheme.typography.bodySmall)
                                if (peers.isEmpty()) {
                                    Card(Modifier.fillMaxWidth()) {
                                        Text("No Knix devices found yet. Start scanning and keep both phones nearby or on the same Wi-Fi.", Modifier.padding(16.dp))
                                    }
                                } else {
                                    peers.forEach { peer ->
                                        Card(Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(peer.name, style = MaterialTheme.typography.titleMedium)
                                                Text(peer.transport.name.replace('_', ' '), style = MaterialTheme.typography.bodySmall)
                                                Text(peer.address ?: "Address available after network connection", style = MaterialTheme.typography.bodySmall)
                                                if (peer.transport == dev.jusu.knix.discovery.DiscoveryTransport.WIFI_DIRECT && !peer.address.isNullOrBlank()) {
                                                    Button(onClick = { connectWifiDirect(peer) }) { Text("Connect with Wi-Fi Direct") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            "Activity" -> {
                                Text("Transfer activity", style = MaterialTheme.typography.titleLarge)
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Current status", style = MaterialTheme.typography.titleMedium)
                                        Text(status.value)
                                        if (transferProgress.value.isNotBlank()) {
                                            Text("Transfer progress", style = MaterialTheme.typography.titleSmall)
                                            Text(transferProgress.value)
                                        } else {
                                            Text("No transfer is currently running.")
                                        }
                                    }
                                }
                                Text("Recent transfers", style = MaterialTheme.typography.titleMedium)
                                if (history.isEmpty()) {
                                    Text("Completed transfers will appear here and remain on this phone.", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    history.forEach { entry ->
                                        Card(Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text("${entry.direction} • ${entry.fileName}", style = MaterialTheme.typography.titleSmall)
                                                    Text(entry.outcome, style = MaterialTheme.typography.labelSmall, color = Color(0xFF568000))
                                                }
                                                Text("${formatBytes(entry.bytes)} • ${DateFormat.getDateTimeInstance().format(Date(entry.timestamp))}", style = MaterialTheme.typography.bodySmall)
                                                if (entry.sha256.isNotBlank()) Text("SHA-256: ${entry.sha256}", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                    OutlinedButton(onClick = {
                                        historyStore.clear()
                                        history.clear()
                                    }) { Text("Clear history on this phone") }
                                }
                                OutlinedButton(onClick = ::stopQuickConnect) { Text("Close active connection") }
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

    private fun connectWifiDirect(peer: DiscoveredPeer) {
        requestPermissionsAndStart()
        val address = peer.address ?: run { status.value = "Peer address is unavailable"; return }
        if (wifiDirect == null) {
            wifiDirect = KnixWifiDirectConnection(applicationContext,
                onStatus = { message -> runOnUiThread { p2pStatus.value = message } },
                onConnected = { info, localAddress ->
                    runOnUiThread {
                        p2pLocalAddress = if (info.isGroupOwner) info.groupOwnerAddress?.hostAddress else localAddress
                        p2pStatus.value = "Wi-Fi Direct ready • ${p2pLocalAddress ?: "P2P address unavailable"}"
                        if (p2pLocalAddress == null) status.value = "Wi-Fi Direct connected, but the P2P interface address could not be found. QR transfer cannot start yet."
                    }
                })
        }
        wifiDirect?.connect(address)
    }

    private fun disconnectWifiDirect() {
        wifiDirect?.disconnect()
        wifiDirect = null
        p2pLocalAddress = null
        p2pStatus.value = "Wi-Fi Direct disconnected"
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

    private fun sendQueuedFiles() {
        val socket = pairedSocket ?: run { status.value = "Connect to a receiver first"; return }
        val key = pairedSessionKey?.copyOf() ?: run { status.value = "Secure session key is unavailable"; return }
        val uris = queuedUris.toList()
        if (uris.isEmpty()) { status.value = "Choose files before sending"; key.fill(0); return }
        if (uris.size > 50) { status.value = "A batch can contain at most 50 files"; key.fill(0); return }
        queuedUris.clear()
        status.value = "Preparing ${uris.size} file(s)…"
        KnixTransferForegroundService.start(this, "Preparing ${uris.size} file(s)")
        transferExecutor.execute {
            val temporaryFiles = mutableListOf<File>()
            try {
                val sources = uris.mapIndexed { index, uri ->
                    val name = queryDisplayName(uri) ?: "Knix-file-${index + 1}"
                    val temp = File(cacheDir, "knix-send-${System.nanoTime()}-$index.tmp")
                    temporaryFiles += temp
                    contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("Cannot open selected file: $name")
                    temp to name
                }
                val results = KnixEncryptedTransfer.sendFiles(socket, key, sources) { fileIndex, fileCount, progress ->
                    runOnUiThread {
                        transferProgress.value = "File $fileIndex of $fileCount • ${progress.transferred} / ${progress.total} bytes"
                        KnixTransferForegroundService.update(this@MainActivity, "Sending file $fileIndex of $fileCount")
                    }
                }
                results.forEachIndexed { index, result ->
                    recordHistory(sources[index].second, "Sent", result.bytes, result.sha256)
                }
                runOnUiThread {
                    KnixTransferForegroundService.stop(this@MainActivity)
                    status.value = "Batch complete: ${results.size} file(s) sent and verified."
                    transferProgress.value = ""
                    closePairedConnection()
                }
            } catch (e: Exception) {
                try { socket.close() } catch (_: Exception) { }
                runOnUiThread {
                    KnixTransferForegroundService.stop(this@MainActivity)
                    closePairedConnection()
                    status.value = "Batch stopped: ${e.message ?: "connection error"}. Reconnect and select the remaining files again."
                    transferProgress.value = ""
                }
            } finally {
                key.fill(0)
                temporaryFiles.forEach { it.delete() }
            }
        }
    }

    private fun recordHistory(name: String, direction: String, bytes: Long, sha256: String) {
        val entry = TransferHistoryEntry(name, direction, bytes, System.currentTimeMillis(), sha256, "Complete")
        historyStore.append(entry)
        runOnUiThread {
            history.add(0, entry)
            while (history.size > 100) history.removeAt(history.lastIndex)
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun startQuickConnect() {
        if (rendezvous != null) return
        KnixTransferForegroundService.start(this, "Waiting for a nearby Knix sender")
        closePairedConnection()
        val server = KnixRendezvousServer(
            onReady = { ticket ->
                runOnUiThread {
                    qrBitmap.value = QuickConnectQr.render(ticket.encode())
                    qrExpiry.value = "Expires at ${ticket.expiresAtEpochSeconds}. Keep both phones on the same Wi-Fi or hotspot."
                }
            },
            onStatus = { message -> runOnUiThread {
                status.value = message
                if (message.contains("expired", ignoreCase = true) || message.contains("could not start", ignoreCase = true)) {
                    KnixTransferForegroundService.stop(this@MainActivity)
                }
            } },
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
                        status.value = "Transfer approved. Receiving encrypted files…"
                        KnixTransferForegroundService.update(this@MainActivity, "Receiving encrypted files")
                    }
                    val results = KnixEncryptedTransfer.receiveFiles(socket, transferKey, folder) { fileIndex, fileCount, progress ->
                        runOnUiThread {
                            transferProgress.value = "File $fileIndex of $fileCount • ${progress.transferred} / ${progress.total} bytes"
                            KnixTransferForegroundService.update(this@MainActivity, "Receiving file $fileIndex of $fileCount")
                        }
                    }
                    results.forEach { result -> recordHistory(result.file.name, "Received", result.bytes, result.sha256) }
                    runOnUiThread {
                        KnixTransferForegroundService.stop(this@MainActivity)
                        status.value = "Received ${results.size} file(s). All SHA-256 checks passed. Saved in: ${folder.absolutePath}"
                        transferProgress.value = ""
                        closePairedConnection()
                    }
                } catch (e: Exception) {
                    try { socket.close() } catch (_: Exception) { }
                    pairedSessionKey?.fill(0)
                    pairedSessionKey = null
                    pairedSocket = null
                    runOnUiThread {
                        KnixTransferForegroundService.stop(this@MainActivity)
                        status.value = "Receive failed: ${e.message ?: "connection error"}. Partial data is kept for resume."
                        transferProgress.value = ""
                    }
                } finally {
                    transferKey.fill(0)
                    pendingApproval = null
                    pendingApprovalChoice = null
                    runOnUiThread { incomingRequest.value = false }
                }
            },
            preferredHost = p2pLocalAddress
        )
        rendezvous = server
        if (!server.start(Build.MODEL ?: "Android device")) {
            rendezvous = null
            KnixTransferForegroundService.stop(this)
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
        KnixTransferForegroundService.stop(this)
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
        wifiDirect?.disconnect()
        wifiDirect = null
        transferExecutor.shutdownNow()
        super.onDestroy()
    }
}
