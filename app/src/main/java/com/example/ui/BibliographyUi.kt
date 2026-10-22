package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.reader.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The document open in the reader, for "Set as …" and "Copy as citation" in the selection menu. */
val LocalReaderDocument = staticCompositionLocalOf<DocumentEntity?> { null }

private val Grey = Color(0xFF3F3F46)

@Composable
private fun bibFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color.Black, unfocusedBorderColor = Color.Black,
    focusedLabelColor = Color.Black, cursorColor = Color.Black,
    focusedContainerColor = Color.White, unfocusedContainerColor = Color.White
)

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

/** Removes markdown italics markers for plain-text clipboard use. */
private fun plain(s: String) = s.replace("*", "")

// ----------------------------------------------------------------- Set as …

/**
 * "Set as …": stores the selected text in one bibliographic field of the open document
 * (DOI, ISBN, year, publisher, journal, …), or — for edited volumes — as the title or author of the
 * chapter being read, or as the book's editor. A preview shows what is stored now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetAsDialog(selection: String, document: DocumentEntity, onDismiss: () -> Unit, pageNumber: Int = 0, chapterTitle: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { BibStore.init(context) }
    // field: a bibliographic field; chapterField: "title" / "author" of the current chapter
    var field by remember { mutableStateOf<BibField?>(null) }
    var chapterField by remember { mutableStateOf<String?>(null) }
    var value by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    // Tags: split from the selection; each can be switched off before saving. Target: the book or this chapter.
    var tagMode by remember { mutableStateOf(false) }
    var tagList by remember { mutableStateOf<List<String>>(emptyList()) }
    var tagOff by remember { mutableStateOf<Set<String>>(emptySet()) }
    var tagsToChapter by remember { mutableStateOf(false) }
    val fields = BibField.entries.filter { it != BibField.CITEKEY && it != BibField.EDITOR }
    val isBook = !document.category.equals("ARTICLE", true)
    val current = remember(document.id, pageNumber, chapterTitle, refresh) {
        BibStore.init(context)
        val v = EditedVolumes.get(context, document.id)?.takeIf { it.enabled }
        val idx = v?.let { EditedVolumes.chapterIndexFor(it, pageNumber, chapterTitle) } ?: -1
        v?.chapters?.getOrNull(idx)
    }
    val editors = remember(document.id, refresh) { BibStore.get(document.id)[BibField.EDITOR] }

    fun pickTags() {
        field = null; chapterField = null; tagMode = true
        tagList = TagSplitter.split(selection); tagOff = emptySet(); value = tagList.joinToString(", ")
    }

    fun pickChapter(kind: String) {
        tagMode = false
        field = null; chapterField = kind
        value = if (kind == "author") ChapterAuthorGuesser.nameLine(selection) ?: selection.trim().removePrefix("by ").trim()
        else selection.replace(Regex("\\s+"), " ").trim().trimEnd('.')
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Color.White,
        shape = RoundedCornerShape(4.dp),
        title = { Text("Set selection as…", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = Color.Black) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("“${selection.take(160)}”", fontFamily = FontFamily.Serif, fontSize = 13.sp, color = Grey, maxLines = 3)
                if (isBook) {
                    // Preview of what is stored for this chapter and book.
                    Column(
                        Modifier.fillMaxWidth().border(1.dp, Color.Black, RoundedCornerShape(2.dp)).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text("THIS CHAPTER", fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Grey)
                        if (current != null) {
                            Text(listOf(current.label, current.title).filter { it.isNotBlank() }.joinToString(". "), fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Black)
                            Text(current.authors.replace("; ", ", ").ifBlank { "Author not set" }, fontFamily = FontFamily.Serif, fontSize = 13.sp,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = if (current.authors.isBlank()) Grey else Color.Black)
                        } else {
                            Text(if (chapterTitle.isNotBlank()) "“$chapterTitle” isn't in the chapter list yet; setting a chapter title or author adds it (and reads the book's contents)."
                                else "No chapter list yet; setting a chapter title or author creates one.", fontSize = 12.sp, color = Grey)
                        }
                        Text("Book editor(s): ${editors.replace("; ", ", ").ifBlank { "not set" }}", fontSize = 12.sp, color = if (editors.isBlank()) Grey else Color.Black)
                    }
                    Text("Edited volume", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Grey)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        InkChip("Chapter title", chapterField == "title", { pickChapter("title") })
                        InkChip("Chapter author", chapterField == "author", { pickChapter("author") })
                        InkChip("Chapter abstract", chapterField == "abstract", { pickChapter("abstract") })
                        InkChip("Book editor", field == BibField.EDITOR, {
                            tagMode = false
                            chapterField = null; field = BibField.EDITOR
                            value = ChapterAuthorGuesser.nameLine(selection) ?: BibFieldCleaner.clean(BibField.EDITOR, selection)
                        })
                    }
                    Text("Bibliographic details", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Grey)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InkChip("Tags", tagMode, { pickTags() })
                    fields.forEach { f ->
                        InkChip(f.label, field == f, {
                            tagMode = false
                            chapterField = null
                            field = f
                            value = BibFieldCleaner.clean(f, selection)
                        })
                    }
                    if (!isBook) InkChip(BibField.EDITOR.label, field == BibField.EDITOR, { chapterField = null; field = BibField.EDITOR; value = BibFieldCleaner.clean(BibField.EDITOR, selection) })
                }
                if (tagMode) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it; tagList = TagSplitter.split(it); tagOff = tagOff.intersect(tagList.toSet()) },
                        label = { Text("Tags (split at commas, semicolons, ·)") },
                        shape = RoundedCornerShape(4.dp),
                        colors = bibFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (tagList.isEmpty()) Text("No tags found in the selection; type them above.", fontSize = 12.sp, color = Grey)
                    else {
                        Text("Tap a tag to leave it out:", fontSize = 12.sp, color = Grey)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            tagList.forEach { t ->
                                InkChip("#$t", t !in tagOff, { tagOff = if (t in tagOff) tagOff - t else tagOff + t })
                            }
                        }
                    }
                    if (isBook) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            InkChip("Add to the book", !tagsToChapter, { tagsToChapter = false })
                            InkChip("Add to this chapter", tagsToChapter, { tagsToChapter = true })
                        }
                    }
                    val existing = if (tagsToChapter) current?.tags.orEmpty() else document.tags
                    if (existing.isNotBlank()) Text("Already: ${existing}", fontSize = 12.sp, color = Grey)
                }
                val label = when (chapterField) { "title" -> "Chapter title"; "author" -> "Chapter author(s)"; "abstract" -> "Chapter abstract"; else -> field?.let { if (it == BibField.EDITOR && isBook) "Book editor(s)" else it.label } }
                if (label != null && !tagMode) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text(label) },
                        supportingText = {
                            val existing = when (chapterField) {
                                "title" -> current?.title.orEmpty()
                                "author" -> current?.authors.orEmpty()
                                "abstract" -> current?.abstract.orEmpty()
                                else -> field?.let { BibStore.get(document.id)[it] }.orEmpty()
                            }
                            val adds = (chapterField == "author" && current?.authorSource == "user" && existing.isNotBlank()) ||
                                (field == BibField.EDITOR && existing.isNotBlank())
                            when {
                                existing.isNotBlank() && adds -> Text("Adds to: ${existing.replace("; ", ", ")} (edit to replace)", color = Grey)
                                existing.isNotBlank() -> Text("Replaces: ${existing.replace("; ", ", ")}", color = Grey)
                                value.isBlank() -> Text("Couldn't find it in the selection, type it here", color = Grey)
                            }
                        },
                        singleLine = field != BibField.TITLE && chapterField != "title" && chapterField != "abstract",
                        shape = RoundedCornerShape(4.dp),
                        colors = bibFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color.Black, trackColor = Color(0xFFE4E4E7))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (field != null || chapterField != null || (tagMode && tagList.any { it !in tagOff })) && value.isNotBlank(), onClick = {
                if (tagMode) {
                    val chosen = tagList.filter { it !in tagOff }
                    busy = true
                    scope.launch {
                        val msg = runCatching {
                            if (tagsToChapter) EditedVolumes.setChapterField(context, document, pageNumber, chapterTitle, "tags", chosen.joinToString(", "))
                            else withContext(Dispatchers.IO) {
                                val dao = AppDatabase.getInstance(context).documentDao()
                                val fresh = dao.getDocumentById(document.id) ?: document
                                val merged = (NoteSuggestionEngine.parseTagsList(fresh.tags) + chosen).distinctBy { it.lowercase() }
                                dao.updateTags(document.id, merged.joinToString(", "))
                                "${chosen.size} tag${if (chosen.size == 1) "" else "s"} added to the book"
                            }
                        }.getOrElse { "Couldn't save: ${it.message}" }
                        busy = false
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                    return@TextButton
                }
                val cf = chapterField
                if (cf != null) {
                    busy = true
                    scope.launch {
                        val msg = runCatching { EditedVolumes.setChapterField(context, document, pageNumber, chapterTitle, cf, value) }
                            .getOrElse { "Couldn't save: ${it.message}" }
                        busy = false
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                    return@TextButton
                }
                val f = field ?: return@TextButton
                if (f == BibField.EDITOR) {
                    val existing = BibStore.get(document.id)[f]
                    val names = value.split(';').map { it.trim() }.filter { it.isNotBlank() }
                    val merged = (existing.split(';').map { it.trim() }.filter { it.isNotBlank() } + names).distinctBy { it.lowercase() }
                    BibStore.set(document.id, f, merged.joinToString("; "))
                    Toast.makeText(context, if (existing.isBlank()) "Book editor saved" else "Editor added", Toast.LENGTH_SHORT).show()
                } else {
                    BibStore.set(document.id, f, value)
                    Toast.makeText(context, "${f.label} saved", Toast.LENGTH_SHORT).show()
                }
                onDismiss()
            }) { Text(if (busy) "Saving…" else "Save", color = Color.Black, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel", color = Grey) } }
    )
}

// ----------------------------------------------------------------- Copy as citation

/** Shows the passage cited in every style; the default style (Settings) is first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CiteDialog(
    document: DocumentEntity,
    quote: String,
    page: String,
    onDismiss: () -> Unit,
    pageNumber: Int = 0,
    chapterTitle: String = ""
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { BibStore.init(context) }
    // In an edited volume the passage is cited as its chapter (author, “Chapter,” in Book, edited by …).
    val chapterCite = remember(document.id, pageNumber, chapterTitle) {
        BibStore.init(context)
        val v = com.example.reader.EditedVolumes.get(context, document.id)?.takeIf { it.enabled }
        val idx = v?.let { com.example.reader.EditedVolumes.chapterIndexFor(it, pageNumber, chapterTitle) } ?: -1
        if (v != null && idx >= 0) Triple(v.chapters[idx], v, idx) else null
    }
    var citeChapter by remember { mutableStateOf(chapterCite != null) }
    val bookDocument = document
    @Suppress("NAME_SHADOWING")
    val document = remember(citeChapter, chapterCite) {
        if (citeChapter && chapterCite != null) com.example.reader.EditedVolumes.chapterAsDocument(bookDocument, chapterCite.second, chapterCite.third).first else bookDocument
    }
    var rec by remember(document.id) { BibStore.init(context); mutableStateOf(BibStore.get(document.id)) }
    var defaultStyle by remember { mutableStateOf(Citations.defaultStyle(context)) }
    val styles = remember(defaultStyle) { listOf(defaultStyle) + CitationStyle.entries.filter { it != defaultStyle } }
    var editPage by remember { mutableStateOf(page) }
    var showBibEditor by remember { mutableStateOf(false) }

    if (showBibEditor) {
        BibliographyEditorDialog(bookDocument) {
            showBibEditor = false
            rec = if (citeChapter && chapterCite != null) {
                com.example.reader.EditedVolumes.chapterAsDocument(bookDocument, chapterCite.second, chapterCite.third).second
            } else BibStore.get(document.id)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(4.dp),
        title = { Text("Copy as citation", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = Color.Black) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (chapterCite != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InkChip("This chapter", citeChapter, { citeChapter = true })
                        InkChip("Whole book", !citeChapter, { citeChapter = false })
                    }
                    if (citeChapter && chapterCite.first.authors.isBlank()) {
                        Text("The chapter's author is not set. Add it in the book's chapter list (tap the book in the library).", fontSize = 12.sp, color = Grey)
                    }
                }
                OutlinedTextField(
                    value = editPage,
                    onValueChange = { editPage = it },
                    label = { Text("Page") },
                    singleLine = true,
                    shape = RoundedCornerShape(4.dp),
                    colors = bibFieldColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                val missing = buildList {
                    if (Citations.people(document.author).isEmpty()) add("author")
                    if (Citations.year(document, rec) == "n.d.") add("year")
                }
                if (missing.isNotEmpty()) {
                    Text("Missing ${missing.joinToString(" and ")}. Select it in the text and use Set as…, or edit the details.", fontSize = 12.sp, color = Grey)
                }
                // One style at a time, picked from a dropdown; the choice becomes the default everywhere.
                var styleMenu by remember { mutableStateOf(false) }
                val style = defaultStyle
                Box {
                    InkButton("Style: ${style.label} ▾", onClick = { styleMenu = true })
                    DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }, modifier = Modifier.background(Color.White)) {
                        CitationStyle.entries.forEach { st ->
                            DropdownMenuItem(
                                text = { Text(st.label + if (st == style) "  ✓" else "", fontWeight = if (st == style) FontWeight.Bold else FontWeight.Normal) },
                                onClick = { Citations.setDefaultStyle(context, st); defaultStyle = st; styleMenu = false }
                            )
                        }
                    }
                }
                run {
                    val inText = Citations.inText(style, document, rec, editPage)
                    val ref = Citations.reference(style, document, rec)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.Black, RoundedCornerShape(2.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(style.label.uppercase() + if (citeChapter && chapterCite != null) " · BOOK SECTION" else "",
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Grey)
                        Text(plain(ref), fontFamily = FontFamily.Serif, fontSize = 13.sp, color = Color.Black)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (quote.isNotBlank()) InkButton("Quote + $inText", icon = Icons.Default.ContentCopy, filled = true, onClick = {
                                copyToClipboard(context, "Citation", "“${quote.trim()}” $inText")
                            })
                            InkButton(if (style == CitationStyle.CHICAGO_NOTES) "Footnote" else "In-text", filled = quote.isBlank(), onClick = { copyToClipboard(context, "Citation", inText) })
                            InkButton("Reference", onClick = { copyToClipboard(context, "Reference", plain(ref)) })
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InkButton("Copy BibTeX", icon = Icons.Default.ContentCopy, onClick = { copyToClipboard(context, "BibTeX", Citations.bibtex(document, rec)) })
                    InkButton("Copy RIS", icon = Icons.Default.ContentCopy, onClick = { copyToClipboard(context, "RIS", Ris.export(document, rec)) })
                    InkButton("Share .ris", onClick = {
                        runCatching {
                            val dir = java.io.File(context.cacheDir, "exports/sections").apply { mkdirs() }
                            val f = java.io.File(dir, MarkdownExporter.sanitizeFilename(rec[BibField.TITLE].ifBlank { document.title }).ifBlank { "reference" } + ".ris")
                            f.writeText(Ris.export(document, rec), Charsets.UTF_8)
                            MarkdownExporter.shareFile(context, f, "application/x-research-info-systems", "Share RIS")
                        }
                    })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Color.Black, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { showBibEditor = true }) { Text("Edit details", color = Grey) } }
    )
}

// ----------------------------------------------------------------- full editor

/** Edit every bibliographic field, look up by DOI/ISBN, and copy BibTeX. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BibliographyEditorDialog(document: DocumentEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var record by remember { mutableStateOf(BibRecord()) }
    var author by remember { mutableStateOf(document.author) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    LaunchedEffect(document.id) {
        withContext(Dispatchers.IO) {
            BibStore.init(context)
            BibStore.seedFromFile(document)
        }
        record = BibStore.get(document.id)
    }

    // ---- Look up details: what is found is shown next to what is filled in; the user chooses.
    var summary by remember { mutableStateOf(document.summary) }
    var lookStatus by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<MetadataLookup.Candidate>>(emptyList()) }
    var compare by remember { mutableStateOf<List<MetadataLookup.Row>>(emptyList()) }
    val use = remember { mutableStateMapOf<String, Boolean>() }

    fun showCompare(f: MetadataLookup.Found) {
        candidates = emptyList()
        val all = MetadataLookup.compare(record, author, summary, document.title, f)
        val rows = all.filter { !it.same }
        use.clear(); rows.forEach { use[it.key] = it.current.isBlank() }
        compare = rows
        val from = f.sources.joinToString(", ").ifBlank { "the lookup" }
        lookStatus = if (rows.isEmpty()) "From $from: everything found already matches." else
            "From $from · ${rows.size} differ" + (all.size - rows.size).let { if (it > 0) " · $it already match" else "" } + ". Choose for each, then Apply."
    }

    fun lookup(search: Boolean) {
        busy = true; compare = emptyList(); candidates = emptyList()
        lookStatus = if (search) "Searching by title…" else "Looking up…"
        scope.launch {
            val title = record[BibField.TITLE].ifBlank { document.title }
            val res = withContext(Dispatchers.IO) {
                if (search) MetadataLookup.search(title, author).map { MetadataLookup.Outcome(candidates = it, note = if (it.isEmpty()) "No matches." else "Matches by title — pick the right one:") }
                else MetadataLookup.run(record, title, author)
            }
            res.onSuccess { o ->
                val f = o.found
                if (f != null) showCompare(f) else { candidates = o.candidates; lookStatus = o.note.ifBlank { "Pick the right match:" } }
            }.onFailure { lookStatus = it.message ?: "The lookup failed." }
            busy = false
        }
    }

    fun applyChosen() {
        var r = record
        var n = 0
        compare.filter { use[it.key] == true }.forEach { row ->
            n++
            when (row.key) {
                "AUTHOR" -> author = row.found
                "ABSTRACT" -> summary = row.found
                "TYPE" -> r = r.copy(entryType = row.found)
                else -> runCatching { BibField.valueOf(row.key) }.getOrNull()?.let { r = r.with(it, row.found) }
            }
        }
        record = r
        compare = emptyList()
        lookStatus = if (n > 0) "Filled in $n field${if (n == 1) "" else "s"} — check them, then Save." else "Nothing changed."
    }

    var replaceAll by remember { mutableStateOf(false) }
    fun applyRis(text: String) {
        busy = true; status = "Reading RIS…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { Ris.apply(context, document, text, replaceAll) } }
                .onSuccess { res ->
                    record = BibStore.get(document.id)
                    author = res.document.author
                    status = if (res.filled > 0) "Filled ${res.filled} field${if (res.filled == 1) "" else "s"} from the RIS record" + (if (replaceAll) " (replacing what was there)." else " (empty fields only).") else "The RIS record had nothing new."
                }
                .onFailure { status = it.message ?: "Could not read the RIS record." }
            busy = false
        }
    }
    val risOpen = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() }
            if (text.isNullOrBlank()) status = "Could not read that file." else applyRis(text)
        }
    }
    val risSave = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/x-research-info-systems")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(Ris.export(document.copy(author = author), record).toByteArray(Charsets.UTF_8)) }; true }.getOrDefault(false)
            }
            status = if (ok) "RIS file saved." else "Could not save the RIS file."
        }
    }

    val core = listOf(BibField.TITLE, BibField.YEAR, BibField.PUBLISHER, BibField.PLACE, BibField.DOI, BibField.ISBN, BibField.JOURNAL, BibField.VOLUME, BibField.ISSUE, BibField.PAGES)
    val visible = if (showAll) BibField.entries.toList() else core

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(4.dp),
        title = { Text("Bibliographic details", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = Color.Black) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Type", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Grey)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("book" to "Book", "article" to "Article", "incollection" to "Chapter", "phdthesis" to "Thesis", "techreport" to "Report", "misc" to "Other").forEach { (k, l) ->
                        val effective = record.entryType.ifBlank { if (record[BibField.JOURNAL].isNotBlank() || document.category.equals("ARTICLE", true)) "article" else "book" }
                        InkChip(l, effective == k, { record = record.copy(entryType = k) })
                    }
                }
                Text("Look up online — DOI, ISBN, ISSN or title", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Grey)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InkButton("Look up details", icon = Icons.Default.Search, filled = true, enabled = !busy, onClick = { lookup(false) })
                    InkButton("Search by title", enabled = !busy, onClick = { lookup(true) })
                }
                val ids = listOf(BibField.DOI, BibField.ISBN, BibField.ISSN).filter { record[it].isNotBlank() }
                Text(
                    if (ids.isNotEmpty()) "Uses the ${ids.joinToString(", ") { it.name }} — Crossref, Open Library and Google Books." else "No DOI, ISBN or ISSN yet: searches by title and author.",
                    fontSize = 12.sp, color = Grey
                )
                if (lookStatus.isNotBlank()) Text(lookStatus, fontSize = 12.sp, color = Color.Black)
                if (candidates.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().border(1.dp, Color.Black, RoundedCornerShape(2.dp))) {
                        candidates.forEachIndexed { i, c ->
                            Column(Modifier.fillMaxWidth().clickable { lookStatus = "Chosen: ${c.label}"; showCompare(c.found) }.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(c.label, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Black)
                                Text(c.detail, fontSize = 11.sp, color = Grey)
                            }
                            if (i < candidates.lastIndex) HorizontalDivider(color = Color(0xFFE4E4E7))
                        }
                    }
                }
                if (compare.isNotEmpty()) {
                    fun shown(key: String, v: String) = if (key == "TYPE") when (v) {
                        "book" -> "Book"; "article" -> "Article"; "incollection", "inbook" -> "Book section (chapter)"; "phdthesis" -> "Thesis"; "techreport" -> "Report"; "misc" -> "Other"; "" -> "—"; else -> v
                    } else v.ifBlank { "—" }
                    Column(Modifier.fillMaxWidth().border(1.dp, Color.Black, RoundedCornerShape(2.dp))) {
                        compare.forEachIndexed { i, row ->
                            val on = use[row.key] == true
                            Column(Modifier.fillMaxWidth().background(if (on) Color(0xFFF4F4F5) else Color.White).padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(row.label.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Grey)
                                Text("Now: " + shown(row.key, row.current), fontSize = 13.sp, color = if (on) Grey else Color.Black, maxLines = 4,
                                    textDecoration = if (on && row.current.isNotBlank()) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                                Text("Found: " + shown(row.key, row.found), fontSize = 13.sp, color = if (on) Color.Black else Grey, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 4)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    InkChip(if (row.current.isBlank()) "Leave empty" else "Keep", !on, { use[row.key] = false })
                                    InkChip(if (row.current.isBlank()) "Fill in" else "Use new", on, { use[row.key] = true })
                                }
                            }
                            if (i < compare.lastIndex) HorizontalDivider(color = Color(0xFFE4E4E7))
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InkButton("Only fill empty", onClick = { compare.forEach { use[it.key] = it.current.isBlank() } })
                        InkButton("Use all new", onClick = { compare.forEach { use[it.key] = true } })
                        InkButton("Discard", onClick = { compare = emptyList(); lookStatus = "" })
                        InkButton("Apply chosen", filled = true, onClick = { applyChosen() })
                    }
                }
                Text("RIS (Zotero, EndNote, Mendeley, library catalogues)", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Grey)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InkButton("Import RIS file…", enabled = !busy, onClick = { risOpen.launch(arrayOf("application/x-research-info-systems", "text/plain", "application/octet-stream", "*/*")) })
                    InkButton("Paste RIS", icon = Icons.Default.ContentPaste, enabled = !busy, onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if (text.isBlank()) status = "The clipboard is empty." else applyRis(text)
                    })
                    InkButton("Export RIS file…", onClick = { risSave.launch(MarkdownExporter.sanitizeFilename(document.title) + ".ris") })
                    InkButton("Copy RIS", icon = Icons.Default.ContentCopy, onClick = { copyToClipboard(context, "RIS", Ris.export(document.copy(author = author), record)) })
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = replaceAll, onCheckedChange = { replaceAll = it }, colors = CheckboxDefaults.colors(checkedColor = Color.Black, checkmarkColor = Color.White))
                    Text("Importing replaces fields that are already filled", fontSize = 12.sp, color = Color.Black)
                }
                if (status.isNotBlank()) Text(status, fontSize = 12.sp, color = Grey)
                OutlinedTextField(
                    value = author, onValueChange = { author = it },
                    label = { Text("Author(s)") },
                    supportingText = { Text("Separate several authors with ;", color = Grey) },
                    shape = RoundedCornerShape(4.dp), colors = bibFieldColors(), modifier = Modifier.fillMaxWidth()
                )
                visible.forEach { f ->
                    OutlinedTextField(
                        value = record[f],
                        onValueChange = { record = record.with(f, it) },
                        label = { Text(if (f == BibField.CITEKEY) "Citation key (default: ${Citations.citeKey(document, record.with(BibField.CITEKEY, ""))})" else f.label) },
                        singleLine = f != BibField.TITLE,
                        shape = RoundedCornerShape(4.dp), colors = bibFieldColors(), modifier = Modifier.fillMaxWidth()
                    )
                }
                TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) "Fewer fields" else "More fields (subtitle, edition, editor, series, URL…)", color = Color.Black)
                }
                InkButton("Copy BibTeX", icon = Icons.Default.ContentCopy, onClick = {
                    copyToClipboard(context, "BibTeX", Citations.bibtex(document.copy(author = author), record))
                })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                BibStore.put(document.id, record)
                if (author.trim() != document.author.trim()) {
                    scope.launch(Dispatchers.IO) { AppDatabase.getInstance(context).documentDao().updateAuthor(document.id, author.trim()) }
                }
                if (summary.trim() != document.summary.trim()) {
                    scope.launch(Dispatchers.IO) { AppDatabase.getInstance(context).documentDao().updateSummary(document.id, summary.trim()) }
                }
                Toast.makeText(context, "Bibliographic details saved", Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text("Save", color = Color.Black, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Grey) } }
    )
}
