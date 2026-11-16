package com.example.ui

import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
    fun wanted() { if (Dictation.goesOnline(context) || Dictation.hasModel(context, lang)) begin() else askModel = true }

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
            text = { Text("Dictation in ${if (lang == "en") "English" else "Norwegian"} needs a speech model on the tablet when it is written offline: about ${Dictation.modelMegabytes(context, lang)} MB, downloaded once. After that it works without the internet. Tap the microphone again when the download has finished.") },
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


/** One of a few choices in Settings → Dictation; the chosen one is black. */
@Composable
private fun DictationChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) Color.White else Color.Black,
        modifier = Modifier
            .padding(end = 6.dp, bottom = 6.dp)
            .background(if (selected) Color.Black else Color.White)
            .border(1.dp, Color.Black)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    )
}

/** Settings → Dictation: who writes what is said, and with which offline model. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DictationSettingsSection() {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(Dictation.mode(context)) }
    var service by remember { mutableStateOf(Dictation.service(context)) }
    var fast by remember { mutableStateOf(Dictation.fast(context)) }
    val able = remember { runCatching { com.example.reader.Transcripts.onlineProviders(context) }.getOrDefault(emptyList()) }
    val aiLabel = remember { runCatching { com.example.reader.AiPreferences(context).load().provider.label }.getOrDefault("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, Color.Black, RoundedCornerShape(4.dp))
            .padding(14.dp)
    ) {
        Text("Dictation", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.Black)
        Text("The microphone beside a title or a note: the tablet listens, and the text is written when you stop.", fontSize = 12.sp, color = Color(0xFF3F3F46))

        Text("WHO WRITES THE TEXT", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        FlowRow {
            DictationChoice("Online when there is internet", mode == "auto") { mode = "auto"; Dictation.saveSettings(context, "auto", null, null, null) }
            DictationChoice("Always online", mode == "online") { mode = "online"; Dictation.saveSettings(context, "online", null, null, null) }
            DictationChoice("Always offline", mode == "offline") { mode = "offline"; Dictation.saveSettings(context, "offline", null, null, null) }
        }
        Text("Online is quick, but the sound of what you say is sent to the service. Offline, nothing leaves the tablet.", fontSize = 12.sp, color = Color(0xFF3F3F46))

        Text("ONLINE SERVICE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        FlowRow {
            DictationChoice("As under AI ($aiLabel)", service == "ai") { service = "ai"; Dictation.saveSettings(context, null, "ai", null, null) }
            able.forEach { p -> DictationChoice(p.label, service == p.name) { service = p.name; Dictation.saveSettings(context, null, p.name, null, null) } }
        }
        Text(
            if (able.isEmpty()) "No service has an API key yet (Gemini, OpenAI or Other, under AI above). Until then dictation is written offline."
            else "Only services with a saved API key are listed. Claude cannot write speech; with Claude chosen under AI, the first of these is used.",
            fontSize = 12.sp, color = Color(0xFF3F3F46)
        )

        Text("OFFLINE MODEL", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        FlowRow {
            DictationChoice("Accurate (Small, about 180 MB)", !fast) { fast = false; Dictation.saveSettings(context, null, null, false, null) }
            DictationChoice("Fast (Base, about 60 MB)", fast) { fast = true; Dictation.saveSettings(context, null, null, true, null) }
        }
        Text("Fast is several times quicker and makes more mistakes. Each model is downloaded once, when you first dictate with it.", fontSize = 12.sp, color = Color(0xFF3F3F46))
    }
}
