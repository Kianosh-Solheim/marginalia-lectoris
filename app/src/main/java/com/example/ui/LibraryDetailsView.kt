package com.example.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.reader.BibField
import com.example.reader.BibRecord
import com.example.reader.BibStore
import com.example.reader.Citations
import com.example.reader.EditedVolumes
import com.example.reader.FolderBooks
import com.example.reader.NoteSuggestionEngine
import com.example.reader.People
import com.example.reader.Zotero
import com.example.reader.ZoteroPlaceholders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val DGrey = Color(0xFF6B6B6B)
private val DRule = Color(0xFFE4E4E7)

/** A column of the details table. */
private enum class DCol(val label: String, val width: Dp, val numeric: Boolean = false) {
    CREATOR("Creator", 150.dp), YEAR("Year", 56.dp, true), TITLE("Title", 360.dp), CITEKEY("Citation Key", 220.dp),
    TYPE("Item Type", 120.dp), FILE("File", 70.dp), NOTES("Notes", 56.dp, true), PROGRESS("Read", 56.dp, true),
    TAGS("Tags", 170.dp), ADDED("Date Added", 110.dp, true), ZOTERO("Zotero", 60.dp), SEARCH("Searchable", 120.dp)
}

private val DEFAULT_COLS = listOf(DCol.CREATOR, DCol.YEAR, DCol.TITLE, DCol.CITEKEY, DCol.FILE, DCol.NOTES)

/** What the table shows for one item. */
private class DRow(val doc: DocumentEntity, val rec: BibRecord, val notes: Int) {
    val creator = shortCreator(doc.author)
    val year = Regex("\\d{4}").find(rec[BibField.YEAR])?.value ?: rec[BibField.YEAR]
    val citekey = runCatching { Citations.citeKey(doc, rec) }.getOrDefault("")
    val type = typeLabel(doc, rec.entryType)
    val file = when { ZoteroPlaceholders.isPlaceholder(doc) -> "No file"; FolderBooks.isFolder(doc) -> "Folder"; else -> doc.fileType }
    val progress = if (ZoteroPlaceholders.isPlaceholder(doc) || FolderBooks.isFolder(doc)) -1 else (doc.readingProgress * 100).toInt()
    val tags = NoteSuggestionEngine.parseTagsList(doc.tags).joinToString(", ")
    val zotero = Zotero.linkOf(doc.id) != null
    var searchState: String = ""

    fun text(c: DCol): String = when (c) {
        DCol.CREATOR -> creator; DCol.YEAR -> year; DCol.TITLE -> doc.title; DCol.CITEKEY -> citekey; DCol.TYPE -> type
        DCol.FILE -> file; DCol.NOTES -> notes.toString(); DCol.PROGRESS -> if (progress < 0) "" else "$progress%"
        DCol.TAGS -> tags; DCol.ADDED -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(doc.dateAdded)); DCol.ZOTERO -> if (zotero) "✓" else ""
        DCol.SEARCH -> searchState
    }

    fun sortKey(c: DCol): Comparable<*> = when (c) {
        DCol.NOTES -> notes; DCol.PROGRESS -> progress; DCol.ADDED -> doc.dateAdded
        else -> text(c).lowercase(Locale.ROOT)
    }
}

private fun shortCreator(author: String): String {
    val people = People.split(author.replace(Regex("\\s*\\(ed\\.?\\)\\s*", RegexOption.IGNORE_CASE), " ").trim())
    if (people.isEmpty() || people[0].equals("Unknown Author", true)) return ""
    fun family(p: String) = if (p.contains(',')) p.substringBefore(',').trim() else p.trim().substringAfterLast(' ')
    return when (people.size) { 1 -> family(people[0]); 2 -> "${family(people[0])} and ${family(people[1])}"; else -> "${family(people[0])} et al." }
}

private fun typeLabel(doc: DocumentEntity, entryType: String): String = when {
    FolderBooks.isFolder(doc) -> "Folder book"
    else -> when (entryType) {
        "book" -> "Book"; "article" -> "Journal article"; "incollection", "inbook" -> "Book section"; "phdthesis" -> "Thesis"
        "techreport" -> "Report"; "misc" -> "Other"; else -> if (doc.category.equals("ARTICLE", true)) "Article" else "Book"
    }
}

private fun prefs(ctx: Context) = ctx.getSharedPreferences("library_details", Context.MODE_PRIVATE)

