package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.core.content.ContextCompat
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.data.local.SettingsEntity
import com.example.reader.AiProvider
import com.example.reader.LocalWhisper
import com.example.reader.Recordings
import com.example.reader.SpokenComments
import com.example.reader.Transcripts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Starts or stops recording for this book, asking for the microphone the first time. */
@Composable
fun rememberRecordingToggle(document: DocumentEntity, place: () -> Triple<Int, String, String>): () -> Unit {
    val context = LocalContext.current
    val active by Recordings.active.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            val (p, l, c) = place()
            Recordings.start(context, document, p, l, c)
        } else Recordings.error.value = "Recording needs the microphone. Allow it in Android's app settings."
    }
    return {
        if (active != null) Recordings.stop(context)
        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val (p, l, c) = place()
            Recordings.start(context, document, p, l, c)
        } else {
            val ask = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) ask += Manifest.permission.POST_NOTIFICATIONS
            launcher.launch(ask.toTypedArray())
        }
    }
}

/** The small "● REC 12:34" badge shown over the book while recording, with pause and stop. */
@Composable
fun RecordingBadge(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val a by Recordings.active.collectAsState()
    val err by Recordings.error.collectAsState()
    val rec = a
    if (rec == null) {
        if (err.isNotBlank()) {
            Surface(color = Color.White, border = BorderStroke(1.5.dp, Color.Black), shape = RoundedCornerShape(6.dp), modifier = modifier.clickable { Recordings.error.value = "" }) {
                Text(err, color = Color.Black, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        return
    }
    Surface(color = Color.Black, shape = RoundedCornerShape(20.dp), modifier = modifier) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(10.dp).background(if (rec.paused) Color(0xFF9CA3AF) else Color(0xFFEF4444), CircleShape))
            Text((if (rec.paused) "PAUSED " else "REC ") + SpokenComments.clock(rec.elapsedMs), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (Build.VERSION.SDK_INT >= 24) TextButton(onClick = { if (rec.paused) Recordings.resume(context) else Recordings.pause(context) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (rec.paused) "Resume" else "Pause", color = Color.White, fontSize = 13.sp)
            }
            TextButton(onClick = { Recordings.stop(context) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Stop", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** The book's recordings: play, transcribe, read the transcript, delete. */
@Composable
fun RecordingsDialog(document: DocumentEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val changes by Recordings.changes.collectAsState()
    val progress by Transcripts.progress.collectAsState()
    val errors by Transcripts.lastError.collectAsState()
    val localState by LocalWhisper.state.collectAsState()
    var list by remember { mutableStateOf<List<Recordings.Info>>(emptyList()) }
    var settings by remember { mutableStateOf(SettingsEntity()) }
    LaunchedEffect(document.id, changes) {
        withContext(Dispatchers.IO) {
            settings = AppDatabase.getInstance(context).settingsDao().getSettingsSnapshot() ?: SettingsEntity()
            list = Recordings.list(context, document, settings)
        }
    }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { runCatching { player?.release() } } }
    var transcribing by remember { mutableStateOf<Recordings.Info?>(null) }
    var reading by remember { mutableStateOf<Recordings.Info?>(null) }
    var deleting by remember { mutableStateOf<Recordings.Info?>(null) }
    val grey = Color(0xFF52525B)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(4.dp),
        title = { Text("Recordings", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tap Record (the microphone in the reader's top bar) to talk while you read. Page turns and highlights are kept with the time you made them.",
                    fontSize = 13.sp, color = grey)
                if (list.isEmpty()) Text("No recordings for this book yet.", fontSize = 14.sp, color = Color.Black)
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.id }) { r ->
                        Column(Modifier.fillMaxWidth().border(1.dp, Color.Black, RoundedCornerShape(4.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.startedAt)), fontWeight = FontWeight.Bold, color = Color.Black)
                            Text("${SpokenComments.clock(r.durationMs)} · ${r.events} events · ${r.audio.length() / 1024 / 1024} MB" +
                                (if (r.transcribed) " · transcribed" else ""), fontSize = 12.sp, color = grey, fontFamily = FontFamily.Monospace)
                            val p = progress[r.id] ?: if (localState.id == r.id && localState.busy) localState.status else null
                            if (p != null) Text(p, fontSize = 13.sp, color = Color.Black)
                            errors[r.id]?.let { Text(it, fontSize = 13.sp, color = Color(0xFFB91C1C)) }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                InkChip(if (playing == r.id) "Stop" else "Play", playing == r.id, {
                                    runCatching { player?.release() }; player = null
                                    if (playing == r.id) playing = "" else {
                                        player = MediaPlayer().apply { setDataSource(r.audio.absolutePath); prepare(); start(); setOnCompletionListener { playing = "" } }
                                        playing = r.id
                                    }
                                })
                                if (r.transcribed) InkChip("Transcript", false, { reading = r })
                                InkChip(if (r.transcribed) "Again…" else "Transcribe…", false, { Transcripts.clearError(r.id); transcribing = r })
                                InkChip("Delete", false, { deleting = r })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Color.Black, fontWeight = FontWeight.Bold) } }
    )

    transcribing?.let { r ->
        TranscribeDialog(r, onDismiss = { transcribing = null }, onOnline = { provider, lang ->
            transcribing = null
            Transcripts.startOnline(context, document, r, provider, lang)
        }, onLocal = { lang ->
            transcribing = null
            LocalWhisper.start(context, document, r, lang)
        })
    }
    reading?.let { r ->
        val text = remember(r.id, changes) { runCatching { r.transcript.readText() }.getOrDefault("") }
        AlertDialog(
            onDismissRequest = { reading = null }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
            title = { Text("Transcript", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = { Text(text.substringAfter("\n---\n", text).trim(), fontSize = 14.sp, color = Color.Black, modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { reading = null }) { Text("Close", color = Color.Black) } }
        )
    }
    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
            title = { Text("Delete this recording?", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = { Text("The audio, its timeline and transcript are deleted, and the spoken comments it added to your notes are removed.") },
            confirmButton = { TextButton(onClick = {
                if (playing == r.id) { runCatching { player?.release() }; player = null; playing = "" }
                scope.launch(Dispatchers.IO) { Recordings.delete(r) }; deleting = null
            }) { Text("Delete", color = Color(0xFFB91C1C), fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = grey) } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscribeDialog(r: Recordings.Info, onDismiss: () -> Unit, onOnline: (AiProvider, String) -> Unit, onLocal: (String) -> Unit) {
    val context = LocalContext.current
    val providers = remember { Transcripts.onlineProviders(context) }
    var lang by remember { mutableStateOf(r.language.takeIf { it == "no" || it == "en" } ?: "auto") }
    val grey = Color(0xFF52525B)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
        title = { Text("Transcribe", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Language", fontWeight = FontWeight.SemiBold, color = Color.Black)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("auto" to "Detect", "no" to "Norwegian", "en" to "English").forEach { (v, l) -> InkChip(l, lang == v, { lang = v }) }
                }
                HorizontalDivider()
                Text("On this tablet", fontWeight = FontWeight.SemiBold, color = Color.Black)
                Text("Offline with Whisper (Norwegian: the National Library's NB-Whisper). The first time, the model is downloaded (about 180 MB). Slow: leave it running while charging.",
                    fontSize = 13.sp, color = grey)
                InkButton("Transcribe on the tablet", onClick = { onLocal(lang) })
                HorizontalDivider()
                Text("On a PC", fontWeight = FontWeight.SemiBold, color = Color.Black)
                Text("Open the library in the browser, go to this book and choose Recordings → Transcribe on this computer. Much faster, still private.",
                    fontSize = 13.sp, color = grey)
                HorizontalDivider()
                Text("Online", fontWeight = FontWeight.SemiBold, color = Color.Black)
                if (providers.isEmpty()) Text("Add a Gemini or OpenAI key in Settings → AI to use this. The audio is sent to that service.", fontSize = 13.sp, color = grey)
                else {
                    Text("The audio is sent to the service you choose.", fontSize = 13.sp, color = grey)
                    providers.forEach { p -> InkButton("With ${p.label}", onClick = { onOnline(p, lang) }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Black) } }
    )
}
