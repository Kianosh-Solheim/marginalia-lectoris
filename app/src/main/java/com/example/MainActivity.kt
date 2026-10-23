package com.example

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.DocumentEntity
import com.example.ui.AddDocumentDialog
import com.example.ui.AppNavSection
import com.example.ui.HighlightsListScreen
import com.example.ui.HomeScreen
import com.example.ui.FolderPartBanner
import com.example.ui.ReaderScreen
import com.example.ui.ReaderViewModel
import com.example.ui.SettingsScreen
import com.example.ui.WidgetPinnedBooksManagerDialog
import com.example.ui.theme.ReadMarkTheme
import com.example.widget.PinnedBooksWidgetProvider
import com.example.widget.ReadingPosition
import com.example.widget.ResumeTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {

    private val viewModel: ReaderViewModel by viewModels()
    private val _openWidgetManagerRequest = MutableStateFlow(false)
    val openWidgetManagerRequest = _openWidgetManagerRequest.asStateFlow()

    private val _configuringAppWidgetId = MutableStateFlow<Int?>(null)
    val configuringAppWidgetId = _configuringAppWidgetId.asStateFlow()

    private val _incomingSharedUri = MutableStateFlow<android.net.Uri?>(null)
    val incomingSharedUri = _incomingSharedUri.asStateFlow()

    private val _incomingSharedText = MutableStateFlow<String?>(null)
    val incomingSharedText = _incomingSharedText.asStateFlow()

    override fun onResume() {
        super.onResume()
        com.example.reader.FolderSync.scanSoon()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.reader.BibStore.init(applicationContext)
        com.example.reader.EditedVolumes.init(applicationContext)
        com.example.reader.FolderBooks.init(applicationContext)
        com.example.server.WebServer.loadPrefs(applicationContext)
        // Book folders ↔ app: the Markdown, RIS and BibTeX files in each folder are kept in step.
        com.example.reader.FolderSync.start(applicationContext)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }
        enableEdgeToEdge()
        setContent {
            ReadMarkTheme {
                com.example.ui.KeepScreenOnEffect(this)
                Surface(modifier = Modifier.fillMaxSize()) {
                    val openWidgetMgr by openWidgetManagerRequest.collectAsStateWithLifecycle()
                    val configWidgetId by configuringAppWidgetId.collectAsStateWithLifecycle()
                    val sharedUri by incomingSharedUri.collectAsStateWithLifecycle()
                    val sharedText by incomingSharedText.collectAsStateWithLifecycle()
                    ReadMarkApp(
                        viewModel = viewModel,
                        externalShowWidgetManager = openWidgetMgr,
                        configuringAppWidgetId = configWidgetId,
                        incomingSharedUri = sharedUri,
                        incomingSharedText = sharedText,
                        onClearSharedRequest = {
                            _incomingSharedUri.value = null
                            _incomingSharedText.value = null
                        },
                        onClearWidgetManagerRequest = {
                            _openWidgetManagerRequest.value = false
                            _configuringAppWidgetId.value = null
                        },
                        onFinishWidgetConfig = { appWidgetId ->
                            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                            setResult(Activity.RESULT_OK, resultValue)
                            _openWidgetManagerRequest.value = false
                            _configuringAppWidgetId.value = null
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val docId = intent.getLongExtra(PinnedBooksWidgetProvider.EXTRA_OPEN_DOCUMENT_ID, -1L)
        if (docId > 0L) {
            val hasResumeExtras = intent.hasExtra("extra_resume_cfi") ||
                intent.hasExtra("extra_resume_paragraph") ||
                intent.hasExtra("extra_resume_offset") ||
                intent.hasExtra("extra_resume_loc") ||
                intent.hasExtra("extra_resume_page")
            val resume = if (hasResumeExtras) {
                val cfi = intent.getStringExtra("extra_resume_cfi").orEmpty()
                val paragraph = intent.getIntExtra("extra_resume_paragraph", 0)
                val offset = intent.getIntExtra("extra_resume_offset", 0)
                val loc = intent.getIntExtra("extra_resume_loc", 0)
                val page = intent.getIntExtra("extra_resume_page", 1)
                ResumeTarget(ReadingPosition(cfi = cfi, paragraph = paragraph, offset = offset, loc = loc), page)
            } else {
                null
            }
            viewModel.openDocumentById(docId, resume)
            intent.removeExtra(PinnedBooksWidgetProvider.EXTRA_OPEN_DOCUMENT_ID)
            intent.removeExtra("extra_resume_cfi")
            intent.removeExtra("extra_resume_paragraph")
            intent.removeExtra("extra_resume_offset")
            intent.removeExtra("extra_resume_loc")
            intent.removeExtra("extra_resume_page")
            return
        }

        val isWidgetConfigAction = intent.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE ||
            intent.action == PinnedBooksWidgetProvider.ACTION_CONFIGURE_WIDGET_PINS ||
            intent.getBooleanExtra(PinnedBooksWidgetProvider.EXTRA_OPEN_WIDGET_MANAGER, false)
        if (isWidgetConfigAction) {
            val widgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                _configuringAppWidgetId.value = widgetId
            }
            _openWidgetManagerRequest.value = true
            intent.removeExtra(PinnedBooksWidgetProvider.EXTRA_OPEN_WIDGET_MANAGER)
            return
        }

        when (intent.action) {
            Intent.ACTION_SEND -> {
                val streamUri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (streamUri != null) {
                    _incomingSharedUri.value = streamUri
                    intent.removeExtra(Intent.EXTRA_STREAM)
                } else if (!extraText.isNullOrBlank()) {
                    _incomingSharedText.value = extraText
                    intent.removeExtra(Intent.EXTRA_TEXT)
                }
                intent.action = Intent.ACTION_MAIN
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                val firstUri = uris?.firstOrNull()
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                if (firstUri != null) {
                    _incomingSharedUri.value = firstUri
                    intent.removeExtra(Intent.EXTRA_STREAM)
                }
                intent.action = Intent.ACTION_MAIN
            }
            Intent.ACTION_VIEW -> {
                val dataUri = intent.data
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                if (dataUri != null) {
                    _incomingSharedUri.value = dataUri
                    intent.data = null
                }
                intent.action = Intent.ACTION_MAIN
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (viewModel.activeDocument.value != null &&
            viewModel.settings.value.volumeKeyNavigation &&
            !viewModel.isTtsCurrentlyPlaying.value
        ) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        viewModel.navigatePagePrevious()
                    }
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        viewModel.navigatePageNext()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Physical volume up/down buttons page turn navigation (bypassed when TTS is playing so volume keys adjust actual audio volume)
        if (viewModel.activeDocument.value != null &&
            viewModel.settings.value.volumeKeyNavigation &&
            !viewModel.isTtsCurrentlyPlaying.value
        ) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    viewModel.navigatePagePrevious()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    viewModel.navigatePageNext()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (viewModel.activeDocument.value != null &&
            viewModel.settings.value.volumeKeyNavigation &&
            !viewModel.isTtsCurrentlyPlaying.value
        ) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }
}

@Composable
fun ReadMarkApp(
    viewModel: ReaderViewModel,
    externalShowWidgetManager: Boolean = false,
    configuringAppWidgetId: Int? = null,
    incomingSharedUri: android.net.Uri? = null,
    incomingSharedText: String? = null,
    onClearSharedRequest: () -> Unit = {},
    onClearWidgetManagerRequest: () -> Unit = {},
    onFinishWidgetConfig: (Int) -> Unit = {}
) {
    val context = LocalContext.current

    val currentSection by viewModel.currentSection.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val documents by viewModel.allDocuments.collectAsStateWithLifecycle()
    val recentDocuments by viewModel.recentDocuments.collectAsStateWithLifecycle()
    val allHighlights by viewModel.allHighlights.collectAsStateWithLifecycle()
    val activeDocument by viewModel.activeDocument.collectAsStateWithLifecycle()
    val pendingHighlightRequest by viewModel.pendingHighlightRequest.collectAsStateWithLifecycle()
    val activeHighlights by viewModel.activeDocumentHighlights.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val everyDocument by viewModel.everyDocument.collectAsStateWithLifecycle()
    val folderVersion by com.example.reader.FolderBooks.version.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }
    var showWidgetManagerDialog by remember { mutableStateOf(false) }
    var isSettingsOpen by remember { mutableStateOf(false) }
    var isHighlightsOpen by remember { mutableStateOf(false) }
    // Edited volume whose chapter list is open (stays open behind the reader, so Back returns to it).
    var volumeDocId by remember { mutableStateOf<Long?>(null) }
    // Folder book whose chapter list is open (also stays open behind the reader).
    var folderDocId by remember { mutableStateOf<Long?>(null) }
    var addToFolderDoc by remember { mutableStateOf<com.example.data.local.DocumentEntity?>(null) }
    var creatingFolderBook by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val openFromLibrary: (com.example.data.local.DocumentEntity) -> Unit = { doc ->
        when {
            com.example.reader.FolderBooks.isFolder(doc) -> folderDocId = doc.id
            com.example.reader.EditedVolumes.isEditedVolume(context, doc) -> volumeDocId = doc.id
            else -> viewModel.openDocument(doc)
        }
    }

    when {
        activeDocument != null -> {
            val doc = activeDocument!!
            var readerPlace by remember(doc.id) { mutableStateOf(doc.currentPage to doc.totalPages) }
            val partNav = remember(doc.id, folderVersion) { com.example.reader.FolderBooks.neighbours(doc.id) }
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            androidx.compose.runtime.key(doc.id) {
            ReaderScreen(
                document = doc,
                initialPage = doc.currentPage,
                initialHighlightRequest = pendingHighlightRequest,
                onClearInitialHighlightRequest = { viewModel.clearPendingHighlightRequest() },
                highlights = activeHighlights,
                settings = settings,
                pageTurnTrigger = viewModel.pageTurnTrigger,
                onNavigateBack = { viewModel.closeDocument() },
                onProgressUpdate = { page, total ->
                    readerPlace = page to total
                    viewModel.updateReadingProgress(doc.id, page, total)
                },
                onAddHighlight = { title, quote, notes, page, printedPage, chapter, colorHex, tags, paragraphCitation, pageEquivalent, epubCfi ->
                    viewModel.addHighlight(
                        documentId = doc.id,
                        title = title,
                        highlightedText = quote,
                        userNotes = notes,
                        pageNumber = page,
                        printedPageLabel = printedPage,
                        chapterTitle = chapter,
                        colorHex = colorHex,
                        tags = tags,
                        paragraphCitation = paragraphCitation,
                        pageEquivalent = pageEquivalent,
                        epubCfi = epubCfi
                    )
                },
                onUpdateHighlight = { updatedHighlight ->
                    viewModel.updateHighlight(updatedHighlight)
                },
                onDeleteHighlight = { highlight ->
                    viewModel.deleteHighlight(highlight)
                },
                onExportVaultZip = {
                    viewModel.exportVaultZip(context, doc, activeHighlights)
                },
                onExportMoc = {
                    viewModel.exportMoc(context, doc, activeHighlights)
                },
                onExportSingleHighlight = { highlight ->
                    viewModel.exportSingleHighlight(context, highlight, doc)
                },
                onExportObsidianMarkdown = {
                    viewModel.exportUnifiedObsidianMarkdown(context, doc, activeHighlights)
                },
                onCopyObsidianMarkdown = {
                    viewModel.copyUnifiedObsidianMarkdown(context, doc, activeHighlights)
                },
                onSyncToObsidianVault = {
                    viewModel.syncToObsidianVault(context, doc, activeHighlights)
                },
                onSaveChapters = { chaptersJson ->
                    viewModel.updateDocumentChapters(doc.id, chaptersJson)
                },
                onUpdatePdfCrop = { left, top, right, bottom ->
                    viewModel.updateDocumentPdfCrop(doc.id, left, top, right, bottom)
                },
                onUpdatePdfTextExtractionConfig = { mode, overridesJson, ocrLanguage ->
                    viewModel.updateDocumentTextExtractionConfig(doc.id, mode, overridesJson, ocrLanguage)
                },
                onToggleWidgetPin = {
                    val willPin = !doc.isPinnedToWidget
                    viewModel.toggleDocumentWidgetPin(doc)
                    Toast.makeText(
                        context,
                        if (willPin) "Pinned \"${doc.title}\" to Home Screen Widget!"
                        else "Unpinned \"${doc.title}\" from Home Screen Widget",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onUpdateDocumentAuthor = { newAuthor ->
                    viewModel.updateDocumentAuthor(doc.id, newAuthor)
                },
                onUpdateDocumentSummary = { newSummary ->
                    viewModel.updateDocumentSummary(doc.id, newSummary)
                },
                onSaveSettings = { updated ->
                    viewModel.saveSettings(updated)
                },
                onTtsPlayingChanged = { isPlaying ->
                    viewModel.setTtsPlayingState(isPlaying)
                }
            )
            }
            if (partNav != null) {
                FolderPartBanner(
                    partId = doc.id,
                    atEnd = readerPlace.second > 0 && readerPlace.first >= readerPlace.second,
                    everyDocument = everyDocument,
                    onOpen = { next -> viewModel.openDocument(next) }
                )
            }
            }
        }

        isSettingsOpen -> {
            BackHandler { isSettingsOpen = false }
            SettingsScreen(
                currentSettings = settings,
                onSaveSettings = { updated ->
                    viewModel.saveSettings(updated)
                },
                onNavigateBack = { isSettingsOpen = false },
                onOpenWidgetManager = {
                    isSettingsOpen = false
                    showWidgetManagerDialog = true
                }
            )
        }

        folderDocId != null && everyDocument.any { it.id == folderDocId } -> {
            val fdoc = everyDocument.first { it.id == folderDocId }
            BackHandler { folderDocId = null }
            com.example.ui.FolderBookScreen(
                folder = fdoc,
                everyDocument = everyDocument,
                highlights = allHighlights,
                onBack = { folderDocId = null },
                onOpenPart = { part -> viewModel.openDocument(part) },
                onContinue = { viewModel.openDocument(fdoc) },
                onDelete = {
                    folderDocId = null
                    viewModel.deleteDocument(fdoc)
                }
            )
        }

        volumeDocId != null && (documents.any { it.id == volumeDocId } || recentDocuments.any { it.id == volumeDocId }) -> {
            val vdoc = (documents.firstOrNull { it.id == volumeDocId } ?: recentDocuments.first { it.id == volumeDocId })
            BackHandler { volumeDocId = null }
            com.example.ui.EditedVolumeScreen(
                document = vdoc,
                highlights = allHighlights.filter { it.documentId == vdoc.id },
                settings = settings,
                onBack = { volumeDocId = null },
                onOpenAtPage = { page -> viewModel.openDocument(vdoc, overridePage = page) },
                onContinue = { viewModel.openDocument(vdoc) },
                onNotEditedVolume = { volumeDocId = null }
            )
        }

        isHighlightsOpen -> {
            BackHandler { isHighlightsOpen = false }
            HighlightsListScreen(
                highlights = allHighlights,
                documents = everyDocument,
                settings = settings,
                onNavigateBack = { isHighlightsOpen = false },
                onOpenDocumentAtPage = { doc, page, highlight ->
                    isHighlightsOpen = false
                    viewModel.openDocument(doc, overridePage = page, targetHighlight = highlight)
                },
                onUpdateHighlight = { updatedHighlight ->
                    viewModel.updateHighlight(updatedHighlight)
                },
                onDeleteHighlight = { highlight ->
                    viewModel.deleteHighlight(highlight)
                }
            )
        }

        else -> {
            HomeScreen(
                currentSection = currentSection,
                searchQuery = searchQuery,
                documents = documents,
                recentDocuments = recentDocuments,
                allHighlights = allHighlights,
                settings = settings,
                onSelectSection = { section ->
                    viewModel.selectSection(section)
                },
                onUpdateSearchQuery = { query ->
                    viewModel.updateSearchQuery(query)
                },
                onOpenDocument = openFromLibrary,
                onOpenChapters = { doc ->
                    com.example.reader.EditedVolumes.setEnabled(context, doc, true)
                    volumeDocId = doc.id
                },
                onOpenDocumentWithResume = { doc, resume ->
                    viewModel.openDocumentById(doc.id, resume)
                },
                onOpenDocumentAtPage = { doc, page, highlight ->
                    viewModel.openDocument(doc, overridePage = page, targetHighlight = highlight)
                },
                onOpenSettings = { isSettingsOpen = true },
                onAddToFolderBook = { doc -> addToFolderDoc = doc },
                onChangeCategory = { doc, cat -> viewModel.changeCategory(doc, cat) },
                onNewFolderBook = { creatingFolderBook = true },
                onOpenAllHighlights = { isHighlightsOpen = true },
                onAddDocumentClick = { showAddDialog = true },
                onToggleBookWidgetPin = { doc ->
                    val willPin = !doc.isPinnedToWidget
                    viewModel.toggleDocumentWidgetPin(doc)
                    Toast.makeText(
                        context,
                        if (willPin) "Pinned \"${doc.title}\" to Home Screen Widget!"
                        else "Unpinned \"${doc.title}\" from Home Screen Widget",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onOpenWidgetManager = { showWidgetManagerDialog = true },
                onDeleteDocument = { doc ->
                    viewModel.deleteDocument(doc)
                    Toast.makeText(
                        context,
                        "Deleted \"${doc.title}\"",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onUpdateDocumentInfo = { doc, newTitle, newAuthor, newTags, newCoverBitmap, resetToDefaultCover ->
                    viewModel.updateDocumentInfo(
                        document = doc,
                        newTitle = newTitle,
                        newAuthor = newAuthor,
                        newTags = newTags,
                        newCoverBitmap = newCoverBitmap,
                        resetToDefaultCover = resetToDefaultCover,
                        newSummary = doc.summary
                    )
                    Toast.makeText(
                        context,
                        "Updated info for \"$newTitle\"",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onUpdateDocumentSummary = { docId, newSummary ->
                    viewModel.updateDocumentSummary(docId, newSummary)
                },
                onExportSingleHighlight = { highlight, doc ->
                    viewModel.exportSingleHighlight(context, highlight, doc)
                },
                onExportMoc = { doc, docHighlights ->
                    viewModel.exportMoc(context, doc, docHighlights)
                },
                onExportCompleteBundleZip = { doc, docHighlights, onResult ->
                    viewModel.exportCompleteBundleAsZip(context, doc, docHighlights, onResult)
                },
                onExportCompleteBundleFolder = { doc, docHighlights, targetParentFolder, onResult ->
                    viewModel.exportCompleteBundleAsFolder(context, doc, docHighlights, targetParentFolder, onResult)
                },
                onShareSourceFile = { doc ->
                    viewModel.shareSourceDocumentFile(context, doc)
                },
                onShareSelectedHighlights = { selectedHighlights, doc ->
                    viewModel.shareSelectedHighlightsAsMd(context, selectedHighlights, doc)
                },
                onShareAllNotesAndMocOnly = { doc, docHighlights, asZip ->
                    viewModel.shareAllNotesAndMocOnly(context, doc, docHighlights, asZip)
                },
                onUpdateCoverAspectRatio = { docId, ratio ->
                    viewModel.updateDocumentCoverAspectRatio(docId, ratio)
                },
                onUpdateEstimatedReadingMinutes = { docId, minutes ->
                    viewModel.updateDocumentEstimatedReadingMinutes(docId, minutes)
                }
            )
        }
    }

    if (showWidgetManagerDialog || externalShowWidgetManager) {
        WidgetPinnedBooksManagerDialog(
            documents = recentDocuments,
            maxPinnedBooks = settings.maxPinnedBooksInWidget,
            configuringAppWidgetId = configuringAppWidgetId,
            onToggleBookPin = { doc ->
                viewModel.toggleDocumentWidgetPin(doc)
            },
            onUpdateMaxPinnedBooks = { newMax ->
                viewModel.updateMaxPinnedBooksInWidget(newMax, configuringAppWidgetId)
            },
            onOpenBookFromWidget = { doc ->
                showWidgetManagerDialog = false
                onClearWidgetManagerRequest()
                viewModel.openDocument(doc)
            },
            onOpenDocumentWithResume = { doc, resume ->
                showWidgetManagerDialog = false
                onClearWidgetManagerRequest()
                viewModel.openDocumentById(doc.id, resume)
            },
            onDismiss = {
                showWidgetManagerDialog = false
                onClearWidgetManagerRequest()
            },
            onFinishWidgetConfiguration = { appWidgetId ->
                showWidgetManagerDialog = false
                onFinishWidgetConfig(appWidgetId)
            }
        )
    }

    addToFolderDoc?.let { target ->
        com.example.ui.AddToFolderBookDialog(
            document = target,
            everyDocument = everyDocument,
            onDismiss = { addToFolderDoc = null },
            onDone = { folder ->
                addToFolderDoc = null
                Toast.makeText(context, "Added to “${folder.title}”", Toast.LENGTH_SHORT).show()
            }
        )
    }
    if (creatingFolderBook) {
        com.example.ui.FolderBookDetailsDialog(
            onDismiss = { creatingFolderBook = false },
            onSave = { t, e, tags, summary ->
                creatingFolderBook = false
                scope.launch {
                    val f = com.example.reader.FolderBooks.create(context, t, e)
                    if (tags.isNotBlank() || summary.isNotBlank()) com.example.reader.FolderBooks.updateInfo(context, f.id, t, e, tags, summary)
                    folderDocId = f.id
                }
            }
        )
    }

    val hasSharedDocumentRequest = incomingSharedUri != null || !incomingSharedText.isNullOrBlank()
    if (showAddDialog || hasSharedDocumentRequest) {
        AddDocumentDialog(
            customStoragePath = settings.customStoragePath,
            storageLocationType = settings.storageLocationType,
            initialSharedUri = incomingSharedUri,
            initialSharedText = incomingSharedText,
            onDismiss = {
                showAddDialog = false
                onClearSharedRequest()
            },
            onAddDocument = { title, author, category, fileType, filePath, coverName, totalPages, coverUri, openImmediately ->
                viewModel.addDocument(
                    title = title,
                    author = author,
                    category = category,
                    fileType = fileType,
                    filePath = filePath,
                    coverDrawableName = coverName,
                    totalPages = totalPages,
                    coverUri = coverUri,
                    openImmediately = openImmediately
                )
                if (hasSharedDocumentRequest) {
                    Toast.makeText(
                        context,
                        "Saved \"$title\" to Marginalia Lectoris!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                showAddDialog = false
                onClearSharedRequest()
            }
        )
    }
}
