package com.example.ui

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.DocumentEntity
import com.example.reader.SearchIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val SGrey = Color(0xFF52525B)

/** A plain bordered text field in the app's ink style. */
@Composable
internal fun InkField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, singleLine: Boolean = true, minLines: Int = 1) {
    Box(modifier.border(1.dp, Color.Black).padding(horizontal = 8.dp, vertical = 8.dp)) {
        BasicTextField(value, onChange, singleLine = singleLine, minLines = minLines, textStyle = TextStyle(fontSize = 15.sp, color = Color.Black), cursorBrush = SolidColor(Color.Black), modifier = Modifier.fillMaxWidth())
        if (value.isEmpty()) Text(placeholder, color = Color(0xFF9CA3AF), fontSize = 15.sp)
    }
}

/** What can be typed in a search, with examples. */
@Composable
fun SearchHelpDialog(onDismiss: () -> Unit) {
    val rows = listOf(
        "vold makt" to "Fuzzy: both words close together, in any order. Other word forms count too (vold → volden, voldelig; violence → violent).",
        "\"radical right\"" to "Quotes: these words together, in this order.",
        "Exact" to "The whole search as one sentence, word for word.",
        "-left" to "Leave out pages with this word (or -\"a phrase\").",
        "far right OR populism" to "Either one. Write OR in capitals.",
        "author:Tilly" to "Only books by this author. Also title:…, tag:Master, type:book / type:article.",
        "year:2010-2020" to "Only books from these years (or year:2017).",
        "Typos" to "Also similar spellings and OCR mistakes (tried by itself when nothing is found).",
        "+ Synonym" to "Make words mean the same in every search: far right = radical right = extreme right.",
        "Whole words" to "Words do not match longer words or other forms.")
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
        title = { Text("How to search", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { (a, b) ->
                    Row { Text(a, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.Black, modifier = Modifier.width(170.dp)); Text(b, fontSize = 14.sp, color = Color.Black) }
                }
                Text("Case, accents, punctuation and words broken over a line are always ignored.", fontSize = 12.sp, color = SGrey)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Color.Black) } })
}

/** The words of a search without its operators. */
fun plainTerms(q: String): String = q.replace(Regex("(?i)(^|\\s)(author|title|tag|year|type|forfatter|tittel|år):(\"[^\"]*\"|\\S+)"), " ")
    .replace(Regex("(^|\\s)-(\"[^\"]*\"|\\S+)"), " ").replace(Regex("\\bOR\\b"), ",").replace("\"", "").replace(Regex("\\s+"), " ").trim()

/** "+ Synonym": terms that mean the same in every Fuzzy search (also in Settings on the web page). */
@Composable
fun SynonymDialog(query: String, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val first = plainTerms(query).split(',').first().trim()
    var terms by remember { mutableStateOf(if (first.isNotEmpty()) "$first, " else "") }
    var groups by remember { mutableStateOf(SearchIndex.synonyms(context)) }
    fun save(g: List<List<String>>) { SearchIndex.setSynonyms(context, g); groups = SearchIndex.synonyms(context); onSaved() }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
        title = { Text("Synonyms", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Terms that should mean the same in every Fuzzy search. Searching for one also finds the others. Separate them with commas.", fontSize = 13.sp, color = SGrey)
                InkField(terms, { terms = it }, "far right, radical right, extreme right", Modifier.fillMaxWidth())
                Text("YOUR GROUPS", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(groups) { g ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(g.joinToString(" = "), fontSize = 14.sp, color = Color.Black, modifier = Modifier.weight(1f))
                            InkChip("Edit", false, { terms = g.joinToString(", "); groups = groups - listOf(g) ; SearchIndex.setSynonyms(context, groups) })
                            Spacer(Modifier.width(6.dp))
                            InkChip("Remove", false, { save(groups - listOf(g)) })
                        }
                    }
                    if (groups.isEmpty()) item { Text("No synonym groups yet.", fontSize = 13.sp, color = SGrey) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val g = terms.split(',', '=', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (g.size >= 2) { save(groups + listOf(g)); onDismiss() }
            }) { Text("Save", color = Color.Black, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Black) } })
}

/** The sentence around a match in a passage. */
fun sentenceOf(snippet: String, start: Int, end: Int): String {
    val t = snippet.replace(Regex("^…"), " ").replace(Regex("…$"), " ")
    val s = start.coerceIn(0, t.length); val e = end.coerceIn(s, t.length)
    val a = Regex("[.!?][\"”’)]?\\s").findAll(t.substring(0, s)).lastOrNull()?.let { it.range.last + 1 } ?: 0
    val b = Regex("[.!?][\"”’)]?(\\s|$)").find(t, e)?.let { it.range.first + 1 } ?: t.length
    return t.substring(a, b.coerceAtLeast(a)).trim()
}

private val noteColors = listOf("#FFEB3B", "#FFE082", "#FF9800", "#81C784", "#64B5F6", "#BA68C8", "#EF5350", "#E4E4E7")

