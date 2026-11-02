package com.example.reader

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.server.LibraryOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * What can be done to several library items at once (web view and tablet): their files or notes as
 * one .zip, a bibliography, tags, shelf, folder book, delete, and Zotero.
 */
object BulkActions {

    data class Result(val message: String, val done: Int = 0, val skipped: Int = 0, val details: List<String> = emptyList())

    private fun dao(ctx: Context) = AppDatabase.getInstance(ctx).documentDao()

    suspend fun docs(ctx: Context, ids: List<Long>): List<DocumentEntity> = withContext(Dispatchers.IO) {
        ids.distinct().mapNotNull { dao(ctx).getDocumentById(it) }
    }

    // ------------------------------------------------------------------ file names

    /** "Allen", "Allen and Bell", "Allen et al." */
    fun creators(author: String): String {
        val people = People.split(author.replace(Regex("\\s*\\(eds?\\.?\\)\\s*", RegexOption.IGNORE_CASE), " ").trim())
            .filter { it.isNotBlank() && !it.equals("Unknown Author", true) }
        fun family(p: String) = if (p.contains(',')) p.substringBefore(',').trim() else p.trim().substringAfterLast(' ')
        return when (people.size) { 0 -> ""; 1 -> family(people[0]); 2 -> "${family(people[0])} and ${family(people[1])}"; else -> "${family(people[0])} et al." }
    }

    /** The title up to a colon, at most about 60 characters, cut at a whole word. */
    fun shortTitle(title: String): String {
        var t = title.trim().ifBlank { "Untitled" }
        val colon = Regex("[:–—]|\\s-\\s|\\?").find(t)?.range?.first ?: -1
        if (colon in 8 until t.length) t = t.substring(0, colon).trim()
        if (t.length > 60) {
            val cut = t.substring(0, 60)
            t = (cut.substringBeforeLast(' ').takeIf { it.length >= 25 } ?: cut).trim().trimEnd(',', ';', '.', '-')
        }
        return t
    }

    /** "Allen et al. - 2020 - The right wing extremism" (no extension). */
    fun fileBase(doc: DocumentEntity, rec: BibRecord = BibStore.get(doc.id)): String {
        val who = creators(if (FolderBooks.isFolder(doc)) EditedVolumes.editors(doc) else doc.author)
        val year = Regex("\\b(1[5-9]\\d{2}|20\\d{2})\\b").find(rec[BibField.YEAR])?.value
            ?: runCatching { MarkdownExporter.extractPublishedYear(doc) }.getOrDefault("").takeIf { it.isNotBlank() }
        val title = shortTitle(rec[BibField.TITLE].ifBlank { doc.title })
        return clean(listOfNotNull(who.takeIf { it.isNotBlank() }, year, title).joinToString(" - "))
    }

    private fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(150).ifBlank { "Untitled" }

    private fun unique(name: String, used: MutableSet<String>): String {
        var n = name; var i = 2
        while (!used.add(n.lowercase(Locale.ROOT))) { n = name.substringBeforeLast('.', name) + " ($i)" + (if (name.contains('.')) "." + name.substringAfterLast('.') else ""); i++ }
        return n
    }

    private fun ext(doc: DocumentEntity): String = File(doc.filePath).extension.ifBlank { doc.fileType.lowercase(Locale.ROOT) }.lowercase(Locale.ROOT)

    private fun outFile(ctx: Context, name: String): File = File(File(ctx.cacheDir, "exports/bulk").apply { mkdirs() }, clean(name))

    // ------------------------------------------------------------------ files and notes

