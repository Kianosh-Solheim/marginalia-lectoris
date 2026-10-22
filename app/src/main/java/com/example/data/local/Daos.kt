package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY lastReadTimestamp DESC")
    fun getAllDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE category = :category ORDER BY lastReadTimestamp DESC")
    fun getDocumentsByCategory(category: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' ORDER BY lastReadTimestamp DESC")
    fun searchDocuments(query: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun getDocumentById(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents WHERE filePath = :filePath LIMIT 1")
    suspend fun getDocumentByFilePath(filePath: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE title = :title LIMIT 1")
    suspend fun getDocumentByTitle(title: String): DocumentEntity?

    @Query("SELECT * FROM documents")
    suspend fun getAllDocumentsSnapshot(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    fun observeDocumentById(id: Long): Flow<DocumentEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: DocumentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(documents: List<DocumentEntity>)

    @Update
    suspend fun updateDocument(document: DocumentEntity)

    @Query("UPDATE documents SET tags = :tags WHERE id = :id")
    suspend fun updateTags(id: Long, tags: String)

    @Query("UPDATE documents SET currentPage = :page, totalPages = :totalPages, readingProgress = :progress, lastReadTimestamp = :timestamp WHERE id = :id")
    suspend fun updateReadingProgress(id: Long, page: Int, totalPages: Int, progress: Float, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE documents SET lastPositionCfi = :cfi, lastPositionParagraph = :paragraph, lastPositionOffset = :offset, lastPositionLoc = :loc, lastPositionLabel = :label, lastPositionSnippet = :snippet, lastPositionChapter = :chapter WHERE id = :id")
    suspend fun updateReadingPosition(
        id: Long,
        cfi: String,
        paragraph: Int,
        offset: Int,
        loc: Int,
        label: String,
        snippet: String,
        chapter: String
    )

    @Query("UPDATE documents SET chaptersJson = :chaptersJson WHERE id = :id")
    suspend fun updateDocumentChapters(id: Long, chaptersJson: String)

    @Query("UPDATE documents SET author = :author WHERE id = :id")
    suspend fun updateAuthor(id: Long, author: String)

    @Query("UPDATE documents SET summary = :summary WHERE id = :id")
    suspend fun updateSummary(id: Long, summary: String)

    @Query("UPDATE documents SET pdfCropLeft = :left, pdfCropTop = :top, pdfCropRight = :right, pdfCropBottom = :bottom WHERE id = :id")
    suspend fun updateDocumentPdfCrop(id: Long, left: Float, top: Float, right: Float, bottom: Float)

    @Query("UPDATE documents SET pdfTextExtractionMode = :mode, pdfPageExtractionOverridesJson = :overridesJson, pdfOcrLanguage = :ocrLang WHERE id = :id")
    suspend fun updateDocumentTextExtractionConfig(id: Long, mode: String, overridesJson: String, ocrLang: String)

    @Query("UPDATE documents SET isPinnedToWidget = :pinned, widgetPinOrder = :pinOrder WHERE id = :id")
    suspend fun updateDocumentWidgetPin(id: Long, pinned: Boolean, pinOrder: Long = System.currentTimeMillis())

    @Query("UPDATE documents SET coverAspectRatio = :aspectRatio WHERE id = :id")
    suspend fun updateDocumentCoverAspectRatio(id: Long, aspectRatio: Float)

    @Query("UPDATE documents SET estimatedReadingMinutes = :estimatedMinutes WHERE id = :id")
    suspend fun updateDocumentEstimatedReadingMinutes(id: Long, estimatedMinutes: Int)

    @Query("SELECT * FROM documents WHERE isPinnedToWidget = 1 ORDER BY widgetPinOrder ASC, lastReadTimestamp DESC")
    fun observePinnedWidgetDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE isPinnedToWidget = 1 ORDER BY widgetPinOrder ASC, lastReadTimestamp DESC")
    suspend fun getPinnedWidgetDocumentsSnapshot(): List<DocumentEntity>

    @Query("SELECT * FROM documents ORDER BY lastReadTimestamp DESC")
    suspend fun getAllDocumentsOrderedByLastReadSnapshot(): List<DocumentEntity>

    @Delete
    suspend fun deleteDocument(document: DocumentEntity)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocumentById(id: Long)

    @Query("DELETE FROM documents")
    suspend fun deleteAllDocuments()

    @Query("SELECT COUNT(*) FROM documents")
    suspend fun getDocumentCount(): Int
}

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights WHERE documentId = :documentId ORDER BY pageNumber ASC, createdTimestamp ASC")
    fun getHighlightsForDocument(documentId: Long): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights ORDER BY createdTimestamp DESC")
    fun getAllHighlights(): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE documentId = :documentId ORDER BY pageNumber ASC, createdTimestamp ASC")
    suspend fun getHighlightsListForDocument(documentId: Long): List<HighlightEntity>

    @Query("SELECT * FROM highlights WHERE id = :id LIMIT 1")
    suspend fun getHighlightById(id: Long): HighlightEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHighlight(highlight: HighlightEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllHighlights(highlights: List<HighlightEntity>)

    @Update
    suspend fun updateHighlight(highlight: HighlightEntity)

    @Delete
    suspend fun deleteHighlight(highlight: HighlightEntity)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteHighlightById(id: Long)

    @Query("DELETE FROM highlights")
    suspend fun deleteAllHighlights()

    @Query("SELECT COUNT(*) FROM highlights WHERE documentId = :documentId")
    fun getHighlightCountForDocument(documentId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM highlights")
    fun getTotalHighlightCount(): Flow<Int>
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    fun getSettings(): Flow<SettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettingsSnapshot(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: SettingsEntity)

    @Query("UPDATE app_settings SET showContinueReadingCard = :show WHERE id = 1")
    suspend fun setShowContinueReadingCard(show: Boolean)
}

