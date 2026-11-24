package com.example.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.local.DocumentEntity
import com.example.reader.AudiobookPlayer
import com.example.reader.Audiobooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun clock(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

/**
 * The audiobook bar at the bottom of the reader (e-ink style: black on white, few redraws):
 * play/pause, 30 s back/forward, chapter back/forward, the place, speed, sleep timer, and the files.
 * Without audio yet it offers to add the files.
 */
@Composable
fun AudiobookBar(document: DocumentEntity, onClose: () -> Unit, onUseTts: (() -> Unit)?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val changes by Audiobooks.changes.collectAsState()
    var has by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf("") }
    LaunchedEffect(document.id, changes) {
        has = withContext(Dispatchers.IO) { Audiobooks.has(context, document.id) }
        if (has == true) AudiobookPlayer.load(context, document.id, document.title)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var n = 0; var err = ""
            for (u in uris) {
                busy = "Copying ${++n} of ${uris.size}…"
                withContext(Dispatchers.IO) {
                    runCatching {
                        val name = context.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "audio.mp3"
                        val tmp = File(Audiobooks.dir(context, document.id).parentFile, ".incoming/${System.nanoTime()}.part").apply { parentFile?.mkdirs() }
                        context.contentResolver.openInputStream(u)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
                        try { Audiobooks.add(context, document.id, tmp, name) } finally { tmp.delete() }
                    }.onFailure { err = it.message ?: "Could not add the file" }
                }
            }
            busy = ""
            if (err.isNotBlank()) Toast.makeText(context, err, Toast.LENGTH_LONG).show()
        }
    }
    val st by AudiobookPlayer.state.collectAsState()
    var showFiles by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().background(Color.White).border(1.dp, Color.Black).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (has == false || busy.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (busy.isNotBlank()) busy else "No audiobook for this book yet.", modifier = Modifier.weight(1f), fontSize = 14.sp)
                if (busy.isBlank()) InkButton("Add audio files…", filled = true, onClick = { pick.launch(arrayOf("audio/*")) })
                if (onUseTts != null && busy.isBlank()) InkButton("Read aloud (TTS)", onClick = onUseTts)
                InkButton("×", onClick = onClose)
            }
            if (busy.isBlank()) Text("One .m4b with chapters, or all the .mp3 files at once (they are put in name order). You can also add them in the web view, on the book’s page.", fontSize = 12.sp, color = Color(0xFF52525B))
            return@Column
        }
        if (has == null || st.docId != document.id) { Text("Loading the audiobook…", fontSize = 13.sp); return@Column }
        val dur = st.durationMs.coerceAtLeast(1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(st.chapter.ifBlank { document.title }, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${clock(st.positionMs)} / ${clock(st.durationMs)}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("−${clock(((st.durationMs - st.positionMs) / st.speed.coerceAtLeast(0.5f)).toLong())}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF52525B))
        }
        // The place in the whole book: tap anywhere on the line to go there.
        Box(Modifier.fillMaxWidth().height(22.dp).pointerInput(dur) {
            detectTapGestures { off -> AudiobookPlayer.seekTo((off.x / size.width.coerceAtLeast(1) * dur).toLong()) }
        }) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(2.dp).background(Color(0xFFD4D4D8)))
            AudiobookPlayer.chapters().forEach { (_, ms) -> Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(ms.toFloat() / dur).height(10.dp).wrapContentWidth(Alignment.End).width(1.dp).background(Color(0xFF9CA3AF))) }
            Box(Modifier.align(Alignment.CenterStart).fillMaxWidth((st.positionMs.toFloat() / dur).coerceIn(0f, 1f)).height(4.dp).background(Color.Black))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InkButton("|◁", onClick = { AudiobookPlayer.chapterStep(-1) })
            InkButton("−30", onClick = { AudiobookPlayer.skip(-30_000) })
            InkButton(if (st.playing) "Pause" else "Play", filled = true, onClick = { AudiobookPlayer.toggle(context) })
            InkButton("+30", onClick = { AudiobookPlayer.skip(30_000) })
            InkButton("▷|", onClick = { AudiobookPlayer.chapterStep(1) })
            Spacer(Modifier.weight(1f))
            InkButton("%.2gx".format(st.speed), onClick = {
                val speeds = listOf(0.8f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)
                AudiobookPlayer.setSpeed(speeds[(speeds.indexOfFirst { it >= st.speed - 0.01f } + 1) % speeds.size])
            })
            InkButton(when { st.sleepAtChapterEnd -> "Sleep: ch. end"; st.sleepAt > 0 -> "Sleep ${((st.sleepAt - System.currentTimeMillis()) / 60000 + 1)}m"; else -> "Sleep" }, onClick = { showSleep = true })
            InkButton("Files", onClick = { showFiles = true })
            InkButton("×", onClick = onClose)
        }
    }

    if (showSleep) Dialog(onDismissRequest = { showSleep = false }) {
        Column(Modifier.background(Color.White).border(1.dp, Color.Black).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Stop playing after…", fontWeight = FontWeight.Bold)
            listOf(10, 15, 30, 45, 60).forEach { m -> InkButton("$m minutes", onClick = { AudiobookPlayer.sleep(m); showSleep = false }) }
            InkButton("The end of this chapter", onClick = { AudiobookPlayer.sleep(-1); showSleep = false })
            InkButton("Off", onClick = { AudiobookPlayer.sleep(0); showSleep = false })
        }
    }
    if (showFiles) Dialog(onDismissRequest = { showFiles = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).background(Color.White).border(1.dp, Color.Black).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Chapters", fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.weight(1f, fill = false)) {
                itemsIndexed(AudiobookPlayer.chapters()) { i, (t, ms) ->
                    Row(Modifier.fillMaxWidth().clickable { AudiobookPlayer.seekTo(ms); showFiles = false }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(clock(ms), fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(70.dp))
                        Text(t, fontWeight = if (i == st.chapterIndex) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton("Add more files…", onClick = { showFiles = false; pick.launch(arrayOf("audio/*")) })
                InkButton("Remove the audiobook", onClick = {
                    showFiles = false
                    scope.launch { withContext(Dispatchers.IO) { Audiobooks.remove(context, document.id, null) }; Toast.makeText(context, "Audiobook removed from the tablet", Toast.LENGTH_SHORT).show() }
                })
                InkButton("Close", onClick = { showFiles = false })
            }
        }
    }
}
