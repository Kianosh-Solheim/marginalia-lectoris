package com.example.reader

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Courses: programmes → semesters → courses (teachers, links, sessions with their readings), kept as one
 * JSON document in courses.json; teachers' photos in courses_photos/. Saved whole by the web page, with a
 * version number so two pages editing at once do not overwrite each other unseen.
 */
object Courses {
    private val lock = Any()
    fun file(ctx: Context) = File(ctx.filesDir, "courses.json")
    fun photos(ctx: Context) = File(ctx.filesDir, "courses_photos")

    fun read(ctx: Context): String = synchronized(lock) {
        val f = file(ctx)
        val data = try { if (f.isFile) JSONObject(f.readText()) else JSONObject() } catch (_: Exception) { JSONObject() }
        if (!data.has("programmes")) data.put("programmes", org.json.JSONArray())
        JSONObject().put("version", if (f.isFile) f.lastModified() else 0L).put("data", data).toString()
    }

    /** Saves [data] if the file is still the [version] the page read; otherwise says so (and gives the newer one). */
    fun write(ctx: Context, data: JSONObject, version: Long): JSONObject = synchronized(lock) {
        val f = file(ctx)
        val now = if (f.isFile) f.lastModified() else 0L
        if (version >= 0 && now != 0L && version != now) return JSONObject(read(ctx)).put("conflict", true)
        val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeText(data.toString()); tmp.renameTo(f)
        // Keep modification times distinct, so the next version check sees this save.
        if (f.lastModified() <= now) f.setLastModified(now + 1)
        JSONObject().put("ok", true).put("version", f.lastModified())
    }

    fun savePhoto(ctx: Context, bytes: ByteArray, type: String): String {
        require(bytes.isNotEmpty() && bytes.size <= 6 * 1024 * 1024) { "The picture is empty or too large (6 MB at most)." }
        val ext = if (type.contains("png")) "png" else "jpg"
        val dir = photos(ctx).apply { mkdirs() }
        val name = java.util.UUID.randomUUID().toString().replace("-", "").take(16) + "." + ext
        File(dir, name).writeBytes(bytes)
        return name
    }

    fun photo(ctx: Context, name: String): File {
        require(Regex("^[a-z0-9]{8,32}\\.(jpg|png)$").matches(name)) { "Bad name" }
        return File(photos(ctx), name).takeIf { it.isFile } ?: throw NoSuchElementException("No such picture")
    }

    // ---------------------------------------------------------------- from the web and from pasted text (only when asked)

