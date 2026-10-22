package com.example.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.data.StorageManager
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.data.local.SettingsEntity
import com.example.reader.readium.ReadiumPublicationManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.Splitter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.epub.pageList
import org.readium.r2.shared.publication.services.positions
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Finds the chapters of an edited volume and a first guess at each chapter's author. */
object ChapterDetector {

    data class Detection(val volume: EditedVolume)

    suspend fun detect(context: Context, document: DocumentEntity): Result<Detection> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(document.filePath)
            check(file.isFile) { "The book file is missing." }
            val (chapters, total) = if (document.fileType.equals("PDF", true) || file.extension.equals("pdf", true)) fromPdf(context, file, document.author, document.title)
            else fromEpub(context, file, document.author, document.title)
            check(chapters.isNotEmpty()) { "This book has no table of contents. Add the chapters by hand with “Add chapter”." }
            Detection(EditedVolume(enabled = true, chapters = chapters, totalPages = total, detectedAt = System.currentTimeMillis(), format = EditedVolume.CURRENT_FORMAT))
        }
    }

    // ---------------------------------------------------------------------------------- PDF

    private fun fromPdf(context: Context, file: File, bookAuthors: String, bookTitle: String): Pair<List<VolumeChapter>, Int> {
        PdfOutline.ensureLoader(context)
        PDDocument.load(file, MemoryUsageSetting.setupMixed(24L * 1024L * 1024L)).use { pd ->
            val total = pd.numberOfPages
            val labels = runCatching { pd.documentCatalog?.pageLabels?.labelsByPageIndices }.getOrNull()
            val outline = PdfOutline.read(pd)
            val entries = outline.map { VolumeStructure.Entry(it.title, it.page, it.level) }
            val stripper = PDFTextStripper()
            val texts = HashMap<Int, String>()
            val textOf: (Int) -> String = { i ->
                texts.getOrPut(i) {
                    val page = entries[i].page
                    runCatching {
                        stripper.startPage = page
                        stripper.endPage = (page + 1).coerceAtMost(total)
                        stripper.getText(pd).take(2500)
                    }.getOrDefault("")
                }
            }
            val built = VolumeStructure.build(entries, textOf, bookAuthors, bookTitle)
            // Printed page range of each chapter from the PDF's page labels.
            val withPages = built.mapIndexed { i, c ->
                if (!c.isArticle || labels == null) c else {
                    val end = EditedVolumes.endPageOf(built, i, total)
                    val a = labels.getOrNull(c.startPage - 1).orEmpty(); val b = labels.getOrNull(end - 1).orEmpty()
                    val range = if (a.isNotBlank() && b.isNotBlank() && a != b) "$a–$b" else a
                    if (range.any { it.isDigit() }) c.copy(printedPages = range) else c
                }
            }
            return withPages to total
        }
    }

    // ---------------------------------------------------------------------------------- EPUB

    private suspend fun fromEpub(context: Context, file: File, bookAuthors: String, bookTitle: String): Pair<List<VolumeChapter>, Int> {
        val pub = ReadiumPublicationManager.openPublication(context, file) ?: error("Could not open the EPUB.")
        val positions = runCatching { pub.positions() }.getOrDefault(emptyList())
        val total = positions.size.coerceAtLeast(pub.readingOrder.size)
        val flat = mutableListOf<Pair<Link, Int>>()
        fun walk(links: List<Link>, level: Int) {
            links.forEach { l -> flat += l to level; if (level + 1 < PdfOutline.MAX_DEPTH) walk(l.children, level + 1) }
        }
        walk(pub.tableOfContents, 0)
        val pageList = runCatching { flattenLinks(pub.pageList) }.getOrDefault(emptyList())
        val entries = flat.mapIndexed { i, (link, level) ->
            val hrefStr = link.href.toString()
            val clean = hrefStr.substringBefore('#')
            val pos = positions.firstOrNull { it.href.toString() == hrefStr || it.href.toString() == clean }?.locations?.position
            val spineIdx = pub.readingOrder.indexOfFirst { it.href.toString() == hrefStr || it.href.toString() == clean }
            val start = if (pos != null && pos > 0) pos else if (spineIdx >= 0) spineIdx + 1 else 1
            val title = PdfOutline.cleanTitle(link.title.orEmpty()).ifBlank { "Chapter ${i + 1}" }
            VolumeStructure.Entry(title, start, level, hrefStr, printedRange(pub, pageList, flat, i))
        }
        // Read every chapter file once up front (EPUB files are small), so building stays non-suspending.
        val fileText = HashMap<String, String>()
        entries.map { it.href.substringBefore('#') }.distinct().forEach { f ->
            val link = pub.readingOrder.firstOrNull { it.href.toString() == f } ?: return@forEach
            val bytes = pub.get(link)?.let { r -> try { r.read().getOrNull() } finally { r.close() } } ?: return@forEach
            fileText[f] = Jsoup.parse(String(bytes, Charsets.UTF_8)).body()?.let { blockText(it) }.orEmpty().take(2500)
        }
        val built = VolumeStructure.build(entries, { i -> fileText[entries[i].href.substringBefore('#')].orEmpty() }, bookAuthors, bookTitle)
        return built to total
    }

    /** Opening text (first one or two pages) of each chapter, for the AI author lookup. */
    suspend fun openingTexts(context: Context, document: DocumentEntity, chapters: List<VolumeChapter>): List<String> = withContext(Dispatchers.IO) {
        val file = File(document.filePath)
        if (document.fileType.equals("PDF", true) || file.extension.equals("pdf", true)) {
            if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(file, MemoryUsageSetting.setupMixed(24L * 1024L * 1024L)).use { pd ->
                val stripper = PDFTextStripper()
                chapters.map { c ->
                    runCatching {
                        stripper.startPage = c.startPage.coerceIn(1, pd.numberOfPages)
                        stripper.endPage = (c.startPage + 1).coerceIn(1, pd.numberOfPages)
                        stripper.getText(pd).take(2500)
                    }.getOrDefault("")
                }
            }
        } else {
            val pub = ReadiumPublicationManager.openPublication(context, file) ?: return@withContext chapters.map { "" }
            chapters.map { c ->
                val f = c.href.substringBefore('#')
                val link = pub.readingOrder.firstOrNull { it.href.toString() == f } ?: return@map ""
                val bytes = pub.get(link)?.let { r -> try { r.read().getOrNull() } finally { r.close() } } ?: return@map ""
                Jsoup.parse(String(bytes, Charsets.UTF_8)).body()?.let { blockText(it) }.orEmpty().take(2500)
            }
        }
    }

    private fun flattenLinks(links: List<Link>): List<Link> = links.flatMap { listOf(it) + flattenLinks(it.children) }

    /** Printed page range from the EPUB page list: first and last page marker inside the chapter's files. */
    private fun printedRange(pub: Publication, pageList: List<Link>, toc: List<Pair<Link, Int>>, index: Int): String {
        if (pageList.isEmpty()) return ""
        val order = pub.readingOrder.map { it.href.toString() }
        fun spine(h: String) = order.indexOf(h.substringBefore('#'))
        val (link, level) = toc[index]
        val from = spine(link.href.toString()).takeIf { it >= 0 } ?: return ""
        val nextLink = toc.drop(index + 1).firstOrNull { it.second <= level && spine(it.first.href.toString()) > from }?.first
        val to = nextLink?.let { spine(it.href.toString()) - 1 } ?: (order.size - 1)
        val inside = pageList.filter { spine(it.href.toString()) in from..to }.mapNotNull { it.title?.trim()?.takeIf { t -> t.isNotBlank() } }
        if (inside.isEmpty()) return ""
        return if (inside.size == 1 || inside.first() == inside.last()) inside.first() else "${inside.first()}–${inside.last()}"
    }

    /** Text of block elements, one per line, so name lines stay separate from the title. */
    fun blockText(root: Element): String {
        val blocks = root.select("h1,h2,h3,h4,h5,h6,p,div,li,blockquote,dt,dd,figcaption,address,span.author")
            .filter { el -> el.children().none { it.isBlock } || el.tagName() == "p" }
        return blocks.joinToString("\n") { it.text() }.ifBlank { root.text() }
    }
}

