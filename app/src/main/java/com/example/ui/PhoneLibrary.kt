package com.example.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.DocumentEntity
import com.example.reader.AudiobookPlayer
import com.example.reader.Audiobooks
import com.example.reader.TabletLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val PInk = Color.Black
private val PGrey = Color(0xFF3F3F46)
private val PRule = Color(0xFFD4D4D8)
private val PDesk = Color(0xFFE7E5E4)
private val PGreen = Color(0xFF15803D)
private val PRed = Color(0xFFB91C1C)
private val PNotice = Color(0xFFFEF3C7)
private val CoverColors = listOf(Color(0xFF6B8DA9), Color(0xFFD1B35C), Color(0xFFC26D5E), Color(0xFF7B9E87), Color(0xFFF4F4F5), Color(0xFF3F3F46))

/** One book on the phone's shelf: here, on the tablet, or both. */
private data class ShelfItem(
    val key: String, val title: String, val author: String, val local: DocumentEntity?, val remote: TabletLink.Remote?,
    val audioHere: Boolean, val audioOnTablet: Boolean, val progress: Float, val notes: Int
) {
    val here get() = local != null
    val hasAudio get() = audioHere || audioOnTablet
}

/**
 * The library on a phone (smallest width under 600 dp): every book, here and on the paired tablet, as
 * large covers two to a row. A green corner: the book is on this phone. A headphones square: it has an
 * audiobook (green: on this phone; white: on the tablet). A cover opens the book's page.
 */