    private val http by lazy {
        okhttp3.OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).followRedirects(true).build()
    }
    private fun fetchDoc(url: String): org.jsoup.nodes.Document {
        require(Regex("^https?://[^\\s]+$").matches(url)) { "Give a web address starting with https://" }
        http.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) MarginaliaLectoris").header("Accept-Language", "en, nb;q=0.8").build()).execute().use { r ->
            check(r.isSuccessful) { "The page answered HTTP ${r.code}" }
            val bytes = r.body?.byteStream()?.use { it.readNBytesCompat(3 * 1024 * 1024) } ?: ByteArray(0)
            return org.jsoup.Jsoup.parse(String(bytes, Charsets.UTF_8), url)
        }
    }
    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16 * 1024)
        while (out.size() < max) { val n = read(buf); if (n < 0) break; out.write(buf, 0, minOf(n, max - out.size())) }
        return out.toByteArray()
    }
    /** The readable text of a page, with its links listed after it (so profile and syllabus links can be found). */
    private fun pageText(doc: org.jsoup.nodes.Document, links: Boolean): String {
        val main = doc.selectFirst("main") ?: doc.selectFirst("article") ?: doc.body()
        main.select("script, style, nav, footer, header, noscript, form").remove()
        val text = main.wholeText().replace(Regex("[ \\t\\u00A0]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim()
        if (!links) return text
        val ls = main.select("a[href]").mapNotNull { a -> val t = a.text().trim(); val u = a.absUrl("href"); if (t.isBlank() || !u.startsWith("http")) null else "$t -> $u" }.distinct().take(150)
        return text.take(20000) + "\n\nLinks on the page:\n" + ls.joinToString("\n")
    }
    private fun ai(ctx: Context, instruction: String, content: String, maxTokens: Int): JSONObject =
        try { NoteAiAssistant.askJson(ctx, instruction, content, maxTokens = maxTokens, maxChars = 30000) }
        catch (e: IllegalStateException) { throw IllegalArgumentException(e.message ?: "The AI could not be asked.") }

    /** A course's web page → code, title, credits, description, teachers and links (for you to check). */
    fun fetchCourse(ctx: Context, url: String): JSONObject {
        val doc = fetchDoc(url)
        val o = ai(ctx, "From this university course web page, take what is stated about the course: its code, title, credits (ECTS, a number), " +
            "a description (2–4 sentences, in the page's own words where possible, in the page's language), the teaching staff with their role in the course and, when given, " +
            "email and the address of their profile page (from the list of links), and the most useful links (syllabus or reading list, timetable, Canvas or the learning platform, exam). " +
            "Leave out anything not on the page. Return only JSON: {\"code\": string, \"title\": string, \"credits\": number|null, \"description\": string, " +
            "\"teachers\": [{\"name\": string, \"role\": string, \"email\": string, \"profile\": string}], \"links\": [{\"label\": string, \"url\": string}]}.",
            pageText(doc, true), 2500)
        return o.put("url", url)
    }

    /** A staff profile page → name, title, email, phone, office and their photo (saved here). */
    fun fetchPerson(ctx: Context, url: String): JSONObject {
        val doc = fetchDoc(url)
        val imgs = (listOfNotNull(doc.selectFirst("meta[property=og:image]")?.absUrl("content")?.takeIf { it.isNotBlank() }?.let { "$it (og:image)" }) +
            doc.select("img[src]").map { "${it.absUrl("src")} (alt: ${it.attr("alt").take(60)}; class: ${it.className().take(40)})" }).distinct().take(40)
        val mails = doc.select("a[href^=mailto:]").map { it.attr("href").removePrefix("mailto:").substringBefore('?') }.distinct()
        val phones = doc.select("a[href^=tel:]").map { it.attr("href").removePrefix("tel:") }.distinct()
        val o = try {
            ai(ctx, "From this university staff profile page, take the person's name, position or title, email, phone, office (building and room) and the address of their portrait photo " +
                "(from the list of images: the one that is a photo of this person, not a logo or icon). Leave out anything not on the page. " +
                "Return only JSON: {\"name\": string, \"title\": string, \"email\": string, \"phone\": string, \"office\": string, \"photo\": string}.",
                pageText(doc, false).take(12000) + "\n\nImages:\n" + imgs.joinToString("\n") + "\nEmail links: " + mails.joinToString() + "\nPhone links: " + phones.joinToString(), 800)
        } catch (e: IllegalArgumentException) {
            // No AI: what the page says plainly.
            JSONObject().put("name", doc.selectFirst("h1")?.text().orEmpty()).put("email", mails.firstOrNull().orEmpty()).put("phone", phones.firstOrNull().orEmpty())
                .put("photo", doc.selectFirst("meta[property=og:image]")?.absUrl("content").orEmpty()).put("note", e.message)
        }
        if (o.optString("email").isBlank() && mails.isNotEmpty()) o.put("email", mails.first())
        val photo = o.optString("photo")
        o.remove("photo")
        if (photo.startsWith("http")) runCatching {
            http.newCall(okhttp3.Request.Builder().url(photo).header("User-Agent", "Mozilla/5.0 (Android) MarginaliaLectoris").build()).execute().use { r ->
                val type = r.header("Content-Type").orEmpty()
                if (r.isSuccessful && type.startsWith("image/")) {
                    val bytes = r.body?.byteStream()?.use { it.readNBytesCompat(6 * 1024 * 1024) } ?: ByteArray(0)
                    if (bytes.size > 2000) o.put("photoName", savePhoto(ctx, bytes, type))
                }
            }
        }
        return o.put("profile", url)
    }

    /** Pasted course outline or reading list → sessions with their readings (for you to check). */
    fun parseOutline(ctx: Context, text: String, year: String): JSONObject {
        require(text.isNotBlank()) { "Paste the course outline or the reading list first." }
        return ai(ctx, "Turn this course outline or reading list into sessions. For each session give: date as YYYY-MM-DD (when no year is given, it is probably $year), " +
            "time (like 10:15–12:00), room, kind (Lecture, Seminar, Workshop, Supervision, Exam, Deadline or Other), topic, and its readings. " +
            "For each reading give: author (surnames, as listed), year, title, container (the book or journal it is in), chapter, pages (like 45–78), " +
            "required (false when marked optional, recommended, further or supplementary reading), and text (the reading exactly as listed). " +
            "When the text is only a reading list without sessions, make one session per week, topic or heading, without a date. Keep the order of the text. " +
            "Return only JSON: {\"sessions\": [{\"date\": string, \"time\": string, \"room\": string, \"kind\": string, \"topic\": string, " +
            "\"readings\": [{\"author\": string, \"year\": string, \"title\": string, \"container\": string, \"chapter\": string, \"pages\": string, \"required\": boolean, \"text\": string}]}]}.",
            text.take(30000), 8000)
    }
}