/**
 * The library as a table, in the manner of Zotero: columns of your choice (tap a heading to sort),
 * rows that open to show their chapters and notes, and the chosen item's fields on the right,
 * edited in place.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryDetailsView(
    documents: List<DocumentEntity>,
    allHighlights: List<HighlightEntity>,
    onOpenDocument: (DocumentEntity) -> Unit,
    onOpenDocumentAtPage: (DocumentEntity, Int, HighlightEntity?) -> Unit,
    onLongPress: (DocumentEntity) -> Unit,
    onUpdateDocumentInfo: (DocumentEntity, String, String, String) -> Unit,
    onUpdateSummary: (Long, String) -> Unit,
    onChangeCategory: (DocumentEntity, String) -> Unit,
    selection: LibrarySelection? = null
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { BibStore.init(context); Zotero.init(context); EditedVolumes.init(context) } }
    var cols by remember {
        mutableStateOf(prefs(context).getString("cols", null)?.split(',')?.mapNotNull { n -> runCatching { DCol.valueOf(n) }.getOrNull() }?.takeIf { it.isNotEmpty() } ?: DEFAULT_COLS)
    }
    var sort by remember { mutableStateOf(runCatching { DCol.valueOf(prefs(context).getString("sort", "TITLE")!!) }.getOrDefault(DCol.TITLE)) }
    var asc by remember { mutableStateOf(prefs(context).getBoolean("asc", true)) }
    var selected by remember { mutableStateOf(prefs(context).getLong("selected", 0L)) }
    val open = remember { mutableStateMapOf<Long, Boolean>() }
    var bibVersion by remember { mutableIntStateOf(0) }
    var choosing by remember { mutableStateOf(false) }

    val notesBy = remember(allHighlights) { allHighlights.groupBy { it.documentId } }
    val indexing by com.example.reader.SearchIndex.progress.collectAsState()
    val rows = remember(documents, notesBy, bibVersion, if (DCol.SEARCH in cols) indexing.done * 100000 + indexing.page / 25 else 0) {
        documents.map { DRow(it, BibStore.get(it.id), notesBy[it.id]?.size ?: 0) }.onEach { r ->
            if (DCol.SEARCH in cols) r.searchState = runCatching { com.example.reader.SearchIndex.status(context, r.doc) }.getOrNull()?.let { st ->
                when (st.state) {
                    "done" -> "✓"; "ocr" -> "✓ (OCR ${st.ocrLeft} left)"
                    "reading" -> if (st.pagesTotal > 0) "reading ${100 * st.pagesDone / st.pagesTotal}%" else "reading"
                    "queued" -> "waiting"; else -> ""
                }
            }.orEmpty()
        }
    }
    val sorted = remember(rows, sort, asc) {
        val cmp = Comparator<DRow> { a, b ->
            @Suppress("UNCHECKED_CAST")
            val r = (a.sortKey(sort) as Comparable<Any>).compareTo(b.sortKey(sort) as Any)
            if (r != 0) (if (asc) r else -r) else a.doc.title.compareTo(b.doc.title, true)
        }
        rows.sortedWith(cmp)
    }
    fun saveCols(c: List<DCol>) { cols = c; prefs(context).edit().putString("cols", c.joinToString(",") { it.name }).apply() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 880.dp
        val fullWidth = maxWidth
        val tableWidth = 28.dp + cols.fold(0.dp) { a, c -> a + c.width }
        Row(Modifier.fillMaxSize().border(1.dp, Color.Black)) {
            // ---------------------------------------------------------------- table
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${rows.size} item${if (rows.size == 1) "" else "s"}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = DGrey, modifier = Modifier.weight(1f))
                    InkChip("Columns…", false, { choosing = true })
                }
                Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                    Column(Modifier.width(maxOf(tableWidth, if (wide) fullWidth - 420.dp else fullWidth))) {
                        // Header
                        Row(Modifier.fillMaxWidth().border(width = 0.dp, color = Color.Transparent).background(Color.White)) {
                            if (selection?.active == true) Spacer(Modifier.width(28.dp))
                            Spacer(Modifier.width(28.dp))
                            cols.forEach { c ->
                                Text(c.label + if (sort == c) (if (asc) " ▲" else " ▼") else "",
                                    fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    textAlign = if (c.numeric) TextAlign.End else TextAlign.Start,
                                    modifier = Modifier.then(if (c == DCol.TITLE) Modifier.weight(1f).widthIn(min = c.width) else Modifier.width(c.width))
                                        .clickable {
                                            if (sort == c) asc = !asc else { sort = c; asc = !c.numeric }
                                            prefs(context).edit().putString("sort", sort.name).putBoolean("asc", asc).apply()
                                        }.padding(horizontal = 8.dp, vertical = 8.dp))
                            }
                        }
                        HorizontalDivider(color = Color.Black)
                        LazyColumn(Modifier.fillMaxSize()) {
                            sorted.forEachIndexed { i, r ->
                                val d = r.doc
                                val isOpen = open[d.id] == true
                                val canOpen = r.notes > 0 || FolderBooks.isFolder(d) || (EditedVolumes.get(context, d.id)?.enabled == true)
                                item(key = d.id) {
                                    val picking = selection?.active == true
                                    val sel = if (picking) selection!!.isSelected(d.id) else selected == d.id
                                    Row(Modifier.fillMaxWidth().background(if (sel) Color.Black else if (i % 2 == 1) Color(0xFFFAFAFA) else Color.White)
                                        .combinedClickable(
                                            onClick = {
                                                if (picking) selection!!.toggle(d.id)
                                                else { selected = d.id; prefs(context).edit().putLong("selected", d.id).apply() }
                                            },
                                            onDoubleClick = { if (!picking) onOpenDocument(d) },
                                            onLongClick = { if (picking) selection!!.toggle(d.id) else onLongPress(d) }),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        if (picking) Text(if (sel) "☑" else "☐", color = if (sel) Color.White else Color.Black, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.width(28.dp))
                                        Text(if (!canOpen) "" else if (isOpen) "▾" else "▸", color = if (sel) Color.White else Color.Black, fontSize = 14.sp, textAlign = TextAlign.Center,
                                            modifier = Modifier.width(28.dp).clickable(enabled = canOpen) { open[d.id] = !isOpen }.padding(vertical = 9.dp))
                                        cols.forEach { c ->
                                            Text(r.text(c), fontSize = 14.sp, color = if (sel) Color.White else Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                fontFamily = if (c == DCol.CITEKEY) FontFamily.Monospace else FontFamily.Default,
                                                textAlign = if (c.numeric) TextAlign.End else TextAlign.Start,
                                                modifier = Modifier.then(if (c == DCol.TITLE) Modifier.weight(1f).widthIn(min = c.width) else Modifier.width(c.width)).padding(horizontal = 8.dp, vertical = 9.dp))
                                        }
                                    }
                                }
                                if (isOpen) item(key = "c${d.id}") {
                                    ChildRows(d, notesBy[d.id].orEmpty(), onOpenDocument, onOpenDocumentAtPage)
                                }
                            }
                        }
                    }
                }
            }
            // ---------------------------------------------------------------- item panel
            val chosen = rows.firstOrNull { it.doc.id == selected }
            if (wide) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(Color.Black))
                Box(Modifier.width(420.dp).fillMaxHeight()) {
                    if (chosen == null) Text("Choose an item to see and edit its details.", color = DGrey, modifier = Modifier.padding(16.dp))
                    else key(chosen.doc.id) {
                        ItemPanel(chosen, notesBy[chosen.doc.id].orEmpty(), onOpenDocument, onOpenDocumentAtPage, onUpdateDocumentInfo, onUpdateSummary, onChangeCategory) { bibVersion++ }
                    }
                }
            }
        }
        if (!wide && selected != 0L) {
            rows.firstOrNull { it.doc.id == selected }?.let { chosen ->
                androidx.compose.ui.window.Dialog(onDismissRequest = { selected = 0L }) {
                    Surface(color = Color.White, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).border(1.5.dp, Color.Black, RoundedCornerShape(4.dp))) {
                        ItemPanel(chosen, notesBy[chosen.doc.id].orEmpty(), onOpenDocument, onOpenDocumentAtPage, onUpdateDocumentInfo, onUpdateSummary, onChangeCategory) { bibVersion++ }
                    }
                }
            }
        }
    }

    if (choosing) {
        AlertDialog(onDismissRequest = { choosing = false }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
            title = { Text("Columns", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DCol.entries.forEach { c ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = c != DCol.TITLE) {
                            saveCols(DCol.entries.filter { x -> if (x == c) c !in cols else x in cols })
                        }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = c in cols, onCheckedChange = null, enabled = c != DCol.TITLE,
                                colors = CheckboxDefaults.colors(checkedColor = Color.Black, checkmarkColor = Color.White))
                            Text(c.label, modifier = Modifier.padding(start = 8.dp), color = Color.Black)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Done", color = Color.Black, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { saveCols(DEFAULT_COLS) }) { Text("Default columns", color = DGrey) } })
    }
}

/** The opened row: its chapters (edited volume or folder book) and its notes. */
@Composable
private fun ChildRows(d: DocumentEntity, notes: List<HighlightEntity>, onOpenDocument: (DocumentEntity) -> Unit, onOpenAt: (DocumentEntity, Int, HighlightEntity?) -> Unit) {
    val context = LocalContext.current
    var parts by remember(d.id) { mutableStateOf<List<DocumentEntity>>(emptyList()) }
    LaunchedEffect(d.id) {
        if (FolderBooks.isFolder(d)) parts = withContext(Dispatchers.IO) {
            FolderBooks.partsOf(d.id).mapNotNull { AppDatabase.getInstance(context).documentDao().getDocumentById(it) }
        }
    }
    val chapters = remember(d.id) { EditedVolumes.get(context, d.id)?.takeIf { it.enabled }?.chapters.orEmpty().withIndex().filter { it.value.isArticle } }
    Column(Modifier.fillMaxWidth().background(Color(0xFFF4F4F5)).padding(start = 36.dp, end = 8.dp, top = 2.dp, bottom = 4.dp)) {
        parts.forEachIndexed { i, p ->
            ChildLine("§", "${i + 1}  ${p.title}", p.author.takeIf { it.isNotBlank() && it != "Unknown Author" }?.replace("; ", ", ")) { onOpenDocument(p) }
        }
        chapters.forEach { (_, c) ->
            ChildLine("§", c.title, listOfNotNull(c.authors.takeIf { it.isNotBlank() }?.replace("; ", ", "), c.printedPages.takeIf { it.isNotBlank() }?.let { "pp. $it" }).joinToString(" · ").ifBlank { null }) {
                onOpenAt(d, c.startPage, null)
            }
        }
        notes.sortedBy { it.pageNumber }.forEach { n ->
            val loc = n.printedPageLabel.takeIf { it.isNotBlank() }?.let { "p. $it" } ?: n.chapterTitle
            ChildLine("✎", n.title.ifBlank { "Note" }, listOfNotNull(n.highlightedText.takeIf { it.isNotBlank() }?.take(120), loc.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { null }) {
                onOpenAt(d, n.pageNumber, n)
            }
        }
        if (parts.isEmpty() && chapters.isEmpty() && notes.isEmpty()) Text("No chapters or notes.", color = DGrey, fontSize = 13.sp, modifier = Modifier.padding(6.dp))
    }
}

@Composable
private fun ChildLine(mark: String, title: String, sub: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(mark, color = DGrey, fontSize = 13.sp, modifier = Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, fontSize = 12.sp, color = DGrey, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Fields in Zotero's order for each kind of item: the record field and its label. */
private fun fieldsFor(type: String): List<Pair<BibField, String>> = when (type) {
    "article" -> listOf(BibField.JOURNAL to "Publication", BibField.VOLUME to "Volume", BibField.ISSUE to "Issue", BibField.PAGES to "Pages", BibField.YEAR to "Date",
        BibField.SERIES to "Series", BibField.DOI to "DOI", BibField.ISSN to "ISSN", BibField.URL to "URL", BibField.LANGUAGE to "Language")
    "incollection", "inbook" -> listOf(BibField.BOOKTITLE to "Book Title", BibField.EDITOR to "Editor(s)", BibField.TRANSLATOR to "Translator", BibField.SERIES to "Series",
        BibField.VOLUME to "Volume", BibField.EDITION to "Edition", BibField.PLACE to "Place", BibField.PUBLISHER to "Publisher", BibField.YEAR to "Date",
        BibField.PAGES to "Pages", BibField.ISBN to "ISBN", BibField.DOI to "DOI", BibField.URL to "URL", BibField.LANGUAGE to "Language")
    "phdthesis" -> listOf(BibField.PUBLISHER to "University", BibField.PLACE to "Place", BibField.YEAR to "Date", BibField.PAGES to "Pages", BibField.DOI to "DOI", BibField.URL to "URL", BibField.LANGUAGE to "Language")
    "techreport" -> listOf(BibField.SERIES to "Report Series", BibField.ISSUE to "Report Number", BibField.PLACE to "Place", BibField.PUBLISHER to "Institution",
        BibField.YEAR to "Date", BibField.PAGES to "Pages", BibField.DOI to "DOI", BibField.URL to "URL", BibField.LANGUAGE to "Language")
    else -> listOf(BibField.EDITOR to "Editor(s)", BibField.TRANSLATOR to "Translator", BibField.SERIES to "Series", BibField.VOLUME to "Volume", BibField.EDITION to "Edition",
        BibField.PLACE to "Place", BibField.PUBLISHER to "Publisher", BibField.YEAR to "Date", BibField.PAGES to "Pages", BibField.ISBN to "ISBN",
        BibField.DOI to "DOI", BibField.URL to "URL", BibField.LANGUAGE to "Language")
}

private val TYPES = listOf("book" to "Book", "article" to "Journal article", "incollection" to "Book section", "phdthesis" to "Thesis", "techreport" to "Report", "misc" to "Other")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemPanel(
    row: DRow,
    notes: List<HighlightEntity>,
    onOpenDocument: (DocumentEntity) -> Unit,
    onOpenAt: (DocumentEntity, Int, HighlightEntity?) -> Unit,
    onUpdateDocumentInfo: (DocumentEntity, String, String, String) -> Unit,
    onUpdateSummary: (Long, String) -> Unit,
    onChangeCategory: (DocumentEntity, String) -> Unit,
    onBibChanged: () -> Unit
) {
    val context = LocalContext.current
    val d = row.doc
    var rec by remember(d.id) { mutableStateOf(row.rec) }
    val type = rec.entryType.ifBlank { if (d.category.equals("ARTICLE", true)) "article" else "book" }
    var checking by remember { mutableStateOf(false) }
    fun saveBib(f: BibField, v: String) {
        rec = rec.with(f, v); BibStore.put(d.id, rec); onBibChanged()
        ZoteroPlaceholders.pushSoon(context, d.id)
    }
    val isFolder = FolderBooks.isFolder(d)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(d.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.Black)
            Text(listOf(d.author.replace("; ", ", "), row.year).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 13.sp, color = DGrey)
        }
        HorizontalDivider(color = DRule)
        PanelSection("Info", true) {
            FieldRow("Item Type") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TYPES.forEach { (k, l) -> InkChip(l, type == k || (k == "incollection" && type == "inbook"), { rec = rec.copy(entryType = k); BibStore.put(d.id, rec); onBibChanged() }) }
                }
            }
            EditField("Title", d.title) { v -> if (v.isNotBlank()) onUpdateDocumentInfo(d, v, d.author, d.tags) }
            EditField(if (isFolder) "Editor(s)" else "Author(s)", People.split(d.author).joinToString("; "), placeholder = "Name Nameson; Other Person") { v ->
                onUpdateDocumentInfo(d, d.title, People.join(People.split(v)), d.tags)
            }
            fieldsFor(type).filter { !(isFolder && it.first == BibField.EDITOR) }.forEach { (f, label) ->
                EditField(label, rec[f], mono = f == BibField.DOI || f == BibField.ISBN || f == BibField.ISSN) { v -> saveBib(f, v) }
            }
            EditField("Citation Key", rec[BibField.CITEKEY], mono = true, placeholder = "${row.citekey} (automatic)") { v -> saveBib(BibField.CITEKEY, v) }
            FieldRow("Shelf") {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    InkChip("Books", !d.category.equals("ARTICLE", true), { if (d.category.equals("ARTICLE", true)) onChangeCategory(d, "BOOK") })
                    InkChip("Articles", d.category.equals("ARTICLE", true), { if (!d.category.equals("ARTICLE", true)) onChangeCategory(d, "ARTICLE") })
                }
            }
            FieldRow("Date Added") { Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(d.dateAdded)), fontSize = 14.sp, color = Color.Black) }
        }
        PanelSection("Abstract", true) {
            EditField("", d.summary, multiline = true, placeholder = "Add an abstract…") { v -> onUpdateSummary(d.id, v) }
        }
        val tags = NoteSuggestionEngine.parseTagsList(d.tags)
        PanelSection("Tags · ${tags.size}", true) {
            EditField("", tags.joinToString(", "), placeholder = "Add tags, separated by commas…") { v ->
                onUpdateDocumentInfo(d, d.title, d.author, NoteSuggestionEngine.joinTagsList(NoteSuggestionEngine.parseTagsList(v)))
            }
        }
        PanelSection("Notes · ${notes.size}", notes.isNotEmpty()) {
            if (notes.isEmpty()) Text("No notes yet.", color = DGrey, fontSize = 13.sp)
            notes.sortedBy { it.pageNumber }.take(60).forEach { n ->
                Column(Modifier.fillMaxWidth().clickable { onOpenAt(d, n.pageNumber, n) }.padding(vertical = 6.dp)) {
                    Text(n.title.ifBlank { "Note" }, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.Black)
                    if (n.highlightedText.isNotBlank()) Text(n.highlightedText.take(160), fontSize = 12.sp, color = DGrey, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (n.printedPageLabel.isNotBlank()) Text("p. ${n.printedPageLabel}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = DGrey)
                }
                HorizontalDivider(color = DRule)
            }
        }
        PanelSection("Attachment", true) {
            Text(when {
                ZoteroPlaceholders.isPlaceholder(d) -> "No file yet · from Zotero"
                isFolder -> "Folder book · ${FolderBooks.partsOf(d.id).size} chapters"
                else -> "${d.fileType} · ${d.totalPages} ${if (d.fileType.equals("PDF", true)) "pages" else "positions"}"
            }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Black)
            Text(java.io.File(d.filePath).name, fontSize = 12.sp, color = DGrey)
            Spacer(Modifier.height(6.dp))
            InkButton(if (ZoteroPlaceholders.isPlaceholder(d)) "Open (get the file)" else if (d.readingProgress > 0f) "Continue reading" else "Open", filled = true, onClick = { onOpenDocument(d) })
        }
        PanelSection("Zotero", true) {
            val key = Zotero.linkOf(d.id)
            when {
                Zotero.account(context) == null -> Text("Zotero is not connected (side menu ▸ Zotero).", color = DGrey, fontSize = 13.sp)
                key != null -> {
                    Text("▣ Linked to Zotero" + if (ZoteroPlaceholders.isSynced(context, d.id)) " · kept in step" else "", fontWeight = FontWeight.Bold, color = Color(0xFF15803D), fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    InkButton("Check citation", onClick = { checking = true })
                }
                else -> Text("Not linked to a Zotero item. Link it in the Zotero screen.", color = DGrey, fontSize = 13.sp)
            }
        }
    }
    if (checking) Zotero.linkOf(d.id)?.let { k -> ZoteroCheckDialog(d.id, null, k, onDismiss = { checking = false; rec = BibStore.get(d.id); onBibChanged() }) }
}

@Composable
private fun PanelSection(title: String, initiallyOpen: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val context = LocalContext.current
    val key = "sec_" + title.substringBefore(" ·")
    var open by remember { mutableStateOf(prefs(context).getBoolean(key, initiallyOpen)) }
    Row(Modifier.fillMaxWidth().clickable { open = !open; prefs(context).edit().putBoolean(key, open).apply() }.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(title.uppercase(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Black, modifier = Modifier.weight(1f))
        Text(if (open) "▾" else "▸", color = Color.Black)
    }
    if (open) Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp), content = content)
    HorizontalDivider(color = DRule)
}

