package com.example.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.data.local.DocumentEntity
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Reflowable view of a PDF: the PDF is turned into a styled EPUB (headings, italics, block quotes,
 * footnotes, figures, printed page markers) and read with the same engine as other EPUBs.
 * The pages around the reader's position are converted first so reading starts within seconds;
 * the whole book follows in the background and is kept on disk for next time.
 */
object PdfReflow {
    const val VERSION = 2
    private const val TAG = "PdfReflow"

    /** A converted book (or the first part of one). */
    class Book(
        val file: File,
        val pages: List<ReflowPageEntry>,
        private val pageText: Map<Int, String>,
        val complete: Boolean
    ) {
        val firstPdf: Int get() = pages.firstOrNull()?.pdf ?: 1
        val lastPdf: Int get() = pages.lastOrNull()?.pdf ?: 1

        fun entryFor(pdf: Int): ReflowPageEntry? = pages.lastOrNull { it.pdf <= pdf } ?: pages.firstOrNull()
        fun hrefFor(pdf: Int): String? = entryFor(pdf)?.let { "${it.href}#pdfpage-${it.pdf}" }
        fun fileHrefFor(pdf: Int): String? = entryFor(pdf)?.href
        fun labelFor(pdf: Int): String = pages.firstOrNull { it.pdf == pdf }?.label.orEmpty()
        fun covers(pdf: Int) = complete || pdf in firstPdf..lastPdf

        /** PDF pages where a quote starts and ends (searching near [near] first). */
        fun spanForQuote(quote: String, near: Int): Pair<Int, Int> {
            val words = quote.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(' ').filter { it.isNotBlank() }
            if (words.isEmpty()) return near to near
            val head = words.take(6).joinToString(" ")
            val tail = words.takeLast(6).joinToString(" ")
            val order = pages.map { it.pdf }.sortedBy { kotlin.math.abs(it - near) }
            val start = order.firstOrNull { pageText[it]?.contains(head) == true }
                ?: order.firstOrNull { p -> pageText[p]?.let { t -> words.take(3).joinToString(" ").let { t.contains(it) } } == true }
                ?: near
            val end = (start..(start + 2)).firstOrNull { pageText[it]?.contains(tail) == true }
                ?: if (words.size > 6) (start..(start + 1)).lastOrNull { p -> pageText[p]?.contains(words.takeLast(3).joinToString(" ")) == true } ?: start else start
            return start to max(start, end)
        }
    }

    fun dir(context: Context, documentId: Long) = File(context.filesDir, "reflow/$documentId").apply { mkdirs() }
    private fun keyFor(file: File) = "${file.length()}-${file.lastModified()}-v$VERSION"

    /** The finished conversion, if it exists and matches the PDF. */
    fun loadCached(context: Context, document: DocumentEntity): Book? {
        val d = dir(context, document.id)
        val idx = File(d, "index.json"); val epub = File(d, "book.epub")
        if (!idx.isFile || !epub.isFile) return null
        return runCatching {
            val o = JSONObject(idx.readText())
            if (o.optString("key") != keyFor(File(document.filePath))) return null
            readIndex(o, epub, complete = true)
        }.getOrNull()
    }

    private fun readIndex(o: JSONObject, epub: File, complete: Boolean): Book {
        val arr = o.getJSONArray("pages")
        val pages = (0 until arr.length()).map { i -> arr.getJSONArray(i).let { ReflowPageEntry(it.getInt(0), it.getString(1), it.getString(2)) } }
        val t = o.optJSONObject("text") ?: JSONObject()
        val text = HashMap<Int, String>(); t.keys().forEach { k -> text[k.toInt()] = t.getString(k) }
        return Book(epub, pages, text, complete)
    }

    private fun writeIndex(file: File, key: String, out: ReflowOutput) {
        val o = JSONObject().put("key", key)
        val arr = JSONArray(); out.pages.forEach { arr.put(JSONArray().put(it.pdf).put(it.label).put(it.href)) }
        o.put("pages", arr)
        val t = JSONObject(); out.pageText.forEach { (k, v) -> t.put(k.toString(), v) }
        o.put("text", t)
        file.writeText(o.toString())
    }

