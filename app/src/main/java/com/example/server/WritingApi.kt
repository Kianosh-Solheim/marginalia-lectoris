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
            // Citations: search, styles and locales.
            first == "cite-note" -> HttpResponse.json(com.example.reader.CslData.forNote(context, req.query["id"]?.toLongOrNull() ?: throw IllegalArgumentException("id")).toString())
            first == "citekeys" && m == "POST" -> HttpResponse.json(com.example.reader.CslData.citekeys(context, JSONObject(req.bodyText()).optJSONArray("ids").let { a -> (0 until (a?.length() ?: 0)).map { a!!.optString(it) } }).toString())
            first == "citekey-lookup" && m == "POST" -> HttpResponse.json(com.example.reader.CslData.byCitekey(context, JSONObject(req.bodyText()).optJSONArray("keys").let { a -> (0 until (a?.length() ?: 0)).map { a!!.optString(it) } }).toString())
            first == "cite-search" -> HttpResponse.json(com.example.reader.CslData.search(context, req.query["q"].orEmpty()).toString())
            first == "styles-index" -> HttpResponse.json(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.example.reader.CslData.stylesIndex(context) })
            first == "style" -> HttpResponse.json(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.example.reader.CslData.style(context, req.query["name"].orEmpty()) }.toString())
            first == "locale" -> HttpResponse.text(200, kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.example.reader.CslData.locale(context, req.query["lang"].orEmpty()) }, "application/xml; charset=utf-8")
            first == "typst-status" -> HttpResponse.json(TypstEngine.status(context).toString())
            first == "typst-install" && m == "POST" -> { TypstEngine.install(context); HttpResponse.json(TypstEngine.status(context).toString()) }
            first == "typst.wasm" -> {
                val f = TypstEngine.wasm(context)
                if (!f.isFile) HttpResponse.error(404, "Typst is not installed") else HttpResponse.FileBody(f, "application/wasm").header("Cache-Control", "private, max-age=31536000, immutable")
            }
            first == "ocr-status" -> HttpResponse.json(OcrEngine.status(context).toString())
            first == "ocr-install" && m == "POST" -> { OcrEngine.install(context); HttpResponse.json(OcrEngine.status(context).toString()) }
            first == "ocr" -> {
                val f = OcrEngine.file(context, s.getOrNull(1).orEmpty()) ?: throw NoSuchElementException("Text recognition is not downloaded yet")
                val type = if (f.name.endsWith(".js")) "application/javascript" else "application/octet-stream"
                HttpResponse.FileBody(f, type).header("Cache-Control", "private, max-age=31536000, immutable")
            }
            first == "latex-status" -> HttpResponse.json(LatexEngine.status(context).toString())
            first == "latex-install" && m == "POST" -> { LatexEngine.install(context, JSONObject(req.bodyText().ifBlank { "{}" }).optString("collection", "basic")); HttpResponse.json(LatexEngine.status(context).toString()) }
            first == "latex" -> {
                val name = s.getOrNull(1).orEmpty()
                val f = LatexEngine.file(context, name) ?: throw NoSuchElementException("LaTeX is not downloaded yet")
                val type = when {
                    name.endsWith(".wasm") -> "application/wasm"
                    name.endsWith(".js") -> "text/javascript; charset=utf-8"
                    name.endsWith(".txt") || name.endsWith(".cnf") || name.endsWith(".profile") -> "text/plain; charset=utf-8"
                    else -> "application/octet-stream"
                }
                HttpResponse.FileBody(f, type).header("Cache-Control", "max-age=31536000, immutable")
            }
            first == "latex-pkgs" -> HttpResponse.json(CtanPackages.list(context).toString())
            first == "latex-pkg" && m == "POST" -> HttpResponse.json(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CtanPackages.fetch(context, JSONObject(req.bodyText()).optString("name").trim()) }.toString())
            first == "latex-pkg" && m == "DELETE" -> { CtanPackages.remove(context, req.query["name"].orEmpty()); HttpResponse.json(CtanPackages.list(context).toString()) }
            first == "latex-pkg-file" -> HttpResponse.FileBody(CtanPackages.file(context, req.query["name"].orEmpty(), req.query["file"].orEmpty()), "application/octet-stream")
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
                        require(name.substringAfterLast('.', "").lowercase() in setOf("docx", "odt", "md", "tex", "html", "pdf", "bib", "typ", "zip")) { "Not an export" }
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
                        com.example.reader.TrashBin.put(context, dir, "Writing")   // into the bin, from where it can be put back
                        HttpResponse.json("{}")
                    }
                    else -> HttpResponse.error(404, "Not found")
                }
            }
        }
    }
}