/** "+ Note" on a search result: the sentence with its page, a title, own words, tags and colour. */
@Composable
fun NoteFromHitDialog(doc: DocumentEntity, loc: Int, label: String, chapter: String, snippet: String, start: Int, end: Int, onDismiss: () -> Unit, onSaved: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var quote by remember { mutableStateOf(sentenceOf(snippet, start, end)) }
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(noteColors[0]) }
    val isPdf = doc.fileType.equals("PDF", true)
    val where = if (isPdf) "p. ${label.ifBlank { loc.toString() }}" + (if (label.isNotBlank() && label != loc.toString()) " (PDF $loc)" else "") else label.ifBlank { "pos. $loc" }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
        title = { Text("New note from the search", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${doc.title} · $where", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = SGrey)
                Text("QUOTE (YOU CAN SHORTEN IT)", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Black)
                InkField(quote, { quote = it }, "", Modifier.fillMaxWidth(), singleLine = false, minLines = 3)
                InkField(title, { title = it }, "Title (optional)", Modifier.fillMaxWidth())
                InkField(notes, { notes = it }, "Your own words (optional)", Modifier.fillMaxWidth(), singleLine = false, minLines = 3)
                InkField(tags, { tags = it }, "Tags, separated by commas (optional)", Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    noteColors.forEach { c ->
                        val col = Color(android.graphics.Color.parseColor(c))
                        Box(Modifier.size(30.dp).background(col).border(if (c == color) 3.dp else 1.dp, Color.Black).clickable { color = c })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (quote.isBlank()) return@TextButton
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            com.example.server.LibraryOps(context).addHighlight(
                                documentId = doc.id, title = title, highlightedText = quote.trim(), userNotes = notes, pageNumber = loc,
                                printedPageLabel = if (isPdf) label else "", chapterTitle = chapter, colorHex = color, tags = tags)
                        }
                    }.onSuccess { android.widget.Toast.makeText(context, "Note saved", android.widget.Toast.LENGTH_SHORT).show(); onSaved(); onDismiss() }
                        .onFailure { android.widget.Toast.makeText(context, "Could not save the note: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
                }
            }) { Text("Save note", color = Color.Black, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Black) } })
}

/** The hit map: marks along the book where the matches are; tap one to go there. */
@Composable
fun HitMapStrip(positions: List<Int>, total: Int, current: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (positions.isEmpty() || total <= 1) return
    val n = 120
    val bins = remember(positions, total) {
        val m = LinkedHashMap<Int, Pair<Int, Int>>()   // bin → (first hit, count)
        positions.forEachIndexed { k, p -> val b = (((p - 1).toFloat() / (total - 1)) * n).toInt().coerceIn(0, n - 1); m[b] = (m[b]?.first ?: k) to ((m[b]?.second ?: 0) + 1) }
        m
    }
    val max = bins.values.maxOf { it.second }
    val curBin = if (current in positions.indices) (((positions[current] - 1).toFloat() / (total - 1)) * n).toInt().coerceIn(0, n - 1) else -1
    BoxWithConstraints(modifier.fillMaxWidth().height(16.dp).border(1.dp, Color(0xFFD4D4D8))) {
        val w = maxWidth
        bins.forEach { (b, e) ->
            val h = 5.dp + 11.dp * (e.second.toFloat() / max)
            Box(Modifier.offset(x = w * (b.toFloat() / n)).align(Alignment.BottomStart).width((w / n).coerceAtLeast(2.dp)).height(h)
                .background(if (b == curBin) Color(0xFFDC2626) else Color(0xFFCA8A04)).clickable { onPick(e.first) })
        }
    }
}

/** Recent and saved searches as chips; tap to search again. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SavedSearchesRow(refresh: Int, onApply: (JSONObject) -> Unit) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(SearchIndex.savedSearches(context)) }
    LaunchedEffect(refresh) { saved = SearchIndex.savedSearches(context) }
    fun list(k: String) = saved.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
    val pinned = list("pinned"); val recent = list("recent").take(8)
    if (pinned.isEmpty() && recent.isEmpty()) return
    fun label(e: JSONObject) = e.optString("q") + (if (e.optString("mode") == "exact") " (exact)" else "")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (pinned.isNotEmpty()) Text("SAVED", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = SGrey, modifier = Modifier.align(Alignment.CenterVertically))
        pinned.forEach { e ->
            InkChip("★ " + label(e), false, { onApply(e) })
            InkChip("×", false, { saved = SearchIndex.updateSaved(context, "unpin", e) })
        }
        if (recent.isNotEmpty()) Text("RECENT", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = SGrey, modifier = Modifier.align(Alignment.CenterVertically))
        recent.forEach { e -> InkChip(label(e), false, { onApply(e) }) }
        if (recent.isNotEmpty()) InkChip("Clear recent", false, { saved = SearchIndex.updateSaved(context, "clear", null) })
    }
}

/** The AI search methods: key, label, what it does. */
val aiMethods = listOf(
    Triple("guided", "Guided", "The AI chosen in Settings → AI turns your question into the words authors would use (Norwegian and English), the normal search finds passages with them, and the AI picks the best and says why. Only those passages are sent to the AI."),
    Triple("gemini", "Meaning · Gemini", "Compares the meaning of your question with every page, so passages count even when no words match. Needs the Gemini meaning index (below); your question is sent to Google."),
    Triple("local", "Meaning · offline", "The same, with a small model on the tablet: nothing leaves the tablet. Needs the offline model and its index (below)."))