@Composable
fun PhoneLibrary(
    documents: List<DocumentEntity>,
    highlightCounts: Map<Long, Int>,
    onMenu: () -> Unit,
    onOpenDocument: (DocumentEntity) -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { TabletLink.init(context) }
    val paired by TabletLink.paired
    val reachable by TabletLink.reachable
    val lastSync by TabletLink.lastSyncAt
    val remote by TabletLink.remoteBooks
    val audioChanges by Audiobooks.changes.collectAsState()
    LaunchedEffect(paired) { if (paired) runCatching { TabletLink.remoteLibrary(context) } }

    val withAudio by produceState(emptySet<Long>(), documents, audioChanges) {
        value = withContext(Dispatchers.IO) { documents.filter { runCatching { Audiobooks.has(context, it.id) }.getOrDefault(false) }.map { it.id }.toSet() }
    }
    val items = remember(documents, remote, withAudio, highlightCounts) {
        val byUuid = remote.associateBy { it.uuid }
        val locals = documents.map { d ->
            val r = byUuid[d.uuid.takeIf { it.isNotBlank() }]
            ShelfItem(d.uuid.ifBlank { "local-${d.id}" }, d.title, d.author, d, r, d.id in withAudio, r?.audiobook == true,
                d.readingProgress.coerceIn(0f, 1f), highlightCounts[d.id] ?: 0)
        }
        val localUuids = documents.map { it.uuid }.toSet()
        locals + remote.filter { it.uuid !in localUuids }.map { r ->
            ShelfItem(r.uuid, r.title, r.author, null, r, false, r.audiobook, r.progress.toFloat().coerceIn(0f, 1f), 0)
        }
    }
    var filter by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    val shown = items.filter {
        (filter == "all" || (filter == "here" && it.here) || (filter == "audio" && it.hasAudio)) &&
            (query.isBlank() || it.title.contains(query, true) || it.author.contains(query, true))
    }
    val away = paired && reachable == false
    val player by AudiobookPlayer.state.collectAsState()

    Box(Modifier.fillMaxSize().background(Color.White).windowInsetsPadding(WindowInsets.systemBars)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SquareButton(onClick = onMenu, label = "Menu") { Icon(Icons.Default.Menu, null, tint = PInk) }
                Text("Library", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = PInk, modifier = Modifier.weight(1f))
                TabletChip(paired, reachable, lastSync, onClick = { if (paired) TabletLink.syncSoon(context, 0) else onOpenSettings() })
            }
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(44.dp).border(1.5.dp, PInk).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Search, null, tint = PGrey, modifier = Modifier.size(18.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Find a book or author", fontSize = 15.sp, color = Color(0xFF71717A))
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = PInk), cursorBrush = SolidColor(PInk), modifier = Modifier.fillMaxWidth())
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("All", items.size, filter == "all") { filter = "all" }
                FilterChip("On this phone", items.count { it.here }, filter == "here") { filter = "here" }
                FilterChip("Audiobooks", items.count { it.hasAudio }, filter == "audio") { filter = "audio" }
            }
            Box(Modifier.fillMaxWidth().height(1.5.dp).background(PInk))
            if (away) Text(
                "The tablet is out of reach. Your places and notes wait on this phone and go to the tablet when you are back on its Wi‑Fi. Books only on the tablet can be downloaded then.",
                fontSize = 13.sp, lineHeight = 18.sp, color = PInk, modifier = Modifier.fillMaxWidth().background(PNotice).padding(horizontal = 16.dp, vertical = 10.dp)
            )
            Box(Modifier.weight(1f).fillMaxWidth().background(PDesk)) {
                if (shown.isEmpty()) EmptyShelf(paired, filter, query, onOpenSettings)
                else LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 16.dp, end = 20.dp, top = 18.dp, bottom = if (player.docId != 0L) 110.dp else 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    items(shown, key = { it.key }) { item ->
                        Column(Modifier.clickable { open = item.key }.alpha(if (away && !item.here) 0.4f else 1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            BookCover(item, Modifier.fillMaxWidth().aspectRatio(2f / 3f), corner = 34.dp)
                            Box(Modifier.fillMaxWidth().height(3.dp).background(PRule)) { Box(Modifier.fillMaxWidth(item.progress).height(3.dp).background(PInk)) }
                            Text(item.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = PInk)
                            if (item.author.isNotBlank()) Text(item.author, fontSize = 12.sp, color = PGrey, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (player.docId != 0L) MiniPlayer(player, documents.firstOrNull { it.id == player.docId }, onOpenDocument, Modifier.align(Alignment.BottomCenter))
    }

    open?.let { key ->
        val item = items.firstOrNull { it.key == key }
        if (item == null) open = null
        else PhoneBookPage(item, away, onDismiss = { open = null }, onOpenDocument = { open = null; onOpenDocument(it) })
    }
}

@Composable
private fun SquareButton(onClick: () -> Unit, label: String, content: @Composable () -> Unit) {
    Surface(onClick = onClick, color = Color.White, shape = androidx.compose.ui.graphics.RectangleShape, border = androidx.compose.foundation.BorderStroke(1.5.dp, PInk), modifier = Modifier.size(44.dp).semanticsLabel(label)) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

private fun Modifier.semanticsLabel(label: String) = this.semantics { contentDescription = label }

@Composable
private fun TabletChip(paired: Boolean, reachable: Boolean?, lastSync: Long, onClick: () -> Unit) {
    val label = when {
        !paired -> "Connect a tablet"
        reachable == false -> "Tablet out of reach"
        lastSync > 0 -> "Tablet, synced " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(lastSync))
        else -> "Tablet"
    }
    Surface(onClick = onClick, color = Color.White, shape = androidx.compose.ui.graphics.RectangleShape, border = androidx.compose.foundation.BorderStroke(1.5.dp, PInk), modifier = Modifier.height(44.dp)) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (paired) Box(Modifier.size(9.dp).then(if (reachable == false) Modifier.border(1.5.dp, PInk, CircleShape) else Modifier.background(PGreen, CircleShape)))
            Text(label, fontSize = 13.sp, color = PInk, maxLines = 1)
        }
    }
}

@Composable
private fun FilterChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, color = if (selected) PInk else Color.White, shape = androidx.compose.ui.graphics.RectangleShape, border = androidx.compose.foundation.BorderStroke(1.5.dp, PInk), modifier = Modifier.height(38.dp)) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, fontSize = 14.sp, color = if (selected) Color.White else PInk)
            Text("$count", fontSize = 13.sp, color = if (selected) Color(0xFFD4D4D8) else PGrey)
        }
    }
}

