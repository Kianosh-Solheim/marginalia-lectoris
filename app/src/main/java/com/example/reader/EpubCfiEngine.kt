package com.example.reader

import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import java.io.File

/**
 * Represents a single step in an EPUB Canonical Fragment Identifier (CFI) path,
 * e.g. `/6`, `/4[ch1]`, `/2[para-1]`.
 */
data class EpubCfiStep(
    val stepIndex: Int,
    val idAssertion: String = ""
)

/**
 * Represents a terminal character offset in an EPUB CFI path or range endpoint,
 * e.g. `/1:14` or `/1:14[pre,post]`.
 */
data class EpubCfiTerminalOffset(
    val textNodeStep: Int = 1,
    val charOffset: Int = 0,
    val textAssertionPrefix: String = "",
    val textAssertionSuffix: String = ""
)

/**
 * Structured representation of a parsed W3C / IDPF EPUB Canonical Fragment Identifier (EPUB CFI 1.1).
 *
 * Supports both:
 * - Single-element selection ranges: `epubcfi(/6/S[spineId]!/4/B[blockId],/1:start,/1:end)`
 * - Multi-element selection ranges: `epubcfi(/6/S[spineId]!/4,/B1[id1]/1:start,/B2[id2]/1:end)`
 * - Point locations: `epubcfi(/6/S[spineId]!/4/B[blockId]/1:offset)`
 */
data class ParsedEpubCfi(
    val rawCfi: String,
    val spineStep: Int,
    val spineIdAssertion: String = "",
    val bodyStep: Int = 4,
    val startElementSteps: List<EpubCfiStep>,
    val endElementSteps: List<EpubCfiStep>,
    val startOffset: EpubCfiTerminalOffset,
    val endOffset: EpubCfiTerminalOffset,
    val isRange: Boolean
) {
    val fragmentUri: String
        get() = if (rawCfi.startsWith("#")) rawCfi else "#$rawCfi"

    val canonicalCfi: String
        get() = rawCfi.removePrefix("#")

    /** 0-based chapter index in the EPUB `<spine>` (`(spineStep / 2) - 1`). */
    val spineIndex: Int
        get() = ((spineStep / 2) - 1).coerceAtLeast(0)

    val startBlockStep: Int
        get() = startElementSteps.lastOrNull()?.stepIndex ?: 2

    val endBlockStep: Int
        get() = endElementSteps.lastOrNull()?.stepIndex ?: startBlockStep

    /** 0-based block index inside `<body>` (`(startBlockStep / 2) - 1`). */
    val startBlockIndex: Int
        get() = ((startBlockStep / 2) - 1).coerceAtLeast(0)

    /** 0-based end block index inside `<body>` (`(endBlockStep / 2) - 1`). */
    val endBlockIndex: Int
        get() = ((endBlockStep / 2) - 1).coerceAtLeast(startBlockIndex)

    val startBlockIdAssertion: String
        get() = startElementSteps.lastOrNull()?.idAssertion.orEmpty()

    val endBlockIdAssertion: String
        get() = endElementSteps.lastOrNull()?.idAssertion.orEmpty()

    val startCharOffset: Int
        get() = startOffset.charOffset.coerceAtLeast(0)

    val endCharOffset: Int
        get() = endOffset.charOffset.coerceAtLeast(startCharOffset)
}

/**
 * Result of resolving an EPUB CFI against a parsed [EpubBook].
 */
data class ResolvedEpubCfiSelection(
    val chapterIndex: Int,
    val chapterId: String,
    val chapterTitle: String,
    val startBlockIndex: Int,
    val endBlockIndex: Int,
    val startParagraphNumber: Int,
    val endParagraphNumber: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val extractedText: String,
    val canonicalCfi: String
) {
    val fragmentCfi: String
        get() = if (canonicalCfi.startsWith("#")) canonicalCfi else "#$canonicalCfi"
}

/**
 * Represents a point in an EPUB document for EPUB CFI 1.1 construction and resolution.
 *
 * @param spineIndex 0-based index into `<spine>` (serialized as `2 * (spineIndex + 1)`, with `[spineId]` assertion when present).
 * @param spineId Optional XML ID of the `<itemref>` or `<item>` in the OPF spine.
 * @param elementPath Path of child element steps inside the content document (starting from `<body>` `/4` or relative to `<body>`).
 *                    Each [EpubCfiStep.stepIndex] is an even integer (`2 * 1-based element index`) with optional `[idAssertion]`.
 * @param textNodeStep Odd step addressing text position between/within elements (defaults to `1`).
 * @param charOffset Character offset following `:` (e.g. `/1:0`).
 */
data class EpubCfiPoint(
    val spineIndex: Int,
    val spineId: String = "",
    val elementPath: List<EpubCfiStep>,
    val textNodeStep: Int = 1,
    val charOffset: Int = 0
) {
    constructor(
        spineIndex: Int,
        elementPath: String,
        charOffset: Int = 0,
        spineId: String = "",
        textNodeStep: Int = 1
    ) : this(
        spineIndex = spineIndex,
        spineId = spineId,
        elementPath = EpubCfiEngine.parseElementPathSteps(elementPath),
        textNodeStep = textNodeStep,
        charOffset = charOffset
    )
}

/**
 * Result type returned by [EpubCfiEngine.parseCfi], returning an [Error] on malformed input.
 */
sealed class EpubCfiParseResult {
    data class Success(val parsed: ParsedEpubCfi) : EpubCfiParseResult()
    data class Error(val message: String, val rawInput: String = "") : EpubCfiParseResult()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error

    fun getOrNull(): ParsedEpubCfi? = (this as? Success)?.parsed
    fun errorMessageOrNull(): String? = (this as? Error)?.message
}

/**
 * Result of [EpubCfiEngine.resolveCfi]: spine index, element path, and char offset,
 * plus the [EpubPage] containing it (and 0-based [pageIndex]).
 */
data class EpubCfiResolution(
    val spineIndex: Int,
    val spineId: String = "",
    val elementPathSteps: List<EpubCfiStep>,
    val charOffset: Int,
    val endElementPathSteps: List<EpubCfiStep> = elementPathSteps,
    val endCharOffset: Int = charOffset,
    val isRange: Boolean = false,
    val page: EpubPage? = null,
    val pageIndex: Int = -1,
    val canonicalCfi: String = ""
) {
    val elementPath: String
        get() = elementPathSteps.joinToString("") { step ->
            val escaped = EpubCfiEngine.escapeCfiAssertion(step.idAssertion)
            if (escaped.isNotBlank()) "/${step.stepIndex}[$escaped]" else "/${step.stepIndex}"
        }
}

/**
 * Full implementation of the W3C / IDPF EPUB Canonical Fragment Identifier (CFI) 1.1 specification
 * (`epubcfi(...)`) for machine-readable, reflow-independent highlight and selection persistence
 * across different reader engine versions (Compose Reflow Paginated, Continuous Vertical Scroll,
 * and KOReader WebKit Multi-Column Engine).
 */
object EpubCfiEngine {

    private val CFI_SPECIAL_CHARS_REGEX = Regex("([\\^\\[\\],();])")
    private val CFI_UNESCAPE_REGEX = Regex("\\^([\\^\\[\\],();])")

    /**
     * Escapes special characters (`^`, `[`, `]`, `,`, `(`, `)`, `;`) in XML IDs or text assertions
     * using the circumflex (`^`) escape character per EPUB CFI 1.1 Section 3.1.1.
     */
    fun escapeCfiAssertion(raw: String): String {
        if (raw.isEmpty()) return ""
        return CFI_SPECIAL_CHARS_REGEX.replace(raw.trim()) { m -> "^${m.value}" }
    }

    /**
     * Unescapes circumflex-escaped characters (`^[`, `^]`, `^^`, `^,`, etc.) in an EPUB CFI assertion.
     */
    fun unescapeCfiAssertion(escaped: String): String {
        if (escaped.isEmpty()) return ""
        return CFI_UNESCAPE_REGEX.replace(escaped) { m -> m.groupValues[1] }
    }

    /**
     * Normalizes an EPUB CFI string into canonical `epubcfi(...)` or `#epubcfi(...)` form.
     */
    fun normalizeCfi(rawCfi: String, includeHashPrefix: Boolean = false): String {
        val trimmed = rawCfi.trim()
        if (trimmed.isEmpty()) return ""
        val withoutHash = trimmed.removePrefix("#").trim()
        val core = when {
            withoutHash.startsWith("epubcfi(", ignoreCase = true) && withoutHash.endsWith(")") -> {
                "epubcfi(" + withoutHash.substring(8, withoutHash.length - 1).trim() + ")"
            }
            withoutHash.startsWith("/6/") -> {
                "epubcfi($withoutHash)"
            }
            else -> withoutHash
        }
        return if (includeHashPrefix) "#$core" else core
    }

