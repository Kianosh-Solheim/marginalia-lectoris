package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.data.local.DocumentEntity
import com.example.reader.SearchIndex
import kotlinx.coroutines.launch

/** A search asked for from the library's search, to be shown when the book opens. */
data class PendingFind(val docId: Long, val query: String, val whole: Boolean, val loc: Int, val label: String, val exact: Boolean = false, val typos: Boolean = false)
val pendingFind = mutableStateOf<PendingFind?>(null)

/** One match in the open book: [page] is the reader's page (1-based). */
data class FindHit(val page: Int, val where: String, val snippet: String, val start: Int, val end: Int)

/** The find bar's state, kept per book while it is open. */
class FindState {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    var whole by mutableStateOf(false)
    /** Exact: the search is one sentence, word for word. Fuzzy: the words close together, small misspellings allowed. */
    var exact by mutableStateOf(false)
    /** Also similar spellings (fuzzy only; otherwise tried only when nothing is found). */
    var typos by mutableStateOf(false)
    var hits by mutableStateOf<List<FindHit>>(emptyList())
    var index by mutableIntStateOf(-1)
    var busy by mutableStateOf(false)
    var searched by mutableStateOf("")
}

/** Matches in the reader's own pages (EPUB, DAISY, text): page by page, in memory. */
suspend fun findInPages(ctx: android.content.Context, pages: List<com.example.reader.EpubPage>, query: String, whole: Boolean, exact: Boolean, typos: Boolean = false): List<FindHit> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val q = SearchIndex.prepare(ctx, query, whole, exact, typos)
    fun scan(): List<FindHit> {
        val out = ArrayList<FindHit>()
        pages.forEachIndexed { i, p ->
            val text = p.paragraphs.joinToString("\n")
            if (text.isBlank()) return@forEachIndexed
            SearchIndex.findIn(text, q).forEach { (s, a, b) -> out += FindHit(i + 1, p.pageEquivalentLabel.takeIf { it.isNotBlank() }?.let { "p. $it" } ?: p.chapterTitle, s, a, b) }
        }
        return out
    }
    val out = scan()
    // Nothing found: try similar spellings.
    if (out.isEmpty() && !typos && SearchIndex.addVariants(ctx, q)) scan() else out
}

/** Matches in a PDF, from the full-text index (the book is read first if it has not been yet). */
suspend fun findInPdf(ctx: android.content.Context, doc: DocumentEntity, query: String, whole: Boolean, exact: Boolean, typos: Boolean = false): List<FindHit> =
    SearchIndex.searchBook(ctx, doc, query, whole, exact, kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SearchIndex.prepare(ctx, query, whole, exact, typos) }).map { h -> FindHit(h.loc, if (h.label.isNotBlank() && h.label != h.loc.toString()) "p. ${h.label} (PDF ${h.loc})" else "p. ${h.loc}", h.snippet, h.start, h.end) }

private fun snippetText(h: FindHit) = buildAnnotatedString {
    append(h.snippet.substring(0, h.start.coerceIn(0, h.snippet.length)))
    withStyle(SpanStyle(background = Color(0xFFFDE047), fontWeight = FontWeight.Bold)) { append(h.snippet.substring(h.start.coerceIn(0, h.snippet.length), h.end.coerceIn(h.start, h.snippet.length))) }
    append(h.snippet.substring(h.end.coerceIn(0, h.snippet.length)))
}

/**
 * The find bar at the bottom of the reader: type, Enter to search, ‹ › to go between matches,
 * "List" for all of them with their passages.
 */
