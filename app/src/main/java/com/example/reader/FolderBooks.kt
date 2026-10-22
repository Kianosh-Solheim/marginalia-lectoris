package com.example.reader

import android.content.Context
import com.example.data.StorageManager
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Folder books: a book (for example an edited volume) made of several files, one per chapter.
 *
 * The folder book is a library item of its own (file type "FOLDER") with its own title, editors,
 * cover and tags; its chapter files are ordinary library items that are hidden from the shelf and
 * shown inside the folder book instead. Each chapter keeps its own notes, progress and author, and
 * is cited as a chapter in the book. The order of the chapters is kept in files/folder_books.json
 * (included in backups); a copy of the list is also written next to the folder book's cover.
 */
object FolderBooks {
    const val FILE_TYPE = "FOLDER"
    private const val FILE = "folder_books.json"
    private const val MARKER = "folder-book.json"

    /** What the reader last read in a folder book (for widgets and the “Continue” button). */
    data class Reading(val partId: Long, val number: Int, val title: String, val author: String, val progress: Float)
    data class Entry(val parts: List<Long> = emptyList(), val reading: Reading? = null)

    private val cache = HashMap<Long, Entry>()
    @Volatile private var loaded = false
    @Volatile private var file: File? = null
    @Volatile var appContext: Context? = null
        private set