@Composable
private fun FieldRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        if (label.isNotEmpty()) Text(label, fontSize = 12.sp, color = DGrey, textAlign = TextAlign.End, modifier = Modifier.width(104.dp).padding(end = 10.dp, top = 4.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

/** A field shown as text; tap to edit; Done or leaving the field saves. */
@Composable
private fun EditField(label: String, value: String, mono: Boolean = false, multiline: Boolean = false, placeholder: String = "", onSave: (String) -> Unit) {
    var editing by remember(value) { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(value) }
    var hadFocus by remember { mutableStateOf(false) }
    val style = TextStyle(fontSize = 14.sp, color = Color.Black, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    fun finish() { editing = false; hadFocus = false; if (text.trim() != value.trim()) onSave(text.trim()) }
    FieldRow(label) {
        if (!editing) {
            Text(value.ifBlank { placeholder }, style = style.copy(color = if (value.isBlank()) Color(0xFFA1A1AA) else Color.Black),
                modifier = Modifier.fillMaxWidth().clickable { editing = true }.padding(horizontal = 4.dp, vertical = 4.dp))
        } else {
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            BasicTextField(text, { text = it }, textStyle = style, cursorBrush = SolidColor(Color.Black), singleLine = !multiline,
                keyboardOptions = KeyboardOptions(imeAction = if (multiline) ImeAction.Default else ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { finish() }),
                modifier = Modifier.fillMaxWidth().border(1.dp, Color.Black).padding(4.dp)
                    .focusRequester(focus)
                    .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) finish() })
        }
    }
}
