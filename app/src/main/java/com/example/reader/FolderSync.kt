package com.example.reader

import android.content.Context
import android.util.Log
import com.example.data.StorageManager
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.data.local.SettingsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Keeps each book's folder and the app in step, both ways. The files in the folder are what
 * counts; the app's database is a fast index of them.
 *
 * Every book or article folder holds:
 *  - the overview note ("Notes on ….md"): title, authors, tags, all bibliographic details in the
 *    front matter, and the abstract;
 *  - one note per highlight ("<note title>.md"): its title, tags and your notes;
 *  - "<title>.ris" and "<title>.bib": the citation records.
 *
 * A change in the app is written to the files within a second or two. A change made to a file
 * (in a text editor, Obsidian, a file manager…) is read back when the app looks at the folders:
 * at start, when it comes to the front, and once a minute while it runs. Changes are merged field
 * by field; when the same field changed in both places, the newest change wins.
 *
 * Deleting a note file does not delete the highlight (the note file comes back). A highlight
 * deleted in the app removes its note file only if the file was not edited outside the app.
 */
object FolderSync {
    private const val TAG = "FolderSync"
    private const val ID_DOC = "marginalia-doc"
    private const val ID_NOTE = "marginalia-note"

    private lateinit var app: Context
    @Volatile private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    /** When the folders were last brought in step, and how many changes came from files. */
    val lastSync = MutableStateFlow(0L)
    val importedFromFiles = MutableStateFlow(0)

    // ------------------------------------------------------------------------------ state

    /** What was last written for one document: file names (inside its folder) and modification times. */
    private class DocState {
        var overview: String = ""
        var ris: String = ""
        var bib: String = ""
        val notes = HashMap<Long, String>()
        val mtimes = HashMap<String, Long>()
        var sig: String = ""
        var appChangedAt: Long = 0L
    }

    private val state = HashMap<Long, DocState>()
    private fun baseDir(docId: Long) = File(app.filesDir, "folder_sync/$docId").apply { mkdirs() }
    private fun stateFile() = File(app.filesDir, "folder_sync/state.json")

    private fun loadState() {
        runCatching {
            val f = stateFile(); if (!f.isFile) return
            val root = JSONObject(f.readText())
            root.keys().forEach { k ->
                val o = root.getJSONObject(k)
                val s = DocState()
                s.overview = o.optString("overview"); s.ris = o.optString("ris"); s.bib = o.optString("bib")
                s.sig = o.optString("sig"); s.appChangedAt = o.optLong("appChangedAt")
                o.optJSONObject("notes")?.let { n -> n.keys().forEach { s.notes[it.toLong()] = n.getString(it) } }
                o.optJSONObject("mtimes")?.let { m -> m.keys().forEach { s.mtimes[it] = m.getLong(it) } }
                state[k.toLong()] = s
            }
        }.onFailure { Log.w(TAG, "Could not read the sync state", it) }
    }

