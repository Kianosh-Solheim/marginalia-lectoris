package com.example.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Classifies reconstructed structural blocks in the PDF-to-Ebook reflow representation.
 */
enum class PdfReflowBlockType {
    CHAPTER_HEADING,
    SUBHEADING,
    PARAGRAPH,
    BLOCKQUOTE,
    LIST_ITEM,
    FOOTNOTE,
    UNSUPPORTED_ELEMENT
}

/**
 * Classifies content from a PDF page that cannot be reliably converted into reflowable prose
 * without losing structure or meaning (Core Requirement 3).
 */
enum class PdfUnsupportedReason(val displayTitle: String) {
    SCANNED_OR_IMAGE_ONLY_PAGE("Scanned / Image-Only Page"),
    COMPLEX_TABLE("Table / Tabular Data"),
    MATH_EQUATION_OR_FORMULA("Mathematical Equation / Formula"),
    CHART_OR_DIAGRAM_OR_IMAGE("Figure, Chart, or Diagram"),
    COMPLEX_MULTICOLUMN_OR_OVERLAPPING("Complex Multi-Column / Non-Standard Layout"),
    UNLINKED_MARGINAL_OR_FOOTNOTE("Marginal / Unlinked Note"),
    INTERACTIVE_FORM_OR_MEDIA("Interactive PDF Form / Element")
}

/**
 * A single reconstructed block in the PDF-to-Ebook reading view, anchored directly back
 * to its source PDF page(s) (1-based) and printed book page number(s) (Arabic or Roman).
 */
data class PdfReflowBlock(
    val id: String,
    val type: PdfReflowBlockType,
    val text: String,
    val chapterTitle: String,
    val startPdfPage: Int,          // 1-based PDF page number
    val endPdfPage: Int,            // 1-based PDF page number (>= startPdfPage)
    val startPrintedPage: String,   // e.g. "14" or "xiv" or ""
    val endPrintedPage: String,     // e.g. "15" or "xv" or ""
    val unsupportedReason: PdfUnsupportedReason? = null,
    val cropRectNormalized: RectF? = null, // Normalized [0..1] crop region on startPdfPage for visual snapshot
    val footnotes: Map<String, String> = emptyMap()
) {
    val spansMultiplePdfPages: Boolean
        get() = endPdfPage > startPdfPage

    val spansMultiplePrintedPages: Boolean
        get() = startPrintedPage.isNotBlank() &&
            endPrintedPage.isNotBlank() &&
            !startPrintedPage.equals(endPrintedPage, ignoreCase = true)

    fun formattedPdfPageRange(): String {
        return if (endPdfPage > startPdfPage) "$startPdfPage–$endPdfPage" else "$startPdfPage"
    }

    fun formattedPrintedPageRange(): String {
        val s = startPrintedPage.trim()
        val e = endPrintedPage.trim()
        return when {
            s.isNotEmpty() && e.isNotEmpty() && !s.equals(e, ignoreCase = true) -> "$s–$e"
            s.isNotEmpty() -> s
            e.isNotEmpty() -> e
            else -> ""
        }
    }
}

/**
 * Holds the complete PDF-to-Ebook parsed document model along with bidirectional page/text anchors
 * so the Original PDF view and the Reflowable Ebook view stay 100% synchronised.
 */
data class PdfEbookDocument(
    val title: String,
    val author: String,
    val totalPdfPages: Int,
    val blocks: List<PdfReflowBlock>,
    val printedPageByPdfPage: Map<Int, String>, // 1-based PDF page -> printed page label
    val removedRunningHeadersFooters: List<String>,
    val epubBook: EpubBook
) {
    /**
     * Resolves the exact PDF page range and printed page range for any selected quote
     * or highlight text (including highlights that span across a page break!).
     */
    fun resolvePageSpanForQuote(
        quote: String,
        fallbackPdfPage: Int = 1
    ): PdfQuotePageSpan {
        val cleanQuote = quote.trim()
        if (cleanQuote.isEmpty() || blocks.isEmpty()) {
            val fallbackPrinted = printedPageByPdfPage[fallbackPdfPage] ?: ""
            return PdfQuotePageSpan(
                startPdfPage = fallbackPdfPage,
                endPdfPage = fallbackPdfPage,
                startPrintedPage = fallbackPrinted,
                endPrintedPage = fallbackPrinted
            )
        }

        val normalizedQuoteTokens = tokenizeNormalized(cleanQuote)
        val matchedBlocks = mutableListOf<PdfReflowBlock>()

        // 1. Check blocks that overlap with the quote text
        val quoteParagraphs = cleanQuote
            .split(Regex("\\n+"))
            .map { it.removePrefix("## ").trim() }
            .filter { it.isNotEmpty() }

        for (block in blocks) {
            if (block.type == PdfReflowBlockType.UNSUPPORTED_ELEMENT && !cleanQuote.contains(block.text, ignoreCase = true)) {
                continue
            }
            val blockClean = block.text.removePrefix("## ").trim()
            if (blockClean.isEmpty()) continue

            val directHit = blockClean.contains(cleanQuote, ignoreCase = true) ||
                cleanQuote.contains(blockClean, ignoreCase = true) ||
                quoteParagraphs.any { seg ->
                    seg.length >= 8 && (blockClean.contains(seg, ignoreCase = true) || seg.contains(blockClean, ignoreCase = true))
                }

            if (directHit) {
                matchedBlocks.add(block)
                continue
            }

            // Check prefix/suffix overlap when a single paragraph was split across pages or partially selected
            if (normalizedQuoteTokens.size >= 3) {
                val blockTokens = tokenizeNormalized(blockClean)
                if (blockTokens.size >= 3 && hasSignificantTokenOverlap(normalizedQuoteTokens, blockTokens)) {
                    matchedBlocks.add(block)
                }
            }
        }

        if (matchedBlocks.isEmpty()) {
            val fallbackBlock = blocks.firstOrNull { fallbackPdfPage in it.startPdfPage..it.endPdfPage }
            val pStart = fallbackBlock?.startPdfPage ?: fallbackPdfPage
            val pEnd = fallbackBlock?.endPdfPage ?: fallbackPdfPage
            val prStart = fallbackBlock?.startPrintedPage?.ifBlank { printedPageByPdfPage[pStart] ?: "" }
                ?: (printedPageByPdfPage[pStart] ?: "")
            val prEnd = fallbackBlock?.endPrintedPage?.ifBlank { printedPageByPdfPage[pEnd] ?: prStart }
                ?: (printedPageByPdfPage[pEnd] ?: prStart)
            return PdfQuotePageSpan(
                startPdfPage = pStart,
                endPdfPage = pEnd,
                startPrintedPage = prStart,
                endPrintedPage = prEnd
            )
        }

        val minPdfPage = matchedBlocks.minOf { it.startPdfPage }
        val maxPdfPage = matchedBlocks.maxOf { it.endPdfPage }

        // If a single block itself spans two PDF pages (a cross-page merged paragraph),
        // determine whether the selected quote actually touches both pages or only one page!
        if (matchedBlocks.size == 1 && matchedBlocks.first().spansMultiplePdfPages) {
            val single = matchedBlocks.first()
            val blockClean = single.text.removePrefix("## ").trim()
            val idx = blockClean.indexOf(cleanQuote, ignoreCase = true)
            if (idx >= 0 && blockClean.length > 20) {
                val midpoint = blockClean.length / 2
                val quoteStart = idx
                val quoteEnd = idx + cleanQuote.length
                if (quoteEnd < midpoint - 10) {
                    // Selection is strictly in the first page portion of the merged paragraph
                    val p = single.startPdfPage
                    val pr = single.startPrintedPage.ifBlank { printedPageByPdfPage[p] ?: "" }
                    return PdfQuotePageSpan(p, p, pr, pr)
                } else if (quoteStart > midpoint + 10) {
                    // Selection is strictly in the second page portion of the merged paragraph
                    val p = single.endPdfPage
                    val pr = single.endPrintedPage.ifBlank { printedPageByPdfPage[p] ?: "" }
                    return PdfQuotePageSpan(p, p, pr, pr)
                }
            }
        }

        val startPrinted = matchedBlocks.minByOrNull { it.startPdfPage }?.startPrintedPage
            ?.ifBlank { printedPageByPdfPage[minPdfPage] ?: "" }
            ?: (printedPageByPdfPage[minPdfPage] ?: "")
        val endPrinted = matchedBlocks.maxByOrNull { it.endPdfPage }?.endPrintedPage
            ?.ifBlank { printedPageByPdfPage[maxPdfPage] ?: startPrinted }
            ?: (printedPageByPdfPage[maxPdfPage] ?: startPrinted)

        return PdfQuotePageSpan(
            startPdfPage = minPdfPage,
            endPdfPage = maxPdfPage,
            startPrintedPage = startPrinted,
            endPrintedPage = endPrinted
        )
    }

    /**
     * Returns the printed page range string (e.g. "3" or "3–4") and PDF page range string (e.g. "14" or "14–15")
     * for a range of PDF pages.
     */
    fun resolveSpanForPdfPageRange(startPdfPage: Int, endPdfPage: Int): PdfQuotePageSpan {
        val sPdf = min(startPdfPage, endPdfPage).coerceAtLeast(1)
        val ePdf = max(startPdfPage, endPdfPage).coerceAtLeast(sPdf)
        val sPrinted = printedPageByPdfPage[sPdf] ?: ""
        val ePrinted = printedPageByPdfPage[ePdf] ?: sPrinted
        return PdfQuotePageSpan(
            startPdfPage = sPdf,
            endPdfPage = ePdf,
            startPrintedPage = sPrinted,
            endPrintedPage = ePrinted
        )
    }

    /**
     * Checks whether a given [HighlightEntity] belongs to [pdfPageNumber] (1-based), either because:
     * - `highlight.pageNumber == pdfPageNumber`, OR
     * - `highlight.printedPageLabel` encodes a multi-page PDF span covering `pdfPageNumber` (e.g. `|PDF:14–15`), OR
     * - the highlight quote spans a cross-page block that touches `pdfPageNumber`.
     */
    fun doesHighlightTouchPdfPage(highlight: com.example.data.local.HighlightEntity, pdfPageNumber: Int): Boolean {
        if (highlight.pageNumber == pdfPageNumber) return true
        val (pdfRangeStr, _) = PdfToEbookParser.parseStoredPageLabels(
            primaryPdfPage = highlight.pageNumber,
            rawPrintedPageLabel = highlight.printedPageLabel
        )
        val rangeParts = pdfRangeStr.split(Regex("[–\\-]")).mapNotNull { it.trim().toIntOrNull() }
        if (rangeParts.size == 2 && pdfPageNumber in rangeParts[0]..rangeParts[1]) {
            return true
        }
        val resolved = resolvePageSpanForQuote(highlight.highlightedText, highlight.pageNumber)
        return pdfPageNumber in resolved.startPdfPage..resolved.endPdfPage
    }

    companion object {
        private fun tokenizeNormalized(text: String): List<String> {
            return text.lowercase(Locale.ROOT)
                .split(Regex("\\s+"))
                .map { it.replace(Regex("[^a-z0-9æøå]"), "") }
                .filter { it.isNotEmpty() }
        }

        private fun hasSignificantTokenOverlap(quoteTokens: List<String>, blockTokens: List<String>): Boolean {
            if (quoteTokens.isEmpty() || blockTokens.isEmpty()) return false
            val window = min(5, min(quoteTokens.size, blockTokens.size))
            if (window < 3) return false
            // Check if the first `window` tokens or last `window` tokens of quote appear contiguously in blockTokens
            val prefix = quoteTokens.take(window)
            val suffix = quoteTokens.takeLast(window)
            for (i in 0..(blockTokens.size - window)) {
                var matchPrefix = true
                var matchSuffix = true
                for (k in 0 until window) {
                    if (blockTokens[i + k] != prefix[k]) matchPrefix = false
                    if (blockTokens[i + k] != suffix[k]) matchSuffix = false
                }
                if (matchPrefix || matchSuffix) return true
            }
            return false
        }
    }
}