    /**
     * Opens the reflowable book. [onBook] is called (on the caller's context) first with the pages
     * around [startPdf] and later with the whole book; [onProgress] reports the background work.
     */
    suspend fun open(
        context: Context,
        document: DocumentEntity,
        startPdf: Int,
        onProgress: (message: String, fraction: Float) -> Unit,
        onBook: (Book) -> Unit
    ) {
        loadCached(context, document)?.let { onBook(it); return }
        val file = File(document.filePath)
        val d = dir(context, document.id)
        val imgDir = File(d, "img").apply { mkdirs() }
        val key = keyFor(file)
        val models = ConcurrentHashMap<Int, RPage>()
        val meta = withContext(Dispatchers.IO) { readMeta(context, file) }
        val total = meta.pageCount
        if (total <= 0) error("The PDF has no pages.")
        val lang = BibStore.get(document.id)[BibField.LANGUAGE].ifBlank { "" }

        // 1. The pages around the reading position.
        val from = (startPdf - 6).coerceIn(1, total)
        val to = (startPdf + 24).coerceIn(from, total)
        onProgress("Converting the pages you're reading…", 0f)
        withContext(Dispatchers.IO) {
            Extractor(context, file, imgDir).use { ex -> for (p in from..to) models[p] = ex.page(p) }
        }
        if (to - from + 1 < total) {
            val window = withContext(Dispatchers.Default) {
                val pages = (from..to).mapNotNull { models[it] }
                val language = lang.ifBlank { guessLang(pages) }
                val out = ReflowComposer.compose(pages, meta.outline, document.title, EditedVolumes.displayAuthor(document), language, fileStarts(meta, from, to))
                val f = File(d, "window-$from.epub")
                writeEpub(f, out, document, language, imgDir)
                Book(f, out.pages, out.pageText, complete = false)
            }
            onBook(window)
        }

        // 2. The whole book, in the background (two workers), reusing what is done.
        val done = AtomicInteger(models.size)
        val rest = (1..total).filter { !models.containsKey(it) }
        val halves = listOf(rest.filterIndexed { i, _ -> i % 2 == 0 }, rest.filterIndexed { i, _ -> i % 2 == 1 })
        coroutineScope {
            halves.filter { it.isNotEmpty() }.map { part ->
                async(Dispatchers.IO) {
                    Extractor(context, file, imgDir).use { ex ->
                        part.forEach { p ->
                            models[p] = runCatching { ex.page(p) }.getOrElse { RPage(p, emptyList()) }
                            val n = done.incrementAndGet()
                            if (n % 8 == 0) withContext(Dispatchers.Main) { onProgress("Preparing the whole book… ${n * 100 / total}%", n.toFloat() / total) }
                        }
                    }
                }
            }.forEach { it.await() }
        }
        val full = withContext(Dispatchers.Default) {
            val pages = (1..total).mapNotNull { models[it] }
            val language = lang.ifBlank { guessLang(pages) }
            val out = ReflowComposer.compose(pages, meta.outline, document.title, EditedVolumes.displayAuthor(document), language, fileStarts(meta, 1, total))
            val f = File(d, "book.epub")
            writeEpub(f, out, document, language, imgDir)
            writeIndex(File(d, "index.json"), key, out)
            d.listFiles()?.filter { it.name.startsWith("window-") }?.forEach { runCatching { it.delete() } }
            Book(f, out.pages, out.pageText, complete = true)
        }
        onProgress("", 1f)
        onBook(full)
    }

    /** Removes the converted copy (e.g. after the PDF's text settings change). */
    fun clear(context: Context, documentId: Long) { runCatching { File(context.filesDir, "reflow/$documentId").deleteRecursively() } }

