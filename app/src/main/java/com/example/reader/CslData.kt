package com.example.reader

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Citation data for the writing program: items as CSL-JSON (the form citation styles read), from the
 * library (books, articles, chapters of edited volumes) and from Zotero; and CSL styles and locales,
 * fetched once from the Zotero style repository and the CSL locales and kept on the tablet.
 */
object CslData {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    // ------------------------------------------------------------------ items

    private fun names(raw: String): JSONArray = JSONArray().apply {
        Citations.people(raw).forEach { p -> put(if (p.given.isBlank()) JSONObject().put("literal", p.family) else JSONObject().put("family", p.family).put("given", p.given)) }
    }

    /** "2017", "2017-05-03", "May 2017", "2017/2018" → CSL date. */
    fun date(raw: String): JSONObject? {
        val s = raw.trim(); if (s.isBlank() || s == "n.d.") return null
        Regex("^(\\d{4})(?:[-/.](\\d{1,2}))?(?:[-/.](\\d{1,2}))?").find(s)?.let { m ->
            val parts = JSONArray().put(m.groupValues[1].toInt())
            m.groupValues[2].toIntOrNull()?.takeIf { it in 1..12 }?.let { mo -> parts.put(mo); m.groupValues[3].toIntOrNull()?.takeIf { it in 1..31 }?.let { parts.put(it) } }
            return JSONObject().put("date-parts", JSONArray().put(parts))
        }
        Regex("(\\d{4})").find(s)?.let { return JSONObject().put("date-parts", JSONArray().put(JSONArray().put(it.value.toInt()))).put("raw", s) }
        return JSONObject().put("literal", s)
    }

    private fun JSONObject.putIf(k: String, v: String?): JSONObject { if (!v.isNullOrBlank()) put(k, v.trim()); return this }

    /** A library item as CSL-JSON (its Zotero record when it is linked to one). */
    fun fromDoc(ctx: Context, d: DocumentEntity): JSONObject {
        Zotero.linkOf(d.id)?.let { key -> Zotero.item(key)?.let { z -> return fromZotero(z).put("id", "ml:${d.id}") } }
        val rec = BibStore.get(d.id)
        val chapter = rec.entryType == "incollection" || rec.entryType == "inbook" || rec[BibField.BOOKTITLE].isNotBlank()
        val article = !chapter && (rec[BibField.JOURNAL].isNotBlank() || d.category.equals("ARTICLE", true))
        val sub = rec[BibField.SUBTITLE]
        val t = rec[BibField.TITLE].ifBlank { d.title }.let { if (sub.isNotBlank() && !it.endsWith(sub, true)) "$it: $sub" else it }
        val o = JSONObject().put("id", "ml:${d.id}").put("type", if (chapter) "chapter" else if (article) "article-journal" else "book").put("title", t)
        if (d.author.isNotBlank()) {
            val edited = EditedVolumes.isEditedVolume(ctx, d)
            if (edited && rec[BibField.EDITOR].isBlank()) o.put("editor", names(EditedVolumes.editors(d))) else o.put("author", names(d.author))
        }
        if (rec[BibField.EDITOR].isNotBlank()) o.put("editor", names(rec[BibField.EDITOR]))
        if (rec[BibField.TRANSLATOR].isNotBlank()) o.put("translator", names(rec[BibField.TRANSLATOR]))
        date(rec[BibField.YEAR].ifBlank { runCatching { MarkdownExporter.extractPublishedYear(d) }.getOrDefault("") })?.let { o.put("issued", it) }
        o.putIf("container-title", rec[BibField.JOURNAL].ifBlank { rec[BibField.BOOKTITLE] }).putIf("publisher", rec[BibField.PUBLISHER]).putIf("publisher-place", rec[BibField.PLACE])
            .putIf("edition", rec[BibField.EDITION]).putIf("volume", rec[BibField.VOLUME]).putIf("issue", rec[BibField.ISSUE]).putIf("page", rec[BibField.PAGES])
            .putIf("collection-title", rec[BibField.SERIES]).putIf("DOI", rec[BibField.DOI]).putIf("ISBN", rec[BibField.ISBN]).putIf("ISSN", rec[BibField.ISSN])
            .putIf("URL", rec[BibField.URL]).putIf("language", rec[BibField.LANGUAGE]).putIf("citation-key", rec[BibField.CITEKEY])
        return o
    }

