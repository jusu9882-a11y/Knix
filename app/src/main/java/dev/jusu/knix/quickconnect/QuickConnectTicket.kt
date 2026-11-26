package dev.jusu.knix.quickconnect

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * Short-lived, versioned QR ticket. It contains a one-time challenge, never a private key.
 * Tickets are only created once a real listening endpoint exists.
 */
data class QuickConnectTicket(
    val host: String,
    val port: Int,
    val challenge: String,
    val expiresAtEpochSeconds: Long,
    val deviceName: String
) {
    fun encode(): String {
        require(host.isNotBlank() && host.length <= 253 && host.matches(Regex("[A-Za-z0-9:.\\-]+"))) {
            "Invalid receiver host"
        }
        require(port in 1..65535)
        require(challenge.matches(Regex("[A-Za-z0-9_-]{32,128}")))
        require(deviceName.length in 1..64 && deviceName.none { it.isISOControl() })
        require(expiresAtEpochSeconds > 0)
        return "knix://connect?v=1&host=${enc(host)}&port=$port&challenge=$challenge&expires=$expiresAtEpochSeconds&name=${enc(deviceName)}"
    }

    fun isExpired(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        expiresAtEpochSeconds <= nowEpochSeconds

    companion object {
        private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

        fun create(
            host: String,
            port: Int,
            deviceName: String,
            lifetimeSeconds: Long = 120,
            nowEpochSeconds: Long = System.currentTimeMillis() / 1000
        ): QuickConnectTicket {
            require(lifetimeSeconds in 15..300) { "QR lifetime must be between 15 and 300 seconds" }
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            val challenge = android.util.Base64.encodeToString(
                bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
            return QuickConnectTicket(host, port, challenge, nowEpochSeconds + lifetimeSeconds, deviceName)
                .also { it.encode() }
        }

        fun parse(raw: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1000): QuickConnectTicket {
            require(raw.length <= 2048) { "QR payload is too large" }
            val uri = URI(raw)
            require(uri.scheme == "knix" && uri.host == "connect") { "Not a Knix connection QR" }
            val pairs = uri.rawQuery.orEmpty().split('&').map { part ->
                val pair = part.split('=', limit = 2)
                require(pair.size == 2) { "Malformed QR field" }
                URLDecoder.decode(pair[0], StandardCharsets.UTF_8.name()) to
                    URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name())
            }
            require(pairs.map { it.first }.toSet().size == pairs.size) { "Duplicate QR fields" }
            val values = pairs.toMap()
            require(values.keys == setOf("v", "host", "port", "challenge", "expires", "name")) {
                "Missing or unexpected QR fields"
            }
            require(values["v"] == "1") { "Unsupported Knix QR version" }
            val ticket = QuickConnectTicket(
                host = values["host"] ?: error("Missing host"),
                port = values["port"]?.toIntOrNull() ?: error("Invalid port"),
                challenge = values["challenge"] ?: error("Missing challenge"),
                expiresAtEpochSeconds = values["expires"]?.toLongOrNull() ?: error("Invalid expiry"),
                deviceName = values["name"] ?: error("Missing device name")
            )
            ticket.encode()
            require(!ticket.isExpired(nowEpochSeconds)) { "This Knix QR has expired" }
            require(ticket.expiresAtEpochSeconds - nowEpochSeconds <= 300) { "QR expiry is outside the allowed window" }
            return ticket
        }
    }
}

object QuickConnectQr {
    fun render(payload: String, sizePx: Int = 768): Bitmap {
        require(sizePx in 256..2048)
        val matrix = QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8")
        )
        return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until sizePx) for (x in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
    }
}
