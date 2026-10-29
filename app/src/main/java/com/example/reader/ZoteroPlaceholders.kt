package com.example.reader

import android.content.Context
import android.util.Log
import com.example.data.StorageManager
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Placeholders: Zotero items that are in the library without a file (file type "ZOTERO").
 *
 * A placeholder has its own folder ("<Title> (Zotero)") with a small marker file, a generated cover,
 * its notes and the Obsidian overview note. Its details follow Zotero: what changed in Zotero is
 * applied on every Zotero sync, and what the reader changed in the app is sent to Zotero. A file can
 * be added later — from Zotero or from the tablet — and the book keeps its id, notes and link, and
 * stays synced with Zotero the same way.
 *
 * What was last agreed with Zotero (per field) is kept in files/zotero_synced.json, so a change can
 * be told apart from the other side's change (a three-way merge).
 */
object ZoteroPlaceholders {
    const val FILE_TYPE = "ZOTERO"
    private const val MARKER = "zotero-item.json"
    private const val FILE = "zotero_synced.json"
    private const val TAG = "ZoteroPlaceholders"

    private data class Entry(val key: String, val snapshot: Map<String, String>, val tags: Set<String>, val localOnly: Set<String>)

    private val entries = HashMap<Long, Entry>()
    @Volatile private var loaded = false
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = HashMap<Long, Job>()

    fun isPlaceholder(doc: DocumentEntity?): Boolean = doc?.fileType.equals(FILE_TYPE, true)

