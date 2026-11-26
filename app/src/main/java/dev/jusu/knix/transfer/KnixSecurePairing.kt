package dev.jusu.knix.transfer

import java.io.ByteArrayOutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Ephemeral, mutually-confirmed P-256 ECDH pairing. QR challenge is a bearer secret. */
object KnixSecurePairing {
    private const val PAIR = "KNIX-PAIR/1"
    private const val FINISH = "KNIX-PAIR-FINISH/1"
    private const val OK = "KNIX-PAIR-OK/1"
    private const val MAX_LINE_BYTES = 2048
    private val random = SecureRandom()

    data class Result(val sessionKey: ByteArray, val peerFingerprint: String, val transcriptHash: ByteArray)
    private data class KeyMaterial(val sessionKey: ByteArray, val confirmationKey: ByteArray)

    fun initiate(socket: Socket, challenge: String): Result {
        validateChallenge(challenge)
        socket.soTimeout = 8_000
        val local = generateEphemeralKeyPair()
        val initiatorPublic = b64(local.public.encoded)
        writeLine(socket, "$PAIR|$initiatorPublic")
        val response = readLine(socket).split('|')
        require(response.size == 3 && response[0] == PAIR) { "Invalid pairing response" }
        val responderKey = decodePublicKey(response[1])
        val transcript = transcript(challenge, initiatorPublic, response[1])
        val material = derive(local.private, responderKey, challenge, transcript)
        val expected = proof(material.confirmationKey, transcript, "RESPONDER")
        require(MessageDigest.isEqual(expected, unb64(response[2]))) {
            "Receiver authentication failed. Scan a fresh QR code."
        }
        writeLine(socket, "$FINISH|${b64(proof(material.confirmationKey, transcript, "INITIATOR"))}")
        require(readLine(socket) == OK) { "Receiver did not confirm secure pairing" }
        return Result(material.sessionKey, fingerprint(responderKey.encoded), sha256(transcript))
    }

    fun respond(socket: Socket, challenge: String): Result {
        validateChallenge(challenge)
        socket.soTimeout = 8_000
        val request = readLine(socket).split('|')
        require(request.size == 2 && request[0] == PAIR) { "Invalid pairing request" }
        val initiatorKey = decodePublicKey(request[1])
        val local = generateEphemeralKeyPair()
        val responderPublic = b64(local.public.encoded)
        val transcript = transcript(challenge, request[1], responderPublic)
        val material = derive(local.private, initiatorKey, challenge, transcript)
        writeLine(socket, "$PAIR|$responderPublic|${b64(proof(material.confirmationKey, transcript, "RESPONDER"))}")
        val finish = readLine(socket).split('|')
        require(finish.size == 2 && finish[0] == FINISH) { "Missing initiator key confirmation" }
        val expected = proof(material.confirmationKey, transcript, "INITIATOR")
        require(MessageDigest.isEqual(expected, unb64(finish[1]))) {
            "Initiator authentication failed. Pairing challenge did not match."
        }
        writeLine(socket, OK)
        return Result(material.sessionKey, fingerprint(initiatorKey.encoded), sha256(transcript))
    }

    private fun generateEphemeralKeyPair() = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"), random)
        generateKeyPair()
    }

    private fun decodePublicKey(encoded: String): PublicKey {
        require(encoded.length in 80..512) { "Invalid public key length" }
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(encoded)))
        val ec = key as? java.security.interfaces.ECPublicKey
            ?: throw IllegalArgumentException("Invalid EC public key")
        require(ec.params.curve.field.fieldSize == 256 && ec.params.order.bitLength() == 256) {
            "Only P-256 pairing keys are accepted"
        }
        return key
    }

    private fun derive(
        privateKey: java.security.PrivateKey, peerPublicKey: PublicKey,
        challenge: String, transcript: ByteArray
    ): KeyMaterial {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        val shared = agreement.generateSecret()
        try {
            val salt = sha256(unb64(challenge))
            val info = "KNIX secure pairing v1".toByteArray(StandardCharsets.US_ASCII) + transcript
            val expanded = hkdfSha256(shared, salt, info, 64)
            return KeyMaterial(expanded.copyOfRange(0, 32), expanded.copyOfRange(32, 64))
        } finally { shared.fill(0) }
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = extract.doFinal(ikm)
        try {
            val output = ByteArrayOutputStream()
            var previous = ByteArray(0)
            var counter = 1
            while (output.size() < length) {
                val expand = Mac.getInstance("HmacSHA256")
                expand.init(SecretKeySpec(prk, "HmacSHA256"))
                expand.update(previous)
                expand.update(info)
                expand.update(counter.toByte())
                previous = expand.doFinal()
                output.write(previous)
                counter++
            }
            return output.toByteArray().copyOf(length)
        } finally { prk.fill(0) }
    }

    private fun transcript(challenge: String, initiator: String, responder: String) =
        "$PAIR\n$challenge\n$initiator\n$responder".toByteArray(StandardCharsets.US_ASCII)

    private fun proof(key: ByteArray, transcript: ByteArray, role: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(transcript)
        mac.update("\n$role".toByteArray(StandardCharsets.US_ASCII))
        return mac.doFinal()
    }

    private fun writeLine(socket: Socket, value: String) {
        val bytes = (value + "\n").toByteArray(StandardCharsets.US_ASCII)
        require(bytes.size <= MAX_LINE_BYTES) { "Pairing message too large" }
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    private fun readLine(socket: Socket): String {
        val output = ByteArrayOutputStream()
        val input = socket.getInputStream()
        while (output.size() <= MAX_LINE_BYTES) {
            val value = input.read()
            if (value == -1) throw IllegalStateException("Peer closed during pairing")
            if (value == 10) return output.toString(StandardCharsets.US_ASCII.name())
            if (value !in 0..127 || value == 13) throw IllegalArgumentException("Invalid pairing message encoding")
            output.write(value)
        }
        throw IllegalArgumentException("Pairing message too large")
    }

    private fun validateChallenge(challenge: String) {
        require(challenge.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "Invalid QR challenge" }
        require(unb64(challenge).size == 24) { "Unexpected QR challenge size" }
    }

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unb64(value: String) = Base64.getUrlDecoder().decode(value)
    private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
    private fun fingerprint(bytes: ByteArray) =
        sha256(bytes).take(8).joinToString(":") { "%02X".format(it) }
}
