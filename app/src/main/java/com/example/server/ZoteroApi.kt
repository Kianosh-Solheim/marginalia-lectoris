package com.example.server

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.reader.BibField
import com.example.reader.BibStore
import com.example.reader.CitationStyle
import com.example.reader.Citations
import com.example.reader.EditedVolumes
import com.example.reader.FolderBooks
import com.example.reader.MetadataLookup
import com.example.reader.People
import com.example.reader.Zotero
import org.json.JSONArray
import org.json.JSONObject

/**
 * /api/zotero/…: the web view's Zotero tab and the Zotero section of the Info side panel.
 *
 *   GET    status                     connected?, user, key's last 4 characters, counts
 *   POST   key {key} · DELETE key     connect / disconnect
 *   POST   sync                       fetch what changed in Zotero
 *   GET    collections                the collection tree (flat, with parents)
 *   GET    items?collection=&q=&unfiled=1&deep=1
 *   GET    item/{key}?style=          fields, Zotero's reference, link, suggested books
 *   GET    doc/{id}[?chapter=i]       the linked item compared with the book (or chapter)
 *   POST   link {key, doc, chapter?} · POST unlink {doc, chapter?}
 *   POST   push {key, fields}         send the app's values for these fields to Zotero
 *   POST   create {doc}               a new Zotero item from a book, linked
 *   POST   chapter-apply {doc, chapter, fields}
 *   POST   import {collection}        link what matches, add collection tags, list the rest
 *   POST   lookup {identifier}        DOI, ISBN, PMID, arXiv or ISSN
 */
class ZoteroApi(private val context: Context) {
    private val db = AppDatabase.getInstance(context)

    private fun style(req: HttpRequest): CitationStyle =
        req.query["style"]?.let { s -> runCatching { CitationStyle.valueOf(s) }.getOrNull() } ?: Citations.defaultStyle(context)

    private fun ok(o: JSONObject, code: Int = 200) = HttpResponse.json(o.toString(), code)

    suspend fun route(req: HttpRequest, s: List<String>): HttpResponse {
        Zotero.init(context)
        BibStore.init(context)
        EditedVolumes.init(context)
        val m = req.method
        val what = s.firstOrNull().orEmpty()
        return when {
            what == "status" -> ok(status())
            what == "key" && m == "POST" -> {
                Zotero.connect(context, JSONObject(req.bodyText()).optString("key"))
                runCatching { Zotero.sync(context) }
                ok(status())
            }
            what == "key" && m == "DELETE" -> { Zotero.disconnect(context); ok(status()) }
            what == "sync" && m == "POST" -> {
                val n = Zotero.sync(context)
                ok(status().put("changed", n))
            }
            what == "collections" -> ok(JSONObject().put("collections", JSONArray().apply {
                Zotero.collectionList().forEach { c ->
                    put(JSONObject().put("key", c.optString("key")).put("name", c.optString("name"))
                        .put("parent", c.opt("parentCollection").let { if (it is String) it else "" })
                        .put("count", Zotero.list(c.optString("key"), null).size))
                }
            }).put("total", Zotero.itemCount()).put("unfiled", Zotero.list(null, null, unfiled = true).size))
            what == "items" -> {
                val list = Zotero.list(req.query["collection"], req.query["q"], req.query["unfiled"] == "1", req.query["deep"] == "1")
                ok(JSONObject().put("items", JSONArray().apply { list.take(2000).forEach { put(Zotero.summary(it)) } }).put("count", list.size))
            }
            what == "item" && s.size >= 2 -> ok(itemJson(s[1], style(req)))
            what == "doc" && s.size >= 2 -> ok(docCheck(s[1].toLong(), req.query["chapter"]?.toIntOrNull(), style(req), req.query["bib"] == "1"))
            what == "link" && m == "POST" -> {
                val b = JSONObject(req.bodyText())
                val doc = docOf(b.getLong("doc"))
                val key = b.getString("key")
                val chapter = if (b.has("chapter") && !b.isNull("chapter")) b.getInt("chapter") else null
                Zotero.link(context, doc.id, key, chapter)
                if (chapter == null) addCollectionTags(doc, key)
                ok(docCheck(doc.id, chapter, style(req)))
            }
            what == "unlink" && m == "POST" -> {
                val b = JSONObject(req.bodyText())
                val chapter = if (b.has("chapter") && !b.isNull("chapter")) b.getInt("chapter") else null
                Zotero.unlink(context, b.getLong("doc"), chapter)
                ok(JSONObject().put("ok", true))
            }
            what == "push" && m == "POST" -> {
                val b = JSONObject(req.bodyText())
                val fo = b.getJSONObject("fields")
                val changes = LinkedHashMap<String, String>()
                fo.keys().forEach { k -> changes[k] = fo.optString(k) }
                require(changes.isNotEmpty()) { "Choose what to send to Zotero." }
                val skipped = Zotero.push(context, b.getString("key"), changes)
                ok(JSONObject().put("ok", true).put("skipped", JSONArray(skipped))
                    .put("message", "Sent to Zotero" + if (skipped.isNotEmpty()) " (no place in Zotero for: ${skipped.joinToString(", ")})" else ""))
            }
            what == "create" && m == "POST" -> {
                val b = JSONObject(req.bodyText())
                val doc = docOf(b.getLong("doc"))
                val key = Zotero.create(context, doc, BibStore.get(doc.id), b.optString("collection").takeIf { it.isNotBlank() })
                ok(docCheck(doc.id, null, style(req)).put("created", key))
            }
            what == "chapter-apply" && m == "POST" -> {
                val b = JSONObject(req.bodyText())
                val doc = docOf(b.getLong("doc"))
                val idx = b.getInt("chapter")
                val v = EditedVolumes.get(context, doc.id) ?: throw NoSuchElementException("This book has no chapter list.")
                val c = v.chapters.getOrNull(idx) ?: throw NoSuchElementException("No such chapter.")
                val f = b.getJSONObject("fields")
                var next = c
                if (f.has("TITLE")) next = next.copy(title = f.optString("TITLE").trim(), isManual = true)
                if (f.has("AUTHOR")) next = next.copy(authors = People.join(People.split(f.optString("AUTHOR"))), authorSource = "user", isManual = true)
                if (f.has("PAGES")) next = next.copy(printedPages = f.optString("PAGES").trim(), isManual = true)
                if (f.has("DOI")) next = next.copy(doi = f.optString("DOI").trim(), isManual = true)
                if (f.has("ABSTRACT")) next = next.copy(abstract = f.optString("ABSTRACT").trim(), isManual = true)
                EditedVolumes.put(context, doc.id, v.copy(chapters = v.chapters.toMutableList().also { it[idx] = next }))
                ok(docCheck(doc.id, idx, style(req)))
            }
            what == "import" && m == "POST" -> ok(importCollection(JSONObject(req.bodyText()).getString("collection")))
            what == "lookup" && m == "POST" -> {
                val id = JSONObject(req.bodyText()).optString("identifier").trim()
                require(id.isNotBlank()) { "Type a DOI, ISBN, PMID or arXiv ID." }
                val f = runCatching { MetadataLookup.byIdentifier(id) }.getOrElse { throw IllegalArgumentException(it.message ?: "The lookup failed.") }
                    ?: throw NoSuchElementException("Nothing was found for “${id.take(60)}”.")
                ok(JSONObject().put("found", MetadataLookup.toJson(f)))
            }
            else -> throw NoSuchElementException("Unknown address.")
        }
    }