    /** A chapter of an edited volume as CSL-JSON (its own Zotero book section when there is one). */
    fun fromChapter(ctx: Context, d: DocumentEntity, index: Int, ch: VolumeChapter): JSONObject {
        Zotero.chapterLinkOf(d.id, index)?.let { key -> Zotero.item(key)?.let { z -> return fromZotero(z).put("id", "ml:${d.id}:$index") } }
        val book = fromDoc(ctx, d)
        val o = JSONObject().put("id", "ml:${d.id}:$index").put("type", "chapter").put("title", ch.title)
            .put("author", names(ch.authors)).put("container-title", book.optString("title"))
        book.optJSONArray("editor")?.let { o.put("editor", it) } ?: if (EditedVolumes.editors(d).isNotBlank()) o.put("editor", names(EditedVolumes.editors(d))) else Unit
        listOf("issued", "publisher", "publisher-place", "edition", "ISBN", "collection-title", "language").forEach { k -> book.opt(k)?.let { o.put(k, it) } }
        o.putIf("page", ch.printedPages).putIf("DOI", ch.doi)
        return o
    }

    private val zType = mapOf("book" to "book", "bookSection" to "chapter", "journalArticle" to "article-journal", "magazineArticle" to "article-magazine",
        "newspaperArticle" to "article-newspaper", "thesis" to "thesis", "report" to "report", "webpage" to "webpage", "conferencePaper" to "paper-conference",
        "encyclopediaArticle" to "entry-encyclopedia", "dictionaryEntry" to "entry-dictionary", "case" to "legal_case", "statute" to "legislation", "bill" to "bill",
        "document" to "document", "manuscript" to "manuscript", "letter" to "personal_communication", "interview" to "interview", "film" to "motion_picture",
        "blogPost" to "post-weblog", "forumPost" to "post", "preprint" to "article", "dataset" to "dataset", "presentation" to "speech", "hearing" to "hearing",
        "map" to "map", "artwork" to "graphic", "radioBroadcast" to "broadcast", "tvBroadcast" to "broadcast", "podcast" to "song", "software" to "software", "standard" to "standard")
    private val zFields = linkedMapOf("title" to "title", "caseName" to "title", "nameOfAct" to "title", "subject" to "title",
        "publicationTitle" to "container-title", "bookTitle" to "container-title", "websiteTitle" to "container-title", "proceedingsTitle" to "container-title",
        "encyclopediaTitle" to "container-title", "dictionaryTitle" to "container-title", "blogTitle" to "container-title", "forumTitle" to "container-title",
        "programTitle" to "container-title", "reporter" to "container-title", "code" to "container-title", "seriesTitle" to "collection-title", "series" to "collection-title",
        "publisher" to "publisher", "university" to "publisher", "institution" to "publisher", "court" to "authority", "place" to "publisher-place",
        "volume" to "volume", "reporterVolume" to "volume", "codeNumber" to "volume", "issue" to "issue", "pages" to "page", "firstPage" to "page",
        "edition" to "edition", "seriesNumber" to "collection-number", "numberOfPages" to "number-of-pages", "numberOfVolumes" to "number-of-volumes",
        "reportNumber" to "number", "billNumber" to "number", "docketNumber" to "number", "publicLawNumber" to "number", "section" to "section",
        "DOI" to "DOI", "ISBN" to "ISBN", "ISSN" to "ISSN", "url" to "URL", "abstractNote" to "abstract", "language" to "language",
        "shortTitle" to "title-short", "journalAbbreviation" to "container-title-short", "thesisType" to "genre", "reportType" to "genre", "medium" to "medium",
        "archive" to "archive", "archiveLocation" to "archive_location", "extra" to "note")
    private val zCreators = mapOf("author" to "author", "editor" to "editor", "translator" to "translator", "bookAuthor" to "container-author",
        "seriesEditor" to "collection-editor", "contributor" to "contributor", "director" to "director", "interviewer" to "interviewer", "recipient" to "recipient",
        "reviewedAuthor" to "reviewed-author", "composer" to "composer", "producer" to "producer")

