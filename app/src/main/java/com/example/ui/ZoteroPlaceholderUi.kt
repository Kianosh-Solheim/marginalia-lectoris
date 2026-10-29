package com.example.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.example.reader.Zotero
import com.example.reader.ZoteroPlaceholders
import com.example.server.LibraryOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The placeholder (Zotero item without a file) whose card is open, or null. */
val placeholderOpen = mutableStateOf<Long?>(null)

private val PGrey = Color(0xFF52525B)
private val PRule = Color(0xFFD4D4D8)

/**
 * Opening a placeholder from the library: its details from Zotero, its notes, and the ways to get
 * the file — from Zotero, or from the tablet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceholderHost() {
    val id = placeholderOpen.value ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var doc by remember(id) { mutableStateOf<DocumentEntity?>(null) }
    var noteCount by remember(id) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var choices by remember { mutableStateOf<List<Zotero.Attachment>?>(null) }
    var askRemove by remember { mutableStateOf<Zotero.Attachment?>(null) }
    var writing by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val close = { placeholderOpen.value = null }

    LaunchedEffect(id, refresh) {
        withContext(Dispatchers.IO) {
            Zotero.init(context)
            val d = AppDatabase.getInstance(context).documentDao().getDocumentById(id)
            doc = d
            noteCount = runCatching { AppDatabase.getInstance(context).highlightDao().getHighlightsListForDocument(id).size }.getOrDefault(0)
        }
    }
    val d = doc
    if (d == null) return
    val linkKey = remember(id, refresh) { Zotero.linkOf(id) }
    val connected = Zotero.account(context) != null

    fun fetch(att: Zotero.Attachment?) {
        busy = "Fetching the file from Zotero…"; message = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ZoteroPlaceholders.fetchFromZotero(context, id, att?.key) } }
            busy = null
            r.onSuccess { (_, used) -> refresh++; askRemove = used }
                .onFailure { message = it.message ?: "Couldn't fetch the file." }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "Adding the file…"; message = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val name = runCatching {
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
                    val tmp = File(File(context.cacheDir, "placeholder-files").apply { mkdirs() }, "${System.nanoTime()}-${File(name).name}")
                    try {
                        (context.contentResolver.openInputStream(uri) ?: error("Could not read “$name”.")).use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
                        ZoteroPlaceholders.attach(context, id, tmp, name)
                    } finally { tmp.delete() }
                }
            }
            busy = null
            r.onSuccess {
                Toast.makeText(context, "File added — the book opens like any other now", Toast.LENGTH_LONG).show()
                close()
            }.onFailure { message = it.message ?: "Couldn't add the file." }
        }
    }

    AlertDialog(
        onDismissRequest = { if (busy == null) close() },
        containerColor = Color.White, shape = RoundedCornerShape(4.dp),
        title = {
            Column {
                Text("NO FILE · ZOTERO", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PGrey)
                Text(d.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.Black)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.author.isNotBlank()) Text(d.author.replace("; ", ", "), fontSize = 14.sp, color = Color.Black)
                Text("The details follow Zotero: what changes there is updated here on every Zotero refresh, and what you change here is sent to Zotero.", fontSize = 13.sp, color = PGrey)
                if (d.summary.isNotBlank()) Text(d.summary.take(600) + if (d.summary.length > 600) "…" else "", fontFamily = FontFamily.Serif, fontSize = 14.sp, color = Color.Black)
                Text("$noteCount note${if (noteCount == 1) "" else "s"}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = PGrey)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ZoteroPlaceholders.isPlaceholder(d)) {
                        InkButton("Get the file from Zotero", filled = true, enabled = busy == null && connected && linkKey != null, onClick = {
                            busy = "Asking Zotero for the file…"; message = ""
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runCatching { Zotero.attachments(context, linkKey!!) } }
                                busy = null
                                r.onSuccess { list ->
                                    val ok = list.filter { it.downloadable }
                                    when {
                                        list.isEmpty() -> message = "This item has no PDF or EPUB in Zotero."
                                        ok.isEmpty() -> message = "Zotero keeps no copy of this file online (it is linked on your computer or stored with WebDAV). Add it from the tablet instead."
                                        ok.size == 1 -> fetch(ok.first())
                                        else -> choices = ok
                                    }
                                }.onFailure { message = "Couldn't reach Zotero: ${it.message}" }
                            }
                        })
                        InkButton("Add a file from the tablet…", enabled = busy == null, onClick = {
                            picker.launch(arrayOf("application/pdf", "application/epub+zip", "application/zip", "text/html", "text/markdown", "text/plain", "*/*"))
                        })
                    }
                    InkButton("New note", enabled = busy == null, onClick = { writing = true })
                    if (linkKey != null && connected) InkButton("Check citation", enabled = busy == null, onClick = { checking = true })
                }
                busy?.let { Text(it, fontSize = 13.sp, color = PGrey) }
                if (message.isNotBlank()) Text(message, fontSize = 13.sp, color = Color(0xFFB91C1C))
                if (!connected) Text("Connect Zotero (side menu ▸ Zotero) to fetch the file.", fontSize = 13.sp, color = PGrey)
            }
        },
        confirmButton = { TextButton(enabled = busy == null, onClick = close) { Text("Close", color = Color.Black, fontWeight = FontWeight.Bold) } }
    )

    choices?.let { list ->
        AlertDialog(onDismissRequest = { choices = null }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
            title = { Text("Which file?", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { a ->
                        InkButton(a.title.ifBlank { a.fileName }.ifBlank { a.key } + if (a.fileName.isNotBlank() && a.fileName != a.title) " · ${a.fileName}" else "",
                            onClick = { choices = null; fetch(a) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { choices = null }) { Text("Cancel", color = PGrey) } })
    }

    askRemove?.let { att ->
        val canWrite = Zotero.account(context)?.canWrite == true
        AlertDialog(onDismissRequest = { askRemove = null; close() }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
            title = { Text("File added to the tablet", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = {
                Text("“${att.fileName.ifBlank { att.title }}” is now on the tablet, and the book's details stay in step with Zotero. Keep the file in Zotero too, or move Zotero's copy to Zotero's trash (it can be restored there)?" +
                    if (!canWrite) "\n\nYour Zotero key is read-only, so the file can only be kept there." else "")
            },
            confirmButton = {
                TextButton(enabled = canWrite, onClick = {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Zotero.trashItem(context, att.key, att.version) } }
                        Toast.makeText(context, r.fold({ "Zotero's copy moved to its trash" }, { "Kept in Zotero: ${it.message}" }), Toast.LENGTH_LONG).show()
                        askRemove = null; close()
                    }
                }) { Text("Move to Zotero's trash", color = if (canWrite) Color(0xFFB91C1C) else PGrey) }
            },
            dismissButton = { TextButton(onClick = { askRemove = null; close() }) { Text("Keep it in Zotero", color = Color.Black, fontWeight = FontWeight.Bold) } })
    }

    if (writing) PlaceholderNoteDialog(d, onDone = { writing = false; refresh++ })
    if (checking && linkKey != null) ZoteroCheckDialog(id, null, linkKey, onDismiss = { checking = false; refresh++ })
}

/** A note of the reader's own on a book without a file: title, quote, thoughts and where in the book. */
@Composable
private fun PlaceholderNoteDialog(doc: DocumentEntity, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var quote by remember { mutableStateOf("") }
    var page by remember { mutableStateOf("") }
    var chapter by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Black, unfocusedBorderColor = PRule)
    AlertDialog(onDismissRequest = { if (!busy) onDone() }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
        title = { Text("New note", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = colors)
                OutlinedTextField(notes, { notes = it }, label = { Text("Your notes") }, minLines = 3, modifier = Modifier.fillMaxWidth(), colors = colors)
                OutlinedTextField(quote, { quote = it }, label = { Text("Quote (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth(), colors = colors)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(page, { page = it }, label = { Text("Page") }, singleLine = true, modifier = Modifier.weight(1f), colors = colors)
                    OutlinedTextField(chapter, { chapter = it }, label = { Text("Chapter") }, singleLine = true, modifier = Modifier.weight(2f), colors = colors)
                }
                OutlinedTextField(tags, { tags = it }, label = { Text("Tags (comma between)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = colors)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (title.isNotBlank() || notes.isNotBlank() || quote.isNotBlank()), onClick = {
                busy = true
                scope.launch {
                    val label = page.trim()
                    val n = Regex("\\d+").find(label)?.value?.toIntOrNull() ?: 1
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            LibraryOps(context).addHighlight(
                                documentId = doc.id, title = title.trim(), highlightedText = quote.trim(), userNotes = notes.trim(),
                                pageNumber = n, printedPageLabel = label, chapterTitle = chapter.trim(), colorHex = "#FFEB3B", tags = tags,
                                paragraphCitation = if (label.isNotBlank()) (if (label.contains('–') || label.contains('-')) "pp. " else "p. ") + label else "",
                                pageEquivalent = label
                            )
                        }
                    }
                    busy = false
                    r.onSuccess { onDone() }.onFailure { Toast.makeText(context, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }) { Text("Save", color = Color.Black, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDone) { Text("Cancel", color = PGrey) } })
}