/** Exports one chapter: its notes, overview, BibTeX and its own pages as a PDF file. */
object ChapterExporter {

    data class Result(val file: File, val savedCopy: File?, val noteCount: Int, val pdfName: String)

    suspend fun export(context: Context, book: DocumentEntity, volume: EditedVolume, index: Int, highlights: List<HighlightEntity>, settings: SettingsEntity): Result =
        withContext(Dispatchers.IO) {
            val chapter = volume.chapters[index]
            val chapterNotes = EditedVolumes.highlightsIn(volume, index, highlights)
            val baseName = EditedVolumes.exportFileName(book, volume, index).substringBeforeLast('.')
            val workDir = File(context.cacheDir, "exports/chapters").apply { mkdirs() }
            val pdf = File(workDir, "${MarkdownExporter.sanitizeFilename(baseName)}.pdf")
            if (book.fileType.equals("PDF", true) || book.filePath.endsWith(".pdf", true)) {
                extractPdfPages(context, File(book.filePath), chapter.startPage, EditedVolumes.endPageOf(volume.chapters, index, volume.totalPages), pdf)
            } else {
                renderEpubChapterPdf(context, book, volume, index, pdf)
            }
            BibStore.init(context)
            val (doc, rec) = EditedVolumes.chapterAsDocument(book, volume, index)
            // Notes keep the book's own page/locus data; only the linked source file becomes the chapter PDF.
            val bookStyleName = MarkdownExporter.formatExportedSourceFilename(doc)
            val pdfName = MarkdownExporter.formatExportedSourceFilename(doc.copy(filePath = pdf.absolutePath, fileType = "PDF"))
            fun fix(md: String) = md.replace(bookStyleName, pdfName)
            val folder = MarkdownExporter.sanitizeFilename(baseName)
            val zip = File(File(context.cacheDir, "exports").apply { mkdirs() }, "$folder.zip")
            val used = HashSet<String>()
            ZipOutputStream(FileOutputStream(zip)).use { zos ->
                fun unique(name: String): String {
                    var n = name; var k = 2
                    while (!used.add(n.lowercase(Locale.ROOT))) { n = name.substringBeforeLast('.') + "-$k." + name.substringAfterLast('.'); k++ }
                    return n
                }
                fun text(name: String, content: String) {
                    zos.putNextEntry(ZipEntry("$folder/${unique(name)}")); zos.write(content.toByteArray(Charsets.UTF_8)); zos.closeEntry()
                }
                fun bin(name: String, f: File) {
                    if (!f.isFile) return
                    zos.putNextEntry(ZipEntry("$folder/${unique(name)}")); f.inputStream().use { it.copyTo(zos) }; zos.closeEntry()
                }
                text("${MarkdownExporter.sanitizeFilename(MarkdownExporter.getMocTitle(doc.title, settings))}.md",
                    fix(MarkdownExporter.generateMocMarkdown(doc, chapterNotes, settings)))
                chapterNotes.forEach { h -> text("${MarkdownExporter.sanitizeFilename(h.title)}.md", fix(MarkdownExporter.generateHighlightMarkdown(h, doc, settings))) }
                if (chapterNotes.isNotEmpty()) text("highlight.md", fix(MarkdownExporter.generateUnifiedObsidianBookMarkdown(doc, chapterNotes, settings)))
                text("${Citations.citeKey(doc, rec)}.bib", Citations.bibtex(doc, rec))
                Sketches.filesFor(context, chapterNotes.map { it.userNotes }).forEach { bin(it.name, it) }
                bin(pdfName, pdf)
                runCatching { MarkdownExporter.resolveOrGenerateCoverFile(context, book, settings) }.getOrNull()
                    ?.let { bin(MarkdownExporter.formatExportedCoverFilename(doc), it) }
            }
            // Keep a copy next to the other exports in the library folder.
            val saved = runCatching {
                val dir = File(StorageManager.getRootDirectory(context, settings.customStoragePath, settings.storageLocationType), "Exports/Chapters").apply { mkdirs() }
                File(dir, zip.name).also { zip.copyTo(it, overwrite = true) }
            }.getOrNull()
            Result(zip, saved, chapterNotes.size, pdfName)
        }

