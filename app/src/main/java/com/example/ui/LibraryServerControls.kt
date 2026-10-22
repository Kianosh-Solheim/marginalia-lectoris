package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.server.QrCode
import com.example.server.WebServer

/** “Keep the screen on”: off, only while the library is open in a browser, or always while the app is open. */
object ScreenAwake {
    const val OFF = "off"
    const val WITH_SERVER = "server"
    const val ALWAYS = "always"
    val mode = mutableStateOf(OFF)

    fun load(context: Context) {
        mode.value = context.getSharedPreferences(WebServer.PREFS, Context.MODE_PRIVATE).getString("keep_screen_on", OFF) ?: OFF
        WebServer.loadPrefs(context)
    }

    fun set(context: Context, value: String) {
        context.getSharedPreferences(WebServer.PREFS, Context.MODE_PRIVATE).edit().putString("keep_screen_on", value).apply()
        mode.value = value
    }
}

/** Applies “Keep the screen on” to the app's window. Put once near the top of the activity's content. */
@Composable
fun KeepScreenOnEffect(activity: Activity) {
    LaunchedEffect(Unit) { ScreenAwake.load(activity) }
    val mode by ScreenAwake.mode
    val running by WebServer.running
    val on = mode == ScreenAwake.ALWAYS || (mode == ScreenAwake.WITH_SERVER && running)
    DisposableEffect(on) {
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { }
    }
}

/** Switching the server on asks for the notification permission first (Android 13+). */
@Composable
fun rememberServerSwitch(): (Boolean) -> Unit {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { WebServer.start(context) }
    return remember(context) {
        { on: Boolean ->
            if (!on) WebServer.stop(context)
            else {
                WebServer.error.value = ""
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else WebServer.start(context)
            }
        }
    }
}

/** Turns “stay awake” on or off; turning it on also asks Android not to put the app to sleep. */
fun setServerStayAwake(context: Context, on: Boolean) {
    WebServer.setStayAwake(context, on)
    if (!on) return
    runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            @Suppress("BatteryLife")
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/** A QR code for [text], drawn in black on white with a quiet border. */
@Composable
fun QrCodeImage(text: String, size: Dp, modifier: Modifier = Modifier) {
    val qr = remember(text) { runCatching { QrCode.encodeText(text) }.getOrNull() } ?: return
    Canvas(modifier.size(size).background(Color.White)) {
        val quiet = 2
        val n = qr.size + quiet * 2
        val cell = this.size.minDimension / n
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (qr.get(x, y)) drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/** The compact block in the side menu: switch the library in the browser on/off, keep awake toggles. */
@Composable
fun DrawerLibraryServerBlock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { ScreenAwake.load(context) }
    val running by WebServer.running
    val url by WebServer.url
    val addresses by WebServer.addresses
    LaunchedEffect(running) {
        while (running) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { WebServer.refreshAddresses() }; kotlinx.coroutines.delay(4000) }
    }
    val pin by WebServer.pin
    val keepScreen by ScreenAwake.mode
    val stayAwake by WebServer.stayAwake
    val switch = rememberServerSwitch()
    val grey = Color(0xFF3F3F46)
    Column(modifier.fillMaxWidth().border(1.dp, Color.Black).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Library in the browser", fontWeight = FontWeight.Medium, color = Color.Black, fontSize = 14.sp)
                if (running) {
                    if (addresses.isEmpty()) Text(url.removePrefix("http://"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    addresses.forEach { a ->
                        Text(a.url.removePrefix("http://"),
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                    Text("PIN ${pin.chunked(3).joinToString(" ")}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = grey)
                } else {
                    Text("Off", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = grey)
                }
            }
            Switch(
                checked = running, onCheckedChange = { switch(it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color.Black, uncheckedThumbColor = Color.Black, uncheckedTrackColor = Color.White, uncheckedBorderColor = Color.Black)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Server stays awake", fontSize = 13.sp, color = Color.Black, modifier = Modifier.weight(1f))
            Switch(
                checked = stayAwake, onCheckedChange = { setServerStayAwake(context, it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color.Black, uncheckedThumbColor = Color.Black, uncheckedTrackColor = Color.White, uncheckedBorderColor = Color.Black)
            )
        }
        Text("Keep screen on", fontSize = 13.sp, color = Color.Black)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(ScreenAwake.OFF to "Off", ScreenAwake.WITH_SERVER to "With browser", ScreenAwake.ALWAYS to "Always").forEach { (v, label) ->
                InkChip(label, keepScreen == v, { ScreenAwake.set(context, v) })
            }
        }
    }
}
