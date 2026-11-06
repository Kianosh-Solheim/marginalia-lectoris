package com.example.reader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * AI search: finding passages by what they are about, not only by their words. Runs only when asked.
 *
 * - **Guided** (A): the AI chosen in Settings → AI turns the question into the words authors would use
 *   (Norwegian and English), the normal search finds passages with them, and the AI ranks the best and
 *   says in a line why each fits. Only those passages are sent to the AI.
 * - **Meaning, Gemini** (B): every page gets a meaning vector from Gemini's embedding model once (the
 *   text is sent to Google for that); a search compares the question's vector with them.
 * - **Meaning, offline** (C): the same with a small model on the tablet (multilingual-e5-small); nothing
 *   leaves the tablet, but building the index takes hours on an e-ink tablet.
 */
object AiSearch {
    private const val TAG = "AiSearch"
    const val GEMINI = "gemini"
    const val LOCAL = "local"
    private const val GEMINI_MODEL = "gemini-embedding-001"
    private const val GEMINI_DIMS = 768

    data class Result(val docId: Long, val loc: Int, val href: String, val label: String, val snippet: String,
                      val start: Int, val end: Int, val score: Float, val why: String)
    data class Answer(val results: List<Result>, val terms: List<String>, val note: String = "")

    private val client = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).connectTimeout(20, TimeUnit.SECONDS).readTimeout(100, TimeUnit.SECONDS).build()

    // ------------------------------------------------------------------ A: guided

    /** Guided AI search (two AI calls): the question → search words → passages → ranked with reasons. */
    fun guided(ctx: Context, question: String, docId: Long?, filters: SearchIndex.Filters?): Answer {
        val q = question.trim()
        require(q.isNotEmpty()) { "Write what you are looking for." }
        // 1) What would authors write?
        val exp = NoteAiAssistant.askJson(ctx,
            "You help someone search their own academic library (books and articles in Norwegian and English; mostly political science, " +
                "law and sociology). They describe what they are looking for. Give the search words that passages about it would actually " +
                "contain: 8 to 14 short searches of 1 to 3 words, in both English and Norwegian (bokmål), including synonyms, technical " +
                "terms and typical phrasings; put a phrase in double quotes when the words belong together. Do not use operators. " +
                "Return only JSON: {\"queries\": [string, ...]}",
            q, maxTokens = 600, maxChars = 2000)
        val terms = exp.optJSONArray("queries")?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }.orEmpty()
            .map { it.replace(Regex("[^\\p{L}\\p{N}\"' -]"), " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 2 }.distinctBy { it.lowercase() }.take(14)
        check(terms.isNotEmpty()) { "The AI gave no search words. Try again or describe it differently." }
        // 2) Passages with those words: the ones found by several searches first.
        val pool = LinkedHashMap<String, Pair<SearchIndex.Hit, Int>>()
        for (t in terms) {
            val pq = SearchIndex.prepare(ctx, t, false, false, false, filters)
            val r = SearchIndex.search(ctx, t, false, docId, perBook = if (docId != null) 12 else 3, prepared = pq)
            val hits = (if (docId != null) r.flatMap { it.second.second }.take(12) else r.take(25).flatMap { it.second.second })
            hits.forEach { h -> val k = "${h.docId}|${h.loc}|${h.href}"; pool[k] = (pool[k]?.first ?: h) to ((pool[k]?.second ?: 0) + 1) }
        }
        if (pool.isEmpty()) return Answer(emptyList(), terms, "No passages had any of these words.")
        val cands = pool.values.sortedByDescending { it.second }.take(40).map { it.first }
        // 3) The AI ranks them.
        val w = SearchIndex.db(ctx)
        val titles = HashMap<Long, String>()
        val texts = cands.map { h -> passageAround(w, h, 520) }
        val content = buildString {
            append("Question: ").append(q).append("\n\n")
            cands.forEachIndexed { i, h ->
                val t = titles.getOrPut(h.docId) { docTitle(ctx, h.docId) }
                append("[").append(i + 1).append("] ").append(t).append(", ").append(if (h.label.isNotBlank()) h.label else "p. ${h.loc}").append("\n")
                append(texts[i]).append("\n\n")
            }
        }
        val ranked = NoteAiAssistant.askJson(ctx,
            "Below are numbered passages from someone's library and their question. Judge how well each passage answers or bears on " +
                "the question (0 = not at all, 10 = exactly). Leave out passages below 5. For each kept passage write why in at most 15 " +
                "words, in the language of the question. Return only JSON: {\"results\": [{\"n\": number, \"score\": number, \"why\": string}, ...]} " +
                "best first.",
            content, maxTokens = 2400, maxChars = 26000)
        val arr = ranked.optJSONArray("results") ?: JSONArray()
        val out = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val n = o.optInt("n", -1) - 1
            val h = cands.getOrNull(n) ?: return@mapNotNull null
            Result(h.docId, h.loc, h.href, h.label, h.snippet, h.start, h.end, o.optDouble("score", 0.0).toFloat() / 10f, o.optString("why"))
        }.distinctBy { "${it.docId}|${it.loc}|${it.href}" }.sortedByDescending { it.score }
        return Answer(out, terms, if (out.isEmpty()) "The AI found none of the ${cands.size} passages relevant." else "")
    }

    private fun docTitle(ctx: Context, id: Long): String = runCatching {
        kotlinx.coroutines.runBlocking { com.example.data.local.AppDatabase.getInstance(ctx).documentDao().getDocumentById(id)?.let { d -> listOf(d.author.split(';').first().trim(), d.title).filter { it.isNotBlank() }.joinToString(": ") } }
    }.getOrNull().orEmpty()

    /** About [size] characters of the page around a search hit. */
    private fun passageAround(w: SQLiteDatabase, h: SearchIndex.Hit, size: Int): String {
        val text = w.rawQuery("SELECT text FROM units WHERE doc_id=? AND loc=? AND href=? LIMIT 1", arrayOf(h.docId.toString(), h.loc.toString(), h.href)).use { if (it.moveToFirst()) it.getString(0) else null }
            ?.let { SearchIndex.collapse(it) } ?: return h.snippet
        val core = h.snippet.removePrefix("…").removeSuffix("…").let { it.substring((it.length / 2 - 20).coerceAtLeast(0), (it.length / 2 + 20).coerceAtMost(it.length)) }
        val at = text.indexOf(core).takeIf { it >= 0 } ?: return h.snippet
        val a = (at - size / 2).coerceAtLeast(0); val b = (at + size / 2).coerceAtMost(text.length)
        return (if (a > 0) "…" else "") + text.substring(a, b).trim() + if (b < text.length) "…" else ""
    }

    // ------------------------------------------------------------------ B and C: meaning index

    data class Progress(val running: Boolean = false, val src: String = "", val done: Int = 0, val total: Int = 0, val note: String = "", val error: String = "")
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** How much of the library has meaning vectors from [src]: (pages done, pages in the index). */
    fun counts(ctx: Context, src: String): Pair<Int, Int> = runCatching {
        val w = SearchIndex.db(ctx)
        val total = w.rawQuery("SELECT COUNT(*) FROM units", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val done = w.rawQuery("SELECT COUNT(DISTINCT e.unit_id) FROM emb e JOIN units u ON u.id = e.unit_id WHERE e.src=?", arrayOf(src)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        done to total
    }.getOrDefault(0 to 0)

    fun stop() { job?.cancel(); job = null; _progress.value = _progress.value.copy(running = false, note = "Stopped") }

    /** Deletes the meaning vectors of [src] (and, for the offline model, the model itself when [model]). */
    fun delete(ctx: Context, src: String, model: Boolean = false) {
        if (_progress.value.running && _progress.value.src == src) stop()
        runCatching { SearchIndex.db(ctx).execSQL("DELETE FROM emb WHERE src=?", arrayOf(src)) }
        cache.remove(src)
        if (model && src == LOCAL) { LocalEmbedder.close(); LocalEmbedder.dir(ctx.filesDir).deleteRecursively() }
    }

    /** Builds (or brings up to date) the meaning index from [src], in the background. */
    fun start(ctx: Context, src: String) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            _progress.value = Progress(true, src, note = "Starting…")
            try { build(app, src) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { Log.w(TAG, "Meaning index ($src)", e); _progress.value = _progress.value.copy(running = false, error = e.message ?: e.toString()) }
        }
    }

    /** The parts of a page given a vector: Gemini takes a whole page, the offline model about 1,200 characters at a time. */
    private fun parts(text: String, src: String): List<IntRange> {
        if (text.isBlank()) return emptyList()
        if (src == GEMINI) return if (text.count { it.isLetter() } >= 30) listOf(0 until minOf(text.length, 6000)) else emptyList()
        val out = ArrayList<IntRange>()
        var a = 0
        while (a < text.length) {
            var b = minOf(text.length, a + 1200)
            if (b < text.length) {
                val cut = text.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), b).takeIf { it > a + 600 }
                if (cut != null) b = cut + 1
            }
            if (text.substring(a, b).count { it.isLetter() } >= 30) out += a until b
            a = b
        }
        return out
    }

    private suspend fun build(ctx: Context, src: String) {
        val w = SearchIndex.db(ctx)
        if (src == LOCAL) check(LocalEmbedder.installed(ctx.filesDir)) { "Download the offline model first." }
        if (src == GEMINI) check(AiPreferences(ctx).readKey(AiProvider.GEMINI).isNotBlank()) { "Add a Gemini API key in Settings → AI first." }
        // Pages that are gone (books deleted, pages read again with OCR) lose their vectors.
        w.execSQL("DELETE FROM emb WHERE unit_id NOT IN (SELECT id FROM units)")
        var (done, total) = counts(ctx, src)
        val batch = if (src == GEMINI) 40 else 6
        while (kotlin.coroutines.coroutineContext.isActive) {
            val rows = w.rawQuery("SELECT u.id, u.text FROM units u WHERE NOT EXISTS (SELECT 1 FROM emb e WHERE e.src=? AND e.unit_id=u.id) ORDER BY u.doc_id, u.id LIMIT $batch", arrayOf(src)).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1).orEmpty()) }
            }
            if (rows.isEmpty()) break
            val items = rows.flatMap { (id, text) -> parts(text, src).mapIndexed { k, r -> Triple(id, k, r) } }
            val vecs: List<FloatArray> = if (items.isEmpty()) emptyList() else {
                val texts = items.map { (id, _, r) -> rows.first { it.first == id }.second.substring(r.first, r.last + 1) }
                if (src == GEMINI) geminiEmbed(ctx, texts, query = false) else texts.chunked(3).flatMap { LocalEmbedder.embed(ctx.filesDir, it, query = false) }
            }
            w.beginTransaction()
            try {
                items.forEachIndexed { i, (id, k, r) -> w.execSQL("INSERT OR REPLACE INTO emb(src, unit_id, part, a, b, vec) VALUES(?,?,?,?,?,?)", arrayOf<Any>(src, id, k, r.first, r.last + 1, quantize(vecs[i]))) }
                // Pages without text are marked as done.
                rows.filter { (id, _) -> items.none { it.first == id } }.forEach { (id, _) -> w.execSQL("INSERT OR REPLACE INTO emb(src, unit_id, part, a, b, vec) VALUES(?,?,?,?,?,?)", arrayOf<Any>(src, id, -1, 0, 0, ByteArray(0))) }
                w.setTransactionSuccessful()
            } finally { w.endTransaction() }
            done += rows.size
            if (done > total) total = done
            cache.remove(src)
            _progress.value = Progress(true, src, done, total, if (src == GEMINI) "Asking Gemini for meaning vectors" else "Reading pages with the offline model")
        }
        cache.remove(src)
        _progress.value = Progress(false, src, done, total, "Ready")
    }

    private fun quantize(v: FloatArray): ByteArray = ByteArray(v.size) { (v[it] * 127f).toInt().coerceIn(-127, 127).toByte() }

    /** Gemini meaning vectors (length 1). Waits and tries again when the free quota is used up. */
    private suspend fun geminiEmbed(ctx: Context, texts: List<String>, query: Boolean): List<FloatArray> {
        val key = AiPreferences(ctx).readKey(AiProvider.GEMINI)
        check(key.isNotBlank()) { "Add a Gemini API key in Settings → AI." }
        val body = JSONObject().put("requests", JSONArray().apply {
            texts.forEach { t -> put(JSONObject().put("model", "models/$GEMINI_MODEL").put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", t))))
                .put("taskType", if (query) "RETRIEVAL_QUERY" else "RETRIEVAL_DOCUMENT").put("outputDimensionality", GEMINI_DIMS)) }
        })
        var wait = 30_000L
        while (true) {
            val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:batchEmbedContents").header("x-goog-api-key", key)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val (code, text) = try { client.newCall(req).execute().use { it.code to it.body?.string().orEmpty() } }
                catch (e: java.io.IOException) { if (query) throw IllegalStateException("Could not reach Gemini. Check the internet connection."); -1 to "" }
            if (code == 200) {
                val arr = JSONObject(text).optJSONArray("embeddings") ?: throw IllegalStateException("Gemini returned no vectors.")
                return (0 until arr.length()).map { i -> arr.getJSONObject(i).getJSONArray("values").let { v -> norm(FloatArray(v.length()) { v.getDouble(it).toFloat() }) } }
            }
            if (code == 401 || code == 403) throw IllegalStateException("Gemini refused the API key (HTTP $code). Check it in Settings → AI.")
            if (code == 400) throw IllegalStateException("Gemini could not read the request (HTTP 400): ${text.take(200)}")
            if (query && code != 429) throw IllegalStateException("Gemini did not answer (HTTP $code).")
            // Quota or a network hiccup: wait and try again (the index continues where it was).
            if (query && code == 429) throw IllegalStateException("Gemini's quota is used up for now; try again in a minute.")
            _progress.value = _progress.value.copy(note = if (code == 429) "Waiting for Gemini's quota (it continues by itself)…" else "No connection; trying again…")
            delay(wait); wait = (wait * 2).coerceAtMost(300_000L)
        }
    }

    private fun norm(v: FloatArray): FloatArray {
        var s = 0.0; for (x in v) s += x * x
        val inv = (1.0 / kotlin.math.sqrt(s).coerceAtLeast(1e-9)).toFloat()
        for (i in v.indices) v[i] *= inv
        return v
    }

    // The vectors, kept in memory between searches.
    private class Vectors(val unit: LongArray, val doc: LongArray, val a: IntArray, val b: IntArray, val dims: Int, val data: ByteArray)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Vectors>()

    private fun vectors(ctx: Context, src: String): Vectors = cache.getOrPut(src) {
        val w = SearchIndex.db(ctx)
        val n = w.rawQuery("SELECT COUNT(*) FROM emb WHERE src=? AND part >= 0", arrayOf(src)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val dims = if (src == GEMINI) GEMINI_DIMS else LocalEmbedder.DIMS
        val unit = LongArray(n); val doc = LongArray(n); val a = IntArray(n); val b = IntArray(n); val data = ByteArray(n * dims)
        var k = 0
        w.rawQuery("SELECT e.unit_id, u.doc_id, e.a, e.b, e.vec FROM emb e JOIN units u ON u.id = e.unit_id WHERE e.src=? AND e.part >= 0", arrayOf(src)).use { c ->
            while (c.moveToNext() && k < n) {
                val v = c.getBlob(4); if (v.size != dims) continue
                unit[k] = c.getLong(0); doc[k] = c.getLong(1); a[k] = c.getInt(2); b[k] = c.getInt(3)
                System.arraycopy(v, 0, data, k * dims, dims); k++
            }
        }
        Vectors(unit.copyOf(k), doc.copyOf(k), a.copyOf(k), b.copyOf(k), dims, data.copyOf(k * dims))
    }

    /** Meaning search: the passages whose meaning is closest to the question. */
    fun semantic(ctx: Context, question: String, src: String, docId: Long?, filters: SearchIndex.Filters?, k: Int = 40): Answer {
        val q = question.trim()
        require(q.isNotEmpty()) { "Write what you are looking for." }
        val vs = vectors(ctx, src)
        check(vs.unit.isNotEmpty()) { if (src == GEMINI) "The Gemini meaning index is empty. Build it first." else "The offline meaning index is empty. Download the model and build the index first." }
        val qv = if (src == GEMINI) kotlinx.coroutines.runBlocking { geminiEmbed(ctx, listOf(q), query = true) }[0] else LocalEmbedder.embed(ctx.filesDir, listOf(q), query = true)[0]
        val qb = quantize(qv)
        val allowed = SearchIndex.allowedFor(ctx, filters)
        // Best part per page.
        val best = HashMap<Long, Pair<Int, Int>>()   // unit → (index, score)
        val d = vs.dims
        for (i in vs.unit.indices) {
            if (docId != null && vs.doc[i] != docId) continue
            if (allowed != null && vs.doc[i] !in allowed) continue
            var s = 0; val off = i * d
            for (j in 0 until d) s += qb[j] * vs.data[off + j]
            val cur = best[vs.unit[i]]
            if (cur == null || s > cur.second) best[vs.unit[i]] = i to s
        }
        val top = best.values.sortedByDescending { it.second }.take(k)
        if (top.isEmpty()) return Answer(emptyList(), emptyList(), "Nothing in the meaning index matches the filters.")
        val w = SearchIndex.db(ctx)
        val rows = HashMap<Long, Array<Any>>()
        w.rawQuery("SELECT id, doc_id, loc, href, label, text FROM units WHERE id IN (${top.joinToString(",") { vs.unit[it.first].toString() }})", null).use { c ->
            while (c.moveToNext()) rows[c.getLong(0)] = arrayOf(c.getLong(1), c.getInt(2), c.getString(3).orEmpty(), c.getString(4).orEmpty(), c.getString(5).orEmpty())
        }
        val out = top.mapNotNull { (i, s) ->
            val r = rows[vs.unit[i]] ?: return@mapNotNull null
            val text = r[4] as String
            val part = text.substring(vs.a[i].coerceIn(0, text.length), vs.b[i].coerceIn(0, text.length))
            val sn = SearchIndex.collapse(part).trim().let { if (it.length > 520) it.take(520) + "…" else it }
            Result(r[0] as Long, r[1] as Int, r[2] as String, r[3] as String, sn, 0, 0, s / (127f * 127f), "")
        }
        return Answer(out, emptyList())
    }

    // ------------------------------------------------------------------ the offline model

    data class Download(val running: Boolean = false, val done: Long = 0, val total: Long = 0, val error: String = "")
    private val _download = MutableStateFlow(Download())
    val download: StateFlow<Download> = _download

    /** Downloads the offline model (about 118 MB) and its word list (17 MB, made compact afterwards). */
    fun downloadModel(ctx: Context) {
        if (_download.value.running) return
        val root = ctx.applicationContext.filesDir
        scope.launch {
            _download.value = Download(true)
            try {
                val d = LocalEmbedder.dir(root).apply { mkdirs() }
                val total = 118_308_185L + 17_082_730L
                var before = 0L
                fun get(url: String, to: File) {
                    val tmp = File(to.parentFile, to.name + ".part")
                    client.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
                        .newCall(Request.Builder().url(url).build()).execute().use { r ->
                            check(r.isSuccessful) { "Download failed (HTTP ${r.code})" }
                            val body = r.body ?: error("Empty download")
                            body.byteStream().use { inp -> tmp.outputStream().use { out ->
                                val buf = ByteArray(1 shl 16); var n: Int; var got = 0L
                                while (inp.read(buf).also { n = it } >= 0) { out.write(buf, 0, n); got += n; _download.value = Download(true, before + got, total) }
                                before += got
                            } }
                        }
                    check(tmp.length() > 1000) { "Download incomplete" }
                    tmp.renameTo(to)
                }
                val tokJson = File(d, "tokenizer.json")
                if (!File(d, "vocab.bin").isFile) { get(LocalEmbedder.TOKENIZER_URL, tokJson); E5Tokenizer.convert(tokJson, File(d, "vocab.bin")); tokJson.delete() } else before += 17_082_730L
                if (!File(d, "model.onnx").isFile) get(LocalEmbedder.MODEL_URL, File(d, "model.onnx"))
                _download.value = Download(false, total, total)
            } catch (e: Throwable) {
                Log.w(TAG, "Model download", e)
                _download.value = Download(false, error = e.message ?: e.toString())
            }
        }
    }

    /** Everything about AI search, for the web page's Settings and search page. */
    fun statusJson(ctx: Context): JSONObject {
        val (gd, gt) = counts(ctx, GEMINI); val (ld, lt) = counts(ctx, LOCAL)
        val p = _progress.value; val dl = _download.value
        return JSONObject()
            .put("provider", AiPreferences(ctx).load().provider.label)
            .put("hasGeminiKey", AiPreferences(ctx).readKey(AiProvider.GEMINI).isNotBlank())
            .put("gemini", JSONObject().put("done", gd).put("total", gt))
            .put("local", JSONObject().put("done", ld).put("total", lt).put("model", LocalEmbedder.installed(ctx.filesDir)))
            .put("progress", JSONObject().put("running", p.running).put("src", p.src).put("done", p.done).put("total", p.total).put("note", p.note).put("error", p.error))
            .put("download", JSONObject().put("running", dl.running).put("done", dl.done).put("total", dl.total).put("error", dl.error))
    }
}