data class PdfQuotePageSpan(
    val startPdfPage: Int,
    val endPdfPage: Int,
    val startPrintedPage: String,
    val endPrintedPage: String
) {
    val spansMultiplePdfPages: Boolean
        get() = endPdfPage > startPdfPage

    val spansMultiplePrintedPages: Boolean
        get() = startPrintedPage.isNotBlank() &&
            endPrintedPage.isNotBlank() &&
            !startPrintedPage.equals(endPrintedPage, ignoreCase = true)

    /**
     * Formats the `printedPageLabel` stored in `HighlightEntity` so that:
     * - Single page with printed page "42": `"42"`
     * - Highlight spanning printed pages "42" to "43" (and PDF pages 50 to 51):
     *   If PDF pages also span, encodes `"42–43"` (or if no printed labels exist, `"PDF 50–51"`).
     */
    fun toStoredPrintedPageLabel(): String {
        val s = startPrintedPage.trim()
        val e = endPrintedPage.trim()
        return when {
            s.isNotEmpty() && e.isNotEmpty() && !s.equals(e, ignoreCase = true) -> {
                if (endPdfPage > startPdfPage) {
                    "$s–$e|PDF:$startPdfPage–$endPdfPage"
                } else {
                    "$s–$e"
                }
            }
            s.isNotEmpty() -> {
                if (endPdfPage > startPdfPage) {
                    "$s|PDF:$startPdfPage–$endPdfPage"
                } else {
                    s
                }
            }
            e.isNotEmpty() -> {
                if (endPdfPage > startPdfPage) {
                    "$e|PDF:$startPdfPage–$endPdfPage"
                } else {
                    e
                }
            }
            endPdfPage > startPdfPage -> {
                "|PDF:$startPdfPage–$endPdfPage"
            }
            else -> ""
        }
    }

    fun formatHumanReadableSummary(): String {
        val pdfLabel = if (endPdfPage > startPdfPage) {
            "PDF pages $startPdfPage–$endPdfPage"
        } else {
            "PDF page number $startPdfPage"
        }
        val s = startPrintedPage.trim()
        val e = endPrintedPage.trim()
        val printedLabel = when {
            s.isNotEmpty() && e.isNotEmpty() && !s.equals(e, ignoreCase = true) ->
                "printed pages $s–$e"
            s.isNotEmpty() ->
                "printed page number $s"
            e.isNotEmpty() ->
                "printed page number $e"
            else -> ""
        }
        return if (printedLabel.isNotEmpty()) "$pdfLabel, $printedLabel" else pdfLabel
    }
}

/**
 * Represents a paginated page in the PDF-to-Ebook view, enriched with source PDF page numbers,
 * printed book page numbers, and optional embedded visual snapshots for unsupported PDF elements.
 */
data class PdfEbookReflowPage(
    val epubPage: EpubPage,
    val startPdfPage: Int,
    val endPdfPage: Int,
    val startPrintedPage: String,
    val endPrintedPage: String,
    val blocksOnPage: List<PdfReflowBlock>
) {
    fun formatStatusBarSummary(ebookPageNumber: Int, totalEbookPages: Int): String {
        val pdfPart = if (endPdfPage > startPdfPage) {
            "PDF pp. $startPdfPage–$endPdfPage"
        } else {
            "PDF p. $startPdfPage"
        }
        val sPrint = startPrintedPage.trim()
        val ePrint = endPrintedPage.trim()
        val printedPart = when {
            sPrint.isNotEmpty() && ePrint.isNotEmpty() && !sPrint.equals(ePrint, ignoreCase = true) ->
                " • Printed pp. $sPrint–$ePrint"
            sPrint.isNotEmpty() ->
                " • Printed p. $sPrint"
            ePrint.isNotEmpty() ->
                " • Printed p. $ePrint"
            else -> ""
        }
        return "Ebook p. $ebookPageNumber/$totalEbookPages ($pdfPart$printedPart)"
    }
}

/**
 * Core parser that converts a PDF document into a clean, continuous, reflowable ebook representation
 * while preserving the original PDF untouched and maintaining exact page/highlight anchors.
 */
object PdfToEbookParser {

    private data class VisualLine(
        val pageIndex: Int, // 0-based
        val lineIndex: Int,
        val words: List<PdfPageWord>,
        val text: String,
        val leftRatio: Float,
        val topRatio: Float,
        val rightRatio: Float,
        val bottomRatio: Float,
        val heightRatio: Float
    )

    private val CHAPTER_HEADING_REGEX = Regex(
        "^(?:chapter|kapittel|part|del|section|book|prologue|epilogue|preface|introduction|appendix|conclusion|abstract|references|bibliography)\\b(?:\\s+[0-9ivxlcdmIVXLCDM]+)?(?:[:.\\-—\\s].*)?$",
        RegexOption.IGNORE_CASE
    )
    private val NUMBERED_HEADING_REGEX = Regex(
        "^(?:[0-9]{1,2}(?:\\.[0-9]{1,2}){0,2}|[IVXLCDM]{1,6})[.)]?\\s+[A-ZÆØÅ][A-Za-z0-9ÆØÅæøå,\\-—'\"()\\s]{2,75}$"
    )
    private val BULLET_LIST_REGEX = Regex("^(?:[•◦▪▫\\-*–—]|\\([a-z0-9]+\\)|[0-9]{1,3}[.)])\\s+.+")
    private val FOOTNOTE_LINE_REGEX = Regex("^(?:\\[([0-9]{1,3})\\]|([0-9]{1,3})[.)]|\\*+)\\s+(.+)$")
    private val TABLE_CAPTION_REGEX = Regex("^(?:table|tabell)\\s+[0-9ivxlcdmIVXLCDM]+(?:[:.\\s].*)?$", RegexOption.IGNORE_CASE)
    private val FIGURE_CAPTION_REGEX = Regex("^(?:figure|fig\\.|figur|chart|diagram|map|plate)\\s*[0-9ivxlcdmIVXLCDM]+(?:[:.\\s].*)?$", RegexOption.IGNORE_CASE)
    private val MATH_SYMBOLS_REGEX = Regex("[∑∏∫√∂∇±×÷≈≠≤≥∞∈∉⊂⊃∪∩∀∃⇒⇔αβγδεζηθλμπρσφψωΔΣΩ]|\\b(?:lim|sin|cos|tan|log|ln)\\b")
    private val INTERACTIVE_FORM_REGEX = Regex("(?:\\[\\s*]|\\(\\s*\\)|_{4,}|\\b(?:Sign here|Submit Form|Click here|Check box)\\b)", RegexOption.IGNORE_CASE)

    /**
     * Parses a PDF via [PdfDocumentRenderer] into a structured [PdfEbookDocument].
     */
    fun parsePdfToEbook(
        renderer: PdfDocumentRenderer,
        documentTitle: String,
        documentAuthor: String,
        chapterBookmarks: List<BookChapter> = emptyList(),
        onProgress: ((currentPage: Int, totalPages: Int) -> Unit)? = null
    ): PdfEbookDocument {
        val totalPages = renderer.pageCount.coerceAtLeast(1)
        val rawPages = ArrayList<PdfPageTextData>(totalPages)
        for (pageIdx in 0 until totalPages) {
            onProgress?.invoke(pageIdx + 1, totalPages)
            rawPages.add(renderer.extractPageTextData(pageIdx))
        }
        // Release the heavy PDFBox PDDocument once all pages are extracted so memory stays low
        renderer.releasePdDocumentMemory()

        return parsePdfToEbookFromPageData(
            rawPages = rawPages,
            documentTitle = documentTitle,
            documentAuthor = documentAuthor,
            chapterBookmarks = chapterBookmarks
        )
    }

