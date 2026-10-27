package com.example.server

import android.content.Context
import com.example.data.StorageManager
import com.example.data.local.AppDatabase
import com.example.data.local.SettingsEntity
import com.example.reader.MarkdownExporter
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Presentation mode. Presentations live in <library>/Presentations/<title>/ with the deck file,
 * "<title> – my notes.md" (your typed notes, one section per slide) and "<title> – ink.json"
 * (pen strokes per slide). The slides are drawn in the browser (the web view and the tablet's
 * presenter screen); this side stores files, reads speaker notes and transitions out of the
 * deck, and relays which slide is showing from the presenter to every audience screen.
 */
object Presentations {
    val supported = setOf("pptx", "odp", "pdf")
    val rejected = mapOf(
        "ppt" to "Old PowerPoint files (.ppt) can't be shown. Open it in PowerPoint and save it as .pptx.",
        "key" to "Keynote files can't be shown. Export it from Keynote as PowerPoint (.pptx) or PDF.",
        "pps" to "Save the slide show as .pptx first.",
        "ppsx" to "Save the slide show as .pptx first."
    )

    /** Changes when presentations are added, renamed or deleted. */
    val changes = MutableStateFlow(0)

    data class Deck(val id: String, val dir: File, val file: File, val title: String, val type: String, val added: Long) {
        val notesFile: File get() = File(dir, MarkdownExporter.sanitizeFilename("$title – my notes") + ".md")
        val inkFile: File get() = File(dir, MarkdownExporter.sanitizeFilename("$title – ink") + ".json")
        val metaFile: File get() = File(dir, ".deck.json")
    }

    suspend fun root(context: Context): File {
        val s = AppDatabase.getInstance(context).settingsDao().getSettingsSnapshot() ?: SettingsEntity()
        return File(StorageManager.getRootDirectory(context, s.customStoragePath, s.storageLocationType), "Presentations").apply { mkdirs() }
    }

    private fun idOf(dir: File) = dir.name

    suspend fun list(context: Context): List<Deck> =
        (root(context).listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { deckIn(it) }.sortedByDescending { it.added }

    fun deckIn(dir: File): Deck? {
        val f = (dir.listFiles() ?: emptyArray()).firstOrNull { it.isFile && it.extension.lowercase(Locale.ROOT) in supported } ?: return null
        val meta = runCatching { JSONObject(File(dir, ".deck.json").readText()) }.getOrNull()
        return Deck(idOf(dir), dir, f, meta?.optString("title").orEmpty().ifBlank { dir.name }, f.extension.lowercase(Locale.ROOT),
            meta?.optLong("added") ?: f.lastModified())
    }

    suspend fun get(context: Context, id: String): Deck? {
        require(!id.contains('/') && !id.contains("..")) { "Bad id." }
        val d = File(root(context), id)
        return if (d.isDirectory) deckIn(d) else null
    }

    /** Stores a deck from [input] under a folder named after the file. */
    suspend fun add(context: Context, name: String, input: InputStream): Deck {
        val clean = File(name).name
        val ext = clean.substringAfterLast('.', "").lowercase(Locale.ROOT)
        rejected[ext]?.let { throw IllegalArgumentException(it) }
        require(ext in supported) { "“$clean” is not a presentation (.pptx, .odp or .pdf)." }
        val title = clean.substringBeforeLast('.').trim().ifBlank { "Presentation" }
        val base = MarkdownExporter.sanitizeFilename(title).ifBlank { "Presentation" }
        var dir = File(root(context), base)
        var n = 2
        while (dir.exists()) { dir = File(root(context), "$base ($n)"); n++ }
        dir.mkdirs()
        val f = File(dir, MarkdownExporter.sanitizeFilename(title) + "." + ext)
        try {
            f.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
            if (ext != "pdf") runCatching { ZipFile(f).close() }.getOrElse { throw IllegalArgumentException("“$clean” could not be opened. Is it a complete file?") }
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
        File(dir, ".deck.json").writeText(JSONObject().put("title", title).put("added", System.currentTimeMillis()).toString())
        changes.value++
        return deckIn(dir)!!
    }

    fun rename(d: Deck, title: String) {
        val o = runCatching { JSONObject(d.metaFile.readText()) }.getOrDefault(JSONObject())
        o.put("title", title.trim().ifBlank { d.title })
        d.metaFile.writeText(o.toString())
        changes.value++
    }

    fun delete(d: Deck) { d.dir.deleteRecursively(); changes.value++ }

    fun json(d: Deck): JSONObject = JSONObject().put("id", d.id).put("title", d.title).put("type", d.type)
        .put("size", d.file.length()).put("added", d.added).put("fileName", d.file.name)
        .put("slides", runCatching { info(d).optJSONArray("slides")?.length() ?: 0 }.getOrDefault(0))

    // ------------------------------------------------------------------------ speaker notes and transitions

    /** {slides:[{notes, transition:{type, dir, ms}}]} — for PDF only the count is unknown here (the browser counts). */
    fun info(d: Deck): JSONObject {
        val cache = File(d.dir, ".slides.json")
        if (cache.isFile && cache.lastModified() >= d.file.lastModified()) runCatching { return JSONObject(cache.readText()) }
        val o = when (d.type) { "pptx" -> pptxInfo(d.file); "odp" -> odpInfo(d.file); else -> JSONObject().put("slides", JSONArray()) }
        runCatching { cache.writeText(o.toString()) }
        return o
    }

    private fun parser(input: InputStream): XmlPullParser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser().apply { setInput(input, "UTF-8") }

    private fun rels(zip: ZipFile, path: String): Map<String, String> {
        val dir = path.substringBeforeLast('/')
        val relPath = "$dir/_rels/${path.substringAfterLast('/')}.rels"
        val e = zip.getEntry(relPath) ?: return emptyMap()
        val out = HashMap<String, String>()
        zip.getInputStream(e).use { s ->
            val p = parser(s)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val target = p.getAttributeValue(null, "Target").orEmpty()
                    val type = p.getAttributeValue(null, "Type").orEmpty().substringAfterLast('/')
                    val full = if (target.startsWith("/")) target.removePrefix("/") else normalize("$dir/$target")
                    out["id:" + p.getAttributeValue(null, "Id")] = full
                    out["type:$type"] = full
                }
            }
        }
        return out
    }

