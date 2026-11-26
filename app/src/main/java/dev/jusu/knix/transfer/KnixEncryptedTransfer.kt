package dev.jusu.knix.transfer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Single-file, one-session encrypted transfer protocol.
 *
 * A fresh ECDH session key must be used for each connection. Both peers run
 * sendFile/receiveFile concurrently. Receiver persists bytes in a .knix.part
 * file and advertises its offset; sender verifies the partial-prefix digest
 * before resuming. Final SHA-256 is checked before the destination is committed.
 */
object KnixEncryptedTransfer {
    const val CHUNK_SIZE = 1024 * 1024
    private const val MAGIC = "KNIX-XFER/1"
    private const val TAG_BYTES = 16
    private const val MAX_FRAME = CHUNK_SIZE + 4096
    private const val TYPE_READY: Byte = 1
    private const val TYPE_META: Byte = 2
    private const val TYPE_CHUNK: Byte = 3
    private const val TYPE_END: Byte = 4
    private const val TYPE_ACK: Byte = 5
    private const val TYPE_ERROR: Byte = 6

    data class Progress(val transferred: Long, val total: Long)
    data class Result(val file: File, val bytes: Long, val sha256: String)

    private class SecureChannel(
        socket: Socket,
        private val key: ByteArray,
        private val sendDirection: Byte
    ) {
        private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        private var sendSequence = 0L
        private var receiveSequence = 0L
        private val receiveDirection = if (sendDirection == 0.toByte()) 1.toByte() else 0.toByte()

        fun send(type: Byte, payload: ByteArray = ByteArray(0)) {
            val plain = byteArrayOf(type) + payload
            val encryptedLength = plain.size + TAG_BYTES
            require(encryptedLength in TAG_BYTES + 1..MAX_FRAME) { "Transfer frame exceeds limit" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce(sendDirection, sendSequence)))
            cipher.updateAAD(aad(sendDirection, sendSequence))
            val encrypted = cipher.doFinal(plain)
            output.writeInt(encrypted.size)
            output.write(encrypted)
            output.flush()
            sendSequence++
        }

