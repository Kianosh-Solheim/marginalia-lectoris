package com.example.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.text.HtmlCompat
import com.example.data.StorageManager
import com.example.data.local.AppDatabase
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Speech mode: a script (typed, loaded from a file, or converted from handwriting), pages of pen
 * writing, and a speech timer.
 *
 * Each speech is kept as files/speeches/<id>.json (script, ink, timer settings). “Save as Markdown”
 * writes the script to Readmark/Speeches/<Title>.md, where Obsidian or Syncthing can pick it up.
 */
object Speeches {

    /** One pen stroke. Points are x, y, pressure, time (ms from the stroke's start); x and y in page widths. */
    class Stroke(val points: FloatArray, val width: Float = 1f) {
        val count get() = points.size / 4
        fun x(i: Int) = points[i * 4]
        fun y(i: Int) = points[i * 4 + 1]
        fun p(i: Int) = points[i * 4 + 2]
        fun t(i: Int) = points[i * 4 + 3]
        fun toJson(): JSONObject = JSONObject().put("w", width.toDouble()).put("p", JSONArray().apply { points.forEach { put(Math.round(it * 10000) / 10000.0) } })
        companion object {
            fun from(o: JSONObject): Stroke {
                val a = o.optJSONArray("p") ?: JSONArray()
                return Stroke(FloatArray(a.length()) { a.optDouble(it).toFloat() }, o.optDouble("w", 1.0).toFloat())
            }
        }
    }

    data class Speech(
        val id: String,
        val title: String = "Untitled speech",
        val script: String = "",
        val pages: List<List<Stroke>> = listOf(emptyList()),
        val durationSec: Int = 10 * 60,
        val warningsSec: List<Int> = listOf(5 * 60, 60),
        val silent: Boolean = true,
        val vibrate: Boolean = true,
        val fontSize: Int = 22,
        val norwegian: Boolean = true,
        val fingerDraws: Boolean = false,
        val updated: Long = System.currentTimeMillis()
    )