    /** A Zotero item (its "data") as CSL-JSON. */
    fun fromZotero(z: JSONObject): JSONObject {
        val o = JSONObject().put("id", "z:" + z.optString("key")).put("type", zType[z.optString("itemType")] ?: "document")
        for ((zf, cf) in zFields) { val v = z.optString(zf); if (v.isNotBlank() && !o.has(cf)) o.put(cf, v.trim()) }
        z.optJSONArray("creators")?.let { a ->
            for (i in 0 until a.length()) {
                val c = a.optJSONObject(i) ?: continue
                val role = zCreators[c.optString("creatorType")] ?: continue
                val n = if (c.optString("name").isNotBlank()) JSONObject().put("literal", c.optString("name")) else JSONObject().put("family", c.optString("lastName")).put("given", c.optString("firstName"))
                (o.optJSONArray(role) ?: JSONArray().also { o.put(role, it) }).put(n)
            }
        }
        date(z.optString("date").ifBlank { z.optString("dateDecided").ifBlank { z.optString("dateEnacted") } })?.let { o.put("issued", it) }
        date(z.optString("accessDate"))?.let { o.put("accessed", it) }
        return o
    }

    /** Zotero's web address for an item (lets Zotero in Word/LibreOffice link the citation to your library). */
    fun zoteroUri(ctx: Context, key: String): String? = Zotero.account(ctx)?.userId?.let { "http://zotero.org/users/$it/items/$key" }

    /** Search for "Insert citation": the library (books, articles, chapters) and Zotero. */
    suspend fun search(ctx: Context, q: String, limit: Int = 40): JSONArray {
        Zotero.init(ctx); BibStore.init(ctx); EditedVolumes.init(ctx)
        val terms = SearchIndex.tokens(q)
        fun hit(s: String) = terms.isNotEmpty() && SearchIndex.tokens(s).joinToString(" ").let { h -> terms.all { h.contains(it) } }
        val out = ArrayList<Pair<Int, JSONObject>>()
        val linkedKeys = HashSet<String>()
        val docs = AppDatabase.getInstance(ctx).documentDao().getAllDocumentsSnapshot().filter { ItemFiles.isFileItem(it) || ZoteroPlaceholders.isPlaceholder(it) }
        for (d in docs) {
            Zotero.linkOf(d.id)?.let { linkedKeys += it }
            val rec = BibStore.get(d.id)
            val hay = listOf(d.title, d.author, rec[BibField.YEAR], rec[BibField.CITEKEY], rec[BibField.JOURNAL], rec[BibField.DOI], rec[BibField.ISBN]).joinToString(" ")
            if (hit(hay)) out += 2 to entry(ctx, fromDoc(ctx, d), "library", Zotero.linkOf(d.id))
            val vol = EditedVolumes.get(ctx, d.id)
            if (vol != null && vol.enabled) vol.chapters.forEachIndexed { i, ch ->
                Zotero.chapterLinkOf(d.id, i)?.let { linkedKeys += it }
                if (ch.isArticle && ch.authors.isNotBlank() && hit(listOf(ch.title, ch.authors, d.title).joinToString(" "))) out += 1 to entry(ctx, fromChapter(ctx, d, i, ch), "chapter", Zotero.chapterLinkOf(d.id, i))
            }
        }
        if (terms.isNotEmpty()) Zotero.list(null, q).filter { it.optString("itemType") !in setOf("note", "attachment", "annotation") && it.optString("key") !in linkedKeys }.take(limit).forEach { z ->
            out += 0 to entry(ctx, fromZotero(z), "zotero", z.optString("key"))
        }
        return JSONArray().apply { out.sortedByDescending { it.first }.take(limit).forEach { put(it.second) } }
    }

    /** The source and place of a note, for citing it when it is dragged into a text: { source, locator, label }. */
    suspend fun forNote(ctx: Context, noteId: Long): JSONObject {
        Zotero.init(ctx); BibStore.init(ctx); EditedVolumes.init(ctx)
        val db = AppDatabase.getInstance(ctx)
        val n = db.highlightDao().getHighlightById(noteId) ?: throw NoSuchElementException("No such note")
        val d = db.documentDao().getDocumentById(n.documentId) ?: throw NoSuchElementException("The note's book is gone")
        // A note in a chapter of an edited volume cites the chapter (with its own authors).
        val vol = EditedVolumes.get(ctx, d.id)
        val idx = if (vol != null && vol.enabled) EditedVolumes.chapterIndexFor(vol, n.pageNumber, n.chapterTitle) else -1
        val ch = if (idx >= 0) vol!!.chapters[idx].takeIf { it.isArticle && it.authors.isNotBlank() } else null
        val src = if (ch != null) entry(ctx, fromChapter(ctx, d, idx, ch), "chapter", Zotero.chapterLinkOf(d.id, idx))
        else entry(ctx, fromDoc(ctx, d), "library", Zotero.linkOf(d.id))
        val file = ItemFiles.noteFile(ctx, d.id, n.id).orEmpty().ifBlank { d.filePath }
        val pdf = file.endsWith(".pdf", true) || (ItemFiles.noteFile(ctx, d.id, n.id).isNullOrBlank() && d.fileType.equals("PDF", true))
        val loc = n.printedPageLabel.trim().ifBlank { n.pageEquivalent.trim().replace(Regex("^pp?\\.\\s*", RegexOption.IGNORE_CASE), "") }.ifBlank { if (pdf && n.pageNumber > 0) n.pageNumber.toString() else "" }
        return JSONObject().put("source", src).put("locator", loc).put("label", "page")
    }

