package com.example.reader

import android.content.Context
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * One chapter of an edited volume, treated almost like an article of its own.
 * [startPage]/[endPage] are PDF page numbers for PDFs and Readium positions for EPUBs (the same
 * numbers the reader uses for its table of contents), so a chapter can be opened directly.
 */
data class VolumeChapter(
    val title: String,
    val authors: String = "",          // "Name Nameson; Other Person"
    val startPage: Int = 1,
    val endPage: Int = 0,              // inclusive; 0 = until the next chapter starts
    val href: String = "",             // EPUB resource (with optional #anchor)
    val level: Int = 0,                // 0 = chapter, 1+ = nested (shown indented)
    val isPart: Boolean = false,       // "Part I" headings that only group chapters
    val isMatter: Boolean = false,     // front/back matter: contents, index, bibliography …
    val printedPages: String = "",     // printed page range for citations, e.g. "129–180"
    val doi: String = "",
    val authorSource: String = "",     // "toc", "text", "ai" or "user"
    val label: String = "",            // the book's own chapter number, e.g. "3" or "IV"
    val isSection: Boolean = false,    // a heading inside a chapter (2nd/3rd level), for navigation only
    val isManual: Boolean = false,     // added or edited by the user: kept when the contents are read again
    val tags: String = "",             // "tag-one, tag-two" (chapter's own keywords)
    val abstract: String = "",
    val furthestPage: Int = 0          // furthest page read in this chapter (reading progress)
) {
    val isArticle: Boolean get() = !isPart && !isMatter && !isSection
}

data class EditedVolume(
    val enabled: Boolean = true,
    val chapters: List<VolumeChapter> = emptyList(),
    val totalPages: Int = 0,
    val detectedAt: Long = 0L,
    val format: Int = CURRENT_FORMAT   // 2 = all heading levels; 3 = book-wide wrapper entries removed
) {
    companion object { const val CURRENT_FORMAT = 3 }
}

/** Stores edited-volume chapter lists as JSON in the app's files folder (included in backups). */
object EditedVolumes {
    private const val FILE = "edited_volumes.json"
    private val cache = HashMap<Long, EditedVolume>()
    @Volatile private var loaded = false
    @Volatile private var file: File? = null

    @Volatile var appContext: Context? = null
        private set

    fun init(context: Context) = ensure(context)

