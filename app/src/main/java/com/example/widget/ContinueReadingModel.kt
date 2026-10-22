package com.example.widget

import com.example.data.local.DocumentEntity
import java.util.Locale
import kotlin.math.roundToInt

/** Which kinds of documents a Continue Reading widget may show. Stored per widget id. */
enum class ContinueFilter {
    ALL, BOOKS, ARTICLES;

    companion object {
        fun fromName(name: String?): ContinueFilter =
            values().firstOrNull { it.name == name } ?: ALL
    }
}

/** Reflow-stable reading position saved by the reader on every page turn. */
data class ReadingPosition(
    val cfi: String = "",        // books: epubcfi(...) of the first visible text; articles/PDF: empty
    val paragraph: Int = 0,      // 1-based paragraph (¶) in chapter (books) or article
    val offset: Int = 0,         // articles: character offset inside the paragraph
    val loc: Int = 0,            // books: stable "EPUB loc." (150 characters per location)
    val label: String = "",      // display: "p. 118 · loc. 2,314" or "¶ 14 of 26"
    val snippet: String = "",    // first words of the visible text, max ~120 characters
    val chapter: String = ""     // books: chapter title
)

/** What a widget tap asks the reader to restore. Resolve in this order: cfi, paragraph + offset, loc, page. */
data class ResumeTarget(val position: ReadingPosition, val page: Int)

data class Selection(
    val primary: DocumentEntity?,
    val secondary: DocumentEntity?,
    val recent: List<DocumentEntity>,
    val recentBooks: List<DocumentEntity> = emptyList(),
    val recentArticles: List<DocumentEntity> = emptyList()
)

object ContinueReadingSelector {
    const val FINISHED_THRESHOLD = 0.99f

    fun isArticle(d: DocumentEntity) = d.category.equals("ARTICLE", ignoreCase = true)
    fun isStarted(d: DocumentEntity) = d.readingProgress > 0f || d.currentPage > 1
    fun isFinished(d: DocumentEntity) = d.readingProgress >= FINISHED_THRESHOLD

    private fun matches(d: DocumentEntity, filter: ContinueFilter) = when (filter) {
        ContinueFilter.ALL -> true
        ContinueFilter.BOOKS -> !isArticle(d)
        ContinueFilter.ARTICLES -> isArticle(d)
    }

    private fun startedSorted(docs: List<DocumentEntity>, filter: ContinueFilter) =
        docs.filter { matches(it, filter) && isStarted(it) }
            .sortedByDescending { it.lastReadTimestamp }

    /**
     * Selects the two most recently opened reading items (regardless of type, honoring filter)
     * plus separate Recent Books and Recent Articles shelf lists.
     */
    fun selectFeaturedAndShelves(
        docs: List<DocumentEntity>,
        filter: ContinueFilter,
        shelfMaxItems: Int = 10
    ): Selection {
        val started = startedSorted(docs, filter)
        val prioritized = started.filter { !isFinished(it) } + started.filter { isFinished(it) }
        val allSorted = if (prioritized.isNotEmpty()) {
            prioritized.distinctBy { it.id }
        } else {
            docs.filter { matches(it, filter) }.sortedByDescending { it.lastReadTimestamp }
        }

        val first = allSorted.getOrNull(0)
        val second = allSorted.getOrNull(1)
        val featuredIds = setOfNotNull(first?.id, second?.id)

        // All recent books and articles sorted by most recently read
        val allBooksSorted = (started.filter { !isArticle(it) } + docs.filter { !isArticle(it) }.sortedByDescending { it.lastReadTimestamp })
            .distinctBy { it.id }
        val allArticlesSorted = (started.filter { isArticle(it) } + docs.filter { isArticle(it) }.sortedByDescending { it.lastReadTimestamp })
            .distinctBy { it.id }

        val nonFeaturedBooks = allBooksSorted.filter { it.id !in featuredIds }
        val nonFeaturedArticles = allArticlesSorted.filter { it.id !in featuredIds }

        val booksShelf = when (filter) {
            ContinueFilter.ARTICLES -> emptyList()
            else -> (if (nonFeaturedBooks.isNotEmpty()) nonFeaturedBooks else allBooksSorted).take(shelfMaxItems)
        }
        val articlesShelf = when (filter) {
            ContinueFilter.BOOKS -> emptyList()
            else -> (if (nonFeaturedArticles.isNotEmpty()) nonFeaturedArticles else allArticlesSorted).take(shelfMaxItems)
        }

        val restRecents = allSorted.filter { it.id !in featuredIds }.take(shelfMaxItems)

        return Selection(
            primary = first,
            secondary = second,
            recent = restRecents,
            recentBooks = booksShelf,
            recentArticles = articlesShelf
        )
    }

