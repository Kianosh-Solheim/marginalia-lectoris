package com.example.reader

import android.content.Context
import com.example.data.StorageManager
import com.example.data.local.AppDatabase
import com.example.data.local.SettingsEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A course session's own page: the lecture slides, a note for each slide, pen marks, recordings and their
 * transcripts. The files are what counts, so they reach the Obsidian vault and other devices by Syncthing:
 *
 *   <library>/Courses/<course code>/<date> <kind> – <topic>/
 *     .session.json                           which session this folder belongs to (found again after renaming)
 *     "<code> <date> – <topic>.md"            the session note (MOC): details, readings, links to every slide note and recording
 *     "<code> <date> – Slide 03.md"           one note per slide (front matter + your text; edit it in Obsidian too)
 *     .ink.json                               pen strokes per slide
 *     Recordings/                             audio, timeline (with slide changes) and transcript, as for books
 *
 * The slides themselves are not copied: the session refers to a presentation or a library PDF (in courses.json).
 */
object CourseSessions {
    private val lock = Any()

    /** The slide each open session page shows ("<course>/<session>" → 1-based), so a recording started on the tablet begins there. */
    val lastSlide = java.util.concurrent.ConcurrentHashMap<String, Int>()

    class Ref(val course: JSONObject, val session: JSONObject) {
        val courseId: String get() = course.optString("id")
        val sessionId: String get() = session.optString("id")
        val code: String get() = course.optString("code").ifBlank { course.optString("title") }.ifBlank { "Course" }.trim()
        val day: String get() = session.optString("date").ifBlank { session.optString("week").takeIf { it.isNotBlank() }?.let { "week $it" } ?: "" }
        val topic: String get() = session.optString("topic").ifBlank { session.optString("kind").ifBlank { "Lecture" } }.trim()
        /** "SAMPOL390 2026-11-25": the start of every file name, so names stay unique in the vault. */
        val base: String get() = MarkdownExporter.sanitizeFilename(listOf(code, day).filter { it.isNotBlank() }.joinToString(" ")).take(80)
    }

    fun find(context: Context, courseId: String, sessionId: String): Ref? {
        var out: Ref? = null
        Courses.forEachCourse(Courses.data(context)) { c ->
            if (out != null || c.optString("id") != courseId) return@forEachCourse
            val ss = c.optJSONArray("sessions") ?: return@forEachCourse
            for (i in 0 until ss.length()) ss.optJSONObject(i)?.takeIf { it.optString("id") == sessionId }?.let { out = Ref(c, it) }
        }
        return out
    }

    private suspend fun coursesRoot(context: Context): File {
        val s = AppDatabase.getInstance(context).settingsDao().getSettingsSnapshot() ?: SettingsEntity()
        return File(StorageManager.getRootDirectory(context, s.customStoragePath, s.storageLocationType), "Courses").apply { mkdirs() }
    }

    /** The session's folder: found by its marker file (so renaming the course or topic keeps it), else made. */
    suspend fun folder(context: Context, ref: Ref): File {
        val root = coursesRoot(context)
        synchronized(lock) {
            existingIn(root, ref)?.let { return it }
            val courseDir = File(root, MarkdownExporter.sanitizeFilename(ref.code).take(60))
            val name = MarkdownExporter.sanitizeFilename(listOf(ref.day, ref.session.optString("kind").ifBlank { "Lecture" }).filter { it.isNotBlank() }.joinToString(" ") + " – " + ref.topic).take(100)
            var dir = File(courseDir, name)
            var n = 2
            while (dir.exists()) { dir = File(courseDir, "$name ($n)"); n++ }
            dir.mkdirs()
            File(dir, ".session.json").writeText(JSONObject().put("course", ref.courseId).put("session", ref.sessionId).put("made", System.currentTimeMillis()).toString())
            return dir
        }
    }

    /** The session's folder if it has one yet (nothing is made just by looking). */
    suspend fun existing(context: Context, ref: Ref): File? = existingIn(coursesRoot(context), ref)

