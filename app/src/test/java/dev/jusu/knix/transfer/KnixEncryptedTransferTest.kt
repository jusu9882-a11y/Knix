package dev.jusu.knix.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

class KnixEncryptedTransferTest {
    private fun transfer(sourceBytes: ByteArray, resumeBytes: ByteArray = ByteArray(0)) {
        val directory = createTempDir(prefix = "knix-transfer-test")
        try {
            val source = File(directory, "source.bin").apply { writeBytes(sourceBytes) }
            val destinationDirectory = File(directory, "received")
            destinationDirectory.mkdirs()
            val destination = File(destinationDirectory, source.name)
            val partial = File(destinationDirectory, ".knix.pending.part")
            if (resumeBytes.isNotEmpty()) partial.writeBytes(resumeBytes)
            val key = ByteArray(32) { (it * 13 + 7).toByte() }
            val server = ServerSocket(0)
            val receiverResult = AtomicReference<KnixEncryptedTransfer.Result>()
            val receiverFailure = AtomicReference<Throwable?>()
            val receiver = Thread {
                try {
                    server.accept().use { receiverResult.set(
                        KnixEncryptedTransfer.receiveFile(it, key, destinationDirectory)
                    ) }
                } catch (t: Throwable) { receiverFailure.set(t) }
            }
            receiver.start()
            val senderResult = Socket("127.0.0.1", server.localPort).use {
                KnixEncryptedTransfer.sendFile(it, key, source)
            }
            receiver.join(15_000)
            server.close()
            assertTrue("receiver must finish", !receiver.isAlive)
            receiverFailure.get()?.let { throw AssertionError("receiver failed", it) }
            assertArrayEquals(sourceBytes, destination.readBytes())
            assertEquals(sourceBytes.size.toLong(), senderResult.bytes)
            assertEquals(sourceBytes.size.toLong(), receiverResult.get().bytes)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(sourceBytes).joinToString("") { "%02x".format(it) },
                receiverResult.get().sha256)
            assertTrue("partial file should be committed/removed", !partial.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun transfersAndAuthenticatesMultiChunkFile() {
        val bytes = ByteArray(KnixEncryptedTransfer.CHUNK_SIZE * 2 + 137) { (it * 31).toByte() }
        transfer(bytes)
    }

    @Test fun resumesFromVerifiedPartialFile() {
        val bytes = ByteArray(KnixEncryptedTransfer.CHUNK_SIZE + 991) { (it * 17).toByte() }
        transfer(bytes, bytes.copyOfRange(0, 12_345))
    }

    @Test fun transfersEmptyFile() {
        transfer(ByteArray(0))
    }

    @Test fun transfersMultipleFilesOverOneAuthenticatedSession() {
        val directory = createTempDir(prefix = "knix-batch-test")
        try {
            val firstBytes = ByteArray(48_123) { (it * 11).toByte() }
            val secondBytes = ByteArray(1_234_567) { (it * 23).toByte() }
            val first = File(directory, "first.bin").apply { writeBytes(firstBytes) }
            val second = File(directory, "second.bin").apply { writeBytes(secondBytes) }
            val destination = File(directory, "received").apply { mkdirs() }
            val key = ByteArray(32) { (it * 7 + 3).toByte() }
            val server = ServerSocket(0)
            val receiverResults = AtomicReference<List<KnixEncryptedTransfer.Result>>()
            val receiverFailure = AtomicReference<Throwable?>()
            val receiver = Thread {
                try {
                    server.accept().use {
                        receiverResults.set(KnixEncryptedTransfer.receiveFiles(it, key, destination))
                    }
                } catch (t: Throwable) { receiverFailure.set(t) }
            }
            receiver.start()
            val senderResults = Socket("127.0.0.1", server.localPort).use {
                KnixEncryptedTransfer.sendFiles(it, key, listOf(first to first.name, second to second.name))
            }
            receiver.join(20_000)
            server.close()
            assertTrue("batch receiver must finish", !receiver.isAlive)
            receiverFailure.get()?.let { throw AssertionError("batch receiver failed", it) }
            assertEquals(2, senderResults.size)
            assertEquals(2, receiverResults.get().size)
            assertArrayEquals(firstBytes, File(destination, "first.bin").readBytes())
            assertArrayEquals(secondBytes, File(destination, "second.bin").readBytes())
            assertEquals(firstBytes.size.toLong(), receiverResults.get()[0].bytes)
            assertEquals(secondBytes.size.toLong(), receiverResults.get()[1].bytes)
        } finally { directory.deleteRecursively() }
    }
}