    /**
     * Parses pre-extracted [PdfPageTextData] pages into a continuous, reflowable [PdfEbookDocument].
     */
    fun parsePdfToEbookFromPageData(
        rawPages: List<PdfPageTextData>,
        documentTitle: String,
        documentAuthor: String,
        chapterBookmarks: List<BookChapter> = emptyList()
    ): PdfEbookDocument {
        val totalPages = rawPages.size
        if (totalPages <= 0) {
            val emptyEpub = EpubBook(
                title = documentTitle,
                author = documentAuthor,
                description = "",
                chapters = emptyList()
            )
            return PdfEbookDocument(
                title = documentTitle,
                author = documentAuthor,
                totalPdfPages = 0,
                blocks = emptyList(),
                printedPageByPdfPage = emptyMap(),
                removedRunningHeadersFooters = emptyList(),
                epubBook = emptyEpub
            )
        }

        // 1. Collect and infer printed page numbers across all pages (including sequential interpolation when a page omits its number)
        val printedPageMap = buildPrintedPageMap(rawPages)

        // 2. Build visual lines for every page
        val pageLinesMap = mutableMapOf<Int, List<VisualLine>>()
        for (pageData in rawPages) {
            pageLinesMap[pageData.pageIndex] = buildVisualLinesForPage(pageData)
        }

        // 3. Identify repetitive running headers, running footers, author/book/chapter banners, and page numbers
        val chapterTitlesSet = chapterBookmarks.map { normalizeHeaderFooterComparison(it.title) }
            .filter { it.isNotEmpty() }
            .toSet()
        val (removedLinesByPage, removedSummaryList) = detectRepetitiveHeadersAndFooters(
            pageLinesMap = pageLinesMap,
            totalPages = totalPages,
            documentTitle = documentTitle,
            documentAuthor = documentAuthor,
            knownChapterTitles = chapterTitlesSet
        )

        // 4. Reconstruct logical structure page by page and merge paragraphs broken across page boundaries
        val bookmarksByPage = chapterBookmarks.groupBy { it.pageNumber }
        var currentChapterTitle = chapterBookmarks.firstOrNull { it.pageNumber <= 1 }?.title
            ?: documentTitle.ifBlank { "Chapter 1" }

        val rawBlocks = mutableListOf<PdfReflowBlock>()
        var blockCounter = 1

        for (pageIdx in 0 until totalPages) {
            val pdfPageNumber = pageIdx + 1
            val printedLabel = printedPageMap[pdfPageNumber] ?: ""

            // Update chapter title if bookmark starts on this page
            bookmarksByPage[pdfPageNumber]?.firstOrNull()?.let { bm ->
                if (bm.title.isNotBlank()) {
                    currentChapterTitle = bm.title.trim()
                }
            }

            val allLines = pageLinesMap[pageIdx] ?: emptyList()
            val ignoredLineIndices = removedLinesByPage[pageIdx] ?: emptySet()
            val contentLines = allLines.filter { it.lineIndex !in ignoredLineIndices }

            // Core Requirement 3: Scanned / image-only page detection
            if (contentLines.isEmpty()) {
                val reason = PdfUnsupportedReason.SCANNED_OR_IMAGE_ONLY_PAGE
                val placeholderText = "[Unsupported PDF Element: ${reason.displayTitle} on PDF page $pdfPageNumber" +
                    (if (printedLabel.isNotBlank()) " (printed page $printedLabel)" else "") +
                    " — Tap or view snapshot below; original page preserved in PDF View.]"
                rawBlocks.add(
                    PdfReflowBlock(
                        id = "pdf_block_${blockCounter++}",
                        type = PdfReflowBlockType.UNSUPPORTED_ELEMENT,
                        text = placeholderText,
                        chapterTitle = currentChapterTitle,
                        startPdfPage = pdfPageNumber,
                        endPdfPage = pdfPageNumber,
                        startPrintedPage = printedLabel,
                        endPrintedPage = printedLabel,
                        unsupportedReason = reason,
                        cropRectNormalized = RectF(0.02f, 0.02f, 0.98f, 0.98f)
                    )
                )
                continue
            }

            // Analyze multi-column layout on this page:
            // - Clean 2-column academic layout -> reorder left column top-to-bottom, then right column top-to-bottom
            // - Unresolvable overlapping / 3+ tangled columns -> preserve text in best Y-order AND emit an unsupported layout warning card with snapshot
            val columnAnalysis = analyzeAndOrderPageColumns(contentLines)
            if (columnAnalysis.hasUnreliableMultiColumnOrOverlap) {
                val reason = PdfUnsupportedReason.COMPLEX_MULTICOLUMN_OR_OVERLAPPING
                val warningText = "[Unsupported PDF Layout: ${reason.displayTitle} on PDF page $pdfPageNumber" +
                    (if (printedLabel.isNotBlank()) " (printed page $printedLabel)" else "") +
                    " — Text is preserved below, alongside the original visual layout snapshot.]"
                rawBlocks.add(
                    PdfReflowBlock(
                        id = "pdf_block_${blockCounter++}",
                        type = PdfReflowBlockType.UNSUPPORTED_ELEMENT,
                        text = warningText,
                        chapterTitle = currentChapterTitle,
                        startPdfPage = pdfPageNumber,
                        endPdfPage = pdfPageNumber,
                        startPrintedPage = printedLabel,
                        endPrintedPage = printedLabel,
                        unsupportedReason = reason,
                        cropRectNormalized = columnAnalysis.complexRegionRect ?: RectF(0.04f, 0.08f, 0.96f, 0.92f)
                    )
                )
            }

            val orderedLines = columnAnalysis.orderedLines

            // Extract bottom-of-page footnotes first so we can link them or preserve them cleanly
            val pageFootnotes = mutableMapOf<String, String>()
            val mainBodyLines = mutableListOf<VisualLine>()
            for (line in orderedLines) {
                val fnMatch = FOOTNOTE_LINE_REGEX.find(line.text.trim())
                if (line.topRatio >= 0.74f && fnMatch != null) {
                    val fnKey = (fnMatch.groups[1]?.value ?: fnMatch.groups[2]?.value ?: "*").trim()
                    val fnBody = fnMatch.groups[3]?.value?.trim() ?: line.text.trim()
                    if (fnBody.isNotEmpty()) {
                        pageFootnotes[fnKey] = fnBody
                    }
                    mainBodyLines.add(line)
                } else {
                    mainBodyLines.add(line)
                }
            }

            // Detect large vertical gaps that indicate an embedded figure/diagram/chart on the page
            val figureGaps = detectEmbeddedFigureRegions(mainBodyLines)
            var nextFigureIdx = 0

            // Segment mainBodyLines into structural blocks (Headings, Paragraphs, Quotations, Lists, Tables, Equations, Footnotes)
            val pageBlocks = segmentLinesIntoBlocks(
                lines = mainBodyLines,
                pdfPageNumber = pdfPageNumber,
                printedLabel = printedLabel,
                initialChapterTitle = currentChapterTitle,
                pageFootnotes = pageFootnotes,
                figureGaps = figureGaps,
                nextBlockId = { "pdf_block_${blockCounter++}" },
                onChapterTitleChanged = { newTitle -> currentChapterTitle = newTitle }
            )

            // If there were any remaining detected figure regions below the last line, attach them
            while (nextFigureIdx < figureGaps.size) {
                val gapRect = figureGaps[nextFigureIdx++]
                if (pageBlocks.none { it.unsupportedReason == PdfUnsupportedReason.CHART_OR_DIAGRAM_OR_IMAGE && it.cropRectNormalized == gapRect }) {
                    rawBlocks.add(
                        PdfReflowBlock(
                            id = "pdf_block_${blockCounter++}",
                            type = PdfReflowBlockType.UNSUPPORTED_ELEMENT,
                            text = "[Unsupported PDF Element: ${PdfUnsupportedReason.CHART_OR_DIAGRAM_OR_IMAGE.displayTitle} on PDF page $pdfPageNumber" +
                                (if (printedLabel.isNotBlank()) " (printed page $printedLabel)" else "") +
                                " — Original visual region preserved]",
                            chapterTitle = currentChapterTitle,
                            startPdfPage = pdfPageNumber,
                            endPdfPage = pdfPageNumber,
                            startPrintedPage = printedLabel,
                            endPrintedPage = printedLabel,
                            unsupportedReason = PdfUnsupportedReason.CHART_OR_DIAGRAM_OR_IMAGE,
                            cropRectNormalized = gapRect
                        )
                    )
                }
            }

            rawBlocks.addAll(pageBlocks)
        }

        // 5. Stitch paragraphs that continue across a PDF page boundary (e.g. page N ends mid-sentence and page N+1 continues it)
        val mergedBlocks = mergeCrossPageParagraphs(rawBlocks)

        // 6. Convert the structured blocks into an EpubBook so existing typography, reflow pagination,
        //    TTS, footnotes, and search work seamlessly!
        val epubBook = buildEpubBookFromReflowBlocks(
            title = documentTitle,
            author = documentAuthor,
            blocks = mergedBlocks
        )

        return PdfEbookDocument(
            title = documentTitle,
            author = documentAuthor,
            totalPdfPages = totalPages,
            blocks = mergedBlocks,
            printedPageByPdfPage = printedPageMap,
            removedRunningHeadersFooters = removedSummaryList,
            epubBook = epubBook
        )
    }