    /** Phone layouts and tablet wide: one primary item (most recent unfinished) plus recents and shelves. */
    fun select(docs: List<DocumentEntity>, filter: ContinueFilter, recentCount: Int): Selection {
        val started = startedSorted(docs, filter)
        val primary = started.firstOrNull { !isFinished(it) } ?: started.firstOrNull()
        val secondary = started.filter { it.id != primary?.id }.let { rem ->
            rem.firstOrNull { !isFinished(it) } ?: rem.firstOrNull()
        }
        val shelves = selectFeaturedAndShelves(docs, filter)
        return Selection(
            primary = primary,
            secondary = secondary,
            recent = recentOf(started, setOfNotNull(primary?.id), recentCount),
            recentBooks = shelves.recentBooks,
            recentArticles = shelves.recentArticles
        )
    }

    /** Tablet large / dual: most recent unfinished book beside most recent unfinished article (or top 2 recent items) plus shelves. */
    fun selectDual(docs: List<DocumentEntity>, filter: ContinueFilter, recentCount: Int): Selection {
        val started = startedSorted(docs, filter)
        val unfinished = started.filter { !isFinished(it) }
        val book = unfinished.firstOrNull { !isArticle(it) }
        val article = unfinished.firstOrNull { isArticle(it) }
        val first = book ?: article ?: started.firstOrNull()
        val second = if (book != null && article != null) {
            article
        } else {
            started.firstOrNull { it.id != first?.id }
        }
        val shelves = selectFeaturedAndShelves(docs, filter)
        return Selection(
            primary = first,
            secondary = second,
            recent = recentOf(started, setOfNotNull(first?.id, second?.id), recentCount),
            recentBooks = shelves.recentBooks,
            recentArticles = shelves.recentArticles
        )
    }

    private fun recentOf(started: List<DocumentEntity>, excludeIds: Set<Long>, count: Int): List<DocumentEntity> {
        val rest = started.filter { it.id !in excludeIds }
        return (rest.filter { !isFinished(it) } + rest.filter { isFinished(it) }).take(count)
    }

    fun selectMostRecent(documents: List<DocumentEntity>, filter: ContinueFilter): DocumentEntity? {
        return select(documents, filter, recentCount = 0).primary
    }

    fun selectForTabletLarge(documents: List<DocumentEntity>, filter: ContinueFilter): Pair<DocumentEntity?, DocumentEntity?> {
        val sel = selectDual(documents, filter, recentCount = 0)
        return sel.primary to sel.secondary
    }
}

object ContinueFormatter {
    fun percent(d: DocumentEntity): Int = (d.readingProgress * 100f).roundToInt().coerceIn(0, 100)

    fun computeProgressRatio(d: DocumentEntity): Float = d.readingProgress.coerceIn(0f, 1f)

    fun timeLeft(d: DocumentEntity): String {
        if (d.estimatedReadingMinutes <= 0) return ""
        val left = (d.estimatedReadingMinutes * (1f - d.readingProgress)).roundToInt().coerceAtLeast(1)
        val hours = left / 60
        val minutes = left % 60
        return when {
            hours == 0 -> "$minutes min left"
            minutes == 0 -> "$hours h left"
            else -> "$hours h $minutes min left"
        }
    }

    fun formatTimeRemaining(d: DocumentEntity): String = timeLeft(d).ifBlank { "${percent(d)}% read" }

    /** "Ch. N · Title · p. 118 · loc. 2,314" or "Ch. title · p. 118 · loc. 2,314" for books, "¶ 14 of 26" for articles. */
    /** Author line: in an edited volume, the author(s) of the chapter being read. */
    fun author(d: DocumentEntity): String {
        if (com.example.reader.FolderBooks.isFolder(d)) {
            com.example.reader.FolderBooks.readingOf(d.id)?.author?.takeIf { it.isNotBlank() }?.let { return it }
            return com.example.reader.EditedVolumes.displayAuthor(d)
        }
        val r = runCatching { com.example.reader.EditedVolumes.reading(d) }.getOrNull()
        val names = r?.chapter?.authors?.replace("; ", ", ").orEmpty()
        return if (names.isNotBlank()) names else com.example.reader.EditedVolumes.displayAuthor(d)
    }

