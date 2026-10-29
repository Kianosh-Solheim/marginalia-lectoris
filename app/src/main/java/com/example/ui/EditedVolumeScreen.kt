package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.data.local.SettingsEntity
import com.example.reader.BibStore
import com.example.reader.ChapterDetector
import com.example.reader.ChapterExporter
import com.example.reader.EditedVolume
import com.example.reader.EditedVolumes
import com.example.reader.MarkdownExporter
import com.example.reader.NoteAiAssistant
import com.example.reader.VolumeChapter
import kotlinx.coroutines.launch

private val VolGrey = Color(0xFF52525B)
private val VolRule = Color(0xFFD4D4D8)

/**
 * The contents of an edited volume: every chapter with its author(s), pages and notes. Tapping a
 * chapter opens the book there; each chapter can be cited and exported on its own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditedVolumeScreen(
    document: DocumentEntity,
    highlights: List<HighlightEntity>,
    settings: SettingsEntity,
    onBack: () -> Unit,
    onOpenAtPage: (Int) -> Unit,
    onContinue: () -> Unit,
    onNotEditedVolume: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var volume by remember(document.id) { mutableStateOf(EditedVolumes.get(context, document.id)) }
    var busy by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Int?>(null) }      // index, or -1 for a new chapter
    var citing by remember { mutableStateOf<Int?>(null) }
    val isPdf = document.fileType.equals("PDF", true)
    val editors = remember(document.id, document.author) { BibStore.init(context); EditedVolumes.editors(document) }

    fun save(v: EditedVolume) { EditedVolumes.put(context, document.id, v); volume = v }

    fun detect(keepEdits: Boolean) {
        busy = "Reading the table of contents…"; status = ""
        scope.launch {
            ChapterDetector.detect(context, document).onSuccess { d ->
                val old = if (keepEdits) volume?.chapters.orEmpty() else emptyList()
                val merged = EditedVolumes.mergeRedetected(old, d.volume.chapters)
                save(d.volume.copy(chapters = merged))
                val articles = merged.count { it.isArticle }
                val withAuthor = merged.count { it.isArticle && it.authors.isNotBlank() }
                status = "Found $articles chapters; authors read for $withAuthor." +
                    if (withAuthor < articles) " Use “Find authors with AI” or tap ✎ to add the rest." else ""
            }.onFailure {
                val cur = volume
                if (cur == null) save(EditedVolume(enabled = true, totalPages = document.totalPages, format = EditedVolume.CURRENT_FORMAT))
                else if (cur.format < EditedVolume.CURRENT_FORMAT) save(cur.copy(format = EditedVolume.CURRENT_FORMAT))
                status = it.message ?: "Could not read the chapters."
            }
            busy = ""
        }
    }

    LaunchedEffect(document.id) {
        val cur = volume
        when {
            cur == null || cur.chapters.isEmpty() -> detect(keepEdits = false)
            // Lists made before all heading levels were read: read them again, keeping typed and AI authors.
            cur.format < EditedVolume.CURRENT_FORMAT -> detect(keepEdits = true)
        }
    }

    val v = volume
    val chapters = v?.chapters.orEmpty()
    val currentIdx = remember(v, document.currentPage, document.lastPositionChapter) {
        if (v == null || (document.readingProgress <= 0f && document.currentPage <= 1)) -1
        else EditedVolumes.chapterIndexFor(v, document.currentPage, document.lastPositionChapter)
    }
    val notesByChapter = remember(v, highlights) {
        if (v == null) emptyMap() else EditedVolumes.highlightsByChapter(v, highlights).let { g -> chapters.indices.associateWith { g[it]?.size ?: 0 } }
    }
    val numbers = remember(chapters) { chapters.indices.map { EditedVolumes.numberOf(chapters, it) } }
    var showSections by remember { mutableStateOf(true) }
    val hasSections = chapters.any { it.isSection }

    Surface(color = Color.White, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // ------------------------------------------------------------ header
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.Black) }
                Text("CONTENTS", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VolGrey, modifier = Modifier.weight(1f))
            }
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(document.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Color.Black, lineHeight = 28.sp)
                if (editors.isNotBlank()) {
                    Text("Edited by ${editors.replace("; ", ", ")}", fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 15.sp, color = Color.Black)
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val cur = chapters.getOrNull(currentIdx)
                    if (cur != null) {
                        InkButton("Continue · ${cur.title.take(34)}${if (cur.title.length > 34) "…" else ""}", icon = Icons.AutoMirrored.Filled.MenuBook, filled = true, onClick = onContinue)
                    } else {
                        InkButton("Read from the start", icon = Icons.AutoMirrored.Filled.MenuBook, filled = true, onClick = onContinue)
                    }
                    InkButton("Fill from Crossref", icon = Icons.Default.Refresh, enabled = busy.isBlank() && chapters.any { it.isArticle }, onClick = {
                        val vol = volume ?: return@InkButton
                        busy = "Looking up the chapters on Crossref…"; status = ""
                        scope.launch {
                            val rec = BibStore.get(document.id)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.example.reader.CrossrefChapters.fetch(document.title, rec) }
                                .onSuccess { records ->
                                    val (updated, matched) = EditedVolumes.applyCrossref(vol.chapters, records)
                                    save(vol.copy(chapters = updated))
                                    val articles = vol.chapters.count { it.isArticle }
                                    status = "Crossref: matched $matched of $articles chapters (${records.size} records) — authors, pages, DOIs and abstracts filled where missing."
                                }
                                .onFailure { status = it.message ?: "Crossref lookup failed." }
                            busy = ""
                        }
                    })
                    InkButton("Find authors with AI", icon = Icons.Default.AutoAwesome, enabled = busy.isBlank() && chapters.any { it.isArticle }, onClick = {
                        val vol = volume ?: return@InkButton
                        busy = "Asking the AI for the chapter authors…"; status = ""
                        scope.launch {
                            val idx = vol.chapters.indices.filter { vol.chapters[it].isArticle && vol.chapters[it].authorSource !in setOf("user", "crossref") }
                            val openings = runCatching { ChapterDetector.openingTexts(context, document, idx.map { vol.chapters[it] }) }.getOrElse { idx.map { "" } }
                            NoteAiAssistant.chapterAuthors(context, document.title, editors, idx.mapIndexed { k, i -> vol.chapters[i].title to openings[k] })
                                .onSuccess { names ->
                                    var found = 0
                                    val updated = vol.chapters.toMutableList()
                                    idx.forEachIndexed { k, i ->
                                        val n = names.getOrNull(k).orEmpty().trim()
                                        if (n.isNotBlank()) { updated[i] = updated[i].copy(authors = n, authorSource = "ai"); found++ }
                                    }
                                    save(vol.copy(chapters = updated))
                                    status = "AI found authors for $found of ${idx.size} chapters. Check them; tap ✎ to correct."
                                }
                                .onFailure { status = it.message ?: "The AI lookup failed." }
                            busy = ""
                        }
                    })
                    InkButton("Read contents again", icon = Icons.Default.Refresh, enabled = busy.isBlank(), onClick = { detect(keepEdits = true) })
                    InkButton("Add chapter", icon = Icons.Default.Add, enabled = busy.isBlank(), onClick = { editing = -1 })
                    if (hasSections) InkChip(if (showSections) "Sections shown" else "Sections hidden", showSections, { showSections = !showSections })
                }
                if (busy.isNotBlank()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = Color.Black, trackColor = Color(0xFFE4E4E7))
                    Text(busy, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Black)
                }
                if (status.isNotBlank()) Text(status, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Black)
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(thickness = 1.5.dp, color = Color.Black)
            }

            // ------------------------------------------------------------ chapters
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
                itemsIndexed(chapters, key = { i, c -> "$i-${c.startPage}-${c.title}" }) { i, c ->
                    val indent = (c.level.coerceIn(0, 5) * 14).dp
                    when {
                        c.isSection -> if (showSections) Row(
                            Modifier.fillMaxWidth().clickable { onOpenAtPage(c.startPage) }
                                .padding(start = 54.dp + indent - 14.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("↳ ${c.title}", fontFamily = FontFamily.Serif, fontSize = 13.sp, color = Color(0xFF27272A), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (isPdf) "PDF ${c.startPage}" else "", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = VolGrey)
                            IconButton(onClick = { editing = i }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Edit, "Edit", tint = VolGrey, modifier = Modifier.size(14.dp)) }
                        }
                        c.isPart -> Text(
                            c.title.uppercase(),
                            fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.5.sp, color = Color.Black,
                            modifier = Modifier.fillMaxWidth().clickable { onOpenAtPage(c.startPage) }.padding(start = 20.dp + indent, end = 20.dp, top = 22.dp, bottom = 6.dp)
                        )
                        c.isMatter -> Row(
                            Modifier.fillMaxWidth().clickable { onOpenAtPage(c.startPage) }.padding(start = 20.dp + indent, end = 20.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(c.title, fontFamily = FontFamily.Serif, fontSize = 14.sp, color = VolGrey, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(pageLabel(c, chapters, i, v?.totalPages ?: 0, isPdf), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = VolGrey)
                            IconButton(onClick = { editing = i }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Edit, "Edit", tint = VolGrey, modifier = Modifier.size(16.dp)) }
                        }
                        else -> ChapterRow(
                            number = numbers.getOrElse(i) { "" },
                            indent = indent,
                            chapter = c,
                            pages = pageLabel(c, chapters, i, v?.totalPages ?: 0, isPdf),
                            notes = notesByChapter[i] ?: 0,
                            isCurrent = i == currentIdx,
                            progress = v?.let { EditedVolumes.progressOf(it, i) } ?: 0f,
                            onOpen = { onOpenAtPage(c.startPage) },
                            onEdit = { editing = i },
                            onCite = { citing = i },
                            onExport = { kind ->
                                val vol = volume ?: return@ChapterRow
                                busy = "Exporting “${c.title.take(40)}” · ${kind.label}…"; status = ""
                                scope.launch {
                                    runCatching { com.example.reader.SectionExports.chapter(context, document, vol, i, kind, highlights, settings) }
                                        .onSuccess { r ->
                                            status = "Exported ${r.file.name}"
                                            runCatching { MarkdownExporter.shareFile(context, r.file, r.mime, "Export chapter: ${c.title}") }
                                        }
                                        .onFailure { status = "Export failed: ${it.message}" }
                                    busy = ""
                                }
                            }
                        )
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider(color = VolRule)
                        Text(
                            "Not an edited volume? The book then opens straight to reading, like other books. You can turn chapters back on from the book's long-press menu.",
                            fontSize = 12.sp, color = VolGrey
                        )
                        InkButton("Open normally from now on", onClick = {
                            EditedVolumes.setEnabled(context, document, false)
                            onNotEditedVolume()
                        })
                    }
                }
            }
        }
    }

    editing?.let { idx ->
        val vol = volume ?: EditedVolume(totalPages = document.totalPages)
        ChapterEditDialog(
            initial = vol.chapters.getOrNull(idx) ?: VolumeChapter(title = "", startPage = document.currentPage.coerceAtLeast(1)),
            isNew = idx < 0,
            isPdf = isPdf,
            onDismiss = { editing = null },
            onDelete = {
                save(vol.copy(chapters = vol.chapters.filterIndexed { k, _ -> k != idx }))
                editing = null
            },
            onSave = { c ->
                val list = vol.chapters.toMutableList()
                if (idx < 0) list += c.copy(level = EditedVolumes.levelForNewChapter(vol.chapters, c.startPage), isManual = true)
                else list[idx] = c.copy(isManual = true)
                save(vol.copy(chapters = list.sortedWith(compareBy({ it.startPage }, { it.level }))))
                editing = null
            }
        )
    }

    citing?.let { idx ->
        val c = chapters.getOrNull(idx)
        if (c != null) {
            CiteDialog(document = document, quote = "", page = "", pageNumber = c.startPage, chapterTitle = c.title, onDismiss = { citing = null })
        } else citing = null
    }
}

private fun pageLabel(c: VolumeChapter, all: List<VolumeChapter>, i: Int, total: Int, isPdf: Boolean): String {
    val end = EditedVolumes.endPageOf(all, i, total.coerceAtLeast(c.startPage))
    val printed = c.printedPages.takeIf { it.isNotBlank() }?.let { if (it.contains('–') || it.contains('-')) "pp. $it" else "p. $it" }
    val pdf = if (isPdf) (if (end > c.startPage) "PDF ${c.startPage}–$end" else "PDF ${c.startPage}") else null
    return listOfNotNull(printed, pdf).joinToString(" · ")
}

@Composable
private fun ChapterRow(
    number: String,
    indent: androidx.compose.ui.unit.Dp = 0.dp,
    chapter: VolumeChapter,
    pages: String,
    notes: Int,
    isCurrent: Boolean,
    progress: Float = 0f,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onCite: () -> Unit,
    onExport: (com.example.reader.SectionExports.Kind) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp + indent, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                number,
                fontFamily = FontFamily.Serif, fontSize = 18.sp, color = Color.Black,
                modifier = Modifier.width(40.dp).padding(top = 1.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(chapter.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.Black, lineHeight = 21.sp)
                if (chapter.authors.isNotBlank()) {
                    Text(
                        chapter.authors.replace("; ", ", ") + when (chapter.authorSource) { "ai" -> "  ·  AI"; "crossref" -> "  ·  Crossref"; else -> "" },
                        fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 15.sp, color = Color.Black
                    )
                } else {
                    Text("Unknown author — tap ✎", fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 14.sp, color = VolGrey)
                }
                if (chapter.tags.isNotBlank()) {
                    Text(com.example.reader.TagSplitter.split(chapter.tags).joinToString("  ") { "#$it" },
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isCurrent) {
                        Text(
                            "READING", fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.background(Color.Black, RoundedCornerShape(2.dp)).padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    val read = when {
                        progress >= 0.97f -> "read ✓"
                        progress > 0.01f -> "${(progress * 100).toInt()}% read"
                        else -> null
                    }
                    val meta = listOfNotNull(pages.takeIf { it.isNotBlank() }, read, if (notes > 0) "$notes note${if (notes == 1) "" else "s"}" else null).joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = VolGrey)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Chapter actions", tint = Color.Black) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(Color.White)) {
                    DropdownMenuItem(text = { Text("Read this chapter") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Edit title, author, pages") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Copy as citation") }, leadingIcon = { Icon(Icons.Default.FormatQuote, null) }, onClick = { menu = false; onCite() })
                    DropdownMenuItem(
                        text = { Text("Export…") },
                        leadingIcon = { Icon(Icons.Default.IosShare, null) }, onClick = { menu = false; exportMenu = true }
                    )
                }
                DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }, modifier = Modifier.background(Color.White)) {
                    Text("EXPORT THIS CHAPTER", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VolGrey,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    com.example.reader.SectionExports.Kind.entries.forEach { k ->
                        val sub = when (k) {
                            com.example.reader.SectionExports.Kind.PDF -> "Only this chapter's pages"
                            com.example.reader.SectionExports.Kind.RIS -> "Book section for Zotero, EndNote, Mendeley"
                            com.example.reader.SectionExports.Kind.BIB -> "@incollection with book, editors, pages"
                            com.example.reader.SectionExports.Kind.NOTES -> if (notes > 0) "$notes note${if (notes == 1) "" else "s"}" else "No notes yet"
                            com.example.reader.SectionExports.Kind.ZIP -> "Notes, chapter PDF, BibTeX and cover"
                        }
                        DropdownMenuItem(
                            text = { Column { Text(k.label, fontWeight = FontWeight.SemiBold); Text(sub, fontSize = 12.sp, color = VolGrey) } },
                            onClick = { exportMenu = false; onExport(k) }
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = VolRule, modifier = Modifier.padding(start = 54.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChapterEditDialog(
    initial: VolumeChapter,
    isNew: Boolean,
    isPdf: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (VolumeChapter) -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(initial.title) }
    var authors by remember { mutableStateOf(initial.authors) }
    var start by remember { mutableStateOf(initial.startPage.toString()) }
    var end by remember { mutableStateOf(if (initial.endPage > 0) initial.endPage.toString() else "") }
    var printed by remember { mutableStateOf(initial.printedPages) }
    var doi by remember { mutableStateOf(initial.doi) }
    var tags by remember { mutableStateOf(initial.tags) }
    var abstract by remember { mutableStateOf(initial.abstract) }
    var kind by remember { mutableStateOf(when { initial.isPart -> 1; initial.isMatter -> 2; initial.isSection -> 3; else -> 0 }) }
    var label by remember { mutableStateOf(initial.label) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color.Black, unfocusedBorderColor = Color(0xFF71717A), focusedLabelColor = Color.Black,
        cursorColor = Color.Black, focusedTextColor = Color.Black, unfocusedTextColor = Color.Black
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        shape = RoundedCornerShape(4.dp),
        title = { Text(if (isNew) "Add chapter" else "Edit chapter", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = Color.Black) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InkChip("Chapter", kind == 0, { kind = 0 })
                    InkChip("Part heading", kind == 1, { kind = 1 })
                    InkChip("Front/back matter", kind == 2, { kind = 2 })
                    InkChip("Section", kind == 3, { kind = 3 })
                }
                OutlinedTextField(title, { title = it }, label = { Text("Chapter title") }, colors = fieldColors, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth())
                if (kind == 0) {
                    OutlinedTextField(label, { label = it.trim() }, label = { Text("Chapter number (as printed)") }, singleLine = true,
                        colors = fieldColors, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth())
                    PeopleField(value = authors, onValueChange = { authors = it }, label = "Chapter author(s)", addLabel = "Add author")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it.filter(Char::isDigit) }, label = { Text(if (isPdf) "First PDF page" else "Start position") }, singleLine = true,
                        colors = fieldColors, shape = RoundedCornerShape(4.dp), modifier = Modifier.weight(1f))
                    OutlinedTextField(end, { end = it.filter(Char::isDigit) }, label = { Text(if (isPdf) "Last PDF page" else "End position") }, singleLine = true,
                        placeholder = { Text("auto") }, colors = fieldColors, shape = RoundedCornerShape(4.dp), modifier = Modifier.weight(1f))
                }
                if (kind == 0) {
                    OutlinedTextField(printed, { printed = it }, label = { Text("Printed pages (for citations)") }, placeholder = { Text("129–180") }, singleLine = true,
                        colors = fieldColors, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(doi, { doi = it }, label = { Text("Chapter DOI (optional)") }, singleLine = true,
                        colors = fieldColors, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(tags, { tags = it }, label = { Text("Chapter tags / keywords") },
                        supportingText = { Text("Separate with commas") },
                        colors = fieldColors, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(abstract, { abstract = it }, label = { Text("Chapter abstract") }, minLines = 2, maxLines = 6,
                        colors = fieldColors, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth())
                }
                if (!isNew) TextButton(onClick = onDelete) { Text("Remove from the list", color = Color(0xFFB91C1C)) }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && (start.toIntOrNull() ?: 0) > 0, onClick = {
                val s = start.toIntOrNull() ?: 1
                val e = end.toIntOrNull()?.takeIf { it >= s } ?: 0
                val cleanAuthors = authors.split(';').map { it.trim() }.filter { it.isNotBlank() }.joinToString("; ")
                val pagesClean = printed.trim().replace(Regex("\\s*[-–]\\s*"), "–")
                if (end.isNotBlank() && e == 0) Toast.makeText(context, "The last page is before the first page; it was ignored.", Toast.LENGTH_SHORT).show()
                onSave(
                    initial.copy(
                        title = title.trim(),
                        authors = if (kind == 0) cleanAuthors else "",
                        startPage = s,
                        endPage = e,
                        isPart = kind == 1,
                        isMatter = kind == 2,
                        isSection = kind == 3,
                        label = if (kind == 0) label else "",
                        printedPages = if (kind == 0) pagesClean else initial.printedPages,
                        doi = if (kind == 0) com.example.reader.BibFieldCleaner.clean(com.example.reader.BibField.DOI, doi).ifBlank { doi.trim() } else "",
                        tags = if (kind == 0) com.example.reader.TagSplitter.split(tags).joinToString(", ") else "",
                        abstract = if (kind == 0) abstract.trim() else "",
                        authorSource = if (cleanAuthors != initial.authors) "user" else initial.authorSource
                    )
                )
            }) { Text("Save", color = Color.Black, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = VolGrey) } }
    )
}