    /**
     * Checks whether [candidate] is a valid, machine-readable EPUB CFI (`epubcfi(/6/...!... )`).
     */
    fun isValidEpubCfi(candidate: String): Boolean {
        return parseCfi(candidate) is EpubCfiParseResult.Success
    }

    private fun normalizeElementPathWithBody(rawSteps: List<EpubCfiStep>): List<EpubCfiStep> {
        val coerced = rawSteps.map { step ->
            val evenStep = if (step.stepIndex <= 0) 2 else if (step.stepIndex % 2 != 0) step.stepIndex + 1 else step.stepIndex
            step.copy(stepIndex = evenStep)
        }
        return when {
            coerced.isEmpty() -> listOf(EpubCfiStep(4), EpubCfiStep(2))
            coerced.first().stepIndex == 4 && coerced.size >= 2 -> coerced
            else -> listOf(EpubCfiStep(4)) + coerced
        }
    }

    private fun formatStep(step: EpubCfiStep): String {
        val escapedId = escapeCfiAssertion(step.idAssertion)
        return if (escapedId.isNotBlank()) "/${step.stepIndex}[$escapedId]" else "/${step.stepIndex}"
    }

    fun parseElementPathSteps(rawElementPath: String): List<EpubCfiStep> {
        val segments = splitOutsideBrackets(rawElementPath.trim(), '/').filter { it.isNotBlank() }
        val steps = segments.mapNotNull { parseSingleStepSegment(it) }
        return if (steps.isEmpty()) listOf(EpubCfiStep(4), EpubCfiStep(2)) else steps
    }

    /**
     * (a) Builds a canonical EPUB CFI 1.1 point identifier:
     * - Package step: `/6` addresses `<spine>`.
     * - Spine item step: `2 * (spineIndex + 1)` with `[spineId]` assertion when non-blank, e.g. `/6/4[chap01]`.
     * - `!` marks the indirection into the content document.
     * - Inside the content document: even steps address child elements (`2 * 1-based element index`, with `[id]` assertion when present);
     *   odd step `/1` addresses text position, followed by `:charOffset`.
     * - Always wraps output as `epubcfi(...)`.
     */
    fun buildPointCfi(
        spineIndex: Int,
        elementPath: List<EpubCfiStep>,
        charOffset: Int = 0,
        spineId: String = "",
        textNodeStep: Int = 1,
        includeHashPrefix: Boolean = false
    ): String {
        val safeSpineStep = ((spineIndex.coerceAtLeast(0) + 1) * 2).coerceAtLeast(2)
        val escapedSpineId = escapeCfiAssertion(spineId)
        val spineStepStr = if (escapedSpineId.isNotBlank()) "/$safeSpineStep[$escapedSpineId]" else "/$safeSpineStep"
        val fullElementSteps = normalizeElementPathWithBody(elementPath)
        val elementPathStr = fullElementSteps.joinToString("") { formatStep(it) }
        val safeTextStep = if (textNodeStep <= 0) 1 else if (textNodeStep % 2 == 0) textNodeStep - 1 else textNodeStep
        val safeCharOffset = charOffset.coerceAtLeast(0)
        val cfiBody = "epubcfi(/6$spineStepStr!$elementPathStr/$safeTextStep:$safeCharOffset)"
        return if (includeHashPrefix) "#$cfiBody" else cfiBody
    }

    /**
     * Overload accepting a String elementPath (e.g. `"/4/6[p3]"` or `"/6[p3]"`).
     */
    fun buildPointCfi(
        spineIndex: Int,
        elementPath: String,
        charOffset: Int = 0,
        spineId: String = "",
        textNodeStep: Int = 1,
        includeHashPrefix: Boolean = false
    ): String {
        return buildPointCfi(
            spineIndex = spineIndex,
            elementPath = parseElementPathSteps(elementPath),
            charOffset = charOffset,
            spineId = spineId,
            textNodeStep = textNodeStep,
            includeHashPrefix = includeHashPrefix
        )
    }

    /**
     * Overload accepting 1-based or even element step integers as `elementPath` (e.g. `intArrayOf(4, 2)` or `intArrayOf(2)`).
     */
    fun buildPointCfi(
        spineIndex: Int,
        elementPath: IntArray,
        charOffset: Int = 0,
        spineId: String = ""
    ): String {
        val steps = elementPath.map { raw ->
            val even = if (raw <= 0) 2 else if (raw % 2 == 0) raw else raw * 2
            EpubCfiStep(even, "")
        }
        return buildPointCfi(
            spineIndex = spineIndex,
            elementPath = steps,
            charOffset = charOffset,
            spineId = spineId
        )
    }

    /**
     * Constructs a canonical EPUB CFI 1.1 point location (`epubcfi(/6/S[spineId]!/4/B[blockId]/1:offset)`).
     */
    fun buildBlockPointCfi(
        spineIndex: Int,
        spineId: String = "",
        blockIndex: Int,
        blockId: String = "",
        charOffset: Int = 0,
        includeHashPrefix: Boolean = false
    ): String {
        val safeBlockStep = ((blockIndex.coerceAtLeast(0) + 1) * 2).coerceAtLeast(2)
        return buildPointCfi(
            spineIndex = spineIndex,
            elementPath = listOf(EpubCfiStep(4), EpubCfiStep(safeBlockStep, blockId)),
            charOffset = charOffset,
            spineId = spineId,
            includeHashPrefix = includeHashPrefix
        )
    }

    /**
     * (b) Builds a canonical EPUB CFI 1.1 range identifier in the form:
     * `epubcfi(commonParent,startRelative,endRelative)`
     * Always wrapped as `epubcfi(...)`.
     */
    fun buildRangeCfi(
        start: EpubCfiPoint,
        end: EpubCfiPoint,
        includeHashPrefix: Boolean = false
    ): String {
        val startCfi = buildPointCfi(
            spineIndex = start.spineIndex,
            elementPath = start.elementPath,
            charOffset = start.charOffset,
            spineId = start.spineId,
            textNodeStep = start.textNodeStep
        )
        val endCfi = buildPointCfi(
            spineIndex = end.spineIndex,
            elementPath = end.elementPath,
            charOffset = end.charOffset,
            spineId = end.spineId.ifBlank { start.spineId },
            textNodeStep = end.textNodeStep
        )
        return buildRangeCfi(startCfi, endCfi, includeHashPrefix = includeHashPrefix)
    }

    /**
     * (b) Builds a canonical EPUB CFI 1.1 range identifier from two point CFIs (`start` and `end`)
     * in the form `epubcfi(commonParent,startRelative,endRelative)`.
     */
    fun buildRangeCfi(
        start: String,
        end: String,
        includeHashPrefix: Boolean = false
    ): String {
        val parsedStart = (parseCfi(start) as? EpubCfiParseResult.Success)?.parsed
        val parsedEnd = (parseCfi(end) as? EpubCfiParseResult.Success)?.parsed
        if (parsedStart == null || parsedEnd == null) {
            return normalizeCfi(start, includeHashPrefix = includeHashPrefix)
        }

        val spineStep = parsedStart.spineStep
        val escapedSpineId = escapeCfiAssertion(parsedStart.spineIdAssertion)
        val spineStepStr = if (escapedSpineId.isNotBlank()) "/$spineStep[$escapedSpineId]" else "/$spineStep"
        val packagePrefix = "/6$spineStepStr!/4"

        val startSteps = parsedStart.startElementSteps
        val endSteps = parsedEnd.startElementSteps

        var commonLen = 0
        val minLen = minOf(startSteps.size, endSteps.size)
        while (commonLen < minLen) {
            val s = startSteps[commonLen]
            val e = endSteps[commonLen]
            if (s.stepIndex == e.stepIndex && s.idAssertion == e.idAssertion) {
                commonLen++
            } else {
                break
            }
        }

        val commonStepsStr = startSteps.take(commonLen).joinToString("") { formatStep(it) }
        val startRemStepsStr = startSteps.drop(commonLen).joinToString("") { formatStep(it) }
        val endRemStepsStr = endSteps.drop(commonLen).joinToString("") { formatStep(it) }

        val sTextStep = parsedStart.startOffset.textNodeStep.coerceAtLeast(1)
        val eTextStep = parsedEnd.startOffset.textNodeStep.coerceAtLeast(1)
        val sChar = parsedStart.startCharOffset
        val eChar = if (commonLen == startSteps.size && commonLen == endSteps.size) {
            parsedEnd.startCharOffset.coerceAtLeast(sChar + 1)
        } else {
            parsedEnd.startCharOffset.coerceAtLeast(0)
        }

        val commonParent = "$packagePrefix$commonStepsStr"
        val startRelative = "$startRemStepsStr/$sTextStep:$sChar"
        val endRelative = "$endRemStepsStr/$eTextStep:$eChar"
        val cfiBody = "epubcfi($commonParent,$startRelative,$endRelative)"
        return if (includeHashPrefix) "#$cfiBody" else cfiBody
    }

