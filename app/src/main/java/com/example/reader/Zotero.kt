package com.example.reader

import android.content.Context
import com.example.data.local.DocumentEntity
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Zotero through its Web API (api.zotero.org, version 3): your library's collections and items,
 * Zotero's own formatted citations, and — with a key that may write — corrected fields and new items
 * sent back. Files stay on the tablet; Zotero is only the citation source.
 *
 * The API key is kept with the AI keys (encrypted, never in backups). The library is cached in the
 * app's files folder and fetched again only from the library version last seen. Links between books
 * and Zotero items ("doc:12" → item key, "ch:12:3" for chapter 3) are kept in zotero_links.json.
 */
object Zotero {
    private const val BASE = "https://api.zotero.org"
    private const val SECRET = "ZOTERO"
    private val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    private val JSON_TYPE = "application/json".toMediaType()

    data class Account(val userId: String, val username: String, val canWrite: Boolean)
    data class Suggestion(val document: DocumentEntity, val reason: String, val strength: Int)
    data class ItemSuggestion(val key: String, val reason: String, val strength: Int)

    private val collections = LinkedHashMap<String, JSONObject>()
    private val items = LinkedHashMap<String, JSONObject>()
    private val links = HashMap<String, String>()
    private val templates = HashMap<String, JSONObject>()
    private val bibCache = HashMap<String, String>()
    @Volatile private var loaded = false
    @Volatile private var dir: File? = null
    @Volatile private var linksFile: File? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("zotero", Context.MODE_PRIVATE)

    @Synchronized fun init(ctx: Context) {
        if (loaded) return
        val d = File(ctx.applicationContext.filesDir, "zotero").apply { mkdirs() }
        dir = d
        linksFile = File(ctx.applicationContext.filesDir, "zotero_links.json")
        runCatching { File(d, "collections.json").takeIf { it.isFile }?.let { f -> val a = JSONArray(f.readText()); for (i in 0 until a.length()) a.getJSONObject(i).let { collections[it.getString("key")] = it } } }
        runCatching { File(d, "items.json").takeIf { it.isFile }?.let { f -> val a = JSONArray(f.readText()); for (i in 0 until a.length()) a.getJSONObject(i).let { items[it.getString("key")] = it } } }
        runCatching { linksFile?.takeIf { it.isFile }?.let { f -> val o = JSONObject(f.readText()); o.keys().forEach { k -> links[k] = o.getString(k) } } }
        loaded = true
    }

    @Synchronized private fun saveCache() {
        val d = dir ?: return
        writeAtomic(File(d, "collections.json"), JSONArray(collections.values.toList()).toString())
        writeAtomic(File(d, "items.json"), JSONArray(items.values.toList()).toString())
    }

