package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "documents",
    indices = [Index(value = ["lastReadTimestamp"])]
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val author: String,
    val filePath: String,
    val fileType: String, // "EPUB" or "PDF"
    val category: String, // "BOOK" or "ARTICLE"
    val coverDrawableName: String? = null,
    val coverUri: String? = null,
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val readingProgress: Float = 0f,
    val lastReadTimestamp: Long = System.currentTimeMillis(),
    val dateAdded: Long = System.currentTimeMillis(),
    val summary: String = "",
    val chaptersJson: String = "",
    val pdfCropLeft: Float = 0f,
    val pdfCropTop: Float = 0f,
    val pdfCropRight: Float = 1f,
    val pdfCropBottom: Float = 1f,
    val isPinnedToWidget: Boolean = false,
    val widgetPinOrder: Long = 0L,
    val pdfTextExtractionMode: String = "", // "", "AUTO", "ORIGINAL", "OCR", "HYBRID" ("" falls back to global setting)
    val pdfPageExtractionOverridesJson: String = "", // e.g. "1=OCR||4=ORIGINAL||7=REPAIR_DUPLICATES"
    val pdfOcrLanguage: String = "AUTO", // "AUTO", "en", "no", "de", "fr", "es", "la"
    val tags: String = "", // Comma-separated document tags (e.g. "philosophy, stoicism")
    val coverAspectRatio: Float = 0f, // Intrinsic cover image width/height ratio (0f = unknown, fallback to 2f/3f)
    val estimatedReadingMinutes: Int = 0, // Estimated total reading time in minutes based on file content and average reading speed
    val epubVersion: String = "",
    val epubLayoutType: String = "",
    val epubFeaturesSummary: String = "",
    val lastPositionCfi: String = "",
    val lastPositionParagraph: Int = 0,
    val lastPositionOffset: Int = 0,
    val lastPositionLoc: Int = 0,
    val lastPositionLabel: String = "",
    val lastPositionSnippet: String = "",
    val lastPositionChapter: String = ""
)

@Entity(
    tableName = "highlights",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["documentId"]),
        Index(value = ["createdTimestamp"])
    ]
)
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val documentId: Long,
    val title: String, // Becomes the markdown filename: "[title].md"
    val highlightedText: String, // The exact quote from document
    val userNotes: String = "", // Personal commentary / PKM notes
    val pageNumber: Int = 1,
    val printedPageLabel: String = "", // Printed book page number (e.g. "3", "xiv") if present
    val chapterTitle: String = "",
    val paragraphCitation: String = "", // Chapter + Paragraph citation (e.g. "Chapter 1: Foundations, ¶ 3")
    val pageEquivalent: String = "", // EPUB Page List / print-equivalent page marker (e.g. "p. 42", "pp. 42–43", "xiv") if present
    val epubCfi: String = "", // Canonical EPUB CFI 1.1 selection range (e.g. "epubcfi(/6/2[ch1]!/4/4[p2],/1:0,/1:42)")
    val colorHex: String = "#E4E4E7",
    val tags: String = "", // Comma-separated individual note tags (e.g. "epistemology, methodology")
    val createdTimestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "app_settings")
data class SettingsEntity(
    @PrimaryKey
    val id: Int = 1,
    val defaultTags: String = "Literature",
    val mocTitleFormat: String = "Notes on {{title}}",
    val wikiLinkStyle: Boolean = true, // [[Title]] vs [Title](Title.md)
    val includeDateInFrontmatter: Boolean = true,
    val includePageInFrontmatter: Boolean = true,
    val includeSourceInFrontmatter: Boolean = true,
    val includeAuthorInFrontmatter: Boolean = true,
    val customFrontmatterYaml: String = "",
    val readerFontSizeSp: Float = 18f,
    val readerTheme: String = "EINK",
    val volumeKeyNavigation: Boolean = true,
    val volumeKeyAnimation: Boolean = true,
    val readerTextAlign: String = "JUSTIFY", // "JUSTIFY" or "LEFT"
    val readerMarginDp: Int = 24,           // 12, 24, 36, 48
    val readerLineSpacing: Float = 1.6f,    // 1.3f, 1.6f, 1.9f
    val readerFontFamily: String = "SERIF", // "SERIF", "SANS", "MONO"
    val readerViewMode: String = "PAGINATED", // "PAGINATED" or "VERTICAL_SCROLL"
    val pageTurnAnimation: String = "PAGE_CURL", // "PAGE_CURL", "SLIDE", "NONE"
    val storageLocationType: String = "DOCUMENTS", // "DOCUMENTS", "APP_INTERNAL", "CUSTOM"
    val customStoragePath: String = "",
    val maxPinnedBooksInWidget: Int = 6,
    val ttsSpeechRate: Float = 1.05f,
    val ttsPitch: Float = 0.92f,
    val ttsAutoAdvancePage: Boolean = true,
    val ttsPreferredVoiceId: String = "",
    val ttsLanguage: String = "en-GB", // "en-GB" (British English) or "nb-NO" (Norwegian)
    val ttsMinimized: Boolean = false,
    val defaultPdfTextExtractionMode: String = "AUTO", // "AUTO", "ORIGINAL", "OCR", "HYBRID"
    val defaultPdfOcrLanguage: String = "AUTO", // "AUTO", "en", "no", "de", "fr", "es", "la"
    val showEpubPageEquivalents: Boolean = true,
    val paginateByEpubPageEquivalents: Boolean = false,
    val readerParagraphStyle: String = "BOTH", // "INDENT", "SPACED", "BOTH", "NONE"
    val showContinueReadingCard: Boolean = true,
    val readerColumnCount: Int = 1, // 1 = Single-column mode, 2 = Two-column mode
    val publisherStylesEnabled: Boolean = true, // true = Preserve publisher CSS styling, false = Custom typography overrides
    val readerTopMarginDp: Int = 16, // Top margin in dp when Paginated Pages mode is active (0..160)
    val readerBottomMarginDp: Int = 16 // Bottom margin in dp when Paginated Pages mode is active (0..160)
)

typealias AppSettings = SettingsEntity

