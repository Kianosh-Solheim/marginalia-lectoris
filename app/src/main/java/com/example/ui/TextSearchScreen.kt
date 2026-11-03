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
@Composable
fun TextSearchHost(onOpenDocument: (DocumentEntity) -> Unit, onOpenDocumentAtPage: (DocumentEntity, Int, HighlightEntity?) -> Unit) {
    if (!textSearchOpen.value) return
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var whole by remember { mutableStateOf(context.getSharedPreferences("search", 0).getBoolean("whole", false)) }
    var exact by remember { mutableStateOf(context.getSharedPreferences("search", 0).getBoolean("exact", false)) }
    var books by remember { mutableStateOf<List<Triple<DocumentEntity, Int, List<SearchIndex.Hit>>>>(emptyList()) }
    var notes by remember { mutableStateOf<List<SearchIndex.NoteHit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val progress by SearchIndex.progress.collectAsState()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { SearchIndex.kick(context); runCatching { focus.requestFocus() } }
    LaunchedEffect(query, whole, exact) {
        val q = query.trim()
        if (q.length < 2) { books = emptyList(); notes = emptyList(); return@LaunchedEffect }
        delay(300)
        busy = true
        val r = withContext(Dispatchers.IO) {
            val dao = AppDatabase.getInstance(context).documentDao()
            val pq = SearchIndex.prepare(context, q, whole, exact)
            SearchIndex.search(context, q, whole, perBook = 5, prepared = pq).mapNotNull { (id, rr) -> dao.getDocumentById(id)?.let { Triple(it, rr.first, rr.second) } } to
                SearchIndex.searchNotes(context, q, whole, limit = 60, prepared = pq)
        }
        books = r.first
        notes = r.second
        busy = false
    }
    fun open(d: DocumentEntity, hit: SearchIndex.Hit) {
        pendingFind.value = PendingFind(d.id, query.trim(), whole, hit.loc, hit.label, exact)
        textSearchOpen.value = false
        if (d.fileType.equals("PDF", true)) onOpenDocumentAtPage(d, hit.loc, null) else onOpenDocument(d)
    }

    Dialog(onDismissRequest = { textSearchOpen.value = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f).border(1.5.dp, Color.Black), color = Color.White) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Search in the books", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color.Black, modifier = Modifier.weight(1f))
                    InkChip("Fuzzy", !exact, { exact = false; context.getSharedPreferences("search", 0).edit().putBoolean("exact", false).apply() })
                    InkChip("Exact", exact, { exact = true; context.getSharedPreferences("search", 0).edit().putBoolean("exact", true).apply() })
                    InkChip("Whole words", whole, { whole = !whole; context.getSharedPreferences("search", 0).edit().putBoolean("whole", whole).apply() })
                    InkChip("Close", false, { textSearchOpen.value = false })
                }
                Box(Modifier.fillMaxWidth().border(1.5.dp, Color.Black).padding(10.dp)) {
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(fontSize = 18.sp, color = Color.Black), cursorBrush = SolidColor(Color.Black),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = {}),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus))
                    if (query.isEmpty()) Text("Words or a sentence…", color = Color(0xFF9CA3AF), fontSize = 18.sp)
                }
                val total = books.sumOf { it.second }
                Text(buildString {
                    if (busy) append("Searching…") else if (query.trim().length >= 2) append("Found on $total page${if (total == 1) "" else "s"} in ${books.size} book${if (books.size == 1) "" else "s"} · ${notes.size} note${if (notes.size == 1) "" else "s"}")
                    else append(if (exact) "Exact: the words in this order, as one sentence. Case, accents, punctuation and line-break hyphens are ignored."
                        else "Fuzzy: the words close together, in any order; small misspellings are found too; words also find longer forms (vold → volden).")
                    if (progress.running) append("\nPreparing books for search: ${progress.done} of ${progress.total} ready · now “${progress.current}” " +
                        (if (progress.phase == "ocr") "(reading scanned pages with OCR: ${progress.page} of ${progress.pages})" else if (progress.pages > 0) "(page ${progress.page} of ${progress.pages})" else "") +
                        if (progress.ocrLeft > 0 && progress.phase != "ocr") " · ${progress.ocrLeft} scanned pages wait for OCR" else "")
                }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TGrey)
                LazyColumn(Modifier.fillMaxSize()) {
                    if (notes.isNotEmpty()) {
                        item { Text("YOUR NOTES", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Black, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
                        items(notes.take(30), key = { "n" + it.note.id }) { n ->
                            Column(Modifier.fillMaxWidth().clickable {
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
                    items(books, key = { "b" + it.first.id }) { (d, count, hits) ->
                        Column(Modifier.fillMaxWidth().padding(top = 12.dp).border(1.dp, Color.Black)) {
                            Row(Modifier.fillMaxWidth().background(Color(0xFFFAFAFA)).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.Black, maxLines = 2)
                                    if (d.author.isNotBlank()) Text(d.author.replace("; ", ", "), fontSize = 12.sp, color = TGrey, maxLines = 1)
                                }
                                Text("$count p.", fontFamily = FontFamily.Monospace, color = Color.Black)
                            }
                            hits.forEach { h ->
                                Column(Modifier.fillMaxWidth().clickable { open(d, h) }.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                    Text(if (d.fileType.equals("PDF", true)) "p. ${h.label.ifBlank { h.loc.toString() }}" else h.label.ifBlank { "pos. ${h.loc}" },
                                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TGrey)
                                    Text(marked(h.snippet, h.start, h.end), fontSize = 14.sp, color = Color.Black)
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
