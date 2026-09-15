package dev.shareme

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Re-openable sources allow hashing, retries and resume without loading whole files into memory. */
data class SendEntry(val path: String, val size: Long, val directory: Boolean = false, val open: () -> InputStream = { ByteArrayInputStream(byteArrayOf()) })

internal data class Peer(val host: String, val port: Int, val fingerprint: String, val token: String) {
    companion object {
        fun parse(code: String): Peer {
            val uri = URI(code)
            require(uri.scheme == "shareme" && uri.host != null && uri.port in 1..65535 && uri.userInfo == null) { "Paste a complete ShareMe connection link" }
            val values = (uri.rawQuery ?: "").split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val key = values["key"] ?: ""
            val token = values["token"] ?: ""
            require(key.matches(Regex("[a-f0-9]{64}")) && token.matches(Regex("[a-f0-9]{32}"))) { "Connection link is incomplete" }
            return Peer(uri.host, uri.port, key, token)
        }
    }
}

class NativeTransferController(deviceName: String, receiveFolder: File, privateFolder: File) : ShareController, AutoCloseable {
    private val root = receiveFolder.canonicalFile.apply { mkdirs() }
    private val partials = File(privateFolder, "partials").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(ShareState(deviceName = deviceName, destination = root.absolutePath))
    override val state: StateFlow<ShareState> = mutable
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.hex()
    private val closed = AtomicBoolean(false)
    private val sending = AtomicBoolean(false)
    private val receiving = Semaphore(1)
    private val connections = Semaphore(4)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var listener: SSLServerSocket? = null
    @Volatile private var peer: Peer? = null
    @Volatile private var sendSocket: Socket? = null
    @Volatile private var sendJob: Job? = null
    private val cancelled = AtomicBoolean(false)