    private val _hidden = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)

    /** “Hide placeholders” in the library (remembered on the tablet). */
    fun hidden(ctx: Context): kotlinx.coroutines.flow.StateFlow<Boolean?> {
        if (_hidden.value == null) _hidden.value = ctx.applicationContext.getSharedPreferences("zotero", Context.MODE_PRIVATE).getBoolean("hidePlaceholders", false)
        return _hidden
    }

    fun setHidden(ctx: Context, hide: Boolean) {
        ctx.applicationContext.getSharedPreferences("zotero", Context.MODE_PRIVATE).edit().putBoolean("hidePlaceholders", hide).apply()
        _hidden.value = hide
    }

    @Synchronized private fun ensure(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = File(ctx.applicationContext.filesDir, FILE)
        if (!f.exists()) return
        runCatching {
            val root = JSONObject(f.readText())
            root.keys().forEach { id ->
                val o = root.getJSONObject(id)
                val snap = o.optJSONObject("snapshot")?.let { s -> s.keys().asSequence().associateWith { s.optString(it) } }.orEmpty()
                fun set(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
                entries[id.toLong()] = Entry(o.optString("key"), snap, set("tags"), set("localOnly"))
            }
        }.onFailure { Log.w(TAG, "Could not read $FILE", it) }
    }

    @Synchronized private fun save(ctx: Context) {
        val root = JSONObject()
        entries.forEach { (id, e) ->
            root.put(id.toString(), JSONObject().put("key", e.key).put("snapshot", JSONObject(e.snapshot))
                .put("tags", JSONArray(e.tags.toList())).put("localOnly", JSONArray(e.localOnly.toList())))
        }
        val f = File(ctx.applicationContext.filesDir, FILE)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(root.toString(2))
        if (!tmp.renameTo(f)) { f.writeText(root.toString(2)); tmp.delete() }
    }

    /** Is this book's information kept in step with Zotero (a placeholder, or one that got its file from one)? */
    fun isSynced(ctx: Context, docId: Long): Boolean { ensure(ctx); return synchronized(this) { docId in entries } }

    /** Stops keeping the book in step with Zotero (when it is unlinked). */
    fun forget(ctx: Context, docId: Long) {
        ensure(ctx)
        synchronized(this) { if (entries.remove(docId) != null) save(ctx) }
    }

    private fun dao(ctx: Context) = AppDatabase.getInstance(ctx).documentDao()

    // ------------------------------------------------------------------ the fields that are compared

    private fun people(s: String) = People.join(People.split(s))

    private val bibKeys = BibField.entries.filter { it != BibField.TITLE && it != BibField.SUBTITLE }

    private fun zoteroValues(d: JSONObject): Map<String, String> {
        val f = Zotero.toFound(d)
        val m = LinkedHashMap<String, String>()
        m["TITLE"] = d.optString("title").trim()
        m["AUTHOR"] = people(f.authors)
        m["ABSTRACT"] = f.abstract.trim()
        bibKeys.forEach { k -> m[k.name] = f.record[k].let { if (k == BibField.EDITOR || k == BibField.TRANSLATOR) people(it) else it } }
        return m
    }

    private fun appValues(doc: DocumentEntity, rec: BibRecord): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["TITLE"] = doc.title.trim()
        m["AUTHOR"] = people(doc.author.takeUnless { it.equals("Unknown Author", true) }.orEmpty())
        m["ABSTRACT"] = doc.summary.trim()
        bibKeys.forEach { k -> m[k.name] = rec[k].let { if (k == BibField.EDITOR || k == BibField.TRANSLATOR) people(it) else it } }
        return m
    }

    private fun same(a: String?, b: String?) = a.orEmpty().trim().replace(Regex("\\s+"), " ") == b.orEmpty().trim().replace(Regex("\\s+"), " ")

    private fun tagName(s: String) = s.trim().replace(Regex("\\s+"), "_")
    private fun zoteroTags(d: JSONObject): Set<String> = (Zotero.tagsOf(d) + Zotero.collectionNames(d)).map(::tagName).filter { it.isNotBlank() }.toSet()

    /** Writes Zotero's values for [keys] into the book and its record. */
    private suspend fun applyFromZotero(ctx: Context, doc: DocumentEntity, d: JSONObject, keys: Collection<String>, newTags: Set<String>): DocumentEntity {
        if (keys.isEmpty() && newTags.isEmpty()) return doc
        val z = zoteroValues(d)
        val found = Zotero.toFound(d)
        var next = doc
        var rec = BibStore.get(doc.id)
        keys.forEach { k ->
            when (k) {
                "TITLE" -> {
                    next = next.copy(title = z["TITLE"].orEmpty().ifBlank { next.title })
                    rec = rec.with(BibField.TITLE, found.record[BibField.TITLE]).with(BibField.SUBTITLE, found.record[BibField.SUBTITLE])
                }
                "AUTHOR" -> next = next.copy(author = z["AUTHOR"].orEmpty())
                "ABSTRACT" -> next = next.copy(summary = z["ABSTRACT"].orEmpty())
                else -> runCatching { BibField.valueOf(k) }.getOrNull()?.let { f -> rec = rec.with(f, found.record[f]) }
            }
        }
        if (found.record.entryType.isNotBlank() && rec.entryType.isBlank()) rec = rec.copy(entryType = found.record.entryType)
        if (newTags.isNotEmpty()) {
            val list = NoteSuggestionEngine.parseTagsList(next.tags)
            next = next.copy(tags = NoteSuggestionEngine.joinTagsList((list + newTags).distinctBy { it.lowercase(Locale.ROOT) }))
        }
        if (next != doc) dao(ctx).updateDocument(next)
        BibStore.put(doc.id, rec)
        return next
    }

    // ------------------------------------------------------------------ making placeholders

    private fun categoryFor(itemType: String) = when (itemType) {
        "book", "thesis", "encyclopediaArticle", "dictionaryEntry", "manuscript", "map", "artwork" -> "BOOK"
        else -> "ARTICLE"
    }

    private fun folderName(title: String) = "${title.trim().ifBlank { "Untitled" }.take(120)} (Zotero)"

    data class Created(val doc: DocumentEntity?, val key: String, val skipped: String? = null)

    /** Adds the Zotero items [keys] to the library as placeholders. Items already linked to a book are skipped. */
    suspend fun create(ctx: Context, keys: List<String>): List<Created> = withContext(Dispatchers.IO) {
        ensure(ctx)
        Zotero.init(ctx)
        BibStore.init(ctx)
        val settings = AppDatabase.getInstance(ctx).settingsDao().getSettingsSnapshot()
        val custom = settings?.customStoragePath.orEmpty()
        val type = settings?.storageLocationType ?: "DOCUMENTS"
        val out = ArrayList<Created>()
        mutex.withLock {
            for (key in keys.distinct()) {
                if (Zotero.docFor(key) != null) { out += Created(null, key, "already in the library"); continue }
                val d = Zotero.item(key) ?: Zotero.refresh(ctx, key)
                if (d == null) { out += Created(null, key, "not found in Zotero"); continue }
                runCatching {
                    val z = zoteroValues(d)
                    val title = z["TITLE"].orEmpty().ifBlank { "Untitled" }
                    val author = z["AUTHOR"].orEmpty().ifBlank { People.join(Zotero.creators(d, "editor")) }
                    val cat = categoryFor(d.optString("itemType"))
                    val dirName = folderName(title)
                    val dir = StorageManager.getDocumentFolder(ctx, cat, dirName, custom, type)
                    val marker = File(dir, MARKER)
                    marker.writeText(JSONObject().put("zoteroItem", key).put("title", title)
                        .put("note", "Placeholder for a Zotero item. The book's file is not on the tablet yet.").toString(2))
                    val coverBy = author.replace("; ", ", ").ifBlank { "Zotero" }
                    val cover = runCatching {
                        StorageManager.saveCoverImage(ctx, cat, dirName, EpubParser.generateBookCoverBitmap(title, coverBy), custom, type)
                    }.getOrNull()
                    val tags = zoteroTags(d)
                    val doc = DocumentEntity(
                        title = title,
                        author = author,
                        filePath = marker.absolutePath,
                        fileType = FILE_TYPE,
                        category = cat,
                        coverUri = cover?.absolutePath,
                        totalPages = 0,
                        currentPage = 1,
                        lastReadTimestamp = 0L,
                        summary = z["ABSTRACT"].orEmpty(),
                        tags = NoteSuggestionEngine.joinTagsList(tags.toList()),
                        coverAspectRatio = 2f / 3f
                    )
                    val id = dao(ctx).insertDocument(doc)
                    val found = Zotero.toFound(d)
                    BibStore.put(id, found.record)
                    Zotero.link(ctx, id, key)
                    synchronized(this@ZoteroPlaceholders) { entries[id] = Entry(key, z, tags, emptySet()); save(ctx) }
                    val created = dao(ctx).getDocumentById(id) ?: doc.copy(id = id)
                    // Zotero has no citation key yet: give it the app's, so both cite with the same key.
                    if (Zotero.citationKey(d).isBlank() && Zotero.account(ctx)?.canWrite == true) runCatching {
                        val ck = Citations.citeKey(created, BibStore.get(id))
                        Zotero.push(ctx, key, mapOf("CITEKEY" to ck))
                        BibStore.put(id, BibStore.get(id).with(BibField.CITEKEY, ck))
                        synchronized(this@ZoteroPlaceholders) { entries[id]?.let { e -> entries[id] = e.copy(snapshot = e.snapshot + ("CITEKEY" to ck)) }; save(ctx) }
                    }
                    out += Created(created, key)
                }.onFailure { e -> Log.w(TAG, "Placeholder for $key failed", e); out += Created(null, key, e.message ?: "failed") }
            }
        }
        out
    }

    // ------------------------------------------------------------------ keeping them in step

    data class SyncResult(val updated: Int, val sent: Int, val failed: Int)

    /** Three-way merge of every synced book with its Zotero item. Zotero's change wins over the app's on the same field. */
    suspend fun syncAll(ctx: Context): SyncResult = withContext(Dispatchers.IO) {
        ensure(ctx)
        val ids = synchronized(this@ZoteroPlaceholders) { entries.keys.toList() }
        var updated = 0; var sent = 0; var failed = 0
        ids.forEach { id ->
            val r = runCatching { syncOne(ctx, id) }.getOrElse { Log.w(TAG, "Sync of $id failed", it); failed++; null }
            if (r != null) { updated += r.updated; sent += r.sent; failed += r.failed }
        }
        SyncResult(updated, sent, failed)
    }

    suspend fun syncOne(ctx: Context, docId: Long): SyncResult = withContext(Dispatchers.IO) {
        ensure(ctx)
        Zotero.init(ctx)
        BibStore.init(ctx)
        mutex.withLock {
            val e = synchronized(this@ZoteroPlaceholders) { entries[docId] } ?: return@withLock SyncResult(0, 0, 0)
            val doc = dao(ctx).getDocumentById(docId)
            if (doc == null || Zotero.linkOf(docId) != e.key) {
                synchronized(this@ZoteroPlaceholders) { entries.remove(docId); save(ctx) }
                return@withLock SyncResult(0, 0, 0)
            }
            val d = Zotero.item(e.key) ?: return@withLock SyncResult(0, 0, 0)   // removed from Zotero: the book is kept as it is
            val z = zoteroValues(d)
            val a = appValues(doc, BibStore.get(docId))
            val fromZotero = ArrayList<String>()
            val toZotero = LinkedHashMap<String, String>()
            z.keys.forEach { k ->
                val s = e.snapshot[k]
                when {
                    !same(z[k], s) -> if (!same(a[k], z[k])) fromZotero += k
                    !same(a[k], s) && k !in e.localOnly -> toZotero[k] = a[k].orEmpty()
                    k in e.localOnly && !same(a[k], s) -> Unit   // kept in the app only (Zotero has no field for it)
                }
            }
            val zt = zoteroTags(d)
            val newTags = zt - e.tags
            var failed = 0
            var localOnly = e.localOnly
            var item = d
            if (toZotero.isNotEmpty() && Zotero.account(ctx)?.canWrite == true) {
                runCatching {
                    val skipped = Zotero.push(ctx, e.key, toZotero)
                    localOnly = localOnly + skipped
                    item = Zotero.item(e.key) ?: d
                }.onFailure { Log.w(TAG, "Sending ${toZotero.keys} failed", it); failed = 1 }
            } else if (toZotero.isNotEmpty()) failed = 1
            applyFromZotero(ctx, doc, item, fromZotero, newTags)
            // What both sides now agree on. Fields that could not be sent keep the old value, so they are tried again.
            val agreed = zoteroValues(item).toMutableMap()
            if (failed == 1) toZotero.keys.forEach { k -> e.snapshot[k]?.let { agreed[k] = it } }
            localOnly.forEach { k -> if (k in toZotero) agreed[k] = z[k].orEmpty() }
            synchronized(this@ZoteroPlaceholders) { entries[docId] = Entry(e.key, agreed, e.tags + zt, localOnly); save(ctx) }
            if (fromZotero.isNotEmpty() || newTags.isNotEmpty()) refreshCover(ctx, docId)
            SyncResult(if (fromZotero.isNotEmpty() || newTags.isNotEmpty()) 1 else 0, if (toZotero.isNotEmpty() && failed == 0) 1 else 0, failed)
        }
    }

    /** After a change in the app: sends it to Zotero shortly after (several quick edits go together). */
    fun pushSoon(ctx: Context, docId: Long) {
        ensure(ctx)
        if (!isSynced(ctx, docId)) return
        val app = ctx.applicationContext
        synchronized(pending) {
            pending[docId]?.cancel()
            pending[docId] = scope.launch {
                delay(1500)
                runCatching { syncOne(app, docId) }.onFailure { Log.w(TAG, "Sending to Zotero failed", it) }
                synchronized(pending) { pending.remove(docId) }
            }
        }
    }

    /** A placeholder's generated cover shows its title and authors; draw it again when they change. */
    private suspend fun refreshCover(ctx: Context, docId: Long) {
        val doc = dao(ctx).getDocumentById(docId) ?: return
        if (!isPlaceholder(doc)) return
        val cover = doc.coverUri?.let { File(it) } ?: return
        val dir = File(doc.filePath).parentFile ?: return
        if (cover.parentFile?.canonicalPath != dir.canonicalPath) return
        runCatching {
            val bmp = EpubParser.generateBookCoverBitmap(doc.title, doc.author.replace("; ", ", ").ifBlank { "Zotero" })
            cover.outputStream().use { o ->
                @Suppress("DEPRECATION")
                val fmt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) android.graphics.Bitmap.CompressFormat.WEBP_LOSSY else android.graphics.Bitmap.CompressFormat.WEBP
                bmp.compress(fmt, 90, o)
            }
        }
    }

    // ------------------------------------------------------------------ adding the file

    /**
     * Puts [file] (a PDF, EPUB, HTML, Markdown or text file) into placeholder [docId]: the book keeps
     * its id, details, notes and Zotero link, and from now on opens like any other book. [file] is
     * copied; the caller removes its own copy.
     */
    suspend fun attach(ctx: Context, docId: Long, file: File, originalName: String): DocumentEntity = withContext(Dispatchers.IO) {
        val doc = dao(ctx).getDocumentById(docId) ?: throw NoSuchElementException("This book is no longer in the library.")
        require(isPlaceholder(doc)) { "This book already has its file." }
        val settings = AppDatabase.getInstance(ctx).settingsDao().getSettingsSnapshot()
        val custom = settings?.customStoragePath.orEmpty()
        val storage = settings?.storageLocationType ?: "DOCUMENTS"

        val header = ByteArray(4).also { b -> file.inputStream().use { it.read(b) } }
        val isPdf = header[0] == '%'.code.toByte() && header[1] == 'P'.code.toByte() && header[2] == 'D'.code.toByte() && header[3] == 'F'.code.toByte()
        val isZip = header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
        val ext = originalName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val fileType = when {
            isPdf || ext == "pdf" -> "PDF"
            ext == "zip" && isZip && DaisyParserEngine.isDaisyZipArchive(file) -> "DAISY"
            ext == "epub" || isZip -> "EPUB"
            ext == "md" || ext == "markdown" -> "MD"
            ext == "html" || ext == "htm" || ext == "xhtml" -> "HTML"
            ext == "txt" -> "TXT"
            else -> throw IllegalArgumentException("“$originalName” is not a PDF, EPUB, DAISY, HTML, Markdown or text file.")
        }
        val fileExt = when (fileType) { "PDF" -> "pdf"; "DAISY" -> "zip"; "EPUB" -> "epub"; "MD" -> "md"; "HTML" -> "html"; else -> "txt" }

        // The placeholder's folder becomes the book's folder: "<Title>" when that name is free, else as it is.
        val marker = File(doc.filePath)
        var dir = marker.parentFile ?: throw IllegalStateException("The placeholder's folder is missing on the tablet.")
        val wanted = File(dir.parentFile, StorageManager.sanitizeName(doc.title).ifBlank { dir.name })
        var coverPath = doc.coverUri
        if (wanted.isDirectory && wanted.listFiles().isNullOrEmpty()) wanted.delete()
        if (!wanted.exists() && dir.renameTo(wanted)) {
            coverPath = coverPath?.let { c -> File(c).takeIf { it.parentFile?.absolutePath == dir.absolutePath }?.let { File(wanted, it.name).absolutePath } ?: c }
            dir = wanted
        }
        File(dir, MARKER).delete()
        val base = StorageManager.sanitizeName(originalName.substringBeforeLast('.')).ifBlank { StorageManager.sanitizeName(doc.title).ifBlank { "book" } }
        val target = File(dir, "$base.$fileExt")
        file.copyTo(target, overwrite = true, bufferSize = 256 * 1024)

        var pages = 1
        var bmp: android.graphics.Bitmap? = null
        runCatching {
            when (fileType) {
                "PDF" -> { val r = PdfDocumentRenderer(target, ctx); pages = r.pageCount.coerceAtLeast(1); bmp = r.renderPage(0, targetWidth = 400); r.close() }
                "EPUB" -> target.inputStream().use { s -> val e = EpubParser.parseEpub(s); pages = EpubParser.paginateBook(e).first.size.coerceAtLeast(1); bmp = e.coverBitmap }
                "DAISY" -> { val meta = DaisyParserEngine.peekDaisyZipMetadata(zipFile = target, fallbackTitle = doc.title, fallbackAuthor = doc.author); pages = meta.estimatedPages.coerceAtLeast(1); bmp = meta.coverBitmap }
                "HTML" -> { val p = EpubParser.parseHtmlDocument(rawHtml = target.readText(Charsets.UTF_8), fallbackTitle = doc.title, fallbackAuthor = doc.author); pages = EpubParser.paginateBook(p).first.size.coerceAtLeast(1) }
                else -> pages = (target.readLines().count { it.isNotBlank() } / 4).coerceAtLeast(1)
            }
        }.onFailure { Log.w(TAG, "Reading the new file failed", it) }
        // The file's own cover replaces the generated one.
        bmp?.let { b ->
            runCatching { StorageManager.saveCoverImage(ctx, doc.category, dir.name, b, custom, storage) }.getOrNull()?.let { coverPath = it.absolutePath }
        }
        val minutes = runCatching { ReadingTimeEstimator.calculateReadingTimeMinutes(file = target, fileType = fileType, totalPages = pages, category = doc.category, context = ctx) }.getOrDefault(0)
        val ratio = StorageManager.decodeCoverAspectRatio(coverPath)
        val next = doc.copy(
            filePath = target.absolutePath, fileType = fileType, totalPages = pages, currentPage = 1, coverUri = coverPath,
            coverAspectRatio = if (ratio > 0f) ratio else doc.coverAspectRatio,
            estimatedReadingMinutes = if (minutes > 0) minutes else doc.estimatedReadingMinutes,
            lastReadTimestamp = System.currentTimeMillis()
        )
        dao(ctx).updateDocument(next)
        runCatching { com.example.widget.PinnedBooksWidgetProvider.refreshAllWidgets(ctx) }
        next
    }

    /** Fetches the PDF/EPUB attached in Zotero and adds it to placeholder [docId]. Returns the book and the attachment used. */
    suspend fun fetchFromZotero(ctx: Context, docId: Long, attachmentKey: String?): Pair<DocumentEntity, Zotero.Attachment> = withContext(Dispatchers.IO) {
        val doc = dao(ctx).getDocumentById(docId) ?: throw NoSuchElementException("This book is no longer in the library.")
        val key = Zotero.linkOf(docId) ?: throw IllegalArgumentException("This book is not linked to a Zotero item.")
        val atts = Zotero.attachments(ctx, key)
        val att = (if (attachmentKey.isNullOrBlank()) atts.firstOrNull { it.downloadable } else atts.firstOrNull { it.key == attachmentKey })
            ?: throw NoSuchElementException(if (atts.isEmpty()) "This item has no PDF or EPUB in Zotero." else "Zotero keeps no copy of this item's file online (it is linked on your computer or stored with WebDAV).")
        val tmpDir = File(ctx.cacheDir, "zotero-files/${System.nanoTime()}")
        try {
            val f = Zotero.downloadAttachment(ctx, att, tmpDir)
            attach(ctx, doc.id, f, f.name) to att
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
