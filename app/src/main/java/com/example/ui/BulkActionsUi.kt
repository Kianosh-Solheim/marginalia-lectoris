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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.DocumentEntity
import com.example.reader.BulkActions
import com.example.reader.Citations
import com.example.reader.FolderBooks
import com.example.reader.MarkdownExporter
import com.example.reader.NoteSuggestionEngine
import com.example.reader.Zotero
import com.example.reader.ZoteroPlaceholders
import kotlinx.coroutines.launch

/** Several library items chosen at once (all library views). */
class LibrarySelection {
    var active by mutableStateOf(false)
    val ids = mutableStateListOf<Long>()
    fun isSelected(id: Long) = id in ids
    fun toggle(id: Long) { if (id in ids) ids.remove(id) else ids.add(id) }
    fun clear() { ids.clear(); active = false }
}

private val BGrey = Color(0xFF52525B)

private fun copy(ctx: Context, label: String, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, "$label copied", Toast.LENGTH_SHORT).show()
}

/** The bar shown while items are selected: what to do with all of them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BulkBar(selection: LibrarySelection, documents: List<DocumentEntity>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chosen = documents.filter { selection.isSelected(it.id) }
    var busy by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf("") }
    var checks by remember { mutableStateOf<List<BulkActions.Check>?>(null) }
    var checking by remember { mutableStateOf<Pair<Long, String>?>(null) }

    fun act(label: String, block: suspend () -> String) {
        busy = label
        scope.launch {
            val m = runCatching { block() }.getOrElse { "Couldn't finish: ${it.message}" }
            busy = ""
            Toast.makeText(context, m, Toast.LENGTH_LONG).show()
        }
    }
    fun share(label: String, make: suspend () -> Pair<java.io.File, String>) {
        busy = label
        scope.launch {
            runCatching { make() }.onSuccess { (f, mime) -> MarkdownExporter.shareFile(context, f, mime, f.name) }
                .onFailure { Toast.makeText(context, "Couldn't export: ${it.message}", Toast.LENGTH_LONG).show() }
            busy = ""
        }
    }

    Column(Modifier.fillMaxWidth().background(Color.White).border(1.5.dp, Color.Black).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${chosen.size} selected", fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 15.sp)
            InkChip("Select all", false, { selection.ids.clear(); selection.ids.addAll(documents.map { it.id }) })
            InkChip("Done", false, { selection.clear() })
            if (busy.isNotBlank()) Text(busy, fontSize = 13.sp, color = BGrey)
        }
        if (chosen.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val withFiles = chosen.count { !ZoteroPlaceholders.isPlaceholder(it) }
            InkButton("Share files (.zip)", filled = true, enabled = busy.isBlank() && withFiles > 0, onClick = {
                share("Packing $withFiles file${if (withFiles == 1) "" else "s"}…") { BulkActions.filesZip(context, chosen).first to "application/zip" }
            })
            InkButton("Share notes (.zip)", enabled = busy.isBlank(), onClick = { share("Exporting the notes…") { BulkActions.notesZip(context, chosen).first to "application/zip" } })
            InkButton("Bibliography…", enabled = busy.isBlank(), onClick = { dialog = "bib" })
            InkButton("Organise…", enabled = busy.isBlank(), onClick = { dialog = "organise" })
            if (Zotero.account(context) != null) InkButton("Zotero…", enabled = busy.isBlank(), onClick = { dialog = "zotero" })
        }
    }

    @Composable
    fun Choice(title: String, sub: String, onClick: () -> Unit) {
        Column(Modifier.fillMaxWidth().clickable { dialog = ""; onClick() }.padding(vertical = 9.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Color.Black)
            if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = BGrey)
        }
        HorizontalDivider(color = Color(0xFFE4E4E7))
    }
    @Composable
    fun Sheet(title: String, content: @Composable ColumnScope.() -> Unit) {
        AlertDialog(onDismissRequest = { dialog = "" }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
            title = { Text(title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), content = content) },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text("Close", color = BGrey) } })
    }

    when (dialog) {
        "bib" -> Sheet("Bibliography · ${chosen.size} items") {
            Choice("Share BibTeX (.bib)", "One file for all selected items") { share("Preparing BibTeX…") { BulkActions.bibFile(context, chosen, "bib") to "application/x-bibtex" } }
            Choice("Share RIS (.ris)", "For Zotero, EndNote, Mendeley") { share("Preparing RIS…") { BulkActions.bibFile(context, chosen, "ris") to "application/x-research-info-systems" } }
            Choice("Copy reference list", "Alphabetical, in ${Citations.defaultStyle(context).label}") { copy(context, "Reference list", BulkActions.references(chosen, Citations.defaultStyle(context))) }
            Choice("Copy citation keys", "[@key1; @key2] for Pandoc and Obsidian") { copy(context, "Citation keys", BulkActions.citeKeys(chosen)) }
        }
        "organise" -> Sheet("Organise · ${chosen.size} items") {
            Choice("Add tags…", "To every selected item") { dialog = "addTags" }
            Choice("Remove tags…", "From every selected item") { dialog = "removeTags" }
            Choice("Move to Books", "") { act("Moving…") { BulkActions.category(context, chosen, "BOOK").message } }
            Choice("Move to Articles", "") { act("Moving…") { BulkActions.category(context, chosen, "ARTICLE").message } }
            Choice("Add to a folder book…", "As its chapters, in this order") { dialog = "folder" }
            Choice("Delete…", "From the tablet, with their notes") { dialog = "delete" }
        }
        "zotero" -> Sheet("Zotero · ${chosen.size} items") {
            Choice("Check against Zotero", "Which items differ from their Zotero item") {
                busy = "Comparing with Zotero…"
                scope.launch { checks = runCatching { BulkActions.zoteroCheck(context, chosen) }.getOrNull(); busy = "" }
            }
            Choice("Add missing to Zotero", "New Zotero items for those not yet there") { act("Adding to Zotero…") { BulkActions.zoteroAdd(context, chosen).message } }
            Choice("Look up details", "By DOI / ISBN / ISSN; only empty fields are filled") { act("Looking up the details…") { BulkActions.lookup(context, chosen).message } }
        }
        "addTags" -> {
            var text by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { dialog = "" }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
                title = { Text("Add tags to ${chosen.size} items", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
                text = { OutlinedTextField(text, { text = it }, label = { Text("Tags, separated by commas") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Black)) },
                confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = {
                    val add = text.split(',').map { it.trim() }.filter { it.isNotBlank() }; dialog = ""
                    act("Adding tags…") { BulkActions.tags(context, chosen, add, emptyList()).message }
                }) { Text("Add", color = Color.Black, fontWeight = FontWeight.Bold) } },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel", color = BGrey) } })
        }
        "removeTags" -> {
            val all = remember(chosen) { chosen.flatMap { NoteSuggestionEngine.parseTagsList(it.tags) }.groupingBy { it.lowercase() }.eachCount().toList().sortedByDescending { it.second } }
            val pick = remember { mutableStateListOf<String>() }
            AlertDialog(onDismissRequest = { dialog = "" }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
                title = { Text("Remove tags", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
                text = {
                    if (all.isEmpty()) Text("The selected items have no tags.", color = BGrey)
                    else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        all.forEach { (t, n) -> InkChip("#$t $n", t in pick, { if (t in pick) pick.remove(t) else pick.add(t) }) }
                    }
                },
                confirmButton = { TextButton(enabled = pick.isNotEmpty(), onClick = {
                    val rm = pick.toList(); dialog = ""
                    act("Removing tags…") { BulkActions.tags(context, chosen, emptyList(), rm).message }
                }) { Text("Remove", color = Color.Black, fontWeight = FontWeight.Bold) } },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel", color = BGrey) } })
        }
        "folder" -> Sheet("Add to a folder book") {
            val folders = documents.filter { FolderBooks.isFolder(it) }
            if (folders.isEmpty()) Text("There are no folder books yet.", color = BGrey)
            folders.forEach { f -> Choice(f.title, "${FolderBooks.partsOf(f.id).size} chapters") { act("Adding…") { BulkActions.addToFolder(context, f.id, chosen).message } } }
        }
        "delete" -> AlertDialog(onDismissRequest = { dialog = "" }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
            title = { Text("Delete ${chosen.size} items?", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = { Text(chosen.take(6).joinToString(", ") { "“${it.title}”" } + (if (chosen.size > 6) " …" else "") + " and their notes will be removed from the tablet.") },
            confirmButton = { TextButton(onClick = {
                val list = chosen.toList(); dialog = ""
                act("Deleting…") { BulkActions.delete(context, list).message.also { selection.clear() } }
            }) { Text("Delete", color = Color(0xFFB91C1C), fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel", color = BGrey) } })
    }

    checks?.let { list ->
        AlertDialog(onDismissRequest = { checks = null }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
            title = { Text("Checked against Zotero", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val differ = list.filter { it.key != null && it.differences.isNotEmpty() }
                    val same = list.filter { it.key != null && it.differences.isEmpty() }
                    val unlinked = list.filter { it.key == null }
                    Text("${same.size} match Zotero, ${differ.size} differ, ${unlinked.size} not linked.", color = BGrey, fontSize = 13.sp)
                    differ.forEach { c ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(c.doc.title, fontWeight = FontWeight.Bold, color = Color.Black); Text(c.differences.joinToString(", "), fontSize = 12.sp, color = BGrey) }
                            InkButton("Check", onClick = { checking = c.doc.id to c.key!! })
                        }
                    }
                    if (unlinked.isNotEmpty()) Text("Not linked: " + unlinked.joinToString(", ") { it.doc.title }, fontSize = 12.sp, color = BGrey, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { checks = null }) { Text("Done", color = Color.Black, fontWeight = FontWeight.Bold) } })
    }
    checking?.let { (id, key) -> ZoteroCheckDialog(id, null, key, onDismiss = { checking = null }) }
}

/** A selected item's frame and tick in the cover and card views. */
@Composable
fun SelectableFrame(selected: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.then(if (selected) Modifier.border(3.dp, Color.Black) else Modifier).padding(if (selected) 3.dp else 0.dp)) {
        content()
        if (selected) Text("✓", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).background(Color.Black).padding(horizontal = 7.dp, vertical = 2.dp))
    }
}
