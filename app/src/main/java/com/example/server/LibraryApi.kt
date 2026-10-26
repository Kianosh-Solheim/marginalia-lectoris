package com.example.server

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.reader.BibField
import com.example.reader.BibRecord
import com.example.reader.BibStore
import com.example.reader.ChapterExporter
import com.example.reader.CitationStyle
import com.example.reader.Citations
import com.example.reader.EditedVolumes
import com.example.reader.FolderBooks
import com.example.reader.MarkdownExporter
import com.example.reader.PdfChapterHelper
import com.example.reader.PdfDocumentRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The web server's routes: the page itself (from assets/web), sign-in with the PIN, and a JSON API
 * for the library, notes, bibliography, exports, uploads and reading. Every change goes through
 * [LibraryOps], which does exactly what the app does for the same change.
 */
class LibraryApi(private val context: Context, private val pinMatches: (String) -> Boolean) {
    private val ops = LibraryOps(context)
    private val db = AppDatabase.getInstance(context)
    private val settingsApi = SettingsApi(context)
    private val failures = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val seen = ConcurrentHashMap<String, Long>()
    private val renderers = LinkedHashMap<Long, PdfDocumentRenderer>()

    init {
        BibStore.init(context)
        EditedVolumes.init(context)
        FolderBooks.init(context)
    }

    fun close() {
        synchronized(renderers) { renderers.values.forEach { runCatching { it.close() } }; renderers.clear() }
    }

    fun activeBrowsers(): Int {
        val cutoff = System.currentTimeMillis() - 120_000
        return seen.values.count { it > cutoff }
    }

    // ------------------------------------------------------------------------------- routing