    data class Summary(val id: String, val title: String, val updated: Long, val words: Int)

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "speeches").apply { mkdirs() }
    private fun fileOf(ctx: Context, id: String) = File(dir(ctx), "$id.json")
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("speeches", Context.MODE_PRIVATE)

    fun newSpeech(ctx: Context, title: String = "Untitled speech", script: String = ""): Speech {
        val s = Speech(id = "s" + System.currentTimeMillis().toString(36), title = title, script = script)
        save(ctx, s)
        return s
    }

    fun list(ctx: Context): List<Summary> = dir(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
        runCatching {
            val o = JSONObject(f.readText())
            Summary(o.getString("id"), o.optString("title"), o.optLong("updated"), o.optString("script").split(Regex("\\s+")).count { it.isNotBlank() })
        }.getOrNull()
    }.sortedByDescending { it.updated }

    fun load(ctx: Context, id: String): Speech? = runCatching { fromJson(JSONObject(fileOf(ctx, id).readText())) }.getOrNull()

    /** The speech opened last, or a new one. */
    fun last(ctx: Context): Speech = prefs(ctx).getString("last", null)?.let { load(ctx, it) } ?: list(ctx).firstOrNull()?.let { load(ctx, it.id) } ?: newSpeech(ctx)

    fun remember(ctx: Context, id: String) = prefs(ctx).edit().putString("last", id).apply()

    @Synchronized fun save(ctx: Context, s: Speech) {
        val f = fileOf(ctx, s.id)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(toJson(s.copy(updated = System.currentTimeMillis())).toString())
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }

    /** Moves a speech's file into "_to_delete" inside the speeches folder (it can be restored from there). */
    fun remove(ctx: Context, id: String) {
        val f = fileOf(ctx, id)
        val bin = File(dir(ctx), "_to_delete").apply { mkdirs() }
        if (f.exists() && !f.renameTo(File(bin, f.name))) f.delete()
    }

    private fun toJson(s: Speech): JSONObject = JSONObject()
        .put("id", s.id).put("title", s.title).put("script", s.script)
        .put("pages", JSONArray().apply { s.pages.forEach { pg -> put(JSONArray().apply { pg.forEach { put(it.toJson()) } }) } })
        .put("durationSec", s.durationSec).put("warningsSec", JSONArray(s.warningsSec))
        .put("silent", s.silent).put("vibrate", s.vibrate).put("fontSize", s.fontSize).put("norwegian", s.norwegian)
        .put("fingerDraws", s.fingerDraws).put("updated", s.updated)

    private fun fromJson(o: JSONObject): Speech {
        val pages = o.optJSONArray("pages")?.let { a ->
            (0 until a.length()).map { i -> a.optJSONArray(i)?.let { pg -> (0 until pg.length()).mapNotNull { j -> pg.optJSONObject(j)?.let(Stroke::from) } }.orEmpty() }
        }.orEmpty().ifEmpty { listOf(emptyList()) }
        val w = o.optJSONArray("warningsSec")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: listOf(300, 60)
        return Speech(
            id = o.getString("id"), title = o.optString("title", "Untitled speech"), script = o.optString("script"), pages = pages,
            durationSec = o.optInt("durationSec", 600), warningsSec = w, silent = o.optBoolean("silent", true), vibrate = o.optBoolean("vibrate", true),
            fontSize = o.optInt("fontSize", 22), norwegian = o.optBoolean("norwegian", true), fingerDraws = o.optBoolean("fingerDraws", false),
            updated = o.optLong("updated")
        )
    }

    // ------------------------------------------------------------------ Markdown file

    /** Writes the script to Readmark/Speeches/<Title>.md and returns the file. */
    suspend fun saveMarkdown(ctx: Context, s: Speech): File = withContext(Dispatchers.IO) {
        val settings = AppDatabase.getInstance(ctx).settingsDao().getSettingsSnapshot()
        val root = StorageManager.getRootDirectory(ctx, settings?.customStoragePath.orEmpty(), settings?.storageLocationType ?: "DOCUMENTS")
        val folder = File(root, "Speeches").apply { mkdirs() }
        val name = StorageManager.sanitizeName(s.title).ifBlank { "Speech" }
        val f = File(folder, "$name.md")
        val body = s.script.trimEnd()
        val text = if (body.trimStart().startsWith("# ")) body + "\n" else "# ${s.title}\n\n$body\n"
        f.writeText(text)
        f
    }

    // ------------------------------------------------------------------ loading files

    val loadableTypes = arrayOf(
        "text/markdown", "text/plain", "text/x-markdown", "text/html", "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/octet-stream", "*/*"
    )

    data class Loaded(val title: String, val text: String)

    /** Reads a Markdown, text, HTML, Word (.docx) or PDF file as script text. */
    suspend fun readFile(ctx: Context, uri: Uri): Loaded = withContext(Dispatchers.IO) {
        val name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "speech.txt"
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val tmp = File(ctx.cacheDir, "speech-import-${System.nanoTime()}.$ext")
        try {
            (ctx.contentResolver.openInputStream(uri) ?: error("Could not read “$name”.")).use { i -> tmp.outputStream().use { i.copyTo(it) } }
            val text = when (ext) {
                "md", "markdown", "txt", "text", "" -> tmp.readText(Charsets.UTF_8)
                "html", "htm", "xhtml" -> HtmlCompat.fromHtml(tmp.readText(Charsets.UTF_8), HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
                "docx" -> docxText(tmp)
                "pdf" -> pdfText(ctx, tmp)
                else -> throw IllegalArgumentException("“$name” can't be loaded. Use a Markdown, text, Word (.docx), HTML or PDF file.")
            }
            Loaded(name.substringBeforeLast('.').ifBlank { "Speech" }, text.replace("\r\n", "\n").trim())
        } finally { tmp.delete() }
    }

    /** Word text as Markdown: headings become #, list paragraphs become -, bold runs **…**. */
    private fun docxText(f: File): String = ZipFile(f).use { zip ->
        val entry = zip.getEntry("word/document.xml") ?: error("This Word file has no text.")
        val xml = zip.getInputStream(entry).bufferedReader().readText()
        val out = StringBuilder()
        Regex("<w:p[ >].*?</w:p>|<w:p/>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { pm ->
            val p = pm.value
            val style = Regex("<w:pStyle w:val=\"([^\"]+)\"").find(p)?.groupValues?.get(1).orEmpty()
            val level = Regex("^(?:Heading|Overskrift|heading)\\s?(\\d)").find(style)?.groupValues?.get(1)?.toIntOrNull()
                ?: if (style.equals("Title", true) || style.equals("Tittel", true)) 1 else null
            val isList = p.contains("<w:numPr>")
            val line = StringBuilder()
            Regex("<w:r[ >].*?</w:r>", RegexOption.DOT_MATCHES_ALL).findAll(p).forEach { rm ->
                val r = rm.value
                val t = Regex("<w:t(?: [^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL).findAll(r).joinToString("") { it.groupValues[1] } +
                    (if (r.contains("<w:tab/>")) "\t" else "") + (if (r.contains("<w:br/>")) "\n" else "")
                val bold = level == null && (r.contains("<w:b/>") || r.contains("<w:b w:val=\"1\"/>") || r.contains("<w:b w:val=\"true\"/>"))
                line.append(if (bold && t.isNotBlank()) "**$t**" else t)
            }
            val text = line.toString().replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            when {
                text.isBlank() -> out.append("\n")
                level != null -> out.append("\n" + "#".repeat(level.coerceIn(1, 6)) + " " + text.trim() + "\n\n")
                isList -> out.append("- ").append(text.trim()).append("\n")
                else -> out.append(text.trim()).append("\n\n")
            }
        }
        out.toString().replace(Regex("\n{3,}"), "\n\n")
    }

    private fun pdfText(ctx: Context, f: File): String {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx.applicationContext)
        return com.tom_roush.pdfbox.pdmodel.PDDocument.load(f).use { doc ->
            com.tom_roush.pdfbox.text.PDFTextStripper().apply { sortByPosition = true; paragraphStart = "\n" }.getText(doc)
        }.replace(Regex("[ \\t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n")
    }

    // ------------------------------------------------------------------ handwriting to text

    private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    private fun modelFor(norwegian: Boolean): DigitalInkRecognitionModel? {
        val tags = if (norwegian) listOf("nb-NO", "nb", "no", "nn-NO") else listOf("en-GB", "en-US", "en")
        for (tag in tags) {
            val id = runCatching { DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag) }.getOrNull() ?: continue
            return DigitalInkRecognitionModel.builder(id).build()
        }
        return null
    }

    // A recogniser per language is kept ready, so converting does not wait for the model to load.
    private val recognizers = HashMap<Boolean, com.google.mlkit.vision.digitalink.DigitalInkRecognizer>()
    private val downloaded = HashSet<Boolean>()
    private val recogniseLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun recognizer(norwegian: Boolean, onStatus: (String) -> Unit): com.google.mlkit.vision.digitalink.DigitalInkRecognizer {
        synchronized(recognizers) { recognizers[norwegian] }?.let { return it }
        val model = modelFor(norwegian) ?: error(if (norwegian) "Norwegian handwriting isn't supported by the recogniser." else "The English handwriting model is unavailable.")
        if (norwegian !in downloaded) {
            val manager = RemoteModelManager.getInstance()
            if (!manager.isModelDownloaded(model).await()) {
                onStatus("Downloading the handwriting model (once, needs internet)…")
                manager.download(model, DownloadConditions.Builder().build()).await()
            }
            downloaded += norwegian
        }
        val r = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        synchronized(recognizers) { recognizers[norwegian] = r }
        return r
    }

    /** True when a recogniser for this language is loaded (so converting in the background costs no download). */
    fun isReady(norwegian: Boolean): Boolean = synchronized(recognizers) { norwegian in recognizers }

    /** Loads the handwriting model in the background (when Speech mode opens), if it is already downloaded. */
    suspend fun warmUp(norwegian: Boolean) {
        runCatching {
            val model = modelFor(norwegian) ?: return
            if (!RemoteModelManager.getInstance().isModelDownloaded(model).await()) return
            val r = recognizer(norwegian) {}
            // A tiny first recognition makes the recogniser load its model now rather than on the first real line.
            val ink = Ink.builder().addStroke(Ink.Stroke.builder().addPoint(Ink.Point.create(0f, 0f, 0L)).addPoint(Ink.Point.create(10f, 10f, 30L)).build()).build()
            r.recognize(ink).await()
        }
    }

    /** Text already recognised for each line of a page: line → (which strokes, text). */
    class LineCache { internal val lines = HashMap<Int, Pair<List<Int>, String>>() ; internal var norwegian: Boolean? = null }

    /**
     * Converts a page of handwriting to text, line by line ([lineHeight] in page widths, as the ruled
     * lines on the page). Lines whose strokes have not changed since the last time come from [cache],
     * so converting while writing makes the final conversion almost instant. The model downloads once
     * (needs internet), then works offline.
     */
    suspend fun recognise(strokes: List<Stroke>, lineHeight: Float, pageWidthPx: Float, norwegian: Boolean, cache: LineCache = LineCache(), onStatus: (String) -> Unit = {}): String =
        recogniseLock.withLock { recogniseLocked(strokes, lineHeight, pageWidthPx, norwegian, cache, onStatus) }

    private suspend fun recogniseLocked(strokes: List<Stroke>, lineHeight: Float, pageWidthPx: Float, norwegian: Boolean, cache: LineCache, onStatus: (String) -> Unit): String {
        if (strokes.isEmpty()) return ""
        if (cache.norwegian != norwegian) { cache.lines.clear(); cache.norwegian = norwegian }
        val recognizer = recognizer(norwegian, onStatus)
        fun midY(s: Stroke): Float { var a = 0f; for (i in 0 until s.count) a += s.y(i); return a / s.count.coerceAtLeast(1) }
        val rows = strokes.withIndex().filter { it.value.count > 0 }.groupBy { (midY(it.value) / lineHeight).toInt() }.toSortedMap()
        cache.lines.keys.retainAll(rows.keys)
        val out = StringBuilder()
        var pre = ""
        var lastRow = -10
        for ((row, list) in rows) {
            val ordered = list.sortedBy { it.index }.map { it.value }
            val sig = ordered.map { System.identityHashCode(it) }
            val text = cache.lines[row]?.takeIf { it.first == sig }?.second ?: run {
                val ink = Ink.builder()
                var clock = 0L
                ordered.forEach { s ->
                    val sb = Ink.Stroke.builder()
                    for (i in 0 until s.count) sb.addPoint(Ink.Point.create(s.x(i) * pageWidthPx, s.y(i) * pageWidthPx, clock + s.t(i).toLong()))
                    clock += (s.t(s.count - 1).toLong() + 50L)
                    ink.addStroke(sb.build())
                }
                val ctx = RecognitionContext.builder().setPreContext(pre.takeLast(20))
                    .setWritingArea(WritingArea(pageWidthPx, lineHeight * pageWidthPx * 1.6f)).build()
                val t = recognizer.recognize(ink.build(), ctx).await().candidates.firstOrNull()?.text.orEmpty().trim()
                cache.lines[row] = sig to t
                t
            }
            if (text.isBlank()) continue
            if (out.isNotEmpty()) out.append(if (row - lastRow > 1) "\n\n" else "\n")
            out.append(text)
            pre = text
            lastRow = row
        }
        return out.toString()
    }
}
