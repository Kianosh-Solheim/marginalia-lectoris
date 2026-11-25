package com.example.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.reader.CourseSessions
import com.example.reader.Recordings
import com.example.reader.SpokenComments
import com.example.server.WebServer
import kotlinx.coroutines.delay
import java.net.URLEncoder

/** The session open on the tablet: (course id, session id, title), or null. */
val openSession = mutableStateOf<Triple<String, String, String>?>(null)

/**
 * A course session on the tablet: the same page as the web view's (#/course/…/session/…) — slides, a note per
 * slide, pen marks, transcript — in a WebView signed in with the app's own token, under a bar that records
 * the lecture with the tablet's microphone (asking for it the first time).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SessionView(courseId: String, sessionId: String, title: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val running by WebServer.running
    var port by remember { mutableStateOf(WebServer.port()) }
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!WebServer.running.value) WebServer.start(context)
        repeat(60) { if (WebServer.port() != null && WebServer.running.value) { port = WebServer.port(); return@LaunchedEffect }; delay(250) }
        waited = true
    }
    DisposableEffect(Unit) {
        val w = (context as? android.app.Activity)?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    BackHandler { onClose() }
    val key = "$courseId/$sessionId"
    val active by Recordings.active.collectAsState()
    val err by Recordings.error.collectAsState()
    val mine = active?.takeIf { it.sessionKey == key }
    val start = { Recordings.error.value = ""; Recordings.startSession(context, courseId, sessionId, CourseSessions.lastSlide[key] ?: 1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) start()
        else Recordings.error.value = "Recording needs the microphone. Allow it in Android's app settings."
    }
    val record = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else {
            val ask = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) ask += Manifest.permission.POST_NOTIFICATIONS
            launcher.launch(ask.toTypedArray())
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BarButton("←", onClick = onClose)
            Text(title, color = Color.White, fontFamily = FontFamily.Serif, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            when {
                mine != null -> {
                    Box(Modifier.size(10.dp).background(if (mine.paused) Color(0xFF9CA3AF) else Color(0xFFEF4444), CircleShape))
                    Text((if (mine.paused) "PAUSED " else "REC ") + SpokenComments.clock(mine.elapsedMs) + if (mine.slide > 0) " · slide ${mine.slide}" else "",
                        color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    if (Build.VERSION.SDK_INT >= 24) BarButton(if (mine.paused) "Resume" else "Pause") { if (mine.paused) Recordings.resume(context) else Recordings.pause(context) }
                    BarButton("Stop", filled = true) { Recordings.stop(context) }
                }
                active != null -> Text("Recording something else", color = Color.White, fontSize = 13.sp)
                else -> BarButton("● Record", filled = true, onClick = record)
            }
        }
        if (err.isNotBlank() && mine == null) Text(err, color = Color.Black, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().border(1.dp, Color.Black).clickable { Recordings.error.value = "" }.padding(horizontal = 12.dp, vertical = 6.dp))
        val p = port
        if (p == null || !running) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(if (waited) "The library server did not start. Switch on “Library in the browser” in Settings and try again." else "Starting…", fontSize = 16.sp, color = Color.Black)
            }
        } else {
            val enc = { s: String -> URLEncoder.encode(s, "UTF-8").replace("+", "%20") }
            val next = URLEncoder.encode("#/course/${enc(courseId)}/session/${enc(sessionId)}?eink=1&tablet=1", "UTF-8")
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
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

@Composable
private fun BarButton(label: String, filled: Boolean = false, onClick: () -> Unit) {
    Text(label, color = if (filled) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        modifier = Modifier.border(1.dp, Color.White).background(if (filled) Color.White else Color.Black).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp))
}