    private fun normalize(path: String): String {
        val parts = ArrayList<String>()
        path.split('/').forEach { seg -> when (seg) { ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1); ".", "" -> {}; else -> parts.add(seg) } }
        return parts.joinToString("/")
    }

    private fun pptxInfo(file: File): JSONObject {
        val slides = JSONArray()
        ZipFile(file).use { zip ->
            val presRels = rels(zip, "ppt/presentation.xml")
            val order = ArrayList<String>()
            zip.getEntry("ppt/presentation.xml")?.let { e ->
                zip.getInputStream(e).use { s ->
                    val p = parser(s)
                    while (p.next() != XmlPullParser.END_DOCUMENT) {
                        if (p.eventType == XmlPullParser.START_TAG && p.name == "sldId") {
                            val rid = p.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                            presRels["id:$rid"]?.let { order += it }
                        }
                    }
                }
            }
            order.forEach { slidePath ->
                val o = JSONObject()
                var hidden = false
                zip.getEntry(slidePath)?.let { e -> zip.getInputStream(e).use { s ->
                    val p = parser(s)
                    var t: JSONObject? = null
                    var tDepth = -1
                    while (p.next() != XmlPullParser.END_DOCUMENT) {
                        if (p.eventType == XmlPullParser.START_TAG) {
                            if (p.name == "sld" && p.getAttributeValue(null, "show") == "0") hidden = true
                            if (p.name == "transition" && t == null) {
                                // The first description is the newest one (mc:Choice comes before mc:Fallback).
                                val spd = p.getAttributeValue(null, "spd")
                                val dur = p.getAttributeValue(P14, "dur") ?: p.getAttributeValue(null, "dur")
                                t = JSONObject().put("ms", dur?.toIntOrNull() ?: when (spd) { "slow" -> 1000; "fast" -> 300; else -> 600 })
                                p.getAttributeValue(null, "advTm")?.toIntOrNull()?.let { t!!.put("autoMs", it) }
                                tDepth = p.depth
                            } else if (tDepth > 0 && p.depth == tDepth + 1 && t?.has("type") == false) {
                                t!!.put("type", p.name).put("dir", p.getAttributeValue(null, "dir") ?: p.getAttributeValue(null, "orient") ?: "")
                            }
                        } else if (p.eventType == XmlPullParser.END_TAG && p.name == "transition" && p.depth == tDepth) tDepth = -1
                    }
                    if (t != null) o.put("transition", t)
                } }
                val notesPath = rels(zip, slidePath)["type:notesSlide"]
                o.put("notes", notesPath?.let { np -> zip.getEntry(np)?.let { e -> zip.getInputStream(e).use { notesText(it) } } }.orEmpty())
                if (hidden) o.put("hidden", true)
                slides.put(o)
            }
        }
        return JSONObject().put("slides", slides)
    }

    /** The text of a notes slide: only the body placeholder (not the slide image or the page number). */
    private fun notesText(s: InputStream): String {
        val p = parser(s)
        val out = StringBuilder()
        var inBody = false
        var depthSp = -1
        var para = StringBuilder()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "sp" -> { depthSp = p.depth; inBody = false }
                    "ph" -> if (depthSp > 0) { val type = p.getAttributeValue(null, "type"); inBody = type == "body" || (type == null && p.getAttributeValue(null, "idx") == "1") }
                    "t" -> if (inBody) para.append(p.nextText())
                    "br" -> if (inBody) para.append('\n')
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "p" -> if (inBody) { out.append(para.toString().trimEnd()).append('\n'); para = StringBuilder() }
                    "sp" -> if (p.depth == depthSp) { inBody = false; depthSp = -1 }
                }
            }
        }
        return out.toString().trim().replace(Regex("\n{3,}"), "\n\n")
    }

    private fun odpInfo(file: File): JSONObject {
        val slides = JSONArray()
        ZipFile(file).use { zip ->
            val styles = HashMap<String, JSONObject>() // drawing-page style name → transition
            fun readStyles(entry: String) = zip.getEntry(entry)?.let { e -> zip.getInputStream(e).use { s ->
                val p = parser(s)
                var style: String? = null
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "style" && p.getAttributeValue(STYLE, "family") == "drawing-page") style = p.getAttributeValue(STYLE, "name")
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "drawing-page-properties" && style != null) {
                        val type = p.getAttributeValue(SMIL, "type")
                        val sub = p.getAttributeValue(SMIL, "subtype").orEmpty()
                        val dir = p.getAttributeValue(SMIL, "direction").orEmpty()
                        val speed = p.getAttributeValue(PRES, "transition-speed")
                        val dur = p.getAttributeValue(PRES, "duration") ?: p.getAttributeValue(SMIL, "dur")
                        if (type != null) styles[style!!] = JSONObject().put("type", odfType(type, sub)).put("dir", odfDir(sub, dir))
                            .put("ms", odfMs(dur) ?: when (speed) { "slow" -> 1000; "fast" -> 300; else -> 600 })
                    }
                }
            } }
            readStyles("styles.xml"); readStyles("content.xml")
            zip.getEntry("content.xml")?.let { e -> zip.getInputStream(e).use { s ->
                val p = parser(s)
                var cur: JSONObject? = null
                var inNotes = false
                var notes = StringBuilder()
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    when (p.eventType) {
                        XmlPullParser.START_TAG -> when {
                            p.name == "page" && p.namespace == DRAW -> {
                                cur = JSONObject(); notes = StringBuilder()
                                p.getAttributeValue(DRAW, "style-name")?.let { st -> styles[st]?.let { cur!!.put("transition", it) } }
                            }
                            p.name == "notes" && p.namespace == PRES -> inNotes = true
                        }
                        XmlPullParser.TEXT -> if (inNotes) notes.append(p.text)
                        XmlPullParser.END_TAG -> when {
                            p.name == "p" && inNotes -> notes.append('\n')
                            p.name == "notes" && p.namespace == PRES -> inNotes = false
                            p.name == "page" && p.namespace == DRAW -> { cur?.put("notes", notes.toString().trim()); cur?.let { slides.put(it) }; cur = null }
                        }
                    }
                }
            } }
        }
        return JSONObject().put("slides", slides)
    }

    private const val P14 = "http://schemas.microsoft.com/office/powerpoint/2010/main"
    private const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    private const val SMIL = "urn:oasis:names:tc:opendocument:xmlns:smil-compatible:1.0"
    private const val PRES = "urn:oasis:names:tc:opendocument:xmlns:presentation:1.0"
    private const val DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"

    private fun odfType(type: String, sub: String): String = when (type) {
        "fade" -> if (sub == "fadeOverColor") "fadeBlack" else "fade"
        "slideWipe" -> "push"; "pushWipe" -> "push"; "barWipe" -> "wipe"; "irisWipe", "ellipseWipe" -> "zoom"
        "barnDoorWipe" -> "split"; "zoom" -> "zoom"; else -> "fade"
    }
    private fun odfDir(sub: String, dir: String): String = when {
        sub.contains("Left", true) || sub.contains("fromRight", true) -> "l"
        sub.contains("Right", true) || sub.contains("fromLeft", true) -> "r"
        sub.contains("Top", true) -> "d"; sub.contains("Bottom", true) -> "u"
        else -> if (dir == "reverse") "r" else ""
    }
    private fun odfMs(dur: String?): Int? = dur?.let { d -> Regex("([0-9.]+)s").find(d)?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * 1000).toInt() } }

    // ------------------------------------------------------------------------ your notes and ink

    /** Your typed notes: "## Slide 3" sections in the notes file. */
    fun myNotes(d: Deck): JSONObject {
        val o = JSONObject()
        if (!d.notesFile.isFile) return o
        var slide = -1
        val sb = StringBuilder()
        fun flush() { if (slide > 0) o.put(slide.toString(), sb.toString().trim()); sb.setLength(0) }
        d.notesFile.readLines().forEach { line ->
            val m = Regex("^##\\s+Slide\\s+(\\d+)").find(line)
            if (m != null) { flush(); slide = m.groupValues[1].toInt() } else if (slide > 0) sb.appendLine(line)
        }
        flush()
        return o
    }

    fun setMyNote(d: Deck, slide: Int, text: String) {
        val all = myNotes(d)
        if (text.isBlank()) all.remove(slide.toString()) else all.put(slide.toString(), text.trim())
        val keys = all.keys().asSequence().mapNotNull { it.toIntOrNull() }.sorted().toList()
        val sb = StringBuilder("---\ntitle: \"${d.title.replace("\"", "'")} – my notes\"\npresentation: \"${d.file.name}\"\ntags:\n  - Presentation\n---\n")
        keys.forEach { k -> sb.append("\n## Slide ").append(k).append("\n\n").append(all.optString(k.toString())).append('\n') }
        d.notesFile.writeText(sb.toString())
    }

    fun ink(d: Deck): JSONObject = runCatching { JSONObject(d.inkFile.readText()) }.getOrDefault(JSONObject())

    fun setInk(d: Deck, slide: Int, strokes: JSONArray) {
        val all = ink(d)
        if (strokes.length() == 0) all.remove(slide.toString()) else all.put(slide.toString(), strokes)
        d.inkFile.writeText(all.toString())
    }

    // ------------------------------------------------------------------------ live: presenter → audience

    data class Live(val id: String = "", val slide: Int = 0, val black: Boolean = false, val version: Long = 0, val ink: JSONArray? = null, val laser: JSONObject? = null)

    @Volatile var live = Live()
        private set
    private val lock = Object()
    private val audiences = HashMap<String, Long>() // remote → last seen
    @Volatile var presenterSeen = 0L

    fun setLive(id: String, slide: Int, black: Boolean, ink: JSONArray?, laser: JSONObject? = null) {
        synchronized(lock) {
            live = Live(id, slide, black, live.version + 1, ink, laser)
            (lock as Object).notifyAll()
        }
    }

    /** Waits up to [waitMs] for a change after [since]; returns the state. */
    fun waitLive(since: Long, waitMs: Long): Live {
        val until = System.currentTimeMillis() + waitMs
        synchronized(lock) {
            while (live.version <= since) {
                val left = until - System.currentTimeMillis()
                if (left <= 0) break
                (lock as Object).wait(left)
            }
            return live
        }
    }

    fun audienceSeen(remote: String) { synchronized(audiences) { audiences[remote] = System.currentTimeMillis() } }
    fun audienceCount(): Int = synchronized(audiences) { val now = System.currentTimeMillis(); audiences.entries.removeAll { now - it.value > 40_000 }; audiences.size }

    fun liveJson(l: Live = live): JSONObject = JSONObject().put("id", l.id).put("slide", l.slide).put("black", l.black)
        .put("version", l.version).put("audiences", audienceCount()).put("ink", l.ink ?: JSONArray()).put("laser", l.laser ?: JSONObject.NULL)
}
