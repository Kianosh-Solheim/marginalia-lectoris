package com.example.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.text.HtmlCompat
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.reader.BibField
import com.example.reader.BibStore
import com.example.reader.Citations
import com.example.reader.MetadataLookup
import com.example.reader.People
import com.example.reader.Zotero
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Opened from the side menu: the Zotero library on the tablet (same as the web view's Zotero tab). */
val zoteroOpen = mutableStateOf(false)

private val ZGrey = Color(0xFF52525B)
private val ZRule = Color(0xFFD4D4D8)

@Composable
fun ZoteroHost() {
    if (!zoteroOpen.value) return
    Dialog(onDismissRequest = { zoteroOpen.value = false }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        val view = androidx.compose.ui.platform.LocalView.current
        SideEffect {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        }
        Surface(Modifier.fillMaxSize(), color = Color.White) { ZoteroScreen(onClose = { zoteroOpen.value = false }) }
    }
}

private fun zoteroTypeLabel(t: String) = when (t) {
    "book" -> "Book"; "bookSection" -> "Book section"; "journalArticle" -> "Journal article"; "thesis" -> "Thesis"; "report" -> "Report"
    "conferencePaper" -> "Conference paper"; "webpage" -> "Web page"; "magazineArticle" -> "Magazine article"; "newspaperArticle" -> "Newspaper article"
    else -> t
}

@Composable
fun ZoteroScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Zotero.init(context); BibStore.init(context) } }
    var account by remember { mutableStateOf(Zotero.account(context)) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    var collection by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var docs by remember { mutableStateOf<List<DocumentEntity>>(emptyList()) }
    var importResult by remember { mutableStateOf<String?>(null) }
    BackHandler { if (selected != null) selected = null else onClose() }

    fun sync(loud: Boolean) {
        if (account == null) return
        busy = true; status = "Fetching from Zotero…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Zotero.sync(context) } }
            busy = false
            status = r.fold({ n -> if (n > 0 || loud) "$n change${if (n == 1) "" else "s"} from Zotero" else "" }, { "Couldn't reach Zotero: ${it.message}" })
            version++
        }
    }
    LaunchedEffect(account) {
        docs = withContext(Dispatchers.IO) { AppDatabase.getInstance(context).documentDao().getAllDocumentsSnapshot() }
        if (account != null) sync(false)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InkButton("←", onClick = { if (selected != null) selected = null else onClose() })
            Text("Zotero", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Color.Black)
            account?.let { Text("${Zotero.itemCount()} items · ${it.username}${if (it.canWrite) "" else " · read-only key"}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = ZGrey, modifier = Modifier.weight(1f)) }
                ?: Spacer(Modifier.weight(1f))
            if (account != null) {
                InkButton(if (busy) "Fetching…" else "Refresh", enabled = !busy, onClick = { sync(true) })
                InkButton("Disconnect", onClick = { Zotero.disconnect(context); account = null; selected = null })
            }
        }
        if (status.isNotBlank()) Text(status, fontSize = 13.sp, color = ZGrey)
        val acct = account
        if (acct == null) {
            ZoteroConnect(onConnected = { account = it })
            return@Column
        }
        key(version) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Collections
                Column(Modifier.width(220.dp).fillMaxHeight().border(1.dp, ZRule)) {
                    val cols = remember(version) { Zotero.collectionList() }
                    LazyColumn {
                        item { ZCol("My Library", Zotero.itemCount(), collection == null, 0) { collection = null; selected = null } }
                        fun depth(c: JSONObject): Int { var d = 0; var p = c.opt("parentCollection"); while (p is String && d < 8) { d++; p = cols.firstOrNull { it.optString("key") == p }?.opt("parentCollection") }; return d }
                        val ordered = mutableListOf<JSONObject>()
                        fun walk(parent: String) { cols.filter { (it.opt("parentCollection") as? String ?: "") == parent }.forEach { ordered += it; walk(it.optString("key")) } }
                        walk("")
                        items(ordered, key = { it.optString("key") }) { c ->
                            ZCol(c.optString("name"), Zotero.list(c.optString("key"), null).size, collection == c.optString("key"), depth(c) + 1) { collection = c.optString("key"); selected = null }
                        }
                    }
                }
                // Items
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(query, { query = it }, placeholder = { Text("Search title, author, year, key…") }, singleLine = true, modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Black, unfocusedBorderColor = ZRule), shape = RoundedCornerShape(4.dp))
                        if (collection != null) InkButton("Import collection", enabled = !busy, onClick = {
                            val col = collection ?: return@InkButton
                            busy = true
                            scope.launch {
                                importResult = withContext(Dispatchers.IO) { runCatching { importCollection(context, col) }.getOrElse { "Couldn't import: ${it.message}" } }
                                busy = false; version++
                            }
                        })
                    }
                    val list = remember(version, collection, query) { Zotero.list(collection, query, deep = true) }
                    Text("${list.size} item${if (list.size == 1) "" else "s"}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = ZGrey)
                    LazyColumn(Modifier.fillMaxSize().border(1.dp, ZRule)) {
                        items(list, key = { it.optString("key") }) { d ->
                            val k = d.optString("key")
                            Column(Modifier.fillMaxWidth().background(if (selected == k) Color(0xFFF4F4F5) else Color.White).clickable { selected = k }.padding(10.dp)) {
                                Text((if (Zotero.docFor(k) != null) "▣ " else "") + d.optString("title").ifBlank { "(untitled)" }, fontFamily = FontFamily.Serif, fontSize = 15.sp, color = Color.Black)
                                Text(listOf(Zotero.creatorsLine(d), Zotero.year(d), zoteroTypeLabel(d.optString("itemType"))).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = ZGrey)
                            }
                            HorizontalDivider(color = ZRule)
                        }
                    }
                }
                // Detail
                Column(Modifier.weight(1.2f).fillMaxHeight().border(1.dp, ZRule).padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val k = selected
                    if (k == null) Text("Choose an item.", color = ZGrey)
                    else ZoteroItemDetail(k, docs, onChanged = { version++ })
                }
            }
        }
    }
    importResult?.let { msg ->
        AlertDialog(onDismissRequest = { importResult = null }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
            title = { Text("Collection imported", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { importResult = null }) { Text("Done", color = Color.Black, fontWeight = FontWeight.Bold) } })
    }
}

