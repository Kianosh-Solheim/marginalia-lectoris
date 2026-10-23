package com.example.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.StorageManager
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.data.local.SettingsEntity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object MarkdownExporter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    fun sanitizeFilename(name: String): String {
        val sanitized = name.replace(Regex("[/\\\\?%*:|\"<>]"), "-")
            .trim()
            .replace(Regex("-+"), "-")
        return if (sanitized.isBlank()) "Untitled-Note" else sanitized
    }

    /**
     * Formats page numbers for UI display & Markdown export:
     * - For PDFs with a printed book page number (single e.g. "3", "xiv" or multi-page span e.g. "3–4", "xiv–xv"):
     *   "PDF page number 14, printed page number 3" or "PDF pages 14–15, printed pages 3–4"
     * - For PDFs without a printed page number:
     *   "PDF page number 14" or "PDF pages 14–15"
     * - For EPUB/TXT/HTML:
     *   Only Page Equivalent (if present) and Chapter + Paragraph (without reflow-dependent Page #).
     */
    fun formatHighlightPageDisplay(highlight: HighlightEntity, isPdf: Boolean): String {
        val (pdfRangeStr, printed) = PdfToEbookParser.parseStoredPageLabels(
            primaryPdfPage = highlight.pageNumber,
            rawPrintedPageLabel = highlight.printedPageLabel
        )
        val isMultiPdf = pdfRangeStr.contains("–") || pdfRangeStr.contains("-")
        val isMultiPrinted = printed.contains("–") || printed.contains("-")
        val pdfLabel = if (isMultiPdf) "PDF pages $pdfRangeStr" else "PDF page number $pdfRangeStr"
        val printedLabel = if (isMultiPrinted) "printed pages $printed" else "printed page number $printed"
        val pageEq = highlight.pageEquivalent.trim()
        val isMultiEq = pageEq.contains("–") || pageEq.contains("-")
        val pageEqLabel = if (pageEq.isNotEmpty()) {
            if (isMultiEq) "page equivalent pp. $pageEq" else "page equivalent p. $pageEq"
        } else ""
        val paraCitation = highlight.paragraphCitation.trim().ifBlank {
            if (!isPdf && highlight.chapterTitle.isNotBlank()) "${highlight.chapterTitle.trim()}, ¶ 1" else ""
        }

        return if (isPdf) {
            val base = if (printed.isNotEmpty()) {
                "$pdfLabel, $printedLabel"
            } else {
                pdfLabel
            }
            if (paraCitation.isNotEmpty()) "$base ($paraCitation)" else base
        } else {
            val parts = mutableListOf<String>()
            if (pageEqLabel.isNotEmpty()) {
                parts.add(pageEqLabel)
            } else if (printed.isNotEmpty()) {
                parts.add(printedLabel)
            }
            if (paraCitation.isNotEmpty()) {
                parts.add(paraCitation)
            }
            if (parts.isEmpty()) "¶ 1" else parts.joinToString(" • ")
        }
    }

    /**
     * Compact badge label for small UI chips (e.g. "PDF p. 14 • Printed p. 3" or "Page eq. p. 42 • Ch. 1, ¶ 3").
     */
    fun formatHighlightBadgeLabel(highlight: HighlightEntity, isPdf: Boolean): String {
        val (pdfRangeStr, printed) = PdfToEbookParser.parseStoredPageLabels(
            primaryPdfPage = highlight.pageNumber,
            rawPrintedPageLabel = highlight.printedPageLabel
        )
        val isMultiPdf = pdfRangeStr.contains("–") || pdfRangeStr.contains("-")
        val isMultiPrinted = printed.contains("–") || printed.contains("-")
        val pdfPrefix = if (isMultiPdf) "PDF pp. $pdfRangeStr" else "PDF p. $pdfRangeStr"
        val printedPrefix = if (isMultiPrinted) "Printed pp. $printed" else "Printed p. $printed"
        val pageEq = highlight.pageEquivalent.trim()
        val isMultiEq = pageEq.contains("–") || pageEq.contains("-")
        val paraCitation = highlight.paragraphCitation.trim().ifBlank {
            if (!isPdf && highlight.chapterTitle.isNotBlank()) "${highlight.chapterTitle.trim()}, ¶ 1" else ""
        }

        return if (isPdf) {
            if (printed.isNotEmpty()) {
                "$pdfPrefix • $printedPrefix"
            } else {
                pdfPrefix
            }
        } else {
            val parts = mutableListOf<String>()
            if (pageEq.isNotEmpty()) {
                parts.add("Page eq. ${if (isMultiEq) "pp." else "p."} $pageEq")
            } else if (printed.isNotEmpty()) {
                parts.add("(${if (isMultiPrinted) "pp." else "p."} $printed)")
            }
            if (paraCitation.isNotEmpty()) {
                parts.add(paraCitation)
            }
            if (parts.isEmpty()) "¶ 1" else parts.joinToString(" • ")
        }
    }

    /**
     * Extracts a 4-digit publication year (1000..2099) from an EPUB OPF `<dc:date>`, PDF metadata CreationDate,
     * document summary, or original filename when available. Returns `""` if no year is available.
     */
    fun extractPublishedYear(document: DocumentEntity): String {
        // A year set by the user ("Set as → Year") or fetched from DOI/ISBN takes precedence.
        BibStore.get(document.id)[BibField.YEAR].takeIf { it.isNotBlank() }?.let { return it }
        val yearRegex = Regex("(?:^|[^0-9])(1[5-9]\\d{2}|20\\d{2})(?:[^0-9]|$)")

        // 1. Check original file name (excluding purely random temp timestamps)
        val sourceFile = File(document.filePath)
        val rawFileName = sourceFile.nameWithoutExtension
        if (rawFileName.isNotBlank() && !rawFileName.startsWith("test_") && !rawFileName.startsWith("tmp")) {
            val matchInName = yearRegex.find(rawFileName)?.groupValues?.getOrNull(1)
            if (!matchInName.isNullOrBlank()) return matchInName
        }

        // 2. Check EPUB OPF <dc:date> or PDF document information dictionary if source file exists
        if (sourceFile.exists() && sourceFile.isFile) {
            try {
                if (document.fileType.equals("EPUB", ignoreCase = true) || sourceFile.extension.equals("epub", ignoreCase = true)) {
                    java.util.zip.ZipInputStream(sourceFile.inputStream()).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name.endsWith(".opf", ignoreCase = true)) {
                                val opfText = String(zis.readBytes(), Charsets.UTF_8)
                                val dateTagRegex = Regex("<(?:[a-zA-Z0-9]+:)?date\\b[^>]*>([^<]+)</(?:[a-zA-Z0-9]+:)?date>", RegexOption.IGNORE_CASE)
                                val dateText = dateTagRegex.find(opfText)?.groupValues?.getOrNull(1).orEmpty()
                                val matchInOpf = yearRegex.find(dateText)?.groupValues?.getOrNull(1)
                                if (!matchInOpf.isNullOrBlank()) return matchInOpf
                                break
                            }
                            entry = zis.nextEntry
                        }
                    }
                } else if (document.fileType.equals("HTML", ignoreCase = true) ||
                    sourceFile.extension.equals("html", ignoreCase = true) ||
                    sourceFile.extension.equals("htm", ignoreCase = true)
                ) {
                    val htmlHead = sourceFile.readText(Charsets.UTF_8).take(4000)
                    val metaDateRegex = Regex("<meta\\b[^>]*name=[\"'](?:date|dc\\.date|dcterms\\.issued|publication_date)[\"'][^>]*content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                    val dateContent = metaDateRegex.find(htmlHead)?.groupValues?.getOrNull(1).orEmpty()
                    val matchInHtml = yearRegex.find(dateContent)?.groupValues?.getOrNull(1)
                    if (!matchInHtml.isNullOrBlank()) return matchInHtml
                }
            } catch (_: Throwable) {}
        }

        // 3. Check summary or author string if it explicitly contains a year (e.g. "(1999)")
        val summaryMatch = yearRegex.find(document.summary)?.groupValues?.getOrNull(1)
        if (!summaryMatch.isNullOrBlank()) return summaryMatch

        val authorYearMatch = yearRegex.find(document.author)?.groupValues?.getOrNull(1)
        if (!authorYearMatch.isNullOrBlank()) return authorYearMatch

        return ""
    }

    /**
     * Parses the raw author string into clean individual author names (without years or brackets).
     */
    fun parseAuthorNamesList(rawAuthor: String): List<String> {
        val cleanedRaw = rawAuthor
            .replace(Regex("\\(\\s*(?:1[5-9]\\d{2}|20\\d{2})\\s*\\)"), "")
            .trim()
        if (cleanedRaw.isBlank() ||
            cleanedRaw.equals("Unknown", ignoreCase = true) ||
            cleanedRaw.equals("Unknown Author", ignoreCase = true)
        ) {
            return emptyList()
        }
        return cleanedRaw
            .split(Regex("[,;\\n]|\\band\\b", RegexOption.IGNORE_CASE))
            .map { it.trim().trim('"', '\'').removePrefix("[[").removeSuffix("]]").trim() }
            .filter {
                it.isNotBlank() &&
                    !it.equals("Unknown", ignoreCase = true) &&
                    !it.equals("Unknown Author", ignoreCase = true)
            }
    }

    /**
     * Formats the exported book or article filename following:
     * `Author(s) Name - Year - Title of Book or Article.ext`
     * Includes the published year when available; otherwise omits the year and its surrounding separator.
     * Uses the actual source extension for the book or article file.
     */
    fun formatExportedSourceFilename(document: DocumentEntity): String {
        val sourceFile = File(document.filePath)
        val rawExt = sourceFile.extension.ifBlank { document.fileType }
        val ext = when {
            rawExt.equals("epub", ignoreCase = true) -> "EPUB"
            rawExt.equals("pdf", ignoreCase = true) -> "PDF"
            rawExt.equals("html", ignoreCase = true) || rawExt.equals("htm", ignoreCase = true) || rawExt.equals("xhtml", ignoreCase = true) -> rawExt.uppercase(Locale.ROOT)
            rawExt.equals("txt", ignoreCase = true) -> "TXT"
            rawExt.isNotBlank() -> rawExt
            else -> "EPUB"
        }

        val authorsList = parseAuthorNamesList(document.author)
        val authorsPart = authorsList.joinToString(", ").let { sanitizeFilename(it) }.takeIf {
            authorsList.isNotEmpty() && it.isNotBlank() && it != "Untitled-Note"
        }
        val yearPart = extractPublishedYear(document).trim().takeIf { it.isNotBlank() }
        val titlePart = sanitizeFilename(document.title.ifBlank { "Untitled" })

        val parts = mutableListOf<String>()
        if (!authorsPart.isNullOrBlank()) parts.add(authorsPart)
        if (!yearPart.isNullOrBlank()) parts.add(yearPart)
        parts.add(titlePart)

        return "${parts.joinToString(" - ")}.$ext"
    }

    /**
     * Formats the exported WebP front-cover image filename following:
     * `Title of Book - front cover.WebP`
     */
    fun formatExportedCoverFilename(document: DocumentEntity): String {
        val cleanTitle = sanitizeFilename(document.title.ifBlank { "Book" })
        return "$cleanTitle - front cover.WebP"
    }

    /**
     * Formats the plain locator string (`Locus`) with NO Markdown or wiki link, in this exact order:
     * 1. Printed page number, if the source provides one (`p. X` or `pp. X–Y`).
     * 2. The digital source locator, such as a PDF page number (`PDF p. Y` / `PDF pp. Y–Z`)
     *    or EPUB location (`EPUB loc. Z`).
     *
     * Examples:
     * - Printed page and PDF page available: `p. 12, PDF p. 15`
     * - Printed page and EPUB location available: `p. 12, EPUB loc. 238`
     * - No printed page, PDF page available: `PDF p. 15`
     * - No printed page, EPUB location available: `EPUB loc. 238`
     */
    fun formatLocusText(highlight: HighlightEntity, document: DocumentEntity? = null): String {
        val fileType = document?.fileType?.uppercase(Locale.ROOT)
            ?: if (highlight.printedPageLabel.isNotBlank() && highlight.pageEquivalent.isBlank()) "PDF" else "EPUB"
        val isPdf = fileType == "PDF"
        val (pdfRangeStr, parsedPrintedFromPdf) = PdfToEbookParser.parseStoredPageLabels(
            primaryPdfPage = highlight.pageNumber,
            rawPrintedPageLabel = highlight.printedPageLabel
        )

        if (isPdf) {
            val rawPrinted = parsedPrintedFromPdf.trim()
            val cleanPrinted = rawPrinted
                .removePrefix("pp.")
                .removePrefix("p.")
                .removePrefix("page equivalent")
                .trim()
                .removePrefix("pp.")
                .removePrefix("p.")
                .trim()

            val printedPart = if (cleanPrinted.isNotEmpty()) {
                val isMulti = cleanPrinted.contains("–") || cleanPrinted.contains("-")
                if (isMulti) "pp. $cleanPrinted" else "p. $cleanPrinted"
            } else ""

            val digitalPart = if (pdfRangeStr.isNotBlank() && highlight.pageNumber > 0) {
                val isMultiPdf = pdfRangeStr.contains("–") || pdfRangeStr.contains("-")
                if (isMultiPdf) "PDF pp. $pdfRangeStr" else "PDF p. $pdfRangeStr"
            } else ""

            val parts = mutableListOf<String>()
            if (printedPart.isNotEmpty()) parts.add(printedPart)
            if (digitalPart.isNotEmpty()) parts.add(digitalPart)
            return parts.joinToString(", ")
        }

        if (fileType == "HTML" || fileType == "HTM" || fileType == "XHTML") {
            return if (highlight.pageNumber > 0) "HTML loc. ${highlight.pageNumber}" else ""
        }
        if (fileType == "TXT") {
            return if (highlight.pageNumber > 0) "TXT loc. ${highlight.pageNumber}" else ""
        }

        // For EPUB highlights: format printed page equivalent (if available) and EPUB location
        val cleanPage = highlight.pageEquivalent.trim()
            .removePrefix("pp.")
            .removePrefix("p.")
            .removePrefix("page equivalent")
            .trim()
            .removePrefix("pp.")
            .removePrefix("p.")
            .trim()
        val printedPart = if (cleanPage.isNotEmpty()) {
            val isMulti = cleanPage.contains("–") || cleanPage.contains("-")
            if (isMulti) "pp. $cleanPage" else "p. $cleanPage"
        } else ""

        val locMatch = Regex("EPUB\\s+loc\\.\\s*(\\d+)(?:[–\\-](\\d+))?", RegexOption.IGNORE_CASE).find(highlight.paragraphCitation)
        val sLoc = locMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: highlight.pageNumber.takeIf { it > 0 }
        val eLoc = locMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: sLoc
        val digitalPart = if (sLoc != null) {
            if (eLoc != null && eLoc > sLoc) "EPUB loc. $sLoc–$eLoc" else "EPUB loc. $sLoc"
        } else ""

        val parts = mutableListOf<String>()
        if (printedPart.isNotEmpty()) parts.add(printedPart)
        if (digitalPart.isNotEmpty()) parts.add(digitalPart)
        return parts.joinToString(", ")
    }

    private fun resolveEpubHighlightPassageCitation(
        highlight: HighlightEntity,
        document: DocumentEntity?
    ): EpubPassageCitation {
        val storedOrComputedCfi = if (highlight.epubCfi.isNotBlank() && EpubCfiEngine.isValidEpubCfi(highlight.epubCfi)) {
            EpubCfiEngine.normalizeCfi(highlight.epubCfi, includeHashPrefix = false)
        } else {
            EpubCfiEngine.getOrComputeHighlightCfi(
                highlight = highlight,
                document = document,
                includeHashPrefix = false
            )
        }
        if (document != null && document.filePath.isNotBlank() && highlight.highlightedText.isNotBlank()) {
            val sourceFile = File(document.filePath)
            if (sourceFile.exists() && sourceFile.isFile && sourceFile.extension.equals("epub", ignoreCase = true)) {
                try {
                    val book = sourceFile.inputStream().use { EpubParser.parseEpub(it) }
                    val resolved = EpubParser.resolvePassageCitation(
                        epubBook = book,
                        quote = highlight.highlightedText,
                        fallbackChapterTitle = highlight.chapterTitle
                    )
                    return if (highlight.epubCfi.isNotBlank() && EpubCfiEngine.isValidEpubCfi(highlight.epubCfi)) {
                        resolved.copy(epubCfi = EpubCfiEngine.normalizeCfi(highlight.epubCfi, includeHashPrefix = false))
                    } else if (resolved.epubCfi.isNotBlank()) {
                        resolved
                    } else {
                        resolved.copy(epubCfi = storedOrComputedCfi)
                    }
                } catch (_: Throwable) {}
            }
        }
        return EpubPassageCitation.fromStoredHighlightFields(
            chapterTitle = highlight.chapterTitle,
            paragraphCitation = highlight.paragraphCitation,
            pageEquivalent = highlight.pageEquivalent.ifBlank { highlight.printedPageLabel },
            fallbackPageNumber = highlight.pageNumber,
            epubCfi = storedOrComputedCfi
        )
    }

    /**
     * Builds a standard EPUB Canonical Fragment Identifier (EPUB CFI) selection range for a highlight,
     * following the IDPF / W3C EPUB CFI 1.1 specification (`#epubcfi(/6/S[id]!/4/E/1:start,/1:end)`
     * or `#epubcfi(/6/S[id]!/4,/B1/1:start,/B2/1:end)` for multi-paragraph selections):
     * - `/6` addresses the `<spine>` in the EPUB Package Document (`.opf`).
     * - `/S[chapterId]` addresses the spine `<itemref>` using its even step index (`(chapterIndex + 1) * 2`).
     * - `!/4` steps into the referenced XHTML content document's `<body>` element.
     * - `/B/1:offset` addresses the target block element (`B = paragraphNum * 2`) and character offset in its text node (`/1:offset`).
     * - When the source EPUB file is available on disk, inspects the actual spine and chapter text to compute exact spine item IDs, element steps, and character start/end offsets; otherwise deterministically computes the standard EPUB CFI range from the highlight's chapter, paragraph citation, and quote text.
     */
    fun formatStandardEpubSelectionCfi(
        highlight: HighlightEntity,
        document: DocumentEntity
    ): String {
        if (highlight.epubCfi.isNotBlank() && EpubCfiEngine.isValidEpubCfi(highlight.epubCfi)) {
            return EpubCfiEngine.normalizeCfi(highlight.epubCfi, includeHashPrefix = true)
        }
        return EpubCfiEngine.getOrComputeHighlightCfi(
            highlight = highlight,
            document = document,
            includeHashPrefix = true
        )
    }

    /**
     * Builds the wiki/markdown link to the highlight in the source file (`[[target|display text]]`).
     * - For EPUB files: uses the standard W3C / IDPF EPUB CFI selection range format
     *   `#epubcfi(/6/S!/4/B,/1:start,/1:end)`.
     * - For PDF files: uses the standard PDF page fragment `#page=Y`.
     * - For HTML/XHTML files: uses the standard W3C Text Fragment `#:~:text=...`.
     */
    fun formatSourceHighlightWikiLink(
        highlight: HighlightEntity,
        document: DocumentEntity,
        displayText: String = "Link to highlight",
        wikiLinkStyle: Boolean = true
    ): String {
        val sourceFilename = formatExportedSourceFilename(document)
        val fileType = document.fileType.uppercase(Locale.ROOT)
        val isPdf = fileType == "PDF" || sourceFilename.endsWith(".PDF", ignoreCase = true)
        val isHtml = fileType == "HTML" || fileType == "HTM" || fileType == "XHTML" ||
            sourceFilename.endsWith(".HTML", ignoreCase = true) || sourceFilename.endsWith(".HTM", ignoreCase = true)
        val isTxt = fileType == "TXT" || sourceFilename.endsWith(".TXT", ignoreCase = true)

        val anchorFragment = when {
            isPdf -> {
                val (pdfRangeStr, _) = PdfToEbookParser.parseStoredPageLabels(
                    primaryPdfPage = highlight.pageNumber,
                    rawPrintedPageLabel = highlight.printedPageLabel
                )
                "#page=${pdfRangeStr.substringBefore("–").substringBefore("-").ifBlank { highlight.pageNumber.toString() }}"
            }
            isHtml -> {
                val snippet = highlight.highlightedText.trim().take(64).replace(" ", "%20")
                if (snippet.isNotBlank()) "#:~:text=$snippet" else "#loc-${highlight.pageNumber}"
            }
            isTxt -> {
                if (highlight.pageNumber > 0) "#line=${highlight.pageNumber}" else ""
            }
            else -> {
                formatStandardEpubSelectionCfi(highlight, document)
            }
        }
        val target = "$sourceFilename$anchorFragment"
        return if (wikiLinkStyle) {
            "[[$target|$displayText]]"
        } else {
            "[$displayText]($target)"
        }
    }

    /**
     * Formats the chapter heading for the Notes on X overview file:
     * - Preserves `# Chapter X: Chapter name` when the chapter title already starts with `Chapter`,
     *   or formats it cleanly as `# <Chapter title>` without fabricating chapter numbers.
     */
    fun formatOverviewChapterHeading(rawChapterTitle: String): String {
        val clean = rawChapterTitle.trim().removePrefix("#").trim()
        if (clean.isBlank()) return ""
        val normalized = Regex("^(Chapter\\s+\\d+)\\s+(?!:)(.+)$", RegexOption.IGNORE_CASE).replace(clean) { m ->
            "${m.groupValues[1]}: ${m.groupValues[2]}"
        }
        return "# $normalized"
    }

    /**
     * Formats the bold chapter/location attribution line in an individual note's quote callout:
     * - For EPUB highlights: writes the location line using `obsidianLocus()`, replacing the section-only location.
     * - For PDF highlights: unchanged.
     */
    fun formatIndividualNoteQuoteFooterLine(
        highlight: HighlightEntity,
        document: DocumentEntity,
        settings: SettingsEntity
    ): String {
        val fileType = document.fileType.uppercase(Locale.ROOT)
        val isPdf = fileType == "PDF"
        val highlightLink = formatSourceHighlightWikiLink(
            highlight = highlight,
            document = document,
            displayText = "Link to highlight",
            wikiLinkStyle = settings.wikiLinkStyle
        )


        val rawParaCite = highlight.paragraphCitation.trim()
        val rawChapter = highlight.chapterTitle.trim()

        // Only use chapter/paragraph details that actually exist on the highlight; never invent "¶ 1" if missing
        val chapterAndPara = when {
            rawParaCite.isNotBlank() -> rawParaCite
            rawChapter.isNotBlank() -> rawChapter
            else -> ""
        }
        val locus = formatLocusText(highlight, document)

        val locationSegments = mutableListOf<String>()
        if (chapterAndPara.isNotEmpty()) {
            locationSegments.add(chapterAndPara)
        }
        if (locus.isNotEmpty() && !chapterAndPara.contains(locus, ignoreCase = true)) {
            locationSegments.add(locus)
        }

        return if (locationSegments.isNotEmpty()) {
            ">** – ${locationSegments.joinToString(", ")}** $highlightLink"
        } else if (!isPdf && highlight.pageEquivalent.isNotBlank()) {
            ">** – p. ${highlight.pageEquivalent.trim()}** $highlightLink"
        } else {
            ">$highlightLink"
        }
    }

    /**
     * Splits and formats authors as a YAML list of quoted wikilinks:
     * Author(s):
     *   - "[[Margaret Diane LeCompte]]"
     *   - "[[Jean J. Schensul]]"
     * Returns empty string if no author is available so unavailable details are omitted.
     */
    fun formatAuthorsFrontmatter(rawAuthor: String): String {
        val validAuthors = parseAuthorNamesList(rawAuthor)
        if (validAuthors.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("Author(s):")
        for (author in validAuthors) {
            sb.appendLine("  - \"[[$author]]\"")
        }
        return sb.toString().trimEnd()
    }

    /**
     * Generates Markdown content for an individual highlight note with YAML frontmatter:
     * ```
     * ---
     * title: Title of Note
     * From Book: "[[Author Name - Year - Book Title.EPUB]]"
     * Author(s):
     *   - "[[Name Nameson]]"
     * Locus: "p. X, PDF p. Y"
     * tags:
     *   - Literature
     *   - Book_note
     * ---
     *
     * > [!quote]
     * > ```
     * >"highlighted text"
     * >```
     * >
     * >** – Chapter X My Journey, ¶ X** [[Link to selection in the EPUB, HTML, PDF etc.|Link to highlight]]
     *
     * # My Notes:
     *
     * ---
     * *Part of [[Notes on X]]*
     * ```
     */
    fun generateHighlightMarkdown(
        highlight: HighlightEntity,
        document: DocumentEntity,
        settings: SettingsEntity
    ): String {
        val defaultTagsList = settings.defaultTags.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val noteSpecificTags = NoteSuggestionEngine.parseTagsList(highlight.tags)
        val tagsList = LinkedHashSet<String>().apply {
            if (defaultTagsList.isEmpty()) {
                add("Literature")
            } else {
                defaultTagsList.forEach { add(it) }
            }
            if (none { it.equals("Book_note", ignoreCase = true) || it.equals("Book note", ignoreCase = true) }) {
                add("Book_note")
            }
            noteSpecificTags.forEach { tag ->
                if (none { it.equals(tag, ignoreCase = true) }) {
                    add(tag)
                }
            }
        }.toList()

        val exportedSourceFilename = formatExportedSourceFilename(document)
        val authorsYaml = formatAuthorsFrontmatter(document.author)
        val locusText = formatLocusText(highlight, document)
        val cleanNoteTitle = highlight.title.trim().ifBlank { "Untitled Note" }

        val sb = StringBuilder()
        sb.appendLine("---")
        sb.appendLine("title: $cleanNoteTitle")
        sb.appendLine("${FolderSync.NOTE_KEY}: ${highlight.id}")
        sb.appendLine("From Book: \"[[$exportedSourceFilename]]\"")
        if (authorsYaml.isNotBlank()) {
            sb.appendLine(authorsYaml)
        }
        if (locusText.isNotBlank()) {
            sb.appendLine("Locus: \"$locusText\"")
        }
        run {
            val rec = BibStore.get(document.id)
            val page = Citations.pageFor(highlight.printedPageLabel, highlight.pageEquivalent, highlight.paragraphCitation,
                highlight.pageNumber, document.fileType.equals("PDF", true))
            sb.appendLine("citekey: ${Citations.citeKey(document, rec)}")
            sb.appendLine("citation: \"${Citations.inText(Citations.defaultStyle(), document, rec, page).replace("\"", "'")}\"")
        }
        if (settings.customFrontmatterYaml.isNotBlank()) {
            sb.appendLine(settings.customFrontmatterYaml.trim())
        }
        sb.appendLine("tags:")
        tagsList.forEach { tag ->
            sb.appendLine("  - $tag")
        }
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("> [!quote] ")
        sb.appendLine("> ```")
        val cleanQuote = highlight.highlightedText.trim()
        val quotedLines = "\"$cleanQuote\"".lines()
        quotedLines.forEach { line ->
            sb.appendLine(">$line")
        }
        sb.appendLine(">```")
        sb.appendLine(">")
        sb.appendLine(formatIndividualNoteQuoteFooterLine(highlight, document, settings))
        sb.appendLine()
        sb.appendLine("# My Notes:")
        if (highlight.userNotes.isNotBlank()) {
            sb.appendLine(highlight.userNotes.trim())
        }
        sb.appendLine()
        sb.appendLine("---")
        val linkToMoc = getMocTitle(document.title, settings)
        if (settings.wikiLinkStyle) {
            sb.appendLine("*Part of [[$linkToMoc]]*")
        } else {
            sb.appendLine("*Part of [$linkToMoc](${sanitizeFilename(linkToMoc)}.md)*")
        }

        return sb.toString()
    }

    fun getMocTitle(documentTitle: String, settings: SettingsEntity): String {
        return if (settings.mocTitleFormat.contains("{{title}}")) {
            settings.mocTitleFormat.replace("{{title}}", documentTitle)
        } else {
            "Notes on $documentTitle"
        }
    }

    /**
     * Generates the Notes on X overview file (Map of Content) that links to all individual note files:
     * ```
     * ---
     * title: Notes on X
     * tags:
     *   - Literature
     *   - Overview
     * Author(s):
     *   - "[[Name Nameson]]"
     * From: "[[Author Name - Year - Book Title.EPUB]]"
     * Photo: "[[Book Title - front cover.WebP]]"
     * ---
     * # Chapter X: Chapter name
     * - [[Title of Note]] - p. X, PDF p. Y [[Link to highlight|Link to selection]]
     * ```
     */
    fun generateMocMarkdown(
        document: DocumentEntity,
        highlights: List<HighlightEntity>,
        settings: SettingsEntity
    ): String {
        val mocTitle = getMocTitle(document.title, settings)
        val defaultTagsList = settings.defaultTags.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val documentSpecificTags = NoteSuggestionEngine.parseTagsList(document.tags)
        val overviewTags = LinkedHashSet<String>().apply {
            if (defaultTagsList.isEmpty()) {
                add("Literature")
            } else {
                defaultTagsList.forEach { add(it) }
            }
            if (none { it.equals("Overview", ignoreCase = true) }) {
                add("Overview")
            }
            documentSpecificTags.forEach { tag ->
                if (none { it.equals(tag, ignoreCase = true) }) {
                    add(tag)
                }
            }
        }.toList()

        val authorsYaml = formatAuthorsFrontmatter(document.author)
        val exportedSourceFilename = formatExportedSourceFilename(document)
        val exportedCoverFilename = formatExportedCoverFilename(document)

        val sb = StringBuilder()
        sb.appendLine("---")
        sb.appendLine("title: $mocTitle")
        sb.appendLine("tags:")
        overviewTags.forEach { tag ->
            sb.appendLine("  - $tag")
        }
        if (authorsYaml.isNotBlank()) {
            sb.appendLine(authorsYaml)
        }
        sb.appendLine("From: \"[[$exportedSourceFilename]]\"")
        sb.appendLine("Photo: \"[[$exportedCoverFilename]]\"")
        val bib = BibStore.get(document.id)
        // Everything below can be edited here; the app reads the changes back (see FolderSync).
        sb.appendLine("book-title: \"${document.title.replace("\"", "'")}\"")
        sb.appendLine("category: ${if (document.category.equals("ARTICLE", true)) "article" else "book"}")
        if (bib.entryType.isNotBlank()) sb.appendLine("entry-type: ${bib.entryType}")
        sb.appendLine("citekey: ${Citations.citeKey(document, bib)}")
        BibField.entries.filter { it.bibtexKey.isNotBlank() && it != BibField.TITLE }.forEach { f ->
            val v = FolderSync.OverviewFields.frontMatterValue(f, document, bib)
            if (v.isNotBlank()) sb.appendLine("${f.bibtexKey}: \"$v\"")
        }
        sb.appendLine("${FolderSync.DOC_KEY}: ${document.id}")
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("# Reference")
        sb.appendLine()
        sb.appendLine(Citations.reference(Citations.defaultStyle(), document, bib))
        sb.appendLine()
        sb.appendLine("```bibtex")
        sb.append(Citations.bibtex(document, bib))
        sb.appendLine("```")
        sb.appendLine()

        if (document.summary.isNotBlank()) {
            sb.appendLine("# Abstract / Summary")
            sb.appendLine()
            sb.appendLine(document.summary.trim())
            if (highlights.isNotEmpty()) {
                sb.appendLine()
            }
        }

        if (highlights.isNotEmpty()) {
            val grouped = highlights.groupBy { h ->
                h.chapterTitle.trim().ifBlank {
                    h.paragraphCitation.substringBefore(", ¶").trim()
                }
            }
            var firstGroup = true
            grouped.forEach { (chapterKey, chapterHighlights) ->
                if (!firstGroup) {
                    sb.appendLine()
                }
                firstGroup = false
                if (chapterKey.isNotBlank()) {
                    sb.appendLine(formatOverviewChapterHeading(chapterKey))
                }
                chapterHighlights.forEach { h ->
                    val noteTitle = h.title.trim().ifBlank { "Untitled Note" }
                    val noteLink = if (settings.wikiLinkStyle) {
                        "[[$noteTitle]]"
                    } else {
                        "[$noteTitle](${sanitizeFilename(noteTitle)}.md)"
                    }
                    val locus = formatLocusText(h, document)
                    val highlightLink = formatSourceHighlightWikiLink(
                        highlight = h,
                        document = document,
                        displayText = "Link to highlight",
                        wikiLinkStyle = settings.wikiLinkStyle
                    )
                    val locusSegment = if (locus.isNotBlank()) " - $locus" else ""
                    sb.appendLine("- $noteLink$locusSegment $highlightLink")
                }
            }
        }

        return sb.toString()
    }

    /**
     * Resolves or generates the cover image file as a **WebP** file (`Title of Book - front cover.WebP`)
     * for a document so it can be included in bundle exports.
     */
    fun resolveOrGenerateCoverFile(
        context: Context,
        document: DocumentEntity,
        settings: SettingsEntity
    ): File? {
        try {
            var coverBitmap: android.graphics.Bitmap? = null

            // 1. Check explicit coverUri
            if (!document.coverUri.isNullOrBlank()) {
                val explicitCover = File(document.coverUri)
                if (explicitCover.exists() && explicitCover.length() > 0) {
                    coverBitmap = android.graphics.BitmapFactory.decodeFile(explicitCover.absolutePath)
                }
            }

            // 2. Check document's folder for existing WebP or JPG cover
            val docFolder = StorageManager.getDocumentFolder(
                context = context,
                category = document.category,
                documentTitle = document.title,
                customPath = settings.customStoragePath,
                storageType = settings.storageLocationType
            )
            val webpCoverName = formatExportedCoverFilename(document)
            val folderWebpCover = File(docFolder, webpCoverName)
            if (coverBitmap == null && folderWebpCover.exists() && folderWebpCover.length() > 0) {
                return folderWebpCover
            }
            val folderJpgCover = File(docFolder, "cover.jpg")
            if (coverBitmap == null && folderJpgCover.exists() && folderJpgCover.length() > 0) {
                coverBitmap = android.graphics.BitmapFactory.decodeFile(folderJpgCover.absolutePath)
            }

            // 3. Check .covers cache directory
            if (coverBitmap == null) {
                val rootDir = StorageManager.getRootDirectory(context, settings.customStoragePath, settings.storageLocationType)
                val coversDir = File(rootDir, ".covers")
                val candidateCacheNames = listOf(
                    "pdf_${document.id}.jpg",
                    "epub_${document.id}.jpg",
                    "html_${document.id}.jpg",
                    "txt_${document.id}.jpg"
                )
                for (cacheName in candidateCacheNames) {
                    val cached = File(coversDir, cacheName)
                    if (cached.exists() && cached.length() > 0) {
                        coverBitmap = android.graphics.BitmapFactory.decodeFile(cached.absolutePath)
                        if (coverBitmap != null) break
                    }
                }
            }

            // 4. Generate on the fly from the source file or title/author
            if (coverBitmap == null) {
                val sourceFile = File(document.filePath)
                val isPdf = document.fileType.equals("PDF", ignoreCase = true) ||
                    document.filePath.endsWith(".pdf", ignoreCase = true)
                coverBitmap = when {
                    isPdf && sourceFile.exists() -> {
                        val renderer = PdfDocumentRenderer(sourceFile, context)
                        val rendered = renderer.renderPage(0, targetWidth = 480)
                        renderer.close()
                        rendered ?: EpubParser.generateBookCoverBitmap(document.title, document.author)
                    }
                    document.fileType.equals("EPUB", ignoreCase = true) && sourceFile.exists() -> {
                        EpubParser.extractCoverBitmap(sourceFile, document.title, document.author)
                    }
                    else -> {
                        EpubParser.generateBookCoverBitmap(document.title, document.author)
                    }
                }
            }

            val finalBitmap = coverBitmap ?: EpubParser.generateBookCoverBitmap(document.title, document.author)
            FileOutputStream(folderWebpCover).use { out ->
                @Suppress("DEPRECATION")
                val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    android.graphics.Bitmap.CompressFormat.WEBP
                }
                finalBitmap.compress(format, 90, out)
            }
            return folderWebpCover
        } catch (e: Throwable) {
            e.printStackTrace()
            return null
        }
    }

    data class ExportBundleResult(
        val outputFileOrFolder: File,
        val isZip: Boolean,
        val includedFiles: List<String>
    )

    /**
     * Exports a complete bundle containing:
     * 1. The Notes on X overview file (`Notes on [Title].md`)
     * 2. All individual highlight `.md` files (plus `highlight.md` summary)
     * 3. The original source file named `Author(s) Name - Year - Title of Book or Article.ext`
     * 4. The book cover image file as WebP named `Title of Book - front cover.WebP`
     * as a `.zip` archive.
     */
    fun createCompleteDocumentBundleZip(
        context: Context,
        document: DocumentEntity,
        highlights: List<HighlightEntity>,
        settings: SettingsEntity
    ): ExportBundleResult {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val cleanDocTitle = sanitizeFilename(document.title)
        val zipFileName = "$cleanDocTitle-Complete-Bundle.zip"
        val zipFile = File(exportDir, zipFileName)
        val includedFiles = mutableListOf<String>()

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val usedEntryNames = mutableSetOf<String>()

            fun addTextEntry(fileName: String, content: String) {
                var finalName = fileName
                var counter = 2
                val base = fileName.substringBeforeLast(".")
                val ext = fileName.substringAfterLast(".", "md")
                while (usedEntryNames.contains(finalName.lowercase(Locale.ROOT))) {
                    finalName = "$base-$counter.$ext"
                    counter++
                }
                usedEntryNames.add(finalName.lowercase(Locale.ROOT))
                zos.putNextEntry(ZipEntry("$cleanDocTitle/$finalName"))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                includedFiles.add(finalName)
            }

            fun addBinaryFileEntry(fileName: String, sourceFile: File) {
                if (!sourceFile.exists() || !sourceFile.isFile) return
                var finalName = fileName
                var counter = 2
                val base = fileName.substringBeforeLast(".")
                val ext = fileName.substringAfterLast(".", "")
                while (usedEntryNames.contains(finalName.lowercase(Locale.ROOT))) {
                    finalName = if (ext.isNotEmpty()) "$base-$counter.$ext" else "$base-$counter"
                    counter++
                }
                usedEntryNames.add(finalName.lowercase(Locale.ROOT))
                zos.putNextEntry(ZipEntry("$cleanDocTitle/$finalName"))
                sourceFile.inputStream().use { input ->
                    input.copyTo(zos)
                }
                zos.closeEntry()
                includedFiles.add(finalName)
            }

            // 1. Write Notes on [Title].md (Overview file)
            val mocTitle = getMocTitle(document.title, settings)
            val mocFilename = "${sanitizeFilename(mocTitle)}.md"
            val mocContent = generateMocMarkdown(document, highlights, settings)
            addTextEntry(mocFilename, mocContent)

            // 2. Write each individual highlight note .md file
            highlights.forEach { highlight ->
                val noteFilename = "${sanitizeFilename(highlight.title)}.md"
                val noteContent = generateHighlightMarkdown(highlight, document, settings)
                addTextEntry(noteFilename, noteContent)
            }

            // 3. Write unified Obsidian summary note (highlight.md)
            val unifiedContent = generateUnifiedObsidianBookMarkdown(document, highlights, settings)
            addTextEntry("highlight.md", unifiedContent)

            // BibTeX and handwriting sketches
            BibStore.init(context)
            val bibRec = BibStore.get(document.id)
            addTextEntry("${Citations.citeKey(document, bibRec)}.bib", Citations.bibtex(document, bibRec))
            Sketches.filesFor(context, highlights.map { it.userNotes }).forEach { addBinaryFileEntry(it.name, it) }

            // 4. Write source file named: Author(s) Name - Year - Title of Book or Article.ext
            val sourceFile = File(document.filePath)
            if (sourceFile.exists() && sourceFile.isFile) {
                val formattedSourceName = formatExportedSourceFilename(document)
                addBinaryFileEntry(formattedSourceName, sourceFile)
            }

            // 5. Write book cover image file as WebP: Title of Book - front cover.WebP
            val coverFile = resolveOrGenerateCoverFile(context, document, settings)
            if (coverFile != null && coverFile.exists()) {
                val formattedCoverName = formatExportedCoverFilename(document)
                addBinaryFileEntry(formattedCoverName, coverFile)
            }
        }

        return ExportBundleResult(
            outputFileOrFolder = zipFile,
            isZip = true,
            includedFiles = includedFiles
        )
    }

    /**
     * Exports a complete bundle containing:
     * 1. The Notes on X overview file (`Notes on [Title].md`)
     * 2. All individual highlight `.md` files (plus `highlight.md` summary)
     * 3. The original source file named `Author(s) Name - Year - Title of Book or Article.ext`
     * 4. The book cover image file as WebP named `Title of Book - front cover.WebP`
     * as a regular folder on disk.
     */
    fun exportCompleteDocumentBundleToFolder(
        context: Context,
        document: DocumentEntity,
        highlights: List<HighlightEntity>,
        settings: SettingsEntity,
        targetParentFolder: File? = null
    ): ExportBundleResult {
        val cleanDocTitle = sanitizeFilename(document.title)
        val baseDir = targetParentFolder ?: File(
            StorageManager.getRootDirectory(context, settings.customStoragePath, settings.storageLocationType),
            "Exports"
        ).apply { mkdirs() }

        val targetFolder = File(baseDir, cleanDocTitle).apply { mkdirs() }
        // Also keep the document's own library folder updated with all exported notes and cover
        val libraryDocFolder = StorageManager.getDocumentFolder(
            context = context,
            category = document.category,
            documentTitle = document.title,
            customPath = settings.customStoragePath,
            storageType = settings.storageLocationType
        )

        val includedFiles = mutableListOf<String>()
        val usedFileNames = mutableSetOf<String>()

        fun writeTextToBothFolders(fileName: String, content: String) {
            var finalName = fileName
            var counter = 2
            val base = fileName.substringBeforeLast(".")
            val ext = fileName.substringAfterLast(".", "md")
            while (usedFileNames.contains(finalName.lowercase(Locale.ROOT))) {
                finalName = "$base-$counter.$ext"
                counter++
            }
            usedFileNames.add(finalName.lowercase(Locale.ROOT))

            File(targetFolder, finalName).writeText(content, Charsets.UTF_8)
            if (libraryDocFolder.absolutePath != targetFolder.absolutePath) {
                try {
                    File(libraryDocFolder, finalName).writeText(content, Charsets.UTF_8)
                } catch (_: Exception) {}
            }
            includedFiles.add(finalName)
        }

        fun copyBinaryToFolder(fileName: String, sourceFile: File, alsoCopyToLibraryFolder: Boolean = true) {
            if (!sourceFile.exists() || !sourceFile.isFile) return
            var finalName = fileName
            var counter = 2
            val base = fileName.substringBeforeLast(".")
            val ext = fileName.substringAfterLast(".", "")
            while (usedFileNames.contains(finalName.lowercase(Locale.ROOT))) {
                finalName = if (ext.isNotEmpty()) "$base-$counter.$ext" else "$base-$counter"
                counter++
            }
            usedFileNames.add(finalName.lowercase(Locale.ROOT))

            val destInTarget = File(targetFolder, finalName)
            if (sourceFile.absolutePath != destInTarget.absolutePath) {
                sourceFile.copyTo(destInTarget, overwrite = true)
            }
            if (alsoCopyToLibraryFolder) {
                val destInLibrary = File(libraryDocFolder, finalName)
                if (sourceFile.absolutePath != destInLibrary.absolutePath) {
                    try {
                        sourceFile.copyTo(destInLibrary, overwrite = true)
                    } catch (_: Exception) {}
                }
            }
            includedFiles.add(finalName)
        }

        // 1. Write Notes on [Title].md (Overview file)
        val mocTitle = getMocTitle(document.title, settings)
        val mocFilename = "${sanitizeFilename(mocTitle)}.md"
        val mocContent = generateMocMarkdown(document, highlights, settings)
        writeTextToBothFolders(mocFilename, mocContent)

        // 2. Write each individual highlight note .md file
        highlights.forEach { highlight ->
            val noteFilename = "${sanitizeFilename(highlight.title)}.md"
            val noteContent = generateHighlightMarkdown(highlight, document, settings)
            writeTextToBothFolders(noteFilename, noteContent)
        }

        // 3. Write unified Obsidian summary note (highlight.md)
        val unifiedContent = generateUnifiedObsidianBookMarkdown(document, highlights, settings)
        writeTextToBothFolders("highlight.md", unifiedContent)

        // BibTeX and handwriting sketches
        BibStore.init(context)
        val bibRec = BibStore.get(document.id)
        writeTextToBothFolders("${Citations.citeKey(document, bibRec)}.bib", Citations.bibtex(document, bibRec))
        Sketches.filesFor(context, highlights.map { it.userNotes }).forEach { copyBinaryToFolder(it.name, it, alsoCopyToLibraryFolder = true) }

        // 4. Copy source file named: Author(s) Name - Year - Title of Book or Article.ext (only into the exported bundle folder)
        val sourceFile = File(document.filePath)
        if (sourceFile.exists() && sourceFile.isFile) {
            val formattedSourceName = formatExportedSourceFilename(document)
            copyBinaryToFolder(formattedSourceName, sourceFile, alsoCopyToLibraryFolder = false)
        }

        // 5. Copy book cover image file as WebP: Title of Book - front cover.WebP
        val coverFile = resolveOrGenerateCoverFile(context, document, settings)
        if (coverFile != null && coverFile.exists()) {
            val formattedCoverName = formatExportedCoverFilename(document)
            copyBinaryToFolder(formattedCoverName, coverFile, alsoCopyToLibraryFolder = true)
        }

        return ExportBundleResult(
            outputFileOrFolder = targetFolder,
            isZip = false,
            includedFiles = includedFiles
        )
    }

    /**
     * Creates a ZIP file containing the Notes on X overview file (`Notes on [Title].md`),
     * all individual highlight markdown files, and the WebP front-cover image.
     */
    fun createVaultZip(
        context: Context,
        document: DocumentEntity,
        highlights: List<HighlightEntity>,
        settings: SettingsEntity
    ): File {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val zipFileName = "${sanitizeFilename(document.title)}-PKM-Vault.zip"
        val zipFile = File(exportDir, zipFileName)

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            // 1. Write Notes on X Overview File
            val mocTitle = getMocTitle(document.title, settings)
            val mocContent = generateMocMarkdown(document, highlights, settings)
            val mocEntry = ZipEntry("${sanitizeFilename(mocTitle)}.md")
            zos.putNextEntry(mocEntry)
            zos.write(mocContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 2. Write each highlight note as an individual .md file
            val usedFilenames = mutableSetOf<String>()
            highlights.forEach { highlight ->
                val baseName = sanitizeFilename(highlight.title)
                var filename = "$baseName.md"
                var counter = 2
                while (usedFilenames.contains(filename.lowercase(Locale.ROOT))) {
                    filename = "$baseName-$counter.md"
                    counter++
                }
                usedFilenames.add(filename.lowercase(Locale.ROOT))

                val content = generateHighlightMarkdown(highlight, document, settings)
                val entry = ZipEntry(filename)
                zos.putNextEntry(entry)
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            // BibTeX and handwriting sketches
            BibStore.init(context)
            val bibRec = BibStore.get(document.id)
            zos.putNextEntry(ZipEntry("${Citations.citeKey(document, bibRec)}.bib"))
            zos.write(Citations.bibtex(document, bibRec).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            Sketches.filesFor(context, highlights.map { it.userNotes }).forEach { sketch ->
                zos.putNextEntry(ZipEntry(sketch.name))
                sketch.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }

            // 3. Include WebP front cover image
            val coverFile = resolveOrGenerateCoverFile(context, document, settings)
            if (coverFile != null && coverFile.exists()) {
                val formattedCoverName = formatExportedCoverFilename(document)
                zos.putNextEntry(ZipEntry(formattedCoverName))
                coverFile.inputStream().use { input ->
                    input.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
        return zipFile
    }

    /**
     * Exports a single highlight as a .md file.
     */
    fun createSingleHighlightFile(
        context: Context,
        highlight: HighlightEntity,
        document: DocumentEntity,
        settings: SettingsEntity
    ): File {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val filename = "${sanitizeFilename(highlight.title)}.md"
        val file = File(exportDir, filename)
        file.writeText(generateHighlightMarkdown(highlight, document, settings), Charsets.UTF_8)
        return file
    }

    /**
     * Exports the MOC as a .md file.
     */
    fun createMocFile(
        context: Context,
        document: DocumentEntity,
        highlights: List<HighlightEntity>,
        settings: SettingsEntity
    ): File {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val mocTitle = getMocTitle(document.title, settings)
        val filename = "${sanitizeFilename(mocTitle)}.md"
        val file = File(exportDir, filename)
        file.writeText(generateMocMarkdown(document, highlights, settings), Charsets.UTF_8)
        return file
    }

    /**
     * Generates a complete, single Obsidian-flavored Markdown note for the entire book.
     * Includes YAML frontmatter, book metadata callout, and all highlights formatted with Obsidian callouts.
     */
    fun generateUnifiedObsidianBookMarkdown(
        document: DocumentEntity,
        highlights: List<HighlightEntity>,
        settings: SettingsEntity
    ): String {
        val dateStr = dateFormat.format(Date(System.currentTimeMillis()))
        val isoStr = isoFormat.format(Date(System.currentTimeMillis()))
        val tagsList = settings.defaultTags.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        val documentSpecificTags = NoteSuggestionEngine.parseTagsList(document.tags)
        documentSpecificTags.forEach { tag ->
            if (tagsList.none { it.equals(tag, ignoreCase = true) }) {
                tagsList.add(tag)
            }
        }

        if (!tagsList.contains("koreader")) tagsList.add("koreader")
        if (!tagsList.contains("literature-notes")) tagsList.add("literature-notes")

        val progressPercent = if (document.totalPages > 0) {
            String.format(Locale.US, "%.1f%%", (document.currentPage.toFloat() / document.totalPages) * 100f)
        } else "0%"

        val sb = StringBuilder()
        sb.appendLine("---")
        sb.appendLine("title: \"${document.title.replace("\"", "\\\"")}\"")
        val authorsYaml = formatAuthorsFrontmatter(document.author)
        if (authorsYaml.isNotBlank()) {
            sb.appendLine(authorsYaml)
        }
        sb.appendLine("category: \"${document.category.lowercase()}\"")
        sb.appendLine("file_type: \"${document.fileType.lowercase()}\"")
        sb.appendLine("total_pages: ${document.totalPages}")
        sb.appendLine("current_page: ${document.currentPage}")
        sb.appendLine("progress: \"$progressPercent\"")
        sb.appendLine("total_highlights: ${highlights.size}")
        sb.appendLine("source: \"KOReader\"")
        sb.appendLine("date: $dateStr")
        sb.appendLine("created: \"$isoStr\"")
        if (settings.customFrontmatterYaml.isNotBlank()) {
            sb.appendLine(settings.customFrontmatterYaml.trim())
        }
        sb.appendLine("tags:")
        tagsList.forEach { tag ->
            sb.appendLine("  - $tag")
        }
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("# [[${document.title}]]")
        sb.appendLine()
        sb.appendLine("> [!abstract] Book Metadata")
        sb.appendLine("> - **Author:** ${document.author}")
        sb.appendLine("> - **Format:** ${document.fileType.uppercase()} • ${document.category}")
        sb.appendLine("> - **Reading Progress:** Page ${document.currentPage} of ${document.totalPages} ($progressPercent)")
        sb.appendLine("> - **Total Annotations:** ${highlights.size} highlights & notes")
        sb.appendLine("> - **Export Source:** KOReader Workflow")
        sb.appendLine()

        if (document.summary.isNotBlank()) {
            sb.appendLine("> [!summary] Reading Summary")
            sb.appendLine("> ${document.summary.trim().replace("\n", "\n> ")}")
            sb.appendLine()
        }

        sb.appendLine("## Highlights & Annotations")
        sb.appendLine()

        if (highlights.isEmpty()) {
            sb.appendLine("*No highlights recorded in KOReader yet.*")
        } else {
            val isPdf = document.fileType.equals("PDF", ignoreCase = true)
            // Group highlights by chapter / section
            val grouped = highlights.groupBy { it.chapterTitle.ifBlank { "General Notes" } }
            grouped.forEach { (chapter, chHighlights) ->
                sb.appendLine("### $chapter")
                sb.appendLine()

                chHighlights.forEach { h ->
                    val pageStr = formatHighlightPageDisplay(h, isPdf)
                    val headerDetail = if (pageStr.isNotBlank()) "Highlight ($pageStr)" else "Highlight"
                    val noteTags = NoteSuggestionEngine.parseTagsList(h.tags)
                    sb.appendLine("> [!quote] $headerDetail")
                    sb.appendLine("> \"${h.highlightedText.trim().replace("\n", "\n> ")}\"")
                    if (pageStr.isNotBlank()) {
                        sb.appendLine(">")
                        sb.appendLine("> — *$pageStr*")
                    }
                    if (noteTags.isNotEmpty()) {
                        sb.appendLine(">")
                        sb.appendLine("> ${noteTags.joinToString(" ") { "#$it" }}")
                    }
                    sb.appendLine()

                    if (h.userNotes.isNotBlank()) {
                        sb.appendLine("> [!note] ${h.title}")
                        sb.appendLine("> ${h.userNotes.trim().replace("\n", "\n> ")}")
                        sb.appendLine()
                    }

                    sb.appendLine("---")
                    sb.appendLine()
                }
            }
        }

        val mocTitle = getMocTitle(document.title, settings)
        sb.appendLine("*Exported from KOReader • Linked to [[$mocTitle]]*")
        return sb.toString()
    }

    /**
     * Exports a complete unified Obsidian note file for the document.
     */
    fun createUnifiedBookFile(
        context: Context,
        document: DocumentEntity,
        highlights: List<HighlightEntity>,
        settings: SettingsEntity
    ): File {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val filename = "${sanitizeFilename(document.title)}.md"
        val file = File(exportDir, filename)
        file.writeText(generateUnifiedObsidianBookMarkdown(document, highlights, settings), Charsets.UTF_8)
        return file
    }

    /**
     * Copies text directly to Android system clipboard.
     */
    fun copyToClipboard(context: Context, text: String, label: String = "Obsidian Markdown") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    /**
     * Saves the Notes on X overview file, all individual highlight note `.md` files,
     * the WebP front cover image, and the unified summary directly into the designated storage or vault path.
     */
    fun saveToObsidianVault(
        context: Context,
        document: DocumentEntity,
        highlights: List<HighlightEntity>,
        settings: SettingsEntity
    ): File {
        val docFolder = StorageManager.getDocumentFolder(
            context = context,
            category = document.category,
            documentTitle = document.title,
            customPath = settings.customStoragePath,
            storageType = settings.storageLocationType
        )
        // 1. Write Notes on X overview file
        val mocTitle = getMocTitle(document.title, settings)
        val mocFile = File(docFolder, "${sanitizeFilename(mocTitle)}.md")
        mocFile.writeText(generateMocMarkdown(document, highlights, settings), Charsets.UTF_8)

        // 2. Write each individual note file
        val usedFilenames = mutableSetOf<String>()
        highlights.forEach { highlight ->
            val baseName = sanitizeFilename(highlight.title)
            var filename = "$baseName.md"
            var counter = 2
            while (usedFilenames.contains(filename.lowercase(Locale.ROOT))) {
                filename = "$baseName-$counter.md"
                counter++
            }
            usedFilenames.add(filename.lowercase(Locale.ROOT))
            File(docFolder, filename).writeText(generateHighlightMarkdown(highlight, document, settings), Charsets.UTF_8)
        }

        // 3. Ensure WebP cover image exists in folder
        resolveOrGenerateCoverFile(context, document, settings)

        // 4. Also keep unified note file updated
        val content = generateUnifiedObsidianBookMarkdown(document, highlights, settings)
        val targetFile = File(docFolder, "${sanitizeFilename(document.title)}.md")
        targetFile.writeText(content, Charsets.UTF_8)
        return mocFile
    }

    /**
     * Resolves the MIME type for a document's source file based on its extension or fileType.
     */
    fun getMimeTypeForSourceFile(document: DocumentEntity): String {
        val ext = File(document.filePath).extension.ifBlank { document.fileType }.lowercase(Locale.ROOT)
        return when (ext) {
            "pdf" -> "application/pdf"
            "epub" -> "application/epub+zip"
            "daisy", "zip" -> "application/zip"
            "html", "htm" -> "text/html"
            "xhtml" -> "application/xhtml+xml"
            "md", "markdown" -> "text/markdown"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /**
     * Prepares the original source file (EPUB/PDF/HTML/DAISY/MD/TXT) in the exports cache directory
     * with a clean exported filename so it can be reliably shared via FileProvider.
     */
    fun prepareSourceFileForSharing(
        context: Context,
        document: DocumentEntity
    ): File {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val exportedName = formatExportedSourceFilename(document)
        val outFile = File(exportDir, exportedName)
        val sourceFile = File(document.filePath)
        if (sourceFile.exists() && sourceFile.isFile && sourceFile.length() > 0L) {
            if (sourceFile.absolutePath != outFile.absolutePath) {
                sourceFile.copyTo(outFile, overwrite = true)
            }
        } else {
            val fallbackText = buildString {
                appendLine("# ${document.title}")
                appendLine("Author: ${document.author}")
                if (document.summary.isNotBlank()) {
                    appendLine()
                    appendLine(document.summary)
                }
            }
            outFile.writeText(fallbackText, Charsets.UTF_8)
        }
        return outFile
    }

    /**
     * Generates individual `.md` files for a list of selected highlights.
     */
    fun createMultipleHighlightFiles(
        context: Context,
        highlights: List<HighlightEntity>,
        document: DocumentEntity,
        settings: SettingsEntity
    ): List<File> {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val usedFilenames = mutableSetOf<String>()
        return highlights.map { highlight ->
            val baseName = sanitizeFilename(highlight.title)
            var filename = "$baseName.md"
            var counter = 2
            while (usedFilenames.contains(filename.lowercase(Locale.ROOT))) {
                filename = "$baseName-$counter.md"
                counter++
            }
            usedFilenames.add(filename.lowercase(Locale.ROOT))
            val file = File(exportDir, filename)
            file.writeText(generateHighlightMarkdown(highlight, document, settings), Charsets.UTF_8)
            file
        }
    }

    /**
     * Generates all individual note `.md` files and the Map of Content (`Notes on [Title].md`) file,
     * explicitly leaving out the source document file (EPUB/PDF/HTML/etc.) and the cover image file.
     */
    fun createAllNotesAndMocFilesOnly(
        context: Context,
        document: DocumentEntity,
        highlights: List<HighlightEntity>,
        settings: SettingsEntity
    ): List<File> {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val files = mutableListOf<File>()
        val usedFilenames = mutableSetOf<String>()

        // 1. Map of Content (MOC) file
        val mocTitle = getMocTitle(document.title, settings)
        val mocFilename = "${sanitizeFilename(mocTitle)}.md"
        usedFilenames.add(mocFilename.lowercase(Locale.ROOT))
        val mocFile = File(exportDir, mocFilename)
        mocFile.writeText(generateMocMarkdown(document, highlights, settings), Charsets.UTF_8)
        files.add(mocFile)

        // 2. All individual highlight note .md files
        highlights.forEach { highlight ->
            val baseName = sanitizeFilename(highlight.title)
            var filename = "$baseName.md"
            var counter = 2
            while (usedFilenames.contains(filename.lowercase(Locale.ROOT))) {
                filename = "$baseName-$counter.md"
                counter++
            }
            usedFilenames.add(filename.lowercase(Locale.ROOT))
            val noteFile = File(exportDir, filename)
            noteFile.writeText(generateHighlightMarkdown(highlight, document, settings), Charsets.UTF_8)
            files.add(noteFile)
        }

        return files
    }

    /**
     * Creates a `.zip` archive containing ONLY all individual note `.md` files and the Map of Content
     * (`Notes on [Title].md`) file — explicitly leaving out the source document file (EPUB/PDF/HTML/etc.)
     * and the cover image file.
     */
    fun createAllNotesAndMocOnlyZip(
        context: Context,
        document: DocumentEntity,
        highlights: List<HighlightEntity>,
        settings: SettingsEntity
    ): File {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val cleanDocTitle = sanitizeFilename(document.title)
        val zipFile = File(exportDir, "$cleanDocTitle-Notes-and-MOC.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val usedFilenames = mutableSetOf<String>()

            // 1. Write Map of Content (Notes on [Title].md)
            val mocTitle = getMocTitle(document.title, settings)
            val mocFilename = "${sanitizeFilename(mocTitle)}.md"
            usedFilenames.add(mocFilename.lowercase(Locale.ROOT))
            zos.putNextEntry(ZipEntry(mocFilename))
            zos.write(generateMocMarkdown(document, highlights, settings).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 2. Write each highlight note as an individual .md file (NO source file, NO cover image)
            highlights.forEach { highlight ->
                val baseName = sanitizeFilename(highlight.title)
                var filename = "$baseName.md"
                var counter = 2
                while (usedFilenames.contains(filename.lowercase(Locale.ROOT))) {
                    filename = "$baseName-$counter.md"
                    counter++
                }
                usedFilenames.add(filename.lowercase(Locale.ROOT))
                val content = generateHighlightMarkdown(highlight, document, settings)
                zos.putNextEntry(ZipEntry(filename))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            // 3. BibTeX and handwriting sketches
            BibStore.init(context)
            val bibRec = BibStore.get(document.id)
            zos.putNextEntry(ZipEntry("${Citations.citeKey(document, bibRec)}.bib"))
            zos.write(Citations.bibtex(document, bibRec).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            Sketches.filesFor(context, highlights.map { it.userNotes }).forEach { sketch ->
                zos.putNextEntry(ZipEntry(sketch.name))
                sketch.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zipFile
    }

    /**
     * Opens Android share sheet for a single generated file (ZIP, MD, EPUB, PDF, HTML, etc.).
     */
    fun shareFile(context: Context, file: File, mimeType: String, chooserTitle: String) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /**
     * Opens Android share sheet for one or multiple files (using ACTION_SEND for 1 file
     * or ACTION_SEND_MULTIPLE for multiple files).
     */
    fun shareMultipleFiles(
        context: Context,
        files: List<File>,
        mimeType: String = "text/markdown",
        chooserTitle: String
    ) {
        if (files.isEmpty()) return
        if (files.size == 1) {
            shareFile(context, files.first(), mimeType, chooserTitle)
            return
        }

        val uris = ArrayList<Uri>(files.size)
        var clip: ClipData? = null
        files.forEach { file ->
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            uris.add(uri)
            if (clip == null) {
                clip = ClipData.newRawUri(file.name, uri)
            } else {
                clip?.addItem(ClipData.Item(uri))
            }
        }

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            if (clip != null) {
                clipData = clip
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