    /**
     * Constructs a canonical EPUB CFI 1.1 range (or point) identifier from structural coordinates:
     * - Single-element selection range: `epubcfi(/6/S[spineId]!/4/B[blockId],/1:start,/1:end)`
     * - Multi-element selection range: `epubcfi(/6/S[spineId]!/4,/B1[id1]/1:start,/B2[id2]/1:end)`
     */
    fun buildSelectionRangeCfi(
        spineIndex: Int,
        spineId: String = "",
        startBlockIndex: Int,
        startBlockId: String = "",
        startCharOffset: Int,
        endBlockIndex: Int = startBlockIndex,
        endBlockId: String = startBlockId,
        endCharOffset: Int,
        includeHashPrefix: Boolean = false
    ): String {
        val safeStartBlockStep = ((startBlockIndex.coerceAtLeast(0) + 1) * 2).coerceAtLeast(2)
        val safeEndBlockStep = ((endBlockIndex.coerceAtLeast(startBlockIndex.coerceAtLeast(0)) + 1) * 2).coerceAtLeast(safeStartBlockStep)
        val safeStartChar = startCharOffset.coerceAtLeast(0)
        val safeEndChar = if (safeStartBlockStep == safeEndBlockStep) {
            endCharOffset.coerceAtLeast(safeStartChar + 1)
        } else {
            endCharOffset.coerceAtLeast(1)
        }

        val startPt = EpubCfiPoint(
            spineIndex = spineIndex,
            spineId = spineId,
            elementPath = listOf(EpubCfiStep(4), EpubCfiStep(safeStartBlockStep, startBlockId)),
            charOffset = safeStartChar
        )
        val endPt = EpubCfiPoint(
            spineIndex = spineIndex,
            spineId = spineId,
            elementPath = listOf(EpubCfiStep(4), EpubCfiStep(safeEndBlockStep, endBlockId)),
            charOffset = safeEndChar
        )
        return buildRangeCfi(startPt, endPt, includeHashPrefix = includeHashPrefix)
    }