@Composable
fun FindBar(state: FindState, search: suspend (String, Boolean) -> List<FindHit>, onJump: (FindHit) -> Unit, modifier: Modifier = Modifier,
            statusOf: (() -> SearchIndex.Status)? = null) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(Unit) { if (state.searched.isBlank() && state.query.isBlank()) { state.exact = context.getSharedPreferences("search", 0).getBoolean("exact", false); state.typos = context.getSharedPreferences("search", 0).getBoolean("typos", false) } }
    var listOpen by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun go(i: Int) {
        if (state.hits.isEmpty()) return
        state.index = (i + state.hits.size) % state.hits.size
        onJump(state.hits[state.index])
    }
    fun run(thenGo: Boolean = true) {
        val q = state.query.trim()
        if (q.isEmpty()) { state.hits = emptyList(); state.index = -1; return }
        state.busy = true
        scope.launch {
            state.hits = runCatching { search(q, state.whole) }.getOrDefault(emptyList())
            state.searched = q; state.busy = false; state.index = -1
            if (thenGo && state.hits.isNotEmpty()) go(0)
        }
    }
    LaunchedEffect(Unit) { if (state.query.isBlank()) runCatching { focus.requestFocus() } }
    // A PDF that is still being read for search: show how far, and search again as more pages come in.
    var status by remember { mutableStateOf<SearchIndex.Status?>(null) }
    val progress by SearchIndex.progress.collectAsState()
    val latestStatusOf by rememberUpdatedState(statusOf)
    LaunchedEffect(statusOf != null) {
        if (latestStatusOf == null) return@LaunchedEffect
        while (true) {
            val st = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { latestStatusOf?.invoke() } ?: break
            val changed = status?.let { it.pagesDone != st.pagesDone || it.ocrLeft != st.ocrLeft || it.state != st.state } ?: false
            status = st
            if (changed && state.searched.isNotBlank() && !state.busy) {
                val keep = state.index
                state.hits = runCatching { search(state.searched, state.whole) }.getOrDefault(state.hits)
                state.index = keep.coerceAtMost(state.hits.size - 1)
            }
            kotlinx.coroutines.delay(if (st.state == "done" || st.state == "none") 15000 else 2500)
        }
    }

    Column(modifier.fillMaxWidth().background(Color.White).border(1.5.dp, Color.Black).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f).border(1.dp, Color.Black).padding(horizontal = 8.dp, vertical = 8.dp)) {
                BasicTextField(state.query, { state.query = it }, singleLine = true, textStyle = TextStyle(fontSize = 16.sp, color = Color.Black), cursorBrush = SolidColor(Color.Black),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (state.searched != state.query.trim()) run() else go(state.index + 1) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus))
                if (state.query.isEmpty()) Text("Search in this book…", color = Color(0xFF9CA3AF), fontSize = 16.sp)
            }
            fun setExact(v: Boolean) {
                if (state.exact == v) return
                state.exact = v
                context.getSharedPreferences("search", 0).edit().putBoolean("exact", v).apply()
                if (state.searched.isNotBlank()) run(false)
            }
            InkChip("Fuzzy", !state.exact, { setExact(false) })
            InkChip("Exact", state.exact, { setExact(true) })
            if (!state.exact) InkChip("Typos", state.typos, {
                state.typos = !state.typos
                context.getSharedPreferences("search", 0).edit().putBoolean("typos", state.typos).apply()
                if (state.searched.isNotBlank()) run(false)
            })
            InkChip("Whole words", state.whole, { state.whole = !state.whole; if (state.searched.isNotBlank()) run(false) })
            InkChip("‹", false, { go(state.index - 1) })
            InkChip("›", false, { if (state.searched != state.query.trim()) run() else go(state.index + 1) })
            InkChip("List", listOpen, { listOpen = true })
            InkChip("×", false, { state.open = false })
        }
        status?.let { st ->
            val line = when (st.state) {
                "done" -> null
                "ocr" -> "Text searchable · ${st.ocrLeft} scanned page${if (st.ocrLeft == 1) "" else "s"} still to read with OCR" +
                    if (progress.running && progress.phase == "ocr") " (now: ${progress.page} of ${progress.pages}${if (progress.current.isNotBlank()) ", ${progress.current}" else ""})" else ""
                "reading" -> "Reading this book for search: page ${st.pagesDone} of ${st.pagesTotal} — matches so far are shown"
                "queued" -> if (st.pagesDone > 0) "Waiting to finish reading for search (${st.pagesDone} of ${st.pagesTotal} pages read)" else "Waiting to be read for search — it is next in line"
                else -> null
            }
            if (line != null) Text(line, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.Black,
                modifier = Modifier.padding(top = 4.dp).fillMaxWidth().background(Color(0xFFFFFBEB)).border(1.dp, Color.Black).padding(horizontal = 6.dp, vertical = 3.dp))
        }
        Text(when {
            state.busy -> "Searching…"
            state.searched.isBlank() -> if (state.exact) "Exact: the words in this order, as one sentence (case, accents and punctuation ignored)."
                else "Fuzzy: the words close together, in any order. \"Quotes\" keep a phrase together. Typos: also similar spellings (tried by itself when nothing is found)."
            state.hits.isEmpty() -> "Nothing found for “${state.searched}”."
            else -> "${state.hits.size} match${if (state.hits.size == 1) "" else "es"}" + if (state.index >= 0) " · ${state.index + 1} of ${state.hits.size} · ${state.hits[state.index].where}" else ""
        }, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF52525B), modifier = Modifier.padding(top = 4.dp))
    }

    if (listOpen) {
        AlertDialog(onDismissRequest = { listOpen = false }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
            title = { Text("“${state.searched}” · ${state.hits.size}", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    itemsIndexed(state.hits.take(1000)) { i, h ->
                        Column(Modifier.fillMaxWidth().background(if (i == state.index) Color(0xFFFEF9C3) else Color.White)
                            .clickable { listOpen = false; go(i) }.padding(vertical = 7.dp)) {
                            Text(h.where, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF52525B))
                            Text(snippetText(h), fontSize = 14.sp, color = Color.Black)
                        }
                        HorizontalDivider(color = Color(0xFFE4E4E7))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { listOpen = false }) { Text("Close", color = Color.Black) } })
    }
}