    private val _version = MutableStateFlow(0)
    /** Bumped on every change, so lists that hide chapter files update. */
    val version: StateFlow<Int> = _version.asStateFlow()

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
                val arr = o.optJSONArray("parts") ?: JSONArray()
                val parts = (0 until arr.length()).map { arr.getLong(it) }
                val r = o.optJSONObject("reading")?.let {
                    Reading(it.optLong("id"), it.optInt("n"), it.optString("title"), it.optString("author"), it.optDouble("p", 0.0).toFloat())
                }
                cache[id.toLong()] = Entry(parts, r)
            }
        }
    }

    @Synchronized fun reload(context: Context) { cache.clear(); loaded = false; ensure(context); _version.value++ }

    @Synchronized private fun save() {
        val f = file ?: return
        val root = JSONObject()
        cache.forEach { (id, e) ->
            val o = JSONObject().put("parts", JSONArray(e.parts))
            e.reading?.let { r -> o.put("reading", JSONObject().put("id", r.partId).put("n", r.number).put("title", r.title).put("author", r.author).put("p", r.progress.toDouble())) }
            root.put(id.toString(), o)
        }
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(root.toString(2))
        if (!tmp.renameTo(f)) { f.writeText(root.toString(2)); tmp.delete() }
        _version.value++
    }

    private fun ctx(): Context? = appContext ?: BibStore.appContext

    fun isFolder(doc: DocumentEntity?): Boolean = doc?.fileType.equals(FILE_TYPE, true)

    @Synchronized fun partsOf(folderId: Long): List<Long> { ctx()?.let { ensure(it) }; return cache[folderId]?.parts.orEmpty() }

    @Synchronized fun readingOf(folderId: Long): Reading? { ctx()?.let { ensure(it) }; return cache[folderId]?.reading }

    @Synchronized fun folderOf(partId: Long): Long? {
        ctx()?.let { ensure(it) }
        return cache.entries.firstOrNull { partId in it.value.parts }?.key
    }

    /** Ids of chapter files (hidden from the shelf). */
    @Synchronized fun hiddenIds(): Set<Long> { ctx()?.let { ensure(it) }; return cache.values.flatMap { it.parts }.toSet() }

    /** The library without the chapter files of folder books. */
    fun visible(docs: List<DocumentEntity>): List<DocumentEntity> {
        val hidden = hiddenIds()
        return if (hidden.isEmpty()) docs else docs.filter { it.id !in hidden }
    }

    /** Previous and next chapter of [partId] in its folder book. */
    fun neighbours(partId: Long): Triple<Long, Long?, Long?>? {
        val folder = folderOf(partId) ?: return null
        val parts = partsOf(folder)
        val i = parts.indexOf(partId)
        if (i < 0) return null
        return Triple(folder, parts.getOrNull(i - 1), parts.getOrNull(i + 1))
    }

    // ------------------------------------------------------------------ changes

    private fun dao(context: Context) = AppDatabase.getInstance(context).documentDao()

    private fun folderDirTitle(title: String) = "$title (folder book)"

    /** Creates an empty folder book. [editors] are “Name Nameson; Other Person”. */
    suspend fun create(context: Context, title: String, editors: String, category: String = "BOOK"): DocumentEntity = withContext(Dispatchers.IO) {
        ensure(context)
        val settings = AppDatabase.getInstance(context).settingsDao().getSettingsSnapshot()
        val custom = settings?.customStoragePath.orEmpty()
        val type = settings?.storageLocationType ?: "DOCUMENTS"
        val cleanTitle = title.trim().ifBlank { "Untitled book" }
        val dir = StorageManager.getDocumentFolder(context, category, folderDirTitle(cleanTitle), custom, type)
        val marker = File(dir, MARKER)
        if (!marker.exists()) marker.writeText(JSONObject().put("title", cleanTitle).put("parts", JSONArray()).toString(2))
        val names = editors.split(';', ',').map { it.trim() }.filter { it.isNotBlank() }.joinToString("; ")
        val cover = runCatching {
            StorageManager.saveCoverImage(context, category, folderDirTitle(cleanTitle),
                EpubParser.generateBookCoverBitmap(cleanTitle, if (names.isBlank()) "" else names.replace("; ", ", ") + " (ed.)"), custom, type)
        }.getOrNull()
        val doc = DocumentEntity(
            title = cleanTitle,
            author = names.replace("; ", ", "),
            filePath = marker.absolutePath,
            fileType = FILE_TYPE,
            category = category,
            coverUri = cover?.absolutePath,
            totalPages = 0,
            currentPage = 1,
            lastReadTimestamp = System.currentTimeMillis(),
            coverAspectRatio = 2f / 3f
        )
        val id = dao(context).insertDocument(doc)
        BibStore.init(context)
        if (names.isNotBlank()) BibStore.put(id, BibStore.get(id).with(BibField.EDITOR, names).with(BibField.TITLE, cleanTitle))
        synchronized(this@FolderBooks) { cache[id] = Entry(); save() }
        dao(context).getDocumentById(id) ?: doc.copy(id = id)
    }

    /** Adds library items as chapters (at the end, in the given order). A chapter can be in one folder book only. */
    suspend fun addParts(context: Context, folderId: Long, partIds: List<Long>) = withContext(Dispatchers.IO) {
        ensure(context)
        val folder = dao(context).getDocumentById(folderId) ?: return@withContext
        val ids = partIds.filter { it != folderId }
        synchronized(this@FolderBooks) {
            cache.keys.toList().forEach { k -> if (k != folderId) cache[k] = cache[k]!!.let { e -> e.copy(parts = e.parts - ids.toSet()) } }
            val cur = cache[folderId] ?: Entry()
            cache[folderId] = cur.copy(parts = cur.parts + ids.filter { it !in cur.parts })
            save()
        }
        BibStore.init(context)
        val bookRec = BibStore.get(folderId)
        ids.forEach { id ->
            val part = dao(context).getDocumentById(id) ?: return@forEach
            if (isFolder(part)) return@forEach
            // Cite the chapter as a chapter of this book, keeping what was already filled in.
            var r = BibStore.get(id)
            fun fill(f: BibField, v: String) { if (v.isNotBlank() && r[f].isBlank()) r = r.with(f, v) }
            fill(BibField.BOOKTITLE, folder.title)
            fill(BibField.EDITOR, EditedVolumes.editors(folder))
            fill(BibField.PUBLISHER, bookRec[BibField.PUBLISHER])
            fill(BibField.PLACE, bookRec[BibField.PLACE])
            fill(BibField.YEAR, bookRec[BibField.YEAR])
            fill(BibField.ISBN, bookRec[BibField.ISBN])
            fill(BibField.EDITION, bookRec[BibField.EDITION])
            fill(BibField.SERIES, bookRec[BibField.SERIES])
            if (r.entryType.isBlank()) r = r.copy(entryType = "incollection")
            BibStore.put(id, r)
        }
        sync(context, folderId)
    }

    /** Takes a chapter out of its folder book (it is shown on the shelf again). */
    suspend fun removePart(context: Context, folderId: Long, partId: Long) = withContext(Dispatchers.IO) {
        ensure(context)
        synchronized(this@FolderBooks) {
            val cur = cache[folderId] ?: return@withContext
            cache[folderId] = cur.copy(parts = cur.parts - partId, reading = cur.reading?.takeIf { it.partId != partId })
            save()
        }
        sync(context, folderId)
    }

    /** Moves a chapter up (-1) or down (+1). */
    suspend fun move(context: Context, folderId: Long, partId: Long, delta: Int) = withContext(Dispatchers.IO) {
        ensure(context)
        synchronized(this@FolderBooks) {
            val cur = cache[folderId] ?: return@withContext
            val list = cur.parts.toMutableList()
            val i = list.indexOf(partId)
            val j = (i + delta).coerceIn(0, list.size - 1)
            if (i < 0 || i == j) return@withContext
            list.removeAt(i); list.add(j, partId)
            cache[folderId] = cur.copy(parts = list)
            save()
        }
        sync(context, folderId)
    }

    /** Called when a library item is deleted: a folder book frees its chapters; a chapter leaves its book. */
    fun forgetDocument(context: Context, docId: Long) {
        ensure(context)
        synchronized(this) {
            var changed = cache.remove(docId) != null
            cache.keys.toList().forEach { k ->
                val e = cache[k]!!
                if (docId in e.parts) { cache[k] = e.copy(parts = e.parts - docId, reading = e.reading?.takeIf { it.partId != docId }); changed = true }
            }
            if (changed) save()
        }
    }

    /** Edits the folder book's own details (its folder on disk stays where it is). */
    suspend fun updateInfo(context: Context, folderId: Long, title: String, editors: String, tags: String, summary: String) = withContext(Dispatchers.IO) {
        val d = dao(context).getDocumentById(folderId) ?: return@withContext
        val names = editors.split(';').map { it.trim() }.filter { it.isNotBlank() }.joinToString("; ")
        val cleanTags = NoteSuggestionEngine.joinTagsList(NoteSuggestionEngine.parseTagsList(tags))
        val newTitle = title.trim().ifBlank { d.title }
        dao(context).updateDocument(d.copy(title = newTitle, author = names.replace("; ", ", "), tags = cleanTags, summary = summary.trim()))
        BibStore.init(context)
        BibStore.put(folderId, BibStore.get(folderId).with(BibField.EDITOR, names).with(BibField.TITLE, newTitle))
        // Chapters cite the book under its new title and editors.
        partsOf(folderId).forEach { id ->
            var r = BibStore.get(id)
            if (r[BibField.BOOKTITLE].isBlank() || r[BibField.BOOKTITLE] == d.title) r = r.with(BibField.BOOKTITLE, newTitle)
            if (names.isNotBlank()) r = r.with(BibField.EDITOR, names)
            BibStore.put(id, r)
        }
        sync(context, folderId)
    }

    /**
     * Recomputes the folder book's pages, progress and last-read time from its chapters, and what
     * was read last. Called after a chapter's reading progress changes.
     */
    suspend fun sync(context: Context, folderId: Long) = withContext(Dispatchers.IO) {
        ensure(context)
        val dao = dao(context)
        val folder = dao.getDocumentById(folderId) ?: return@withContext
        val ids = partsOf(folderId)
        val parts = ids.mapNotNull { dao.getDocumentById(it) }
        val total = parts.sumOf { it.totalPages.coerceAtLeast(1) }
        val read = parts.sumOf { (it.readingProgress.coerceIn(0f, 1f) * it.totalPages.coerceAtLeast(1)).toDouble() }
        val progress = if (total > 0) (read / total).toFloat().coerceIn(0f, 1f) else 0f
        val last = parts.filter { it.readingProgress > 0f || it.currentPage > 1 }.maxByOrNull { it.lastReadTimestamp }
        val lastRead = maxOf(folder.lastReadTimestamp, last?.lastReadTimestamp ?: 0L)
        val reading = last?.let { Reading(it.id, ids.indexOf(it.id) + 1, it.title, it.author, it.readingProgress) }
        synchronized(this@FolderBooks) {
            val cur = cache[folderId] ?: Entry(ids)
            cache[folderId] = cur.copy(reading = reading)
            save()
        }
        val pagesBefore = last?.let { l -> parts.takeWhile { it.id != l.id }.sumOf { it.totalPages.coerceAtLeast(1) } } ?: 0
        dao.updateDocument(folder.copy(
            totalPages = total.coerceAtLeast(1),
            currentPage = (pagesBefore + (last?.currentPage ?: 1)).coerceAtLeast(1),
            readingProgress = progress,
            lastReadTimestamp = lastRead,
            lastPositionChapter = last?.title.orEmpty(),
            lastPositionLabel = last?.lastPositionLabel.orEmpty(),
            coverUri = folder.coverUri?.takeIf { File(it).isFile } ?: parts.firstOrNull()?.coverUri
        ))
        writeMarker(folder, parts)
        runCatching { com.example.widget.PinnedBooksWidgetProvider.refreshAllWidgets(context) }
        runCatching { com.example.widget.ContinueReadingWidgetProvider.refreshAllThrottled(context) }
    }

    private fun writeMarker(folder: DocumentEntity, parts: List<DocumentEntity>) = runCatching {
        val f = File(folder.filePath)
        if (f.parentFile?.isDirectory != true) return@runCatching
        f.writeText(JSONObject()
            .put("title", folder.title)
            .put("editors", folder.author)
            .put("parts", JSONArray().apply { parts.forEach { p -> put(JSONObject().put("title", p.title).put("author", p.author).put("file", p.filePath)) } })
            .toString(2))
    }

    /** The chapter to open for “Continue”: the one read last, else the first. */
    suspend fun resumePart(context: Context, folderId: Long): DocumentEntity? = withContext(Dispatchers.IO) {
        val dao = dao(context)
        val r = readingOf(folderId)
        r?.let { dao.getDocumentById(it.partId) } ?: partsOf(folderId).firstNotNullOfOrNull { dao.getDocumentById(it) }
    }
}