@Composable
private fun EmptyShelf(paired: Boolean, filter: String, query: String, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when {
                query.isNotBlank() -> "No book matches “$query”."
                filter == "here" -> "No books on this phone yet. Open one from the tablet's library and download it."
                filter == "audio" -> "No audiobooks yet."
                !paired -> "No books yet. Connect your Marginalia tablet to see its library here."
                else -> "The tablet's library is on its way."
            },
            fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 24.sp, color = PInk
        )
        if (!paired && query.isBlank()) {
            Spacer(Modifier.height(16.dp))
            InkButton("Connect a tablet", filled = true, minHeight = 44.dp, onClick = onOpenSettings)
        }
    }
}

/** The cover: the book's own image (here or from the tablet), else its title set on a colour. Hard ink shadow, green corner when on the phone. */
@Composable
private fun BookCover(item: ShelfItem, modifier: Modifier, corner: Dp) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(null, item.key, item.local?.coverUri, item.remote?.coverVersion) {
        value = withContext(Dispatchers.IO) {
            val f = item.local?.coverUri?.let { File(it) }?.takeIf { it.isFile && it.length() > 0 } ?: item.remote?.let { TabletLink.cover(context, it) }
            f?.let { runCatching { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 2 }) }.getOrNull() }
        }
    }
    val fill = CoverColors[(item.key.hashCode() and 0x7fffffff) % CoverColors.size]
    val ink = if (fill == Color(0xFF3F3F46)) Color.White else PInk
    Box(modifier.drawBehind { val o = 4.dp.toPx(); drawRect(PInk, topLeft = Offset(o, o), size = size) }.background(fill).border(1.5.dp, PInk)) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(1.5.dp))
        else Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(item.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 20.sp, color = ink, maxLines = 6, overflow = TextOverflow.Ellipsis)
            Text(item.author, fontSize = 11.sp, color = ink, maxLines = 2)
        }
        if (item.here) {
            Canvas(Modifier.align(Alignment.TopEnd).size(corner)) {
                drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height); close() }, PGreen)
            }
            Icon(Icons.Default.Check, contentDescription = "On this phone", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(corner * 0.42f))
        }
        if (item.hasAudio) Box(
            Modifier.align(Alignment.BottomStart).padding(8.dp).size(30.dp).background(if (item.audioHere) PGreen else Color.White).border(1.5.dp, PInk),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Headphones, contentDescription = if (item.audioHere) "Audiobook on this phone" else "Audiobook on the tablet", tint = if (item.audioHere) Color.White else PInk, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun MiniPlayer(st: AudiobookPlayer.State, doc: DocumentEntity?, onOpenDocument: (DocumentEntity) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    Row(
        modifier.padding(10.dp).fillMaxWidth().drawBehind { val o = 4.dp.toPx(); drawRect(PInk, topLeft = Offset(o, o), size = size) }
            .background(Color.White).border(1.5.dp, PInk).clickable(enabled = doc != null) { doc?.let(onOpenDocument) }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(st.title.ifBlank { doc?.title.orEmpty() }, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = PInk)
            Text(listOf(st.chapter, "${clockOf(st.positionMs)} of ${clockOf(st.durationMs)}").filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = PGrey, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Surface(onClick = { AudiobookPlayer.toggle(context) }, color = PInk, shape = androidx.compose.ui.graphics.RectangleShape, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(if (st.playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (st.playing) "Pause" else "Play", tint = Color.White) }
        }
    }
}

private fun clockOf(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

/** A book's page: its place, Listen / Read, and what of it is on this phone, with downloads and removal. */
@Composable
private fun PhoneBookPage(item: ShelfItem, away: Boolean, onDismiss: () -> Unit, onOpenDocument: (DocumentEntity) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    val doc = item.local
    val r = item.remote
    val localAudio by produceState<org.json.JSONObject?>(null, doc?.id, item.audioHere) {
        value = if (doc != null && item.audioHere) withContext(Dispatchers.IO) { Audiobooks.json(context, doc.id) } else null
    }
    val tabletAudio by produceState<org.json.JSONObject?>(null, r?.uuid) {
        value = if (r != null && r.audiobook && !away) TabletLink.remoteAudiobook(context, r) else null
    }
    val audioMs = localAudio?.optLong("durationMs") ?: tabletAudio?.optLong("durationMs") ?: 0L
    val audioPos = localAudio?.optLong("positionMs") ?: tabletAudio?.optLong("positionMs") ?: 0L
    val audioBytes = (tabletAudio ?: localAudio)?.optJSONArray("files")?.let { a -> (0 until a.length()).sumOf { a.getJSONObject(it).optLong("size") } } ?: 0L

    fun download(withAudio: Boolean) {
        if (r == null) return
        scope.launch {
            busy = "Starting…"
            runCatching { TabletLink.download(context, r, withAudio) { busy = it } }
                .onSuccess { busy = ""; Audiobooks.changes.value = Audiobooks.changes.value + 1 }
                .onFailure { busy = ""; Toast.makeText(context, "Could not download: ${it.message ?: it.javaClass.simpleName}", Toast.LENGTH_LONG).show() }
        }
    }

    Dialog(onDismissRequest = { if (busy.isBlank()) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).verticalScroll(rememberScrollState())) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SquareButton(onClick = { if (busy.isBlank()) onDismiss() }, label = "Back to the library") { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = PInk) }
                    Text("Library", fontSize = 14.sp, color = PGrey)
                }
                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BookCover(item, Modifier.width(132.dp).aspectRatio(2f / 3f), corner = 40.dp)
                    Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 27.sp, color = PInk)
                        if (item.author.isNotBlank()) Text(item.author, fontSize = 14.sp, color = PGrey)
                        Text(listOfNotNull(doc?.fileType ?: r?.fileType, doc?.totalPages?.takeIf { it > 1 }?.let { "$it pages" }).joinToString(", "), fontSize = 13.sp, color = PGrey)
                        val place = listOfNotNull(
                            (doc?.lastPositionChapter?.ifBlank { null } ?: r?.place?.ifBlank { null }),
                            if (audioPos > 0) "${clockOf(audioPos)} into the audiobook" else null
                        ).joinToString(", ")
                        if (place.isNotBlank()) Text(place + ".", fontSize = 13.sp, lineHeight = 18.sp, color = PInk, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (doc != null && item.audioHere) BigButton(if (audioPos > 0) "Listen from ${clockOf(audioPos)}" else "Listen", filled = true, Modifier.weight(1f)) {
                        if (AudiobookPlayer.load(context, doc.id, doc.title)) AudiobookPlayer.play(context)
                        onDismiss()
                    }
                    if (doc != null) BigButton("Read", filled = !item.audioHere, Modifier.weight(1f)) { onOpenDocument(doc) }
                    else BigButton(if (away) "Download when home" else "Download", filled = true, Modifier.weight(1f), enabled = !away && busy.isBlank()) { download(withAudio = r?.audiobook == true) }
                }
                if (busy.isNotBlank()) Text(busy, fontSize = 13.sp, color = PInk, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
                Text("What is on this phone", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PInk,
                    modifier = Modifier.fillMaxWidth().drawBehind { drawRect(PInk, size = size.copy(height = 1.5.dp.toPx())) }.padding(horizontal = 16.dp, vertical = 10.dp))
                StatusRow(
                    done = doc != null, title = "The book",
                    detail = listOfNotNull(doc?.fileType ?: r?.fileType, (doc?.filePath?.let { File(it).length() } ?: r?.size)?.takeIf { it > 0 }?.let { mb(it) }).joinToString(", "),
                    action = if (doc == null && r != null) "Download" else null, enabled = !away && busy.isBlank(), onAction = { download(false) }
                )
                if (item.hasAudio) StatusRow(
                    done = item.audioHere, title = "Audiobook",
                    detail = listOfNotNull(audioMs.takeIf { it > 0 }?.let { hours(it) }, audioBytes.takeIf { it > 0 }?.let { mb(it) }).joinToString(", ").ifBlank { if (item.audioHere) "On this phone" else "On the tablet" },
                    action = if (!item.audioHere && r?.audiobook == true) "Download" else null, enabled = !away && busy.isBlank(), onAction = { download(true) }
                )
                if (doc != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(28.dp).border(1.5.dp, PInk), contentAlignment = Alignment.Center) { Icon(Icons.Default.Bookmark, null, tint = PInk, modifier = Modifier.size(16.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(if (item.notes == 1) "1 highlight or note" else "${item.notes} highlights and notes", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PInk)
                        Text(if (r != null) "The same on the tablet after each sync" else "Only on this phone", fontSize = 13.sp, color = PGrey)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(PRule))
                if (doc != null && r != null) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.audioHere) BigButton("Remove the audiobook from this phone", filled = false, Modifier.fillMaxWidth(), danger = true, enabled = busy.isBlank()) {
                        scope.launch { TabletLink.removeAudioFromPhone(context, doc.id); Audiobooks.changes.value = Audiobooks.changes.value + 1 }
                    }
                    BigButton("Remove from this phone", filled = false, Modifier.fillMaxWidth(), danger = true, enabled = busy.isBlank()) { confirmRemove = true }
                    Text("The book stays on the tablet, and your notes and place are kept there.", fontSize = 12.sp, lineHeight = 17.sp, color = PGrey)
                }
            }
        }
    }
    if (confirmRemove && doc != null) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove “${item.title}” from this phone?") },
        text = { Text("Send any new notes first: they are synced before it is removed. The book stays on the tablet.") },
        confirmButton = { InkButton("Remove", danger = true, onClick = {
            confirmRemove = false
            scope.launch {
                busy = "Syncing notes first…"
                TabletLink.syncNow(context)
                if (TabletLink.reachable.value == true) {
                    TabletLink.removeFromPhone(context, doc.id); busy = ""; onDismiss()
                } else { busy = ""; Toast.makeText(context, "The tablet is out of reach, so new notes could not be sent. Try again at home.", Toast.LENGTH_LONG).show() }
            }
        }) },
        dismissButton = { InkButton("Cancel", onClick = { confirmRemove = false }) },
        containerColor = Color.White, shape = androidx.compose.ui.graphics.RectangleShape
    )
}