    fun start() {
        scope.launch {
            try {
                require(root.isDirectory && partials.isDirectory) { "Cannot create receive folder" }
                val identity = TlsIdentity()
                val server = identity.context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
                listener = server
                if (closed.get()) { server.close(); return@launch }
                server.enabledProtocols = server.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
                val addresses = localAddresses()
                val host = addresses.firstOrNull() ?: "127.0.0.1"
                val link = "shareme://$host:${server.localPort}?key=${identity.fingerprint}&token=$token"
                val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 0, 0)
                val qr = List(matrix.height) { y -> List(matrix.width) { x -> matrix[x, y] } }
                mutable.update { it.copy(ready = true, connectionCode = link, qr = qr, addresses = addresses, message = if (addresses.isEmpty()) "No LAN address found. Connect to Wi-Fi and restart the app." else "Ready. Scan the QR on the other device to pair.") }
                while (!closed.get()) {
                    val socket = server.accept() as SSLSocket
                    if (!connections.tryAcquire()) { socket.close(); continue }
                    sockets.add(socket)
                    scope.launch {
                        try { receive(socket) }
                        catch (_: Exception) { /* Transfer-specific errors are reported after authentication. */ }
                        finally { sockets.remove(socket); socket.close(); connections.release() }
                    }
                }
            } catch (error: Exception) {
                if (!closed.get()) message("Receiver stopped: ${error.message}")
                mutable.update { it.copy(ready = false) }
            }
        }
    }

    override fun connect(code: String) {
        if (!sending.compareAndSet(false, true)) return
        mutable.update { it.copy(busy = true, message = "Checking device identity…") }
        scope.launch {
            try {
                val candidate = Peer.parse(code)
                open(candidate).use { socket ->
                    val input = DataInputStream(socket.inputStream.buffered())
                    val output = DataOutputStream(socket.outputStream.buffered())
                    authenticate(candidate, input, output)
                    output.writeUTF("PAIR")
                    output.writeUTF(state.value.deviceName.take(128))
                    output.writeUTF(state.value.connectionCode)
                    output.flush()
                    val name = input.readUTF()
                    peer = candidate
                    mutable.update { it.copy(peer = name, message = "Connected securely to $name. Choose files or a folder.") }
                }
            } catch (error: Exception) { message("Could not connect: ${error.message}") }
            finally { sending.set(false); mutable.update { it.copy(busy = false) }; sendSocket = null }
        }
    }

    override fun disconnect() { peer = null; mutable.update { it.copy(peer = "", message = "Disconnected. This device can still receive files.") } }
    fun message(text: String) { mutable.update { it.copy(message = text) } }

    fun send(entries: List<SendEntry>) {
        val destination = peer ?: return message("Connect a device first.")
        if (entries.isEmpty()) return message("No files selected.")
        if (!sending.compareAndSet(false, true)) return
        cancelled.set(false)
        mutable.update { it.copy(busy = true) }
        sendJob = scope.launch {
            try {
                for (entry in entries) {
                    ensureActive()
                    val id = UUID.randomUUID().toString()
                    activity(Transfer(id, entry.path, entry.size))
                    try {
                        validateRelativePath(entry.path)
                        require(entry.size >= 0) { "Unknown file size. Save a local copy before sending." }
                        val hash = if (entry.directory) "" else entry.open().use { sha256(it) { checkCancelled() } }
                        var completed = false
                        for (attempt in 0..2) {
                            checkCancelled()
                            try {
                                transmit(destination, entry, hash, id)
                                completed = true
                                break
                            } catch (error: IOException) {
                                if (attempt == 2 || cancelled.get()) throw error
                                activity(Transfer(id, entry.path, entry.size, phase = Phase.Preparing, detail = "Connection interrupted. Resuming, attempt ${attempt + 2}/3…"))
                                delay(1000L * (attempt + 1))
                            }
                        }
                        check(completed)
                        activity(Transfer(id, entry.path, entry.size, entry.size, Phase.Complete, "Receiver verified SHA-256"))
                    } catch (error: Exception) {
                        activity(Transfer(id, entry.path, entry.size, phase = Phase.Failed, detail = if (cancelled.get()) "Cancelled. Send again to resume." else error.message.orEmpty()))
                        throw error
                    }
                }
                message("Transfer complete. ${entries.size} item(s) delivered.")
            } catch (error: Exception) { message(if (cancelled.get()) "Sending cancelled. Partial files can resume on the next send." else "Transfer stopped: ${error.message}") }
            finally { sending.set(false); sendSocket = null; mutable.update { it.copy(busy = false) } }
        }
    }

    private fun transmit(destination: Peer, entry: SendEntry, hash: String, id: String) {
        open(destination).use { socket ->
            val input = DataInputStream(socket.inputStream.buffered())
            val output = DataOutputStream(socket.outputStream.buffered())
            authenticate(destination, input, output)
            output.writeUTF(if (entry.directory) "DIRECTORY" else "FILE")
            output.writeUTF(entry.path)
            if (entry.directory) {
                output.flush()
                require(input.readUTF() == "OK") { "Receiver could not create folder" }
                return
            }
            output.writeLong(entry.size); output.writeUTF(hash); output.flush()
            val response = input.readUTF()
            require(response == "READY") { response }
            val offset = input.readLong()
            require(offset in 0..entry.size) { "Invalid resume offset" }
            entry.open().use { source ->
                var skip = offset
                while (skip > 0) {
                    checkCancelled()
                    val skipped = source.skip(skip)
                    if (skipped > 0) skip -= skipped else { require(source.read() != -1) { "Source changed" }; skip-- }
                }
                val buffer = ByteArray(256 * 1024)
                var position = offset
                var lastUpdate = 0L
                while (position < entry.size) {
                    checkCancelled()
                    val count = source.read(buffer, 0, minOf(buffer.size.toLong(), entry.size - position).toInt())
                    require(count > 0) { "Source file changed while sending" }
                    output.write(buffer, 0, count)
                    position += count
                    if (System.currentTimeMillis() - lastUpdate > 100) {
                        activity(Transfer(id, entry.path, entry.size, position, Phase.Sending))
                        lastUpdate = System.currentTimeMillis()
                    }
                }
            }
            output.flush()
            // A large file can take longer than the network idle timeout to verify on a slow device.
            socket.soTimeout = 10 * 60 * 1000
            require(input.readUTF() == "OK") { "Receiver could not verify the file. Send it again." }
        }
    }

    private fun open(destination: Peer): SSLSocket {
        val socket = pinnedContext(destination.fingerprint).socketFactory.createSocket() as SSLSocket
        sendSocket = socket
        socket.soTimeout = 30_000
        socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        try {
            socket.connect(InetSocketAddress(destination.host, destination.port), 10_000)
            socket.startHandshake()
            return socket
        } catch (error: Exception) { socket.close(); throw error }
    }

    private fun authenticate(destination: Peer, input: DataInputStream, output: DataOutputStream) {
        output.writeUTF("SHAREME/1"); output.writeUTF(destination.token); output.flush()
        require(input.readUTF() == "OK") { "Pairing expired. Copy a new link from the receiving device." }
    }

    private fun receive(socket: SSLSocket) {
        socket.soTimeout = 30_000
        val input = DataInputStream(socket.inputStream.buffered())
        val output = DataOutputStream(socket.outputStream.buffered())
        if (input.readUTF() != "SHAREME/1" || !MessageDigest.isEqual(input.readUTF().toByteArray(), token.toByteArray())) {
            output.writeUTF("Pairing rejected"); output.flush(); return
        }
        output.writeUTF("OK"); output.flush()
        val command = input.readUTF()
        if (command == "PING") { output.writeUTF(state.value.deviceName.take(128)); output.flush(); return }
        if (command == "PAIR") {
            val name = input.readUTF().take(128)
            val offered = Peer.parse(input.readUTF())
            // Use the actual connection address for reverse transfers, including multihomed devices.
            peer = offered.copy(host = socket.inetAddress.hostAddress ?: offered.host)
            mutable.update { it.copy(peer = name, message = "Paired with $name. You can now send files both ways.") }
            output.writeUTF(state.value.deviceName.take(128)); output.flush(); return
        }
        if (!receiving.tryAcquire()) { output.writeUTF("Receiver is busy. Try again shortly."); output.flush(); return }
        val id = UUID.randomUUID().toString()
        var path = "Incoming file"
        var total = 0L
        try {
            require(command == "FILE" || command == "DIRECTORY") { "Unsupported command" }
            path = validateRelativePath(input.readUTF())
            val target = safeFile(path)
            if (command == "DIRECTORY") {
                require(target.mkdirs() || target.isDirectory) { "Cannot create folder" }
                output.writeUTF("OK"); output.flush(); return
            }
            total = input.readLong()
            val expectedHash = input.readUTF()
            require(total >= 0 && expectedHash.matches(Regex("[a-f0-9]{64}"))) { "Invalid file metadata" }
            val key = digest("$path\n$total\n$expectedHash".toByteArray())
            val partial = File(partials, "$key.part")
            val receipt = File(partials, "$key.done")
            if (receipt.isFile) {
                val previous = runCatching { safeFile(receipt.readText()) }.getOrNull()
                if (previous != null && previous.isFile && previous.length() == total && previous.inputStream().use { sha256(it) } == expectedHash) {
                    output.writeUTF("READY"); output.writeLong(total); output.writeUTF("OK"); output.flush()
                    activity(Transfer(id, path, total, total, Phase.Complete, "Already received and verified"))
                    return
                }
            }
            if (partial.length() > total) require(partial.delete())
            require(total - partial.length() < partials.usableSpace) { "Not enough storage space" }
            val parent = requireNotNull(target.parentFile)
            require(parent.mkdirs() || parent.isDirectory) { "Cannot create destination folder" }
            var position = partial.length()
            output.writeUTF("READY"); output.writeLong(position); output.flush()
            activity(Transfer(id, path, total, position, Phase.Receiving))
            FileOutputStream(partial, true).use { stream ->
                val buffer = ByteArray(256 * 1024)
                var lastUpdate = 0L
                while (position < total) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), total - position).toInt())
                    if (count <= 0) throw EOFException("Connection interrupted. Waiting for sender to resume.")
                    stream.write(buffer, 0, count)
                    position += count
                    if (System.currentTimeMillis() - lastUpdate > 100) {
                        activity(Transfer(id, path, total, position, Phase.Receiving))
                        lastUpdate = System.currentTimeMillis()
                    }
                }
                stream.fd.sync()
            }
            if (partial.inputStream().use { sha256(it) } != expectedHash) {
                partial.delete()
                error("Integrity check failed. The partial file was discarded; send it again.")
            }
            val destination = uniqueTarget(target)
            // Same filesystem storage is supplied by both launchers; rename makes completed files appear atomically.
            java.nio.file.Files.move(partial.toPath(), destination.toPath())
            receipt.writeText(destination.relativeTo(root).invariantSeparatorsPath)
            activity(Transfer(id, path, total, total, Phase.Complete, "SHA-256 verified · ${destination.name}"))
            output.writeUTF("OK"); output.flush()
        } catch (error: Exception) {
            activity(Transfer(id, path, total, phase = Phase.Failed, detail = error.message.orEmpty()))
            runCatching { output.writeUTF("Transfer rejected: ${error.message}".take(1024)); output.flush() }
        } finally { receiving.release() }
    }

    private fun safeFile(path: String): File {
        validateRelativePath(path)
        val file = File(root, path).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "Path escapes receive folder" }
        return file
    }

    private fun uniqueTarget(target: File): File {
        var candidate = target
        var suffix = 1
        while (candidate.exists()) {
            val extension = target.extension.let { if (it.isBlank()) "" else ".$it" }
            candidate = File(target.parentFile, "${target.nameWithoutExtension} (${suffix++})$extension")
        }
        return candidate
    }

    private fun checkCancelled() { if (cancelled.get() || closed.get()) throw IOException("Sending cancelled") }
    override fun cancel() { cancelled.set(true); runCatching { sendSocket?.close() } }
    private fun activity(transfer: Transfer) {
        mutable.update { state -> state.copy(transfers = (listOf(transfer) + state.transfers.filterNot { it.id == transfer.id }).take(100)) }
    }
    override fun close() {
        closed.set(true); cancel(); listener?.close(); sockets.forEach { runCatching { it.close() } }; scope.cancel()
    }
}

internal fun sha256(input: InputStream, checkpoint: () -> Unit = {}): String {
    val hash = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(256 * 1024)
    while (true) {
        checkpoint()
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) hash.update(buffer, 0, count)
    }
    return hash.digest().hex()
}

private fun localAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        .sortedByDescending { it.isSiteLocalAddress }.map { it.hostAddress.orEmpty() }
}.getOrDefault(emptyList())

fun entriesFromFiles(files: List<File>): List<SendEntry> = files.flatMap { selection ->
    require(!java.nio.file.Files.isSymbolicLink(selection.toPath())) { "Symbolic links cannot be sent" }
    val base = requireNotNull(selection.canonicalFile.parentFile) { "Select a folder inside the drive, rather than the drive root" }
    (if (selection.isDirectory) selection.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.toList() else listOf(selection))
        .filterNot { java.nio.file.Files.isSymbolicLink(it.toPath()) }
        .map { file -> SendEntry(file.relativeTo(base).invariantSeparatorsPath, if (file.isDirectory) 0 else file.length(), file.isDirectory) { file.inputStream() } }
}