@Composable
private fun ZCol(name: String, count: Int, on: Boolean, depth: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (on) Color.Black else Color.White).clickable(onClick = onClick).padding(start = (10 + depth * 14).dp, end = 10.dp, top = 8.dp, bottom = 8.dp)) {
        Text(name, color = if (on) Color.White else Color.Black, fontSize = 14.sp, fontWeight = if (depth == 0) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        Text(count.toString(), color = if (on) Color.White else ZGrey, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ZoteroConnect(onConnected: (Zotero.Account) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Connect Zotero", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.Black)
        Text("Make an API key at zotero.org/settings/keys with “Allow library access”, and “Allow write access” to send fixes and new items to Zotero. Paste it here. It is stored encrypted on the tablet and never in backups.", fontSize = 14.sp, color = ZGrey)
        OutlinedTextField(key, { key = it.trim() }, label = { Text("Zotero API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Black, unfocusedBorderColor = ZRule), shape = RoundedCornerShape(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton(if (busy) "Checking…" else "Connect", filled = true, enabled = !busy && key.isNotBlank(), onClick = {
                busy = true; msg = ""
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Zotero.connect(context, key).also { runCatching { Zotero.sync(context) } } } }
                    busy = false
                    r.onSuccess { onConnected(it) }.onFailure { msg = it.message ?: "Couldn't connect." }
                }
            })
            InkButton("Open zotero.org/settings/keys", onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.zotero.org/settings/keys")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
        }
        if (msg.isNotBlank()) Text(msg, color = Color(0xFFB91C1C), fontSize = 13.sp)
    }
}

