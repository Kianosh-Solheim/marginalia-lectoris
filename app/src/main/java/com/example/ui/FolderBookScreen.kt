package com.example.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
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
import com.example.reader.EditedVolumes
import com.example.reader.FolderBookExporter
import com.example.reader.FolderBooks
import com.example.reader.MarkdownExporter
import com.example.server.LibraryImporter
import com.example.server.LibraryOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val FbGrey = Color(0xFF52525B)
private val FbRule = Color(0xFFD4D4D8)

/** Copies a picked file into the cache and adds it to the library (the same way “Add document” does). */
suspend fun importUriToLibrary(context: Context, uri: Uri): DocumentEntity = withContext(Dispatchers.IO) {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
    val tmp = File(File(context.cacheDir, "folder-imports").apply { mkdirs() }, "${System.nanoTime()}-${File(name).name}")
    try {
        (context.contentResolver.openInputStream(uri) ?: error("Could not read “$name”.")).use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
        LibraryImporter.import(context, LibraryOps(context), tmp, name, "BOOK")
    } finally {
        tmp.delete()
    }
}

/**
 * A folder book: one book made of several files (one per chapter). Shows the chapters in order
 * with their authors, progress and notes; chapters can be added (new files or books already in
 * the library), reordered and taken out again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FolderBookScreen(
    folder: DocumentEntity,
    everyDocument: List<DocumentEntity>,
    highlights: List<HighlightEntity>,
    onBack: () -> Unit,
    onOpenPart: (DocumentEntity) -> Unit,
    onContinue: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by FolderBooks.version.collectAsState()
    val partIds = remember(folder.id, version) { FolderBooks.partsOf(folder.id) }
    val byId = remember(everyDocument) { everyDocument.associateBy { it.id } }
    val parts = partIds.mapNotNull { byId[it] }
    val notesBy = remember(highlights) { highlights.groupingBy { it.documentId }.eachCount() }
    val reading = remember(folder.id, version) { FolderBooks.readingOf(folder.id) }
    val editors = remember(folder.id, folder.author, version) { EditedVolumes.editors(folder) }
    var busy by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var citingPart by remember { mutableStateOf<DocumentEntity?>(null) }
    fun exportPart(p: DocumentEntity, kind: com.example.reader.SectionExports.Kind) {
        busy = "Exporting “${p.title.take(40)}” · ${kind.label}…"
        scope.launch {
            runCatching {
                val settings = com.example.data.local.AppDatabase.getInstance(context).settingsDao().getSettingsSnapshot() ?: com.example.data.local.SettingsEntity()
                com.example.reader.SectionExports.document(context, p, kind, highlights, settings)
            }.onSuccess { MarkdownExporter.shareFile(context, it.file, it.mime, "Export chapter: ${p.title}") }
                .onFailure { Toast.makeText(context, it.message ?: "Export failed", Toast.LENGTH_LONG).show() }
            busy = ""
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val added = mutableListOf<Long>()
            uris.forEachIndexed { i, uri ->
                busy = "Adding ${i + 1} of ${uris.size}…"
                runCatching { importUriToLibrary(context, uri) }
                    .onSuccess { added += it.id }
                    .onFailure { Toast.makeText(context, it.message ?: "Could not add a file.", Toast.LENGTH_LONG).show() }
            }
            if (added.isNotEmpty()) FolderBooks.addParts(context, folder.id, added)
            busy = ""
        }
    }

    Surface(color = Color.White, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.Black) }
                Text("FOLDER BOOK", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = FbGrey, modifier = Modifier.weight(1f))
                IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Edit details", tint = Color.Black) }
            }
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(folder.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Color.Black, lineHeight = 28.sp)
                if (editors.isNotBlank()) Text("Edited by ${editors.replace("; ", ", ")}", fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 15.sp, color = Color.Black)
                Text(
                    "${parts.size} chapter${if (parts.size == 1) "" else "s"} · ${(folder.readingProgress * 100).toInt()}% read · ${parts.sumOf { notesBy[it.id] ?: 0 }} notes",
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = FbGrey
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (parts.isNotEmpty()) {
                        val label = reading?.let { "Continue · ${it.title.take(30)}${if (it.title.length > 30) "…" else ""}" } ?: "Read from the start"
                        InkButton(label, icon = Icons.AutoMirrored.Filled.MenuBook, filled = true, onClick = onContinue)
                    }
                    InkButton("Add files", icon = Icons.Default.Add, enabled = busy.isEmpty(), onClick = { picker.launch(arrayOf("application/pdf", "application/epub+zip", "*/*")) })
                    InkButton("Add from library", icon = Icons.Default.LibraryAdd, enabled = busy.isEmpty(), onClick = { picking = true })
                    if (parts.isNotEmpty()) InkButton("Export all chapters", icon = Icons.Default.IosShare, enabled = busy.isEmpty(), onClick = {
                        busy = "Exporting…"
                        scope.launch {
                            runCatching { FolderBookExporter.export(context, folder.id) }
                                .onSuccess { MarkdownExporter.shareFile(context, it, "application/zip", "Export ${folder.title}") }
                                .onFailure { Toast.makeText(context, it.message ?: "Export failed", Toast.LENGTH_LONG).show() }
                            busy = ""
                        }
                    })
                }
                if (busy.isNotEmpty()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = Color.Black, trackColor = FbRule)
                    Text(busy, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Black)
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(thickness = 1.5.dp, color = Color.Black)
            }
            if (parts.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No chapters yet", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.Black)
                    Text("Add the book's chapters as separate PDF or EPUB files, or move books that are already in the library into it.", fontSize = 14.sp, color = FbGrey)
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                itemsIndexed(parts, key = { _, p -> p.id }) { i, p ->
                    var menu by remember { mutableStateOf(false) }
                    var exportMenu by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenPart(p) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text("${i + 1}", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = FbGrey, modifier = Modifier.width(34.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(p.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.Black, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (p.author.isNotBlank() && p.author != "Unknown Author") Text(p.author, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 14.sp, color = FbGrey)
                            val prog = when {
                                p.readingProgress >= 0.999f -> "read ✓"
                                p.readingProgress > 0f -> "${(p.readingProgress * 100).toInt()}% read"
                                else -> ""
                            }
                            Text(
                                listOf(p.fileType, "${p.totalPages} ${if (p.fileType.equals("PDF", true)) "pages" else "positions"}", prog, (notesBy[p.id] ?: 0).let { if (it > 0) "$it notes" else "" })
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = FbGrey
                            )
                            if (reading?.partId == p.id) Text("◆ Reading now", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More", tint = Color.Black) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(Color.White)) {
                                DropdownMenuItem(text = { Text("Read") }, onClick = { menu = false; onOpenPart(p) })
                                DropdownMenuItem(text = { Text("Copy as citation") }, onClick = { menu = false; citingPart = p })
                                DropdownMenuItem(text = { Text("Export…") }, onClick = { menu = false; exportMenu = true })
                                if (i > 0) DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; scope.launch { FolderBooks.move(context, folder.id, p.id, -1) } })
                                if (i < parts.size - 1) DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; scope.launch { FolderBooks.move(context, folder.id, p.id, +1) } })
                                DropdownMenuItem(text = { Text("Take out of this book") }, onClick = {
                                    menu = false
                                    scope.launch { FolderBooks.removePart(context, folder.id, p.id) }
                                    Toast.makeText(context, "“${p.title}” is back on the shelf.", Toast.LENGTH_SHORT).show()
                                })
                            }
                            DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }, modifier = Modifier.background(Color.White)) {
                                com.example.reader.SectionExports.Kind.entries.forEach { k ->
                                    val label = if (k == com.example.reader.SectionExports.Kind.PDF) "Chapter file (${p.fileType})" else k.label
                                    DropdownMenuItem(text = { Text(label) }, onClick = { exportMenu = false; exportPart(p, k) })
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = FbRule)
                }
                item {
                    Spacer(Modifier.height(24.dp))
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete this folder book…", color = FbGrey) }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    citingPart?.let { p ->
        CiteDialog(document = p, quote = "", page = "", onDismiss = { citingPart = null })
    }

    if (picking) {
        val inOther = remember(version) { FolderBooks.hiddenIds() }
        val candidates = everyDocument.filter { !FolderBooks.isFolder(it) && it.id !in partIds && it.id !in inOther }
        LibraryPickerDialog(
            title = "Add to “${folder.title}”",
            documents = candidates,
            onDismiss = { picking = false },
            onConfirm = { ids ->
                picking = false
                scope.launch { FolderBooks.addParts(context, folder.id, ids) }
            }
        )
    }
    if (editing) {
        FolderBookDetailsDialog(
            initialTitle = folder.title,
            initialEditors = editors,
            initialTags = folder.tags,
            initialSummary = folder.summary,
            heading = "Edit folder book",
            onDismiss = { editing = false },
            onSave = { t, e, tags, summary ->
                editing = false
                scope.launch { FolderBooks.updateInfo(context, folder.id, t, e, tags, summary) }
            }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Color.White, shape = RoundedCornerShape(4.dp),
            title = { Text("Delete this folder book?", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = { Text("The folder book is removed. Its ${parts.size} chapter files and their notes stay in the library and are shown on the shelf again.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = Color.Black, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = FbGrey) } }
        )
    }
}

/** Title, editors, tags and abstract of a folder book (used to create and to edit one). */
@Composable
fun FolderBookDetailsDialog(
    initialTitle: String = "",
    initialEditors: String = "",
    initialTags: String = "",
    initialSummary: String = "",
    heading: String = "New folder book",
    onDismiss: () -> Unit,
    onSave: (title: String, editors: String, tags: String, summary: String) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var editors by remember { mutableStateOf(initialEditors) }
    var tags by remember { mutableStateOf(initialTags) }
    var summary by remember { mutableStateOf(initialSummary) }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color.Black, unfocusedBorderColor = Color.Black, focusedLabelColor = Color.Black,
        cursorColor = Color.Black, focusedTextColor = Color.Black, unfocusedTextColor = Color.Black
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White, shape = RoundedCornerShape(4.dp),
        title = { Text(heading, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = Color.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("One book made of several files, one per chapter (for example an edited volume).", fontSize = 13.sp, color = FbGrey)
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, colors = colors, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth())
                PeopleField(value = editors, onValueChange = { editors = it }, label = "Editor(s)", addLabel = "Add editor")
                OutlinedTextField(tags, { tags = it }, label = { Text("Tags (comma separated)") }, singleLine = true, colors = colors, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth())
                PreviousTagsPicker(selected = com.example.reader.NoteSuggestionEngine.parseTagsList(tags), typed = "") { picked ->
                    tags = com.example.reader.NoteSuggestionEngine.joinTagsList(com.example.reader.NoteSuggestionEngine.parseTagsList(tags) + picked)
                }
                OutlinedTextField(summary, { summary = it }, label = { Text("Abstract (optional)") }, minLines = 2, maxLines = 5, colors = colors, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onSave(title, editors, tags, summary) }) {
                Text("Save", color = if (title.isNotBlank()) Color.Black else FbGrey, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = FbGrey) } }
    )
}

/** Choose books from the library (checkboxes). */
@Composable
fun LibraryPickerDialog(title: String, documents: List<DocumentEntity>, onDismiss: () -> Unit, onConfirm: (List<Long>) -> Unit) {
    var query by remember { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<Long>() }
    val shown = documents.filter { query.isBlank() || it.title.contains(query, true) || it.author.contains(query, true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White, shape = RoundedCornerShape(4.dp),
        title = { Text(title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = Color.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search") }, singleLine = true, shape = RoundedCornerShape(4.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Black, unfocusedBorderColor = Color.Black, cursorColor = Color.Black),
                    modifier = Modifier.fillMaxWidth())
                if (documents.isEmpty()) Text("Nothing in the library can be added.", color = FbGrey)
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.id }) { d ->
                        Row(Modifier.fillMaxWidth().clickable { if (d.id in chosen) chosen.remove(d.id) else chosen.add(d.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = d.id in chosen, onCheckedChange = { if (it) chosen.add(d.id) else chosen.remove(d.id) },
                                colors = CheckboxDefaults.colors(checkedColor = Color.Black, checkmarkColor = Color.White))
                            Column(Modifier.weight(1f)) {
                                Text(d.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${d.author} · ${d.fileType}", fontSize = 12.sp, color = FbGrey, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = { onConfirm(chosen.toList()) }) {
                Text(if (chosen.isEmpty()) "Add" else "Add ${chosen.size}", color = if (chosen.isNotEmpty()) Color.Black else FbGrey, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = FbGrey) } }
    )
}

/** Choose a folder book for [document], or make a new one. */
@Composable
fun AddToFolderBookDialog(document: DocumentEntity, everyDocument: List<DocumentEntity>, onDismiss: () -> Unit, onDone: (DocumentEntity) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val folders = everyDocument.filter { FolderBooks.isFolder(it) }
    var creating by remember { mutableStateOf(folders.isEmpty()) }
    if (creating) {
        FolderBookDetailsDialog(onDismiss = onDismiss, onSave = { t, e, tags, summary ->
            scope.launch {
                val f = FolderBooks.create(context, t, e)
                if (tags.isNotBlank() || summary.isNotBlank()) FolderBooks.updateInfo(context, f.id, t, e, tags, summary)
                FolderBooks.addParts(context, f.id, listOf(document.id))
                onDone(f)
            }
        })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White, shape = RoundedCornerShape(4.dp),
        title = { Text("Add to a folder book", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = Color.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("“${document.title}” becomes a chapter of the book you choose.", fontSize = 13.sp, color = FbGrey)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(folders, key = { it.id }) { f ->
                        Column(Modifier.fillMaxWidth().border(1.dp, Color.Black).clickable {
                            scope.launch { FolderBooks.addParts(context, f.id, listOf(document.id)); onDone(f) }
                        }.padding(10.dp)) {
                            Text(f.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = Color.Black)
                            Text("${FolderBooks.partsOf(f.id).size} chapters", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = FbGrey)
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { creating = true }) { Text("New folder book…", color = Color.Black, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = FbGrey) } }
    )
}

/**
 * Shown over the reader at the end of a chapter that belongs to a folder book: opens the next
 * chapter (or, from the last one, offers to go back to the first).
 */
@Composable
fun BoxScope.FolderPartBanner(partId: Long, atEnd: Boolean, everyDocument: List<DocumentEntity>, onOpen: (DocumentEntity) -> Unit) {
    val nav = remember(partId, everyDocument.size) { FolderBooks.neighbours(partId) } ?: return
    var dismissed by remember(partId) { mutableStateOf(false) }
    if (!atEnd || dismissed) return
    val byId = everyDocument.associateBy { it.id }
    val folder = byId[nav.first]
    val next = nav.third?.let { byId[it] }
    val parts = FolderBooks.partsOf(nav.first)
    val number = parts.indexOf(partId) + 1
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(start = 16.dp, end = 16.dp, bottom = 72.dp)
            .widthIn(max = 560.dp)
            .background(Color.White)
            .border(1.5.dp, Color.Black)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "END OF CHAPTER $number OF ${parts.size}" + (folder?.let { " · ${it.title.uppercase()}" } ?: ""),
                fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = FbGrey, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                if (next != null) "Next: ${next.title}" else "That was the last chapter.",
                fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.Black, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            if (next != null && next.author.isNotBlank() && next.author != "Unknown Author") {
                Text(next.author, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 13.sp, color = FbGrey, maxLines = 1)
            }
        }
        if (next != null) InkButton("Next chapter →", filled = true, onClick = { onOpen(next) })
        TextButton(onClick = { dismissed = true }) { Text("×", color = Color.Black, fontSize = 18.sp) }
    }
}
