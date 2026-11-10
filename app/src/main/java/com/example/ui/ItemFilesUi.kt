package com.example.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.reader.ItemFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val FGrey = Color(0xFF52525B)

private fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

/**
 * The item's folder, like a file explorer: the main file, other editions, cover, notes Markdown,
 * recordings… with Read / Make main / Make cover / Open / Rename / Delete (to _to_delete), and Add files.
 */
@Composable
fun ItemFilesDialog(document: DocumentEntity, onDismiss: () -> Unit, onRead: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf(document) }
    var files by remember { mutableStateOf<List<ItemFiles.Entry>>(emptyList()) }
    var folder by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<ItemFiles.Entry?>(null) }
    var deleting by remember { mutableStateOf<ItemFiles.Entry?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        withContext(Dispatchers.IO) {
            doc = AppDatabase.getInstance(context).documentDao().getDocumentById(document.id) ?: doc
            folder = ItemFiles.folderOf(context, doc).absolutePath
            files = ItemFiles.list(context, doc)
        }
    }
    fun run(label: String, block: suspend () -> Unit) {
        busy = label
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onFailure { android.widget.Toast.makeText(context, it.message ?: it.toString(), android.widget.Toast.LENGTH_LONG).show() }
            busy = ""; reload++
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) run("Adding ${uris.size} file${if (uris.size == 1) "" else "s"}…") {
            uris.forEach { u ->
                val name = context.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
                context.contentResolver.openInputStream(u)?.use { ItemFiles.add(context, doc, name, it) }
            }
        }
    }
    fun openExternally(f: File) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), f.name))
        }.onFailure { android.widget.Toast.makeText(context, "No app can open ${f.name}", android.widget.Toast.LENGTH_LONG).show() }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f).border(1.5.dp, Color.Black), color = Color.White) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Files", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color.Black)
                        Text(doc.title, fontSize = 13.sp, color = FGrey, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    InkChip("+ Add files", false, { picker.launch(arrayOf("*/*")) })
                    InkChip("Close", false, onDismiss)
                }
                Text(folder, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = FGrey)
                Text("The main file is the one Read opens. Making another file main keeps each file's own place, and notes open in the file they were made in. Deleted files go to Readmark/_to_delete.", fontSize = 12.sp, color = FGrey)
                if (busy.isNotBlank()) Text(busy, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Black)
                HorizontalDivider(color = Color.Black)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(files, key = { it.rel }) { f ->
                        Row(Modifier.fillMaxWidth().padding(start = if (f.rel.contains('/')) 24.dp else 0.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (f.image) {
                                val bmp = remember(f.rel, f.modified) {
                                    runCatching { kotlinx.coroutines.runBlocking { val file = ItemFiles.resolve(context, doc, f.rel); android.graphics.BitmapFactory.decodeFile(file.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }) } }.getOrNull()
                                }
                                if (bmp != null) androidx.compose.foundation.Image(bmp.asImageBitmap(), null, Modifier.size(44.dp).border(1.dp, Color(0xFFE4E4E7)), contentScale = ContentScale.Crop)
                                else Box(Modifier.size(44.dp).border(1.dp, Color.Black))
                            } else Box(Modifier.width(44.dp).border(1.dp, Color.Black).padding(vertical = 3.dp), contentAlignment = Alignment.Center) {
                                Text(if (f.isDir) "DIR" else f.kind.take(8), fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Color.Black, maxLines = 1)
                            }
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(if (f.rel == "@cover") "${f.name} (app cover cache)" else f.rel, fontSize = 14.sp, color = Color.Black, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (f.isMain) Text(" MAIN ", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color.White, modifier = Modifier.background(Color.Black))
                                    if (f.isCover) Text(" COVER ", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color.White, modifier = Modifier.background(Color(0xFF7C3AED)))
                                }
                                Text(listOf(if (f.isDir) "" else fmtSize(f.size), java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(f.modified))).filter { it.isNotBlank() }.joinToString(" · "),
                                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = FGrey)
                            }
                            if (f.isMain) InkChip("Read", true, { onDismiss(); onRead() })
                            else if (f.readable) InkChip("Make main", false, { run("Making ${f.name} the main file…") { ItemFiles.setMain(context, doc, f.rel) } })
                            if (f.image && !f.isCover) InkChip("Make cover", false, { run("Changing the cover…") { ItemFiles.setCover(context, doc, f.rel) } })
                            if (!f.isDir) InkChip("Open", false, { scope.launch { val file = withContext(Dispatchers.IO) { ItemFiles.resolve(context, doc, f.rel) }; openExternally(file) } })
                            if (f.rel != "@cover") InkChip("Rename", false, { renaming = f })
                            if (!f.isMain && f.rel != "@cover") InkChip("Delete", false, { deleting = f })
                        }
                        HorizontalDivider(color = Color(0xFFE4E4E7))
                    }
                    if (files.isEmpty()) item { Text("Reading the folder…", fontSize = 13.sp, color = FGrey, modifier = Modifier.padding(8.dp)) }
                }
            }
        }
    }
    renaming?.let { f ->
        var name by remember(f.rel) { mutableStateOf(f.name) }
        AlertDialog(onDismissRequest = { renaming = null }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
            title = { Text("Rename", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = { InkField(name, { name = it }, "New name", Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { val n = name.trim(); renaming = null; if (n.isNotEmpty() && n != f.name) run("Renaming…") { ItemFiles.rename(context, doc, f.rel, n) } }) { Text("Rename", color = Color.Black, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel", color = Color.Black) } })
    }
    deleting?.let { f ->
        AlertDialog(onDismissRequest = { deleting = null }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
            title = { Text("Remove this file?", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = { Text("“${f.name}” is moved to the _to_delete folder in Readmark (nothing is erased).") },
            confirmButton = { TextButton(onClick = { deleting = null; run("Moving to _to_delete…") { ItemFiles.delete(context, doc, f.rel) } }) { Text("Move to _to_delete", color = Color.Black, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = Color.Black) } })
    }
}
