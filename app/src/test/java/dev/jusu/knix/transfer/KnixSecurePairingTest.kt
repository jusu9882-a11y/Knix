package dev.jusu.knix.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

class KnixSecurePairingTest {
    private fun challenge(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(24) { (it * 7 + 3).toByte() })

    @Test
    fun bothPeersDeriveTheSameSessionKeyAndTranscript() {
        val challenge = challenge()
        val server = ServerSocket(0)
        val receiverResult = AtomicReference<KnixSecurePairing.Result>()
        val receiverFailure = AtomicReference<Throwable?>()
        val receiver = Thread {
            try {
                server.accept().use { receiverResult.set(KnixSecurePairing.respond(it, challenge)) }
            } catch (t: Throwable) {
                receiverFailure.set(t)
            }
        }
        receiver.start()

        val senderResult = Socket("127.0.0.1", server.localPort).use {
            KnixSecurePairing.initiate(it, challenge)
        }
        receiver.join(10_000)
        server.close()

        assertTrue("receiver thread should finish", !receiver.isAlive)
        receiverFailure.get()?.let { throw AssertionError("receiver handshake failed", it) }
        val received = requireNotNull(receiverResult.get())
        assertArrayEquals(senderResult.sessionKey, received.sessionKey)
        assertArrayEquals(senderResult.transcriptHash, received.transcriptHash)
        assertNotEquals(senderResult.peerFingerprint, received.peerFingerprint)
    }

    @Test
    fun mismatchedQrChallengeCannotCompletePairing() {
        val receiverChallenge = challenge()
        val senderChallenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(24) { (it * 11 + 9).toByte() })
        val server = ServerSocket(0)
        val receiverFailure = AtomicReference<Throwable?>()
        val receiver = Thread {
            try {
                server.accept().use { KnixSecurePairing.respond(it, receiverChallenge) }
            } catch (t: Throwable) {
                receiverFailure.set(t)
            }
        }
        receiver.start()

        var rejected = false
        try {
            Socket("127.0.0.1", server.localPort).use {
                KnixSecurePairing.initiate(it, senderChallenge)
            }
        } catch (_: Exception) {
            rejected = true
        }
        receiver.join(10_000)
        server.close()
        assertTrue("sender with the wrong QR challenge must be rejected", rejected)
    }
}
