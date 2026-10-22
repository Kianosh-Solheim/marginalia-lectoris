package com.example.ui

import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.reader.BookChapter
import com.example.reader.EpubBook
import com.example.reader.EpubCfiEngine
import com.example.reader.EpubPage
import com.example.reader.EpubParser
import com.example.reader.PdfDocumentRenderer
import com.example.widget.ReadingPosition
import com.example.widget.ReadingPositionBuilder
import com.example.widget.ResumeTarget
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * EPUB Reader using HorizontalPager:
 * - Tap middle: toggles full-screen reading mode on/off
 * - Tap left 20%: previous page
 * - Tap right 20%: next page
 * - Swipe left/right: flips pages
 * - Physical volume buttons: flips pages
 * - Typography settings: font size, justification, margins, line spacing, font family, color themes
 */
private data class ContinuousParagraphItem(
    val text: String,
    val pageIndex: Int,
    val chapterTitle: String,
    val footnotes: Map<String, String>,
    val isCoverPage: Boolean = false,
    val coverBitmap: Bitmap? = null,
    val pageEquivalent: String = "",
    val semanticBlock: com.example.reader.EpubSemanticBlock? = null,
    val footnoteEntries: Map<String, com.example.reader.EpubFootnoteEntry> = emptyMap(),
    val fileKey: String = ""
)