    private fun existingIn(root: File, ref: Ref): File? {
        (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.forEach { course ->
            (course.listFiles() ?: emptyArray()).filter { it.isDirectory }.forEach { dir ->
                val m = File(dir, ".session.json")
                if (m.isFile && runCatching { JSONObject(m.readText()).optString("session") }.getOrNull() == ref.sessionId) return dir
            }
        }
        return null
    }

    fun recordingsDir(dir: File) = File(dir, "Recordings").apply { mkdirs() }

    // ------------------------------------------------------------------------ notes per slide

    /** Front matter keys and the body of a Markdown file. */
    private fun split(text: String): Pair<Map<String, String>, String> {
        if (!text.startsWith("---")) return emptyMap<String, String>() to text
        val end = text.indexOf("\n---", 3)
        if (end < 0) return emptyMap<String, String>() to text
        val fm = text.substring(3, end).lines().mapNotNull { l ->
            val k = l.substringBefore(':', "").trim(); if (k.isBlank() || l.startsWith(" ")) null else k to l.substringAfter(':').trim().trim('"')
        }.toMap()
        return fm to text.substring(end + 4).trimStart('\n').removePrefix("\n")
    }

    private fun markdownFiles(dir: File) = (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.extension.equals("md", true) }

    /** Slide number → (file, note text). A slide note is any note in the folder whose front matter has "slide:". */
    private fun slideFiles(dir: File): Map<Int, Pair<File, String>> {
        val out = HashMap<Int, Pair<File, String>>()
        markdownFiles(dir).forEach { f ->
            val (fm, body) = runCatching { split(f.readText()) }.getOrNull() ?: return@forEach
            val n = fm["slide"]?.toIntOrNull() ?: return@forEach
            out[n] = f to body.trim()
        }
        return out
    }

    fun notes(dir: File): JSONObject = JSONObject().apply { slideFiles(dir).forEach { (n, p) -> put(n.toString(), p.second) } }

    /** Saves the note on [slide] (1-based) as its own file; an empty note removes the file. */
    fun setNote(ref: Ref, dir: File, slide: Int, text: String) {
        require(slide in 1..2000) { "Bad slide number." }
        synchronized(lock) {
            val existing = slideFiles(dir)[slide]?.first
            if (text.isBlank()) { existing?.delete(); return }
            val f = existing ?: File(dir, MarkdownExporter.sanitizeFilename("${ref.base} – Slide ${slide.toString().padStart(2, '0')}") + ".md")
            val sb = StringBuilder("---\n")
            sb.append("title: \"").append(esc("${ref.topic} – slide $slide")).append("\"\n")
            sb.append("slide: ").append(slide).append('\n')
            sb.append("marginalia-session: ").append(ref.sessionId).append('\n')
            sb.append("course: \"").append(esc(ref.code)).append("\"\n")
            if (ref.session.optString("date").isNotBlank()) sb.append("date: ").append(ref.session.optString("date")).append('\n')
            sb.append("From Session: \"[[").append(mocName(ref)).append("]]\"\n")
            sb.append("tags:\n  - Lecture-note\n  - ").append(courseTag(ref.course)).append('\n')
            sb.append("---\n\n").append(text.trim()).append('\n')
            f.writeText(sb.toString())
        }
    }

    private fun esc(s: String) = s.replace("\"", "'")

    // ------------------------------------------------------------------------ pen marks

    private fun inkFile(dir: File) = File(dir, ".ink.json")
    fun ink(dir: File): JSONObject = runCatching { JSONObject(inkFile(dir).readText()) }.getOrDefault(JSONObject())
    fun setInk(dir: File, slide: Int, strokes: JSONArray) {
        synchronized(lock) {
            val all = ink(dir)
            if (strokes.length() == 0) all.remove(slide.toString()) else all.put(slide.toString(), strokes)
            val tmp = File(dir, ".ink.json.tmp"); tmp.writeText(all.toString()); if (!tmp.renameTo(inkFile(dir))) { inkFile(dir).writeText(all.toString()); tmp.delete() }
        }
    }

    // ------------------------------------------------------------------------ the session note (MOC)

    private fun mocName(ref: Ref) = MarkdownExporter.sanitizeFilename("${ref.base} – ${ref.topic}").take(120)

    /** Writes the session note again: details, readings, slide notes and recordings. Renames it if the topic changed. */
    fun writeMoc(ref: Ref, dir: File, docTitles: Map<Long, String> = emptyMap()) {
        synchronized(lock) {
            val target = File(dir, mocName(ref) + ".md")
            // An older session note of this session under another name (the topic or date changed): replaced.
            markdownFiles(dir).forEach { f -> if (f != target && runCatching { split(f.readText()).first["marginalia-moc"] }.getOrNull() == ref.sessionId) f.delete() }
            val x = ref.session
            val sb = StringBuilder("---\n")
            sb.append("title: \"").append(esc(ref.topic)).append("\"\n")
            sb.append("marginalia-moc: ").append(ref.sessionId).append('\n')
            sb.append("course: \"").append(esc(listOf(ref.course.optString("code"), ref.course.optString("title")).filter { it.isNotBlank() }.joinToString(" – "))).append("\"\n")
            if (x.optString("date").isNotBlank()) sb.append("date: ").append(x.optString("date")).append('\n')
            listOf("time", "room", "kind", "lecturer").forEach { k -> if (x.optString(k).isNotBlank()) sb.append(k).append(": \"").append(esc(x.optString(k))).append("\"\n") }
            sb.append("tags:\n  - Lecture\n  - ").append(courseTag(ref.course)).append('\n')
            sb.append("---\n\n# ").append(ref.topic).append("\n\n")
            if (x.optString("note").isNotBlank()) sb.append(x.optString("note").trim()).append("\n\n")
            val reads = x.optJSONArray("readings")
            if (reads != null && reads.length() > 0) {
                sb.append("## Readings\n\n")
                for (i in 0 until reads.length()) {
                    val r = reads.optJSONObject(i) ?: continue
                    val title = r.optLong("docId", 0L).takeIf { it > 0 }?.let { docTitles[it] } ?: r.optString("text").ifBlank { "(a reading)" }
                    val where = listOf(r.optString("chapter"), r.optString("pages").takeIf { it.isNotBlank() }?.let { "pp. $it" }.orEmpty()).filter { it.isNotBlank() }.joinToString(", ")
                    sb.append("- ").append(title).append(if (where.isNotBlank()) " — $where" else "").append(if (r.optString("required") == "optional") " (optional)" else "").append('\n')
                }
                sb.append('\n')
            }
            val slides = slideFiles(dir).toSortedMap()
            if (slides.isNotEmpty()) {
                sb.append("## Slide notes\n\n")
                slides.forEach { (n, p) ->
                    val first = p.second.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty().take(100)
                    sb.append("- [[").append(p.first.nameWithoutExtension).append("|Slide ").append(n).append("]]").append(if (first.isNotBlank()) " — $first" else "").append('\n')
                }
                sb.append('\n')
            }
            val recs = Recordings.listIn(recordingsDir(dir))
            if (recs.isNotEmpty()) {
                sb.append("## Recordings\n\n")
                recs.sortedBy { it.startedAt }.forEach { r ->
                    sb.append("- ![[").append(r.audio.name).append("]]").append(" (").append(SpokenComments.clock(r.durationMs)).append(")")
                    if (r.transcribed) sb.append(" · [[").append(r.transcript.nameWithoutExtension).append("|Transcript]]")
                    sb.append('\n')
                }
                sb.append('\n')
            }
            target.writeText(sb.toString())
        }
    }

    suspend fun docTitles(context: Context, ref: Ref): Map<Long, String> {
        val ids = ref.session.optJSONArray("readings")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optLong("docId", 0L)?.takeIf { id -> id > 0 } } }.orEmpty()
        if (ids.isEmpty()) return emptyMap()
        val dao = AppDatabase.getInstance(context).documentDao()
        return ids.mapNotNull { id -> dao.getDocumentById(id)?.let { id to it.title } }.toMap()
    }

    /** The session note after a recording or transcript changed (found from the recording's timeline). */
    suspend fun refreshFromTimeline(context: Context, tl: JSONObject) {
        val ref = find(context, tl.optString("courseId"), tl.optString("sessionId")) ?: return
        val dir = folder(context, ref)
        writeMoc(ref, dir, docTitles(context, ref))
    }

    // ------------------------------------------------------------------------ the transcript, slide by slide

    /** The transcript as Markdown: speech under the slide that was showing when it was said. */
    fun transcriptMarkdown(info: Recordings.Info, tl: JSONObject, segs: List<Transcripts.Segment>, language: String, engine: String, dir: File?): String {
        val clock = SpokenComments::clock
        val evs = tl.optJSONArray("events")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            .filter { it.optString("type") == "slide" || it.optString("type") == "start" }.sortedBy { it.optLong("t") }
        val notes = dir?.let { slideFiles(it) }.orEmpty()
        val started = tl.optLong("started", info.startedAt)
        val sb = StringBuilder("---\n")
        sb.append("title: \"Recording – ").append(esc(tl.optString("documentTitle"))).append(" – ")
            .append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(started))).append("\"\n")
        sb.append("marginalia-recording: ").append(info.id).append('\n')
        sb.append("marginalia-session: ").append(tl.optString("sessionId")).append('\n')
        sb.append("date: ").append(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(started))).append('\n')
        sb.append("duration: \"").append(clock(tl.optLong("durationMs", info.durationMs))).append("\"\n")
        sb.append("language: ").append(language).append('\n')
        sb.append("transcribed-with: ").append(engine).append('\n')
        sb.append("tags:\n  - Recording\n  - Lecture\n---\n\n")
        sb.append("![[").append(info.audio.name).append("]]\n")
        // Sections start at each slide change; consecutive changes with no speech between them are skipped.
        val bounds = evs.ifEmpty { listOf(JSONObject().put("t", 0).put("type", "start")) }
        var k = 0
        bounds.forEachIndexed { i, e ->
            val next = bounds.getOrNull(i + 1)?.optLong("t") ?: Long.MAX_VALUE
            val mine = ArrayList<Transcripts.Segment>()
            while (k < segs.size && (segs[k].start < next || i == bounds.lastIndex)) { mine += segs[k]; k++ }
            if (mine.isEmpty()) return@forEachIndexed
            val t = e.optLong("t")
            val slide = e.optInt("slide", 0)
            sb.append("\n### [[").append(info.audio.name).append("#t=").append(t / 1000).append('|').append(clock(t)).append("]] ")
            sb.append(if (slide > 0) "Slide $slide" else "Start")
            notes[slide]?.let { sb.append(" · [[").append(it.first.nameWithoutExtension).append("|note]]") }
            sb.append('\n')
            mine.forEach { s -> sb.append(s.text).append('\n') }
        }
        if (segs.isEmpty()) sb.append("\n*No speech was found in this recording.*\n")
        return sb.toString()
    }
}
