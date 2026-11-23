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
}