    @Synchronized private fun ensure(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        if (file == null) file = File(context.applicationContext.filesDir, FILE)
        if (loaded) return
        loaded = true
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val root = JSONObject(f.readText())
            root.keys().forEach { id ->
                val o = root.getJSONObject(id)
                val arr = o.optJSONArray("chapters") ?: JSONArray()
                val chapters = (0 until arr.length()).mapNotNull { i ->
                    val c = arr.optJSONObject(i) ?: return@mapNotNull null
                    VolumeChapter(
                        title = c.optString("title"),
                        authors = c.optString("authors"),
                        startPage = c.optInt("start", 1),
                        endPage = c.optInt("end", 0),
                        href = c.optString("href"),
                        level = c.optInt("level", 0),
                        isPart = c.optBoolean("part", false),
                        isMatter = c.optBoolean("matter", false),
                        printedPages = c.optString("printed"),
                        doi = c.optString("doi"),
                        authorSource = c.optString("src"),
                        label = c.optString("label"),
                        isSection = c.optBoolean("section", false),
                        isManual = c.optBoolean("manual", false),
                        tags = c.optString("tags"),
                        abstract = c.optString("abstract"),
                        furthestPage = c.optInt("seen", 0)
                    )
                }
                cache[id.toLong()] = EditedVolume(o.optBoolean("enabled", true), chapters, o.optInt("total", 0), o.optLong("at", 0L), o.optInt("format", 1))
            }
        }
    }

    /** Forces a reload (after a backup restore). */
    @Synchronized fun reload(context: Context) { cache.clear(); loaded = false; ensure(context) }

    @Synchronized fun get(context: Context, documentId: Long): EditedVolume? { ensure(context); return cache[documentId] }

    /** Bumped on every change so screens that show chapter details recompose. */
    val changes = androidx.compose.runtime.mutableIntStateOf(0)

    @Synchronized fun put(context: Context, documentId: Long, volume: EditedVolume) {
        ensure(context)
        cache[documentId] = volume
        save()
        changes.intValue++
    }

    @Synchronized fun remove(context: Context, documentId: Long) {
        ensure(context)
        if (cache.remove(documentId) != null) save()
    }

    private fun save() {
        val f = file ?: return
        val root = JSONObject()
        cache.forEach { (id, v) ->
            val arr = JSONArray()
            v.chapters.forEach { c ->
                arr.put(JSONObject().apply {
                    put("title", c.title); put("start", c.startPage)
                    if (c.authors.isNotBlank()) put("authors", c.authors)
                    if (c.endPage > 0) put("end", c.endPage)
                    if (c.href.isNotBlank()) put("href", c.href)
                    if (c.level > 0) put("level", c.level)
                    if (c.isPart) put("part", true)
                    if (c.isMatter) put("matter", true)
                    if (c.printedPages.isNotBlank()) put("printed", c.printedPages)
                    if (c.doi.isNotBlank()) put("doi", c.doi)
                    if (c.authorSource.isNotBlank()) put("src", c.authorSource)
                    if (c.label.isNotBlank()) put("label", c.label)
                    if (c.isSection) put("section", true)
                    if (c.isManual) put("manual", true)
                    if (c.tags.isNotBlank()) put("tags", c.tags)
                    if (c.abstract.isNotBlank()) put("abstract", c.abstract)
                    if (c.furthestPage > 0) put("seen", c.furthestPage)
                })
            }
            root.put(id.toString(), JSONObject().put("enabled", v.enabled).put("chapters", arr).put("total", v.totalPages).put("at", v.detectedAt).put("format", v.format))
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(root.toString(1))
        if (!tmp.renameTo(f)) { f.writeText(root.toString(1)); tmp.delete() }
    }

    // ------------------------------------------------------------------ edited-volume test

    private val editedMarker = Regex(
        "\\((?:eds?|red|hrsg|dir|coord)\\.?\\)|\\b(?:edited by|ed\\. by|eds?\\.|redigert av|red\\.|hrsg\\.|herausgegeben von)",
        RegexOption.IGNORE_CASE
    )

    /** Author strings such as "Freeman, Samuel (ed.)" or "edited by …" suggest an edited volume. */
    fun looksEdited(document: DocumentEntity): Boolean {
        if (document.category.equals("ARTICLE", true)) return false
        if (editedMarker.containsMatchIn(document.author)) return true
        return BibStore.get(document.id).let { it[BibField.EDITOR].isNotBlank() && it.entryType != "incollection" }
    }

    /** True when tapping the book should open its chapter list. A stored choice wins over the guess. */
    fun isEditedVolume(context: Context, document: DocumentEntity): Boolean =
        get(context, document.id)?.enabled ?: looksEdited(document)

    fun setEnabled(context: Context, document: DocumentEntity, enabled: Boolean) {
        val cur = get(context, document.id) ?: EditedVolume(enabled = enabled)
        put(context, document.id, cur.copy(enabled = enabled))
    }

    /** The book's editors: the Editor field, or the book's author with "(ed.)" and similar removed. */
    fun editors(document: DocumentEntity): String {
        BibStore.get(document.id)[BibField.EDITOR].takeIf { it.isNotBlank() }?.let { return it }
        return document.author.replace(editedMarker, " ").replace(Regex("\\s+"), " ").trim().trim(',', ';', ' ')
    }

    // ------------------------------------------------------------------ chapter ranges

    /** Last page of chapter [index]: its own end page, else the page before the next chapter at the same or a higher level. */
    fun endPageOf(chapters: List<VolumeChapter>, index: Int, totalPages: Int): Int {
        val c = chapters[index]
        if (c.endPage >= c.startPage) return c.endPage
        var end = totalPages
        for (j in index + 1 until chapters.size) {
            val n = chapters[j]
            if (n.level <= c.level && n.startPage > c.startPage) { end = n.startPage - 1; break }
        }
        return end.coerceAtLeast(c.startPage)
    }

    /**
     * Chapter end pages and titles worked out once per chapter list. Finding the chapter of every
     * note used to redo this for each note and chapter, which hung on large anthologies with many
     * headings and notes (the app stopped responding and the browser waited forever).
     */
    private class Lookup(val volume: EditedVolume, val ends: IntArray, val byTitle: Map<String, Int>)
    @Volatile private var lookup: Lookup? = null

    private fun lookupFor(volume: EditedVolume): Lookup {
        lookup?.let { if (it.volume === volume) return it }
        val list = volume.chapters
        val ends = IntArray(list.size) { i -> endPageOf(list, i, volume.totalPages.coerceAtLeast(list[i].startPage)) }
        val byTitle = HashMap<String, Int>()
        list.forEachIndexed { i, c -> if (c.isArticle) norm(c.title).takeIf { it.isNotBlank() }?.let { byTitle.putIfAbsent(it, i) } }
        return Lookup(volume, ends, byTitle).also { lookup = it }
    }

    /** The article chapter that contains [page] (or whose title matches [chapterTitle]). */
    fun chapterIndexFor(volume: EditedVolume, page: Int, chapterTitle: String = ""): Int {
        val list = volume.chapters
        if (list.isEmpty()) return -1
        val lk = lookupFor(volume)
        val t = norm(chapterTitle)
        if (t.isNotBlank()) lk.byTitle[t]?.let { return it }
        var best = -1
        for (i in list.indices) {
            val c = list[i]
            if (!c.isArticle) continue
            if (page in c.startPage..lk.ends[i] && (best < 0 || c.startPage >= list[best].startPage)) best = i
        }
        return best
    }

    /** Notes grouped by the chapter they belong to (one pass over the notes). */
    fun highlightsByChapter(volume: EditedVolume, highlights: List<HighlightEntity>): Map<Int, List<HighlightEntity>> =
        highlights.groupBy { chapterIndexFor(volume, it.pageNumber, it.chapterTitle) }
            .mapValues { (_, v) -> v.sortedWith(compareBy({ it.pageNumber }, { it.createdTimestamp })) }

    /** The chapter being read in an edited volume, with its number among the articles (for widgets). */
    data class Reading(val number: String, val chapter: VolumeChapter, val progress: Float = 0f)

    /** The chapter's number as printed in the book, else its position among the chapters. */
    fun numberOf(chapters: List<VolumeChapter>, index: Int): String {
        val c = chapters.getOrNull(index) ?: return ""
        if (!c.isArticle) return ""
        if (c.label.isNotBlank()) return c.label
        if (chapters.any { it.isArticle && it.label.isNotBlank() }) return ""
        return chapters.take(index + 1).count { it.isArticle && !it.isMatter }.toString()
    }

    fun reading(document: DocumentEntity): Reading? {
        val ctx = appContext ?: return null
        if (document.category.equals("ARTICLE", true)) return null
        if (document.readingProgress <= 0f && document.currentPage <= 1) return null
        val v = get(ctx, document.id)?.takeIf { it.enabled } ?: return null
        val i = chapterIndexFor(v, document.currentPage, document.lastPositionChapter)
        if (i < 0) return null
        return Reading(numberOf(v.chapters, i), v.chapters[i], progressOf(v, i))
    }

    fun chapterFor(context: Context, document: DocumentEntity, page: Int = document.currentPage, chapterTitle: String = document.lastPositionChapter): VolumeChapter? {
        val v = get(context, document.id)?.takeIf { it.enabled } ?: return null
        return v.chapters.getOrNull(chapterIndexFor(v, page, chapterTitle))
    }

    /** Notes taken in chapter [index]: matched by the chapter title stored with the note, else by page. */
    fun highlightsIn(volume: EditedVolume, index: Int, highlights: List<HighlightEntity>): List<HighlightEntity> =
        highlightsByChapter(volume, highlights)[index].orEmpty()

    /** EPUB only: aligns stored start positions with the chapter list the reader computed. */
    fun syncFromReader(context: Context, document: DocumentEntity, readerChapters: List<BookChapter>) {
        if (document.fileType.equals("PDF", true)) return
        val v = get(context, document.id) ?: return
        if (readerChapters.isEmpty() || v.chapters.isEmpty()) return
        var changed = false
        val updated = v.chapters.map { c ->
            val match = readerChapters.firstOrNull { c.href.isNotBlank() && it.href == c.href }
            if (match != null && match.pageNumber > 0 && match.pageNumber != c.startPage) {
                changed = true; c.copy(startPage = match.pageNumber)
            } else c
        }
        if (changed) put(context, document.id, v.copy(chapters = updated))
    }

    /**
     * "Set as → Chapter title / Chapter author" from the reader. Makes the book an edited volume if
     * needed (reading its contents first), finds the chapter at [page] and stores the value; a
     * chapter is added at [page] when none covers it. Returns a short confirmation.
     */
    suspend fun setChapterField(context: Context, document: DocumentEntity, page: Int, readerChapterTitle: String, kind: String, raw: String): String {
        var v = get(context, document.id)
        if (v == null || v.chapters.isEmpty()) {
            v = ChapterDetector.detect(context, document).getOrNull()?.volume
                ?: EditedVolume(enabled = true, totalPages = document.totalPages, format = EditedVolume.CURRENT_FORMAT)
        }
        v = v.copy(enabled = true)
        val chapters = v.chapters.toMutableList()
        var idx = chapterIndexFor(v, page, readerChapterTitle)
        // Never rename the whole book: use a section that starts here, or add a chapter at this page.
        if (idx >= 0 && (isWrapperChapter(v, idx, document.title) || (kind == "title" && chapters[idx].startPage < page - 3 && chapters.any { it.isSection && it.startPage in (page - 3)..page }))) {
            val sec = chapters.indices.filter { chapters[it].isSection && chapters[it].startPage in (page - 3)..page }
                .maxWithOrNull(compareBy({ chapters[it].startPage }, { -chapters[it].level }))
            idx = if (sec != null) {
                chapters[sec] = chapters[sec].copy(isSection = false, isManual = true); sec
            } else -1
        }
        if (idx < 0) {
            val start = page.coerceAtLeast(1)
            val seed = ChapterTitles.split(readerChapterTitle.ifBlank { "Chapter at page $start" })
            val c = VolumeChapter(title = seed.second, label = seed.first, startPage = start, level = levelForNewChapter(chapters, start), isManual = true)
            idx = chapters.indexOfFirst { it.startPage > start }.let { if (it < 0) chapters.size else it }
            chapters.add(idx, c)
        }
        val c = chapters[idx]
        val msg: String
        chapters[idx] = if (kind == "tags") {
            val add = TagSplitter.split(raw)
            val merged = (TagSplitter.split(c.tags) + add).distinctBy { it.lowercase(Locale.ROOT) }
            msg = "${add.size} tag${if (add.size == 1) "" else "s"} added to “${c.title.take(40)}”"
            c.copy(tags = merged.joinToString(", "), isManual = c.isManual)
        } else if (kind == "abstract") {
            msg = "Abstract saved for “${c.title.take(40)}”"
            c.copy(abstract = raw.replace(Regex("\\s+"), " ").trim().removePrefix("Abstract").trimStart(':', '.', ' ', '—', '–'))
        } else if (kind == "title") {
            val (num, bare) = ChapterTitles.split(raw.replace(Regex("\\s+"), " ").trim())
            msg = "Chapter title set"
            c.copy(title = bare, label = num.ifBlank { c.label }, isManual = true)
        } else {
            val names = raw.split(';').map { it.trim() }.filter { it.isNotBlank() }
                .map { ChapterAuthorGuesser.nameLine(it) ?: it.removePrefix("by ").trim() }
                .flatMap { it.split(';') }.map { it.trim() }.filter { it.isNotBlank() }
            // Typed/selected authors add up; detected ones are replaced by the first selection.
            val base = if (c.authorSource == "user") c.authors.split(';').map { it.trim() }.filter { it.isNotBlank() } else emptyList()
            val merged = (base + names).distinctBy { it.lowercase(Locale.ROOT) }
            msg = if (base.isNotEmpty()) "Author added to “${c.title.take(40)}”" else "Chapter author set for “${c.title.take(40)}”"
            c.copy(authors = merged.joinToString("; "), authorSource = "user")
        }
        put(context, document.id, v.copy(chapters = chapters))
        return msg
    }

    /** Starts a chapter at [page] with [raw] as its title (renames the one already starting there). */
    suspend fun startChapterAt(context: Context, document: DocumentEntity, page: Int, raw: String): String {
        var v = get(context, document.id)
        if (v == null || v.chapters.isEmpty()) {
            v = ChapterDetector.detect(context, document).getOrNull()?.volume
                ?: EditedVolume(enabled = true, totalPages = document.totalPages, format = EditedVolume.CURRENT_FORMAT)
        }
        val chapters = v.chapters.toMutableList()
        val start = page.coerceAtLeast(1)
        val (num, bare) = ChapterTitles.split(raw.replace(Regex("\\s+"), " ").trim())
        val at = chapters.indexOfFirst { it.startPage == start && !it.isSection }
        if (at >= 0) chapters[at] = chapters[at].copy(title = bare, label = num.ifBlank { chapters[at].label }, isManual = true)
        else {
            val c = VolumeChapter(title = bare, label = num, startPage = start, level = levelForNewChapter(chapters, start), isManual = true)
            val idx = chapters.indexOfFirst { it.startPage > start }.let { if (it < 0) chapters.size else it }
            chapters.add(idx, c)
        }
        put(context, document.id, v.copy(enabled = true, chapters = chapters))
        return if (at >= 0) "Chapter renamed" else "Chapter starts on this page"
    }

    /**
     * The author line for a book in the library and widgets: the editors, marked "(ed.)"/"(eds.)",
     * when the book has editors; otherwise the book's author.
     */
    fun displayAuthor(document: DocumentEntity): String {
        if (document.category.equals("ARTICLE", true)) return document.author
        val ctx = appContext ?: BibStore.appContext
        if (ctx != null) runCatching { BibStore.init(ctx) }
        val rec = BibStore.get(document.id)
        // A chapter (for example inside a folder book) is shown with its own author, not the book's editors.
        if (rec.entryType == "incollection") return document.author
        val ed = rec[BibField.EDITOR]
        if (ed.isBlank()) return document.author
        val names = ed.split(';').map { it.trim() }.filter { it.isNotBlank() }
        return names.joinToString(", ") + if (names.size > 1) " (eds.)" else " (ed.)"
    }

    /** A chapter that is really the whole book (one outline entry wrapping everything). */
    fun isWrapperChapter(volume: EditedVolume, index: Int, bookTitle: String): Boolean {
        val c = volume.chapters.getOrNull(index) ?: return false
        if (!c.isArticle) return false
        val end = endPageOf(volume.chapters, index, volume.totalPages.coerceAtLeast(c.startPage))
        val span = end - c.startPage + 1
        val total = volume.totalPages.coerceAtLeast(1)
        return VolumeStructure.sameTitle(c.title, bookTitle) || (total > 40 && span > total * 0.6)
    }

    /** Level for a chapter added at [page]: the level of the chapters around it. */
    fun levelForNewChapter(chapters: List<VolumeChapter>, page: Int): Int {
        val before = chapters.lastOrNull { it.startPage <= page && (it.isArticle || it.isPart) } ?: return 0
        return if (before.isPart) before.level + 1 else before.level
    }

    /**
     * Combines a fresh reading of the contents with the old list: chapters the user added or edited
     * are kept (replacing the matching new entry), and typed or AI authors are carried over.
     */
    fun mergeRedetected(old: List<VolumeChapter>, fresh: List<VolumeChapter>): List<VolumeChapter> {
        fun key(t: String) = norm(ChapterTitles.split(t).second)
        val out = fresh.map { c ->
            val prev = if (c.isArticle) old.firstOrNull { it.isArticle && !it.isManual && (key(it.title) == key(c.title) || (it.startPage == c.startPage && it.authors.isNotBlank())) } else null
            val withAuthors = if (prev != null && prev.authorSource in setOf("user", "ai", "crossref") && prev.authors.isNotBlank()) c.copy(authors = prev.authors, authorSource = prev.authorSource, doi = prev.doi.ifBlank { c.doi })
                else c
            val kept = if (prev != null) withAuthors.copy(tags = prev.tags, abstract = prev.abstract) else withAuthors
            val seen = old.firstOrNull { it.startPage == c.startPage && it.isArticle }?.furthestPage ?: 0
            if (seen > kept.furthestPage) kept.copy(furthestPage = seen) else kept
        }.toMutableList()
        old.filter { it.isManual }.forEach { m ->
            val same = out.indexOfFirst { it.startPage == m.startPage && (it.isArticle || it.isSection) && (key(it.title) == key(m.title) || it.isSection == m.isSection) }
            if (same >= 0) out[same] = m.copy(level = out[same].level)
            else {
                val at = out.indexOfFirst { it.startPage > m.startPage }.let { if (it < 0) out.size else it }
                out.add(at, m)
            }
        }
        return out
    }

    /** Reading progress of chapter [index], 0..1 (by the furthest page reached in it). */
    fun progressOf(volume: EditedVolume, index: Int): Float {
        val c = volume.chapters.getOrNull(index) ?: return 0f
        if (c.furthestPage < c.startPage) return 0f
        val end = endPageOf(volume.chapters, index, volume.totalPages.coerceAtLeast(c.startPage))
        val len = (end - c.startPage + 1).coerceAtLeast(1)
        return ((c.furthestPage - c.startPage + 1).toFloat() / len).coerceIn(0f, 1f)
    }

    /** Called on page turns: remembers the furthest page reached in the chapter at [page]. */
    fun notePageSeen(context: Context, documentId: Long, page: Int, chapterTitle: String = "") {
        val v = get(context, documentId)?.takeIf { it.enabled } ?: return
        val i = chapterIndexFor(v, page, chapterTitle)
        val c = v.chapters.getOrNull(i) ?: return
        if (page <= c.furthestPage || page < c.startPage) return
        val list = v.chapters.toMutableList()
        list[i] = c.copy(furthestPage = page)
        put(context, documentId, v.copy(chapters = list))
    }

    /**
     * Fills chapters from Crossref records: matched by title (or start of the printed page range).
     * Authors are filled unless typed by the user; pages, DOI and abstract only where empty.
     * Returns the updated list and how many chapters were matched.
     */
    fun applyCrossref(chapters: List<VolumeChapter>, records: List<CrossrefChapter>): Pair<List<VolumeChapter>, Int> {
        fun words(t: String) = norm(ChapterTitles.split(t).second).split(' ').filter { it.length > 2 }.toSet()
        val used = HashSet<CrossrefChapter>()
        var matched = 0
        val out = chapters.map { c ->
            if (!c.isArticle) return@map c
            val w = words(c.title)
            val best = records.filter { it !in used }.maxByOrNull { r ->
                val rw = words(r.title)
                val overlap = if (w.isEmpty() || rw.isEmpty()) 0f else w.intersect(rw).size.toFloat() / minOf(w.size, rw.size)
                val pageHit = c.printedPages.isNotBlank() && r.pages.isNotBlank() &&
                    c.printedPages.substringBefore('–').trim() == r.pages.substringBefore('–').trim()
                overlap + if (pageHit) 0.5f else 0f
            } ?: return@map c
            val rw = words(best.title)
            val overlap = if (w.isEmpty() || rw.isEmpty()) 0f else w.intersect(rw).size.toFloat() / minOf(w.size, rw.size)
            val pageHit = c.printedPages.isNotBlank() && best.pages.isNotBlank() &&
                c.printedPages.substringBefore('–').trim() == best.pages.substringBefore('–').trim()
            if (overlap < 0.6f && !(pageHit && overlap >= 0.3f)) return@map c
            used += best; matched++
            c.copy(
                authors = if (c.authorSource == "user" || best.authors.isBlank()) c.authors else best.authors,
                authorSource = if (c.authorSource == "user" || best.authors.isBlank()) c.authorSource else "crossref",
                printedPages = c.printedPages.ifBlank { best.pages },
                doi = c.doi.ifBlank { best.doi },
                abstract = c.abstract.ifBlank { best.abstract }
            )
        }
        return out to matched
    }

    fun norm(s: String): String = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    // ------------------------------------------------------------------ chapter as a document

    /** Stable in-memory id for a chapter, used for its bibliographic record. */
    fun virtualId(bookId: Long, index: Int): Long = -(bookId * 1000L + index + 1)

    /**
     * Builds a document and bibliographic record for one chapter, so the existing citation and export
     * code can treat it like an article: author = chapter author, title = "Chapter (in Book)",
     * BibTeX @incollection with booktitle, editor and pages.
     */
    fun chapterAsDocument(book: DocumentEntity, volume: EditedVolume, index: Int): Pair<DocumentEntity, BibRecord> {
        val c = volume.chapters[index]
        val bookRec = BibStore.get(book.id)
        val bookTitle = listOf(bookRec[BibField.TITLE].ifBlank { book.title }, bookRec[BibField.SUBTITLE]).filter { it.isNotBlank() }.joinToString(": ")
        var rec = BibRecord(bookRec.fields - setOf(BibField.TITLE, BibField.SUBTITLE, BibField.PAGES, BibField.DOI, BibField.CITEKEY, BibField.URL), "incollection")
            .with(BibField.TITLE, c.title)
            .with(BibField.BOOKTITLE, bookTitle)
            .with(BibField.EDITOR, editors(book))
            .with(BibField.PAGES, c.printedPages)
            .with(BibField.DOI, c.doi)
        if (rec[BibField.YEAR].isBlank()) {
            runCatching { MarkdownExporter.extractPublishedYear(book) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { rec = rec.with(BibField.YEAR, it) }
        }
        val id = virtualId(book.id, index)
        val doc = book.copy(
            id = id,
            title = "${c.title} (in ${book.title})",
            author = c.authors.ifBlank { editors(book) },
            category = "BOOK",
            summary = c.abstract,
            tags = c.tags.ifBlank { book.tags }
        )
        BibStore.putTransient(id, rec)
        return doc to rec
    }

    /** "Author - Year - Chapter (in Book).PDF" */
    fun exportFileName(book: DocumentEntity, volume: EditedVolume, index: Int, ext: String = "PDF"): String {
        val (doc, _) = chapterAsDocument(book, volume, index)
        val name = MarkdownExporter.formatExportedSourceFilename(doc.copy(filePath = "chapter.$ext", fileType = ext))
        return name.substringBeforeLast('.') + ".$ext"
    }
}

/** Guesses chapter authors from the table of contents entry or the chapter's opening text. */
object ChapterAuthorGuesser {
    private val particles = setOf("van", "von", "de", "der", "den", "da", "di", "du", "la", "le", "del", "des", "af", "ter", "ten", "y", "bin", "al", "el", "st.")
    private val notNames = setOf(
        "introduction", "chapter", "part", "contents", "preface", "foreword", "abstract", "index", "notes", "references",
        "bibliography", "acknowledgements", "acknowledgments", "conclusion", "appendix", "university", "press", "department",
        "institute", "college", "school", "edition", "volume", "kapittel", "innledning", "del", "forord", "litteratur",
        "the", "and", "of", "in", "on", "for", "to", "a", "an", "with", "from", "og", "i", "på", "om", "til",
        "lecture", "lectures", "essay", "section", "afterword", "epilogue", "prologue", "postscript", "reply", "response", "comment",
        "commentary", "discussion", "review", "symposium", "keynote", "foreword", "summary", "overview", "forelesning", "essay", "svar"
    )
    private val matterTitles = Regex(
        "^(?:cover|title page|half title|copyright|contents|table of contents|list of (?:figures|tables|contributors|abbreviations)|" +
            "notes on contributors|contributors|acknowledg(?:e)?ments|index|subject index|name index|bibliography|references|" +
            "dedication|epigraph|preface|source acknowledg\\w*|permissions|credits|series (?:editor'?s )?preface|editor'?s preface|forord|innhold|innholdsfortegnelse|register|stikkord|litteratur(?:liste)?|om forfatterne|forfatterne|" +
            "colophon|about the (?:authors|editors|contributors)|series page|also by|frontmatter|backmatter|front matter|back matter)\\b",
        RegexOption.IGNORE_CASE
    )
    private val partTitle = Regex("^(?:part|del|teil|partie)\\s+(?:[ivxlc]+|\\d+|one|two|three|four|five|six|seven|eight|nine|ten|første|andre|tredje)\\b", RegexOption.IGNORE_CASE)

    fun isMatter(title: String) = matterTitles.containsMatchIn(title.trim().trimStart('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', ' '))
    fun isPart(title: String) = partTitle.containsMatchIn(title.trim())

    /** Splits "Title / Author" or "Title — Author" table-of-contents entries. Returns (title, authors). */
    fun splitTocTitle(raw: String): Pair<String, String> {
        val t = raw.replace(Regex("\\s+"), " ").trim()
        for (sep in listOf(" / ", " — ", " – ", " - ", " | ")) {
            val i = t.lastIndexOf(sep)
            if (i > 3) {
                val right = t.substring(i + sep.length).trim()
                val names = nameLine(right)
                if (names != null) return t.substring(0, i).trim() to names
            }
        }
        Regex("^(.{4,}?)\\s*\\(([^()]{3,80})\\)$").matchEntire(t)?.let { m ->
            nameLine(m.groupValues[2])?.let { return m.groupValues[1].trim() to it }
        }
        return t to ""
    }

    /** Looks for a name line near the chapter title in the opening text. */
    fun fromText(title: String, text: String, bookAuthors: String = ""): String {
        val lines = text.lines().map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() }.take(40)
        if (lines.isEmpty()) return ""
        val tn = EditedVolumes.norm(ChapterAuthorGuesser.stripNumber(title))
        val titleIdx = lines.indexOfFirst { l ->
            val ln = EditedVolumes.norm(l)
            tn.isNotBlank() && ln.length >= 3 && (ln.contains(tn.take(40)) || tn.contains(ln) && ln.length > tn.length / 2)
        }
        val bookNames = Citations.people(bookAuthors.replace(Regex("\\((?:eds?|red)\\.?\\)", RegexOption.IGNORE_CASE), "")).map { it.family.lowercase(Locale.ROOT) }.toSet()
        val order = if (titleIdx >= 0) {
            ((titleIdx + 1)..(titleIdx + 5)).toList() + ((titleIdx - 1) downTo (titleIdx - 4)).toList()
        } else (0 until 10).toList()
        for (i in order) {
            val line = lines.getOrNull(i) ?: continue
            // A subtitle printed under the title is part of the contents entry, not a name.
            if (tn.isNotBlank() && EditedVolumes.norm(line).let { it.length > 3 && tn.contains(it) }) continue
            val names = nameLine(line) ?: continue
            // A running header with the book's editors is not the chapter author.
            val fams = Citations.people(names).map { it.family.lowercase(Locale.ROOT) }
            if (fams.isNotEmpty() && fams.all { it in bookNames } && titleIdx < 0) continue
            return names
        }
        return ""
    }

    fun stripNumber(title: String) = title.replace(Regex("^(?:chapter|kapittel|ch\\.)?\\s*[0-9ivxlc]+[.:)]?\\s+", RegexOption.IGNORE_CASE), "").trim()

    /** Returns "First Last; Other Person" when the line consists only of personal names, else null. */
    fun nameLine(raw: String): String? {
        var s = raw.trim().replace(Regex("[*†‡§¹²³⁴⁵⁶⁷⁸⁹⁰]+"), "").replace(Regex("(?<=\\p{L})\\d+\\b"), "").trim()
        s = s.replace(Regex("^(?:by|av|von|par|di)\\s+", RegexOption.IGNORE_CASE), "")
        if (s.length < 4 || s.length > 90) return null
        if (s.endsWith('.') && !Regex("\\b\\p{Lu}\\.$").containsMatchIn(s)) return null
        if (s.any { it.isDigit() } || s.contains(':') || s.contains('?') || s.contains('“') || s.contains('"')) return null
        val parts = s.split(Regex("\\s*(?:,|;|&|\\band\\b|\\bog\\b|\\bund\\b)\\s*", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty() || parts.size > 5) return null
        val names = mutableListOf<String>()
        for (p in parts) {
            val tokens = p.split(Regex("\\s+"))
            if (tokens.size !in 2..4 || tokens.any { it.length > 16 }) return null
            var hasSurname = false
            for (tok in tokens) {
                val bare = tok.trim('.', ',')
                val low = bare.lowercase(Locale.ROOT)
                val initial = Regex("^\\p{Lu}\\.(?:-\\p{Lu}\\.?)?$").matches(tok) || (tok.length == 1 && tok[0].isUpperCase() && tok != "A" && tok != "I")
                if (!initial && low in notNames) return null
                when {
                    initial -> {}                                                                  // initial: J. or J.-P.
                    low in particles -> {}
                    Regex("^\\p{Lu}[\\p{L}'’-]+$").matches(bare) && bare.length >= 2 -> hasSurname = true
                    else -> return null
                }
            }
            if (!hasSurname) return null
            names += tokens.joinToString(" ") { tok ->
                val allCaps = tok.length > 2 && tok.filter { it.isLetter() }.all { it.isUpperCase() }
                if (allCaps) tok.lowercase(Locale.ROOT).split('-').joinToString("-") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } } else tok
            }
        }
        return names.joinToString("; ")
    }
}