    /** The items' own files in one .zip, named “Author - Year - Title.pdf”. A folder book's chapter files go in a folder of their own. */
    suspend fun filesZip(ctx: Context, docs: List<DocumentEntity>): Pair<File, Result> = withContext(Dispatchers.IO) {
        BibStore.init(ctx); FolderBooks.init(ctx)
        val out = outFile(ctx, "Files (${docs.size} item${if (docs.size == 1) "" else "s"}).zip")
        val used = HashSet<String>()
        var n = 0; var skipped = 0
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun add(d: DocumentEntity, prefix: String) {
                val f = File(d.filePath)
                if (ZoteroPlaceholders.isPlaceholder(d) || !f.isFile) { skipped++; return }
                val name = unique(prefix + fileBase(d) + "." + ext(d), used)
                zip.putNextEntry(ZipEntry(name)); f.inputStream().use { it.copyTo(zip, 256 * 1024) }; zip.closeEntry(); n++
            }
            for (d in docs) {
                if (FolderBooks.isFolder(d)) {
                    val folder = fileBase(d) + "/"
                    FolderBooks.partsOf(d.id).forEachIndexed { i, pid ->
                        val p = dao(ctx).getDocumentById(pid) ?: return@forEachIndexed
                        add(p, folder + "%02d ".format(i + 1))
                    }
                } else add(d, "")
            }
        }
        out to Result("$n file${if (n == 1) "" else "s"}" + if (skipped > 0) " · $skipped without a file" else "", n, skipped)
    }

    /** Every item's notes and “Notes on …” overview, one folder per item, ready for Obsidian. */
    suspend fun notesZip(ctx: Context, docs: List<DocumentEntity>): Pair<File, Result> = withContext(Dispatchers.IO) {
        BibStore.init(ctx)
        val ops = LibraryOps(ctx)
        val settings = ops.repository.getSettings()
        val out = outFile(ctx, "Notes (${docs.size} item${if (docs.size == 1) "" else "s"}).zip")
        val used = HashSet<String>()
        var n = 0; var notes = 0
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (d in docs) {
                val hls = ops.repository.getHighlightsListForDocument(d.id)
                val folder = unique(fileBase(d), used) + "/"
                val vault = runCatching { MarkdownExporter.createVaultZip(ctx, d, hls, settings) }.getOrNull() ?: continue
                ZipFile(vault).use { z ->
                    z.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
                        zip.putNextEntry(ZipEntry(folder + e.name.substringAfter('/', e.name).ifBlank { e.name }))
                        z.getInputStream(e).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                vault.delete()
                n++; notes += hls.size
            }
        }
        out to Result("$notes note${if (notes == 1) "" else "s"} from $n item${if (n == 1) "" else "s"}", n)
    }

    // ------------------------------------------------------------------ bibliography

    fun bibtex(docs: List<DocumentEntity>): String = docs.joinToString("\n\n") { Citations.bibtex(it, BibStore.get(it.id)) }
    fun ris(docs: List<DocumentEntity>): String = docs.joinToString("\n") { Ris.export(it, BibStore.get(it.id)) }

    suspend fun bibFile(ctx: Context, docs: List<DocumentEntity>, format: String): File = withContext(Dispatchers.IO) {
        BibStore.init(ctx)
        val ris = format.equals("ris", true)
        outFile(ctx, "References (${docs.size}).${if (ris) "ris" else "bib"}").apply { writeText(if (ris) ris(docs) else bibtex(docs), Charsets.UTF_8) }
    }

    /** The reference list in a style (alphabetical, plain text). */
    fun references(docs: List<DocumentEntity>, style: CitationStyle): String =
        docs.map { runCatching { Citations.reference(style, it, BibStore.get(it.id)) }.getOrDefault(it.title) }
            .map { it.replace(Regex("(?<!\\*)\\*(?!\\*)"), "").replace("**", "").trim() }
            .sortedBy { it.lowercase(Locale.ROOT) }.joinToString("\n\n")

    /** “[@a; @b]” for Pandoc / Obsidian, and the keys one per line. */
    fun citeKeys(docs: List<DocumentEntity>): String = docs.map { Citations.citeKey(it, BibStore.get(it.id)) }.filter { it.isNotBlank() }
        .joinToString("; ", prefix = "[", postfix = "]") { "@$it" }

    // ------------------------------------------------------------------ organising

    suspend fun tags(ctx: Context, docs: List<DocumentEntity>, add: List<String>, remove: List<String>): Result = withContext(Dispatchers.IO) {
        val rm = remove.map { it.trim().removePrefix("#").lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.toSet()
        val ad = add.map { it.trim().removePrefix("#").replace(Regex("\\s+"), "_") }.filter { it.isNotBlank() }
        var n = 0
        docs.forEach { d ->
            val cur = NoteSuggestionEngine.parseTagsList(d.tags)
            val next = (cur.filter { it.lowercase(Locale.ROOT) !in rm } + ad).distinctBy { it.lowercase(Locale.ROOT) }
            val joined = NoteSuggestionEngine.joinTagsList(next)
            if (joined != d.tags) { dao(ctx).updateTags(d.id, joined); n++; ZoteroPlaceholders.pushSoon(ctx, d.id) }
        }
        Result("Tags changed on $n item${if (n == 1) "" else "s"}", n)
    }

    suspend fun category(ctx: Context, docs: List<DocumentEntity>, category: String): Result = withContext(Dispatchers.IO) {
        val ops = LibraryOps(ctx)
        var n = 0
        docs.forEach { d -> if (!d.category.equals(category, true)) { runCatching { ops.changeCategory(d, category) }.onSuccess { n++ } } }
        Result("$n moved to ${if (category.equals("ARTICLE", true)) "Articles" else "Books"}", n)
    }

    suspend fun addToFolder(ctx: Context, folderId: Long, docs: List<DocumentEntity>): Result {
        val ids = docs.filter { !FolderBooks.isFolder(it) && it.id != folderId }.map { it.id }
        FolderBooks.addParts(ctx, folderId, ids)
        return Result("${ids.size} added to the folder book", ids.size, docs.size - ids.size)
    }

    suspend fun delete(ctx: Context, docs: List<DocumentEntity>): Result = withContext(Dispatchers.IO) {
        val ops = LibraryOps(ctx)
        var n = 0
        docs.forEach { d -> runCatching { ops.deleteDocument(d) }.onSuccess { n++ } }
        Result("$n deleted", n)
    }

    // ------------------------------------------------------------------ Zotero and lookup

    data class Check(val doc: DocumentEntity, val key: String?, val differences: List<String>)

    /** Each item compared with its Zotero item: which fields differ (unlinked items have no key). */
    suspend fun zoteroCheck(ctx: Context, docs: List<DocumentEntity>): List<Check> = withContext(Dispatchers.IO) {
        Zotero.init(ctx); BibStore.init(ctx)
        docs.map { d ->
            val key = Zotero.linkOf(d.id)
            val item = key?.let { Zotero.item(it) }
            if (key == null || item == null) Check(d, key, emptyList())
            else {
                val rows = MetadataLookup.compare(BibStore.get(d.id), if (FolderBooks.isFolder(d)) "" else d.author, d.summary, d.title, Zotero.toFound(item))
                Check(d, key, rows.filter { !it.same && it.found.isNotBlank() }.map { it.label })
            }
        }
    }

    /** Creates Zotero items for the items not yet in Zotero (and links them). */
    suspend fun zoteroAdd(ctx: Context, docs: List<DocumentEntity>): Result = withContext(Dispatchers.IO) {
        Zotero.init(ctx); BibStore.init(ctx)
        var n = 0; var already = 0
        val failed = ArrayList<String>()
        docs.forEach { d ->
            if (Zotero.linkOf(d.id) != null) { already++; return@forEach }
            runCatching { Zotero.create(ctx, d, BibStore.get(d.id)) }.onSuccess { n++ }.onFailure { failed += "${d.title.take(40)}: ${it.message}" }
        }
        Result("$n added to Zotero" + (if (already > 0) " · $already already there" else "") + (if (failed.isNotEmpty()) " · ${failed.size} failed: ${failed.first()}" else ""), n, already, failed)
    }

    /**
     * Looks up each item by its DOI, ISBN or ISSN (Crossref, Open Library …) and fills in only the
     * fields that are empty; nothing already filled in is changed. Items without an identifier are skipped.
     */
    suspend fun lookup(ctx: Context, docs: List<DocumentEntity>): Result = withContext(Dispatchers.IO) {
        BibStore.init(ctx)
        var filled = 0; var items = 0; var skipped = 0
        docs.forEach { d ->
            var rec = BibStore.get(d.id)
            if (rec[BibField.DOI].isBlank() && rec[BibField.ISBN].isBlank() && rec[BibField.ISSN].isBlank()) { skipped++; return@forEach }
            val found = MetadataLookup.run(rec, d.title, d.author).getOrNull()?.found ?: return@forEach
            var n = 0
            BibField.entries.forEach { f -> val v = found.record[f]; if (v.isNotBlank() && rec[f].isBlank()) { rec = rec.with(f, v); n++ } }
            if (rec.entryType.isBlank() && found.record.entryType.isNotBlank()) rec = rec.copy(entryType = found.record.entryType)
            if (n > 0) BibStore.put(d.id, rec)
            val noAuthor = d.author.isBlank() || d.author.equals("Unknown Author", true)
            if (noAuthor && found.authors.isNotBlank()) { dao(ctx).updateAuthor(d.id, found.authors); n++ }
            if (d.summary.isBlank() && found.abstract.isNotBlank()) { dao(ctx).updateSummary(d.id, found.abstract); n++ }
            if (n > 0) { items++; filled += n; ZoteroPlaceholders.pushSoon(ctx, d.id) }
        }
        Result("$filled field${if (filled == 1) "" else "s"} filled in on $items item${if (items == 1) "" else "s"}" + if (skipped > 0) " · $skipped without DOI/ISBN/ISSN" else "", items, skipped)
    }
}