    @Synchronized private fun saveLinks() {
        val f = linksFile ?: return
        writeAtomic(f, JSONObject(links as Map<*, *>).toString(2))
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    // ------------------------------------------------------------------------------ account

    fun account(ctx: Context): Account? {
        val p = prefs(ctx)
        val id = p.getString("userId", "").orEmpty()
        if (id.isBlank() || !AiPreferences(ctx).hasSecret(SECRET)) return null
        return Account(id, p.getString("username", "").orEmpty(), p.getBoolean("write", false))
    }

    fun keyTail(ctx: Context): String = runCatching { AiPreferences(ctx).readSecret(SECRET).trim().takeLast(4) }.getOrDefault("")
    fun lastSync(ctx: Context): Long = prefs(ctx).getLong("lastSync", 0L)

    private fun key(ctx: Context): String = AiPreferences(ctx).readSecret(SECRET).trim().also { require(it.isNotBlank()) { "Connect Zotero first: paste your API key in Settings ▸ Zotero." } }

    /** Checks the key with Zotero and saves it. */
    fun connect(ctx: Context, apiKey: String): Account {
        init(ctx)
        val k = apiKey.trim()
        require(k.length >= 20) { "That does not look like a Zotero API key (make one at zotero.org/settings/keys)." }
        val o = request(Request.Builder().url("$BASE/keys/current").header("Zotero-API-Key", k).header("Zotero-API-Version", "3").build()) { r ->
            if (r.code == 403 || r.code == 404) throw IllegalArgumentException("Zotero did not accept this key.")
            check(r.isSuccessful) { "Zotero answered HTTP ${r.code}." }
            JSONObject(r.body?.string().orEmpty())
        }
        val uid = o.optLong("userID").toString()
        require(uid != "0") { "This key has no personal library." }
        val access = o.optJSONObject("access")?.optJSONObject("user")
        require(access?.optBoolean("library") == true) { "This key cannot read your library. Allow “library access” when you make the key." }
        val write = access.optBoolean("write")
        val p = prefs(ctx)
        if (p.getString("userId", "") != uid) clearCache(ctx)
        AiPreferences(ctx).saveSecret(SECRET, k)
        p.edit().putString("userId", uid).putString("username", o.optString("username")).putBoolean("write", write).apply()
        return Account(uid, o.optString("username"), write)
    }

    fun disconnect(ctx: Context) {
        AiPreferences(ctx).saveSecret(SECRET, "")
        prefs(ctx).edit().clear().apply()
        clearCache(ctx)
    }

    @Synchronized private fun clearCache(ctx: Context) {
        init(ctx)
        collections.clear(); items.clear(); bibCache.clear()
        prefs(ctx).edit().remove("libVersion").remove("lastSync").apply()
        saveCache()
    }

    // ------------------------------------------------------------------------------ HTTP

    private fun <T> request(req: Request, read: (Response) -> T): T {
        var attempt = 0
        while (true) {
            client.newCall(req).execute().use { r ->
                if ((r.code == 429 || r.code == 503) && attempt < 3) {
                    val wait = (r.header("Retry-After")?.toLongOrNull() ?: 2L).coerceIn(1L, 10L)
                    attempt++
                    Thread.sleep(wait * 1000)
                    return@use
                }
                r.header("Backoff")?.toLongOrNull()?.let { if (it in 1..10) Thread.sleep(it * 1000) }
                return read(r)
            }
        }
    }

    private fun get(ctx: Context, path: String, read: (Response) -> Any?): Any? {
        val acct = account(ctx) ?: throw IllegalArgumentException("Connect Zotero first: paste your API key in Settings ▸ Zotero.")
        val url = "$BASE/users/${acct.userId}/$path"
        return request(Request.Builder().url(url).header("Zotero-API-Key", key(ctx)).header("Zotero-API-Version", "3").build(), read)
    }

    // ------------------------------------------------------------------------------ sync

    private val skipTypes = setOf("attachment", "note", "annotation")

    /** Fetches what changed in Zotero since the last time. Returns the number of changed items and collections. */
    @Synchronized fun sync(ctx: Context): Int {
        init(ctx)
        val p = prefs(ctx)
        val since = p.getLong("libVersion", 0L)
        var newest = since
        var changed = 0
        fun fetchAll(path: String, each: (JSONObject) -> Unit) {
            var start = 0
            while (true) {
                val sep = if (path.contains('?')) '&' else '?'
                val (arr, total) = get(ctx, "$path${sep}since=$since&limit=100&start=$start&format=json") { r ->
                    check(r.isSuccessful) { if (r.code == 403) "Zotero refused the key (403)." else "Zotero answered HTTP ${r.code}." }
                    r.header("Last-Modified-Version")?.toLongOrNull()?.let { if (it > newest) newest = it }
                    JSONArray(r.body?.string().orEmpty()) to (r.header("Total-Results")?.toIntOrNull() ?: 0)
                } as Pair<*, *>
                arr as JSONArray
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(each)
                start += arr.length()
                if (arr.length() == 0 || start >= (total as Int)) break
            }
        }
        fetchAll("collections") { o ->
            val d = o.optJSONObject("data") ?: return@fetchAll
            collections[d.getString("key")] = d; changed++
        }
        fetchAll("items/top") { o ->
            val d = o.optJSONObject("data") ?: return@fetchAll
            val k = d.getString("key")
            if (d.optString("itemType") in skipTypes) items.remove(k) else { items[k] = d; bibCache.keys.removeAll { it.startsWith("$k:") } }
            changed++
        }
        if (since > 0) {
            val del = get(ctx, "deleted?since=$since") { r -> if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()) else null } as JSONObject?
            del?.optJSONArray("collections")?.let { a -> for (i in 0 until a.length()) collections.remove(a.optString(i)) }
            del?.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) { items.remove(a.optString(i)); changed++ } }
        }
        saveCache()
        p.edit().putLong("libVersion", newest).putLong("lastSync", System.currentTimeMillis()).apply()
        return changed
    }

    /** Fetches one item again (after a write, or when Zotero says it changed). */
    @Synchronized fun refresh(ctx: Context, itemKey: String): JSONObject? {
        init(ctx)
        val o = get(ctx, "items/$itemKey?format=json") { r -> if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()) else null } as JSONObject?
        val d = o?.optJSONObject("data") ?: return null
        items[itemKey] = d
        bibCache.keys.removeAll { it.startsWith("$itemKey:") }
        saveCache()
        return d
    }

    // ------------------------------------------------------------------------------ reading the cache

    @Synchronized fun collectionList(): List<JSONObject> = collections.values.sortedBy { it.optString("name").lowercase(Locale.ROOT) }
    @Synchronized fun item(key: String): JSONObject? = items[key]
    @Synchronized fun itemCount(): Int = items.size
    @Synchronized fun collectionCount(): Int = collections.size

    private fun parentOf(c: JSONObject): String = c.opt("parentCollection").let { if (it is String) it else "" }

    /** Items in a collection (and optionally its sub-collections), or the whole library; [query] filters. */
    @Synchronized fun list(collection: String?, query: String?, unfiled: Boolean = false, deep: Boolean = false): List<JSONObject> {
        val wanted: Set<String>? = collection?.takeIf { it.isNotBlank() }?.let { root ->
            if (!deep) setOf(root) else {
                val out = mutableSetOf(root)
                var grew = true
                while (grew) { grew = false; collections.values.forEach { c -> if (parentOf(c) in out && out.add(c.getString("key"))) grew = true } }
                out
            }
        }
        val terms = MetadataLookup.norm(query.orEmpty()).split(' ').filter { it.isNotBlank() }
        return items.values.filter { d ->
            val cols = d.optJSONArray("collections")
            val inCol = wanted == null || (cols != null && (0 until cols.length()).any { cols.optString(it) in wanted })
            val unf = !unfiled || cols == null || cols.length() == 0
            inCol && unf && (terms.isEmpty() || MetadataLookup.norm(searchText(d)).let { h -> terms.all { h.contains(it) } })
        }.sortedWith(compareBy({ creatorsLine(it).lowercase(Locale.ROOT) }, { year(it) }))
    }

    private fun searchText(d: JSONObject) = listOf(d.optString("title"), creatorsLine(d), d.optString("date"), d.optString("publicationTitle"),
        d.optString("bookTitle"), d.optString("DOI"), d.optString("ISBN"), citationKey(d), tagsOf(d).joinToString(" ")).joinToString(" ")

    fun tagsOf(d: JSONObject): List<String> = d.optJSONArray("tags")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("tag")?.takeIf { t -> t.isNotBlank() } } }.orEmpty()

    fun collectionNames(d: JSONObject): List<String> = synchronized(this) {
        d.optJSONArray("collections")?.let { a -> (0 until a.length()).mapNotNull { collections[a.optString(it)]?.optString("name") } }.orEmpty()
    }

    fun creators(d: JSONObject, type: String): List<String> = d.optJSONArray("creators")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("creatorType") == type }.map {
            it.optString("name").ifBlank { "${it.optString("firstName")} ${it.optString("lastName")}".trim() }
        }.filter { it.isNotBlank() }
    }.orEmpty()

    /** Main creators: authors, or editors when there are none. */
    fun creatorsLine(d: JSONObject): String = creators(d, "author").ifEmpty { creators(d, "editor").map { "$it (ed.)" } }.joinToString("; ")

    fun year(d: JSONObject): String = Regex("\\b(1[5-9]\\d{2}|20\\d{2})\\b").find(d.optString("date"))?.value.orEmpty()

    private fun extraValue(d: JSONObject, label: String): String =
        d.optString("extra").lines().firstOrNull { it.trim().startsWith("$label:", ignoreCase = true) }?.substringAfter(':')?.trim().orEmpty()

    /** Zotero's Citation Key field, or (older Better BibTeX) "Citation Key: …" in Extra. */
    fun citationKey(d: JSONObject): String = d.optString("citationKey").trim().ifBlank { extraValue(d, "Citation Key") }

    fun entryType(itemType: String): String = when (itemType) {
        "book" -> "book"
        "bookSection", "encyclopediaArticle", "dictionaryEntry" -> "incollection"
        "journalArticle", "magazineArticle", "newspaperArticle" -> "article"
        "thesis" -> "phdthesis"
        "report" -> "techreport"
        else -> "misc"
    }

    fun itemType(entryType: String, category: String): String = when (entryType) {
        "article" -> "journalArticle"
        "incollection", "inbook" -> "bookSection"
        "phdthesis", "mastersthesis" -> "thesis"
        "techreport" -> "report"
        "book" -> "book"
        else -> if (category.equals("ARTICLE", true)) "journalArticle" else "book"
    }

    fun summary(d: JSONObject): JSONObject = JSONObject()
        .put("key", d.optString("key"))
        .put("title", d.optString("title").ifBlank { "(untitled)" })
        .put("creators", creatorsLine(d))
        .put("year", year(d))
        .put("itemType", d.optString("itemType"))
        .put("container", d.optString("publicationTitle").ifBlank { d.optString("bookTitle") })
        .put("citationKey", citationKey(d))
        .put("collections", JSONArray(collectionNames(d)))
        .put("linked", docFor(d.optString("key"))?.let { JSONObject().put("doc", it.first).apply { it.second?.let { c -> put("chapter", c) } } } ?: JSONObject.NULL)

    // ------------------------------------------------------------------------------ Zotero item ↔ the app's record

    /** A Zotero item as a lookup result, so it can be compared field by field like “Look up details”. */
    fun toFound(d: JSONObject): MetadataLookup.Found {
        val type = d.optString("itemType")
        var r = BibRecord(entryType = entryType(type))
        fun put(f: BibField, v: String?) { val s = v?.trim().orEmpty(); if (s.isNotBlank()) r = r.with(f, s) }
        val title = d.optString("title").trim()
        val cut = title.indexOf(": ")
        if (cut > 0 && type != "journalArticle") { put(BibField.TITLE, title.substring(0, cut)); put(BibField.SUBTITLE, title.substring(cut + 2)) } else put(BibField.TITLE, title)
        put(BibField.YEAR, BibFieldCleaner.clean(BibField.YEAR, d.optString("date")).ifBlank { d.optString("date") })
        put(BibField.PUBLISHER, d.optString("publisher").ifBlank { d.optString("university").ifBlank { d.optString("institution") } })
        put(BibField.PLACE, d.optString("place"))
        put(BibField.EDITION, d.optString("edition"))
        put(BibField.VOLUME, d.optString("volume"))
        put(BibField.ISSUE, d.optString("issue"))
        put(BibField.PAGES, d.optString("pages"))
        put(BibField.SERIES, d.optString("series"))
        put(BibField.URL, d.optString("url"))
        put(BibField.LANGUAGE, d.optString("language"))
        put(BibField.DOI, d.optString("DOI").ifBlank { extraValue(d, "DOI") })
        put(BibField.ISBN, d.optString("ISBN").split(' ', ',', ';').map { BibFieldCleaner.clean(BibField.ISBN, it) }.firstOrNull { it.isNotBlank() } ?: d.optString("ISBN"))
        put(BibField.ISSN, d.optString("ISSN"))
        put(BibField.JOURNAL, d.optString("publicationTitle").ifBlank { d.optString("websiteTitle") })
        put(BibField.BOOKTITLE, d.optString("bookTitle").ifBlank { d.optString("proceedingsTitle").ifBlank { d.optString("encyclopediaTitle") } })
        put(BibField.EDITOR, People.join(creators(d, "editor")))
        put(BibField.TRANSLATOR, People.join(creators(d, "translator")))
        put(BibField.CITEKEY, citationKey(d))
        val authors = People.join(creators(d, "author"))
        return MetadataLookup.Found(r, authors, d.optString("abstractNote").trim(), listOf("Zotero"))
    }

    // ------------------------------------------------------------------------------ links

    @Synchronized fun linkOf(docId: Long): String? = links["doc:$docId"]
    @Synchronized fun chapterLinkOf(docId: Long, chapter: Int): String? = links["ch:$docId:$chapter"]
    @Synchronized fun chapterLinks(docId: Long): Map<Int, String> =
        links.filterKeys { it.startsWith("ch:$docId:") }.mapKeys { it.key.substringAfterLast(':').toInt() }

    /** (document id, chapter index or null) linked to a Zotero item. */
    @Synchronized fun docFor(itemKey: String): Pair<Long, Int?>? = links.entries.firstOrNull { it.value == itemKey }?.key?.let { k ->
        val parts = k.split(':')
        if (parts[0] == "doc") parts[1].toLong() to null else parts[1].toLong() to parts[2].toInt()
    }

    @Synchronized fun link(ctx: Context, docId: Long, itemKey: String, chapter: Int? = null) {
        init(ctx)
        links.entries.removeAll { it.value == itemKey }
        links[if (chapter == null) "doc:$docId" else "ch:$docId:$chapter"] = itemKey
        saveLinks()
    }

    @Synchronized fun unlink(ctx: Context, docId: Long, chapter: Int? = null) {
        init(ctx)
        links.remove(if (chapter == null) "doc:$docId" else "ch:$docId:$chapter")
        saveLinks()
    }

    // ------------------------------------------------------------------------------ matching

    private fun isbnSet(s: String): Set<String> = s.split(' ', ',', ';').map { BibFieldCleaner.clean(BibField.ISBN, it) }.filter { it.isNotBlank() }
        .map { isbn13(it) }.toSet()

    private fun isbn13(s: String): String {
        val d = s.filter { it.isDigit() || it == 'X' || it == 'x' }.uppercase(Locale.ROOT)
        if (d.length != 10) return d
        val core = "978" + d.take(9)
        val sum = core.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return core + ((10 - sum % 10) % 10)
    }

    private fun family(name: String): String = MetadataLookup.norm(if (name.contains(',')) name.substringBefore(',') else name.trim().substringAfterLast(' '))

    private fun score(d: JSONObject, doc: DocumentEntity, rec: BibRecord): Pair<Int, String>? {
        val doi = BibFieldCleaner.clean(BibField.DOI, d.optString("DOI").ifBlank { extraValue(d, "DOI") }).lowercase(Locale.ROOT)
        val myDoi = BibFieldCleaner.clean(BibField.DOI, rec[BibField.DOI]).lowercase(Locale.ROOT)
        if (doi.isNotBlank() && doi == myDoi) return 4 to "Same DOI"
        val a = isbnSet(d.optString("ISBN")); val b = isbnSet(rec[BibField.ISBN])
        if (a.isNotEmpty() && a.intersect(b).isNotEmpty()) return 4 to "Same ISBN"
        val zt = MetadataLookup.norm(d.optString("title"))
        val main = MetadataLookup.norm(rec[BibField.TITLE].ifBlank { doc.title })
        val full = MetadataLookup.norm(listOf(rec[BibField.TITLE].ifBlank { doc.title }, rec[BibField.SUBTITLE]).filter { it.isNotBlank() }.joinToString(" "))
        if (zt.isBlank() || main.isBlank()) return null
        val sameTitle = zt == main || zt == full || (zt.startsWith(main) && main.length >= 12)
        val zf = (creators(d, "author") + creators(d, "editor")).map { family(it) }.toSet()
        val mf = (People.split(doc.author) + People.split(rec[BibField.EDITOR])).map { family(it) }.toSet()
        val sharesName = zf.intersect(mf).isNotEmpty()
        val yearOk = year(d).isNotBlank() && year(d) == Citations.year(doc, rec)
        if (sameTitle && sharesName && yearOk) return 3 to "Same title, year and author"
        if (sameTitle && (sharesName || yearOk)) return 2 to "Same title"
        val words = main.split(' ').filter { it.length > 3 }
        if (words.size >= 2 && words.count { zt.contains(it) } >= (words.size * 0.7).coerceAtLeast(2.0)) return 1 to "Similar title"
        return null
    }

    /** Books on the tablet that may be this Zotero item, best first. */
    fun suggestDocs(d: JSONObject, docs: List<DocumentEntity>): List<Suggestion> =
        docs.mapNotNull { doc -> score(d, doc, BibStore.get(doc.id))?.let { (s, why) -> Suggestion(doc, why, s) } }.sortedByDescending { it.strength }.take(6)

    /** Zotero items that may be this book, best first. */
    @Synchronized fun suggestItems(doc: DocumentEntity, rec: BibRecord): List<ItemSuggestion> =
        items.values.mapNotNull { d -> score(d, doc, rec)?.let { (s, why) -> ItemSuggestion(d.getString("key"), why, s) } }.sortedByDescending { it.strength }.take(6)

    // ------------------------------------------------------------------------------ Zotero's citations

    fun cslStyle(style: CitationStyle): String = when (style) {
        CitationStyle.CHICAGO_AUTHOR_DATE -> "chicago-author-date"
        CitationStyle.APA7 -> "apa"
        CitationStyle.CHICAGO_NOTES -> "chicago-note-bibliography"
        CitationStyle.HARVARD -> "harvard-cite-them-right"
        CitationStyle.MLA9 -> "modern-language-association"
    }

    /** Zotero's reference for an item in [style], as HTML (Zotero formats it with the same CSL style its Word plugin uses). */
    fun bib(ctx: Context, itemKey: String, style: CitationStyle): String {
        init(ctx)
        val d = synchronized(this) { items[itemKey] }
        val cacheKey = "$itemKey:${d?.optInt("version")}:${style.name}"
        synchronized(this) { bibCache[cacheKey] }?.let { return it }
        val html = get(ctx, "items/$itemKey?format=bib&style=${cslStyle(style)}&locale=en-GB") { r ->
            check(r.isSuccessful) { "Zotero answered HTTP ${r.code}." }
            r.body?.string().orEmpty()
        } as String
        synchronized(this) { bibCache[cacheKey] = html }
        return html
    }

    // ------------------------------------------------------------------------------ writing to Zotero

    private fun template(itemType: String): JSONObject {
        synchronized(this) { templates[itemType] }?.let { return it }
        val t = request(Request.Builder().url("$BASE/items/new?itemType=$itemType").header("Zotero-API-Version", "3").build()) { r ->
            check(r.isSuccessful) { "Zotero answered HTTP ${r.code} for the $itemType template." }
            JSONObject(r.body?.string().orEmpty())
        }
        synchronized(this) { templates[itemType] = t }
        return t
    }

    private fun zoteroField(f: BibField, itemType: String): String? = when (f) {
        BibField.YEAR -> "date"
        BibField.PUBLISHER -> when (itemType) { "thesis" -> "university"; "report" -> "institution"; else -> "publisher" }
        BibField.PLACE -> "place"
        BibField.EDITION -> "edition"
        BibField.VOLUME -> "volume"
        BibField.ISSUE -> "issue"
        BibField.PAGES -> "pages"
        BibField.SERIES -> "series"
        BibField.URL -> "url"
        BibField.LANGUAGE -> "language"
        BibField.DOI -> "DOI"
        BibField.ISBN -> "ISBN"
        BibField.ISSN -> "ISSN"
        BibField.JOURNAL -> if (itemType == "webpage") "websiteTitle" else "publicationTitle"
        BibField.BOOKTITLE -> when (itemType) { "conferencePaper" -> "proceedingsTitle"; "encyclopediaArticle" -> "encyclopediaTitle"; else -> "bookTitle" }
        BibField.CITEKEY -> "citationKey"
        else -> null
    }

    private fun person(name: String, type: String): JSONObject {
        val n = name.trim()
        return if (n.contains(',')) JSONObject().put("creatorType", type).put("lastName", n.substringBefore(',').trim()).put("firstName", n.substringAfter(',').trim())
        else if (!n.contains(' ')) JSONObject().put("creatorType", type).put("name", n)
        else JSONObject().put("creatorType", type).put("firstName", n.substringBeforeLast(' ').trim()).put("lastName", n.substringAfterLast(' ').trim())
    }

    /**
     * Writes [changes] into item [d] (a copy is returned; nothing is sent). Keys: AUTHOR, EDITOR,
     * TRANSLATOR, TITLE (the whole title), ABSTRACT, TYPE or a [BibField] name. Fields the item type
     * has no place for go into Extra ("DOI: …", "Citation Key: …") when that makes sense, else are skipped.
     */
    private fun applyChanges(d: JSONObject, changes: Map<String, String>): Pair<JSONObject, List<String>> {
        val out = JSONObject(d.toString())
        val type = out.optString("itemType")
        val valid = template(type)
        val skipped = mutableListOf<String>()
        val extra = out.optString("extra").lines().filter { it.isNotBlank() }.toMutableList()
        fun setExtra(label: String, v: String) {
            extra.removeAll { it.trim().startsWith("$label:", ignoreCase = true) }
            if (v.isNotBlank()) extra += "$label: $v"
        }
        changes.forEach { (k, v0) ->
            val v = v0.trim()
            when (k) {
                "AUTHOR", "EDITOR", "TRANSLATOR" -> {
                    val role = k.lowercase(Locale.ROOT)
                    val old = out.optJSONArray("creators") ?: JSONArray()
                    val keep = (0 until old.length()).mapNotNull { old.optJSONObject(it) }.filter { it.optString("creatorType") != role }
                    val mine = People.split(v).map { person(it, role) }
                    // Authors first, then editors, then the rest, as Zotero lists them.
                    val order = listOf("author", "editor", "translator")
                    out.put("creators", JSONArray((keep + mine).sortedBy { order.indexOf(it.optString("creatorType")).let { i -> if (i < 0) 9 else i } }))
                }
                "TITLE" -> out.put("title", v)
                "ABSTRACT" -> if (valid.has("abstractNote")) out.put("abstractNote", v) else skipped += k
                else -> {
                    val f = runCatching { BibField.valueOf(k) }.getOrNull()
                    val zf = f?.let { zoteroField(it, type) }
                    when {
                        zf != null && valid.has(zf) -> out.put(zf, v)
                        f == BibField.DOI -> setExtra("DOI", v)
                        f == BibField.CITEKEY -> setExtra("Citation Key", v)
                        else -> skipped += k
                    }
                }
            }
        }
        out.put("extra", extra.joinToString("\n"))
        return out to skipped
    }

    private fun ensureWrite(ctx: Context) {
        val a = account(ctx) ?: throw IllegalArgumentException("Connect Zotero first.")
        require(a.canWrite) { "Your Zotero key is read-only. Make a key with write access at zotero.org/settings/keys and paste it in Settings ▸ Zotero." }
    }

    /** Sends [changes] (see [applyChanges]) to Zotero item [itemKey]. Returns the fields that had no place in Zotero. */
    fun push(ctx: Context, itemKey: String, changes: Map<String, String>): List<String> {
        init(ctx)
        ensureWrite(ctx)
        val d = synchronized(this) { items[itemKey] } ?: refresh(ctx, itemKey) ?: throw NoSuchElementException("That Zotero item was not found.")
        val (next, skipped) = applyChanges(d, changes)
        val patch = JSONObject()
        next.keys().forEach { k -> if (k != "key" && k != "version" && k != "dateAdded" && k != "dateModified" && next.opt(k).toString() != d.opt(k)?.toString()) patch.put(k, next.get(k)) }
        if (patch.length() == 0) return skipped
        val acct = account(ctx)!!
        val req = Request.Builder().url("$BASE/users/${acct.userId}/items/$itemKey")
            .header("Zotero-API-Key", key(ctx)).header("Zotero-API-Version", "3")
            .header("If-Unmodified-Since-Version", d.optInt("version").toString())
            .patch(patch.toString().toRequestBody(JSON_TYPE)).build()
        request(req) { r ->
            when {
                r.code == 412 -> { refresh(ctx, itemKey); throw IllegalStateException("The item changed in Zotero meanwhile. Check it again and send once more.") }
                r.code == 403 -> throw IllegalArgumentException("Zotero did not allow the change (the key may be read-only).")
                !r.isSuccessful -> throw IllegalStateException("Zotero answered HTTP ${r.code}: ${r.body?.string().orEmpty().take(200)}")
            }
        }
        refresh(ctx, itemKey)
        return skipped
    }

    /** Creates a Zotero item from a book on the tablet and links them. Returns its key. */
    fun create(ctx: Context, doc: DocumentEntity, rec: BibRecord, collection: String? = null): String {
        init(ctx)
        ensureWrite(ctx)
        val type = itemType(rec.entryType, doc.category)
        val base = JSONObject(template(type).toString())
        base.put("creators", JSONArray())
        val changes = LinkedHashMap<String, String>()
        changes["TITLE"] = listOf(rec[BibField.TITLE].ifBlank { doc.title }, rec[BibField.SUBTITLE]).filter { it.isNotBlank() }.joinToString(": ")
        if (doc.author.isNotBlank() && !FolderBooks.isFolder(doc)) changes["AUTHOR"] = doc.author
        BibField.entries.filter { it != BibField.TITLE && it != BibField.SUBTITLE }.forEach { f -> rec[f].takeIf { it.isNotBlank() }?.let { changes[f.name] = it } }
        if (FolderBooks.isFolder(doc)) EditedVolumes.editors(doc).takeIf { it.isNotBlank() }?.let { changes["EDITOR"] = it }
        if (doc.summary.isNotBlank()) changes["ABSTRACT"] = doc.summary
        changes["CITEKEY"] = Citations.citeKey(doc, rec)
        val (item, _) = applyChanges(base, changes)
        if (collection != null) item.put("collections", JSONArray().put(collection))
        val tags = NoteSuggestionEngine.parseTagsList(doc.tags)
        if (tags.isNotEmpty()) item.put("tags", JSONArray(tags.map { JSONObject().put("tag", it) }))
        val acct = account(ctx)!!
        val req = Request.Builder().url("$BASE/users/${acct.userId}/items")
            .header("Zotero-API-Key", key(ctx)).header("Zotero-API-Version", "3")
            .header("Zotero-Write-Token", UUID.randomUUID().toString().replace("-", ""))
            .post(JSONArray().put(item).toString().toRequestBody(JSON_TYPE)).build()
        val newKey = request(req) { r ->
            val body = r.body?.string().orEmpty()
            check(r.isSuccessful) { "Zotero answered HTTP ${r.code}: ${body.take(200)}" }
            val o = JSONObject(body)
            o.optJSONObject("successful")?.optJSONObject("0")?.optString("key")
                ?: o.optJSONObject("success")?.optString("0")
                ?: throw IllegalStateException(o.optJSONObject("failed")?.optJSONObject("0")?.optString("message") ?: "Zotero did not create the item.")
        }
        refresh(ctx, newKey)
        link(ctx, doc.id, newKey)
        return newKey
    }

    /** Collection names as the app's tags (spaces become _), added to [current] without removing any. */
    fun withCollectionTags(current: String, d: JSONObject): String {
        val names = collectionNames(d).map { it.trim().replace(Regex("\\s+"), "_") }.filter { it.isNotBlank() }
        if (names.isEmpty()) return current
        val list = NoteSuggestionEngine.parseTagsList(current)
        return NoteSuggestionEngine.joinTagsList((list + names).distinctBy { it.lowercase(Locale.ROOT) })
    }
}
