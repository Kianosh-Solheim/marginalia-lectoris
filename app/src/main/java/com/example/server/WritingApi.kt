package com.example.server

import android.content.Context
import android.util.Log
import com.example.data.StorageManager
import com.example.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * The writing program's storage (the editor itself runs in the web page).
 *
 * Each document is a folder Readmark/Writing/<Title>/ with
 * - document.json: the text in the editor's own form (the master copy),
 * - versions/: snapshots (at most one every ten minutes while writing, and on request),
 * - media/: pictures in the text,
 * - <Title>.docx (and other exports): written by the web page on save.
 *
 * It also keeps Pandoc (the converter for DOCX, ODT, Markdown, LaTeX…), downloaded once on request
 * from the npm registry (the official pandoc-wasm package) and checked against its known checksum.
 */
class WritingApi(private val context: Context) {
    private val db = AppDatabase.getInstance(context)

    private suspend fun root(): File {
        val s = db.settingsDao().getSettingsSnapshot()
        return File(StorageManager.getRootDirectory(context, s?.customStoragePath.orEmpty(), s?.storageLocationType ?: "DOCUMENTS"), "Writing").apply { mkdirs() }
    }

    private fun safe(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), " ").replace(Regex("\\s+"), " ").trim().trimStart('.').take(120).ifBlank { "Untitled" }

    /** The folder of a document id (its folder name), never outside Writing/. */
    private suspend fun dirOf(id: String): File {
        val r = root().canonicalFile
        val d = File(r, id).canonicalFile
        require(d.parentFile == r) { "Bad document" }
        if (!File(d, "document.json").isFile) throw NoSuchElementException("No such document")
        return d
    }

    private fun unique(parent: File, name: String): File {
        var f = File(parent, name); var n = 2
        while (f.exists()) { f = File(parent, "$name ($n)"); n++ }
        return f
    }

    private fun summary(d: File): JSONObject {
        val j = runCatching { JSONObject(File(d, "document.json").readText()) }.getOrNull()
        return JSONObject().put("id", d.name).put("title", j?.optString("title")?.ifBlank { d.name } ?: d.name)
            .put("modified", File(d, "document.json").lastModified()).put("created", j?.optLong("created") ?: 0L)
            .put("words", j?.optInt("words") ?: 0)
            .put("exports", JSONArray().apply { d.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("docx", "odt", "md", "tex", "html", "pdf") }?.sortedBy { it.name }?.forEach { put(it.name) } })
    }

    suspend fun route(req: HttpRequest, s: List<String>): HttpResponse {
        val m = req.method
        val first = s.firstOrNull().orEmpty()
        return when {
            s.isEmpty() && m == "GET" -> HttpResponse.json(JSONArray().apply {
                root().listFiles()?.filter { File(it, "document.json").isFile }?.sortedByDescending { File(it, "document.json").lastModified() }?.forEach { put(summary(it)) }
            }.toString())
            s.isEmpty() && m == "POST" -> {
                val b = JSONObject(req.bodyText().ifBlank { "{}" })
                val title = b.optString("title").trim().ifBlank { "Untitled" }
                val d = unique(root(), safe(title)).apply { mkdirs() }
                val now = System.currentTimeMillis()
                val doc = JSONObject().put("title", title).put("created", now).put("modified", now)
                    .put("content", b.optJSONObject("content") ?: JSONObject().put("type", "doc").put("content", JSONArray().put(JSONObject().put("type", "paragraph"))))
                File(d, "document.json").writeText(doc.toString())
                HttpResponse.json(summary(d).toString(), 201)
            }
            first == "pandoc-status" -> HttpResponse.json(Pandoc.status(context).toString())
            first == "pandoc-install" && m == "POST" -> { Pandoc.install(context); HttpResponse.json(Pandoc.status(context).toString()) }
            first == "pandoc.wasm" -> {
                val f = Pandoc.wasm(context)
                if (!f.isFile) throw NoSuchElementException("Pandoc is not downloaded yet")
                HttpResponse.FileBody(f, "application/wasm").header("Cache-Control", "max-age=31536000, immutable")
            }
            else -> {
                val dir = dirOf(first)
                val what = s.getOrNull(1).orEmpty()
                val docFile = File(dir, "document.json")
                when {
                    what.isEmpty() && m == "GET" -> HttpResponse.json(JSONObject(docFile.readText()).put("id", dir.name).put("modified", docFile.lastModified()).toString())
                    // Autosave: the whole document (with a snapshot now and then, or when asked).
                    what.isEmpty() && m == "PUT" -> {
                        val b = JSONObject(req.bodyText())
                        val old = runCatching { JSONObject(docFile.readText()) }.getOrDefault(JSONObject())
                        // Someone else (another browser) saved since this page loaded?
                        val base = b.optLong("baseModified", 0L)
                        if (base > 0 && docFile.lastModified() > base + 1500 && !b.optBoolean("force")) {
                            return HttpResponse.json(JSONObject().put("conflict", true).put("modified", docFile.lastModified()).toString(), 409)
                        }
                        val now = System.currentTimeMillis()
                        val doc = JSONObject().put("title", b.optString("title").ifBlank { old.optString("title", dir.name) })
                            .put("created", old.optLong("created", now)).put("modified", now)
                            .put("words", b.optInt("words")).put("content", b.getJSONObject("content"))
                        b.optJSONObject("settings")?.let { doc.put("settings", it) } ?: old.optJSONObject("settings")?.let { doc.put("settings", it) }
                        val vdir = File(dir, "versions").apply { mkdirs() }
                        val lastV = vdir.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L
                        val label = b.optString("label").let { safe(it).takeIf { l -> b.optString("label").isNotBlank() && l != "Untitled" } }
                        // "Save version": the text as it is now; otherwise (every ten minutes, before restoring or importing) the text before this save.
                        if (b.optBoolean("snapshotNew")) File(vdir, "${now}${if (label != null) " $label" else ""}.json").writeText(doc.toString())
                        else if (b.optBoolean("snapshot") || (old.has("content") && now - lastV > 10 * 60_000L)) {
                            File(vdir, "${now}${if (label != null) " $label" else ""}.json").writeText(old.toString())
                            // Keep the newest 200 snapshots.
                            vdir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(200)?.forEach { it.delete() }
                        }
                        val tmp = File(dir, ".document.json.tmp"); tmp.writeText(doc.toString()); tmp.renameTo(docFile)
                        HttpResponse.json(JSONObject().put("modified", docFile.lastModified()).toString())
                    }
                    what == "versions" && s.size == 2 -> HttpResponse.json(JSONArray().apply {
                        File(dir, "versions").listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.name.substringBefore(' ').substringBefore('.').toLongOrNull() ?: it.lastModified() }?.forEach { f ->
                            val t = f.nameWithoutExtension.substringBefore(' ').toLongOrNull() ?: f.lastModified()
                            put(JSONObject().put("name", f.name).put("time", t).put("label", f.nameWithoutExtension.substringAfter(' ', "")).put("size", f.length()))
                        }
                    }.toString())
                    what == "versions" && s.size == 3 -> {
                        val f = File(File(dir, "versions"), s[2]).canonicalFile
                        require(f.parentFile == File(dir, "versions").canonicalFile && f.isFile) { "No such version" }
                        HttpResponse.Bytes(200, "application/json; charset=utf-8", f.readBytes())
                    }
                    what == "media" && m == "POST" -> {
                        val name = safe(File(req.query["name"].orEmpty()).name).ifBlank { "image" }
                        val mdir = File(dir, "media").apply { mkdirs() }
                        var f = File(mdir, name); var n = 2
                        val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
                        while (f.exists()) { f = File(mdir, "$base-$n${if (ext.isNotEmpty()) ".$ext" else ""}"); n++ }
                        f.outputStream().use { req.body.copyTo(it) }
                        HttpResponse.json(JSONObject().put("name", f.name).put("url", "/api/writing/${android.net.Uri.encode(dir.name)}/media/${android.net.Uri.encode(f.name)}").toString(), 201)
                    }
                    what == "media" && s.size == 3 -> {
                        val f = File(File(dir, "media"), s[2]).canonicalFile
                        require(f.parentFile == File(dir, "media").canonicalFile && f.isFile) { "No such picture" }
                        HttpResponse.FileBody(f, MiniHttpServer.mimeFor(f.name))
                    }
                    // Exports made in the web page (DOCX on every save; ODT, Markdown… on request).
                    what == "export" && m == "POST" -> {
                        val name = safe(File(req.query["name"].orEmpty()).name)
                        require(name.substringAfterLast('.', "").lowercase() in setOf("docx", "odt", "md", "tex", "html", "pdf", "bib")) { "Not an export" }
                        val f = File(dir, name); val tmp = File(dir, ".$name.tmp")
                        tmp.outputStream().use { req.body.copyTo(it) }
                        tmp.renameTo(f)
                        HttpResponse.json(JSONObject().put("name", f.name).toString(), 201)
                    }
                    what == "files" && s.size == 3 -> {
                        val f = File(dir, s[2]).canonicalFile
                        require(f.parentFile == dir && f.isFile) { "No such file" }
                        HttpResponse.FileBody(f, MiniHttpServer.mimeFor(f.name), f.name)
                    }
                    what == "rename" && m == "POST" -> {
                        val title = JSONObject(req.bodyText()).optString("title").trim().ifBlank { "Untitled" }
                        val doc = JSONObject(docFile.readText()).put("title", title)
                        docFile.writeText(doc.toString())
                        var nd = dir
                        if (safe(title) != dir.name) {
                            val target = unique(root(), safe(title))
                            if (dir.renameTo(target)) nd = target
                        }
                        HttpResponse.json(summary(nd).toString())
                    }
                    what.isEmpty() && m == "DELETE" -> {
                        val s2 = db.settingsDao().getSettingsSnapshot()
                        val bin = File(File(StorageManager.getRootDirectory(context, s2?.customStoragePath.orEmpty(), s2?.storageLocationType ?: "DOCUMENTS"), "_to_delete"), "Writing").apply { mkdirs() }
                        val target = unique(bin, dir.name)
                        if (!dir.renameTo(target)) { dir.copyRecursively(target); dir.deleteRecursively() }
                        HttpResponse.json("{}")
                    }
                    else -> HttpResponse.error(404, "Not found")
                }
            }
        }
    }
}

