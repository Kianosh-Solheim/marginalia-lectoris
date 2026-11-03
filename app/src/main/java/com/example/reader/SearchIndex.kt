package com.example.reader

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.core.text.HtmlCompat
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * Full-text search in the books themselves (PDF, EPUB, HTML, Markdown, text), and in the notes.
 *
 * Each book's text is read once — page by page for PDFs (scanned pages are read with OCR), section by
 * section for EPUBs — and kept in a full-text index (SQLite FTS4) in the app's files, so searching
 * the whole library takes milliseconds. Books are indexed in the background, after import and when
 * the app starts; a book that is searched before it is indexed is indexed first.
 *
 * Matching is forgiving: case, accents (é → e, å → a, ø → o, æ → ae), ligatures and words broken
 * over a line with a hyphen are ignored, and words match their longer forms (vold → volden) unless
 * "whole words" is chosen. "Quoted words" match as an exact phrase.
 */
object SearchIndex {
    private const val TAG = "SearchIndex"
    private const val VERSION = 3   // bump to re-index everything after a change in how text is read

    data class Progress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val current: String = "")
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /** One place in a book where the search matched. [loc] is the PDF page or EPUB position; [start]/[end] mark the match in [snippet]. */
    data class Hit(val docId: Long, val loc: Int, val href: String, val label: String, val snippet: String, val start: Int, val end: Int, val occurrence: Int)
    data class BookResult(val doc: DocumentEntity, val count: Int, val hits: List<Hit>)
    data class NoteHit(val note: HighlightEntity, val docTitle: String, val snippet: String, val start: Int, val end: Int)

    // ------------------------------------------------------------------ storage

    private class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "search_index.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE units (id INTEGER PRIMARY KEY, doc_id INTEGER NOT NULL, loc INTEGER, href TEXT, label TEXT, text TEXT)")
            db.execSQL("CREATE INDEX units_doc ON units(doc_id)")
            db.execSQL("CREATE VIRTUAL TABLE fts USING fts4(body, tokenize=simple)")
            db.execSQL("CREATE TABLE docs (doc_id INTEGER PRIMARY KEY, sig TEXT, units INTEGER, updated INTEGER)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    @Volatile private var db: Db? = null
    private fun db(ctx: Context): SQLiteDatabase = (db ?: synchronized(this) { db ?: Db(ctx).also { db = it } }).writableDatabase

    // ------------------------------------------------------------------ forgiving text

    private val combining = Regex("\\p{Mn}+")
    private fun foldChar(c: Char): String = when (c) {
        'ø', 'Ø' -> "o"; 'æ', 'Æ' -> "ae"; 'œ', 'Œ' -> "oe"; 'ß' -> "ss"; 'ł', 'Ł' -> "l"; 'đ', 'Đ', 'ð', 'Ð' -> "d"; 'þ', 'Þ' -> "th"
        '’', '‘', 'ʼ' -> "'"
        else -> {
            val n = Normalizer.normalize(c.toString(), Normalizer.Form.NFKD).replace(combining, "")
            n.lowercase(Locale.ROOT)
        }
    }

    /** The forgiving form of [s], and for each of its characters the index in [s] it came from. */
    fun normWithMap(s: String): Pair<String, IntArray> {
        val out = StringBuilder(s.length)
        val map = IntArray(s.length * 2 + 4)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            // A word broken over a line with a hyphen: "vol-\nden" → "volden".
            if ((c == '-' || c == '\u00AD') && i > 0 && s[i - 1].isLetter()) {
                var j = i + 1
                while (j < s.length && (s[j] == ' ' || s[j] == '\t' || s[j] == '\r')) j++
                if (j < s.length && s[j] == '\n') {
                    j++
                    while (j < s.length && s[j].isWhitespace()) j++
                    if (j < s.length && s[j].isLowerCase()) { i = j; continue }
                }
                if (c == '\u00AD') { i++; continue }
            }
            val f = if (c.isWhitespace()) " " else foldChar(c)
            for (ch in f) {
                if (ch == ' ' && out.isNotEmpty() && out[out.length - 1] == ' ') continue
                if (out.length >= map.size) break
                map[out.length] = i
                out.append(ch)
            }
            i++
        }
        return out.toString() to map.copyOf(out.length)
    }

    fun norm(s: String): String = normWithMap(s).first

    private fun tokens(s: String): List<String> = norm(s).split(Regex("[^\\p{L}\\p{N}']+")).map { it.trim('\'') }.filter { it.isNotBlank() }

    /** A search: words (matched as word beginnings unless [whole]) and "exact phrases". */
    class Query(raw: String, val whole: Boolean) {
        val phrases: List<List<String>>
        val words: List<String>
        init {
            val ph = ArrayList<List<String>>()
            val rest = Regex("\"([^\"]+)\"").replace(raw) { m -> tokens(m.groupValues[1]).takeIf { it.isNotEmpty() }?.let { ph += it }; " " }
            phrases = ph
            words = tokens(rest)
        }
        val isEmpty get() = phrases.isEmpty() && words.isEmpty()
        fun fts(): String = (phrases.map { p -> "\"" + p.joinToString(" ") + "\"" } + words.map { w -> if (whole) w else "$w*" }).joinToString(" ")

        /** Where the search matches in [normText] (the forgiving form): ranges of the phrases and words. */
        fun ranges(normText: String): List<IntRange> {
            val out = ArrayList<IntRange>()
            fun boundaryBefore(i: Int) = i == 0 || !normText[i - 1].isLetterOrDigit()
            fun boundaryAfter(i: Int) = i >= normText.length || !normText[i].isLetterOrDigit()
            fun find(needle: String, prefixOk: Boolean) {
                var from = 0
                while (true) {
                    val i = normText.indexOf(needle, from)
                    if (i < 0) break
                    var end = i + needle.length
                    if (boundaryBefore(i) && (prefixOk || boundaryAfter(end))) {
                        if (prefixOk) while (end < normText.length && normText[end].isLetterOrDigit()) end++
                        out += i until end
                    }
                    from = i + 1
                }
            }
            phrases.forEach { p -> find(p.joinToString(" "), !whole) }
            words.forEach { w -> find(w, !whole) }
            return out.sortedBy { it.first }
        }
    }

    // ------------------------------------------------------------------ reading the books

    private class Chunk(val loc: Int, val href: String, val label: String, val text: String)

    private fun signature(d: DocumentEntity): String {
        val f = File(d.filePath)
        return "$VERSION|${f.length()}|${f.lastModified()}|${d.pdfTextExtractionMode}|${d.pdfPageExtractionOverridesJson.hashCode()}"
    }

    private fun indexable(d: DocumentEntity): Boolean =
        !FolderBooks.isFolder(d) && !ZoteroPlaceholders.isPlaceholder(d) && File(d.filePath).isFile &&
            d.fileType.uppercase(Locale.ROOT) in setOf("PDF", "EPUB", "HTML", "HTM", "XHTML", "TXT", "MD")

    private suspend fun pdfUnits(ctx: Context, d: DocumentEntity, onPage: (Int, Int) -> Unit): List<Chunk> {
        val r = PdfDocumentRenderer(File(d.filePath), ctx)
        try {
            runCatching {
                r.updateExtractionConfiguration(
                    PdfTextExtractionMode.fromId(d.pdfTextExtractionMode),
                    PdfPageDiagnosticsAndOcrEngine.parsePageOverridesJson(d.pdfPageExtractionOverridesJson),
                    d.pdfOcrLanguage.ifBlank { "AUTO" })
            }
            val n = r.pageCount
            val out = ArrayList<Chunk>(n)
            for (i in 0 until n) {
                yield()
                onPage(i + 1, n)
                val data = runCatching { r.extractPageTextData(i) }.getOrNull()
                var text = data?.fullText.orEmpty()
                // A page without a text layer (scanned): read it with OCR.
                if (text.count { it.isLetterOrDigit() } < 20) {
                    val ocr = runCatching {
                        val bmp = r.renderPage(i, targetWidth = 1400)
                        PdfPageDiagnosticsAndOcrEngine.performOcrOnPageBitmap(i, bmp).fullText.also { bmp?.recycle() }
                    }.getOrNull().orEmpty()
                    if (ocr.count { it.isLetterOrDigit() } > text.count { it.isLetterOrDigit() }) text = ocr
                }
                if (text.isNotBlank()) out += Chunk(i + 1, "", data?.printedPageLabel.orEmpty(), text)
            }
            return out
        } finally { runCatching { r.close() } }
    }

    private fun htmlText(html: String): String {
        val body = html.replace(Regex("(?is)<head.*?</head>"), " ").replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)</(p|div|h[1-6]|li|blockquote|tr|section|br)\\s*>|<br\\s*/?>"), "$0\n")
        return HtmlCompat.fromHtml(body, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().replace('\uFFFC', ' ')
    }

    private suspend fun epubUnits(ctx: Context, d: DocumentEntity): List<Chunk> {
        val file = File(d.filePath)
        val index = runCatching { com.example.server.EpubIndex.of(ctx, file) }.getOrNull()
        val out = ArrayList<Chunk>()
        ZipFile(file).use { zip ->
            fun read(path: String) = zip.getEntry(path)?.let { e -> zip.getInputStream(e).bufferedReader().readText() }
            val container = read("META-INF/container.xml") ?: return out
            val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1) ?: return out
            val opf = read(opfPath) ?: return out
            val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val manifest = HashMap<String, String>()
            Regex("<(?:\\w+:)?item\\b[^>]*>").findAll(opf).forEach { m ->
                val tag = m.value
                val id = Regex("\\bid=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: return@forEach
                val href = Regex("\\bhref=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: return@forEach
                manifest[id] = java.net.URLDecoder.decode(href, "UTF-8")
            }
            val spine = Regex("<(?:\\w+:)?itemref\\b[^>]*idref=\"([^\"]+)\"").findAll(opf).mapNotNull { manifest[it.groupValues[1]] }.toList()
            for (rel in spine) {
                yield()
                val full = normalizePath(base + rel)
                val text = read(full)?.let { htmlText(it) }.orEmpty()
                if (text.isBlank()) continue
                val res = index?.let { com.example.server.EpubIndex.resourceFor(it, rel) }
                // Long sections are kept in parts, each with its approximate position.
                val parts = chunk(text, 2400)
                var offset = 0
                parts.forEach { part ->
                    val loc = if (res != null) res.first + ((offset.toDouble() / text.length) * res.count).toInt().coerceAtMost(res.count - 1).coerceAtLeast(0) else 1
                    out += Chunk(loc, rel, res?.title.orEmpty(), part)
                    offset += part.length
                }
            }
        }
        return out
    }

    private fun normalizePath(p: String): String {
        val parts = ArrayList<String>()
        p.split('/').forEach { s -> when (s) { "", "." -> Unit; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1); else -> parts += s } }
        return parts.joinToString("/")
    }

    /** Splits at paragraph or sentence ends near [size] characters. */
    private fun chunk(text: String, size: Int): List<String> {
        if (text.length <= size * 1.3) return listOf(text)
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            var end = (i + size).coerceAtMost(text.length)
            if (end < text.length) {
                val para = text.lastIndexOf('\n', end); val dot = text.lastIndexOf(". ", end)
                end = when { para > i + size / 2 -> para + 1; dot > i + size / 2 -> dot + 2; else -> end }
            }
            out += text.substring(i, end); i = end
        }
        return out
    }

    private fun plainUnits(d: DocumentEntity): List<Chunk> {
        val raw = File(d.filePath).readText(Charsets.UTF_8)
        val text = if (d.fileType.uppercase(Locale.ROOT) in setOf("HTML", "HTM", "XHTML")) htmlText(raw) else raw
        return chunk(text, 2400).map { Chunk(1, "", "", it) }
    }

    // ------------------------------------------------------------------ indexing

    private val mutex = Mutex()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "search-index").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    @Volatile private var job: Job? = null

    private fun isCurrent(ctx: Context, d: DocumentEntity): Boolean =
        db(ctx).rawQuery("SELECT sig FROM docs WHERE doc_id=?", arrayOf(d.id.toString())).use { c -> c.moveToFirst() && c.getString(0) == signature(d) }

    fun isIndexed(ctx: Context, docId: Long): Boolean =
        db(ctx).rawQuery("SELECT 1 FROM docs WHERE doc_id=?", arrayOf(docId.toString())).use { it.moveToFirst() }

    /** Reads one book into the index (again, if it changed). */
    suspend fun indexDoc(ctx: Context, d: DocumentEntity, force: Boolean = false) = mutex.withLock {
        if (!indexable(d)) return@withLock
        if (!force && isCurrent(ctx, d)) return@withLock
        val units = runCatching {
            when (d.fileType.uppercase(Locale.ROOT)) {
                "PDF" -> pdfUnits(ctx, d) { p, n -> _progress.value = _progress.value.copy(current = "${d.title} · page $p of $n") }
                "EPUB" -> epubUnits(ctx, d)
                else -> plainUnits(d)
            }
        }.onFailure { Log.w(TAG, "Could not read ${d.title}", it) }.getOrNull() ?: return@withLock
        val w = db(ctx)
        w.beginTransaction()
        try {
            removeLocked(w, d.id)
            units.forEach { u ->
                val id = w.insert("units", null, ContentValues().apply {
                    put("doc_id", d.id); put("loc", u.loc); put("href", u.href); put("label", u.label); put("text", u.text)
                })
                w.execSQL("INSERT INTO fts(rowid, body) VALUES(?, ?)", arrayOf<Any>(id, norm(u.text)))
            }
            w.insertWithOnConflict("docs", null, ContentValues().apply {
                put("doc_id", d.id); put("sig", signature(d)); put("units", units.size); put("updated", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
    }

    private fun removeLocked(w: SQLiteDatabase, docId: Long) {
        w.execSQL("DELETE FROM fts WHERE rowid IN (SELECT id FROM units WHERE doc_id=?)", arrayOf<Any>(docId))
        w.execSQL("DELETE FROM units WHERE doc_id=?", arrayOf<Any>(docId))
        w.execSQL("DELETE FROM docs WHERE doc_id=?", arrayOf<Any>(docId))
    }

    /** Starts (or continues) indexing the library in the background. Safe to call often. */
    fun kick(ctx: Context) {
        val app = ctx.applicationContext
        if (job?.isActive == true) return
        job = scope.launch {
            delay(4000)   // let the app finish starting
            runCatching {
                val docs = AppDatabase.getInstance(app).documentDao().getAllDocumentsSnapshot()
                // Forget books that were deleted.
                val ids = docs.map { it.id }.toSet()
                val known = db(app).rawQuery("SELECT doc_id FROM docs", null).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
                mutex.withLock { val w = db(app); known.filter { it !in ids }.forEach { removeLocked(w, it) } }
                val todo = docs.filter { indexable(it) && !isCurrent(app, it) }
                    .sortedByDescending { maxOf(it.lastReadTimestamp, it.dateAdded) }   // recently read first
                _progress.value = Progress(todo.isNotEmpty(), 0, todo.size)
                todo.forEachIndexed { i, d ->
                    if (!isActive) return@launch
                    _progress.value = Progress(true, i, todo.size, d.title)
                    indexDoc(app, d)
                }
            }.onFailure { Log.w(TAG, "Indexing stopped", it) }
            _progress.value = Progress(false, _progress.value.total, _progress.value.total)
        }
    }

    // ------------------------------------------------------------------ searching

    private fun collapse(s: String) = s.replace(Regex("\\s+"), " ")

    /** Short passages around each match: (text, match start, match end). */
    private fun snippet(text: String, q: Query): List<Triple<String, Int, Int>> {
        val (n, map) = normWithMap(text)
        return q.ranges(n).map { r ->
            val s = map.getOrElse(r.first) { 0 }
            val e = (map.getOrElse(r.last) { text.length - 1 } + 1).coerceIn(s, text.length)
            val from = (s - 70).coerceAtLeast(0).let { f -> text.lastIndexOf(' ', f).takeIf { it >= 0 && it >= f - 20 } ?: f }
            val to = (e + 90).coerceAtMost(text.length).let { t -> text.indexOf(' ', t).takeIf { it in t..(t + 20) } ?: t }
            val lead = if (from > 0) "…" else ""
            val pre = collapse(text.substring(from, s)).trimStart()
            val mid = collapse(text.substring(s, e))
            val post = collapse(text.substring(e, to)).trimEnd()
            val start = lead.length + pre.length
            Triple(lead + pre + mid + post + if (to < text.length) "…" else "", start, start + mid.length)
        }
    }

    /** Matches of a search in any text (for books searched in memory, such as the tablet's EPUB pages). */
    fun findIn(text: String, raw: String, whole: Boolean): List<Triple<String, Int, Int>> {
        val q = Query(raw, whole)
        return if (q.isEmpty) emptyList() else snippet(text, q)
    }

    /**
     * Searches the indexed books ([docId] = one book only). Returns books with their hits, the book
     * with most hits first (in one book: in reading order).
     */
    fun search(ctx: Context, raw: String, whole: Boolean, docId: Long? = null, perBook: Int = 6, maxUnits: Int = 3000): List<Pair<Long, Pair<Int, List<Hit>>>> {
        val q = Query(raw, whole)
        if (q.isEmpty) return emptyList()
        val sql = "SELECT u.doc_id, u.loc, u.href, u.label, u.text FROM fts JOIN units u ON u.id = fts.rowid WHERE fts MATCH ?" +
            (if (docId != null) " AND u.doc_id = ?" else "") + " ORDER BY u.doc_id, u.id LIMIT $maxUnits"
        val args = if (docId != null) arrayOf(q.fts(), docId.toString()) else arrayOf(q.fts())
        val byDoc = LinkedHashMap<Long, Pair<Int, ArrayList<Hit>>>()
        val occ = HashMap<String, Int>()
        runCatching {
            db(ctx).rawQuery(sql, args).use { c ->
                while (c.moveToNext()) {
                    val doc = c.getLong(0); val loc = c.getInt(1); val href = c.getString(2).orEmpty(); val label = c.getString(3).orEmpty(); val text = c.getString(4).orEmpty()
                    val snips = snippet(text, q)
                    val cur = byDoc.getOrPut(doc) { 0 to ArrayList() }
                    var count = cur.first
                    snips.forEach { (s, a, b) ->
                        val key = "$doc|$href"
                        val o = occ.getOrDefault(key, 0); occ[key] = o + 1
                        if (cur.second.size < (if (docId != null) 2000 else perBook)) cur.second += Hit(doc, loc, href, label, s, a, b, o)
                        count++
                    }
                    byDoc[doc] = count to cur.second
                }
            }
        }.onFailure { Log.w(TAG, "Search failed for '$raw'", it) }
        return byDoc.entries.map { it.key to (it.value.first to it.value.second.toList()) }.sortedByDescending { if (docId != null) 0 else it.second.first }
    }

    /** The notes (title, quote and own words) that match, newest first. */
    suspend fun searchNotes(ctx: Context, raw: String, whole: Boolean, docId: Long? = null, limit: Int = 200): List<NoteHit> = withContext(Dispatchers.IO) {
        val q = Query(raw, whole)
        if (q.isEmpty) return@withContext emptyList()
        val dao = AppDatabase.getInstance(ctx)
        val notes = dao.highlightDao().getAllHighlights().first().filter { docId == null || it.documentId == docId }
        val titles = HashMap<Long, String>()
        notes.mapNotNull { n ->
            val text = listOf(n.title, n.highlightedText, n.userNotes, n.tags).filter { it.isNotBlank() }.joinToString(" — ")
            val needed = q.phrases.size + q.words.size
            val nt = norm(text)
            val found = q.ranges(nt)
            // Every word or phrase must be there (as in the book search).
            val all = (q.phrases.map { it.joinToString(" ") } + q.words).all { t -> found.any { r -> nt.substring(r.first, r.last + 1).startsWith(t) } }
            if (!all || needed == 0) return@mapNotNull null
            val (s, a, b) = snippet(text, q).firstOrNull() ?: return@mapNotNull null
            val title = titles.getOrPut(n.documentId) { dao.documentDao().getDocumentById(n.documentId)?.title.orEmpty() }
            NoteHit(n, title, s, a, b)
        }.sortedByDescending { it.note.id }.take(limit)
    }

    /** Searches, indexing [docId] first if it has not been indexed yet. */
    suspend fun searchBook(ctx: Context, d: DocumentEntity, raw: String, whole: Boolean): List<Hit> = withContext(Dispatchers.IO) {
        if (indexable(d) && !isCurrent(ctx, d)) indexDoc(ctx, d)
        search(ctx, raw, whole, d.id).firstOrNull()?.second?.second.orEmpty()
    }
}
