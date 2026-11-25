package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.local.DocumentEntity
import com.example.reader.PiperVoices
import com.example.reader.ReadAloud
import com.example.reader.ReadAloudSkips
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val RaGrey = Color(0xFF52525B)

/**
 * Read aloud with a Piper voice (on the tablet, offline): the bar at the bottom of the reader. It starts where you are
 * reading ([startHere]); the sentence being read is underlined in the text by the reader.
 */
@Composable
fun ReadAloudBar(document: DocumentEntity, startHere: () -> ReadAloud.Start, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by ReadAloud.state.collectAsState()
    val downloads by PiperVoices.downloading.collectAsState()
    val voicesChanged by PiperVoices.changes.collectAsState()
    var showVoices by remember { mutableStateOf(false) }
    var showSkips by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    val mine = st.docId == document.id
    val start = { ReadAloud.start(context, document, startHere()) }
    fun install(id: String) = scope.launch {
        val r = withContext(Dispatchers.IO) { runCatching { PiperVoices.install(context, id) } }
        r.exceptionOrNull()?.let { Toast.makeText(context, it.message ?: "The download failed", Toast.LENGTH_LONG).show() }
        if (r.isSuccess && ReadAloud.state.value.needVoice == id) start()
    }

    Column(modifier.fillMaxWidth().background(Color.White).border(1.dp, Color.Black).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val need = if (mine) st.needVoice else ""
        when {
            need.isNotBlank() -> {
                val v = PiperVoices.catalog.first { it.id == need }
                val p = downloads[need]
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (p != null) "Downloading ${v.label}… ${(p * 100).toInt()}%" else "Read aloud needs a voice: ${v.label} (about ${v.sizeMb} MB, downloaded once).",
                        modifier = Modifier.weight(1f), fontSize = 14.sp)
                    if (p == null) InkButton("Download", filled = true, onClick = { install(need) })
                    InkButton("Voices…", onClick = { showVoices = true })
                    InkButton("×", onClick = onClose)
                }
            }
            mine && st.error.isNotBlank() -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(st.error, modifier = Modifier.weight(1f), fontSize = 13.sp)
                InkButton("Try again", onClick = start); InkButton("×", onClick = onClose)
            }
            else -> {
                Text(when {
                    mine && st.preparing.isNotBlank() -> st.preparing
                    mine && st.ended -> "The end."
                    mine && st.current != null -> st.current!!.text
                    else -> "Read aloud from where you are reading."
                }, fontSize = 13.sp, color = RaGrey, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InkButton("◁", onClick = { ReadAloud.step(-1) })
                    InkButton(if (mine && st.playing) "Pause" else "Play", filled = true, onClick = {
                        when {
                            mine && st.playing -> ReadAloud.pause()
                            mine && ReadAloud.isRunning() && !st.ended -> ReadAloud.play(context)
                            else -> start()
                        }
                    })
                    InkButton("▷", onClick = { ReadAloud.step(1) })
                    InkButton("From this page", onClick = start)
                    Spacer(Modifier.weight(1f))
                    InkButton("%.2gx".format(if (mine) st.speed else ReadAloud.savedSpeed(context)), onClick = {
                        val speeds = listOf(0.8f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f); val cur = if (mine) st.speed else ReadAloud.savedSpeed(context)
                        ReadAloud.setSpeed(context, speeds[(speeds.indexOfFirst { it >= cur - 0.01f } + 1) % speeds.size])
                    })
                    InkButton(if (mine && st.sleepAt > 0) "Sleep ${((st.sleepAt - System.currentTimeMillis()) / 60000 + 1)}m" else "Sleep", onClick = { showSleep = true })
                    InkButton("Voice", onClick = { showVoices = true })
                    InkButton("Skip…", onClick = { showSkips = true })
                    InkButton("×", onClick = { ReadAloud.stop(); onClose() })
                }
            }
        }
    }

    if (showSleep) Dialog(onDismissRequest = { showSleep = false }) {
        Column(Modifier.background(Color.White).border(1.dp, Color.Black).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Stop reading after…", fontWeight = FontWeight.Bold)
            listOf(10, 15, 30, 45, 60).forEach { m -> InkButton("$m minutes", onClick = { ReadAloud.sleep(m); showSleep = false }) }
            InkButton("Off", onClick = { ReadAloud.sleep(0); showSleep = false })
        }
    }

    if (showSkips) Dialog(onDismissRequest = { showSkips = false }) {
        var s by remember { mutableStateOf(ReadAloud.skips(context)) }
        fun set(n: ReadAloudSkips) { s = n; ReadAloud.setSkips(context, n) }
        Column(Modifier.background(Color.White).border(1.dp, Color.Black).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Leave out when reading aloud", fontWeight = FontWeight.Bold)
            @Composable fun row(label: String, help: String, on: Boolean, f: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth().clickable { f(!on) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = on, onCheckedChange = f)
                Column { Text(label); Text(help, fontSize = 12.sp, color = RaGrey) }
            }
            row("Citations", "(Hitchens 2007, p. 12), (ibid.), [14]", s.citations) { set(s.copy(citations = it)) }
            row("Footnote numbers", "the small numbers after words and sentences", s.footnoteNumbers) { set(s.copy(footnoteNumbers = it)) }
            row("Page headers, footers and numbers", "PDF: running titles and page numbers at the top and bottom", s.headersFooters) { set(s.copy(headersFooters = it)) }
            row("Web addresses", "https://…, www…, doi:…", s.urls) { set(s.copy(urls = it)) }
            row("The reference list", "stops at “References”, “Bibliography”, “Litteratur”…", s.references) { set(s.copy(references = it)) }
            row("Figure and table captions", "paragraphs starting “Figure 2”, “Table 1”, “Figur”…", s.captions) { set(s.copy(captions = it)) }
            Text("Changes apply the next time reading starts (Play from this page).", fontSize = 12.sp, color = RaGrey)
            InkButton("Done", onClick = { showSkips = false })
        }
    }

    if (showVoices) Dialog(onDismissRequest = { showVoices = false }) {
        key(voicesChanged) {
            var lang by remember { mutableStateOf(ReadAloud.langFor(context, document.id) ?: "auto") }
            Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).background(Color.White).border(1.dp, Color.Black).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Voices", fontWeight = FontWeight.Bold)
                Text("Language of this book", fontSize = 13.sp, color = RaGrey)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("auto" to "Detect", "en" to "English", "no" to "Norwegian").forEach { (k, label) ->
                        InkButton(label, filled = lang == k, onClick = { lang = k; ReadAloud.setLang(context, document.id, if (k == "auto") "" else k) })
                    }
                }
                listOf("en" to "English", "no" to "Norwegian").forEach { (l, name) ->
                    Text(name, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    val chosen = ReadAloud.voiceFor(context, l)
                    PiperVoices.catalog.filter { it.lang == l }.forEach { v ->
                        val have = PiperVoices.installed(context, v.id); val p = downloads[v.id]
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RadioButton(selected = chosen == v.id, onClick = { ReadAloud.setVoice(context, l, v.id); PiperVoices.changes.value++ })
                            Column(Modifier.weight(1f)) {
                                Text(v.label, fontSize = 14.sp)
                                Text(when { p != null -> "Downloading… ${(p * 100).toInt()}%"; have -> "On the tablet"; else -> "About ${v.sizeMb} MB" }, fontSize = 12.sp, color = RaGrey)
                            }
                            if (!have && p == null) InkButton("Download", onClick = { install(v.id) })
                            if (have) InkButton("Remove", onClick = { PiperVoices.remove(context, v.id) })
                        }
                    }
                }
                Text("Voices: Piper (rhasspy), packaged by sherpa-onnx; they run on the tablet, and no text leaves it.", fontSize = 11.sp, color = RaGrey)
                InkButton("Done", onClick = { showVoices = false; if (st.docId == document.id && ReadAloud.isRunning()) Toast.makeText(context, "A new voice is used from the next “Play from this page”.", Toast.LENGTH_SHORT).show() })
            }
        }
    }
}
