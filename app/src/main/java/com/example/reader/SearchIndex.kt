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

    /** Every word in the index, once: for finding misspellings quickly. */
    private const val VOCAB = "CREATE TABLE IF NOT EXISTS vocab (term TEXT PRIMARY KEY) WITHOUT ROWID"
    private const val META = "CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)"
    /** Meaning vectors for AI search (see AiSearch): per page part, from Gemini or the offline model. */
    private const val EMB = "CREATE TABLE IF NOT EXISTS emb (src TEXT, unit_id INTEGER, part INTEGER, a INTEGER, b INTEGER, vec BLOB, PRIMARY KEY(src, unit_id, part))"

    private class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "search_index.db", null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE units (id INTEGER PRIMARY KEY, doc_id INTEGER NOT NULL, loc INTEGER, href TEXT, label TEXT, text TEXT)")
            db.execSQL("CREATE INDEX units_doc ON units(doc_id)")
            db.execSQL("CREATE VIRTUAL TABLE fts USING fts4(body, tokenize=simple)")
            db.execSQL(VOCAB)
            // phase: text (being read) · ocr (text done, scanned pages left) · done
            db.execSQL("CREATE TABLE docs (doc_id INTEGER PRIMARY KEY, sig TEXT, phase TEXT, pages INTEGER, pages_done INTEGER, ocr_done INTEGER, updated INTEGER)")
            db.execSQL("CREATE TABLE ocr_todo (doc_id INTEGER, page INTEGER, PRIMARY KEY(doc_id, page))")
            db.execSQL(META)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion == 2) {
                // Better word splitting: the word index is made again from the text already read (no new OCR).
                listOf("fts_terms", "vocab", "fts").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
                db.execSQL("CREATE VIRTUAL TABLE fts USING fts4(body, tokenize=simple)")
                db.execSQL(VOCAB)
                db.execSQL(META)
                db.execSQL("INSERT OR REPLACE INTO meta(k, v) VALUES('rebuild', '0')")
                return
            }
            listOf("fts_terms", "vocab", "units", "fts", "docs", "ocr_todo", "meta", "emb").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
            onCreate(db)
        }
        // Searching can read while the background reader writes.
        override fun onConfigure(db: SQLiteDatabase) { runCatching { db.enableWriteAheadLogging() } }
        override fun onOpen(db: SQLiteDatabase) {
            runCatching { db.execSQL(VOCAB) }
            runCatching { db.execSQL(META) }
            runCatching { db.execSQL(EMB); db.execSQL("CREATE INDEX IF NOT EXISTS emb_src ON emb(src, unit_id)") }
            runCatching { db.execSQL("DROP TABLE IF EXISTS fts_terms") }
        }
    }

    @Volatile private var db: Db? = null
    internal fun db(ctx: Context): SQLiteDatabase = (db ?: synchronized(this) { db ?: Db(ctx).also { db = it } }).writableDatabase

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
            if (c == '\u00AD') { i++; continue }   // soft hyphen: never part of the word
            if ((c == '-' || c == '\u2010' || c == '\u2011') && i > 0 && s[i - 1].isLetter()) {
                var j = i + 1
                while (j < s.length && (s[j] == ' ' || s[j] == '\t' || s[j] == '\r')) j++
                if (j < s.length && s[j] == '\n') {
                    j++
                    while (j < s.length && s[j].isWhitespace()) j++
                    if (j < s.length && s[j].isLowerCase()) { i = j; continue }
                }
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

    /** What goes into the word index: only the words, separated by single spaces (quotes, dashes and the like are not part of any word). */
    fun ftsBody(normText: String): String = TOK.findAll(normText).joinToString(" ") { it.value }

    /**
     * A PDF page whose text layer looks broken: garbled symbols, or words run together without spaces.
     * Such pages are read again with OCR.
     */
    fun weakText(t: String): Boolean {
        val letters = t.count { it.isLetter() }
        if (letters < 20) return false   // (nearly empty pages are handled as scanned pages)
        val nonSpace = t.count { !it.isWhitespace() }
        if (t.count { it == '\uFFFD' } > 5) return true
        if (letters.toDouble() / nonSpace < 0.4) return true
        val words = TOK.findAll(t).count()
        return words > 0 && letters.toDouble() / words > 14.0
    }

    private val TOK = Regex("[\\p{L}\\p{N}]+")
    /** The words of [s] in their forgiving form (letters and digits; everything else separates). */
    fun tokens(s: String): List<String> = TOK.findAll(norm(s)).map { it.value }.toList()

    /**
     * A search. Fuzzy (default): each word, or "quoted phrase", must be on the page close to the others
     * (in any order); words of four letters or more also match small misspellings. Exact: the whole
     * search is one sentence: the same words in the same order (case, accents and punctuation ignored).
     * Words match their longer forms unless [whole]; words of one or two letters only as whole words.
     */
    /** One way a part of the search can match: words in order (one word, or a phrase); [stem]: any word starting with it (word forms). */
    class Alt(val words: List<String>, val stem: String? = null)
    /** One part of the search: it matches if any of its alternatives does (OR, synonyms). */
    class Seg(val alts: List<Alt>) {
        val size get() = alts.maxOf { it.words.size }
        val key get() = alts.first().words.joinToString(" ")
    }

    // --- Word forms (light stemming for Norwegian and English): the ending is cut, and words that start with what is left match.
    private val endings = listOf(
        // English
        "izations", "isations", "ization", "isation", "ations", "ation", "ingly", "edly", "ings", "ing", "ments", "ment", "ness",
        "ities", "ity", "ical", "ics", "tic", "ic", "cy", "ists", "isms", "ist", "ism", "ives", "ive", "ally", "ies", "ied", "ers", "er", "ed", "es", "ly", "s", "y",
        // Norwegian
        "hetene", "heten", "heter", "het", "ingene", "ingen", "inger", "inga", "elige", "elig", "lige", "lig", "ende", "ene", "ane", "en", "et", "ar", "a", "e")
    /** English -ence/-ent/-ance/-ant keep the "en"/"an": violence, violent, violently → violen. */
    private val keepEn = listOf("ences" to 3, "ence" to 2, "ently" to 3, "ents" to 2, "ent" to 1, "ances" to 3, "ance" to 2, "antly" to 3, "ants" to 2, "ant" to 1)
    private val shortOk = setOf("en", "et", "er", "ar")
    fun stem(w: String): String? {
        if (w.length < 5 || w.any { it.isDigit() }) return null
        for ((e, cut) in keepEn) if (w.endsWith(e) && w.length - cut >= 5) return w.dropLast(cut)
        // Short endings need a longer word left (states → state*, not stat*); Norwegian -en/-et/-er/-ar keep four letters (volden → vold*).
        for (e in endings) if (w.endsWith(e) && w.length - e.length >= (if (e.length <= 2 && e !in shortOk) 5 else 4)) return w.dropLast(e.length)
        return null
    }

    /**
     * A search. Fuzzy (default): each word, or "quoted phrase", must be on the page close to the others
     * (in any order); words also match their other forms (vold → volden, voldelig). Exact: the whole
     * search is one sentence: the same words in the same order (case, accents and punctuation ignored).
     * Both: -word leaves out pages with that word; word OR word matches either; author:, title:, tag:,
     * year: and type: narrow the books (see [Filters]). Synonym groups (fuzzy) match each other.
     */
    class Query(raw: String, val whole: Boolean, val exact: Boolean = false, synonyms: List<List<String>> = emptyList()) {
        val segs: List<Seg>
        /** Words or phrases that must not be on the page. */
        val exclude: List<List<String>>
        /** The book filters typed in the search (author:, title:, tag:, year:, type:). */
        val filters: Filters
        init {
            var rest = raw
            // Filters typed in the search.
            val f = Filters()
            rest = Regex("(?i)\\b(author|title|tag|year|type|forfatter|tittel|år|type):(\"[^\"]*\"|\\S+)").replace(rest) { m ->
                val v = m.groupValues[2].trim('"')
                when (m.groupValues[1].lowercase()) {
                    "author", "forfatter" -> f.author = v
                    "title", "tittel" -> f.title = v
                    "tag" -> f.tag = v.removePrefix("#")
                    "year", "år" -> Regex("(\\d{4})?\\s*(-|–|\\.\\.)?\\s*(\\d{4})?").matchEntire(v)?.let { y ->
                        val a = y.groupValues[1].toIntOrNull(); val b = y.groupValues[3].toIntOrNull()
                        if (y.groupValues[2].isEmpty()) { f.yearFrom = a; f.yearTo = a } else { f.yearFrom = a; f.yearTo = b }
                    }
                    "type" -> f.type = if (v.lowercase().startsWith("a")) "ARTICLE" else if (v.lowercase().startsWith("b")) "BOOK" else ""
                }
                " "
            }
            filters = f
            // Items: "phrase", -word, -"phrase", OR, word.
            val items = ArrayList<Pair<String, List<String>>>()   // kind (+, -, OR) and words
            Regex("(-?)\"([^\"]*)\"|(\\S+)").findAll(rest).forEach { m ->
                if (m.groupValues[2].isNotEmpty() || m.groupValues[0].startsWith("\"") || m.groupValues[0].startsWith("-\"")) {
                    val t = tokens(m.groupValues[2]); if (t.isNotEmpty()) items += (if (m.groupValues[1] == "-") "-" else "+") to t
                } else {
                    val w = m.groupValues[3]
                    when {
                        w == "OR" || w == "ELLER" || w == "|" -> items += "OR" to emptyList()
                        w.startsWith("-") && w.length > 1 -> tokens(w.drop(1)).takeIf { it.isNotEmpty() }?.let { items += "-" to it }
                        else -> tokens(w).forEach { items += "+" to listOf(it) }
                    }
                }
            }
            exclude = items.filter { it.first == "-" }.map { it.second }
            val pos = items.filter { it.first != "-" }
            if (exact) {
                val words = pos.filter { it.first == "+" }.flatMap { it.second }
                segs = if (words.isEmpty()) emptyList() else listOf(Seg(listOf(Alt(words))))
            } else {
                // OR joins the parts on each side.
                val raws = ArrayList<MutableList<List<String>>>()
                var joinNext = false
                for ((k, w) in pos) {
                    if (k == "OR") { joinNext = raws.isNotEmpty(); continue }
                    if (joinNext) raws.last() += w else raws += mutableListOf(w)
                    joinNext = false
                }
                // Synonym groups: a part (or a run of single words) that is one of the group's terms matches all of them.
                val groups = synonyms.map { g -> g.map { tokens(it) }.filter { it.isNotEmpty() } }.filter { it.size > 1 }
                val merged = ArrayList<MutableList<List<String>>>()
                var i = 0
                while (i < raws.size) {
                    var took = false
                    if (raws[i].size == 1) for (g in groups) {
                        val hit = g.firstOrNull { term ->
                            if (raws[i][0].size > 1) raws[i][0] == term
                            else term.size <= raws.size - i && term.indices.all { j -> raws[i + j].size == 1 && raws[i + j][0].size == 1 && raws[i + j][0][0] == term[j] }
                        } ?: continue
                        merged += g.toMutableList()
                        i += if (raws[i][0].size > 1) 1 else hit.size
                        took = true; break
                    }
                    if (!took) { merged += raws[i]; i++ }
                }
                segs = merged.map { alts -> Seg(alts.distinct().map { w -> Alt(w, if (w.size == 1 && !whole) stem(w[0]) else null) }) }
            }
        }
        /** Misspellings found in the books, per word (fuzzy only). */
        var variants: Map<String, List<String>> = emptyMap()
        /** The books searched (null: all), and the filters in use (typed and chosen). */
        var allowed: Set<Long>? = null
        var applied: Filters = Filters()
        val isEmpty get() = segs.isEmpty()
        val totalWords get() = segs.sumOf { it.size }
        fun prefix(w: String) = !whole && w.length > 2
        fun typoOk(seg: Seg) = !exact && seg.alts.size == 1 && seg.alts[0].words.size == 1 && seg.alts[0].words[0].length >= 4
        val typoWords get() = segs.filter { typoOk(it) }.map { it.alts[0].words[0] }

        private fun altFts(a: Alt): String =
            if (a.words.size > 1) "\"" + a.words.dropLast(1).joinToString(" ") + " " + a.words.last() + (if (prefix(a.words.last())) "*" else "") + "\""
            else a.stem?.let { "$it*" } ?: if (prefix(a.words[0])) a.words[0] + "*" else a.words[0]

        /** The full-text query for one part: its alternatives (and misspellings) joined with OR. */
        fun ftsOf(seg: Seg): String {
            val l = seg.alts.map { altFts(it) }.toMutableList()
            if (seg.alts.size == 1 && seg.alts[0].words.size == 1) variants[seg.alts[0].words[0]]?.forEach { v -> l += if (prefix(seg.alts[0].words[0])) "$v*" else v }
            return l.distinct().joinToString(" OR ")
        }
        /** Word-index queries for leaving pages out. */
        fun excludeFts(): List<String> = exclude.map { w -> if (w.size > 1) "\"" + w.joinToString(" ") + "\"" else if (prefix(w[0])) w[0] + "*" else w[0] }

        /**
         * The whole search as word-index queries (one per combination of alternatives): a phrase, or the
         * parts near each other. Null when there are too many combinations (then the slower check is used).
         */
        fun ftsMatches(): List<String>? {
            var combos = listOf(listOf<String>())
            for (seg in segs) {
                val alts = seg.alts.map { altFts(it) }
                combos = combos.flatMap { c -> alts.map { c + it } }
                if (combos.size > 24) return null
            }
            return combos.map { c -> if (c.size == 1) c[0] else c.joinToString(" NEAR/${totalWords + 10} ") }
        }
        /** (For the log.) */
        fun ftsMatch(): String = ftsMatches()?.joinToString(" | ") ?: segs.joinToString(" & ") { ftsOf(it) }

        /** Where the longest part matches, alone (for placing a passage). */
        fun looseRanges(normText: String): List<IntRange> {
            val seg = segs.maxByOrNull { it.key.length } ?: return emptyList()
            val toks = TOK.findAll(normText).toList()
            return occurrences(seg, toks.map { it.value }).map { toks[it[0]].range.first..toks[it[1]].range.last }
        }

        private fun wordOk(a: Alt, j: Int, tok: String, single: Boolean): Boolean {
            val t = a.words[j]
            val last = j == a.words.size - 1
            if (tok == t || (last && prefix(t) && tok.startsWith(t))) return true
            if (a.words.size == 1 && a.stem != null && tok.startsWith(a.stem)) return true
            if (single && a.words.size == 1) variants[t]?.forEach { v -> if (tok == v || (prefix(t) && tok.startsWith(v))) return true }
            return false
        }

        /** The places of one part in the words of a page, as (first word, last word). */
        private fun occurrences(seg: Seg, words: List<String>): List<IntArray> {
            val l = ArrayList<IntArray>()
            val single = seg.alts.size == 1
            for (i in words.indices) {
                for (a in seg.alts) {
                    if (i + a.words.size > words.size) continue
                    var ok = true
                    for (j in a.words.indices) if (!wordOk(a, j, words[i + j], single)) { ok = false; break }
                    if (ok) { l += intArrayOf(i, i + a.words.size - 1); break }
                }
            }
            return l
        }

        /** True when the page has a word or phrase that is left out (-word). */
        fun excluded(words: List<String>): Boolean = exclude.any { w ->
            (0..words.size - w.size).any { i -> w.indices.all { j -> words[i + j] == w[j] || (j == w.size - 1 && prefix(w[j]) && words[i + j].startsWith(w[j])) } }
        }

        /** Where the search matches in [normText] (the forgiving form): one range per match. */
        fun ranges(normText: String): List<IntRange> {
            if (segs.isEmpty()) return emptyList()
            val toks = TOK.findAll(normText).toList()
            if (toks.isEmpty()) return emptyList()
            val words = toks.map { it.value }
            if (exclude.isNotEmpty() && excluded(words)) return emptyList()
            val occ = segs.map { occurrences(it, words) }
            if (occ.any { it.isEmpty() }) return emptyList()
            val spans = ArrayList<IntArray>()
            if (segs.size == 1) spans += occ[0]
            else {
                // Close to each other: all parts within a few words of the rarest one.
                val window = totalWords + 10
                val ai = occ.indices.minByOrNull { occ[it].size }!!
                for (a in occ[ai]) {
                    var lo = a[0]; var hi = a[1]; var ok = true
                    for (k in occ.indices) {
                        if (k == ai) continue
                        val best = occ[k].minByOrNull { kotlin.math.abs(it[0] - a[0]) }!!
                        if (kotlin.math.abs(best[0] - a[0]) > window) { ok = false; break }
                        lo = minOf(lo, best[0]); hi = maxOf(hi, best[1])
                    }
                    if (ok) spans += intArrayOf(lo, hi)
                }
            }
            spans.sortBy { it[0] }
            val out = ArrayList<IntRange>()
            var lastEnd = -1
            for (sp in spans) {
                if (sp[0] <= lastEnd) continue
                lastEnd = sp[1]
                out += toks[sp[0]].range.first..toks[sp[1]].range.last
                if (out.size >= 500) break
            }
            return out
        }

        /** The search as the web page needs it to mark matches the same way. */
        fun toJson(): org.json.JSONObject = org.json.JSONObject()
            .put("segs", org.json.JSONArray().apply { segs.forEach { sg -> put(org.json.JSONArray().apply { sg.alts.forEach { a -> put(org.json.JSONObject().put("w", org.json.JSONArray(a.words)).put("stem", a.stem ?: "")) } }) } })
            .put("exclude", org.json.JSONArray().apply { exclude.forEach { put(org.json.JSONArray(it)) } })
            .put("variants", org.json.JSONObject().apply { variants.forEach { (k, v) -> put(k, org.json.JSONArray(v)) } })
            .put("whole", whole).put("exact", exact)
            .put("filters", applied.toJson())
            .put("synonyms", org.json.JSONArray().apply { segs.filter { it.alts.size > 1 }.forEach { put(org.json.JSONArray(it.alts.map { a -> a.words.joinToString(" ") })) } })
            .put("stems", org.json.JSONObject().apply { segs.forEach { sg -> sg.alts.forEach { a -> if (a.stem != null) put(a.words[0], a.stem) } } })
    }

    /** Which books to search: from the filter buttons and from author:/title:/tag:/year:/type: in the search. */
    class Filters(var author: String = "", var title: String = "", var tag: String = "", var type: String = "",
                  var yearFrom: Int? = null, var yearTo: Int? = null, var hasNotes: Boolean = false) {
        val isEmpty get() = author.isBlank() && title.isBlank() && tag.isBlank() && type.isBlank() && yearFrom == null && yearTo == null && !hasNotes
        /** Adds what is set in [o] (the buttons) to what was typed. */
        fun merge(o: Filters): Filters = Filters(author.ifBlank { o.author }, title.ifBlank { o.title }, tag.ifBlank { o.tag }, type.ifBlank { o.type },
            yearFrom ?: o.yearFrom, yearTo ?: o.yearTo, hasNotes || o.hasNotes)
        fun toJson() = org.json.JSONObject().put("author", author).put("title", title).put("tag", tag).put("type", type)
            .put("yearFrom", yearFrom ?: org.json.JSONObject.NULL).put("yearTo", yearTo ?: org.json.JSONObject.NULL).put("hasNotes", hasNotes)
    }

    // ------------------------------------------------------------------ misspellings

    private val variantCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /** Edit distance up to [max] (with swapped neighbours as one edit); more than [max] → max + 1. */
    private fun distance(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev2 = IntArray(b.length + 1); var prev = IntArray(b.length + 1) { it }; var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i; var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v; if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Words in the books that are a small misspelling away from [w] (same first letter). */
    private fun variantsOf(w: SQLiteDatabase, word: String): List<String> = variantCache.getOrPut(word) {
        val max = if (word.length >= 9) 2 else 1
        val first = word.substring(0, 1)
        val next = (first[0] + 1).toString()
        val found = ArrayList<Pair<String, Int>>()
        runCatching {
            w.rawQuery("SELECT term FROM vocab WHERE term >= ? AND term < ? AND length(term) BETWEEN ? AND ?",
                arrayOf(first, next, (word.length - max).toString(), (word.length + max).toString())).use { c ->
                while (c.moveToNext()) {
                    val t = c.getString(0) ?: continue
                    if (t == word) continue
                    val d = distance(word, t, max)
                    if (d <= max) found += t to d
                }
            }
        }.onFailure { Log.w(TAG, "Misspellings of '$word'", it) }
        found.sortedBy { it.second }.take(12).map { it.first }
    }

    /** A search made ready: misspellings looked up in the books (fuzzy only). */
    fun prepare(ctx: Context, raw: String, whole: Boolean, exact: Boolean, typos: Boolean = false, filters: Filters? = null): Query {
        val q = Query(raw, whole, exact, if (exact) emptyList() else synonyms(ctx))
        val f = filters?.let { q.filters.merge(it) } ?: q.filters
        q.applied = f
        if (!f.isEmpty) q.allowed = runCatching { allowedDocs(ctx, f) }.onFailure { Log.w(TAG, "Filters", it) }.getOrNull()
        if (typos) addVariants(ctx, q)
        return q
    }

    /** The books that pass [f] (null: no filter). */
    fun allowedFor(ctx: Context, f: Filters?): Set<Long>? = if (f == null || f.isEmpty) null else runCatching { allowedDocs(ctx, f) }.getOrNull()

    /** The books that pass the filters. */
    private fun allowedDocs(ctx: Context, f: Filters): Set<Long> = kotlinx.coroutines.runBlocking {
        val db = AppDatabase.getInstance(ctx)
        val noted = if (f.hasNotes) db.highlightDao().getAllHighlights().first().map { it.documentId }.toSet() else emptySet()
        fun has(hay: String, needle: String) = needle.isBlank() || norm(hay).contains(norm(needle).trim())
        db.documentDao().getAllDocumentsSnapshot().filter { d ->
            has(d.author, f.author) && has(d.title, f.title) &&
                (f.tag.isBlank() || d.tags.split(',', ';').any { norm(it.trim().removePrefix("#")) == norm(f.tag).trim() }) &&
                (f.type.isBlank() || d.category.equals(f.type, true)) &&
                (!f.hasNotes || d.id in noted) &&
                (f.yearFrom == null && f.yearTo == null || run {
                    val y = runCatching { BibStore.get(d.id)?.get(BibField.YEAR) }.getOrNull()?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
                    y != null && (f.yearFrom == null || y >= f.yearFrom!!) && (f.yearTo == null || y <= f.yearTo!!)
                })
        }.map { it.id }.toSet()
    }

    // --- Synonym groups (kept on the tablet; added from the search page, edited in Settings).
    fun synonyms(ctx: Context): List<List<String>> = runCatching {
        val a = org.json.JSONArray(ctx.getSharedPreferences("search", 0).getString("synonyms", "[]"))
        (0 until a.length()).map { i -> a.getJSONArray(i).let { g -> (0 until g.length()).map { g.getString(it) } } }
    }.getOrDefault(emptyList())
    fun setSynonyms(ctx: Context, groups: List<List<String>>) {
        val clean = groups.map { g -> g.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { norm(it) } }.filter { it.size > 1 }
        ctx.getSharedPreferences("search", 0).edit().putString("synonyms", org.json.JSONArray().apply { clean.forEach { put(org.json.JSONArray(it)) } }.toString()).apply()
    }

    // --- Recent and saved searches (shared by the web page and the tablet).
    fun savedSearches(ctx: Context): org.json.JSONObject = runCatching { org.json.JSONObject(ctx.getSharedPreferences("search", 0).getString("saved", "{}")!!) }.getOrDefault(org.json.JSONObject()).apply {
        if (!has("recent")) put("recent", org.json.JSONArray()); if (!has("pinned")) put("pinned", org.json.JSONArray())
    }
    /** [action]: add (to recent), pin, unpin, remove (from recent), clear (recent). [e]: {q, mode, whole, typos, filters…}. */
    fun updateSaved(ctx: Context, action: String, e: org.json.JSONObject?): org.json.JSONObject = synchronized(this) {
        val o = savedSearches(ctx)
        fun list(k: String) = o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        fun same(a: org.json.JSONObject, b: org.json.JSONObject) = a.optString("q").trim().equals(b.optString("q").trim(), true) && a.optString("mode") == b.optString("mode") &&
            a.optJSONObject("filters")?.toString().orEmpty() == b.optJSONObject("filters")?.toString().orEmpty()
        var recent = list("recent"); var pinned = list("pinned")
        if (e != null && e.optString("q").isNotBlank() || action == "clear") when (action) {
            "add" -> recent = (listOf(e!!.put("at", System.currentTimeMillis())) + recent.filter { !same(it, e!!) }).take(20)
            "pin" -> { if (pinned.none { same(it, e!!) }) pinned = pinned + e!!.put("at", System.currentTimeMillis()) }
            "unpin" -> pinned = pinned.filter { !same(it, e!!) }
            "remove" -> recent = recent.filter { !same(it, e!!) }
            "clear" -> recent = emptyList()
        }
        o.put("recent", org.json.JSONArray(recent)).put("pinned", org.json.JSONArray(pinned))
        ctx.getSharedPreferences("search", 0).edit().putString("saved", o.toString()).apply()
        o
    }

    /** Looks up similar spellings for the search's words (fuzzy only). True when any were found. */
    fun addVariants(ctx: Context, q: Query): Boolean {
        if (q.exact || q.isEmpty) return false
        val w = runCatching { db(ctx) }.getOrNull() ?: return false
        q.variants = q.typoWords.associateWith { variantsOf(w, it) }.filterValues { it.isNotEmpty() }
        return q.variants.isNotEmpty()
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
        val n = norm(u.text)
        w.execSQL("INSERT INTO fts(rowid, body) VALUES(?, ?)", arrayOf<Any>(rowId, ftsBody(n)))
        addVocab(w, n)
        if (variantCache.isNotEmpty()) variantCache.clear()
    }

    private fun addVocab(w: SQLiteDatabase, normText: String) {
        val terms = HashSet<String>()
        TOK.findAll(normText).forEach { m -> if (m.value.length in 4..40) terms += m.value }
        if (terms.isEmpty()) return
        runCatching { w.compileStatement("INSERT OR IGNORE INTO vocab(term) VALUES(?)").use { st -> terms.forEach { t -> st.bindString(1, t); st.executeInsert(); st.clearBindings() } } }
    }

    private fun meta(w: SQLiteDatabase, k: String): String? = runCatching { w.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull()

    /**
     * After an update with better word splitting: makes the word index again from the text already read
     * (no PDF is opened again). PDF pages whose text layer looks broken are queued for OCR on the way.
     */
    private suspend fun rebuildIfNeeded(app: Context, pdfIds: Set<Long>) {
        val w = db(app)
        var last = meta(w, "rebuild")?.toLongOrNull() ?: return
        val total = w.rawQuery("SELECT COUNT(*) FROM units", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        var done = w.rawQuery("SELECT COUNT(*) FROM units WHERE id <= ?", arrayOf(last.toString())).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val weakDocs = HashSet<Long>()
        while (true) {
            val batch = w.rawQuery("SELECT id, doc_id, loc, href, text FROM units WHERE id > ? ORDER BY id LIMIT 200", arrayOf(last.toString())).use { c ->
                buildList { while (c.moveToNext()) add(listOf(c.getLong(0), c.getLong(1), c.getInt(2), c.getString(3).orEmpty(), c.getString(4).orEmpty())) }
            }
            if (batch.isEmpty()) break
            mutex.withLock {
                w.beginTransaction()
                try {
                    batch.forEach { r ->
                        val id = r[0] as Long; val doc = r[1] as Long; val text = r[4] as String
                        val n = norm(text)
                        w.execSQL("INSERT OR REPLACE INTO fts(rowid, body) VALUES(?, ?)", arrayOf<Any>(id, ftsBody(n)))
                        addVocab(w, n)
                        if (doc in pdfIds && (r[3] as String).isEmpty() && weakText(text)) {
                            w.execSQL("INSERT OR IGNORE INTO ocr_todo(doc_id, page) VALUES(?, ?)", arrayOf<Any>(doc, r[2] as Int))
                            weakDocs += doc
                        }
                    }
                    last = batch.last()[0] as Long
                    w.execSQL("INSERT OR REPLACE INTO meta(k, v) VALUES('rebuild', ?)", arrayOf<Any>(last.toString()))
                    w.setTransactionSuccessful()
                } finally { w.endTransaction() }
            }
            done += batch.size
            _progress.value = _progress.value.copy(running = true, current = "Updating the search index for better matching", currentId = 0, phase = "update", page = done, pages = total)
            yield()
        }
        mutex.withLock {
            weakDocs.forEach { d -> w.execSQL("UPDATE docs SET phase='ocr' WHERE doc_id=? AND phase='done'", arrayOf<Any>(d)) }
            w.execSQL("DELETE FROM meta WHERE k='rebuild'")
        }
        variantCache.clear()
        Log.i(TAG, "Search index rebuilt: $total pages, ${weakDocs.size} books with pages queued for OCR")
    }

    /** Books read for search before the word list existed: fill it in once, in the background. */
    private suspend fun buildVocabIfMissing(app: Context) {
        val w = db(app)
        val have = w.rawQuery("SELECT 1 FROM vocab LIMIT 1", null).use { it.moveToFirst() }
        if (have) return
        var last = 0L
        while (true) {
            val batch = w.rawQuery("SELECT id, text FROM units WHERE id > ? ORDER BY id LIMIT 200", arrayOf(last.toString())).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1).orEmpty()) }
            }
            if (batch.isEmpty()) break
            mutex.withLock {
                w.beginTransaction()
                try { batch.forEach { addVocab(w, norm(it.second)) }; w.setTransactionSuccessful() } finally { w.endTransaction() }
            }
            last = batch.last().first
            yield()
        }
        variantCache.clear()
        Log.i(TAG, "Word list for misspellings ready")
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
                        else {
                            insertChunk(w, d.id, Chunk(p, "", labels?.getOrNull(p - 1)?.takeIf { it != p.toString() }.orEmpty(), text))
                            // Searchable now as it is; read again with OCR later if the text layer looks broken.
                            if (weakText(text)) w.execSQL("INSERT OR IGNORE INTO ocr_todo(doc_id, page) VALUES(?, ?)", arrayOf<Any>(d.id, p))
                        }
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
                    if (text.count { it.isLetterOrDigit() } >= 10) {
                        // A page that had a broken text layer: the OCR text replaces it, unless OCR did no better.
                        val old = w.rawQuery("SELECT id, label, text FROM units WHERE doc_id=? AND loc=? AND href=''", arrayOf(d.id.toString(), p.toString())).use { c ->
                            if (c.moveToFirst()) Triple(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty()) else null }
                        if (old == null) insertChunk(w, d.id, Chunk(p, "", "", text))
                        else if (!weakText(text)) {
                            w.execSQL("DELETE FROM fts WHERE rowid=?", arrayOf<Any>(old.first))
                            w.execSQL("DELETE FROM units WHERE id=?", arrayOf<Any>(old.first))
                            insertChunk(w, d.id, Chunk(p, "", old.second, text))
                        }
                    }
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
                runCatching { rebuildIfNeeded(app, all.filter { isPdf(it) }.map { it.id }.toSet()) }.onFailure { Log.w(TAG, "Rebuild", it) }
                runCatching { buildVocabIfMissing(app) }.onFailure { Log.w(TAG, "Word list", it) }
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

    internal fun collapse(s: String) = s.replace(Regex("\\s+"), " ")

    /** A passage around one match [r] (in the forgiving form [n] of [text], with [map] back): (text, match start, match end). */
    private fun passage(text: String, map: IntArray, r: IntRange): Triple<String, Int, Int> {
        val s = map.getOrElse(r.first) { 0 }.coerceIn(0, text.length)
        val e = (map.getOrElse(r.last) { text.length - 1 } + 1).coerceIn(s, text.length)
        val from = (s - 70).coerceAtLeast(0).let { f -> text.lastIndexOf(' ', f).takeIf { it >= 0 && it >= f - 20 } ?: f }
        val to = (e + 90).coerceAtMost(text.length).let { t -> text.indexOf(' ', t).takeIf { it in t..(t + 20) } ?: t }
        val lead = if (from > 0) "…" else ""
        val pre = collapse(text.substring(from, s)).trimStart()
        val mid = collapse(text.substring(s, e)).let { if (it.length > 400) it.take(400) + "…" else it }
        val post = collapse(text.substring(e, to)).trimEnd()
        val start = lead.length + pre.length
        return Triple(lead + pre + mid + post + if (to < text.length) "…" else "", start, start + mid.length)
    }

    /** Short passages around each match: (text, match start, match end). */
    private fun snippet(text: String, q: Query, max: Int = Int.MAX_VALUE): List<Triple<String, Int, Int>> {
        val (n, map) = normWithMap(text)
        return q.ranges(n).take(max).map { passage(text, map, it) }
    }

    /** Matches of a prepared search in any text (for books searched in memory, such as the tablet's EPUB pages). */
    fun findIn(text: String, q: Query): List<Triple<String, Int, Int>> = if (q.isEmpty) emptyList() else snippet(text, q)

    /** Rows of the index that match one part of the search, in order (at most [limit]). */
    private fun idsFor(w: SQLiteDatabase, fts: String, docId: Long?, limit: Int): LongArray {
        val sql = if (docId != null) "SELECT u.id FROM fts JOIN units u ON u.id = fts.rowid WHERE fts MATCH ? AND u.doc_id = ? ORDER BY u.id LIMIT $limit"
            else "SELECT rowid FROM fts WHERE fts MATCH ? ORDER BY rowid LIMIT $limit"
        return w.rawQuery(sql, if (docId != null) arrayOf(fts, docId.toString()) else arrayOf(fts)).use { c ->
            val a = LongArray(c.count.coerceAtMost(limit)); var k = 0
            while (c.moveToNext() && k < a.size) a[k++] = c.getLong(0)
            if (k < a.size) a.copyOf(k) else a
        }
    }

    // One search at a time; a search that is overtaken by a newer one (typing on) stops.
    private val searchLock = Any()
    private val searchGen = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicLong>()

    /**
     * Searches the index: per book (most matching pages first) the number of pages that match (or,
     * within one book, every place) and the first passages.
     */
    fun search(ctx: Context, raw: String, whole: Boolean, docId: Long? = null, perBook: Int = 6, exact: Boolean = false, prepared: Query? = null): List<Pair<Long, Pair<Int, List<Hit>>>> {
        val gen = searchGen.getOrPut(docId ?: 0L) { java.util.concurrent.atomic.AtomicLong() }
        val my = gen.incrementAndGet()
        val stale = { gen.get() != my }
        synchronized(searchLock) {
            if (stale()) return emptyList()
            return try {
                val q = prepared ?: prepare(ctx, raw, whole, exact)
                if (q.isEmpty || q.allowed?.isEmpty() == true) return emptyList()
                if (q.variants.isNotEmpty() || q.ftsMatches() == null) return searchLocked(ctx, raw, whole, docId, perBook, exact, q, stale)
                val r = searchFast(ctx, q, docId, perBook, stale)
                // Nothing found: try similar spellings (fuzzy searches only).
                if (r.isEmpty() && !stale() && addVariants(ctx, q)) searchLocked(ctx, raw, whole, docId, perBook, exact, q, stale) else r
            }
            catch (e: Throwable) { Log.w(TAG, "Search failed for '$raw'", e); emptyList() }
        }
    }

    /**
     * The quick search, answered by the word index itself: an exact sentence as a phrase, fuzzy words as
     * "near each other". Only the pages shown are read, for their passages.
     */
    private fun searchFast(ctx: Context, q: Query, docId: Long?, perBook: Int, stale: () -> Boolean): List<Pair<Long, Pair<Int, List<Hit>>>> {
        val t0 = System.currentTimeMillis()
        val w = db(ctx)
        val matches = q.ftsMatches() ?: return emptyList()
        val counts = LinkedHashMap<Long, Int>()
        val keepIds = HashMap<Long, ArrayList<Long>>()
        val keep = if (docId != null) 3000 else perBook
        // Pages with a left-out word (-word).
        val excl = HashSet<Long>()
        q.excludeFts().forEach { e -> idsFor(w, e, docId, 300000).forEach { excl += it } }
        val allowed = q.allowed
        val seen = if (matches.size > 1) HashSet<Long>() else null
        val sql = "SELECT u.doc_id, u.id FROM fts JOIN units u ON u.id = fts.rowid WHERE fts MATCH ?" +
            (if (docId != null) " AND u.doc_id = ?" else "") + " ORDER BY u.doc_id, u.loc, u.id LIMIT 300000"
        for (match in matches) {
            if (stale()) return emptyList()
            w.rawQuery(sql, if (docId != null) arrayOf(match, docId.toString()) else arrayOf(match)).use { c ->
                while (c.moveToNext()) {
                    val doc = c.getLong(0); val id = c.getLong(1)
                    if (allowed != null && doc !in allowed) continue
                    if (id in excl) continue
                    if (seen != null && !seen.add(id)) continue
                    counts[doc] = (counts[doc] ?: 0) + 1
                    val l = keepIds.getOrPut(doc) { ArrayList() }
                    if (l.size < keep) l += id
                }
            }
        }
        if (stale()) return emptyList()
        val docs = if (docId != null) counts.keys.toList() else counts.keys.sortedByDescending { counts[it] ?: 0 }.take(200)
        // Passages for the pages shown (in the library: the books listed first).
        val withPassages = if (docId != null) docs else docs.take(100)
        val ids = withPassages.flatMap { keepIds[it].orEmpty() }
        val hits = HashMap<Long, ArrayList<Hit>>()
        val occurrences = HashMap<Long, Int>()
        val occ = HashMap<String, Int>()
        for (part in ids.chunked(200)) {
            if (stale()) return emptyList()
            w.rawQuery("SELECT doc_id, loc, href, label, text FROM units WHERE id IN (${part.joinToString(",")}) ORDER BY doc_id, loc, id", null).use { c ->
                while (c.moveToNext()) {
                    val doc = c.getLong(0); val loc = c.getInt(1); val href = c.getString(2).orEmpty(); val label = c.getString(3).orEmpty(); val text = c.getString(4).orEmpty()
                    val (n, map) = normWithMap(text)
                    // The index found the page; place the passage (if the exact place cannot be told, at the most telling word).
                    val rs = q.ranges(n).ifEmpty { q.looseRanges(n).take(1) }
                    val l = hits.getOrPut(doc) { ArrayList() }
                    val key = "$doc|$href"
                    if (rs.isEmpty()) {
                        l += Hit(doc, loc, href, label, collapse(text.take(160)).trim() + if (text.length > 160) "…" else "", 0, 0, 0)
                        occurrences[doc] = (occurrences[doc] ?: 0) + 1
                    }
                    rs.forEach { r ->
                        val o = occ.getOrDefault(key, 0); occ[key] = o + 1
                        val (sn, a, b) = passage(text, map, r)
                        if (l.size < (if (docId != null) 2000 else perBook)) l += Hit(doc, loc, href, label, sn, a, b, o)
                        occurrences[doc] = (occurrences[doc] ?: 0) + 1
                    }
                }
            }
        }
        Log.i(TAG, "Search '${q.ftsMatch()}': ${counts.values.sum()} pages in ${counts.size} books, ${System.currentTimeMillis() - t0} ms")
        return docs.map { d -> d to ((if (docId != null) occurrences[d] ?: counts[d] ?: 0 else counts[d] ?: 0) to hits[d].orEmpty()) }
    }

    private fun searchLocked(ctx: Context, raw: String, whole: Boolean, docId: Long?, perBook: Int, exact: Boolean, prepared: Query?, stale: () -> Boolean): List<Pair<Long, Pair<Int, List<Hit>>>> {
        val q = prepared ?: prepare(ctx, raw, whole, exact)
        if (q.isEmpty) return emptyList()
        val t0 = System.currentTimeMillis()
        val w = db(ctx)
        val limit = if (docId != null) 3000 else 5000
        // 1) Candidate pages, from the most telling part (and the next one, to narrow it down).
        val telling = q.segs.filter { seg -> seg.size > 1 || seg.key.length > 2 }.ifEmpty { q.segs }.sortedByDescending { it.key.length }.take(2)
        var cand = idsFor(w, q.ftsOf(telling[0]), docId, if (telling.size > 1) 50000 else limit)
        if (telling.size > 1 && cand.isNotEmpty() && !stale()) {
            val other = idsFor(w, q.ftsOf(telling[1]), docId, 50000)
            cand = cand.filter { java.util.Arrays.binarySearch(other, it) >= 0 }.toLongArray()
        }
        if (cand.size > limit) cand = cand.copyOf(limit)
        // 2) Read those pages and keep the ones where the search really matches (close together, or the sentence in order).
        val pages = HashMap<Long, Int>(); val occurrences = HashMap<Long, Int>()
        val hits = LinkedHashMap<Long, ArrayList<Hit>>()
        val occ = HashMap<String, Int>()
        val keep = if (docId != null) 2000 else perBook
        var read = 0
        for (part in cand.asList().chunked(200)) {
            if (stale()) { Log.i(TAG, "Search '$raw' overtaken"); return emptyList() }
            w.rawQuery("SELECT doc_id, loc, href, label, text FROM units WHERE id IN (${part.joinToString(",")}) ORDER BY doc_id, loc, id", null).use { c ->
                while (c.moveToNext()) {
                    read++
                    val doc = c.getLong(0); val loc = c.getInt(1); val href = c.getString(2).orEmpty(); val label = c.getString(3).orEmpty(); val text = c.getString(4).orEmpty()
                    if (q.allowed?.contains(doc) == false) continue
                    val (n, map) = normWithMap(text)
                    val rs = q.ranges(n)
                    if (rs.isEmpty()) continue
                    pages[doc] = (pages[doc] ?: 0) + 1
                    occurrences[doc] = (occurrences[doc] ?: 0) + rs.size
                    val l = hits.getOrPut(doc) { ArrayList() }
                    val key = "$doc|$href"
                    rs.forEach { r ->
                        val o = occ.getOrDefault(key, 0); occ[key] = o + 1
                        if (l.size < keep) { val (sn, a, b) = passage(text, map, r); l += Hit(doc, loc, href, label, sn, a, b, o) }
                    }
                }
            }
        }
        val docs = if (docId != null) hits.keys.toList() else hits.keys.sortedByDescending { pages[it] ?: 0 }.take(200)
        Log.i(TAG, "Search '$raw' exact=$exact: $read pages read, ${pages.values.sum()} match, ${System.currentTimeMillis() - t0} ms")
        // For the whole library the number shown is the matching pages; in a book, every place.
        return docs.map { d -> d to ((if (docId != null) occurrences[d] ?: 0 else pages[d] ?: 0) to hits[d].orEmpty()) }
    }

    /** The notes (title, quote and own words) that match, newest first. */
    suspend fun searchNotes(ctx: Context, raw: String, whole: Boolean, docId: Long? = null, limit: Int = 200, exact: Boolean = false, prepared: Query? = null): List<NoteHit> = withContext(Dispatchers.IO) {
        val q = prepared ?: prepare(ctx, raw, whole, exact)
        if (q.isEmpty) return@withContext emptyList()
        val dao = AppDatabase.getInstance(ctx)
        val notes = dao.highlightDao().getAllHighlights().first().filter { (docId == null || it.documentId == docId) && q.allowed?.contains(it.documentId) != false }
        val titles = HashMap<Long, String>()
        notes.mapNotNull { n ->
            val text = listOf(n.title, n.highlightedText, n.userNotes, n.tags).filter { it.isNotBlank() }.joinToString(" — ")
            val (s, a, b) = snippet(text, q, 1).firstOrNull() ?: return@mapNotNull null
            val title = titles.getOrPut(n.documentId) { dao.documentDao().getDocumentById(n.documentId)?.title.orEmpty() }
            NoteHit(n, title, s, a, b)
        }.sortedByDescending { it.note.id }.take(limit)
    }

    /** Searches one book in what is indexed so far; if it is not fully read yet, it is read next. */
    suspend fun searchBook(ctx: Context, d: DocumentEntity, raw: String, whole: Boolean, exact: Boolean = false, prepared: Query? = null): List<Hit> = withContext(Dispatchers.IO) {
        prioritize(ctx, d)
        search(ctx, raw, whole, d.id, exact = exact, prepared = prepared).firstOrNull()?.second?.second.orEmpty()
    }
}
