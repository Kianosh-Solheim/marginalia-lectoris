package com.example.reader

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.example.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * This device (usually a phone) paired with a Marginalia tablet: Settings → Connect to a tablet.
 *
 * Pairing: the tablet shows a QR code (marginalia://pair?c=CODE&t=ID&n=NAME&u=URL…); the code is traded
 * once at `POST /api/pair` for a device key, sent with every request after that. The tablet's addresses
 * are tried in turn, the last one that answered first.
 *
 * Books are downloaded with the tablet's book ID (uuid), so they can be matched later. Listening places
 * sync both ways, newest change wins, with the tablet's clock difference taken into account. Nothing is
 * lost when the tablet can't be reached: the phone keeps its place with its time, and the next sync sends it.
 */
object TabletLink {
    private const val PREFS = "tablet_link"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var pending: Job? = null

    data class Link(val urls: List<String>, val tabletId: String, val tabletName: String, val key: String, val deviceId: String)
    data class Offer(val code: String, val tabletId: String, val tabletName: String, val urls: List<String>)
    data class Remote(val id: Long, val uuid: String, val title: String, val author: String, val fileType: String, val size: Long, val audiobook: Boolean)

    /** One line for the settings: when it last synced, or why it couldn't. */
    val status = mutableStateOf("")
    val busy = mutableStateOf(false)
    val paired = mutableStateOf(false)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(ctx: Context) { paired.value = link(ctx) != null; if (status.value.isBlank()) status.value = prefs(ctx).getString("status", "").orEmpty() }

    fun link(ctx: Context): Link? {
        val p = prefs(ctx)
        val key = p.getString("key", null) ?: return null
        return Link(p.getString("urls", "")!!.split('\n').filter { it.isNotBlank() }, p.getString("tabletId", "")!!, p.getString("tabletName", "")!!, key, p.getString("deviceId", "")!!)
    }

    /** Reads the QR code's text (or a pasted marginalia://pair link). */
    fun parse(text: String): Offer? = runCatching {
        val u = Uri.parse(text.trim())
        if (u.scheme != "marginalia" || u.authority != "pair") return null
        Offer(u.getQueryParameter("c").orEmpty(), u.getQueryParameter("t").orEmpty(), u.getQueryParameter("n").orEmpty(), u.getQueryParameters("u").filter { it.startsWith("http") })
    }.getOrNull()?.takeIf { it.code.isNotBlank() && it.urls.isNotEmpty() }

    /** A typed address (e.g. 192.168.1.20:8080) made into a URL. */
    fun addressToUrl(a: String): String {
        val t = a.trim().trimEnd('/')
        val withScheme = if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
        return if (Regex(":\\d+$").containsMatchIn(withScheme.substringAfter("://"))) withScheme else "$withScheme:8080"
    }

    /** Pairs with the tablet. Returns null when it worked, or the reason it didn't. */
    suspend fun pair(ctx: Context, offer: Offer): String? = withContext(Dispatchers.IO) { pairNow(ctx, offer) }

    private fun pairNow(ctx: Context, offer: Offer): String? {
        val name = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(" ").replaceFirstChar { it.uppercase() }
        var lastError = "The tablet did not answer."
        for (url in offer.urls) {
            try {
                val r = JSONObject(http(url, "/api/pair", "POST", JSONObject().put("code", offer.code).put("name", name).toString(), key = null, timeoutMs = 8000))
                prefs(ctx).edit()
                    .putString("urls", (listOf(url) + offer.urls).distinct().joinToString("\n"))
                    .putString("tabletId", r.optString("tabletId", offer.tabletId)).putString("tabletName", r.optString("tabletName", offer.tabletName))
                    .putString("key", r.getString("key")).putString("deviceId", r.optString("deviceId"))
                    .putLong("clockOffset", r.optLong("now", System.currentTimeMillis()) - System.currentTimeMillis())
                    .apply()
                paired.value = true
                setStatus(ctx, "Paired with ${r.optString("tabletName", "the tablet")}.")
                return null
            } catch (e: HttpError) { return e.message } catch (e: Exception) { lastError = "Could not reach the tablet at $url (${e.message ?: e.javaClass.simpleName}). Is its server on, and are both on the same Wi‑Fi?" }
        }
        return lastError
    }

    fun forget(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        paired.value = false; status.value = ""
    }

    // ------------------------------------------------------------------------------- library and downloads

    suspend fun remoteLibrary(ctx: Context): List<Remote> = withContext(Dispatchers.IO) { remoteLibraryNow(ctx) }

    private fun remoteLibraryNow(ctx: Context): List<Remote> {
        val o = JSONObject(call(ctx, "/api/library"))
        val a = o.optJSONArray("documents") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }
            .filter { !it.optBoolean("folder") && !it.optBoolean("placeholder") && it.optString("uuid").isNotBlank() }
            .map { Remote(it.getLong("id"), it.getString("uuid"), it.optString("title"), it.optString("displayAuthor", it.optString("author")), it.optString("fileType"), it.optLong("size"), it.optBoolean("audiobook")) }
            .also { list -> remember(ctx, list) }
    }

    /** Remote ID by uuid, so a sync can reach a downloaded book's audiobook without listing the library. */
    private fun remember(ctx: Context, list: List<Remote>) {
        val m = remoteIds(ctx); list.forEach { m.put(it.uuid, it.id) }
        prefs(ctx).edit().putString("remoteIds", m.toString()).apply()
    }

    private fun remoteIds(ctx: Context) = runCatching { JSONObject(prefs(ctx).getString("remoteIds", "{}")!!) }.getOrDefault(JSONObject())

    /** Books here that came from the tablet: uuid → local book ID. */
    suspend fun downloaded(ctx: Context): Map<String, Long> = withContext(Dispatchers.IO) { downloadedNow(ctx) }

    private suspend fun downloadedNow(ctx: Context): Map<String, Long> {
        val dao = AppDatabase.getInstance(ctx).documentDao()
        val ids = remoteIds(ctx)
        val out = HashMap<String, Long>()
        for (u in ids.keys().asSequence().toList()) dao.getDocumentByUuid(u)?.let { out[u] = it.id }
        return out
    }

    /**
     * Downloads a book from the tablet, with its audiobook and sentence map when it has them.
     * [progress]: a line to show while it runs.
     */
    suspend fun download(ctx: Context, r: Remote, withAudio: Boolean, progress: (String) -> Unit) = withContext(Dispatchers.IO) { downloadNow(ctx, r, withAudio, progress) }

    private suspend fun downloadNow(ctx: Context, r: Remote, withAudio: Boolean, progress: (String) -> Unit) {
        val dao = AppDatabase.getInstance(ctx).documentDao()
        var doc = dao.getDocumentByUuid(r.uuid)
        if (doc == null) {
            progress("Downloading “${r.title}”…")
            val tmp = File(ctx.cacheDir, "tablet-${System.nanoTime()}.part")
            try {
                val name = fetchToFile(ctx, "/api/doc/${r.id}/file?download=1", tmp) { done -> progress("Downloading “${r.title}”… ${mb(done)} of ${mb(r.size)}") }
                val ext = when (r.fileType.uppercase()) { "PDF" -> "pdf"; "EPUB" -> "epub"; "DAISY" -> "zip"; "MD" -> "md"; "HTML" -> "html"; "TXT" -> "txt"; else -> name.substringAfterLast('.', "epub") }
                val fileName = if (name.contains('.')) name else "${r.title.ifBlank { "Book" }}.$ext"
                doc = com.example.server.LibraryImporter.import(ctx, com.example.server.LibraryOps(ctx), tmp, fileName, "BOOK")
                dao.setUuid(doc.id, r.uuid)
                doc = dao.getDocumentById(doc.id) ?: doc
            } finally { tmp.delete() }
        }
        remember(ctx, listOf(r))
        // The book's notes so far (later ones come with each sync).
        runCatching {
            progress("Notes…")
            val offset = prefs(ctx).getLong("clockOffset", 0L)
            val body = JSONObject().put("docs", JSONArray(listOf(r.uuid))).put("since", 0L).put("push", JSONObject())
            NoteSync.apply(ctx, JSONObject(call(ctx, "/api/sync/notes", "POST", body.toString())), -offset)
        }.onFailure { Log.w("TabletLink", "Notes not fetched", it) }
        if (!withAudio || !r.audiobook) return
        val ab = JSONObject(call(ctx, "/api/doc/${r.id}/audiobook"))
        val files = ab.optJSONArray("files") ?: JSONArray()
        if (Audiobooks.files(ctx, doc!!.id).size < files.length()) {
            Audiobooks.remove(ctx, doc, null)
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val tmp = Audiobooks.incoming(ctx)
                try {
                    fetchToFile(ctx, "/api/doc/${r.id}/audiobook/file/${f.getInt("index")}?download=1", tmp) { done ->
                        progress("Audiobook, file ${i + 1} of ${files.length()}: ${mb(done)} of ${mb(f.optLong("size"))}")
                    }
                    Audiobooks.add(ctx, doc, tmp, f.optString("name", "part-$i.mp3"))
                } finally { tmp.delete() }
            }
        }
        progress("Sentence map…")
        runCatching { AudiobookSync.saveMap(ctx, doc, JSONObject(call(ctx, "/api/doc/${r.id}/audiobook/sync"))) }
            .onFailure { if (it !is HttpError) Log.w("TabletLink", "No map", it) }
        // Start where the tablet is.
        val offset = prefs(ctx).getLong("clockOffset", 0L)
        Audiobooks.savePosition(ctx, doc.id, ab.optLong("positionMs"), ab.optDouble("speed", 1.0), at = ab.optLong("updatedAt") - offset)
    }

    // ------------------------------------------------------------------------------- sync

    /** A sync in a moment (several calls close together make one). */
    fun syncSoon(ctx: Context, delayMs: Long = 1500) {
        val app = ctx.applicationContext
        if (link(app) == null) return
        pending?.cancel()
        pending = scope.launch { delay(delayMs); syncNow(app) }
    }

    /** Syncs the listening places of the books downloaded from the tablet. Returns the status line. */
    suspend fun syncNow(ctx: Context): String = withContext(Dispatchers.IO) { mutex.withLock {
        val app = ctx.applicationContext
        link(app) ?: return@withLock ""
        busy.value = true
        try {
            val session = JSONObject(call(app, "/api/session"))
            if (!session.optBoolean("signedIn")) { setStatus(app, "The tablet no longer knows this phone. Pair it again."); return@withLock status.value }
            val offset = session.optLong("now", System.currentTimeMillis()) - System.currentTimeMillis()
            prefs(app).edit().putLong("clockOffset", offset).apply()
            val ids = remoteIds(app)
            val dao = AppDatabase.getInstance(app).documentDao()
            var sent = 0; var taken = 0
            for (u in ids.keys().asSequence().toList()) {
                val local = dao.getDocumentByUuid(u) ?: continue
                if (Audiobooks.files(app, local.id).isEmpty()) continue
                val remote = JSONObject(runCatching { call(app, "/api/doc/${ids.getLong(u)}/audiobook") }.getOrNull() ?: continue)
                if (!remote.optBoolean("exists")) continue
                val mine = Audiobooks.json(app, local.id)
                val mineAt = mine.optLong("updatedAt")
                val theirsAt = remote.optLong("updatedAt") - offset   // on this phone's clock
                val playingHere = AudiobookPlayer.state.value.let { it.docId == local.id && it.playing }
                when {
                    mineAt > theirsAt + 1000 && mine.optLong("positionMs") != remote.optLong("positionMs") -> {
                        val r = JSONObject(call(app, "/api/doc/${ids.getLong(u)}/audiobook/position", "PUT",
                            JSONObject().put("positionMs", mine.optLong("positionMs")).put("speed", mine.optDouble("speed", 1.0)).toString()))
                        if (!r.optBoolean("tabletPlaying")) sent++
                    }
                    theirsAt > mineAt + 1000 && !playingHere && mine.optLong("positionMs") != remote.optLong("positionMs") -> {
                        Audiobooks.savePosition(app, local.id, remote.optLong("positionMs"), null, at = theirsAt)
                        AudiobookPlayer.positionFromElsewhere(local.id, remote.optLong("positionMs"))
                        taken++
                    }
                }
            }
            val notes = syncNotes(app, ids.keys().asSequence().toList(), offset)
            val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
            val parts = listOfNotNull(
                if (sent > 0) "$sent place${if (sent == 1) "" else "s"} sent" else null,
                if (taken > 0) "$taken place${if (taken == 1) "" else "s"} from the tablet" else null,
                if (notes.first > 0) "${notes.first} note${if (notes.first == 1) "" else "s"} sent" else null,
                if (notes.second > 0) "${notes.second} note change${if (notes.second == 1) "" else "s"} from the tablet" else null
            )
            setStatus(app, "Synced at $time" + if (parts.isEmpty()) "." else parts.joinToString(", ", ": ", "."))
        } catch (e: HttpError) {
            setStatus(app, if (e.code == 401) "The tablet no longer knows this phone. Pair it again." else "Sync failed: ${e.message}")
        } catch (e: Exception) {
            setStatus(app, "The tablet is not reachable now (its server off, or another Wi‑Fi). This phone keeps its places and sends them next time.")
        } finally { busy.value = false }
        status.value
    } }

    /**
     * Highlights and notes of the downloaded books, both ways, in one request. The phone's changes go on the
     * tablet's clock (+[offset]); the tablet's come back onto this one. Returns (sent, received).
     */
    private fun syncNotes(ctx: Context, docUuids: List<String>, offset: Long): Pair<Int, Int> {
        if (docUuids.isEmpty()) return 0 to 0
        val p = prefs(ctx)
        val pulledAt = p.getLong("notesPulledAt", 0L)   // tablet clock
        val pushedAt = p.getLong("notesPushedAt", 0L)   // this clock
        val started = System.currentTimeMillis()
        val mine = NoteSync.changes(ctx, docUuids, pushedAt)
        val push = shifted(mine, offset)
        val body = JSONObject().put("docs", JSONArray(docUuids)).put("since", pulledAt).put("push", push)
        val theirs = JSONObject(call(ctx, "/api/sync/notes", "POST", body.toString()))
        val r = NoteSync.apply(ctx, theirs, -offset)
        p.edit().putLong("notesPulledAt", theirs.optLong("now", pulledAt)).putLong("notesPushedAt", started).apply()
        return (push.getJSONArray("highlights").length() + push.getJSONArray("deleted").length()) to r.total
    }

    private fun shifted(c: JSONObject, by: Long): JSONObject {
        val h = c.optJSONArray("highlights") ?: JSONArray(); val d = c.optJSONArray("deleted") ?: JSONArray()
        for (i in 0 until h.length()) h.getJSONObject(i).let { o ->
            o.put("updatedAt", o.optLong("updatedAt") + by)
            if (o.has("createdTimestamp")) o.put("createdTimestamp", o.optLong("createdTimestamp") + by)
        }
        for (i in 0 until d.length()) d.getJSONObject(i).let { o -> o.put("deletedAt", o.optLong("deletedAt") + by) }
        return c
    }

    private fun setStatus(ctx: Context, s: String) { status.value = s; prefs(ctx).edit().putString("status", s).apply() }

    // ------------------------------------------------------------------------------- HTTP

    class HttpError(val code: Int, message: String) : Exception(message)

    /** A request to the paired tablet, trying its addresses in turn. */
    private fun call(ctx: Context, path: String, method: String = "GET", body: String? = null): String {
        val l = link(ctx) ?: throw IllegalStateException("Not paired with a tablet.")
        var last: Exception? = null
        for (url in l.urls) {
            try { return http(url, path, method, body, l.key).also { good(ctx, l, url) } }
            catch (e: HttpError) { throw e } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("The tablet has no address.")
    }

    /** Streams a download to [out]; returns the file name the tablet gave. */
    private fun fetchToFile(ctx: Context, path: String, out: File, progress: (Long) -> Unit): String {
        val l = link(ctx) ?: throw IllegalStateException("Not paired with a tablet.")
        var last: Exception? = null
        for (url in l.urls) {
            try {
                LanHttp.request(url, path, "GET", l.key, null, 15000).use { r ->
                    if (r.code !in 200..299) throw HttpError(r.code, errorText(r))
                    val name = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").find(r.header("content-disposition"))?.groupValues?.get(1)
                        ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }.orEmpty()
                    var done = 0L; var shown = 0L
                    out.outputStream().use { o ->
                        val buf = ByteArray(256 * 1024)
                        while (true) { val n = r.body.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n
                            if (done - shown > 2_000_000) { shown = done; progress(done) } }
                    }
                    val expected = r.header("content-length").toLongOrNull()
                    if (expected != null && done != expected) throw java.io.IOException("The download stopped after ${mb(done)} of ${mb(expected)}.")
                    good(ctx, l, url)
                    return name
                }
            } catch (e: HttpError) { throw e } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("The tablet has no address.")
    }

    /** The address that answered goes first next time. */
    private fun good(ctx: Context, l: Link, url: String) {
        if (l.urls.firstOrNull() != url) prefs(ctx).edit().putString("urls", (listOf(url) + l.urls).distinct().joinToString("\n")).apply()
    }

    private fun http(base: String, path: String, method: String, body: String?, key: String?, timeoutMs: Int = 5000): String =
        LanHttp.request(base, path, method, key, body?.toByteArray(), timeoutMs).use { r ->
            if (r.code !in 200..299) throw HttpError(r.code, errorText(r))
            r.body.readBytes().toString(Charsets.UTF_8)
        }

    private fun errorText(r: LanHttp.Response): String {
        val t = runCatching { r.body.readBytes().toString(Charsets.UTF_8) }.getOrDefault("")
        return runCatching { JSONObject(t).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "The tablet answered ${r.code}."
    }

    private fun mb(b: Long) = if (b <= 0) "?" else "%.1f MB".format(b / 1_000_000.0)
}