    private suspend fun docOf(id: Long): DocumentEntity = db.documentDao().getDocumentById(id) ?: throw NoSuchElementException("This book is no longer in the library.")

    private fun status(): JSONObject {
        val a = Zotero.account(context)
        return JSONObject().put("connected", a != null)
            .put("username", a?.username.orEmpty()).put("userId", a?.userId.orEmpty()).put("canWrite", a?.canWrite == true)
            .put("last4", if (a != null) Zotero.keyTail(context) else "")
            .put("lastSync", Zotero.lastSync(context)).put("items", Zotero.itemCount()).put("collections", Zotero.collectionCount())
            .put("style", Citations.defaultStyle(context).name).put("styleLabel", Citations.defaultStyle(context).label)
    }

    private suspend fun addCollectionTags(doc: DocumentEntity, key: String) {
        val d = Zotero.item(key) ?: return
        val fresh = db.documentDao().getDocumentById(doc.id) ?: doc
        val tags = Zotero.withCollectionTags(fresh.tags, d)
        if (tags != fresh.tags) db.documentDao().updateTags(doc.id, tags)
    }

    private fun foundJson(d: JSONObject): JSONObject = MetadataLookup.toJson(Zotero.toFound(d))

    private suspend fun itemJson(key: String, style: CitationStyle): JSONObject {
        val d = Zotero.item(key) ?: Zotero.refresh(context, key) ?: throw NoSuchElementException("That item is not in your Zotero library.")
        val docs = db.documentDao().getAllDocumentsSnapshot()
        val o = Zotero.summary(d).put("found", foundJson(d)).put("tags", JSONArray(Zotero.tagsOf(d)))
            .put("date", d.optString("date")).put("abstract", d.optString("abstractNote"))
        o.put("bib", runCatching { Zotero.bib(context, key, style) }.getOrElse { "" })
        o.put("style", style.name)
        Zotero.docFor(key)?.let { (id, ch) -> docs.firstOrNull { it.id == id }?.let { o.put("linkedDoc", JSONObject().put("id", it.id).put("title", it.title).put("author", it.author).apply { ch?.let { c -> put("chapter", c) } }) } }
        o.put("suggestions", JSONArray().apply {
            Zotero.suggestDocs(d, docs).filter { s -> Zotero.linkOf(s.document.id) == null }.forEach { s ->
                put(JSONObject().put("id", s.document.id).put("title", s.document.title).put("author", s.document.author).put("reason", s.reason).put("strength", s.strength))
            }
        })
        return o
    }