    /**
     * The citation keys of cited sources, for LaTeX and Markdown: { id: { key, set, zoteroKey } }.
     * Marginalia's own key comes first ([set] says whether it was typed in or is the default one);
     * [zoteroKey] is the key the linked Zotero item has, so a difference can be shown.
     */
    suspend fun citekeys(ctx: Context, ids: List<String>): JSONObject {
        Zotero.init(ctx); BibStore.init(ctx)
        val dao = AppDatabase.getInstance(ctx).documentDao()
        val out = JSONObject()
        for (id in ids) {
            val p = id.split(':')
            val o = JSONObject().put("key", "").put("set", false).put("zoteroKey", "")
            when {
                p[0] == "z" && p.size >= 2 -> Zotero.item(p[1])?.let { Zotero.citationKey(it) }?.let { o.put("key", it).put("zoteroKey", it).put("set", it.isNotBlank()) }
                p[0] == "ml" && p.size == 2 -> p[1].toLongOrNull()?.let { dao.getDocumentById(it) }?.let { d ->
                    val rec = BibStore.get(d.id)
                    o.put("key", rec[BibField.CITEKEY].trim().ifBlank { Citations.citeKey(d, rec) }).put("set", rec[BibField.CITEKEY].isNotBlank())
                    Zotero.linkOf(d.id)?.let { Zotero.item(it) }?.let { o.put("zoteroKey", Zotero.citationKey(it)) }
                }
                p[0] == "ml" && p.size == 3 -> p[1].toLongOrNull()?.let { docId ->
                    Zotero.chapterLinkOf(docId, p[2].toIntOrNull() ?: -1)?.let { Zotero.item(it) }?.let { Zotero.citationKey(it) }?.let { o.put("key", it).put("zoteroKey", it).put("set", it.isNotBlank()) }
                }
            }
            out.put(id, o)
        }
        return out
    }

    /** The sources behind citation keys (of an imported LaTeX or Markdown text): { key: entry }. Keys nobody has are left out. */
    suspend fun byCitekey(ctx: Context, keys: List<String>): JSONObject {
        Zotero.init(ctx); BibStore.init(ctx)
        val want = keys.toSet(); val out = JSONObject()
        val docs = AppDatabase.getInstance(ctx).documentDao().getAllDocumentsSnapshot().filter { ItemFiles.isFileItem(it) || ZoteroPlaceholders.isPlaceholder(it) }
        for (d in docs) {
            val rec = BibStore.get(d.id)
            val mine = setOf(rec[BibField.CITEKEY].trim(), Citations.citeKey(d, rec), Zotero.linkOf(d.id)?.let { Zotero.item(it) }?.let { Zotero.citationKey(it) }.orEmpty()).filter { it.isNotBlank() }
            for (k in mine) if (k in want && !out.has(k)) out.put(k, entry(ctx, fromDoc(ctx, d), "library", Zotero.linkOf(d.id)))
        }
        if (out.length() < want.size) Zotero.list(null, null).forEach { z ->
            val k = Zotero.citationKey(z)
            if (k.isNotBlank() && k in want && !out.has(k)) out.put(k, entry(ctx, fromZotero(z), "zotero", z.optString("key")))
        }
        return out
    }

