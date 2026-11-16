package com.example.ui

import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.reader.Dictation

/**
 * A small microphone for a text field: tap, talk, tap again — what was said is handed to [onText].
 * The language (NO / EN) is chosen beside it and remembered.
 */
@Composable
fun DictationMic(onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val st by Dictation.state.collectAsState()
    val deliver by rememberUpdatedState(onText)
    val prefs = remember { context.getSharedPreferences("dictation", Context.MODE_PRIVATE) }
    var lang by remember { mutableStateOf(prefs.getString("language", "no") ?: "no") }
    var mine by remember { mutableStateOf(false) }       // this microphone started what is going on
    val mineNow = remember { booleanArrayOf(false) }
    var askModel by remember { mutableStateOf(false) }

    fun say(text: String?) { if (!text.isNullOrBlank()) Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    fun begin() {
        try { Dictation.start(context, lang); mine = true; mineNow[0] = true }
        catch (e: IllegalStateException) { say(e.message) }
    }
    fun wanted() { if (Dictation.hasModel(context, lang)) begin() else askModel = true }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) wanted() else say("Dictation needs the microphone. Allow it in Android's app settings.")
    }

    LaunchedEffect(st.phase, mine) {
        if (!mine) return@LaunchedEffect
        when (st.phase) {
            Dictation.Phase.DONE -> {
                val t = st.text
                Dictation.cancel(); mine = false; mineNow[0] = false
                if (t.isNotBlank()) deliver(t) else say("No words were heard.")
            }
            Dictation.Phase.ERROR -> { say(st.error); Dictation.cancel(); mine = false; mineNow[0] = false }
            Dictation.Phase.IDLE -> { mine = false; mineNow[0] = false }
            else -> {}
        }
    }
    // Closing the dialog while talking drops the dictation.
    DisposableEffect(Unit) { onDispose { if (mineNow[0]) Dictation.cancel() } }

    val recording = mine && st.phase == Dictation.Phase.RECORDING
    val working = mine && st.phase == Dictation.Phase.TRANSCRIBING
    val downloading = st.phase == Dictation.Phase.DOWNLOADING
    val othersBusy = !mine && (st.phase == Dictation.Phase.RECORDING || st.phase == Dictation.Phase.TRANSCRIBING)
    val level by animateFloatAsState(if (recording) st.level else 0f, label = "dictation level")

    Row(modifier = modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!recording && !working && !downloading) {
            Text(
                text = if (lang == "en") "EN" else "NO",
                fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color(0xFF52525B),
                modifier = Modifier
                    .clickable { lang = if (lang == "en") "no" else "en"; prefs.edit().putString("language", lang).apply() }
                    .padding(horizontal = 6.dp, vertical = 10.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable(enabled = !working && !downloading && !othersBusy) {
                    when {
                        recording -> Dictation.stop()
                        !Dictation.hasPermission(context) -> permission.launch(Manifest.permission.RECORD_AUDIO)
                        else -> wanted()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                recording -> {
                    // The red disc grows with the voice.
                    Box(Modifier.size((22 + 16 * level).dp).background(Color(0x55B91C1C), CircleShape))
                    Box(Modifier.size(22.dp).background(Color(0xFFB91C1C), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop dictating", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
                working -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.Black)
                downloading -> Text("${st.percent}%", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color.Black)
                else -> Icon(Icons.Default.Mic, contentDescription = "Dictate", tint = if (othersBusy) Color(0xFFA1A1AA) else Color.Black, modifier = Modifier.size(22.dp))
            }
        }
    }

    if (askModel) {
        AlertDialog(
            onDismissRequest = { askModel = false },
            containerColor = Color.White,
            title = { Text("Download the speech model?") },
            text = { Text("Dictation in ${if (lang == "en") "English" else "Norwegian"} needs a speech model on the tablet: about 180 MB, downloaded once. After that it works without the internet. Tap the microphone again when the download has finished.") },
            confirmButton = {
                TextButton(onClick = {
                    askModel = false
                    try { Dictation.downloadModel(context, lang) } catch (e: IllegalStateException) { say(e.message) }
                }) { Text("Download", color = Color.Black, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { askModel = false }) { Text("Not now", color = Color.Black) } }
        )
    }
}
