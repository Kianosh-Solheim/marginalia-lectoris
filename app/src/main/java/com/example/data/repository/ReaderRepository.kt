package com.example.data.repository

import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.data.local.SettingsEntity
import com.example.widget.ReadingPosition
import kotlinx.coroutines.flow.Flow

class ReaderRepository(private val database: AppDatabase) {

    private val documentDao = database.documentDao()
    private val highlightDao = database.highlightDao()
    private val settingsDao = database.settingsDao()

    val allDocuments: Flow<List<DocumentEntity>> = documentDao.getAllDocuments()
    val allHighlights: Flow<List<HighlightEntity>> = highlightDao.getAllHighlights()
    val settingsFlow: Flow<SettingsEntity?> = settingsDao.getSettings()

    fun getDocumentsByCategory(category: String): Flow<List<DocumentEntity>> =
        documentDao.getDocumentsByCategory(category)

    fun searchDocuments(query: String): Flow<List<DocumentEntity>> =
        documentDao.searchDocuments(query)

    suspend fun getDocumentById(id: Long): DocumentEntity? =
        documentDao.getDocumentById(id)

    fun observeDocumentById(id: Long): Flow<DocumentEntity?> =
        documentDao.observeDocumentById(id)

    suspend fun insertDocument(document: DocumentEntity): Long =
        documentDao.insertDocument(document)

    suspend fun updateDocument(document: DocumentEntity) =
        documentDao.updateDocument(document)

    suspend fun updateReadingProgress(id: Long, page: Int, totalPages: Int, progress: Float) =
        documentDao.updateReadingProgress(id, page, totalPages, progress)

    suspend fun updateReadingPosition(id: Long, pos: ReadingPosition) =
        documentDao.updateReadingPosition(
            id = id,
            cfi = pos.cfi,
            paragraph = pos.paragraph,
            offset = pos.offset,
            loc = pos.loc,
            label = pos.label,
            snippet = pos.snippet,
            chapter = pos.chapter
        )

    suspend fun updateDocumentChapters(id: Long, chaptersJson: String) =
        documentDao.updateDocumentChapters(id, chaptersJson)

    suspend fun updateDocumentPdfCrop(id: Long, left: Float, top: Float, right: Float, bottom: Float) =
        documentDao.updateDocumentPdfCrop(id, left, top, right, bottom)

    suspend fun updateDocumentTextExtractionConfig(id: Long, mode: String, overridesJson: String, ocrLang: String) =
        documentDao.updateDocumentTextExtractionConfig(id, mode, overridesJson, ocrLang)

    suspend fun updateDocumentWidgetPin(id: Long, pinned: Boolean, pinOrder: Long = System.currentTimeMillis()) =
        documentDao.updateDocumentWidgetPin(id, pinned, pinOrder)

    suspend fun updateDocumentCoverAspectRatio(id: Long, aspectRatio: Float) =
        documentDao.updateDocumentCoverAspectRatio(id, aspectRatio)

    suspend fun updateDocumentEstimatedReadingMinutes(id: Long, estimatedMinutes: Int) =
        documentDao.updateDocumentEstimatedReadingMinutes(id, estimatedMinutes)

    val pinnedWidgetDocuments: Flow<List<DocumentEntity>> = documentDao.observePinnedWidgetDocuments()

    suspend fun getPinnedWidgetDocumentsSnapshot(): List<DocumentEntity> =
        documentDao.getPinnedWidgetDocumentsSnapshot()

    suspend fun getAllDocumentsOrderedByLastReadSnapshot(): List<DocumentEntity> =
        documentDao.getAllDocumentsOrderedByLastReadSnapshot()

    suspend fun deleteDocument(document: DocumentEntity) =
        documentDao.deleteDocument(document)

    fun getHighlightsForDocument(documentId: Long): Flow<List<HighlightEntity>> =
        highlightDao.getHighlightsForDocument(documentId)

    suspend fun getHighlightsListForDocument(documentId: Long): List<HighlightEntity> =
        highlightDao.getHighlightsListForDocument(documentId)

    suspend fun insertHighlight(highlight: HighlightEntity): Long =
        highlightDao.insertHighlight(highlight)

    suspend fun updateHighlight(highlight: HighlightEntity) =
        highlightDao.updateHighlight(highlight)

    suspend fun deleteHighlight(highlight: HighlightEntity) =
        highlightDao.deleteHighlight(highlight)

    suspend fun deleteHighlightById(id: Long) =
        highlightDao.deleteHighlightById(id)

    suspend fun getSettings(): SettingsEntity {
        return settingsDao.getSettingsSnapshot() ?: SettingsEntity()
    }

    suspend fun saveSettings(settings: SettingsEntity) =
        settingsDao.saveSettings(settings)
}