    fun locus(d: DocumentEntity): String {
        val isArticle = ContinueReadingSelector.isArticle(d)
        val label = d.lastPositionLabel.ifBlank { "page ${d.currentPage} of ${d.totalPages}" }
        if (com.example.reader.FolderBooks.isFolder(d)) {
            val r = com.example.reader.FolderBooks.readingOf(d.id)
                ?: return "${com.example.reader.FolderBooks.partsOf(d.id).size} chapters"
            val pct = if (r.progress > 0.01f) " · ${(r.progress * 100).toInt()}%" else ""
            return "Ch. ${r.number} · ${ellipsize(r.title, 40)}$pct" + (d.lastPositionLabel.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
        }
        runCatching { com.example.reader.EditedVolumes.reading(d) }.getOrNull()?.let { r ->
            val num = if (r.number.isNotBlank()) "Ch. ${r.number} · " else ""
            val pct = if (r.progress > 0.01f) " · ${(r.progress * 100).toInt()}%" else ""
            return "$num${ellipsize(r.chapter.title, 40)}$pct · $label"
        }
        val rawChapter = d.lastPositionChapter.trim()
        val formattedChapter = if (rawChapter.isNotEmpty() && !isArticle) {
            if (rawChapter.all { it.isDigit() }) {
                val shortTitle = ellipsize(d.title.trim(), 22)
                if (shortTitle.isNotEmpty()) "Ch. $rawChapter · $shortTitle" else "Ch. $rawChapter"
            } else {
                val numericPrefixMatch = Regex("^(\\d+)\\s*[:.\\-–—·]\\s*(.+)$").matchEntire(rawChapter)
                if (numericPrefixMatch != null) {
                    val num = numericPrefixMatch.groupValues[1]
                    val rest = ellipsize(numericPrefixMatch.groupValues[2].trim(), 22)
                    "Ch. $num · $rest"
                } else {
                    ellipsize(rawChapter, 26)
                }
            }
        } else null
        val parts = listOfNotNull(
            formattedChapter,
            label
        )
        return parts.joinToString(" · ")
    }

    fun formatLocationSummary(d: DocumentEntity): String =
        d.lastPositionLabel.ifBlank { "page ${d.currentPage} of ${d.totalPages.coerceAtLeast(1)}" }

    fun snippet(d: DocumentEntity): String {
        val rawSnippet = d.lastPositionSnippet.trim()
        if (rawSnippet.isNotEmpty()) {
            return if (rawSnippet.startsWith("…") || rawSnippet.startsWith("\"")) rawSnippet else "…$rawSnippet…"
        }
        val isArticle = ContinueReadingSelector.isArticle(d)
        if (isArticle) {
            val abstractText = d.summary.trim()
            if (abstractText.isNotEmpty()) {
                return ellipsize(abstractText, 160)
            }
        }
        // Fallback when reading passage is not yet captured: use reading position locus if started
        if (ContinueReadingSelector.isStarted(d)) {
            val locText = locus(d).trim()
            if (locText.isNotEmpty()) return locText
        }
        return ""
    }

    fun ellipsize(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"
}

/** Builds the position stored by the reader. Pure functions so they can be unit tested. */
object ReadingPositionBuilder {
    fun snippet(raw: String, maxChars: Int = 120): String {
        val clean = raw.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= maxChars) return clean
        val cut = clean.take(maxChars)
        val lastSpace = cut.lastIndexOf(' ')
        val trimmed = if (lastSpace > maxChars / 2) cut.substring(0, lastSpace) else cut
        return trimmed.trimEnd(',', ';', ':', '.', ' ')
    }

    fun forBook(
        cfi: String,
        paragraph: Int,
        loc: Int,
        printPageLabel: String?,
        fallbackPage: Int,
        totalPages: Int,
        chapter: String,
        firstText: String
    ): ReadingPosition {
        val page = printPageLabel?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { if (it.startsWith("p.", true) || it.startsWith("pp.", true)) it else "p. $it" }
            ?: "page $fallbackPage of $totalPages"
        val locText = if (loc > 0) "loc. " + String.format(Locale.US, "%,d", loc) else null
        return ReadingPosition(
            cfi = cfi,
            paragraph = paragraph,
            loc = loc,
            label = listOfNotNull(page, locText).joinToString(" · "),
            snippet = snippet(firstText),
            chapter = chapter.trim()
        )
    }

    fun forArticle(paragraph: Int, totalParagraphs: Int, offset: Int, firstText: String): ReadingPosition =
        ReadingPosition(
            paragraph = paragraph,
            offset = offset,
            label = if (totalParagraphs > 0) "¶ $paragraph of $totalParagraphs" else "¶ $paragraph",
            snippet = snippet(firstText)
        )

    fun forPdf(page: Int, totalPages: Int): ReadingPosition =
        ReadingPosition(label = "page $page of $totalPages")
}