/** Pandoc for the web page's writing program: downloaded once, kept on the tablet. */
object Pandoc {
    private const val TAG = "Pandoc"
    const val VERSION = "1.1.0"   // pandoc-wasm package (pandoc 3.10)
    private const val TARBALL = "https://registry.npmjs.org/pandoc-wasm/-/pandoc-wasm-$VERSION.tgz"
    private const val TARBALL_SHA512 = "IoWBsC/cbSZe71rcRtxXUxG+Auf8aGDHKYOQZA6OdwmUL2xdpQaqrSGhPa+6zfLdSar7cnyAmTWt6KU9n5kBjQ=="
    private const val WASM_SHA256 = "b47c9de52b5b45f103c2dac6fea52591aeafe3dd6cafed13331b67575233a2ff"

    fun wasm(ctx: Context) = File(ctx.filesDir, "pandoc/pandoc-$VERSION.wasm")
    @Volatile private var running = false
    @Volatile private var done = 0L
    @Volatile private var total = 0L
    @Volatile private var error = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun status(ctx: Context): JSONObject = JSONObject().put("installed", wasm(ctx).isFile).put("running", running).put("done", done).put("total", total).put("error", error).put("version", "3.10")

    fun install(ctx: Context) {
        if (running || wasm(ctx).isFile) return
        running = true; error = ""; done = 0; total = 0
        val app = ctx.applicationContext
        scope.launch {
            try {
                val dir = File(app.filesDir, "pandoc").apply { mkdirs() }
                val tgz = File(dir, "pandoc-wasm.tgz.part")
                val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
                client.newCall(Request.Builder().url(TARBALL).build()).execute().use { r ->
                    check(r.isSuccessful) { "Download failed (HTTP ${r.code})" }
                    val body = r.body ?: error("Empty download")
                    total = body.contentLength()
                    val md = MessageDigest.getInstance("SHA-512")
                    body.byteStream().use { inp -> tgz.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16); var n: Int
                        while (inp.read(buf).also { n = it } >= 0) { out.write(buf, 0, n); md.update(buf, 0, n); done += n }
                    } }
                    val got = android.util.Base64.encodeToString(md.digest(), android.util.Base64.NO_WRAP)
                    check(got == TARBALL_SHA512) { "The download did not match the published checksum" }
                }
                // Take package/src/pandoc.wasm out of the tar.gz.
                val out = File(dir, "pandoc.wasm.part")
                var found = false
                GZIPInputStream(tgz.inputStream().buffered(1 shl 16)).use { gz ->
                    val hdr = ByteArray(512)
                    while (true) {
                        if (!readFully(gz, hdr)) break
                        if (hdr.all { it.toInt() == 0 }) break
                        val name = String(hdr, 0, 100).trimEnd('\u0000')
                        val prefix = String(hdr, 345, 155).trimEnd('\u0000')
                        val full = if (prefix.isNotEmpty()) "$prefix/$name" else name
                        val size = String(hdr, 124, 12).trim('\u0000', ' ').toLong(8)
                        val padded = (size + 511) / 512 * 512
                        if (full == "package/src/pandoc.wasm") {
                            out.outputStream().use { o -> copyN(gz, o, size) }
                            skipN(gz, padded - size)
                            found = true
                        } else skipN(gz, padded)
                    }
                }
                tgz.delete()
                check(found) { "pandoc.wasm was not in the package" }
                val sha = MessageDigest.getInstance("SHA-256").let { d -> out.inputStream().use { i -> val b = ByteArray(1 shl 16); var n: Int; while (i.read(b).also { n = it } >= 0) d.update(b, 0, n) }; d.digest().joinToString("") { "%02x".format(it) } }
                check(sha == WASM_SHA256) { "pandoc.wasm did not match the known checksum" }
                out.renameTo(wasm(app))
            } catch (e: Throwable) {
                Log.w(TAG, "Install", e); error = e.message ?: e.toString()
            } finally { running = false }
        }
    }

    private fun readFully(i: java.io.InputStream, b: ByteArray): Boolean {
        var off = 0
        while (off < b.size) { val n = i.read(b, off, b.size - off); if (n < 0) return false; off += n }
        return true
    }
    private fun copyN(i: java.io.InputStream, o: java.io.OutputStream, n: Long) {
        val buf = ByteArray(1 shl 16); var left = n
        while (left > 0) { val r = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) break; o.write(buf, 0, r); left -= r }
    }
    private fun skipN(i: java.io.InputStream, n: Long) {
        val buf = ByteArray(1 shl 16); var left = n
        while (left > 0) { val r = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) break; left -= r }
    }
}