@Composable
private fun BigButton(label: String, filled: Boolean, modifier: Modifier, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val fg = when { !enabled -> Color(0xFF9CA3AF); filled -> Color.White; danger -> PRed; else -> PInk }
    Surface(onClick = onClick, enabled = enabled, color = if (filled && enabled) PInk else Color.White, shape = androidx.compose.ui.graphics.RectangleShape,
        border = if (filled && enabled) null else androidx.compose.foundation.BorderStroke(1.5.dp, if (danger && enabled) PRed else if (enabled) PInk else Color(0xFF9CA3AF)), modifier = modifier.heightIn(min = 48.dp)) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.Center) { Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 2) }
    }
}

@Composable
private fun StatusRow(done: Boolean, title: String, detail: String, action: String?, enabled: Boolean, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).then(if (done) Modifier.background(PGreen) else Modifier.border(1.5.dp, PInk)), contentAlignment = Alignment.Center) {
            if (done) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PInk)
            if (detail.isNotBlank()) Text(detail, fontSize = 13.sp, color = PGrey)
        }
        when {
            done -> Text("On this phone", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PGreen)
            action != null -> InkButton(action, enabled = enabled, minHeight = 44.dp, onClick = onAction)
            else -> Text("On the tablet", fontSize = 13.sp, color = PGrey)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(PRule))
}

private fun mb(b: Long) = if (b >= 1_000_000_000) "%.1f GB".format(b / 1e9) else "%.0f MB".format((b / 1e6).coerceAtLeast(0.1))
private fun hours(ms: Long): String { val m = ms / 60000; return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min" }