@Composable
fun EpubReaderPagerContent(
    document: DocumentEntity,
    initialPage: Int,
    savedReadingProgress: Float = 0f,
    isUiVisible: Boolean,
    activeTheme: ReaderTheme,
    fontSizeSp: Float,
    textAlignSetting: String,
    marginDp: Int,
    lineSpacingMultiplier: Float,
    fontFamilySetting: String,
    viewModeSetting: String = "PAGINATED",
    pageTurnAnimation: String = "PAGE_CURL",
    volumeKeyAnimation: Boolean = true,
    showEpubPageEquivalents: Boolean = true,
    paginateByEpubPageEquivalents: Boolean = false,
    highlights: List<HighlightEntity>,
    pageTurnTrigger: SharedFlow<Int>,
    targetPageRequest: Int?,
    targetHighlightRequest: HighlightEntity? = null,
    jumpRequestToken: Int = 0,
    preloadedEpubBook: EpubBook? = null,
    pdfEbookDocument: com.example.reader.PdfEbookDocument? = null,
    pdfRendererForSnapshots: PdfDocumentRenderer? = null,
    onSwitchToOriginalPdfPage: ((pdfPage: Int) -> Unit)? = null,
    onToggleUi: () -> Unit,
    onChaptersLoaded: (List<BookChapter>) -> Unit,
    onPagesPaginated: ((List<EpubPage>) -> Unit)? = null,
    onPageChanged: (page: Int, totalPages: Int, chapterTitle: String) -> Unit,
    onActiveReflowPageChanged: ((com.example.reader.PdfEbookReflowPage) -> Unit)? = null,
    onActiveEpubPageEquivalentChanged: ((pageEquivalentLabel: String, hasPageListMetadata: Boolean) -> Unit)? = null,
    onActivePageTextChanged: (String) -> Unit = {},
    onSpeakSelection: (quote: String, page: Int) -> Unit = { _, _ -> },
    onRequestHighlight: (quote: String, page: Int, chapterTitle: String, colorHex: String, paragraphCitation: String, pageEquivalent: String, epubCfi: String) -> Unit,
    onQuickSaveHighlight: (quote: String, page: Int, chapterTitle: String, colorHex: String, paragraphCitation: String, pageEquivalent: String, epubCfi: String) -> Unit = { _, _, _, _, _, _, _ -> },
    onAddAsAuthor: (String) -> Unit = {},
    onSetAsAbstract: (String) -> Unit = {},
    onEditHighlight: (HighlightEntity) -> Unit = {},
    onFootnoteClick: (number: String, text: String) -> Unit,
    onFootnoteEntrySelected: ((com.example.reader.EpubFootnoteEntry) -> Unit)? = null,
    onLandmarksLoaded: ((List<com.example.reader.EpubLandmarkItem>) -> Unit)? = null,
    activeDaisyFileKey: String = "",
    activeDaisyAnchorId: String = "",
    activeDaisyTextSnippet: String = "",
    isDaisyAutoScrollEnabled: Boolean = true,
    onDaisyPhraseTapped: ((anchorId: String, textSnippet: String) -> Unit)? = null,
    paragraphStyleSetting: String = "",
    columnCount: Int = 1,
    topMarginDp: Int = 16,
    bottomMarginDp: Int = 16
) {
    val coroutineScope = rememberCoroutineScope()
    var epubBook by remember(document.id, preloadedEpubBook) { mutableStateOf<EpubBook?>(preloadedEpubBook) }
    var pages by remember(document.id) { mutableStateOf<List<EpubPage>>(emptyList()) }
    var pdfReflowPages by remember(document.id) { mutableStateOf<List<com.example.reader.PdfEbookReflowPage>>(emptyList()) }
    var bookChapters by remember(document.id) { mutableStateOf<List<BookChapter>>(emptyList()) }
    var isLoading by remember(document.id) { mutableStateOf(true) }
    var loadingStageName by remember(document.id) { mutableStateOf("Reading file") }
    var hasCompletedInitialLoad by remember(document.id) { mutableStateOf(false) }


    val isFixedLayoutBook = epubBook?.isFixedLayout == true
    val isVerticalScrollMode = !isFixedLayoutBook && viewModeSetting.equals("VERTICAL_SCROLL", ignoreCase = true)

    val context = androidx.compose.ui.platform.LocalContext.current
    val readerViewModel: ReaderViewModel? = (context as? androidx.lifecycle.ViewModelStoreOwner)?.let {
        androidx.lifecycle.viewmodel.compose.viewModel(viewModelStoreOwner = it)
    }
    val pendingResumeState = readerViewModel?.pendingResume?.collectAsState()
    val pendingResume = pendingResumeState?.value
    val dbSettings by remember(context) {
        com.example.data.repository.ReaderRepository(com.example.data.local.AppDatabase.getInstance(context)).settingsFlow
    }.collectAsState(initial = null)
    val effectiveParagraphStyle = remember(paragraphStyleSetting, dbSettings?.readerParagraphStyle) {
        paragraphStyleSetting.ifBlank { dbSettings?.readerParagraphStyle.orEmpty() }.ifBlank { "BOTH" }.uppercase()
    }
    val mediaOverlayPlayer = remember(document.id) {
        com.example.reader.EpubMediaOverlayPlayer(context)
    }
    androidx.compose.runtime.DisposableEffect(mediaOverlayPlayer) {
        onDispose {
            mediaOverlayPlayer.release()
        }
    }
    LaunchedEffect(epubBook) {
        mediaOverlayPlayer.loadBook(epubBook)
    }
    val mediaOverlayState by mediaOverlayPlayer.state.collectAsState()
    val activeSmilClip = mediaOverlayState.activeClip
    val hasMediaOverlays = epubBook?.mediaOverlays?.isNotEmpty() == true

    val totalPages = pages.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { totalPages }
    )
    val verticalListState = rememberLazyListState()

    val bookWideFootnoteEntries = remember(epubBook) {
        val map = LinkedHashMap<String, com.example.reader.EpubFootnoteEntry>()
        val book = epubBook
        if (book != null) {
            map.putAll(book.footnoteEntries)
            for (ch in book.chapters) {
                map.putAll(ch.footnoteEntries)
            }
        }
        map
    }

    val bookWideFootnotesMap = remember(epubBook) {
        val map = LinkedHashMap<String, String>()
        val book = epubBook
        if (book != null) {
            for (ch in book.chapters) {
                map.putAll(ch.footnotes)
            }
        }
        map
    }

    val continuousItems = remember(epubBook, pages, isVerticalScrollMode) {
        if (!isVerticalScrollMode) return@remember emptyList<ContinuousParagraphItem>()
        val list = mutableListOf<ContinuousParagraphItem>()
        val book = epubBook
        if (book != null && book.chapters.isNotEmpty() && pages.isNotEmpty()) {
            val combinedFootnotes = bookWideFootnotesMap
            val combinedFnEntries = bookWideFootnoteEntries

            val firstPage = pages.firstOrNull()
            if (firstPage?.isCoverPage == true && firstPage.coverBitmap != null) {
                list.add(
                    ContinuousParagraphItem(
                        text = "",
                        pageIndex = 0,
                        chapterTitle = "Cover",
                        footnotes = combinedFootnotes,
                        isCoverPage = true,
                        coverBitmap = firstPage.coverBitmap,
                        pageEquivalent = "",
                        footnoteEntries = combinedFnEntries
                    )
                )
            }

            // Build raw unsplit stream directly from chapters so text never breaks mid-paragraph
            // and reflows continuously as a vertical lazy list regardless of font size!
            data class RawBlockEntry(
                val text: String,
                val chapterTitle: String,
                val footnotes: Map<String, String>,
                val pageEquivalent: String,
                val semanticBlock: com.example.reader.EpubSemanticBlock?,
                val footnoteEntries: Map<String, com.example.reader.EpubFootnoteEntry>,
                val fileKey: String
            )
            val rawBlocks = mutableListOf<RawBlockEntry>()
            for ((chIdx, ch) in book.chapters.withIndex()) {
                val cleanTitle = ch.title.trim()
                val displayChapterTitle = cleanTitle.ifEmpty { "Chapter ${chIdx + 1}" }
                val chFootnotes = combinedFootnotes
                val chFnEntries = combinedFnEntries
                val firstParaEq = ch.paragraphPageEquivalents.firstOrNull { it.isNotBlank() }?.trim() ?: ""
                var addedHeading = false
                val matchedHeadingBlock = ch.semanticBlocks.firstOrNull {
                    it.type == com.example.reader.EpubSemanticBlockType.HEADING &&
                        EpubParser.isDuplicateChapterHeadingText(it.text, cleanTitle, isHeadingOrFirstBlock = true)
                }
                if (cleanTitle.isNotEmpty()) {
                    val headingSem = com.example.reader.EpubSemanticBlock(
                        type = com.example.reader.EpubSemanticBlockType.HEADING,
                        text = cleanTitle,
                        headingLevel = matchedHeadingBlock?.headingLevel?.coerceIn(1, 2) ?: 1,
                        anchorIds = matchedHeadingBlock?.anchorIds ?: emptyList(),
                        inlineLinks = emptyList(),
                        paragraphNumberInChapter = null,
                        pageEquivalent = firstParaEq,
                        fileKey = ch.fileKey
                    )
                    rawBlocks.add(RawBlockEntry("## $cleanTitle", displayChapterTitle, chFootnotes, firstParaEq, headingSem, chFnEntries, ch.fileKey))
                    addedHeading = true
                }
                var semCursor = 0
                for ((pIdx, p) in ch.paragraphs.withIndex()) {
                    val trimmed = p.trim()
                    if (trimmed.isEmpty()) continue
                    val pEq = ch.paragraphPageEquivalents.getOrNull(pIdx)?.trim() ?: ""
                    var matchedSem: com.example.reader.EpubSemanticBlock? = null
                    if (ch.semanticBlocks.isNotEmpty()) {
                        val probe = trimmed.replace(Regex("^#+\\s*"), "").removePrefix("• ").trim().take(32)
                        for (sIdx in semCursor until ch.semanticBlocks.size) {
                            val cand = ch.semanticBlocks[sIdx]
                            if (cand.text.equals(trimmed, ignoreCase = true) ||
                                (probe.length >= 6 && cand.text.contains(probe, ignoreCase = true)) ||
                                (cand.text.length >= 6 && trimmed.contains(cand.text.take(28), ignoreCase = true))
                            ) {
                                val validLinks = if (cand.type != com.example.reader.EpubSemanticBlockType.HEADING) {
                                    EpubParser.sliceInlineLinksForSegment(cand.text, trimmed, cand.inlineLinks)
                                } else {
                                    emptyList()
                                }
                                matchedSem = cand.copy(
                                    text = trimmed,
                                    inlineLinks = validLinks,
                                    pageEquivalent = pEq.ifBlank { cand.pageEquivalent }
                                )
                                if (cand.text.equals(trimmed, ignoreCase = true)) {
                                    semCursor = sIdx + 1
                                }
                                break
                            }
                        }
                    }
                    val isHeadingOrFirst = pIdx <= 1 || matchedSem?.type == com.example.reader.EpubSemanticBlockType.HEADING
                    if (addedHeading && EpubParser.isDuplicateChapterHeadingText(trimmed, cleanTitle, isHeadingOrFirstBlock = isHeadingOrFirst)) continue
                    rawBlocks.add(RawBlockEntry(trimmed, displayChapterTitle, chFootnotes, pEq, matchedSem, chFnEntries, ch.fileKey))
                }
            }

            val totalRaw = rawBlocks.size.coerceAtLeast(1)
            val hasCoverOffset = if (firstPage?.isCoverPage == true) 1 else 0
            val textPageCount = (pages.size - hasCoverOffset).coerceAtLeast(1)

            rawBlocks.forEachIndexed { idx, entry ->
                val mappedPageIdx = hasCoverOffset + ((idx.toFloat() / totalRaw) * textPageCount).toInt().coerceIn(0, textPageCount - 1)
                list.add(
                    ContinuousParagraphItem(
                        text = entry.text,
                        pageIndex = mappedPageIdx,
                        chapterTitle = entry.chapterTitle,
                        footnotes = entry.footnotes,
                        pageEquivalent = entry.pageEquivalent,
                        semanticBlock = entry.semanticBlock,
                        footnoteEntries = entry.footnoteEntries,
                        fileKey = entry.fileKey
                    )
                )
            }
        } else {
            for (p in pages) {
                if (p.isCoverPage && p.coverBitmap != null) {
                    list.add(
                        ContinuousParagraphItem(
                            text = "",
                            pageIndex = p.globalIndex,
                            chapterTitle = p.chapterTitle,
                            footnotes = p.footnotes,
                            isCoverPage = true,
                            coverBitmap = p.coverBitmap,
                            pageEquivalent = "",
                            footnoteEntries = p.footnoteEntries,
                            fileKey = p.fileKey
                        )
                    )
                } else {
                    for ((pIdx, para) in p.paragraphs.withIndex()) {
                        val pEq = p.paragraphPageEquivalents.getOrNull(pIdx)?.trim()?.ifBlank { p.pageEquivalentLabel } ?: p.pageEquivalentLabel
                        val sem = p.semanticBlocks.getOrNull(pIdx)
                        list.add(
                            ContinuousParagraphItem(
                                text = para,
                                pageIndex = p.globalIndex,
                                chapterTitle = p.chapterTitle,
                                footnotes = p.footnotes,
                                pageEquivalent = pEq,
                                semanticBlock = sem,
                                footnoteEntries = p.footnoteEntries,
                                fileKey = p.fileKey
                            )
                        )
                    }
                }
            }
        }
        list
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val effectiveTopMarginDp = if (!isVerticalScrollMode && !isFixedLayoutBook) topMarginDp.coerceIn(0, 160) else 0
        val effectiveBottomMarginDp = if (!isVerticalScrollMode && !isFixedLayoutBook) bottomMarginDp.coerceIn(0, 160) else 0
        // Quantize viewport dimensions so minor status bar / bottom bar animations never re-paginate the book!
        val viewportWidthDp = ((maxWidth.value.toInt().coerceAtLeast(320) / 32) * 32).coerceAtLeast(320)
        val viewportHeightDp = (((maxHeight.value.toInt().coerceAtLeast(480) - effectiveTopMarginDp - effectiveBottomMarginDp).coerceAtLeast(320) / 16) * 16).coerceAtLeast(320)

        val initialOpenedPage = remember(document.id) { initialPage.coerceAtLeast(1) }
        val initialOpenedTotalPages = remember(document.id) { document.totalPages.coerceAtLeast(1) }
        val initialOpenedProgress = remember(document.id) { savedReadingProgress.coerceIn(0f, 1f) }
        var hasRestoredSavedPage by remember(document.id) { mutableStateOf(false) }
        var currentTrackedPage by remember(document.id) { mutableIntStateOf(initialOpenedPage) }

        val hasEpubPageListMetadata = remember(epubBook, pages) {
            val bookHas = epubBook?.let { b ->
                b.hasPageListMetadata || b.chapters.any { ch -> ch.paragraphPageEquivalents.any { it.isNotBlank() } }
            } ?: false
            bookHas || pages.any { it.pageEquivalentLabel.isNotBlank() }
        }

        fun notifyPageChanged(
            page: Int,
            total: Int,
            chapterTitle: String,
            pagesList: List<EpubPage> = pages,
            charOffsetInFirstPara: Int = 0
        ) {
            onPageChanged(page, total, chapterTitle)
            val position = buildReadingPositionForPage(
                document = document,
                page = page,
                totalPages = total,
                chapterTitle = chapterTitle,
                pagesList = pagesList,
                epubBook = epubBook,
                pdfEbookDocument = pdfEbookDocument,
                charOffsetInFirstPara = charOffsetInFirstPara
            )
            readerViewModel?.saveReadingPosition(document.id, position)
        }

        // Parse the book only once per document id (or use in-memory LRU cache); settings changes re-run only pagination!
        LaunchedEffect(document.id, document.filePath, preloadedEpubBook, pdfEbookDocument) {
            if (pdfEbookDocument != null) {
                epubBook = pdfEbookDocument.epubBook
                return@LaunchedEffect
            }
            if (preloadedEpubBook != null) {
                EpubParser.putCachedEpubBook("doc_${document.id}", preloadedEpubBook)
                epubBook = preloadedEpubBook
                return@LaunchedEffect
            }
            EpubParser.getCachedEpubBook("doc_${document.id}")?.let { cached ->
                epubBook = cached
                return@LaunchedEffect
            }
            if (pages.isEmpty()) {
                isLoading = true
                loadingStageName = "Reading file"
            }
            withContext(Dispatchers.IO) {
                try {
                    val file = File(document.filePath)
                    if (file.exists() && (file.isDirectory || file.length() > 0L)) {
                        val lowerPath = document.filePath.lowercase()
                        val isPdfFile = document.fileType.equals("PDF", ignoreCase = true) || lowerPath.endsWith(".pdf")
                        val isDaisyFile = document.fileType.equals("DAISY", ignoreCase = true) || file.isDirectory
                        val isHtmlFile = document.fileType.equals("HTML", ignoreCase = true) ||
                            lowerPath.endsWith(".html") ||
                            lowerPath.endsWith(".htm") ||
                            lowerPath.endsWith(".xhtml")
                        val isTxtFile = document.fileType.equals("TXT", ignoreCase = true) || lowerPath.endsWith(".txt")

                        if (!isPdfFile && !isTxtFile) {
                            loadingStageName = "Parsing chapters"
                            val parsedBook = when {
                                isDaisyFile -> {
                                    val daisyIdx = com.example.reader.DaisyIndexingService.indexArchiveOrDirectory(
                                        fileOrDir = file,
                                        fallbackTitle = document.title,
                                        fallbackAuthor = document.author
                                    )
                                    daisyIdx.epubBook
                                }
                                isHtmlFile -> {
                                    val rawHtml = file.readText(Charsets.UTF_8)
                                    EpubParser.parseHtmlDocument(
                                        rawHtml = rawHtml,
                                        fallbackTitle = document.title,
                                        fallbackAuthor = document.author
                                    )
                                }
                                else -> {
                                    try {
                                        EpubParser.parseEpub(file, cacheKey = "doc_${document.id}")
                                    } catch (_: Throwable) {
                                        val rawText = try { file.readText(Charsets.UTF_8) } catch (_: Throwable) { "" }
                                        EpubParser.parseHtmlDocument(
                                            rawHtml = rawText,
                                            fallbackTitle = document.title,
                                            fallbackAuthor = document.author
                                        )
                                    }
                                }
                            }
                            EpubParser.putCachedEpubBook("doc_${document.id}", parsedBook)
                            epubBook = parsedBook
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        }

        // Pagination effect: re-runs only pagination on typography or viewport changes (debounced by 300ms after initial open)
        LaunchedEffect(document.id, document.filePath, epubBook, pdfEbookDocument, fontSizeSp, lineSpacingMultiplier, marginDp, viewportWidthDp, viewportHeightDp, paginateByEpubPageEquivalents, effectiveParagraphStyle) {
            if (hasCompletedInitialLoad && pages.isNotEmpty()) {
                delay(300L)
            }
            val wasAlreadyRestored = hasRestoredSavedPage
            val oldPageCount = pages.size
            val oldPageIdx = (currentTrackedPage - 1).coerceAtLeast(0)
            if (pages.isEmpty()) {
                isLoading = true
                loadingStageName = if (epubBook == null && pdfEbookDocument == null) "Reading file" else "Paginating"
            }

            suspend fun applyPaginatedPages(
                newPagesList: List<EpubPage>,
                newReflowList: List<com.example.reader.PdfEbookReflowPage>,
                isProgressiveInitialPass: Boolean
            ) {
                if (newPagesList.isEmpty()) return
                val resumeReq = readerViewModel?.pendingResume?.value ?: pendingResume
                if ((!wasAlreadyRestored && !hasRestoredSavedPage) || resumeReq != null) {
                    val hReq = targetHighlightRequest
                    val resolvedTargetIndex = if (resumeReq != null) {
                        resolveResumeTargetPageIndex(
                            resume = resumeReq,
                            pagesList = newPagesList,
                            epubBook = epubBook,
                            document = document
                        )
                    } else if (hReq != null) {
                        findReflowPageIndexForHighlight(newPagesList, hReq)
                    } else if (pdfEbookDocument != null && newReflowList.isNotEmpty()) {
                        val matchingReflowIdx = newReflowList.indexOfFirst { rf ->
                            initialOpenedPage in rf.startPdfPage..rf.endPdfPage
                        }
                        if (matchingReflowIdx >= 0) {
                            matchingReflowIdx
                        } else {
                            val closestIdx = newReflowList.indices.minByOrNull { idx ->
                                kotlin.math.abs(newReflowList[idx].startPdfPage - initialOpenedPage)
                            } ?: 0
                            closestIdx.coerceIn(0, newPagesList.size - 1)
                        }
                    } else {
                        when {
                            initialOpenedPage <= 1 -> 0
                            initialOpenedTotalPages == newPagesList.size && initialOpenedPage in 1..newPagesList.size -> {
                                initialOpenedPage - 1
                            }
                            !isProgressiveInitialPass && initialOpenedTotalPages > 1 && initialOpenedProgress > 0f -> {
                                kotlin.math.round(initialOpenedProgress * (newPagesList.size - 1)).toInt().coerceIn(0, newPagesList.size - 1)
                            }
                            else -> (initialOpenedPage - 1).coerceIn(0, newPagesList.size - 1)
                        }
                    }
                    currentTrackedPage = resolvedTargetIndex + 1
                    val initialPageObj = newPagesList.getOrNull(resolvedTargetIndex)
                    val initialChapterTitle = initialPageObj?.chapterTitle ?: ""
                    notifyPageChanged(resolvedTargetIndex + 1, newPagesList.size, initialChapterTitle, newPagesList)
                    newReflowList.getOrNull(resolvedTargetIndex)?.let { onActiveReflowPageChanged?.invoke(it) }
                    val bookHasMeta = epubBook?.hasPageListMetadata == true || newPagesList.any { it.pageEquivalentLabel.isNotBlank() }
                    onActiveEpubPageEquivalentChanged?.invoke(initialPageObj?.pageEquivalentLabel ?: "", bookHasMeta)

                    if (resolvedTargetIndex >= 0) {
                        if (isVerticalScrollMode) {
                            val itemIdx = continuousItems.indexOfFirst { it.pageIndex >= resolvedTargetIndex }.coerceAtLeast(0)
                            verticalListState.scrollToItem(if (resolvedTargetIndex == 0) 0 else itemIdx + 1)
                        } else if (resolvedTargetIndex > 0 || pagerState.currentPage != resolvedTargetIndex) {
                            pagerState.scrollToPage(resolvedTargetIndex)
                        }
                    }
                    if (!isProgressiveInitialPass) {
                        if (resumeReq != null) {
                            readerViewModel?.clearPendingResume()
                        }
                        hasRestoredSavedPage = true
                    }
                } else {
                    val newTargetIdx = if (isProgressiveInitialPass) {
                        (currentTrackedPage - 1).coerceIn(0, newPagesList.size - 1)
                    } else if (oldPageCount > 1 && newPagesList.size > 1 && oldPageIdx > 0) {
                        val ratio = oldPageIdx.toFloat() / (oldPageCount - 1).coerceAtLeast(1)
                        kotlin.math.round(ratio * (newPagesList.size - 1)).toInt().coerceIn(0, newPagesList.size - 1)
                    } else {
                        oldPageIdx.coerceIn(0, newPagesList.size - 1)
                    }
                    currentTrackedPage = newTargetIdx + 1
                    val updatedPageObj = newPagesList.getOrNull(newTargetIdx)
                    val updatedChapterTitle = updatedPageObj?.chapterTitle ?: ""
                    notifyPageChanged(newTargetIdx + 1, newPagesList.size, updatedChapterTitle, newPagesList)
                    newReflowList.getOrNull(newTargetIdx)?.let { onActiveReflowPageChanged?.invoke(it) }
                    val bookHasMeta = epubBook?.hasPageListMetadata == true || newPagesList.any { it.pageEquivalentLabel.isNotBlank() }
                    onActiveEpubPageEquivalentChanged?.invoke(updatedPageObj?.pageEquivalentLabel ?: "", bookHasMeta)
                    if (isVerticalScrollMode) {
                        val targetItemIdx = continuousItems.indexOfFirst { it.pageIndex >= newTargetIdx }.coerceAtLeast(0)
                        verticalListState.scrollToItem(if (newTargetIdx == 0) 0 else (targetItemIdx + 1).coerceAtMost(continuousItems.size))
                    } else if (newTargetIdx != pagerState.currentPage) {
                        pagerState.scrollToPage(newTargetIdx)
                    }
                    hasRestoredSavedPage = true
                }
            }

            var finalPagesList: List<EpubPage> = emptyList()
            var finalReflowList: List<com.example.reader.PdfEbookReflowPage> = emptyList()

            withContext(Dispatchers.IO) {
                try {
                    if (pdfEbookDocument != null) {
                        loadingStageName = "Paginating"
                        val (reflowList, cList) = com.example.reader.PdfToEbookParser.paginatePdfEbook(
                            ebookDoc = pdfEbookDocument,
                            fontSizeSp = fontSizeSp,
                            lineSpacingMultiplier = lineSpacingMultiplier,
                            marginDp = marginDp,
                            viewportWidthDp = viewportWidthDp,
                            viewportHeightDp = viewportHeightDp
                        )
                        finalReflowList = reflowList
                        finalPagesList = reflowList.map { it.epubPage }
                        pdfReflowPages = reflowList
                        pages = finalPagesList
                        bookChapters = cList
                    } else {
                        val file = File(document.filePath)
                        val lowerPath = document.filePath.lowercase()
                        val isTxtFile = document.fileType.equals("TXT", ignoreCase = true) || lowerPath.endsWith(".txt")
                        if (isTxtFile && file.exists() && file.isFile) {
                            loadingStageName = "Paginating"
                            val text = file.readText(Charsets.UTF_8)
                            val (pList, cList) = EpubParser.paginateTextArticle(
                                document.title,
                                document.author,
                                text,
                                fontSizeSp,
                                lineSpacingMultiplier,
                                marginDp,
                                viewportWidthDp,
                                viewportHeightDp
                            )
                            finalPagesList = pList
                            pages = pList
                            bookChapters = cList
                        } else {
                            val activeBook = epubBook ?: return@withContext
                            loadingStageName = "Paginating"

                            // Progressive pagination: if book is large (> 8 chapters) and opening for the first time,
                            // paginate the prefix chapters covering the saved reading position first so the book opens immediately,
                            // then complete the remaining chapters in the background!
                            val totalChCount = activeBook.chapters.size
                            val estimatedNeededChapterIdx = if (initialOpenedProgress > 0f && totalChCount > 1) {
                                (initialOpenedProgress * totalChCount).toInt().coerceIn(0, totalChCount - 1)
                            } else if (initialOpenedPage > 1 && initialOpenedTotalPages > 1) {
                                ((initialOpenedPage.toFloat() / initialOpenedTotalPages) * totalChCount).toInt().coerceIn(0, totalChCount - 1)
                            } else {
                                0
                            }
                            val prefixChaptersCount = (estimatedNeededChapterIdx + 3).coerceAtLeast(3)

                            if (!hasCompletedInitialLoad && pages.isEmpty() && !activeBook.isFixedLayout && totalChCount > prefixChaptersCount + 2) {
                                val (prefixPages, prefixChs) = EpubParser.paginateBookPrefix(
                                    epubBook = activeBook,
                                    maxChapters = prefixChaptersCount,
                                    fontSizeSp = fontSizeSp,
                                    lineSpacingMultiplier = lineSpacingMultiplier,
                                    marginDp = marginDp,
                                    viewportWidthDp = viewportWidthDp,
                                    viewportHeightDp = viewportHeightDp,
                                    paginateByPageEquivalent = paginateByEpubPageEquivalents,
                                    paragraphStyle = effectiveParagraphStyle
                                )
                                if (prefixPages.isNotEmpty()) {
                                    pages = prefixPages
                                    bookChapters = prefixChs
                                    withContext(Dispatchers.Main) {
                                        isLoading = false
                                        applyPaginatedPages(prefixPages, emptyList(), isProgressiveInitialPass = true)
                                    }
                                }
                            }

                            val (pList, cList) = EpubParser.paginateBook(
                                activeBook,
                                fontSizeSp,
                                lineSpacingMultiplier,
                                marginDp,
                                viewportWidthDp,
                                viewportHeightDp,
                                paginateByPageEquivalent = paginateByEpubPageEquivalents,
                                paragraphStyle = effectiveParagraphStyle
                            )
                            finalPagesList = pList
                            pages = pList
                            bookChapters = cList
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                } finally {
                    val isWaitingForBookParse = epubBook == null && pdfEbookDocument == null &&
                        !document.fileType.equals("TXT", ignoreCase = true) && !document.filePath.lowercase().endsWith(".txt")
                    if (!isWaitingForBookParse) {
                        isLoading = false
                        hasCompletedInitialLoad = true
                    }
                }
            }

            if (finalPagesList.isNotEmpty()) {
                applyPaginatedPages(finalPagesList, finalReflowList, isProgressiveInitialPass = false)
            } else if (!isLoading) {
                hasRestoredSavedPage = true
            }
        }

        LaunchedEffect(pendingResume, pages.size, isVerticalScrollMode, continuousItems.size) {
            val resumeReq = pendingResume
            if (resumeReq != null && pages.isNotEmpty() && hasRestoredSavedPage) {
                val resolvedTargetIndex = resolveResumeTargetPageIndex(
                    resume = resumeReq,
                    pagesList = pages,
                    epubBook = epubBook,
                    document = document
                )
                currentTrackedPage = resolvedTargetIndex + 1
                val targetPageObj = pages.getOrNull(resolvedTargetIndex)
                val targetChapterTitle = targetPageObj?.chapterTitle ?: ""
                notifyPageChanged(resolvedTargetIndex + 1, pages.size, targetChapterTitle, pages)
                if (isVerticalScrollMode && continuousItems.isNotEmpty()) {
                    val itemIdx = continuousItems.indexOfFirst { it.pageIndex >= resolvedTargetIndex }.coerceAtLeast(0)
                    verticalListState.scrollToItem(if (resolvedTargetIndex == 0) 0 else itemIdx + 1)
                } else if (pagerState.currentPage != resolvedTargetIndex) {
                    pagerState.scrollToPage(resolvedTargetIndex)
                }
                readerViewModel?.clearPendingResume()
            }
        }


    LaunchedEffect(bookChapters) {
        if (bookChapters.isNotEmpty()) {
            onChaptersLoaded(bookChapters)
        }
    }

    LaunchedEffect(pages, epubBook) {
        if (pages.isNotEmpty()) {
            onPagesPaginated?.invoke(pages)
            val rawLandmarks = epubBook?.landmarks.orEmpty()
            if (rawLandmarks.isNotEmpty()) {
                val resolved = EpubParser.resolveLandmarksToPages(rawLandmarks, pages)
                onLandmarksLoaded?.invoke(resolved)
            } else {
                onLandmarksLoaded?.invoke(emptyList())
            }
        }
    }

    // Keep TTS supplied with the full readable prose of the currently active EPUB/TXT page
    LaunchedEffect(currentTrackedPage, pages) {
        if (pages.isNotEmpty()) {
            val idx = (currentTrackedPage - 1).coerceIn(0, pages.size - 1)
            val pageObj = pages.getOrNull(idx)
            val pageText = if (pageObj == null || pageObj.isCoverPage) {
                document.title
            } else {
                pageObj.paragraphs.joinToString("\n\n")
            }
            onActivePageTextChanged(pageText)
        }
    }

    // Ensure verticalListState is positioned at the restored page once continuousItems finishes recomputing
    LaunchedEffect(continuousItems, hasRestoredSavedPage) {
        if (hasRestoredSavedPage && isVerticalScrollMode && continuousItems.isNotEmpty()) {
            val targetPageIdx = (currentTrackedPage - 1).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            if (targetPageIdx > 0 && verticalListState.firstVisibleItemIndex == 0) {
                val itemIdx = continuousItems.indexOfFirst { it.pageIndex >= targetPageIdx }.coerceAtLeast(0)
                verticalListState.scrollToItem(itemIdx + 1)
            }
        }
    }

    // Keep Paginated and Vertical Scroll positions synchronized when user switches view mode
    LaunchedEffect(isVerticalScrollMode) {
        if (!hasRestoredSavedPage || pages.isEmpty() || continuousItems.isEmpty()) return@LaunchedEffect
        val targetPageIdx = (currentTrackedPage - 1).coerceIn(0, pages.size - 1)
        if (isVerticalScrollMode) {
            val targetItemIdx = continuousItems.indexOfFirst { it.pageIndex >= targetPageIdx }.coerceAtLeast(0)
            verticalListState.scrollToItem(if (targetPageIdx == 0) 0 else targetItemIdx + 1)
        } else {
            pagerState.scrollToPage(targetPageIdx)
        }
    }

    // Handle Volume Button Triggers
    LaunchedEffect(pageTurnTrigger, totalPages, isVerticalScrollMode, continuousItems.size, pageTurnAnimation, volumeKeyAnimation) {
        pageTurnTrigger.collect { direction ->
            if (isVerticalScrollMode) {
                if (continuousItems.isNotEmpty()) {
                    val step = (direction * 5)
                    val targetItem = (verticalListState.firstVisibleItemIndex + step).coerceIn(0, continuousItems.size - 1)
                    val contentIdx = (targetItem - 1).coerceIn(0, (continuousItems.size - 1).coerceAtLeast(0))
                    val targetPageNum = ((continuousItems.getOrNull(contentIdx)?.pageIndex ?: 0) + 1).coerceIn(1, totalPages)
                    currentTrackedPage = targetPageNum
                    val targetChapter = continuousItems.getOrNull(contentIdx)?.chapterTitle ?: "Chapter $targetPageNum"
                    notifyPageChanged(targetPageNum, totalPages, targetChapter)
                    if (volumeKeyAnimation) {
                        verticalListState.animateScrollToItem(targetItem)
                    } else {
                        verticalListState.scrollToItem(targetItem)
                    }
                }
            } else {
                val target = (pagerState.currentPage + direction).coerceIn(0, totalPages - 1)
                if (target != pagerState.currentPage) {
                    val targetPageNum = target + 1
                    currentTrackedPage = targetPageNum
                    val targetChapter = pages.getOrNull(target)?.chapterTitle ?: "Chapter $targetPageNum"
                    notifyPageChanged(targetPageNum, totalPages, targetChapter)
                    val effectiveAnim = if (volumeKeyAnimation) pageTurnAnimation else "NONE"
                    pagerState.turnToPageWithAnimation(target, effectiveAnim)
                }
            }
        }
    }

    // Synchronize page turning and vertical scrolling with active DAISY or EPUB 3 Media Overlay SMIL audio phrase
    val effectiveActiveAnchorId = activeSmilClip?.textAnchorId?.takeIf { it.isNotBlank() } ?: activeDaisyAnchorId
    val effectiveActiveFileKey = activeSmilClip?.textFileKey?.takeIf { it.isNotBlank() } ?: activeDaisyFileKey
    val isAnyOverlayAutoScrollEnabled = isDaisyAutoScrollEnabled || activeSmilClip != null

    LaunchedEffect(effectiveActiveAnchorId, activeDaisyTextSnippet, effectiveActiveFileKey, isAnyOverlayAutoScrollEnabled, isVerticalScrollMode, pages.size, continuousItems.size) {
        if (!isAnyOverlayAutoScrollEnabled || pages.isEmpty()) return@LaunchedEffect
        val cleanAnchor = effectiveActiveAnchorId.trim()
        val cleanSnippet = activeDaisyTextSnippet.trim()
        val cleanFile = effectiveActiveFileKey.trim().substringAfterLast("/")
        if (cleanAnchor.isEmpty() && cleanSnippet.isEmpty()) return@LaunchedEffect

        val normSnippet = normalizeForQuoteSearch(cleanSnippet)
        val prefixProbe = normSnippet.split(" ").filter { it.isNotBlank() }.take(5).joinToString(" ")

        if (isVerticalScrollMode && continuousItems.isNotEmpty()) {
            val targetItemIdx = continuousItems.indexOfFirst { ci ->
                !ci.isCoverPage && (
                    (cleanAnchor.isNotEmpty() && ci.semanticBlock?.anchorIds?.any { it.equals(cleanAnchor, ignoreCase = true) } == true &&
                        (cleanFile.isEmpty() || ci.fileKey.substringAfterLast("/").equals(cleanFile, ignoreCase = true))) ||
                        (normSnippet.length >= 8 && normalizeForQuoteSearch(ci.text).contains(normSnippet))
                    )
            }.takeIf { it >= 0 } ?: continuousItems.indexOfFirst { ci ->
                !ci.isCoverPage && (
                    (cleanAnchor.isNotEmpty() && ci.semanticBlock?.anchorIds?.any { it.equals(cleanAnchor, ignoreCase = true) } == true) ||
                        (prefixProbe.length >= 8 && normalizeForQuoteSearch(ci.text).contains(prefixProbe))
                    )
            }
            if (targetItemIdx >= 0) {
                val targetPageNum = ((continuousItems.getOrNull(targetItemIdx)?.pageIndex ?: 0) + 1).coerceIn(1, totalPages)
                currentTrackedPage = targetPageNum
                val targetChapter = continuousItems.getOrNull(targetItemIdx)?.chapterTitle ?: "Chapter $targetPageNum"
                notifyPageChanged(targetPageNum, totalPages, targetChapter)
                val visibleIndices = verticalListState.layoutInfo.visibleItemsInfo.map { it.index }
                val lazyTargetIdx = targetItemIdx + 1
                if (lazyTargetIdx !in visibleIndices) {
                    verticalListState.animateScrollToItem(lazyTargetIdx)
                }
            }
        } else {
            val targetPageIdx = pages.indexOfFirst { p ->
                !p.isCoverPage && (
                    (cleanAnchor.isNotEmpty() &&
                        (cleanFile.isEmpty() || p.fileKey.substringAfterLast("/").equals(cleanFile, ignoreCase = true)) &&
                        (p.anchorIds.any { it.equals(cleanAnchor, ignoreCase = true) } ||
                            p.semanticBlocks.any { sb -> sb.anchorIds.any { it.equals(cleanAnchor, ignoreCase = true) } })) ||
                        (normSnippet.length >= 8 && p.paragraphs.any { para -> normalizeForQuoteSearch(para).contains(normSnippet) })
                    )
            }.takeIf { it >= 0 } ?: pages.indexOfFirst { p ->
                !p.isCoverPage && (
                    (cleanAnchor.isNotEmpty() &&
                        (p.anchorIds.any { it.equals(cleanAnchor, ignoreCase = true) } ||
                            p.semanticBlocks.any { sb -> sb.anchorIds.any { it.equals(cleanAnchor, ignoreCase = true) } })) ||
                        (prefixProbe.length >= 8 && p.paragraphs.any { para -> normalizeForQuoteSearch(para).contains(prefixProbe) }) ||
                        (cleanFile.isNotEmpty() && p.fileKey.substringAfterLast("/").equals(cleanFile, ignoreCase = true) && cleanAnchor.isEmpty())
                    )
            }
            if (targetPageIdx >= 0 && targetPageIdx != pagerState.currentPage) {
                val targetPageNum = targetPageIdx + 1
                currentTrackedPage = targetPageNum
                val targetChapter = pages.getOrNull(targetPageIdx)?.chapterTitle ?: "Chapter $targetPageNum"
                notifyPageChanged(targetPageNum, totalPages, targetChapter)
                pagerState.turnToPageWithAnimation(targetPageIdx, pageTurnAnimation)
            }
        }
    }

    // Handle TOC or Highlight menu click jump request (searches for exact quote text first so reflowed pages always land on the highlighted text!)
    LaunchedEffect(targetPageRequest, targetHighlightRequest, jumpRequestToken, isVerticalScrollMode, pages.size, continuousItems.size) {
        val hReq = targetHighlightRequest
        if (hReq != null && pages.isNotEmpty()) {
            val quoteText = hReq.highlightedText.trim()
            val normQuote = normalizeForQuoteSearch(quoteText)
            val firstSegment = quoteText.lineSequence().firstOrNull { it.trim().isNotEmpty() }?.trim() ?: quoteText
            val normFirstSegment = normalizeForQuoteSearch(firstSegment)
            val prefixWords = normFirstSegment.split(" ").filter { it.isNotBlank() }.take(4).joinToString(" ")
            if (isVerticalScrollMode && continuousItems.isNotEmpty()) {
                val matchedItemIdx = continuousItems.indexOfFirst { item ->
                    if (item.isCoverPage) false
                    else {
                        val normItem = normalizeForQuoteSearch(item.text)
                        (normQuote.isNotEmpty() && normItem.contains(normQuote)) ||
                            (normFirstSegment.isNotEmpty() && normItem.contains(normFirstSegment)) ||
                            (prefixWords.length >= 6 && normItem.contains(prefixWords))
                    }
                }
                val resolvedPageIdx = findReflowPageIndexForHighlight(pages, hReq)
                val fallbackIdx = continuousItems.indexOfFirst { it.pageIndex >= resolvedPageIdx }.coerceAtLeast(0)
                val finalItemIdx = if (matchedItemIdx >= 0) matchedItemIdx else fallbackIdx
                val targetPageNum = ((continuousItems.getOrNull(finalItemIdx)?.pageIndex ?: 0) + 1).coerceIn(1, totalPages)
                currentTrackedPage = targetPageNum
                val targetChapter = continuousItems.getOrNull(finalItemIdx)?.chapterTitle ?: "Chapter $targetPageNum"
                notifyPageChanged(targetPageNum, totalPages, targetChapter)
                verticalListState.scrollToItem(finalItemIdx + 1)
            } else {
                val finalPageIdx = findReflowPageIndexForHighlight(pages, hReq)
                val targetPageNum = finalPageIdx + 1
                currentTrackedPage = targetPageNum
                val targetChapter = pages.getOrNull(finalPageIdx)?.chapterTitle ?: "Chapter $targetPageNum"
                notifyPageChanged(targetPageNum, totalPages, targetChapter)
                pagerState.turnToPageWithAnimation(finalPageIdx, pageTurnAnimation)
            }
        } else if (targetPageRequest != null && targetPageRequest in 0 until totalPages) {
            val targetPageNum = targetPageRequest + 1
            currentTrackedPage = targetPageNum
            val targetChapter = pages.getOrNull(targetPageRequest)?.chapterTitle ?: "Chapter $targetPageNum"
            notifyPageChanged(targetPageNum, totalPages, targetChapter)
            if (isVerticalScrollMode && continuousItems.isNotEmpty()) {
                val targetItemIdx = continuousItems.indexOfFirst { it.pageIndex >= targetPageRequest }.coerceAtLeast(0)
                verticalListState.scrollToItem(if (targetPageRequest == 0) 0 else targetItemIdx + 1)
            } else {
                pagerState.turnToPageWithAnimation(targetPageRequest, pageTurnAnimation)
            }
        }
    }

    // Notify active page changed in Paginated mode (including when total pages change after reflow or page turn settles)
    LaunchedEffect(pagerState, pages, pdfReflowPages, hasRestoredSavedPage, isVerticalScrollMode, hasEpubPageListMetadata) {
        if (!isVerticalScrollMode && hasRestoredSavedPage && pages.isNotEmpty()) {
            snapshotFlow { pagerState.currentPage }.collect { pageIdx ->
                val safeIdx = pageIdx.coerceIn(0, pages.size - 1)
                val currentPageNumber = safeIdx + 1
                currentTrackedPage = currentPageNumber
                val currentPageObj = pages.getOrNull(safeIdx)
                val chapterTitle = currentPageObj?.chapterTitle ?: "Chapter $currentPageNumber"
                notifyPageChanged(currentPageNumber, pages.size, chapterTitle)
                pdfReflowPages.getOrNull(safeIdx)?.let { onActiveReflowPageChanged?.invoke(it) }
                onActiveEpubPageEquivalentChanged?.invoke(currentPageObj?.pageEquivalentLabel ?: "", hasEpubPageListMetadata)
            }
        }
    }

    // Notify active page changed in Continuous Vertical Lazy List mode
    LaunchedEffect(verticalListState, hasRestoredSavedPage, isVerticalScrollMode, continuousItems, pages, pdfReflowPages, hasEpubPageListMetadata) {
        if (isVerticalScrollMode && hasRestoredSavedPage && continuousItems.isNotEmpty() && pages.isNotEmpty()) {
            snapshotFlow {
                Triple(
                    verticalListState.firstVisibleItemIndex,
                    verticalListState.firstVisibleItemScrollOffset,
                    !verticalListState.canScrollForward
                )
            }.collect { (rawFirstIdx, _, isAtBottom) ->
                if (isAtBottom && rawFirstIdx > 0) {
                    val lastPageObj = pages.last()
                    currentTrackedPage = pages.size
                    notifyPageChanged(pages.size, pages.size, lastPageObj.chapterTitle)
                    pdfReflowPages.lastOrNull()?.let { onActiveReflowPageChanged?.invoke(it) }
                    onActiveEpubPageEquivalentChanged?.invoke(lastPageObj.pageEquivalentLabel, hasEpubPageListMetadata)
                } else {
                    // Subtract 1 for the top spacer item in LazyColumn
                    val contentIdx = (rawFirstIdx - 1).coerceIn(0, continuousItems.size - 1)
                    val item = continuousItems.getOrNull(contentIdx)
                    if (item != null) {
                        val pageNum = (item.pageIndex + 1).coerceIn(1, (pages.size - 1).coerceAtLeast(1))
                        currentTrackedPage = pageNum
                        notifyPageChanged(pageNum, pages.size, item.chapterTitle)
                        pdfReflowPages.getOrNull(pageNum - 1)?.let { onActiveReflowPageChanged?.invoke(it) }
                        val eqLabel = item.pageEquivalent.ifBlank { pages.getOrNull(pageNum - 1)?.pageEquivalentLabel ?: "" }
                        onActiveEpubPageEquivalentChanged?.invoke(eqLabel, hasEpubPageListMetadata)
                    }
                }
            }
        }
    }

    val activeFontFamily = remember(fontFamilySetting) {
        when (fontFamilySetting) {
            "SANS" -> FontFamily.SansSerif
            "MONO" -> FontFamily.Monospace
            else -> FontFamily.Serif
        }
    }

    val activeTextAlign = remember(textAlignSetting) {
        when (textAlignSetting) {
            "JUSTIFY" -> TextAlign.Justify
            "CENTER" -> TextAlign.Center
            else -> TextAlign.Start
        }
    }

    val isRtlProgression = remember(epubBook) {
        epubBook?.pageProgressionDirection?.equals("rtl", ignoreCase = true) == true
    }
    val bookLayoutDirection = if (isRtlProgression) LayoutDirection.Rtl else LayoutDirection.Ltr

    var activePopupNoteEntry by remember { mutableStateOf<com.example.reader.EpubFootnoteEntry?>(null) }
    var popupNoteReturnPageIdx by remember { mutableStateOf<Int?>(null) }
    var zoomedFigureBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var zoomedFigureCaption by remember { mutableStateOf("") }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(color = activeTheme.textColor)
                Text(
                    text = loadingStageName,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = activeTheme.secondaryTextColor,
                    modifier = Modifier.testTag("epub_loading_stage_label")
                )
            }
        }
    } else if (pages.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "EPUB loaded.\n${document.title}",
                color = activeTheme.textColor,
                textAlign = TextAlign.Center
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            var isAnyTextDragging by remember { mutableStateOf(false) }
            var selectionDragRootY by remember { mutableStateOf<Float?>(null) }
            var activeSelectionToolbar by remember { mutableStateOf<ActiveSelectionToolbarState?>(null) }
            var activeSelectionOwnerKey by remember { mutableStateOf<String?>(null) }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (isVerticalScrollMode) {
                    var verticalContainerTopRootY by remember { mutableFloatStateOf(0f) }
                    var verticalContainerHeightPx by remember { mutableFloatStateOf(1f) }
                    var verticalMergedOwnerIdx by remember { mutableIntStateOf(-1) }
                    var verticalMergedExtraCount by remember { mutableIntStateOf(0) }

                    LaunchedEffect(selectionDragRootY, isAnyTextDragging) {
                        val dragY = selectionDragRootY
                        if (isAnyTextDragging && dragY != null && verticalContainerHeightPx > 100f) {
                            val edgeZonePx = with(density) { 105.dp.toPx() }
                            val maxSpeedPx = with(density) { 16.dp.toPx() }
                            val bottomThreshold = verticalContainerTopRootY + verticalContainerHeightPx - edgeZonePx
                            val topThreshold = verticalContainerTopRootY + edgeZonePx
                            while (true) {
                                val currentY = selectionDragRootY ?: break
                                if (currentY > bottomThreshold) {
                                    val factor = ((currentY - bottomThreshold) / edgeZonePx).coerceIn(0.25f, 1.4f)
                                    verticalListState.scrollBy(maxSpeedPx * factor)
                                } else if (currentY < topThreshold) {
                                    val factor = ((topThreshold - currentY) / edgeZonePx).coerceIn(0.25f, 1.4f)
                                    verticalListState.scrollBy(-maxSpeedPx * factor)
                                } else {
                                    break
                                }
                                delay(16L)
                            }
                        }
                    }

                    // Continuous Vertical Lazy List Mode:
                    // Renders all chapters and paragraphs in a single continuous vertical LazyColumn
                    // so text reflows seamlessly from top to bottom regardless of font size.
                    LazyColumn(
                        state = verticalListState,
                        userScrollEnabled = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { coords ->
                                verticalContainerTopRootY = coords.positionInRoot().y
                                verticalContainerHeightPx = coords.size.height.toFloat()
                            }
                            .padding(horizontal = marginDp.dp)
                            .testTag("epub_vertical_lazy_list")
                    ) {
                        item {
                            Spacer(modifier = Modifier.height(if (isUiVisible) 14.dp else 32.dp))
                        }

                        itemsIndexed(continuousItems) { idx, item ->
                            val isMergedIntoPrevious = verticalMergedOwnerIdx >= 0 &&
                                verticalMergedExtraCount > 0 &&
                                idx in (verticalMergedOwnerIdx + 1)..(verticalMergedOwnerIdx + verticalMergedExtraCount)

                            if (!isMergedIntoPrevious) {
                                if (item.isCoverPage && item.coverBitmap != null) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Image(
                                                bitmap = item.coverBitmap.asImageBitmap(),
                                                contentDescription = "Book Front Cover",
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier
                                                    .fillMaxWidth(0.85f)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .border(1.dp, activeTheme.dividerColor, RoundedCornerShape(6.dp))
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Text(
                                                text = "Cover Page • Scroll down to read",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = activeTheme.secondaryTextColor
                                            )
                                        }
                                    }
                                } else {
                                    val itemKey = "v_$idx"
                                    val prevPageEq = if (idx > 0) continuousItems[idx - 1].pageEquivalent else ""
                                    val showInlinePageEqDivider = showEpubPageEquivalents &&
                                        hasEpubPageListMetadata &&
                                        item.pageEquivalent.isNotBlank() &&
                                        item.pageEquivalent != prevPageEq
                                    if (showInlinePageEqDivider) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 6.dp, bottom = 4.dp)
                                                .testTag("epub_inline_print_page_marker_${item.pageEquivalent}"),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            HorizontalDivider(
                                                modifier = Modifier.weight(1f),
                                                color = activeTheme.dividerColor
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(2.dp),
                                                color = activeTheme.barColor,
                                                border = BorderStroke(1.dp, activeTheme.dividerColor)
                                            ) {
                                                Text(
                                                    text = "p. ${item.pageEquivalent}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = activeTheme.textColor,
                                                    fontWeight = FontWeight.Medium,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                            HorizontalDivider(
                                                modifier = Modifier.weight(1f),
                                                color = activeTheme.dividerColor
                                            )
                                        }
                                    }
                                    val followingInContinuous = remember(continuousItems, idx) {
                                        continuousItems.drop(idx + 1).take(30).filter { !it.isCoverPage }.map { it.text }
                                    }
                                    val unsupportedBlock = remember(pdfEbookDocument, item.text) {
                                        pdfEbookDocument?.blocks?.firstOrNull { b ->
                                            b.type == com.example.reader.PdfReflowBlockType.UNSUPPORTED_ELEMENT &&
                                                (b.text.equals(item.text, ignoreCase = true) || item.text.startsWith("[Unsupported PDF"))
                                        }
                                    }

                                    val embeddedImg = remember(epubBook, item.semanticBlock) {
                                        val imgPath = item.semanticBlock?.imagePath.orEmpty()
                                        if (imgPath.isBlank()) null
                                        else epubBook?.embeddedImages?.get(imgPath) ?: epubBook?.embeddedImages?.get(imgPath.substringAfterLast("/"))
                                    }

                                    val jumpToInternalTarget: (String, String, String) -> Unit = { _, targetFileKey, targetAnchorId ->
                                        val targetItemIdx = continuousItems.indexOfFirst { ci ->
                                            (targetAnchorId.isNotBlank() && ci.semanticBlock?.anchorIds?.any { it.equals(targetAnchorId, ignoreCase = true) } == true) ||
                                                (targetAnchorId.isBlank() && targetFileKey.isNotBlank() && ci.fileKey.equals(targetFileKey, ignoreCase = true))
                                        }.takeIf { it >= 0 } ?: continuousItems.indexOfFirst { ci ->
                                            targetFileKey.isNotBlank() && ci.fileKey.equals(targetFileKey, ignoreCase = true)
                                        }
                                        if (targetItemIdx >= 0) {
                                            val targetPageNum = ((continuousItems.getOrNull(targetItemIdx)?.pageIndex ?: 0) + 1).coerceIn(1, totalPages)
                                            currentTrackedPage = targetPageNum
                                            val targetChapter = continuousItems.getOrNull(targetItemIdx)?.chapterTitle ?: "Chapter $targetPageNum"
                                            notifyPageChanged(targetPageNum, totalPages, targetChapter)
                                            coroutineScope.launch {
                                                verticalListState.animateScrollToItem(targetItemIdx + 1)
                                            }
                                        }
                                    }

                                    val jumpBackToMainTextFromFn: (com.example.reader.EpubFootnoteEntry) -> Unit = { fnEntry ->
                                        val backAnchor = fnEntry.backlinkAnchorId.ifBlank { fnEntry.sourceAnchorId }
                                        val cleanSnippet = fnEntry.sourceContextSnippet
                                            .trim()
                                            .removePrefix("…")
                                            .removeSuffix("…")
                                            .removePrefix("...")
                                            .removeSuffix("...")
                                            .trim()
                                        val probe = if (cleanSnippet.length > 28) cleanSnippet.substring(4, 26).trim() else cleanSnippet.take(24).trim()
                                        val cleanMarker = fnEntry.displayMarker.trim('[', ']', '*', ' ')
                                        val targetItemIdx = continuousItems.indexOfFirst { ci ->
                                            ci.semanticBlock?.type != com.example.reader.EpubSemanticBlockType.FOOTNOTE_DEF && (
                                                (backAnchor.isNotBlank() && ci.semanticBlock?.anchorIds?.any { it.equals(backAnchor, ignoreCase = true) } == true) ||
                                                    (probe.length >= 8 && ci.text.contains(probe, ignoreCase = true)) ||
                                                    (cleanMarker.isNotEmpty() && ci.text.contains("[$cleanMarker]"))
                                                )
                                        }
                                        if (targetItemIdx >= 0) {
                                            val targetPageNum = ((continuousItems.getOrNull(targetItemIdx)?.pageIndex ?: 0) + 1).coerceIn(1, totalPages)
                                            currentTrackedPage = targetPageNum
                                            val targetChapter = continuousItems.getOrNull(targetItemIdx)?.chapterTitle ?: "Chapter $targetPageNum"
                                            notifyPageChanged(targetPageNum, totalPages, targetChapter)
                                            coroutineScope.launch {
                                                verticalListState.scrollToItem(targetItemIdx + 1)
                                            }
                                        } else if (onFootnoteEntrySelected != null) {
                                            onFootnoteEntrySelected(fnEntry)
                                        } else {
                                            onFootnoteClick(fnEntry.displayMarker, fnEntry.noteText)
                                        }
                                    }

                                    var selectedVerticalHighlightForSheet by remember { mutableStateOf<HighlightEntity?>(null) }

                                    val sb = item.semanticBlock
                                    val blockLayoutDirection = resolveBlockLayoutDirection(sb, bookLayoutDirection)
                                    val isPhase4CustomBlock = sb != null && (
                                        sb.type == com.example.reader.EpubSemanticBlockType.TABLE ||
                                            sb.type == com.example.reader.EpubSemanticBlockType.PREFORMATTED ||
                                            sb.type == com.example.reader.EpubSemanticBlockType.DEFINITION_LIST ||
                                            sb.type == com.example.reader.EpubSemanticBlockType.CALLOUT ||
                                            (sb.type == com.example.reader.EpubSemanticBlockType.FIGURE && embeddedImg != null)
                                        )
                                    val isSmilActiveOnThisBlock = activeSmilClip != null &&
                                        activeSmilClip.textAnchorId.isNotBlank() &&
                                        sb?.anchorIds?.any { it.equals(activeSmilClip.textAnchorId, ignoreCase = true) } == true &&
                                        (activeSmilClip.textFileKey.isBlank() || item.fileKey.substringAfterLast("/").equals(activeSmilClip.textFileKey.substringAfterLast("/"), ignoreCase = true))

                                    CompositionLocalProvider(LocalLayoutDirection provides blockLayoutDirection) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .then(
                                                    if (isSmilActiveOnThisBlock) {
                                                        Modifier
                                                            .border(width = 2.dp, color = Color.Black)
                                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                                            .testTag("epub_active_media_overlay_block_${activeSmilClip?.textAnchorId.orEmpty()}")
                                                    } else {
                                                        Modifier
                                                    }
                                                )
                                        ) {
                                            if (isPhase4CustomBlock && sb != null) {
                                                EpubPhase4BlockRenderer(
                                                    block = sb,
                                                    embeddedImageBitmap = embeddedImg,
                                                    activeTheme = activeTheme,
                                                    fontSizeSp = fontSizeSp,
                                                    lineSpacingMultiplier = lineSpacingMultiplier,
                                                    onFigureZoom = { bmp, caption ->
                                                        zoomedFigureBitmap = bmp
                                                        zoomedFigureCaption = caption
                                                    },
                                                    activeStandaloneAudioPath = mediaOverlayState.activeStandaloneAudioPath,
                                                    isStandalonePlaying = mediaOverlayState.isStandalonePlaying,
                                                    onToggleStandaloneAudio = { audioPath ->
                                                        mediaOverlayPlayer.toggleStandaloneAudio(audioPath)
                                                    }
                                                )
                                            } else {
                                                Column(modifier = Modifier.fillMaxWidth()) {
                                                    SelectableParagraphItem(
                                                        paragraph = item.text,
                                                        pageIndex = item.pageIndex,
                                                        chapterTitle = item.chapterTitle,
                                                        activeTheme = activeTheme,
                                                        activeFontFamily = activeFontFamily,
                                                        fontSizeSp = fontSizeSp,
                                                        lineSpacingMultiplier = lineSpacingMultiplier,
                                                        activeTextAlign = activeTextAlign,
                                                        matchingHighlights = highlights,
                                                        footnotesMap = item.footnotes,
                                                        semanticBlock = item.semanticBlock,
                                                        footnoteEntries = item.footnoteEntries,
                                                        embeddedImageBitmap = embeddedImg,
                                                        onInternalLinkClick = jumpToInternalTarget,
                                                        onFootnoteEntryClick = { entry ->
                                                            popupNoteReturnPageIdx = item.pageIndex
                                                            activePopupNoteEntry = entry
                                                        },
                                                        onJumpBackFromFootnote = jumpBackToMainTextFromFn,
                                                        followingParagraphs = followingInContinuous,
                                                        shouldClearSelection = activeSelectionOwnerKey != null && activeSelectionOwnerKey != itemKey,
                                                        isPdfDocument = pdfEbookDocument != null,
                                                        resolveQuotePageSpan = if (pdfEbookDocument != null) {
                                                            { q, fallbackEbookPage ->
                                                                val fallbackPdf = pdfReflowPages.getOrNull((fallbackEbookPage - 1).coerceAtLeast(0))?.startPdfPage ?: 1
                                                                pdfEbookDocument.resolvePageSpanForQuote(q, fallbackPdf)
                                                            }
                                                        } else null,
                                                        resolvePassageCitation = { q, chTitle ->
                                                            EpubParser.resolvePassageCitation(
                                                                epubBook = epubBook,
                                                                quote = q,
                                                                fallbackChapterTitle = chTitle
                                                            )
                                                        },
                                                        onToggleUi = onToggleUi,
                                                        onFootnoteClick = onFootnoteClick,
                                                        onRequestHighlight = onRequestHighlight,
                                                        onQuickSaveHighlight = onQuickSaveHighlight,
                                                        onSpeakSelection = onSpeakSelection,
                                                        onEditHighlight = { hl ->
                                                            selectedVerticalHighlightForSheet = hl
                                                        },
                                                        onSelectionActive = { active ->
                                                            if (active) {
                                                                activeSelectionOwnerKey = itemKey
                                                                isAnyTextDragging = true
                                                            } else if (activeSelectionOwnerKey == itemKey) {
                                                                isAnyTextDragging = false
                                                                selectionDragRootY = null
                                                            }
                                                        },
                                                        onSelectionDragRootY = { rootY ->
                                                            if (activeSelectionOwnerKey == itemKey || rootY != null) {
                                                                selectionDragRootY = rootY
                                                            }
                                                        },
                                                        onMergedParagraphCountChanged = { count ->
                                                            if (count > 0) {
                                                                verticalMergedOwnerIdx = idx
                                                                verticalMergedExtraCount = count
                                                            } else if (verticalMergedOwnerIdx == idx) {
                                                                verticalMergedOwnerIdx = -1
                                                                verticalMergedExtraCount = 0
                                                            }
                                                        },
                                                        onSelectionToolbarStateChanged = { toolbarState ->
                                                            if (toolbarState != null) {
                                                                activeSelectionOwnerKey = itemKey
                                                                activeSelectionToolbar = toolbarState
                                                            } else if (activeSelectionOwnerKey == itemKey) {
                                                                activeSelectionOwnerKey = null
                                                                activeSelectionToolbar = null
                                                            }
                                                        },
                                                        activeDaisyAnchorId = activeDaisyAnchorId,
                                                        activeDaisyTextSnippet = activeDaisyTextSnippet,
                                                            onDaisyPhraseTapped = if (hasMediaOverlays || onDaisyPhraseTapped != null) {
                                                                { tappedAnchorId, tappedSnippet ->
                                                                    var handled = false
                                                                    if (hasMediaOverlays) {
                                                                        val candidateAnchors = buildList {
                                                                            if (tappedAnchorId.isNotBlank()) add(tappedAnchorId)
                                                                            sb?.anchorIds?.forEach { if (it.isNotBlank() && !it.startsWith("__epub_dir_")) add(it) }
                                                                        }
                                                                        for (cand in candidateAnchors) {
                                                                            if (mediaOverlayPlayer.playFromTextAnchor(cand, item.fileKey)) {
                                                                                handled = true
                                                                                break
                                                                            }
                                                                        }
                                                                    }
                                                                    if (!handled) {
                                                                        onDaisyPhraseTapped?.invoke(tappedAnchorId, tappedSnippet)
                                                                    }
                                                                }
                                                            } else null,
                                                            paragraphStyleSetting = effectiveParagraphStyle,
                                                            isFirstAfterHeading = idx > 0 && (
                                                                continuousItems[idx - 1].semanticBlock?.type == com.example.reader.EpubSemanticBlockType.HEADING ||
                                                                    continuousItems[idx - 1].text.startsWith("## ")
                                                                )
                                                        )
                                                    if (isSmilActiveOnThisBlock) {
                                                        HorizontalDivider(
                                                            thickness = 2.dp,
                                                            color = Color.Black,
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .testTag("epub_active_media_overlay_underline")
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (selectedVerticalHighlightForSheet != null) {
                                        val hl = selectedVerticalHighlightForSheet!!
                                        EpubHighlightReferenceActionSheet(
                                            highlight = hl,
                                            epubBook = epubBook,
                                            activeTheme = activeTheme,
                                            onDismiss = { selectedVerticalHighlightForSheet = null },
                                            onOpenHighlightEditor = {
                                                selectedVerticalHighlightForSheet = null
                                                onEditHighlight(hl)
                                            }
                                        )
                                    }

                                    if (unsupportedBlock != null && pdfRendererForSnapshots != null) {
                                        UnsupportedPdfElementSnapshotCard(
                                            block = unsupportedBlock,
                                            renderer = pdfRendererForSnapshots,
                                            activeTheme = activeTheme,
                                            onOpenOriginalPdfPage = { pdfPage ->
                                                onSwitchToOriginalPdfPage?.invoke(pdfPage)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(if (activeSelectionToolbar != null) 220.dp else if (isUiVisible) 80.dp else 40.dp))
                        }
                    }
                } else {
                    // Horizontal Pager enabling horizontal swiping for next/previous pages!
                    // For RTL books, reverseLayout = true so swipe right turns to the next page!
                    val effectiveTwoColumn = columnCount == 2 && !isFixedLayoutBook
                    val customPageSize = remember(effectiveTwoColumn) {
                        if (effectiveTwoColumn) {
                            object : androidx.compose.foundation.pager.PageSize {
                                override fun androidx.compose.ui.unit.Density.calculateMainAxisPageSize(
                                    availableSpace: Int,
                                    pageSpacing: Int
                                ): Int = ((availableSpace - pageSpacing) / 2).coerceAtLeast(1)
                            }
                        } else {
                            androidx.compose.foundation.pager.PageSize.Fill
                        }
                    }
                    HorizontalPager(
                        state = pagerState,
                        pageSize = customPageSize,
                        pageSpacing = if (effectiveTwoColumn) 8.dp else 0.dp,
                        reverseLayout = isRtlProgression,
                        beyondViewportPageCount = 1,
                        userScrollEnabled = !isAnyTextDragging && activeSelectionToolbar == null,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(if (isRtlProgression) "epub_horizontal_pager_rtl" else "epub_horizontal_pager_ltr")
                    ) { pageIndex ->
                        val currentPageObj = pages.getOrNull(pageIndex)
                        val chapterTitle = currentPageObj?.chapterTitle ?: "Chapter ${pageIndex + 1}"
                        val pageLazyListState = rememberLazyListState()
                        var isSelectionActiveOnThisPage by remember { mutableStateOf(false) }
                        var pageSelectionDragRootY by remember { mutableStateOf<Float?>(null) }
                        var pageContainerTopRootY by remember { mutableFloatStateOf(0f) }
                        var pageContainerHeightPx by remember { mutableFloatStateOf(1f) }
                        var pageMergedOwnerIdx by remember { mutableIntStateOf(-1) }
                        var pageMergedExtraCount by remember { mutableIntStateOf(0) }

                        // If we navigated to this page for a specific highlight (e.g. when a long paragraph or large font size causes vertical overflow on the page),
                        // scroll pageLazyListState directly to the paragraph containing the highlighted text!
                        LaunchedEffect(targetHighlightRequest, jumpRequestToken, pagerState.currentPage, currentPageObj) {
                            val hReq = targetHighlightRequest
                            if (hReq != null && pagerState.currentPage == pageIndex && currentPageObj != null && !currentPageObj.isCoverPage) {
                                val normQuote = normalizeForQuoteSearch(hReq.highlightedText)
                                val firstSeg = normalizeForQuoteSearch(
                                    hReq.highlightedText.lineSequence().firstOrNull { it.trim().isNotEmpty() } ?: hReq.highlightedText
                                )
                                val prefixWords = firstSeg.split(" ").filter { it.isNotBlank() }.take(4).joinToString(" ")
                                val paraIdx = currentPageObj.paragraphs.indexOfFirst { p ->
                                    val np = normalizeForQuoteSearch(p)
                                    (normQuote.isNotEmpty() && np.contains(normQuote)) ||
                                        (firstSeg.length >= 6 && np.contains(firstSeg)) ||
                                        (prefixWords.length >= 6 && np.contains(prefixWords)) ||
                                        (np.length >= 12 && normQuote.contains(np))
                                }
                                if (paraIdx > 0) {
                                    // +1 for the page header item in LazyColumn
                                    pageLazyListState.animateScrollToItem(paraIdx + 1)
                                } else {
                                    pageLazyListState.scrollToItem(0)
                                }
                            }
                        }

                        // Also scroll pageLazyListState when active DAISY SMIL phrase is inside a page that has multiple paragraphs
                        LaunchedEffect(activeDaisyAnchorId, activeDaisyTextSnippet, pagerState.currentPage, currentPageObj, isDaisyAutoScrollEnabled) {
                            if (isDaisyAutoScrollEnabled && pagerState.currentPage == pageIndex && currentPageObj != null && !currentPageObj.isCoverPage) {
                                val cleanAnchor = activeDaisyAnchorId.trim()
                                val cleanSnippet = activeDaisyTextSnippet.trim()
                                if (cleanAnchor.isNotEmpty() || cleanSnippet.isNotEmpty()) {
                                    val normSnippet = normalizeForQuoteSearch(cleanSnippet)
                                    val paraIdx = currentPageObj.paragraphs.indices.firstOrNull { pIdx ->
                                        val sem = currentPageObj.semanticBlocks.getOrNull(pIdx)
                                        val pText = currentPageObj.paragraphs[pIdx]
                                        (cleanAnchor.isNotEmpty() && sem?.anchorIds?.any { it.equals(cleanAnchor, ignoreCase = true) } == true) ||
                                            (normSnippet.length >= 8 && normalizeForQuoteSearch(pText).contains(normSnippet))
                                    }
                                    if (paraIdx != null && paraIdx > 0) {
                                        val visibleIndices = pageLazyListState.layoutInfo.visibleItemsInfo.map { it.index }
                                        if ((paraIdx + 1) !in visibleIndices) {
                                            pageLazyListState.animateScrollToItem(paraIdx + 1)
                                        }
                                    }
                                }
                            }
                        }

                        // Build the list of paragraphs for this page. When text selection is active,
                        // automatically append all subsequent pages so the user can continuously scroll
                        // down and select across page boundaries without starting a new selection!
                        val activePageItems = remember(pages, pageIndex, isSelectionActiveOnThisPage, bookWideFootnoteEntries, bookWideFootnotesMap) {
                            val currentParas = currentPageObj?.paragraphs ?: emptyList()
                            val currentParaEqs = currentPageObj?.paragraphPageEquivalents ?: emptyList()
                            val currentFns = if (bookWideFootnotesMap.isNotEmpty()) bookWideFootnotesMap else (currentPageObj?.footnotes ?: emptyMap())
                            val currentSemBlocks = currentPageObj?.semanticBlocks ?: emptyList()
                            val currentFnEntries = if (bookWideFootnoteEntries.isNotEmpty()) bookWideFootnoteEntries else (currentPageObj?.footnoteEntries ?: emptyMap())
                            val currentFileKey = currentPageObj?.fileKey.orEmpty()
                            val baseList = currentParas.mapIndexed { pIdx, text ->
                                ContinuousParagraphItem(
                                    text = text,
                                    pageIndex = pageIndex,
                                    chapterTitle = chapterTitle,
                                    footnotes = currentFns,
                                    pageEquivalent = currentParaEqs.getOrNull(pIdx)?.trim() ?: "",
                                    semanticBlock = currentSemBlocks.getOrNull(pIdx),
                                    footnoteEntries = currentFnEntries,
                                    fileKey = currentFileKey
                                )
                            }
                            if (!isSelectionActiveOnThisPage) {
                                baseList
                            } else {
                                val extended = ArrayList<ContinuousParagraphItem>(baseList.size + 60)
                                extended.addAll(baseList)
                                val maxExtraPages = minOf(pages.size, pageIndex + 10)
                                for (nextPageIdx in (pageIndex + 1) until maxExtraPages) {
                                    val nextP = pages[nextPageIdx]
                                    if (nextP.isCoverPage) continue
                                    for ((npIdx, para) in nextP.paragraphs.withIndex()) {
                                        extended.add(
                                            ContinuousParagraphItem(
                                                text = para,
                                                pageIndex = nextPageIdx,
                                                chapterTitle = nextP.chapterTitle,
                                                footnotes = currentFns,
                                                pageEquivalent = nextP.paragraphPageEquivalents.getOrNull(npIdx)?.trim() ?: "",
                                                semanticBlock = nextP.semanticBlocks.getOrNull(npIdx),
                                                footnoteEntries = currentFnEntries,
                                                fileKey = nextP.fileKey
                                            )
                                        )
                                    }
                                }
                                extended
                            }
                        }

                        // Auto-scroll the page vertically when the user drags their selection near the bottom or top edge!
                        LaunchedEffect(pageSelectionDragRootY, isSelectionActiveOnThisPage) {
                            val dragY = pageSelectionDragRootY
                            if (isSelectionActiveOnThisPage && dragY != null && pageContainerHeightPx > 100f) {
                                val edgeZonePx = with(density) { 105.dp.toPx() }
                                val maxSpeedPx = with(density) { 16.dp.toPx() }
                                val bottomThreshold = pageContainerTopRootY + pageContainerHeightPx - edgeZonePx
                                val topThreshold = pageContainerTopRootY + edgeZonePx
                                while (true) {
                                    val currentY = pageSelectionDragRootY ?: break
                                    if (currentY > bottomThreshold) {
                                        val factor = ((currentY - bottomThreshold) / edgeZonePx).coerceIn(0.25f, 1.4f)
                                        pageLazyListState.scrollBy(maxSpeedPx * factor)
                                    } else if (currentY < topThreshold) {
                                        val factor = ((topThreshold - currentY) / edgeZonePx).coerceIn(0.25f, 1.4f)
                                        pageLazyListState.scrollBy(-maxSpeedPx * factor)
                                    } else {
                                        break
                                    }
                                    delay(16L)
                                }
                            }
                        }

                        // When selection ends after scrolling down into subsequent pages, return cleanly
                        // to Paginated Page View at the page the user scrolled to!
                        var highestReachedPageIdx by remember { mutableIntStateOf(pageIndex) }
                        LaunchedEffect(pageLazyListState, isSelectionActiveOnThisPage, activePageItems) {
                            if (isSelectionActiveOnThisPage && activePageItems.isNotEmpty()) {
                                snapshotFlow { pageLazyListState.firstVisibleItemIndex }
                                    .collect { firstIdx ->
                                        val item = activePageItems.getOrNull((firstIdx - 1).coerceIn(0, activePageItems.size - 1))
                                        if (item != null) {
                                            highestReachedPageIdx = item.pageIndex
                                        }
                                    }
                            }
                        }

                        LaunchedEffect(isSelectionActiveOnThisPage) {
                            if (!isSelectionActiveOnThisPage) {
                                val targetPage = highestReachedPageIdx.coerceIn(0, totalPages - 1)
                                pageLazyListState.scrollToItem(0)
                                if (targetPage != pageIndex && pagerState.currentPage == pageIndex) {
                                    pagerState.scrollToPage(targetPage)
                                }
                                highestReachedPageIdx = pageIndex
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .applyPhysicalPageTurnEffect(
                                    pagerState = pagerState,
                                    pageIndex = pageIndex,
                                    pageTurnAnimation = pageTurnAnimation,
                                    activeTheme = activeTheme
                                )
                        ) {
                            if (currentPageObj?.isCoverPage == true && currentPageObj.coverBitmap != null) {
                                // Front Cover Page
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp)
                                        .pointerInput(pageTurnAnimation, totalPages, isRtlProgression) {
                                            detectTapGestures { offset ->
                                                val leftDelta = if (isRtlProgression) 1 else -1
                                                val rightDelta = if (isRtlProgression) -1 else 1
                                                if (offset.x < size.width * 0.2f) {
                                                    val target = (pagerState.currentPage + leftDelta).coerceIn(0, totalPages - 1)
                                                    if (target != pagerState.currentPage) {
                                                        val targetPageNum = target + 1
                                                        currentTrackedPage = targetPageNum
                                                        val targetChapter = pages.getOrNull(target)?.chapterTitle ?: "Chapter $targetPageNum"
                                                        notifyPageChanged(targetPageNum, totalPages, targetChapter)
                                                        coroutineScope.launch {
                                                            pagerState.turnToPageWithAnimation(target, pageTurnAnimation)
                                                        }
                                                    }
                                                } else if (offset.x > size.width * 0.8f) {
                                                    val target = (pagerState.currentPage + rightDelta).coerceIn(0, totalPages - 1)
                                                    if (target != pagerState.currentPage) {
                                                        val targetPageNum = target + 1
                                                        currentTrackedPage = targetPageNum
                                                        val targetChapter = pages.getOrNull(target)?.chapterTitle ?: "Chapter $targetPageNum"
                                                        notifyPageChanged(targetPageNum, totalPages, targetChapter)
                                                        coroutineScope.launch {
                                                            pagerState.turnToPageWithAnimation(target, pageTurnAnimation)
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Image(
                                            bitmap = currentPageObj.coverBitmap.asImageBitmap(),
                                            contentDescription = "Book Front Cover",
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .fillMaxWidth(0.9f)
                                                .clip(RoundedCornerShape(6.dp))
                                                .border(1.dp, activeTheme.dividerColor, RoundedCornerShape(6.dp))
                                        )
                                        Spacer(modifier = Modifier.height(14.dp))
                                        Text(
                                            text = "Cover Page • Swipe to read",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = activeTheme.secondaryTextColor
                                        )
                                    }
                                }
                            } else if (currentPageObj?.isFixedLayoutPage == true) {
                                EpubFixedLayoutPageView(
                                    pageObj = currentPageObj,
                                    pageIndex = pageIndex,
                                    totalPages = totalPages,
                                    epubBook = epubBook,
                                    documentTitle = document.title,
                                    activeTheme = activeTheme,
                                    activeFontFamily = activeFontFamily,
                                    bookLayoutDirection = bookLayoutDirection,
                                    isUiVisible = isUiVisible,
                                    showEpubPageEquivalents = showEpubPageEquivalents,
                                    onToggleUi = onToggleUi,
                                    onTurnPage = { delta ->
                                        val effectiveDelta = if (isRtlProgression) -delta else delta
                                        val target = (pagerState.currentPage + effectiveDelta).coerceIn(0, totalPages - 1)
                                        if (target != pagerState.currentPage) {
                                            val targetPageNum = target + 1
                                            currentTrackedPage = targetPageNum
                                            val targetChapter = pages.getOrNull(target)?.chapterTitle ?: "Chapter $targetPageNum"
                                            notifyPageChanged(targetPageNum, totalPages, targetChapter)
                                            coroutineScope.launch {
                                                pagerState.turnToPageWithAnimation(target, pageTurnAnimation)
                                            }
                                        }
                                    },
                                    onFigureZoom = { bmp, caption ->
                                        zoomedFigureBitmap = bmp
                                        zoomedFigureCaption = caption
                                    }
                                )
                            } else {
                                // Regular text page with configurable margins and formatting.
                                // Unlocked for vertical scrolling during selection so you can scroll down across pages!
                                LazyColumn(
                                    state = pageLazyListState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .onGloballyPositioned { coords ->
                                            pageContainerTopRootY = coords.positionInRoot().y
                                            pageContainerHeightPx = coords.size.height.toFloat()
                                        }
                                        .padding(
                                            start = marginDp.dp,
                                            end = marginDp.dp,
                                            top = effectiveTopMarginDp.dp,
                                            bottom = effectiveBottomMarginDp.dp
                                        ),
                                    userScrollEnabled = true
                                ) {
                                    item {
                                        Spacer(modifier = Modifier.height(if (isUiVisible) 14.dp else 32.dp))
                                        val reflowMeta = pdfReflowPages.getOrNull(pageIndex)
                                        val epubPageEqLabel = currentPageObj?.pageEquivalentLabel ?: ""
                                        val pageHeaderSummary = if (reflowMeta != null) {
                                            reflowMeta.formatStatusBarSummary(pageIndex + 1, totalPages)
                                        } else if (showEpubPageEquivalents && epubPageEqLabel.isNotBlank()) {
                                            val prefix = if (epubPageEqLabel.contains("–") || epubPageEqLabel.contains("-")) "Print pp. $epubPageEqLabel" else "Print p. $epubPageEqLabel"
                                            if (paginateByEpubPageEquivalents) {
                                                "$prefix (1:1 Print Page • Ebook ${pageIndex + 1}/$totalPages)"
                                            } else {
                                                "$prefix • Ebook ${pageIndex + 1} of $totalPages"
                                            }
                                        } else {
                                            "Page ${pageIndex + 1} of $totalPages"
                                        }
                                        val pageStatusChipText = remember(currentPageObj, epubPageEqLabel, showEpubPageEquivalents) {
                                            buildEpubStatusMonospaceChipText(
                                                pageObj = currentPageObj,
                                                pageEquivalentLabel = if (showEpubPageEquivalents) epubPageEqLabel else ""
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (isSelectionActiveOnThisPage) {
                                                    "$chapterTitle • $pageHeaderSummary (Continuous Selection Scroll Active)"
                                                } else {
                                                    "$chapterTitle • $pageHeaderSummary"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelectionActiveOnThisPage) Color(0xFF7C3AED) else activeTheme.secondaryTextColor,
                                                fontWeight = if (isSelectionActiveOnThisPage) FontWeight.Bold else FontWeight.Normal,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("reflow_page_header_${pageIndex + 1}")
                                            )
                                            if (pageStatusChipText.isNotBlank() && reflowMeta == null) {
                                                Surface(
                                                    shape = RoundedCornerShape(2.dp),
                                                    color = activeTheme.barColor,
                                                    border = BorderStroke(1.dp, activeTheme.dividerColor),
                                                    modifier = Modifier
                                                        .padding(start = 8.dp)
                                                        .testTag("epub_header_print_page_badge_${pageIndex + 1}")
                                                ) {
                                                    Text(
                                                        text = pageStatusChipText,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = activeTheme.textColor,
                                                        fontWeight = FontWeight.Medium,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                    }

                                    itemsIndexed(activePageItems) { idx, item ->
                                        val isMergedIntoPrevious = pageMergedOwnerIdx >= 0 &&
                                            pageMergedExtraCount > 0 &&
                                            idx in (pageMergedOwnerIdx + 1)..(pageMergedOwnerIdx + verticalMergedExtraCountOrPage(pageMergedExtraCount))

                                        if (!isMergedIntoPrevious) {
                                            val itemKey = "p_${pageIndex}_$idx"
                                            val prevPageEq = if (idx > 0) activePageItems[idx - 1].pageEquivalent else ""
                                            val showMidPagePrintBreak = showEpubPageEquivalents &&
                                                hasEpubPageListMetadata &&
                                                idx > 0 &&
                                                item.pageEquivalent.isNotBlank() &&
                                                item.pageEquivalent != prevPageEq
                                            if (showMidPagePrintBreak) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(top = 6.dp, bottom = 4.dp)
                                                        .testTag("epub_inline_print_page_marker_${item.pageEquivalent}"),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    HorizontalDivider(
                                                        modifier = Modifier.weight(1f),
                                                        color = activeTheme.dividerColor
                                                    )
                                                    Surface(
                                                        shape = RoundedCornerShape(2.dp),
                                                        color = activeTheme.barColor,
                                                        border = BorderStroke(1.dp, activeTheme.dividerColor)
                                                    ) {
                                                        Text(
                                                            text = "p. ${item.pageEquivalent}",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = activeTheme.textColor,
                                                            fontWeight = FontWeight.Medium,
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                    HorizontalDivider(
                                                        modifier = Modifier.weight(1f),
                                                        color = activeTheme.dividerColor
                                                    )
                                                }
                                            }
                                            val followingFromNextPages = remember(pages, pageIndex, idx, activePageItems) {
                                                val restOnCurrentAndSubsequent = ArrayList<String>(16)
                                                for (remIdx in (idx + 1) until activePageItems.size) {
                                                    restOnCurrentAndSubsequent.add(activePageItems[remIdx].text)
                                                    if (restOnCurrentAndSubsequent.size >= 15) break
                                                }
                                                if (restOnCurrentAndSubsequent.size < 15) {
                                                    val maxLookaheadPage = minOf(pages.size, pageIndex + 3)
                                                    for (nextPIdx in (pageIndex + 1) until maxLookaheadPage) {
                                                        val np = pages[nextPIdx]
                                                        if (np.isCoverPage) continue
                                                        for (pText in np.paragraphs) {
                                                            restOnCurrentAndSubsequent.add(pText)
                                                            if (restOnCurrentAndSubsequent.size >= 15) break
                                                        }
                                                        if (restOnCurrentAndSubsequent.size >= 15) break
                                                    }
                                                }
                                                restOnCurrentAndSubsequent
                                            }
                                            val unsupportedBlock = remember(pdfEbookDocument, item.text) {
                                                pdfEbookDocument?.blocks?.firstOrNull { b ->
                                                    b.type == com.example.reader.PdfReflowBlockType.UNSUPPORTED_ELEMENT &&
                                                        (b.text.equals(item.text, ignoreCase = true) || item.text.startsWith("[Unsupported PDF"))
                                                }
                                            }

                                            val embeddedImg = remember(epubBook, item.semanticBlock) {
                                                val imgPath = item.semanticBlock?.imagePath.orEmpty()
                                                if (imgPath.isBlank()) null
                                                else epubBook?.embeddedImages?.get(imgPath) ?: epubBook?.embeddedImages?.get(imgPath.substringAfterLast("/"))
                                            }

                                            val jumpToInternalTargetPaginated: (String, String, String) -> Unit = { _, targetFileKey, targetAnchorId ->
                                                val targetPageIdx = pages.indexOfFirst { p ->
                                                    !p.isCoverPage && (
                                                        (targetAnchorId.isNotBlank() && p.anchorIds.any { it.equals(targetAnchorId, ignoreCase = true) }) ||
                                                            (targetAnchorId.isBlank() && targetFileKey.isNotBlank() && p.fileKey.equals(targetFileKey, ignoreCase = true))
                                                        )
                                                }.takeIf { it >= 0 } ?: pages.indexOfFirst { p ->
                                                    !p.isCoverPage && targetFileKey.isNotBlank() && p.fileKey.equals(targetFileKey, ignoreCase = true)
                                                }
                                                if (targetPageIdx >= 0) {
                                                    val targetPageNum = targetPageIdx + 1
                                                    currentTrackedPage = targetPageNum
                                                    val targetChapter = pages.getOrNull(targetPageIdx)?.chapterTitle ?: "Chapter $targetPageNum"
                                                    notifyPageChanged(targetPageNum, totalPages, targetChapter)
                                                    coroutineScope.launch {
                                                        pagerState.turnToPageWithAnimation(targetPageIdx, pageTurnAnimation)
                                                    }
                                                }
                                            }

                                            val jumpBackToMainTextPaginated: (com.example.reader.EpubFootnoteEntry) -> Unit = { fnEntry ->
                                                val backAnchor = fnEntry.backlinkAnchorId.ifBlank { fnEntry.sourceAnchorId }
                                                val cleanSnippet = fnEntry.sourceContextSnippet
                                                    .trim()
                                                    .removePrefix("…")
                                                    .removeSuffix("…")
                                                    .removePrefix("...")
                                                    .removeSuffix("...")
                                                    .trim()
                                                val probe = if (cleanSnippet.length > 28) cleanSnippet.substring(4, 26).trim() else cleanSnippet.take(24).trim()
                                                val cleanMarker = fnEntry.displayMarker.trim('[', ']', '*', ' ')
                                                val targetPageIdx = pages.indexOfFirst { p ->
                                                    !p.isCoverPage && p.globalIndex != pageIndex && (
                                                        (backAnchor.isNotBlank() && p.anchorIds.any { it.equals(backAnchor, ignoreCase = true) }) ||
                                                            (probe.length >= 8 && p.semanticBlocks.any { sb ->
                                                                sb.type != com.example.reader.EpubSemanticBlockType.FOOTNOTE_DEF &&
                                                                    sb.text.contains(probe, ignoreCase = true)
                                                            }) ||
                                                            (cleanMarker.isNotEmpty() && p.semanticBlocks.any { sb ->
                                                                sb.type != com.example.reader.EpubSemanticBlockType.FOOTNOTE_DEF &&
                                                                    (sb.inlineLinks.any { lnk -> lnk.isFootnoteRef && (lnk.label.trim('[', ']', '*', ' ') == cleanMarker || lnk.footnoteId == fnEntry.noteId) } ||
                                                                        sb.text.contains("[$cleanMarker]"))
                                                            }) ||
                                                            (fnEntry.sourceFileKey.isNotBlank() && p.fileKey.equals(fnEntry.sourceFileKey, ignoreCase = true) &&
                                                                fnEntry.sourceParagraphNumber > 0 && p.semanticBlocks.any { sb -> sb.paragraphNumberInChapter == fnEntry.sourceParagraphNumber })
                                                        )
                                                }.takeIf { it >= 0 } ?: pages.indexOfFirst { p ->
                                                    !p.isCoverPage && p.globalIndex != pageIndex && (
                                                        (fnEntry.sourceFileKey.isNotBlank() && p.fileKey.equals(fnEntry.sourceFileKey, ignoreCase = true)) ||
                                                            (fnEntry.sourceChapterTitle.isNotBlank() && p.chapterTitle.equals(fnEntry.sourceChapterTitle, ignoreCase = true))
                                                        )
                                                }
                                                if (targetPageIdx >= 0) {
                                                    val targetPageNum = targetPageIdx + 1
                                                    currentTrackedPage = targetPageNum
                                                    val targetChapter = pages.getOrNull(targetPageIdx)?.chapterTitle ?: "Chapter $targetPageNum"
                                                    notifyPageChanged(targetPageNum, totalPages, targetChapter)
                                                    coroutineScope.launch {
                                                        pagerState.turnToPageWithAnimation(targetPageIdx, pageTurnAnimation)
                                                    }
                                                } else if (onFootnoteEntrySelected != null) {
                                                    onFootnoteEntrySelected(fnEntry)
                                                } else {
                                                    onFootnoteClick(fnEntry.displayMarker, fnEntry.noteText)
                                                }
                                            }

                                            var selectedHighlightForSheet by remember { mutableStateOf<HighlightEntity?>(null) }

                                            val sb = item.semanticBlock
                                            val blockLayoutDirection = resolveBlockLayoutDirection(sb, bookLayoutDirection)
                                            val isPhase4CustomBlock = sb != null && (
                                                sb.type == com.example.reader.EpubSemanticBlockType.TABLE ||
                                                    sb.type == com.example.reader.EpubSemanticBlockType.PREFORMATTED ||
                                                    sb.type == com.example.reader.EpubSemanticBlockType.DEFINITION_LIST ||
                                                    sb.type == com.example.reader.EpubSemanticBlockType.CALLOUT ||
                                                    (sb.type == com.example.reader.EpubSemanticBlockType.FIGURE && embeddedImg != null)
                                                )
                                            val isSmilActiveOnThisBlock = activeSmilClip != null &&
                                                activeSmilClip.textAnchorId.isNotBlank() &&
                                                sb?.anchorIds?.any { it.equals(activeSmilClip.textAnchorId, ignoreCase = true) } == true &&
                                                (activeSmilClip.textFileKey.isBlank() || item.fileKey.substringAfterLast("/").equals(activeSmilClip.textFileKey.substringAfterLast("/"), ignoreCase = true))

                                            CompositionLocalProvider(LocalLayoutDirection provides blockLayoutDirection) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .then(
                                                            if (isSmilActiveOnThisBlock) {
                                                                Modifier
                                                                    .border(width = 2.dp, color = Color.Black)
                                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                                                    .testTag("epub_active_media_overlay_block_${activeSmilClip?.textAnchorId.orEmpty()}")
                                                            } else {
                                                                Modifier
                                                            }
                                                        )
                                                ) {
                                                    if (isPhase4CustomBlock && sb != null) {
                                                        EpubPhase4BlockRenderer(
                                                            block = sb,
                                                            embeddedImageBitmap = embeddedImg,
                                                            activeTheme = activeTheme,
                                                            fontSizeSp = fontSizeSp,
                                                            lineSpacingMultiplier = lineSpacingMultiplier,
                                                            onFigureZoom = { bmp, caption ->
                                                                zoomedFigureBitmap = bmp
                                                                zoomedFigureCaption = caption
                                                            },
                                                            activeStandaloneAudioPath = mediaOverlayState.activeStandaloneAudioPath,
                                                            isStandalonePlaying = mediaOverlayState.isStandalonePlaying,
                                                            onToggleStandaloneAudio = { audioPath ->
                                                                mediaOverlayPlayer.toggleStandaloneAudio(audioPath)
                                                            }
                                                        )
                                                    } else {
                                                        Column(modifier = Modifier.fillMaxWidth()) {
                                                            SelectableParagraphItem(
                                                                paragraph = item.text,
                                                                pageIndex = item.pageIndex,
                                                                chapterTitle = item.chapterTitle,
                                                                activeTheme = activeTheme,
                                                                activeFontFamily = activeFontFamily,
                                                                fontSizeSp = fontSizeSp,
                                                                lineSpacingMultiplier = lineSpacingMultiplier,
                                                                activeTextAlign = activeTextAlign,
                                                                matchingHighlights = highlights,
                                                                footnotesMap = item.footnotes,
                                                                semanticBlock = item.semanticBlock,
                                                                footnoteEntries = item.footnoteEntries,
                                                                embeddedImageBitmap = embeddedImg,
                                                                onInternalLinkClick = jumpToInternalTargetPaginated,
                                                                onFootnoteEntryClick = { entry ->
                                                                    popupNoteReturnPageIdx = pageIndex
                                                                    activePopupNoteEntry = entry
                                                                },
                                                                onJumpBackFromFootnote = jumpBackToMainTextPaginated,
                                                                followingParagraphs = followingFromNextPages,
                                                                shouldClearSelection = activeSelectionOwnerKey != null && activeSelectionOwnerKey != itemKey,
                                                                isPdfDocument = pdfEbookDocument != null,
                                                                resolveQuotePageSpan = if (pdfEbookDocument != null) {
                                                                    { q, fallbackEbookPage ->
                                                                        val fallbackPdf = pdfReflowPages.getOrNull((fallbackEbookPage - 1).coerceAtLeast(0))?.startPdfPage ?: 1
                                                                        pdfEbookDocument.resolvePageSpanForQuote(q, fallbackPdf)
                                                                    }
                                                                } else null,
                                                                resolvePassageCitation = { q, chTitle ->
                                                                    EpubParser.resolvePassageCitation(
                                                                        epubBook = epubBook,
                                                                        quote = q,
                                                                        fallbackChapterTitle = chTitle
                                                                    )
                                                                },
                                                                onToggleUi = onToggleUi,
                                                                onTurnPageDirection = { dir ->
                                                                    val effectiveDir = if (isRtlProgression) -dir else dir
                                                                    val target = (pagerState.currentPage + effectiveDir).coerceIn(0, totalPages - 1)
                                                                    if (target != pagerState.currentPage) {
                                                                        val targetPageNum = target + 1
                                                                        currentTrackedPage = targetPageNum
                                                                        val targetChapter = pages.getOrNull(target)?.chapterTitle ?: "Chapter $targetPageNum"
                                                                        notifyPageChanged(targetPageNum, totalPages, targetChapter)
                                                                        coroutineScope.launch {
                                                                            pagerState.turnToPageWithAnimation(target, pageTurnAnimation)
                                                                        }
                                                                    } else {
                                                                        onToggleUi()
                                                                    }
                                                                },
                                                                onFootnoteClick = onFootnoteClick,
                                                                onRequestHighlight = onRequestHighlight,
                                                                onQuickSaveHighlight = onQuickSaveHighlight,
                                                                onSpeakSelection = onSpeakSelection,
                                                                onEditHighlight = { hl ->
                                                                    selectedHighlightForSheet = hl
                                                                },
                                                                onSelectionActive = { active ->
                                                                    if (active) {
                                                                        activeSelectionOwnerKey = itemKey
                                                                        isSelectionActiveOnThisPage = true
                                                                        isAnyTextDragging = true
                                                                    } else if (activeSelectionOwnerKey == itemKey) {
                                                                        isSelectionActiveOnThisPage = false
                                                                        isAnyTextDragging = false
                                                                        pageSelectionDragRootY = null
                                                                        activeSelectionToolbar = null
                                                                    }
                                                                },
                                                                onSelectionDragRootY = { rootY ->
                                                                    if (activeSelectionOwnerKey == itemKey || rootY != null) {
                                                                        pageSelectionDragRootY = rootY
                                                                    }
                                                                },
                                                                onMergedParagraphCountChanged = { count ->
                                                                    if (count > 0) {
                                                                        pageMergedOwnerIdx = idx
                                                                        pageMergedExtraCount = count
                                                                    } else if (pageMergedOwnerIdx == idx) {
                                                                        pageMergedOwnerIdx = -1
                                                                        pageMergedExtraCount = 0
                                                                    }
                                                                },
                                                                onSelectionToolbarStateChanged = { toolbarState ->
                                                                    if (toolbarState != null) {
                                                                        activeSelectionOwnerKey = itemKey
                                                                        isSelectionActiveOnThisPage = true
                                                                        activeSelectionToolbar = toolbarState
                                                                    } else if (activeSelectionOwnerKey == itemKey) {
                                                                        activeSelectionOwnerKey = null
                                                                        activeSelectionToolbar = null
                                                                        isSelectionActiveOnThisPage = false
                                                                        isAnyTextDragging = false
                                                                    }
                                                                },
                                                                activeDaisyAnchorId = activeDaisyAnchorId,
                                                                activeDaisyTextSnippet = activeDaisyTextSnippet,
                                                                onDaisyPhraseTapped = if (hasMediaOverlays || onDaisyPhraseTapped != null) {
                                                                    { tappedAnchorId, tappedSnippet ->
                                                                        var handled = false
                                                                        if (hasMediaOverlays) {
                                                                            val candidateAnchors = buildList {
                                                                                if (tappedAnchorId.isNotBlank()) add(tappedAnchorId)
                                                                                sb?.anchorIds?.forEach { if (it.isNotBlank() && !it.startsWith("__epub_dir_")) add(it) }
                                                                            }
                                                                            for (cand in candidateAnchors) {
                                                                                if (mediaOverlayPlayer.playFromTextAnchor(cand, item.fileKey)) {
                                                                                    handled = true
                                                                                    break
                                                                                }
                                                                            }
                                                                        }
                                                                        if (!handled) {
                                                                            onDaisyPhraseTapped?.invoke(tappedAnchorId, tappedSnippet)
                                                                        }
                                                                    }
                                                                } else null,
                                                                paragraphStyleSetting = effectiveParagraphStyle,
                                                                isFirstAfterHeading = if (idx > 0) {
                                                                    activePageItems[idx - 1].semanticBlock?.type == com.example.reader.EpubSemanticBlockType.HEADING ||
                                                                        activePageItems[idx - 1].text.startsWith("## ")
                                                                } else {
                                                                    val prevPage = if (pageIndex > 0) pages.getOrNull(pageIndex - 1) else null
                                                                    prevPage != null && !prevPage.isCoverPage && (
                                                                        prevPage.semanticBlocks.lastOrNull()?.type == com.example.reader.EpubSemanticBlockType.HEADING ||
                                                                            prevPage.paragraphs.lastOrNull()?.startsWith("## ") == true
                                                                        )
                                                                }
                                                            )
                                                            if (isSmilActiveOnThisBlock) {
                                                                HorizontalDivider(
                                                                    thickness = 2.dp,
                                                                    color = Color.Black,
                                                                    modifier = Modifier
                                                                        .fillMaxWidth()
                                                                        .testTag("epub_active_media_overlay_underline")
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            if (selectedHighlightForSheet != null) {
                                                val hl = selectedHighlightForSheet!!
                                                EpubHighlightReferenceActionSheet(
                                                    highlight = hl,
                                                    epubBook = epubBook,
                                                    activeTheme = activeTheme,
                                                    onDismiss = { selectedHighlightForSheet = null },
                                                    onOpenHighlightEditor = {
                                                        selectedHighlightForSheet = null
                                                        onEditHighlight(hl)
                                                    }
                                                )
                                            }

                                            if (unsupportedBlock != null && pdfRendererForSnapshots != null) {
                                                UnsupportedPdfElementSnapshotCard(
                                                    block = unsupportedBlock,
                                                    renderer = pdfRendererForSnapshots,
                                                    activeTheme = activeTheme,
                                                    onOpenOriginalPdfPage = { pdfPage ->
                                                        onSwitchToOriginalPdfPage?.invoke(pdfPage)
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    item {
                                        Spacer(modifier = Modifier.height(if (activeSelectionToolbar != null) 220.dp else if (isUiVisible) 80.dp else 40.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // Pinned Floating Selection UI:
                // 1) While actively dragging finger: compact top banner so bottom of screen stays clear for edge auto-scrolling
                // 2) As soon as finger is released (or always at bottom): unified Paper & Ink Highlighting Menu!
                val toolbarState = activeSelectionToolbar
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
                                text = "Selecting text • Drag to bottom edge to scroll down • Release finger for Highlighting Menu",
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        val activePageIdx = (currentTrackedPage - 1).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                        val activeChapterTitle = toolbarState.chapterTitle.ifBlank { pages.getOrNull(activePageIdx)?.chapterTitle ?: document.title }
                        val resolvedSelectionCitation = remember(epubBook, toolbarState.quote, activeChapterTitle) {
                            EpubParser.resolvePassageCitation(
                                epubBook = epubBook,
                                quote = toolbarState.quote,
                                fallbackChapterTitle = activeChapterTitle
                            )
                        }
                        val enrichedToolbarState = remember(toolbarState, resolvedSelectionCitation, pdfEbookDocument) {
                            if (pdfEbookDocument == null) {
                                toolbarState.copy(
                                    plainLocus = resolvedSelectionCitation.plainLocus(),
                                    epubCfi = resolvedSelectionCitation.epubCfi.ifBlank {
                                        toolbarState.epubCfi.ifBlank { "epubcfi(/6/2!/4/2/1:0)" }
                                    }
                                )
                            } else {
                                toolbarState
                            }
                        }
                        SelectionActionToolbarCard(
                            state = enrichedToolbarState,
                            onAddAsAuthor = onAddAsAuthor,
                            onSetAsAbstract = onSetAsAbstract,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        )
                    }
                }

                // Phase 4 Pop-up Note Flat Bottom Card & Origin Return Bar
                val popupEntry = activePopupNoteEntry
                if (popupEntry != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        EpubPopupNoteBottomCard(
                            entry = popupEntry,
                            activeTheme = activeTheme,
                            hasReturnOrigin = popupNoteReturnPageIdx != null,
                            onDismiss = { activePopupNoteEntry = null },
                            onGoToNote = {
                                val targetAnchor = popupEntry.footnoteAnchorId.ifBlank { popupEntry.noteId }
                                val targetFile = popupEntry.footnoteFileKey
                                if (isVerticalScrollMode) {
                                    val targetItemIdx = continuousItems.indexOfFirst { ci ->
                                        ci.semanticBlock?.type == com.example.reader.EpubSemanticBlockType.FOOTNOTE_DEF &&
                                            ((targetAnchor.isNotBlank() && ci.semanticBlock.anchorIds.any { it.equals(targetAnchor, ignoreCase = true) }) ||
                                                ci.text.contains(popupEntry.noteText.take(28), ignoreCase = true))
                                    }.takeIf { it >= 0 } ?: continuousItems.indexOfFirst { ci ->
                                        targetAnchor.isNotBlank() && ci.semanticBlock?.anchorIds?.any { it.equals(targetAnchor, ignoreCase = true) } == true
                                    }
                                    if (targetItemIdx >= 0) {
                                        val targetPageNum = ((continuousItems.getOrNull(targetItemIdx)?.pageIndex ?: 0) + 1).coerceIn(1, totalPages)
                                        currentTrackedPage = targetPageNum
                                        val targetChapter = continuousItems.getOrNull(targetItemIdx)?.chapterTitle ?: "Chapter $targetPageNum"
                                        notifyPageChanged(targetPageNum, totalPages, targetChapter)
                                        coroutineScope.launch {
                                            verticalListState.animateScrollToItem(targetItemIdx + 1)
                                        }
                                    }
                                } else {
                                    val targetPageIdx = pages.indexOfFirst { p ->
                                        !p.isCoverPage && (
                                            (targetAnchor.isNotBlank() && p.anchorIds.any { it.equals(targetAnchor, ignoreCase = true) }) ||
                                                p.semanticBlocks.any { sb ->
                                                    sb.type == com.example.reader.EpubSemanticBlockType.FOOTNOTE_DEF &&
                                                        sb.text.contains(popupEntry.noteText.take(28), ignoreCase = true)
                                                } ||
                                                (targetAnchor.isBlank() && targetFile.isNotBlank() && p.fileKey.equals(targetFile, ignoreCase = true))
                                            )
                                    }
                                    if (targetPageIdx >= 0) {
                                        val targetPageNum = targetPageIdx + 1
                                        currentTrackedPage = targetPageNum
                                        val targetChapter = pages.getOrNull(targetPageIdx)?.chapterTitle ?: "Chapter $targetPageNum"
                                        notifyPageChanged(targetPageNum, totalPages, targetChapter)
                                        coroutineScope.launch {
                                            pagerState.turnToPageWithAnimation(targetPageIdx, pageTurnAnimation)
                                        }
                                    }
                                }
                            },
                            onBackToOrigin = {
                                val originIdx = popupNoteReturnPageIdx
                                activePopupNoteEntry = null
                                popupNoteReturnPageIdx = null
                                if (originIdx != null && originIdx in 0 until totalPages) {
                                    val targetPageNum = originIdx + 1
                                    currentTrackedPage = targetPageNum
                                    val targetChapter = pages.getOrNull(originIdx)?.chapterTitle ?: "Chapter $targetPageNum"
                                    notifyPageChanged(targetPageNum, totalPages, targetChapter)
                                    if (isVerticalScrollMode && continuousItems.isNotEmpty()) {
                                        val targetItemIdx = continuousItems.indexOfFirst { it.pageIndex >= originIdx }.coerceAtLeast(0)
                                        coroutineScope.launch {
                                            verticalListState.animateScrollToItem(if (originIdx == 0) 0 else targetItemIdx + 1)
                                        }
                                    } else {
                                        coroutineScope.launch {
                                            pagerState.turnToPageWithAnimation(originIdx, pageTurnAnimation)
                                        }
                                    }
                                }
                            }
                        )
                    }
                }

                // Phase 4 Full-screen Figure Zoom Modal
                val zoomBmp = zoomedFigureBitmap
                if (zoomBmp != null) {
                    EpubFigureZoomModal(
                        bitmap = zoomBmp,
                        caption = zoomedFigureCaption,
                        onDismiss = {
                            zoomedFigureBitmap = null
                            zoomedFigureCaption = ""
                        }
                    )
                }
            }

            // Phase 5 Part C: Flat newspaper-style Media Overlays (SMIL) bottom audio bar
            // Shown ONLY when the book has media overlays (epubBook.mediaOverlays is non-empty).
            if (hasMediaOverlays) {
                EpubMediaOverlayBottomBar(
                    state = mediaOverlayState,
                    onPlayPause = { mediaOverlayPlayer.togglePlayPause() },
                    onPreviousClip = { mediaOverlayPlayer.previousClip() },
                    onNextClip = { mediaOverlayPlayer.nextClip() },
                    onSelectSpeed = { speed -> mediaOverlayPlayer.setPlaybackSpeed(speed) }
                )
            }

            // Bottom navigation bar (only visible when isUiVisible is true)
            AnimatedVisibility(
                visible = isUiVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(width = 1.dp, color = activeTheme.dividerColor)
                        .background(activeTheme.barColor)
                ) {
                    val activePageDisplay = currentTrackedPage.coerceIn(1, totalPages)
                    val progressFraction = if (totalPages > 1) {
                        ((activePageDisplay - 1).toFloat() / (totalPages - 1).toFloat()).coerceIn(0f, 1f)
                    } else 1f

                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Top progress bar on the bottom bar: fills right-to-left for RTL books, left-to-right for LTR books
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(activeTheme.dividerColor.copy(alpha = 0.35f))
                                .testTag(if (isRtlProgression) "epub_progress_bar_rtl" else "epub_progress_bar_ltr"),
                            contentAlignment = if (isRtlProgression) Alignment.CenterEnd else Alignment.CenterStart
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(progressFraction)
                                    .background(activeTheme.textColor)
                                    .testTag("epub_progress_bar_fill")
                            )
                        }

                        if (isRtlProgression && totalPages > 1) {
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                Slider(
                                    value = activePageDisplay.toFloat(),
                                    onValueChange = { newVal ->
                                        val targetIdx = (kotlin.math.round(newVal).toInt() - 1).coerceIn(0, totalPages - 1)
                                        val targetPageNum = targetIdx + 1
                                        currentTrackedPage = targetPageNum
                                        val targetChapter = pages.getOrNull(targetIdx)?.chapterTitle ?: "Chapter $targetPageNum"
                                        notifyPageChanged(targetPageNum, totalPages, targetChapter)
                                        coroutineScope.launch {
                                            if (isVerticalScrollMode && continuousItems.isNotEmpty()) {
                                                val itemIdx = continuousItems.indexOfFirst { it.pageIndex >= targetIdx }.coerceAtLeast(0)
                                                verticalListState.scrollToItem(if (targetIdx == 0) 0 else itemIdx + 1)
                                            } else {
                                                pagerState.scrollToPage(targetIdx)
                                            }
                                        }
                                    },
                                    valueRange = 1f..totalPages.toFloat(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = activeTheme.textColor,
                                        activeTrackColor = activeTheme.textColor,
                                        inactiveTrackColor = activeTheme.dividerColor
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(26.dp)
                                        .padding(horizontal = 16.dp)
                                        .testTag("epub_page_scrubber_rtl")
                                )
                            }
                        }

                        // Hide font size, font family, line spacing and margin controls when epubBook.isFixedLayout is true
                        if (epubBook?.isFixedLayout != true) {
                            EpubTypographyControlsBar(
                                fontSizeSp = fontSizeSp,
                                fontFamilySetting = fontFamilySetting,
                                lineSpacingMultiplier = lineSpacingMultiplier,
                                marginDp = marginDp,
                                paragraphStyleSetting = effectiveParagraphStyle,
                                activeTheme = activeTheme
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val leftButtonStep = if (isRtlProgression) 1 else -1
                            val rightButtonStep = if (isRtlProgression) -1 else 1
                            val canStepLeft = if (isRtlProgression) {
                                if (isVerticalScrollMode) verticalListState.firstVisibleItemIndex < continuousItems.size - 1 else pagerState.currentPage < totalPages - 1
                            } else {
                                if (isVerticalScrollMode) verticalListState.firstVisibleItemIndex > 0 else pagerState.currentPage > 0
                            }
                            val canStepRight = if (isRtlProgression) {
                                if (isVerticalScrollMode) verticalListState.firstVisibleItemIndex > 0 else pagerState.currentPage > 0
                            } else {
                                if (isVerticalScrollMode) verticalListState.firstVisibleItemIndex < continuousItems.size - 1 else pagerState.currentPage < totalPages - 1
                            }

                            IconButton(
                                onClick = {
                                    if (isVerticalScrollMode) {
                                        val targetItem = (verticalListState.firstVisibleItemIndex + (leftButtonStep * 5))
                                            .coerceIn(0, (continuousItems.size - 1).coerceAtLeast(0))
                                        val contentIdx = (targetItem - 1).coerceIn(0, (continuousItems.size - 1).coerceAtLeast(0))
                                        val targetPageNum = ((continuousItems.getOrNull(contentIdx)?.pageIndex ?: 0) + 1).coerceIn(1, totalPages)
                                        currentTrackedPage = targetPageNum
                                        val targetChapter = continuousItems.getOrNull(contentIdx)?.chapterTitle ?: "Chapter $targetPageNum"
                                        notifyPageChanged(targetPageNum, totalPages, targetChapter)
                                        coroutineScope.launch { verticalListState.animateScrollToItem(targetItem) }
                                    } else {
                                        val target = (pagerState.currentPage + leftButtonStep).coerceIn(0, totalPages - 1)
                                        if (target != pagerState.currentPage) {
                                            val targetPageNum = target + 1
                                            currentTrackedPage = targetPageNum
                                            val targetChapter = pages.getOrNull(target)?.chapterTitle ?: "Chapter $targetPageNum"
                                            notifyPageChanged(targetPageNum, totalPages, targetChapter)
                                            coroutineScope.launch {
                                                pagerState.turnToPageWithAnimation(target, pageTurnAnimation)
                                            }
                                        }
                                    }
                                },
                                enabled = canStepLeft,
                                modifier = Modifier.testTag(if (isRtlProgression) "next_page_button" else "prev_page_button")
                            ) {
                                Icon(
                                    Icons.Default.ChevronLeft,
                                    contentDescription = if (isRtlProgression) "Next Page" else "Previous Page",
                                    tint = activeTheme.textColor
                                )
                            }

                            val activeReflowPage = pdfReflowPages.getOrNull(activePageDisplay - 1)
                            val bottomReflowSummary = activeReflowPage?.formatStatusBarSummary(activePageDisplay, totalPages)
                            val activeEpubPageObj = pages.getOrNull(activePageDisplay - 1)
                            val activeEpubPrintEq = activeEpubPageObj?.pageEquivalentLabel ?: ""
                            val statusMonospaceChip = remember(activeEpubPageObj, activeEpubPrintEq, showEpubPageEquivalents, totalPages) {
                                buildEpubStatusMonospaceChipText(
                                    pageObj = activeEpubPageObj,
                                    pageEquivalentLabel = if (showEpubPageEquivalents) activeEpubPrintEq else "",
                                    totalPages = totalPages
                                )
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (bottomReflowSummary == null && statusMonospaceChip.isNotBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(2.dp),
                                        color = activeTheme.backgroundColor,
                                        border = BorderStroke(1.dp, activeTheme.dividerColor),
                                        modifier = Modifier.testTag("epub_status_bar_citable_chip")
                                    ) {
                                        Text(
                                            text = statusMonospaceChip,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Medium,
                                            color = activeTheme.textColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                val bottomPageLabel = if (activeEpubPageObj?.isFixedLayoutPage == true) {
                                    val cleanEq = activeEpubPrintEq.trim().removePrefix("pp.").removePrefix("p.").trim()
                                    if (showEpubPageEquivalents && cleanEq.isNotEmpty()) {
                                        "Print p. $cleanEq • page $activePageDisplay of $totalPages"
                                    } else {
                                        "page $activePageDisplay of $totalPages"
                                    }
                                } else {
                                    bottomReflowSummary ?: "Page $activePageDisplay of $totalPages"
                                }
                                Text(
                                    text = bottomPageLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = activeTheme.secondaryTextColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("epub_bottom_bar_page_summary"),
                                    textAlign = TextAlign.Center
                                )
                            }

                            IconButton(
                                onClick = {
                                    if (isVerticalScrollMode) {
                                        val targetItem = (verticalListState.firstVisibleItemIndex + (rightButtonStep * 5))
                                            .coerceIn(0, (continuousItems.size - 1).coerceAtLeast(0))
                                        val contentIdx = (targetItem - 1).coerceIn(0, (continuousItems.size - 1).coerceAtLeast(0))
                                        val targetPageNum = ((continuousItems.getOrNull(contentIdx)?.pageIndex ?: 0) + 1).coerceIn(1, totalPages)
                                        currentTrackedPage = targetPageNum
                                        val targetChapter = continuousItems.getOrNull(contentIdx)?.chapterTitle ?: "Chapter $targetPageNum"
                                        notifyPageChanged(targetPageNum, totalPages, targetChapter)
                                        coroutineScope.launch { verticalListState.animateScrollToItem(targetItem) }
                                    } else {
                                        val target = (pagerState.currentPage + rightButtonStep).coerceIn(0, totalPages - 1)
                                        if (target != pagerState.currentPage) {
                                            val targetPageNum = target + 1
                                            currentTrackedPage = targetPageNum
                                            val targetChapter = pages.getOrNull(target)?.chapterTitle ?: "Chapter $targetPageNum"
                                            notifyPageChanged(targetPageNum, totalPages, targetChapter)
                                            coroutineScope.launch {
                                                pagerState.turnToPageWithAnimation(target, pageTurnAnimation)
                                            }
                                        }
                                    }
                                },
                                enabled = canStepRight,
                                modifier = Modifier.testTag(if (isRtlProgression) "prev_page_button" else "next_page_button")
                            ) {
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = if (isRtlProgression) "Previous Page" else "Next Page",
                                    tint = activeTheme.textColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

private fun verticalMergedExtraCountOrPage(pageMergedExtraCount: Int): Int = pageMergedExtraCount

internal fun buildReadingPositionForPage(
    document: DocumentEntity,
    page: Int,
    totalPages: Int,
    chapterTitle: String,
    pagesList: List<EpubPage>,
    epubBook: EpubBook?,
    pdfEbookDocument: com.example.reader.PdfEbookDocument?,
    charOffsetInFirstPara: Int = 0
): ReadingPosition {
    val safeTotal = totalPages.coerceAtLeast(1)
    val safePage = page.coerceIn(1, safeTotal)
    val isPdf = pdfEbookDocument != null ||
        document.fileType.equals("PDF", ignoreCase = true) ||
        document.filePath.endsWith(".pdf", ignoreCase = true)
    if (isPdf) {
        return ReadingPositionBuilder.forPdf(safePage, safeTotal)
    }

    val isArticle = document.category.equals("ARTICLE", ignoreCase = true) ||
        document.fileType.equals("TXT", ignoreCase = true) ||
        document.fileType.equals("HTML", ignoreCase = true) ||
        document.filePath.endsWith(".txt", ignoreCase = true) ||
        document.filePath.endsWith(".html", ignoreCase = true) ||
        document.filePath.endsWith(".htm", ignoreCase = true)

    val pageObj = pagesList.getOrNull((safePage - 1).coerceIn(0, (pagesList.size - 1).coerceAtLeast(0)))
    val firstVisiblePara = pageObj?.paragraphs
        ?.firstOrNull { it.isNotBlank() && !it.startsWith("## ") }
        ?: pageObj?.paragraphs?.firstOrNull { it.isNotBlank() }
        ?: ""
    val firstVisibleText = firstVisiblePara.replace(Regex("^#+\\s*"), "").trim()
    val paragraph = (pageObj?.startParagraphNumber ?: 1).coerceAtLeast(1)

    if (isArticle) {
        val totalParagraphs = pagesList.filter { !it.isCoverPage }
            .maxOfOrNull { it.endParagraphNumber }
            ?.coerceAtLeast(paragraph) ?: paragraph
        val firstBlock = pageObj?.semanticBlocks?.firstOrNull { it.isCountedParagraph }
            ?: pageObj?.semanticBlocks?.firstOrNull { it.text.isNotBlank() }
        val chIdx = (pageObj?.chapterIndex ?: 0).coerceAtLeast(0)
        val ch = epubBook?.chapters?.getOrNull(chIdx)
        val fullParaText = ch?.countedTextParagraphs?.getOrNull(paragraph - 1)?.text
            ?: ch?.paragraphs?.getOrNull(paragraph - 1)
            ?: firstBlock?.text
            ?: firstVisiblePara
        val splitOffset = if (firstVisibleText.isNotEmpty() && fullParaText.isNotEmpty() && !fullParaText.startsWith(firstVisibleText)) {
            fullParaText.indexOf(firstVisibleText.take(32), ignoreCase = true).coerceAtLeast(0)
        } else {
            0
        }
        val offset = (charOffsetInFirstPara + splitOffset).coerceAtLeast(0)
        return ReadingPositionBuilder.forArticle(
            paragraph = paragraph,
            totalParagraphs = totalParagraphs,
            offset = offset,
            firstText = firstVisibleText
        )
    }

    val loc = (pageObj?.startStableLocation ?: 1).coerceAtLeast(1)
    val printPageLabel = pageObj?.pageEquivalentLabel
    val cfi = if (pageObj == null || pageObj.isCoverPage) {
        ""
    } else {
        val spineIdx = pageObj.chapterIndex.coerceAtLeast(0)
        val ch = epubBook?.chapters?.getOrNull(spineIdx)
        val spineId = ch?.id ?: pageObj.fileKey.substringBeforeLast(".")
        val firstSemBlock = pageObj.semanticBlocks.firstOrNull {
            it.type != com.example.reader.EpubSemanticBlockType.HEADING && it.text.isNotBlank()
        } ?: pageObj.semanticBlocks.firstOrNull { it.text.isNotBlank() }

        val blockId = firstSemBlock?.anchorIds
            ?.firstOrNull { it.isNotBlank() && !it.startsWith("__epub_") }
            .orEmpty()

        val blockIndex = if (ch != null && ch.semanticBlocks.isNotEmpty() && firstSemBlock != null) {
            val probe = firstSemBlock.text.replace(Regex("^#+\\s*"), "").trim().take(32)
            ch.semanticBlocks.indexOfFirst { cand ->
                (blockId.isNotEmpty() && cand.anchorIds.any { it.equals(blockId, ignoreCase = true) }) ||
                    (firstSemBlock.paragraphNumberInChapter != null && cand.paragraphNumberInChapter == firstSemBlock.paragraphNumberInChapter)
            }.takeIf { it >= 0 } ?: ch.semanticBlocks.indexOfFirst { cand ->
                probe.length >= 6 && cand.text.contains(probe, ignoreCase = true)
            }.takeIf { it >= 0 } ?: (paragraph - 1).coerceAtLeast(0)
        } else {
            (paragraph - 1).coerceAtLeast(0)
        }

        val fullBlockText = ch?.semanticBlocks?.getOrNull(blockIndex)?.text ?: firstSemBlock?.text.orEmpty()
        val charOffset = if (firstVisibleText.isNotEmpty() && fullBlockText.isNotEmpty() && !fullBlockText.startsWith(firstVisibleText)) {
            fullBlockText.indexOf(firstVisibleText.take(32), ignoreCase = true).coerceAtLeast(0)
        } else {
            0
        }

        EpubCfiEngine.buildBlockPointCfi(
            spineIndex = spineIdx,
            spineId = spineId,
            blockIndex = blockIndex,
            blockId = blockId,
            charOffset = charOffset
        )
    }

    return ReadingPositionBuilder.forBook(
        cfi = cfi,
        paragraph = paragraph,
        loc = loc,
        printPageLabel = printPageLabel,
        fallbackPage = safePage,
        totalPages = safeTotal,
        chapter = chapterTitle.ifBlank { pageObj?.chapterTitle.orEmpty() },
        firstText = firstVisibleText
    )
}

internal fun resolveResumeTargetWithStep(
    resume: ResumeTarget,
    pagesList: List<EpubPage>,
    epubBook: EpubBook?,
    document: DocumentEntity
): Pair<Int, String> {
    if (pagesList.isEmpty()) return 0 to "none"
    val pos = resume.position

    // (a) epubcfi via EpubCfiEngine.resolveCfiInBook
    if (pos.cfi.isNotBlank() && epubBook != null && epubBook.chapters.isNotEmpty()) {
        val resolved = EpubCfiEngine.resolveCfiInBook(epubBook, pos.cfi, pos.snippet)
        if (resolved != null) {
            val chapterPages = pagesList.filter { !it.isCoverPage && it.chapterIndex == resolved.chapterIndex }
            if (chapterPages.isNotEmpty()) {
                val parsed = EpubCfiEngine.parseEpubCfi(pos.cfi)
                val anchorId = parsed?.startBlockIdAssertion.orEmpty()
                val byAnchor = if (anchorId.isNotEmpty()) {
                    chapterPages.firstOrNull { p ->
                        p.anchorIds.any { it.equals(anchorId, ignoreCase = true) } ||
                            p.semanticBlocks.any { sb -> sb.anchorIds.any { it.equals(anchorId, ignoreCase = true) } }
                    }
                } else null

                val targetParaNum = if (pos.paragraph > 0) pos.paragraph else resolved.startParagraphNumber
                val paraPages = if (targetParaNum > 0) {
                    chapterPages.filter { p ->
                        targetParaNum in p.startParagraphNumber..p.endParagraphNumber
                    }
                } else emptyList()

                val sliceProbe = pos.snippet.ifBlank {
                    resolved.extractedText.lineSequence().firstOrNull { it.trim().isNotEmpty() }?.trim().orEmpty()
                }
                val probe = if (sliceProbe.length > 36) sliceProbe.take(36).trim() else sliceProbe
                val searchPool = if (paraPages.isNotEmpty()) paraPages else chapterPages
                val byText = if (probe.isNotEmpty()) {
                    searchPool.firstOrNull { p ->
                        p.paragraphs.any { para -> para.contains(probe, ignoreCase = true) }
                    }
                } else null

                val byPara = paraPages.firstOrNull()

                val matchedPage = byAnchor ?: byText ?: byPara ?: chapterPages.first()
                return matchedPage.globalIndex.coerceIn(0, pagesList.lastIndex) to "a_epubcfi"
            }
        }
    }

    // (b) paragraph plus offset (articles, and books without a CFI)
    if (pos.paragraph > 0) {
        val candidatePages = pagesList.filter { !it.isCoverPage && pos.paragraph in it.startParagraphNumber..it.endParagraphNumber }
        if (candidatePages.isNotEmpty()) {
            val matchedPage = if (pos.offset > 0 && candidatePages.size > 1) {
                val chIdx = candidatePages.first().chapterIndex.coerceAtLeast(0)
                val ch = epubBook?.chapters?.getOrNull(chIdx)
                val fullPara = ch?.countedTextParagraphs?.getOrNull(pos.paragraph - 1)?.text
                    ?: ch?.paragraphs?.getOrNull(pos.paragraph - 1)
                    ?: ""
                val subProbe = if (pos.offset < fullPara.length) {
                    fullPara.substring(pos.offset).trim().take(32)
                } else ""
                if (subProbe.isNotEmpty()) {
                    candidatePages.firstOrNull { p ->
                        p.paragraphs.any { para -> para.contains(subProbe, ignoreCase = true) }
                    } ?: candidatePages.first()
                } else {
                    candidatePages.first()
                }
            } else {
                candidatePages.first()
            }
            return matchedPage.globalIndex.coerceIn(0, pagesList.lastIndex) to "b_paragraph_offset"
        }
    }

    // (c) stable loc: the page whose startStableLocation..endStableLocation contains it
    if (pos.loc > 0) {
        val locPage = pagesList.firstOrNull { p ->
            !p.isCoverPage && pos.loc in p.startStableLocation..p.endStableLocation
        }
        if (locPage != null) {
            return locPage.globalIndex.coerceIn(0, pagesList.lastIndex) to "c_stable_loc"
        }
    }

    // (d) page number
    val fallbackIdx = (resume.page - 1).coerceIn(0, pagesList.lastIndex)
    return fallbackIdx to "d_page_number"
}

internal fun resolveResumeTargetPageIndex(
    resume: ResumeTarget,
    pagesList: List<EpubPage>,
    epubBook: EpubBook?,
    document: DocumentEntity
): Int = resolveResumeTargetWithStep(resume, pagesList, epubBook, document).first

internal fun resolveBlockLayoutDirection(
    semanticBlock: com.example.reader.EpubSemanticBlock?,
    bookDefaultDirection: LayoutDirection
): LayoutDirection {
    if (semanticBlock != null) {
        if (semanticBlock.anchorIds.any { it.equals("__epub_dir_rtl__", ignoreCase = true) }) {
            return LayoutDirection.Rtl
        }
        if (semanticBlock.anchorIds.any { it.equals("__epub_dir_ltr__", ignoreCase = true) }) {
            return LayoutDirection.Ltr
        }
    }
    return bookDefaultDirection
}

internal fun buildEpubStatusMonospaceChipText(
    pageObj: EpubPage?,
    pageEquivalentLabel: String,
    totalPages: Int = 1
): String {
    if (pageObj == null || pageObj.isCoverPage) return ""

    val cleanPageEq = pageEquivalentLabel.trim()
        .removePrefix("pp.")
        .removePrefix("p.")
        .trim()

    if (pageObj.isFixedLayoutPage) {
        return if (cleanPageEq.isNotEmpty()) {
            val isMulti = cleanPageEq.contains("–") || cleanPageEq.contains("-")
            if (isMulti) "pp. $cleanPageEq" else "p. $cleanPageEq"
        } else {
            val pageNum = (pageObj.globalIndex + 1).coerceAtLeast(1)
            val total = totalPages.coerceAtLeast(pageNum)
            "page $pageNum of $total"
        }
    }

    val parts = mutableListOf<String>()
    if (cleanPageEq.isNotEmpty()) {
        val isMulti = cleanPageEq.contains("–") || cleanPageEq.contains("-")
        parts.add(if (isMulti) "pp. $cleanPageEq" else "p. $cleanPageEq")
    }

    val sPara = pageObj.startParagraphNumber.coerceAtLeast(1)
    val ePara = pageObj.endParagraphNumber.coerceAtLeast(sPara)
    parts.add(if (ePara > sPara) "¶ $sPara–$ePara" else "¶ $sPara")

    val sLoc = pageObj.startStableLocation.coerceAtLeast(1)
    val eLoc = pageObj.endStableLocation.coerceAtLeast(sLoc)
    parts.add(if (eLoc > sLoc) "loc. $sLoc–$eLoc" else "loc. $sLoc")

    return parts.joinToString(" • ")
}

@Composable
internal fun EpubTypographyControlsBar(
    fontSizeSp: Float,
    fontFamilySetting: String,
    lineSpacingMultiplier: Float,
    marginDp: Int,
    paragraphStyleSetting: String = "BOTH",
    activeTheme: ReaderTheme
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("epub_typography_controls_bar"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Font size: ${fontSizeSp.toInt()}sp",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = activeTheme.secondaryTextColor,
            modifier = Modifier.testTag("epub_font_size_control")
        )
        Text(
            text = "Font family: $fontFamilySetting",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = activeTheme.secondaryTextColor,
            modifier = Modifier.testTag("epub_font_family_control")
        )
        Text(
            text = "Line spacing: ${lineSpacingMultiplier}x",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = activeTheme.secondaryTextColor,
            modifier = Modifier.testTag("epub_line_spacing_control")
        )
        Text(
            text = "Margin: ${marginDp}dp",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = activeTheme.secondaryTextColor,
            modifier = Modifier.testTag("epub_margin_control")
        )
        Text(
            text = "Paragraph: $paragraphStyleSetting",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = activeTheme.secondaryTextColor,
            modifier = Modifier.testTag("epub_paragraph_style_control")
        )
    }
}

@Composable
internal fun EpubFixedLayoutPageView(
    pageObj: EpubPage,
    pageIndex: Int,
    totalPages: Int,
    epubBook: EpubBook?,
    documentTitle: String,
    activeTheme: ReaderTheme,
    activeFontFamily: FontFamily,
    bookLayoutDirection: LayoutDirection,
    isUiVisible: Boolean,
    showEpubPageEquivalents: Boolean,
    onToggleUi: () -> Unit,
    onTurnPage: (Int) -> Unit,
    onFigureZoom: (Bitmap, String) -> Unit
) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current

    var scale by remember(pageObj.globalIndex) { mutableFloatStateOf(1f) }
    var offsetX by remember(pageObj.globalIndex) { mutableFloatStateOf(0f) }
    var offsetY by remember(pageObj.globalIndex) { mutableFloatStateOf(0f) }

    val vpW = pageObj.viewportWidth.takeIf { it > 0 } ?: 1024
    val vpH = pageObj.viewportHeight.takeIf { it > 0 } ?: 1448
    val aspectRatio = (vpW.toFloat() / vpH.toFloat()).coerceIn(0.35f, 2.8f)

    val cleanPrintEq = pageObj.pageEquivalentLabel.trim()
        .removePrefix("pp.")
        .removePrefix("p.")
        .trim()
    val fxlLocusLabel = remember(pageObj, cleanPrintEq, pageIndex, totalPages, showEpubPageEquivalents) {
        if (showEpubPageEquivalents && cleanPrintEq.isNotEmpty()) {
            val isMulti = cleanPrintEq.contains("–") || cleanPrintEq.contains("-")
            if (isMulti) "pp. $cleanPrintEq" else "p. $cleanPrintEq"
        } else {
            "page ${pageIndex + 1} of $totalPages"
        }
    }

    val fxlCitation = remember(epubBook, pageObj, pageIndex, totalPages, documentTitle) {
        val sampleQuote = pageObj.paragraphs.firstOrNull { it.isNotBlank() }.orEmpty()
        EpubParser.resolvePassageCitation(
            epubBook = epubBook,
            quote = sampleQuote,
            fallbackChapterTitle = pageObj.chapterTitle.ifBlank { documentTitle }
        )
    }

    val canUseExistingRenderer = remember(pageObj) {
        pageObj.semanticBlocks.isNotEmpty() || pageObj.paragraphs.any { it.isNotBlank() }
    }

    // White letterboxing container around the scaled pre-paginated spine page
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .pointerInput(pageObj.globalIndex, scale) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.05f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2f
                        }
                    },
                    onTap = { tapOffset ->
                        if (scale <= 1.05f) {
                            when {
                                tapOffset.x < size.width * 0.2f -> onTurnPage(-1)
                                tapOffset.x > size.width * 0.8f -> onTurnPage(1)
                                else -> onToggleUi()
                            }
                        } else {
                            onToggleUi()
                        }
                    }
                )
            }
            .pointerInput(pageObj.globalIndex) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 4.5f)
                    scale = newScale
                    if (newScale > 1.01f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
            .testTag("epub_fxl_letterbox_container_${pageIndex + 1}"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = if (isUiVisible) 10.dp else 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top bar for FXL page: shows chapter title, print page or "page N of M", and Copy reference button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.Black)
                    .background(Color.White)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("epub_fxl_page_header_${pageIndex + 1}"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${pageObj.chapterTitle.ifBlank { documentTitle }} • $fxlLocusLabel",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("epub_fxl_locus_text_${pageIndex + 1}")
                )
                OutlinedButton(
                    onClick = {
                        val locus = fxlCitation.plainLocus()
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(locus))
                        android.widget.Toast.makeText(context, "Copied: $locus", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(0.dp),
                    border = BorderStroke(1.dp, Color.Black),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .height(26.dp)
                        .testTag("epub_fxl_copy_reference_button_${pageIndex + 1}")
                ) {
                    Text(
                        text = "Copy reference",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
            }

            // Scaled Fixed-Layout Spine Viewport Box (maintains viewport aspect ratio with white letterboxing)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .aspectRatio(aspectRatio, matchHeightConstraintsFirst = true)
                        .border(1.5.dp, Color.Black)
                        .background(Color.White)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                        .testTag("epub_fxl_viewport_frame_${pageIndex + 1}")
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides bookLayoutDirection) {
                        if (canUseExistingRenderer) {
                            val scrollState = androidx.compose.foundation.rememberScrollState()
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scrollState)
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (pageObj.semanticBlocks.isNotEmpty()) {
                                    pageObj.semanticBlocks.forEach { sb ->
                                        val blockDir = resolveBlockLayoutDirection(sb, bookLayoutDirection)
                                        val embeddedImg = remember(epubBook, sb.imagePath) {
                                            val imgPath = sb.imagePath
                                            if (imgPath.isBlank()) null
                                            else epubBook?.embeddedImages?.get(imgPath)
                                                ?: epubBook?.embeddedImages?.get(imgPath.substringAfterLast("/"))
                                        }
                                        CompositionLocalProvider(LocalLayoutDirection provides blockDir) {
                                            val isCustomBlock = sb.type == com.example.reader.EpubSemanticBlockType.TABLE ||
                                                sb.type == com.example.reader.EpubSemanticBlockType.PREFORMATTED ||
                                                sb.type == com.example.reader.EpubSemanticBlockType.DEFINITION_LIST ||
                                                sb.type == com.example.reader.EpubSemanticBlockType.CALLOUT ||
                                                (sb.type == com.example.reader.EpubSemanticBlockType.FIGURE && embeddedImg != null)
                                            if (isCustomBlock) {
                                                EpubPhase4BlockRenderer(
                                                    block = sb,
                                                    embeddedImageBitmap = embeddedImg,
                                                    activeTheme = activeTheme,
                                                    fontSizeSp = 15f,
                                                    lineSpacingMultiplier = 1.35f,
                                                    onFigureZoom = onFigureZoom
                                                )
                                            } else {
                                                Text(
                                                    text = sb.text,
                                                    fontFamily = activeFontFamily,
                                                    fontWeight = if (sb.type == com.example.reader.EpubSemanticBlockType.HEADING) FontWeight.Bold else FontWeight.Normal,
                                                    fontSize = if (sb.type == com.example.reader.EpubSemanticBlockType.HEADING) 17.sp else 15.sp,
                                                    lineHeight = 21.sp,
                                                    color = Color.Black,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    pageObj.paragraphs.forEach { para ->
                                        Text(
                                            text = para,
                                            fontFamily = activeFontFamily,
                                            fontSize = 15.sp,
                                            lineHeight = 21.sp,
                                            color = Color.Black,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        } else {
                            // Restricted WebView fallback for pre-paginated XHTML when existing renderer has no blocks
                            val rawXhtml = pageObj.htmlContent
                            AndroidView(
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        settings.javaScriptEnabled = false
                                        settings.allowFileAccess = false
                                        settings.allowContentAccess = false
                                        settings.blockNetworkLoads = true
                                        settings.blockNetworkImage = true
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        webViewClient = WebViewClient()
                                    }
                                },
                                update = { webView ->
                                    webView.loadDataWithBaseURL(
                                        "file:///android_asset/",
                                        rawXhtml,
                                        "application/xhtml+xml",
                                        "UTF-8",
                                        null
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("epub_fxl_restricted_webview_${pageIndex + 1}")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpubHighlightReferenceActionSheet(
    highlight: HighlightEntity,
    epubBook: EpubBook?,
    activeTheme: ReaderTheme,
    onDismiss: () -> Unit,
    onOpenHighlightEditor: () -> Unit
) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val citation = remember(highlight, epubBook) {
        if (epubBook != null && highlight.highlightedText.isNotBlank()) {
            val resolved = EpubParser.resolvePassageCitation(
                epubBook = epubBook,
                quote = highlight.highlightedText,
                fallbackChapterTitle = highlight.chapterTitle
            )
            if (highlight.epubCfi.isNotBlank()) {
                resolved.copy(epubCfi = com.example.reader.EpubCfiEngine.normalizeCfi(highlight.epubCfi, includeHashPrefix = false))
            } else {
                resolved
            }
        } else {
            com.example.reader.EpubPassageCitation.fromStoredHighlightFields(
                chapterTitle = highlight.chapterTitle,
                paragraphCitation = highlight.paragraphCitation,
                pageEquivalent = highlight.pageEquivalent,
                fallbackPageNumber = highlight.pageNumber,
                epubCfi = highlight.epubCfi
            )
        }
    }
    val plainLocus = remember(citation) { citation.plainLocus() }
    val epubCfiText = remember(citation, highlight.epubCfi) {
        highlight.epubCfi.trim().removePrefix("#").ifBlank { citation.epubCfi }.ifBlank { "epubcfi(/6/2!/4/2/1:0)" }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(2.dp),
            color = activeTheme.backgroundColor,
            border = BorderStroke(1.dp, activeTheme.textColor),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("epub_highlight_reference_sheet")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "HIGHLIGHT REFERENCE",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = activeTheme.textColor
                )
                DictionaryButton(highlight.highlightedText)
                Surface(
                    shape = RoundedCornerShape(2.dp),
                    color = activeTheme.barColor,
                    border = BorderStroke(1.dp, activeTheme.dividerColor),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = plainLocus,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = activeTheme.textColor,
                            modifier = Modifier.testTag("highlight_sheet_plain_locus_text")
                        )
                        Text(
                            text = epubCfiText,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = activeTheme.secondaryTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("highlight_sheet_epub_cfi_text")
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(plainLocus))
                            android.widget.Toast.makeText(context, "Copied: $plainLocus", android.widget.Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        shape = RoundedCornerShape(2.dp),
                        border = BorderStroke(1.dp, activeTheme.textColor),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("highlight_sheet_copy_reference_button")
                    ) {
                        Text(
                            text = "Copy reference",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = activeTheme.textColor
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(epubCfiText))
                            android.widget.Toast.makeText(context, "Copied: $epubCfiText", android.widget.Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        shape = RoundedCornerShape(2.dp),
                        border = BorderStroke(1.dp, activeTheme.textColor),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("highlight_sheet_copy_cfi_button")
                    ) {
                        Text(
                            text = "Copy CFI",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = activeTheme.textColor
                        )
                    }
                }
                OutlinedButton(
                    onClick = onOpenHighlightEditor,
                    shape = RoundedCornerShape(2.dp),
                    border = BorderStroke(1.dp, activeTheme.dividerColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("highlight_sheet_edit_note_button")
                ) {
                    Text(
                        text = "Edit Note",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = activeTheme.textColor
                    )
                }
            }
        }
    }
}

/**
 * Renders an inline visual snapshot card for unsupported PDF elements (Core Requirement 3:
 * Scanned/image-only pages, complex tables, mathematical equations, charts/diagrams, complex
 * multi-column layouts, or interactive form elements) in the PDF-to-Ebook reflow view so no
 * meaningful visual information is ever lost.
 */
@Composable
internal fun UnsupportedPdfElementSnapshotCard(
    block: com.example.reader.PdfReflowBlock,
    renderer: PdfDocumentRenderer,
    activeTheme: ReaderTheme,
    onOpenOriginalPdfPage: (Int) -> Unit
) {
    var snapshotBitmap by remember(block.id, block.startPdfPage, block.cropRectNormalized) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(block.id, block.startPdfPage, block.cropRectNormalized, renderer) {
        withContext(Dispatchers.IO) {
            try {
                val rect = block.cropRectNormalized
                val pageIdx = (block.startPdfPage - 1).coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                val bmp = renderer.renderPage(
                    pageIndex = pageIdx,
                    targetWidth = 900,
                    cropLeft = rect?.left ?: 0f,
                    cropTop = rect?.top ?: 0f,
                    cropRight = rect?.right ?: 1f,
                    cropBottom = rect?.bottom ?: 1f
                )
                snapshotBitmap = bmp
            } catch (_: Exception) {
            }
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

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("unsupported_pdf_element_card_${block.startPdfPage}"),
        shape = RoundedCornerShape(10.dp),
        color = activeTheme.barColor,
        border = BorderStroke(1.dp, Color(0xFF7C3AED).copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = block.unsupportedReason?.displayTitle ?: "Preserved PDF Visual Region",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF7C3AED)
                    )
                    val printedSuffix = if (block.startPrintedPage.isNotBlank()) {
                        ", printed p. ${block.formattedPrintedPageRange()}"
                    } else ""
                    Text(
                        text = "PDF p. ${block.formattedPdfPageRange()}$printedSuffix • Visual snapshot preserved",
                        style = MaterialTheme.typography.labelSmall,
                        color = activeTheme.secondaryTextColor
                    )
                }
                OutlinedButton(
                    onClick = { onOpenOriginalPdfPage(block.startPdfPage) },
                    modifier = Modifier
                        .height(30.dp)
                        .testTag("open_original_pdf_from_placeholder_${block.startPdfPage}"),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF7C3AED))
                ) {
                    Text(
                        text = "View in PDF",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF7C3AED)
                    )
                }
            }

            if (snapshotBitmap != null) {
                Image(
                    bitmap = snapshotBitmap!!.asImageBitmap(),
                    contentDescription = "Snapshot of ${block.unsupportedReason?.displayTitle ?: "PDF element"} on PDF page ${block.startPdfPage}",
                    contentScale = ContentScale.FillWidth,
                    colorFilter = pdfColorFilter,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, activeTheme.dividerColor, RoundedCornerShape(6.dp))
                )
            }
        }
    }
}

@Composable
internal fun EpubPhase4BlockRenderer(
    block: com.example.reader.EpubSemanticBlock,
    embeddedImageBitmap: Bitmap?,
    activeTheme: ReaderTheme,
    fontSizeSp: Float,
    lineSpacingMultiplier: Float,
    onFigureZoom: (Bitmap, String) -> Unit,
    activeStandaloneAudioPath: String = "",
    isStandalonePlaying: Boolean = false,
    onToggleStandaloneAudio: ((String) -> Unit)? = null
) {
    val ruleColor = activeTheme.textColor
    val bgColor = activeTheme.backgroundColor

    when (block.type) {
        com.example.reader.EpubSemanticBlockType.TABLE -> {
            val hScrollState = androidx.compose.foundation.rememberScrollState()
            val colCount = remember(block.tableHeaders, block.tableRows) {
                maxOf(
                    block.tableHeaders.size,
                    block.tableRows.maxOfOrNull { it.size } ?: 0
                ).coerceAtLeast(1)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .horizontalScroll(hScrollState)
                    .border(1.5.dp, ruleColor)
                    .background(bgColor)
                    .testTag("epub_table_block")
            ) {
                if (block.tableHeaders.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .background(bgColor)
                            .height(IntrinsicSize.Min)
                    ) {
                        for (cIdx in 0 until colCount) {
                            val headerText = block.tableHeaders.getOrNull(cIdx).orEmpty()
                            Box(
                                modifier = Modifier
                                    .widthIn(min = 110.dp, max = 260.dp)
                                    .fillMaxHeight()
                                    .border(0.75.dp, ruleColor)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = headerText,
                                    fontFamily = FontFamily.Serif,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = (fontSizeSp * 0.88f).sp,
                                    color = ruleColor
                                )
                            }
                        }
                    }
                    HorizontalDivider(thickness = 1.5.dp, color = ruleColor)
                }
                block.tableRows.forEachIndexed { rIdx, rowCells ->
                    Row(
                        modifier = Modifier
                            .background(bgColor)
                            .height(IntrinsicSize.Min)
                    ) {
                        for (cIdx in 0 until colCount) {
                            val cellText = rowCells.getOrNull(cIdx).orEmpty()
                            Box(
                                modifier = Modifier
                                    .widthIn(min = 110.dp, max = 260.dp)
                                    .fillMaxHeight()
                                    .border(0.75.dp, ruleColor)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = cellText,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = (fontSizeSp * 0.82f).sp,
                                    color = ruleColor
                                )
                            }
                        }
                    }
                    if (rIdx < block.tableRows.lastIndex) {
                        HorizontalDivider(thickness = 1.dp, color = ruleColor)
                    }
                }
            }
        }

        com.example.reader.EpubSemanticBlockType.PREFORMATTED -> {
            val hScrollState = androidx.compose.foundation.rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .border(1.dp, ruleColor)
                    .background(bgColor)
                    .horizontalScroll(hScrollState)
                    .padding(12.dp)
                    .testTag("epub_code_block")
            ) {
                Text(
                    text = block.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = (fontSizeSp * 0.85f).sp,
                    lineHeight = (fontSizeSp * 0.85f * 1.35f).sp,
                    color = ruleColor,
                    softWrap = false
                )
            }
        }

        com.example.reader.EpubSemanticBlockType.DEFINITION_LIST -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag("epub_definition_list_block"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val items = if (block.definitionItems.isNotEmpty()) {
                    block.definitionItems
                } else {
                    listOf("" to listOf(block.text))
                }
                items.forEach { (term, definitions) ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        if (term.isNotBlank()) {
                            Text(
                                text = term,
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = fontSizeSp.sp,
                                color = ruleColor,
                                modifier = Modifier.testTag("epub_dl_term")
                            )
                        }
                        definitions.forEach { defText ->
                            if (defText.isNotBlank()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp)
                                        .height(IntrinsicSize.Min)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(1.5.dp)
                                            .fillMaxHeight()
                                            .background(ruleColor)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = defText,
                                        fontFamily = FontFamily.Serif,
                                        fontSize = fontSizeSp.sp,
                                        lineHeight = (fontSizeSp * lineSpacingMultiplier).sp,
                                        color = ruleColor,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("epub_dl_definition")
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        com.example.reader.EpubSemanticBlockType.CALLOUT -> {
            if (block.calloutLabel.equals("AUDIO", ignoreCase = true)) {
                val audioPath = block.imagePath.trim().ifBlank {
                    block.anchorIds.firstOrNull { it.startsWith("__epub_audio_src:") }?.removePrefix("__epub_audio_src:").orEmpty()
                }
                val labelText = block.text.removePrefix("[Audio:").removeSuffix("]").trim().ifBlank {
                    audioPath.substringAfterLast("/").ifBlank { "Audio clip" }
                }
                val isThisPlaying = isStandalonePlaying &&
                    audioPath.isNotEmpty() &&
                    activeStandaloneAudioPath.equals(audioPath, ignoreCase = true)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .border(1.dp, Color.Black)
                        .background(Color.White)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("epub_standalone_audio_block"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AUDIO • $labelText",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("epub_standalone_audio_label")
                    )
                    OutlinedButton(
                        onClick = { onToggleStandaloneAudio?.invoke(audioPath) },
                        shape = RoundedCornerShape(0.dp),
                        border = BorderStroke(1.dp, Color.Black),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .height(28.dp)
                            .testTag("epub_standalone_audio_play_button")
                    ) {
                        Text(
                            text = if (isThisPlaying) "Pause" else "Play",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                }
            } else {
                val label = block.calloutLabel.ifBlank { "Note" }
                val bodyText = remember(block.text, label) {
                    val prefix = "[$label]"
                    if (block.text.startsWith(prefix)) {
                        block.text.removePrefix(prefix).trim()
                    } else {
                        block.text
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .border(1.5.dp, ruleColor)
                        .background(bgColor)
                        .padding(12.dp)
                        .testTag("epub_callout_block"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = label,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = (fontSizeSp * 0.92f).sp,
                        color = ruleColor,
                        modifier = Modifier.testTag("epub_callout_label")
                    )
                    HorizontalDivider(thickness = 1.dp, color = ruleColor)
                    Text(
                        text = bodyText,
                        fontFamily = FontFamily.Serif,
                        fontSize = fontSizeSp.sp,
                        lineHeight = (fontSizeSp * lineSpacingMultiplier).sp,
                        color = ruleColor,
                        modifier = Modifier.testTag("epub_callout_body")
                    )
                }
            }
        }

        com.example.reader.EpubSemanticBlockType.FIGURE -> {
            val caption = block.captionText.ifBlank {
                block.text.removePrefix("[Image:").removeSuffix("]").trim()
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .testTag("epub_figure_block"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (embeddedImageBitmap != null) {
                    Image(
                        bitmap = embeddedImageBitmap.asImageBitmap(),
                        contentDescription = if (caption.isNotBlank()) caption else "Book Figure",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, ruleColor)
                            .clickable { onFigureZoom(embeddedImageBitmap, caption) }
                            .testTag("epub_figure_image")
                    )
                }
                if (caption.isNotBlank()) {
                    Text(
                        text = caption,
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        fontSize = (fontSizeSp * 0.84f).sp,
                        color = ruleColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("epub_figure_caption")
                    )
                }
            }
        }

        else -> {}
    }
}

@Composable
internal fun EpubMediaOverlayBottomBar(
    state: com.example.reader.EpubMediaOverlayPlaybackState,
    onPlayPause: () -> Unit,
    onPreviousClip: () -> Unit,
    onNextClip: () -> Unit,
    onSelectSpeed: (Float) -> Unit
) {
    val totalClips = state.clips.size
    val displayClipNum = if (state.activeClipIndex in 0 until totalClips) state.activeClipIndex + 1 else 1
    val durationSuffix = if (state.displayTotalDuration.isNotBlank()) " • ${state.displayTotalDuration}" else ""

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.Black)
            .background(Color.White)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("epub_media_overlay_bottom_bar"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MEDIA OVERLAY • Clip $displayClipNum/$totalClips$durationSuffix",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .testTag("epub_media_overlay_status_text")
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.example.reader.EpubMediaOverlayPlayer.SUPPORTED_SPEEDS.forEach { spd ->
                    val isSelected = kotlin.math.abs(state.playbackSpeed - spd) < 0.05f
                    val spdLabel = when (spd) {
                        0.75f -> "0.75x"
                        1.0f -> "1x"
                        1.25f -> "1.25x"
                        1.5f -> "1.5x"
                        else -> "${spd}x"
                    }
                    Box(
                        modifier = Modifier
                            .border(1.dp, Color.Black)
                            .background(if (isSelected) Color.Black else Color.White)
                            .clickable { onSelectSpeed(spd) }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .testTag("epub_media_overlay_speed_$spdLabel"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = spdLabel,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) Color.White else Color.Black
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onPreviousClip,
                enabled = state.activeClipIndex > 0,
                shape = RoundedCornerShape(0.dp),
                border = BorderStroke(1.dp, Color.Black),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(30.dp)
                    .testTag("epub_media_overlay_prev_button")
            ) {
                Text(
                    text = "Prev",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
            OutlinedButton(
                onClick = onPlayPause,
                shape = RoundedCornerShape(0.dp),
                border = BorderStroke(1.dp, Color.Black),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(30.dp)
                    .testTag("epub_media_overlay_play_pause_button")
            ) {
                Text(
                    text = if (state.isPlaying) "Pause" else "Play",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
            OutlinedButton(
                onClick = onNextClip,
                enabled = state.activeClipIndex < totalClips - 1,
                shape = RoundedCornerShape(0.dp),
                border = BorderStroke(1.dp, Color.Black),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(30.dp)
                    .testTag("epub_media_overlay_next_button")
            ) {
                Text(
                    text = "Next",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
        }
    }
}

@Composable
internal fun EpubPopupNoteBottomCard(
    entry: com.example.reader.EpubFootnoteEntry,
    activeTheme: ReaderTheme,
    hasReturnOrigin: Boolean,
    onDismiss: () -> Unit,
    onGoToNote: () -> Unit,
    onBackToOrigin: () -> Unit
) {
    val ruleColor = activeTheme.textColor
    val bgColor = activeTheme.backgroundColor
    val headerLabel = remember(entry) {
        when (entry.noteKind.lowercase()) {
            "glossref", "doc-glossref", "dfn", "doc-glossary", "glossary" -> "GLOSSARY • ${entry.displayMarker}"
            "biblioref", "doc-biblioref", "doc-bibliography", "bibliography" -> "BIBLIOGRAPHY • ${entry.displayMarker}"
            else -> "NOTE • ${entry.displayMarker}"
        }
    }

    Surface(
        shape = RoundedCornerShape(0.dp),
        color = bgColor,
        border = BorderStroke(1.5.dp, ruleColor),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("epub_popup_note_bottom_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = headerLabel,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = ruleColor
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("epub_popup_note_dismiss_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Note Popup",
                        tint = ruleColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            HorizontalDivider(thickness = 1.dp, color = ruleColor)
            Text(
                text = entry.noteText,
                fontFamily = FontFamily.Serif,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = ruleColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("epub_popup_note_body_text")
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onGoToNote,
                    shape = RoundedCornerShape(0.dp),
                    border = BorderStroke(1.dp, ruleColor),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("epub_popup_go_to_note_button")
                ) {
                    Text(
                        text = "Go to note",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = ruleColor
                    )
                }
                if (hasReturnOrigin) {
                    OutlinedButton(
                        onClick = onBackToOrigin,
                        shape = RoundedCornerShape(0.dp),
                        border = BorderStroke(1.dp, ruleColor),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("epub_popup_back_to_origin_button")
                    ) {
                        Text(
                            text = "Back",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = ruleColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun EpubFigureZoomModal(
    bitmap: Bitmap,
    caption: String,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("epub_figure_zoom_modal"),
            color = Color.White,
            shape = RoundedCornerShape(0.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.5.dp, Color.Black)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "FIGURE ZOOM (${(scale * 100).toInt()}%)",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color.Black
                    )
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(0.dp),
                        border = BorderStroke(1.dp, Color.Black),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("epub_figure_zoom_close_button")
                    ) {
                        Text(
                            text = "Close",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                scale = newScale
                                if (newScale > 1f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = caption.ifBlank { "Zoomed Figure" },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY
                            )
                    )
                }
                if (caption.isNotBlank()) {
                    HorizontalDivider(thickness = 1.5.dp, color = Color.Black)
                    Text(
                        text = caption,
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        fontSize = 13.sp,
                        color = Color.Black,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun EpubLandmarksDrawerSection(
    landmarks: List<com.example.reader.EpubLandmarkItem>,
    activeTheme: ReaderTheme,
    onSelectLandmark: (com.example.reader.EpubLandmarkItem) -> Unit
) {
    if (landmarks.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("epub_landmarks_drawer_section")
    ) {
        Text(
            text = "Landmarks",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = activeTheme.textColor,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        landmarks.forEachIndexed { idx, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectLandmark(item) }
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .testTag("landmark_item_${item.epubType}_$idx"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,
                    color = activeTheme.textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(0.dp),
                    color = activeTheme.barColor,
                    border = BorderStroke(1.dp, activeTheme.dividerColor),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        text = "p. ${item.resolvedPage}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = activeTheme.textColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        HorizontalDivider(thickness = 1.5.dp, color = activeTheme.dividerColor)
    }
}