/** Chapter numbers printed in titles: "Chapter 3: X", "3. X", "3 X", "IV. X", "Kapittel 2 X". */
object ChapterTitles {
    private val worded = Regex("^(?:chapter|kapittel|kap\\.|ch\\.|kapitel|chapitre)\\s+([0-9]{1,3}|[IVXLC]{1,7})\\b[.:)–—-]?\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val dotted = Regex("^([0-9]{1,3}|[IVXLC]{1,7})[.:)]\\s+(\\S.*)$")
    private val bare = Regex("^([0-9]{1,2})\\s+(\\p{Lu}.*)$")
    private val dashed = Regex("^([0-9]{1,3})\\s*[–—-]\\s+(\\S.*)$")

    /** Returns (number, title without the number). Number is "" when the title has none. */
    fun split(raw: String): Pair<String, String> {
        val t = raw.trim()
        worded.matchEntire(t)?.let { m -> return m.groupValues[1] to m.groupValues[2].trim().ifBlank { t } }
        for (r in listOf(dotted, dashed, bare)) {
            r.matchEntire(t)?.let { m ->
                val num = m.groupValues[1]
                // "1.2 Section" style numbering is kept whole for sections; "I." only counts with a real title after it.
                return num to m.groupValues[2].trim()
            }
        }
        return "" to t
    }