/** Pandoc for the web page's writing program: downloaded once, kept on the tablet. */
/** A WebAssembly program fetched once from the npm registry, checked against known checksums and kept on the tablet. */
open class WasmDownload(private val TAG: String, private val folder: String, private val file: String, private val TARBALL: String,
                        private val TARBALL_SHA512: String, private val member: String, private val WASM_SHA256: String, private val label: String) {
    fun wasm(ctx: Context) = File(ctx.filesDir, "$folder/$file")
    @Volatile private var running = false
    @Volatile private var done = 0L
    @Volatile private var total = 0L
    @Volatile private var error = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun status(ctx: Context): JSONObject = JSONObject().put("installed", wasm(ctx).isFile).put("running", running).put("done", done).put("total", total).put("error", error).put("version", label)

    fun install(ctx: Context) {
        if (running || wasm(ctx).isFile) return
        running = true; error = ""; done = 0; total = 0
        val app = ctx.applicationContext
        scope.launch {
            try {
                val dir = File(app.filesDir, folder).apply { mkdirs() }
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
                        if (full == member) {
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

/** Pandoc, the document converter (pandoc-wasm 1.1.0 = Pandoc 3.10). */
object Pandoc : WasmDownload("Pandoc", "pandoc", "pandoc-1.1.0.wasm", "https://registry.npmjs.org/pandoc-wasm/-/pandoc-wasm-1.1.0.tgz",
    "IoWBsC/cbSZe71rcRtxXUxG+Auf8aGDHKYOQZA6OdwmUL2xdpQaqrSGhPa+6zfLdSar7cnyAmTWt6KU9n5kBjQ==", "package/src/pandoc.wasm",
    "b47c9de52b5b45f103c2dac6fea52591aeafe3dd6cafed13331b67575233a2ff", "3.10")

/**
 * Text recognition (OCR) for scanned PDF pages, read in the browser with tesseract.js 7: the engine
 * (tesseract.js-core 7.0.0, SIMD, LSTM only) and the English and Norwegian models (tessdata
 * 4.0.0_best_int, from the official @tesseract.js-data packages). Each is downloaded once, checked
 * against known checksums and kept in its own folder.
 */
object OcrCore : WasmDownload("OCR", "ocr-core", "tesseract-core-simd-lstm-7.0.0.wasm.js", "https://registry.npmjs.org/tesseract.js-core/-/tesseract.js-core-7.0.0.tgz",
    "WnNH518NzmbSq9zgTPeoF8c+xmilS8rFIl1YKbk/ptuuc7p6cLNELNuPAzcmsYw450ca6bLa8j3t0VAtq435Vw==", "package/tesseract-core-simd-lstm.wasm.js",
    "c58b46a4c796c0b8afccf77591d5b875b6896b45d402bbce8caa6f5362447b38", "7.0.0")
object OcrEng : WasmDownload("OCR", "ocr-eng", "eng-4.0.0_best_int.traineddata.gz", "https://registry.npmjs.org/@tesseract.js-data/eng/-/eng-1.0.0.tgz",
    "mbTumm6KQPUHyzTPQaF3ObXYnx0SqqfV2nabqFVQBwD6Kl7PhGSLSzOlfFTWy0P3BjghaSKA2W9GB19Jk+ZcTg==", "package/4.0.0_best_int/eng.traineddata.gz",
    "45b4cb346724ac1774f1c36f42f182b887bcdb28ebe63e6fff90ac41f3fcff91", "eng")
object OcrNor : WasmDownload("OCR", "ocr-nor", "nor-4.0.0_best_int.traineddata.gz", "https://registry.npmjs.org/@tesseract.js-data/nor/-/nor-1.0.0.tgz",
    "UYIAl2T2g9Nha+uKQie4uE4zGN1a/sbs4sddsH3Ao54BguKyGbSBz4pBG/+zZY2Lnre3/z/z3d9RiVL6DVKvPw==", "package/4.0.0_best_int/nor.traineddata.gz",
    "6c5d91a5b684da387bfcf16b38bae8d94771812dc9ef706b413c728ca1d37c93", "nor")

/** The three parts of text recognition, downloaded and reported as one. */
object OcrEngine {
    private val parts = listOf(OcrCore, OcrEng, OcrNor)
    fun status(ctx: Context): JSONObject {
        val all = parts.map { it.status(ctx) }
        return JSONObject().put("installed", all.all { it.optBoolean("installed") }).put("running", all.any { it.optBoolean("running") })
            .put("done", all.sumOf { it.optLong("done") }).put("total", all.sumOf { it.optLong("total") })
            .put("error", all.map { it.optString("error") }.firstOrNull { it.isNotBlank() }.orEmpty()).put("version", "tesseract.js-core 7.0.0")
    }
    fun install(ctx: Context) = parts.forEach { it.install(ctx) }
    /** The file the browser asks for by name: the engine, or a language's model. */
    fun file(ctx: Context, name: String): File? = when (name) {
        "tesseract-core-simd-lstm.wasm.js" -> OcrCore.wasm(ctx)
        "eng.traineddata.gz" -> OcrEng.wasm(ctx)
        "nor.traineddata.gz" -> OcrNor.wasm(ctx)
        else -> null
    }?.takeIf { it.isFile }
}

/** Typst, the typesetter that makes the PDFs (typst.ts web compiler 0.7.0). */
object TypstEngine : WasmDownload("Typst", "typst", "typst-compiler-0.7.0.wasm", "https://registry.npmjs.org/@myriaddreamin/typst-ts-web-compiler/-/typst-ts-web-compiler-0.7.0.tgz",
    "nMwMcfOBy5pABPDuVM7/u8425SxhPUM+OJe6daqqgVOT3+neCTz4JKwx2L8XasfEHTNvd0cUI07LR9q5ADo7Gw==", "package/pkg/typst_ts_web_compiler_bg.wasm",
    "1fc968438a672366dfec39c96c842c26ed29caff4eb1bcaab19a6c60867de5fd", "0.7.0")


/**
 * LaTeX for the web page (BusyTeX: TeX Live 2026 as WebAssembly, from the texlyre-busytex release).
 * The release is one archive (about 520 MB); only the engine and the chosen collection of packages are
 * taken out of it, each checked against a known checksum. Compiling happens in the browser.
 */
object LatexEngine {
    private const val TAG = "LatexEngine"
    const val VERSION = "1.4.1"
    private const val URL = "https://github.com/TeXlyre/texlyre-busytex/releases/download/assets-v1.4.1/busytex-assets.tar.gz"
    private val ENGINE = mapOf(
        "busytex.js" to (271047L to "875b87795162cb26b15bd93cffa2e8739bcb900d623ab904029b39402508a1a4"),
        "busytex.wasm" to (32507525L to "8d1988fc58cd1611c3cbd6bd986c3e607e5ff44f1d94252207ff7aae4cee72e0"),
        "busytex_worker.js" to (3123L to "80eca56a2eb015bdfbebff26e539dd793407b73b6c1cd351170e6fdb6e17c39a"),
        "busytex_pipeline.js" to (37112L to "60dd3820d4ce38423d53ce618f3554f8bedcbf0f340e4e2a55739fc87c4f827d"),
        "busytex_biber.js" to (3688L to "e1a3ff55a120e392d3a7ef27f1bff1978c7a9d5978999296e39e1cfeac01d923"),
        "biber.js" to (1069103L to "f097b241ec1dfaff8c43b7c3e242a85c535d4a24b6252288da869d1a8b465c08"),
        "biber.wasm" to (9644474L to "6c2ddccf3b95e06b419d4aee5f88ef10ddec658ce760fabb34d7145371ff18c5"),
        "biber.data" to (21079910L to "9ea1357e6180477479b5edfe17538ba655ebebc03142a195d85fb1d47899df21"),
        "texmf.cnf" to (43630L to "a2e9447fc8b6dfa407fa8d0d710e59b039e22598e3f17de0bdf54daba478742c"),
        "versions.txt" to (438L to "6c7fc394e74e3cc9182c8f689dcb976970fcf9ad594b1749c61e085cf38bedc5")
    )
    private val COLLECTIONS = mapOf(
        "basic" to mapOf(
        "texlive-basic.js" to (2091651L to "d4abc2e93a1ae33099107c6ad8a61813978cd44036a95697db2f2365a393c283"),
        "texlive-basic.data" to (92785062L to "ccb35d98d77cbaf988e1481f8538709f72166196785cf6b8785eabe6c8fb2993"),
        "texlive-basic.txt" to (545452L to "0a85ed693845b22b8701f2bff8a89f502403a2be9b74a6441709f48f1f0d48cd"),
        "texlive-basic.js.providespackage.txt" to (17646L to "1570049adf2e1cbc8dcf400be02acbcbc4a777d9a78d712bfdc2161b225ac9b3"),
        "texlive-basic.profile" to (553L to "013db046f620d6f2e534583ece98b601f49fd889f3ebfa0e03cfeb1aaf8bff28")
        ),
        "recommended" to mapOf(
        "texlive-recommended.js" to (3929114L to "4354ad2040462400ca856e595cec970509c95f8d2ac87fb3e06e0e17ffc9033a"),
        "texlive-recommended.data" to (201197697L to "7a912aa15f9e534b1948566771bf2510ca704fab3c3fdece7f75bc6558525a92"),
        "texlive-recommended.txt" to (1081332L to "e9660be0acd822d73f0105eeeaef65e5951d971eb651b0d60a9c3216e4d7fe2f"),
        "texlive-recommended.js.providespackage.txt" to (27453L to "16fd9f471fe18c5aecebf80f6462170e47adf187c6ff74447121559929904e02"),
        "texlive-recommended.profile" to (638L to "3cfe162838ace24720ac831a49a5f547a9038885a8054b52b5bca83f12f143bb")
        ),
        "extra" to mapOf(
        "texlive-extra.js" to (7345232L to "6c935b358ababde879aa2c62b48601d6e6be4ee7f3f9ed86c7f49136b803d618"),
        "texlive-extra.data" to (341568560L to "9ae117b8fd7eacc13430296278d63603070f083c6f1e647937a6866874c726ae"),
        "texlive-extra.txt" to (1864342L to "bc022d2fb45aad716847ac18299b84b6e4c7a5d12e399687cc9dbfcd40a7fb2c"),
        "texlive-extra.js.providespackage.txt" to (153961L to "40e2ceb5b1ef0b401bc26e1148550cd44c4345b2d17fbc6aff81b4a95f7f365f"),
        "texlive-extra.profile" to (639L to "fe9ce035e04dd2ff523606556ec2fc15e7419321e26f09590596c6c3f13281f5")
        )
    )
    @Volatile private var running = false
    @Volatile private var done = 0L
    @Volatile private var total = 0L
    @Volatile private var error = ""
    @Volatile private var installing = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun dir(ctx: Context) = File(ctx.filesDir, "latex/$VERSION")
    private fun marker(ctx: Context) = File(dir(ctx), "collection.txt")
    fun collection(ctx: Context): String? = marker(ctx).takeIf { it.isFile }?.readText()?.trim()?.takeIf { c ->
        c in COLLECTIONS && (ENGINE.keys + COLLECTIONS.getValue(c).keys).all { File(dir(ctx), it).isFile } }
    fun file(ctx: Context, name: String): File? {
        val c = collection(ctx) ?: return null
        if (name !in ENGINE && name !in COLLECTIONS.getValue(c)) return null
        return File(dir(ctx), name).takeIf { it.isFile }
    }
    fun mb(c: String) = ((ENGINE.values.sumOf { it.first } + COLLECTIONS.getValue(c).values.sumOf { it.first }) / 1_000_000)

    fun status(ctx: Context): JSONObject = JSONObject().put("installed", collection(ctx) != null).put("collection", collection(ctx) ?: JSONObject.NULL)
        .put("running", running).put("installing", installing).put("done", done).put("total", total).put("error", error).put("version", "TeX Live 2026 (BusyTeX $VERSION)")
        .put("sizes", JSONObject().apply { COLLECTIONS.keys.forEach { put(it, mb(it)) } })

    /** Takes the engine and collection [c] out of the release archive (streamed; the archive itself is not kept). */
    fun install(ctx: Context, c: String) {
        require(c in COLLECTIONS) { "Unknown collection: $c" }
        if (running || collection(ctx) == c) return
        running = true; error = ""; done = 0; total = 0; installing = c
        val app = ctx.applicationContext
        scope.launch {
            val part = File(app.filesDir, "latex/$VERSION.part")
            try {
                part.deleteRecursively(); part.mkdirs()
                val want = ENGINE + COLLECTIONS.getValue(c)
                val got = HashSet<String>()
                val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
                client.newCall(Request.Builder().url(URL).build()).execute().use { r ->
                    check(r.isSuccessful) { "Download failed (HTTP ${r.code})" }
                    val body = r.body ?: error("Empty download")
                    total = body.contentLength()
                    val counting = object : java.io.FilterInputStream(body.byteStream()) {
                        override fun read(): Int = super.read().also { if (it >= 0) done++ }
                        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) done += it }
                    }
                    GZIPInputStream(counting.buffered(1 shl 16), 1 shl 16).use { gz ->
                        val hdr = ByteArray(512)
                        while (got.size < want.size) {
                            if (!readFully(gz, hdr) || hdr.all { it.toInt() == 0 }) break
                            val name = String(hdr, 0, 100).trimEnd('\u0000')
                            val prefix = String(hdr, 345, 155).trimEnd('\u0000')
                            val full = (if (prefix.isNotEmpty()) "$prefix/$name" else name).removePrefix("busytex/")
                            val size = String(hdr, 124, 12).trim('\u0000', ' ').toLong(8)
                            val padded = (size + 511) / 512 * 512
                            val known = want[full]
                            if (known != null && hdr[156] != '5'.code.toByte()) {
                                check(size == known.first) { "$full has an unexpected size" }
                                val md = MessageDigest.getInstance("SHA-256")
                                File(part, full).outputStream().buffered(1 shl 16).use { o ->
                                    val buf = ByteArray(1 shl 16); var left = size
                                    while (left > 0) { val n = gz.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; o.write(buf, 0, n); md.update(buf, 0, n); left -= n }
                                    check(left == 0L) { "The download ended early" }
                                }
                                val sha = md.digest().joinToString("") { "%02x".format(it) }
                                check(sha == known.second) { "$full did not match the known checksum" }
                                got += full
                                skipN(gz, padded - size)
                            } else skipN(gz, padded)
                        }
                    }
                }
                check(got.size == want.size) { "Missing from the release: " + (want.keys - got).joinToString() }
                File(part, "collection.txt").writeText(c)
                val final = dir(app)
                final.deleteRecursively()
                check(part.renameTo(final)) { "Could not put the files in place" }
            } catch (e: Throwable) {
                Log.w(TAG, "Install", e); error = e.message ?: e.toString(); part.deleteRecursively()
            } finally { running = false; installing = "" }
        }
    }

    private fun readFully(i: java.io.InputStream, b: ByteArray): Boolean {
        var off = 0
        while (off < b.size) { val n = i.read(b, off, b.size - off); if (n < 0) return false; off += n }
        return true
    }
    private fun skipN(i: java.io.InputStream, n: Long) {
        val buf = ByteArray(1 shl 16); var left = n
        while (left > 0) { val r = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) break; left -= r }
    }
}


/**
 * LaTeX packages from CTAN that are not in the TeX Live collection (biblatex-chicago, for one): fetched
 * by name when asked, kept on the tablet, and handed to the browser with every compile.
 */
object CtanPackages {
    private const val TAG = "CtanPackages"
    private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,80}$")
    private val KEEP = Regex("\\.(sty|cls|clo|bbx|cbx|lbx|dbx|def|cfg|fd|ldf|tex|bst|bib|lua|ist|csv)$", RegexOption.IGNORE_CASE)
    private const val MAX_BYTES = 40L shl 20
    fun dir(ctx: Context) = File(ctx.filesDir, "latex/ctan")
    private fun pkgDir(ctx: Context, name: String): File { require(NAME.matches(name)) { "Not a CTAN package name: $name" }; return File(dir(ctx), name) }

    fun list(ctx: Context): JSONObject {
        val arr = JSONArray()
        dir(ctx).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { d ->
            val files = d.listFiles()?.filter { it.isFile && it.name != "about.json" }?.sortedBy { it.name }.orEmpty()
            val about = File(d, "about.json").takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            arr.put(JSONObject().put("name", d.name).put("version", about?.optString("version").orEmpty())
                .put("files", JSONArray(files.map { it.name })).put("bytes", files.sumOf { it.length() }))
        }
        return JSONObject().put("packages", arr)
    }

    fun file(ctx: Context, name: String, file: String): File {
        require(!file.contains('/') && !file.contains('\\') && !file.startsWith(".")) { "Bad file name" }
        return File(pkgDir(ctx, name), file).takeIf { it.isFile && it.name != "about.json" } ?: throw NoSuchElementException("No such file")
    }

    fun remove(ctx: Context, name: String) { pkgDir(ctx, name).deleteRecursively() }

    /** Fetches [name] from CTAN (its TDS zip when there is one, otherwise the package folder). */
    fun fetch(ctx: Context, name: String): JSONObject {
        val target = pkgDir(ctx, name)
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
        val info = client.newCall(Request.Builder().url("https://www.ctan.org/json/2.0/pkg/$name").build()).execute().use { r ->
            if (r.code == 404) throw NoSuchElementException("CTAN has no package called $name")
            check(r.isSuccessful) { "CTAN answered HTTP ${r.code}" }
            runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrElse { JSONObject() }
        }
        if (info.has("errors")) throw NoSuchElementException("CTAN has no package called $name")
        // The fields come as an object ({path, file}) or as a plain path, depending on the API version.
        fun pathOf(v: Any?): String = when (v) { is JSONObject -> v.optString("path"); is String -> v; else -> "" }
        val install = pathOf(info.opt("install"))
        val ctanPath = pathOf(info.opt("ctan"))
        val single = (info.opt("ctan") as? JSONObject)?.optBoolean("file") == true
        val urls = LinkedHashSet<String>().apply {
            if (install.isNotBlank()) add("https://mirrors.ctan.org/install/" + install.trimStart('/'))
            if (ctanPath.isNotBlank()) add("https://mirrors.ctan.org/" + ctanPath.trimStart('/').removeSuffix("/") + if (single) "" else ".zip")
            // Where most packages live, if CTAN did not say.
            add("https://mirrors.ctan.org/macros/latex/contrib/$name.zip")
            if (name.startsWith("biblatex-")) add("https://mirrors.ctan.org/macros/latex/contrib/biblatex-contrib/$name.zip")
        }.toList()
        Log.i(TAG, "CTAN $name: install=$install ctan=$ctanPath keys=${info.keys().asSequence().toList()}")
        val part = File(dir(ctx), "$name.part").apply { deleteRecursively(); mkdirs() }
        var kept = 0; var lastError = ""
        try {
            for (url in urls) {
                part.listFiles()?.forEach { it.delete() }; kept = 0
                try {
                    client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        check(r.isSuccessful) { "Download failed (HTTP ${r.code}) from $url" }
                        if (!url.endsWith(".zip")) {
                            val fname = url.substringAfterLast('/')
                            if (KEEP.containsMatchIn(fname)) { File(part, fname).outputStream().use { o -> r.body!!.byteStream().copyTo(o) }; kept++ }
                            return@use
                        }
                        var total = 0L
                        java.util.zip.ZipInputStream(r.body!!.byteStream().buffered(1 shl 16)).use { zip ->
                            while (true) {
                                val e = zip.nextEntry ?: break
                                val path = e.name
                                // From a TDS zip only what TeX reads (tex/, bibtex/); from a folder zip the files themselves.
                                val inTds = path.startsWith("tex/") || path.startsWith("bibtex/")
                                val wanted = !e.isDirectory && KEEP.containsMatchIn(path) && (url.contains("/install/") == inTds || !url.contains("/install/")) &&
                                    !path.contains("/doc/") && !path.substringAfterLast('/').startsWith(".")
                                if (wanted) {
                                    val out = File(part, path.substringAfterLast('/'))
                                    out.outputStream().use { o -> total += zip.copyTo(o) }
                                    check(total <= MAX_BYTES) { "$name is too large" }
                                    kept++
                                }
                            }
                        }
                    }
                    if (kept > 0) break
                } catch (e: Exception) { lastError = e.message ?: e.toString(); Log.w(TAG, "fetch $url", e) }
            }
            check(kept > 0) { if (lastError.isNotBlank()) lastError else "$name has no files LaTeX can use directly (it may need to be built from its source)" }
            File(part, "about.json").writeText(JSONObject().put("version", info.optJSONObject("version")?.optString("number").orEmpty()).put("license", info.opt("license")?.toString().orEmpty()).toString())
            target.deleteRecursively()
            check(part.renameTo(target)) { "Could not put the files in place" }
        } finally { part.deleteRecursively() }
        return list(ctx)
    }
}