    private fun guessLang(pages: List<RPage>): String {
        val sample = pages.take(30).flatMap { it.lines }.take(200).joinToString(" ") { l -> l.joinToString(" ") { it.text } }
        return if (NoteAiAssistant.detectNorwegianOrEnglish(sample) == "Norwegian") "nb" else "en"
    }

    // ------------------------------------------------------------------ outline and chapter files

    private class Meta(val pageCount: Int, val outline: List<ROutline>)

    private fun readMeta(context: Context, file: File): Meta {
        PdfOutline.ensureLoader(context)
        PDDocument.load(file, MemoryUsageSetting.setupMixed(16L * 1024 * 1024)).use { pd ->
            val total = pd.numberOfPages
            var entries = runCatching { PdfOutline.read(pd) }.getOrDefault(emptyList())
            // A single bookmark wrapping the whole book: use what is under it.
            val tops = entries.filter { it.level == 0 }
            if (tops.size == 1 && entries.size > 3) entries = entries.filter { it !== tops[0] }.map { it.copy(level = (it.level - 1).coerceAtLeast(0)) }
            return Meta(total, entries.map { ROutline(it.title, it.page, it.level) })
        }
    }

    /** Where XHTML files start: chapter bookmarks, and at most ~30 pages per file. */
    private fun fileStarts(meta: Meta, from: Int, to: Int): List<Int> {
        val marks = meta.outline.filter { it.level <= 1 && it.page in from..to }.map { it.page }.toSortedSet()
        marks += from
        val out = sortedSetOf<Int>()
        val list = marks.toList()
        list.forEachIndexed { i, s ->
            out += s
            val end = (list.getOrNull(i + 1) ?: (to + 1)) - 1
            var p = s + 30
            while (p <= end - 5) { out += p; p += 30 }
        }
        return out.toList()
    }

    // ------------------------------------------------------------------ EPUB packaging

