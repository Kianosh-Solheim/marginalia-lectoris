package com.example.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.reader.MarkdownExporter
import com.example.reader.SpeechTimer
import com.example.reader.Speeches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opened from the side menu: Speech mode. */
val speechOpen = mutableStateOf(false)

private val SGrey = Color(0xFF52525B)
private val SRule = Color(0xFFD4D4D8)

@Composable
fun SpeechHost() {
    if (!speechOpen.value) return
    Dialog(onDismissRequest = { speechOpen.value = false }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        }
        Surface(Modifier.fillMaxSize(), color = Color.White) { SpeechScreen(onClose = { speechOpen.value = false }) }
    }
}

/**
 * Speech mode: the script on one side (read or edit, any size), a page to write on with the pen on
 * the other, and the speech timer on top. Handwriting converts to text into the script; Markdown,
 * text, Word, HTML and PDF files load as the script; the script saves as Markdown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeechScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var speech by remember { mutableStateOf(Speeches.last(context)) }
    var script by remember(speech.id) { mutableStateOf(TextFieldValue(speech.script)) }
    var pages by remember(speech.id) { mutableStateOf(speech.pages) }
    var page by remember(speech.id) { mutableIntStateOf(0) }
    var editing by remember(speech.id) { mutableStateOf(speech.script.isBlank()) }
    var dirty by remember { mutableIntStateOf(0) }
    var inkVersion by remember { mutableIntStateOf(0) }
    var eraser by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf("") }
    var converted by remember { mutableStateOf<String?>(null) }
    var showTimerSettings by remember { mutableStateOf(false) }
    var showOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var cue by remember { mutableStateOf<SpeechTimer.Cue?>(null) }
    val timer by SpeechTimer.state.collectAsState()
    val inkView = remember { InkCanvasView(context) }
    val hostView = LocalView.current

    LaunchedEffect(speech.id) { Speeches.remember(context, speech.id) }
    LaunchedEffect(speech.id, speech.durationSec, speech.warningsSec, speech.silent, speech.vibrate) {
        SpeechTimer.configure(context, speech.durationSec, speech.warningsSec, speech.silent, speech.vibrate)
    }
    // Pen page: load the current page, and keep what is written.
    LaunchedEffect(speech.id, page) {
        inkView.fingerDraws = speech.fingerDraws
        inkView.setStrokes(pages.getOrNull(page).orEmpty())
        inkVersion++
    }
    DisposableEffect(inkView) {
        inkView.onChanged = {
            pages = pages.toMutableList().also { l -> while (l.size <= page) l.add(emptyList()); l[page] = inkView.strokes() }
            inkVersion++; dirty++
        }
        onDispose { inkView.onChanged = null }
    }
    LaunchedEffect(eraser) { inkView.eraser = eraser }
    LaunchedEffect(speech.fingerDraws) { inkView.fingerDraws = speech.fingerDraws }
    // Saved shortly after each change.
    LaunchedEffect(dirty) {
        if (dirty == 0) return@LaunchedEffect
        delay(800)
        val s = speech.copy(script = script.text, pages = pages)
        withContext(Dispatchers.IO) { Speeches.save(context, s) }
    }
    // Cues from the timer: flash the edge for a few seconds.
    LaunchedEffect(Unit) {
        SpeechTimer.cues.collect { c -> cue = c; delay(if (c.secondsLeft == 0) 6000 else 3500); if (cue == c) cue = null }
    }
    // Keep the screen on while the timer runs.
    DisposableEffect(timer.running) {
        hostView.keepScreenOn = timer.running
        onDispose { hostView.keepScreenOn = false }
    }

    fun update(s: Speeches.Speech) { speech = s; dirty++ }
    fun flush() { val s = speech.copy(script = script.text, pages = pages); speech = s; Speeches.save(context, s) }
    fun close() { flush(); onClose() }
    BackHandler { close() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "Loading the file…"
        scope.launch {
            runCatching { Speeches.readFile(context, uri) }.onSuccess { l ->
                if (script.text.isBlank()) {
                    script = TextFieldValue(l.text)
                    if (speech.title == "Untitled speech") speech = speech.copy(title = l.title)
                } else {
                    val t = script.text.trimEnd() + "\n\n" + l.text
                    script = TextFieldValue(t, TextRange(t.length))
                }
                editing = false; dirty++
                Toast.makeText(context, "Loaded “${l.title}”", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(context, it.message ?: "Couldn't load the file.", Toast.LENGTH_LONG).show() }
            busy = ""
        }
    }

    val edgeWidth = if (cue != null) 10.dp else 0.dp
    Box(Modifier.fillMaxSize().background(Color.White).border(edgeWidth, Color.Black)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // ---------------------------------------------------------------- top bar: title and timer
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InkButton("←", onClick = { close() })
                Column(Modifier.weight(1f).clickable { renaming = true }) {
                    Text("SPEECH", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SGrey)
                    Text(speech.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.Black, maxLines = 1)
                }
                val over = timer.overtime
                Text(
                    SpeechTimer.format(timer.remainingMs),
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 40.sp,
                    color = if (over) Color.White else Color.Black,
                    modifier = Modifier.background(if (over) Color.Black else Color.White, RoundedCornerShape(4.dp)).border(1.5.dp, Color.Black, RoundedCornerShape(4.dp))
                        .clickable { showTimerSettings = true }.padding(horizontal = 14.dp, vertical = 2.dp)
                )
                InkButton(if (timer.running) "Pause" else if (timer.started) "Resume" else "Start", filled = !timer.running, onClick = {
                    if (timer.running) SpeechTimer.pause() else SpeechTimer.start(context)
                })
                InkButton("Reset", enabled = timer.started, onClick = { SpeechTimer.reset() })
                InkButton("Timer…", onClick = { showTimerSettings = true })
            }
            cue?.let { c ->
                Text(SpeechTimer.label(c.secondsLeft), fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White,
                    modifier = Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 12.dp, vertical = 6.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InkButton("Speeches…", onClick = { flush(); showOpen = true })
                InkButton("Load file…", enabled = busy.isBlank(), onClick = { picker.launch(Speeches.loadableTypes) })
                InkButton("Save as Markdown", enabled = busy.isBlank(), onClick = {
                    flush()
                    scope.launch {
                        runCatching { Speeches.saveMarkdown(context, speech) }
                            .onSuccess { Toast.makeText(context, "Saved: Speeches/${it.name}", Toast.LENGTH_LONG).show() }
                            .onFailure { Toast.makeText(context, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show() }
                    }
                })
                InkButton("Share .md", onClick = {
                    flush()
                    scope.launch {
                        runCatching { Speeches.saveMarkdown(context, speech) }
                            .onSuccess { MarkdownExporter.shareFile(context, it, "text/markdown", "Share speech") }
                            .onFailure { Toast.makeText(context, "Couldn't share: ${it.message}", Toast.LENGTH_LONG).show() }
                    }
                })
                if (busy.isNotBlank()) Text(busy, fontSize = 13.sp, color = SGrey, modifier = Modifier.align(Alignment.CenterVertically))
            }
            HorizontalDivider(color = Color.Black, thickness = 1.5.dp)

            // ---------------------------------------------------------------- script and pen page
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 760.dp
                val scriptPane: @Composable (Modifier) -> Unit = { m ->
                    Column(m, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("SCRIPT", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SGrey, modifier = Modifier.weight(1f))
                            InkChip("Read", !editing, { editing = false })
                            InkChip("Edit", editing, { editing = true })
                            InkChip("A−", false, { update(speech.copy(fontSize = (speech.fontSize - 2).coerceAtLeast(12))) })
                            InkChip("A+", false, { update(speech.copy(fontSize = (speech.fontSize + 2).coerceAtMost(64))) })
                        }
                        val style = TextStyle(fontFamily = FontFamily.Serif, fontSize = speech.fontSize.sp, lineHeight = (speech.fontSize * 1.45f).sp, color = Color.Black)
                        Box(Modifier.fillMaxSize().border(1.dp, SRule).padding(12.dp)) {
                            if (editing) {
                                BasicTextField(script, { script = it; dirty++ }, textStyle = style, cursorBrush = SolidColor(Color.Black),
                                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                                if (script.text.isEmpty()) Text("Type the speech, load a file, or write with the pen and convert it.", style = style.copy(color = Color(0xFF9CA3AF)))
                            } else {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                    if (script.text.isBlank()) Text("No script yet. Tap Edit, load a file, or convert your handwriting.", style = style.copy(color = Color(0xFF9CA3AF)))
                                    else scriptParagraphs(script.text).forEach { (kind, text) ->
                                        when (kind) {
                                            1 -> Text(text, style = style.copy(fontWeight = FontWeight.Bold, fontSize = (speech.fontSize * 1.3f).sp), modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                                            2 -> Text("•  $text", style = style, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
                                            else -> Text(text, style = style, modifier = Modifier.padding(bottom = 12.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                val penPane: @Composable (Modifier) -> Unit = { m ->
                    Column(m, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("PEN · PAGE ${page + 1}/${pages.size.coerceAtLeast(1)}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SGrey,
                                modifier = Modifier.align(Alignment.CenterVertically))
                            InkChip("Pen", !eraser, { eraser = false })
                            InkChip("Eraser", eraser, { eraser = true })
                            key(inkVersion) {
                                InkChip("Undo", false, { inkView.undo() })
                                InkChip("Clear", false, { inkView.clear() })
                            }
                            InkChip("◀", false, { if (page > 0) page-- })
                            InkChip("▶", false, { if (page < pages.size - 1) page++ })
                            InkChip("+ Page", false, { pages = pages + listOf(emptyList()); page = pages.size - 1; dirty++ })
                            InkChip("Finger draws", speech.fingerDraws, { update(speech.copy(fingerDraws = !speech.fingerDraws)) })
                            InkChip(if (speech.norwegian) "Norsk" else "English", false, { update(speech.copy(norwegian = !speech.norwegian)) })
                            InkButton(if (busy.startsWith("Convert")) "Converting…" else "Convert to text", filled = true, enabled = busy.isBlank(), onClick = {
                                val strokes = inkView.strokes()
                                if (strokes.isEmpty()) { Toast.makeText(context, "Write something on the page first.", Toast.LENGTH_SHORT).show(); return@InkButton }
                                busy = "Converting the handwriting…"
                                scope.launch {
                                    runCatching { Speeches.recognise(strokes, inkView.lineHeight, inkView.width.toFloat().coerceAtLeast(1f), speech.norwegian) { busy = it } }
                                        .onSuccess { t -> if (t.isBlank()) Toast.makeText(context, "No text was recognised.", Toast.LENGTH_LONG).show() else converted = t }
                                        .onFailure { Toast.makeText(context, "Couldn't convert: ${it.message ?: "recogniser unavailable"}", Toast.LENGTH_LONG).show() }
                                    busy = ""
                                }
                            })
                        }
                        AndroidView(factory = { (inkView.parent as? android.view.ViewGroup)?.removeView(inkView); inkView }, modifier = Modifier.fillMaxSize().border(1.dp, Color.Black))
                    }
                }
                if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    scriptPane(Modifier.weight(1f).fillMaxHeight())
                    penPane(Modifier.weight(1f).fillMaxHeight())
                } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    scriptPane(Modifier.weight(1f).fillMaxWidth())
                    penPane(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }

    // ---------------------------------------------------------------- dialogs
    converted?.let { text ->
        var t by remember(text) { mutableStateOf(text) }
        AlertDialog(onDismissRequest = { converted = null }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
            title = { Text("Converted handwriting", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Correct anything that was misread, then add it to the script.", fontSize = 13.sp, color = SGrey)
                    OutlinedTextField(t, { t = it }, minLines = 4, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Black, unfocusedBorderColor = SRule))
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        // At the cursor when editing, otherwise at the end.
                        val cur = script
                        val at = if (editing) cur.selection.end.coerceIn(0, cur.text.length) else cur.text.length
                        val before = cur.text.substring(0, at); val after = cur.text.substring(at)
                        val sep = if (before.isBlank()) "" else if (before.endsWith("\n\n")) "" else if (before.endsWith("\n")) "\n" else "\n\n"
                        val ins = sep + t.trim() + if (after.isNotEmpty() && !after.startsWith("\n")) "\n\n" else ""
                        script = TextFieldValue(before + ins + after, TextRange(before.length + ins.length))
                        dirty++; converted = null
                    }) { Text("Add to script", color = Color.Black, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = {
                        val cur = script
                        script = TextFieldValue(cur.text.trimEnd().let { if (it.isBlank()) t.trim() else it + "\n\n" + t.trim() }); dirty++
                        inkView.clear(); converted = null
                    }) { Text("Add and clear page", color = Color.Black) }
                }
            },
            dismissButton = { TextButton(onClick = { converted = null }) { Text("Cancel", color = SGrey) } })
    }

    if (showTimerSettings) TimerSettingsDialog(speech, timer.started, onDismiss = { showTimerSettings = false }, onSave = { s ->
        update(s); showTimerSettings = false
        SpeechTimer.configure(context, s.durationSec, s.warningsSec, s.silent, s.vibrate)
    })

    if (renaming) {
        var name by remember { mutableStateOf(speech.title) }
        AlertDialog(onDismissRequest = { renaming = false }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
            title = { Text("Name of the speech", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Black, unfocusedBorderColor = SRule)) },
            confirmButton = { TextButton(onClick = { update(speech.copy(title = name.trim().ifBlank { "Untitled speech" })); renaming = false }) { Text("Save", color = Color.Black, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel", color = SGrey) } })
    }

    if (showOpen) {
        val list = remember { Speeches.list(context) }
        AlertDialog(onDismissRequest = { showOpen = false }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
            title = { Text("Speeches", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.id }) { s ->
                        Row(Modifier.fillMaxWidth().clickable {
                            Speeches.load(context, s.id)?.let { speech = it }; showOpen = false
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.title, fontWeight = if (s.id == speech.id) FontWeight.Bold else FontWeight.Normal, color = Color.Black)
                                Text("${s.words} words · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(s.updated))}", fontSize = 12.sp, color = SGrey)
                            }
                        }
                        HorizontalDivider(color = SRule)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { speech = Speeches.newSpeech(context); showOpen = false }) { Text("New speech", color = Color.Black, fontWeight = FontWeight.Bold) } },
            dismissButton = {
                Row {
                    if (list.size > 1) TextButton(onClick = {
                        Speeches.remove(context, speech.id)
                        speech = Speeches.list(context).firstOrNull()?.let { Speeches.load(context, it.id) } ?: Speeches.newSpeech(context)
                        showOpen = false
                    }) { Text("Remove this one", color = SGrey) }
                    TextButton(onClick = { showOpen = false }) { Text("Close", color = SGrey) }
                }
            })
    }
}

/** Paragraphs of a Markdown script for reading: 1 = heading, 2 = list item, 0 = paragraph. */
private fun scriptParagraphs(md: String): List<Pair<Int, String>> {
    val out = ArrayList<Pair<Int, String>>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += 0 to para.toString().trim(); para.clear() }
    md.lines().forEach { raw ->
        val l = raw.trimEnd()
        when {
            l.isBlank() -> flush()
            l.trimStart().startsWith("#") -> { flush(); out += 1 to l.trimStart().trimStart('#').trim() }
            Regex("^\\s*([-*+]|\\d+[.)])\\s+").containsMatchIn(l) -> { flush(); out += 2 to l.replace(Regex("^\\s*([-*+]|\\d+[.)])\\s+"), "") }
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(l.trim()) }
        }
    }
    flush()
    return out.map { (k, t) -> k to t.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("(?<![*\\w])\\*(?!\\s)(.+?)\\*"), "$1") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimerSettingsDialog(speech: Speeches.Speech, started: Boolean, onDismiss: () -> Unit, onSave: (Speeches.Speech) -> Unit) {
    var minutes by remember { mutableStateOf((speech.durationSec / 60).toString()) }
    var seconds by remember { mutableStateOf((speech.durationSec % 60).toString()) }
    val warnings = remember { mutableStateListOf<Int>().apply { addAll(speech.warningsSec) } }
    var custom by remember { mutableStateOf("") }
    var silent by remember { mutableStateOf(speech.silent) }
    var vibrate by remember { mutableStateOf(speech.vibrate) }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Black, unfocusedBorderColor = SRule)
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
        title = { Text("Timer", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SPEAKING TIME", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SGrey)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(3) }, label = { Text("Minutes") }, singleLine = true, modifier = Modifier.width(110.dp), colors = colors)
                    OutlinedTextField(seconds, { seconds = it.filter(Char::isDigit).take(2) }, label = { Text("Seconds") }, singleLine = true, modifier = Modifier.width(110.dp), colors = colors)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3, 5, 7, 10, 15, 20, 30, 45).forEach { m -> InkChip("$m min", minutes == m.toString() && (seconds.toIntOrNull() ?: 0) == 0, { minutes = m.toString(); seconds = "0" }) }
                }
                if (started) Text("The new time applies after Reset.", fontSize = 12.sp, color = SGrey)
                Text("WARNINGS (TIME LEFT)", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SGrey)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf(600, 300, 180, 120, 60, 30) + warnings).distinct().sortedDescending().forEach { w ->
                        InkChip(if (w % 60 == 0) "${w / 60} min" else if (w > 60) "${w / 60}:%02d".format(w % 60) else "$w s", w in warnings, {
                            if (w in warnings) warnings.remove(w) else warnings.add(w)
                        })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(custom, { custom = it.filter { c -> c.isDigit() || c == ':' || c == '.' }.take(6) }, label = { Text("Other (m or m:ss)") }, singleLine = true, modifier = Modifier.width(170.dp), colors = colors)
                    InkButton("Add", enabled = custom.isNotBlank(), onClick = {
                        val sec = if (custom.contains(':')) custom.substringBefore(':').toIntOrNull()?.let { m -> m * 60 + (custom.substringAfter(':').toIntOrNull() ?: 0) }
                        else custom.toDoubleOrNull()?.let { (it * 60).toInt() }
                        if (sec != null && sec > 0 && sec !in warnings) warnings.add(sec)
                        custom = ""
                    })
                }
                Text("A cue is also given at zero; after that the clock counts the overtime.", fontSize = 12.sp, color = SGrey)
                Text("CUES", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SGrey)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InkChip("Silent", silent, { silent = !silent })
                    InkChip("Vibrate", vibrate, { vibrate = !vibrate })
                }
                Text(if (silent) "Silent: the screen edge flashes" + (if (vibrate) " and the tablet vibrates" else "") + " — no sound." else "A short tone plays at each cue, with the flash" + (if (vibrate) " and vibration." else "."), fontSize = 12.sp, color = SGrey)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val total = ((minutes.toIntOrNull() ?: 0) * 60 + (seconds.toIntOrNull() ?: 0)).coerceAtLeast(10)
                onSave(speech.copy(durationSec = total, warningsSec = warnings.filter { it < total }.distinct().sortedDescending(), silent = silent, vibrate = vibrate))
            }) { Text("Save", color = Color.Black, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = SGrey) } })
}