    private fun entry(ctx: Context, csl: JSONObject, source: String, zoteroKey: String?): JSONObject {
        val a = (csl.optJSONArray("author") ?: csl.optJSONArray("editor"))?.let { arr -> (0 until arr.length()).joinToString("; ") { i -> arr.optJSONObject(i)?.let { n -> n.optString("family").ifBlank { n.optString("literal") } }.orEmpty() } }.orEmpty()
        val y = csl.optJSONObject("issued")?.optJSONArray("date-parts")?.optJSONArray(0)?.optInt(0)?.takeIf { it > 0 }?.toString().orEmpty()
        return JSONObject().put("id", csl.optString("id")).put("source", source).put("csl", csl)
            .put("label", listOf(a, y).filter { it.isNotBlank() }.joinToString(", ")).put("title", csl.optString("title"))
            .put("container", csl.optString("container-title")).put("type", csl.optString("type"))
            .put("uri", zoteroKey?.let { zoteroUri(ctx, it) } ?: "")
    }

    // ------------------------------------------------------------------ styles and locales

    private fun dir(ctx: Context, sub: String) = File(ctx.filesDir, "csl/$sub").apply { mkdirs() }
    private fun fetch(url: String): String = client.newCall(Request.Builder().url(url).header("User-Agent", "MarginaliaLectoris").build()).execute().use { r ->
        check(r.isSuccessful) { "Could not fetch $url (HTTP ${r.code})" }
        r.body?.string() ?: error("Empty answer")
    }

    /** The list of all styles in the Zotero style repository (kept for a week). */
    fun stylesIndex(ctx: Context): String {
        val f = File(dir(ctx, ""), "styles.json")
        if (f.isFile && System.currentTimeMillis() - f.lastModified() < 7L * 86400_000L) return f.readText()
        return try { fetch("https://www.zotero.org/styles-files/styles.json").also { f.writeText(it) } }
        catch (e: Exception) { if (f.isFile) f.readText() else throw IllegalStateException("Could not get the list of styles: ${e.message}") }
    }

    private val safeName = Regex("^[a-z0-9][a-z0-9-]{0,120}$")

    /**
     * A style by its short name (e.g. "apa"): its XML, with a dependent style's independent parent
     * resolved; plus its title, class (note or in-text) and default locale.
     */
    fun style(ctx: Context, name: String): JSONObject {
        require(safeName.matches(name)) { "Bad style name" }
        val f = File(dir(ctx, "styles"), "$name.csl")
        val xml = if (f.isFile) f.readText() else fetch("https://www.zotero.org/styles/$name").also { require(it.contains("<style")) { "Not a citation style" }; f.writeText(it) }
        val title = Regex("<title>([^<]*)</title>").find(xml)?.groupValues?.get(1)?.let { decodeXml(it) }.orEmpty()
        val defLocale = Regex("<style[^>]*default-locale=\"([^\"]+)\"").find(xml)?.groupValues?.get(1).orEmpty()
        val parent = Regex("<link[^>]*href=\"https?://www\\.zotero\\.org/styles/([a-z0-9-]+)\"[^>]*rel=\"independent-parent\"|<link[^>]*rel=\"independent-parent\"[^>]*href=\"https?://www\\.zotero\\.org/styles/([a-z0-9-]+)\"").find(xml)
            ?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
        val id = Regex("<id>([^<]*)</id>").find(xml)?.groupValues?.get(1)?.trim().orEmpty().ifBlank { "http://www.zotero.org/styles/$name" }
        val engineXml = if (parent != null) style(ctx, parent).getString("xml") else xml
        val cls = Regex("<style[^>]*class=\"([a-z-]+)\"").find(engineXml)?.groupValues?.get(1) ?: "in-text"
        return JSONObject().put("name", name).put("id", id).put("title", title).put("xml", engineXml).put("class", cls)
            .put("defaultLocale", defLocale.ifBlank { Regex("<style[^>]*default-locale=\"([^\"]+)\"").find(engineXml)?.groupValues?.get(1).orEmpty() })
            .put("hasBibliography", engineXml.contains("<bibliography"))
    }

    /** A CSL locale file (e.g. "en-US", "nb-NO"). */
    fun locale(ctx: Context, lang: String): String {
        require(Regex("^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})?$").matches(lang)) { "Bad language" }
        val f = File(dir(ctx, "locales"), "locales-$lang.xml")
        if (f.isFile) return f.readText()
        return fetch("https://raw.githubusercontent.com/citation-style-language/locales/master/locales-$lang.xml").also { require(it.contains("<locale")) { "Not a locale" }; f.writeText(it) }
    }

    private fun decodeXml(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
}
