package dev.shareme

import kotlinx.coroutines.flow.StateFlow

enum class Phase { Preparing, Sending, Receiving, Complete, Failed }
data class Transfer(
    val id: String,
    val name: String,
    val total: Long,
    val completed: Long = 0,
    val phase: Phase = Phase.Preparing,
    val detail: String = "",
)
data class ShareState(
    val deviceName: String,
    val connectionCode: String = "",
    val addresses: List<String> = emptyList(),
    val peer: String = "",
    val destination: String = "",
    val ready: Boolean = false,
    val busy: Boolean = false,
    val message: String = "Starting secure receiver…",
    val transfers: List<Transfer> = emptyList(),
    val qr: List<List<Boolean>> = emptyList(),
)
interface ShareController {
    val state: StateFlow<ShareState>
    fun connect(code: String)
    fun disconnect()
    fun cancel()
}

/** Validate on every receiver, regardless of the sender's platform. */
fun validateRelativePath(path: String): String {
    require(path.isNotBlank() && path.length <= 2048) { "Invalid file path" }
    require(!path.startsWith('/') && !path.contains('\\')) { "Absolute paths are not allowed" }
    val parts = path.split('/')
    require(parts.size <= 32) { "Folder nesting exceeds 32 levels" }
    require(parts.none { it.isBlank() || it == "." || it == ".." }) { "Invalid folder path" }
    require(parts.all { part ->
        part.length <= 240 && part.none { it.code < 32 || it in ":*?\"<>|" } &&
            !part.endsWith('.') && !part.endsWith(' ') &&
            part.substringBefore('.').uppercase() !in setOf("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9")
    }) { "File name is not portable across supported devices" }
    return path
}

fun readableSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${bytes / (1024L * 1024 * 1024)} GB"
}
