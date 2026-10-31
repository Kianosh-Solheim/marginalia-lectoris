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
 * Speech mode: one page with the script and room to write on it with the pen (over the text, in the
 * margins, or on the ruled space below), and the speech timer on top. Handwriting converts to text into
 * the script where it was written; Markdown, text, Word, HTML and PDF files load as the script; the
 * script saves as Markdown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeechScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var speech by remember { mutableStateOf(Speeches.last(context)) }
    var script by remember(speech.id) { mutableStateOf(TextFieldValue(speech.script)) }
    // All writing of the speech on one page (older speeches had several pages: they are put one below the other).
    var ink by remember(speech.id) { mutableStateOf(mergePages(speech.pages)) }
    var editing by remember(speech.id) { mutableStateOf(false) }
    var dirty by remember { mutableIntStateOf(0) }
    var inkVersion by remember { mutableIntStateOf(0) }
    var eraser by remember { mutableStateOf(false) }
    var showInk by remember { mutableStateOf(true) }
    var showText by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf("") }
    var converted by remember { mutableStateOf<Pair<Speeches.Recognised, List<Speeches.Stroke>>?>(null) }
    var showTimerSettings by remember { mutableStateOf(false) }
    var showOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var cue by remember { mutableStateOf<SpeechTimer.Cue?>(null) }
    val timer by SpeechTimer.state.collectAsState()
    val inkView = remember { InkCanvasView(context) }
    // Text already recognised per line of writing, filled in while writing.
    val lineCache = remember(speech.id) { Speeches.LineCache() }
    val hostView = LocalView.current

    LaunchedEffect(speech.id) { Speeches.remember(context, speech.id) }
    LaunchedEffect(speech.id, speech.durationSec, speech.warningsSec, speech.silent, speech.vibrate) {
        SpeechTimer.configure(context, speech.durationSec, speech.warningsSec, speech.silent, speech.vibrate)
    }
    LaunchedEffect(speech.id) {
        inkView.fingerDraws = speech.fingerDraws
        inkView.setStrokes(ink)
        inkView.scrollToTop()
        inkVersion++
    }
    LaunchedEffect(script.text, speech.fontSize) { inkView.setScript(script.text, speech.fontSize) }
    DisposableEffect(inkView) {
        inkView.onChanged = { ink = inkView.strokes(); inkVersion++; dirty++ }
        onDispose { inkView.onChanged = null }
    }
    LaunchedEffect(eraser) { inkView.eraser = eraser }
    LaunchedEffect(showInk) { inkView.showInk = showInk }
    LaunchedEffect(showText) { inkView.showText = showText }
    LaunchedEffect(speech.fingerDraws) { inkView.fingerDraws = speech.fingerDraws }
    // Load the handwriting model as soon as Speech mode opens (when it is already downloaded).
    LaunchedEffect(speech.norwegian) { withContext(Dispatchers.Default) { Speeches.warmUp(speech.norwegian) } }
    // Convert while writing: a moment after the pen stops, the lines that changed are recognised in the background.
    LaunchedEffect(inkVersion, speech.norwegian) {
        delay(700)
        val strokes = inkView.strokes()
        if (strokes.isEmpty() || !Speeches.isReady(speech.norwegian) || inkView.width <= 0) return@LaunchedEffect
        runCatching { Speeches.recognise(strokes, inkView.width.toFloat(), speech.norwegian, lineCache) }
    }
    // Saved shortly after each change.
    LaunchedEffect(dirty) {
        if (dirty == 0) return@LaunchedEffect
        delay(800)
        val s = speech.copy(script = script.text, pages = listOf(ink))
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
    fun flush() { val s = speech.copy(script = script.text, pages = listOf(ink)); speech = s; Speeches.save(context, s) }
    fun close() { flush(); onClose() }
    BackHandler { if (editing) editing = false else close() }

    /** Puts [text] into the script at [offset] (in the Markdown source) as its own paragraph. */
    fun insertParagraph(offset: Int, text: String) {
        val cur = script.text
        val at = offset.coerceIn(0, cur.length)
        val before = cur.substring(0, at).trimEnd('\n', ' ')
        val after = cur.substring(at).trimStart('\n', ' ')
        val joined = listOf(before, text.trim(), after).filter { it.isNotBlank() }.joinToString("\n\n")
        script = TextFieldValue(joined, TextRange((if (before.isBlank()) 0 else before.length + 2) + text.trim().length))
        dirty++
    }

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

    // Dialogs over the page, or the text editor: let the pen reach them instead of the page.
    SideEffect { inkView.paused = converted != null || showTimerSettings || showOpen || renaming || editing }

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

            // ---------------------------------------------------------------- page tools
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InkChip("Read", !editing, { editing = false })
                InkChip("Edit text", editing, { editing = true })
                InkChip("A−", false, { update(speech.copy(fontSize = (speech.fontSize - 2).coerceAtLeast(12))) })
                InkChip("A+", false, { update(speech.copy(fontSize = (speech.fontSize + 2).coerceAtMost(64))) })
                ToolGap()
                InkChip("Pen", !eraser && !editing, { eraser = false; editing = false })
                InkChip("Eraser", eraser && !editing, { eraser = true; editing = false })
                key(inkVersion) {
                    InkChip("Undo", false, { inkView.undo() })
                    InkChip("Clear writing", false, { inkView.clear() })
                }
                InkChip(if (showInk) "Hide writing" else "Show writing", !showInk, { showInk = !showInk })
                InkChip(if (showText) "Hide text" else "Show text", !showText, { showText = !showText })
                InkChip("Finger draws", speech.fingerDraws, { update(speech.copy(fingerDraws = !speech.fingerDraws)) })
                ToolGap()
                // The handwriting language, clearly shown: the active one is filled.
                Text("HANDWRITING", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black,
                    modifier = Modifier.align(Alignment.CenterVertically))
                Row(Modifier.border(1.5.dp, Color.Black, RoundedCornerShape(4.dp))) {
                    listOf(true to "Norsk", false to "English").forEach { (no, label) ->
                        val on = speech.norwegian == no
                        Text((if (on) "✓ " else "") + label, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (on) Color.White else Color.Black,
                            modifier = Modifier.background(if (on) Color.Black else Color.White).clickable { if (!on) update(speech.copy(norwegian = no)) }
                                .padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                InkButton(if (busy.startsWith("Convert")) "Converting…" else "Convert to text", filled = true, enabled = busy.isBlank(), onClick = {
                    val strokes = inkView.strokes()
                    if (strokes.isEmpty()) { Toast.makeText(context, "Write something on the page first.", Toast.LENGTH_SHORT).show(); return@InkButton }
                    busy = "Converting the handwriting…"
                    scope.launch {
                        runCatching { Speeches.recognise(strokes, inkView.width.toFloat().coerceAtLeast(1f), speech.norwegian, lineCache) { busy = it } }
                            .onSuccess { r -> if (r.text.isBlank()) Toast.makeText(context, "No text was recognised.", Toast.LENGTH_LONG).show() else converted = r to strokes }
                            .onFailure { Toast.makeText(context, "Couldn't convert: ${it.message ?: "recogniser unavailable"}", Toast.LENGTH_LONG).show() }
                        busy = ""
                    }
                })
            }

            // ---------------------------------------------------------------- the page: script and writing together
            val style = TextStyle(fontFamily = FontFamily.Serif, fontSize = speech.fontSize.sp, lineHeight = (speech.fontSize * 1.45f).sp, color = Color.Black)
            Box(Modifier.fillMaxSize().border(1.dp, Color.Black)) {
                AndroidView(
                    factory = { (inkView.parent as? android.view.ViewGroup)?.removeView(inkView); inkView },
                    update = { v -> v.visibility = if (editing) android.view.View.INVISIBLE else android.view.View.VISIBLE },
                    modifier = Modifier.fillMaxSize()
                )
                if (editing) {
                    Box(Modifier.fillMaxSize().background(Color.White).padding(16.dp)) {
                        BasicTextField(script, { script = it; dirty++ }, textStyle = style, cursorBrush = SolidColor(Color.Black),
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                        if (script.text.isEmpty()) Text("Type the speech here. Markdown works: # heading, - list.", style = style.copy(color = Color(0xFF9CA3AF)))
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- dialogs
    converted?.let { (r, used) ->
        var t by remember(r) { mutableStateOf(r.text) }
        AlertDialog(onDismissRequest = { converted = null }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
            title = { Text("Converted handwriting", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Correct anything that was misread. It goes into the script where you wrote it" +
                        " (after the paragraph next to it, or at the end).", fontSize = 13.sp, color = SGrey)
                    OutlinedTextField(t, { t = it }, minLines = 4, modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Black, unfocusedBorderColor = SRule))
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        insertParagraph(inkView.sourceOffsetAt(r.top), t)
                        inkView.remove(used); converted = null
                    }) { Text("Add to script", color = Color.Black, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = {
                        insertParagraph(inkView.sourceOffsetAt(r.top), t); converted = null
                    }) { Text("Add, keep writing", color = Color.Black) }
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

/** A small gap between groups of tools. */
@Composable
private fun ToolGap() { Spacer(Modifier.width(10.dp)) }

/** Older speeches kept writing on separate pages: put them one below the other on the single page. */
private fun mergePages(pages: List<List<Speeches.Stroke>>): List<Speeches.Stroke> {
    if (pages.size <= 1) return pages.firstOrNull().orEmpty()
    val out = ArrayList<Speeches.Stroke>()
    var offset = 0f
    pages.forEach { pg ->
        if (pg.isEmpty()) return@forEach
        val shift = offset
        pg.forEach { s ->
            val p = s.points.copyOf()
            for (i in 0 until s.count) p[i * 4 + 1] += shift
            out += Speeches.Stroke(p, s.width, s.start)
        }
        offset = (out.maxOfOrNull { it.maxY() } ?: offset) + 0.2f
    }
    return out
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