/** Runs an AI search (on a background thread). */
fun runAiSearch(ctx: android.content.Context, method: String, q: String, docId: Long?, filters: SearchIndex.Filters?): com.example.reader.AiSearch.Answer = when (method) {
    "gemini" -> com.example.reader.AiSearch.semantic(ctx, q, com.example.reader.AiSearch.GEMINI, docId, filters)
    "local" -> com.example.reader.AiSearch.semantic(ctx, q, com.example.reader.AiSearch.LOCAL, docId, filters)
    else -> com.example.reader.AiSearch.guided(ctx, q, docId, filters)
}

/** The meaning indexes (Gemini and offline): how far, and the buttons to build, stop, download and delete. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiIndexPanel() {
    val context = LocalContext.current
    val p by com.example.reader.AiSearch.progress.collectAsState()
    val dl by com.example.reader.AiSearch.download.collectAsState()
    var counts by remember { mutableStateOf<Pair<Pair<Int, Int>, Pair<Int, Int>>?>(null) }
    var hasKey by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(false) }
    LaunchedEffect(p.running, p.done, dl.running) {
        while (true) {
            withContext(Dispatchers.IO) {
                counts = com.example.reader.AiSearch.counts(context, com.example.reader.AiSearch.GEMINI) to com.example.reader.AiSearch.counts(context, com.example.reader.AiSearch.LOCAL)
                hasKey = com.example.reader.AiPreferences(context).readKey(com.example.reader.AiProvider.GEMINI).isNotBlank()
                model = com.example.reader.LocalEmbedder.installed(context.filesDir)
            }
            if (!p.running && !dl.running) break
            kotlinx.coroutines.delay(3000)
        }
    }
    val (g, l) = counts ?: ((0 to 0) to (0 to 0))
    Column(Modifier.fillMaxWidth().border(1.dp, Color.Black).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("MEANING INDEXES", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
        Text("Gemini: ${g.first} of ${g.second} pages" + (if (p.running && p.src == "gemini") " · ${p.note}" else "") + (if (!hasKey) " · add a Gemini API key in Settings → AI first" else ""),
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Black)
        Text("Sends every page's text to Google once. The free quota is limited, so a big library can take hours (it waits and continues by itself); a paid key is quick and costs about one or two dollars.", fontSize = 12.sp, color = SGrey)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (p.running && p.src == "gemini") InkChip("Stop", false, { com.example.reader.AiSearch.stop() })
            else if (hasKey && !p.running) InkChip(if (g.first > 0) "Update Gemini index" else "Build Gemini index", false, { com.example.reader.AiSearch.start(context, com.example.reader.AiSearch.GEMINI) })
            if (g.first > 0 && !(p.running && p.src == "gemini")) InkChip("Delete Gemini index", false, { com.example.reader.AiSearch.delete(context, com.example.reader.AiSearch.GEMINI); counts = null })
        }
        HorizontalDivider(color = Color(0xFFE4E4E7))
        Text(if (!model) "Offline: model not downloaded" else "Offline: ${l.first} of ${l.second} pages" + (if (p.running && p.src == "local") " · ${p.note}" else ""),
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Black)
        if (dl.running) Text("Downloading the model: ${dl.done / 1048576} of ${dl.total / 1048576} MB", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Black)
        if (dl.error.isNotBlank()) Text("Download: ${dl.error}", fontSize = 12.sp, color = Color(0xFFB91C1C))
        Text("A small multilingual model (about 135 MB) on the tablet: nothing leaves it. Building the index takes a few hours on the tablet; it runs in the background and can be stopped and continued.", fontSize = 12.sp, color = SGrey)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!model) { if (!dl.running) InkChip("Download the model (135 MB)", false, { com.example.reader.AiSearch.downloadModel(context) }) }
            else if (p.running && p.src == "local") InkChip("Stop", false, { com.example.reader.AiSearch.stop() })
            else if (!p.running) InkChip(if (l.first > 0) "Update offline index" else "Build offline index", false, { com.example.reader.AiSearch.start(context, com.example.reader.AiSearch.LOCAL) })
            if (model && !(p.running && p.src == "local")) InkChip("Delete model and index", false, { com.example.reader.AiSearch.delete(context, com.example.reader.AiSearch.LOCAL, model = true); model = false; counts = null })
        }
        if (p.error.isNotBlank() && !p.running) Text("Last error: ${p.error}", fontSize = 12.sp, color = Color(0xFFB91C1C))
    }
}
