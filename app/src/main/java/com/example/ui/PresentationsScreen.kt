package com.example.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.server.Presentations
import com.example.server.WebServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date

/** Opened from the side menu; shown over the home screen. */
val presentationsOpen = mutableStateOf(false)

@Composable
fun PresentationsHost() {
    if (!presentationsOpen.value) return
    Dialog(onDismissRequest = { presentationsOpen.value = false }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.White) { PresentationsScreen(onClose = { presentationsOpen.value = false }) }
    }
}

@Composable
fun PresentationsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val changes by Presentations.changes.collectAsState()
    var decks by remember { mutableStateOf<List<Presentations.Deck>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var presenting by remember { mutableStateOf<Presentations.Deck?>(null) }
    var deleting by remember { mutableStateOf<Presentations.Deck?>(null) }
    LaunchedEffect(changes) { decks = withContext(Dispatchers.IO) { runCatching { Presentations.list(context) }.getOrDefault(emptyList()) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            uris.forEach { uri ->
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "presentation.pptx"
                status = "Adding $name…"
                status = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)!!.use { Presentations.add(context, name, it) }
                        "Added $name"
                    }.getOrElse { it.message ?: "Could not add $name" }
                }
            }
        }
    }
    val grey = Color(0xFF52525B)
    presenting?.let { d -> PresenterView(d) { presenting = null }; return }
    BackHandler { onClose() }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InkButton("←", onClick = onClose)
            Text("Presentations", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Color.Black, modifier = Modifier.weight(1f))
            InkButton("+ Add presentation", filled = true, onClick = {
                pick.launch(arrayOf("application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/vnd.oasis.opendocument.presentation", "application/pdf", "application/vnd.ms-powerpoint", "application/octet-stream"))
            })
        }
        Text("PowerPoint (.pptx), LibreOffice (.odp) or PDF. Present here, and show the slides full screen on a computer: open the library address in its browser and choose Presentations → Audience screen. Files can also be dropped into the web view.",
            fontSize = 14.sp, color = grey)
        if (status.isNotBlank()) Text(status, fontSize = 14.sp, color = Color.Black)
        if (decks.isEmpty()) Text("No presentations yet.", fontSize = 15.sp, color = Color.Black)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(decks, key = { it.id }) { d ->
                Row(
                    Modifier.fillMaxWidth().border(1.5.dp, Color.Black, RoundedCornerShape(4.dp)).clickable { presenting = d }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.size(56.dp, 40.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                        Text(d.type.uppercase(), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(d.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.Black)
                        Text("${d.file.length() / 1024} KB · added ${DateFormat.getDateInstance().format(Date(d.added))}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = grey)
                    }
                    InkButton("Present", filled = true, onClick = { presenting = d })
                    InkButton("Delete", onClick = { deleting = d })
                }
            }
        }
    }
    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
            title = { Text("Delete this presentation?", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = { Text("“${d.title}”, your notes and pen marks on it are deleted.") },
            confirmButton = { TextButton(onClick = { scope.launch(Dispatchers.IO) { Presentations.delete(d) }; deleting = null }) { Text("Delete", color = Color(0xFFB91C1C), fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = grey) } }
        )
    }
}

/**
 * The presenter screen: the same page as the web view's presenter (#/presenter), shown in a
 * WebView signed in through the app's own token. It needs the library server, which also
 * carries the slides to the audience screen.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PresenterView(deck: Presentations.Deck, onClose: () -> Unit) {
    val context = LocalContext.current
    val running by WebServer.running
    val addresses by WebServer.addresses
    var port by remember { mutableStateOf(WebServer.port()) }
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!WebServer.running.value) WebServer.start(context)
        repeat(60) { if (WebServer.port() != null && WebServer.running.value) { port = WebServer.port(); return@LaunchedEffect }; delay(250) }
        waited = true
    }
    // Keep the screen on while presenting.
    DisposableEffect(Unit) {
        val w = (context as? android.app.Activity)?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    BackHandler { onClose() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        val p = port
        val audience = addresses.firstOrNull()?.url
        Row(Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (audience != null) "Audience screen: open $audience/#/audience on the projector computer · PIN ${WebServer.pin.value.chunked(3).joinToString(" ")}"
                else "Connect the tablet to the same Wi‑Fi as the projector computer, its hotspot, or a USB cable (USB tethering).",
                color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClose) { Text("Close", color = Color.White, fontWeight = FontWeight.Bold) }
        }
        if (p == null || !running) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(if (waited) "The library server did not start. Switch on “Library in the browser” in Settings and try again." else "Starting…",
                    fontSize = 16.sp, color = Color.Black)
            }
        } else {
            val next = URLEncoder.encode("#/presenter/${URLEncoder.encode(deck.id, "UTF-8").replace("+", "%20")}?eink=1", "UTF-8")
            val url = "http://127.0.0.1:$p/api/local-login?t=${WebServer.localToken}&next=$next"
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = WebViewClient()
                        webChromeClient = WebChromeClient()
                        loadUrl(url)
                    }
                },
                onRelease = { it.destroy() },
                // weight, not fillMaxSize: the page must end where the screen ends, below the address bar.
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}