        fun receive(expectedType: Byte): ByteArray {
            val length = input.readInt()
            require(length in TAG_BYTES + 1..MAX_FRAME) { "Invalid encrypted frame length" }
            val encrypted = ByteArray(length)
            input.readFully(encrypted)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce(receiveDirection, receiveSequence)))
            cipher.updateAAD(aad(receiveDirection, receiveSequence))
            val plain = cipher.doFinal(encrypted)
            receiveSequence++
            require(plain.isNotEmpty()) { "Empty transfer frame" }
            val type = plain[0]
            val payload = plain.copyOfRange(1, plain.size)
            if (type == TYPE_ERROR) throw IllegalStateException(
                payload.toString(StandardCharsets.UTF_8).take(512))
            require(type == expectedType) { "Unexpected transfer frame type: $type" }
            return payload
        }

        private fun nonce(direction: Byte, sequence: Long): ByteArray =
            ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
                .putInt(if (direction == 0.toByte()) 0x4B4E4958 else 0x58494E4B)
                .putLong(sequence).array()

        private fun aad(direction: Byte, sequence: Long): ByteArray =
            "$MAGIC|$direction|$sequence".toByteArray(StandardCharsets.US_ASCII)
    }

    fun sendFile(
        socket: Socket,
        sessionKey: ByteArray,
        source: File,
        onProgress: (Progress) -> Unit = {}
    ): Result {
        require(sessionKey.size == 32) { "A 256-bit session key is required" }
        require(source.isFile && source.canRead()) { "Selected file cannot be read" }
        require(source.name.toByteArray(StandardCharsets.UTF_8).size <= 240) { "File name is too long" }
        val total = source.length()
        val fullDigest = digest(source)
        val channel = SecureChannel(socket, sessionKey, 0)
        val ready = DataInputStream(channel.receive(TYPE_READY).inputStream())
        val offset = ready.readLong()
        val prefixDigest = ByteArray(32).also(ready::readFully)
        require(ready.available() == 0 && offset in 0..total) { "Invalid resume request" }
        val actualPrefix = digestPrefix(source, offset)
        require(MessageDigest.isEqual(prefixDigest, actualPrefix)) {
            "Receiver partial file does not match the selected source; restart transfer"
        }
        val name = source.name.toByteArray(StandardCharsets.UTF_8)
        val meta = java.io.ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeUTF(source.name)
                out.writeLong(total)
                out.writeLong(offset)
                out.write(fullDigest)
                out.write(actualPrefix)
            }
        }.toByteArray()
        channel.send(TYPE_META, meta)

        FileInputStream(source).use { file ->
            skipFully(file, offset)
            var sent = offset
            val buffer = ByteArray(CHUNK_SIZE)
            while (sent < total) {
                val wanted = minOf(buffer.size.toLong(), total - sent).toInt()
                var count = 0
                while (count < wanted) {
                    val read = file.read(buffer, count, wanted - count)
                    if (read < 0) throw IllegalStateException("Source file changed during transfer")
                    count += read
                }
                channel.send(TYPE_CHUNK, buffer.copyOf(count))
                sent += count
                onProgress(Progress(sent, total))
            }
        }
        channel.send(TYPE_END)
        val ack = channel.receive(TYPE_ACK)
        require(MessageDigest.isEqual(ack, fullDigest)) { "Receiver integrity confirmation did not match" }
        return Result(source, total, fullDigest.hex())
    }

    fun receiveFile(
        socket: Socket,
        sessionKey: ByteArray,
        destination: File,
        onProgress: (Progress) -> Unit = {}
    ): Result {
        require(sessionKey.size == 32) { "A 256-bit session key is required" }
        require(!destination.exists() || destination.isFile) { "Destination is not a file" }
        destination.parentFile?.let { require(it.exists() || it.mkdirs()) { "Cannot create destination directory" } }
        val partial = File(destination.parentFile, destination.name + ".knix.part")
        require(!destination.exists()) { "Destination already exists" }
        require(!partial.exists() || partial.isFile) { "Partial destination is invalid" }
        val offset = if (partial.exists()) partial.length() else 0L
        val prefixDigest = digest(partial.takeIf { it.exists() })
        val channel = SecureChannel(socket, sessionKey, 1)
        val ready = java.io.ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out -> out.writeLong(offset); out.write(prefixDigest) }
        }.toByteArray()
        channel.send(TYPE_READY, ready)
        val meta = DataInputStream(channel.receive(TYPE_META).inputStream())
        val name = meta.readUTF()
        val total = meta.readLong()
        val senderOffset = meta.readLong()
        val expectedDigest = ByteArray(32).also(meta::readFully)
        val expectedPrefix = ByteArray(32).also(meta::readFully)
        require(meta.available() == 0 && name.isNotBlank() && name.length <= 240) { "Invalid file metadata" }
        require(File(name).name == name && !name.contains('/') && !name.contains('\\\\')) { "Unsafe file name" }
        require(total >= 0 && senderOffset == offset && offset <= total) { "Invalid transfer size or resume offset" }
        require(MessageDigest.isEqual(prefixDigest, expectedPrefix)) { "Partial file digest mismatch" }

        RandomAccessFile(partial, "rw").use { output ->
            output.seek(offset)
            var received = offset
            while (received < total) {
                val chunk = channel.receive(TYPE_CHUNK)
                require(chunk.isNotEmpty() && chunk.size <= CHUNK_SIZE &&
                    chunk.size.toLong() <= total - received) { "Invalid file chunk" }
                output.write(chunk)
                received += chunk.size
                onProgress(Progress(received, total))
            }
            require(channel.receive(TYPE_END).isEmpty()) { "Unexpected end frame payload" }
            output.fd.sync()
        }
        require(partial.length() == total) { "Received file size mismatch" }
        val actualDigest = digest(partial)
        require(MessageDigest.isEqual(actualDigest, expectedDigest)) { "File SHA-256 verification failed" }
        if (!partial.renameTo(destination)) {
            FileInputStream(partial).use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output); output.fd.sync() }
            }
            require(partial.delete()) { "Could not clean up partial transfer" }
        }
        channel.send(TYPE_ACK, actualDigest)
        return Result(destination, total, actualDigest.hex())
    }

    private fun digest(file: File?): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        if (file != null && file.exists()) FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        }
        return md.digest()
    }

    private fun digestPrefix(file: File, length: Long): ByteArray {
        require(length in 0..file.length())
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) throw IllegalStateException("Source shortened while hashing")
                md.update(buffer, 0, n)
                remaining -= n
            }
        }
        return md.digest()
    }

    private fun skipFully(input: FileInputStream, amount: Long) {
        var remaining = amount
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) { if (input.read() < 0) throw IllegalStateException("Cannot resume source"); remaining-- }
            else remaining -= skipped
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
