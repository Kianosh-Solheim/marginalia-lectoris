package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.reader.TabletLink
import com.example.ui.theme.Ink
import kotlinx.coroutines.launch

/**
 * Settings → Sync & web view → Connect to a tablet. Used on a phone: pair with a Marginalia tablet by
 * scanning the QR code it shows (Settings → Library in the browser → Pair a phone), then download its books
 * and audiobooks here; listening places sync whenever both are on the same Wi‑Fi.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TabletLinkSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val grey = Color(0xFF3F3F46)
    LaunchedEffect(Unit) { TabletLink.init(context) }
    val paired by TabletLink.paired
    val status by TabletLink.status
    val busy by TabletLink.busy
    var message by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var showBooks by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    fun pairWith(offer: TabletLink.Offer) {
        working = true; message = "Pairing…"
        scope.launch {
            val err = TabletLink.pair(context, offer)
            working = false
            message = err ?: ""
            if (err == null) TabletLink.syncSoon(context, 200)
        }
    }

    fun scan() {
        val scanner = com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(
            context,
            com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
        )
        scanner.startScan()
            .addOnSuccessListener { b ->
                val raw = b.rawValue.orEmpty().trim()
                val offer = TabletLink.parse(raw)
                when {
                    offer != null -> {
                        // Show what was read, so it can be checked (and Connect pressed again if needed).
                        address = offer.urls.first().removePrefix("http://").removePrefix("https://").trimEnd('/')
                        code = offer.code
                        pairWith(offer)
                    }
                    // The browser address's QR code (beside the PIN): keep the address, the code still has to come from Pair a phone.
                    raw.startsWith("http://") || raw.startsWith("https://") -> {
                        address = raw.removePrefix("http://").removePrefix("https://").trimEnd('/')
                        message = "That was the tablet's browser address (filled in below). Now press Pair a phone on the tablet, under Phones further down in the same settings, and scan that code instead, or type its 8-letter code below."
                    }
                    else -> message = "That QR code is not a Marginalia pairing code. On the tablet: Settings → Library in the browser → Phones → Pair a phone."
                }
            }
            .addOnFailureListener { message = "The scanner could not start here (it needs Google Play services). Type the address and code instead." }
    }

    Box(Modifier.fillMaxWidth().border(1.5.dp, Color.Black, RoundedCornerShape(4.dp)).padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connect to a tablet", style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif), fontWeight = FontWeight.Bold, color = Color.Black)
            val link = remember(paired) { TabletLink.link(context) }
            if (!paired || link == null) {
                Text(
                    "On a phone: see the books on your Marginalia tablet, download them with their audiobooks, and listen here. " +
                        "The listening place follows you between the phone and the tablet. On the tablet, switch on Library in the browser " +
                        "and press Pair a phone, then scan the code it shows.",
                    style = MaterialTheme.typography.bodySmall, color = grey
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InkButton("Scan the tablet's code", filled = true, enabled = !working, minHeight = Ink.TouchTarget, onClick = { scan() })
                }
                Text("Or type them", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.Black)
                OutlinedTextField(address, { address = it.take(80) }, singleLine = true, label = { Text("Address on the tablet, e.g. 192.168.1.20:8080") },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Black, unfocusedBorderColor = Color.Black, cursorColor = Color.Black))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(8) }, singleLine = true, label = { Text("Code") },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(4.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Black, unfocusedBorderColor = Color.Black, cursorColor = Color.Black))
                    InkButton("Connect", enabled = !working && address.isNotBlank() && code.length == 8, minHeight = Ink.TouchTarget, onClick = {
                        pairWith(TabletLink.Offer(code, "", "", listOf(TabletLink.addressToUrl(address))))
                    })
                }
            } else {
                Text("Paired with ${link.tabletName.ifBlank { "a tablet" }}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.Black)
                Text(if (busy) "Syncing…" else status.ifBlank { "Not synced yet." }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = grey)
                Text(
                    "Listening places, highlights and notes of the downloaded books sync both ways when the app opens or is left and when you pause listening, " +
                        "if the tablet's server is on and both are on the same Wi‑Fi. " +
                        "When it can't reach the tablet, the phone keeps its places and sends them next time.",
                    style = MaterialTheme.typography.bodySmall, color = grey
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InkButton("Books on the tablet", filled = true, minHeight = Ink.TouchTarget, onClick = { showBooks = true })
                    InkButton("Sync now", enabled = !busy, minHeight = Ink.TouchTarget, onClick = { scope.launch { TabletLink.syncNow(context) } })
                    InkButton("Disconnect", minHeight = Ink.TouchTarget, onClick = { TabletLink.forget(context); message = "Disconnected. The books stay on this phone." })
                }
            }
            if (message.isNotBlank()) Text(message, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Black)
        }
    }
    if (showBooks) TabletBooksDialog(onDismiss = { showBooks = false })
}

/** The tablet's library, with a download button per book. */
@Composable
private fun TabletBooksDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val grey = Color(0xFF3F3F46)
    var books by remember { mutableStateOf<List<TabletLink.Remote>?>(null) }
    var here by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var error by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var onlyAudio by remember { mutableStateOf(false) }

    suspend fun load() {
        error = ""
        runCatching { books = TabletLink.remoteLibrary(context); here = TabletLink.downloaded(context) }
            .onFailure { error = (it as? TabletLink.HttpError)?.message ?: "The tablet is not reachable. Is its server on, and are both on the same Wi‑Fi?" }
    }
    LaunchedEffect(Unit) { load() }

    Dialog(onDismissRequest = { if (downloading == null) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Books on the tablet", style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif), fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.weight(1f))
                    InkButton("Close", enabled = downloading == null, minHeight = Ink.TouchTarget, onClick = onDismiss)
                }
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Find") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Black, unfocusedBorderColor = Color.Black, cursorColor = Color.Black))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InkChip("All", !onlyAudio, { onlyAudio = false }, minHeight = Ink.TouchTarget)
                    InkChip("With audiobook", onlyAudio, { onlyAudio = true }, minHeight = Ink.TouchTarget)
                }
                if (progress.isNotBlank()) Text(progress, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Black)
                if (error.isNotBlank()) {
                    Text(error, fontSize = 14.sp, color = Color.Black)
                    InkButton("Try again", minHeight = Ink.TouchTarget, onClick = { scope.launch { load() } })
                }
                val list = books
                if (list == null && error.isBlank()) Text("Asking the tablet…", color = grey)
                if (list != null) {
                    val shown = list.filter { (!onlyAudio || it.audiobook) && (query.isBlank() || it.title.contains(query, true) || it.author.contains(query, true)) }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.weight(1f)) {
                        items(shown, key = { it.uuid }) { b ->
                            val onPhone = here.containsKey(b.uuid)
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(b.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(listOf(b.author, b.fileType, if (b.audiobook) "audiobook" else "").filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = grey, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (onPhone) Text("On this phone", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Black)
                                }
                                val label = when { downloading == b.uuid -> "…"; onPhone && b.audiobook -> "Update"; onPhone -> "Downloaded"; else -> "Download" }
                                InkButton(label, enabled = downloading == null && !(onPhone && !b.audiobook), minHeight = Ink.TouchTarget, onClick = {
                                    downloading = b.uuid
                                    scope.launch {
                                        try {
                                            TabletLink.download(context, b, withAudio = true) { progress = it }
                                            here = TabletLink.downloaded(context)
                                            progress = "“${b.title}” is on this phone, in the library."
                                        } catch (e: Exception) {
                                            progress = "Could not download “${b.title}”: ${e.message ?: e.javaClass.simpleName}"
                                        } finally { downloading = null }
                                    }
                                })
                            }
                            HorizontalDivider(color = Color.Black, thickness = 1.dp)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(downloading) { if (downloading == null && progress.startsWith("“")) Toast.makeText(context, progress, Toast.LENGTH_SHORT).show() }
}
