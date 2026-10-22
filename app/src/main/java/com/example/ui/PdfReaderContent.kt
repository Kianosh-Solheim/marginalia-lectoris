package com.example.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.model.parseHighlightColor
import com.example.reader.BookChapter
import com.example.reader.PdfChapterHelper
import com.example.reader.PdfDocumentRenderer
import com.example.reader.PdfPageTextData
import com.example.reader.PdfPageWord
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Helper to find the closest PDF word index at a given normalized (ratioX, ratioY) position on the PDF page.
 */
private fun findClosestPdfWordIndex(
    words: List<PdfPageWord>,
    ratioX: Float,
    ratioY: Float
): Int? {
    if (words.isEmpty()) return null
    val rx = ratioX.coerceIn(0f, 1f)
    val ry = ratioY.coerceIn(0f, 1f)

    // 1. Direct hit inside a word bounding box (with generous vertical/horizontal touch padding)
    val directHit = words.firstOrNull { w ->
        val padY = (w.bottomRatio - w.topRatio).coerceAtLeast(0.012f) * 0.35f
        val padX = 0.008f
        rx in (w.leftRatio - padX)..(w.rightRatio + padX) &&
            ry in (w.topRatio - padY)..(w.bottomRatio + padY)
    }
    if (directHit != null) return directHit.wordIndex

    // 2. Find words on the same or closest line first, then pick the closest horizontally
    val closestLineWords = words.filter { w ->
        val centerY = (w.topRatio + w.bottomRatio) * 0.5f
        val lineHalfH = ((w.bottomRatio - w.topRatio).coerceAtLeast(0.015f)) * 1.15f
        kotlin.math.abs(ry - centerY) <= lineHalfH
    }
    if (closestLineWords.isNotEmpty()) {
        return closestLineWords.minByOrNull { w ->
            when {
                rx < w.leftRatio -> w.leftRatio - rx
                rx > w.rightRatio -> rx - w.rightRatio
                else -> 0f
            }
        }?.wordIndex
    }

    // 3. Fallback: nearest word by weighted Euclidean distance (vertical distance weighted 3x so we stay on the intended line)
    return words.minByOrNull { w ->
        val cx = (w.leftRatio + w.rightRatio) * 0.5f
        val cy = (w.topRatio + w.bottomRatio) * 0.5f
        val dx = rx - cx
        val dy = (ry - cy) * 3.0f
        dx * dx + dy * dy
    }?.wordIndex
}

/**
 * Joins a contiguous range of [PdfPageWord]s into clean readable prose, preserving line breaks
 * and re-joining hyphenated line-wraps cleanly.
 */
private fun buildQuoteFromPdfWords(
    words: List<PdfPageWord>,
    startWordIndex: Int,
    endWordIndex: Int
): String {
    if (words.isEmpty()) return ""
    val s = minOf(startWordIndex, endWordIndex).coerceIn(0, words.size - 1)
    val e = maxOf(startWordIndex, endWordIndex).coerceIn(0, words.size - 1)
    val slice = words.subList(s, e + 1)
    if (slice.isEmpty()) return ""

    val sb = StringBuilder()
    var prevLine = slice.first().lineIndex
    for ((i, w) in slice.withIndex()) {
        if (i > 0) {
            if (w.lineIndex != prevLine) {
                // If previous line ended with a hyphenated word wrap, join seamlessly or with a space
                if (sb.endsWith("-") && sb.length >= 2 && Character.isLetter(sb[sb.length - 2]) && w.text.firstOrNull()?.isLowerCase() == true) {
                    sb.setLength(sb.length - 1)
                } else {
                    sb.append(' ')
                }
                prevLine = w.lineIndex
            } else {
                sb.append(' ')
            }
        }
        sb.append(w.text)
    }
    return sb.toString().trim()
}

/**
 * Matches saved [HighlightEntity] quotes back onto the PDF page's extracted [PdfPageWord] list
 * so saved highlights render directly over the PDF page and can be tapped to edit!
 * Supports both single-page highlights and highlights spanning across a PDF page boundary
 * (matching the full quote, prefix segment at bottom of page N, or suffix segment at top of page N+1).
 */
/** First part of a selection that continues on the next page (one note for both parts). */
private data class CarriedPdfSelection(val startPage: Int, val quote: String, val printed: String, val chapterTitle: String)

/** Joins the two parts of a selection across a page break, mending a word hyphenated at the break. */
private fun joinAcrossPages(first: String, second: String): String {
    val a = first.trimEnd(); val b = second.trimStart()
    if (a.isEmpty()) return b
    if (b.isEmpty()) return a
    return if (a.endsWith("-") && a.length > 1 && a[a.length - 2].isLetter() && b.first().isLowerCase()) a.dropLast(1) + b else "$a $b"
}

private fun matchSavedHighlightsOnPdfWords(
    words: List<PdfPageWord>,
    pageHighlights: List<HighlightEntity>
): List<Triple<Int, Int, HighlightEntity>> {
    if (words.isEmpty() || pageHighlights.isEmpty()) return emptyList()
    val results = mutableListOf<Triple<Int, Int, HighlightEntity>>()

    val normalizedPageWords = words.map {
        it.text.lowercase().replace(Regex("[^a-z0-9æøå]"), "")
    }

    for (h in pageHighlights) {
        val quoteTokens = h.highlightedText
            .split(Regex("\\s+"))
            .map { it.lowercase().replace(Regex("[^a-z0-9æøå]"), "") }
            .filter { it.isNotEmpty() }
        if (quoteTokens.isEmpty()) continue

        var matchedStart = -1
        var matchedEnd = -1

        // 1. Search for full sequence or leading prefix sequence match on this PDF page
        val probeCount = minOf(quoteTokens.size, 6)
        for (i in 0..(normalizedPageWords.size - probeCount).coerceAtLeast(0)) {
            var allMatch = true
            for (k in 0 until probeCount) {
                val pageTok = normalizedPageWords.getOrNull(i + k) ?: ""
                val qTok = quoteTokens[k]
                if (pageTok != qTok && !pageTok.contains(qTok) && !qTok.contains(pageTok)) {
                    allMatch = false
                    break
                }
            }
            if (allMatch) {
                matchedStart = i
                // Extend as far as quoteTokens continue matching on this page (or to the end of the page if it wraps to the next page)
                var endCandidate = i + probeCount - 1
                var qIdx = probeCount
                var pIdx = i + probeCount
                while (qIdx < quoteTokens.size && pIdx < normalizedPageWords.size) {
                    val pTok = normalizedPageWords[pIdx]
                    val qTok = quoteTokens[qIdx]
                    if (pTok == qTok || pTok.contains(qTok) || qTok.contains(pTok)) {
                        endCandidate = pIdx
                        qIdx++
                        pIdx++
                    } else if (pIdx + 1 < normalizedPageWords.size &&
                        (normalizedPageWords[pIdx + 1] == qTok || (pTok + normalizedPageWords[pIdx + 1]) == qTok)
                    ) {
                        // Hyphenated word split across two PDF line tokens
                        endCandidate = pIdx + 1
                        qIdx++
                        pIdx += 2
                    } else {
                        break
                    }
                }
                matchedEnd = maxOf(endCandidate, (i + quoteTokens.size - 1).coerceAtMost(words.size - 1))
                break
            }
        }

        // 2. If the highlight started on the previous PDF page and continues onto the top of THIS PDF page,
        //    match the trailing suffix of quoteTokens against the words on this page!
        if (matchedStart < 0 && quoteTokens.size >= 4) {
            val suffixProbeCount = minOf(quoteTokens.size, 5)
            val suffixTokens = quoteTokens.takeLast(suffixProbeCount)
            for (i in (normalizedPageWords.size - suffixProbeCount).coerceAtLeast(0) downTo 0) {
                var allSuffixMatch = true
                for (k in 0 until suffixProbeCount) {
                    val pageTok = normalizedPageWords.getOrNull(i + k) ?: ""
                    val qTok = suffixTokens[k]
                    if (pageTok != qTok && !pageTok.contains(qTok) && !qTok.contains(pageTok)) {
                        allSuffixMatch = false
                        break
                    }
                }
                if (allSuffixMatch) {
                    val suffixEnd = (i + suffixProbeCount - 1).coerceIn(0, words.size - 1)
                    // Walk backwards from suffixEnd as far as preceding quoteTokens match on this page
                    var startCandidate = i
                    var qBack = quoteTokens.size - suffixProbeCount - 1
                    var pBack = i - 1
                    while (qBack >= 0 && pBack >= 0) {
                        val pTok = normalizedPageWords[pBack]
                        val qTok = quoteTokens[qBack]
                        if (pTok == qTok || pTok.contains(qTok) || qTok.contains(pTok)) {
                            startCandidate = pBack
                            qBack--
                            pBack--
                        } else {
                            break
                        }
                    }
                    matchedStart = startCandidate
                    matchedEnd = suffixEnd
                    break
                }
            }
        }

        if (matchedStart >= 0 && matchedEnd >= matchedStart) {
            results.add(Triple(matchedStart, matchedEnd, h))
        }
    }
    return results
}

