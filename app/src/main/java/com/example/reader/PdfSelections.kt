package com.example.reader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Where each PDF highlight sits in the text as pdf.js sees it, so a note's link can carry
 * `&selection=b,bo,e,eo` (the form the PDF++ plugin for Obsidian opens and marks).
 *
 * The positions are worked out once per highlight, in a hidden WebView running the same pdf.js
 * the web reader uses, and kept in files/pdf_selections.json. A highlight whose quote or page
 * changes is worked out again. When new positions arrive, [version] changes and the notes are
 * written again.
 */
object PdfSelections {
    private const val TAG = "PdfSelections"
    private const val TIMEOUT_MS = 120_000L

    val version = MutableStateFlow(0)

    private lateinit var app: Context
    private val store = HashMap<Long, Pair<String, String>>() // id -> (key, selection; "" = not found)
    private val pending = LinkedHashMap<Long, JSONObject>()
    private var running = false
    private var loaded = false
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        synchronized(this) {
            if (loaded) return
            app = context.applicationContext
            runCatching {
                val f = file()
                if (f.isFile) {
                    val o = JSONObject(f.readText())
                    o.keys().forEach { k ->
                        val e = o.getJSONObject(k)
                        store[k.toLong()] = e.optString("k") to e.optString("s")
                    }
                }
            }.onFailure { Log.w(TAG, "Could not read the store", it) }
            loaded = true
        }
    }

    private fun file() = File(app.filesDir, "pdf_selections.json")

    private fun startPage(h: HighlightEntity): Int {
        val (range, _) = PdfToEbookParser.parseStoredPageLabels(h.pageNumber, h.printedPageLabel)
        return range.substringBefore("–").substringBefore("-").trim().toIntOrNull() ?: h.pageNumber
    }

    private fun key(h: HighlightEntity, d: DocumentEntity): String =
        MessageDigest.getInstance("SHA-1").digest("${d.filePath}|${startPage(h)}|${h.highlightedText}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun isPdf(d: DocumentEntity) = d.fileType.equals("PDF", true) || d.filePath.endsWith(".pdf", true)

    /** The selection for this highlight, or null when it is not known (yet). */
    fun get(h: HighlightEntity, d: DocumentEntity): String? {
        if (!loaded || !isPdf(d)) return null
        val e = synchronized(this) { store[h.id] } ?: return null
        return e.second.takeIf { e.first == key(h, d) && it.isNotBlank() }
    }

    /** Asks for the positions of any of these highlights not worked out yet. */
    fun request(context: Context, d: DocumentEntity, highlights: List<HighlightEntity>) {
        init(context)
        if (!isPdf(d)) return
        val f = File(d.filePath)
        if (!f.isFile) return
        var added = false
        synchronized(this) {
            for (h in highlights) {
                if (h.documentId != d.id || h.highlightedText.isBlank()) continue
                val k = key(h, d)
                if (store[h.id]?.first == k || pending.containsKey(h.id)) continue
                pending[h.id] = JSONObject().put("id", h.id).put("path", f.absolutePath).put("length", f.length())
                    .put("page", startPage(h)).put("quote", h.highlightedText).put("key", k)
                added = true
            }
        }
        if (added) main.post { runNext() }
    }

    // ------------------------------------------------------------------------------ the worker

    private var web: WebView? = null
    private var batch: Map<Long, JSONObject> = emptyMap()
    private val timeout = Runnable { Log.w(TAG, "Timed out"); finish() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun runNext() {
        if (running) return
        val jobs = synchronized(this) {
            val take = pending.entries.take(200).associate { it.key to it.value }
            take.keys.forEach { pending.remove(it) }
            take
        }
        if (jobs.isEmpty()) return
        running = true
        batch = jobs
        val allowed = jobs.values.map { it.getString("path") }.toSet()
        val json = JSONArray().apply { jobs.values.forEach { put(it) } }.toString()
        runCatching {
            val w = WebView(app)
            web = w
            w.settings.javaScriptEnabled = true
            w.settings.allowFileAccess = false
            w.addJavascriptInterface(Bridge(allowed), "Bridge")
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("locateAll(${JSONObject.quote(json)})", null)
                }
            }
            w.loadUrl("file:///android_asset/pdfsel/index.html")
            main.postDelayed(timeout, TIMEOUT_MS)
        }.onFailure { Log.w(TAG, "Could not start", it); finish() }
    }

    private fun record(id: Long, sel: String) {
        val job = batch[id] ?: return
        synchronized(this) { store[id] = job.getString("key") to sel }
    }

    private fun finish() {
        main.removeCallbacks(timeout)
        runCatching { web?.destroy() }
        web = null
        // Anything not answered counts as not found, so it is not tried again and again.
        synchronized(this) { batch.forEach { (id, j) -> if (store[id]?.first != j.getString("key")) store[id] = j.getString("key") to "" } }
        batch = emptyMap()
        running = false
        save()
        version.value = version.value + 1
        runNext()
    }

    private fun save() = runCatching {
        val o = JSONObject()
        synchronized(this) { store.forEach { (id, e) -> o.put(id.toString(), JSONObject().put("k", e.first).put("s", e.second)) } }
        val f = file()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(f)) { f.writeText(o.toString()); tmp.delete() }
    }.onFailure { Log.w(TAG, "Could not save", it) }

    private class Bridge(private val allowed: Set<String>) {
        @JavascriptInterface
        fun read(path: String, begin: Long, length: Int): String {
            if (path !in allowed) return ""
            return runCatching {
                RandomAccessFile(path, "r").use { f ->
                    val n = minOf(length.toLong(), (f.length() - begin).coerceAtLeast(0)).toInt()
                    val buf = ByteArray(n)
                    f.seek(begin); f.readFully(buf)
                    Base64.encodeToString(buf, Base64.NO_WRAP)
                }
            }.getOrDefault("")
        }

        @JavascriptInterface
        fun done(id: String, selection: String) {
            val i = id.toLongOrNull() ?: return
            main.post { record(i, selection) }
        }

        @JavascriptInterface
        fun log(message: String) { Log.d(TAG, message) }

        @JavascriptInterface
        fun finished() { main.post { finish() } }
    }
}
