package dev.shareme

import java.io.*
import java.net.InetSocketAddress
import java.nio.file.Files
import javax.net.ssl.SSLSocket
import kotlin.test.*

class TransferIntegrationTest {
    private lateinit var workspace: File
    private lateinit var sender: NativeTransferController
    private lateinit var receiver: NativeTransferController
    private lateinit var received: File

    @BeforeTest fun start() {
        workspace = Files.createTempDirectory("shareme-test-").toFile()
        received = File(workspace, "received")
        sender = NativeTransferController("Sender", File(workspace, "sender"), File(workspace, "sender-state"))
        receiver = NativeTransferController("Receiver", received, File(workspace, "receiver-state"))
        sender.start(); receiver.start()
        await { receiver.state.value.ready && sender.state.value.ready }
    }
    @AfterTest fun stop() {
        sender.close(); receiver.close()
        workspace.deleteRecursively()
    }
    private fun await(check: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!check()) { if (System.currentTimeMillis() > end) fail("Timed out. Sender: ${sender.state.value}; receiver: ${receiver.state.value}"); Thread.sleep(20) }
    }
    private fun link() = receiver.state.value.connectionCode.replace(Regex("//[^:]+:"), "//127.0.0.1:")
    private fun pair() { sender.connect(link()); await { !sender.state.value.busy }; assertEquals("Receiver", sender.state.value.peer) }
    private fun send(path: String, bytes: ByteArray) {
        sender.send(listOf(SendEntry(path, bytes.size.toLong()) { bytes.inputStream() }))
        await { !sender.state.value.busy }
        assertTrue(sender.state.value.message.startsWith("Transfer complete"), sender.state.value.message)
    }
    private fun raw(block: (DataInputStream, DataOutputStream) -> Unit) {
        val peer = Peer.parse(link())
        (pinnedContext(peer.fingerprint).socketFactory.createSocket() as SSLSocket).use { socket ->
            socket.soTimeout = 5000
            socket.connect(InetSocketAddress(peer.host, peer.port))
            socket.startHandshake()
            val input = DataInputStream(socket.inputStream.buffered())
            val output = DataOutputStream(socket.outputStream.buffered())
            output.writeUTF("SHAREME/1"); output.writeUTF(peer.token); output.flush()
            assertEquals("OK", input.readUTF())
            block(input, output)
        }
    }
    @Test fun sendsFoldersEmptyFilesAndBinaryDataBothWays() {
        pair()
        val data = ByteArray(1024 * 1024 + 29) { (it % 251).toByte() }
        sender.send(listOf(SendEntry("empty-folder", 0, true), SendEntry("photos/empty.txt", 0), SendEntry("photos/旅行.bin", data.size.toLong()) { data.inputStream() }))
        await { !sender.state.value.busy }
        assertTrue(File(received, "empty-folder").isDirectory)
        assertEquals(0, File(received, "photos/empty.txt").length())
        assertContentEquals(data, File(received, "photos/旅行.bin").readBytes())
        receiver.connect(sender.state.value.connectionCode.replace(Regex("//[^:]+:"), "//127.0.0.1:"))
        await { !receiver.state.value.busy }
        receiver.send(listOf(SendEntry("reply.txt", 2) { "OK".byteInputStream() }))
        await { !receiver.state.value.busy }
        assertEquals("OK", File(workspace, "sender/reply.txt").readText())
    }
    @Test fun resumesAfterSocketDisconnect() {
        pair()
        val bytes = ByteArray(512 * 1024) { (it % 239).toByte() }
        raw { input, output ->
            output.writeUTF("FILE"); output.writeUTF("resumable.bin"); output.writeLong(bytes.size.toLong()); output.writeUTF(digest(bytes)); output.flush()
            assertEquals("READY", input.readUTF()); assertEquals(0L, input.readLong())
            output.write(bytes, 0, 65536); output.flush()
        }
        await { receiver.state.value.transfers.any { it.phase == Phase.Failed } }
        val partial = File(workspace, "receiver-state/partials").listFiles()!!.single { it.extension == "part" }
        assertEquals(65536L, partial.length())
        assertFalse(File(received, "resumable.bin").exists())
        send("resumable.bin", bytes)
        assertContentEquals(bytes, File(received, "resumable.bin").readBytes())
        assertFalse(partial.exists())
    }
    @Test fun preservesExistingFilesAndMakesRetriesIdempotent() {
        pair()
        send("notes.txt", "original".toByteArray())
        send("notes.txt", "changed".toByteArray())
        send("notes.txt", "changed".toByteArray())
        assertEquals("original", File(received, "notes.txt").readText())
        assertEquals("changed", File(received, "notes (1).txt").readText())
        assertEquals(2, received.listFiles()!!.size)
    }
    @Test fun rejectsWrongPairingSecretAndPinnedCertificate() {
        sender.connect(link().substringBefore("token=") + "token=" + "0".repeat(32))
        await { !sender.state.value.busy }
        assertEquals("", sender.state.value.peer)
        sender.connect(link().replace(Regex("key=[a-f0-9]+"), "key=" + "0".repeat(64)))
        await { !sender.state.value.busy }
        assertEquals("", sender.state.value.peer)
    }
    @Test fun rejectsTraversalFromAuthenticatedPeer() {
        raw { input, output ->
            output.writeUTF("DIRECTORY"); output.writeUTF("../escape"); output.flush()
            assertTrue(input.readUTF().startsWith("Transfer rejected"))
        }
        assertFalse(File(workspace, "escape").exists())
    }
    @Test fun discardsCorruptPayload() {
        raw { input, output ->
            output.writeUTF("FILE"); output.writeUTF("bad.bin"); output.writeLong(3); output.writeUTF(digest("abc".toByteArray())); output.flush()
            assertEquals("READY", input.readUTF()); assertEquals(0L, input.readLong())
            output.write("xyz".toByteArray()); output.flush()
            assertTrue(input.readUTF().contains("Integrity check failed"))
        }
        assertFalse(File(received, "bad.bin").exists())
        assertTrue(File(workspace, "receiver-state/partials").listFiles()!!.none { it.extension == "part" })
    }
}