    /** Copies PDF pages [from]..[to] (1-based, inclusive) into a new PDF. */
    fun extractPdfPages(context: Context, source: File, from: Int, to: Int, out: File) {
        if (!PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(source, MemoryUsageSetting.setupMixed(24L * 1024L * 1024L)).use { pd ->
            val start = from.coerceIn(1, pd.numberOfPages)
            val end = to.coerceIn(start, pd.numberOfPages)
            val splitter = Splitter().apply {
                setStartPage(start); setEndPage(end); setSplitAtPage(end - start + 1)
                setMemoryUsageSetting(MemoryUsageSetting.setupMixed(24L * 1024L * 1024L))
            }
            val parts = splitter.split(pd)
            try {
                parts.first().save(out)
            } finally {
                parts.forEach { runCatching { it.close() } }
            }
        }
    }

    /**
     * EPUB chapters have no fixed pages, so the chapter's text is set as a clean A5 PDF:
     * headings, paragraphs and quotations in a serif face, with a running header and page numbers.
     */
    suspend fun renderEpubChapterPdf(context: Context, book: DocumentEntity, volume: EditedVolume, index: Int, out: File) {
        val chapter = volume.chapters[index]
        val pub = ReadiumPublicationManager.openPublication(context, File(book.filePath)) ?: error("Could not open the EPUB.")
        val order = pub.readingOrder
        val startFile = chapter.href.substringBefore('#')
        val startAnchor = chapter.href.substringAfter('#', "")
        val next = volume.chapters.drop(index + 1).firstOrNull { it.level <= chapter.level && it.href.isNotBlank() }
            ?.takeIf { n -> !(n.href.substringBefore('#') == startFile && n.href.substringAfter('#', "").isBlank()) }
        val endFile = next?.href?.substringBefore('#')
        val endAnchor = next?.href?.substringAfter('#', "").orEmpty()
        val from = order.indexOfFirst { it.href.toString() == startFile }.coerceAtLeast(0)
        val to = endFile?.let { f -> order.indexOfFirst { it.href.toString() == f } }?.takeIf { it >= from } ?: order.lastIndex

        data class Block(val text: String, val kind: Char) // h = heading, p = paragraph, q = quote, l = list item
        val blocks = mutableListOf<Block>()
        for (i in from..to) {
            val link = order[i]
            val bytes = pub.get(link)?.let { r -> try { r.read().getOrNull() } finally { r.close() } } ?: continue
            val doc = Jsoup.parse(String(bytes, Charsets.UTF_8))
            val all = doc.allElements
            val indexOf = HashMap<Element, Int>().apply { all.forEachIndexed { k, e -> put(e, k) } }
            val startAt = if (i == from && startAnchor.isNotBlank()) doc.getElementById(startAnchor)?.let { indexOf[it] } ?: 0 else 0
            val stopAt = if (endFile != null && link.href.toString() == endFile) {
                if (endAnchor.isNotBlank()) doc.getElementById(endAnchor)?.let { indexOf[it] } ?: 0 else 0
            } else Int.MAX_VALUE
            if (stopAt == 0 && i != from) break
            doc.body()?.select("h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,dt,dd,figcaption,div")?.forEach { el ->
                if (el.tagName() == "div" && (el.children().any { it.isBlock } || el.ownText().isBlank())) return@forEach
                if (el.parents().any { it.tagName() in setOf("p", "li", "blockquote", "dd", "h1", "h2", "h3", "h4", "h5", "h6") }) return@forEach
                val k = indexOf[el] ?: return@forEach
                val containsStart = startAt in k until (k + el.allElements.size)
                if (k < startAt && !containsStart) return@forEach
                if (k >= stopAt) return@forEach
                val t = el.text().trim()
                if (t.isBlank()) return@forEach
                val kind = when {
                    el.tagName().matches(Regex("h[1-6]")) -> 'h'
                    el.tagName() == "blockquote" -> 'q'
                    el.tagName() == "li" -> 'l'
                    else -> 'p'
                }
                blocks += Block(t, kind)
            }
            if (stopAt != Int.MAX_VALUE) break
        }
        if (blocks.isEmpty()) blocks += Block(chapter.title, 'h')

        val pageW = 420; val pageH = 595 // A5 in points
        val margin = 48f; val top = 64f; val bottom = 56f
        val width = (pageW - 2 * margin).toInt()
        val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        val body = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { typeface = serif; textSize = 10.5f; color = Color.BLACK }
        val head = TextPaint(body).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textSize = 14f }
        val quote = TextPaint(body).apply { textSize = 9.5f }
        val small = TextPaint(body).apply { textSize = 7.5f; color = Color.DKGRAY }
        val pdf = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun finishPage() {
            page?.let { p ->
                val c = p.canvas
                c.drawText("$pageNo", pageW / 2f - small.measureText("$pageNo") / 2f, pageH - 28f, small)
                pdf.finishPage(p)
            }
            page = null
        }
        fun newPage() {
            finishPage()
            pageNo++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            val header = listOf(chapter.authors.replace("; ", ", "), chapter.title, book.title).filter { it.isNotBlank() }.joinToString(" · ")
                .let { if (it.length > 90) it.take(89) + "…" else it }
            page!!.canvas.drawText(header, margin, 34f, small)
            y = top
        }
        newPage()
        // Title block
        val titleBlock = buildString {
            append(chapter.title)
        }
        listOf(Triple(titleBlock, head, 6f), Triple(chapter.authors.replace("; ", ", "), TextPaint(body).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC) }, 18f))
            .filter { it.first.isNotBlank() }
            .forEach { (t, p, gap) ->
                val l = StaticLayout.Builder.obtain(t, 0, t.length, p, width).build()
                page!!.canvas.save(); page!!.canvas.translate(margin, y); l.draw(page!!.canvas); page!!.canvas.restore()
                y += l.height + gap
            }
        val skipFirstHeading = blocks.firstOrNull()?.let { it.kind == 'h' && EditedVolumes.norm(it.text) == EditedVolumes.norm(chapter.title) } == true
        class Style(val paint: TextPaint, val indent: Float, val before: Float, val after: Float)
        blocks.drop(if (skipFirstHeading) 1 else 0).forEach { b ->
            val st = when (b.kind) {
                'h' -> Style(head, 0f, 10f, 6f)
                'q' -> Style(quote, 18f, 4f, 6f)
                'l' -> Style(body, 10f, 0f, 3f)
                else -> Style(body, 0f, 0f, 5f)
            }
            val txt = if (b.kind == 'l') "• ${b.text}" else b.text
            val w = (width - 2 * st.indent).toInt()
            val layout = StaticLayout.Builder.obtain(txt, 0, txt.length, st.paint, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.25f).build()
            y += st.before
            // Headings never sit alone at the foot of a page.
            if (b.kind == 'h' && pageH - bottom - y < layout.height + 40f) newPage()
            var line = 0
            var fresh = false
            while (line < layout.lineCount) {
                val avail = pageH - bottom - y
                var last = line
                while (last < layout.lineCount && layout.getLineBottom(last) - layout.getLineTop(line) <= avail) last++
                if (last == line) {
                    if (fresh) last = line + 1 else { newPage(); fresh = true; continue }
                }
                val h = (layout.getLineBottom(last - 1) - layout.getLineTop(line)).toFloat()
                val c = page!!.canvas
                c.save()
                c.clipRect(margin + st.indent, y, margin + st.indent + w, y + h)
                c.translate(margin + st.indent, y - layout.getLineTop(line))
                layout.draw(c)
                c.restore()
                y += h
                line = last
                fresh = false
                if (line < layout.lineCount) { newPage(); fresh = true }
            }
            y += st.after
        }
        finishPage()
        FileOutputStream(out).use { pdf.writeTo(it) }
        pdf.close()
    }
}