    /**
     * Generates a canonical EPUB CFI 1.1 selection range for a passage in an [EpubBook] (or from
     * fallback chapter/paragraph citation metadata when the EPUB archive is not loaded in memory).
     */
    fun generateCfiForBookSelection(
        epubBook: EpubBook?,
        quote: String,
        fallbackChapterTitle: String = "",
        fallbackParagraphCitation: String = "",
        explicitStartChar: Int? = null,
        explicitEndChar: Int? = null,
        includeHashPrefix: Boolean = false
    ): String {
        val cleanQuote = quote.trim()
        val rawCite = fallbackParagraphCitation.trim()
        val rawChapter = fallbackChapterTitle.trim()

        val paraNumsMatch = Regex("¶+\\s*(\\d+)(?:\\s*[–\\-]\\s*(\\d+))?").find(rawCite)
        val parsedStartPara = paraNumsMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val parsedEndPara = paraNumsMatch?.groupValues?.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(parsedStartPara) ?: parsedStartPara

        val chapterNumMatch = Regex("(?:chapter|kapittel|ch\\.?)\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(rawChapter.ifBlank { rawCite })
        val fallbackChapterOrdinal = chapterNumMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1

        var spineIndex = (fallbackChapterOrdinal - 1).coerceAtLeast(0)
        var spineItemId = ""
        var startBlockIndex = (parsedStartPara - 1).coerceAtLeast(0)
        var endBlockIndex = (parsedEndPara - 1).coerceAtLeast(startBlockIndex)
        var startBlockId = ""
        var endBlockId = ""
        var startCharOffset = explicitStartChar?.coerceAtLeast(0) ?: 0
        var endCharOffset = explicitEndChar?.coerceAtLeast(startCharOffset + 1) ?: cleanQuote.length.coerceAtLeast(1)

        if (epubBook != null && epubBook.chapters.isNotEmpty()) {
            val matchedChapterIdx = findMatchingChapterIndex(
                epubBook = epubBook,
                quote = cleanQuote,
                fallbackChapterTitle = rawChapter.ifBlank { rawCite.substringBefore(", ¶").trim() },
                fallbackOrdinal = fallbackChapterOrdinal
            )
            val chapter = epubBook.chapters[matchedChapterIdx]
            spineIndex = matchedChapterIdx
            spineItemId = chapter.id.trim()

            val quoteSegments = cleanQuote.split(Regex("\n\\s*\n+")).map { it.trim() }.filter { it.isNotBlank() }
            val firstSegment = quoteSegments.firstOrNull() ?: cleanQuote
            val lastSegment = quoteSegments.lastOrNull() ?: cleanQuote

            if (chapter.semanticBlocks.isNotEmpty()) {
                val (sIdx, eIdx) = findMatchingSemanticBlockRange(
                    blocks = chapter.semanticBlocks,
                    firstSegment = firstSegment,
                    lastSegment = lastSegment,
                    fallbackStartPara = parsedStartPara,
                    fallbackEndPara = parsedEndPara
                )
                startBlockIndex = sIdx
                endBlockIndex = eIdx
                val startBlock = chapter.semanticBlocks[sIdx]
                val endBlock = chapter.semanticBlocks[eIdx]
                startBlockId = startBlock.anchorIds.firstOrNull().orEmpty()
                endBlockId = endBlock.anchorIds.firstOrNull().orEmpty()

                val foundStart = findQuoteOffsetInBlockText(startBlock.text, firstSegment)
                if (foundStart >= 0) {
                    startCharOffset = foundStart
                    if (sIdx == eIdx) {
                        endCharOffset = (foundStart + firstSegment.length).coerceAtLeast(startCharOffset + 1)
                    }
                } else if (explicitStartChar != null) {
                    startCharOffset = explicitStartChar.coerceAtLeast(0)
                    if (sIdx == eIdx) {
                        endCharOffset = (explicitEndChar ?: (startCharOffset + firstSegment.length)).coerceAtLeast(startCharOffset + 1)
                    }
                }

                if (sIdx != eIdx) {
                    val foundEnd = findQuoteOffsetInBlockText(endBlock.text, lastSegment)
                    endCharOffset = if (foundEnd >= 0) {
                        (foundEnd + lastSegment.length).coerceAtLeast(1)
                    } else {
                        lastSegment.length.coerceAtLeast(1)
                    }
                }
            } else if (chapter.paragraphs.isNotEmpty()) {
                val (sIdx, eIdx) = findMatchingParagraphRange(
                    paragraphs = chapter.paragraphs,
                    firstSegment = firstSegment,
                    lastSegment = lastSegment,
                    fallbackStartPara = parsedStartPara,
                    fallbackEndPara = parsedEndPara
                )
                startBlockIndex = sIdx
                endBlockIndex = eIdx
                val sText = chapter.paragraphs[sIdx]
                val eText = chapter.paragraphs[eIdx]
                val foundStart = findQuoteOffsetInBlockText(sText, firstSegment)
                if (foundStart >= 0) {
                    startCharOffset = foundStart
                    if (sIdx == eIdx) {
                        endCharOffset = (foundStart + firstSegment.length).coerceAtLeast(startCharOffset + 1)
                    }
                }
                if (sIdx != eIdx) {
                    val foundEnd = findQuoteOffsetInBlockText(eText, lastSegment)
                    endCharOffset = if (foundEnd >= 0) {
                        (foundEnd + lastSegment.length).coerceAtLeast(1)
                    } else {
                        lastSegment.length.coerceAtLeast(1)
                    }
                }
            }
        }

        return buildSelectionRangeCfi(
            spineIndex = spineIndex,
            spineId = spineItemId,
            startBlockIndex = startBlockIndex,
            startBlockId = startBlockId,
            startCharOffset = startCharOffset,
            endBlockIndex = endBlockIndex,
            endBlockId = endBlockId,
            endCharOffset = endCharOffset,
            includeHashPrefix = includeHashPrefix
        )
    }

    /**
     * Generates or normalizes the canonical EPUB CFI 1.1 selection string for a [HighlightEntity] and [DocumentEntity].
     * If [highlight.epubCfi] is already populated and valid, returns it in normalized form; otherwise computes it.
     */
    fun getOrComputeHighlightCfi(
        highlight: HighlightEntity,
        document: DocumentEntity?,
        epubBook: EpubBook? = null,
        includeHashPrefix: Boolean = false
    ): String {
        if (highlight.epubCfi.isNotBlank() && isValidEpubCfi(highlight.epubCfi)) {
            return normalizeCfi(highlight.epubCfi, includeHashPrefix = includeHashPrefix)
        }
        val loadedBook = epubBook ?: run {
            val path = document?.filePath.orEmpty()
            if (path.isNotBlank()) {
                val file = File(path)
                val isEpub = document?.fileType.equals("EPUB", ignoreCase = true) || file.extension.equals("epub", ignoreCase = true)
                if (isEpub && file.exists() && file.isFile) {
                    try {
                        file.inputStream().use { EpubParser.parseEpub(it) }
                    } catch (_: Throwable) {
                        null
                    }
                } else null
            } else null
        }

        return generateCfiForBookSelection(
            epubBook = loadedBook,
            quote = highlight.highlightedText,
            fallbackChapterTitle = highlight.chapterTitle,
            fallbackParagraphCitation = highlight.paragraphCitation,
            includeHashPrefix = includeHashPrefix
        )
    }

    /**
     * (c) Parses an EPUB CFI 1.1 string (`epubcfi(...)`), returning an [EpubCfiParseResult.Error]
     * on malformed input and [EpubCfiParseResult.Success] when valid.
     */
    fun parseCfi(cfi: String): EpubCfiParseResult {
        val raw = cfi.trim()
        if (raw.isEmpty()) {
            return EpubCfiParseResult.Error("CFI string is empty.", cfi)
        }
        val trimmed = raw.removePrefix("#").trim()
        if (!trimmed.startsWith("epubcfi(", ignoreCase = true) || !trimmed.endsWith(")")) {
            return EpubCfiParseResult.Error("CFI must be wrapped in epubcfi(...).", cfi)
        }
        val inner = trimmed.substring(8, trimmed.length - 1).trim()
        if (inner.isEmpty()) {
            return EpubCfiParseResult.Error("CFI body inside epubcfi(...) is empty.", cfi)
        }
        if (!inner.startsWith("/")) {
            return EpubCfiParseResult.Error("CFI path must start with '/'.", cfi)
        }

        // Validate balanced unescaped brackets
        var bracketDepth = 0
        var idx = 0
        while (idx < inner.length) {
            val ch = inner[idx]
            if (ch == '^') {
                if (idx + 1 >= inner.length) {
                    return EpubCfiParseResult.Error("Trailing escape character '^' in CFI.", cfi)
                }
                idx += 2
                continue
            }
            if (ch == '[') bracketDepth++
            if (ch == ']') {
                bracketDepth--
                if (bracketDepth < 0) {
                    return EpubCfiParseResult.Error("Unmatched closing bracket ']' in CFI.", cfi)
                }
            }
            idx++
        }
        if (bracketDepth != 0) {
            return EpubCfiParseResult.Error("Unclosed bracket '[' in CFI.", cfi)
        }

        val rangeParts = splitUnescapedTopLevelCommas(inner)
        if (rangeParts.size != 1 && rangeParts.size != 3) {
            return EpubCfiParseResult.Error("CFI must be either a point (1 part) or a range (3 comma-separated parts).", cfi)
        }

        val isRange = rangeParts.size == 3
        if (isRange && (rangeParts[0].isBlank() || rangeParts[1].isBlank() || rangeParts[2].isBlank())) {
            return EpubCfiParseResult.Error("Range CFI has an empty parent or relative subpath.", cfi)
        }
        if (isRange && (!rangeParts[1].trim().startsWith("/") || !rangeParts[2].trim().startsWith("/"))) {
            return EpubCfiParseResult.Error("Range relative subpaths must start with '/'.", cfi)
        }

        val fullStartPath = if (isRange) rangeParts[0].trim() + rangeParts[1].trim() else rangeParts[0].trim()
        val fullEndPath = if (isRange) rangeParts[0].trim() + rangeParts[2].trim() else rangeParts[0].trim()

        val startParsed = parseSingleFullCfiPathStrict(fullStartPath)
            ?: return EpubCfiParseResult.Error("Malformed CFI start path: $fullStartPath", cfi)
        val endParsed = if (isRange) {
            parseSingleFullCfiPathStrict(fullEndPath)
                ?: return EpubCfiParseResult.Error("Malformed CFI end path: $fullEndPath", cfi)
        } else {
            startParsed
        }

        val parsedObj = ParsedEpubCfi(
            rawCfi = "epubcfi($inner)",
            spineStep = startParsed.spineStep,
            spineIdAssertion = startParsed.spineIdAssertion,
            bodyStep = startParsed.bodyStep,
            startElementSteps = startParsed.elementSteps,
            endElementSteps = endParsed.elementSteps,
            startOffset = startParsed.terminalOffset,
            endOffset = endParsed.terminalOffset,
            isRange = isRange
        )
        return EpubCfiParseResult.Success(parsedObj)
    }

    /**
     * Parses a W3C / IDPF EPUB CFI 1.1 string (`epubcfi(...)` or `#epubcfi(...)`) into a [ParsedEpubCfi].
     * Returns `null` if the string is not a valid EPUB CFI.
     */
    fun parseEpubCfi(rawCfi: String): ParsedEpubCfi? {
        return (parseCfi(rawCfi) as? EpubCfiParseResult.Success)?.parsed
    }

    private data class SinglePathComponents(
        val spineStep: Int,
        val spineIdAssertion: String,
        val bodyStep: Int,
        val elementSteps: List<EpubCfiStep>,
        val terminalOffset: EpubCfiTerminalOffset
    )

    private fun parseSingleFullCfiPathStrict(fullPath: String): SinglePathComponents? {
        // Must contain '!' indirection from package document (/6/...) into content document
        val indirectionParts = splitOutsideBrackets(fullPath, '!')
        if (indirectionParts.size < 2) return null

        val packagePath = indirectionParts[0].trim()
        val contentPath = indirectionParts.drop(1).joinToString("!").trim()
        if (packagePath.isEmpty() || contentPath.isEmpty()) return null

        val rawPkgSegments = splitOutsideBrackets(packagePath, '/').filter { it.isNotBlank() }
        if (rawPkgSegments.size < 2) return null
        val packageSteps = mutableListOf<EpubCfiStep>()
        for (seg in rawPkgSegments) {
            val step = parseSingleStepSegment(seg) ?: return null
            packageSteps.add(step)
        }

        // Package step /6 addresses <spine>; spine item step is even (2 x 1-based itemref index)
        if (packageSteps[0].stepIndex != 6) return null
        val spineStepObj = packageSteps[1]
        if (spineStepObj.stepIndex <= 0 || spineStepObj.stepIndex % 2 != 0) return null

        val rawSegments = splitOutsideBrackets(contentPath, '/').filter { it.isNotBlank() }
        if (rawSegments.isEmpty()) return null

        val elementSteps = mutableListOf<EpubCfiStep>()
        var terminalOffset = EpubCfiTerminalOffset(1, 0)

        for ((idx, seg) in rawSegments.withIndex()) {
            val isLast = idx == rawSegments.lastIndex
            if (seg.contains(':')) {
                if (!isLast) return null
                terminalOffset = parseTerminalSegment(seg) ?: return null
            } else {
                val step = parseSingleStepSegment(seg) ?: return null
                if (step.stepIndex <= 0) return null
                if (isLast && step.stepIndex % 2 == 1) {
                    terminalOffset = EpubCfiTerminalOffset(
                        textNodeStep = step.stepIndex,
                        charOffset = 0,
                        textAssertionPrefix = step.idAssertion
                    )
                } else {
                    if (step.stepIndex % 2 != 0) return null
                    elementSteps.add(step)
                }
            }
        }

        if (elementSteps.isEmpty()) return null

        val bodyStep = if (elementSteps.first().stepIndex == 4) 4 else 4
        val innerElements = if (elementSteps.first().stepIndex == 4 && elementSteps.size > 1) {
            elementSteps.drop(1)
        } else if (elementSteps.size == 1 && elementSteps.first().stepIndex == 4) {
            listOf(EpubCfiStep(2, ""))
        } else {
            elementSteps
        }

        return SinglePathComponents(
            spineStep = spineStepObj.stepIndex,
            spineIdAssertion = spineStepObj.idAssertion,
            bodyStep = bodyStep,
            elementSteps = innerElements,
            terminalOffset = terminalOffset
        )
    }

    private fun parseStepsList(path: String): List<EpubCfiStep> {
        val segments = splitOutsideBrackets(path, '/').filter { it.isNotBlank() }
        return segments.mapNotNull { parseSingleStepSegment(it) }
    }

    private fun parseSingleStepSegment(segment: String): EpubCfiStep? {
        val bracketStart = findUnescapedChar(segment, '[')
        val numPart = if (bracketStart >= 0) segment.substring(0, bracketStart).trim() else segment.trim()
        val stepNum = numPart.toIntOrNull() ?: return null
        val assertion = if (bracketStart >= 0) {
            val bracketEnd = findUnescapedChar(segment, ']', bracketStart + 1)
            if (bracketEnd < 0) return null
            val rawAssert = segment.substring(bracketStart + 1, bracketEnd)
            unescapeCfiAssertion(rawAssert)
        } else ""
        return EpubCfiStep(stepIndex = stepNum, idAssertion = assertion)
    }

    private fun parseTerminalSegment(segment: String): EpubCfiTerminalOffset? {
        val colonIdx = findUnescapedChar(segment, ':')
        if (colonIdx < 0) return null
        val beforeColon = segment.substring(0, colonIdx).trim()
        val textNodeStep = if (beforeColon.isEmpty()) 1 else beforeColon.toIntOrNull() ?: return null
        if (textNodeStep <= 0) return null
        val afterColon = segment.substring(colonIdx + 1).trim()
        if (afterColon.isEmpty()) return null
        val bracketStart = findUnescapedChar(afterColon, '[')
        val offsetPart = if (bracketStart >= 0) afterColon.substring(0, bracketStart).trim() else afterColon
        val charOffset = offsetPart.toIntOrNull() ?: return null
        if (charOffset < 0) return null

        var pre = ""
        var post = ""
        if (bracketStart >= 0) {
            val bracketEnd = findUnescapedChar(afterColon, ']', bracketStart + 1)
            if (bracketEnd < 0) return null
            val assertionBody = afterColon.substring(bracketStart + 1, bracketEnd)
            val commaParts = splitOutsideBrackets(assertionBody, ',')
            pre = unescapeCfiAssertion(commaParts.getOrNull(0).orEmpty())
            post = unescapeCfiAssertion(commaParts.getOrNull(1).orEmpty())
        }
        return EpubCfiTerminalOffset(
            textNodeStep = textNodeStep,
            charOffset = charOffset,
            textAssertionPrefix = pre,
            textAssertionSuffix = post
        )
    }

    private fun splitUnescapedTopLevelCommas(input: String): List<String> {
        return splitOutsideBrackets(input, ',')
    }

    private fun splitOutsideBrackets(input: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var bracketDepth = 0
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '^' && i + 1 < input.length) {
                current.append(c).append(input[i + 1])
                i += 2
                continue
            }
            when (c) {
                '[' -> {
                    bracketDepth++
                    current.append(c)
                }
                ']' -> {
                    bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                    current.append(c)
                }
                delimiter -> {
                    if (bracketDepth == 0) {
                        result.add(current.toString())
                        current.setLength(0)
                    } else {
                        current.append(c)
                    }
                }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    private fun findUnescapedChar(input: String, target: Char, startIndex: Int = 0): Int {
        var i = startIndex
        while (i < input.length) {
            val c = input[i]
            if (c == '^') {
                i += 2
                continue
            }
            if (c == target) return i
            i++
        }
        return -1
    }

    /**
     * (d) Resolves a CFI string into:
     * - `spineIndex`, `elementPath`, and `charOffset`
     * - the [EpubPage] containing it (when `pages` is provided)
     * If resolution is ambiguous, prefers the XML `id` assertion over the numeric step.
     */
    fun resolveCfi(
        cfi: String,
        pages: List<EpubPage> = emptyList(),
        epubBook: EpubBook? = null,
        fallbackQuote: String = ""
    ): EpubCfiResolution? {
        val parsed = parseEpubCfi(cfi) ?: return null

        // Resolve spine index preferring idAssertion when epubBook is present
        val resolvedSpineIndex = if (epubBook != null && epubBook.chapters.isNotEmpty() && parsed.spineIdAssertion.isNotBlank()) {
            epubBook.chapters.indexOfFirst { ch ->
                ch.id.equals(parsed.spineIdAssertion, ignoreCase = true) ||
                    ch.fileKey.equals(parsed.spineIdAssertion, ignoreCase = true) ||
                    ch.fileKey.substringBeforeLast(".").equals(parsed.spineIdAssertion, ignoreCase = true)
            }.takeIf { it >= 0 } ?: parsed.spineIndex.coerceIn(0, epubBook.chapters.lastIndex)
        } else if (epubBook != null && epubBook.chapters.isNotEmpty()) {
            parsed.spineIndex.coerceIn(0, epubBook.chapters.lastIndex)
        } else {
            parsed.spineIndex
        }

        // Resolve element step inside chapter preferring idAssertion over numeric step
        var resolvedElementSteps = listOf(EpubCfiStep(parsed.bodyStep)) + parsed.startElementSteps
        var resolvedEndElementSteps = listOf(EpubCfiStep(parsed.bodyStep)) + parsed.endElementSteps
        var resolvedCharOffset = parsed.startCharOffset
        var resolvedEndCharOffset = parsed.endCharOffset

        if (epubBook != null && resolvedSpineIndex in epubBook.chapters.indices) {
            val chapter = epubBook.chapters[resolvedSpineIndex]
            if (chapter.semanticBlocks.isNotEmpty()) {
                val startBlockIdx = resolveSemanticBlockIndex(
                    blocks = chapter.semanticBlocks,
                    idAssertion = parsed.startBlockIdAssertion,
                    stepBlockIndex = parsed.startBlockIndex,
                    fallbackQuote = fallbackQuote,
                    isEnd = false
                )
                val endBlockIdx = if (parsed.isRange) {
                    resolveSemanticBlockIndex(
                        blocks = chapter.semanticBlocks,
                        idAssertion = parsed.endBlockIdAssertion,
                        stepBlockIndex = parsed.endBlockIndex,
                        fallbackQuote = fallbackQuote,
                        isEnd = true
                    ).coerceAtLeast(startBlockIdx)
                } else {
                    startBlockIdx
                }
                val startBlock = chapter.semanticBlocks[startBlockIdx]
                val endBlock = chapter.semanticBlocks[endBlockIdx]
                val startStepNum = (startBlockIdx + 1) * 2
                val endStepNum = (endBlockIdx + 1) * 2
                val startId = parsed.startBlockIdAssertion.ifBlank { startBlock.anchorIds.firstOrNull().orEmpty() }
                val endId = parsed.endBlockIdAssertion.ifBlank { endBlock.anchorIds.firstOrNull().orEmpty() }
                resolvedElementSteps = listOf(EpubCfiStep(4), EpubCfiStep(startStepNum, startId))
                resolvedEndElementSteps = listOf(EpubCfiStep(4), EpubCfiStep(endStepNum, endId))
            }
        }

        val pageIdx = if (pages.isNotEmpty()) {
            resolveReflowPageIndexForCfi(
                pages = pages,
                rawCfi = cfi,
                epubBook = epubBook,
                fallbackQuote = fallbackQuote
            ) ?: -1
        } else {
            -1
        }
        val matchedPage = if (pageIdx in pages.indices) pages[pageIdx] else null

        return EpubCfiResolution(
            spineIndex = resolvedSpineIndex,
            spineId = parsed.spineIdAssertion,
            elementPathSteps = resolvedElementSteps,
            charOffset = resolvedCharOffset,
            endElementPathSteps = resolvedEndElementSteps,
            endCharOffset = resolvedEndCharOffset,
            isRange = parsed.isRange,
            page = matchedPage,
            pageIndex = pageIdx,
            canonicalCfi = parsed.canonicalCfi
        )
    }

    /**
     * Resolves a stored EPUB CFI string against an [EpubBook], returning the resolved chapter,
     * semantic block / paragraph indices, character offsets, and extracted text.
     *
     * Handles differences across reader engine versions by resolving:
     * 1. Spine item ID assertion (`[idref]`) or `spineIndex`.
     * 2. Block XML ID assertion (`[blockId]`) or `startBlockIndex` / `endBlockIndex`.
     * 3. Character offsets with fallback verification against [fallbackQuote] if block segmentation changed.
     */
    fun resolveCfiInBook(
        epubBook: EpubBook,
        rawCfi: String,
        fallbackQuote: String = ""
    ): ResolvedEpubCfiSelection? {
        if (epubBook.chapters.isEmpty()) return null
        val parsed = parseEpubCfi(rawCfi) ?: return null

        // 1. Resolve chapter by spineIdAssertion first, then spineIndex
        val chapterIndex = if (parsed.spineIdAssertion.isNotBlank()) {
            epubBook.chapters.indexOfFirst { ch ->
                ch.id.equals(parsed.spineIdAssertion, ignoreCase = true) ||
                    ch.fileKey.equals(parsed.spineIdAssertion, ignoreCase = true) ||
                    ch.fileKey.substringBeforeLast(".").equals(parsed.spineIdAssertion, ignoreCase = true)
            }.takeIf { it >= 0 } ?: parsed.spineIndex.coerceIn(0, epubBook.chapters.lastIndex)
        } else {
            parsed.spineIndex.coerceIn(0, epubBook.chapters.lastIndex)
        }

        val chapter = epubBook.chapters[chapterIndex]

        if (chapter.semanticBlocks.isNotEmpty()) {
            val startBlockIdx = resolveSemanticBlockIndex(
                blocks = chapter.semanticBlocks,
                idAssertion = parsed.startBlockIdAssertion,
                stepBlockIndex = parsed.startBlockIndex,
                fallbackQuote = fallbackQuote,
                isEnd = false
            )
            val endBlockIdx = if (parsed.isRange && (parsed.endBlockStep != parsed.startBlockStep || parsed.endBlockIdAssertion != parsed.startBlockIdAssertion)) {
                resolveSemanticBlockIndex(
                    blocks = chapter.semanticBlocks,
                    idAssertion = parsed.endBlockIdAssertion,
                    stepBlockIndex = parsed.endBlockIndex,
                    fallbackQuote = fallbackQuote,
                    isEnd = true
                ).coerceAtLeast(startBlockIdx)
            } else {
                startBlockIdx
            }

            val startBlock = chapter.semanticBlocks[startBlockIdx]
            val endBlock = chapter.semanticBlocks[endBlockIdx]

            val (resolvedStartChar, resolvedEndChar, textSlice) = extractSliceAcrossBlocks(
                blocks = chapter.semanticBlocks.map { it.text },
                startBlockIdx = startBlockIdx,
                endBlockIdx = endBlockIdx,
                rawStartChar = parsed.startCharOffset,
                rawEndChar = parsed.endCharOffset,
                fallbackQuote = fallbackQuote
            )

            val rangeBlocks = chapter.semanticBlocks.subList(startBlockIdx, (endBlockIdx + 1).coerceAtMost(chapter.semanticBlocks.size))
            val countedInSpan = rangeBlocks.mapNotNull { it.paragraphNumberInChapter }
            val startParaNum = countedInSpan.firstOrNull()
                ?: chapter.semanticBlocks.take(startBlockIdx + 1).lastOrNull { it.paragraphNumberInChapter != null }?.paragraphNumberInChapter
                ?: 1
            val endParaNum = maxOf(startParaNum, countedInSpan.lastOrNull() ?: startParaNum)

            val canonical = buildSelectionRangeCfi(
                spineIndex = chapterIndex,
                spineId = chapter.id,
                startBlockIndex = startBlockIdx,
                startBlockId = startBlock.anchorIds.firstOrNull().orEmpty(),
                startCharOffset = resolvedStartChar,
                endBlockIndex = endBlockIdx,
                endBlockId = endBlock.anchorIds.firstOrNull().orEmpty(),
                endCharOffset = resolvedEndChar,
                includeHashPrefix = false
            )

            return ResolvedEpubCfiSelection(
                chapterIndex = chapterIndex,
                chapterId = chapter.id,
                chapterTitle = chapter.title,
                startBlockIndex = startBlockIdx,
                endBlockIndex = endBlockIdx,
                startParagraphNumber = startParaNum,
                endParagraphNumber = endParaNum,
                startCharOffset = resolvedStartChar,
                endCharOffset = resolvedEndChar,
                extractedText = textSlice,
                canonicalCfi = canonical
            )
        } else if (chapter.paragraphs.isNotEmpty()) {
            val startIdx = parsed.startBlockIndex.coerceIn(0, chapter.paragraphs.lastIndex)
            val endIdx = parsed.endBlockIndex.coerceIn(startIdx, chapter.paragraphs.lastIndex)
            val (resolvedStartChar, resolvedEndChar, textSlice) = extractSliceAcrossBlocks(
                blocks = chapter.paragraphs,
                startBlockIdx = startIdx,
                endBlockIdx = endIdx,
                rawStartChar = parsed.startCharOffset,
                rawEndChar = parsed.endCharOffset,
                fallbackQuote = fallbackQuote
            )
            val canonical = buildSelectionRangeCfi(
                spineIndex = chapterIndex,
                spineId = chapter.id,
                startBlockIndex = startIdx,
                startCharOffset = resolvedStartChar,
                endBlockIndex = endIdx,
                endCharOffset = resolvedEndChar,
                includeHashPrefix = false
            )
            return ResolvedEpubCfiSelection(
                chapterIndex = chapterIndex,
                chapterId = chapter.id,
                chapterTitle = chapter.title,
                startBlockIndex = startIdx,
                endBlockIndex = endIdx,
                startParagraphNumber = startIdx + 1,
                endParagraphNumber = endIdx + 1,
                startCharOffset = resolvedStartChar,
                endCharOffset = resolvedEndChar,
                extractedText = textSlice,
                canonicalCfi = canonical
            )
        }

        return null
    }

    /**
     * Resolves a highlight's [HighlightEntity.epubCfi] into a `(startChar, endChar)` character span
     * inside a rendered [displayParagraph] when the paragraph matches the CFI's target block.
     * Supports both unsplit blocks and paragraphs that were split across pages during reflow.
     */
    fun resolveHighlightSpanInParagraph(
        displayParagraph: String,
        chapterIndex: Int? = null,
        chapterId: String? = null,
        chapterTitle: String = "",
        semanticBlock: EpubSemanticBlock? = null,
        paragraphIndexInChapter: Int? = null,
        highlight: HighlightEntity
    ): Pair<Int, Int>? {
        if (displayParagraph.isEmpty() || highlight.epubCfi.isBlank()) return null
        val parsed = parseEpubCfi(highlight.epubCfi) ?: return null

        // Check if chapter matches (when chapter metadata is available)
        if (parsed.spineIdAssertion.isNotBlank() && !chapterId.isNullOrBlank()) {
            if (!chapterId.equals(parsed.spineIdAssertion, ignoreCase = true)) {
                return null
            }
        } else if (chapterIndex != null && chapterIndex >= 0) {
            if (chapterIndex != parsed.spineIndex) {
                return null
            }
        } else if (highlight.chapterTitle.isNotBlank() && chapterTitle.isNotBlank()) {
            if (!highlight.chapterTitle.trim().equals(chapterTitle.trim(), ignoreCase = true)) {
                return null
            }
        }

        // Check if block matches via XML anchor ID or block index / paragraph number
        val anchorMatch = parsed.startBlockIdAssertion.isNotBlank() &&
            semanticBlock?.anchorIds?.any { it.equals(parsed.startBlockIdAssertion, ignoreCase = true) } == true
        val indexMatch = when {
            paragraphIndexInChapter != null -> paragraphIndexInChapter in parsed.startBlockIndex..parsed.endBlockIndex
            semanticBlock?.paragraphNumberInChapter != null -> {
                val pNum = semanticBlock.paragraphNumberInChapter - 1
                pNum == parsed.startBlockIndex || pNum in parsed.startBlockIndex..parsed.endBlockIndex
            }
            else -> false
        }

        if (!anchorMatch && !indexMatch) return null

        // If displayParagraph has the exact character range and matches the quote (or quote is blank), return it
        val quote = highlight.highlightedText.trim()
        if (parsed.startBlockIndex == parsed.endBlockIndex) {
            val s = parsed.startCharOffset.coerceIn(0, displayParagraph.length)
            val e = parsed.endCharOffset.coerceIn(s, displayParagraph.length)
            if (e > s) {
                val slice = displayParagraph.substring(s, e)
                if (quote.isEmpty() ||
                    slice.equals(quote, ignoreCase = true) ||
                    normalizeText(slice) == normalizeText(quote)
                ) {
                    return Pair(s, e)
                }
            }
        }

        // If the paragraph was split across pages or reflowed by a different engine version,
        // locate the quote segment inside this matched block
        if (quote.isNotEmpty()) {
            val exactIdx = displayParagraph.indexOf(quote, ignoreCase = true)
            if (exactIdx >= 0) {
                return Pair(exactIdx, (exactIdx + quote.length).coerceAtMost(displayParagraph.length))
            }
        } else if (parsed.endCharOffset > parsed.startCharOffset && parsed.startCharOffset < displayParagraph.length) {
            val s = parsed.startCharOffset.coerceIn(0, displayParagraph.length - 1)
            val e = parsed.endCharOffset.coerceIn(s + 1, displayParagraph.length)
            return Pair(s, e)
        }

        return null
    }

    /**
     * Resolves the 0-based page index in a reflowed [pages] list for a stored EPUB CFI (`epubcfi(...)`),
     * ensuring highlights and selections jump to the exact page across any font size, margin, or engine version.
     */
    fun resolveReflowPageIndexForCfi(
        pages: List<EpubPage>,
        rawCfi: String,
        epubBook: EpubBook? = null,
        fallbackQuote: String = ""
    ): Int? {
        if (pages.isEmpty() || rawCfi.isBlank()) return null
        val parsed = parseEpubCfi(rawCfi) ?: return null

        // 0. If resolution has an explicit block XML ID assertion, always prefer the id assertion over numeric step!
        if (parsed.startBlockIdAssertion.isNotBlank()) {
            val anchorPage = pages.firstOrNull { p ->
                !p.isCoverPage && (
                    p.anchorIds.any { it.equals(parsed.startBlockIdAssertion, ignoreCase = true) } ||
                        p.semanticBlocks.any { sb -> sb.anchorIds.any { it.equals(parsed.startBlockIdAssertion, ignoreCase = true) } }
                    )
            }
            if (anchorPage != null) return anchorPage.globalIndex
        }

        // 1. If we have an EpubBook, resolve the exact slice text and block from the book first
        if (epubBook != null && epubBook.chapters.isNotEmpty()) {
            val resolved = resolveCfiInBook(epubBook, rawCfi, fallbackQuote)
            if (resolved != null) {
                val sliceProbe = resolved.extractedText.lineSequence().firstOrNull { it.trim().isNotEmpty() }?.trim().orEmpty()
                val chapterPages = pages.filter { !it.isCoverPage && it.chapterIndex == resolved.chapterIndex }
                if (chapterPages.isNotEmpty()) {
                    // Check anchor ID match first
                    if (parsed.startBlockIdAssertion.isNotBlank()) {
                        val anchorPage = chapterPages.firstOrNull { p ->
                            p.anchorIds.any { it.equals(parsed.startBlockIdAssertion, ignoreCase = true) } ||
                                p.semanticBlocks.any { sb -> sb.anchorIds.any { it.equals(parsed.startBlockIdAssertion, ignoreCase = true) } }
                        }
                        if (anchorPage != null) return anchorPage.globalIndex
                    }
                    // Check exact slice text match within the target chapter's pages (handles mid-paragraph page splits!)
                    if (sliceProbe.isNotEmpty()) {
                        val probe = if (sliceProbe.length > 36) sliceProbe.take(36).trim() else sliceProbe
                        val matchedPage = chapterPages.firstOrNull { p ->
                            p.paragraphs.any { para -> para.contains(probe, ignoreCase = true) }
                        }
                        if (matchedPage != null) return matchedPage.globalIndex
                    }
                    return chapterPages.first().globalIndex
                }
            }
        }

        // 2. Direct inspection of pages using parsed CFI coordinates (preferring spineIdAssertion over spineIndex)
        val bySpineIdPages = if (parsed.spineIdAssertion.isNotBlank()) {
            pages.filter { p ->
                !p.isCoverPage && (
                    p.fileKey.equals(parsed.spineIdAssertion, ignoreCase = true) ||
                        p.fileKey.substringBeforeLast(".").equals(parsed.spineIdAssertion, ignoreCase = true)
                    )
            }
        } else emptyList()

        val targetChapterPages = bySpineIdPages.ifEmpty {
            pages.filter { p -> !p.isCoverPage && p.chapterIndex == parsed.spineIndex }
        }

        if (targetChapterPages.isNotEmpty()) {
            val targetParaNum = parsed.startBlockIndex + 1
            val byParaNum = targetChapterPages.firstOrNull { p ->
                p.semanticBlocks.any { sb -> sb.paragraphNumberInChapter == targetParaNum } ||
                    (targetParaNum in p.startParagraphNumber..p.endParagraphNumber)
            }
            if (byParaNum != null) return byParaNum.globalIndex

            var seenBlocks = 0
            for (p in targetChapterPages) {
                val blockCountOnPage = p.paragraphs.size.coerceAtLeast(1)
                if (parsed.startBlockIndex < seenBlocks + blockCountOnPage) {
                    return p.globalIndex
                }
                seenBlocks += blockCountOnPage
            }
            return targetChapterPages.first().globalIndex
        }

        return null
    }

    /**
     * Compares two EPUB CFI strings in canonical document order per EPUB CFI 1.1 Section 3.4.
     */
    fun compareEpubCfi(cfiA: String, cfiB: String): Int {
        val parsedA = parseEpubCfi(cfiA)
        val parsedB = parseEpubCfi(cfiB)
        if (parsedA == null && parsedB == null) return cfiA.compareTo(cfiB)
        if (parsedA == null) return 1
        if (parsedB == null) return -1

        val spineCmp = parsedA.spineStep.compareTo(parsedB.spineStep)
        if (spineCmp != 0) return spineCmp

        val maxSteps = maxOf(parsedA.startElementSteps.size, parsedB.startElementSteps.size)
        for (i in 0 until maxSteps) {
            val stepA = parsedA.startElementSteps.getOrNull(i)?.stepIndex ?: 0
            val stepB = parsedB.startElementSteps.getOrNull(i)?.stepIndex ?: 0
            val cmp = stepA.compareTo(stepB)
            if (cmp != 0) return cmp
        }

        val startOffsetCmp = parsedA.startCharOffset.compareTo(parsedB.startCharOffset)
        if (startOffsetCmp != 0) return startOffsetCmp

        val endBlockCmp = parsedA.endBlockStep.compareTo(parsedB.endBlockStep)
        if (endBlockCmp != 0) return endBlockCmp

        return parsedA.endCharOffset.compareTo(parsedB.endCharOffset)
    }

    private fun normalizeText(s: String): String = s.lowercase()
        .replace(Regex("[“”\"'’‘«»]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun findQuoteOffsetInBlockText(blockText: String, quoteSegment: String): Int {
        if (blockText.isEmpty() || quoteSegment.isEmpty()) return -1
        val exact = blockText.indexOf(quoteSegment, ignoreCase = true)
        if (exact >= 0) return exact
        val cleanQuote = quoteSegment.trim()
        val trimmedIdx = blockText.indexOf(cleanQuote, ignoreCase = true)
        if (trimmedIdx >= 0) return trimmedIdx
        if (cleanQuote.length >= 12) {
            val prefix = cleanQuote.take(24).trim()
            val prefixIdx = blockText.indexOf(prefix, ignoreCase = true)
            if (prefixIdx >= 0) return prefixIdx
        }
        return -1
    }

    private fun findMatchingChapterIndex(
        epubBook: EpubBook,
        quote: String,
        fallbackChapterTitle: String,
        fallbackOrdinal: Int
    ): Int {
        val cleanFallback = fallbackChapterTitle.trim()
        val normQuoteFirst = normalizeText(quote.lineSequence().firstOrNull { it.trim().isNotEmpty() } ?: quote)
        val probe = normQuoteFirst.split(" ").filter { it.isNotBlank() }.take(5).joinToString(" ")

        if (cleanFallback.isNotEmpty()) {
            val titleMatchIdx = epubBook.chapters.indexOfFirst { ch ->
                ch.title.trim().equals(cleanFallback, ignoreCase = true)
            }
            if (titleMatchIdx >= 0) {
                val ch = epubBook.chapters[titleMatchIdx]
                if (normQuoteFirst.isEmpty() ||
                    ch.paragraphs.any { normalizeText(it).contains(probe) } ||
                    ch.semanticBlocks.any { normalizeText(it.text).contains(probe) }
                ) {
                    return titleMatchIdx
                }
            }
        }

        if (normQuoteFirst.isNotEmpty()) {
            val quoteMatchIdx = epubBook.chapters.indexOfFirst { ch ->
                ch.semanticBlocks.any { sb ->
                    val n = normalizeText(sb.text)
                    n.contains(normQuoteFirst) || (probe.length >= 8 && n.contains(probe))
                } || ch.paragraphs.any { p ->
                    val n = normalizeText(p)
                    n.contains(normQuoteFirst) || (probe.length >= 8 && n.contains(probe))
                }
            }
            if (quoteMatchIdx >= 0) return quoteMatchIdx
        }

        if (cleanFallback.isNotEmpty()) {
            val titleMatchIdx = epubBook.chapters.indexOfFirst { ch ->
                ch.title.trim().equals(cleanFallback, ignoreCase = true)
            }
            if (titleMatchIdx >= 0) return titleMatchIdx
        }

        return (fallbackOrdinal - 1).coerceIn(0, epubBook.chapters.lastIndex)
    }

    private fun findMatchingSemanticBlockRange(
        blocks: List<EpubSemanticBlock>,
        firstSegment: String,
        lastSegment: String,
        fallbackStartPara: Int,
        fallbackEndPara: Int
    ): Pair<Int, Int> {
        val normFirst = normalizeText(firstSegment)
        val normLast = normalizeText(lastSegment)
        val probe = normFirst.split(" ").filter { it.isNotBlank() }.take(5).joinToString(" ")

        var startIdx = -1
        var endIdx = -1
        if (normFirst.isNotEmpty()) {
            for ((idx, sb) in blocks.withIndex()) {
                val n = normalizeText(sb.text)
                if (n.isEmpty()) continue
                if (startIdx < 0) {
                    if (n.contains(normFirst) ||
                        (normFirst.length >= 12 && normFirst.contains(n)) ||
                        (probe.length >= 8 && n.contains(probe))
                    ) {
                        startIdx = idx
                        endIdx = idx
                        if (firstSegment == lastSegment) break
                    }
                } else if (firstSegment != lastSegment && normLast.isNotEmpty()) {
                    if (n.contains(normLast) || (n.length >= 12 && normLast.contains(n))) {
                        endIdx = idx
                        break
                    }
                }
            }
        }

        if (startIdx >= 0) {
            return Pair(startIdx, endIdx.coerceAtLeast(startIdx))
        }

        val sByPara = blocks.indexOfFirst { it.paragraphNumberInChapter == fallbackStartPara }
            .takeIf { it >= 0 } ?: (fallbackStartPara - 1).coerceIn(0, blocks.lastIndex)
        val eByPara = blocks.indexOfFirst { it.paragraphNumberInChapter == fallbackEndPara }
            .takeIf { it >= 0 } ?: (fallbackEndPara - 1).coerceIn(sByPara, blocks.lastIndex)
        return Pair(sByPara, eByPara.coerceAtLeast(sByPara))
    }

    private fun findMatchingParagraphRange(
        paragraphs: List<String>,
        firstSegment: String,
        lastSegment: String,
        fallbackStartPara: Int,
        fallbackEndPara: Int
    ): Pair<Int, Int> {
        val normFirst = normalizeText(firstSegment)
        val normLast = normalizeText(lastSegment)
        val probe = normFirst.split(" ").filter { it.isNotBlank() }.take(5).joinToString(" ")

        var startIdx = -1
        var endIdx = -1
        if (normFirst.isNotEmpty()) {
            for ((idx, p) in paragraphs.withIndex()) {
                val n = normalizeText(p)
                if (n.isEmpty()) continue
                if (startIdx < 0) {
                    if (n.contains(normFirst) ||
                        (normFirst.length >= 12 && normFirst.contains(n)) ||
                        (probe.length >= 8 && n.contains(probe))
                    ) {
                        startIdx = idx
                        endIdx = idx
                        if (firstSegment == lastSegment) break
                    }
                } else if (firstSegment != lastSegment && normLast.isNotEmpty()) {
                    if (n.contains(normLast) || (n.length >= 12 && normLast.contains(n))) {
                        endIdx = idx
                        break
                    }
                }
            }
        }

        if (startIdx >= 0) {
            return Pair(startIdx, endIdx.coerceAtLeast(startIdx))
        }

        val sIdx = (fallbackStartPara - 1).coerceIn(0, paragraphs.lastIndex)
        val eIdx = (fallbackEndPara - 1).coerceIn(sIdx, paragraphs.lastIndex)
        return Pair(sIdx, eIdx)
    }

    private fun resolveSemanticBlockIndex(
        blocks: List<EpubSemanticBlock>,
        idAssertion: String,
        stepBlockIndex: Int,
        fallbackQuote: String,
        isEnd: Boolean
    ): Int {
        if (idAssertion.isNotBlank()) {
            val byId = blocks.indexOfFirst { sb ->
                sb.anchorIds.any { it.equals(idAssertion, ignoreCase = true) }
            }
            if (byId >= 0) return byId
        }
        if (stepBlockIndex in blocks.indices) {
            val candidate = blocks[stepBlockIndex]
            if (fallbackQuote.isBlank()) return stepBlockIndex
            val seg = if (isEnd) {
                fallbackQuote.split(Regex("\n\\s*\n+")).lastOrNull()?.trim().orEmpty()
            } else {
                fallbackQuote.split(Regex("\n\\s*\n+")).firstOrNull()?.trim().orEmpty()
            }
            if (seg.isEmpty() || findQuoteOffsetInBlockText(candidate.text, seg) >= 0) {
                return stepBlockIndex
            }
        }
        if (fallbackQuote.isNotBlank()) {
            val seg = if (isEnd) {
                fallbackQuote.split(Regex("\n\\s*\n+")).lastOrNull()?.trim().orEmpty()
            } else {
                fallbackQuote.split(Regex("\n\\s*\n+")).firstOrNull()?.trim().orEmpty()
            }
            val byQuote = blocks.indexOfFirst { sb -> findQuoteOffsetInBlockText(sb.text, seg) >= 0 }
            if (byQuote >= 0) return byQuote
        }
        return stepBlockIndex.coerceIn(0, blocks.lastIndex)
    }

    private fun extractSliceAcrossBlocks(
        blocks: List<String>,
        startBlockIdx: Int,
        endBlockIdx: Int,
        rawStartChar: Int,
        rawEndChar: Int,
        fallbackQuote: String
    ): Triple<Int, Int, String> {
        val startText = blocks[startBlockIdx]
        val endText = blocks[endBlockIdx]

        if (startBlockIdx == endBlockIdx) {
            val s = rawStartChar.coerceIn(0, startText.length)
            val e = rawEndChar.coerceIn(s, startText.length)
            val directSlice = if (e > s) startText.substring(s, e) else ""
            if (fallbackQuote.isNotBlank() &&
                (directSlice.isEmpty() || !normalizeText(directSlice).contains(normalizeText(fallbackQuote).take(12)))
            ) {
                val found = findQuoteOffsetInBlockText(startText, fallbackQuote)
                if (found >= 0) {
                    val endPos = (found + fallbackQuote.trim().length).coerceAtMost(startText.length)
                    return Triple(found, endPos, startText.substring(found, endPos))
                }
            }
            return Triple(s, e, directSlice.ifBlank { fallbackQuote.ifBlank { startText } })
        } else {
            val s = rawStartChar.coerceIn(0, startText.length)
            val e = rawEndChar.coerceIn(0, endText.length)
            val parts = mutableListOf<String>()
            parts.add(startText.substring(s).trim())
            for (midIdx in (startBlockIdx + 1) until endBlockIdx) {
                parts.add(blocks[midIdx].trim())
            }
            parts.add(endText.substring(0, e).trim())
            val joined = parts.filter { it.isNotEmpty() }.joinToString("\n\n")
            return Triple(s, e, joined.ifBlank { fallbackQuote })
        }
    }
}
