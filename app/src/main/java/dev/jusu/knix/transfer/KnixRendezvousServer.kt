package dev.jusu.knix.transfer

import dev.jusu.knix.quickconnect.QuickConnectTicket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal one-time QR rendezvous listener. This is not the file-transfer engine:
 * it accepts only a bounded KNIX rendezvous hello and never accepts file bytes.
 * A secure authenticated session protocol must replace this handshake before transfers ship.
 */
class KnixRendezvousServer(
    private val onReady: (QuickConnectTicket) -> Unit,
    private val onStatus: (String) -> Unit,
    private val port: Int = 0
) {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var activeTicket: QuickConnectTicket? = null

    fun start(deviceName: String): Boolean {
        if (!running.compareAndSet(false, true)) return false
        executor.execute {
            try {
                val socket = ServerSocket(port, 8)
                socket.soTimeout = 1000
                server = socket
                val host = findLocalIpv4()
                    ?: throw IllegalStateException("Connect to a Wi-Fi network or hotspot before creating a QR code")
                val ticket = QuickConnectTicket.create(host, socket.localPort, deviceName)
                activeTicket = ticket
                onReady(ticket)
                onStatus("Quick Connect is ready. QR expires in about two minutes.")
                while (running.get() && !ticket.isExpired()) {
                    try {
                        socket.accept().use { client -> handleHello(client, ticket) }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Poll the stop flag and ticket expiry.
                    }
                }
                if (running.get()) onStatus("Quick Connect QR expired. Create a new one to continue.")
            } catch (e: Exception) {
                if (running.get()) onStatus("Quick Connect could not start: ${e.message ?: "network unavailable"}")
            } finally {
                running.set(false)
                activeTicket = null
                try { server?.close() } catch (_: Exception) { }
                server = null
            }
        }
        return true
    }

    private fun handleHello(client: Socket, ticket: QuickConnectTicket) {
        client.soTimeout = 3000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
        val line = reader.readLine()?.take(256) ?: return
        if (line != "KNIX-HELLO/1 ${ticket.challenge}" || ticket.isExpired()) {
            client.getOutputStream().write("KNIX-REJECT/1\n".toByteArray(StandardCharsets.US_ASCII))
            return
        }
        client.getOutputStream().write("KNIX-RENDEZVOUS-OK/1\n".toByteArray(StandardCharsets.US_ASCII))
        onStatus("A device presented the QR challenge. Secure pairing and file transfer are not implemented yet.")
        // One-time ticket: stop accepting further attempts after a successful challenge.
        running.set(false)
    }

    fun stop() {
        running.set(false)
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
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
