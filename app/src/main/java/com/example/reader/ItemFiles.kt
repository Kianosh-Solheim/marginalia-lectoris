package com.example.reader

import android.content.Context
import android.graphics.BitmapFactory
import com.example.data.StorageManager
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * The files of one item: everything in its folder (Readmark/Books|Articles/<Title>/), such as the
 * main file, other editions (an EPUB beside the PDF), the cover, the notes Markdown and recordings.
 *
 * The **main file** is the one "Read" opens (the document's filePath). Another readable file can be
 * made the main file; each file keeps its own reading place, chapters and PDF settings, and each note
 * remembers the file it was made in, so all notes stay in one list and still open in the right file.
 * Nothing is erased: deleted files are moved to Readmark/_to_delete/.
 */
object ItemFiles {
    val readable = setOf("pdf", "epub", "txt", "html", "htm", "xhtml", "zip")
    val images = setOf("jpg", "jpeg", "png", "webp", "gif")

    data class Entry(val rel: String, val name: String, val size: Long, val modified: Long, val isDir: Boolean,
                     val isMain: Boolean, val isCover: Boolean, val readable: Boolean, val image: Boolean, val kind: String)

    private fun storeFile(ctx: Context) = File(ctx.filesDir, "item_files.json")
    private var store: JSONObject? = null
    @Synchronized private fun store(ctx: Context): JSONObject = store ?: runCatching { JSONObject(storeFile(ctx).readText()) }.getOrDefault(JSONObject()).also { store = it }
    @Synchronized private fun save(ctx: Context) { runCatching { val f = storeFile(ctx); val t = File(f.parentFile, f.name + ".tmp"); t.writeText(store(ctx).toString()); t.renameTo(f) } }
    @Synchronized private fun item(ctx: Context, docId: Long): JSONObject = store(ctx).optJSONObject(docId.toString()) ?: JSONObject().also { store(ctx).put(docId.toString(), it) }

    fun isFileItem(d: DocumentEntity) = !d.fileType.equals("FOLDER", true) && !d.fileType.equals("ZOTERO", true)

    /** The item's own folder (made when the main file lies directly in Books/ or Articles/). */
    suspend fun folderOf(ctx: Context, d: DocumentEntity): File {
        val main = File(d.filePath)
        val parent = main.parentFile
        if (isFileItem(d) && parent != null && parent.name != "Books" && parent.name != "Articles") return parent
        val s = AppDatabase.getInstance(ctx).settingsDao().getSettingsSnapshot()
        return StorageManager.getDocumentFolder(ctx, d.category, d.title, s?.customStoragePath.orEmpty(), s?.storageLocationType ?: "DOCUMENTS")
    }

    private fun kindOf(f: File): String {
        val e = f.extension.lowercase()
        return when {
            f.isDirectory -> "folder"
            e == "pdf" -> "PDF"
            e == "epub" -> "EPUB"
            e == "zip" -> "ZIP"
            e in setOf("md", "markdown") -> "Markdown"
            e == "txt" -> "Text"
            e in setOf("html", "htm", "xhtml") -> "HTML"
            e in images -> "Image"
            e in setOf("m4a", "mp3", "wav", "ogg", "opus", "aac") -> "Audio"
            e in setOf("json", "bib", "ris") -> e.uppercase()
            e in setOf("docx", "doc", "odt") -> "Document"
            else -> e.uppercase().ifBlank { "File" }
        }
    }

    /** Everything in the item's folder (and its sub-folders), the main file first. */
    suspend fun list(ctx: Context, d: DocumentEntity): List<Entry> {
        val root = folderOf(ctx, d)
        val main = runCatching { File(d.filePath).canonicalFile }.getOrNull()
        val cover = d.coverUri?.takeIf { it.isNotBlank() }?.let { runCatching { File(it).canonicalFile }.getOrNull() }
        val out = ArrayList<Entry>()
        fun walk(dir: File, prefix: String, depth: Int) {
            dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.forEach { f ->
                if (f.name.startsWith(".") || f.name.endsWith(".tmp") || f.name.endsWith(".part")) return@forEach
                val rel = prefix + f.name
                val canon = runCatching { f.canonicalFile }.getOrNull()
                val e = f.extension.lowercase()
                out += Entry(rel, f.name, if (f.isFile) f.length() else 0L, f.lastModified(), f.isDirectory, canon == main, canon == cover,
                    f.isFile && e in readable, f.isFile && e in images, kindOf(f))
                if (f.isDirectory && depth < 3) walk(f, "$rel/", depth + 1)
            }
        }
        if (root.isDirectory) walk(root, "", 0)
        // The cover may be kept outside the folder (the app's cover cache).
        if (cover != null && cover.isFile && out.none { it.isCover }) out += Entry("@cover", cover.name, cover.length(), cover.lastModified(), false, false, true, false, true, "Image")
        return out.sortedWith(compareBy({ !it.isMain }, { it.rel.contains('/') }, { it.rel.lowercase() }))
    }

    /** The file at [rel] inside the item's folder (never outside it). */
    suspend fun resolve(ctx: Context, d: DocumentEntity, rel: String): File {
        if (rel == "@cover") return File(d.coverUri ?: error("No cover"))
        val root = folderOf(ctx, d).canonicalFile
        val f = File(root, rel).canonicalFile
        require(f.path.startsWith(root.path + File.separator) || f == root) { "Not in the item's folder" }
        return f
    }

    private fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().trimStart('.').ifBlank { "file" }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name); var n = 2
        val base = name.substringBeforeLast('.', name); val ext = name.substringAfterLast('.', "").let { if (it.isNotEmpty() && it != name) ".$it" else "" }
        while (f.exists()) { f = File(dir, "$base ($n)$ext"); n++ }
        return f
    }

    /** Adds a file to the item's folder (a new name if one is taken). */
    suspend fun add(ctx: Context, d: DocumentEntity, name: String, input: InputStream): File {
        val dir = folderOf(ctx, d).apply { mkdirs() }
        val target = unique(dir, safeName(name))
        val tmp = File(dir, ".${target.name}.part")
        tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
        check(tmp.length() > 0) { "The file was empty" }
        tmp.renameTo(target)
        return target
    }

    /** Adds a file by moving it from elsewhere (for "attach to an existing item" on import). */
    suspend fun addByMove(ctx: Context, d: DocumentEntity, src: File): File {
        val dir = folderOf(ctx, d).apply { mkdirs() }
        val target = unique(dir, safeName(src.name))
        if (!src.renameTo(target)) { src.copyTo(target); src.delete() }
        return target
    }

    /** Renames a file; the main file and the cover stay connected. */
    suspend fun rename(ctx: Context, d: DocumentEntity, rel: String, newName: String): DocumentEntity {
        val f = resolve(ctx, d, rel)
        require(rel != "@cover") { "The cover kept by the app cannot be renamed" }
        val target = File(f.parentFile, safeName(newName))
        require(!target.exists()) { "A file named ${target.name} is already there" }
        val wasMain = runCatching { File(d.filePath).canonicalFile == f }.getOrDefault(false)
        val wasCover = runCatching { d.coverUri != null && File(d.coverUri).canonicalFile == f }.getOrDefault(false)
        check(f.renameTo(target)) { "Could not rename the file" }
        // Notes and saved places refer to files by name.
        renameKey(ctx, d.id, f.name, target.name)
        var nd = d
        if (wasMain) nd = nd.copy(filePath = target.absolutePath)
        if (wasCover) nd = nd.copy(coverUri = target.absolutePath)
        if (nd != d) AppDatabase.getInstance(ctx).documentDao().updateDocument(nd)
        return nd
    }

    @Synchronized private fun renameKey(ctx: Context, docId: Long, old: String, new: String) {
        val it = item(ctx, docId)
        it.optJSONObject("pos")?.let { p -> p.opt(old)?.let { v -> p.remove(old); p.put(new, v) } }
        it.optJSONObject("notes")?.let { n -> n.keys().asSequence().toList().forEach { k -> if (n.optString(k) == old) n.put(k, new) } }
        save(ctx)
    }

    /** Moves a file to Readmark/_to_delete/<item>/ (the main file cannot be deleted). */
    suspend fun delete(ctx: Context, d: DocumentEntity, rel: String) {
        val f = resolve(ctx, d, rel)
        require(rel != "@cover") { "The cover kept by the app cannot be deleted here" }
        require(runCatching { File(d.filePath).canonicalFile != f }.getOrDefault(true)) { "This is the main file. Make another file the main file first." }
        val s = AppDatabase.getInstance(ctx).settingsDao().getSettingsSnapshot()
        val root = StorageManager.getRootDirectory(ctx, s?.customStoragePath.orEmpty(), s?.storageLocationType ?: "DOCUMENTS")
        val bin = File(File(root, "_to_delete"), folderOf(ctx, d).name).apply { mkdirs() }
        val target = unique(bin, f.name)
        if (!f.renameTo(target)) { if (f.isDirectory) { f.copyRecursively(target); f.deleteRecursively() } else { f.copyTo(target); f.delete() } }
        if (runCatching { d.coverUri != null && File(d.coverUri).canonicalFile == f }.getOrDefault(false))
            AppDatabase.getInstance(ctx).documentDao().updateDocument(d.copy(coverUri = null))
    }

    /** Makes an image the item's cover. */
    suspend fun setCover(ctx: Context, d: DocumentEntity, rel: String): DocumentEntity {
        val f = resolve(ctx, d, rel)
        require(f.extension.lowercase() in images) { "Only an image can be the cover" }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        val ratio = if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else d.coverAspectRatio
        val nd = d.copy(coverUri = f.absolutePath, coverAspectRatio = ratio)
        AppDatabase.getInstance(ctx).documentDao().updateDocument(nd)
        return nd
    }

    fun typeFor(f: File): String = when (f.extension.lowercase()) {
        "pdf" -> "PDF"
        "zip" -> if (runCatching { DaisyParserEngine.isDaisyZipArchive(f) }.getOrDefault(false)) "DAISY" else "EPUB"
        "html", "htm", "xhtml" -> "HTML"
        "txt" -> "TXT"
        else -> "EPUB"
    }

    /**
     * Makes another readable file the main file. The place, chapters and PDF settings of the current
     * file are kept for it, and the new file's own (if it was read before) come back.
     */
    suspend fun setMain(ctx: Context, d: DocumentEntity, rel: String): DocumentEntity {
        val f = resolve(ctx, d, rel)
        require(f.isFile && f.extension.lowercase() in readable) { "Only a PDF, EPUB, text or HTML file can be the main file" }
        val old = File(d.filePath)
        if (runCatching { old.canonicalFile == f }.getOrDefault(false)) return d
        val db = AppDatabase.getInstance(ctx)
        synchronized(this) {
            val it = item(ctx, d.id)
            val pos = it.optJSONObject("pos") ?: JSONObject().also { p -> it.put("pos", p) }
            pos.put(old.name, JSONObject().put("currentPage", d.currentPage).put("totalPages", d.totalPages).put("readingProgress", d.readingProgress.toDouble())
                .put("chaptersJson", d.chaptersJson).put("fileType", d.fileType).put("cropL", d.pdfCropLeft.toDouble()).put("cropT", d.pdfCropTop.toDouble())
                .put("cropR", d.pdfCropRight.toDouble()).put("cropB", d.pdfCropBottom.toDouble()).put("mode", d.pdfTextExtractionMode).put("overrides", d.pdfPageExtractionOverridesJson))
            // Notes made so far without a file belong to the old main file.
            val notes = it.optJSONObject("notes") ?: JSONObject().also { n -> it.put("notes", n) }
            kotlinx.coroutines.runBlocking { db.highlightDao().getHighlightsListForDocument(d.id) }.forEach { h -> if (!notes.has(h.id.toString())) notes.put(h.id.toString(), old.name) }
            save(ctx)
        }
        val p = synchronized(this) { item(ctx, d.id).optJSONObject("pos")?.optJSONObject(f.name) }
        val nd = d.copy(
            filePath = f.absolutePath, fileType = typeFor(f),
            currentPage = p?.optInt("currentPage", 1) ?: 1, totalPages = p?.optInt("totalPages", 1) ?: 1,
            readingProgress = (p?.optDouble("readingProgress", 0.0) ?: 0.0).toFloat(), chaptersJson = p?.optString("chaptersJson").orEmpty(),
            pdfCropLeft = (p?.optDouble("cropL", 0.0) ?: 0.0).toFloat(), pdfCropTop = (p?.optDouble("cropT", 0.0) ?: 0.0).toFloat(),
            pdfCropRight = (p?.optDouble("cropR", 1.0) ?: 1.0).toFloat(), pdfCropBottom = (p?.optDouble("cropB", 1.0) ?: 1.0).toFloat(),
            pdfTextExtractionMode = p?.optString("mode").orEmpty(), pdfPageExtractionOverridesJson = p?.optString("overrides").orEmpty(),
            epubVersion = "", epubLayoutType = "")
        db.documentDao().updateDocument(nd)
        // Notes made in the new main file need no mark any more.
        synchronized(this) { item(ctx, d.id).optJSONObject("notes")?.let { n -> n.keys().asSequence().toList().forEach { k -> if (n.optString(k) == f.name) n.remove(k) } }; save(ctx) }
        runCatching { SearchIndex.kick(ctx) }
        return nd
    }

    /** The file a note was made in, when it is not the main file (null: the main file). */
    fun noteFile(ctx: Context, docId: Long, noteId: Long): String? = synchronized(this) {
        store(ctx).optJSONObject(docId.toString())?.optJSONObject("notes")?.optString(noteId.toString())?.takeIf { it.isNotBlank() }
    }

    /** Has the item more than one readable file? */
    fun hasOtherReadable(d: DocumentEntity): Boolean {
        val dir = File(d.filePath).parentFile ?: return false
        if (dir.name == "Books" || dir.name == "Articles") return false
        return (dir.listFiles()?.count { it.isFile && it.extension.lowercase() in readable && !it.name.startsWith(".") } ?: 0) > 1
    }

    /** All files with their facts, for the web page. */
    suspend fun json(ctx: Context, d: DocumentEntity): JSONObject {
        val dir = folderOf(ctx, d)
        return JSONObject().put("folder", dir.absolutePath).put("files", org.json.JSONArray().apply {
            list(ctx, d).forEach { e -> put(JSONObject().put("rel", e.rel).put("name", e.name).put("size", e.size).put("modified", e.modified)
                .put("dir", e.isDir).put("main", e.isMain).put("cover", e.isCover).put("readable", e.readable).put("image", e.image).put("kind", e.kind)) }
        })
    }

    /**
     * An imported item that looks like one already in the library (same ISBN, DOI or title, a different
     * file): the older item, to offer attaching the new file to it.
     */
    suspend fun lookalike(ctx: Context, d: DocumentEntity): DocumentEntity? {
        if (!isFileItem(d)) return null
        val db = AppDatabase.getInstance(ctx)
        val norm = { s: String -> SearchIndex.tokens(s).joinToString(" ") }
        val title = norm(d.title.substringBefore(':'))
        if (title.length < 4) return null
        val rec = runCatching { BibStore.get(d.id) }.getOrNull()
        val isbn = rec?.get(BibField.ISBN)?.filter { it.isDigit() || it == 'X' }.orEmpty()
        val doi = rec?.get(BibField.DOI)?.lowercase()?.trim().orEmpty()
        return db.documentDao().getAllDocumentsSnapshot().filter { it.id != d.id && isFileItem(it) }.firstOrNull { o ->
            val r = runCatching { BibStore.get(o.id) }.getOrNull()
            (isbn.length >= 10 && r?.get(BibField.ISBN)?.filter { it.isDigit() || it == 'X' } == isbn) ||
                (doi.isNotBlank() && r?.get(BibField.DOI)?.lowercase()?.trim() == doi) ||
                norm(o.title.substringBefore(':')) == title
        }
    }

    /** Moves an imported item's file into [into] as another file and removes the imported item. */
    suspend fun mergeInto(ctx: Context, from: DocumentEntity, into: DocumentEntity, ops: suspend (DocumentEntity) -> Unit): File {
        val src = File(from.filePath)
        check(src.isFile) { "The imported file is missing" }
        val moved = addByMove(ctx, into, src)
        // The imported item had no notes of its own yet; it goes (its now empty folder too).
        ops(from)
        return moved
    }
}