/** Links what clearly matches, tags the books with the collection, and says what is left. */
private suspend fun importCollection(context: android.content.Context, collection: String): String {
    val dao = AppDatabase.getInstance(context).documentDao()
    val docs = dao.getAllDocumentsSnapshot()
    var linked = 0; var already = 0; var tagged = 0
    val unsure = mutableListOf<String>(); val missing = mutableListOf<String>()
    Zotero.list(collection, null).forEach { d ->
        val k = d.getString("key")
        val existing = Zotero.docFor(k)
        if (existing != null) {
            already++
            if (existing.second == null) docs.firstOrNull { it.id == existing.first }?.let { doc ->
                val t = Zotero.withCollectionTags(doc.tags, d); if (t != doc.tags) { dao.updateTags(doc.id, t); tagged++ }
            }
            return@forEach
        }
        val best = Zotero.suggestDocs(d, docs).filter { Zotero.linkOf(it.document.id) == null }
        val top = best.firstOrNull()
        if (top != null && top.strength >= 3 && best.count { it.strength >= 3 } == 1) {
            Zotero.link(context, top.document.id, k); linked++
            val t = Zotero.withCollectionTags(top.document.tags, d); if (t != top.document.tags) { dao.updateTags(top.document.id, t); tagged++ }
        } else if (best.isNotEmpty()) unsure += d.optString("title") else missing += d.optString("title")
    }
    return buildString {
        append("$linked linked now, $already linked already, $tagged book${if (tagged == 1) "" else "s"} tagged with the collection.")
        if (unsure.isNotEmpty()) append("\n\nProbably on the tablet — open each to choose the book:\n• " + unsure.take(12).joinToString("\n• "))
        if (missing.isNotEmpty()) append("\n\nNot on the tablet:\n• " + missing.take(12).joinToString("\n• ") + if (missing.size > 12) "\n…" else "")
    }
}