/**
 * PDF Reader using HorizontalPager with real word-level PDF text extraction & selection:
 * - Long-press or double-tap any word on a PDF page to select real PDF text with KOReader handles
 * - Drag across words, lines, and paragraphs just like EPUBs
 * - Adjust selection with handles or "+ Word" / "- Word" / "+ Next Para" / "Select All"
 * - Tap middle: toggles full-screen reading mode on/off
 * - Tap left 20%: previous page
 * - Tap right 20%: next page
 * - Swipe left/right: flips pages with physical 3D curl
 * - Physical volume buttons: flips pages
 */
@Composable
fun PdfReaderPagerContent(
    document: DocumentEntity,
    initialPage: Int,
    isUiVisible: Boolean,
    activeTheme: ReaderTheme,
    viewModeSetting: String = "PAGINATED",
    pageTurnAnimation: String = "PAGE_CURL",
    volumeKeyAnimation: Boolean = true,
    highlights: List<HighlightEntity> = emptyList(),
    pdfEbookDocument: com.example.reader.PdfEbookDocument? = null,
    externalPdfRenderer: PdfDocumentRenderer? = null,
    pageTurnTrigger: SharedFlow<Int>,
    targetPageRequest: Int?,
    onToggleUi: () -> Unit,
    onChaptersLoaded: (List<BookChapter>) -> Unit,
    onPageChanged: (page: Int, total: Int, chapterTitle: String) -> Unit,
    onActivePrintedPageChanged: (String) -> Unit = {},
    onActivePageTextChanged: (String) -> Unit = {},
    onSpeakSelection: (quote: String, page: Int) -> Unit = { _, _ -> },
    onRequestHighlight: (quote: String, page: Int, printedPageLabel: String, chapterTitle: String, colorHex: String) -> Unit,
    onQuickSaveHighlight: (quote: String, page: Int, printedPageLabel: String, chapterTitle: String, colorHex: String) -> Unit = { _, _, _, _, _ -> },
    onAddAsAuthor: (String) -> Unit = {},
    onSetAsAbstract: (String) -> Unit = {},
    onEditHighlight: (HighlightEntity) -> Unit = {},
    showPdfCropDialog: Boolean = false,
    onDismissPdfCropDialog: () -> Unit = {},
    onOpenPdfCropDialog: () -> Unit = {},
    onUpdatePdfCrop: (left: Float, top: Float, right: Float, bottom: Float) -> Unit = { _, _, _, _ -> },
    extractionConfigVersion: Int = 0,
    glossaeEnabled: Boolean = false,
    onOpenPdfTextToolsDialog: (initialTab: Int, selectedRegion: android.graphics.RectF, selectedText: String) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    var localPdfRenderer by remember { mutableStateOf<PdfDocumentRenderer?>(null) }
    val pdfRenderer = externalPdfRenderer ?: localPdfRenderer
    var pageCount by remember(externalPdfRenderer) {
        mutableIntStateOf((externalPdfRenderer?.pageCount ?: document.totalPages).coerceAtLeast(1))
    }
    var chaptersList by remember { mutableStateOf<List<BookChapter>>(emptyList()) }

    LaunchedEffect(document.filePath, externalPdfRenderer) {
        if (externalPdfRenderer != null) {
            pageCount = withContext(Dispatchers.IO) { externalPdfRenderer.pageCount.coerceAtLeast(1) }
        } else {
            val file = File(document.filePath)
            if (file.exists()) {
                val created = withContext(Dispatchers.IO) { PdfDocumentRenderer(file, context) }
                val count = withContext(Dispatchers.IO) { created.pageCount.coerceAtLeast(1) }
                localPdfRenderer?.close()
                localPdfRenderer = created
                pageCount = count
            }
        }
    }

    DisposableEffect(document.filePath, externalPdfRenderer) {
        onDispose {
            localPdfRenderer?.close()
            localPdfRenderer = null
        }
    }

    LaunchedEffect(document.filePath, document.chaptersJson, pageCount) {
        val file = File(document.filePath)
        if (file.exists()) {
            val chapters = withContext(Dispatchers.IO) {
                PdfChapterHelper.extractOrGenerateChapters(file, pageCount.coerceAtLeast(1), document.chaptersJson)
            }
            chaptersList = chapters
            onChaptersLoaded(chapters)
        }
    }

    val pagerState = rememberPagerState(
        initialPage = (initialPage - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
        pageCount = { pageCount }
    )

    var hasRestoredSavedPage by remember(document.id) { mutableStateOf(false) }

    LaunchedEffect(pageCount, initialPage) {
        if (pageCount > 1 && !hasRestoredSavedPage) {
            val target = (initialPage - 1).coerceIn(0, pageCount - 1)
            if (target > 0) {
                pagerState.scrollToPage(target)
            }
            hasRestoredSavedPage = true
        } else if (pageCount == 1) {
            hasRestoredSavedPage = true
        }
    }

    // Handle Volume Button Triggers
    LaunchedEffect(pageTurnTrigger, pageCount, pageTurnAnimation, volumeKeyAnimation) {
        pageTurnTrigger.collect { direction ->
            val target = (pagerState.currentPage + direction).coerceIn(0, pageCount - 1)
            if (target != pagerState.currentPage) {
                val effectiveAnim = if (volumeKeyAnimation) pageTurnAnimation else "NONE"
                pagerState.turnToPageWithAnimation(target, effectiveAnim)
            }
        }
    }

    // Handle TOC click jump request
    LaunchedEffect(targetPageRequest) {
        if (targetPageRequest != null && targetPageRequest in 0 until pageCount) {
            pagerState.turnToPageWithAnimation(targetPageRequest, pageTurnAnimation)
        }
    }

    // Notify active page changed only after restoring saved position
    LaunchedEffect(pagerState.currentPage, hasRestoredSavedPage, chaptersList) {
        if (hasRestoredSavedPage && pageCount > 0) {
            val currentPageNumber = pagerState.currentPage + 1
            val chapterTitle = chaptersList.filter { it.pageNumber <= currentPageNumber }
                .maxByOrNull { it.pageNumber }?.title ?: "Page $currentPageNumber"
            onPageChanged(currentPageNumber, pageCount, chapterTitle)
        }
    }

    val pdfColorFilter = remember(activeTheme) {
        if (activeTheme == ReaderTheme.DARK) {
            ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        } else null
    }

    var isPdfDraggingGlobal by remember { mutableStateOf(false) }
    var isCurrentPageZoomed by remember { mutableStateOf(false) }
    var activeSelectionToolbar by remember { mutableStateOf<ActiveSelectionToolbarState?>(null) }
    var carriedSelection by remember(document.id) { mutableStateOf<CarriedPdfSelection?>(null) }
    var activeSelectionPageIdx by remember { mutableIntStateOf(-1) }
    var activePrintedPageLabel by remember { mutableStateOf("") }

    // Glossae (display only): flipped cards, cards whose note does not fit, and the "… more" preview.
    var glossFlipped by remember(document.id) { mutableStateOf(emptySet<Long>()) }
    var glossOverflowing by remember(document.id) { mutableStateOf(emptySet<Long>()) }
    var glossPreview by remember(document.id) { mutableStateOf<HighlightEntity?>(null) }
    LaunchedEffect(pagerState.currentPage, glossaeEnabled) { glossFlipped = emptySet() }
    glossPreview?.let { previewed ->
        com.example.ui.glossae.GlossNotePreviewDialog(
            highlight = previewed,
            onDismiss = { glossPreview = null },
            onEdit = { onEditHighlight(it) }
        )
    }

    // Clear active selection if the user flips to another page
    LaunchedEffect(pagerState.currentPage) {
        isCurrentPageZoomed = false
        if (activeSelectionPageIdx != -1 && activeSelectionPageIdx != pagerState.currentPage) {
            activeSelectionPageIdx = -1
            activeSelectionToolbar = null
            isPdfDraggingGlobal = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Horizontal Pager enabling horizontal swiping for next/previous PDF pages!
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                userScrollEnabled = !isPdfDraggingGlobal && activeSelectionToolbar == null && !isCurrentPageZoomed,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (isUiVisible) 4.dp else 0.dp)
            ) { pageIndex ->
                var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
                var pageTextData by remember { mutableStateOf<PdfPageTextData?>(null) }
                var nextPageParagraphs by remember { mutableStateOf<List<String>>(emptyList()) }
                var isRendering by remember { mutableStateOf(true) }

                // Pinch-to-Zoom & Pan state for this PDF page (1.0x .. 4.5x)
                var pageZoomScale by remember { mutableFloatStateOf(1f) }
                var pageZoomOffset by remember { mutableStateOf(Offset.Zero) }

                LaunchedEffect(pagerState.currentPage, pageZoomScale) {
                    if (pagerState.currentPage == pageIndex) {
                        isCurrentPageZoomed = pageZoomScale > 1.02f
                    } else if (pageZoomScale > 1.01f) {
                        pageZoomScale = 1f
                        pageZoomOffset = Offset.Zero
                    }
                }

                // Word-level selection state on this PDF page
                var selectedWordRange by remember { mutableStateOf<Pair<Int, Int>?>(null) }
                var anchorWordIndex by remember { mutableIntStateOf(0) }
                var extraAppendedParagraphs by remember { mutableIntStateOf(0) }
                var isPdfDragging by remember { mutableStateOf(false) }

                // Fallback region box if the PDF page is a pure scanned image with 0 extractable words
                var dragBoxStart by remember { mutableStateOf<Offset?>(null) }
                var dragBoxCurrent by remember { mutableStateOf<Offset?>(null) }

                var renderedImageSizePx by remember { mutableStateOf(Size.Zero) }
                val pageScrollState = rememberScrollState()

                val pageChapterTitle = remember(chaptersList, pageIndex) {
                    val pNum = pageIndex + 1
                    chaptersList.filter { it.pageNumber <= pNum }
                        .maxByOrNull { it.pageNumber }?.title ?: "Page $pNum"
                }

                val pageHighlights = remember(highlights, pageIndex, pdfEbookDocument) {
                    val pdfPageNum = pageIndex + 1
                    highlights.filter { h ->
                        if (h.pageNumber == pdfPageNum) {
                            true
                        } else if (pdfEbookDocument != null) {
                            pdfEbookDocument.doesHighlightTouchPdfPage(h, pdfPageNum)
                        } else {
                            val (pdfRangeStr, _) = com.example.reader.PdfToEbookParser.parseStoredPageLabels(
                                primaryPdfPage = h.pageNumber,
                                rawPrintedPageLabel = h.printedPageLabel
                            )
                            val rangeParts = pdfRangeStr.split(Regex("[–\\-]")).mapNotNull { it.trim().toIntOrNull() }
                            (rangeParts.size == 2 && pdfPageNum in rangeParts[0]..rangeParts[1]) ||
                                (h.pageNumber == pdfPageNum - 1 && h.highlightedText.length >= 24)
                        }
                    }
                }

                val matchedSavedHighlightSpans = remember(pageTextData, pageHighlights) {
                    val words = pageTextData?.words ?: emptyList()
                    matchSavedHighlightsOnPdfWords(words, pageHighlights)
                }

                // Glossae: one card per annotated highlight, covering the bounding box of its words on this page.
                val glossCards = remember(matchedSavedHighlightSpans, pageTextData, glossaeEnabled) {
                    val words = pageTextData?.words ?: emptyList()
                    if (!glossaeEnabled || words.isEmpty()) emptyList() else {
                        matchedSavedHighlightSpans.mapNotNull { (hs, he, h) ->
                            if (!com.example.ui.glossae.Glossae.hasGloss(h)) return@mapNotNull null
                            val slice = words.subList(hs.coerceIn(0, words.size - 1), (he + 1).coerceIn(1, words.size))
                            if (slice.isEmpty()) return@mapNotNull null
                            com.example.ui.glossae.GlossPdfCard(
                                highlight = h,
                                left = (slice.minOf { it.leftRatio } - 0.004f).coerceIn(0f, 1f),
                                top = (slice.minOf { it.topRatio } - 0.002f).coerceIn(0f, 1f),
                                right = (slice.maxOf { it.rightRatio } + 0.004f).coerceIn(0f, 1f),
                                bottom = (slice.maxOf { it.bottomRatio } + 0.002f).coerceIn(0f, 1f)
                            )
                        }
                    }
                }
                val currentGlossCards by rememberUpdatedState(glossCards)

                // Clear this page's selection if another page took ownership or toolbar was dismissed
                LaunchedEffect(activeSelectionPageIdx) {
                    if (activeSelectionPageIdx != pageIndex && (selectedWordRange != null || isPdfDragging)) {
                        selectedWordRange = null
                        extraAppendedParagraphs = 0
                        isPdfDragging = false
                        dragBoxStart = null
                        dragBoxCurrent = null
                    }
                }

                val cropLeft = document.pdfCropLeft
                val cropTop = document.pdfCropTop
                val cropRight = document.pdfCropRight
                val cropBottom = document.pdfCropBottom

                // 1. Render the visual PDF page Bitmap IMMEDIATELY using Coroutine-based background rendering
                //    (with LRU Bitmap cache & native PdfRenderer / PDFBox PDFRenderer subsampling)
                //    so the page appears on screen in milliseconds without blocking the UI thread!
                LaunchedEffect(pdfRenderer, pageIndex, cropLeft, cropTop, cropRight, cropBottom) {
                    val renderer = pdfRenderer ?: return@LaunchedEffect
                    if (pageBitmap == null) {
                        isRendering = true
                    }
                    // Active page renders immediately; offscreen preloaded neighbor yields slightly so active page is first
                    if (pagerState.currentPage != pageIndex && pageBitmap == null) {
                        delay(45L)
                    }
                    val bmp = renderer.renderPageAsync(
                        pageIndex = pageIndex,
                        targetWidth = 1080,
                        cropLeft = cropLeft,
                        cropTop = cropTop,
                        cropRight = cropRight,
                        cropBottom = cropBottom
                    )
                    pageBitmap = bmp
                    isRendering = false
                }

                // 2. Extract selectable word coordinates & text layer asynchronously in the background
                //    after the page image is already visible!
                LaunchedEffect(pdfRenderer, pageIndex, cropLeft, cropTop, cropRight, cropBottom, extractionConfigVersion, pagerState.currentPage) {
                    val renderer = pdfRenderer ?: return@LaunchedEffect
                    // Prioritize the currently visible page; preloaded adjacent pages wait briefly so active page never contends for PDFBox
                    if (pagerState.currentPage != pageIndex) {
                        delay(200L)
                    }
                    val rawTextData = renderer.extractPageTextDataAsync(pageIndex)
                    val textData = withContext(Dispatchers.Default) {
                        renderer.transformTextDataForCrop(
                            rawData = rawTextData,
                            cropLeft = cropLeft,
                            cropTop = cropTop,
                            cropRight = cropRight,
                            cropBottom = cropBottom
                        )
                    }
                    // Line the text layer up with the printed letters (fixes selections shifted to one side
                    // in PDFs whose text layer is offset, e.g. scans with an OCR layer).
                    var bmpForAlign = pageBitmap
                    var waitTries = 0
                    while (bmpForAlign == null && waitTries < 60) {
                        delay(100L)
                        bmpForAlign = pageBitmap
                        waitTries++
                    }
                    val alignedTextData = bmpForAlign?.let { bmp ->
                        withContext(Dispatchers.Default) {
                            try {
                                com.example.reader.PdfInkAligner.align(
                                    "${document.filePath}#$pageIndex@$cropLeft,$cropTop,$cropRight,$cropBottom#$extractionConfigVersion",
                                    bmp,
                                    textData
                                )
                            } catch (t: Throwable) {
                                textData
                            }
                        }
                    } ?: textData
                    pageTextData = alignedTextData
                    if (pagerState.currentPage == pageIndex) {
                        val resolvedPrinted = textData.printedPageLabel.ifBlank {
                            pdfEbookDocument?.printedPageByPdfPage?.get(pageIndex + 1) ?: ""
                        }
                        activePrintedPageLabel = resolvedPrinted
                        onActivePrintedPageChanged(resolvedPrinted)
                        onActivePageTextChanged(textData.fullText)
                    }
                }

                // 3. Only load nextPageParagraphs on demand if the user starts selecting text on this page
                LaunchedEffect(pdfRenderer, pageIndex, selectedWordRange != null, extractionConfigVersion) {
                    val renderer = pdfRenderer ?: return@LaunchedEffect
                    if (selectedWordRange != null && nextPageParagraphs.isEmpty() && pageIndex + 1 < renderer.pageCount) {
                        val nextParas = renderer.extractPageTextDataAsync(pageIndex + 1).paragraphs
                        nextPageParagraphs = nextParas
                    }
                }

                LaunchedEffect(pagerState.currentPage, pageTextData, pdfEbookDocument) {
                    if (pagerState.currentPage == pageIndex && pageTextData != null) {
                        val resolvedPrinted = pageTextData?.printedPageLabel?.ifBlank {
                            pdfEbookDocument?.printedPageByPdfPage?.get(pageIndex + 1) ?: ""
                        } ?: ""
                        activePrintedPageLabel = resolvedPrinted
                        onActivePrintedPageChanged(resolvedPrinted)
                        onActivePageTextChanged(pageTextData?.fullText ?: "")
                    }
                }

                val clearPdfSelection = {
                    selectedWordRange = null
                    extraAppendedParagraphs = 0
                    isPdfDragging = false
                    isPdfDraggingGlobal = false
                    dragBoxStart = null
                    dragBoxCurrent = null
                    if (activeSelectionPageIdx == pageIndex) {
                        activeSelectionPageIdx = -1
                        activeSelectionToolbar = null
                    }
                }

                // Keep the bottom SelectionActionToolbarCard synchronized with the current PDF word selection
                LaunchedEffect(
                    selectedWordRange,
                    extraAppendedParagraphs,
                    isPdfDragging,
                    pageTextData,
                    nextPageParagraphs,
                    pageChapterTitle,
                    pdfEbookDocument,
                    carriedSelection
                ) {
                    val range = selectedWordRange
                    val words = pageTextData?.words ?: emptyList()
                    val basePrinted = pageTextData?.printedPageLabel?.ifBlank {
                        pdfEbookDocument?.printedPageByPdfPage?.get(pageIndex + 1) ?: ""
                    } ?: ""
                    val detectedPrinted = if (extraAppendedParagraphs > 0 && pageIndex + 2 <= pageCount) {
                        val startPdf = pageIndex + 1
                        val endPdf = pageIndex + 2
                        val nextPrinted = pdfEbookDocument?.printedPageByPdfPage?.get(endPdf)
                            ?: (basePrinted.toIntOrNull()?.let { (it + 1).toString() } ?: "")
                        com.example.reader.PdfQuotePageSpan(
                            startPdfPage = startPdf,
                            endPdfPage = endPdf,
                            startPrintedPage = basePrinted,
                            endPrintedPage = nextPrinted
                        ).toStoredPrintedPageLabel()
                    } else {
                        basePrinted
                    }
                    if (range != null && words.isNotEmpty()) {
                        val sIdx = minOf(range.first, range.second).coerceIn(0, words.size - 1)
                        val eIdx = maxOf(range.first, range.second).coerceIn(0, words.size - 1)
                        val baseQuote = buildQuoteFromPdfWords(words, sIdx, eIdx)
                        val appendedText = if (extraAppendedParagraphs > 0 && nextPageParagraphs.isNotEmpty()) {
                            "\n\n" + nextPageParagraphs.take(extraAppendedParagraphs).joinToString("\n\n")
                        } else {
                            ""
                        }
                        val pageQuote = (baseQuote + appendedText).trim()
                        // A selection begun on the previous page and finished here is one passage, saved as one note.
                        val carried = carriedSelection?.takeIf { it.startPage == pageIndex && extraAppendedParagraphs == 0 }
                        val fullQuote = if (carried != null) joinAcrossPages(carried.quote, pageQuote) else pageQuote
                        val savePage = carried?.startPage ?: (pageIndex + 1)
                        val savePrinted = if (carried != null) {
                            com.example.reader.PdfQuotePageSpan(
                                startPdfPage = carried.startPage,
                                endPdfPage = pageIndex + 1,
                                startPrintedPage = carried.printed,
                                endPrintedPage = basePrinted
                            ).toStoredPrintedPageLabel()
                        } else detectedPrinted
                        val saveChapter = carried?.chapterTitle ?: pageChapterTitle
                        val nearPageEnd = eIdx >= maxOf(words.size - 60, (words.size * 0.8f).toInt())
                        if (fullQuote.isNotEmpty()) {
                            activeSelectionPageIdx = pageIndex
                            activeSelectionToolbar = ActiveSelectionToolbarState(
                                dictionaryContext = buildQuoteFromPdfWords(words, (sIdx - 60).coerceAtLeast(0), (eIdx + 60).coerceAtMost(words.lastIndex)),
                                quote = fullQuote,
                                pageNumber = savePage,
                                printedPageLabel = savePrinted,
                                isPdfDocument = true,
                                chapterTitle = saveChapter,
                                continuedFrom = carried?.let { c -> if (c.printed.isNotBlank()) "p. ${c.printed}" else "PDF p. ${c.startPage}" }.orEmpty(),
                                onCancelContinuation = if (carried != null) ({ carriedSelection = null }) else null,
                                onContinueOnNextPage = if (carried == null && extraAppendedParagraphs == 0 && nearPageEnd && pageIndex + 1 < pageCount) ({
                                    carriedSelection = CarriedPdfSelection(pageIndex + 1, pageQuote, basePrinted, pageChapterTitle)
                                    clearPdfSelection()
                                    coroutineScope.launch { pagerState.turnToPageWithAnimation(pageIndex + 1, pageTurnAnimation) }
                                }) else null,
                                isCurrentlyDragging = isPdfDragging,
                                canAddNextParagraph = (eIdx < words.size - 1) || (extraAppendedParagraphs < nextPageParagraphs.size),
                                onAddWord = {
                                    if (eIdx < words.size - 1) {
                                        selectedWordRange = Pair(sIdx, eIdx + 1)
                                    } else if (extraAppendedParagraphs < nextPageParagraphs.size) {
                                        extraAppendedParagraphs++
                                    }
                                },
                                onRemoveWord = {
                                    if (extraAppendedParagraphs > 0) {
                                        extraAppendedParagraphs--
                                    } else if (eIdx > sIdx) {
                                        selectedWordRange = Pair(sIdx, eIdx - 1)
                                    }
                                },
                                onAddNextParagraph = {
                                    if (eIdx < words.size - 1) {
                                        // Extend selection to the end of the next line block or rest of page
                                        val currentLine = words[eIdx].lineIndex
                                        val targetLine = currentLine + 3
                                        val nextStop = words.indexOfLast { it.lineIndex <= targetLine }
                                            .let { if (it > eIdx) it else words.size - 1 }
                                        selectedWordRange = Pair(sIdx, nextStop)
                                    } else if (extraAppendedParagraphs < nextPageParagraphs.size) {
                                        extraAppendedParagraphs++
                                    }
                                },
                                onSelectAll = {
                                    selectedWordRange = Pair(0, words.size - 1)
                                },
                                onClearSelection = {
                                    clearPdfSelection()
                                },
                                onSaveNoteWithDialog = { colorHex ->
                                    onRequestHighlight(fullQuote, savePage, savePrinted, saveChapter, colorHex)
                                    carriedSelection = null
                                    clearPdfSelection()
                                },
                                onQuickSaveHighlight = { colorHex ->
                                    onQuickSaveHighlight(fullQuote, savePage, savePrinted, saveChapter, colorHex)
                                    carriedSelection = null
                                    clearPdfSelection()
                                },
                                onSpeakSelection = {
                                    onSpeakSelection(fullQuote, pageIndex + 1)
                                    clearPdfSelection()
                                },
                                hasUnusualPdfTextWarning = fullQuote.any { ch ->
                                    ch == '\uFFFD' || Character.getType(ch) == Character.PRIVATE_USE.toInt()
                                } || com.example.reader.PdfPageDiagnosticsAndOcrEngine.collapseRepeatedCharactersInToken(fullQuote) != fullQuote ||
                                    fullQuote.split(Regex("\\s+")).any { com.example.reader.PdfPageDiagnosticsAndOcrEngine.hasMissingWordSpaces(it) },
                                onCompareOrOcrSelection = {
                                    val slice = words.subList(sIdx, eIdx + 1)
                                    val minL = (slice.minOfOrNull { it.leftRatio } ?: 0f).coerceIn(0f, 0.9f)
                                    val minT = (slice.minOfOrNull { it.topRatio } ?: 0f).coerceIn(0f, 0.9f)
                                    val maxR = (slice.maxOfOrNull { it.rightRatio } ?: 1f).coerceIn((minL + 0.08f).coerceAtMost(1f), 1f)
                                    val maxB = (slice.maxOfOrNull { it.bottomRatio } ?: 1f).coerceIn((minT + 0.08f).coerceAtMost(1f), 1f)
                                    val regionRect = android.graphics.RectF(minL, minT, maxR, maxB)
                                    clearPdfSelection()
                                    onOpenPdfTextToolsDialog(2, regionRect, fullQuote)
                                }
                            )
                        }
                    } else if (activeSelectionPageIdx == pageIndex && !isPdfDragging) {
                        activeSelectionToolbar = null
                    }
                }

                val currentTextData by rememberUpdatedState(pageTextData)
                val currentSelectedRange by rememberUpdatedState(selectedWordRange)
                val currentImageSize by rememberUpdatedState(renderedImageSizePx)
                val currentMatchedHighlights by rememberUpdatedState(matchedSavedHighlightSpans)
                val currentOnEditHighlight by rememberUpdatedState(onEditHighlight)
                val currentChapterTitleState by rememberUpdatedState(pageChapterTitle)
                val currentZoomScale by rememberUpdatedState(pageZoomScale)
                val currentZoomOffset by rememberUpdatedState(pageZoomOffset)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .applyPhysicalPageTurnEffect(
                            pagerState = pagerState,
                            pageIndex = pageIndex,
                            pageTurnAnimation = pageTurnAnimation,
                            activeTheme = activeTheme
                        )
                        .verticalScroll(
                            state = pageScrollState,
                            enabled = !isPdfDragging && selectedWordRange == null && pageZoomScale <= 1.02f
                        ),
                    contentAlignment = Alignment.TopCenter
                ) {
                    if (isRendering) {
                        Box(modifier = Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = activeTheme.textColor)
                        }
                    } else if (pageBitmap != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight()
                                .onGloballyPositioned { coords ->
                                    renderedImageSizePx = Size(
                                        coords.size.width.toFloat().coerceAtLeast(1f),
                                        coords.size.height.toFloat().coerceAtLeast(1f)
                                    )
                                }
                                .pointerInput(pageIndex, pageTurnAnimation) {
                                    fun toNormalized(offset: Offset): Pair<Float, Float> {
                                        val imgW = currentImageSize.width.coerceAtLeast(size.width.toFloat().coerceAtLeast(1f))
                                        val imgH = currentImageSize.height.coerceAtLeast(size.height.toFloat().coerceAtLeast(1f))
                                        return Pair(
                                            (offset.x / imgW).coerceIn(0f, 1f),
                                            (offset.y / imgH).coerceIn(0f, 1f)
                                        )
                                    }

                                    detectCustomPdfSelectionGestures(
                                        getZoomScale = { currentZoomScale },
                                        getZoomOffset = { currentZoomOffset },
                                        onZoomTransform = { newScale, newOffset ->
                                            pageZoomScale = newScale
                                            pageZoomOffset = newOffset
                                        },
                                        hasActiveSelection = { currentSelectedRange != null },
                                        onActiveSelectionDragStart = { downPos ->
                                            val words = currentTextData?.words ?: emptyList()
                                            val existing = currentSelectedRange
                                            if (words.isNotEmpty() && existing != null) {
                                                val (rx, ry) = toNormalized(downPos)
                                                val touchedIdx = findClosestPdfWordIndex(words, rx, ry) ?: existing.first
                                                val (selStart, selEnd) = existing
                                                val distStart = kotlin.math.abs(touchedIdx - selStart)
                                                val distEnd = kotlin.math.abs(touchedIdx - selEnd)
                                                anchorWordIndex = if (distStart < distEnd && touchedIdx <= (selStart + selEnd) / 2) {
                                                    selEnd
                                                } else {
                                                    selStart
                                                }
                                                isPdfDragging = true
                                                isPdfDraggingGlobal = true
                                            }
                                        },
                                        onActiveSelectionDragMove = { movePos ->
                                            val words = currentTextData?.words ?: emptyList()
                                            if (words.isNotEmpty()) {
                                                val (rx, ry) = toNormalized(movePos)
                                                val curIdx = findClosestPdfWordIndex(words, rx, ry)
                                                if (curIdx != null) {
                                                    val newStart = minOf(anchorWordIndex, curIdx).coerceIn(0, words.size - 1)
                                                    val newEnd = maxOf(anchorWordIndex, curIdx).coerceIn(0, words.size - 1)
                                                    selectedWordRange = Pair(newStart, newEnd)
                                                }
                                            }
                                        },
                                        onActiveSelectionDragEnd = { hasDragged, releasePos ->
                                            isPdfDragging = false
                                            isPdfDraggingGlobal = false
                                            if (!hasDragged) {
                                                val words = currentTextData?.words ?: emptyList()
                                                val existing = currentSelectedRange
                                                if (words.isNotEmpty() && existing != null) {
                                                    val (rx, ry) = toNormalized(releasePos)
                                                    val tappedIdx = findClosestPdfWordIndex(words, rx, ry)
                                                    val (selStart, selEnd) = existing
                                                    if (tappedIdx != null && tappedIdx > selEnd) {
                                                        selectedWordRange = Pair(selStart, tappedIdx)
                                                    } else if (tappedIdx != null && tappedIdx < selStart) {
                                                        selectedWordRange = Pair(tappedIdx, selEnd)
                                                    } else {
                                                        clearPdfSelection()
                                                    }
                                                } else {
                                                    clearPdfSelection()
                                                }
                                            }
                                        },
                                        onDoubleTap = { tapPos ->
                                            val words = currentTextData?.words ?: emptyList()
                                            val (rx, ry) = toNormalized(tapPos)
                                            val wordIdx = if (words.isNotEmpty()) findClosestPdfWordIndex(words, rx, ry) else null
                                            val tappedWord = wordIdx?.let { words.getOrNull(it) }
                                            val hitDirectWord = tappedWord != null &&
                                                rx in (tappedWord.leftRatio - 0.025f)..(tappedWord.rightRatio + 0.025f) &&
                                                ry in (tappedWord.topRatio - 0.025f)..(tappedWord.bottomRatio + 0.025f)
                                            if (hitDirectWord && wordIdx != null) {
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                anchorWordIndex = wordIdx
                                                selectedWordRange = Pair(wordIdx, wordIdx)
                                                activeSelectionPageIdx = pageIndex
                                            } else {
                                                // Double-tap on whitespace/margins or image toggles 2.25x zoom / 1.0x reset!
                                                if (currentZoomScale > 1.05f) {
                                                    pageZoomScale = 1f
                                                    pageZoomOffset = Offset.Zero
                                                } else {
                                                    val targetScale = 2.25f
                                                    val center = Offset(size.width * 0.5f, size.height * 0.5f)
                                                    val focusOffset = (center - tapPos) * (targetScale - 1f)
                                                    val maxDx = (size.width * (targetScale - 1f) * 0.5f).coerceAtLeast(0f)
                                                    val maxDy = (size.height * (targetScale - 1f) * 0.5f).coerceAtLeast(0f)
                                                    pageZoomScale = targetScale
                                                    pageZoomOffset = Offset(
                                                        x = focusOffset.x.coerceIn(-maxDx, maxDx),
                                                        y = focusOffset.y.coerceIn(-maxDy, maxDy)
                                                    )
                                                }
                                            }
                                        },
                                        onDragStart = { offset ->
                                            val words = currentTextData?.words ?: emptyList()
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                            isPdfDragging = true
                                            isPdfDraggingGlobal = true
                                            activeSelectionPageIdx = pageIndex
                                            if (words.isNotEmpty()) {
                                                val (rx, ry) = toNormalized(offset)
                                                val startIdx = findClosestPdfWordIndex(words, rx, ry) ?: 0
                                                anchorWordIndex = startIdx
                                                selectedWordRange = Pair(startIdx, startIdx)
                                                dragBoxStart = null
                                                dragBoxCurrent = null
                                            } else {
                                                dragBoxStart = offset
                                                dragBoxCurrent = offset
                                            }
                                        },
                                        onDragMove = { current ->
                                            val words = currentTextData?.words ?: emptyList()
                                            if (words.isNotEmpty()) {
                                                val (rx, ry) = toNormalized(current)
                                                val curIdx = findClosestPdfWordIndex(words, rx, ry)
                                                if (curIdx != null) {
                                                    val newStart = minOf(anchorWordIndex, curIdx).coerceIn(0, words.size - 1)
                                                    val newEnd = maxOf(anchorWordIndex, curIdx).coerceIn(0, words.size - 1)
                                                    selectedWordRange = Pair(newStart, newEnd)
                                                }
                                            } else {
                                                dragBoxCurrent = current
                                            }
                                        },
                                        onDragFinish = { s, e ->
                                            val words = currentTextData?.words ?: emptyList()
                                            isPdfDragging = false
                                            isPdfDraggingGlobal = false
                                            if (words.isEmpty()) {
                                                val dist = kotlin.math.hypot(e.x - s.x, e.y - s.y)
                                                if (dist > 25f) {
                                                    val fallbackText = currentTextData?.fullText?.takeIf { it.isNotBlank() }
                                                        ?: "Selected region on Page ${pageIndex + 1}"
                                                    val fallbackPrinted = currentTextData?.printedPageLabel ?: ""
                                                    onRequestHighlight(fallbackText, pageIndex + 1, fallbackPrinted, currentChapterTitleState, "#FFEB3B")
                                                }
                                                dragBoxStart = null
                                                dragBoxCurrent = null
                                            }
                                        },
                                        onDragCancel = {
                                            isPdfDragging = false
                                            isPdfDraggingGlobal = false
                                            dragBoxStart = null
                                            dragBoxCurrent = null
                                        },
                                        onTap = { offset ->
                                            // 0. Glossae: a tap on a gloss card flips it ("… more" corner opens the full note)
                                            if (currentGlossCards.isNotEmpty()) {
                                                val (gx, gy) = toNormalized(offset)
                                                val hitCard = currentGlossCards.lastOrNull { it.contains(gx, gy) }
                                                if (hitCard != null) {
                                                    val imgW = currentImageSize.width.coerceAtLeast(1f)
                                                    val imgH = currentImageSize.height.coerceAtLeast(1f)
                                                    val hid = hitCard.highlight.id
                                                    val isFront = hid !in glossFlipped
                                                    val wantsMore = isFront && hid in glossOverflowing &&
                                                        com.example.ui.glossae.Glossae.isInMoreZone(
                                                            gx * imgW, gy * imgH,
                                                            hitCard.left * imgW, hitCard.top * imgH,
                                                            hitCard.right * imgW, hitCard.bottom * imgH,
                                                            density
                                                        )
                                                    if (wantsMore) {
                                                        glossPreview = hitCard.highlight
                                                    } else {
                                                        glossFlipped = if (isFront) glossFlipped + hid else glossFlipped - hid
                                                    }
                                                    return@detectCustomPdfSelectionGestures
                                                }
                                            }
                                            // 1. Check if the user tapped on an existing saved PDF highlight to edit it
                                            val words = currentTextData?.words ?: emptyList()
                                            if (words.isNotEmpty() && currentMatchedHighlights.isNotEmpty()) {
                                                val (rx, ry) = toNormalized(offset)
                                                val tappedWordIdx = findClosestPdfWordIndex(words, rx, ry)
                                                if (tappedWordIdx != null) {
                                                    val tappedWord = words.getOrNull(tappedWordIdx)
                                                    if (tappedWord != null &&
                                                        rx in (tappedWord.leftRatio - 0.02f)..(tappedWord.rightRatio + 0.02f) &&
                                                        ry in (tappedWord.topRatio - 0.02f)..(tappedWord.bottomRatio + 0.02f)
                                                    ) {
                                                        val hitHighlight = currentMatchedHighlights.firstOrNull { (hs, he, _) ->
                                                            tappedWordIdx in hs..he
                                                        }?.third
                                                        if (hitHighlight != null) {
                                                            currentOnEditHighlight(hitHighlight)
                                                            return@detectCustomPdfSelectionGestures
                                                        }
                                                    }
                                                }
                                            }

                                            // 2. Otherwise handle left/right 20% edge page turns (when unzoomed) or center UI toggle
                                            val screenWidth = size.width
                                            if (currentZoomScale <= 1.02f && offset.x < screenWidth * 0.2f) {
                                                if (pagerState.currentPage > 0) {
                                                    coroutineScope.launch {
                                                        pagerState.turnToPageWithAnimation(pagerState.currentPage - 1, pageTurnAnimation)
                                                    }
                                                }
                                            } else if (currentZoomScale <= 1.02f && offset.x > screenWidth * 0.8f) {
                                                if (pagerState.currentPage < pageCount - 1) {
                                                    coroutineScope.launch {
                                                        pagerState.turnToPageWithAnimation(pagerState.currentPage + 1, pageTurnAnimation)
                                                    }
                                                }
                                            }
                                            // A tap in the middle of the page does nothing: the menu opens only from
                                            // the corner button, so stray taps while reading never bring it up.
                                        }
                                    )
                                }
                                .graphicsLayer {
                                    scaleX = pageZoomScale
                                    scaleY = pageZoomScale
                                    translationX = pageZoomOffset.x
                                    translationY = pageZoomOffset.y
                                }
                        ) {
                            Image(
                                bitmap = pageBitmap!!.asImageBitmap(),
                                contentDescription = "PDF Page ${pageIndex + 1}",
                                contentScale = ContentScale.FillWidth,
                                colorFilter = pdfColorFilter,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .wrapContentHeight()
                                    .clip(RoundedCornerShape(if (isUiVisible) 4.dp else 0.dp))
                            )

                            // Overlay Canvas for saved PDF highlights + active word-level selection + KOReader selection handles!
                            Canvas(modifier = Modifier.matchParentSize()) {
                                val canvasW = size.width
                                val canvasH = size.height
                                val words = pageTextData?.words ?: emptyList()

                                // 1. Draw saved highlights on this PDF page
                                for ((hs, he, hEntity) in matchedSavedHighlightSpans) {
                                    val highlightColor = Color(parseHighlightColor(hEntity.colorHex)).copy(alpha = 0.38f)
                                    val slice = words.subList(
                                        hs.coerceIn(0, words.size - 1),
                                        (he + 1).coerceIn(1, words.size)
                                    )
                                    val byLine = slice.groupBy { it.lineIndex }
                                    for ((_, lineWords) in byLine) {
                                        val left = (lineWords.minOf { it.leftRatio } * canvasW).coerceIn(0f, (canvasW - 1f).coerceAtLeast(0f))
                                        val top = (lineWords.minOf { it.topRatio } * canvasH).coerceIn(0f, (canvasH - 1f).coerceAtLeast(0f))
                                        val minR = (left + 4f).coerceAtMost(canvasW)
                                        val minB = (top + 6f).coerceAtMost(canvasH)
                                        val right = (lineWords.maxOf { it.rightRatio } * canvasW).coerceIn(minR, canvasW)
                                        val bottom = (lineWords.maxOf { it.bottomRatio } * canvasH).coerceIn(minB, canvasH)
                                        drawRoundRect(
                                            color = highlightColor,
                                            topLeft = Offset(left, top),
                                            size = Size(right - left, bottom - top),
                                            cornerRadius = CornerRadius(4f, 4f)
                                        )
                                    }
                                }

                                // 2. Draw active word-level selection highlight & KOReader handles
                                val selRange = selectedWordRange
                                if (selRange != null && words.isNotEmpty()) {
                                    val sIdx = minOf(selRange.first, selRange.second).coerceIn(0, words.size - 1)
                                    val eIdx = maxOf(selRange.first, selRange.second).coerceIn(0, words.size - 1)
                                    val selectedSlice = words.subList(sIdx, eIdx + 1)
                                    val byLine = selectedSlice.groupBy { it.lineIndex }
                                    val activeHighlightColor = Color(0x66FFEB3B)

                                    for ((_, lineWords) in byLine) {
                                        val left = (lineWords.minOf { it.leftRatio } * canvasW).coerceIn(0f, (canvasW - 1f).coerceAtLeast(0f))
                                        val top = (lineWords.minOf { it.topRatio } * canvasH).coerceIn(0f, (canvasH - 1f).coerceAtLeast(0f))
                                        val minR = (left + 4f).coerceAtMost(canvasW)
                                        val minB = (top + 6f).coerceAtMost(canvasH)
                                        val right = (lineWords.maxOf { it.rightRatio } * canvasW).coerceIn(minR, canvasW)
                                        val bottom = (lineWords.maxOf { it.bottomRatio } * canvasH).coerceIn(minB, canvasH)
                                        drawRoundRect(
                                            color = activeHighlightColor,
                                            topLeft = Offset(left, top),
                                            size = Size(right - left, bottom - top),
                                            cornerRadius = CornerRadius(4f, 4f)
                                        )
                                    }

                                    // Draw KOReader-style purple selection handles at the start word and end word
                                    val firstWord = words[sIdx]
                                    val lastWord = words[eIdx]
                                    val handleColor = Color(0xFF7C3AED)
                                    val stemWidth = 2.5.dp.toPx()
                                    val handleRadius = 5.5.dp.toPx()

                                    val startLeft = firstWord.leftRatio * canvasW
                                    val startTop = firstWord.topRatio * canvasH
                                    val startBottom = firstWord.bottomRatio * canvasH

                                    drawLine(
                                        color = handleColor,
                                        start = Offset(startLeft, startTop),
                                        end = Offset(startLeft, startBottom),
                                        strokeWidth = stemWidth
                                    )
                                    drawCircle(
                                        color = handleColor,
                                        radius = handleRadius,
                                        center = Offset(startLeft, startTop)
                                    )

                                    val endRight = lastWord.rightRatio * canvasW
                                    val endTop = lastWord.topRatio * canvasH
                                    val endBottom = lastWord.bottomRatio * canvasH

                                    drawLine(
                                        color = handleColor,
                                        start = Offset(endRight, endTop),
                                        end = Offset(endRight, endBottom),
                                        strokeWidth = stemWidth
                                    )
                                    drawCircle(
                                        color = handleColor,
                                        radius = handleRadius,
                                        center = Offset(endRight, endBottom)
                                    )
                                }

                                // 3. Fallback rectangle for scanned image PDFs with zero words
                                if (isPdfDragging && dragBoxStart != null && dragBoxCurrent != null) {
                                    val s = dragBoxStart!!
                                    val e = dragBoxCurrent!!
                                    val left = minOf(s.x, e.x)
                                    val top = minOf(s.y, e.y)
                                    val right = maxOf(s.x, e.x)
                                    val bottom = maxOf(s.y, e.y)
                                    drawRoundRect(
                                        color = Color(0x66FFEB3B),
                                        topLeft = Offset(left, top),
                                        size = Size(right - left, maxOf(bottom - top, 24f)),
                                        cornerRadius = CornerRadius(6f, 6f)
                                    )
                                }
                            }

                            if (glossCards.isNotEmpty()) {
                                com.example.ui.glossae.GlossPdfOverlay(
                                    cards = glossCards,
                                    flipped = glossFlipped,
                                    background = activeTheme.backgroundColor,
                                    foreground = activeTheme.textColor,
                                    onOverflowChanged = { id, overflows ->
                                        glossOverflowing = if (overflows) glossOverflowing + id else glossOverflowing - id
                                    },
                                    modifier = Modifier.matchParentSize()
                                )
                            }
                        }

                        // Floating Reset Zoom Pill when user pinches to zoom in (> 1.04x)
                        if (pageZoomScale > 1.04f) {
                            Surface(
                                onClick = {
                                    pageZoomScale = 1f
                                    pageZoomOffset = Offset.Zero
                                },
                                color = Color(0xE618181B),
                                contentColor = Color.White,
                                shape = RoundedCornerShape(20.dp),
                                shadowElevation = 6.dp,
                                border = BorderStroke(1.dp, Color(0xFF7C3AED)),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 12.dp, end = 12.dp)
                                    .testTag("pdf_reset_zoom_pill")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "${(pageZoomScale * 100).toInt()}% • Reset Zoom",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        // Extra bottom padding when the selection toolbar is open so the bottom of the PDF page can be scrolled above the toolbar
                        if (activeSelectionToolbar != null) {
                            Spacer(modifier = Modifier.height(220.dp))
                        }
                    } else {
                        Text("Page ${pageIndex + 1}", color = activeTheme.textColor)
                    }
                }
            }

            // Pinned Floating Selection UI for PDF (identical to EPUB):
            // 1) While actively dragging finger: compact top banner
            // 2) As soon as finger is released: full SelectionActionToolbarCard with Highlight, Add Note, colors, +Word/-Word, Wikilink, Copy!
            val toolbarState = activeSelectionToolbar
            val waiting = carriedSelection
            if (waiting != null && toolbarState == null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(0.dp),
                    color = Color.White,
                    border = BorderStroke(1.5.dp, Color.Black)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp, end = 4.dp)) {
                        Text(
                            text = if (pagerState.currentPage == waiting.startPage) "Select the rest of the passage on this page — it joins the part from PDF p. ${waiting.startPage}"
                                else "Turn to PDF p. ${waiting.startPage + 1} to finish the selection",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        TextButton(onClick = { carriedSelection = null }) { Text("Cancel", color = Color.Black, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            if (toolbarState != null) {
                if (toolbarState.isCurrentlyDragging) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        shape = RoundedCornerShape(0.dp),
                        color = Color.White,
                        border = BorderStroke(1.5.dp, Color.Black),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {
                        Text(
                            text = "Selecting PDF text • Drag across words & lines • Release finger for Highlighting Menu",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    SelectionActionToolbarCard(
                        state = toolbarState,
                        onAddAsAuthor = onAddAsAuthor,
                        onSetAsAbstract = onSetAsAbstract,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    )
                }
            }
        }

        // Bottom page controls (shown with the menu): square, black and white, like the rest of the reader.
        AnimatedVisibility(
            visible = isUiVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val ink = activeTheme.textColor
            val paper = activeTheme.barColor
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(paper)
            ) {
                Box(Modifier.fillMaxWidth().height(1.5.dp).background(ink))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    @Composable
                    fun SquareButton(enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .border(1.dp, if (enabled) ink else activeTheme.dividerColor)
                                .clickable(enabled = enabled, onClick = onClick),
                            contentAlignment = Alignment.Center
                        ) { content() }
                    }
                    @Composable
                    fun SquareChip(label: String, active: Boolean, testTag: String, onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
                        Row(
                            modifier = Modifier
                                .height(36.dp)
                                .border(1.dp, ink)
                                .background(if (active) ink else paper)
                                .clickable(onClick = onClick)
                                .padding(horizontal = 10.dp)
                                .testTag(testTag),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (icon != null) {
                                Icon(icon, contentDescription = null, tint = if (active) paper else ink, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                            }
                            Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = if (active) paper else ink)
                        }
                    }

                    SquareButton(enabled = pagerState.currentPage > 0, onClick = {
                        if (pagerState.currentPage > 0) {
                            coroutineScope.launch { pagerState.turnToPageWithAnimation(pagerState.currentPage - 1, pageTurnAnimation) }
                        }
                    }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous Page", tint = ink)
                    }

                    val currentChapter = chaptersList.filter { it.pageNumber <= pagerState.currentPage + 1 }
                        .maxByOrNull { it.pageNumber }?.title
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "PDF ${pagerState.currentPage + 1} / $pageCount" +
                                if (activePrintedPageLabel.isNotBlank()) "  ·  printed p. $activePrintedPageLabel" else "",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = ink,
                            maxLines = 1
                        )
                        if (!currentChapter.isNullOrBlank()) {
                            Text(
                                text = currentChapter,
                                fontFamily = FontFamily.Serif,
                                fontSize = 12.sp,
                                color = activeTheme.secondaryTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    SquareButton(enabled = pagerState.currentPage < pageCount - 1, onClick = {
                        if (pagerState.currentPage < pageCount - 1) {
                            coroutineScope.launch { pagerState.turnToPageWithAnimation(pagerState.currentPage + 1, pageTurnAnimation) }
                        }
                    }) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next Page", tint = ink)
                    }

                    val isCroppedBook = document.pdfCropLeft > 0.005f ||
                        document.pdfCropTop > 0.005f ||
                        document.pdfCropRight < 0.995f ||
                        document.pdfCropBottom < 0.995f
                    val customText = (!document.pdfTextExtractionMode.equals("AUTO", ignoreCase = true) && document.pdfTextExtractionMode.isNotBlank()) ||
                        document.pdfPageExtractionOverridesJson.isNotBlank()
                    SquareChip("Text / OCR", customText, "pdf_bottom_text_tools_btn", {
                        onOpenPdfTextToolsDialog(0, android.graphics.RectF(0f, 0f, 1f, 1f), "")
                    })
                    val zoomPct = ((1f / (document.pdfCropRight - document.pdfCropLeft).coerceIn(0.15f, 1f)) * 100).toInt()
                    SquareChip(if (isCroppedBook) "Crop $zoomPct%" else "Crop", isCroppedBook, "pdf_bottom_crop_zoom_btn", onOpenPdfCropDialog, Icons.Default.Crop)
                }

                if (pageCount > 1) {
                    Slider(
                        value = pagerState.currentPage.toFloat(),
                        onValueChange = {
                            coroutineScope.launch { pagerState.scrollToPage(it.toInt()) }
                        },
                        valueRange = 0f..(pageCount - 1).toFloat(),
                        steps = if (pageCount <= 40) (pageCount - 2).coerceAtLeast(0) else 0,
                        colors = SliderDefaults.colors(
                            thumbColor = ink,
                            activeTrackColor = ink,
                            inactiveTrackColor = activeTheme.dividerColor,
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp).height(30.dp)
                    )
                }
            }
        }
    }

    if (showPdfCropDialog) {
        PdfViewCropDialog(
            document = document,
            currentPageIndex = pagerState.currentPage,
            pdfRenderer = pdfRenderer,
            onDismiss = onDismissPdfCropDialog,
            onSaveCropForBook = { l, t, r, b ->
                onUpdatePdfCrop(l, t, r, b)
                onDismissPdfCropDialog()
                val isReset = l <= 0.005f && t <= 0.005f && r >= 0.995f && b >= 0.995f
                if (isReset) {
                    Toast.makeText(context, "Restored Full PDF Page View for ${document.title}", Toast.LENGTH_SHORT).show()
                } else {
                    val zoomPct = ((1f / (r - l).coerceIn(0.15f, 1f)) * 100).toInt()
                    Toast.makeText(
                        context,
                        "Applied ${zoomPct}% PDF Reading Region globally to ${document.title}!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }
}