/** Exports a whole folder book: each chapter's notes, overview, BibTeX and file, plus a contents note. */
object FolderBookExporter {
    suspend fun export(context: Context, folderId: Long): File = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val folder = db.documentDao().getDocumentById(folderId) ?: error("This folder book no longer exists.")
        val settings = db.settingsDao().getSettingsSnapshot() ?: com.example.data.local.SettingsEntity()
        val parts = FolderBooks.partsOf(folderId).mapNotNull { db.documentDao().getDocumentById(it) }
        BibStore.init(context)
        val root = MarkdownExporter.sanitizeFilename(folder.title)
        val out = File(File(context.cacheDir, "exports").apply { mkdirs() }, "$root - all chapters.zip")
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(out)).use { zos ->
            fun text(name: String, content: String) {
                zos.putNextEntry(java.util.zip.ZipEntry("$root/$name")); zos.write(content.toByteArray(Charsets.UTF_8)); zos.closeEntry()
            }
            val contents = StringBuilder()
            contents.append("# ").append(folder.title).append("\n\n")
            val editors = EditedVolumes.editors(folder)
            if (editors.isNotBlank()) contents.append("Edited by ").append(editors.replace("; ", ", ")).append("\n\n")
            if (folder.tags.isNotBlank()) contents.append(NoteSuggestionEngine.parseTagsList(folder.tags).joinToString(" ") { "#$it" }).append("\n\n")
            if (folder.summary.isNotBlank()) contents.append(folder.summary).append("\n\n")
            contents.append("## Contents\n\n")
            val bib = StringBuilder(Citations.bibtex(folder, BibStore.get(folder.id))).append("\n")
            parts.forEachIndexed { i, p ->
                val hls = db.highlightDao().getHighlightsListForDocument(p.id)
                val sub = "%02d - %s".format(i + 1, MarkdownExporter.sanitizeFilename(p.title))
                val moc = MarkdownExporter.sanitizeFilename(MarkdownExporter.getMocTitle(p.title, settings))
                contents.append(i + 1).append(". [[").append(moc).append("|").append(p.title).append("]]")
                if (p.author.isNotBlank()) contents.append(" — ").append(p.author)
                contents.append(" (").append(hls.size).append(" notes)\n")
                bib.append(Citations.bibtex(p, BibStore.get(p.id))).append("\n")
                val bundle = runCatching { MarkdownExporter.createCompleteDocumentBundleZip(context, p, hls, settings).outputFileOrFolder }.getOrNull() ?: return@forEachIndexed
                java.util.zip.ZipFile(bundle).use { z ->
                    z.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
                        val name = e.name.substringAfter('/', e.name)
                        zos.putNextEntry(java.util.zip.ZipEntry("$root/$sub/$name"))
                        z.getInputStream(e).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                bundle.delete()
            }
            text("${MarkdownExporter.sanitizeFilename(folder.title)} – contents.md", contents.toString())
            text("${MarkdownExporter.sanitizeFilename(folder.title)}.bib", bib.toString())
        }
        out
    }
}
