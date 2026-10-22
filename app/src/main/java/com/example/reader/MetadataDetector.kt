package com.example.reader

import android.content.Context
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads the title, subtitle, authors, DOI, ISBN and year of a newly added file, offline:
 *  - from the file name (library-style names such as “Title _ Subtitle -- Last, First -- … -- isbn13 978… -- …”
 *    or “Author - Title (2013)”),
 *  - from a PDF's own metadata, when it is not junk (“Microsoft Word - …”, “untitled”),
 *  - from the first pages: the largest text near the top is the title, the lines under it are the
 *    subtitle and the authors; DOIs, ISBNs (checksum-verified) and the year are found in the text.
 * Online lookups (Crossref, Open Library) stay on the “Look up” buttons.
 */
object MetadataDetector {

    data class Found(
        val title: String = "",
        val subtitle: String = "",
        val authors: String = "",     // "First Last, First Last"
        val record: BibRecord = BibRecord()
    ) {
        /** Title as shown in the library: “Main: Subtitle”. */
        val fullTitle: String get() = if (subtitle.isNotBlank() && !title.endsWith(subtitle, true)) "$title: $subtitle" else title
    }

    // Details found while adding a file, kept until the library item exists (keyed by file path).
    private val pending = ConcurrentHashMap<String, BibRecord>()

    fun remember(filePath: String, record: BibRecord) { if (record.fields.isNotEmpty() || record.entryType.isNotBlank()) pending[filePath] = record }

    /** Stores the details found for [filePath] in the new item's bibliography (empty fields only). */
    fun commit(context: Context, filePath: String, documentId: Long) {
        val found = pending.remove(filePath) ?: return
        BibStore.init(context)
        var rec = BibStore.get(documentId)
        found.fields.forEach { (k, v) -> if (rec[k].isBlank()) rec = rec.with(k, v) }
        if (rec.entryType.isBlank() && found.entryType.isNotBlank()) rec = rec.copy(entryType = found.entryType)
        BibStore.put(documentId, rec)
    }

    // ------------------------------------------------------------------ file names

    private val md5 = Regex("^[0-9a-f]{32}$", RegexOption.IGNORE_CASE)

    fun fromFileName(fileName: String): Found {
        val stem = fileName.substringBeforeLast('.').replace(Regex("\\s*\\(\\d+\\)$"), "").trim()
        var r = BibRecord()
        isbnIn(stem)?.let { r = r.with(BibField.ISBN, it) }
        doiIn(stem.replace('_', '/'))?.let { if (it.count { c -> c == '/' } >= 1) r = r.with(BibField.DOI, it) }

        // Anna's Archive / Library Genesis style: "Title -- Author -- details -- publisher -- isbn13 … -- md5 -- source"
        if (stem.contains(" -- ")) {
            val parts = stem.split(" -- ").map { it.trim() }.filter { it.isNotBlank() }
            val (main, sub) = Ris.splitTitle(colons(parts[0]))
            val authors = parts.getOrNull(1)?.let { a ->
                a.split(';', '&').map { Ris.naturalName(it) }.filter { it.isNotBlank() && !it.any(Char::isDigit) }.joinToString(", ")
            }.orEmpty()
            for (p in parts.drop(2)) {
                val low = p.lowercase(Locale.ROOT)
                when {
                    md5.matches(p) || low in setOf("ann", "anna", "libgen", "z-library", "zlib") -> {}
                    low.startsWith("isbn") -> {}
                    p.contains(',') -> {
                        // "Series, 34, 2nd ed, Place, Year"
                        for (bit in p.split(',').map { it.trim() }.filter { it.isNotBlank() }) {
                            when {
                                Regex("^(\\d+)(st|nd|rd|th)\\s*(ed\\.?|edition)$", RegexOption.IGNORE_CASE).matches(bit) -> r = r.with(BibField.EDITION, bit.takeWhile { it.isDigit() } + bit.dropWhile { it.isDigit() }.take(2))
                                Regex("^(1[5-9]|20)\\d{2}$").matches(bit) -> if (r[BibField.YEAR].isBlank()) r = r.with(BibField.YEAR, bit)
                                bit.all { it.isDigit() } -> if (r[BibField.SERIES].isNotBlank() && r[BibField.VOLUME].isBlank()) r = r.with(BibField.VOLUME, bit)
                                r[BibField.SERIES].isBlank() && bit.length > 6 && bit.contains(' ') -> r = r.with(BibField.SERIES, bit)
                                r[BibField.PLACE].isBlank() && bit.length in 3..40 && bit.first().isUpperCase() -> r = r.with(BibField.PLACE, bit)
                            }
                        }
                    }
                    Regex("^(1[5-9]|20)\\d{2}$").matches(p) -> r = r.with(BibField.YEAR, p)
                    r[BibField.PUBLISHER].isBlank() && p.length in 3..60 && p.first().isUpperCase() -> r = r.with(BibField.PUBLISHER, p)
                }
            }
            return Found(main, sub, authors, r.copy(entryType = if (r[BibField.ISBN].isNotBlank()) "book" else ""))
        }

        // "Author - Title (Year)", "Title - Author", "Author - Chapter (in Book)"
        var s = stem
        Regex("\\(in\\s+([^)]{3,})\\)\\s*$", RegexOption.IGNORE_CASE).find(s)?.let { m ->
            r = r.with(BibField.BOOKTITLE, colons(m.groupValues[1])).copy(entryType = "incollection")
            s = s.removeRange(m.range).trim()
        }
        Regex("[\\[(]((?:1[5-9]|20)\\d{2})[\\])]").find(s)?.let { m -> r = r.with(BibField.YEAR, m.groupValues[1]); s = s.removeRange(m.range).trim() }
        s = s.replace(Regex("\\s*[\\[(][^\\])]*[\\])]\\s*$"), "").trim()
        val bits = s.split(Regex("\\s+[-–—]\\s+")).map { it.trim() }.filter { it.isNotBlank() }
        if (bits.size >= 2) {
            val firstIsName = ChapterAuthorGuesser.nameLine(people(bits[0]).replace(", ", "; ")) != null
            val lastIsName = ChapterAuthorGuesser.nameLine(people(bits.last()).replace(", ", "; ")) != null
            if (firstIsName) {
                val (main, sub) = Ris.splitTitle(colons(bits.drop(1).joinToString(" - ")))
                return Found(main, sub, people(bits[0]), r)
            }
            if (lastIsName) {
                val (main, sub) = Ris.splitTitle(colons(bits.dropLast(1).joinToString(" - ")))
                return Found(main, sub, people(bits.last()), r)
            }
        }
        val (main, sub) = Ris.splitTitle(colons(if (s.contains(' ')) s else s.replace('.', ' ')))
        return Found(main, sub, "", r)
    }

