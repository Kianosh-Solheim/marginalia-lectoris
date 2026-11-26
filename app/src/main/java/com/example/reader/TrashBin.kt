package com.example.reader

import android.content.Context
import com.example.data.StorageManager
import com.example.data.local.AppDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The bin: Readmark/_to_delete/. Deleted and replaced files are moved there, nothing is erased, one folder
 * per book (or "Writing"). A record (.bin.json) keeps where each came from and when, so it can be put back.
 * Things put there before the record existed are listed too, with their file time, and are put back into
 * their book's folder when it can be found.
 */
object TrashBin {
    private const val INDEX = ".bin.json"
    private val lock = Any()

    suspend fun root(ctx: Context): File {
        val s = AppDatabase.getInstance(ctx).settingsDao().getSettingsSnapshot()
        val base = StorageManager.getRootDirectory(ctx, s?.customStoragePath.orEmpty(), s?.storageLocationType ?: "DOCUMENTS")
        return File(base, "_to_delete")
    }

    private fun index(bin: File): JSONObject = runCatching { JSONObject(File(bin, INDEX).readText()) }.getOrDefault(JSONObject())
    private fun saveIndex(bin: File, o: JSONObject) {
        bin.mkdirs()
        val f = File(bin, INDEX); val t = File(bin, "$INDEX.tmp"); t.writeText(o.toString()); t.renameTo(f)
    }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name); var n = 2
        val base = name.substringBeforeLast('.', name); val ext = name.substringAfterLast('.', "").let { if (it.isNotEmpty() && it != name) ".$it" else "" }
        while (f.exists()) { f = File(dir, "$base ($n)$ext"); n++ }
        return f
    }

    private fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            if (from.isDirectory) { from.copyRecursively(to); from.deleteRecursively() } else { from.copyTo(to); from.delete() }
        }
    }

    /** Moves [f] (a file or a folder) into the bin's [group] folder, and notes where it came from. → its place in the bin */
    suspend fun put(ctx: Context, f: File, group: String): File {
        val bin = root(ctx)
        val dir = File(bin, group.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Other" }).apply { mkdirs() }
        val target = unique(dir, f.name)
        val from = f.absolutePath
        move(f, target)
        synchronized(lock) {
            val o = index(bin)
            o.put(relOf(bin, target), JSONObject().put("from", from).put("deletedAt", System.currentTimeMillis()))
            saveIndex(bin, o)
        }
        return target
    }

    private fun relOf(bin: File, f: File) = f.canonicalPath.removePrefix(bin.canonicalPath).trimStart('/', File.separatorChar)

    /** A path in the bin, refused if it would lead outside it. */
    suspend fun resolve(ctx: Context, rel: String): File {
        val bin = root(ctx).canonicalFile
        val f = File(bin, rel).canonicalFile
        require(f.path.startsWith(bin.path + File.separator) && f.exists()) { "Not in the bin" }
        require(f.name != INDEX) { "Not in the bin" }
        return f
    }

    private fun sizeOf(f: File): Long = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()

    /** Everything in the bin: per group (book folder, "Writing"), the things in it, newest first. */
    suspend fun list(ctx: Context): JSONObject {
        val bin = root(ctx)
        val idx = index(bin)
        val items = JSONArray(); var total = 0L
        bin.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { g ->
            g.listFiles()?.filter { !it.name.startsWith(".") }?.forEach { f ->
                val rel = relOf(bin, f)
                val rec = idx.optJSONObject(rel)
                val size = sizeOf(f); total += size
                items.put(JSONObject()
                    .put("rel", rel).put("name", f.name).put("group", g.name).put("dir", f.isDirectory)
                    .put("files", if (f.isDirectory) f.walkTopDown().count { it.isFile } else 1)
                    .put("size", size)
                    .put("deletedAt", rec?.optLong("deletedAt") ?: f.lastModified())
                    .put("from", rec?.optString("from").orEmpty())
                    .put("ext", if (f.isDirectory) "" else f.extension.lowercase()))
            }
        }
        val sorted = (0 until items.length()).map { items.getJSONObject(it) }.sortedByDescending { it.optLong("deletedAt") }
        return JSONObject().put("items", JSONArray(sorted)).put("size", total).put("path", bin.absolutePath)
    }

    /** Where a thing from the bin goes back to: where it came from, else its book's folder, else the storage folder. */
    private suspend fun homeOf(ctx: Context, bin: File, f: File, rec: JSONObject?): File {
        rec?.optString("from")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        val base = bin.parentFile ?: bin
        val group = f.parentFile?.name.orEmpty()
        val candidates = listOf(File(base, "Books/$group"), File(base, "Articles/$group"), File(base, group))
        val dir = candidates.firstOrNull { it.isDirectory } ?: File(base, "Restored")
        return File(dir, f.name)
    }

    /** Puts a thing back; if something of that name is there now, it comes back under a new name. → where it went */
    suspend fun restore(ctx: Context, rel: String): File {
        val f = resolve(ctx, rel)
        val bin = root(ctx)
        val rec = synchronized(lock) { index(bin).optJSONObject(relOf(bin, f)) }
        val home = homeOf(ctx, bin, f, rec)
        home.parentFile?.mkdirs()
        val target = if (home.exists()) unique(home.parentFile!!, home.name.substringBeforeLast('.', home.name) + " (restored)" + home.name.substringAfterLast('.', "").let { if (it.isNotEmpty() && it != home.name) ".$it" else "" }) else home
        val key = relOf(bin, f)
        move(f, target)
        synchronized(lock) { val o = index(bin); o.remove(key); saveIndex(bin, o) }
        f.parentFile?.takeIf { it.isDirectory && it.listFiles()?.isEmpty() == true }?.delete()
        return target
    }

    /** Deletes a thing for good. */
    suspend fun erase(ctx: Context, rel: String) {
        val f = resolve(ctx, rel)
        val bin = root(ctx)
        val key = relOf(bin, f)
        if (f.isDirectory) f.deleteRecursively() else f.delete()
        synchronized(lock) { val o = index(bin); o.remove(key); saveIndex(bin, o) }
        f.parentFile?.takeIf { it.isDirectory && it.listFiles()?.isEmpty() == true }?.delete()
    }

    /** Empties the bin: everything in it is deleted for good. → how many things */
    suspend fun empty(ctx: Context): Int {
        val bin = root(ctx)
        var n = 0
        bin.listFiles()?.forEach { g ->
            if (g.isDirectory) { n += g.listFiles()?.count { !it.name.startsWith(".") } ?: 0; g.deleteRecursively() }
            else if (g.name != INDEX) g.delete()
        }
        synchronized(lock) { saveIndex(bin, JSONObject()) }
        return n
    }
}
