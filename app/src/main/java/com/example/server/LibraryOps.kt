package com.example.server

import android.content.Context
import com.example.data.StorageManager
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.data.repository.ReaderRepository
import com.example.reader.MarkdownExporter
import com.example.ui.DocumentMetadataHelper
import com.example.widget.ContinueReadingWidgetProvider
import com.example.widget.PinnedBooksWidgetProvider
import com.example.widget.ReadingPositionBuilder
import java.io.File

/**
 * Library operations shared with the web server. These mirror ReaderViewModel's own functions
 * (same storage layout, notes files and widget refreshes) so a change made in the browser is
 * exactly what the same change in the app would do. Room flows update the app's screens.
 */
class LibraryOps(context: Context) {
    private val app: Context = context.applicationContext
    val repository = ReaderRepository(AppDatabase.getInstance(app))

    suspend fun addDocument(
        title: String,
        author: String,
        category: String,
        fileType: String,
        filePath: String,
        coverDrawableName: String? = null,
        totalPages: Int = 1,
        coverUri: String? = null
    ): DocumentEntity {
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
            PinnedBooksWidgetProvider.refreshAllWidgets(app)
            ContinueReadingWidgetProvider.refreshAll(app)
            return updatedExisting
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
        PinnedBooksWidgetProvider.refreshAllWidgets(app)
        ContinueReadingWidgetProvider.refreshAll(app)
        return created ?: doc.copy(id = newId)
    }

    suspend fun deleteDocument(document: DocumentEntity) {
        val curSettings = repository.getSettings()
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

    suspend fun updateDocumentInfo(
        document: DocumentEntity,
        newTitle: String,
        newAuthor: String,
        newTags: String,
        newCoverBitmap: android.graphics.Bitmap? = null,
        resetToDefaultCover: Boolean = false,
        newSummary: String = document.summary
    ): DocumentEntity {
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
                // Notes, overview, citation records and anything else in the folder move along too.
                oldFolder.listFiles()?.forEach { f ->
                    val t = File(newFolder, f.name)
                    if (!t.exists()) runCatching { if (f.isDirectory) f.copyRecursively(t) else f.copyTo(t) }
                }
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
        return updatedDoc
    }

    suspend fun addHighlight(
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
    ): HighlightEntity {
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
        val newId = repository.insertHighlight(highlight)

        // Save highlight.md inside the document folder: Readmark/[Books|Articles]/[Title]/highlight.md
        val curSettings = repository.getSettings()
        if (doc != null) {
            val allDocHighlights = repository.getHighlightsListForDocument(documentId)
            StorageManager.saveHighlightMarkdown(
                context = app,
                category = doc.category,
                documentTitle = doc.title,
                highlights = allDocHighlights,
                author = doc.author,
                customPath = curSettings.customStoragePath,
                storageType = curSettings.storageLocationType,
                tags = doc.tags
            )
        }
        return highlight.copy(id = newId)
    }

    suspend fun updateHighlight(highlight: HighlightEntity) {
        repository.updateHighlight(highlight)
        val doc = repository.getDocumentById(highlight.documentId)
        val curSettings = repository.getSettings()
        if (doc != null) {
            val allDocHighlights = repository.getHighlightsListForDocument(highlight.documentId)
            StorageManager.saveHighlightMarkdown(
                context = app,
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

    suspend fun deleteHighlight(highlight: HighlightEntity) {
        repository.deleteHighlight(highlight)
        val doc = repository.getDocumentById(highlight.documentId)
        val curSettings = repository.getSettings()
        if (doc != null) {
            val allDocHighlights = repository.getHighlightsListForDocument(highlight.documentId)
            StorageManager.saveHighlightMarkdown(
                context = app,
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

    suspend fun updateReadingProgress(documentId: Long, page: Int, totalPages: Int) {
        val safeTotal = totalPages.coerceAtLeast(1)
        val safePage = page.coerceIn(1, safeTotal)
        val progress = if (safeTotal > 1) {
            if (safePage >= safeTotal) 1f
            else ((safePage - 1).toFloat() / (safeTotal - 1).toFloat()).coerceIn(0f, 0.99f)
        } else {
            0f
        }
        repository.updateReadingProgress(documentId, safePage, safeTotal, progress)
        val currentDoc = repository.getDocumentById(documentId)
        if (currentDoc != null && (currentDoc.fileType.equals("PDF", ignoreCase = true) || currentDoc.filePath.endsWith(".pdf", ignoreCase = true))) {
            repository.updateReadingPosition(documentId, ReadingPositionBuilder.forPdf(safePage, safeTotal))
        }
        com.example.reader.FolderBooks.folderOf(documentId)?.let { com.example.reader.FolderBooks.sync(app, it) }
        PinnedBooksWidgetProvider.refreshAllWidgets(app)
        ContinueReadingWidgetProvider.refreshAllThrottled(app)
    }

    /**
     * Moves a library item between Books and Articles: its folder (file, cover, notes) moves to the
     * other shelf's folder and the item's category changes. A folder book only changes category.
     */
    suspend fun changeCategory(document: DocumentEntity, category: String): DocumentEntity {
        val cat = if (category.equals("ARTICLE", true)) "ARTICLE" else "BOOK"
        if (document.category.equals(cat, true)) return document
        if (document.fileType.equals("FOLDER", true)) {
            val updated = document.copy(category = cat)
            repository.updateDocument(updated)
            return updated
        }
        val settings = repository.getSettings()
        val oldFile = File(document.filePath)
        val oldFolder = oldFile.parentFile
        val newFolder = StorageManager.getDocumentFolder(app, cat, document.title, settings.customStoragePath, settings.storageLocationType)
        var filePath = document.filePath
        var coverUri = document.coverUri
        if (oldFolder != null && oldFolder.isDirectory && oldFolder.name != "Books" && oldFolder.name != "Articles" &&
            runCatching { oldFolder.canonicalPath != newFolder.canonicalPath }.getOrDefault(true)
        ) {
            oldFolder.listFiles()?.forEach { f ->
                val target = File(newFolder, f.name)
                val moved = if (f.isDirectory) runCatching { f.copyRecursively(target, overwrite = true); f.deleteRecursively(); true }.getOrDefault(false)
                else f.renameTo(target) || runCatching { f.copyTo(target, overwrite = true); f.delete(); true }.getOrDefault(false)
                if (moved) {
                    if (f.absolutePath == oldFile.absolutePath) filePath = target.absolutePath
                    if (coverUri != null && f.absolutePath == File(coverUri!!).absolutePath) coverUri = target.absolutePath
                }
            }
            if (oldFolder.listFiles().isNullOrEmpty()) oldFolder.delete()
        }
        val updated = document.copy(category = cat, filePath = filePath, coverUri = coverUri)
        repository.updateDocument(updated)
        PinnedBooksWidgetProvider.refreshAllWidgets(app)
        ContinueReadingWidgetProvider.refreshAll(app)
        return updated
    }
}