    /** Roman numeral prefix like "I. Foundations" or "II Justice". */
    fun hasRomanPrefix(raw: String) = Regex("^[IVXLC]{1,6}[.:)]?\\s+\\p{Lu}").containsMatchIn(raw.trim())
}

/**
 * Turns a book's full table of contents (any depth) into the chapter list of an edited volume.
 * Parts ("Part I", or groupings whose children carry authors while they don't) become headings and
 * their children are examined in turn; the first level below that is a chapter (an article with its
 * own author); everything deeper becomes a section of that chapter.
 *
 * Contents often split one chapter over several entries; these are merged first:
 *  - title, with the author as a child or as the next entry on the same page;
 *  - author, with the title as its only child (or the next entry on the same page);
 *  - "Chapter 3", with the title (and maybe the author) as children.
 */
object VolumeStructure {

    data class Entry(val title: String, val page: Int, val level: Int, val href: String = "", val printedPages: String = "")

    private class Node(val index: Int, val entry: Entry) {
        val children = mutableListOf<Node>()
        var title: String = entry.title
        var authors: String = ""        // found in the contents structure itself
        var label: String = ""
        var inheritedAuthors: String = "" // from an author heading that groups several chapters
    }

    /** Stronger evidence than [ChapterAuthorGuesser.nameLine]: initials, all caps, particles or several people. */
    private fun clearlyNames(raw: String): String? {
        val names = ChapterAuthorGuesser.nameLine(raw) ?: return null
        val t = raw.trim()
        val strong = names.contains(";") || Regex("\\b\\p{Lu}\\.").containsMatchIn(t) ||
            (t.length > 3 && t.filter { it.isLetter() }.all { it.isUpperCase() }) ||
            Regex("\\b(?:van|von|de|der|da|di|du|la|le|af|ter)\\b").containsMatchIn(t) || t.startsWith("by ", true) || t.startsWith("av ", true)
        return if (strong) names else null
    }