    private fun writeEpub(target: File, out: ReflowOutput, document: DocumentEntity, lang: String, imgDir: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        ZipOutputStream(FileOutputStream(tmp)).use { zip ->
            // mimetype first, uncompressed
            val mt = "application/epub+zip".toByteArray()
            val e = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mt.size.toLong(); compressedSize = mt.size.toLong()
                crc = CRC32().apply { update(mt) }.value
            }
            zip.putNextEntry(e); zip.write(mt); zip.closeEntry()
            fun text(name: String, s: String) { zip.putNextEntry(ZipEntry(name)); zip.write(s.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            text("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            val modified = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
            val imgs = out.images.filter { File(imgDir, it).isFile }
            text("OEBPS/content.opf", ReflowComposer.opf(document.title, EditedVolumes.displayAuthor(document), lang,
                "urn:marginalia:pdf:${document.id}", out.files.map { it.first }, imgs, modified))
            text("OEBPS/nav.xhtml", out.nav)
            text("OEBPS/style.css", out.css)
            out.files.forEach { (name, x) -> text("OEBPS/$name", x) }
            imgs.forEach { name ->
                zip.putNextEntry(ZipEntry("OEBPS/images/$name").apply { method = ZipEntry.STORED
                    val bytes = File(imgDir, name).readBytes(); size = bytes.size.toLong(); compressedSize = bytes.size.toLong()
                    crc = CRC32().apply { update(bytes) }.value })
                zip.write(File(imgDir, name).readBytes()); zip.closeEntry()
            }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
    }

    // ------------------------------------------------------------------ extraction

    /** Reads one PDF page at a time: styled words (size, bold, italic), figure positions, page label. */
    private class Extractor(context: Context, private val file: File, private val imgDir: File) : Closeable {
        private val pd: PDDocument
        private val labels: Array<String>?
        private val pfd: ParcelFileDescriptor? = runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
        private val renderer: PdfRenderer? = pfd?.let { runCatching { PdfRenderer(it) }.getOrNull() }

        init {
            PdfOutline.ensureLoader(context)
            pd = PDDocument.load(file, MemoryUsageSetting.setupMixed(24L * 1024 * 1024))
            labels = runCatching { pd.documentCatalog?.pageLabels?.labelsByPageIndices }.getOrNull()
        }

        private inner class Stripper : PDFTextStripper() {
            val words = mutableListOf<RWord>()
            val images = mutableListOf<FloatArray>() // l, t, r, b in page ratios
            var pageW = 1f; var pageH = 1f; var llx = 0f; var lly = 0f; var rotated = false

            init { sortByPosition = true; suppressDuplicateOverlappingText = true }

            override fun startPage(page: PDPage) {
                super.startPage(page)
                val box = page.cropBox
                pageW = box.width; pageH = box.height; llx = box.lowerLeftX; lly = box.lowerLeftY
                rotated = page.rotation % 360 != 0
            }

            override fun writeString(text: String, positions: MutableList<TextPosition>) {
                if (text.isBlank() || positions.isEmpty()) return
                // PDFBox hands over runs of text; when the PDF stores real space characters a run can be a
                // whole line of a column. Split at spaces so every word has its own box (needed to see columns).
                var start = 0
                for (k in 0..positions.size) {
                    val atEnd = k == positions.size
                    val isSpace = !atEnd && positions[k].unicode.isNullOrBlank()
                    if (atEnd || isSpace) {
                        if (k > start) addWord(positions.subList(start, k))
                        start = k + 1
                    }
                }
            }

            private fun addWord(positions: List<TextPosition>) {
                val t = positions.joinToString("") { it.unicode.orEmpty() }.trim()
                if (t.isEmpty()) return
                val first = positions.first()
                val w = first.pageWidth.takeIf { it > 0 } ?: pageW
                val h = first.pageHeight.takeIf { it > 0 } ?: pageH
                val l = positions.minOf { it.xDirAdj }
                val r = positions.maxOf { it.xDirAdj + it.widthDirAdj }
                val base = positions.maxOf { it.yDirAdj }
                val ht = positions.maxOf { it.heightDir }.coerceAtLeast(1f)
                val mid = positions[positions.size / 2]
                val size = mid.fontSizeInPt.takeIf { it > 1.5f } ?: (ht * 1.35f)
                val font = mid.font
                val name = font?.name.orEmpty()
                val desc = runCatching { font?.fontDescriptor }.getOrNull()
                val bold = Regex("bold|black|heavy|semibold|demi", RegexOption.IGNORE_CASE).containsMatchIn(name) ||
                    (desc?.isForceBold == true) || ((desc?.fontWeight ?: 0f) >= 600f)
                val italic = Regex("italic|oblique|-it\\b|-it$|it$|bdit|boldit", RegexOption.IGNORE_CASE).containsMatchIn(name.substringAfter('+')) ||
                    (desc?.isItalic == true) || ((desc?.italicAngle ?: 0f) != 0f)
                words += RWord(t, l / w, (base - ht) / h, r / w, base / h, size, bold, italic)
            }

            override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
                if (operator.name == "Do" && operands.isNotEmpty() && !rotated) {
                    val n = operands[0] as? COSName
                    if (n != null && runCatching { resources?.isImageXObject(n) }.getOrNull() == true) {
                        runCatching {
                            val m = graphicsState.currentTransformationMatrix
                            val x = m.translateX - llx; val y = m.translateY - lly
                            val sw = m.scalingFactorX; val sh = m.scalingFactorY
                            images += floatArrayOf(x / pageW, 1f - (y + sh) / pageH, (x + sw) / pageW, 1f - y / pageH)
                        }
                    }
                }
                super.processOperator(operator, operands)
            }
        }

        fun page(pdf: Int): RPage {
            val st = Stripper()
            st.startPage = pdf; st.endPage = pdf
            runCatching { st.getText(pd) }.onFailure { Log.w(TAG, "text p$pdf", it) }
            val label = labels?.getOrNull(pdf - 1).orEmpty()
            val ws = st.words
            val figs = st.images.map { a -> floatArrayOf(a[0].coerceIn(0f, 1f), a[1].coerceIn(0f, 1f), a[2].coerceIn(0f, 1f), a[3].coerceIn(0f, 1f)) }
                .filter { (it[2] - it[0]) > 0.05f && (it[3] - it[1]) > 0.03f }

            // A page with (almost) no text but a big image is a scan: keep the whole page as a picture.
            val bigArea = figs.maxOfOrNull { (it[2] - it[0]) * (it[3] - it[1]) } ?: 0f
            if (ws.size < 12 && (bigArea > 0.5f || ws.isEmpty() && renderer != null)) {
                val name = render(pdf, null)
                return RPage(pdf, emptyList(), catalogLabel = label, scannedImage = name.orEmpty())
            }
            // Lines by baseline, then columns resolved into reading order.
            val lines = groupLines(ws)
            val pw = ArrayList<PdfPageWord>(ws.size)
            val byKey = HashMap<String, RWord>()
            lines.forEachIndexed { li, l -> l.forEach { w ->
                val pwd = PdfPageWord(w.text, w.l, w.t, w.r, w.b, li, pw.size); pw += pwd
                byKey["${w.l}|${w.t}|${w.text}"] = w
            } }
            var gutters = emptyList<Float>()
            val ordered: List<List<RWord>> = runCatching {
                val res = PdfColumnOrder.reorder(pw)
                if (res.columns > 1) {
                    gutters = PdfColumnOrder.findGutters(pw)
                    res.words.groupBy { it.lineIndex }.toSortedMap().values.map { l -> l.mapNotNull { byKey["${it.leftRatio}|${it.topRatio}|${it.text}"] } }
                } else lines
            }.getOrDefault(lines).filter { it.isNotEmpty() }
            val images = figs.filter { (it[2] - it[0]) * (it[3] - it[1]) in 0.02f..0.9f }.mapIndexedNotNull { i, f ->
                render(pdf, f)?.let { RImage(f[0], f[1], f[2], f[3], it) }
            }
            return RPage(pdf, ordered, gutters, images, label)
        }

        private fun groupLines(ws: List<RWord>): List<List<RWord>> {
            val sorted = ws.sortedBy { it.b }
            val lines = mutableListOf<MutableList<RWord>>()
            val bases = mutableListOf<Float>()
            for (w in sorted) {
                val h = (w.b - w.t).coerceAtLeast(0.004f)
                val i = bases.indexOfLast { kotlin.math.abs(it - w.b) < h * 0.45f }
                if (i >= 0) lines[i] += w else { lines += mutableListOf(w); bases += w.b }
            }
            return lines.map { l -> l.sortedBy { it.l } }
        }

        /** Renders a figure region (or the whole page) to a JPEG in the book's image folder. */
        private fun render(pdf: Int, region: FloatArray?): String? {
            val r = renderer ?: return null
            val name = if (region == null) "page_$pdf.jpg" else "fig_${pdf}_${(region[0] * 1000).toInt()}_${(region[1] * 1000).toInt()}.jpg"
            val out = File(imgDir, name)
            if (out.isFile && out.length() > 0) return name
            return runCatching {
                synchronized(r) {
                    val pg = r.openPage(pdf - 1)
                    try {
                        val targetW = if (region == null) 1300 else 1600
                        val scale = targetW.toFloat() / pg.width
                        val bw = targetW; val bh = (pg.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val crop = if (region == null) bmp else {
                            val x0 = (region[0] * bw).toInt().coerceIn(0, bw - 1); val y0 = (region[1] * bh).toInt().coerceIn(0, bh - 1)
                            val x1 = (region[2] * bw).toInt().coerceIn(x0 + 1, bw); val y1 = (region[3] * bh).toInt().coerceIn(y0 + 1, bh)
                            Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0)
                        }
                        FileOutputStream(out).use { crop.compress(Bitmap.CompressFormat.JPEG, 84, it) }
                        if (crop !== bmp) crop.recycle()
                        bmp.recycle()
                    } finally {
                        pg.close()
                    }
                }
                name
            }.getOrNull()
        }

        override fun close() {
            runCatching { pd.close() }
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }
    }
}
