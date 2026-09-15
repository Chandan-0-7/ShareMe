package dev.shareme.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import dev.shareme.NativeTransferController
import dev.shareme.SendEntry
import dev.shareme.ShareMeApp
import kotlinx.coroutines.*
import java.io.File
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class ShareMeApplication : Application() {
    lateinit var controller: NativeTransferController
    lateinit var received: File
    override fun onCreate() {
        super.onCreate()
        val storage = getExternalFilesDir(null) ?: filesDir
        received = File(storage, "Received").apply { mkdirs() }
        controller = NativeTransferController("${Build.MANUFACTURER} ${Build.MODEL}", received, File(storage, ".shareme")).also { it.start() }
    }
}

class MainActivity : ComponentActivity() {
    private val app get() = application as ShareMeApplication
    private val controller get() = app.controller
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scan = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { controller.connect(it) }
    }
    private val files = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) work.launch {
            runCatching { controller.send(uris.map { source(it) }) }.onFailure { controller.message("Could not select files: ${it.message}") }
        }
    }
    private val folder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) work.launch {
            runCatching {
                controller.message("Reading selected folder…")
                val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                val name = sourceName(document)
                val entries = mutableListOf<SendEntry>()
                collectFolder(uri, document, name, entries, 0)
                controller.send(entries)
            }.onFailure { controller.message("Could not read folder: ${it.message}") }
        }
    }
    private val export = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) work.launch {
            runCatching {
                val document = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                controller.message("Exporting received files…")
                exportFolder(app.received, document)
                controller.message("Received files exported to your selected folder.")
            }.onFailure { controller.message("Export failed: ${it.message}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShareMeApp(controller, chooseFiles = { files.launch(arrayOf("*/*")) }, chooseFolder = { folder.launch(null) }, openReceived = { export.launch(null) }, copyCode = { code ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ShareMe connection", code))
                controller.message("Link copied. Paste it into ShareMe on the other device.")
            }, scanCode = { scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the QR shown in ShareMe on your other device").setBeepEnabled(false).setOrientationLocked(false)) })
        }
    }

    private fun sourceName(uri: Uri): String = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: error("Cannot read selected name")

    private fun source(uri: Uri, path: String = sourceName(uri)): SendEntry {
        val size = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1
        } ?: -1
        require(size >= 0) { "Download $path to local storage before sending" }
        return SendEntry(path, size) { contentResolver.openInputStream(uri) ?: error("Cannot open $path") }
    }

    private fun collectFolder(tree: Uri, directory: Uri, path: String, entries: MutableList<SendEntry>, depth: Int) {
        require(depth < 32 && entries.size < 100_000) { "Folder is too deeply nested or contains too many entries" }
        entries.add(SendEntry(path, 0, directory = true))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(directory))
        contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val child = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                val childPath = "$path/${cursor.getString(1)}"
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) collectFolder(tree, child, childPath, entries, depth + 1)
                else entries.add(source(child, childPath))
            }
        } ?: error("Folder permission was lost")
    }

    private fun exportFolder(source: File, destination: Uri) {
        source.listFiles()?.forEach { file ->
            val document = DocumentsContract.createDocument(contentResolver, destination, if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream", file.name)
                ?: error("Cannot create ${file.name}")
            if (file.isDirectory) exportFolder(file, document)
            else file.inputStream().use { input -> (contentResolver.openOutputStream(document) ?: error("Cannot write ${file.name}")).use { output -> input.copyTo(output) } }
        }
    }
    override fun onDestroy() { work.cancel(); super.onDestroy() }
}