    /**
     * Paginates a [PdfEbookDocument] for the given viewport and typography settings while mapping every
     * resulting ebook page to its exact source PDF page range and printed book page range.
     */
    fun paginatePdfEbook(
        ebookDoc: PdfEbookDocument,
        fontSizeSp: Float = 18f,
        lineSpacingMultiplier: Float = 1.6f,
        marginDp: Int = 24,
        viewportWidthDp: Int = 392,
        viewportHeightDp: Int = 720
    ): Pair<List<PdfEbookReflowPage>, List<BookChapter>> {
        val (epubPages, toc) = EpubParser.paginateBook(
            epubBook = ebookDoc.epubBook,
            fontSizeSp = fontSizeSp,
            lineSpacingMultiplier = lineSpacingMultiplier,
            marginDp = marginDp,
            viewportWidthDp = viewportWidthDp,
            viewportHeightDp = viewportHeightDp
        )

        // Map each paginated EpubPage back to the PdfReflowBlocks whose text appears on that page
        // Build fast lookup maps by exact cleaned text and 24-char prefixes/suffixes, plus a forward cursor
        val exactBlockMap = mutableMapOf<String, MutableList<PdfReflowBlock>>()
        val prefixBlockMap = mutableMapOf<String, MutableList<PdfReflowBlock>>()
        for (b in ebookDoc.blocks) {
            val bClean = b.text.removePrefix("## ").trim().lowercase(Locale.ROOT)
            if (bClean.isNotEmpty()) {
                exactBlockMap.getOrPut(bClean) { mutableListOf() }.add(b)
                if (bClean.length >= 20) {
                    prefixBlockMap.getOrPut(bClean.take(20)) { mutableListOf() }.add(b)
                }
            }
        }

        var lastKnownPdfPage = 1
        var lastKnownPrintedPage = ebookDoc.printedPageByPdfPage[1] ?: ""
        var searchCursorIdx = 0

        val reflowPages = epubPages.map { epubPage ->
            if (epubPage.isCoverPage) {
                val p1Printed = ebookDoc.printedPageByPdfPage[1] ?: ""
                PdfEbookReflowPage(
                    epubPage = epubPage,
                    startPdfPage = 1,
                    endPdfPage = 1,
                    startPrintedPage = p1Printed,
                    endPrintedPage = p1Printed,
                    blocksOnPage = emptyList()
                )
            } else {
                val matched = mutableListOf<PdfReflowBlock>()
                for (para in epubPage.paragraphs) {
                    val cleanPara = para.removePrefix("## ").trim()
                    if (cleanPara.isEmpty()) continue
                    val lowerPara = cleanPara.lowercase(Locale.ROOT)

                    // 1. Fast exact match
                    val exactHits = exactBlockMap[lowerPara]
                    if (!exactHits.isNullOrEmpty()) {
                        matched.addAll(exactHits)
                        continue
                    }

                    // 2. Fast 20-char prefix match (handles paragraphs split at start of page)
                    if (lowerPara.length >= 20) {
                        val prefixHits = prefixBlockMap[lowerPara.take(20)]
                        if (!prefixHits.isNullOrEmpty()) {
                            matched.addAll(prefixHits)
                            continue
                        }
                    }

                    // 3. Windowed cursor search around current block position (for mid-paragraph splits)
                    val windowStart = (searchCursorIdx - 6).coerceAtLeast(0)
                    val windowEnd = (searchCursorIdx + 40).coerceAtMost(ebookDoc.blocks.size)
                    var foundInWindow = false
                    for (bIdx in windowStart until windowEnd) {
                        val b = ebookDoc.blocks[bIdx]
                        val bClean = b.text.removePrefix("## ").trim()
                        if (bClean.isNotEmpty() && (
                                bClean.contains(cleanPara, ignoreCase = true) ||
                                    cleanPara.contains(bClean, ignoreCase = true) ||
                                    (cleanPara.length >= 20 && bClean.contains(cleanPara.take(20), ignoreCase = true)) ||
                                    (cleanPara.length >= 20 && bClean.contains(cleanPara.takeLast(20), ignoreCase = true))
                            )
                        ) {
                            matched.add(b)
                            searchCursorIdx = bIdx
                            foundInWindow = true
                            break
                        }
                    }

                    if (!foundInWindow && ebookDoc.blocks.size <= 300) {
                        val hit = ebookDoc.blocks.firstOrNull { b ->
                            val bClean = b.text.removePrefix("## ").trim()
                            bClean.isNotEmpty() && (
                                bClean.contains(cleanPara, ignoreCase = true) ||
                                    cleanPara.contains(bClean, ignoreCase = true) ||
                                    (cleanPara.length >= 20 && bClean.contains(cleanPara.take(20), ignoreCase = true)) ||
                                    (cleanPara.length >= 20 && bClean.contains(cleanPara.takeLast(20), ignoreCase = true))
                                )
                        }
                        if (hit != null) {
                            matched.add(hit)
                        }
                    }
                }

                val distinctBlocks = matched.distinctBy { it.id }
                val startPdf = distinctBlocks.minOfOrNull { it.startPdfPage } ?: lastKnownPdfPage
                val endPdf = distinctBlocks.maxOfOrNull { it.endPdfPage } ?: startPdf
                val startPrinted = distinctBlocks.minByOrNull { it.startPdfPage }?.startPrintedPage
                    ?.ifBlank { ebookDoc.printedPageByPdfPage[startPdf] ?: "" }
                    ?: (ebookDoc.printedPageByPdfPage[startPdf] ?: lastKnownPrintedPage)
                val endPrinted = distinctBlocks.maxByOrNull { it.endPdfPage }?.endPrintedPage
                    ?.ifBlank { ebookDoc.printedPageByPdfPage[endPdf] ?: startPrinted }
                    ?: (ebookDoc.printedPageByPdfPage[endPdf] ?: startPrinted)

                lastKnownPdfPage = endPdf
                lastKnownPrintedPage = endPrinted

                PdfEbookReflowPage(
                    epubPage = epubPage,
                    startPdfPage = startPdf,
                    endPdfPage = endPdf,
                    startPrintedPage = startPrinted,
                    endPrintedPage = endPrinted,
                    blocksOnPage = distinctBlocks
                )
            }
        }

        return Pair(reflowPages, toc)
    }

    /**
     * Parses a stored `printedPageLabel` (which may contain an encoded PDF page span like `"42–43|PDF:50–51"`
     * or a plain printed page range `"42–43"` or single `"42"`) into its human-readable printed page string
     * and optional PDF page range string.
     */
    fun parseStoredPageLabels(
        primaryPdfPage: Int,
        rawPrintedPageLabel: String
    ): Pair<String, String> {
        val raw = rawPrintedPageLabel.trim()
        if (!raw.contains("|PDF:")) {
            return Pair(primaryPdfPage.toString(), raw)
        }
        val parts = raw.split("|PDF:", limit = 2)
        val printedPart = parts[0].trim()
        val pdfRangePart = parts.getOrNull(1)?.trim()?.ifBlank { primaryPdfPage.toString() } ?: primaryPdfPage.toString()
        return Pair(pdfRangePart, printedPart)
    }

    /**
     * Builds a map of 1-based PDF page -> printed page label, inferring missing Arabic sequential labels
     * when surrounding pages establish a clear linear offset (e.g., page 3 = "1", page 4 = "", page 5 = "3" -> page 4 = "2").
     */
    private fun buildPrintedPageMap(rawPages: List<PdfPageTextData>): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        for (p in rawPages) {
            val pdfPage = p.pageIndex + 1
            if (p.printedPageLabel.isNotBlank()) {
                result[pdfPage] = p.printedPageLabel.trim()
            }
        }