    /** The link of a book (or chapter) and what Zotero has, side by side with the app's own record. */
    private suspend fun docCheck(docId: Long, chapter: Int?, style: CitationStyle, withBib: Boolean = false): JSONObject {
        val doc = docOf(docId)
        val rec = BibStore.get(doc.id)
        val o = JSONObject().put("connected", Zotero.account(context) != null).put("canWrite", Zotero.account(context)?.canWrite == true)
            .put("style", style.name).put("styleLabel", style.label)
        val vol = EditedVolumes.get(context, doc.id)?.takeIf { it.enabled }
        // The app's side: the book, or one chapter as a book section.
        val (mine, mineRec) = if (chapter != null && vol != null && chapter in vol.chapters.indices) EditedVolumes.chapterAsDocument(doc, vol, chapter) else doc to rec
        val appCiteKey = Citations.citeKey(mine, mineRec)
        o.put("app", JSONObject()
            .put("fields", JSONObject().apply { mineRec.fields.forEach { (k, v) -> put(k.name, v) }; put(BibField.TITLE.name, mineRec[BibField.TITLE].ifBlank { mine.title }) })
            .put("author", if (FolderBooks.isFolder(mine)) "" else mine.author).put("abstract", mine.summary).put("entryType", mineRec.entryType)
            .put("citationKey", appCiteKey).put("reference", runCatching { Citations.reference(style, mine, mineRec) }.getOrDefault("")))
        if (chapter == null && vol != null) o.put("chapterLinks", JSONObject().apply { Zotero.chapterLinks(doc.id).forEach { (i, k) -> put(i.toString(), k) } })
        val key = if (chapter == null) Zotero.linkOf(doc.id) else Zotero.chapterLinkOf(doc.id, chapter)
        if (key == null) {
            o.put("linked", false)
            if (Zotero.account(context) != null) o.put("suggestions", JSONArray().apply {
                Zotero.suggestItems(mine, mineRec).forEach { s -> Zotero.item(s.key)?.let { d -> put(Zotero.summary(d).put("reason", s.reason).put("strength", s.strength)) } }
            })
            return o
        }
        o.put("linked", true).put("key", key)
        val d = Zotero.item(key)
        if (d == null) { o.put("missing", true); return o }
        o.put("item", Zotero.summary(d).put("found", foundJson(d)))
        // Zotero's own reference needs the internet: only when asked for.
        if (withBib) o.put("bib", runCatching { Zotero.bib(context, key, style) }.getOrElse { "" })
        o.put("zoteroCitationKey", Zotero.citationKey(d))
        return o
    }

    private suspend fun importCollection(collection: String): JSONObject {
        val docs = db.documentDao().getAllDocumentsSnapshot()
        val linked = JSONArray(); val already = JSONArray(); val unmatched = JSONArray(); val unsure = JSONArray()
        var tagged = 0
        Zotero.list(collection, null).forEach { d ->
            val key = d.getString("key")
            val existing = Zotero.docFor(key)
            if (existing != null) {
                if (existing.second == null) docs.firstOrNull { it.id == existing.first }?.let { doc ->
                    val t = Zotero.withCollectionTags(doc.tags, d); if (t != doc.tags) { db.documentDao().updateTags(doc.id, t); tagged++ }
                }
                already.put(Zotero.summary(d)); return@forEach
            }
            val best = Zotero.suggestDocs(d, docs).filter { Zotero.linkOf(it.document.id) == null }
            val top = best.firstOrNull()
            if (top != null && top.strength >= 3 && best.count { it.strength >= 3 } == 1) {
                Zotero.link(context, top.document.id, key)
                val t = Zotero.withCollectionTags(top.document.tags, d)
                if (t != top.document.tags) { db.documentDao().updateTags(top.document.id, t); tagged++ }
                linked.put(Zotero.summary(d).put("doc", JSONObject().put("id", top.document.id).put("title", top.document.title)).put("reason", top.reason))
            } else if (best.isNotEmpty()) {
                unsure.put(Zotero.summary(d).put("suggestions", JSONArray().apply { best.forEach { s -> put(JSONObject().put("id", s.document.id).put("title", s.document.title).put("author", s.document.author).put("reason", s.reason)) } }))
            } else unmatched.put(Zotero.summary(d))
        }
        return JSONObject().put("linked", linked).put("already", already).put("unsure", unsure).put("unmatched", unmatched).put("tagged", tagged)
    }
}