    fun handle(req: HttpRequest): HttpResponse {
        val p = req.path
        if (!p.startsWith("/api/")) return static(p)

        if (p == "/api/login" && req.method == "POST") return login(req)
        if (p == "/api/session") {
            return HttpResponse.json(JSONObject().put("signedIn", authed(req)).put("device", deviceName()).toString())
        }
        // The tablet's own presenter screen (a WebView on 127.0.0.1) signs in with a token only the app knows.
        if (p == "/api/local-login" && (req.remote.startsWith("127.") || req.remote == "::1" || req.remote.endsWith(":127.0.0.1")) &&
            WebServer.localToken.isNotBlank() && req.query["t"] == WebServer.localToken) {
            val token = Sessions.create(context)
            val next = req.query["next"].orEmpty().takeIf { it.startsWith("#/") } ?: "#/"
            return HttpResponse.text(302, "").header("Location", "/$next")
                .header("Set-Cookie", "$COOKIE=$token; Path=/; Max-Age=${30 * 24 * 3600}; HttpOnly; SameSite=Strict")
        }
        if (!authed(req)) return HttpResponse.error(401, "Sign in with the PIN shown on the tablet.")
        if (req.method != "GET" && req.method != "HEAD" && req.header("x-marginalia") != "1") {
            return HttpResponse.error(403, "Missing request header.")
        }
        WebServer.lastActivity = System.currentTimeMillis()
        seen[req.remote] = System.currentTimeMillis()

        val seg = p.removePrefix("/api/").trimEnd('/').split('/')
        return runBlocking(Dispatchers.IO) {
            try {
                route(req, seg)
            } catch (e: NoSuchElementException) {
                HttpResponse.error(404, e.message ?: "Not found")
            } catch (e: IllegalArgumentException) {
                HttpResponse.error(400, e.message ?: "Bad request")
            } catch (e: Throwable) {
                Log.e("LibraryApi", "Failed: ${req.method} ${req.path}", e)
                HttpResponse.error(500, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun route(req: HttpRequest, s: List<String>): HttpResponse {
        val m = req.method
        when {
            s == listOf("logout") && m == "POST" -> {
                Sessions.remove(context, req.cookie(COOKIE))
                return HttpResponse.json("{}").header("Set-Cookie", "$COOKIE=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict")
            }
            s == listOf("library") && m == "GET" -> return HttpResponse.json(library().toString())
            s == listOf("highlights") && m == "GET" -> return HttpResponse.json(allHighlights().toString())
            s == listOf("tags") && m == "GET" -> {
                val t = com.example.ui.TagIndex.load(context)
                return HttpResponse.json(JSONObject().put("documents", JSONArray(t.documents)).put("notes", JSONArray(t.notes)).toString())
            }
            s == listOf("upload") && m == "POST" -> return upload(req)
            s.firstOrNull() == "presentations" -> return presentationsRoute(req, s.drop(1))
            s.firstOrNull() == "live" -> return liveRoute(req, s.drop(1))
            s == listOf("settings") && m == "GET" -> return HttpResponse.json(settingsApi.get().toString()).header("Cache-Control", "no-store")
            s == listOf("settings") && (m == "PUT" || m == "PATCH") -> return HttpResponse.json(settingsApi.put(JSONObject(req.bodyText())).toString())
            s == listOf("settings", "ai-key") && m == "POST" -> return HttpResponse.json(settingsApi.setKey(JSONObject(req.bodyText())).toString())
            s == listOf("settings", "check-folders") && m == "POST" -> return HttpResponse.json(settingsApi.checkFolders().toString())
            s == listOf("reader-settings") && m == "GET" -> return HttpResponse.json(readerSettingsJson().toString()).header("Cache-Control", "no-store")
            s == listOf("reader-settings") && (m == "PUT" || m == "PATCH") -> {
                saveReaderSettings(JSONObject(req.bodyText()))
                return HttpResponse.json(readerSettingsJson().toString())
            }
            s == listOf("citation-style") && m == "POST" -> {
                val st = runCatching { CitationStyle.valueOf(JSONObject(req.bodyText()).optString("style")) }
                    .getOrElse { throw IllegalArgumentException("Unknown citation style.") }
                Citations.setDefaultStyle(context, st)
                return HttpResponse.json(JSONObject().put("defaultStyle", st.name).toString())
            }
            s == listOf("folders") && m == "POST" -> {
                val b = JSONObject(req.bodyText())
                val title = b.optString("title").trim()
                require(title.isNotBlank()) { "Give the book a title." }
                val f = FolderBooks.create(context, title, b.optString("editors"), if (b.optString("category").equals("ARTICLE", true)) "ARTICLE" else "BOOK")
                if (b.optString("tags").isNotBlank() || b.optString("summary").isNotBlank()) {
                    FolderBooks.updateInfo(context, f.id, title, b.optString("editors"), b.optString("tags"), b.optString("summary"))
                }
                val ids = b.optJSONArray("parts")?.let { a -> (0 until a.length()).map { a.getLong(it) } }.orEmpty()
                if (ids.isNotEmpty()) FolderBooks.addParts(context, f.id, ids)
                return HttpResponse.json(docJson(db.documentDao().getDocumentById(f.id) ?: f, 0).toString(), 201)
            }
            s.size >= 2 && s[0] == "highlight" -> {
                val h = db.highlightDao().getHighlightById(s[1].toLong()) ?: throw NoSuchElementException("No such note.")
                return when (m) {
                    "PATCH" -> {
                        val b = JSONObject(req.bodyText())
                        val updated = h.copy(
                            title = b.optString("title", h.title).trim().ifBlank { h.title },
                            userNotes = b.optString("userNotes", h.userNotes),
                            tags = if (b.has("tags")) com.example.reader.NoteSuggestionEngine.joinTagsList(
                                com.example.reader.NoteSuggestionEngine.parseTagsList(b.optString("tags"))) else h.tags,
                            colorHex = b.optString("colorHex", h.colorHex).ifBlank { h.colorHex },
                            highlightedText = b.optString("highlightedText", h.highlightedText).ifBlank { h.highlightedText }
                        )
                        ops.updateHighlight(updated)
                        HttpResponse.json(hlJson(updated).toString())
                    }
                    "DELETE" -> { ops.deleteHighlight(h); HttpResponse.json("{}") }
                    else -> HttpResponse.json(hlJson(h).toString())
                }
            }
            s.size >= 2 && s[0] == "doc" -> return docRoute(req, s[1].toLong(), s.drop(2))
        }
        throw NoSuchElementException("Unknown address.")
    }

    private suspend fun docRoute(req: HttpRequest, id: Long, rest: List<String>): HttpResponse {
        val doc = db.documentDao().getDocumentById(id) ?: throw NoSuchElementException("This book is no longer in the library.")
        val m = req.method
        val what = rest.firstOrNull().orEmpty()
        return when {
            what == "" && m == "GET" -> {
                val o = detail(doc)
                // Starts the browser-friendly copy of a large PDF; the reader uses it once it is ready.
                if (isPdf(doc)) o.put("webCopy", PdfWebCopy.copyFor(context, doc.id, File(doc.filePath)) != null)
                HttpResponse.json(o.toString())
            }
            what == "" && m == "PATCH" && FolderBooks.isFolder(doc) -> {
                val b = JSONObject(req.bodyText())
                if (b.has("category")) ops.changeCategory(doc, b.optString("category"))
                FolderBooks.updateInfo(context, doc.id, b.optString("title", doc.title), b.optString("author", EditedVolumes.editors(doc)),
                    b.optString("tags", doc.tags), b.optString("summary", doc.summary))
                HttpResponse.json(detail(db.documentDao().getDocumentById(doc.id) ?: doc).toString())
            }
            what == "parts" && m == "POST" -> {
                require(FolderBooks.isFolder(doc)) { "This is not a folder book." }
                val a = JSONObject(req.bodyText()).optJSONArray("ids") ?: org.json.JSONArray()
                FolderBooks.addParts(context, doc.id, (0 until a.length()).map { a.getLong(it) })
                HttpResponse.json(detail(db.documentDao().getDocumentById(doc.id) ?: doc).toString())
            }
            what == "parts" && rest.size >= 2 -> {
                require(FolderBooks.isFolder(doc)) { "This is not a folder book." }
                val pid = rest[1].toLong()
                when {
                    m == "DELETE" -> FolderBooks.removePart(context, doc.id, pid)
                    rest.getOrNull(2) == "move" -> FolderBooks.move(context, doc.id, pid, JSONObject(req.bodyText()).optInt("delta", 0))
                    else -> throw NoSuchElementException("Unknown address.")
                }
                HttpResponse.json(detail(db.documentDao().getDocumentById(doc.id) ?: doc).toString())
            }
            what == "ris" && m == "POST" -> {
                val b = JSONObject(req.bodyText())
                val res = com.example.reader.Ris.apply(context, doc, b.optString("text"), b.optBoolean("overwrite", false))
                HttpResponse.json(detail(res.document).put("risFilled", res.filled).toString())
            }
            what == "" && m == "PATCH" -> {
                val b = JSONObject(req.bodyText())
                val moved = if (b.has("category")) ops.changeCategory(doc, b.optString("category")) else doc
                val updated = ops.updateDocumentInfo(
                    document = moved,
                    newTitle = b.optString("title", moved.title),
                    newAuthor = b.optString("author", moved.author),
                    newTags = b.optString("tags", moved.tags),
                    newSummary = b.optString("summary", moved.summary),
                    resetToDefaultCover = b.optBoolean("resetCover", false)
                )
                HttpResponse.json(detail(updated).toString())
            }
            what == "" && m == "DELETE" -> { ops.deleteDocument(doc); HttpResponse.json("{}") }
            what == "pin" && m == "POST" -> {
                val pinned = JSONObject(req.bodyText()).optBoolean("pinned", !doc.isPinnedToWidget)
                ops.repository.updateDocumentWidgetPin(doc.id, pinned, if (pinned) System.currentTimeMillis() else 0L)
                com.example.widget.PinnedBooksWidgetProvider.refreshAllWidgets(context)
                HttpResponse.json(JSONObject().put("pinned", pinned).toString())
            }
            what == "cover" && m == "POST" -> {
                require(req.contentLength > 0) { "The image is empty." }
                require(req.contentLength <= 30L * 1024 * 1024) { req.drain(); "The image is too large (30 MB at most)." }
                val bytes = req.body.readBytes()
                HttpResponse.json(detail(CoverChanger.setImage(context, ops, doc, bytes)).toString())
            }
            what == "cover" && m == "DELETE" -> HttpResponse.json(detail(CoverChanger.reset(context, ops, doc)).toString())
            what == "cover" -> cover(doc)
            what == "file" -> {
                val f = File(doc.filePath)
                if (!f.isFile) throw NoSuchElementException("The book file is missing on the tablet.")
                if (req.query["download"] == "1") {
                    HttpResponse.FileBody(f, MiniHttpServer.mimeFor(f.name), MarkdownExporter.formatExportedSourceFilename(doc).ifBlank { f.name })
                } else {
                    // Reading: a large PDF is served from its browser-friendly copy once that is ready.
                    val served = if (isPdf(doc) && req.query["web"] == "1") PdfWebCopy.readyCopy(context, doc.id, f) ?: f else f
                    HttpResponse.FileBody(served, MiniHttpServer.mimeFor(f.name)).header("Cache-Control", "no-store")
                }
            }
            what == "highlights" && m == "GET" -> HttpResponse.json(JSONArray().apply {
                ops.repository.getHighlightsListForDocument(doc.id).forEach { put(hlJson(it)) }
            }.toString())
            what == "highlights" && m == "POST" -> HttpResponse.json(hlJson(addHighlight(doc, JSONObject(req.bodyText()))).toString(), 201)
            what == "progress" && m == "POST" -> {
                val b = JSONObject(req.bodyText())
                if (isPdf(doc)) ops.updateReadingProgress(doc.id, b.getInt("page"), b.optInt("total", doc.totalPages))
                HttpResponse.json("{}")
            }
            what == "epub-index" -> {
                val index = EpubIndex.of(context, File(doc.filePath)) ?: throw IllegalArgumentException("Could not read this EPUB.")
                HttpResponse.json(EpubIndex.toJson(index).toString())
            }
            what == "pdf-label" -> {
                val page = req.query["page"]?.toIntOrNull() ?: 1
                HttpResponse.json(JSONObject().put("label", printedLabel(doc, page)).put("chapter", pdfChapter(doc, page)).toString())
            }
            what == "bib" && m == "GET" -> HttpResponse.json(bibJson(doc).toString())
            what == "bib" && (m == "PUT" || m == "PATCH") -> {
                val b = JSONObject(req.bodyText())
                var rec = BibStore.get(doc.id)
                BibField.entries.forEach { f ->
                    if (!b.has(f.name)) return@forEach
                    var v = b.optString(f.name).trim()
                    // A DOI pasted as a link (https://doi.org/10.…) is stored as the DOI itself.
                    if (f == BibField.DOI && v.isNotBlank()) v = com.example.reader.BibFieldCleaner.clean(BibField.DOI, v).ifBlank { v }
                    rec = rec.with(f, v)
                }
                if (b.has("_type")) rec = rec.copy(entryType = b.optString("_type"))
                BibStore.put(doc.id, rec)
                HttpResponse.json(bibJson(doc).toString())
            }
            what == "lookup" && m == "POST" -> {
                // The editor's current (unsaved) values: a DOI typed a moment ago is used at once.
                val b = JSONObject(req.bodyText())
                val fo = b.optJSONObject("fields") ?: JSONObject()
                var rec = BibRecord()
                BibField.entries.forEach { f -> fo.optString(f.name).takeIf { it.isNotBlank() }?.let { rec = rec.with(f, it) } }
                val res = if (b.optBoolean("search", false)) {
                    com.example.reader.MetadataLookup.search(b.optString("title"), b.optString("author")).map {
                        com.example.reader.MetadataLookup.Outcome(candidates = it, note = if (it.isEmpty()) "" else "Matches by title:")
                    }
                } else com.example.reader.MetadataLookup.run(rec, b.optString("title"), b.optString("author"))
                val out = res.getOrElse { throw IllegalArgumentException(it.message ?: "The lookup failed.") }
                HttpResponse.json(JSONObject()
                    .put("note", out.note)
                    .apply { out.found?.let { put("found", com.example.reader.MetadataLookup.toJson(it)) } }
                    .put("candidates", JSONArray().apply {
                        out.candidates.forEach { c -> put(JSONObject().put("label", c.label).put("detail", c.detail).put("found", com.example.reader.MetadataLookup.toJson(c.found))) }
                    }).toString())
            }
            what == "chapter" -> chapterRoute(doc, req)
            what == "text-config" && m == "GET" -> HttpResponse.json(textConfigJson(doc).toString())
            what == "text-config" && (m == "PUT" || m == "PATCH") -> HttpResponse.json(saveTextConfig(doc, JSONObject(req.bodyText())).toString())
            what == "page-text" -> HttpResponse.json(pageText(doc, req.query["page"]?.toIntOrNull() ?: 1).toString())
            what == "page-check" -> HttpResponse.json(pageCheck(doc, req.query["page"]?.toIntOrNull() ?: 1).toString())
            what == "recordings" -> recordingsRoute(doc, req, rest.drop(1))
            what == "set-as" && m == "POST" && rest.getOrNull(1) == "preview" -> HttpResponse.json(setAsPreview(doc, JSONObject(req.bodyText())).toString())
            what == "set-as" && m == "POST" -> HttpResponse.json(setAs(doc, JSONObject(req.bodyText())).toString())
            what == "export" -> export(doc, rest.getOrNull(1).orEmpty(), req)
            else -> throw NoSuchElementException("Unknown address.")
        }
    }

    // ------------------------------------------------------------------------------- sign-in

    private fun authed(req: HttpRequest): Boolean = Sessions.valid(context, req.cookie(COOKIE))

    private fun login(req: HttpRequest): HttpResponse {
        val now = System.currentTimeMillis()
        val (count, until) = failures[req.remote] ?: (0 to 0L)
        if (until > now) return HttpResponse.error(429, "Too many wrong PINs. Try again in ${((until - now) / 1000) + 1} s.")
        val pin = runCatching { JSONObject(req.bodyText(4096)).optString("pin") }.getOrDefault("").filter { it.isDigit() }
        if (pin.isEmpty() || !pinMatches(pin)) {
            val n = count + 1
            failures[req.remote] = if (n >= 5) 0 to now + 60_000 else n to 0L
            return HttpResponse.error(401, if (n >= 5) "Wrong PIN. Locked for one minute." else "Wrong PIN.")
        }
        failures.remove(req.remote)
        val token = Sessions.create(context)
        WebServer.lastActivity = now
        return HttpResponse.json(JSONObject().put("ok", true).toString())
            .header("Set-Cookie", "$COOKIE=$token; Path=/; Max-Age=${30 * 24 * 3600}; HttpOnly; SameSite=Strict")
    }

    private fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.distinct()
        .joinToString(" ").replaceFirstChar { it.titlecase(Locale.ROOT) }

    // ------------------------------------------------------------------------------- static page

    private fun static(path: String): HttpResponse {
        val clean = path.trimStart('/').ifBlank { "index.html" }
        if (clean.contains("..")) return HttpResponse.error(400, "Bad path")
        val name = if (clean.startsWith("read/") || clean.startsWith("book/")) "index.html" else clean
        return try {
            val bytes = context.assets.open("web/$name").use { it.readBytes() }
            HttpResponse.Bytes(200, MiniHttpServer.mimeFor(name), bytes).apply {
                if (name.startsWith("lib/")) header("Cache-Control", "public, max-age=604800")
                else header("Cache-Control", "no-cache")
            }
        } catch (_: java.io.IOException) {
            HttpResponse.text(404, "Not found")
        }
    }

    // ------------------------------------------------------------------------------- JSON

    private fun isPdf(doc: DocumentEntity) = doc.fileType.equals("PDF", true) || doc.filePath.endsWith(".pdf", true)

    private suspend fun library(): JSONObject {
        val docs = db.documentDao().getAllDocumentsOrderedByLastReadSnapshot()
        val counts = db.highlightDao().getAllHighlights().first().groupingBy { it.documentId }.eachCount()
        return JSONObject()
            .put("device", deviceName())
            .put("documents", JSONArray().apply { docs.forEach { put(docJson(it, counts[it.id] ?: 0)) } })
    }

    private fun docJson(d: DocumentEntity, highlightCount: Int): JSONObject {
        val f = File(d.filePath)
        val cover = d.coverUri?.let { File(it) }?.takeIf { it.isFile }
        return JSONObject()
            .put("id", d.id)
            .put("title", d.title)
            .put("author", d.author)
            .put("displayAuthor", runCatching { EditedVolumes.displayAuthor(d) }.getOrDefault(d.author))
            .put("category", d.category)
            .put("fileType", d.fileType)
            .put("tags", d.tags)
            .put("summary", d.summary)
            .put("currentPage", d.currentPage)
            .put("totalPages", d.totalPages)
            .put("progress", d.readingProgress.toDouble())
            .put("lastRead", d.lastReadTimestamp)
            .put("dateAdded", d.dateAdded)
            .put("pinned", d.isPinnedToWidget)
            .put("fileName", f.name)
            .put("size", if (f.isFile) f.length() else 0L)
            .put("highlights", highlightCount)
            .put("chapter", d.lastPositionChapter)
            .put("placeLabel", d.lastPositionLabel)
            .put("minutes", d.estimatedReadingMinutes)
            .put("coverRatio", d.coverAspectRatio.toDouble().takeIf { it.isFinite() } ?: 0.0)
            .put("coverVersion", (cover?.lastModified() ?: 0L) + d.title.hashCode())
            .put("editedVolume", !FolderBooks.isFolder(d) && runCatching { EditedVolumes.isEditedVolume(context, d) }.getOrDefault(false))
            .put("folder", FolderBooks.isFolder(d))
            .apply {
                if (FolderBooks.isFolder(d)) put("partCount", FolderBooks.partsOf(d.id).size)
                FolderBooks.folderOf(d.id)?.let { put("inFolder", it) }
            }
    }

    private suspend fun detail(d: DocumentEntity): JSONObject {
        val hls = ops.repository.getHighlightsListForDocument(d.id)
        val o = docJson(d, hls.size)
        o.put("highlightList", JSONArray().apply { hls.forEach { put(hlJson(it)) } })
        o.put("bib", bibJson(d))
        runCatching {
            o.put("citekey", Citations.citeKey(d, BibStore.get(d.id)))
            o.put("sourceFile", com.example.reader.MarkdownExporter.formatExportedSourceFilename(d))
            o.put("overviewLink", "[[" + com.example.reader.MarkdownExporter.getMocTitle(d.title, ops.repository.getSettings()) + "]]")
        }
        if (FolderBooks.isFolder(d)) {
            val all = db.highlightDao().getAllHighlights().first()
            val counts = all.groupingBy { it.documentId }.eachCount()
            val ids = FolderBooks.partsOf(d.id)
            val parts = ids.mapNotNull { db.documentDao().getDocumentById(it) }
            o.put("parts", JSONArray().apply { parts.forEachIndexed { i, p -> put(docJson(p, counts[p.id] ?: 0).put("number", i + 1)) } })
            o.put("highlightList", JSONArray().apply { all.filter { it.documentId in ids }.sortedWith(compareBy({ ids.indexOf(it.documentId) }, { it.pageNumber })).forEach { put(hlJson(it)) } })
            o.put("highlights", all.count { it.documentId in ids })
            o.put("resumeId", FolderBooks.readingOf(d.id)?.partId ?: ids.firstOrNull() ?: 0L)
            o.put("editors", EditedVolumes.editors(d))
        }
        FolderBooks.neighbours(d.id)?.let { (fid, prev, next) ->
            val f = db.documentDao().getDocumentById(fid)
            val ids = FolderBooks.partsOf(fid)
            o.put("folderNav", JSONObject()
                .put("id", fid).put("title", f?.title.orEmpty()).put("number", ids.indexOf(d.id) + 1).put("count", ids.size)
                .apply {
                    prev?.let { db.documentDao().getDocumentById(it) }?.let { put("prev", JSONObject().put("id", it.id).put("title", it.title).put("fileType", it.fileType)) }
                    next?.let { db.documentDao().getDocumentById(it) }?.let { put("next", JSONObject().put("id", it.id).put("title", it.title).put("fileType", it.fileType).put("author", it.author)) }
                })
        }
        val vol = runCatching { EditedVolumes.get(context, d.id) }.getOrNull()
        if (vol != null && vol.enabled && vol.chapters.isNotEmpty()) {
            val notesBy = runCatching { EditedVolumes.highlightsByChapter(vol, hls) }.getOrDefault(emptyMap())
            o.put("chapters", JSONArray().apply {
                vol.chapters.forEachIndexed { i, c ->
                    put(JSONObject()
                        .put("index", i)
                        .put("number", runCatching { EditedVolumes.numberOf(vol.chapters, i) }.getOrDefault(""))
                        .put("title", c.title)
                        .put("authors", c.authors)
                        .put("startPage", c.startPage)
                        .put("endPage", EditedVolumes.endPageOf(vol.chapters, i, vol.totalPages))
                        .put("printedPages", c.printedPages)
                        .put("href", c.href)
                        .put("level", c.level)
                        .put("kind", when { c.isPart -> "part"; c.isMatter -> "matter"; c.isSection -> "section"; else -> "chapter" })
                        .put("tags", c.tags)
                        .put("abstract", c.abstract)
                        .put("doi", c.doi)
                        .put("progress", runCatching { EditedVolumes.progressOf(vol, i).toDouble() }.getOrDefault(0.0))
                        .put("notes", notesBy[i]?.size ?: 0))
                }
            })
        }
        return o
    }

    private fun hlJson(h: HighlightEntity): JSONObject = JSONObject()
        .put("id", h.id)
        .put("documentId", h.documentId)
        .put("title", h.title)
        .put("highlightedText", h.highlightedText)
        .put("userNotes", h.userNotes)
        .put("pageNumber", h.pageNumber)
        .put("printedPageLabel", h.printedPageLabel)
        .put("chapterTitle", h.chapterTitle)
        .put("paragraphCitation", h.paragraphCitation)
        .put("pageEquivalent", h.pageEquivalent)
        .put("epubCfi", h.epubCfi)
        .put("colorHex", h.colorHex)
        .put("tags", h.tags)
        .put("created", h.createdTimestamp)

    private suspend fun allHighlights(): JSONObject {
        val docs = db.documentDao().getAllDocumentsSnapshot().associateBy { it.id }
        val list = db.highlightDao().getAllHighlights().first()
        return JSONObject().put("highlights", JSONArray().apply {
            list.forEach { h ->
                val d = docs[h.documentId] ?: return@forEach
                put(hlJson(h).put("documentTitle", d.title).put("documentAuthor", EditedVolumes.displayAuthor(d)).put("fileType", d.fileType))
            }
        })
    }

    private fun bibJson(d: DocumentEntity): JSONObject {
        val rec: BibRecord = BibStore.get(d.id)
        val fields = JSONObject()
        BibField.entries.forEach { f -> fields.put(f.name, rec[f]) }
        val styles = JSONArray()
        CitationStyle.entries.forEach { st ->
            styles.put(JSONObject()
                .put("id", st.name)
                .put("label", st.label)
                .put("reference", runCatching { Citations.reference(st, d, rec) }.getOrDefault(""))
                .put("inText", runCatching { Citations.inText(st, d, rec, "") }.getOrDefault("")))
        }
        return JSONObject()
            .put("fields", fields)
            .put("labels", JSONObject().apply { BibField.entries.forEach { put(it.name, it.label) } })
            .put("entryType", rec.entryType)
            .put("styles", styles)
            .put("defaultStyle", runCatching { Citations.defaultStyle(context).name }.getOrDefault(CitationStyle.CHICAGO_AUTHOR_DATE.name))
            .put("bibtex", runCatching { Citations.bibtex(d, rec) }.getOrDefault(""))
            .put("ris", runCatching { com.example.reader.Ris.export(d, rec) }.getOrDefault(""))
    }

    // ------------------------------------------------------------------------------- reading settings (shared with the app)

    private suspend fun readerSettingsJson(): JSONObject {
        val st = ops.repository.getSettings()
        return JSONObject()
            .put("theme", st.readerTheme.uppercase(Locale.ROOT).let { if (it == "CREAM" || it == "DARK") it else "WHITE" })
            .put("fontSizeSp", st.readerFontSizeSp.toDouble())
            .put("fontFamily", st.readerFontFamily.ifBlank { "SERIF" })
            .put("textAlign", st.readerTextAlign.ifBlank { "JUSTIFY" })
            .put("lineSpacing", st.readerLineSpacing.toDouble())
            .put("marginDp", st.readerMarginDp)
            .put("topMarginDp", st.readerTopMarginDp)
            .put("bottomMarginDp", st.readerBottomMarginDp)
            .put("paragraphStyle", st.readerParagraphStyle.ifBlank { "BOTH" })
            .put("viewMode", st.readerViewMode.ifBlank { "PAGINATED" })
            .put("columns", st.readerColumnCount.coerceIn(1, 2))
            .put("publisherStyles", st.publisherStylesEnabled)
    }

    private suspend fun saveReaderSettings(b: JSONObject) {
        val st = ops.repository.getSettings()
        fun pick(key: String, allowed: Set<String>, cur: String) = b.optString(key, cur).uppercase(Locale.ROOT).takeIf { it in allowed } ?: cur
        val next = st.copy(
            readerTheme = if (b.has("theme")) pick("theme", setOf("WHITE", "CREAM", "DARK"), st.readerTheme) else st.readerTheme,
            readerFontSizeSp = if (b.has("fontSizeSp")) b.optDouble("fontSizeSp", st.readerFontSizeSp.toDouble()).toFloat().coerceIn(10f, 40f) else st.readerFontSizeSp,
            readerFontFamily = pick("fontFamily", setOf("SERIF", "SANS", "MONO"), st.readerFontFamily),
            readerTextAlign = pick("textAlign", setOf("JUSTIFY", "LEFT"), st.readerTextAlign),
            readerLineSpacing = if (b.has("lineSpacing")) b.optDouble("lineSpacing", st.readerLineSpacing.toDouble()).toFloat().coerceIn(1.0f, 2.6f) else st.readerLineSpacing,
            readerMarginDp = if (b.has("marginDp")) b.optInt("marginDp", st.readerMarginDp).coerceIn(0, 96) else st.readerMarginDp,
            readerTopMarginDp = if (b.has("topMarginDp")) b.optInt("topMarginDp", st.readerTopMarginDp).coerceIn(0, 160) else st.readerTopMarginDp,
            readerBottomMarginDp = if (b.has("bottomMarginDp")) b.optInt("bottomMarginDp", st.readerBottomMarginDp).coerceIn(0, 160) else st.readerBottomMarginDp,
            readerParagraphStyle = pick("paragraphStyle", setOf("INDENT", "SPACED", "BOTH", "NONE"), st.readerParagraphStyle),
            readerViewMode = pick("viewMode", setOf("PAGINATED", "VERTICAL_SCROLL"), st.readerViewMode),
            readerColumnCount = if (b.has("columns")) b.optInt("columns", st.readerColumnCount).coerceIn(1, 2) else st.readerColumnCount,
            publisherStylesEnabled = if (b.has("publisherStyles")) b.optBoolean("publisherStyles", st.publisherStylesEnabled) else st.publisherStylesEnabled
        )
        if (next != st) db.settingsDao().saveSettings(next)
    }

    // ------------------------------------------------------------------------------- PDF text & OCR (shared with the app)

    /** The shared renderer, set to this book's text mode, per-page fixes and OCR language. */
    private fun configuredRenderer(doc: DocumentEntity): PdfDocumentRenderer {
        val r = renderer(doc)
        r.updateExtractionConfiguration(
            com.example.reader.PdfTextExtractionMode.fromId(doc.pdfTextExtractionMode),
            com.example.reader.PdfPageDiagnosticsAndOcrEngine.parsePageOverridesJson(doc.pdfPageExtractionOverridesJson),
            doc.pdfOcrLanguage.ifBlank { "AUTO" }
        )
        return r
    }

    private fun textConfigJson(doc: DocumentEntity): JSONObject {
        val overrides = com.example.reader.PdfPageDiagnosticsAndOcrEngine.parsePageOverridesJson(doc.pdfPageExtractionOverridesJson)
        return JSONObject()
            .put("mode", com.example.reader.PdfTextExtractionMode.fromId(doc.pdfTextExtractionMode).id)
            .put("ocrLanguage", doc.pdfOcrLanguage.ifBlank { "AUTO" })
            .put("overrides", JSONObject().apply { overrides.forEach { (p, o) -> if (o != com.example.reader.PdfPageExtractionOverride.FOLLOW_DOCUMENT) put(p.toString(), o.id) } })
            .put("modes", JSONArray().apply { com.example.reader.PdfTextExtractionMode.entries.forEach { put(JSONObject().put("id", it.id).put("label", it.shortBadge).put("title", it.title).put("description", it.description)) } })
            .put("pageOptions", JSONArray().apply { com.example.reader.PdfPageExtractionOverride.entries.forEach { put(JSONObject().put("id", it.id).put("label", it.shortLabel).put("title", it.displayTitle).put("description", it.description)) } })
            .put("languages", JSONArray().apply { com.example.reader.PdfOcrLanguageOption.entries.forEach { put(JSONObject().put("code", it.code).put("label", it.displayName)) } })
    }

    private suspend fun saveTextConfig(doc: DocumentEntity, b: JSONObject): JSONObject {
        require(isPdf(doc)) { "Text modes are for PDFs." }
        var mode = com.example.reader.PdfTextExtractionMode.fromId(doc.pdfTextExtractionMode)
        var lang = doc.pdfOcrLanguage.ifBlank { "AUTO" }
        val overrides = com.example.reader.PdfPageDiagnosticsAndOcrEngine.parsePageOverridesJson(doc.pdfPageExtractionOverridesJson).toMutableMap()
        if (b.has("mode")) mode = com.example.reader.PdfTextExtractionMode.fromId(b.optString("mode"))
        if (b.has("ocrLanguage")) lang = com.example.reader.PdfOcrLanguageOption.fromCode(b.optString("ocrLanguage")).code
        if (b.has("page")) {
            val p = b.optInt("page", 0)
            require(p >= 1) { "Which page?" }
            val o = com.example.reader.PdfPageExtractionOverride.fromId(b.optString("override"))
            if (o == com.example.reader.PdfPageExtractionOverride.FOLLOW_DOCUMENT) overrides.remove(p) else overrides[p] = o
        }
        val json = com.example.reader.PdfPageDiagnosticsAndOcrEngine.serializePageOverridesJson(overrides)
        db.documentDao().updateDocumentTextExtractionConfig(doc.id, mode.id, json, lang)
        if (lang != doc.pdfOcrLanguage) runCatching { renderer(doc).invalidateAllExtractionCaches() }
        val fresh = db.documentDao().getDocumentById(doc.id) ?: doc
        configuredRenderer(fresh)
        return textConfigJson(fresh)
    }

    /**
     * The text of one PDF page as the tablet reads it (after OCR or repair), with each word's box
     * as fractions of the page. `same` = nothing changed from the PDF's own text layer.
     */
    private fun pageText(doc: DocumentEntity, page: Int): JSONObject {
        require(isPdf(doc)) { "Only PDFs have pages." }
        val r = configuredRenderer(doc)
        val idx = (page - 1).coerceIn(0, (r.pageCount - 1).coerceAtLeast(0))
        val raw = r.extractRawOriginalPageTextData(idx)
        val data = r.extractPageTextData(idx)
        val override = r.pageExtractionOverrides[idx + 1] ?: com.example.reader.PdfPageExtractionOverride.FOLLOW_DOCUMENT
        val same = data.fullText == raw.fullText && data.words.size == raw.words.size
        val source = when {
            same -> "Original PDF text"
            override != com.example.reader.PdfPageExtractionOverride.FOLLOW_DOCUMENT -> override.shortLabel
            raw.words.isEmpty() -> "OCR"
            else -> com.example.reader.PdfTextExtractionMode.fromId(doc.pdfTextExtractionMode).shortBadge + " · repaired"
        }
        return JSONObject()
            .put("page", idx + 1)
            .put("same", same)
            .put("source", source)
            .put("printedLabel", data.printedPageLabel)
            .put("words", if (same) JSONArray() else JSONArray().apply {
                data.words.forEach { w -> put(JSONArray().put(w.text).put(w.leftRatio.toDouble()).put(w.topRatio.toDouble()).put(w.rightRatio.toDouble()).put(w.bottomRatio.toDouble()).put(w.lineIndex)) }
            })
    }

    private fun pageCheck(doc: DocumentEntity, page: Int): JSONObject {
        require(isPdf(doc)) { "Only PDFs can be checked." }
        val r = configuredRenderer(doc)
        val idx = (page - 1).coerceIn(0, (r.pageCount - 1).coerceAtLeast(0))
        val rep = r.getPageDiagnostics(idx, includeBitmapInspection = true)
        return JSONObject()
            .put("page", idx + 1)
            .put("classification", JSONObject().put("id", rep.classification.id).put("title", rep.classification.displayTitle).put("advice", rep.classification.summaryAdvice))
            .put("summary", rep.plainLanguageSummary)
            .put("issues", JSONArray().apply { rep.issues.forEach { put(JSONObject().put("title", it.title).put("explanation", it.plainLanguageExplanation).put("action", it.suggestedActionTitle)) } })
            .put("words", rep.nativeWordCount)
            .put("sampleOriginal", rep.sampleOriginalText.take(600))
            .put("sampleRepaired", rep.sampleRepairedText.take(600))
            .put("activeSource", rep.activeSourceLabel)
            .put("recommended", JSONObject().put("id", rep.recommendedOverride.id).put("label", rep.recommendedOverride.shortLabel).put("title", rep.recommendedOverride.displayTitle))
    }

    // ------------------------------------------------------------------------------- Set as …

    /** The app's position for a web selection: PDF page, or the EPUB position from href + progression. */
    private suspend fun setAsPage(doc: DocumentEntity, b: JSONObject): Int =
        if (isPdf(doc)) b.optInt("page", 1).coerceAtLeast(1)
        else {
            val href = b.optString("href")
            val index = if (href.isNotBlank()) runCatching { EpubIndex.of(context, File(doc.filePath)) }.getOrNull() else null
            if (index != null) EpubIndex.positionFor(index, href, b.optDouble("progression", 0.0)) else b.optInt("page", 1).coerceAtLeast(1)
        }

    // ------------------------------------------------------------------------------- presentations

    private suspend fun presentationsRoute(req: HttpRequest, rest: List<String>): HttpResponse {
        val m = req.method
        val P = Presentations
        if (rest.isEmpty()) {
            return when (m) {
                "POST" -> {
                    val name = File(req.query["name"].orEmpty()).name.ifBlank { "presentation.pptx" }
                    require(req.contentLength > 0) { "The file is empty." }
                    HttpResponse.json(P.json(P.add(context, name, req.body)).toString(), 201)
                }
                else -> HttpResponse.json(JSONObject().put("presentations", JSONArray().apply { P.list(context).forEach { put(P.json(it)) } }).toString())
                    .header("Cache-Control", "no-store")
            }
        }
        val d = P.get(context, rest[0]) ?: throw NoSuchElementException("No such presentation.")
        return when (rest.getOrNull(1).orEmpty()) {
            "" -> when (m) {
                "DELETE" -> { P.delete(d); HttpResponse.json("{}") }
                "PATCH" -> { P.rename(d, JSONObject(req.bodyText()).optString("title")); HttpResponse.json(P.json(P.get(context, d.id) ?: d).toString()) }
                else -> HttpResponse.json(P.json(d).put("info", P.info(d)).put("myNotes", P.myNotes(d)).put("ink", P.ink(d)).toString()).header("Cache-Control", "no-store")
            }
            "file" -> HttpResponse.FileBody(d.file, MiniHttpServer.mimeFor(d.file.name), if (req.query["download"] == "1") d.file.name else null)
            "notes" -> { val b = JSONObject(req.bodyText()); P.setMyNote(d, b.optInt("slide"), b.optString("text")); HttpResponse.json(P.myNotes(d).toString()) }
            "ink" -> { val b = JSONObject(req.bodyText(8 * 1024 * 1024)); P.setInk(d, b.optInt("slide"), b.optJSONArray("strokes") ?: JSONArray()); HttpResponse.json("{}") }
            else -> throw NoSuchElementException("Unknown address.")
        }
    }

    private fun liveRoute(req: HttpRequest, rest: List<String>): HttpResponse {
        val P = Presentations
        return when {
            rest.isEmpty() && req.method == "POST" -> {
                val b = JSONObject(req.bodyText(8 * 1024 * 1024))
                P.presenterSeen = System.currentTimeMillis()
                P.setLive(b.optString("id"), b.optInt("slide"), b.optBoolean("black"), b.optJSONArray("ink"))
                HttpResponse.json(P.liveJson().toString())
            }
            rest.isEmpty() -> {
                if (req.query["role"] == "audience") P.audienceSeen(req.remote)
                val since = req.query["since"]?.toLongOrNull() ?: -1
                val l = if (since >= 0) P.waitLive(since, 25_000) else P.live
                HttpResponse.json(P.liveJson(l).put("presenterSeen", P.presenterSeen)
                    .put("addresses", JSONArray().apply { WebServer.addresses.value.forEach { a -> put(JSONObject().put("label", a.label).put("url", a.url)) } }).toString())
                    .header("Cache-Control", "no-store")
            }
            else -> throw NoSuchElementException("Unknown address.")
        }
    }

    // ------------------------------------------------------------------------------- recordings

    private fun recJson(r: com.example.reader.Recordings.Info): JSONObject = JSONObject()
        .put("id", r.id).put("started", r.startedAt).put("durationMs", r.durationMs).put("events", r.events)
        .put("size", r.audio.length()).put("language", r.language).put("transcribed", r.transcribed)
        .put("status", com.example.reader.Transcripts.progress.value[r.id]
            ?: com.example.reader.LocalWhisper.state.value.takeIf { it.busy && it.id == r.id }?.status ?: JSONObject.NULL)
        .put("error", com.example.reader.Transcripts.lastError.value[r.id] ?: JSONObject.NULL)

    private suspend fun recordingsRoute(doc: DocumentEntity, req: HttpRequest, rest: List<String>): HttpResponse {
        val settings = ops.repository.getSettings()
        val recs = com.example.reader.Recordings
        if (rest.isEmpty()) {
            val active = recs.active.value?.takeIf { it.docId == doc.id }
            return HttpResponse.json(JSONObject()
                .put("recordings", JSONArray().apply { recs.list(context, doc, settings).forEach { put(recJson(it)) } })
                .put("recording", if (active == null) JSONObject.NULL else JSONObject().put("elapsedMs", active.elapsedMs).put("paused", active.paused))
                .put("online", JSONArray().apply { com.example.reader.Transcripts.onlineProviders(context).forEach { put(JSONObject().put("id", it.name).put("label", it.label)) } })
                .put("offline", com.example.reader.WhisperNative.available)
                .toString()).header("Cache-Control", "no-store")
        }
        val info = recs.find(context, doc, settings, rest[0]) ?: throw NoSuchElementException("No such recording.")
        val m = req.method
        return when (rest.getOrNull(1).orEmpty()) {
            "audio" -> HttpResponse.FileBody(info.audio, "audio/mp4", if (req.query["download"] == "1") info.audio.name else null)
            "timeline" -> HttpResponse.json(recs.timeline(info).toString())
            "transcript" -> when (m) {
                "POST" -> {
                    val b = JSONObject(req.bodyText(32 * 1024 * 1024))
                    val segs = com.example.reader.Transcripts.parseSegments(b.optJSONArray("segments") ?: JSONArray())
                    com.example.reader.Transcripts.save(context, doc, info, segs, b.optString("language", "auto"), b.optString("engine", "Whisper in the browser"))
                    HttpResponse.json(recJson(recs.info(info.audio) ?: info).toString())
                }
                else -> if (req.query["format"] == "md") {
                    if (!info.transcript.isFile) throw NoSuchElementException("Not transcribed yet.")
                    if (req.query["download"] == "1") HttpResponse.FileBody(info.transcript, "text/markdown; charset=utf-8", info.transcript.name)
                    else HttpResponse.text(200, info.transcript.readText(), "text/markdown; charset=utf-8")
                } else {
                    val f = File(info.audio.parentFile, info.id + ".transcript.json")
                    if (!f.isFile) throw NoSuchElementException("Not transcribed yet.")
                    HttpResponse.json(f.readText())
                }
            }
            "transcribe" -> {
                val b = JSONObject(req.bodyText())
                val lang = b.optString("language", "auto").let { if (it == "no" || it == "en") it else "auto" }
                com.example.reader.Transcripts.clearError(info.id)
                when (b.optString("engine")) {
                    "tablet" -> com.example.reader.LocalWhisper.start(context, doc, info, lang)
                    else -> {
                        val p = runCatching { com.example.reader.AiProvider.valueOf(b.optString("provider")) }.getOrElse { throw IllegalArgumentException("Choose a service.") }
                        com.example.reader.Transcripts.startOnline(context, doc, info, p, lang)
                    }
                }
                HttpResponse.json(recJson(info).toString())
            }
            "" -> if (m == "DELETE") { recs.delete(info); HttpResponse.json("{}") } else HttpResponse.json(recJson(info).toString())
            else -> throw NoSuchElementException("Unknown address.")
        }
    }

    private suspend fun setAsPreview(doc: DocumentEntity, b: JSONObject): JSONObject {
        BibStore.init(context)
        val text = b.optString("text")
        val page = setAsPage(doc, b)
        val chapterTitle = b.optString("chapter")
        val isBook = !doc.category.equals("ARTICLE", true)
        val rec = BibStore.get(doc.id)
        val vol = EditedVolumes.get(context, doc.id)?.takeIf { it.enabled }
        val idx = vol?.let { EditedVolumes.chapterIndexFor(it, page, chapterTitle) } ?: -1
        val c = vol?.chapters?.getOrNull(idx)
        val fields = JSONArray()
        BibField.entries.filter { it != BibField.CITEKEY }.forEach { f ->
            val v = if (f == BibField.EDITOR) com.example.reader.ChapterAuthorGuesser.nameLine(text) ?: com.example.reader.BibFieldCleaner.clean(f, text)
            else com.example.reader.BibFieldCleaner.clean(f, text)
            fields.put(JSONObject().put("key", f.name).put("label", if (f == BibField.EDITOR && isBook) "Book editor(s)" else f.label).put("value", v).put("current", rec[f]))
        }
        return JSONObject()
            .put("isBook", isBook)
            .put("page", page)
            .put("fields", fields)
            .put("tags", JSONArray(com.example.reader.TagSplitter.split(text)))
            .put("bookTags", doc.tags)
            .put("bookAuthor", doc.author)
            .put("bookAbstract", doc.summary)
            .put("chapter", if (c == null) JSONObject.NULL else JSONObject()
                .put("title", c.title).put("label", c.label).put("authors", c.authors).put("abstract", c.abstract).put("tags", c.tags)
                .put("authorsAdd", c.authorSource == "user" && c.authors.isNotBlank()))
            .put("chapterHint", chapterTitle)
            .put("guess", JSONObject()
                .put("title", text.replace(Regex("\\s+"), " ").trim().trimEnd('.'))
                .put("author", com.example.reader.ChapterAuthorGuesser.nameLine(text) ?: text.trim().removePrefix("by ").trim())
                .put("abstract", text.replace(Regex("\\s+"), " ").trim()))
    }

    private suspend fun setAs(doc: DocumentEntity, b: JSONObject): JSONObject {
        BibStore.init(context)
        val target = b.optString("target")
        val value = b.optString("value").trim()
        require(value.isNotBlank()) { "Nothing to save." }
        val page = setAsPage(doc, b)
        val chapterTitle = b.optString("chapter")
        val msg = when {
            target == "chapter:new" -> EditedVolumes.startChapterAt(context, doc, page, value)
            target == "author" -> {
                db.documentDao().updateAuthor(doc.id, value.replace(Regex("\\s+"), " "))
                if (doc.category.equals("ARTICLE", true)) "Author saved" else "Author of the book saved"
            }
            target == "abstract" -> {
                db.documentDao().updateSummary(doc.id, value.replace(Regex("\\s+"), " ").removePrefix("Abstract").trimStart(':', '.', ' ', '—', '–'))
                "Abstract saved"
            }
            target.startsWith("chapter:") -> EditedVolumes.setChapterField(context, doc, page, chapterTitle, target.removePrefix("chapter:"), value)
            target == "tags" -> {
                val chosen = com.example.reader.TagSplitter.split(value)
                val fresh = db.documentDao().getDocumentById(doc.id) ?: doc
                val merged = (com.example.reader.NoteSuggestionEngine.parseTagsList(fresh.tags) + chosen).distinctBy { it.lowercase(Locale.ROOT) }
                db.documentDao().updateTags(doc.id, merged.joinToString(", "))
                "${chosen.size} tag${if (chosen.size == 1) "" else "s"} added to the book"
            }
            target == "EDITOR" -> {
                val existing = BibStore.get(doc.id)[BibField.EDITOR]
                val names = value.split(';').map { it.trim() }.filter { it.isNotBlank() }
                val merged = (existing.split(';').map { it.trim() }.filter { it.isNotBlank() } + names).distinctBy { it.lowercase(Locale.ROOT) }
                BibStore.set(doc.id, BibField.EDITOR, merged.joinToString("; "))
                if (existing.isBlank()) "Book editor saved" else "Editor added"
            }
            else -> {
                val f = runCatching { BibField.valueOf(target) }.getOrElse { throw IllegalArgumentException("Unknown field.") }
                val v = if (f == BibField.DOI) com.example.reader.BibFieldCleaner.clean(BibField.DOI, value).ifBlank { value } else value
                BibStore.set(doc.id, f, v)
                "${f.label} saved"
            }
        }
        return JSONObject().put("message", msg)
    }

    /** One chapter of an edited volume as a citable book section (GET doc/{id}/chapter?index=i). */
    private fun chapterRoute(doc: DocumentEntity, req: HttpRequest): HttpResponse {
        val vol = EditedVolumes.get(context, doc.id) ?: throw NoSuchElementException("This book has no chapter list.")
        val i = req.query["index"]?.toIntOrNull() ?: throw IllegalArgumentException("Which chapter?")
        require(i in vol.chapters.indices) { "No such chapter." }
        BibStore.init(context)
        val (cdoc, _) = EditedVolumes.chapterAsDocument(doc, vol, i)
        val c = vol.chapters[i]
        return HttpResponse.json(JSONObject()
            .put("index", i)
            .put("title", c.title)
            .put("authors", c.authors)
            .put("printedPages", c.printedPages)
            .put("doi", c.doi)
            .put("bookTitle", doc.title)
            .put("editors", EditedVolumes.editors(doc))
            .put("bib", bibJson(cdoc)).toString())
    }

    // ------------------------------------------------------------------------------- covers & PDF helpers

    private suspend fun cover(doc: DocumentEntity): HttpResponse {
        val direct = doc.coverUri?.let { File(it) }?.takeIf { it.isFile && it.length() > 0 }
        val f = direct ?: runCatching { MarkdownExporter.resolveOrGenerateCoverFile(context, doc, ops.repository.getSettings()) }.getOrNull()
        if (f == null || !f.isFile) throw NoSuchElementException("No cover.")
        return HttpResponse.FileBody(f, MiniHttpServer.mimeFor(f.name).let { if (it == "application/octet-stream") "image/jpeg" else it })
            .header("Cache-Control", "private, max-age=86400")
    }

    private fun renderer(doc: DocumentEntity): PdfDocumentRenderer = synchronized(renderers) {
        renderers[doc.id]?.let { return it }
        while (renderers.size >= 2) { val k = renderers.keys.first(); runCatching { renderers.remove(k)?.close() } }
        PdfDocumentRenderer(File(doc.filePath), context).also { renderers[doc.id] = it }
    }

    private fun printedLabel(doc: DocumentEntity, page: Int): String = runCatching {
        renderer(doc).extractPageTextData((page - 1).coerceAtLeast(0)).printedPageLabel
    }.getOrDefault("")

    private fun pdfChapter(doc: DocumentEntity, page: Int): String = runCatching {
        val count = renderer(doc).pageCount
        PdfChapterHelper.extractOrGenerateChapters(File(doc.filePath), count, doc.chaptersJson)
            .filter { it.pageNumber <= page }.maxByOrNull { it.pageNumber }?.title.orEmpty()
    }.getOrDefault("")

    // ------------------------------------------------------------------------------- notes

    private suspend fun addHighlight(doc: DocumentEntity, b: JSONObject): HighlightEntity {
        val text = b.optString("text").trim()
        require(text.isNotBlank()) { "Select some text first." }
        val color = b.optString("colorHex").ifBlank { "#FFEB3B" }
        return if (isPdf(doc)) {
            val page = b.optInt("page", 1).coerceAtLeast(1)
            val endPage = b.optInt("endPage", page).coerceAtLeast(page)
            val chapter = b.optString("chapter").ifBlank { pdfChapter(doc, page) }.ifBlank { "Page $page" }
            val first = b.optString("printedLabel").ifBlank { printedLabel(doc, page) }
            val last = if (endPage > page) printedLabel(doc, endPage) else ""
            val printed = if (first.isNotBlank() && last.isNotBlank() && last != first) "$first–$last" else first
            ops.addHighlight(
                documentId = doc.id, title = b.optString("title"), highlightedText = text,
                userNotes = b.optString("userNotes"), pageNumber = page, printedPageLabel = printed,
                chapterTitle = chapter, colorHex = color, tags = b.optString("tags")
            )
        } else {
            val href = b.optString("href")
            val index = runCatching { EpubIndex.of(context, File(doc.filePath)) }.getOrNull()
            val position = if (index != null && href.isNotBlank()) EpubIndex.positionFor(index, href, b.optDouble("progression", 0.0)) else b.optInt("page", 1)
            val chapter = b.optString("chapter").ifBlank { index?.let { EpubIndex.resourceFor(it, href)?.title }.orEmpty() }.ifBlank { "Chapter" }
            val label = b.optString("pageLabel").trim()
            val pPrefix = if (label.contains('–') || label.contains('-')) "pp." else "p."
            val citation = if (label.isNotBlank()) "$chapter, $pPrefix $label" else "$chapter, pos. $position"
            ops.addHighlight(
                documentId = doc.id, title = b.optString("title"), highlightedText = text,
                userNotes = b.optString("userNotes"), pageNumber = position, printedPageLabel = label,
                chapterTitle = chapter, colorHex = color, tags = b.optString("tags"),
                paragraphCitation = citation, pageEquivalent = label, epubCfi = b.optString("cfi")
            )
        }
    }

    // ------------------------------------------------------------------------------- exports

    private suspend fun export(doc: DocumentEntity, kind: String, req: HttpRequest): HttpResponse {
        val settings = ops.repository.getSettings()
        val hls = ops.repository.getHighlightsListForDocument(doc.id)
        val dir = File(context.cacheDir, "exports/web").apply { mkdirs() }
        fun md(name: String, content: String): HttpResponse {
            val f = File(dir, MarkdownExporter.sanitizeFilename(name) + ".md")
            f.writeText(content, Charsets.UTF_8)
            return HttpResponse.FileBody(f, "text/markdown; charset=utf-8", f.name)
        }
        return when (kind) {
            "bundle" -> {
                val r = MarkdownExporter.createCompleteDocumentBundleZip(context, doc, hls, settings)
                HttpResponse.FileBody(r.outputFileOrFolder, "application/zip", r.outputFileOrFolder.name)
            }
            "vault" -> {
                val z = MarkdownExporter.createVaultZip(context, doc, hls, settings)
                HttpResponse.FileBody(z, "application/zip", z.name)
            }
            "moc" -> md(MarkdownExporter.getMocTitle(doc.title, settings), MarkdownExporter.generateMocMarkdown(doc, hls, settings))
            "notes" -> md("${doc.title} – notes", MarkdownExporter.generateUnifiedObsidianBookMarkdown(doc, hls, settings))
            "folder" -> {
                val z = com.example.reader.FolderBookExporter.export(context, doc.id)
                HttpResponse.FileBody(z, "application/zip", z.name)
            }
            "clean-text" -> {
                require(isPdf(doc)) { "Only PDFs have a text layer to export." }
                val r = configuredRenderer(doc)
                val pagesText = (0 until r.pageCount.coerceAtLeast(1)).map { (it + 1) to r.extractPageTextData(it).fullText }
                val f = com.example.reader.PdfPageDiagnosticsAndOcrEngine.exportCleanExtractedTextFile(context, doc.title, pagesText)
                HttpResponse.FileBody(f, "text/plain; charset=utf-8", MarkdownExporter.sanitizeFilename(doc.title).ifBlank { "document" } + " – text.txt")
            }
            "searchable-pdf" -> {
                require(isPdf(doc)) { "Only PDFs can be exported as a searchable PDF." }
                val r = configuredRenderer(doc)
                val f = com.example.reader.PdfPageDiagnosticsAndOcrEngine.exportSearchablePdfWithCorrectedTextLayer(
                    context, r, doc.title, r.documentExtractionMode, r.pageExtractionOverrides, r.getManualPageTextEdits())
                HttpResponse.FileBody(f, "application/pdf", MarkdownExporter.sanitizeFilename(doc.title).ifBlank { "document" } + " – searchable.pdf")
            }
            "ris" -> {
                val f = File(dir, "${MarkdownExporter.sanitizeFilename(doc.title).ifBlank { "reference" }}.ris")
                f.writeText(com.example.reader.Ris.export(doc, BibStore.get(doc.id)), Charsets.UTF_8)
                HttpResponse.FileBody(f, "application/x-research-info-systems; charset=utf-8", f.name)
            }
            "bib" -> {
                val rec = BibStore.get(doc.id)
                val f = File(dir, "${Citations.citeKey(doc, rec).ifBlank { "reference" }}.bib")
                f.writeText(Citations.bibtex(doc, rec), Charsets.UTF_8)
                HttpResponse.FileBody(f, "application/x-bibtex; charset=utf-8", f.name)
            }
            "note" -> {
                val h = hls.firstOrNull { it.id == req.query["id"]?.toLongOrNull() } ?: throw NoSuchElementException("No such note.")
                md(h.title, MarkdownExporter.generateHighlightMarkdown(h, doc, settings))
            }
            "chapter" -> {
                val vol = EditedVolumes.get(context, doc.id) ?: throw NoSuchElementException("This book has no chapter list.")
                val i = req.query["index"]?.toIntOrNull() ?: throw IllegalArgumentException("Which chapter?")
                require(i in vol.chapters.indices) { "No such chapter." }
                BibStore.init(context)
                val (cdoc, crec) = EditedVolumes.chapterAsDocument(doc, vol, i)
                val base = MarkdownExporter.sanitizeFilename(EditedVolumes.exportFileName(doc, vol, i).substringBeforeLast('.')).ifBlank { "chapter" }
                when (req.query["format"].orEmpty()) {
                    "pdf" -> {
                        val f = File(dir, "$base.pdf")
                        if (isPdf(doc)) ChapterExporter.extractPdfPages(context, File(doc.filePath), vol.chapters[i].startPage, EditedVolumes.endPageOf(vol.chapters, i, vol.totalPages), f)
                        else ChapterExporter.renderEpubChapterPdf(context, doc, vol, i, f)
                        HttpResponse.FileBody(f, "application/pdf", f.name)
                    }
                    "ris" -> {
                        val f = File(dir, "$base.ris")
                        f.writeText(com.example.reader.Ris.export(cdoc, crec), Charsets.UTF_8)
                        HttpResponse.FileBody(f, "application/x-research-info-systems; charset=utf-8", f.name)
                    }
                    "bib" -> {
                        val f = File(dir, "${Citations.citeKey(cdoc, crec).ifBlank { "chapter" }}.bib")
                        f.writeText(Citations.bibtex(cdoc, crec), Charsets.UTF_8)
                        HttpResponse.FileBody(f, "application/x-bibtex; charset=utf-8", f.name)
                    }
                    "notes" -> {
                        val notes = EditedVolumes.highlightsIn(vol, i, hls)
                        md("${vol.chapters[i].title} – notes", MarkdownExporter.generateUnifiedObsidianBookMarkdown(cdoc, notes, settings))
                    }
                    else -> {
                        val r = ChapterExporter.export(context, doc, vol, i, hls, settings)
                        HttpResponse.FileBody(r.file, "application/zip", r.file.name)
                    }
                }
            }
            else -> throw NoSuchElementException("Unknown export.")
        }
    }

    // ------------------------------------------------------------------------------- uploads

    private suspend fun upload(req: HttpRequest): HttpResponse {
        val name = File(req.query["name"].orEmpty()).name.ifBlank { "document" }
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(ext in LibraryImporter.supportedExtensions) { "“$name” is not a PDF, EPUB, DAISY (.zip), HTML, Markdown or text file." }
        require(req.contentLength > 0) { "The file is empty." }
        val free = context.cacheDir.usableSpace
        require(free <= 0 || req.contentLength < free - 50L * 1024 * 1024) { "Not enough free space on the tablet." }
        val tmpDir = File(context.cacheDir, "web-uploads").apply { mkdirs() }
        val tmp = File(tmpDir, "${System.nanoTime()}.$ext")
        try {
            tmp.outputStream().use { out -> req.body.copyTo(out, 256 * 1024) }
            require(tmp.length() == req.contentLength) { "The upload was interrupted." }
            val doc = LibraryImporter.import(context, ops, tmp, name, req.query["category"].orEmpty())
            BibStore.seedFromFile(doc)
            req.query["folder"]?.toLongOrNull()?.let { fid -> FolderBooks.addParts(context, fid, listOf(doc.id)) }
            return HttpResponse.json(docJson(doc, 0).toString(), 201)
        } finally {
            tmp.delete()
        }
    }

    companion object {
        const val COOKIE = "ml_session"
    }
}