    private fun nameOnly(raw: String): String? {
        if (ChapterAuthorGuesser.isMatter(raw) || ChapterAuthorGuesser.isPart(raw)) return null
        return ChapterAuthorGuesser.nameLine(raw)
    }

    private fun numberOnly(raw: String): String? =
        Regex("^(?:chapter|kapittel|kap\\.|ch\\.|kapitel|chapitre)?\\s*([0-9]{1,3}|[IVXLC]{1,7})[.:]?$", RegexOption.IGNORE_CASE)
            .matchEntire(raw.trim())?.groupValues?.get(1)

    private fun near(a: Node, b: Node) = b.entry.page in a.entry.page..(a.entry.page + 1)

    private fun join(a: String, b: String) = listOf(a, b).filter { it.isNotBlank() }.joinToString("; ")

    /** Merges title/author/number entries that belong to one chapter. */
    private fun normalize(nodes: MutableList<Node>, ownChapter: (Node) -> Boolean, confirm: (Node, String) -> Boolean) {
        // Same level: "Title" followed by "Author" on the same page (or the reverse).
        var i = 0
        while (i < nodes.size) {
            val n = nodes[i]
            val next = nodes.getOrNull(i + 1)
            if (next != null && next.children.isEmpty() && next.entry.page == n.entry.page) {
                val nextName = nameOnly(next.title)
                val selfName = nameOnly(n.title)
                // Title first, then author, is the usual order when both look like names.
                if (nextName != null && (selfName == null || clearlyNames(n.title) == null) && !ownChapter(next) && confirm(n, nextName)) {
                    n.authors = join(n.authors, nextName); nodes.removeAt(i + 1); continue
                }
                if (selfName != null && nextName == null && n.children.isEmpty() && confirm(next, selfName)) {
                    next.authors = join(next.authors, selfName); nodes.removeAt(i); continue
                }
            }
            i++
        }
        for (n in nodes) {
            // "Chapter 3" with the title as its first child.
            numberOnly(n.title)?.let { num ->
                val first = n.children.firstOrNull { nameOnly(it.title) == null }
                if (first != null && near(n, first)) {
                    n.label = num; n.title = first.title
                    n.authors = join(n.authors, first.authors)
                    n.children.remove(first); n.children.addAll(0, first.children)
                }
            }
            // An author heading with the chapter title as its only child → one chapter.
            val selfName = nameOnly(n.title)
            if (selfName != null && n.children.size == 1 && nameOnly(n.children[0].title) == null && near(n, n.children[0]) && confirm(n.children[0], selfName)) {
                val c = n.children[0]
                n.authors = join(selfName, c.authors); n.title = c.title
                if (c.label.isNotBlank()) n.label = c.label
                n.children.clear(); n.children.addAll(c.children)
            } else if (n.children.size >= 2 && clearlyNames(n.title) != null && n.children.none { nameOnly(it.title) != null }) {
                // An author heading over several chapters (e.g. lectures by one author).
                val names = clearlyNames(n.title)!!
                n.children.forEach { it.inheritedAuthors = names }
            }
            // Author entries as children on the chapter's first pages.
            val nameKids = n.children.take(2).filter { it.children.isEmpty() && near(n, it) && nameOnly(it.title) != null && !ownChapter(it) && confirm(n, nameOnly(it.title)!!) }
            if (nameKids.isNotEmpty()) {
                n.authors = join(n.authors, nameKids.joinToString("; ") { nameOnly(it.title)!! })
                n.children.removeAll(nameKids)
            }
            normalize(n.children, ownChapter, confirm)
        }
    }