    /** "Title _ Subtitle" (file names cannot contain ':') → "Title: Subtitle". */
    private fun colons(s: String) = s.replace(Regex("\\s+_\\s+"), ": ").replace(Regex("(?<=\\w)_\\s+"), ": ").replace('_', ' ')
        .replace(Regex("(?<=\\p{L})\\s{2,}(?=\\p{Lu})"), ": ").replace(Regex("\\s+"), " ").trim()

    /**
     * Names from a file name: “Shafer-Landau, Russell” (inverted) or “Felicia Cohn, Joanne Lynn” (a list),
     * separated by ; & or “and”. Returns “First Last, First Last”.
     */
    private fun people(s: String): String = s.split(Regex("\\s*(?:;|&|\\band\\b)\\s*", RegexOption.IGNORE_CASE)).flatMap { piece ->
        val bits = piece.split(',').map { it.trim() }.filter { it.isNotBlank() }
        when {
            bits.size == 2 && bits.any { !it.contains(' ') } -> listOf(Ris.naturalName(piece))
            else -> bits
        }
    }.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotBlank() }.joinToString(", ")

    // ------------------------------------------------------------------ identifiers

    fun doiIn(text: String): String? =
        Regex("\\b10\\.\\d{4,9}/[^\\s\"<>,;]+", RegexOption.IGNORE_CASE).find(text)?.value?.trimEnd('.', ')', ']')

    fun isbnIn(text: String): String? {
        val cands = Regex("(?:ISBN(?:-1[03])?|isbn1[03]?)?[:\\s]*((?:97[89][-\\s]?)?\\d{1,5}[-\\s]?\\d{1,7}[-\\s]?\\d{1,7}[-\\s]?[\\dXx])\\b").findAll(text)
        for (m in cands) {
            val d = m.groupValues[1].filter { it.isDigit() || it == 'X' || it == 'x' }.uppercase(Locale.ROOT)
            if ((d.length == 13 && validIsbn13(d)) || (d.length == 10 && validIsbn10(d))) return d
        }
        return null
    }

    private fun validIsbn13(d: String): Boolean {
        if (!d.startsWith("978") && !d.startsWith("979")) return false
        val sum = d.take(12).mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return (10 - sum % 10) % 10 == d[12] - '0'
    }

    private fun validIsbn10(d: String): Boolean {
        if (!d.take(9).all { it.isDigit() }) return false
        val sum = d.take(9).mapIndexed { i, c -> (c - '0') * (10 - i) }.sum() + if (d[9] == 'X') 10 else (d[9] - '0')
        return sum % 11 == 0
    }

    // ------------------------------------------------------------------ PDFs

    private data class Line(val text: String, val size: Float, val y: Float, val page: Int)

    private class LineStripper : PDFTextStripper() {
        val lines = mutableListOf<Line>()
        init { sortByPosition = true }
        override fun writeString(text: String, positions: MutableList<TextPosition>) {
            if (positions.isEmpty() || text.isBlank()) return
            val size = positions.maxOf { it.fontSizeInPt.takeIf { s -> s > 0.5f } ?: it.heightDir }
            val y = positions.first().yDirAdj
            val last = lines.lastOrNull()
            if (last != null && last.page == currentPageNo && kotlin.math.abs(last.y - y) < 2f) {
                lines[lines.size - 1] = last.copy(text = last.text + " " + text.trim(), size = maxOf(last.size, size))
            } else lines += Line(text.trim(), size, y, currentPageNo)
        }
    }

    private val junkTitle = Regex("^(microsoft word|untitled|document|doc\\d|scan|layout|print|pdf|title|\\d+)\\b|\\.(docx?|indd|tex|dvi|pdf|qxd)$|^[\\w-]+\\.\\w{2,4}$", RegexOption.IGNORE_CASE)
    private val junkAuthor = Regex("^(admin|administrator|user|owner|unknown|author|sony|hp|dell|lenovo|acrobat|pdf|adobe|microsoft|calibre|\\w+\\.\\w+)$|\\b(?:distiller|ghostscript|tex|word|indesign)\\b", RegexOption.IGNORE_CASE)

    fun fromPdf(context: Context, file: File, fileName: String): Found {
        val byName = fromFileName(fileName)
        return runCatching {
            PdfOutline.ensureLoader(context)
            PDDocument.load(file, MemoryUsageSetting.setupMixed(24L * 1024 * 1024)).use { pd ->
                if (pd.isEncrypted) return@use byName
                val info = pd.documentInformation
                val infoTitle = info?.title?.trim().orEmpty().takeIf { it.length >= 4 && !junkTitle.containsMatchIn(it) }.orEmpty()
                val infoAuthor = info?.author?.trim().orEmpty().takeIf { it.length >= 3 && !junkAuthor.containsMatchIn(it) }.orEmpty()
                val stripper = LineStripper()
                stripper.startPage = 1
                stripper.endPage = minOf(pd.numberOfPages, 8)
                runCatching { stripper.getText(pd) }
                val lines = stripper.lines
                val allText = lines.joinToString("\n") { it.text }

                var r = byName.record
                fun put(f: BibField, v: String?) { if (!v.isNullOrBlank() && r[f].isBlank()) r = r.with(f, v) }
                put(BibField.DOI, doiIn(allText) ?: doiIn(info?.subject.orEmpty()) ?: doiIn(info?.keywords.orEmpty()))
                put(BibField.ISBN, isbnIn(allText))
                put(BibField.YEAR, Regex("(?:©|\\(c\\)|copyright|first published|published)\\s*(?:in\\s*)?(?:\\D{0,40}?)((?:1[5-9]|20)\\d{2})", RegexOption.IGNORE_CASE).find(allText)?.groupValues?.get(1))
                Regex("\\bISSN[:\\s]*(\\d{4}-?\\d{3}[\\dXx])").find(allText)?.groupValues?.get(1)?.let { put(BibField.ISSN, it.uppercase(Locale.ROOT)) }

                // Title: the largest text in the upper part of the first page that has some text.
                val firstPage = lines.map { it.page }.distinct().firstOrNull { p -> lines.count { it.page == p } >= 3 } ?: 1
                val pageLines = lines.filter { it.page == firstPage && it.text.length >= 2 }
                val pageHeight = runCatching { pd.getPage(firstPage - 1).mediaBox.height }.getOrDefault(800f)
                val candidates = pageLines.filter { it.y < pageHeight * 0.7f && it.text.length in 3..220 && !it.text.contains("doi", true) && !Regex("^\\d+$").matches(it.text) }
                val body = pageLines.map { it.size }.sorted().let { if (it.isEmpty()) 10f else it[it.size / 2] }
                val maxSize = candidates.maxOfOrNull { it.size } ?: 0f
                var textTitle = ""
                var textSubtitle = ""
                var textAuthors = ""
                if (maxSize >= body * 1.25f) {
                    val start = pageLines.indexOfFirst { it.size >= maxSize * 0.95f && it in candidates }
                    var i = start
                    val titleParts = mutableListOf<String>()
                    while (i in pageLines.indices && pageLines[i].size >= maxSize * 0.93f && titleParts.size < 4) { titleParts += pageLines[i].text; i++ }
                    textTitle = titleParts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
                    // Following lines: a smaller heading is the subtitle; name lines are the authors.
                    val after = pageLines.drop(i).take(8)
                    val editedBy = Regex("^(?:edited|ed\\.|eds\\.|compiled|selected|redigert)(?:\\s+and\\s+\\w+)?\\s+(?:by|av)\\s+(.+)$", RegexOption.IGNORE_CASE)
                    for (l in pageLines.drop(i).take(14)) {
                        val m = editedBy.find(l.text) ?: continue
                        ChapterAuthorGuesser.nameLine(m.groupValues[1].replace(Regex("\\s+(?:and|&|og|und)\\s+"), "; "))?.let { eds ->
                            put(BibField.EDITOR, eds)
                            if (textAuthors.isBlank()) textAuthors = eds.replace("; ", ", ")
                        }
                        break
                    }
                    for (l in after) {
                        if (textAuthors.isNotBlank()) break
                        val name = ChapterAuthorGuesser.nameLine(l.text.replace(Regex("\\s+(?:and|&|og|und)\\s+"), "; "))
                        if (name != null) { textAuthors = name.replace("; ", ", "); break }
                        if (textSubtitle.isBlank() && l.size >= body * 1.1f && l.size < maxSize * 0.93f && l.text.length in 3..200) textSubtitle = l.text
                    }
                    if (textAuthors.isBlank()) {
                        // Authors are often above the title on article title pages and book title pages.
                        pageLines.take(start.coerceAtLeast(0)).asReversed().take(4).firstNotNullOfOrNull {
                            ChapterAuthorGuesser.nameLine(it.text.replace(Regex("\\s+(?:and|&|og|und)\\s+"), "; "))
                        }?.let { textAuthors = it.replace("; ", ", ") }
                    }
                    if (textTitle.uppercase(Locale.ROOT) == textTitle && textTitle.length > 6) textTitle = titleCase(textTitle)
                    if (textSubtitle.uppercase(Locale.ROOT) == textSubtitle && textSubtitle.length > 6) textSubtitle = titleCase(textSubtitle)
                }

                // Choose: a library-style file name is reliable; else the PDF's own title if the page shows it; else the big text.
                val nameReliable = fileName.contains(" -- ") || byName.authors.isNotBlank()
                fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
                val chosen: Pair<String, String> = when {
                    nameReliable && byName.title.isNotBlank() -> byName.title to byName.subtitle
                    infoTitle.isNotBlank() && (textTitle.isBlank() || norm(allText).contains(norm(infoTitle).take(40))) -> Ris.splitTitle(infoTitle)
                    textTitle.length >= 3 -> Ris.splitTitle(textTitle).let { (m, s) -> if (s.isBlank()) m to textSubtitle else m to s }
                    infoTitle.isNotBlank() -> Ris.splitTitle(infoTitle)
                    else -> byName.title to byName.subtitle
                }
                val authors = when {
                    byName.authors.isNotBlank() -> byName.authors
                    infoAuthor.isNotBlank() -> infoAuthor.replace(Regex("\\s*(;|\\band\\b|&)\\s*"), ", ")
                    else -> textAuthors
                }
                val looksArticle = r[BibField.DOI].isNotBlank() && (r[BibField.ISSN].isNotBlank() || Regex("\\b(journal|vol\\.|volume|issue|pp\\.)", RegexOption.IGNORE_CASE).containsMatchIn(allText.take(4000)))
                if (r.entryType.isBlank()) r = r.copy(entryType = if (looksArticle) "article" else if (r[BibField.ISBN].isNotBlank()) "book" else "")
                Found(chosen.first.trim(), chosen.second.trim(), authors, r)
            }
        }.getOrElse { byName }
    }

    private fun titleCase(s: String): String {
        val small = setOf("a", "an", "and", "as", "at", "but", "by", "for", "in", "of", "on", "or", "the", "to", "with", "og", "i", "på", "av")
        return s.lowercase(Locale.ROOT).split(' ').mapIndexed { i, w ->
            if (i > 0 && w in small) w else w.split('-').joinToString("-") { p -> p.replaceFirstChar { it.titlecase(Locale.ROOT) } }
        }.joinToString(" ")
    }

    /** EPUBs already carry a title and authors; this splits off the subtitle and adds what the file name knows. */
    fun forEpub(title: String, author: String, fileName: String): Found {
        val byName = fromFileName(fileName)
        val (main, sub) = Ris.splitTitle(title)
        return Found(main.ifBlank { byName.title }, sub.ifBlank { if (main.isBlank()) byName.subtitle else "" }, author.ifBlank { byName.authors }, byName.record)
    }
}
