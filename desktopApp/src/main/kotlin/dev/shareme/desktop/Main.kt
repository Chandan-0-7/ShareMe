package dev.shareme.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.shareme.NativeTransferController
import dev.shareme.ShareMeApp
import dev.shareme.entriesFromFiles
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.InetAddress
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

fun main() = application {
    val root = remember { File(System.getProperty("user.home"), "Downloads/ShareMe").apply { mkdirs() } }
    val controller = remember {
        NativeTransferController(
            runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("My computer"),
            root,
            File(root.parentFile, ".shareme"),
        ).also { it.start() }
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    Window(onCloseRequest = ::exitApplication, title = "ShareMe", state = rememberWindowState(width = 980.dp, height = 850.dp)) {
        fun choose(folder: Boolean) {
            SwingUtilities.invokeLater {
                val picker = JFileChooser().apply {
                    dialogTitle = if (folder) "Send a folder" else "Send files"
                    fileSelectionMode = if (folder) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY
                    isMultiSelectionEnabled = !folder
                }
                if (picker.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    // Walk folders off the UI thread, including large directory trees.
                    Thread {
                        runCatching {
                            val files = if (folder) listOf(picker.selectedFile) else picker.selectedFiles.toList()
                            controller.send(entriesFromFiles(files))
                        }.onFailure { controller.message("Cannot read selection: ${it.message}") }
                    }.start()
                }
            }
        }
        ShareMeApp(controller, chooseFiles = { choose(false) }, chooseFolder = { choose(true) }, openReceived = {
            runCatching { Desktop.getDesktop().open(root) }.onFailure { controller.message("Received files: ${root.absolutePath}") }
        }, copyCode = { code ->
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(code), null)
            controller.message("Connection link copied. Paste it into ShareMe on the other device.")
        })
    }
}
