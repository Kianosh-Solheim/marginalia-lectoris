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

    /**
     * What the indexer is doing: [books] read for search out of [total], the book now ([current]) with
     * its [page] of [pages], [phase] "text" (reading the text layer) or "ocr" (reading scanned pages),
     * and how many scanned pages wait for OCR in all ([ocrLeft]).
     */
    data class Progress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val current: String = "",
                        val currentId: Long = 0, val phase: String = "", val page: Int = 0, val pages: Int = 0, val ocrLeft: Int = 0)

    /** One book's state in the index. [state]: "done", "reading" (now), "ocr" (text done, scanned pages left), "queued", "none" (cannot be searched). */
    data class Status(val state: String, val pagesDone: Int = 0, val pagesTotal: Int = 0, val ocrLeft: Int = 0, val ocrDone: Int = 0) {
        val searchable get() = state == "done" || state == "ocr" || pagesDone > 0
    }
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /** One place in a book where the search matched. [loc] is the PDF page or EPUB position; [start]/[end] mark the match in [snippet]. */
    data class Hit(val docId: Long, val loc: Int, val href: String, val label: String, val snippet: String, val start: Int, val end: Int, val occurrence: Int)
    data class BookResult(val doc: DocumentEntity, val count: Int, val hits: List<Hit>)
    data class NoteHit(val note: HighlightEntity, val docTitle: String, val snippet: String, val start: Int, val end: Int)

    // ------------------------------------------------------------------ storage

    private class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "search_index.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE units (id INTEGER PRIMARY KEY, doc_id INTEGER NOT NULL, loc INTEGER, href TEXT, label TEXT, text TEXT)")
            db.execSQL("CREATE INDEX units_doc ON units(doc_id)")
            db.execSQL("CREATE VIRTUAL TABLE fts USING fts4(body, tokenize=simple)")
            // phase: text (being read) · ocr (text done, scanned pages left) · done
            db.execSQL("CREATE TABLE docs (doc_id INTEGER PRIMARY KEY, sig TEXT, phase TEXT, pages INTEGER, pages_done INTEGER, ocr_done INTEGER, updated INTEGER)")
            db.execSQL("CREATE TABLE ocr_todo (doc_id INTEGER, page INTEGER, PRIMARY KEY(doc_id, page))")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            listOf("units", "fts", "docs", "ocr_todo").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
            onCreate(db)
        }
    }

    @Volatile private var db: Db? = null
    private fun db(ctx: Context): SQLiteDatabase = (db ?: synchronized(this) { db ?: Db(ctx).also { db = it } }).writableDatabase

    // ------------------------------------------------------------------ forgiving text

    private val combining = Regex("\\p{Mn}+")
    private val foldCache = java.util.concurrent.ConcurrentHashMap<Char, String>()
    private val asciiLower = Array(128) { it.toChar().lowercaseChar().toString() }
    private fun foldChar(c: Char): String = if (c.code < 128) asciiLower[c.code] else foldCache.getOrPut(c) { foldSlow(c) }
    private fun foldSlow(c: Char): String = when (c) {
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
        return "$VERSION|${f.length()}|${f.lastModified()}"
    }

    fun indexable(d: DocumentEntity): Boolean =
        !FolderBooks.isFolder(d) && !ZoteroPlaceholders.isPlaceholder(d) && File(d.filePath).isFile &&
            d.fileType.uppercase(Locale.ROOT) in setOf("PDF", "EPUB", "HTML", "HTM", "XHTML", "TXT", "MD")

    private fun isPdf(d: DocumentEntity) = d.fileType.equals("PDF", true)

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
                val text = read(normalizePath(base + rel))?.let { htmlText(it) }.orEmpty()
                if (text.isBlank()) continue
                val res = index?.let { com.example.server.EpubIndex.resourceFor(it, rel) }
                var offset = 0
                chunk(text, 2400).forEach { part ->
                    val loc = if (res != null) res.first + ((offset.toDouble() / text.length) * res.count).toInt().coerceIn(0, (res.count - 1).coerceAtLeast(0)) else 1
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

    // ------------------------------------------------------------------ the index's bookkeeping

    private class Row(val sig: String, val phase: String, val pages: Int, val pagesDone: Int, val ocrDone: Int)

    private fun row(ctx: Context, id: Long): Row? = db(ctx).rawQuery("SELECT sig, phase, pages, pages_done, ocr_done FROM docs WHERE doc_id=?", arrayOf(id.toString())).use { c ->
        if (c.moveToFirst()) Row(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getInt(2), c.getInt(3), c.getInt(4)) else null
    }

    private fun ocrLeft(ctx: Context, id: Long? = null): Int = db(ctx).rawQuery(
        if (id == null) "SELECT COUNT(*) FROM ocr_todo" else "SELECT COUNT(*) FROM ocr_todo WHERE doc_id=?", if (id == null) null else arrayOf(id.toString())
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun setRow(w: SQLiteDatabase, id: Long, sig: String, phase: String, pages: Int, pagesDone: Int, ocrDone: Int) {
        w.insertWithOnConflict("docs", null, ContentValues().apply {
            put("doc_id", id); put("sig", sig); put("phase", phase); put("pages", pages); put("pages_done", pagesDone); put("ocr_done", ocrDone); put("updated", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun insertChunk(w: SQLiteDatabase, id: Long, u: Chunk) {
        val rowId = w.insert("units", null, ContentValues().apply { put("doc_id", id); put("loc", u.loc); put("href", u.href); put("label", u.label); put("text", u.text) })
        w.execSQL("INSERT INTO fts(rowid, body) VALUES(?, ?)", arrayOf<Any>(rowId, norm(u.text)))
    }

    private fun removeLocked(w: SQLiteDatabase, docId: Long) {
        w.execSQL("DELETE FROM fts WHERE rowid IN (SELECT id FROM units WHERE doc_id=?)", arrayOf<Any>(docId))
        w.execSQL("DELETE FROM units WHERE doc_id=?", arrayOf<Any>(docId))
        w.execSQL("DELETE FROM docs WHERE doc_id=?", arrayOf<Any>(docId))
        w.execSQL("DELETE FROM ocr_todo WHERE doc_id=?", arrayOf<Any>(docId))
    }

    /** One book's state in the index. */
    fun status(ctx: Context, d: DocumentEntity): Status {
        if (!indexable(d)) return Status("none")
        val r = row(ctx, d.id)
        val p = _progress.value
        val now = p.running && p.currentId == d.id
        if (r == null || r.sig != signature(d)) return if (now) Status("reading", p.page, p.pages) else Status("queued")
        return when (r.phase) {
            "done" -> Status("done", r.pages, r.pages, 0, r.ocrDone)
            "ocr" -> Status(if (now) "reading" else "ocr", r.pages, r.pages, ocrLeft(ctx, d.id), r.ocrDone)
            else -> Status(if (now) "reading" else "queued", r.pagesDone, r.pages)
        }
    }

    /** States of all books (for the library's list), by document id. */
    fun statuses(ctx: Context, docs: List<DocumentEntity>): Map<Long, Status> = docs.associate { it.id to status(ctx, it) }

    // ------------------------------------------------------------------ indexing

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "search-index").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    @Volatile private var job: Job? = null
    /** A book someone is searching in now: read it next (the book being read pauses and continues later). */
    @Volatile private var priority: Long = 0
    private val mutex = Mutex()

    /** Reads the text of a PDF page by page with PDFBox (fast), continuing where it stopped; scanned pages are noted for OCR. */
    private suspend fun pdfTextPhase(ctx: Context, d: DocumentEntity, from: Int): Boolean {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx.applicationContext)
        val sig = signature(d)
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(File(d.filePath), com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(16L * 1024 * 1024)).use { pdf ->
            val n = pdf.numberOfPages
            val labels = runCatching { pdf.documentCatalog.pageLabels?.labelsByPageIndices }.getOrNull()
            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper().apply { sortByPosition = true }
            val w = db(ctx)
            var page = from + 1
            while (page <= n) {
                if (priority != 0L && priority != d.id) return false   // someone is searching another book: pause this one
                val batch = ArrayList<Pair<Int, String>>()
                val end = (page + 9).coerceAtMost(n)
                for (p in page..end) {
                    yield()
                    _progress.value = _progress.value.copy(currentId = d.id, current = d.title, phase = "text", page = p, pages = n)
                    stripper.startPage = p; stripper.endPage = p
                    batch += p to runCatching { stripper.getText(pdf) }.getOrDefault("")
                }
                w.beginTransaction()
                try {
                    batch.forEach { (p, text) ->
                        if (text.count { it.isLetterOrDigit() } < 20) w.execSQL("INSERT OR IGNORE INTO ocr_todo(doc_id, page) VALUES(?, ?)", arrayOf<Any>(d.id, p))
                        else insertChunk(w, d.id, Chunk(p, "", labels?.getOrNull(p - 1)?.takeIf { it != p.toString() }.orEmpty(), text))
                    }
                    setRow(w, d.id, sig, if (end >= n) "ocr" else "text", n, end, 0)
                    w.setTransactionSuccessful()
                } finally { w.endTransaction() }
                page = end + 1
            }
            if (ocrLeft(ctx, d.id) == 0) setRow(db(ctx), d.id, sig, "done", n, n, 0)
        }
        return true
    }

    /** Reads scanned pages of a PDF with OCR, a few at a time. Returns false when paused for another book. */
    private suspend fun pdfOcrPhase(ctx: Context, d: DocumentEntity): Boolean {
        val pages = db(ctx).rawQuery("SELECT page FROM ocr_todo WHERE doc_id=? ORDER BY page", arrayOf(d.id.toString())).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }
        if (pages.isEmpty()) { row(ctx, d.id)?.let { r -> setRow(db(ctx), d.id, r.sig, "done", r.pages, r.pages, r.ocrDone) }; return true }
        val r = PdfDocumentRenderer(File(d.filePath), ctx)
        try {
            var doneHere = row(ctx, d.id)?.ocrDone ?: 0
            pages.forEachIndexed { i, p ->
                if (priority != 0L && priority != d.id) return false
                yield()
                _progress.value = _progress.value.copy(currentId = d.id, current = d.title, phase = "ocr", page = i + 1, pages = pages.size)
                val text = runCatching {
                    val bmp = r.renderPage(p - 1, targetWidth = 1400)
                    PdfPageDiagnosticsAndOcrEngine.performOcrOnPageBitmap(p - 1, bmp).fullText.also { bmp?.recycle() }
                }.getOrNull().orEmpty()
                val w = db(ctx)
                w.beginTransaction()
                try {
                    if (text.count { it.isLetterOrDigit() } >= 10) insertChunk(w, d.id, Chunk(p, "", "", text))
                    w.execSQL("DELETE FROM ocr_todo WHERE doc_id=? AND page=?", arrayOf<Any>(d.id, p))
                    doneHere++
                    val rr = row(ctx, d.id)
                    if (rr != null) setRow(w, d.id, rr.sig, if (i == pages.size - 1) "done" else "ocr", rr.pages, rr.pages, doneHere)
                    w.setTransactionSuccessful()
                } finally { w.endTransaction() }
            }
        } finally { runCatching { r.close() } }
        return true
    }

    /** Reads one book (or continues it). Returns false when it paused for another book. */
    private suspend fun work(ctx: Context, d: DocumentEntity): Boolean = mutex.withLock {
        val r = row(ctx, d.id)
        val sig = signature(d)
        if (r != null && r.sig != sig) { val w = db(ctx); w.beginTransaction(); try { removeLocked(w, d.id); w.setTransactionSuccessful() } finally { w.endTransaction() } }
        val cur = row(ctx, d.id)
        if (isPdf(d)) {
            if (cur == null || cur.phase == "text") return@withLock pdfTextPhase(ctx, d, cur?.pagesDone ?: 0)
            return@withLock true
        }
        if (cur?.phase == "done") return@withLock true
        _progress.value = _progress.value.copy(currentId = d.id, current = d.title, phase = "text", page = 0, pages = 0)
        val units = runCatching { if (d.fileType.equals("EPUB", true)) epubUnits(ctx, d) else plainUnits(d) }
            .onFailure { Log.w(TAG, "Could not read ${d.title}", it) }.getOrNull() ?: emptyList()
        val w = db(ctx)
        w.beginTransaction()
        try {
            removeLocked(w, d.id)
            units.forEach { insertChunk(w, d.id, it) }
            setRow(w, d.id, sig, "done", units.size, units.size, 0)
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
        true
    }

    /**
     * Starts (or continues) indexing in the background. All books' text is read first (fast); scanned
     * pages are read with OCR after that. [first] is read before anything else (a book being searched).
     */
    fun kick(ctx: Context, first: Long = 0) {
        val app = ctx.applicationContext
        if (first != 0L) priority = first
        if (job?.isActive == true) return
        job = scope.launch {
            if (first == 0L) delay(3000)   // let the app finish starting
            runCatching {
                val dao = AppDatabase.getInstance(app).documentDao()
                // Forget books that were deleted.
                val all = dao.getAllDocumentsSnapshot()
                val ids = all.map { it.id }.toSet()
                val known = db(app).rawQuery("SELECT doc_id FROM docs", null).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
                mutex.withLock { val w = db(app); known.filter { it !in ids }.forEach { removeLocked(w, it) } }
                while (isActive) {
                    val docs = dao.getAllDocumentsSnapshot().filter { indexable(it) }
                    val needText = docs.filter { d -> row(app, d.id).let { it == null || it.sig != signature(d) || it.phase == "text" } }
                        .sortedByDescending { maxOf(it.lastReadTimestamp, it.dateAdded) }
                    val needOcr = docs.filter { d -> row(app, d.id).let { it != null && it.sig == signature(d) && it.phase == "ocr" } }
                        .sortedByDescending { maxOf(it.lastReadTimestamp, it.dateAdded) }
                    val pr = priority
                    val next = needText.firstOrNull { it.id == pr } ?: needOcr.firstOrNull { it.id == pr } ?: needText.firstOrNull() ?: needOcr.firstOrNull()
                    if (pr != 0L && next?.id != pr) priority = 0
                    if (next == null) break
                    val doneBooks = docs.size - needText.size - needOcr.size
                    _progress.value = _progress.value.copy(running = true, done = doneBooks, total = docs.size, ocrLeft = ocrLeft(app), currentId = next.id, current = next.title)
                    val finished = if (next in needText || !isPdf(next)) work(app, next) else mutex.withLock { pdfOcrPhase(app, next) }
                    if (finished && priority == next.id && row(app, next.id)?.phase == "done") priority = 0
                }
            }.onFailure { Log.w(TAG, "Indexing stopped", it) }
            val p = _progress.value
            _progress.value = Progress(false, p.total, p.total)
        }
    }

    /** Someone is searching this book: read it next. */
    fun prioritize(ctx: Context, d: DocumentEntity) { if (indexable(d) && status(ctx, d).state != "done") kick(ctx, d.id) }

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

    /** Searches one book in what is indexed so far; if it is not fully read yet, it is read next. */
    suspend fun searchBook(ctx: Context, d: DocumentEntity, raw: String, whole: Boolean): List<Hit> = withContext(Dispatchers.IO) {
        prioritize(ctx, d)
        search(ctx, raw, whole, d.id).firstOrNull()?.second?.second.orEmpty()
    }
}
