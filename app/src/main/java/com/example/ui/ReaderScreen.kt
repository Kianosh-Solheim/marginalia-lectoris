package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import com.example.ui.koreader.KOReaderTopBar
import com.example.ui.koreader.KOReaderBottomStatusBar
import com.example.ui.koreader.KOReaderQuickScrubberDialog
import com.example.ui.koreader.KOReaderBookInfoDialog
import com.example.ui.koreader.KOReaderObsidianHubDialog
import com.example.ui.koreader.KOReaderToolsDialog
import com.example.ui.koreader.KOReaderTtsControllerCard
import com.example.ui.koreader.KOReaderTtsSettingsDialog
import com.example.ui.koreader.KOReaderEpubEngine
import com.example.reader.BritishMaleOfflineTtsManager
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.data.local.SettingsEntity
import com.example.model.HighlightPalette
import com.example.model.parseHighlightColor
import com.example.reader.BookChapter
import com.example.reader.EpubBook
import com.example.reader.EpubPage
import com.example.reader.EpubParser
import com.example.reader.MarkdownExporter
import com.example.reader.PdfChapterHelper
import com.example.reader.PdfDocumentRenderer
import com.example.reader.PdfPageTextData
import com.example.reader.PdfPageWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/** The old reflow (parse every page, show plain paragraphs) is replaced by [com.example.reader.PdfReflow]. */
private const val USE_LEGACY_PDF_REFLOW = false

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    document: DocumentEntity,
    initialPage: Int,
    initialHighlightRequest: HighlightEntity? = null,
    onClearInitialHighlightRequest: () -> Unit = {},
    highlights: List<HighlightEntity>,
    settings: SettingsEntity,
    pageTurnTrigger: SharedFlow<Int>,
    onNavigateBack: () -> Unit,
    onProgressUpdate: (page: Int, totalPages: Int) -> Unit,
    onAddHighlight: (title: String, quote: String, notes: String, page: Int, printedPage: String, chapter: String, colorHex: String, tags: String, paragraphCitation: String, pageEquivalent: String, epubCfi: String) -> Unit,
    onUpdateHighlight: (HighlightEntity) -> Unit = {},
    onDeleteHighlight: (HighlightEntity) -> Unit,
    onExportVaultZip: () -> Unit,
    onExportMoc: () -> Unit,
    onExportSingleHighlight: (HighlightEntity) -> Unit,
    onExportObsidianMarkdown: () -> Unit = {},
    onCopyObsidianMarkdown: () -> Unit = {},
    onSyncToObsidianVault: () -> Unit = {},
    onSaveChapters: (String) -> Unit = {},
    onUpdatePdfCrop: (left: Float, top: Float, right: Float, bottom: Float) -> Unit = { _, _, _, _ -> },
    onUpdatePdfTextExtractionConfig: (mode: String, overridesJson: String, ocrLanguage: String) -> Unit = { _, _, _ -> },
    onToggleWidgetPin: (() -> Unit)? = null,
    onUpdateDocumentAuthor: (String) -> Unit = {},
    onUpdateDocumentSummary: (String) -> Unit = {},
    onSaveSettings: (SettingsEntity) -> Unit = {},
    onTtsPlayingChanged: (Boolean) -> Unit = {}
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalBookPath provides document.filePath, LocalReaderDocument provides document) {

    val context = LocalContext.current
    // Glossae (notes laid over their passages) is remembered per book.
    var glossaeEnabled by remember(document.id) { mutableStateOf(com.example.ui.glossae.Glossae.isEnabled(context, document.id)) }
    LaunchedEffect(document.id) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.example.reader.BibStore.init(context)
            com.example.reader.BibStore.seedFromFile(document)
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val chapterDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var currentDocAuthor by remember(document.id, document.author) { mutableStateOf(document.author) }
    var currentDocSummary by remember(document.id, document.summary) { mutableStateOf(document.summary) }
    val effectiveDocument = remember(document, currentDocAuthor, currentDocSummary) {
        document.copy(author = currentDocAuthor, summary = currentDocSummary)
    }

    var pendingAuthorSelectionText by remember { mutableStateOf<String?>(null) }
    var pendingAbstractSelectionText by remember { mutableStateOf<String?>(null) }
    var abstractPriorSummarySnapshot by remember { mutableStateOf("") }
    var abstractWasAutoSavedWhenEmpty by remember { mutableStateOf(false) }

    val handleAddAsAuthorSelection: (String) -> Unit = { selectedText ->
        val cleaned = DocumentMetadataHelper.cleanSelectedAuthorText(selectedText)
        if (cleaned.isNotBlank()) {
            pendingAuthorSelectionText = selectedText
        } else {
            Toast.makeText(context, "Select text containing an author's name first", Toast.LENGTH_SHORT).show()
        }
    }

    val handleSetAsAbstractSelection: (String) -> Unit = { selectedText ->
        val cleaned = DocumentMetadataHelper.cleanSelectedAbstractText(selectedText)
        if (cleaned.isNotBlank()) {
            val priorSummary = currentDocSummary.trim()
            if (priorSummary.isBlank()) {
                currentDocSummary = cleaned
                onUpdateDocumentSummary(cleaned)
                abstractPriorSummarySnapshot = ""
                abstractWasAutoSavedWhenEmpty = true
                pendingAbstractSelectionText = cleaned
                Toast.makeText(context, "Saved as document Abstract/Summary!", Toast.LENGTH_SHORT).show()
            } else {
                abstractPriorSummarySnapshot = priorSummary
                abstractWasAutoSavedWhenEmpty = false
                pendingAbstractSelectionText = cleaned
            }
        } else {
            Toast.makeText(context, "Select a passage of text first", Toast.LENGTH_SHORT).show()
        }
    }

    var currentActivePage by remember(document.id) { mutableIntStateOf(initialPage.coerceAtLeast(1)) }
    var activeTotalPages by remember(document.id) { mutableIntStateOf(document.totalPages.coerceAtLeast(1)) }
    var activeFootnote by remember { mutableStateOf<Pair<String, String>?>(null) }
    var activeFootnoteEntry by remember { mutableStateOf<com.example.reader.EpubFootnoteEntry?>(null) }
    var activePageTextForTts by remember(document.id) { mutableStateOf("") }
    var showTtsBar by remember(document.id) { mutableStateOf(false) }
    var isTtsMinimized by remember(settings.ttsMinimized) { mutableStateOf(settings.ttsMinimized) }
    var showTtsSettingsDialog by remember { mutableStateOf(false) }
    var pendingTtsAutoPlayNextPage by remember(document.id) { mutableStateOf(false) }

    // PDF-to-Ebook Reflow Reading Mode State (synchronised with Original PDF View)
    val isPdfDocument = remember(document.fileType, document.filePath) {
        document.fileType.equals("PDF", ignoreCase = true) || document.filePath.lowercase().endsWith(".pdf")
    }
    val isDaisyDocument = remember(document.fileType, document.filePath) {
        document.fileType.equals("DAISY", ignoreCase = true) || File(document.filePath).isDirectory
    }
    var daisyBookIndex by remember(document.id) { mutableStateOf<com.example.reader.DaisyBookIndex?>(null) }
    var daisyEpubBook by remember(document.id) { mutableStateOf<EpubBook?>(null) }
    var showDaisyAudioBar by remember(document.id, isDaisyDocument) { mutableStateOf(isDaisyDocument) }
    var isDaisyAudioMinimized by remember(document.id) { mutableStateOf(false) }

    var isPdfEbookMode by remember(document.id) { mutableStateOf(false) }
    var sharedPdfRenderer by remember(document.id) { mutableStateOf<PdfDocumentRenderer?>(null) }
    var pdfEbookDocument by remember(document.id) { mutableStateOf<com.example.reader.PdfEbookDocument?>(null) }
    var isParsingPdfEbook by remember(document.id) { mutableStateOf(false) }
    var pdfParsingProgressPage by remember(document.id) { mutableIntStateOf(0) }
    var pdfParsingTotalPages by remember(document.id) { mutableIntStateOf(1) }
    var activeReflowPageMeta by remember(document.id) { mutableStateOf<com.example.reader.PdfEbookReflowPage?>(null) }
    var activePdfPrintedPageLabel by remember(document.id) { mutableStateOf("") }
    var syncedPdfPageAnchor by remember(document.id) { mutableIntStateOf(initialPage.coerceAtLeast(1)) }
    var syncedPdfTotalPages by remember(document.id) { mutableIntStateOf(document.totalPages.coerceAtLeast(1)) }

    // PDF Text Extraction Mode, Per-Page Overrides & OCR State
    var pdfExtractionMode by remember(document.id, document.pdfTextExtractionMode) {
        mutableStateOf(com.example.reader.PdfTextExtractionMode.fromId(document.pdfTextExtractionMode))
    }
    var pdfPageExtractionOverrides by remember(document.id, document.pdfPageExtractionOverridesJson) {
        mutableStateOf(com.example.reader.PdfPageDiagnosticsAndOcrEngine.parsePageOverridesJson(document.pdfPageExtractionOverridesJson))
    }
    var pdfOcrLanguage by remember(document.id, document.pdfOcrLanguage) {
        mutableStateOf(document.pdfOcrLanguage.ifBlank { "AUTO" })
    }
    var pdfExtractionConfigVersion by remember(document.id) { mutableIntStateOf(0) }
    var showPdfTextToolsDialog by remember { mutableStateOf(false) }
    var pdfTextToolsInitialTab by remember { mutableIntStateOf(0) }
    var pdfTextToolsInitialRegion by remember { mutableStateOf(android.graphics.RectF(0f, 0f, 1f, 1f)) }
    var pdfTextToolsInitialSelectedText by remember { mutableStateOf("") }

    // Initialize a single shared PdfDocumentRenderer for PDF files on a background Coroutine
    // so opening large PDFs never blocks the UI thread!
    LaunchedEffect(document.id, document.filePath) {
        if (isPdfDocument) {
            val file = File(document.filePath)
            if (file.exists()) {
                val created = withContext(Dispatchers.IO) {
                    PdfDocumentRenderer(file, context).apply {
                        updateExtractionConfiguration(
                            mode = pdfExtractionMode,
                            overrides = pdfPageExtractionOverrides,
                            ocrLanguage = pdfOcrLanguage
                        )
                    }
                }
                val count = withContext(Dispatchers.IO) { created.pageCount.coerceAtLeast(1) }
                sharedPdfRenderer?.close()
                sharedPdfRenderer = created
                syncedPdfTotalPages = count
                activeTotalPages = count
            }
        }
    }

    DisposableEffect(document.id, document.filePath) {
        onDispose {
            sharedPdfRenderer?.close()
            sharedPdfRenderer = null
        }
    }

    LaunchedEffect(sharedPdfRenderer, pdfExtractionMode, pdfPageExtractionOverrides, pdfOcrLanguage) {
        sharedPdfRenderer?.updateExtractionConfiguration(
            mode = pdfExtractionMode,
            overrides = pdfPageExtractionOverrides,
            ocrLanguage = pdfOcrLanguage
        )
    }

    // Parse PdfEbookDocument on background thread when PDF-to-Ebook mode is activated (so opening a PDF in Original PDF View is instantaneous and never competes for PDFBox/memory)
    LaunchedEffect(isPdfDocument, isPdfEbookMode, sharedPdfRenderer, document.title, document.author, document.chaptersJson, pdfExtractionConfigVersion) {
        val renderer = sharedPdfRenderer
        // Superseded by the EPUB-based reflow (PdfReflow); kept for reference only.
        if (USE_LEGACY_PDF_REFLOW && isPdfDocument && isPdfEbookMode && renderer != null && pdfEbookDocument == null) {
            isParsingPdfEbook = true
            val totalP = renderer.pageCount.coerceAtLeast(1)
            pdfParsingTotalPages = totalP
            pdfParsingProgressPage = 1
            withContext(Dispatchers.IO) {
                try {
                    val file = File(document.filePath)
                    val chapters = PdfChapterHelper.extractOrGenerateChapters(file, totalP, document.chaptersJson)
                    val parsed = com.example.reader.PdfToEbookParser.parsePdfToEbook(
                        renderer = renderer,
                        documentTitle = document.title,
                        documentAuthor = document.author,
                        chapterBookmarks = chapters,
                        onProgress = { curPage, totPages ->
                            pdfParsingProgressPage = curPage
                            pdfParsingTotalPages = totPages
                        }
                    )
                    pdfEbookDocument = parsed
                } catch (e: Throwable) {
                    e.printStackTrace()
                } finally {
                    isParsingPdfEbook = false
                }
            }
        }
    }

    // Reflowable view of a PDF: converted to a styled EPUB (pages around the reading position first,
    // the whole book in the background) and read with the EPUB engine.
    var reflowBook by remember(document.id) { mutableStateOf<com.example.reader.PdfReflow.Book?>(null) }
    var reflowStatus by remember(document.id) { mutableStateOf("") }
    var reflowError by remember(document.id) { mutableStateOf<String?>(null) }
    var reflowJob by remember(document.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LaunchedEffect(isPdfDocument, isPdfEbookMode) {
        if (isPdfDocument && isPdfEbookMode && reflowJob == null && reflowBook?.complete != true) {
            reflowError = null
            reflowJob = coroutineScope.launch {
                try {
                    com.example.reader.PdfReflow.open(
                        context, document, syncedPdfPageAnchor,
                        onProgress = { msg, _ -> reflowStatus = msg },
                        onBook = { b -> reflowBook = b }
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    android.util.Log.e("PdfReflow", "conversion failed", t)
                    reflowError = t.message ?: t.javaClass.simpleName
                } finally {
                    reflowJob = null
                }
            }
        }
    }

    // Chapters list for Table of Contents side menu & page jump triggers
    var chaptersList by remember { mutableStateOf<List<BookChapter>>(emptyList()) }
    // Edited volumes: remember how far each chapter has been read.
    val volumePage = if (isPdfDocument && isPdfEbookMode) syncedPdfPageAnchor else currentActivePage
    LaunchedEffect(document.id, volumePage) {
        if (com.example.reader.EditedVolumes.get(context, document.id)?.enabled == true) {
            kotlinx.coroutines.delay(1500) // only pages actually read, not ones flipped past
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.example.reader.EditedVolumes.notePageSeen(context, document.id, volumePage) }
            }
        }
    }
    // Edited volumes made with an older version of the chapter reader are read again (keeping the user's edits).
    LaunchedEffect(document.id) {
        val v = com.example.reader.EditedVolumes.get(context, document.id)
        if (v != null && v.enabled && v.format < com.example.reader.EditedVolume.CURRENT_FORMAT) {
            com.example.reader.ChapterDetector.detect(context, document).onSuccess { d ->
                com.example.reader.EditedVolumes.put(context, document.id,
                    d.volume.copy(chapters = com.example.reader.EditedVolumes.mergeRedetected(v.chapters, d.volume.chapters)))
            }
        }
    }
    // Edited volumes: keep the chapter list's start positions in line with the reader's contents.
    LaunchedEffect(chaptersList) {
        if (chaptersList.isNotEmpty()) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.example.reader.EditedVolumes.syncFromReader(context, document, chaptersList) }
        }
    }
    var landmarksList by remember(document.id) { mutableStateOf<List<com.example.reader.EpubLandmarkItem>>(emptyList()) }
    var activeEpubPages by remember(document.id) { mutableStateOf<List<com.example.reader.EpubPage>>(emptyList()) }
    var targetPageRequest by remember(document.id) {
        mutableStateOf(initialHighlightRequest?.let { (it.pageNumber - 1).coerceAtLeast(0) })
    }
    var targetHighlightRequest by remember(document.id) { mutableStateOf(initialHighlightRequest) }
    var jumpRequestToken by remember(document.id) { mutableIntStateOf(if (initialHighlightRequest != null) 1 else 0) }

    // Readium Mobile Toolkit engine state (publisher fidelity, column layout & fixed-layout support)
    var publisherStylesEnabled by remember(settings.publisherStylesEnabled) { mutableStateOf(settings.publisherStylesEnabled) }
    var readerColumnCount by remember(settings.readerColumnCount) { mutableIntStateOf(settings.readerColumnCount.coerceIn(1, 2)) }
    var isReadiumFixedLayout by remember(document.id) { mutableStateOf(false) }
    var useReadiumEngine by remember { mutableStateOf(true) }

    LaunchedEffect(initialHighlightRequest) {
        if (initialHighlightRequest != null) {
            targetHighlightRequest = initialHighlightRequest
            targetPageRequest = (initialHighlightRequest.pageNumber - 1).coerceAtLeast(0)
            jumpRequestToken++
            onClearInitialHighlightRequest()
        }
    }

    // Fast, Offline Male British English (en-GB) & Natural Norwegian (nb-NO) Text-To-Speech Manager
    val ttsManager = remember(document.id) {
        BritishMaleOfflineTtsManager(
            context = context,
            initialRate = settings.ttsSpeechRate,
            initialPitch = settings.ttsPitch,
            initialAutoAdvance = settings.ttsAutoAdvancePage,
            initialPreferredVoiceId = settings.ttsPreferredVoiceId,
            initialLanguage = settings.ttsLanguage,
            onPageFinishedAutoAdvance = {
                val total = activeTotalPages.coerceAtLeast(1)
                if (currentActivePage < total) {
                    pendingTtsAutoPlayNextPage = true
                    targetHighlightRequest = null
                    targetPageRequest = currentActivePage // 0-indexed next page = currentActivePage
                    jumpRequestToken++
                }
            }
        )
    }
    val ttsState by ttsManager.uiState.collectAsState()

    val daisyAudioManager = remember(document.id) {
        com.example.reader.DaisyAudioPlayerManager(
            context = context,
            ttsManager = ttsManager,
            onAutoAdvanceToPage = { targetPageNumber, clip ->
                if (clip.textSnippet.isNotBlank() || clip.textAnchorId.isNotBlank()) {
                    targetHighlightRequest = HighlightEntity(
                        documentId = document.id,
                        title = clip.chapterTitle,
                        highlightedText = clip.textSnippet,
                        userNotes = clip.textAnchorId,
                        pageNumber = targetPageNumber.coerceAtLeast(1),
                        chapterTitle = clip.chapterTitle,
                        paragraphCitation = clip.textFileKey,
                        pageEquivalent = clip.pageEquivalent,
                        colorHex = "#FFEB3B"
                    )
                } else {
                    targetHighlightRequest = null
                    targetPageRequest = (targetPageNumber - 1).coerceAtLeast(0)
                }
                jumpRequestToken++
            }
        )
    }
    val daisyAudioState by daisyAudioManager.state.collectAsState()

    // Parse and index DAISY book archive or directory when opening a DAISY document
    LaunchedEffect(document.id, document.filePath, isDaisyDocument) {
        if (isDaisyDocument) {
            val fileOrDir = File(document.filePath)
            if (fileOrDir.exists()) {
                val resolvedIndex = withContext(Dispatchers.IO) {
                    com.example.reader.DaisyIndexingService.indexArchiveOrDirectory(
                        fileOrDir = fileOrDir,
                        fallbackTitle = document.title,
                        fallbackAuthor = document.author
                    )
                }
                daisyBookIndex = resolvedIndex
                daisyEpubBook = resolvedIndex.epubBook
                daisyAudioManager.attachBookIndex(resolvedIndex)
            }
        }
    }

    // Notify Activity/ViewModel whenever TTS or DAISY audio playback state changes so physical volume buttons
    // adjust media volume instead of turning pages while audio is playing!
    LaunchedEffect(ttsState.isPlaying, daisyAudioState.isPlaying) {
        onTtsPlayingChanged(ttsState.isPlaying || daisyAudioState.isPlaying)
    }

    // Typography & Formatting states
    var fontSizeSp by remember(settings) { mutableFloatStateOf(settings.readerFontSizeSp) }
    var textAlignSetting by remember(settings) { mutableStateOf(settings.readerTextAlign) }
    var marginDp by remember(settings) { mutableIntStateOf(settings.readerMarginDp) }
    var topMarginDp by remember(settings) { mutableIntStateOf(settings.readerTopMarginDp.coerceIn(0, 160)) }
    var bottomMarginDp by remember(settings) { mutableIntStateOf(settings.readerBottomMarginDp.coerceIn(0, 160)) }
    var lineSpacingMultiplier by remember(settings) { mutableFloatStateOf(settings.readerLineSpacing) }
    var fontFamilySetting by remember(settings) { mutableStateOf(settings.readerFontFamily) }
    var readerViewModeSetting by remember(settings) { mutableStateOf(settings.readerViewMode.ifBlank { "PAGINATED" }) }
    var pageTurnAnimationSetting by remember(settings) { mutableStateOf(settings.pageTurnAnimation.ifBlank { "PAGE_CURL" }) }
    var volumeKeyAnimationSetting by remember(settings) { mutableStateOf(settings.volumeKeyAnimation) }
    var showEpubPageEquivalentsSetting by remember(settings) { mutableStateOf(settings.showEpubPageEquivalents) }
    var paginateByEpubPageEquivalentsSetting by remember(settings) { mutableStateOf(settings.paginateByEpubPageEquivalents) }
    var activeEpubPageEquivalentLabel by remember(document.id) { mutableStateOf("") }
    var activeEpubHasPageListMetadata by remember(document.id) { mutableStateOf(false) }
    var targetPrintPageRequest by remember(document.id) { mutableStateOf<String?>(null) }
    var showGoToPrintPageDialog by remember { mutableStateOf(false) }

    LaunchedEffect(document.id) {
        activeEpubPageEquivalentLabel = ""
        activeEpubHasPageListMetadata = false
        targetPrintPageRequest = null
    }
    var readerThemeSetting by remember(settings) {
        val initialT = if (settings.readerTheme.equals("DARK", ignoreCase = true)) "DARK"
        else if (settings.readerTheme.equals("CREAM", ignoreCase = true)) "CREAM"
        else "WHITE"
        mutableStateOf(initialT)
    }

    val persistFormatting = {
        val currentTts = ttsManager.uiState.value
        val updated = settings.copy(
            readerFontSizeSp = fontSizeSp,
            readerTextAlign = textAlignSetting,
            readerMarginDp = marginDp,
            readerTopMarginDp = topMarginDp.coerceIn(0, 160),
            readerBottomMarginDp = bottomMarginDp.coerceIn(0, 160),
            readerLineSpacing = lineSpacingMultiplier,
            readerFontFamily = fontFamilySetting,
            readerViewMode = readerViewModeSetting,
            pageTurnAnimation = pageTurnAnimationSetting,
            volumeKeyAnimation = volumeKeyAnimationSetting,
            showEpubPageEquivalents = showEpubPageEquivalentsSetting,
            paginateByEpubPageEquivalents = paginateByEpubPageEquivalentsSetting,
            readerTheme = readerThemeSetting,
            readerColumnCount = readerColumnCount.coerceIn(1, 2),
            publisherStylesEnabled = publisherStylesEnabled,
            ttsSpeechRate = currentTts.speechRate,
            ttsPitch = currentTts.pitch,
            ttsAutoAdvancePage = currentTts.autoAdvancePage,
            ttsPreferredVoiceId = currentTts.activeVoiceId,
            ttsLanguage = currentTts.activeLanguage,
            ttsMinimized = isTtsMinimized
        )
        onSaveSettings(updated)
    }

    // Whenever the active page text changes, feed it to TTS (and auto-continue if TTS was playing or auto-advanced)
    LaunchedEffect(activePageTextForTts, currentActivePage) {
        if (activePageTextForTts.isNotBlank()) {
            val shouldStartImmediately = pendingTtsAutoPlayNextPage || ttsManager.uiState.value.isPlaying
            pendingTtsAutoPlayNextPage = false
            ttsManager.loadPageText(
                rawText = activePageTextForTts,
                pageNumber = currentActivePage,
                startImmediately = shouldStartImmediately
            )
        }
    }

    DisposableEffect(document.id) {
        onDispose {
            onTtsPlayingChanged(false)
            ttsManager.shutdown()
            daisyAudioManager.release()
            onProgressUpdate(currentActivePage, activeTotalPages.coerceAtLeast(1))
            persistFormatting()
        }
    }

    BackHandler {
        if (chapterDrawerState.isOpen) {
            coroutineScope.launch { chapterDrawerState.close() }
        } else {
            onTtsPlayingChanged(false)
            ttsManager.stop()
            daisyAudioManager.stop()
            onProgressUpdate(currentActivePage, activeTotalPages.coerceAtLeast(1))
            persistFormatting()
            onNavigateBack()
        }
    }

    // Fullscreen / Immersive Reading mode: Tap in middle hides all UI bars (including system status bar), tap again brings them back!
    var isUiVisible by remember { mutableStateOf(true) }
    val localView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(isUiVisible) {
        val window = (context as? android.app.Activity)?.window
        if (window != null) {
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, localView)
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (isUiVisible) {
                controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            } else {
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val window = (context as? android.app.Activity)?.window
            if (window != null) {
                androidx.core.view.WindowCompat.getInsetsController(window, localView)
                    .show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            }
        }
    }

    val activeReaderTheme = ReaderTheme.entries.firstOrNull { it.id.equals(readerThemeSetting, ignoreCase = true) } ?: ReaderTheme.WHITE

    var showFormattingSheet by remember { mutableStateOf(false) }
    var showHighlightsSheet by remember { mutableStateOf(false) }
    var showExportPreviewDialog by remember { mutableStateOf(false) }
    var showAddHighlightDialog by remember { mutableStateOf(false) }
    var showAddChapterDialog by remember { mutableStateOf(false) }

    // KOReader Specialized Dialogs
    var showBookInfoDialog by remember { mutableStateOf(false) }
    var showQuickScrubberDialog by remember { mutableStateOf(false) }
    var showObsidianHubDialog by remember { mutableStateOf(false) }
    var showToolsDialog by remember { mutableStateOf(false) }
    var showPdfCropDialog by remember { mutableStateOf(false) }

    var highlightQuoteText by remember { mutableStateOf("") }
    var highlightChapterText by remember { mutableStateOf("") }
    var highlightParagraphCitation by remember { mutableStateOf("") }
    var highlightPageEquivalent by remember { mutableStateOf("") }
    var highlightEpubCfi by remember { mutableStateOf("") }
    var highlightPageNumber by remember { mutableIntStateOf(initialPage) }
    var highlightPrintedPageLabel by remember { mutableStateOf("") }
    var highlightColorHex by remember { mutableStateOf("#FFEB3B") }
    var highlightToEdit by remember { mutableStateOf<HighlightEntity?>(null) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val recordingNow by com.example.reader.Recordings.active.collectAsState()
    val activeChapter = remember(chaptersList, currentActivePage) {
        chaptersList.filter { it.pageNumber <= currentActivePage }
            .maxByOrNull { it.pageNumber } ?: chaptersList.firstOrNull()
    }

    val recordingLabel = {
        if (isPdfDocument) activePdfPrintedPageLabel.trim()
        else runCatching { activeEpubPages.getOrNull(currentActivePage - 1)?.pageEquivalentLabel.orEmpty() }.getOrDefault("")
    }
    val toggleRecording = rememberRecordingToggle(document) { Triple(currentActivePage, recordingLabel(), activeChapter?.title.orEmpty()) }
    LaunchedEffect(currentActivePage, recordingNow?.docId) {
        if (recordingNow != null) com.example.reader.Recordings.page(document.id, currentActivePage, recordingLabel(), activeChapter?.title.orEmpty())
    }

    // Compute synchronized page info strings for the Top Bar and Bottom Info Bar
    val topBarPageSubtitle = remember(
        isPdfDocument,
        isPdfEbookMode,
        currentActivePage,
        activeTotalPages,
        activeReflowPageMeta,
        activePdfPrintedPageLabel,
        syncedPdfPageAnchor,
        syncedPdfTotalPages,
        showEpubPageEquivalentsSetting,
        activeEpubPageEquivalentLabel
    ) {
        if (isPdfDocument && isPdfEbookMode) {
            val meta = activeReflowPageMeta
            val sPdf = meta?.startPdfPage ?: syncedPdfPageAnchor
            val ePdf = meta?.endPdfPage ?: sPdf
            val pdfStr = if (ePdf > sPdf) "PDF pp. $sPdf–$ePdf" else "PDF p. $sPdf"
            val prStr = meta?.let {
                val s = it.startPrintedPage.trim()
                val e = it.endPrintedPage.trim()
                when {
                    s.isNotEmpty() && e.isNotEmpty() && !s.equals(e, ignoreCase = true) -> " • Printed pp. $s–$e"
                    s.isNotEmpty() -> " • Printed p. $s"
                    e.isNotEmpty() -> " • Printed p. $e"
                    else -> ""
                }
            } ?: activePdfPrintedPageLabel.trim().takeIf { it.isNotEmpty() }?.let { " • Printed p. $it" }.orEmpty()
            if (meta == null) "$pdfStr$prStr" else "Ebook p. $currentActivePage/$activeTotalPages • $pdfStr$prStr"
        } else if (isPdfDocument) {
            val pr = activePdfPrintedPageLabel.trim()
            if (pr.isNotEmpty()) {
                "PDF p. $currentActivePage/$activeTotalPages • Printed p. $pr"
            } else {
                "PDF p. $currentActivePage/$activeTotalPages"
            }
        } else if (showEpubPageEquivalentsSetting && activeEpubPageEquivalentLabel.isNotBlank()) {
            val eq = activeEpubPageEquivalentLabel.trim()
            val eqPrefix = if (eq.contains("–") || eq.contains("-")) "Printed pp. $eq" else "Printed p. $eq"
            "$eqPrefix • Ebook p. $currentActivePage/$activeTotalPages"
        } else {
            ""
        }
    }

    val bottomBarPageInfoLabel = remember(
        isPdfDocument,
        isPdfEbookMode,
        syncedPdfPageAnchor,
        syncedPdfTotalPages,
        currentActivePage,
        activeTotalPages,
        activeReflowPageMeta,
        activePdfPrintedPageLabel,
        showEpubPageEquivalentsSetting,
        activeEpubPageEquivalentLabel
    ) {
        if (isPdfDocument && isPdfEbookMode) {
            val meta = activeReflowPageMeta
            if (meta != null) {
                meta.formatStatusBarSummary(currentActivePage, activeTotalPages)
            } else {
                val pr = activePdfPrintedPageLabel.trim()
                (if (pr.isNotEmpty()) "Printed p. $pr • " else "") + "PDF p. $syncedPdfPageAnchor/$syncedPdfTotalPages"
            }
        } else if (isPdfDocument) {
            val pr = activePdfPrintedPageLabel.trim()
            if (pr.isNotEmpty()) {
                "PDF p. $currentActivePage/$activeTotalPages • Printed p. $pr"
            } else {
                "Page $currentActivePage / $activeTotalPages"
            }
        } else if (showEpubPageEquivalentsSetting && activeEpubPageEquivalentLabel.isNotBlank()) {
            val eq = activeEpubPageEquivalentLabel.trim()
            val eqPrefix = if (eq.contains("–") || eq.contains("-")) "Printed pp. $eq" else "Printed p. $eq"
            "$eqPrefix (Ebook p. $currentActivePage/$activeTotalPages)"
        } else {
            ""
        }
    }

    ModalNavigationDrawer(
        drawerState = chapterDrawerState,
        gesturesEnabled = false,
        scrimColor = Color.Transparent,
        drawerContent = {
            val tocVolumeTick = com.example.reader.EditedVolumes.changes.intValue
            val tocVolume = remember(document.id, tocVolumeTick) {
                com.example.reader.EditedVolumes.get(context, document.id)?.takeIf { it.enabled && it.chapters.any { c -> c.isArticle } }
                    ?.takeIf { com.example.reader.EditedVolumes.isEditedVolume(context, document) }
            }
            ReaderChapterDrawerSheet(
                chapterAuthor = { ch -> tocVolume?.let { com.example.reader.EditedVolumes.authorForContentsEntry(it, ch.title) } },
                documentTitle = document.title,
                chaptersList = chaptersList,
                activeChapter = activeChapter,
                currentActivePage = currentActivePage,
                activeTotalPages = activeTotalPages,
                highlightsCount = highlights.size,
                daisyBookIndex = daisyBookIndex,
                activeDaisyClipIndex = daisyAudioState.currentClipIndex,
                onAddChapterClick = { showAddChapterDialog = true },
                onChapterSelected = { chapter ->
                    targetHighlightRequest = null
                    targetPageRequest = (chapter.pageNumber - 1).coerceAtLeast(0)
                    jumpRequestToken++
                    if (isDaisyDocument) {
                        val navItem = daisyBookIndex?.nccNavItems?.firstOrNull {
                            it.title.equals(chapter.title, ignoreCase = true)
                        }
                        if (navItem != null) {
                            daisyAudioManager.seekToNavItem(navItem, autoPlay = daisyAudioState.isPlaying)
                        }
                    }
                    coroutineScope.launch { chapterDrawerState.close() }
                },
                onDaisyNavItemSelected = { navItem ->
                    daisyAudioManager.seekToNavItem(navItem, autoPlay = daisyAudioState.isPlaying)
                    val matchingCh = chaptersList.firstOrNull {
                        it.title.equals(navItem.title, ignoreCase = true) ||
                            it.title.contains(navItem.title, ignoreCase = true)
                    }
                    if (matchingCh != null && !navItem.isPageNumberTarget) {
                        targetHighlightRequest = null
                        targetPageRequest = (matchingCh.pageNumber - 1).coerceAtLeast(0)
                        jumpRequestToken++
                    } else if (navItem.textAnchorId.isNotBlank() || navItem.pageLabel.isNotBlank() || navItem.title.isNotBlank()) {
                        targetHighlightRequest = HighlightEntity(
                            documentId = document.id,
                            title = navItem.title,
                            highlightedText = navItem.title,
                            userNotes = navItem.textAnchorId,
                            pageNumber = navItem.pageNumber.coerceAtLeast(1),
                            chapterTitle = if (navItem.isPageNumberTarget) "" else navItem.title,
                            paragraphCitation = navItem.textFileKey,
                            pageEquivalent = navItem.pageLabel,
                            colorHex = "#FFEB3B"
                        )
                        jumpRequestToken++
                    }
                    coroutineScope.launch { chapterDrawerState.close() }
                },
                onDaisySmilClipSelected = { clip ->
                    showDaisyAudioBar = true
                    daisyAudioManager.seekToClipIndex(clip.index, autoPlay = true)
                    if (clip.textSnippet.isNotBlank() || clip.textAnchorId.isNotBlank()) {
                        targetHighlightRequest = HighlightEntity(
                            documentId = document.id,
                            title = clip.chapterTitle,
                            highlightedText = clip.textSnippet,
                            userNotes = clip.textAnchorId,
                            pageNumber = clip.pageNumber.coerceAtLeast(1),
                            chapterTitle = clip.chapterTitle,
                            paragraphCitation = clip.textFileKey,
                            pageEquivalent = clip.pageEquivalent,
                            colorHex = "#FFEB3B"
                        )
                        jumpRequestToken++
                    }
                    coroutineScope.launch { chapterDrawerState.close() }
                },
                onViewNotesClick = {
                    coroutineScope.launch { chapterDrawerState.close() }
                    showHighlightsSheet = true
                },
                landmarksSection = if (!isPdfDocument && landmarksList.isNotEmpty()) {
                    {
                        EpubLandmarksDrawerSection(
                            landmarks = landmarksList,
                            activeTheme = ReaderTheme.WHITE,
                            onSelectLandmark = { lm ->
                                targetHighlightRequest = null
                                targetPageRequest = (lm.resolvedPage - 1).coerceAtLeast(0)
                                jumpRequestToken++
                                coroutineScope.launch { chapterDrawerState.close() }
                            }
                        )
                    }
                } else null
            )
        }
    ) {
        Scaffold(
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            topBar = {
                val volumeTick = com.example.reader.EditedVolumes.changes.intValue
                val volumeChapterLine = remember(document.id, volumePage, activeChapter, volumeTick) {
                    val v = com.example.reader.EditedVolumes.get(context, document.id)?.takeIf { it.enabled }
                    val i = v?.let { com.example.reader.EditedVolumes.chapterIndexFor(it, volumePage, activeChapter?.title.orEmpty()) } ?: -1
                    val c = v?.chapters?.getOrNull(i)
                    if (v == null || c == null) "" else {
                        val n = com.example.reader.EditedVolumes.numberOf(v.chapters, i)
                        (if (n.isNotBlank()) "Ch. $n · " else "") + c.title + (if (c.authors.isNotBlank()) " — ${c.authors.replace("; ", ", ")}" else "")
                    }
                }
                KOReaderTopBar(
                    document = effectiveDocument,
                    volumeChapterLine = volumeChapterLine,
                    currentPage = currentActivePage,
                    totalPages = activeTotalPages.coerceAtLeast(1),
                    chapterTitle = activeChapter?.title ?: "",
                    highlightsCount = highlights.size,
                    activeTheme = activeReaderTheme,
                    visible = isUiVisible,
                    isTtsPlaying = ttsState.isPlaying || daisyAudioState.isPlaying,
                    isTtsBarVisible = if (isDaisyDocument) showDaisyAudioBar else showTtsBar,
                    isPdfEbookMode = isPdfEbookMode,
                    pageInfoSubtitle = topBarPageSubtitle,
                    onTogglePdfEbookMode = if (isPdfDocument) {
                        {
                            targetHighlightRequest = null
                            targetPageRequest = null
                            val nextMode = !isPdfEbookMode
                            if (!nextMode) {
                                currentActivePage = syncedPdfPageAnchor.coerceIn(1, syncedPdfTotalPages.coerceAtLeast(1))
                                activeTotalPages = syncedPdfTotalPages.coerceAtLeast(1)
                            }
                            isPdfEbookMode = nextMode
                        }
                    } else null,
                    onNavigateBack = {
                        ttsManager.stop()
                        daisyAudioManager.stop()
                        if (isPdfDocument) {
                            onProgressUpdate(syncedPdfPageAnchor, syncedPdfTotalPages.coerceAtLeast(1))
                        } else {
                            onProgressUpdate(currentActivePage, activeTotalPages.coerceAtLeast(1))
                        }
                        persistFormatting()
                        onNavigateBack()
                    },
                    onOpenFileMenu = { showBookInfoDialog = true },
                    onOpenTocMenu = { coroutineScope.launch { chapterDrawerState.open() } },
                    onOpenTypesettingMenu = { showFormattingSheet = true },
                    onOpenPdfCropMenu = { showPdfCropDialog = true },
                    onOpenPdfTextToolsMenu = if (isPdfDocument) {
                        {
                            pdfTextToolsInitialTab = 0
                            pdfTextToolsInitialRegion = android.graphics.RectF(0f, 0f, 1f, 1f)
                            pdfTextToolsInitialSelectedText = ""
                            showPdfTextToolsDialog = true
                        }
                    } else null,
                    isRecording = recordingNow?.docId == document.id,
                    onToggleRecording = toggleRecording,
                    onToggleTtsBar = {
                        if (isDaisyDocument) {
                            if (!showDaisyAudioBar) {
                                showDaisyAudioBar = true
                                if (!daisyAudioState.isPlaying) {
                                    daisyAudioManager.togglePlayPause()
                                }
                            } else {
                                showDaisyAudioBar = false
                            }
                        } else {
                            if (!showTtsBar) {
                                showTtsBar = true
                                if (!ttsState.isPlaying) {
                                    ttsManager.togglePlayPause(activePageTextForTts, currentActivePage)
                                }
                            } else {
                                showTtsBar = false
                            }
                        }
                    },
                    onOpenAnnotationsMenu = { showObsidianHubDialog = true },
                    onOpenSettingsMenu = { showToolsDialog = true },
                    glossaeEnabled = glossaeEnabled,
                    onToggleGlossae = {
                        glossaeEnabled = !glossaeEnabled
                        com.example.ui.glossae.Glossae.setEnabled(context, document.id, glossaeEnabled)
                        android.widget.Toast.makeText(
                            context,
                            if (glossaeEnabled) "Glossae on: tap a note to see the text" else "Glossae off",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            },
            bottomBar = {
                KOReaderBottomStatusBar(
                    currentPage = currentActivePage,
                    totalPages = activeTotalPages.coerceAtLeast(1),
                    chapterTitle = activeChapter?.title ?: "",
                    activeTheme = activeReaderTheme,
                    visible = isUiVisible,
                    pageInfoLabel = bottomBarPageInfoLabel,
                    onScrubberClick = { showQuickScrubberDialog = true },
                    onToggleFullscreen = { isUiVisible = !isUiVisible }
                )
            },
            containerColor = activeReaderTheme.backgroundColor
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(activeReaderTheme.backgroundColor)
            ) {
                if (isPdfDocument && !isPdfEbookMode) {
                    PdfReaderPagerContent(
                        document = document,
                        initialPage = syncedPdfPageAnchor,
                        isUiVisible = isUiVisible,
                        activeTheme = activeReaderTheme,
                        viewModeSetting = readerViewModeSetting,
                        pageTurnAnimation = pageTurnAnimationSetting,
                        volumeKeyAnimation = volumeKeyAnimationSetting,
                        highlights = highlights,
                        pdfEbookDocument = pdfEbookDocument,
                        externalPdfRenderer = sharedPdfRenderer,
                        pageTurnTrigger = pageTurnTrigger,
                        targetPageRequest = targetPageRequest,
                        onToggleUi = { isUiVisible = !isUiVisible },
                        onChaptersLoaded = { chaptersList = it },
                        onPageChanged = { page, total, chapterTitle ->
                            currentActivePage = page
                            activeTotalPages = total.coerceAtLeast(1)
                            syncedPdfPageAnchor = page
                            syncedPdfTotalPages = total.coerceAtLeast(1)
                            highlightPageNumber = page
                            highlightChapterText = chapterTitle
                            onProgressUpdate(page, total)
                        },
                        onActivePrintedPageChanged = { printedLabel ->
                            activePdfPrintedPageLabel = printedLabel
                        },
                        onActivePageTextChanged = { pageText ->
                            activePageTextForTts = pageText
                        },
                        onSpeakSelection = { quote, pageNum ->
                            showTtsBar = true
                            ttsManager.speakSelection(quote, pageNum)
                        },
                        onRequestHighlight = { quote, page, printedPage, chapterTitle, colorHex ->
                            highlightQuoteText = quote
                            highlightPageNumber = page
                            highlightPrintedPageLabel = printedPage
                            highlightChapterText = chapterTitle
                            highlightParagraphCitation = if (chapterTitle.isNotBlank()) "$chapterTitle, ¶ 1" else "Page $page, ¶ 1"
                            highlightPageEquivalent = ""
                            highlightEpubCfi = ""
                            highlightColorHex = colorHex
                            showAddHighlightDialog = true
                        },
                        onQuickSaveHighlight = { quote, page, printedPage, chapterTitle, colorHex ->
                            val autoTitle = com.example.reader.NoteSuggestionEngine.suggestTitles(
                                highlightedText = quote,
                                chapterTitle = chapterTitle,
                                pageNumber = page
                            ).firstOrNull() ?: "Note - PDF page $page"
                            val autoTags = com.example.reader.NoteSuggestionEngine.joinTagsList(
                                com.example.reader.NoteSuggestionEngine.suggestTags(
                                    highlightedText = quote,
                                    chapterTitle = chapterTitle,
                                    documentTitle = document.title
                                ).take(3)
                            )
                            val paraCite = if (chapterTitle.isNotBlank()) "$chapterTitle, ¶ 1" else "Page $page, ¶ 1"
                            onAddHighlight(autoTitle, quote, "", page, printedPage, chapterTitle, colorHex, autoTags, paraCite, "", "")
                            val (pdfRangeStr, cleanPrinted) = com.example.reader.PdfToEbookParser.parseStoredPageLabels(page, printedPage)
                            val pdfPart = if (pdfRangeStr.contains("–") || pdfRangeStr.contains("-")) "PDF pages $pdfRangeStr" else "PDF page number $pdfRangeStr"
                            val prPart = if (cleanPrinted.contains("–") || cleanPrinted.contains("-")) "printed pages $cleanPrinted" else "printed page number $cleanPrinted"
                            val pageMsg = if (cleanPrinted.isNotBlank()) "$pdfPart, $prPart" else pdfPart
                            Toast.makeText(context, "Saved highlight ($pageMsg)!", Toast.LENGTH_SHORT).show()
                        },
                        onAddAsAuthor = handleAddAsAuthorSelection,
                        onSetAsAbstract = handleSetAsAbstractSelection,
                        onEditHighlight = { highlight ->
                            highlightToEdit = highlight
                        },
                        showPdfCropDialog = showPdfCropDialog,
                        onDismissPdfCropDialog = { showPdfCropDialog = false },
                        onOpenPdfCropDialog = { showPdfCropDialog = true },
                        onUpdatePdfCrop = onUpdatePdfCrop,
                        extractionConfigVersion = pdfExtractionConfigVersion,
                        glossaeEnabled = glossaeEnabled,
                        onOpenPdfTextToolsDialog = { initialTab, selectedRegion, selectedText ->
                            pdfTextToolsInitialTab = initialTab
                            pdfTextToolsInitialRegion = selectedRegion
                            pdfTextToolsInitialSelectedText = selectedText
                            showPdfTextToolsDialog = true
                        }
                    )
                } else if (isPdfDocument && isPdfEbookMode && !USE_LEGACY_PDF_REFLOW) {
                    val book = reflowBook
                    if (book == null) {
                        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (reflowError == null) CircularProgressIndicator(color = activeReaderTheme.textColor)
                                Text(
                                    text = reflowError?.let { "Couldn't make the reflowable view: $it" } ?: reflowStatus.ifBlank { "Converting the pages you're reading…" },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                                    color = activeReaderTheme.textColor,
                                    textAlign = TextAlign.Center
                                )
                                OutlinedButton(
                                    onClick = { isPdfEbookMode = false },
                                    border = BorderStroke(1.dp, activeReaderTheme.textColor),
                                    shape = RoundedCornerShape(0.dp)
                                ) { Text("Back to the PDF", fontSize = 12.sp, color = activeReaderTheme.textColor) }
                            }
                        }
                    } else {
                        val reflowLayoutMode = com.example.reader.readium.PrintPageLayoutMode.resolve(
                            paginateByPrintedPages = paginateByEpubPageEquivalentsSetting,
                            showPrintedPageNumbers = showEpubPageEquivalentsSetting,
                            viewMode = readerViewModeSetting
                        )
                        Box(modifier = Modifier.fillMaxSize()) {
                            androidx.compose.runtime.key(book.file.absolutePath, reflowLayoutMode) {
                                val reflowDoc = remember(book.file.absolutePath) { document.copy(filePath = book.file.absolutePath, fileType = "EPUB") }
                                val startHref = remember(book.file.absolutePath) { book.hrefFor(syncedPdfPageAnchor) }
                                com.example.ui.readium.ReadiumEpubReaderContent(
                                    document = reflowDoc,
                                    initialPage = 1,
                                    isUiVisible = isUiVisible,
                                    activeTheme = activeReaderTheme,
                                    publisherStylesEnabled = true,
                                    fontSizeSp = fontSizeSp,
                                    fontFamilySetting = fontFamilySetting,
                                    lineSpacingMultiplier = lineSpacingMultiplier,
                                    marginDp = marginDp,
                                    topMarginDp = topMarginDp,
                                    bottomMarginDp = bottomMarginDp,
                                    textAlignSetting = textAlignSetting,
                                    viewModeSetting = readerViewModeSetting,
                                    columnCount = readerColumnCount,
                                    paragraphStyleSetting = settings.readerParagraphStyle,
                                    paginateByEpubPageEquivalents = paginateByEpubPageEquivalentsSetting,
                                    printLayoutMode = reflowLayoutMode,
                                    glossaeEnabled = glossaeEnabled,
                                    highlights = highlights,
                                    pageTurnTrigger = pageTurnTrigger,
                                    targetPageRequest = targetPageRequest,
                                    targetHighlightRequest = targetHighlightRequest,
                                    targetPrintPageRequest = targetPrintPageRequest,
                                    jumpRequestToken = jumpRequestToken,
                                    initialHref = startHref,
                                    highlightHref = { h -> book.fileHrefFor(h.pageNumber) },
                                    onPrintPageAnchorChanged = { id ->
                                        id.removePrefix("pdfpage-").toIntOrNull()?.let { p ->
                                            if (p != syncedPdfPageAnchor) {
                                                syncedPdfPageAnchor = p.coerceIn(1, syncedPdfTotalPages.coerceAtLeast(1))
                                                activePdfPrintedPageLabel = book.labelFor(p)
                                                highlightPageNumber = p
                                                onProgressUpdate(syncedPdfPageAnchor, syncedPdfTotalPages.coerceAtLeast(1))
                                            }
                                        }
                                    },
                                    onToggleUi = { },
                                    onPageChanged = { page, total, chapterTitle ->
                                        currentActivePage = page
                                        activeTotalPages = total.coerceAtLeast(1)
                                        highlightChapterText = chapterTitle
                                    },
                                    onProgressUpdate = { _, _ -> },
                                    onActiveEpubPageEquivalentChanged = { eq, _ ->
                                        if (eq.isNotBlank()) activePdfPrintedPageLabel = eq.substringBefore('–')
                                    },
                                    onChaptersLoaded = { chaptersList = it },
                                    onSpeakSelection = { quote, pageNum ->
                                        showTtsBar = true
                                        ttsManager.speakSelection(quote, pageNum)
                                    },
                                    onRequestHighlight = { quote, _, chapterTitle, colorHex, _, _, _ ->
                                        val (sPdf, ePdf) = book.spanForQuote(quote, syncedPdfPageAnchor)
                                        highlightQuoteText = quote
                                        highlightPageNumber = sPdf
                                        highlightPrintedPageLabel = com.example.reader.PdfQuotePageSpan(sPdf, ePdf, book.labelFor(sPdf), book.labelFor(ePdf)).toStoredPrintedPageLabel()
                                        highlightChapterText = chapterTitle
                                        highlightParagraphCitation = ""
                                        highlightPageEquivalent = ""
                                        highlightEpubCfi = ""
                                        highlightColorHex = colorHex
                                        showAddHighlightDialog = true
                                    },
                                    onQuickSaveHighlight = { quote, _, chapterTitle, colorHex, _, _, _ ->
                                        val (sPdf, ePdf) = book.spanForQuote(quote, syncedPdfPageAnchor)
                                        val printed = com.example.reader.PdfQuotePageSpan(sPdf, ePdf, book.labelFor(sPdf), book.labelFor(ePdf)).toStoredPrintedPageLabel()
                                        val autoTags = com.example.reader.NoteSuggestionEngine.joinTagsList(
                                            com.example.reader.NoteSuggestionEngine.suggestTags(highlightedText = quote, chapterTitle = chapterTitle, documentTitle = document.title).take(3)
                                        )
                                        val autoTitle = com.example.reader.NoteSuggestionEngine.suggestTitles(highlightedText = quote, chapterTitle = chapterTitle, pageNumber = sPdf)
                                            .firstOrNull() ?: "Note - Page $sPdf"
                                        onAddHighlight(autoTitle, quote, "", sPdf, printed, chapterTitle, colorHex, autoTags, "", "", "")
                                        Toast.makeText(context, "Saved highlight (PDF p. $sPdf${if (printed.isNotBlank()) ", printed p. $printed" else ""})", Toast.LENGTH_SHORT).show()
                                    },
                                    onAddAsAuthor = handleAddAsAuthorSelection,
                                    onSetAsAbstract = handleSetAsAbstractSelection,
                                    onEditHighlight = { highlight -> highlightToEdit = highlight }
                                )
                            }
                            if (!book.complete && reflowStatus.isNotBlank()) {
                                Text(
                                    text = reflowStatus,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = activeReaderTheme.secondaryTextColor,
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
                                )
                            }
                        }
                    }
                } else if (isPdfDocument && isPdfEbookMode && (isParsingPdfEbook || pdfEbookDocument == null)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = Color(0xFF7C3AED))
                            Text(
                                text = "Reconstructing Reflowable Ebook View…",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = activeReaderTheme.textColor
                            )
                            Text(
                                text = "Parsing PDF page $pdfParsingProgressPage of ${pdfParsingTotalPages.coerceAtLeast(1)} • Removing repeated headers/footers & preserving layout anchors",
                                style = MaterialTheme.typography.bodySmall,
                                color = activeReaderTheme.secondaryTextColor,
                                textAlign = TextAlign.Center
                            )
                            OutlinedButton(
                                onClick = { isPdfEbookMode = false },
                                border = BorderStroke(1.dp, activeReaderTheme.dividerColor),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("Return to Original PDF View", fontSize = 12.sp, color = activeReaderTheme.textColor)
                            }
                        }
                    }
                } else if (!isPdfDocument && !isDaisyDocument && useReadiumEngine) {
                    // Recreate the Readium reader when the printed-page layout changes: the layout is applied
                    // while the EPUB's HTML is served, so the publication must be reopened.
                    val readiumPrintLayoutMode = com.example.reader.readium.PrintPageLayoutMode.resolve(
                        paginateByPrintedPages = paginateByEpubPageEquivalentsSetting,
                        showPrintedPageNumbers = showEpubPageEquivalentsSetting,
                        viewMode = readerViewModeSetting
                    )
                    androidx.compose.runtime.key(readiumPrintLayoutMode) {
                        com.example.ui.readium.ReadiumEpubReaderContent(
                            document = document,
                            initialPage = initialPage,
                            isUiVisible = isUiVisible,
                            activeTheme = activeReaderTheme,
                            publisherStylesEnabled = publisherStylesEnabled,
                            fontSizeSp = fontSizeSp,
                            fontFamilySetting = fontFamilySetting,
                            lineSpacingMultiplier = lineSpacingMultiplier,
                            marginDp = marginDp,
                            topMarginDp = topMarginDp,
                            bottomMarginDp = bottomMarginDp,
                            textAlignSetting = textAlignSetting,
                            viewModeSetting = readerViewModeSetting,
                            columnCount = readerColumnCount,
                            paragraphStyleSetting = settings.readerParagraphStyle,
                            paginateByEpubPageEquivalents = paginateByEpubPageEquivalentsSetting,
                            printLayoutMode = readiumPrintLayoutMode,
                            glossaeEnabled = glossaeEnabled,
                            highlights = highlights,
                            pageTurnTrigger = pageTurnTrigger,
                            targetPageRequest = targetPageRequest,
                            targetHighlightRequest = targetHighlightRequest,
                            targetPrintPageRequest = targetPrintPageRequest,
                            jumpRequestToken = jumpRequestToken,
                            onToggleUi = { isUiVisible = !isUiVisible },
                            onPageChanged = { page, total, chapterTitle ->
                                currentActivePage = page
                                activeTotalPages = total.coerceAtLeast(1)
                                highlightPageNumber = page
                                highlightChapterText = chapterTitle
                                onProgressUpdate(page, total)
                            },
                            onProgressUpdate = { page, total ->
                                onProgressUpdate(page, total)
                            },
                            onActiveEpubCfiChanged = { cfi ->
                                highlightEpubCfi = cfi
                            },
                            onActiveEpubPageEquivalentChanged = { eq, has ->
                                activeEpubPageEquivalentLabel = eq
                                activeEpubHasPageListMetadata = has
                            },
                            onChaptersLoaded = { chaptersList = it },
                            onFixedLayoutDetected = { fxl ->
                                isReadiumFixedLayout = fxl
                            },
                            onFallbackToClassic = {
                                useReadiumEngine = false
                            },
                            onSpeakSelection = { quote, pageNum ->
                                showTtsBar = true
                                ttsManager.speakSelection(quote, pageNum)
                            },
                            onRequestHighlight = { quote, page, chapterTitle, colorHex, paragraphCitation, pageEquivalent, epubCfi ->
                                highlightQuoteText = quote
                                highlightPageNumber = page
                                highlightPrintedPageLabel = if (pageEquivalent.isNotBlank()) pageEquivalent else ""
                                highlightChapterText = chapterTitle
                                highlightParagraphCitation = paragraphCitation
                                highlightPageEquivalent = pageEquivalent
                                highlightEpubCfi = epubCfi
                                highlightColorHex = colorHex
                                showAddHighlightDialog = true
                            },
                            onQuickSaveHighlight = { quote, page, chapterTitle, colorHex, paragraphCitation, pageEquivalent, epubCfi ->
                                val autoTags = com.example.reader.NoteSuggestionEngine.joinTagsList(
                                    com.example.reader.NoteSuggestionEngine.suggestTags(
                                        highlightedText = quote,
                                        chapterTitle = chapterTitle,
                                        documentTitle = document.title
                                    ).take(3)
                                )
                                val autoTitle = com.example.reader.NoteSuggestionEngine.suggestTitles(
                                    highlightedText = quote,
                                    chapterTitle = chapterTitle,
                                    pageNumber = page
                                ).firstOrNull() ?: "Note - Page $page"
                                val prLabel = if (pageEquivalent.isNotBlank()) pageEquivalent else ""
                                onAddHighlight(autoTitle, quote, "", page, prLabel, chapterTitle, colorHex, autoTags, paragraphCitation, pageEquivalent, epubCfi)
                                val citeDetail = if (pageEquivalent.isNotBlank()) "$paragraphCitation • p. eq. $pageEquivalent" else paragraphCitation
                                Toast.makeText(context, "Saved highlight ($citeDetail)!", Toast.LENGTH_SHORT).show()
                            },
                            onAddAsAuthor = handleAddAsAuthorSelection,
                            onSetAsAbstract = handleSetAsAbstractSelection,
                            onEditHighlight = { highlight ->
                                highlightToEdit = highlight
                            }
                        )
                    }
                } else {
                    EpubReaderPagerContent(
                        document = document,
                        initialPage = if (isPdfDocument) syncedPdfPageAnchor else initialPage,
                        savedReadingProgress = document.readingProgress,
                        isUiVisible = isUiVisible,
                        activeTheme = activeReaderTheme,
                        fontSizeSp = fontSizeSp,
                        textAlignSetting = textAlignSetting,
                        marginDp = marginDp,
                        lineSpacingMultiplier = lineSpacingMultiplier,
                        fontFamilySetting = fontFamilySetting,
                        viewModeSetting = readerViewModeSetting,
                        pageTurnAnimation = pageTurnAnimationSetting,
                        volumeKeyAnimation = volumeKeyAnimationSetting,
                        showEpubPageEquivalents = showEpubPageEquivalentsSetting,
                        paginateByEpubPageEquivalents = paginateByEpubPageEquivalentsSetting,
                        highlights = highlights,
                        pageTurnTrigger = pageTurnTrigger,
                        targetPageRequest = targetPageRequest,
                        targetHighlightRequest = targetHighlightRequest,
                        jumpRequestToken = jumpRequestToken,
                        preloadedEpubBook = if (isDaisyDocument) daisyEpubBook else pdfEbookDocument?.epubBook,
                        pdfEbookDocument = if (isPdfDocument) pdfEbookDocument else null,
                        pdfRendererForSnapshots = if (isPdfDocument) sharedPdfRenderer else null,
                        onSwitchToOriginalPdfPage = if (isPdfDocument) {
                            { targetPdfPage ->
                                syncedPdfPageAnchor = targetPdfPage.coerceIn(1, syncedPdfTotalPages.coerceAtLeast(1))
                                targetPageRequest = (syncedPdfPageAnchor - 1).coerceAtLeast(0)
                                isPdfEbookMode = false
                            }
                        } else null,
                        onToggleUi = { if (!isPdfDocument) isUiVisible = !isUiVisible },
                        onChaptersLoaded = { chaptersList = it },
                        onLandmarksLoaded = { landmarksList = it },
                        onPagesPaginated = { paginatedList ->
                            activeEpubPages = paginatedList
                            if (isDaisyDocument) {
                                val currentIdx = daisyBookIndex
                                if (currentIdx != null && paginatedList.isNotEmpty()) {
                                    coroutineScope.launch(Dispatchers.Default) {
                                        val updatedIdx = currentIdx.withResolvedPageNumbers(paginatedList)
                                        withContext(Dispatchers.Main) {
                                            daisyBookIndex = updatedIdx
                                            daisyAudioManager.attachBookIndex(updatedIdx)
                                        }
                                    }
                                }
                            }
                        },
                        onPageChanged = { page, total, chapterTitle ->
                            currentActivePage = page
                            activeTotalPages = total.coerceAtLeast(1)
                            if (!isPdfDocument) {
                                highlightPageNumber = page
                                highlightChapterText = chapterTitle
                                onProgressUpdate(page, total)
                            } else {
                                highlightChapterText = chapterTitle
                            }
                        },
                        onActiveEpubPageEquivalentChanged = { eqLabel, hasPageList ->
                            activeEpubPageEquivalentLabel = eqLabel
                            activeEpubHasPageListMetadata = hasPageList
                        },
                        onActiveReflowPageChanged = { reflowPage ->
                            activeReflowPageMeta = reflowPage
                            syncedPdfPageAnchor = reflowPage.startPdfPage.coerceIn(1, syncedPdfTotalPages.coerceAtLeast(1))
                            activePdfPrintedPageLabel = reflowPage.startPrintedPage
                            highlightPageNumber = reflowPage.startPdfPage
                            onProgressUpdate(syncedPdfPageAnchor, syncedPdfTotalPages.coerceAtLeast(1))
                        },
                        onActivePageTextChanged = { pageText ->
                            activePageTextForTts = pageText
                        },
                        onSpeakSelection = { quote, pageNum ->
                            if (isDaisyDocument && daisyAudioState.hasRecordedAudio && !daisyAudioState.useTtsFallback) {
                                showDaisyAudioBar = true
                                daisyAudioManager.seekToPageOrAnchor(
                                    pageNumber = pageNum,
                                    textSnippet = quote,
                                    autoPlay = true
                                )
                            } else {
                                showTtsBar = true
                                ttsManager.speakSelection(quote, pageNum)
                            }
                        },
                        onRequestHighlight = { quote, page, chapterTitle, colorHex, paragraphCitation, pageEquivalent, epubCfi ->
                            if (isPdfDocument && pdfEbookDocument != null) {
                                val fallbackPdf = activeReflowPageMeta?.startPdfPage ?: syncedPdfPageAnchor
                                val span = pdfEbookDocument!!.resolvePageSpanForQuote(quote, fallbackPdf)
                                highlightQuoteText = quote
                                highlightPageNumber = span.startPdfPage
                                highlightPrintedPageLabel = span.toStoredPrintedPageLabel()
                                highlightChapterText = chapterTitle
                                highlightParagraphCitation = paragraphCitation
                                highlightPageEquivalent = pageEquivalent
                                highlightEpubCfi = ""
                                highlightColorHex = colorHex
                                showAddHighlightDialog = true
                            } else {
                                highlightQuoteText = quote
                                highlightPageNumber = page
                                highlightPrintedPageLabel = ""
                                highlightChapterText = chapterTitle
                                highlightParagraphCitation = paragraphCitation
                                highlightPageEquivalent = pageEquivalent
                                highlightEpubCfi = epubCfi
                                highlightColorHex = colorHex
                                showAddHighlightDialog = true
                            }
                        },
                        onQuickSaveHighlight = { quote, page, chapterTitle, colorHex, paragraphCitation, pageEquivalent, epubCfi ->
                            val autoTags = com.example.reader.NoteSuggestionEngine.joinTagsList(
                                com.example.reader.NoteSuggestionEngine.suggestTags(
                                    highlightedText = quote,
                                    chapterTitle = chapterTitle,
                                    documentTitle = document.title
                                ).take(3)
                            )
                            if (isPdfDocument && pdfEbookDocument != null) {
                                val fallbackPdf = activeReflowPageMeta?.startPdfPage ?: syncedPdfPageAnchor
                                val span = pdfEbookDocument!!.resolvePageSpanForQuote(quote, fallbackPdf)
                                val resolvedPdfPage = span.startPdfPage
                                val storedPrinted = span.toStoredPrintedPageLabel()
                                val autoTitle = com.example.reader.NoteSuggestionEngine.suggestTitles(
                                    highlightedText = quote,
                                    chapterTitle = chapterTitle,
                                    pageNumber = resolvedPdfPage
                                ).firstOrNull() ?: "Note - PDF page $resolvedPdfPage"
                                onAddHighlight(autoTitle, quote, "", resolvedPdfPage, storedPrinted, chapterTitle, colorHex, autoTags, paragraphCitation, pageEquivalent, "")
                                Toast.makeText(context, "Saved highlight (${span.formatHumanReadableSummary()})!", Toast.LENGTH_SHORT).show()
                            } else {
                                val autoTitle = com.example.reader.NoteSuggestionEngine.suggestTitles(
                                    highlightedText = quote,
                                    chapterTitle = chapterTitle,
                                    pageNumber = page
                                ).firstOrNull() ?: "Note - Page $page"
                                onAddHighlight(autoTitle, quote, "", page, "", chapterTitle, colorHex, autoTags, paragraphCitation, pageEquivalent, epubCfi)
                                val citeDetail = if (pageEquivalent.isNotBlank()) "$paragraphCitation • p. eq. $pageEquivalent" else paragraphCitation
                                Toast.makeText(context, "Saved highlight ($citeDetail)!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onAddAsAuthor = handleAddAsAuthorSelection,
                        onSetAsAbstract = handleSetAsAbstractSelection,
                        onEditHighlight = { highlight ->
                            highlightToEdit = highlight
                        },
                        onFootnoteClick = { num, text ->
                            activeFootnoteEntry = null
                            activeFootnote = Pair(num, text)
                        },
                        onFootnoteEntrySelected = { entry ->
                            activeFootnoteEntry = entry
                            activeFootnote = Pair(entry.displayMarker, entry.noteText)
                        },
                        activeDaisyFileKey = if (isDaisyDocument && daisyAudioState.isAudioModeEnabled) daisyAudioState.activeFileKey else "",
                        activeDaisyAnchorId = if (isDaisyDocument && daisyAudioState.isAudioModeEnabled) daisyAudioState.activeAnchorId else "",
                        activeDaisyTextSnippet = if (isDaisyDocument && daisyAudioState.isAudioModeEnabled) daisyAudioState.activeTextSnippet else "",
                        isDaisyAutoScrollEnabled = daisyAudioState.autoTurnPages,
                        onDaisyPhraseTapped = if (isDaisyDocument) {
                            { anchorId, textSnippet ->
                                if (daisyAudioState.isAudioModeEnabled) {
                                    daisyAudioManager.seekToPageOrAnchor(
                                        pageNumber = currentActivePage,
                                        anchorIds = if (anchorId.isNotBlank()) listOf(anchorId) else emptyList(),
                                        textSnippet = textSnippet,
                                        autoPlay = daisyAudioState.isPlaying
                                    )
                                } else {
                                    isUiVisible = !isUiVisible
                                }
                            }
                        } else null,
                        paragraphStyleSetting = if (!isPdfDocument && publisherStylesEnabled) "" else settings.readerParagraphStyle,
                        columnCount = readerColumnCount,
                        topMarginDp = topMarginDp,
                        bottomMarginDp = bottomMarginDp
                    )
                }

                // Floating DAISY 2.02 / DAISY 3 Synchronized Audio & SMIL Playback Controller Card
                AnimatedVisibility(
                    visible = isDaisyDocument && showDaisyAudioBar,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier
                        .align(if (isDaisyAudioMinimized) Alignment.BottomEnd else Alignment.BottomCenter)
                        .padding(
                            horizontal = 14.dp,
                            vertical = if (isUiVisible) 52.dp else 14.dp
                        )
                ) {
                    DaisyPlaybackControlBar(
                        daisyState = daisyAudioState,
                        activeTheme = activeReaderTheme,
                        isMinimized = isDaisyAudioMinimized,
                        onToggleMinimized = { isDaisyAudioMinimized = !isDaisyAudioMinimized },
                        onTogglePlayPause = { daisyAudioManager.togglePlayPause() },
                        onPreviousPhrase = { daisyAudioManager.previousPhrase() },
                        onNextPhrase = { daisyAudioManager.nextPhrase() },
                        onSeekRelativeMs = { deltaMs -> daisyAudioManager.seekRelativeMs(deltaMs) },
                        onSeekGlobalMs = { targetMs -> daisyAudioManager.seekToGlobalPositionMs(targetMs) },
                        onChangePlaybackSpeed = { speed -> daisyAudioManager.setPlaybackSpeed(speed) },
                        onSelectAudioMode = { audioEnabled, useTtsFallback ->
                            daisyAudioManager.setAudioModeEnabled(audioEnabled)
                            if (audioEnabled) {
                                daisyAudioManager.setUseTtsFallback(useTtsFallback)
                            }
                        },
                        onToggleAutoTurnPages = { enabled -> daisyAudioManager.setAutoTurnPages(enabled) },
                        onCloseBar = {
                            daisyAudioManager.pause()
                            showDaisyAudioBar = false
                        }
                    )
                }

                // Floating Offline Male British English & Natural Norwegian TTS Controller Bar (supports Full Card & Minimized Corner Pill)
                AnimatedVisibility(
                    visible = showTtsBar,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier
                        .align(if (isTtsMinimized) Alignment.BottomEnd else Alignment.BottomCenter)
                        .padding(
                            horizontal = 14.dp,
                            vertical = if (isUiVisible) 52.dp else 14.dp
                        )
                ) {
                    KOReaderTtsControllerCard(
                        ttsState = ttsState,
                        activeTheme = activeReaderTheme,
                        isMinimized = isTtsMinimized,
                        onToggleMinimized = {
                            isTtsMinimized = !isTtsMinimized
                            persistFormatting()
                        },
                        onToggleLanguage = { lang ->
                            ttsManager.setLanguage(lang)
                            persistFormatting()
                        },
                        onTogglePlayPause = {
                            ttsManager.togglePlayPause(activePageTextForTts, currentActivePage)
                        },
                        onPreviousSentence = {
                            ttsManager.previousSentence()
                        },
                        onNextSentence = {
                            ttsManager.nextSentence()
                        },
                        onStop = {
                            ttsManager.stop()
                        },
                        onChangeSpeechRate = { newRate ->
                            ttsManager.setSpeechRate(newRate)
                            persistFormatting()
                        },
                        onOpenVoiceSettings = {
                            showTtsSettingsDialog = true
                        },
                        onCloseBar = {
                            ttsManager.stop()
                            showTtsBar = false
                        }
                    )
                }
            
                // E-ink: periodic clean refresh while turning pages, and after Glossae is toggled.
                EinkRefreshOverlay(pageKey = currentActivePage, forceKey = glossaeEnabled)
            }
        }
    }

    // KOReader Offline Male British English & Natural Norwegian TTS Settings Dialog
    if (showTtsSettingsDialog) {
        KOReaderTtsSettingsDialog(
            ttsState = ttsState,
            onLanguageChange = { lang ->
                ttsManager.setLanguage(lang)
                persistFormatting()
            },
            onSpeechRateChange = { rate ->
                ttsManager.setSpeechRate(rate)
                persistFormatting()
            },
            onPitchChange = { pitch ->
                ttsManager.setPitch(pitch)
                persistFormatting()
            },
            onAutoAdvanceChange = { enabled ->
                ttsManager.setAutoAdvancePage(enabled)
                persistFormatting()
            },
            onSelectVoice = { voiceId ->
                ttsManager.selectVoice(voiceId)
                persistFormatting()
            },
            onInstallSystemVoices = {
                ttsManager.openSystemTtsVoiceInstaller()
            },
            onResetBritishMaleDefault = {
                ttsManager.setSpeechRate(1.05f)
                ttsManager.setPitch(0.92f)
                ttsManager.selectVoice("")
                persistFormatting()
            },
            onDismiss = { showTtsSettingsDialog = false }
        )
    }

    // KOReader Quick Scrubber Dialog
    if (showQuickScrubberDialog) {
        val currentEpubBook = if (isDaisyDocument) daisyEpubBook else pdfEbookDocument?.epubBook
        KOReaderQuickScrubberDialog(
            currentPage = currentActivePage,
            totalPages = activeTotalPages.coerceAtLeast(1),
            chapterTitle = activeChapter?.title ?: "",
            onDismiss = { showQuickScrubberDialog = false },
            onJumpToPage = { targetPage ->
                targetHighlightRequest = null
                targetPageRequest = (targetPage - 1).coerceAtLeast(0)
                jumpRequestToken++
            },
            epubBook = currentEpubBook,
            epubPages = activeEpubPages,
            hasPageList = currentEpubBook?.hasPageList == true || activeEpubHasPageListMetadata || activeEpubPages.any { it.pageEquivalentLabel.isNotBlank() },
            totalStableLocations = currentEpubBook?.totalStableLocations ?: activeEpubPages.maxOfOrNull { it.endStableLocation } ?: 1
        )
    }

    // Recording mode: "● REC 12:34" with pause and stop, over the book while recording.
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = if (isUiVisible) 96.dp else 8.dp, end = 12.dp), contentAlignment = Alignment.TopEnd) {
        RecordingBadge()
    }

    // "Go to printed page" action exposed when Quick Scrubber is open and publication has page list metadata
    if (showQuickScrubberDialog && activeEpubHasPageListMetadata) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 28.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                color = Color.Black,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color.White),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .clickable {
                        showQuickScrubberDialog = false
                        showGoToPrintPageDialog = true
                    }
                    .testTag("scrubber_go_to_printed_page_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MenuBook,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Go to printed page",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }

    if (showGoToPrintPageDialog) {
        var printPageInput by remember { mutableStateOf("") }
        var printPageError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showGoToPrintPageDialog = false },
            title = {
                Text(
                    text = "Go to printed page",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Enter print page number or label (e.g. 20, xiv):",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    OutlinedTextField(
                        value = printPageInput,
                        onValueChange = {
                            printPageInput = it
                            printPageError = null
                        },
                        singleLine = true,
                        placeholder = { Text("e.g. 20", fontFamily = FontFamily.Monospace) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("go_to_print_page_input")
                    )
                    if (printPageError != null) {
                        Text(
                            text = printPageError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = printPageInput.trim()
                        if (trimmed.isNotBlank()) {
                            targetHighlightRequest = null
                            targetPrintPageRequest = trimmed
                            jumpRequestToken++
                            showGoToPrintPageDialog = false
                        } else {
                            printPageError = "Page cannot be empty."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.testTag("confirm_go_to_print_page_button")
                ) {
                    Text("Go", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showGoToPrintPageDialog = false },
                    shape = RoundedCornerShape(0.dp),
                    border = BorderStroke(1.dp, Color.Black)
                ) {
                    Text("Cancel", fontFamily = FontFamily.Monospace, color = Color.Black)
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier
                .border(2.dp, Color.Black)
                .testTag("go_to_print_page_dialog")
        )
    }

    // KOReader Book Information Dialog
    if (showBookInfoDialog) {
        KOReaderBookInfoDialog(
            document = effectiveDocument,
            currentPage = currentActivePage,
            totalPages = activeTotalPages.coerceAtLeast(1),
            highlightsCount = highlights.size,
            onDismiss = { showBookInfoDialog = false },
            onNavigateBackToLibrary = {
                onProgressUpdate(currentActivePage, activeTotalPages.coerceAtLeast(1))
                persistFormatting()
                onNavigateBack()
            },
            onToggleWidgetPin = onToggleWidgetPin
        )
    }

    // KOReader Obsidian Annotations Hub Dialog
    if (showObsidianHubDialog) {
        KOReaderObsidianHubDialog(
            document = effectiveDocument,
            highlights = highlights,
            settings = settings,
            onDismiss = { showObsidianHubDialog = false },
            onExportObsidianMarkdown = onExportObsidianMarkdown,
            onCopyObsidianMarkdown = onCopyObsidianMarkdown,
            onSyncToVault = onSyncToObsidianVault,
            onExportZipVault = onExportVaultZip,
            onOpenHighlightPage = { page ->
                targetHighlightRequest = null
                targetPageRequest = (page - 1).coerceAtLeast(0)
                jumpRequestToken++
            },
            onOpenHighlight = { h ->
                targetHighlightRequest = h
                targetPageRequest = (h.pageNumber - 1).coerceAtLeast(0)
                jumpRequestToken++
            },
            onEditHighlight = { h ->
                highlightToEdit = h
            },
            onDeleteHighlight = onDeleteHighlight
        )
    }

    // KOReader Tools & Settings Dialog
    if (showToolsDialog) {
        KOReaderToolsDialog(
            settings = settings,
            onSaveSettings = onSaveSettings,
            onDismiss = { showToolsDialog = false }
        )
    }

    // PDF Text Tools, Diagnostics, Visual Comparison, Repair & OCR Controls Dialog
    if (showPdfTextToolsDialog && isPdfDocument) {
        PdfTextToolsDialog(
            documentTitle = document.title,
            currentPageNumber = currentActivePage.coerceAtLeast(1),
            totalPages = syncedPdfTotalPages.coerceAtLeast(1),
            renderer = sharedPdfRenderer,
            currentMode = pdfExtractionMode,
            currentPageOverrides = pdfPageExtractionOverrides,
            currentOcrLanguage = pdfOcrLanguage,
            initialSelectedRegion = pdfTextToolsInitialRegion,
            initialSelectedText = pdfTextToolsInitialSelectedText,
            initialTabIndex = pdfTextToolsInitialTab,
            onApplyExtractionConfiguration = { newMode, newOverrides, newOcrLang ->
                pdfExtractionMode = newMode
                pdfPageExtractionOverrides = newOverrides
                pdfOcrLanguage = newOcrLang
                val overridesJson = com.example.reader.PdfPageDiagnosticsAndOcrEngine.serializePageOverridesJson(newOverrides)
                sharedPdfRenderer?.updateExtractionConfiguration(newMode, newOverrides, newOcrLang)
                onUpdatePdfTextExtractionConfig(newMode.id, overridesJson, newOcrLang)
                pdfEbookDocument = null
                pdfExtractionConfigVersion++
            },
            onDismiss = { showPdfTextToolsDialog = false }
        )
    }

    // Footnote Shortcut Dialog
    if (activeFootnote != null) {
        val (fnNum, fnText) = activeFootnote!!
        val fnEntry = activeFootnoteEntry
        val hasBackReference = fnEntry != null && (
            fnEntry.sourceContextSnippet.isNotBlank() ||
                fnEntry.sourceChapterTitle.isNotBlank() ||
                fnEntry.sourceFileKey.isNotBlank() ||
                fnEntry.backlinkAnchorId.isNotBlank()
            )
        FootnotePopup(
            footnoteNumber = fnNum,
            footnoteText = fnText,
            footnoteEntry = fnEntry,
            onJumpToReference = if (hasBackReference && fnEntry != null) {
                {
                    val cleanSnippet = fnEntry.sourceContextSnippet
                        .trim()
                        .removePrefix("…")
                        .removeSuffix("…")
                        .removePrefix("...")
                        .removeSuffix("...")
                        .trim()
                    targetHighlightRequest = HighlightEntity(
                        documentId = document.id,
                        title = "Footnote Reference [${fnEntry.displayMarker}]",
                        highlightedText = cleanSnippet.ifBlank { "[${fnEntry.displayMarker}]" },
                        userNotes = fnEntry.backlinkAnchorId.ifBlank { fnEntry.sourceAnchorId },
                        pageNumber = currentActivePage.coerceAtLeast(1),
                        chapterTitle = fnEntry.sourceChapterTitle,
                        paragraphCitation = fnEntry.sourceFileKey,
                        colorHex = "#FFEB3B"
                    )
                    jumpRequestToken++
                }
            } else null,
            onDismiss = {
                activeFootnote = null
                activeFootnoteEntry = null
            }
        )
    }

    // Modal Bottom Sheet: Typography & Formatting Options
    if (showFormattingSheet) {
        ReaderFormattingBottomSheet(
            document = document,
            isPdfDocument = isPdfDocument,
            isPdfEbookMode = isPdfEbookMode,
            pdfExtractionMode = pdfExtractionMode,
            activeEpubHasPageListMetadata = activeEpubHasPageListMetadata,
            showEpubPageEquivalentsSetting = showEpubPageEquivalentsSetting,
            paginateByEpubPageEquivalentsSetting = paginateByEpubPageEquivalentsSetting,
            readerViewModeSetting = readerViewModeSetting,
            pageTurnAnimationSetting = pageTurnAnimationSetting,
            volumeKeyAnimationSetting = volumeKeyAnimationSetting,
            readerThemeSetting = readerThemeSetting,
            fontSizeSp = fontSizeSp,
            marginDp = marginDp,
            textAlignSetting = textAlignSetting,
            lineSpacingMultiplier = lineSpacingMultiplier,
            fontFamilySetting = fontFamilySetting,
            publisherStylesEnabled = publisherStylesEnabled,
            onPublisherStylesEnabledChange = {
                publisherStylesEnabled = it
                persistFormatting()
            },
            isFixedLayoutBook = isReadiumFixedLayout,
            useReadiumEngine = useReadiumEngine,
            onUseReadiumEngineChange = { useReadiumEngine = it },
            columnCount = readerColumnCount,
            onColumnCountChange = {
                readerColumnCount = it.coerceIn(1, 2)
                persistFormatting()
            },
            topMarginDp = topMarginDp,
            onTopMarginDpChange = { topMarginDp = it.coerceIn(0, 160) },
            bottomMarginDp = bottomMarginDp,
            onBottomMarginDpChange = { bottomMarginDp = it.coerceIn(0, 160) },
            onDismiss = {
                persistFormatting()
                showFormattingSheet = false
            },
            onSelectOriginalPdfMode = {
                targetHighlightRequest = null
                targetPageRequest = null
                currentActivePage = syncedPdfPageAnchor.coerceIn(1, syncedPdfTotalPages.coerceAtLeast(1))
                activeTotalPages = syncedPdfTotalPages.coerceAtLeast(1)
                isPdfEbookMode = false
            },
            onSelectPdfEbookMode = {
                targetHighlightRequest = null
                targetPageRequest = null
                isPdfEbookMode = true
            },
            onOpenPdfCropFromSheet = {
                persistFormatting()
                showFormattingSheet = false
                showPdfCropDialog = true
            },
            onUpdatePdfCrop = onUpdatePdfCrop,
            onSelectPdfExtractionMode = { modeOpt ->
                pdfExtractionMode = modeOpt
                val overridesJson = com.example.reader.PdfPageDiagnosticsAndOcrEngine.serializePageOverridesJson(pdfPageExtractionOverrides)
                sharedPdfRenderer?.updateExtractionConfiguration(modeOpt, pdfPageExtractionOverrides, pdfOcrLanguage)
                onUpdatePdfTextExtractionConfig(modeOpt.id, overridesJson, pdfOcrLanguage)
                pdfEbookDocument = null
                pdfExtractionConfigVersion++
            },
            onOpenPdfTextToolsTab = { tabIndex ->
                persistFormatting()
                showFormattingSheet = false
                pdfTextToolsInitialTab = tabIndex
                pdfTextToolsInitialRegion = android.graphics.RectF(0f, 0f, 1f, 1f)
                pdfTextToolsInitialSelectedText = ""
                showPdfTextToolsDialog = true
            },
            onShowEpubPageEquivalentsChange = {
                showEpubPageEquivalentsSetting = it
                if (!it) paginateByEpubPageEquivalentsSetting = false
                persistFormatting()
            },
            onPaginateByEpubPageEquivalentsChange = {
                paginateByEpubPageEquivalentsSetting = it
                persistFormatting()
            },
            onReaderViewModeChange = {
                readerViewModeSetting = it
                persistFormatting()
            },
            onPageTurnAnimationChange = {
                pageTurnAnimationSetting = it
                persistFormatting()
            },
            onVolumeKeyAnimationChange = {
                volumeKeyAnimationSetting = it
                persistFormatting()
            },
            onReaderThemeChange = {
                readerThemeSetting = it
                persistFormatting()
            },
            onFontSizeChange = { fontSizeSp = it },
            onMarginDpChange = { marginDp = it },
            onTextAlignChange = {
                textAlignSetting = it
                persistFormatting()
            },
            onLineSpacingChange = {
                lineSpacingMultiplier = it
                persistFormatting()
            },
            onFontFamilyChange = {
                fontFamilySetting = it
                persistFormatting()
            }
        )
    }

    // Modal Bottom Sheet: Highlights List & Export Actions
    if (showHighlightsSheet) {
        ReaderHighlightsBottomSheet(
            document = document,
            highlights = highlights,
            sheetState = sheetState,
            onDismiss = { showHighlightsSheet = false },
            onOpenExportPreview = {
                showHighlightsSheet = false
                showExportPreviewDialog = true
            },
            onSelectHighlight = { h ->
                targetHighlightRequest = h
                targetPageRequest = (h.pageNumber - 1).coerceAtLeast(0)
                jumpRequestToken++
                showHighlightsSheet = false
            },
            onEditHighlight = { h -> highlightToEdit = h },
            onDeleteHighlight = onDeleteHighlight
        )
    }

    // Add Highlight & Note Dialog
    if (showAddHighlightDialog) {
        CreateHighlightDialog(
            initialQuote = highlightQuoteText,
            initialPage = highlightPageNumber,
            initialPrintedPageLabel = highlightPrintedPageLabel,
            initialChapter = highlightChapterText,
            initialParagraphCitation = highlightParagraphCitation,
            initialPageEquivalent = highlightPageEquivalent,
            initialEpubCfi = highlightEpubCfi,
            initialColorHex = highlightColorHex,
            documentTitle = document.title,
            isPdfDocument = document.fileType.equals("PDF", ignoreCase = true),
            onDismiss = { showAddHighlightDialog = false },
            onSave = { title, quote, notes, page, printedPage, chapter, colorHex, tags, paragraphCitation, pageEquivalent, epubCfi ->
                onAddHighlight(title, quote, notes, page, printedPage, chapter, colorHex, tags, paragraphCitation, pageEquivalent, epubCfi)
                showAddHighlightDialog = false
            }
        )
    }

    // Edit Existing Highlight Dialog
    val editingHighlight = highlightToEdit
    if (editingHighlight != null) {
        CreateHighlightDialog(
            initialTitle = editingHighlight.title,
            initialQuote = editingHighlight.highlightedText,
            initialNotes = editingHighlight.userNotes,
            initialPage = editingHighlight.pageNumber,
            initialPrintedPageLabel = editingHighlight.printedPageLabel,
            initialChapter = editingHighlight.chapterTitle,
            initialParagraphCitation = editingHighlight.paragraphCitation,
            initialPageEquivalent = editingHighlight.pageEquivalent,
            initialEpubCfi = editingHighlight.epubCfi,
            initialColorHex = editingHighlight.colorHex,
            initialTags = editingHighlight.tags,
            documentTitle = document.title,
            isPdfDocument = document.fileType.equals("PDF", ignoreCase = true),
            dialogTitle = "Edit Highlight & Note",
            saveButtonLabel = "Save Changes",
            onDismiss = { highlightToEdit = null },
            onSave = { title, quote, notes, page, printedPage, chapter, colorHex, tags, paragraphCitation, pageEquivalent, epubCfi ->
                onUpdateHighlight(
                    editingHighlight.copy(
                        title = title.trim().ifBlank { "Note - Page $page" },
                        highlightedText = quote.trim(),
                        userNotes = notes.trim(),
                        pageNumber = page,
                        printedPageLabel = printedPage.trim(),
                        chapterTitle = chapter.trim(),
                        paragraphCitation = paragraphCitation.trim(),
                        pageEquivalent = pageEquivalent.trim(),
                        epubCfi = epubCfi.trim(),
                        colorHex = colorHex,
                        tags = tags
                    )
                )
                highlightToEdit = null
                Toast.makeText(context, "Highlight updated!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Add Chapter Marker Dialog
    if (showAddChapterDialog) {
        AddChapterMarkerDialog(
            nextChapterIndex = chaptersList.size + 1,
            currentActivePage = currentActivePage,
            onDismiss = { showAddChapterDialog = false },
            onAddChapter = { newChapter ->
                val updated = (chaptersList + newChapter).sortedBy { it.pageNumber }
                chaptersList = updated
                // Store only the user's own markers; the PDF's bookmarks are read fresh each time.
                onSaveChapters(PdfChapterHelper.serializeUserMarkers(PdfChapterHelper.userMarkers(document.chaptersJson) + newChapter))
                showAddChapterDialog = false
            }
        )
    }

    // Export Preview Dialog
    if (showExportPreviewDialog) {
        ExportPreviewDialog(
            document = effectiveDocument,
            highlights = highlights,
            settings = settings,
            onDismiss = { showExportPreviewDialog = false },
            onExportVaultZip = onExportVaultZip,
            onExportMoc = onExportMoc,
            onExportSingleHighlight = onExportSingleHighlight,
            currentPage = currentActivePage
        )
    }

    // Add as Author Dialog (triggered from Selection Menu -> More -> Add as Author)
    val authorSelection = pendingAuthorSelectionText
    if (authorSelection != null) {
        AddAuthorFromSelectionDialog(
            selectedText = authorSelection,
            currentRawAuthor = currentDocAuthor,
            documentTitle = effectiveDocument.title,
            onDismiss = { pendingAuthorSelectionText = null },
            onConfirmAuthors = { updatedRawAuthor, addedAuthorName ->
                currentDocAuthor = updatedRawAuthor
                onUpdateDocumentAuthor(updatedRawAuthor)
                pendingAuthorSelectionText = null
                Toast.makeText(
                    context,
                    "Added \"$addedAuthorName\" to document authors!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    // Set as Abstract/Summary Dialog (triggered from Selection Menu -> More -> Set as Abstract/Summary)
    val abstractSelection = pendingAbstractSelectionText
    if (abstractSelection != null) {
        SetAbstractFromSelectionDialog(
            selectedPassage = abstractSelection,
            existingAbstract = abstractPriorSummarySnapshot,
            documentTitle = effectiveDocument.title,
            wasAutoSavedWhenEmpty = abstractWasAutoSavedWhenEmpty,
            onDismiss = { pendingAbstractSelectionText = null },
            onConfirmSaveAbstract = { finalAbstract ->
                currentDocSummary = finalAbstract
                onUpdateDocumentSummary(finalAbstract)
                pendingAbstractSelectionText = null
                Toast.makeText(
                    context,
                    "Saved document Abstract/Summary!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}
}