        // Fill in gaps where surrounding pages have sequential Arabic numbers with the exact same offset
        val total = rawPages.size
        for (pdfPage in 1..total) {
            if (result[pdfPage].isNullOrBlank()) {
                val prevEntry = (pdfPage - 1 downTo max(1, pdfPage - 3)).firstNotNullOfOrNull { prevP ->
                    val num = result[prevP]?.toIntOrNull()
                    if (num != null) Pair(prevP, num) else null
                }
                val nextEntry = (pdfPage + 1..min(total, pdfPage + 3)).firstNotNullOfOrNull { nextP ->
                    val num = result[nextP]?.toIntOrNull()
                    if (num != null) Pair(nextP, num) else null
                }
                if (prevEntry != null && nextEntry != null) {
                    val prevOffset = prevEntry.second - prevEntry.first
                    val nextOffset = nextEntry.second - nextEntry.first
                    if (prevOffset == nextOffset && pdfPage + prevOffset >= 1) {
                        result[pdfPage] = (pdfPage + prevOffset).toString()
                    }
                }
            }
        }
        return result
    }

    private fun buildVisualLinesForPage(pageData: PdfPageTextData): List<VisualLine> {
        if (pageData.words.isEmpty()) return emptyList()
        val byLine = pageData.words.groupBy { it.lineIndex }.entries.sortedBy { it.key }
        return byLine.mapNotNull { (lineIdx, words) ->
            if (words.isEmpty()) return@mapNotNull null
            val sortedWords = words.sortedBy { it.leftRatio }
            val text = sortedWords.joinToString(" ") { it.text }.trim()
            if (text.isEmpty()) return@mapNotNull null
            val minL = sortedWords.minOf { it.leftRatio }
            val minT = sortedWords.minOf { it.topRatio }
            val maxR = sortedWords.maxOf { it.rightRatio }
            val maxB = sortedWords.maxOf { it.bottomRatio }
            VisualLine(
                pageIndex = pageData.pageIndex,
                lineIndex = lineIdx,
                words = sortedWords,
                text = text,
                leftRatio = minL,
                topRatio = minT,
                rightRatio = maxR,
                bottomRatio = maxB,
                heightRatio = (maxB - minT).coerceAtLeast(0.008f)
            )
        }
    }

    /**
     * Core Requirement 1:
     * Automatically identifies and removes repetitive page-level elements that are not part of the main text:
     * - Author names repeated in headers or footers
     * - Book titles repeated on every page
     * - Chapter titles repeated in running headers
     * - Running headers and footers
     * - Standalone or margin page numbers
     * - Decorative repeated text
     *
     * Never removes text merely because it appears more than once in the body — only candidates at the
     * top/bottom margin of pages that match structural header/footer criteria are removed.
     */
    private fun detectRepetitiveHeadersAndFooters(
        pageLinesMap: Map<Int, List<VisualLine>>,
        totalPages: Int,
        documentTitle: String,
        documentAuthor: String,
        knownChapterTitles: Set<String>
    ): Pair<Map<Int, Set<Int>>, List<String>> {
        val removedByPage = mutableMapOf<Int, MutableSet<Int>>()
        val removedSamples = linkedSetOf<String>()

        val normDocTitle = normalizeHeaderFooterComparison(documentTitle)
        val normAuthors = documentAuthor
            .split(Regex("[,;&]|\\band\\b", RegexOption.IGNORE_CASE))
            .map { normalizeHeaderFooterComparison(it) }
            .filter { it.length >= 3 }

        // Count how many distinct pages each normalized header/footer template appears on (only at top/bottom of a page)
        val topCandidatePages = mutableMapOf<String, MutableSet<Int>>()
        val bottomCandidatePages = mutableMapOf<String, MutableSet<Int>>()

        for ((pageIdx, lines) in pageLinesMap) {
            if (lines.size < 2) continue
            val firstLine = lines.first()
            if (isHeaderCandidatePosition(firstLine, lines)) {
                val norm = normalizeHeaderFooterComparison(firstLine.text)
                if (norm.isNotEmpty()) {
                    topCandidatePages.getOrPut(norm) { mutableSetOf() }.add(pageIdx)
                }
            }
            val lastLine = lines.last()
            if (lastLine.lineIndex != firstLine.lineIndex && isFooterCandidatePosition(lastLine, lines)) {
                val norm = normalizeHeaderFooterComparison(lastLine.text)
                if (norm.isNotEmpty()) {
                    bottomCandidatePages.getOrPut(norm) { mutableSetOf() }.add(pageIdx)
                }
            }
        }

        // Minimum pages for a template to be considered a repeated running header/footer:
        // For >= 3 page PDFs, appearing in the exact header/footer slot on >= 2 pages (including alternating odd/even headers)
        val minRepetitionCount = if (totalPages >= 4) 2 else if (totalPages >= 2) 2 else 99

        for ((pageIdx, lines) in pageLinesMap) {
            if (lines.isEmpty()) continue
            val pageRemoved = removedByPage.getOrPut(pageIdx) { mutableSetOf() }

            // Inspect top 1..2 lines for running headers / page numbers
            val topCandidates = lines.take(min(2, lines.size))
            for ((idxInTop, candidate) in topCandidates.withIndex()) {
                if (idxInTop == 1 && candidate.lineIndex !in pageRemoved && lines.first().lineIndex !in pageRemoved) {
                    // Only inspect the second line if the very first line was already a header/page number
                    continue
                }
                if (!isHeaderCandidatePosition(candidate, lines)) continue
                if (shouldRemoveHeaderOrFooterLine(
                        line = candidate,
                        allPageLines = lines,
                        repetitionMap = topCandidatePages,
                        minRepetitionCount = minRepetitionCount,
                        normDocTitle = normDocTitle,
                        normAuthors = normAuthors,
                        knownChapterTitles = knownChapterTitles,
                        isTopOfPage = true
                    )
                ) {
                    pageRemoved.add(candidate.lineIndex)
                    removedSamples.add(candidate.text)
                }
            }

            // Inspect bottom 1..2 lines for running footers / page numbers
            val bottomCandidates = lines.takeLast(min(2, lines.size)).reversed()
            for ((idxInBottom, candidate) in bottomCandidates.withIndex()) {
                if (idxInBottom == 1 && lines.last().lineIndex !in pageRemoved) {
                    continue
                }
                if (!isFooterCandidatePosition(candidate, lines)) continue
                if (shouldRemoveHeaderOrFooterLine(
                        line = candidate,
                        allPageLines = lines,
                        repetitionMap = bottomCandidatePages,
                        minRepetitionCount = minRepetitionCount,
                        normDocTitle = normDocTitle,
                        normAuthors = normAuthors,
                        knownChapterTitles = knownChapterTitles,
                        isTopOfPage = false
                    )
                ) {
                    pageRemoved.add(candidate.lineIndex)
                    removedSamples.add(candidate.text)
                }
            }
        }

        return Pair(removedByPage, removedSamples.toList())
    }

    private fun isHeaderCandidatePosition(line: VisualLine, allLines: List<VisualLine>): Boolean {
        if (line.topRatio <= 0.16f) return true
        if (allLines.size >= 3 && line.lineIndex == allLines.first().lineIndex) {
            val secondTop = allLines[1].topRatio
            val gap = secondTop - line.bottomRatio
            return line.topRatio <= 0.22f && (gap >= 0.015f || line.words.size <= 10)
        }
        return false
    }

    private fun isFooterCandidatePosition(line: VisualLine, allLines: List<VisualLine>): Boolean {
        if (line.bottomRatio >= 0.84f || line.topRatio >= 0.80f) return true
        if (allLines.size >= 3 && line.lineIndex == allLines.last().lineIndex) {
            val prevBottom = allLines[allLines.size - 2].bottomRatio
            val gap = line.topRatio - prevBottom
            return line.topRatio >= 0.72f && (gap >= 0.015f || line.words.size <= 10)
        }
        return false
    }

    private fun shouldRemoveHeaderOrFooterLine(
        line: VisualLine,
        allPageLines: List<VisualLine>,
        repetitionMap: Map<String, Set<Int>>,
        minRepetitionCount: Int,
        normDocTitle: String,
        normAuthors: List<String>,
        knownChapterTitles: Set<String>,
        isTopOfPage: Boolean
    ): Boolean {
        val raw = line.text.trim()
        if (raw.isEmpty()) return true

        // Never remove a genuine footnote definition at the bottom of a page!
        if (!isTopOfPage && FOOTNOTE_LINE_REGEX.matches(raw) && line.words.size >= 4) {
            return false
        }

        // 1. Standalone page number line (e.g. "14", "xiv", "— 27 —", "Page 14", "14 / 220")
        if (line.words.size <= 3 && PdfDocumentRenderer.isValidPageNumberToken(raw)) {
            return true
        }
        if (Regex("^(?:page|p\\.|pp\\.|side|s\\.)\\s*[0-9ivxlcdmIVXLCDM]+(?:\\s*(?:of|/|av)\\s*[0-9ivxlcdmIVXLCDM]+)?$", RegexOption.IGNORE_CASE).matches(raw)) {
            return true
        }
        if (Regex("^[\\-—–\\[(\\s]*[0-9]{1,4}\\s*/\\s*[0-9]{1,4}[\\-—–\\])\\s]*$").matches(raw)) {
            return true
        }

        // Running header/footer lines are short (typically <= 12 words, <= 95 chars) and don't end like long prose paragraphs
        if (line.words.size > 14 || raw.length > 105) {
            return false
        }

        val normLine = normalizeHeaderFooterComparison(raw)
        val hasOuterPageToken = PdfDocumentRenderer.extractPageTokenFromHeaderOrFooterLine(raw, line.words).isNotEmpty()

        // 2. Line repeated in the same header/footer slot across multiple pages
        val repeatedPagesCount = repetitionMap[normLine]?.size ?: 0
        if (normLine.isNotEmpty() && repeatedPagesCount >= minRepetitionCount) {
            // Ensure we don't strip a genuine chapter heading on its very first appearance if there's no page number token
            // unless it is repeated on multiple pages as a running header
            return true
        }

        // 3. Running header/footer containing a margin page number PLUS book title / author / chapter title (e.g. "Chapter 1: Introduction   14" or "12   Margaret LeCompte")
        if (hasOuterPageToken && line.words.size <= 12) {
            return true
        }

        // 4. Exact match with Book Title or Author Name in the top/bottom running margin (when not the title page itself)
        if (allPageLines.size >= 4) {
            if (normDocTitle.length >= 4 && (normLine == normDocTitle || (hasOuterPageToken && normLine.contains(normDocTitle)))) {
                return true
            }
            if (normAuthors.any { author -> author.length >= 4 && (normLine == author || (hasOuterPageToken && normLine.contains(author))) }) {
                return true
            }
            // Running chapter header: if the line matches a known chapter title AND either has a page number or isn't followed by a heading gap
            if (isTopOfPage && knownChapterTitles.contains(normLine) && hasOuterPageToken) {
                return true
            }
        }

        // 5. Purely decorative separator lines in headers/footers (e.g. "* * *", "────────", ".........")
        if (raw.all { !it.isLetterOrDigit() }) {
            return true
        }

        return false
    }

    /**
     * Normalizes a candidate running header/footer line by stripping leading/trailing page numbers,
     * Roman numerals, punctuation, and digits so "14   Chapter 1: Fieldwork" and "Chapter 1: Fieldwork   15"
     * normalize to the same running template.
     */
    internal fun normalizeHeaderFooterComparison(text: String): String {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ""
        val strippedEdgeNumbers = tokens.toMutableList()
        if (strippedEdgeNumbers.isNotEmpty() && PdfDocumentRenderer.isValidPageNumberToken(strippedEdgeNumbers.first())) {
            strippedEdgeNumbers.removeAt(0)
        }
        if (strippedEdgeNumbers.isNotEmpty() && PdfDocumentRenderer.isValidPageNumberToken(strippedEdgeNumbers.last())) {
            strippedEdgeNumbers.removeAt(strippedEdgeNumbers.size - 1)
        }
        return strippedEdgeNumbers.joinToString(" ")
            .lowercase(Locale.ROOT)
            .replace(Regex("(?:\\bpage\\b|\\bp\\.\\b|\\bpp\\.\\b)"), "")
            .replace(Regex("[^a-zæøå\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private data class ColumnAnalysisResult(
        val orderedLines: List<VisualLine>,
        val isTwoColumnAcademic: Boolean,
        val hasUnreliableMultiColumnOrOverlap: Boolean,
        val complexRegionRect: RectF? = null
    )

    /**
     * Detects whether a page uses a single-column layout, a clean two-column academic layout,
     * or an overlapping / 3+ column complex layout where reading order is uncertain.
     */
    private fun analyzeAndOrderPageColumns(lines: List<VisualLine>): ColumnAnalysisResult {
        if (lines.size < 6) {
            return ColumnAnalysisResult(lines, isTwoColumnAcademic = false, hasUnreliableMultiColumnOrOverlap = false)
        }

        // Check if words inside individual lines have multiple horizontal column jumps around the page center (x ~ 0.45..0.55)
        // Because PDFHelper groups glyphs by Y-coordinate into `lineIndex`, a 2-column page will either have:
        // (a) lines whose words have a large horizontal gutter gap near the middle of the page, or
        // (b) separate narrow lines in left half (< 0.49) and right half (> 0.51).
        val splitLines = mutableListOf<VisualLine>()
        var gutterSplitCount = 0
        var multiGutterCount = 0

        for (line in lines) {
            val segments = splitLineByColumnGutters(line)
            if (segments.size == 2) {
                gutterSplitCount++
            } else if (segments.size >= 3) {
                multiGutterCount++
            }
            splitLines.addAll(segments)
        }

        // Check for overlapping text boxes or 3+ column jumps that cannot be ordered reliably
        if (multiGutterCount >= 3) {
            return ColumnAnalysisResult(
                orderedLines = lines,
                isTwoColumnAcademic = false,
                hasUnreliableMultiColumnOrOverlap = true,
                complexRegionRect = RectF(
                    lines.minOf { it.leftRatio }.coerceIn(0f, 0.2f),
                    lines.minOf { it.topRatio }.coerceIn(0f, 0.2f),
                    lines.maxOf { it.rightRatio }.coerceIn(0.8f, 1f),
                    lines.maxOf { it.bottomRatio }.coerceIn(0.8f, 1f)
                )
            )
        }

        val leftColLines = splitLines.filter { it.rightRatio <= 0.53f && (it.rightRatio - it.leftRatio) >= 0.12f }
        val rightColLines = splitLines.filter { it.leftRatio >= 0.47f && (it.rightRatio - it.leftRatio) >= 0.12f }
        val hasOverlappingYRange = if (leftColLines.isNotEmpty() && rightColLines.isNotEmpty()) {
            val leftMinY = leftColLines.minOf { it.topRatio }
            val leftMaxY = leftColLines.maxOf { it.bottomRatio }
            val rightMinY = rightColLines.minOf { it.topRatio }
            val rightMaxY = rightColLines.maxOf { it.bottomRatio }
            min(leftMaxY, rightMaxY) - max(leftMinY, rightMinY) >= 0.12f
        } else {
            false
        }

        if ((gutterSplitCount >= 3 || hasOverlappingYRange) && leftColLines.size >= 3 && rightColLines.size >= 3) {
            // Reconstruct clean academic 2-column reading order:
            // Full-width title/abstract at top -> Left column top-to-bottom -> Right column top-to-bottom -> Full-width footer/notes at bottom
            val minColTop = min(leftColLines.minOf { it.topRatio }, rightColLines.minOf { it.topRatio })
            val maxColBottom = max(leftColLines.maxOf { it.bottomRatio }, rightColLines.maxOf { it.bottomRatio })

            val topFullWidth = mutableListOf<VisualLine>()
            val leftCol = mutableListOf<VisualLine>()
            val rightCol = mutableListOf<VisualLine>()
            val bottomFullWidth = mutableListOf<VisualLine>()

            for (seg in splitLines) {
                val width = seg.rightRatio - seg.leftRatio
                val centerX = (seg.leftRatio + seg.rightRatio) * 0.5f
                when {
                    width > 0.58f && seg.topRatio <= minColTop + 0.05f -> topFullWidth.add(seg)
                    width > 0.58f && seg.bottomRatio >= maxColBottom - 0.05f -> bottomFullWidth.add(seg)
                    centerX < 0.50f -> leftCol.add(seg)
                    else -> rightCol.add(seg)
                }
            }

            val reindexed = mutableListOf<VisualLine>()
            var idx = 0
            for (l in topFullWidth.sortedBy { it.topRatio }) reindexed.add(l.copy(lineIndex = idx++))
            for (l in leftCol.sortedBy { it.topRatio }) reindexed.add(l.copy(lineIndex = idx++))
            for (l in rightCol.sortedBy { it.topRatio }) reindexed.add(l.copy(lineIndex = idx++))
            for (l in bottomFullWidth.sortedBy { it.topRatio }) reindexed.add(l.copy(lineIndex = idx++))

            return ColumnAnalysisResult(
                orderedLines = reindexed,
                isTwoColumnAcademic = true,
                hasUnreliableMultiColumnOrOverlap = false
            )
        }

        return ColumnAnalysisResult(
            orderedLines = lines,
            isTwoColumnAcademic = false,
            hasUnreliableMultiColumnOrOverlap = false
        )
    }

    /**
     * Splits a single horizontal VisualLine if there is a wide column gutter gap (> 0.045 of page width)
     * between left-column words and right-column words.
     */
    private fun splitLineByColumnGutters(line: VisualLine): List<VisualLine> {
        if (line.words.size < 2) return listOf(line)
        val sorted = line.words.sortedBy { it.leftRatio }
        val chunks = mutableListOf<MutableList<PdfPageWord>>()
        var currentChunk = mutableListOf(sorted.first())

        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val cur = sorted[i]
            val gap = cur.leftRatio - prev.rightRatio
            val crossesCenterGutter = prev.rightRatio <= 0.53f && cur.leftRatio >= 0.47f && gap >= 0.038f
            val wideTabularGap = gap >= 0.085f
            if (crossesCenterGutter || wideTabularGap) {
                chunks.add(currentChunk)
                currentChunk = mutableListOf(cur)
            } else {
                currentChunk.add(cur)
            }
        }
        chunks.add(currentChunk)

        if (chunks.size == 1) return listOf(line)
        return chunks.mapNotNull { wList ->
            if (wList.isEmpty()) return@mapNotNull null
            val txt = wList.joinToString(" ") { it.text }.trim()
            if (txt.isEmpty()) return@mapNotNull null
            line.copy(
                words = wList,
                text = txt,
                leftRatio = wList.minOf { it.leftRatio },
                topRatio = wList.minOf { it.topRatio },
                rightRatio = wList.maxOf { it.rightRatio },
                bottomRatio = wList.maxOf { it.bottomRatio }
            )
        }
    }

    /**
     * Detects large vertical blank regions on a page (> 22% of page height) that correspond to
     * embedded figures, charts, diagrams, or illustrations.
     */
    private fun detectEmbeddedFigureRegions(lines: List<VisualLine>): List<RectF> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedBy { it.topRatio }
        val regions = mutableListOf<RectF>()

        for (i in 0 until sorted.size - 1) {
            val upper = sorted[i]
            val lower = sorted[i + 1]
            val verticalGap = lower.topRatio - upper.bottomRatio
            val hasFigureCaptionNearby = FIGURE_CAPTION_REGEX.matches(upper.text.trim()) ||
                FIGURE_CAPTION_REGEX.matches(lower.text.trim())
            if (verticalGap >= 0.24f || (verticalGap >= 0.14f && hasFigureCaptionNearby)) {
                regions.add(
                    RectF(
                        0.05f,
                        (upper.bottomRatio + 0.01f).coerceIn(0f, 0.90f),
                        0.95f,
                        (lower.topRatio - 0.01f).coerceIn(0.10f, 1f)
                    )
                )
            }
        }
        return regions
    }

    private fun isTableLine(line: VisualLine): Boolean {
        if (TABLE_CAPTION_REGEX.matches(line.text.trim())) return true
        if (line.words.size < 3) return false
        var wideGaps = 0
        val sorted = line.words.sortedBy { it.leftRatio }
        for (i in 1 until sorted.size) {
            val gap = sorted[i].leftRatio - sorted[i - 1].rightRatio
            if (gap >= 0.055f) wideGaps++
        }
        val numericTokens = sorted.count { it.text.any { ch -> ch.isDigit() } }
        return wideGaps >= 2 || (wideGaps >= 1 && numericTokens >= 3 && sorted.size <= 10)
    }

    private fun isMathFormulaLine(line: VisualLine): Boolean {
        val txt = line.text.trim()
        val symbolMatches = MATH_SYMBOLS_REGEX.findAll(txt).count()
        if (symbolMatches >= 2) return true
        val eqCount = txt.count { it == '=' || it == '+' || it == '^' || it == '_' }
        return symbolMatches >= 1 && eqCount >= 2 && line.words.size <= 14
    }

    private fun segmentLinesIntoBlocks(
        lines: List<VisualLine>,
        pdfPageNumber: Int,
        printedLabel: String,
        initialChapterTitle: String,
        pageFootnotes: Map<String, String>,
        figureGaps: List<RectF>,
        nextBlockId: () -> String,
        onChapterTitleChanged: (String) -> Unit
    ): List<PdfReflowBlock> {
        if (lines.isEmpty()) return emptyList()

        val blocks = mutableListOf<PdfReflowBlock>()
        var currentChapter = initialChapterTitle
        val medianLeft = lines.map { it.leftRatio }.sorted().getOrElse(lines.size / 2) { 0.10f }
        val medianRight = lines.map { it.rightRatio }.sorted().getOrElse(lines.size / 2) { 0.88f }
        val medianHeight = lines.map { it.heightRatio }.sorted().getOrElse(lines.size / 2) { 0.018f }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.text.trim()

            // Check if a detected figure gap precedes this line
            for (gap in figureGaps) {
                if (abs(line.topRatio - gap.bottom) <= 0.03f &&
                    blocks.none { it.cropRectNormalized == gap }
                ) {
                    val captionText = if (FIGURE_CAPTION_REGEX.matches(trimmed)) " ($trimmed)" else ""
                    blocks.add(
                        PdfReflowBlock(
                            id = nextBlockId(),
                            type = PdfReflowBlockType.UNSUPPORTED_ELEMENT,
                            text = "[Unsupported PDF Element: ${PdfUnsupportedReason.CHART_OR_DIAGRAM_OR_IMAGE.displayTitle}$captionText on PDF page $pdfPageNumber" +
                                (if (printedLabel.isNotBlank()) " (printed page $printedLabel)" else "") +
                                " — Visual snapshot preserved]",
                            chapterTitle = currentChapter,
                            startPdfPage = pdfPageNumber,
                            endPdfPage = pdfPageNumber,
                            startPrintedPage = printedLabel,
                            endPrintedPage = printedLabel,
                            unsupportedReason = PdfUnsupportedReason.CHART_OR_DIAGRAM_OR_IMAGE,
                            cropRectNormalized = gap
                        )
                    )
                }
            }

            // 1. Complex Table detection (2+ consecutive tabular lines or explicit Table caption + tabular rows)
            if (isTableLine(line)) {
                val tableLines = mutableListOf(line)
                var j = i + 1
                while (j < lines.size && (isTableLine(lines[j]) || (lines[j].topRatio - tableLines.last().bottomRatio <= medianHeight * 2.2f && lines[j].words.size <= 8))) {
                    if (!isTableLine(lines[j]) && j + 1 < lines.size && !isTableLine(lines[j + 1])) break
                    tableLines.add(lines[j])
                    j++
                }
                if (tableLines.size >= 2 || TABLE_CAPTION_REGEX.matches(trimmed)) {
                    val tableTextRows = tableLines.joinToString("\n") { tl ->
                        tl.words.sortedBy { it.leftRatio }.joinToString("  |  ") { it.text }
                    }
                    val crop = RectF(
                        (tableLines.minOf { it.leftRatio } - 0.02f).coerceIn(0f, 1f),
                        (tableLines.minOf { it.topRatio } - 0.02f).coerceIn(0f, 1f),
                        (tableLines.maxOf { it.rightRatio } + 0.02f).coerceIn(0f, 1f),
                        (tableLines.maxOf { it.bottomRatio } + 0.02f).coerceIn(0f, 1f)
                    )
                    blocks.add(
                        PdfReflowBlock(
                            id = nextBlockId(),
                            type = PdfReflowBlockType.UNSUPPORTED_ELEMENT,
                            text = "[Unsupported PDF Element: ${PdfUnsupportedReason.COMPLEX_TABLE.displayTitle} on PDF page $pdfPageNumber" +
                                (if (printedLabel.isNotBlank()) " (printed page $printedLabel)" else "") +
                                "]\n$tableTextRows",
                            chapterTitle = currentChapter,
                            startPdfPage = pdfPageNumber,
                            endPdfPage = pdfPageNumber,
                            startPrintedPage = printedLabel,
                            endPrintedPage = printedLabel,
                            unsupportedReason = PdfUnsupportedReason.COMPLEX_TABLE,
                            cropRectNormalized = crop,
                            footnotes = pageFootnotes
                        )
                    )
                    i = j
                    continue
                }
            }

            // 2. Mathematical Equation / Formula block detection
            if (isMathFormulaLine(line)) {
                val crop = RectF(
                    (line.leftRatio - 0.03f).coerceIn(0f, 1f),
                    (line.topRatio - 0.02f).coerceIn(0f, 1f),
                    (line.rightRatio + 0.03f).coerceIn(0f, 1f),
                    (line.bottomRatio + 0.02f).coerceIn(0f, 1f)
                )
                blocks.add(
                    PdfReflowBlock(
                        id = nextBlockId(),
                        type = PdfReflowBlockType.UNSUPPORTED_ELEMENT,
                        text = "[Unsupported PDF Element: ${PdfUnsupportedReason.MATH_EQUATION_OR_FORMULA.displayTitle} on PDF page $pdfPageNumber" +
                            (if (printedLabel.isNotBlank()) " (printed page $printedLabel)" else "") +
                            "]\n$trimmed",
                        chapterTitle = currentChapter,
                        startPdfPage = pdfPageNumber,
                        endPdfPage = pdfPageNumber,
                        startPrintedPage = printedLabel,
                        endPrintedPage = printedLabel,
                        unsupportedReason = PdfUnsupportedReason.MATH_EQUATION_OR_FORMULA,
                        cropRectNormalized = crop,
                        footnotes = pageFootnotes
                    )
                )
                i++
                continue
            }

            // 3. Interactive Form / Widget element detection
            if (INTERACTIVE_FORM_REGEX.containsMatchIn(trimmed)) {
                val crop = RectF(
                    (line.leftRatio - 0.02f).coerceIn(0f, 1f),
                    (line.topRatio - 0.02f).coerceIn(0f, 1f),
                    (line.rightRatio + 0.02f).coerceIn(0f, 1f),
                    (line.bottomRatio + 0.02f).coerceIn(0f, 1f)
                )
                blocks.add(
                    PdfReflowBlock(
                        id = nextBlockId(),
                        type = PdfReflowBlockType.UNSUPPORTED_ELEMENT,
                        text = "[Unsupported PDF Element: ${PdfUnsupportedReason.INTERACTIVE_FORM_OR_MEDIA.displayTitle} on PDF page $pdfPageNumber]\n$trimmed",
                        chapterTitle = currentChapter,
                        startPdfPage = pdfPageNumber,
                        endPdfPage = pdfPageNumber,
                        startPrintedPage = printedLabel,
                        endPrintedPage = printedLabel,
                        unsupportedReason = PdfUnsupportedReason.INTERACTIVE_FORM_OR_MEDIA,
                        cropRectNormalized = crop,
                        footnotes = pageFootnotes
                    )
                )
                i++
                continue
            }

            // 4. Chapter Heading or Subheading detection
            val isTallerFont = line.heightRatio >= medianHeight * 1.18f
            val isCenteredHeading = (line.leftRatio - medianLeft) >= 0.06f &&
                (medianRight - line.rightRatio) >= 0.06f &&
                line.words.size in 1..12 &&
                !trimmed.endsWith(".")
            val isExplicitHeading = CHAPTER_HEADING_REGEX.matches(trimmed) || NUMBERED_HEADING_REGEX.matches(trimmed)
            val isAllCapsShortHeading = line.words.size in 1..9 &&
                trimmed.length in 3..70 &&
                trimmed.any { it.isLetter() } &&
                trimmed.filter { it.isLetter() }.all { it.isUpperCase() }

            if (isExplicitHeading || ((isTallerFont || isCenteredHeading || isAllCapsShortHeading) && line.words.size <= 12 && !trimmed.endsWith(","))) {
                val headingText = trimmed.removePrefix("## ").trim()
                if (CHAPTER_HEADING_REGEX.matches(headingText) || isTallerFont || isAllCapsShortHeading) {
                    currentChapter = headingText
                    onChapterTitleChanged(headingText)
                }
                blocks.add(
                    PdfReflowBlock(
                        id = nextBlockId(),
                        type = if (CHAPTER_HEADING_REGEX.matches(headingText) || isTallerFont) {
                            PdfReflowBlockType.CHAPTER_HEADING
                        } else {
                            PdfReflowBlockType.SUBHEADING
                        },
                        text = "## $headingText",
                        chapterTitle = currentChapter,
                        startPdfPage = pdfPageNumber,
                        endPdfPage = pdfPageNumber,
                        startPrintedPage = printedLabel,
                        endPrintedPage = printedLabel,
                        footnotes = pageFootnotes
                    )
                )
                i++
                continue
            }

            // 5. Footnote line at bottom of page
            if (line.topRatio >= 0.74f && FOOTNOTE_LINE_REGEX.matches(trimmed)) {
                blocks.add(
                    PdfReflowBlock(
                        id = nextBlockId(),
                        type = PdfReflowBlockType.FOOTNOTE,
                        text = trimmed,
                        chapterTitle = currentChapter,
                        startPdfPage = pdfPageNumber,
                        endPdfPage = pdfPageNumber,
                        startPrintedPage = printedLabel,
                        endPrintedPage = printedLabel,
                        footnotes = pageFootnotes
                    )
                )
                i++
                continue
            }

            // 6. List item detection (bulleted or numbered list)
            if (BULLET_LIST_REGEX.matches(trimmed)) {
                val itemLines = mutableListOf(line)
                var j = i + 1
                while (j < lines.size) {
                    val nextL = lines[j]
                    val nextTrim = nextL.text.trim()
                    val vGap = nextL.topRatio - itemLines.last().bottomRatio
                    if (BULLET_LIST_REGEX.matches(nextTrim) ||
                        CHAPTER_HEADING_REGEX.matches(nextTrim) ||
                        vGap > medianHeight * 1.65f
                    ) {
                        break
                    }
                    itemLines.add(nextL)
                    j++
                }
                val joinedListText = joinVisualLinesToProse(itemLines)
                val normalizedBullet = if (joinedListText.startsWith("•")) {
                    joinedListText
                } else if (Regex("^[\\-*–—]\\s+").containsMatchIn(joinedListText)) {
                    "• " + joinedListText.replaceFirst(Regex("^[\\-*–—]\\s+"), "")
                } else {
                    joinedListText
                }
                blocks.add(
                    PdfReflowBlock(
                        id = nextBlockId(),
                        type = PdfReflowBlockType.LIST_ITEM,
                        text = normalizedBullet,
                        chapterTitle = currentChapter,
                        startPdfPage = pdfPageNumber,
                        endPdfPage = pdfPageNumber,
                        startPrintedPage = printedLabel,
                        endPrintedPage = printedLabel,
                        footnotes = pageFootnotes
                    )
                )
                i = j
                continue
            }

            // 7. Standard Paragraph or Blockquote accumulation
            val paraLines = mutableListOf(line)
            val isIndentedBlockquote = (line.leftRatio - medianLeft) >= 0.045f &&
                (i + 1 < lines.size && (lines[i + 1].leftRatio - medianLeft) >= 0.045f)

            var j = i + 1
            while (j < lines.size) {
                val prevL = paraLines.last()
                val nextL = lines[j]
                val nextTrim = nextL.text.trim()
                val vGap = nextL.topRatio - prevL.bottomRatio

                // Stop paragraph if next line is a heading, list item, table, formula, footnote, or separated by a paragraph vertical gap
                if (vGap > max(medianHeight * 1.48f, 0.024f) ||
                    CHAPTER_HEADING_REGEX.matches(nextTrim) ||
                    NUMBERED_HEADING_REGEX.matches(nextTrim) ||
                    BULLET_LIST_REGEX.matches(nextTrim) ||
                    isTableLine(nextL) ||
                    isMathFormulaLine(nextL) ||
                    (nextL.topRatio >= 0.74f && FOOTNOTE_LINE_REGEX.matches(nextTrim))
                ) {
                    break
                }

                // Also start a new paragraph if the previous line ended with sentence punctuation AND
                // either didn't reach the right margin or the next line has a first-line paragraph indent
                val prevEndedSentence = prevL.text.trim().lastOrNull() in listOf('.', '!', '?', '"', '”', '’', ':')
                val prevShortRight = (medianRight - prevL.rightRatio) >= 0.07f
                val nextHasFirstLineIndent = (nextL.leftRatio - medianLeft) >= 0.025f && !isIndentedBlockquote
                if (prevEndedSentence && (prevShortRight || nextHasFirstLineIndent)) {
                    break
                }

                paraLines.add(nextL)
                j++
            }

            val joinedProse = joinVisualLinesToProse(paraLines)
            val formattedText = if (isIndentedBlockquote && !joinedProse.startsWith("— ") && !joinedProse.startsWith("“")) {
                "“$joinedProse”"
            } else {
                joinedProse
            }

            blocks.add(
                PdfReflowBlock(
                    id = nextBlockId(),
                    type = if (isIndentedBlockquote) PdfReflowBlockType.BLOCKQUOTE else PdfReflowBlockType.PARAGRAPH,
                    text = formattedText,
                    chapterTitle = currentChapter,
                    startPdfPage = pdfPageNumber,
                    endPdfPage = pdfPageNumber,
                    startPrintedPage = printedLabel,
                    endPrintedPage = printedLabel,
                    footnotes = pageFootnotes
                )
            )
            i = j
        }

        return blocks
    }

    /**
     * Joins visual lines within a paragraph into clean reflowable prose, automatically healing
     * end-of-line hyphenation (e.g., "ethno-\ngraphic" -> "ethnographic").
     */
    private fun joinVisualLinesToProse(lines: List<VisualLine>): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()
        for ((idx, line) in lines.withIndex()) {
            val clean = line.text.trim()
            if (clean.isEmpty()) continue
            if (idx == 0) {
                sb.append(clean)
            } else {
                val firstNextChar = clean.firstOrNull()
                if (sb.endsWith("-") &&
                    sb.length >= 2 &&
                    sb[sb.length - 2].isLetter() &&
                    firstNextChar != null &&
                    firstNextChar.isLowerCase()
                ) {
                    // Heal hyphenated line wrap
                    sb.setLength(sb.length - 1)
                    sb.append(clean)
                } else {
                    sb.append(' ').append(clean)
                }
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Merges paragraphs that were split across two consecutive PDF pages (e.g., page N ends mid-sentence
     * without terminal punctuation, and page N+1 begins with a continuation paragraph).
     * The resulting merged block records `startPdfPage = N, endPdfPage = N+1` and
     * `startPrintedPage = printed(N), endPrintedPage = printed(N+1)`!
     */
    private fun mergeCrossPageParagraphs(blocks: List<PdfReflowBlock>): List<PdfReflowBlock> {
        if (blocks.size < 2) return blocks
        val result = mutableListOf<PdfReflowBlock>()
        var i = 0
        while (i < blocks.size) {
            val current = blocks[i]
            if (i + 1 < blocks.size &&
                current.type == PdfReflowBlockType.PARAGRAPH &&
                blocks[i + 1].type == PdfReflowBlockType.PARAGRAPH &&
                blocks[i + 1].startPdfPage == current.endPdfPage + 1
            ) {
                val next = blocks[i + 1]
                val curTrim = current.text.trim()
                val nextTrim = next.text.trim()
                val curLastChar = curTrim.lastOrNull()
                val nextFirstChar = nextTrim.firstOrNull()

                val curEndsIncomplete = curLastChar != null &&
                    curLastChar !in charArrayOf('.', '!', '?', ':', ';', '"', '”', '’')
                val nextStartsLower = nextFirstChar != null && nextFirstChar.isLowerCase()

                if (curEndsIncomplete || nextStartsLower) {
                    val joinedText = if (curTrim.endsWith("-") && curTrim.length >= 2 && curTrim[curTrim.length - 2].isLetter() && nextStartsLower) {
                        curTrim.dropLast(1) + nextTrim
                    } else {
                        "$curTrim $nextTrim"
                    }
                    val merged = current.copy(
                        text = joinedText,
                        endPdfPage = next.endPdfPage,
                        endPrintedPage = next.endPrintedPage.ifBlank { current.endPrintedPage },
                        footnotes = current.footnotes + next.footnotes
                    )
                    result.add(merged)
                    i += 2
                    continue
                }
            }
            result.add(current)
            i++
        }
        return result
    }

    private fun buildEpubBookFromReflowBlocks(
        title: String,
        author: String,
        blocks: List<PdfReflowBlock>
    ): EpubBook {
        if (blocks.isEmpty()) {
            return EpubBook(
                title = title,
                author = author,
                description = "PDF-to-Ebook Reflow View",
                coverBitmap = null,
                chapters = listOf(
                    EpubChapter(
                        id = "pdf_ch_1",
                        title = title.ifBlank { "Document" },
                        content = "",
                        paragraphs = listOf("No readable content extracted.")
                    )
                )
            )
        }

        // Group blocks into chapters whenever a CHAPTER_HEADING or new chapterTitle appears
        val chapters = mutableListOf<EpubChapter>()
        var currentChapterTitle = blocks.first().chapterTitle.ifBlank { title.ifBlank { "Chapter 1" } }
        var currentParagraphs = mutableListOf<String>()
        var currentFootnotes = mutableMapOf<String, String>()
        var chapterCounter = 1

        fun flushChapter() {
            if (currentParagraphs.isNotEmpty()) {
                chapters.add(
                    EpubChapter(
                        id = "pdf_ch_${chapterCounter++}",
                        title = currentChapterTitle,
                        content = currentParagraphs.joinToString("\n\n"),
                        paragraphs = currentParagraphs.toList(),
                        footnotes = currentFootnotes.toMap()
                    )
                )
                currentParagraphs = mutableListOf()
                currentFootnotes = mutableMapOf()
            }
        }

        for (block in blocks) {
            if (block.type == PdfReflowBlockType.CHAPTER_HEADING) {
                val headingClean = block.text.removePrefix("## ").trim()
                if (currentParagraphs.isNotEmpty() && !headingClean.equals(currentChapterTitle, ignoreCase = true)) {
                    flushChapter()
                }
                currentChapterTitle = headingClean.ifBlank { currentChapterTitle }
                currentFootnotes.putAll(block.footnotes)
                currentParagraphs.add(block.text)
            } else {
                if (block.chapterTitle.isNotBlank() &&
                    !block.chapterTitle.equals(currentChapterTitle, ignoreCase = true) &&
                    currentParagraphs.isNotEmpty()
                ) {
                    flushChapter()
                    currentChapterTitle = block.chapterTitle
                }
                currentFootnotes.putAll(block.footnotes)
                currentParagraphs.add(block.text)
            }
        }
        flushChapter()

        return EpubBook(
            title = title,
            author = author,
            description = "PDF-to-Ebook Reflow View",
            coverBitmap = null,
            chapters = chapters
        )
    }
}