    fun sameTitle(a: String, b: String): Boolean {
        val x = EditedVolumes.norm(a); val y = EditedVolumes.norm(b)
        if (x.isBlank() || y.isBlank()) return false
        return x == y || (minOf(x.length, y.length) >= 8 && (x.startsWith(y) || y.startsWith(x)))
    }

    fun build(entries: List<Entry>, textOf: (Int) -> String, bookAuthors: String, bookTitle: String = ""): List<VolumeChapter> {
        if (entries.isEmpty()) return emptyList()
        // Rebuild the tree from levels (levels may skip, e.g. 0 → 2).
        val roots = mutableListOf<Node>()
        val stack = ArrayDeque<Node>()
        entries.forEachIndexed { i, e ->
            val n = Node(i, e)
            while (stack.isNotEmpty() && stack.last().entry.level >= e.level) stack.removeLast()
            if (stack.isEmpty()) roots += n else stack.last().children += n
            stack.addLast(n)
        }
        // An entry whose own first page shows its title with an author under it is a chapter, not an author line.
        // A bookmark that wraps the whole book (its title, or the only top-level entry) is not a chapter:
        // its children are the real contents.
        while (true) {
            val w = roots.indexOfFirst { n ->
                val plainHeading = !ChapterAuthorGuesser.isPart(n.title) && numberOnly(n.title) == null && nameOnly(n.title) == null &&
                    ChapterTitles.split(n.title).first.isBlank()
                n.children.size >= 2 && plainHeading && (sameTitle(n.title, bookTitle) || roots.size == 1 ||
                    (n.children.size >= 4 && roots.count { it.children.isNotEmpty() } == 1 && roots.size <= 4))
            }
            if (w < 0) break
            val n = roots.removeAt(w)
            roots.addAll(w, n.children)
        }
        normalize(roots, { n -> ChapterAuthorGuesser.fromText(n.title, textOf(n.index), bookAuthors).isNotBlank() }) { chapter, name ->
            // A contents entry is taken as the chapter's author only when the chapter's first page agrees
            // (the name printed under the title), or — with no text to check — when it clearly is a name.
            val text = textOf(chapter.index)
            if (text.isBlank()) ChapterAuthorGuesser.nameLine(name) != null
            else {
                val found = ChapterAuthorGuesser.fromText(chapter.title, text, bookAuthors)
                val fam = Citations.people(name).map { EditedVolumes.norm(it.family) }
                found.isNotBlank() && fam.isNotEmpty() && fam.all { f -> EditedVolumes.norm(found).contains(f) } || (found.isBlank() && clearlyNames(name) != null)
            }
        }
        val authorCache = HashMap<Int, String>()
        fun cleanTitle(n: Node) = ChapterAuthorGuesser.splitTocTitle(n.title).first
        fun tocAuthors(n: Node) = join(n.authors, ChapterAuthorGuesser.splitTocTitle(n.title).second).ifBlank { n.inheritedAuthors }
        fun authorOf(n: Node): String = authorCache.getOrPut(n.index) {
            val title = cleanTitle(n)
            val toc = tocAuthors(n)
            when {
                ChapterAuthorGuesser.isMatter(title) || ChapterAuthorGuesser.isPart(title) -> ""
                toc.isNotBlank() -> toc
                else -> ChapterAuthorGuesser.fromText(ChapterTitles.split(title).second, textOf(n.index), bookAuthors)
            }
        }
        fun isPartNode(n: Node, siblings: List<Node>): Boolean {
            if (n.children.isEmpty()) return false
            val title = cleanTitle(n)
            if (ChapterAuthorGuesser.isMatter(title)) return false
            if (n.children.any { it.inheritedAuthors.isNotBlank() }) return true
            if (n.authors.isNotBlank()) return false
            if (ChapterAuthorGuesser.isPart(title)) return true
            if (n.children.size >= 2 && ChapterTitles.hasRomanPrefix(title) && siblings.size <= 12) return true
            // Children have their own authors while the grouping heading has none → it's a part.
            if (authorOf(n).isBlank()) {
                val kids = n.children.take(6)
                val withAuthors = kids.count { authorOf(it).isNotBlank() }
                if (withAuthors >= 1 && withAuthors * 2 >= kids.size) return true
            }
            return false
        }

        val out = mutableListOf<VolumeChapter>()
        fun emitSections(n: Node, depth: Int) {
            n.children.forEach { c ->
                out += VolumeChapter(title = cleanTitle(c), startPage = c.entry.page, href = c.entry.href, level = depth, isSection = true)
                emitSections(c, depth + 1)
            }
        }
        fun classify(nodes: List<Node>, depth: Int) {
            nodes.forEach { n ->
                val title = cleanTitle(n)
                when {
                    ChapterAuthorGuesser.isMatter(title) -> {
                        out += VolumeChapter(title = title, startPage = n.entry.page, href = n.entry.href, level = depth, isMatter = true)
                    }
                    isPartNode(n, nodes) -> {
                        out += VolumeChapter(title = title, startPage = n.entry.page, href = n.entry.href, level = depth, isPart = true)
                        classify(n.children, depth + 1)
                    }
                    else -> {
                        val (num, bare) = ChapterTitles.split(title)
                        val authors = authorOf(n)
                        out += VolumeChapter(
                            title = bare, label = n.label.ifBlank { num }, authors = authors, startPage = n.entry.page, href = n.entry.href, level = depth,
                            printedPages = n.entry.printedPages,
                            authorSource = when { authors.isBlank() -> ""; tocAuthors(n).isNotBlank() -> "toc"; else -> "text" }
                        )
                        emitSections(n, depth + 1)
                    }
                }
            }
        }
        classify(roots, 0)
        return out
    }
}

/** Splits a selected keyword line into tags: "Keywords: justice; public reason, Rawls" → [justice, public-reason, Rawls]. */
object TagSplitter {
    private val label = Regex(
        "^\\s*(?:key\\s*words?|keywords?|tags?|subjects?|topics?|index terms|nøkkelord|stikkord|emneord|søkeord|schlüsselwörter|stichwörter|schlagwörter|mots[- ]clés|palabras clave|jel(?: classification)?(?: codes?)?)\\s*[:：.–—-]?\\s*",
        RegexOption.IGNORE_CASE
    )

    fun split(raw: String): List<String> {
        val text = raw.replace('\u00AD', ' ').trim().replace(label, "")
        // Commas, semicolons, middle dots, bullets, pipes, slashes between words and line breaks separate tags.
        val parts = text.split(Regex("\\s*(?:[,;·•|]|\\s/\\s|\\n|\\s[–—]\\s)\\s*"))
        return parts.map { NoteSuggestionEngine.normalizeTag(it.trim().trimEnd('.', ':')) }
            .filter { it.isNotBlank() && it.length <= 60 }
            .distinctBy { it.lowercase(Locale.ROOT) }
    }
}
