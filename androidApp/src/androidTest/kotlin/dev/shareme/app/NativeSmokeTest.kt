package dev.shareme.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.shareme.NativeTransferController
import dev.shareme.SendEntry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class NativeSmokeTest {
    @Test fun androidTlsPairingAndVerifiedFileTransfer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val temporary = File(context.cacheDir, "transfer-test-${UUID.randomUUID()}").apply { mkdirs() }
        val receiver = NativeTransferController("Android receiver", File(temporary, "received"), File(temporary, "receiver-state"))
        val sender = NativeTransferController("Android sender", File(temporary, "sent"), File(temporary, "sender-state"))
        fun await(check: () -> Boolean) {
            val end = System.currentTimeMillis() + 30_000
            while (!check()) {
                if (System.currentTimeMillis() > end) fail("Timeout: ${receiver.state.value.message} / ${sender.state.value.message}")
                Thread.sleep(30)
            }
        }
        try {
            receiver.start(); sender.start()
            await { receiver.state.value.ready && sender.state.value.ready }
            sender.connect(receiver.state.value.connectionCode.replace(Regex("//[^:]+:"), "//127.0.0.1:"))
            await { !sender.state.value.busy }
            assertEquals("Android receiver", sender.state.value.peer)
            assertEquals("Android sender", receiver.state.value.peer)
            val bytes = ByteArray(512 * 1024 + 7) { (it % 239).toByte() }
            sender.send(listOf(SendEntry("folder/payload.bin", bytes.size.toLong()) { bytes.inputStream() }))
            await { !sender.state.value.busy }
            assertTrue(sender.state.value.message, sender.state.value.message.startsWith("Transfer complete"))
            assertArrayEquals(bytes, File(temporary, "received/folder/payload.bin").readBytes())
        } finally { sender.close(); receiver.close(); temporary.deleteRecursively() }
    }
}
