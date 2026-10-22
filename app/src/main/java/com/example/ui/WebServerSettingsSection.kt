package com.example.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.server.WebServer

/** Settings: open the library from a browser on the same Wi‑Fi. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebServerSettingsSection() {
    val context = LocalContext.current
    val grey = Color(0xFF3F3F46)
    val running by WebServer.running
    val url by WebServer.url
    val addresses by WebServer.addresses
    LaunchedEffect(running) {
        while (running) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { WebServer.refreshAddresses() }; kotlinx.coroutines.delay(3000) }
    }
    val pin by WebServer.pin
    val error by WebServer.error
    val browsers by WebServer.activeBrowsers
    var idle by remember { mutableIntStateOf(WebServer.idleMinutes(context)) }
    var note by remember { mutableStateOf("") }

    val switch = rememberServerSwitch()
    fun switchOn() = switch(true)
    LaunchedEffect(Unit) { ScreenAwake.load(context) }
    val keepScreen by ScreenAwake.mode
    val stayAwake by WebServer.stayAwake

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, Color.Black, RoundedCornerShape(4.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Library in the browser",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                fontWeight = FontWeight.Bold, color = Color.Black
            )
            Text(
                "Open your library from a computer or phone on the same Wi‑Fi: see every book and article, add files by " +
                    "dragging them in, read PDFs and EPUBs, make and edit notes, and download books, notes and exports. " +
                    "Changes appear in the app at once.",
                style = MaterialTheme.typography.bodySmall, color = grey
            )
            if (running) {
                Column(
                    Modifier.fillMaxWidth().border(1.dp, Color.Black, RoundedCornerShape(2.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (addresses.isEmpty()) {
                                Text("Open in a browser", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = grey)
                                Text(url, fontFamily = FontFamily.Monospace, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                            addresses.forEach { a ->
                                Text(if (a.label == "Short name") "Open in a browser (computer)" else "Or by number · ${a.label}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = grey)
                                Text(a.url, fontFamily = FontFamily.Monospace, fontSize = if (addresses.size > 1) 20.sp else 24.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            }
                            Text("PIN", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = grey)
                            Text(pin.chunked(3).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.Black, letterSpacing = 4.sp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            QrCodeImage(url, 150.dp, Modifier.border(1.dp, Color.Black))
                            Text("Scan with a phone", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = grey)
                        }
                    }
                    Text(
                        if (browsers > 0) "In use by $browsers browser${if (browsers == 1) "" else "s"}." else "A browser asks for the PIN once and then stays signed in for 30 days.",
                        fontSize = 12.sp, color = grey
                    )
                }
            }
            if (error.isNotBlank()) Text(error, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Black)
            // The short name (http://<name>.local:8080) that works on any Wi‑Fi without typing numbers.
            val savedName by WebServer.hostName
            var nameInput by remember(savedName) { mutableStateOf(savedName) }
            Text("Short name", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.Black)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("http://", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = grey)
                OutlinedTextField(
                    value = nameInput, onValueChange = { nameInput = it.take(40) }, singleLine = true,
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(4.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Black, unfocusedBorderColor = Color.Black, cursorColor = Color.Black)
                )
                Text(".local:8080", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = grey)
                InkButton("Save", enabled = WebServer.cleanHostName(nameInput) != savedName, onClick = {
                    WebServer.setHostName(context, nameInput); nameInput = WebServer.hostName.value
                })
            }
            Text(
                "Works on any Wi‑Fi from Windows 10/11, Mac, iPhone and Linux, as long as the computer is on the same network. " +
                    "If it doesn't open, use the number address above, or mark the network as Private in Windows (Settings → Network → Wi‑Fi → the network → Private). " +
                    "Android phones usually need the number address or the QR code.",
                style = MaterialTheme.typography.bodySmall, color = grey
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!running) InkButton("Switch on", icon = Icons.Default.Language, filled = true, onClick = { switchOn() })
                else InkButton("Switch off", icon = Icons.Default.PowerSettingsNew, filled = true, onClick = { WebServer.stop(context) })
                InkButton("Sign out all browsers", onClick = {
                    WebServer.forgetBrowsers(context); note = "Every browser must enter the PIN again."
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Server stays awake", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.Black)
                    Text("Keeps answering the browser while the tablet's screen is off (uses a little more battery).", style = MaterialTheme.typography.bodySmall, color = grey)
                }
                Switch(checked = stayAwake, onCheckedChange = { setServerStayAwake(context, it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color.Black, uncheckedThumbColor = Color.Black, uncheckedTrackColor = Color.White, uncheckedBorderColor = Color.Black))
            }
            Text("Keep the screen on", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.Black)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(ScreenAwake.OFF to "Off", ScreenAwake.WITH_SERVER to "While the library is open in a browser", ScreenAwake.ALWAYS to "Always, while the app is open").forEach { (v, label) ->
                    InkChip(label, keepScreen == v, { ScreenAwake.set(context, v) })
                }
            }
            Text("Switch off by itself when unused for", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.Black)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 180 to "3 hours", 0 to "Never").forEach { (m, label) ->
                    InkChip(label, idle == m, { idle = m; WebServer.setIdleMinutes(context, m) })
                }
            }
            Text(
                "Runs in the background with a notification until you switch it off. The tablet and the computer must be on " +
                    "the same network. On ONYX tablets, keep the app from being frozen (App management → Marginalia → no freeze) " +
                    "if the page stops answering when the screen sleeps.",
                style = MaterialTheme.typography.bodySmall, color = grey
            )
            if (note.isNotBlank()) Text(note, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Black)
        }
    }
}
