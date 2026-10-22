package com.example.reader

import android.graphics.Bitmap

/**
 * Semantic block types extracted from EPUB / XHTML / HTML documents.
 * Crucial distinction: only [PARAGRAPH] blocks are counted as ordinary text paragraphs (`<p>`)
 * when computing paragraph citations or navigating between paragraphs!
 */
enum class EpubSemanticBlockType {
    HEADING,
    PARAGRAPH,
    BLOCKQUOTE,
    LIST_ITEM,
    DEFINITION_TERM,
    DEFINITION_DESC,
    DEFINITION_LIST,
    TABLE,
    CALLOUT,
    FIGURE,
    FIGCAPTION,
    FOOTNOTE_DEF,
    DIVIDER,
    PREFORMATTED
}

/**
 * Represents an inline hyperlink, internal anchor link (`<a href="...">`), or footnote/glossary/bibliography reference
 * (`epub:type="noteref"` / `"glossref"` / `"biblioref"` or `role="doc-noteref"` / `"doc-glossref"` / `"doc-biblioref"`) inside a semantic block's text.
 */
data class EpubInlineLink(
    val startChar: Int,
    val endChar: Int,
    val label: String,
    val targetHref: String,        // Normalized file#fragment or #fragment
    val targetFileKey: String = "",
    val targetAnchorId: String = "",
    val isFootnoteRef: Boolean = false,
    val footnoteId: String = "",   // Explicit target anchor ID or footnote key
    val refType: String = ""       // e.g. "noteref", "glossref", "biblioref", or ""
)

/**
 * Represents a bidirectional footnote/endnote/glossary/bibliography target entry in an EPUB:
 * Main text (`sourceChapterIndex`, `sourceParagraphNumber`, `sourceAnchorId`, `sourceContextSnippet`)
 * <-> Note body (`noteId`, `displayMarker`, `noteText`, `footnoteAnchorId`).
 */
data class EpubFootnoteEntry(
    val noteId: String,                 // Primary ID (e.g. "fn1", "note-1", or "1")
    val displayMarker: String,          // Marker shown in text (e.g. "1", "*", "a")
    val noteText: String,               // Clean text of the footnote
    val footnoteFileKey: String = "",   // File where the footnote definition lives
    val footnoteAnchorId: String = "",  // HTML id of the footnote definition element (e.g. "fn1")
    val backlinkHref: String = "",      // Explicit href from a backlink <a href="#fnref1"> inside the footnote
    val backlinkAnchorId: String = "",  // Target anchor id in the main text (e.g. "fnref1")
    val sourceFileKey: String = "",     // File where the footnote was referenced
    val sourceChapterIndex: Int = 0,    // 0-based chapter index of reference in main text
    val sourceChapterTitle: String = "",
    val sourceParagraphNumber: Int = 0, // 1-based <p> paragraph number in the chapter where referenced
    val sourceAnchorId: String = "",    // HTML id on the noteref <a> or <sup> in the main text
    val sourceContextSnippet: String = "", // Surrounding sentence/paragraph in the main text
    val noteKind: String = "footnote"   // "footnote", "endnote", "rearnote", "glossary", "bibliography"
)

/**
 * Represents a hierarchical Table of Contents entry parsed from EPUB 3 `nav.xhtml`, EPUB 2 `toc.ncx`,
 * or built from the document's `h1`–`h6` heading hierarchy when no usable navigation document exists.
 */
data class EpubTocItem(
    val title: String,
    val href: String,              // e.g. "ch1.xhtml#sec-2"
    val fileKey: String,           // e.g. "ch1.xhtml"
    val anchorId: String = "",     // e.g. "sec-2"
    val level: Int = 0,            // 0 = top-level chapter, 1 = section, 2 = subsection, etc.
    val pageNumber: Int = 1        // Resolved 1-based page number after pagination
)

/**
 * Inline style types supported by Readium ts-toolkit / Readium CSS specification:
 * Italics, Bold, Underline, Strikethrough, Superscript, Subscript, Small, Monospace,
 * Mark (highlight), Drop Cap, and Small Caps.
 */
enum class EpubInlineStyleType {
    ITALIC,
    BOLD,
    BOLD_ITALIC,
    UNDERLINE,
    STRIKETHROUGH,
    SUPERSCRIPT,
    SUBSCRIPT,
    SMALL,
    MONOSPACE,
    MARK,
    DROP_CAP,
    SMALL_CAPS
}

/**
 * Represents a styled character range inside a semantic block's plain text.
 */
data class EpubInlineStyleSpan(
    val startChar: Int,
    val endChar: Int,
    val type: EpubInlineStyleType
)

/**
 * Result of extracting readable text along with Readium CSS inline formatting spans,
 * links, author alignment, and verse/stanza detection.
 */
data class FormattedTextExtraction(
    val cleanText: String,
    val inlineLinks: List<EpubInlineLink> = emptyList(),
    val inlineStyles: List<EpubInlineStyleSpan> = emptyList(),
    val authorAlignment: String = "",
    val isVerse: Boolean = false
)

/**
 * Preserves the exact semantic structure of an element from the EPUB XHTML throughout
 * parsing, pagination, and rendering.
 */
data class EpubSemanticBlock(
    val type: EpubSemanticBlockType,
    val text: String,
    val headingLevel: Int = 0,                  // 1..6 for HEADING
    val paragraphNumberInChapter: Int? = null,  // 1-based <p> index ONLY for true <p> PARAGRAPH blocks; null for headings, lists, blockquotes, captions, footnotes, etc.
    val anchorIds: List<String> = emptyList(),  // All HTML id/name attributes attached to or inside this block
    val inlineLinks: List<EpubInlineLink> = emptyList(),
    val inlineCodeRanges: List<IntRange> = emptyList(), // Character ranges of inline <code> spans
    val inlineStyles: List<EpubInlineStyleSpan> = emptyList(), // Readium CSS / ts-toolkit inline formatting spans
    val authorAlignment: String = "",           // "center", "right", "justify", "left", or ""
    val isVerse: Boolean = false,               // true for poetry / verse / stanzas
    val listMarker: String = "",                // e.g. "•" or "1."
    val listOrdered: Boolean = false,
    val listLevel: Int = 0,
    val tableHeaders: List<String> = emptyList(),
    val tableRows: List<List<String>> = emptyList(),
    val definitionItems: List<Pair<String, List<String>>> = emptyList(), // (dt, list of dd) for DEFINITION_LIST
    val calloutLabel: String = "",              // "NOTE", "TIP", "WARNING", or "SIDEBAR" for CALLOUT
    val imagePath: String = "",                 // Internal ZIP path for FIGURE / <img>
    val imageAlt: String = "",
    val captionText: String = "",
    val footnoteId: String = "",                // Set when type == FOOTNOTE_DEF (or when block is a footnote body)
    val backlinkAnchorId: String = "",          // Set when type == FOOTNOTE_DEF and has a backlink to main text
    val pageEquivalent: String = "",            // Preserved 1:1 with paragraphPageEquivalents
    val fileKey: String = ""
) {
    /**
     * Only genuine `<p>` text paragraphs (`type == EpubSemanticBlockType.PARAGRAPH` with a valid
     * `paragraphNumberInChapter`) count as ordinary paragraphs when counting or navigating between paragraphs.
     */
    val isCountedParagraph: Boolean
        get() = type == EpubSemanticBlockType.PARAGRAPH && paragraphNumberInChapter != null
}