@Composable
private fun ZoteroItemDetail(itemKey: String, docs: List<DocumentEntity>, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val d = remember(itemKey, refresh) { Zotero.item(itemKey) } ?: run { Text("Not in the Zotero cache.", color = ZGrey); return }
    var bib by remember(itemKey) { mutableStateOf("") }
    var checking by remember { mutableStateOf<Pair<Long, Int?>?>(null) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(itemKey) {
        bib = withContext(Dispatchers.IO) { runCatching { HtmlCompat.fromHtml(Zotero.bib(context, itemKey, Citations.defaultStyle(context)), HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim() }.getOrDefault("") }
    }
    val link = remember(itemKey, refresh) { Zotero.docFor(itemKey) }
    Text(d.optString("title"), fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.Black)
    Text(listOf(Zotero.creatorsLine(d), Zotero.year(d), zoteroTypeLabel(d.optString("itemType"))).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 13.sp, color = ZGrey)
    Column(Modifier.fillMaxWidth().border(1.dp, ZRule).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (link != null) {
            val doc = docs.firstOrNull { it.id == link.first }
            Text("▣ On the tablet: ${doc?.title ?: "book ${link.first}"}" + (link.second?.let { " (chapter ${it + 1})" } ?: ""), fontWeight = FontWeight.Bold, color = Color(0xFF15803D), fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton("Check citation", filled = true, onClick = { checking = link })
                InkButton("Unlink", onClick = { Zotero.unlink(context, link.first, link.second); refresh++; onChanged() })
            }
        } else {
            Text("NOT ON THE TABLET YET", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ZGrey)
            val sug = remember(itemKey, refresh, docs) { Zotero.suggestDocs(d, docs).filter { Zotero.linkOf(it.document.id) == null } }
            sug.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(s.document.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Black); Text("${s.document.author} · ${s.reason}", fontSize = 12.sp, color = ZGrey) }
                    InkButton("Link", filled = s.strength >= 3, onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                Zotero.link(context, s.document.id, itemKey)
                                val t = Zotero.withCollectionTags(s.document.tags, d)
                                if (t != s.document.tags) AppDatabase.getInstance(context).documentDao().updateTags(s.document.id, t)
                            }
                            refresh++; onChanged()
                        }
                    })
                }
            }
            InkButton("Link to another book…", onClick = { picking = true })
        }
    }
    Text("ZOTERO’S REFERENCE · ${Citations.defaultStyle(context).label.uppercase()}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ZGrey)
    Text(bib.ifBlank { "—" }, fontFamily = FontFamily.Serif, fontSize = 14.sp, color = Color.Black)
    val f = remember(itemKey, refresh) { Zotero.toFound(d) }
    listOf("Citation key" to Zotero.citationKey(d), "Publisher" to f.record[BibField.PUBLISHER], "Place" to f.record[BibField.PLACE], "Pages" to f.record[BibField.PAGES],
        "DOI" to f.record[BibField.DOI], "ISBN" to f.record[BibField.ISBN], "Collections" to Zotero.collectionNames(d).joinToString(", "), "Tags" to Zotero.tagsOf(d).joinToString(", "))
        .filter { it.second.isNotBlank() }.forEach { (l, v) ->
            Row { Text(l, fontSize = 13.sp, color = ZGrey, modifier = Modifier.width(110.dp)); Text(v, fontSize = 13.sp, color = Color.Black) }
        }
    checking?.let { (docId, ch) -> ZoteroCheckDialog(docId, ch, itemKey, onDismiss = { checking = null; refresh++; onChanged() }) }
    if (picking) {
        var q by remember { mutableStateOf(d.optString("title").take(40)) }
        AlertDialog(onDismissRequest = { picking = false }, containerColor = Color.White, shape = RoundedCornerShape(4.dp),
            title = { Text("Link to a book on the tablet", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Black, unfocusedBorderColor = ZRule))
                    val terms = MetadataLookup.norm(q).split(' ').filter { it.isNotBlank() }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(docs.filter { doc -> terms.isEmpty() || MetadataLookup.norm(doc.title + " " + doc.author).let { h -> terms.all { h.contains(it) } } }.take(50), key = { it.id }) { doc ->
                            Column(Modifier.fillMaxWidth().clickable {
                                picking = false
                                scope.launch { withContext(Dispatchers.IO) { Zotero.link(context, doc.id, itemKey) }; refresh++; onChanged() }
                            }.padding(8.dp)) { Text(doc.title, fontWeight = FontWeight.Bold, color = Color.Black); Text(doc.author, fontSize = 12.sp, color = ZGrey) }
                        }
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel", color = ZGrey) } })
    }
}

/** One line of the citation check. */
private data class ZRow(val key: String, val label: String, val mine: String, val theirs: String)

/**
 * The citation check on the tablet: each field that differs between the app and Zotero, with
 * Keep mine, Use Zotero's or Send mine to Zotero.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoteroCheckDialog(docId: Long, chapter: Int?, itemKey: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<ZRow>?>(null) }
    val choice = remember { mutableStateMapOf<String, String>() }
    var doc by remember { mutableStateOf<DocumentEntity?>(null) }
    var busy by remember { mutableStateOf(false) }
    val canWrite = Zotero.account(context)?.canWrite == true
    LaunchedEffect(docId, chapter) {
        val r = withContext(Dispatchers.IO) {
            val dd = AppDatabase.getInstance(context).documentDao().getDocumentById(docId) ?: return@withContext null
            doc = dd
            val item = Zotero.item(itemKey) ?: return@withContext null
            val found = Zotero.toFound(item)
            val vol = com.example.reader.EditedVolumes.get(context, dd.id)?.takeIf { it.enabled }
            val (mine, rec) = if (chapter != null && vol != null && chapter in vol.chapters.indices) com.example.reader.EditedVolumes.chapterAsDocument(dd, vol, chapter) else dd to BibStore.get(dd.id)
            val cmp = MetadataLookup.compare(rec, if (com.example.reader.FolderBooks.isFolder(mine)) "" else mine.author, mine.summary, mine.title, found)
                .filter { !it.same }
                .filter { chapter == null || it.key in setOf("AUTHOR", "TITLE", "PAGES", "DOI", "ABSTRACT") }
                .map { ZRow(it.key, it.label, it.current, it.found) }.toMutableList()
            val mineKey = Citations.citeKey(mine, rec); val theirKey = Zotero.citationKey(item)
            if (mineKey != theirKey) cmp += ZRow("CITEKEY", "Citation key", mineKey, theirKey)
            cmp
        }
        rows = r ?: emptyList()
        r?.forEach { choice[it.key] = when { it.key == "CITEKEY" && it.theirs.isBlank() && canWrite -> "send"; it.mine.isBlank() -> "use"; else -> "keep" } }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Color.White, shape = RoundedCornerShape(4.dp),
        title = { Text(if (chapter == null) "Check against Zotero" else "Check this chapter against Zotero", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val list = rows
                when {
                    list == null -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color.Black)
                    list.isEmpty() -> Text("✓ Everything matches Zotero.", color = Color(0xFF15803D), fontWeight = FontWeight.Bold)
                    else -> list.forEach { r ->
                        Column(Modifier.fillMaxWidth().border(1.dp, ZRule).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(r.label.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ZGrey)
                            Text("This app: ${r.mine.ifBlank { "—" }.take(300)}", fontSize = 13.sp, color = Color.Black)
                            Text("Zotero: ${r.theirs.ifBlank { "—" }.take(300)}", fontSize = 13.sp, color = ZGrey)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                InkChip("Keep mine", choice[r.key] == "keep", { choice[r.key] = "keep" })
                                if (r.theirs.isNotBlank() || r.key != "CITEKEY") InkChip("Use Zotero’s", choice[r.key] == "use", { choice[r.key] = "use" })
                                if (canWrite && r.mine.isNotBlank() && r.key != "TYPE") InkChip("Send mine", choice[r.key] == "send", { choice[r.key] = "send" })
                            }
                        }
                    }
                }
                if (!canWrite) Text("Your key is read-only, so nothing can be sent to Zotero.", fontSize = 12.sp, color = ZGrey)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && !rows.isNullOrEmpty(), onClick = {
                val d0 = doc ?: return@TextButton
                val list = rows.orEmpty()
                busy = true
                scope.launch {
                    val msg = withContext(Dispatchers.IO) {
                        runCatching {
                            val dao = AppDatabase.getInstance(context).documentDao()
                            val use = list.filter { choice[it.key] == "use" }
                            val send = list.filter { choice[it.key] == "send" }
                            if (use.isNotEmpty()) {
                                if (chapter != null) {
                                    val v = com.example.reader.EditedVolumes.get(context, d0.id)
                                    val c = v?.chapters?.getOrNull(chapter)
                                    if (v != null && c != null) {
                                        var n: com.example.reader.VolumeChapter = c
                                        use.forEach { r -> n = when (r.key) {
                                            "TITLE" -> n.copy(title = r.theirs, isManual = true)
                                            "AUTHOR" -> n.copy(authors = People.join(People.split(r.theirs)), authorSource = "user", isManual = true)
                                            "PAGES" -> n.copy(printedPages = r.theirs, isManual = true)
                                            "DOI" -> n.copy(doi = r.theirs, isManual = true)
                                            "ABSTRACT" -> n.copy(abstract = r.theirs, isManual = true)
                                            else -> n
                                        } }
                                        com.example.reader.EditedVolumes.put(context, d0.id, v.copy(chapters = v.chapters.toMutableList().also { it[chapter] = n }))
                                    }
                                } else {
                                    var rec = BibStore.get(d0.id)
                                    use.forEach { r ->
                                        when (r.key) {
                                            "AUTHOR" -> dao.updateAuthor(d0.id, r.theirs)
                                            "ABSTRACT" -> dao.updateSummary(d0.id, r.theirs)
                                            "TYPE" -> rec = rec.copy(entryType = r.theirs)
                                            else -> runCatching { BibField.valueOf(r.key) }.getOrNull()?.let { f -> rec = rec.with(f, r.theirs) }
                                        }
                                    }
                                    BibStore.put(d0.id, rec)
                                }
                            }
                            var extra = ""
                            if (send.isNotEmpty()) {
                                val rec = BibStore.get(d0.id)
                                val changes = LinkedHashMap<String, String>()
                                send.forEach { r ->
                                    if ((r.key == "TITLE" || r.key == "SUBTITLE") && chapter == null) changes["TITLE"] = listOf(rec[BibField.TITLE].ifBlank { d0.title }, rec[BibField.SUBTITLE]).filter { it.isNotBlank() }.joinToString(": ")
                                    else if (r.key != "TYPE") changes[r.key] = r.mine
                                }
                                val skipped = Zotero.push(context, itemKey, changes)
                                extra = " · sent to Zotero" + if (skipped.isNotEmpty()) " (no place for ${skipped.joinToString(", ")})" else ""
                            }
                            "Saved" + (if (use.isNotEmpty()) " · ${use.size} from Zotero" else "") + extra
                        }.getOrElse { "Couldn't save: ${it.message}" }
                    }
                    busy = false
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    onDismiss()
                }
            }) { Text(if (busy) "Saving…" else "Apply", color = Color.Black, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Close", color = ZGrey) } }
    )
}
