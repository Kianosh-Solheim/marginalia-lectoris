package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.reader.SearchIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The library's full-text search is open. */
val textSearchOpen = mutableStateOf(false)

private val TGrey = Color(0xFF52525B)

private fun marked(s: String, a: Int, b: Int) = buildAnnotatedString {
    val x = a.coerceIn(0, s.length); val y = b.coerceIn(x, s.length)
    append(s.substring(0, x))
    withStyle(SpanStyle(background = Color(0xFFFDE047), fontWeight = FontWeight.Bold)) { append(s.substring(x, y)) }
    append(s.substring(y))
}

/** Search the text of every book, and the notes. Opens a match in the book with the find bar showing it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextSearchHost(onOpenDocument: (DocumentEntity) -> Unit, onOpenDocumentAtPage: (DocumentEntity, Int, HighlightEntity?) -> Unit) {
    if (!textSearchOpen.value) return
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("search", 0) }
    var query by remember { mutableStateOf("") }
    var whole by remember { mutableStateOf(prefs.getBoolean("whole", false)) }
    var exact by remember { mutableStateOf(prefs.getBoolean("exact", false)) }
    var typos by remember { mutableStateOf(prefs.getBoolean("typos", false)) }
    var usedTypos by remember { mutableStateOf(false) }
    // Filters, order and view (kept on the tablet)
    var fTag by remember { mutableStateOf(prefs.getString("f_tag", "").orEmpty()) }
    var fType by remember { mutableStateOf(prefs.getString("f_type", "").orEmpty()) }
    var fFrom by remember { mutableStateOf(prefs.getString("f_from", "").orEmpty()) }
    var fTo by remember { mutableStateOf(prefs.getString("f_to", "").orEmpty()) }
    var fNotes by remember { mutableStateOf(prefs.getBoolean("f_notes", false)) }
    var sort by remember { mutableStateOf(prefs.getString("sort", "relevance").orEmpty()) }
    var kwic by remember { mutableStateOf(prefs.getBoolean("kwic", false)) }
    var showFilters by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showSynonyms by remember { mutableStateOf(false) }
    var noteFor by remember { mutableStateOf<Pair<DocumentEntity, SearchIndex.Hit>?>(null) }
    var savedRefresh by remember { mutableIntStateOf(0) }
    var rerun by remember { mutableIntStateOf(0) }
    var info by remember { mutableStateOf("") }
    // AI search (runs only when "Ask AI" is pressed)
    var ai by remember { mutableStateOf(prefs.getBoolean("ai", false)) }
    var aiMethod by remember { mutableStateOf(prefs.getString("ai_method", "guided").orEmpty()) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiAns by remember { mutableStateOf<com.example.reader.AiSearch.Answer?>(null) }
    var aiDocs by remember { mutableStateOf<Map<Long, DocumentEntity>>(emptyMap()) }
    var aiErr by remember { mutableStateOf("") }
    var showIdx by remember { mutableStateOf(false) }
    fun saveF() { prefs.edit().putString("f_tag", fTag).putString("f_type", fType).putString("f_from", fFrom).putString("f_to", fTo).putBoolean("f_notes", fNotes).apply() }
    var books by remember { mutableStateOf<List<Triple<DocumentEntity, Int, List<SearchIndex.Hit>>>>(emptyList()) }
    var notes by remember { mutableStateOf<List<SearchIndex.NoteHit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val progress by SearchIndex.progress.collectAsState()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var allTags by remember { mutableStateOf<List<String>>(emptyList()) }
    val years = remember { HashMap<Long, Int>() }
    LaunchedEffect(Unit) {
        SearchIndex.kick(context); runCatching { focus.requestFocus() }
        allTags = withContext(Dispatchers.IO) { AppDatabase.getInstance(context).documentDao().getAllDocumentsSnapshot().flatMap { it.tags.split(',', ';').map { t -> t.trim().removePrefix("#") } }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() } }
    }
    fun entryNow() = JSONObject().put("q", query.trim()).put("mode", if (exact) "exact" else "fuzzy").put("whole", whole).put("typos", typos)
        .put("filters", JSONObject().put("tag", fTag).put("type", fType).put("yfrom", fFrom).put("yto", fTo).put("notes", fNotes))
    fun addRecent() { if (query.trim().length >= 2) { SearchIndex.updateSaved(context, "add", entryNow()); savedRefresh++ } }
    LaunchedEffect(query, whole, exact, typos, fTag, fType, fFrom, fTo, fNotes, kwic, rerun, ai) {
        if (ai) return@LaunchedEffect
        val q = query.trim()
        if (q.length < 2) { books = emptyList(); notes = emptyList(); info = ""; return@LaunchedEffect }
        delay(300)
        busy = true
        val filters = SearchIndex.Filters(tag = fTag, type = fType, yearFrom = fFrom.toIntOrNull(), yearTo = fTo.toIntOrNull(), hasNotes = fNotes)
        val r = withContext(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(context).documentDao()
            val pq = SearchIndex.prepare(context, q, whole, exact, typos && !exact, filters)
            val b = SearchIndex.search(context, q, whole, perBook = if (kwic) 40 else 5, prepared = pq).mapNotNull { (id, rr) -> dao.getDocumentById(id)?.let { Triple(it, rr.first, rr.second) } }
            usedTypos = pq.variants.isNotEmpty()
            b.forEach { (d, _, _) -> if (d.id !in years) years[d.id] = runCatching { com.example.reader.BibStore.get(d.id)?.get(com.example.reader.BibField.YEAR) }.getOrNull()?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() } ?: 0 }
            Triple(b, SearchIndex.searchNotes(context, q, whole, limit = 60, prepared = pq), pq.toJson())
        }
        books = r.first
        notes = r.second
        info = run {
            val P = r.third; val parts = ArrayList<String>()
            P.optJSONArray("synonyms")?.let { a -> for (i in 0 until a.length()) parts += a.getJSONArray(i).let { g -> (0 until g.length()).joinToString(" = ") { g.getString(it) } } }
            P.optJSONObject("stems")?.let { o -> if (o.length() > 0) parts += "word forms: " + o.keys().asSequence().joinToString(", ") { "$it → ${o.getString(it)}…" } }
            P.optJSONArray("exclude")?.let { a -> if (a.length() > 0) parts += "leaving out: " + (0 until a.length()).joinToString(", ") { i -> a.getJSONArray(i).let { w -> (0 until w.length()).joinToString(" ") { w.getString(it) } } } }
            val f = P.optJSONObject("filters")
            val fl = listOfNotNull(f?.optString("author")?.takeIf { it.isNotBlank() }?.let { "author $it" }, f?.optString("title")?.takeIf { it.isNotBlank() }?.let { "title $it" },
                f?.optString("tag")?.takeIf { it.isNotBlank() }?.let { "#$it" }, f?.optString("type")?.takeIf { it.isNotBlank() }?.let { if (it == "BOOK") "books" else "articles" },
                if (f != null && (!f.isNull("yearFrom") || !f.isNull("yearTo"))) "years ${f.opt("yearFrom").takeIf { it != JSONObject.NULL } ?: "…"}–${f.opt("yearTo").takeIf { it != JSONObject.NULL } ?: "…"}" else null,
                if (f?.optBoolean("hasNotes") == true) "with notes" else null)
            if (fl.isNotEmpty()) parts += "only " + fl.joinToString(", ")
            if (parts.isEmpty()) "" else "Also: " + parts.joinToString(" · ")
        }
        busy = false
    }
    fun askAi() {
        val q = query.trim(); if (q.isEmpty() || aiBusy) return
        aiBusy = true; aiErr = ""; aiAns = null
        val filters = SearchIndex.Filters(tag = fTag, type = fType, yearFrom = fFrom.toIntOrNull(), yearTo = fTo.toIntOrNull(), hasNotes = fNotes)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val a = runAiSearch(context, aiMethod, q, null, filters)
                    val dao = AppDatabase.getInstance(context).documentDao()
                    a to a.results.map { it.docId }.distinct().mapNotNull { dao.getDocumentById(it) }.associateBy { it.id }
                }
            }.onSuccess { (a, d) -> aiAns = a; aiDocs = d; SearchIndex.updateSaved(context, "add", JSONObject().put("q", q).put("mode", "ai").put("method", aiMethod)); savedRefresh++ }
                .onFailure { aiErr = it.message ?: it.toString() }
            aiBusy = false
        }
    }
    fun openAi(d: DocumentEntity, r: com.example.reader.AiSearch.Result) {
        // The passage is found again in the book by its first words.
        val words = SearchIndex.tokens(if (r.end > r.start) r.snippet.substring(r.start, r.end) else r.snippet.removePrefix("…")).take(if (r.end > r.start) 12 else 7)
        pendingFind.value = PendingFind(d.id, if (words.size >= 2) "\"" + words.joinToString(" ") + "\"" else "", false, r.loc, r.label, true)
        textSearchOpen.value = false
        if (d.fileType.equals("PDF", true)) onOpenDocumentAtPage(d, r.loc, null) else onOpenDocument(d)
    }
    fun open(d: DocumentEntity, hit: SearchIndex.Hit) {
        addRecent()
        pendingFind.value = PendingFind(d.id, query.trim(), whole, hit.loc, hit.label, exact, usedTypos)
        textSearchOpen.value = false
        if (d.fileType.equals("PDF", true)) onOpenDocumentAtPage(d, hit.loc, null) else onOpenDocument(d)
    }
    fun apply(e: JSONObject) {
        if (e.optString("mode") == "ai") { query = e.optString("q"); ai = true; aiMethod = e.optString("method", "guided"); return }
        ai = false
        query = e.optString("q"); exact = e.optString("mode") == "exact"; whole = e.optBoolean("whole"); typos = e.optBoolean("typos")
        e.optJSONObject("filters")?.let { f -> fTag = f.optString("tag"); fType = f.optString("type"); fFrom = f.optString("yfrom"); fTo = f.optString("yto"); fNotes = f.optBoolean("notes") }
        saveF()
    }
    fun insert(t: String) { query = (if (query.isBlank() || query.endsWith(" ")) query else "$query ") + t }
    fun where(d: DocumentEntity, h: SearchIndex.Hit) = if (d.fileType.equals("PDF", true)) "p. ${h.label.ifBlank { h.loc.toString() }}" else h.label.ifBlank { "pos. ${h.loc}" }
    val sorted = remember(books, sort) {
        when (sort) {
            "title" -> books.sortedBy { it.first.title.lowercase() }
            "author" -> books.sortedWith(compareBy({ it.first.author.lowercase() }, { it.first.title.lowercase() }))
            "year" -> books.sortedByDescending { years[it.first.id] ?: 0 }
            "yearold" -> books.sortedBy { (years[it.first.id] ?: 0).takeIf { y -> y > 0 } ?: 9999 }
            "lastread" -> books.sortedByDescending { it.first.lastReadTimestamp }
            else -> books.sortedByDescending { it.second }
        }
    }
    val filtersOn = fTag.isNotBlank() || fType.isNotBlank() || fFrom.isNotBlank() || fTo.isNotBlank() || fNotes

    Dialog(onDismissRequest = { textSearchOpen.value = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f).border(1.5.dp, Color.Black), color = Color.White) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Search in the books", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color.Black, modifier = Modifier.weight(1f))
                    if (!ai) {
                        InkChip("Fuzzy", !exact, { exact = false; prefs.edit().putBoolean("exact", false).apply() })
                        InkChip("Exact", exact, { exact = true; prefs.edit().putBoolean("exact", true).apply() })
                        if (!exact) InkChip("Typos", typos, { typos = !typos; prefs.edit().putBoolean("typos", typos).apply() })
                        InkChip("Whole words", whole, { whole = !whole; prefs.edit().putBoolean("whole", whole).apply() })
                    }
                    InkChip("✦ AI", ai, { ai = !ai; prefs.edit().putBoolean("ai", ai).apply() })
                    InkChip("Close", false, { textSearchOpen.value = false })
                }
                Box(Modifier.fillMaxWidth().border(1.5.dp, Color.Black).padding(10.dp)) {
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(fontSize = 18.sp, color = Color.Black), cursorBrush = SolidColor(Color.Black),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { if (ai) askAi() else addRecent() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus))
                    if (query.isEmpty()) Text(if (ai) "Describe what you are looking for, then Ask AI…" else "Words or a sentence…", color = Color(0xFF9CA3AF), fontSize = 18.sp)
                }
                if (ai) {
                    FlowRow(Modifier.fillMaxWidth().background(Color(0xFFF5F3FF)).border(1.dp, Color.Black).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        aiMethods.forEach { (k, l, _) -> InkChip(l, aiMethod == k, { aiMethod = k; prefs.edit().putString("ai_method", k).apply() }) }
                        InkChip(if (aiBusy) "✦ Asking…" else "✦ Ask AI", true, { askAi() })
                        InkChip(if (showIdx) "Meaning indexes ▴" else "Meaning indexes ▾", showIdx, { showIdx = !showIdx })
                        InkChip(if (filtersOn) "Filters ●" else "Filters", showFilters, { showFilters = !showFilters })
                        Text(aiMethods.first { it.first == aiMethod }.third, fontSize = 12.sp, color = TGrey, modifier = Modifier.fillMaxWidth())
                    }
                    if (showIdx) AiIndexPanel()
                }
                // Operators, as buttons
                if (!ai) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InkChip("“ ” Phrase", false, { insert("\"\"") })
                    InkChip("− Leave out", false, { insert("-") })
                    InkChip("OR", false, { insert("OR ") })
                    InkChip("author:", false, { insert("author:") })
                    InkChip("tag:", false, { insert("tag:") })
                    InkChip("year:", false, { insert("year:") })
                    InkChip(if (filtersOn) "Filters ●" else "Filters", showFilters, { showFilters = !showFilters })
                    InkChip("+ Synonym", false, { showSynonyms = true })
                    InkChip("☆ Save search", false, { if (query.isNotBlank()) { SearchIndex.updateSaved(context, "pin", entryNow()); savedRefresh++ } })
                    InkChip("? How to search", false, { showHelp = true })
                }
                if (showFilters) {
                    FlowRow(Modifier.fillMaxWidth().border(1.dp, Color.Black).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("ONLY", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TGrey, modifier = Modifier.align(Alignment.CenterVertically))
                        InkChip("All", fType.isBlank(), { fType = ""; saveF() }); InkChip("Books", fType == "BOOK", { fType = "BOOK"; saveF() }); InkChip("Articles", fType == "ARTICLE", { fType = "ARTICLE"; saveF() })
                        InkChip("With notes", fNotes, { fNotes = !fNotes; saveF() })
                        InkField(fFrom, { fFrom = it.filter(Char::isDigit).take(4); saveF() }, "Year from", Modifier.width(110.dp))
                        InkField(fTo, { fTo = it.filter(Char::isDigit).take(4); saveF() }, "Year to", Modifier.width(110.dp))
                        if (filtersOn) InkChip("Clear filters", false, { fTag = ""; fType = ""; fFrom = ""; fTo = ""; fNotes = false; saveF() })
                        Text("TAG", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TGrey, modifier = Modifier.align(Alignment.CenterVertically))
                        InkChip("Any", fTag.isBlank(), { fTag = ""; saveF() })
                        allTags.forEach { t -> InkChip("#$t", fTag.equals(t, true), { fTag = if (fTag.equals(t, true)) "" else t; saveF() }) }
                    }
                }
                SavedSearchesRow(savedRefresh) { apply(it) }
                if (!ai) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("SORT", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TGrey)
                    listOf("relevance" to "Most matches", "title" to "Title", "author" to "Author", "year" to "Newest", "yearold" to "Oldest", "lastread" to "Last read").forEach { (k, l) ->
                        InkChip(l, sort == k, { sort = k; prefs.edit().putString("sort", k).apply() })
                    }
                    Spacer(Modifier.weight(1f))
                    InkChip("Results", !kwic, { kwic = false; prefs.edit().putBoolean("kwic", false).apply() })
                    InkChip("Concordance", kwic, { kwic = true; prefs.edit().putBoolean("kwic", true).apply() })
                }
                val total = books.sumOf { it.second }
                if (ai) Text(when {
                    aiBusy -> if (aiMethod == "guided") "Asking the AI for search words, searching, and asking it to pick the best passages… (10–40 s)" else "Comparing meanings…"
                    aiErr.isNotBlank() -> aiErr
                    aiAns != null -> "${aiAns!!.results.size} passages" + (if (aiAns!!.note.isNotBlank()) " · ${aiAns!!.note}" else "") + (if (aiAns!!.terms.isNotEmpty()) "\nSearched for: " + aiAns!!.terms.joinToString(" · ") else "")
                    else -> "AI search runs only when you press “Ask AI”."
                }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = if (aiErr.isNotBlank()) Color(0xFFB91C1C) else TGrey)
                else Text(buildString {
                    if (busy) append("Searching…") else if (query.trim().length >= 2) append("Found on $total page${if (total == 1) "" else "s"} in ${books.size} book${if (books.size == 1) "" else "s"} · ${notes.size} note${if (notes.size == 1) "" else "s"}" + if (usedTypos) " · including similar spellings" else "")
                    else append(if (exact) "Exact: the words in this order, as one sentence. Case, accents, punctuation and line-break hyphens are ignored."
                        else "Fuzzy: the words close together, in any order; other word forms count too (vold → volden). Tap “? How to search” for more.")
                    if (info.isNotBlank() && !busy) append("\n$info")
                    if (progress.running && progress.phase == "update") append("\nUpdating the search index for better matching: ${progress.page} of ${progress.pages} pages")
                    else if (progress.running) append("\nPreparing books for search: ${progress.done} of ${progress.total} ready · now “${progress.current}” " +
                        (if (progress.phase == "ocr") "(reading scanned pages with OCR: ${progress.page} of ${progress.pages})" else if (progress.pages > 0) "(page ${progress.page} of ${progress.pages})" else "") +
                        if (progress.ocrLeft > 0 && progress.phase != "ocr") " · ${progress.ocrLeft} scanned pages wait for OCR" else "")
                }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TGrey)
                LazyColumn(Modifier.fillMaxSize()) {
                    if (ai) {
                        val rs = aiAns?.results.orEmpty()
                        items(rs.size) { i ->
                            val r = rs[i]; val d = aiDocs[r.docId] ?: return@items
                            Row(Modifier.fillMaxWidth().clickable { openAi(d, r) }.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.title + " · " + (if (d.fileType.equals("PDF", true)) "p. ${r.label.ifBlank { r.loc.toString() }}" else r.label.ifBlank { "pos. ${r.loc}" }) + " · ${(r.score * 100).toInt()}%",
                                        fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Black)
                                    if (r.why.isNotBlank()) Text("✦ " + r.why, fontSize = 13.sp, color = Color(0xFF5B21B6))
                                    Text(marked(r.snippet, r.start, r.end), fontSize = 14.sp, color = Color.Black)
                                }
                                InkChip("+ Note", false, { noteFor = d to SearchIndex.Hit(r.docId, r.loc, r.href, r.label, r.snippet, r.start, r.end, 0) })
                            }
                            HorizontalDivider(color = Color(0xFFE4E4E7))
                        }
                    } else if (kwic) {
                        // Concordance: every match with the search term in the middle.
                        val lines = sorted.flatMap { (d, _, hits) -> hits.map { h -> d to h } }
                        items(lines.size) { i ->
                            val (d, h) = lines[i]
                            val t = h.snippet; val a = h.start.coerceIn(0, t.length); val b = h.end.coerceIn(a, t.length)
                            Row(Modifier.fillMaxWidth().clickable { open(d, h) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(t.substring(0, a).takeLast(70), fontSize = 13.sp, color = Color.Black, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f),
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
                                Text(t.substring(a, b), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black, maxLines = 1, modifier = Modifier.background(Color(0xFFFDE047)).padding(horizontal = 3.dp))
                                Text(t.substring(b).take(70), fontSize = 13.sp, color = Color.Black, maxLines = 1, modifier = Modifier.weight(1f), overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
                                Text("${d.author.split(';', ',').first().trim()} · ${where(d, h)}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TGrey, maxLines = 1, modifier = Modifier.width(170.dp).padding(start = 8.dp))
                                InkChip("+ Note", false, { noteFor = d to h })
                            }
                            HorizontalDivider(color = Color(0xFFF0F0F0))
                        }
                    } else {
                    if (notes.isNotEmpty()) {
                        item { Text("YOUR NOTES", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Black, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
                        items(notes.take(30), key = { "n" + it.note.id }) { n ->
                            Column(Modifier.fillMaxWidth().clickable {
                                addRecent()
                                scope.launch {
                                    val d = withContext(Dispatchers.IO) { AppDatabase.getInstance(context).documentDao().getDocumentById(n.note.documentId) }
                                    textSearchOpen.value = false
                                    d?.let { onOpenDocumentAtPage(it, n.note.pageNumber, n.note) }
                                }
                            }.padding(vertical = 6.dp)) {
                                Text(n.note.title.ifBlank { "Note" } + " · " + n.docTitle, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Black)
                                Text(marked(n.snippet, n.start, n.end), fontSize = 14.sp, color = Color.Black)
                            }
                            HorizontalDivider(color = Color(0xFFE4E4E7))
                        }
                    }
                    items(sorted, key = { "b" + it.first.id }) { (d, count, hits) ->
                        Column(Modifier.fillMaxWidth().padding(top = 12.dp).border(1.dp, Color.Black)) {
                            Row(Modifier.fillMaxWidth().background(Color(0xFFFAFAFA)).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.Black, maxLines = 2)
                                    val y = years[d.id]?.takeIf { it > 0 }
                                    if (d.author.isNotBlank() || y != null) Text(listOfNotNull(d.author.replace("; ", ", ").takeIf { it.isNotBlank() }, y?.toString()).joinToString(" · "), fontSize = 12.sp, color = TGrey, maxLines = 1)
                                }
                                Text("$count p.", fontFamily = FontFamily.Monospace, color = Color.Black)
                            }
                            hits.forEach { h ->
                                Row(Modifier.fillMaxWidth().clickable { open(d, h) }.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.Top) {
                                    Column(Modifier.weight(1f)) {
                                        Text(where(d, h), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TGrey)
                                        Text(marked(h.snippet, h.start, h.end), fontSize = 14.sp, color = Color.Black)
                                    }
                                    InkChip("+ Note", false, { noteFor = d to h })
                                }
                            }
                            if (count > hits.size) Text("Open the book to see all $count pages →", fontSize = 12.sp, color = TGrey,
                                modifier = Modifier.fillMaxWidth().clickable { hits.firstOrNull()?.let { open(d, it) } }.padding(10.dp))
                        }
                    }
                    }
                }
            }
        }
    }
    if (showHelp) SearchHelpDialog { showHelp = false }
    if (showSynonyms) SynonymDialog(query, { showSynonyms = false }, { rerun++ })
    noteFor?.let { (d, h) -> NoteFromHitDialog(d, h.loc, h.label, if (d.fileType.equals("PDF", true)) "" else h.label, h.snippet, h.start, h.end, { noteFor = null }) }
}