    private fun saveState() {
        runCatching {
            val root = JSONObject()
            state.forEach { (id, s) ->
                root.put(id.toString(), JSONObject()
                    .put("overview", s.overview).put("ris", s.ris).put("bib", s.bib)
                    .put("sig", s.sig).put("appChangedAt", s.appChangedAt)
                    .put("notes", JSONObject().apply { s.notes.forEach { (k, v) -> put(k.toString(), v) } })
                    .put("mtimes", JSONObject().apply { s.mtimes.forEach { (k, v) -> put(k, v) } }))
            }
            val f = stateFile(); f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) { f.writeText(root.toString()); tmp.delete() }
        }
    }

    // ------------------------------------------------------------------------------ start

    /** Starts keeping the folders in step. Safe to call more than once (activity and web server). */
    @OptIn(FlowPreview::class)
    fun start(context: Context) {
        if (started) return
        synchronized(this) { if (started) return; started = true }
        app = context.applicationContext
        BibStore.init(app)
        PdfSelections.init(app)
        scope.launch {
            lock.withLock { loadState() }
            val db = AppDatabase.getInstance(app)
            // App → files: any change to a book, its notes or its bibliographic record.
            launch {
                combine(db.documentDao().getAllDocuments(), db.highlightDao().getAllHighlights(), BibStore.version, PdfSelections.version) { d, h, _, _ -> d to h }
                    .debounce(1200)
                    .collect { (docs, hls) -> runCatching { lock.withLock { syncAll(docs, hls, onlyChangedFiles = false) } }.onFailure { Log.w(TAG, "Sync failed", it) } }
            }
            // Files → app: look at the folders now and then.
            launch {
                delay(4000)
                while (true) { scanNow(); delay(60_000) }
            }
        }
    }

    /** Looks at the folders for changes made outside the app (call when the app comes to the front). */
    fun scanSoon() {
        if (!started) return
        scope.launch { scanNow() }
    }

    private suspend fun scanNow() {
        runCatching {
            lock.withLock {
                val db = AppDatabase.getInstance(app)
                syncAll(db.documentDao().getAllDocumentsSnapshot(), db.highlightDao().getAllHighlights().first(), onlyChangedFiles = true)
            }
        }.onFailure { Log.w(TAG, "Folder scan failed", it) }
    }

    // ------------------------------------------------------------------------------ the sync

    private fun folderOf(d: DocumentEntity): File? {
        val dir = File(d.filePath).parentFile ?: return null
        if (!dir.isDirectory || dir.name == "Books" || dir.name == "Articles") return null
        return dir
    }

    private suspend fun settings(): SettingsEntity =
        AppDatabase.getInstance(app).settingsDao().getSettingsSnapshot() ?: SettingsEntity()

    private suspend fun syncAll(docs: List<DocumentEntity>, hls: List<HighlightEntity>, onlyChangedFiles: Boolean) {
        val settings = settings()
        val byDoc = hls.groupBy { it.documentId }
        var dirty = false
        for (d in docs) {
            val dir = folderOf(d) ?: continue
            val notes = byDoc[d.id].orEmpty().sortedBy { it.pageNumber }
            val st = state.getOrPut(d.id) { DocState() }
            PdfSelections.request(app, d, notes)
            val sig = signature(d, notes, settings)
            // Page turns change the database often; files are only looked at when something that
            // goes into them changed, or on a folder scan.
            val filesChanged = if (onlyChangedFiles || sig != st.sig) filesChangedOnDisk(st, dir) else false
            if (onlyChangedFiles && !filesChanged) continue
            if (!onlyChangedFiles && sig == st.sig && !filesChanged) continue
            if (sig != st.sig && st.sig.isNotEmpty()) st.appChangedAt = System.currentTimeMillis()
            runCatching {
                val imported = mergeFromFiles(d, notes, settings, st, dir)
                if (imported) {
                    importedFromFiles.value = importedFromFiles.value + 1
                    dirty = true
                    // The database changed: the app→files pass that follows writes the result.
                    return@runCatching
                }
                writeFiles(d, notes, settings, st, dir)
                st.sig = sig
                dirty = true
            }.onFailure { Log.w(TAG, "Could not sync “${d.title}”", it) }
        }
        val ids = docs.map { it.id }.toSet()
        state.keys.filter { it !in ids }.forEach { state.remove(it); File(app.filesDir, "folder_sync/$it").deleteRecursively() }
        if (dirty) saveState()
        lastSync.value = System.currentTimeMillis()
    }

    private fun signature(d: DocumentEntity, notes: List<HighlightEntity>, s: SettingsEntity): String {
        val rec = BibStore.get(d.id)
        val sb = StringBuilder()
        sb.append(d.title).append('|').append(d.author).append('|').append(d.tags).append('|').append(d.summary).append('|')
            .append(d.category).append('|').append(d.filePath).append('|').append(d.coverUri).append('|')
        sb.append(rec.entryType).append(rec.fields.toSortedMap().toString()).append('|')
        notes.forEach { h -> sb.append(h.id).append(h.title).append(h.highlightedText).append(h.userNotes).append(h.tags).append(h.pageNumber)
            .append(h.printedPageLabel).append(h.chapterTitle).append(h.paragraphCitation).append(h.epubCfi)
            .append(PdfSelections.get(h, d).orEmpty()).append(';') }
        sb.append("links2|")
        sb.append(s.defaultTags).append(s.mocTitleFormat).append(s.wikiLinkStyle).append(s.customFrontmatterYaml).append(Citations.defaultStyle().name)
        return sha(sb.toString())
    }

    private fun sha(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun filesChangedOnDisk(st: DocState, dir: File): Boolean {
        val names = listOfNotNull(st.overview.ifBlank { null }, st.ris.ifBlank { null }) + st.notes.values
        if (names.any { n -> val f = File(dir, n); f.isFile && f.lastModified() != (st.mtimes[n] ?: -1L) }) return true
        // Never synced: an overview written by Marginalia before (e.g. after reinstalling) is read in.
        return st.overview.isBlank() && dir.listFiles()?.any { it.isFile && it.extension.equals("md", true) && it.name != "highlight.md" } == true
    }

    private fun overviewName(d: DocumentEntity, s: SettingsEntity) =
        StorageManager.sanitizeName(MarkdownExporter.getMocTitle(d.title, s)).let { if (it.endsWith(".md", true)) it else "$it.md" }

    // ------------------------------------------------------------------------------ files → app

    /** Folds edits made to the files into the database. Returns true when something changed. */
    private suspend fun mergeFromFiles(d: DocumentEntity, notes: List<HighlightEntity>, s: SettingsEntity, st: DocState, dir: File): Boolean {
        var changed = false
        val db = AppDatabase.getInstance(app)

        // The overview note: title, authors, tags, abstract, category and every bibliographic field.
        run {
            val name = st.overview.ifBlank { overviewName(d, s) }
            val f = File(dir, name)
            if (!f.isFile || f.lastModified() == (st.mtimes[name] ?: -1L)) return@run
            val base = File(baseDir(d.id), "overview.md").takeIf { it.isFile && st.overview.isNotBlank() }?.readText()
            val text = f.readText()
            val theirs = OverviewFields.parse(text, s)
            val ours = OverviewFields.fromApp(d, BibStore.get(d.id), s)
            // First contact with an overview Marginalia wrote earlier (reinstall, restored folder):
            // what the file says fills in what the app does not know.
            val baseFields = when {
                base != null -> OverviewFields.parse(base, s)
                text.contains("$ID_DOC:") -> HashMap(ours).apply { theirs.forEach { (k, v) -> if (v.isNotBlank() && ours[k].isNullOrBlank()) this[k] = "" } }
                else -> { st.mtimes[name] = f.lastModified(); return@run }
            }
            val fileNewer = f.lastModified() > st.appChangedAt
            val merged = HashMap(ours)
            (theirs.keys + baseFields.keys).forEach { k ->
                val t = theirs[k].orEmpty(); val b = baseFields[k].orEmpty(); val o = ours[k].orEmpty()
                if (t != b && (o == b || fileNewer)) merged[k] = t
            }
            if (merged != ours) { applyOverview(d, ours, merged, s); changed = true }
            st.mtimes[name] = f.lastModified()
        }

        // The RIS file: an edited record fills and replaces the bibliographic fields.
        run {
            val name = st.ris.ifBlank { return@run }
            val f = File(dir, name)
            if (!f.isFile || f.lastModified() == (st.mtimes[name] ?: -1L)) return@run
            val base = File(baseDir(d.id), "record.ris").takeIf { it.isFile }?.readText()
            val text = f.readText()
            if (base != null && text.trim() != base.trim() && f.lastModified() > st.appChangedAt) {
                val fresh = db.documentDao().getDocumentById(d.id) ?: d
                Ris.apply(app, fresh, text, overwrite = true)
                changed = true
            }
            st.mtimes[name] = f.lastModified()
        }

        // One file per note: its title, tags and your notes.
        notes.forEach { h ->
            val name = st.notes[h.id] ?: return@forEach
            val f = File(dir, name)
            if (!f.isFile || f.lastModified() == (st.mtimes[name] ?: -1L)) return@forEach
            val base = File(baseDir(d.id), "n${h.id}.md").takeIf { it.isFile }?.readText() ?: return@forEach
            val theirs = NoteFields.parse(f.readText(), s)
            val b = NoteFields.parse(base, s)
            val ours = NoteFields.fromApp(h, s)
            val fileNewer = f.lastModified() > st.appChangedAt
            val merged = HashMap(ours)
            (theirs.keys + b.keys).forEach { k ->
                val t = theirs[k].orEmpty(); val bb = b[k].orEmpty(); val o = ours[k].orEmpty()
                if (t != bb && (o == bb || fileNewer)) merged[k] = t
            }
            if (merged != ours) {
                val keepDefaults = NoteSuggestionEngine.parseTagsList(h.tags).filter { NoteFields.isDefaultTag(it, s) }
                val tags = (keepDefaults + splitTags(merged["tags"].orEmpty())).distinctBy { it.lowercase(Locale.ROOT) }
                db.highlightDao().updateHighlight(h.copy(
                    title = merged["title"].orEmpty().ifBlank { h.title },
                    userNotes = merged["notes"].orEmpty(),
                    tags = NoteSuggestionEngine.joinTagsList(tags)
                ))
                changed = true
            }
            st.mtimes[name] = f.lastModified()
        }
        return changed
    }

    private suspend fun applyOverview(d: DocumentEntity, ours: Map<String, String>, merged: Map<String, String>, s: SettingsEntity) {
        val ops = com.example.server.LibraryOps(app)
        var doc = d
        // Bibliographic record first, so a renamed folder carries the new details.
        var rec = BibStore.get(d.id)
        BibField.entries.forEach { f ->
            val k = "bib:" + f.name
            if (merged[k].orEmpty() != ours[k].orEmpty()) rec = rec.with(f, merged[k].orEmpty())
        }
        if (merged["type"].orEmpty() != ours["type"].orEmpty()) rec = rec.copy(entryType = merged["type"].orEmpty())
        BibStore.put(d.id, rec)

        val cat = merged["category"].orEmpty()
        if (cat != ours["category"].orEmpty() && cat.isNotBlank()) {
            doc = ops.changeCategory(doc, if (cat.startsWith("art", true)) "ARTICLE" else "BOOK")
        }
        val title = merged["book-title"].orEmpty().ifBlank { doc.title }
        val authors = splitList(merged["authors"].orEmpty()).joinToString("; ")
        val keepDefaults = NoteSuggestionEngine.parseTagsList(doc.tags).filter { OverviewFields.isDefaultTag(it, s) }
        val tags = NoteSuggestionEngine.joinTagsList((keepDefaults + splitTags(merged["tags"].orEmpty())).distinctBy { it.lowercase(Locale.ROOT) })
        val summary = merged["abstract"].orEmpty()
        if (FolderBooks.isFolder(doc)) {
            FolderBooks.updateInfo(app, doc.id, title, authors, tags, summary)
        } else if (title != doc.title || authors != ours["authors"] || tags != doc.tags || summary.trim() != doc.summary.trim()) {
            ops.updateDocumentInfo(doc, title, authors.ifBlank { "Unknown Author" }, tags, newSummary = summary)
        }
    }

    // ------------------------------------------------------------------------------ app → files

    private fun writeFiles(d: DocumentEntity, notes: List<HighlightEntity>, s: SettingsEntity, st: DocState, dir: File) {
        val base = baseDir(d.id)
        val rec = BibStore.get(d.id)

        // Overview note (keeps a "# My Notes" section you add at the end).
        val overviewName = overviewName(d, s)
        moveIfRenamed(dir, st.overview, overviewName)
        val overviewContent = MarkdownExporter.generateMocMarkdown(d, notes, s)
        val overviewFile = File(dir, overviewName)
        writeKeepingUserSection(overviewFile, overviewContent)
        File(base, "overview.md").writeText(overviewContent)
        st.overview = overviewName; st.mtimes[overviewName] = overviewFile.lastModified()

        // Citation records.
        val stem = StorageManager.sanitizeName(d.title).ifBlank { "reference" }
        val risName = "$stem.ris"; val bibName = "$stem.bib"
        moveIfRenamed(dir, st.ris, risName); moveIfRenamed(dir, st.bib, bibName)
        val ris = Ris.export(d, rec)
        writeIfDifferent(File(dir, risName), ris); File(base, "record.ris").writeText(ris)
        writeIfDifferent(File(dir, bibName), Citations.bibtex(d, rec))
        st.ris = risName; st.bib = bibName
        st.mtimes[risName] = File(dir, risName).lastModified(); st.mtimes[bibName] = File(dir, bibName).lastModified()

        // One note per highlight.
        val used = HashSet<String>()
        used += overviewName.lowercase(Locale.ROOT); used += "highlight.md"
        notes.forEach { h ->
            val stemN = StorageManager.sanitizeName(h.title.trim().ifBlank { "Untitled Note" }).ifBlank { "Note" }
            var name = "$stemN.md"; var k = 2
            while (!used.add(name.lowercase(Locale.ROOT))) { name = "$stemN ($k).md"; k++ }
            moveIfRenamed(dir, st.notes[h.id].orEmpty(), name)
            val content = MarkdownExporter.generateHighlightMarkdown(h, d, s)
            val f = File(dir, name)
            writeIfDifferent(f, content)
            File(base, "n${h.id}.md").writeText(content)
            st.notes[h.id] = name; st.mtimes[name] = f.lastModified()
        }
        // Notes deleted in the app: remove their file if nobody edited it.
        val alive = notes.map { it.id }.toSet()
        st.notes.keys.filter { it !in alive }.forEach { id ->
            val name = st.notes.remove(id) ?: return@forEach
            val f = File(dir, name)
            val b = File(base, "n$id.md")
            if (f.isFile && b.isFile && f.readText() == b.readText()) f.delete()
            b.delete(); st.mtimes.remove(name)
        }
    }

    private fun moveIfRenamed(dir: File, oldName: String, newName: String) {
        if (oldName.isBlank() || oldName == newName) return
        val old = File(dir, oldName); val target = File(dir, newName)
        if (old.isFile && !target.exists()) old.renameTo(target)
    }

    private fun writeIfDifferent(f: File, content: String) {
        if (f.isFile && runCatching { f.readText() }.getOrNull() == content) return
        f.writeText(content, Charsets.UTF_8)
    }

    /** Writes the generated overview but keeps anything from a "# My Notes" heading on, written by you. */
    fun writeKeepingUserSection(f: File, generated: String) {
        val old = if (f.isFile) runCatching { f.readText() }.getOrNull() else null
        val mine = old?.let { userSection(it) }.orEmpty()
        val content = if (mine.isNotBlank()) generated.trimEnd() + "\n\n" + mine.trimEnd() + "\n" else generated
        writeIfDifferent(f, content)
    }

    private fun userSection(text: String): String? {
        val m = Regex("(?m)^#{1,3}\\s*My Notes\\s*:?\\s*$").find(text) ?: return null
        return text.substring(m.range.first)
    }

    // ------------------------------------------------------------------------------ parsing

    internal fun splitList(raw: String): List<String> = raw.split(';', '\n').map { it.trim() }.filter { it.isNotBlank() }
    internal fun splitTags(raw: String): List<String> = raw.split(',', ';', '\n').map { it.trim().removePrefix("#") }.filter { it.isNotBlank() }

    /** A small reader for the front matter the app writes: `key: value`, quoted values and `- item` lists. */
    internal object FrontMatter {
        fun parse(text: String): Pair<Map<String, Any>, String> {
            val t = text.replace("\r\n", "\n")
            if (!t.startsWith("---\n")) return emptyMap<String, Any>() to t
            val end = t.indexOf("\n---", 4)
            if (end < 0) return emptyMap<String, Any>() to t
            val head = t.substring(4, end)
            val body = t.substring((end + 4).coerceAtMost(t.length)).removePrefix("\n")
            val out = LinkedHashMap<String, Any>()
            var listKey: String? = null
            head.lines().forEach { line ->
                if (line.isBlank()) return@forEach
                val item = Regex("^\\s+-\\s*(.*)$").find(line)
                if (item != null && listKey != null) {
                    @Suppress("UNCHECKED_CAST")
                    (out.getOrPut(listKey!!) { mutableListOf<String>() } as? MutableList<String>)?.add(unquote(item.groupValues[1]))
                    return@forEach
                }
                val kv = Regex("^([^:\\s][^:]*):\\s*(.*)$").find(line) ?: return@forEach
                val key = kv.groupValues[1].trim(); val v = kv.groupValues[2].trim()
                if (v.isEmpty()) { listKey = key; out[key] = mutableListOf<String>() } else { listKey = null; out[key] = unquote(v) }
            }
            return out to body
        }

        fun unquote(v: String): String {
            var s = v.trim()
            if (s.length >= 2 && ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith('\'') && s.endsWith('\'')))) s = s.substring(1, s.length - 1)
            return s.removePrefix("[[").removeSuffix("]]").trim()
        }

        fun list(m: Map<String, Any>, key: String): List<String> = when (val v = m[key]) {
            is List<*> -> v.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }
            is String -> if (v.isBlank()) emptyList() else v.removePrefix("[").removeSuffix("]").split(',').map { unquote(it) }.filter { it.isNotBlank() }
            else -> emptyList()
        }

        fun str(m: Map<String, Any>, key: String): String = (m[key] as? String).orEmpty().trim()
    }

    /** The fields of an overview note that can be edited by hand. Keys: book-title, authors, tags, abstract, category, type, bib:FIELD. */
    internal object OverviewFields {
        fun isDefaultTag(t: String, s: SettingsEntity): Boolean =
            t.equals("Overview", true) || s.defaultTags.split(',').map { it.trim() }.any { it.equals(t, true) } || (s.defaultTags.isBlank() && t.equals("Literature", true))

        fun parse(text: String, s: SettingsEntity): Map<String, String> {
            val (fm, body) = FrontMatter.parse(text)
            val out = HashMap<String, String>()
            out["book-title"] = FrontMatter.str(fm, "book-title")
            out["authors"] = FrontMatter.list(fm, "Author(s)").joinToString("; ")
            out["tags"] = FrontMatter.list(fm, "tags").filter { !isDefaultTag(it, s) }.joinToString(", ")
            out["category"] = FrontMatter.str(fm, "category").lowercase(Locale.ROOT)
            out["type"] = FrontMatter.str(fm, "entry-type")
            BibField.entries.filter { it.bibtexKey.isNotBlank() && it != BibField.TITLE }.forEach { f ->
                out["bib:" + f.name] = FrontMatter.str(fm, f.bibtexKey)
            }
            out["abstract"] = section(body, "Abstract / Summary")
            return out
        }

        fun fromApp(d: DocumentEntity, rec: BibRecord, s: SettingsEntity): Map<String, String> {
            val out = HashMap<String, String>()
            out["book-title"] = d.title
            out["authors"] = MarkdownExporter.parseAuthorNamesList(d.author).joinToString("; ")
            out["tags"] = NoteSuggestionEngine.parseTagsList(d.tags).filter { !isDefaultTag(it, s) }.joinToString(", ")
            out["category"] = if (d.category.equals("ARTICLE", true)) "article" else "book"
            out["type"] = rec.entryType
            BibField.entries.filter { it.bibtexKey.isNotBlank() && it != BibField.TITLE }.forEach { f ->
                out["bib:" + f.name] = frontMatterValue(f, d, rec)
            }
            out["abstract"] = d.summary.trim()
            return out
        }

        /** The value the overview shows for a field (the year falls back to the one found in the file). */
        fun frontMatterValue(f: BibField, d: DocumentEntity, rec: BibRecord): String =
            if (f == BibField.YEAR) Citations.year(d, rec).takeIf { it != "n.d." }.orEmpty() else rec[f].replace("\"", "'")

        private fun section(body: String, heading: String): String {
            val m = Regex("(?m)^#\\s*" + Regex.escape(heading) + "\\s*$").find(body) ?: return ""
            val rest = body.substring(m.range.last + 1)
            val next = Regex("(?m)^#{1,6}\\s").find(rest)
            return (if (next != null) rest.substring(0, next.range.first) else rest).trim()
        }
    }

    /** The fields of a note file that can be edited by hand: title, tags and your notes. */
    internal object NoteFields {
        fun isDefaultTag(t: String, s: SettingsEntity): Boolean =
            t.equals("Book_note", true) || t.equals("Book note", true) || s.defaultTags.split(',').map { it.trim() }.any { it.equals(t, true) } ||
                (s.defaultTags.isBlank() && t.equals("Literature", true))

        fun parse(text: String, s: SettingsEntity): Map<String, String> {
            val (fm, body) = FrontMatter.parse(text)
            val out = HashMap<String, String>()
            out["title"] = FrontMatter.str(fm, "title")
            out["tags"] = FrontMatter.list(fm, "tags").filter { !isDefaultTag(it, s) }.joinToString(", ")
            val m = Regex("(?m)^#\\s*My Notes:?\\s*$").find(body)
            var notes = if (m != null) body.substring(m.range.last + 1) else ""
            // The footer the app adds: a rule and "*Part of [[Notes on …]]*".
            notes = notes.replace(Regex("(?s)\\n---\\s*\\n\\*Part of .*$"), "")
            out["notes"] = notes.trim()
            return out
        }

        fun fromApp(h: HighlightEntity, s: SettingsEntity): Map<String, String> = hashMapOf(
            "title" to h.title.trim().ifBlank { "Untitled Note" },
            "tags" to NoteSuggestionEngine.parseTagsList(h.tags).filter { !isDefaultTag(it, s) }.joinToString(", "),
            "notes" to h.userNotes.trim()
        )
    }

    const val DOC_KEY = ID_DOC
    const val NOTE_KEY = ID_NOTE
}
