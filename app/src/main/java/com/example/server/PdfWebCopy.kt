package com.example.server

import android.content.Context
import android.util.Log
import com.example.reader.PdfOutline
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A browser-friendly copy of a large PDF, kept in the cache.
 *
 * The browser's PDF engine reads a file in small pieces. To open page 300 it first has to look at
 * the page entries of pages 1–299; in most book PDFs these sit next to each page's (large) image,
 * spread through the whole file, so it had to fetch hundreds of pieces one after the other. Saved
 * again with PDFBox, all page entries are written together near the start of the file, so any page
 * opens after a few small reads. Pages, page numbers, labels and bookmarks are unchanged, so notes
 * keep their pages. The original file is never touched.
 */
object PdfWebCopy {
    private const val MIN_SIZE = 6L * 1024 * 1024
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "PdfWebCopy").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val busy = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    private fun dir(context: Context) = File(context.cacheDir, "web-pdf").apply { mkdirs() }

    private fun key(id: Long, f: File) = "$id-${f.length()}-${f.lastModified()}"

    /** The ready copy, or null (then the original is served and a copy is started). */
    fun copyFor(context: Context, id: Long, original: File): File? {
        if (original.length() < MIN_SIZE) return null
        val k = key(id, original)
        val copy = File(dir(context), "$k.pdf")
        if (copy.isFile && copy.length() > 0) return copy
        prepare(context, id, original)
        return null
    }

    /** The ready copy without starting one. */
    fun readyCopy(context: Context, id: Long, original: File): File? =
        File(dir(context), "${key(id, original)}.pdf").takeIf { it.isFile && it.length() > 0 }

    /** Starts making the copy in the background (does nothing if it exists or is being made). */
    fun prepare(context: Context, id: Long, original: File) {
        if (!original.isFile || original.length() < MIN_SIZE) return
        val k = key(id, original)
        val target = File(dir(context), "$k.pdf")
        if (target.isFile || k in failed || !busy.add(k)) return
        val app = context.applicationContext
        worker.execute {
            val tmp = File(dir(app), "$k.part")
            try {
                PdfOutline.ensureLoader(app)
                PDDocument.load(original, MemoryUsageSetting.setupTempFileOnly().setTempDir(app.cacheDir)).use { pd ->
                    if (pd.isEncrypted) { failed += k; return@use }
                    pd.save(tmp)
                }
                if (tmp.isFile && tmp.length() > 0) {
                    // Old copies of this book (earlier versions of the file) are no longer needed.
                    dir(app).listFiles()?.filter { it.name.startsWith("$id-") && it.name != target.name }?.forEach { it.delete() }
                    if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
                }
                trim(app)
            } catch (t: Throwable) {
                Log.w("PdfWebCopy", "Could not make a web copy of ${original.name}", t)
                failed += k
                tmp.delete()
            } finally {
                busy.remove(k)
            }
        }
    }

    /** Keeps the cache under about 2 GB, oldest first. */
    private fun trim(context: Context) {
        val files = dir(context).listFiles()?.filter { it.name.endsWith(".pdf") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total < 2L * 1024 * 1024 * 1024) break
            total -= f.length(); f.delete()
        }
    }
}
