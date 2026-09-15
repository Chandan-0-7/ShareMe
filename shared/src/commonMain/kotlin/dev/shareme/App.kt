package dev.shareme

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF21362C)
private val Moss = Color(0xFF547639)
private val Paper = Color(0xFFF5F6F0)

@Composable
fun ShareMeApp(
    controller: ShareController,
    chooseFiles: () -> Unit,
    chooseFolder: () -> Unit,
    openReceived: () -> Unit,
    copyCode: (String) -> Unit,
    scanCode: (() -> Unit)? = null,
) {
    val state by controller.state.collectAsState()
    var code by remember { mutableStateOf("") }
    var showConnectionCode by remember { mutableStateOf(false) }
    MaterialTheme(colors = lightColors(primary = Moss, onPrimary = Color.White, background = Paper, surface = Color.White, onSurface = Ink)) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            BoxWithConstraints {
                val wide = maxWidth > 760.dp
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = if (wide) 48.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 28.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("↗ ShareMe", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
                            Text(if (state.ready) "●  Ready to receive" else "○  Starting", color = Moss, fontSize = 12.sp, modifier = Modifier.padding(top = 9.dp))
                        }
                    }
                    item {
                        Column(Modifier.padding(vertical = 14.dp)) {
                            Text("YOUR FILES. YOUR NETWORK.", fontSize = 10.sp, letterSpacing = 2.sp, color = Moss)
                            Text("Between devices.\nWithout the detour.", fontSize = if (wide) 38.sp else 34.sp, lineHeight = if (wide) 43.sp else 39.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.padding(vertical = 12.dp))
                            Text("Direct, encrypted file transfers over the same Wi-Fi.", color = Color(0xFF758071))
                        }
                    }
                    item {
                        Panel {
                            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            Column(Modifier.weight(1f)) {
                            Text("01 / CONNECT", color = Moss, fontSize = 11.sp, letterSpacing = 1.sp)
                            Text(state.deviceName, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                            Text("Scan this code with ShareMe on your phone, or copy the connection link. Pairing enables transfers both ways.", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 10.dp))
                            if (!wide && state.qr.isNotEmpty()) QrCode(state.qr)
                            if (scanCode != null) Button(onClick = scanCode, enabled = state.ready && !state.busy) { Text("Scan other device’s QR") }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { copyCode(state.connectionCode) }, enabled = state.ready) { Text("Copy my link") }
                                TextButton(onClick = { showConnectionCode = !showConnectionCode }, enabled = state.ready) { Text(if (showConnectionCode) "Hide link" else "Show link") }
                            }
                            if (showConnectionCode) SelectionContainer { Text(state.connectionCode, fontSize = 11.sp, modifier = Modifier.padding(vertical = 10.dp)) }
                            if (state.addresses.isNotEmpty()) Text(state.addresses.joinToString("  ·  "), color = Color.Gray, fontSize = 11.sp)
                            OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Other device’s connection link") }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp), singleLine = true, enabled = !state.busy)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { controller.connect(code.trim()) }, enabled = code.isNotBlank() && !state.busy) { Text("Connect device ↗") }
                                if (state.peer.isNotEmpty()) TextButton(onClick = controller::disconnect, enabled = !state.busy) { Text("Disconnect") }
                            }
                            if (state.peer.isNotEmpty()) Text("Connected to ${state.peer}", color = Moss, fontSize = 12.sp)
                            }
                            if (wide && state.qr.isNotEmpty()) Column {
                                QrCode(state.qr)
                                Text("Scan to pair securely", fontSize = 11.sp, color = Moss, modifier = Modifier.padding(start = 12.dp))
                            }
                            }
                        }
                    }
                    item {
                        Panel {
                            Text("02 / TRANSFER", color = Moss, fontSize = 11.sp, letterSpacing = 1.sp)
                            Text("A little less emailing yourself.", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = chooseFiles, enabled = state.peer.isNotEmpty() && !state.busy) { Text("Send files ↑") }
                                OutlinedButton(onClick = chooseFolder, enabled = state.peer.isNotEmpty() && !state.busy) { Text("Send folder") }
                            }
                            TextButton(onClick = openReceived) { Text("Open / export received files ↗") }
                            Text("Saved to ${state.destination}", fontSize = 11.sp, color = Color.Gray)
                            if (state.busy) TextButton(onClick = controller::cancel) { Text("Cancel sending") }
                        }
                    }
                    item { Text(state.message, color = Ink, fontSize = 13.sp) }
                    if (state.transfers.isNotEmpty()) item { Text("TRANSFER ACTIVITY", fontSize = 11.sp, letterSpacing = 1.sp, color = Moss) }
                    items(state.transfers, key = { it.id }) { transfer ->
                        Panel {
                            Text(transfer.name, fontWeight = FontWeight.SemiBold)
                            Text("${transfer.phase} · ${readableSize(transfer.completed)} / ${readableSize(transfer.total)}", fontSize = 12.sp, color = if (transfer.phase == Phase.Failed) Color(0xFFB44535) else Moss, modifier = Modifier.padding(vertical = 8.dp))
                            if (transfer.phase == Phase.Sending || transfer.phase == Phase.Receiving) LinearProgressIndicator(progress = if (transfer.total == 0L) 0f else (transfer.completed.toDouble() / transfer.total).toFloat().coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                            if (transfer.detail.isNotBlank()) Text(transfer.detail, color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                    item { Text("ShareMe / Native preview · Keep the app open during transfers", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(vertical = 10.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), elevation = 0.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), content = content)
    }
}

@Composable
private fun QrCode(qr: List<List<Boolean>>) {
    Canvas(Modifier.size(200.dp).background(Color.White)) {
        val cell = size.width / qr.size
        qr.forEachIndexed { y, row -> row.forEachIndexed { x, dark ->
            if (dark) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + .2f, cell + .2f))
        } }
    }
}
