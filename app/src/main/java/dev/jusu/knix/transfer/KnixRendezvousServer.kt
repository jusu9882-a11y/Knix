package dev.jusu.knix.transfer

import dev.jusu.knix.quickconnect.QuickConnectTicket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-time QR listener that completes authenticated ephemeral ECDH pairing
 * and hands the live socket to the encrypted file receiver.
 */
class KnixRendezvousServer(
    private val onReady: (QuickConnectTicket) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onPaired: (Socket, KnixSecurePairing.Result) -> Unit = { _, _ -> },
    private val port: Int = 0
) {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var activeClient: Socket? = null

    fun start(deviceName: String): Boolean {
        if (!running.compareAndSet(false, true)) return false
        executor.execute {
            try {
                val listener = ServerSocket(port, 8)
                listener.soTimeout = 1000
                server = listener
                val host = findLocalIpv4()
                    ?: throw IllegalStateException("Connect to a Wi-Fi network or hotspot before creating a QR code")
                val ticket = QuickConnectTicket.create(host, listener.localPort, deviceName)
                onReady(ticket)
                onStatus("Secure pairing QR is ready. It expires in about two minutes.")
                while (running.get() && !ticket.isExpired()) {
                    try {
                        val client = listener.accept()
                        activeClient = client
                        client.soTimeout = 8_000
                        try {
                            val paired = KnixSecurePairing.respond(client, ticket.challenge)
                            client.soTimeout = 0
                            onPaired(client, paired)
                            running.set(false)
                        } catch (e: Exception) {
                            onStatus("Pairing or transfer failed: ${e.message ?: "protocol error"}")
                        } finally {
                            try { client.close() } catch (_: Exception) { }
                            activeClient = null
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Periodically check stop and ticket expiry.
                    }
                }
                if (running.get()) onStatus("Quick Connect QR expired. Create a new one to continue.")
            } catch (e: Exception) {
                if (running.get()) onStatus("Quick Connect could not start: ${e.message ?: "network unavailable"}")
            } finally {
                running.set(false)
                try { server?.close() } catch (_: Exception) { }
                server = null
            }
        }
        return true
    }

    fun stop() {
        running.set(false)
        try { activeClient?.close() } catch (_: Exception) { }
        try { server?.close() } catch (_: Exception) { }
        executor.shutdownNow()
        try { executor.awaitTermination(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun findLocalIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan", ignoreCase = true) || it.name.startsWith("ap", ignoreCase = true)) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        } catch (_: Exception) { null }
    }
}
