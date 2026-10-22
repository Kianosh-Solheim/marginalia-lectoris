package com.example.ui

import android.app.Application
import android.content.Context
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.StorageManager
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.data.local.SettingsEntity
import com.example.data.repository.ReaderRepository
import com.example.reader.MarkdownExporter
import com.example.reader.SampleContentProvider
import com.example.widget.ContinueReadingWidgetProvider
import com.example.widget.PinnedBooksWidgetProvider
import com.example.widget.ReadingPosition
import com.example.widget.ReadingPositionBuilder
import com.example.widget.ResumeTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppNavSection(val title: String) {
    HOME("Home"),
    BOOKS("Books"),
    ARTICLES("Articles"),
    ALL_HIGHLIGHTS("All Highlights"),
    SETTINGS("PKM Settings")
}

class ReaderViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val repository = ReaderRepository(database)

    val currentSection = MutableStateFlow(AppNavSection.HOME)
    val searchQuery = MutableStateFlow("")

    private val _loadedSettings = MutableStateFlow(SettingsEntity())
    val settings: StateFlow<SettingsEntity> = _loadedSettings.asStateFlow()

    val isSettingsLoaded = MutableStateFlow(false)

    val allDocuments: StateFlow<List<DocumentEntity>> = combine(
        currentSection,
        searchQuery
    ) { section, query ->
        section to query.trim()
    }.flatMapLatest { (section, rawQuery) ->
        val normalizedQuery = rawQuery.removePrefix("#").trim()
        val sourceFlow = if (normalizedQuery.isEmpty()) {
            repository.allDocuments.combine(com.example.reader.FolderBooks.version) { docs, _ -> com.example.reader.FolderBooks.visible(docs) }
        } else {
            repository.searchDocuments(normalizedQuery)
        }
        sourceFlow.map { docs ->
            val filteredBySection = when (section) {
                AppNavSection.BOOKS -> docs.filter { it.category == "BOOK" }
                AppNavSection.ARTICLES -> docs.filter { it.category == "ARTICLE" }
                else -> docs
            }
            if (normalizedQuery.isEmpty()) {
                filteredBySection
            } else {
                val terms = normalizedQuery.split(Regex("\\s+"))
                    .map { it.removePrefix("#").trim() }
                    .filter { it.isNotEmpty() }
                if (terms.size <= 1) {
                    filteredBySection
                } else {
                    filteredBySection.filter { doc ->
                        terms.all { term ->
                            doc.title.contains(term, ignoreCase = true) ||
                                doc.author.contains(term, ignoreCase = true) ||
                                doc.tags.contains(term, ignoreCase = true)
                        }
                    }
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentDocuments: StateFlow<List<DocumentEntity>> = repository.allDocuments
        .combine(com.example.reader.FolderBooks.version) { docs, _ -> com.example.reader.FolderBooks.visible(docs) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Every library item, including the chapter files inside folder books (for notes lists and lookups). */
    val everyDocument: StateFlow<List<DocumentEntity>> = repository.allDocuments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pinnedWidgetDocuments: StateFlow<List<DocumentEntity>> = repository.pinnedWidgetDocuments
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allHighlights: StateFlow<List<HighlightEntity>> = repository.allHighlights
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pageTurnTrigger = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val isTtsCurrentlyPlaying = MutableStateFlow(false)

    fun setTtsPlayingState(isPlaying: Boolean) {
        isTtsCurrentlyPlaying.value = isPlaying
    }

    fun navigatePageNext() {
        pageTurnTrigger.tryEmit(1)
    }

    fun navigatePagePrevious() {
        pageTurnTrigger.tryEmit(-1)
    }

    private val _activeDocument = MutableStateFlow<DocumentEntity?>(null)
    val activeDocument: StateFlow<DocumentEntity?> = _activeDocument.asStateFlow()

    private val _pendingHighlightRequest = MutableStateFlow<HighlightEntity?>(null)
    val pendingHighlightRequest: StateFlow<HighlightEntity?> = _pendingHighlightRequest.asStateFlow()

    fun clearPendingHighlightRequest() {
        _pendingHighlightRequest.value = null
    }

    private val _pendingResume = MutableStateFlow<ResumeTarget?>(null)
    val pendingResume: StateFlow<ResumeTarget?> = _pendingResume.asStateFlow()

    fun clearPendingResume() {
        _pendingResume.value = null
    }

    val activeDocumentHighlights: StateFlow<List<HighlightEntity>> = _activeDocument
        .flatMapLatest { doc ->
            if (doc != null) {
                repository.getHighlightsForDocument(doc.id)
            } else {
                MutableStateFlow(emptyList())
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val initialSettings = repository.getSettings()
            _loadedSettings.value = initialSettings
            isSettingsLoaded.value = true
            SampleContentProvider.populateInitialDataIfEmpty(application, database)
        }
        viewModelScope.launch {
            repository.settingsFlow.collect { dbSettings ->
                if (dbSettings != null) {
                    _loadedSettings.value = dbSettings
                    isSettingsLoaded.value = true
                }
            }
        }
    }

    fun selectSection(section: AppNavSection) {
        currentSection.value = section
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun openDocument(
        document: DocumentEntity,
        overridePage: Int? = null,
        targetHighlight: HighlightEntity? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            var fresh = repository.getDocumentById(document.id) ?: document
            if (com.example.reader.FolderBooks.isFolder(fresh)) {
                // A folder book opens at the chapter read last.
                fresh = com.example.reader.FolderBooks.resumePart(getApplication(), fresh.id) ?: return@launch
            }
            _pendingResume.value = null
            _pendingHighlightRequest.value = targetHighlight
            val effectivePage = overridePage ?: targetHighlight?.pageNumber
            _activeDocument.value = if (effectivePage != null && effectivePage >= 1) {
                fresh.copy(currentPage = effectivePage)
            } else {
                fresh
            }
        }
    }

    fun openDocumentById(documentId: Long, resume: ResumeTarget? = null) {
        _pendingResume.value = resume
        viewModelScope.launch(Dispatchers.IO) {
            var fresh = repository.getDocumentById(documentId)
            if (com.example.reader.FolderBooks.isFolder(fresh)) {
                _pendingResume.value = null
                fresh = com.example.reader.FolderBooks.resumePart(getApplication(), documentId)
                if (fresh != null) _activeDocument.value = fresh
                return@launch
            }
            if (fresh != null) {
                _activeDocument.value = if (resume != null && resume.page >= 1) {
                    fresh.copy(currentPage = resume.page)
                } else {
                    fresh
                }
            }
        }
    }

    fun toggleDocumentWidgetPin(document: DocumentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val newPinned = !document.isPinnedToWidget
            val order = if (newPinned) System.currentTimeMillis() else 0L
            repository.updateDocumentWidgetPin(document.id, newPinned, order)
            if (_activeDocument.value?.id == document.id) {
                _activeDocument.value = _activeDocument.value?.copy(
                    isPinnedToWidget = newPinned,
                    widgetPinOrder = order
                )
            }
            PinnedBooksWidgetProvider.refreshAllWidgets(getApplication())
        }
    }

    fun setDocumentWidgetPin(documentId: Long, pinned: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val order = if (pinned) System.currentTimeMillis() else 0L
            repository.updateDocumentWidgetPin(documentId, pinned, order)
            if (_activeDocument.value?.id == documentId) {
                _activeDocument.value = _activeDocument.value?.copy(
                    isPinnedToWidget = pinned,
                    widgetPinOrder = order
                )
            }
            PinnedBooksWidgetProvider.refreshAllWidgets(getApplication())
        }
    }

    fun updateMaxPinnedBooksInWidget(maxBooks: Int, appWidgetId: Int? = null) {
        val clamped = maxBooks.coerceIn(1, 16)
        viewModelScope.launch(Dispatchers.IO) {
            val cur = repository.getSettings()
            repository.saveSettings(cur.copy(maxPinnedBooksInWidget = clamped))
            if (appWidgetId != null && appWidgetId != android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID) {
                PinnedBooksWidgetProvider.setMaxBooksForWidget(getApplication(), appWidgetId, clamped)
            }
            PinnedBooksWidgetProvider.refreshAllWidgets(getApplication())
        }
    }

    fun closeDocument() {
        isTtsCurrentlyPlaying.value = false
        _pendingHighlightRequest.value = null
        _activeDocument.value = null
    }

    fun addDocument(
        title: String,
        author: String,
        category: String,
        fileType: String,
        filePath: String,
        coverDrawableName: String? = null,
        totalPages: Int = 1,
        coverUri: String? = null,
        openImmediately: Boolean = true
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val curSettings = repository.getSettings()
            val cleanTitle = title.trim()
            val cleanAuthor = author.trim()

            StorageManager.unmarkDocumentDeleted(
                context = app,
                title = cleanTitle,
                filePath = filePath,
                customPath = curSettings.customStoragePath,
                storageType = curSettings.storageLocationType
            )

            // Check if this document already exists in the database (by file path, sanitized title, or content signature)
            val existingDocs = repository.getAllDocumentsOrderedByLastReadSnapshot()
            val newFile = File(filePath)
            val newCanonPath = runCatching { newFile.canonicalPath }.getOrDefault(filePath)
            val newSig = StorageManager.computeFileContentSignature(newFile)
            val existingMatch = existingDocs.firstOrNull { existing ->
                val exFile = File(existing.filePath)
                val exCanon = runCatching { exFile.canonicalPath }.getOrDefault(existing.filePath)
                exCanon.equals(newCanonPath, ignoreCase = true) ||
                    (existing.category.equals(category, ignoreCase = true) &&
                        StorageManager.sanitizeName(existing.title).equals(StorageManager.sanitizeName(cleanTitle), ignoreCase = true)) ||
                    (newSig != null && StorageManager.computeFileContentSignature(exFile) == newSig)
            }

            val decodedRatio = StorageManager.decodeCoverAspectRatio(coverUri)
            val estimatedMinutes = com.example.reader.ReadingTimeEstimator.calculateReadingTimeMinutes(
                file = newFile,
                fileType = fileType,
                totalPages = totalPages,
                category = category,
                context = app
            )

            if (existingMatch != null) {
                val updatedExisting = existingMatch.copy(
                    title = cleanTitle.ifBlank { existingMatch.title },
                    author = cleanAuthor.ifBlank { existingMatch.author },
                    filePath = if (newFile.exists()) filePath else existingMatch.filePath,
                    coverUri = coverUri ?: existingMatch.coverUri,
                    totalPages = if (totalPages > 1) totalPages else existingMatch.totalPages,
                    lastReadTimestamp = System.currentTimeMillis(),
                    coverAspectRatio = if (decodedRatio > 0f) decodedRatio else existingMatch.coverAspectRatio,
                    estimatedReadingMinutes = if (estimatedMinutes > 0) estimatedMinutes else existingMatch.estimatedReadingMinutes
                )
                repository.updateDocument(updatedExisting)
            com.example.reader.MetadataDetector.commit(app, filePath, updatedExisting.id)
                if (openImmediately) {
                    _activeDocument.value = updatedExisting
                }
                PinnedBooksWidgetProvider.refreshAllWidgets(app)
                ContinueReadingWidgetProvider.refreshAll(app)
                return@launch
            }

            val doc = DocumentEntity(
                title = cleanTitle,
                author = cleanAuthor,
                category = category,
                fileType = fileType,
                filePath = filePath,
                coverDrawableName = coverDrawableName,
                coverUri = coverUri,
                totalPages = totalPages,
                lastReadTimestamp = System.currentTimeMillis(),
                coverAspectRatio = decodedRatio,
                estimatedReadingMinutes = estimatedMinutes
            )
            val newId = repository.insertDocument(doc)
        com.example.reader.MetadataDetector.commit(app, filePath, newId)
            val created = repository.getDocumentById(newId)
            if (created != null && openImmediately) {
                _activeDocument.value = created
            }
            PinnedBooksWidgetProvider.refreshAllWidgets(app)
            ContinueReadingWidgetProvider.refreshAll(app)
        }
    }

    fun updateDocumentCoverAspectRatio(documentId: Long, aspectRatio: Float) {
        if (aspectRatio <= 0f) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateDocumentCoverAspectRatio(documentId, aspectRatio)
            if (_activeDocument.value?.id == documentId) {
                _activeDocument.value = _activeDocument.value?.copy(coverAspectRatio = aspectRatio)
            }
        }
    }

    fun updateDocumentEstimatedReadingMinutes(documentId: Long, estimatedMinutes: Int) {
        if (estimatedMinutes <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateDocumentEstimatedReadingMinutes(documentId, estimatedMinutes)
            if (_activeDocument.value?.id == documentId) {
                _activeDocument.value = _activeDocument.value?.copy(estimatedReadingMinutes = estimatedMinutes)
            }
        }
    }

    fun deleteDocument(document: DocumentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val curSettings = repository.getSettings()
            if (_activeDocument.value?.id == document.id) {
                _activeDocument.value = null
            }
            val folderOfDeleted = com.example.reader.FolderBooks.folderOf(document.id)
            repository.deleteDocument(document)
            com.example.reader.FolderBooks.forgetDocument(app, document.id)
            folderOfDeleted?.let { com.example.reader.FolderBooks.sync(app, it) }
            val remainingDocs = repository.getAllDocumentsOrderedByLastReadSnapshot()
            StorageManager.deleteDocumentFilesFromStorage(
                context = app,
                deletedDoc = document,
                remainingDocs = remainingDocs,
                customPath = curSettings.customStoragePath,
                storageType = curSettings.storageLocationType
            )
            PinnedBooksWidgetProvider.refreshAllWidgets(app)
            ContinueReadingWidgetProvider.refreshAll(app)
        }
    }

    /** Book ↔ article, moving its folder to the other shelf. */
    fun changeCategory(document: DocumentEntity, category: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = com.example.server.LibraryOps(getApplication()).changeCategory(document, category)
            if (_activeDocument.value?.id == document.id) _activeDocument.value = updated
        }
    }

    fun updateDocumentInfo(
        document: DocumentEntity,
        newTitle: String,
        newAuthor: String,
        newTags: String,
        newCoverBitmap: android.graphics.Bitmap? = null,
        resetToDefaultCover: Boolean = false,
        newSummary: String = document.summary
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val curSettings = repository.getSettings()
            val cleanTitle = newTitle.trim().ifBlank { document.title }
            val cleanAuthor = newAuthor.trim().ifBlank { "Unknown Author" }
            val cleanSummary = DocumentMetadataHelper.cleanSelectedAbstractText(newSummary)
            val cleanTags = com.example.reader.NoteSuggestionEngine.joinTagsList(
                com.example.reader.NoteSuggestionEngine.parseTagsList(newTags)
            )

            var updatedFilePath = document.filePath
            var updatedCoverUri = document.coverUri

            // If the title changed such that its sanitized folder name changed, move the book file and folder cleanly
            val oldFolder = File(document.filePath).parentFile
            val newFolder = StorageManager.getDocumentFolder(
                context = app,
                category = document.category,
                documentTitle = cleanTitle,
                customPath = curSettings.customStoragePath,
                storageType = curSettings.storageLocationType
            )
            val oldFolderCanon = runCatching { oldFolder?.canonicalPath }.getOrNull()
            val newFolderCanon = runCatching { newFolder.canonicalPath }.getOrDefault(newFolder.absolutePath)
            if (oldFolder != null && oldFolder.exists() &&
                oldFolderCanon != null &&
                !oldFolderCanon.equals(newFolderCanon, ignoreCase = true) &&
                oldFolder.name != "Books" && oldFolder.name != "Articles"
            ) {
                try {
                    val oldSourceFile = File(document.filePath)
                    if (oldSourceFile.exists()) {
                        val movedSourceFile = File(newFolder, oldSourceFile.name)
                        if (!movedSourceFile.exists()) {
                            oldSourceFile.copyTo(movedSourceFile, overwrite = true)
                        }
                        if (movedSourceFile.exists() && movedSourceFile.length() > 0L) {
                            updatedFilePath = movedSourceFile.absolutePath
                            oldSourceFile.delete()
                        }
                    }
                    if (!updatedCoverUri.isNullOrBlank()) {
                        val oldCover = File(updatedCoverUri)
                        if (oldCover.exists() && oldCover.parentFile?.canonicalPath == oldFolderCanon) {
                            val movedCover = File(newFolder, oldCover.name)
                            oldCover.copyTo(movedCover, overwrite = true)
                            if (movedCover.exists() && movedCover.length() > 0L) {
                                updatedCoverUri = movedCover.absolutePath
                            }
                        }
                    }
                    // Delete the old folder so scanAndSyncLibrary never sees an orphan folder
                    oldFolder.deleteRecursively()
                } catch (_: Throwable) {}
            }

            val rootDir = StorageManager.getRootDirectory(app, curSettings.customStoragePath, curSettings.storageLocationType)
            val coversDir = File(rootDir, ".covers")
            val cacheCandidates = listOf(
                File(coversDir, "pdf_${document.id}.jpg"),
                File(coversDir, "epub_${document.id}.jpg"),
                File(coversDir, "daisy_${document.id}.jpg"),
                File(coversDir, "html_${document.id}.jpg"),
                File(coversDir, "txt_${document.id}.jpg")
            )

            if (newCoverBitmap != null) {
                val savedCover = StorageManager.saveCoverImage(
                    context = app,
                    category = document.category,
                    documentTitle = cleanTitle,
                    bitmap = newCoverBitmap,
                    customPath = curSettings.customStoragePath,
                    storageType = curSettings.storageLocationType
                )
                if (savedCover != null && savedCover.exists()) {
                    updatedCoverUri = savedCover.absolutePath
                }
                cacheCandidates.forEach { try { if (it.exists()) it.delete() } catch (_: Throwable) {} }
                StorageManager.saveCoverBitmap(app, "${document.fileType.lowercase()}_${document.id}", newCoverBitmap, curSettings.customStoragePath)
            } else if (resetToDefaultCover) {
                cacheCandidates.forEach { try { if (it.exists()) it.delete() } catch (_: Throwable) {} }
                val sourceFile = File(updatedFilePath)
                val isPdf = document.fileType.equals("PDF", ignoreCase = true) || updatedFilePath.endsWith(".pdf", ignoreCase = true)
                val regeneratedBmp = try {
                    when {
                        isPdf && sourceFile.exists() -> {
                            val renderer = com.example.reader.PdfDocumentRenderer(sourceFile, app)
                            val page0 = renderer.renderPage(0, targetWidth = 480)
                            renderer.close()
                            page0 ?: com.example.reader.EpubParser.generateBookCoverBitmap(cleanTitle, cleanAuthor)
                        }
                        document.fileType.equals("EPUB", ignoreCase = true) && sourceFile.exists() -> {
                            com.example.reader.EpubParser.extractCoverBitmap(sourceFile, cleanTitle, cleanAuthor)
                        }
                        else -> {
                            com.example.reader.EpubParser.generateBookCoverBitmap(cleanTitle, cleanAuthor)
                        }
                    }
                } catch (_: Throwable) {
                    com.example.reader.EpubParser.generateBookCoverBitmap(cleanTitle, cleanAuthor)
                }
                val savedCover = StorageManager.saveCoverImage(
                    context = app,
                    category = document.category,
                    documentTitle = cleanTitle,
                    bitmap = regeneratedBmp,
                    customPath = curSettings.customStoragePath,
                    storageType = curSettings.storageLocationType
                )
                updatedCoverUri = savedCover?.absolutePath
                StorageManager.saveCoverBitmap(app, "${document.fileType.lowercase()}_${document.id}", regeneratedBmp, curSettings.customStoragePath)
            }

            val updatedCoverRatio = when {
                newCoverBitmap != null -> StorageManager.decodeCoverAspectRatio(updatedCoverUri, newCoverBitmap)
                resetToDefaultCover -> StorageManager.decodeCoverAspectRatio(updatedCoverUri)
                document.coverAspectRatio > 0f -> document.coverAspectRatio
                else -> StorageManager.decodeCoverAspectRatio(updatedCoverUri)
            }

            val updatedDoc = document.copy(
                title = cleanTitle,
                author = cleanAuthor,
                summary = cleanSummary,
                tags = cleanTags,
                filePath = updatedFilePath,
                coverUri = updatedCoverUri,
                coverAspectRatio = updatedCoverRatio
            )
            repository.updateDocument(updatedDoc)
            if (_activeDocument.value?.id == document.id) {
                _activeDocument.value = updatedDoc
            }

            // Keep highlight.md and Notes on X.md in the document's folder synchronized with the new author, summary, tags, and cover
            val allDocHighlights = repository.getHighlightsListForDocument(document.id)
            StorageManager.saveHighlightMarkdown(
                context = app,
                category = updatedDoc.category,
                documentTitle = updatedDoc.title,
                highlights = allDocHighlights,
                author = updatedDoc.author,
                customPath = curSettings.customStoragePath,
                storageType = curSettings.storageLocationType,
                tags = updatedDoc.tags
            )
            val mocTitle = MarkdownExporter.getMocTitle(updatedDoc.title, curSettings)
            val mocContent = MarkdownExporter.generateMocMarkdown(updatedDoc, allDocHighlights, curSettings)
            StorageManager.saveMoc(
                context = app,
                category = updatedDoc.category,
                documentTitle = updatedDoc.title,
                mocTitle = mocTitle,
                markdownContent = mocContent,
                customPath = curSettings.customStoragePath,
                storageType = curSettings.storageLocationType
            )
            PinnedBooksWidgetProvider.refreshAllWidgets(app)
            ContinueReadingWidgetProvider.refreshAll(app)
        }
    }

    fun updateDocumentAuthor(documentId: Long, newAuthor: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val doc = repository.getDocumentById(documentId) ?: _activeDocument.value?.takeIf { it.id == documentId } ?: return@launch
            val cleanAuthor = newAuthor.trim().ifBlank { "Unknown Author" }
            val updatedDoc = doc.copy(author = cleanAuthor)
            repository.updateDocument(updatedDoc)
            if (_activeDocument.value?.id == documentId) {
                _activeDocument.value = updatedDoc
            }
            val app = getApplication<Application>()
            val curSettings = repository.getSettings()
            val allDocHighlights = repository.getHighlightsListForDocument(documentId)
            StorageManager.saveHighlightMarkdown(
                context = app,
                category = updatedDoc.category,
                documentTitle = updatedDoc.title,
                highlights = allDocHighlights,
                author = updatedDoc.author,
                customPath = curSettings.customStoragePath,
                storageType = curSettings.storageLocationType,
                tags = updatedDoc.tags
            )
            val mocTitle = MarkdownExporter.getMocTitle(updatedDoc.title, curSettings)
            val mocContent = MarkdownExporter.generateMocMarkdown(updatedDoc, allDocHighlights, curSettings)
            StorageManager.saveMoc(
                context = app,
                category = updatedDoc.category,
                documentTitle = updatedDoc.title,
                mocTitle = mocTitle,
                markdownContent = mocContent,
                customPath = curSettings.customStoragePath,
                storageType = curSettings.storageLocationType
            )
            PinnedBooksWidgetProvider.refreshAllWidgets(app)
            ContinueReadingWidgetProvider.refreshAll(app)
        }
    }

    fun updateDocumentSummary(documentId: Long, newSummary: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val doc = repository.getDocumentById(documentId) ?: _activeDocument.value?.takeIf { it.id == documentId } ?: return@launch
            val cleanSummary = DocumentMetadataHelper.cleanSelectedAbstractText(newSummary)
            val updatedDoc = doc.copy(summary = cleanSummary)
            repository.updateDocument(updatedDoc)
            if (_activeDocument.value?.id == documentId) {
                _activeDocument.value = updatedDoc
            }
            val app = getApplication<Application>()
            val curSettings = repository.getSettings()
            val allDocHighlights = repository.getHighlightsListForDocument(documentId)
            val mocTitle = MarkdownExporter.getMocTitle(updatedDoc.title, curSettings)
            val mocContent = MarkdownExporter.generateMocMarkdown(updatedDoc, allDocHighlights, curSettings)
            StorageManager.saveMoc(
                context = app,
                category = updatedDoc.category,
                documentTitle = updatedDoc.title,
                mocTitle = mocTitle,
                markdownContent = mocContent,
                customPath = curSettings.customStoragePath,
                storageType = curSettings.storageLocationType
            )
            PinnedBooksWidgetProvider.refreshAllWidgets(app)
            ContinueReadingWidgetProvider.refreshAll(app)
        }
    }

    private var savePositionJob: Job? = null

    fun saveReadingPosition(documentId: Long, position: ReadingPosition) {
        savePositionJob?.cancel()
        savePositionJob = viewModelScope.launch(Dispatchers.IO) {
            delay(800L)
            repository.updateReadingPosition(documentId, position)
            if (_activeDocument.value?.id == documentId) {
                _activeDocument.value = _activeDocument.value?.copy(
                    lastPositionCfi = position.cfi,
                    lastPositionParagraph = position.paragraph,
                    lastPositionOffset = position.offset,
                    lastPositionLoc = position.loc,
                    lastPositionLabel = position.label,
                    lastPositionSnippet = position.snippet,
                    lastPositionChapter = position.chapter
                )
            }
            ContinueReadingWidgetProvider.refreshAllThrottled(getApplication())
        }
    }

    fun updateReadingProgress(documentId: Long, page: Int, totalPages: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val safeTotal = totalPages.coerceAtLeast(1)
            val safePage = page.coerceIn(1, safeTotal)
            val progress = if (safeTotal > 1) {
                if (safePage >= safeTotal) 1f
                else ((safePage - 1).toFloat() / (safeTotal - 1).toFloat()).coerceIn(0f, 0.99f)
            } else {
                0f
            }
            repository.updateReadingProgress(documentId, safePage, safeTotal, progress)
            val currentDoc = _activeDocument.value?.takeIf { it.id == documentId } ?: repository.getDocumentById(documentId)
            if (currentDoc != null && (currentDoc.fileType.equals("PDF", ignoreCase = true) || currentDoc.filePath.endsWith(".pdf", ignoreCase = true))) {
                saveReadingPosition(documentId, ReadingPositionBuilder.forPdf(safePage, safeTotal))
            }
            if (_activeDocument.value?.id == documentId) {
                _activeDocument.value = _activeDocument.value?.copy(
                    currentPage = safePage,
                    totalPages = safeTotal,
                    readingProgress = progress,
                    lastReadTimestamp = System.currentTimeMillis()
                )
            }
            PinnedBooksWidgetProvider.refreshAllWidgets(getApplication())
            ContinueReadingWidgetProvider.refreshAllThrottled(getApplication())
            com.example.reader.FolderBooks.folderOf(documentId)?.let { folderId ->
                com.example.reader.FolderBooks.sync(getApplication(), folderId)
            }
        }
    }

    fun updateDocumentChapters(documentId: Long, chaptersJson: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateDocumentChapters(documentId, chaptersJson)
            if (_activeDocument.value?.id == documentId) {
                _activeDocument.value = _activeDocument.value?.copy(
                    chaptersJson = chaptersJson
                )
            }
        }
    }

    fun updateDocumentPdfCrop(documentId: Long, left: Float, top: Float, right: Float, bottom: Float) {
        val safeLeft = left.coerceIn(0f, 0.85f)
        val safeTop = top.coerceIn(0f, 0.85f)
        val safeRight = right.coerceIn((safeLeft + 0.12f).coerceAtMost(1f), 1f)
        val safeBottom = bottom.coerceIn((safeTop + 0.12f).coerceAtMost(1f), 1f)
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateDocumentPdfCrop(documentId, safeLeft, safeTop, safeRight, safeBottom)
            if (_activeDocument.value?.id == documentId) {
                _activeDocument.value = _activeDocument.value?.copy(
                    pdfCropLeft = safeLeft,
                    pdfCropTop = safeTop,
                    pdfCropRight = safeRight,
                    pdfCropBottom = safeBottom
                )
            }
        }
    }

    fun updateDocumentTextExtractionConfig(
        documentId: Long,
        mode: String,
        overridesJson: String,
        ocrLanguage: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateDocumentTextExtractionConfig(documentId, mode, overridesJson, ocrLanguage)
            if (_activeDocument.value?.id == documentId) {
                _activeDocument.value = _activeDocument.value?.copy(
                    pdfTextExtractionMode = mode,
                    pdfPageExtractionOverridesJson = overridesJson,
                    pdfOcrLanguage = ocrLanguage
                )
            }
        }
    }

    fun addHighlight(
        documentId: Long,
        title: String,
        highlightedText: String,
        userNotes: String,
        pageNumber: Int,
        printedPageLabel: String = "",
        chapterTitle: String,
        colorHex: String,
        tags: String = "",
        paragraphCitation: String = "",
        pageEquivalent: String = "",
        epubCfi: String = ""
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // Quick-saved highlights get an offline (non-AI) title so exported notes don't collide as
            // identical "Note - Page N" filenames. AI suggestions remain button-only in the note dialog.
            val resolvedTitle = if (title.isBlank()) {
                com.example.reader.NoteSuggestionEngine.suggestTitles(
                    highlightedText = highlightedText,
                    userNotes = userNotes,
                    chapterTitle = chapterTitle,
                    pageNumber = pageNumber
                ).firstOrNull() ?: "Note - Page $pageNumber"
            } else {
                title.trim()
            }
            val resolvedTags = com.example.reader.NoteSuggestionEngine.joinTagsList(
                com.example.reader.NoteSuggestionEngine.parseTagsList(tags)
            )
            val resolvedParagraphCitation = paragraphCitation.trim().ifBlank {
                if (chapterTitle.isNotBlank()) "${chapterTitle.trim()}, ¶ 1" else ""
            }
            val doc = repository.getDocumentById(documentId)
            val isPdf = doc?.fileType.equals("PDF", ignoreCase = true)
            val resolvedEpubCfi = when {
                epubCfi.isNotBlank() && com.example.reader.EpubCfiEngine.isValidEpubCfi(epubCfi) ->
                    com.example.reader.EpubCfiEngine.normalizeCfi(epubCfi, includeHashPrefix = false)
                !isPdf && highlightedText.isNotBlank() -> {
                    val tempHighlight = HighlightEntity(
                        documentId = documentId,
                        title = resolvedTitle,
                        highlightedText = highlightedText.trim(),
                        pageNumber = pageNumber,
                        chapterTitle = chapterTitle.trim(),
                        paragraphCitation = resolvedParagraphCitation
                    )
                    com.example.reader.EpubCfiEngine.getOrComputeHighlightCfi(
                        highlight = tempHighlight,
                        document = doc,
                        includeHashPrefix = false
                    )
                }
                else -> ""
            }
            val highlight = HighlightEntity(
                documentId = documentId,
                title = resolvedTitle,
                highlightedText = highlightedText.trim(),
                userNotes = userNotes.trim(),
                pageNumber = pageNumber,
                printedPageLabel = printedPageLabel.trim(),
                chapterTitle = chapterTitle.trim(),
                paragraphCitation = resolvedParagraphCitation,
                pageEquivalent = pageEquivalent.trim(),
                epubCfi = resolvedEpubCfi,
                colorHex = colorHex,
                tags = resolvedTags
            )
            repository.insertHighlight(highlight)

            // Save highlight.md inside the document folder: Readmark/[Books|Articles]/[Title]/highlight.md
            val curSettings = repository.getSettings()
            if (doc != null) {
                val allDocHighlights = repository.getHighlightsListForDocument(documentId)
                StorageManager.saveHighlightMarkdown(
                    context = getApplication<Application>(),
                    category = doc.category,
                    documentTitle = doc.title,
                    highlights = allDocHighlights,
                    author = doc.author,
                    customPath = curSettings.customStoragePath,
                    storageType = curSettings.storageLocationType,
                    tags = doc.tags
                )
            }
        }
    }

    fun updateHighlight(highlight: HighlightEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateHighlight(highlight)
            val doc = repository.getDocumentById(highlight.documentId)
            val curSettings = repository.getSettings()
            if (doc != null) {
                val allDocHighlights = repository.getHighlightsListForDocument(highlight.documentId)
                StorageManager.saveHighlightMarkdown(
                    context = getApplication<Application>(),
                    category = doc.category,
                    documentTitle = doc.title,
                    highlights = allDocHighlights,
                    author = doc.author,
                    customPath = curSettings.customStoragePath,
                    storageType = curSettings.storageLocationType,
                    tags = doc.tags
                )
            }
        }
    }

    fun deleteHighlight(highlight: HighlightEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteHighlight(highlight)
            val doc = repository.getDocumentById(highlight.documentId)
            val curSettings = repository.getSettings()
            if (doc != null) {
                val allDocHighlights = repository.getHighlightsListForDocument(highlight.documentId)
                StorageManager.saveHighlightMarkdown(
                    context = getApplication<Application>(),
                    category = doc.category,
                    documentTitle = doc.title,
                    highlights = allDocHighlights,
                    author = doc.author,
                    customPath = curSettings.customStoragePath,
                    storageType = curSettings.storageLocationType,
                    tags = doc.tags
                )
            }
        }
    }

    fun saveSettings(updatedSettings: SettingsEntity) {
        val normalizedStyle = updatedSettings.readerParagraphStyle.uppercase().takeIf {
            it in setOf("INDENT", "SPACED", "BOTH", "NONE")
        } ?: "BOTH"
        val cleanSettings = updatedSettings.copy(readerParagraphStyle = normalizedStyle)
        _loadedSettings.value = cleanSettings
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveSettings(cleanSettings)
        }
    }

    fun setShowContinueReadingCard(show: Boolean) {
        val updated = _loadedSettings.value.copy(showContinueReadingCard = show)
        saveSettings(updated)
    }


    fun exportVaultZip(context: Context, document: DocumentEntity, highlights: List<HighlightEntity>) {
        viewModelScope.launch(Dispatchers.IO) {
            val curSettings = repository.getSettings()
            val zipFile = MarkdownExporter.createVaultZip(context, document, highlights, curSettings)
            val docFolder = StorageManager.getDocumentFolder(
                context = context,
                category = document.category,
                documentTitle = document.title,
                customPath = curSettings.customStoragePath,
                storageType = curSettings.storageLocationType
            )
            val destZip = File(docFolder, zipFile.name)
            try { zipFile.copyTo(destZip, overwrite = true) } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                MarkdownExporter.shareFile(context, zipFile, "application/zip", "Export PKM Vault for ${document.title}")
            }
        }
    }

    fun exportSingleHighlight(context: Context, highlight: HighlightEntity, document: DocumentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val curSettings = repository.getSettings()
            val mdFile = MarkdownExporter.createSingleHighlightFile(context, highlight, document, curSettings)
            val docFolder = StorageManager.getDocumentFolder(
                context = context,
                category = document.category,
                documentTitle = document.title,
                customPath = curSettings.customStoragePath,
                storageType = curSettings.storageLocationType
            )
            val destMd = File(docFolder, mdFile.name)
            try { mdFile.copyTo(destMd, overwrite = true) } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                MarkdownExporter.shareFile(context, mdFile, "text/markdown", "Export Highlight: ${highlight.title}")
            }
        }
    }

    fun exportMoc(context: Context, document: DocumentEntity, highlights: List<HighlightEntity>) {
        viewModelScope.launch(Dispatchers.IO) {
            val curSettings = repository.getSettings()
            val mocFile = MarkdownExporter.createMocFile(context, document, highlights, curSettings)
            val mocTitle = MarkdownExporter.getMocTitle(document.title, curSettings)
            val docFolder = StorageManager.getDocumentFolder(
                context = context,
                category = document.category,
                documentTitle = document.title,
                customPath = curSettings.customStoragePath,
                storageType = curSettings.storageLocationType
            )
            StorageManager.saveMoc(
                context = context,
                category = document.category,
                documentTitle = document.title,
                mocTitle = mocTitle,
                markdownContent = mocFile.readText(),
                customPath = curSettings.customStoragePath,
                storageType = curSettings.storageLocationType
            )
            // Also export all individual highlight note .md files and the WebP front cover
            val usedFilenames = mutableSetOf<String>()
            highlights.forEach { highlight ->
                val baseName = MarkdownExporter.sanitizeFilename(highlight.title)
                var filename = "$baseName.md"
                var counter = 2
                while (usedFilenames.contains(filename.lowercase(java.util.Locale.ROOT))) {
                    filename = "$baseName-$counter.md"
                    counter++
                }
                usedFilenames.add(filename.lowercase(java.util.Locale.ROOT))
                val noteContent = MarkdownExporter.generateHighlightMarkdown(highlight, document, curSettings)
                try {
                    File(docFolder, filename).writeText(noteContent, Charsets.UTF_8)
                } catch (_: Exception) {}
            }
            MarkdownExporter.resolveOrGenerateCoverFile(context, document, curSettings)

            withContext(Dispatchers.Main) {
                MarkdownExporter.shareFile(context, mocFile, "text/markdown", "Export MOC: $mocTitle")
            }
        }
    }

    /**
     * Exports the book and all its annotations as a complete, unified Obsidian markdown note
     * featuring YAML frontmatter and callout syntax.
     */
    fun exportUnifiedObsidianMarkdown(context: Context, document: DocumentEntity, highlights: List<HighlightEntity>) {
        viewModelScope.launch(Dispatchers.IO) {
            val curSettings = repository.getSettings()
            val mdFile = MarkdownExporter.createUnifiedBookFile(context, document, highlights, curSettings)
            withContext(Dispatchers.Main) {
                MarkdownExporter.shareFile(context, mdFile, "text/markdown", "Export to Obsidian: ${document.title}")
            }
        }
    }

    /**
     * Copies the full Obsidian-flavored Markdown note directly to clipboard.
     */
    fun copyUnifiedObsidianMarkdown(context: Context, document: DocumentEntity, highlights: List<HighlightEntity>): String {
        val curSettings = settings.value
        val markdown = MarkdownExporter.generateUnifiedObsidianBookMarkdown(document, highlights, curSettings)
        MarkdownExporter.copyToClipboard(context, markdown, "Obsidian Note - ${document.title}")
        return markdown
    }

    /**
     * Writes the Obsidian-flavored Markdown directly into the user's Obsidian Vault or app storage.
     */
    fun syncToObsidianVault(context: Context, document: DocumentEntity, highlights: List<HighlightEntity>): File {
        val curSettings = settings.value
        return MarkdownExporter.saveToObsidianVault(context, document, highlights, curSettings)
    }

    /**
     * Exports all notes (all individual highlights + `Notes on X.md` + `highlight.md`),
     * the source document file (PDF/EPUB/HTML/TXT), and the book cover image (`cover.jpg`)
     * as a single `.zip` archive and opens the Android share sheet.
     */
    fun exportCompleteBundleAsZip(
        context: Context,
        document: DocumentEntity,
        highlights: List<HighlightEntity>,
        onCompleted: ((MarkdownExporter.ExportBundleResult) -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val curSettings = repository.getSettings()
            val docHighlights = if (highlights.isNotEmpty()) {
                highlights
            } else {
                repository.getHighlightsListForDocument(document.id)
            }
            val result = MarkdownExporter.createCompleteDocumentBundleZip(
                context = context,
                document = document,
                highlights = docHighlights,
                settings = curSettings
            )
            // Also keep a copy inside the document's folder
            val docFolder = StorageManager.getDocumentFolder(
                context = context,
                category = document.category,
                documentTitle = document.title,
                customPath = curSettings.customStoragePath,
                storageType = curSettings.storageLocationType
            )
            val destZip = File(docFolder, result.outputFileOrFolder.name)
            try {
                result.outputFileOrFolder.copyTo(destZip, overwrite = true)
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                onCompleted?.invoke(result)
                try {
                    MarkdownExporter.shareFile(
                        context = context,
                        file = result.outputFileOrFolder,
                        mimeType = "application/zip",
                        chooserTitle = "Export Complete Bundle (.ZIP) for ${document.title}"
                    )
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Exports all notes (all individual highlights + `Notes on X.md` + `highlight.md`),
     * the source document file (PDF/EPUB/HTML/TXT), and the book cover image (`cover.jpg`)
     * into a regular folder on disk.
     */
    fun exportCompleteBundleAsFolder(
        context: Context,
        document: DocumentEntity,
        highlights: List<HighlightEntity>,
        targetParentFolder: File? = null,
        onCompleted: ((MarkdownExporter.ExportBundleResult) -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val curSettings = repository.getSettings()
            val docHighlights = if (highlights.isNotEmpty()) {
                highlights
            } else {
                repository.getHighlightsListForDocument(document.id)
            }
            val result = MarkdownExporter.exportCompleteDocumentBundleToFolder(
                context = context,
                document = document,
                highlights = docHighlights,
                settings = curSettings,
                targetParentFolder = targetParentFolder
            )
            withContext(Dispatchers.Main) {
                onCompleted?.invoke(result)
            }
        }
    }

    /**
     * Shares only the document's source file (EPUB/PDF/HTML/DAISY/MD/TXT).
     */
    fun shareSourceDocumentFile(context: Context, document: DocumentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val fileToShare = MarkdownExporter.prepareSourceFileForSharing(context, document)
            val mimeType = MarkdownExporter.getMimeTypeForSourceFile(document)
            withContext(Dispatchers.Main) {
                try {
                    MarkdownExporter.shareFile(
                        context = context,
                        file = fileToShare,
                        mimeType = mimeType,
                        chooserTitle = "Share ${document.fileType.uppercase()} File: ${document.title}"
                    )
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Shares one or multiple selected highlights as `.md` files (using ACTION_SEND for 1 note
     * or ACTION_SEND_MULTIPLE for multiple notes).
     */
    fun shareSelectedHighlightsAsMd(
        context: Context,
        selectedHighlights: List<HighlightEntity>,
        document: DocumentEntity
    ) {
        if (selectedHighlights.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val curSettings = repository.getSettings()
            val mdFiles = MarkdownExporter.createMultipleHighlightFiles(
                context = context,
                highlights = selectedHighlights,
                document = document,
                settings = curSettings
            )
            val docFolder = StorageManager.getDocumentFolder(
                context = context,
                category = document.category,
                documentTitle = document.title,
                customPath = curSettings.customStoragePath,
                storageType = curSettings.storageLocationType
            )
            mdFiles.forEach { file ->
                try {
                    file.copyTo(File(docFolder, file.name), overwrite = true)
                } catch (_: Exception) {}
            }
            withContext(Dispatchers.Main) {
                try {
                    val title = if (mdFiles.size == 1) {
                        "Share Note (.md): ${selectedHighlights.first().title}"
                    } else {
                        "Share ${mdFiles.size} Notes (.md) from ${document.title}"
                    }
                    MarkdownExporter.shareMultipleFiles(
                        context = context,
                        files = mdFiles,
                        mimeType = "text/markdown",
                        chooserTitle = title
                    )
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Shares ALL highlight notes (`.md`) and the Map of Content (`Notes on [Title].md`) file,
     * explicitly leaving out the source book/article file (EPUB/PDF/HTML/etc.) and the cover image file.
     * Supports sharing as multiple `.md` files (`ACTION_SEND_MULTIPLE`) or as a `.zip` archive of `.md` files.
     */
    fun shareAllNotesAndMocOnly(
        context: Context,
        document: DocumentEntity,
        highlights: List<HighlightEntity>,
        asZip: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val curSettings = repository.getSettings()
            val docHighlights = if (highlights.isNotEmpty()) {
                highlights
            } else {
                repository.getHighlightsListForDocument(document.id)
            }
            val mdFiles = MarkdownExporter.createAllNotesAndMocFilesOnly(
                context = context,
                document = document,
                highlights = docHighlights,
                settings = curSettings
            )
            // Keep the document's library folder updated with the notes and MOC
            val docFolder = StorageManager.getDocumentFolder(
                context = context,
                category = document.category,
                documentTitle = document.title,
                customPath = curSettings.customStoragePath,
                storageType = curSettings.storageLocationType
            )
            mdFiles.forEach { file ->
                try {
                    file.copyTo(File(docFolder, file.name), overwrite = true)
                } catch (_: Exception) {}
            }

            if (asZip) {
                val zipFile = MarkdownExporter.createAllNotesAndMocOnlyZip(
                    context = context,
                    document = document,
                    highlights = docHighlights,
                    settings = curSettings
                )
                withContext(Dispatchers.Main) {
                    try {
                        MarkdownExporter.shareFile(
                            context = context,
                            file = zipFile,
                            mimeType = "application/zip",
                            chooserTitle = "Share All Notes & Map of Content (.ZIP): ${document.title}"
                        )
                    } catch (_: Exception) {}
                }
            } else {
                withContext(Dispatchers.Main) {
                    try {
                        MarkdownExporter.shareMultipleFiles(
                            context = context,
                            files = mdFiles,
                            mimeType = "text/markdown",
                            chooserTitle = "Share All Notes & Map of Content (${mdFiles.size} .md files)"
                        )
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
