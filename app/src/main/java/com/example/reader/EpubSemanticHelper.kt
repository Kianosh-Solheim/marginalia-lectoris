package com.example.reader

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream

/**
 * Semantic DOM/XML/HTML extractor for EPUB 2 & EPUB 3 documents:
 * - Preserves document hierarchy (`h1`–`h6`, `p`, `blockquote`, `ul`/`ol`/`li`, `dl`/`dt`/`dd`, `figure`/`figcaption`, `aside` footnotes).
 * - Ensures ONLY genuine `<p>` text paragraphs receive a 1-based `paragraphNumberInChapter` (not headings, blockquotes, list items, captions, or footnote definitions).
 * - Parses hierarchical Table of Contents from `nav.xhtml` (`<nav epub:type="toc">`) with nested `<ol>/<li>` levels, or `toc.ncx` (`<navMap>` with nested `<navPoint>`), or builds a hierarchical TOC from `h1`–`h6` headings when no navigation structure exists.
 * - Preserves internal links (`<a href="...">`) and HTML `id`/`name` anchors.
 * - Establishes explicit two-way footnote links (Main text reference <-> Footnote definition <-> Back to main text/context) using `id`, `href`, fragment identifiers, and `epub:type="noteref"` / `epub:type="footnote"` attributes.
 */
object EpubSemanticHelper {

    /**
     * Parses EPUB 3 `nav.xhtml` `<nav epub:type="toc">` or `<nav role="doc-toc">` preserving nested `<ol>`/`<ul>` hierarchy.
     */
    fun parseEpub3NavHierarchy(navHtml: String, navFilePath: String = ""): List<EpubTocItem> {
        val items = mutableListOf<EpubTocItem>()
        try {
            val navDir = if (navFilePath.contains("/")) navFilePath.substringBeforeLast("/") + "/" else ""
            // Match the <nav> element for toc (excluding page-list or landmarks)
            val navMatches = Regex(
                "<nav\\b([^>]*)>(.*?)</nav>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).findAll(navHtml).toList()

            val tocNavBody = navMatches.firstOrNull { m ->
                val attrs = m.groups[1]?.value ?: ""
                (attrs.contains("toc", ignoreCase = true) || attrs.contains("doc-toc", ignoreCase = true)) &&
                    !attrs.contains("page-list", ignoreCase = true) &&
                    !attrs.contains("pagelist", ignoreCase = true)
            }?.groups?.get(2)?.value
                ?: navMatches.firstOrNull { m ->
                    val attrs = m.groups[1]?.value ?: ""
                    !attrs.contains("page-list", ignoreCase = true) &&
                        !attrs.contains("pagelist", ignoreCase = true) &&
                        !attrs.contains("landmarks", ignoreCase = true)
                }?.groups?.get(2)?.value
                ?: return emptyList()

            // Tokenize <ol>, </ol>, <ul>, </ul>, and <a href="...">label</a> to track nesting depth accurately
            val tokenRegex = Regex(
                "<(/?)(ol|ul)\\b[^>]*>|<a\\b[^>]*\\bhref\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            var listDepth = -1
            for (token in tokenRegex.findAll(tocNavBody)) {
                val isClosing = token.groups[1]?.value == "/"
                val listTag = token.groups[2]?.value?.lowercase()
                if (listTag != null) {
                    if (!isClosing) {
                        listDepth++
                    } else {
                        listDepth = (listDepth - 1).coerceAtLeast(-1)
                    }
                } else {
                    val rawHref = token.groups[3]?.value?.trim() ?: continue
                    val rawLabel = token.groups[4]?.value ?: continue
                    val cleanTitle = cleanHtmlText(rawLabel)
                    if (cleanTitle.isEmpty() || rawHref.isEmpty()) continue
                    val decodedHref = try { java.net.URLDecoder.decode(rawHref, "UTF-8") } catch (_: Exception) { rawHref }
                    val resolvedHref = if (decodedHref.startsWith("#")) {
                        navFilePath.substringAfterLast("/") + decodedHref
                    } else {
                        resolveRelativePath(navDir, decodedHref)
                    }
                    val filePart = resolvedHref.substringBefore("#").substringAfterLast("/").trim()
                    val anchorPart = if (resolvedHref.contains("#")) resolvedHref.substringAfter("#").trim() else ""
                    if (filePart.isNotEmpty() || anchorPart.isNotEmpty()) {
                        items.add(
                            EpubTocItem(
                                title = cleanTitle,
                                href = resolvedHref,
                                fileKey = filePart,
                                anchorId = anchorPart,
                                level = listDepth.coerceAtLeast(0)
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}
        return items
    }

    /**
     * Parses EPUB 2 `toc.ncx` `<navMap>` preserving nested `<navPoint>` hierarchy.
     */
    fun parseNcxHierarchy(ncxBytes: ByteArray): List<EpubTocItem> {
        val items = mutableListOf<EpubTocItem>()
        try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
            val parser = factory.newPullParser()
            parser.setInput(ByteArrayInputStream(ncxBytes), "UTF-8")

            var eventType = parser.eventType
            var insideNavMap = false
            var insidePageList = false
            var navPointDepth = -1
            var currentTag = ""

            data class PendingNavPoint(
                val level: Int,
                var label: String = "",
                var src: String = "",
                var emitted: Boolean = false
            )
            val stack = ArrayDeque<PendingNavPoint>()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val rawName = parser.name ?: ""
                        val tag = rawName.substringAfterLast(":").lowercase()
                        currentTag = tag
                        when (tag) {
                            "navmap" -> insideNavMap = true
                            "pagelist" -> insidePageList = true
                            "navpoint" -> if (insideNavMap && !insidePageList) {
                                navPointDepth++
                                stack.addLast(PendingNavPoint(level = navPointDepth.coerceAtLeast(0)))
                            }
                            "content" -> if (insideNavMap && !insidePageList && stack.isNotEmpty()) {
                                val top = stack.last()
                                if (top.src.isEmpty()) {
                                    top.src = parser.getAttributeValue(null, "src")?.trim() ?: ""
                                }
                                if (top.label.isNotEmpty() && top.src.isNotEmpty() && !top.emitted) {
                                    top.emitted = true
                                    val decodedSrc = try { java.net.URLDecoder.decode(top.src, "UTF-8") } catch (_: Exception) { top.src }
                                    val fileKey = decodedSrc.substringBefore("#").substringAfterLast("/").trim()
                                    val anchorId = if (decodedSrc.contains("#")) decodedSrc.substringAfter("#").trim() else ""
                                    items.add(
                                        EpubTocItem(
                                            title = top.label,
                                            href = decodedSrc,
                                            fileKey = fileKey,
                                            anchorId = anchorId,
                                            level = top.level
                                        )
                                    )
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim() ?: ""
                        if (insideNavMap && !insidePageList && currentTag == "text" && text.isNotEmpty() && stack.isNotEmpty()) {
                            val top = stack.last()
                            if (top.label.isEmpty()) {
                                top.label = text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val rawName = parser.name ?: ""
                        val tag = rawName.substringAfterLast(":").lowercase()
                        when (tag) {
                            "navmap" -> insideNavMap = false
                            "pagelist" -> insidePageList = false
                            "navpoint" -> if (insideNavMap && !insidePageList && stack.isNotEmpty()) {
                                val finished = stack.removeLast()
                                if (!finished.emitted && finished.label.isNotEmpty() && finished.src.isNotEmpty()) {
                                    val decodedSrc = try { java.net.URLDecoder.decode(finished.src, "UTF-8") } catch (_: Exception) { finished.src }
                                    val fileKey = decodedSrc.substringBefore("#").substringAfterLast("/").trim()
                                    val anchorId = if (decodedSrc.contains("#")) decodedSrc.substringAfter("#").trim() else ""
                                    items.add(
                                        EpubTocItem(
                                            title = finished.label,
                                            href = decodedSrc,
                                            fileKey = fileKey,
                                            anchorId = anchorId,
                                            level = finished.level
                                        )
                                    )
                                }
                                navPointDepth = (navPointDepth - 1).coerceAtLeast(-1)
                            }
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {}
        return items
    }

    /**
     * Builds a hierarchical Table of Contents from `h1`–`h6` headings across all chapters
     * when the EPUB does not contain a usable `nav.xhtml` or `toc.ncx` navigation structure.
     */
    fun buildTocFromHeadings(chapters: List<EpubChapter>): List<EpubTocItem> {
        val rawHeadings = mutableListOf<Triple<EpubChapter, EpubSemanticBlock, Int>>()
        for (ch in chapters) {
            val fileKey = ch.fileKey.ifBlank { ch.id }
            val chBlocks = ch.semanticBlocks.filter { it.type == EpubSemanticBlockType.HEADING && it.text.isNotBlank() }
            if (chBlocks.isNotEmpty()) {
                for (b in chBlocks) {
                    rawHeadings.add(Triple(ch, b, b.headingLevel.coerceIn(1, 6)))
                }
            } else if (ch.title.isNotBlank()) {
                rawHeadings.add(
                    Triple(
                        ch,
                        EpubSemanticBlock(
                            type = EpubSemanticBlockType.HEADING,
                            text = ch.title,
                            headingLevel = 1,
                            fileKey = fileKey
                        ),
                        1
                    )
                )
            }
        }
        if (rawHeadings.isEmpty()) return emptyList()
        val minHeadingLevel = rawHeadings.minOf { it.third }.coerceAtLeast(1)
        return rawHeadings.map { (ch, block, hLevel) ->
            val fileKey = block.fileKey.ifBlank { ch.fileKey.ifBlank { ch.id } }
            val anchor = block.anchorIds.firstOrNull().orEmpty()
            val href = if (anchor.isNotEmpty()) "$fileKey#$anchor" else fileKey
            EpubTocItem(
                title = block.text.removePrefix("## ").trim(),
                href = href,
                fileKey = fileKey,
                anchorId = anchor,
                level = (hLevel - minHeadingLevel).coerceIn(0, 4)
            )
        }
    }

    /**
     * Result of semantic extraction for a single chapter XHTML file.
     */
    data class SemanticChapterExtraction(
        val semanticBlocks: List<EpubSemanticBlock>,
        val footnoteEntries: Map<String, EpubFootnoteEntry>,
        val anchorToBlockIndex: Map<String, Int>,
        val totalCountedParagraphs: Int
    )

    private const val LINK_START_TOKEN = "⟦EPUB_LINK_START:"
    private const val LINK_MID_TOKEN = "⟦EPUB_LINK_MID⟧"
    private const val LINK_END_TOKEN = "⟦EPUB_LINK_END⟧"

    /**
     * Extracts structured semantic blocks (`<h1>`–`<h6>`, `<p>`, `<blockquote>`, `<ul>`/`<ol>`/`<li>`,
     * `<dl>`/`<dt>`/`<dd>`, `<figure>`/`<figcaption>`, `<aside epub:type="footnote">`) from chapter XHTML,
     * and aligns them with the already-computed `cleanParagraphs` and `paragraphPageEquivalents` so
     * existing Page Equivalent behavior remains 100% untouched!
     */
    fun extractSemanticStructureForChapter(
        rawHtml: String,
        fileKey: String,
        chapterIndex: Int,
        chapterTitle: String,
        existingParagraphs: List<String>,
        existingPageEquivalents: List<String>,
        existingFootnotes: Map<String, String>
    ): SemanticChapterExtraction {
        val cleanFileKey = fileKey.substringAfterLast("/").trim()
        val workingHtml = rawHtml
            .replace(Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<head[^>]*>.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")

        val footnoteEntries = LinkedHashMap<String, EpubFootnoteEntry>()

        // 1. Extract explicit footnote/endnote/rearnote/glossary/bibliography definitions
        // (<aside>, <div/li/p/dd/dfn epub:type="footnote|endnote|rearnote|dfn|glossdef|biblioentry" or role="doc-footnote|doc-endnote|doc-glossary|doc-bibliography" or id="fn...">)
        val footnoteElementIds = mutableSetOf<String>()
        val fnBlockRegex = Regex(
            "<(aside|div|li|p|dd|dfn|section)\\b([^>]*)>(.*?)</\\1>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        for (m in fnBlockRegex.findAll(workingHtml)) {
            val tag = m.groups[1]?.value?.lowercase() ?: ""
            val attrs = m.groups[2]?.value ?: ""
            val innerHtml = m.groups[3]?.value ?: ""
            val idVal = extractAttr(attrs, "id") ?: extractAttr(attrs, "name") ?: ""
            val epubType = (extractAttr(attrs, "epub:type") ?: "") + " " + (extractAttr(attrs, "role") ?: "") + " " + (extractAttr(attrs, "class") ?: "")
            val isCalloutAside = tag == "aside" && (
                epubType.contains("sidebar", ignoreCase = true) ||
                    epubType.contains("tip", ignoreCase = true) ||
                    epubType.contains("warning", ignoreCase = true) ||
                    epubType.contains("notice", ignoreCase = true) ||
                    epubType.contains("callout", ignoreCase = true) ||
                    (epubType.contains("note", ignoreCase = true) &&
                        !epubType.contains("footnote", ignoreCase = true) &&
                        !epubType.contains("endnote", ignoreCase = true) &&
                        !epubType.contains("rearnote", ignoreCase = true))
                )
            val isExplicitFootnoteAttr = !isCalloutAside && (
                epubType.contains("footnote", ignoreCase = true) ||
                    epubType.contains("endnote", ignoreCase = true) ||
                    epubType.contains("rearnote", ignoreCase = true) ||
                    epubType.contains("dfn", ignoreCase = true) ||
                    epubType.contains("gloss", ignoreCase = true) ||
                    epubType.contains("biblio", ignoreCase = true) ||
                    epubType.contains("doc-footnote", ignoreCase = true) ||
                    epubType.contains("doc-endnote", ignoreCase = true) ||
                    epubType.contains("doc-rearnote", ignoreCase = true) ||
                    epubType.contains("doc-glossary", ignoreCase = true) ||
                    epubType.contains("doc-bibliography", ignoreCase = true) ||
                    epubType.contains("doc-biblioentry", ignoreCase = true) ||
                    tag == "dfn" ||
                    (tag == "aside" && idVal.isNotEmpty())
                )
            val isFnIdPattern = idVal.matches(Regex("^(?:fn|footnote|note|endnote|rearnote|en|n|ftn|gloss|dfn|bib|biblio)[_\\-.:]?[a-zA-Z0-9_\\-]+$", RegexOption.IGNORE_CASE))

            if ((isExplicitFootnoteAttr || isFnIdPattern) && idVal.isNotEmpty()) {
                val noteKind = when {
                    epubType.contains("endnote", ignoreCase = true) || idVal.startsWith("en", ignoreCase = true) -> "endnote"
                    epubType.contains("rearnote", ignoreCase = true) || idVal.startsWith("rear", ignoreCase = true) -> "rearnote"
                    epubType.contains("gloss", ignoreCase = true) || epubType.contains("dfn", ignoreCase = true) || tag == "dfn" || idVal.startsWith("gloss", ignoreCase = true) || idVal.startsWith("dfn", ignoreCase = true) -> "glossary"
                    epubType.contains("biblio", ignoreCase = true) || idVal.startsWith("bib", ignoreCase = true) -> "bibliography"
                    else -> "footnote"
                }
                // Find backlink <a href="..."> inside the footnote body if present (e.g. <a href="#fnref1" role="doc-backlink">↩</a>)
                val backlinkMatch = findBacklinkInFootnoteHtml(innerHtml)
                val backlinkHref = backlinkMatch?.first ?: ""
                val backlinkAnchor = backlinkHref.substringAfter("#", "").trim()
                val noteText = stripBacklinkArrows(cleanHtmlText(innerHtml))
                if (noteText.isNotBlank()) {
                    footnoteElementIds.add(idVal)
                    val displayMarker = extractFootnoteMarkerFromIdOrText(idVal, noteText)
                    val cleanBody = noteText.replace(Regex("^(?:\\[\\d+[a-zA-Z]?\\]|\\d+[a-zA-Z]?[.):])\\s+"), "").trim().ifBlank { noteText }
                    val entry = EpubFootnoteEntry(
                        noteId = idVal,
                        displayMarker = displayMarker,
                        noteText = cleanBody,
                        footnoteFileKey = cleanFileKey,
                        footnoteAnchorId = idVal,
                        backlinkHref = backlinkHref,
                        backlinkAnchorId = backlinkAnchor,
                        noteKind = noteKind
                    )
                    footnoteEntries[idVal] = entry
                    if (displayMarker.isNotEmpty() && !footnoteEntries.containsKey(displayMarker)) {
                        footnoteEntries[displayMarker] = entry
                    }
                }
            }
        }

        // 1b. Also extract paragraph/div/li footnotes where the anchor id sits on an inner <a id="fn4">4</a> or <span id="fn4">4</span> at the start of the block.
        // Use a non-backtracking two-step match (first locate block, then inspect leading tag) to prevent catastrophic regex backtracking on large Notes chapters!
        val candidateBlockRegex = Regex(
            "<(?:p|div|li)\\b[^>]*>(.*?)</(?:p|div|li)>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val leadingInnerAnchorRegex = Regex(
            "^\\s*(?:<sup[^>]*>\\s*)?<(?:a|span)\\b([^>]*)>\\s*\\[?(\\d{1,3}[a-zA-Z]?)\\]?[.):]?\\s*</(?:a|span)>\\s*(?:</sup>)?[.):]?\\s*(.+)$",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        for (blockMatch in candidateBlockRegex.findAll(workingHtml)) {
            val innerBlockHtml = blockMatch.groups[1]?.value ?: continue
            if (!innerBlockHtml.contains("id=", ignoreCase = true) && !innerBlockHtml.contains("name=", ignoreCase = true)) continue
            val m = leadingInnerAnchorRegex.find(innerBlockHtml) ?: continue
            val aAttrs = m.groups[1]?.value ?: ""
            val numMarker = m.groups[2]?.value?.trim() ?: ""
            val bodyHtml = m.groups[3]?.value ?: ""
            val idVal = extractAttr(aAttrs, "id") ?: extractAttr(aAttrs, "name") ?: ""
            val hrefVal = extractAttr(aAttrs, "href").orEmpty()
            if (idVal.isNotEmpty()) {
                val bodyText = stripBacklinkArrows(cleanHtmlText(bodyHtml))
                if (bodyText.length > 2) {
                    footnoteElementIds.add(idVal)
                    val marker = numMarker.ifBlank { extractFootnoteMarkerFromIdOrText(idVal, bodyText) }
                    val entry = EpubFootnoteEntry(
                        noteId = idVal,
                        displayMarker = marker,
                        noteText = bodyText,
                        footnoteFileKey = cleanFileKey,
                        footnoteAnchorId = idVal,
                        backlinkHref = hrefVal,
                        backlinkAnchorId = hrefVal.substringAfter("#", "").trim()
                    )
                    footnoteEntries[idVal] = entry
                    if (marker.isNotEmpty() && !footnoteEntries.containsKey(marker)) {
                        footnoteEntries[marker] = entry
                    }
                }
            }
        }

        // Also include any footnotes discovered by the existing parser
        for ((k, v) in existingFootnotes) {
            if (!footnoteEntries.containsKey(k) && v.isNotBlank()) {
                footnoteEntries[k] = EpubFootnoteEntry(
                    noteId = k,
                    displayMarker = k,
                    noteText = v,
                    footnoteFileKey = cleanFileKey,
                    footnoteAnchorId = k
                )
            }
        }

        // 2. Parse semantic blocks in document order from workingHtml
        val rawParsedBlocks = parseHtmlSemanticBlocks(
            html = workingHtml,
            fileKey = cleanFileKey,
            footnoteEntries = footnoteEntries,
            footnoteElementIds = footnoteElementIds
        )

        // 3. Assign 1-based paragraph numbers ONLY to genuine <p> PARAGRAPH blocks
        //    (skipping empty <p>, headings, blockquotes, list items, tables, pre/code blocks, definition lists, callouts, captions, and paragraphs inside footnotes/asides),
        //    restarting at each chapter (top-level spine document or epub:type="chapter"),
        //    and bind pageEquivalent values from existingParagraphs / existingPageEquivalents
        var paragraphCounter = 0
        var existingCursor = 0
        val alignedBlocks = ArrayList<EpubSemanticBlock>(rawParsedBlocks.size)

        for (block in rawParsedBlocks) {
            if (block.type == EpubSemanticBlockType.HEADING && block.headingLevel == 1 &&
                block.anchorIds.any { it.equals("__epub_chapter_reset__", ignoreCase = true) } &&
                paragraphCounter > 0
            ) {
                paragraphCounter = 0
            }
            val cleanedAnchorIds = block.anchorIds.filterNot { it.equals("__epub_chapter_reset__", ignoreCase = true) }
            val assignedParaNum = if (block.type == EpubSemanticBlockType.PARAGRAPH && block.text.isNotBlank()) {
                paragraphCounter++
                paragraphCounter
            } else {
                null
            }

            // Match pageEquivalent from existingPageEquivalents without altering existing Page Equivalent calculation
            var resolvedPageEq = block.pageEquivalent
            if (resolvedPageEq.isBlank() && existingParagraphs.isNotEmpty() && existingPageEquivalents.isNotEmpty()) {
                val cleanProbe = block.text.removePrefix("## ").removePrefix("• ").trim().take(36)
                var foundIdx = -1
                if (cleanProbe.isNotEmpty()) {
                    for (idx in existingCursor until existingParagraphs.size) {
                        if (existingParagraphs[idx].contains(cleanProbe, ignoreCase = true) ||
                            cleanProbe.contains(existingParagraphs[idx].take(28), ignoreCase = true)
                        ) {
                            foundIdx = idx
                            break
                        }
                    }
                }
                if (foundIdx >= 0) {
                    existingCursor = foundIdx
                    resolvedPageEq = existingPageEquivalents.getOrNull(foundIdx).orEmpty()
                } else {
                    val fallbackIdx = existingCursor.coerceIn(0, existingPageEquivalents.lastIndex)
                    resolvedPageEq = existingPageEquivalents.getOrNull(fallbackIdx).orEmpty()
                }
            }

            val updatedBlock = block.copy(
                anchorIds = cleanedAnchorIds,
                paragraphNumberInChapter = assignedParaNum,
                pageEquivalent = resolvedPageEq
            )
            alignedBlocks.add(updatedBlock)

            // 4. For any inline footnote/glossref/biblioref reference inside a main-text block (never a FOOTNOTE_DEF block itself!),
            //    record the reverse link (source context, chapter, paragraph number, anchor ID)
            //    so clicking from the footnote jumps/shows the exact main-text reference!
            if (updatedBlock.type != EpubSemanticBlockType.FOOTNOTE_DEF) {
                for (link in updatedBlock.inlineLinks) {
                    if (link.isFootnoteRef) {
                        val targetId = link.footnoteId.ifBlank { link.targetAnchorId }.ifBlank { link.label.trim('[', ']', '*', ' ') }
                        val existingEntry = footnoteEntries[targetId]
                            ?: footnoteEntries[link.targetAnchorId]
                            ?: footnoteEntries[link.label.trim('[', ']', '*', ' ')]
                        val contextSnippet = buildContextSnippetAroundRange(updatedBlock.text, link.startChar, link.endChar)
                        val sourceAnchor = updatedBlock.anchorIds.firstOrNull().orEmpty()
                        val inferredKind = when {
                            link.refType.contains("gloss", ignoreCase = true) -> "glossary"
                            link.refType.contains("biblio", ignoreCase = true) -> "bibliography"
                            else -> existingEntry?.noteKind ?: "footnote"
                        }
                        if (existingEntry != null) {
                            val enriched = existingEntry.copy(
                                sourceFileKey = cleanFileKey.ifBlank { existingEntry.sourceFileKey },
                                sourceChapterIndex = (chapterIndex - 1).coerceAtLeast(0),
                                sourceChapterTitle = chapterTitle,
                                sourceParagraphNumber = assignedParaNum ?: existingEntry.sourceParagraphNumber.coerceAtLeast(1),
                                sourceAnchorId = if (existingEntry.backlinkAnchorId.isNotEmpty()) existingEntry.backlinkAnchorId else sourceAnchor,
                                sourceContextSnippet = contextSnippet.ifBlank { existingEntry.sourceContextSnippet },
                                noteKind = inferredKind
                            )
                            footnoteEntries[existingEntry.noteId] = enriched
                            if (targetId.isNotEmpty()) footnoteEntries[targetId] = enriched
                            if (existingEntry.displayMarker.isNotEmpty()) footnoteEntries[existingEntry.displayMarker] = enriched
                        } else if (targetId.isNotEmpty()) {
                            val markerClean = link.label.trim('[', ']', '*', ' ')
                            val fallbackBody = existingFootnotes[targetId]
                                ?: existingFootnotes[markerClean]
                                ?: ""
                            // Always register the reference entry (even if noteText is resolved later from an endnotes chapter)
                            // so cross-chapter merging in EpubParser links the main-text reference with the endnote definition!
                            val created = EpubFootnoteEntry(
                                noteId = targetId,
                                displayMarker = markerClean.ifBlank { targetId },
                                noteText = fallbackBody,
                                footnoteFileKey = link.targetFileKey.ifBlank { cleanFileKey },
                                footnoteAnchorId = link.targetAnchorId.ifBlank { targetId },
                                sourceFileKey = cleanFileKey,
                                sourceChapterIndex = (chapterIndex - 1).coerceAtLeast(0),
                                sourceChapterTitle = chapterTitle,
                                sourceParagraphNumber = assignedParaNum ?: 1,
                                sourceAnchorId = sourceAnchor,
                                sourceContextSnippet = contextSnippet,
                                noteKind = inferredKind
                            )
                            footnoteEntries[targetId] = created
                            if (markerClean.isNotEmpty() && !footnoteEntries.containsKey(markerClean)) {
                                footnoteEntries[markerClean] = created
                            }
                        }
                    }
                }
            }
        }

        // Also match footnote definitions whose backlinkAnchorId points to an anchor in one of the semantic blocks
        for ((key, entry) in footnoteEntries.entries.toList()) {
            if (entry.sourceContextSnippet.isBlank() && entry.backlinkAnchorId.isNotEmpty()) {
                val refBlock = alignedBlocks.firstOrNull { b ->
                    b.anchorIds.any { it.equals(entry.backlinkAnchorId, ignoreCase = true) }
                }
                if (refBlock != null) {
                    val updated = entry.copy(
                        sourceFileKey = cleanFileKey,
                        sourceChapterIndex = (chapterIndex - 1).coerceAtLeast(0),
                        sourceChapterTitle = chapterTitle,
                        sourceParagraphNumber = refBlock.paragraphNumberInChapter ?: 1,
                        sourceAnchorId = entry.backlinkAnchorId,
                        sourceContextSnippet = refBlock.text.take(220)
                    )
                    footnoteEntries[key] = updated
                }
            }
        }

        val anchorMap = LinkedHashMap<String, Int>()
        for ((idx, block) in alignedBlocks.withIndex()) {
            for (aid in block.anchorIds) {
                if (aid.isNotBlank() && !anchorMap.containsKey(aid)) {
                    anchorMap[aid] = idx
                }
            }
        }

        return SemanticChapterExtraction(
            semanticBlocks = alignedBlocks,
            footnoteEntries = footnoteEntries,
            anchorToBlockIndex = anchorMap,
            totalCountedParagraphs = paragraphCounter
        )
    }

    private fun parseHtmlSemanticBlocks(
        html: String,
        fileKey: String,
        footnoteEntries: Map<String, EpubFootnoteEntry>,
        footnoteElementIds: Set<String>
    ): List<EpubSemanticBlock> {
        val blocks = mutableListOf<EpubSemanticBlock>()

        // Pre-collect standalone anchors (<a id="..."/> or <span id="..."></span>) that sit right before a block
        // so their anchor IDs attach to the immediately following semantic block!
        val pendingAnchors = mutableListOf<String>()

        // Collect character ranges of <aside>, <figure>, <table>, <dl>, <pre> or container <div/section epub:type="footnote|endnote|rearnote">
        // so inner <p> elements inside footnotes/asides/tables/figures/dl are never double-matched as counted paragraphs!
        val containerSkipRanges = Regex(
            "<(aside|figure|table|dl|pre|div|section)\\b([^>]*)>(.*?)</\\1>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(html).mapNotNull { m ->
            val tag = m.groups[1]?.value?.lowercase() ?: ""
            val attrs = m.groups[2]?.value ?: ""
            val typeRole = (extractAttr(attrs, "epub:type") ?: "") + " " +
                (extractAttr(attrs, "role") ?: "") + " " +
                (extractAttr(attrs, "class") ?: "")
            val idVal = extractAttr(attrs, "id").orEmpty()
            val isStructuredOrFnContainer = tag == "aside" ||
                tag == "figure" ||
                tag == "table" ||
                tag == "dl" ||
                tag == "pre" ||
                typeRole.contains("footnote", ignoreCase = true) ||
                typeRole.contains("endnote", ignoreCase = true) ||
                typeRole.contains("rearnote", ignoreCase = true) ||
                typeRole.contains("doc-footnote", ignoreCase = true) ||
                typeRole.contains("doc-endnote", ignoreCase = true) ||
                typeRole.contains("doc-rearnote", ignoreCase = true) ||
                (idVal.isNotEmpty() && footnoteElementIds.contains(idVal))
            if (isStructuredOrFnContainer) m.range else null
        }.toList()

        // Detect document/body-level or section-level dir="rtl" / dir="ltr" attributes
        val docDefaultDir = Regex("<(?:html|body)\\b[^>]*\\bdir\\s*=\\s*[\"'](rtl|ltr)[\"'][^>]*>", RegexOption.IGNORE_CASE)
            .find(html)?.groups?.get(1)?.value?.lowercase().orEmpty()
        val containerDirRanges = Regex(
            "<(?:div|section|article|main)\\b([^>]*\\bdir\\s*=\\s*[\"'](rtl|ltr)[\"'][^>]*)>(.*?)</(?:div|section|article|main)>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(html).map { m ->
            m.range to (m.groups[2]?.value?.lowercase().orEmpty())
        }.toList()

        // Also detect <section epub:type="chapter"> or <div epub:type="chapter"> boundaries to restart paragraph numbering
        val chapterBoundaryOffsets = Regex(
            "<(?:section|div|article)\\b[^>]*(?:epub:type|role)\\s*=\\s*[\"'][^\"']*(?:\\bchapter\\b|doc-chapter)[^\"']*[\"'][^>]*>",
            RegexOption.IGNORE_CASE
        ).findAll(html).map { it.range.first }.toSortedSet()
        var lastProcessedOffset = 0

        // Match semantic block elements in document order:
        // h1..h6, table, figure, figcaption, blockquote, ul, ol, dl, pre, hr, aside, audio, p
        val blockRegex = Regex(
            "<(h[1-6]|table|figure|figcaption|blockquote|ul|ol|dl|pre|aside|audio|p)\\b([^>]*)>(.*?)</\\1>|<(hr|audio)\\b([^>]*)/?>|<(a|span|div)\\b([^>]*\\b(?:id|name)\\s*=\\s*[\"'][^\"']+[\"'][^>]*)(?:/>|>\\s*</\\6>)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        for (match in blockRegex.findAll(html)) {
            if (chapterBoundaryOffsets.any { it in (lastProcessedOffset + 1)..match.range.first }) {
                pendingAnchors.add("__epub_chapter_reset__")
            }
            lastProcessedOffset = match.range.first

            val pairedTag = match.groups[1]?.value?.lowercase()
            if (pairedTag == "p" && containerSkipRanges.any { match.range.first > it.first && match.range.last <= it.last }) {
                // Skip <p> inside footnote/endnote/aside/table/figure/dl container
                continue
            }
            val pairedAttrs = match.groups[2]?.value ?: ""
            val pairedInner = match.groups[3]?.value ?: ""
            val selfClosingTag = match.groups[4]?.value?.lowercase()
            val selfClosingAttrs = match.groups[5]?.value ?: ""
            val emptyAnchorAttrs = match.groups[7]?.value

            if (emptyAnchorAttrs != null) {
                extractAllIds(emptyAnchorAttrs).forEach { id ->
                    if (!id.startsWith("page", ignoreCase = true)) {
                        pendingAnchors.add(id)
                    }
                }
                continue
            }

            if (selfClosingTag == "hr") {
                val hrIds = (pendingAnchors + extractAllIds(selfClosingAttrs)).distinct()
                pendingAnchors.clear()
                blocks.add(
                    EpubSemanticBlock(
                        type = EpubSemanticBlockType.DIVIDER,
                        text = "* * *",
                        anchorIds = hrIds,
                        fileKey = fileKey
                    )
                )
                continue
            }

            if (selfClosingTag == "audio" || pairedTag == "audio") {
                val audioAttrs = if (selfClosingTag == "audio") selfClosingAttrs else pairedAttrs
                val audioInner = if (selfClosingTag == "audio") "" else pairedInner
                val directSrc = extractAttr(audioAttrs, "src").orEmpty()
                val sourceSrc = if (directSrc.isNotEmpty()) directSrc else {
                    Regex("<source\\b[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                        .find(audioInner)?.groups?.get(1)?.value?.trim().orEmpty()
                }
                val cleanSrc = sourceSrc.removePrefix("../").removePrefix("./").removePrefix("/")
                if (cleanSrc.isNotEmpty()) {
                    val titleLbl = extractAttr(audioAttrs, "title")
                        ?: extractAttr(audioAttrs, "aria-label")
                        ?: cleanSrc.substringAfterLast("/")
                    val audioIds = (pendingAnchors + extractAllIds(audioAttrs) + listOf("__epub_audio_src:$cleanSrc")).distinct()
                    pendingAnchors.clear()
                    blocks.add(
                        EpubSemanticBlock(
                            type = EpubSemanticBlockType.CALLOUT,
                            text = titleLbl.ifBlank { "Audio" },
                            anchorIds = audioIds,
                            calloutLabel = "AUDIO",
                            imagePath = cleanSrc,
                            fileKey = fileKey
                        )
                    )
                }
                continue
            }

            if (pairedTag == null) continue

            val explicitBlockDir = extractAttr(pairedAttrs, "dir")?.lowercase()
                ?: extractAttr(pairedInner, "dir")?.lowercase()
                ?: containerDirRanges.lastOrNull { (r, _) -> match.range.first >= r.first && match.range.last <= r.last }?.second
                ?: docDefaultDir
            val dirAnchorList = when (explicitBlockDir) {
                "rtl" -> listOf("__epub_dir_rtl__")
                "ltr" -> listOf("__epub_dir_ltr__")
                else -> emptyList()
            }

            val blockOwnIds = extractAllIds(pairedAttrs)
            val innerIds = extractAllIdsFromHtml(pairedInner)
            val combinedIds = (pendingAnchors + blockOwnIds + innerIds + dirAnchorList).distinct()
            pendingAnchors.clear()

            val epubTypeAttr = (extractAttr(pairedAttrs, "epub:type") ?: "") + " " +
                (extractAttr(pairedAttrs, "role") ?: "") + " " +
                (extractAttr(pairedAttrs, "class") ?: "")

            val primaryId = blockOwnIds.firstOrNull().orEmpty()
            val isCalloutBlock = pairedTag == "aside" && (
                epubTypeAttr.contains("sidebar", ignoreCase = true) ||
                    epubTypeAttr.contains("tip", ignoreCase = true) ||
                    epubTypeAttr.contains("warning", ignoreCase = true) ||
                    epubTypeAttr.contains("caution", ignoreCase = true) ||
                    epubTypeAttr.contains("notice", ignoreCase = true) ||
                    epubTypeAttr.contains("callout", ignoreCase = true) ||
                    (epubTypeAttr.contains("note", ignoreCase = true) &&
                        !epubTypeAttr.contains("footnote", ignoreCase = true) &&
                        !epubTypeAttr.contains("endnote", ignoreCase = true) &&
                        !epubTypeAttr.contains("rearnote", ignoreCase = true)) ||
                    (primaryId.isEmpty() && !epubTypeAttr.contains("footnote", ignoreCase = true) &&
                        !epubTypeAttr.contains("endnote", ignoreCase = true) &&
                        !epubTypeAttr.contains("rearnote", ignoreCase = true))
                )

            // Check if this block is a footnote definition (<aside epub:type="footnote">, or id in footnoteElementIds)
            val isExplicitFootnoteBlock = !isCalloutBlock && (
                pairedTag == "aside" ||
                    epubTypeAttr.contains("footnote", ignoreCase = true) ||
                    epubTypeAttr.contains("endnote", ignoreCase = true) ||
                    epubTypeAttr.contains("rearnote", ignoreCase = true) ||
                    epubTypeAttr.contains("doc-footnote", ignoreCase = true) ||
                    epubTypeAttr.contains("doc-endnote", ignoreCase = true) ||
                    epubTypeAttr.contains("doc-rearnote", ignoreCase = true) ||
                    (primaryId.isNotEmpty() && footnoteElementIds.contains(primaryId))
                )

            if (isExplicitFootnoteBlock) {
                // Footnote definition block: emit as FOOTNOTE_DEF so it is retained with full semantic metadata but NOT counted as a body paragraph
                val backlink = findBacklinkInFootnoteHtml(pairedInner)
                val fmtRes = extractTextAndInlineFormatting(pairedInner, fileKey, footnoteEntries)
                val strippedText = stripBacklinkArrows(fmtRes.cleanText)
                if (strippedText.isNotBlank()) {
                    val fnId = primaryId.ifBlank {
                        Regex("^\\[?(\\d+)\\]?[.):]?\\s+").find(strippedText)?.groups?.get(1)?.value.orEmpty()
                    }
                    blocks.add(
                        EpubSemanticBlock(
                            type = EpubSemanticBlockType.FOOTNOTE_DEF,
                            text = strippedText,
                            anchorIds = combinedIds,
                            inlineLinks = fmtRes.inlineLinks,
                            inlineStyles = fmtRes.inlineStyles,
                            authorAlignment = fmtRes.authorAlignment,
                            isVerse = fmtRes.isVerse,
                            footnoteId = fnId,
                            backlinkAnchorId = backlink?.first?.substringAfter("#", "").orEmpty(),
                            fileKey = fileKey
                        )
                    )
                }
                continue
            }

            if (isCalloutBlock) {
                val fmtRes = extractTextAndInlineFormatting(pairedInner, fileKey, footnoteEntries)
                val codeRanges = extractInlineCodeRanges(pairedInner, fmtRes.cleanText)
                if (fmtRes.cleanText.isNotBlank()) {
                    val lowerType = epubTypeAttr.lowercase()
                    val calloutLbl = when {
                        lowerType.contains("warning") || lowerType.contains("caution") -> "WARNING"
                        lowerType.contains("tip") -> "TIP"
                        lowerType.contains("sidebar") -> "SIDEBAR"
                        else -> "NOTE"
                    }
                    blocks.add(
                        EpubSemanticBlock(
                            type = EpubSemanticBlockType.CALLOUT,
                            text = fmtRes.cleanText,
                            anchorIds = combinedIds,
                            inlineLinks = fmtRes.inlineLinks,
                            inlineCodeRanges = codeRanges,
                            inlineStyles = fmtRes.inlineStyles,
                            authorAlignment = fmtRes.authorAlignment,
                            isVerse = fmtRes.isVerse,
                            calloutLabel = calloutLbl,
                            fileKey = fileKey
                        )
                    )
                }
                continue
            }

            when (pairedTag) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    val level = pairedTag.removePrefix("h").toIntOrNull()?.coerceIn(1, 6) ?: 2
                    val fmtRes = extractTextAndInlineFormatting(pairedInner, fileKey, footnoteEntries)
                    val codeRanges = extractInlineCodeRanges(pairedInner, fmtRes.cleanText)
                    if (fmtRes.cleanText.isNotBlank()) {
                        blocks.add(
                            EpubSemanticBlock(
                                type = EpubSemanticBlockType.HEADING,
                                text = fmtRes.cleanText,
                                headingLevel = level,
                                anchorIds = combinedIds,
                                inlineLinks = fmtRes.inlineLinks,
                                inlineCodeRanges = codeRanges,
                                inlineStyles = fmtRes.inlineStyles,
                                authorAlignment = fmtRes.authorAlignment,
                                isVerse = fmtRes.isVerse,
                                fileKey = fileKey
                            )
                        )
                    }
                }
                "table" -> {
                    val (headers, rows) = parseHtmlTableWithColspan(pairedInner)
                    val summaryRows = buildList {
                        if (headers.isNotEmpty()) add(headers.joinToString(" | "))
                        rows.forEach { r -> add(r.joinToString(" | ")) }
                    }
                    val summaryText = summaryRows.joinToString(" / ")
                    if (summaryText.isNotBlank()) {
                        blocks.add(
                            EpubSemanticBlock(
                                type = EpubSemanticBlockType.TABLE,
                                text = summaryText,
                                anchorIds = combinedIds,
                                tableHeaders = headers,
                                tableRows = rows,
                                fileKey = fileKey
                            )
                        )
                    }
                }
                "blockquote" -> {
                    val fmtRes = extractTextAndInlineFormatting(pairedInner, fileKey, footnoteEntries)
                    val codeRanges = extractInlineCodeRanges(pairedInner, fmtRes.cleanText)
                    if (fmtRes.cleanText.isNotBlank()) {
                        blocks.add(
                            EpubSemanticBlock(
                                type = EpubSemanticBlockType.BLOCKQUOTE,
                                text = fmtRes.cleanText,
                                anchorIds = combinedIds,
                                inlineLinks = fmtRes.inlineLinks,
                                inlineCodeRanges = codeRanges,
                                inlineStyles = fmtRes.inlineStyles,
                                authorAlignment = fmtRes.authorAlignment,
                                isVerse = fmtRes.isVerse,
                                fileKey = fileKey
                            )
                        )
                    }
                }
                "figure" -> {
                    val imgMatch = Regex("<img\\b([^>]*)/?>|<image\\b([^>]*)/?>", RegexOption.IGNORE_CASE).find(pairedInner)
                    val imgAttrs = imgMatch?.groups?.get(1)?.value ?: imgMatch?.groups?.get(2)?.value ?: ""
                    val imgSrc = extractAttr(imgAttrs, "src") ?: extractAttr(imgAttrs, "xlink:href") ?: ""
                    val imgAlt = extractAttr(imgAttrs, "alt") ?: ""
                    val capMatch = Regex("<figcaption\\b[^>]*>(.*?)</figcaption>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(pairedInner)
                    val capText = capMatch?.groups?.get(1)?.value?.let { cleanHtmlText(it) } ?: ""
                    val bodyWithoutCap = pairedInner.replace(Regex("<figcaption\\b[^>]*>.*?</figcaption>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                    val fmtRes = extractTextAndInlineFormatting(bodyWithoutCap, fileKey, footnoteEntries)
                    val display = when {
                        capText.isNotBlank() -> capText
                        fmtRes.cleanText.isNotBlank() -> fmtRes.cleanText
                        imgAlt.isNotBlank() -> imgAlt
                        else -> "[Figure]"
                    }
                    if (display.isNotBlank() || imgSrc.isNotBlank()) {
                        blocks.add(
                            EpubSemanticBlock(
                                type = EpubSemanticBlockType.FIGURE,
                                text = display.ifBlank { "[Figure]" },
                                anchorIds = combinedIds,
                                inlineLinks = fmtRes.inlineLinks,
                                inlineStyles = fmtRes.inlineStyles,
                                authorAlignment = fmtRes.authorAlignment,
                                isVerse = fmtRes.isVerse,
                                imagePath = imgSrc,
                                imageAlt = imgAlt,
                                captionText = capText,
                                fileKey = fileKey
                            )
                        )
                    }
                }
                "figcaption" -> {
                    val fmtRes = extractTextAndInlineFormatting(pairedInner, fileKey, footnoteEntries)
                    if (fmtRes.cleanText.isNotBlank()) {
                        blocks.add(
                            EpubSemanticBlock(
                                type = EpubSemanticBlockType.FIGCAPTION,
                                text = fmtRes.cleanText,
                                anchorIds = combinedIds,
                                inlineLinks = fmtRes.inlineLinks,
                                inlineStyles = fmtRes.inlineStyles,
                                authorAlignment = fmtRes.authorAlignment,
                                isVerse = fmtRes.isVerse,
                                captionText = fmtRes.cleanText,
                                fileKey = fileKey
                            )
                        )
                    }
                }
                "ul", "ol" -> {
                    val isOrdered = pairedTag == "ol"
                    val startNum = extractAttr(pairedAttrs, "start")?.toIntOrNull() ?: 1
                    val liRegex = Regex("<li\\b([^>]*)>(.*?)</li>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    var itemCounter = startNum
                    for (liMatch in liRegex.findAll(pairedInner)) {
                        val liAttrs = liMatch.groups[1]?.value ?: ""
                        val liInner = liMatch.groups[2]?.value ?: ""
                        val liExplicitDir = extractAttr(liAttrs, "dir")?.lowercase() ?: extractAttr(liInner, "dir")?.lowercase()
                        val liDirAnchor = when (liExplicitDir) {
                            "rtl" -> listOf("__epub_dir_rtl__")
                            "ltr" -> listOf("__epub_dir_ltr__")
                            else -> emptyList()
                        }
                        val liIds = (extractAllIds(liAttrs) + extractAllIdsFromHtml(liInner) + liDirAnchor).distinct()
                        val liEpubType = (extractAttr(liAttrs, "epub:type") ?: "") + " " + (extractAttr(liAttrs, "role") ?: "") + " " + (extractAttr(liAttrs, "class") ?: "")
                        val liPrimaryId = extractAttr(liAttrs, "id").orEmpty()
                        val isFootnoteLi = liEpubType.contains("footnote", ignoreCase = true) ||
                            liEpubType.contains("endnote", ignoreCase = true) ||
                            liEpubType.contains("rearnote", ignoreCase = true) ||
                            (liPrimaryId.isNotEmpty() && footnoteElementIds.contains(liPrimaryId)) ||
                            liPrimaryId.matches(Regex("^(?:fn|footnote|note|endnote|en)[_\\-.:]?\\d+[a-zA-Z]?$", RegexOption.IGNORE_CASE))

                        val explicitVal = extractAttr(liAttrs, "value")?.toIntOrNull()
                        val num = explicitVal ?: itemCounter
                        val marker = if (isOrdered) "$num." else "•"
                        val fmtRes = extractTextAndInlineFormatting(liInner, fileKey, footnoteEntries)
                        val codeRanges = extractInlineCodeRanges(liInner, "$marker ${fmtRes.cleanText}")
                        if (fmtRes.cleanText.isNotBlank()) {
                            if (isFootnoteLi) {
                                val backlink = findBacklinkInFootnoteHtml(liInner)
                                val strippedFn = stripBacklinkArrows(fmtRes.cleanText)
                                blocks.add(
                                    EpubSemanticBlock(
                                        type = EpubSemanticBlockType.FOOTNOTE_DEF,
                                        text = "$marker $strippedFn",
                                        anchorIds = (combinedIds + liIds).distinct(),
                                        inlineLinks = fmtRes.inlineLinks,
                                        inlineStyles = fmtRes.inlineStyles,
                                        authorAlignment = fmtRes.authorAlignment,
                                        isVerse = fmtRes.isVerse,
                                        listMarker = marker,
                                        listOrdered = isOrdered,
                                        footnoteId = liPrimaryId.ifBlank { num.toString() },
                                        backlinkAnchorId = backlink?.first?.substringAfter("#", "").orEmpty(),
                                        fileKey = fileKey
                                    )
                                )
                            } else {
                                val strippedBody = fmtRes.cleanText.replace(Regex("^(?:•|\\d+\\.)\\s+"), "").trim()
                                val fullItemText = "$marker $strippedBody"
                                val markerOffset = marker.length + 1
                                val shiftedLinks = fmtRes.inlineLinks.map {
                                    it.copy(startChar = it.startChar + markerOffset, endChar = it.endChar + markerOffset)
                                }
                                val shiftedStyles = fmtRes.inlineStyles.map {
                                    it.copy(startChar = it.startChar + markerOffset, endChar = it.endChar + markerOffset)
                                }
                                blocks.add(
                                    EpubSemanticBlock(
                                        type = EpubSemanticBlockType.LIST_ITEM,
                                        text = fullItemText,
                                        anchorIds = (combinedIds + liIds).distinct(),
                                        inlineLinks = shiftedLinks,
                                        inlineCodeRanges = codeRanges,
                                        inlineStyles = shiftedStyles,
                                        authorAlignment = fmtRes.authorAlignment,
                                        isVerse = fmtRes.isVerse,
                                        listMarker = marker,
                                        listOrdered = isOrdered,
                                        fileKey = fileKey
                                    )
                                )
                            }
                        }
                        itemCounter = num + 1
                    }
                }
                "dl" -> {
                    val defItems = parseHtmlDefinitionList(pairedInner)
                    val dtDdIds = extractAllIdsFromHtml(pairedInner)
                    val flatParts = mutableListOf<String>()
                    for ((dt, dds) in defItems) {
                        if (dt.isNotBlank()) flatParts.add(dt)
                        for (dd in dds) {
                            if (dd.isNotBlank()) flatParts.add("— $dd")
                        }
                    }
                    val summaryText = flatParts.joinToString(" • ")
                    if (defItems.isNotEmpty() && summaryText.isNotBlank()) {
                        val fmtRes = extractTextAndInlineFormatting(pairedInner, fileKey, footnoteEntries)
                        val codeRanges = extractInlineCodeRanges(pairedInner, summaryText)
                        blocks.add(
                            EpubSemanticBlock(
                                type = EpubSemanticBlockType.DEFINITION_LIST,
                                text = summaryText,
                                anchorIds = (combinedIds + dtDdIds).distinct(),
                                inlineLinks = fmtRes.inlineLinks,
                                inlineCodeRanges = codeRanges,
                                inlineStyles = fmtRes.inlineStyles,
                                authorAlignment = fmtRes.authorAlignment,
                                isVerse = fmtRes.isVerse,
                                definitionItems = defItems,
                                fileKey = fileKey
                            )
                        )
                    }
                }
                "pre" -> {
                    val preservedPreText = pairedInner
                        .replace(Regex("</?code\\b[^>]*>", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                        .replace(Regex("<[^>]*>"), "")
                        .replace("&nbsp;", " ")
                        .replace("&#160;", " ")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&quot;", "\"")
                        .replace("&#39;", "'")
                        .replace("&apos;", "'")
                        .trimEnd()
                    if (preservedPreText.isNotBlank()) {
                        blocks.add(
                            EpubSemanticBlock(
                                type = EpubSemanticBlockType.PREFORMATTED,
                                text = preservedPreText,
                                anchorIds = combinedIds,
                                fileKey = fileKey
                            )
                        )
                    }
                }
                "p" -> {
                    // Check if this <p> is actually a caption or footnote paragraph by class/role/epub:type
                    val isCaptionClass = epubTypeAttr.contains("caption", ignoreCase = true) ||
                        epubTypeAttr.contains("figcaption", ignoreCase = true)
                    val subBlocks = pairedInner.split(Regex("(?:<br\\s*/?>\\s*){2,}", RegexOption.IGNORE_CASE))
                    for ((subIdx, subRaw) in subBlocks.withIndex()) {
                        val fmtRes = extractTextAndInlineFormatting(subRaw, fileKey, footnoteEntries)
                        val codeRanges = extractInlineCodeRanges(subRaw, fmtRes.cleanText)
                        if (fmtRes.cleanText.isNotBlank()) {
                            val blockType = if (isCaptionClass) {
                                EpubSemanticBlockType.FIGCAPTION
                            } else {
                                EpubSemanticBlockType.PARAGRAPH
                            }
                            blocks.add(
                                EpubSemanticBlock(
                                    type = blockType,
                                    text = fmtRes.cleanText,
                                    anchorIds = if (subIdx == 0) combinedIds else extractAllIdsFromHtml(subRaw),
                                    inlineLinks = fmtRes.inlineLinks,
                                    inlineCodeRanges = codeRanges,
                                    inlineStyles = fmtRes.inlineStyles,
                                    authorAlignment = fmtRes.authorAlignment,
                                    isVerse = fmtRes.isVerse,
                                    captionText = if (isCaptionClass) fmtRes.cleanText else "",
                                    fileKey = fileKey
                                )
                            )
                        }
                    }
                }
            }
        }

        return blocks
    }

    internal fun parseHtmlTableWithColspan(tableInnerHtml: String): Pair<List<String>, List<List<String>>> {
        val headers = mutableListOf<String>()
        val rows = mutableListOf<List<String>>()
        val trRegex = Regex("<tr\\b[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val cellRegex = Regex("<(th|td)\\b([^>]*)>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        for (trMatch in trRegex.findAll(tableInnerHtml)) {
            val trInner = trMatch.groups[1]?.value.orEmpty()
            val cellMatches = cellRegex.findAll(trInner).toList()
            if (cellMatches.isEmpty()) continue

            val isAllTh = cellMatches.all { it.groups[1]?.value.equals("th", ignoreCase = true) }
            val parsedCells = mutableListOf<String>()
            for (cMatch in cellMatches) {
                val cellHtml = cMatch.groups[3]?.value.orEmpty()
                val cellText = cleanHtmlText(cellHtml)
                // Respect colspan simply (merge into one cell) and ignore rowspan
                parsedCells.add(cellText)
            }
            if (parsedCells.all { it.isBlank() }) continue
            if (isAllTh && headers.isEmpty() && rows.isEmpty()) {
                headers.addAll(parsedCells)
            } else {
                rows.add(parsedCells)
            }
        }
        return Pair(headers, rows)
    }

    internal fun parseHtmlDefinitionList(dlInnerHtml: String): List<Pair<String, List<String>>> {
        val results = mutableListOf<Pair<String, MutableList<String>>>()
        val itemRegex = Regex("<(dt|dd)\\b[^>]*>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (m in itemRegex.findAll(dlInnerHtml)) {
            val tag = m.groups[1]?.value?.lowercase().orEmpty()
            val text = cleanHtmlText(m.groups[2]?.value.orEmpty())
            if (text.isBlank()) continue
            if (tag == "dt") {
                results.add(Pair(text, mutableListOf()))
            } else if (tag == "dd") {
                if (results.isEmpty()) {
                    results.add(Pair("", mutableListOf(text)))
                } else {
                    results.last().second.add(text)
                }
            }
        }
        return results.map { it.first to it.second.toList() }
    }

    internal fun extractInlineCodeRanges(rawHtml: String, cleanText: String): List<IntRange> {
        if (cleanText.isEmpty() || !rawHtml.contains("<code", ignoreCase = true)) return emptyList()
        val codeRegex = Regex("<code\\b[^>]*>(.*?)</code>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val ranges = mutableListOf<IntRange>()
        var searchFrom = 0
        for (m in codeRegex.findAll(rawHtml)) {
            val snippet = cleanHtmlText(m.groups[1]?.value.orEmpty())
            if (snippet.isNotBlank()) {
                val idx = cleanText.indexOf(snippet, startIndex = searchFrom)
                    .takeIf { it >= 0 }
                    ?: cleanText.indexOf(snippet)
                if (idx >= 0) {
                    val endIdx = (idx + snippet.length - 1).coerceAtLeast(idx)
                    ranges.add(idx..endIdx)
                    searchFrom = (idx + snippet.length).coerceAtMost(cleanText.length)
                }
            }
        }
        return ranges
    }

    /**
     * Extracts plain readable text while preserving exact character spans of
     * Readium CSS / ts-toolkit inline formatting spans (italic, bold, underline, strikethrough,
     * superscript, subscript, small, monospace, mark, drop-cap, small-caps),
     * `<a href="...">` links, footnote/glossary references, author alignment, and verse stanzas.
     */
    fun extractTextAndInlineFormatting(
        rawHtmlFragment: String,
        currentFileKey: String,
        footnoteEntries: Map<String, EpubFootnoteEntry>
    ): FormattedTextExtraction {
        val alignVal = extractAttr(rawHtmlFragment, "align")?.lowercase()
        val styleVal = extractAttr(rawHtmlFragment, "style")?.lowercase().orEmpty()
        val classVal = extractAttr(rawHtmlFragment, "class")?.lowercase().orEmpty()
        val authorAlign = when {
            alignVal == "center" || styleVal.contains(Regex("text-align\\s*:\\s*center")) ||
                classVal.contains(Regex("\\b(center|text-center|centered)\\b")) || rawHtmlFragment.contains("<center>", ignoreCase = true) -> "center"
            alignVal == "right" || styleVal.contains(Regex("text-align\\s*:\\s*right")) ||
                classVal.contains(Regex("\\b(right|text-right)\\b")) -> "right"
            alignVal == "justify" || styleVal.contains(Regex("text-align\\s*:\\s*justify")) ||
                classVal.contains(Regex("\\b(justify|text-justify)\\b")) -> "justify"
            alignVal == "left" || styleVal.contains(Regex("text-align\\s*:\\s*left")) ||
                classVal.contains(Regex("\\b(left|text-left)\\b")) -> "left"
            else -> ""
        }
        val isVerse = classVal.contains(Regex("\\b(verse|poem|poetry|stanza)\\b")) ||
            rawHtmlFragment.contains(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE))

        var working = rawHtmlFragment

        // 1. Preserve explicit <br> as newline sentinel
        working = working.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ⟦EPUB_BR⟧ ")

        // 2. Footnote / link sentinels
        val anchorSupRegex = Regex(
            "<a\\b([^>]*)>\\s*<sup\\b([^>]*)>(.*?)</sup>\\s*</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        working = anchorSupRegex.replace(working) { m ->
            val aAttrs = m.groups[1]?.value ?: ""
            val supAttrs = m.groups[2]?.value ?: ""
            val inner = cleanHtmlText(m.groups[3]?.value ?: "")
            val href = extractAttr(aAttrs, "href").orEmpty()
            val idVal = extractAttr(aAttrs, "id") ?: extractAttr(supAttrs, "id") ?: ""
            val label = if (inner.startsWith("[") && inner.endsWith("]")) inner else "[$inner]"
            val metaPayload = encodeLinkPayload(href = href, isFn = true, anchorId = idVal, rawMarker = inner.trim('[', ']', ' '))
            " $LINK_START_TOKEN$metaPayload$LINK_MID_TOKEN$label$LINK_END_TOKEN"
        }

        val supAnchorRegex = Regex(
            "<sup\\b([^>]*)>\\s*<a\\b([^>]*)>(.*?)</a>\\s*</sup>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        working = supAnchorRegex.replace(working) { m ->
            val supAttrs = m.groups[1]?.value ?: ""
            val aAttrs = m.groups[2]?.value ?: ""
            val inner = cleanHtmlText(m.groups[3]?.value ?: "")
            val href = extractAttr(aAttrs, "href").orEmpty()
            val idVal = extractAttr(aAttrs, "id") ?: extractAttr(supAttrs, "id") ?: ""
            val label = if (inner.startsWith("[") && inner.endsWith("]")) inner else "[$inner]"
            val metaPayload = encodeLinkPayload(href = href, isFn = true, anchorId = idVal, rawMarker = inner.trim('[', ']', ' '))
            " $LINK_START_TOKEN$metaPayload$LINK_MID_TOKEN$label$LINK_END_TOKEN"
        }

        val plainSupRegex = Regex(
            "<sup\\b([^>]*)>\\s*\\[?(\\d+)\\]?\\s*</sup>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        working = plainSupRegex.replace(working) { m ->
            val supAttrs = m.groups[1]?.value ?: ""
            val num = m.groups[2]?.value?.trim() ?: ""
            val idVal = extractAttr(supAttrs, "id").orEmpty()
            val label = "[$num]"
            val metaPayload = encodeLinkPayload(href = "#$num", isFn = true, anchorId = idVal, rawMarker = num)
            " $LINK_START_TOKEN$metaPayload$LINK_MID_TOKEN$label$LINK_END_TOKEN"
        }

        val anchorRegex = Regex(
            "<a\\b([^>]*)>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        working = anchorRegex.replace(working) { m ->
            val aAttrs = m.groups[1]?.value ?: ""
            val innerText = cleanHtmlText(m.groups[2]?.value ?: "")
            val href = extractAttr(aAttrs, "href")?.trim().orEmpty()
            val idVal = extractAttr(aAttrs, "id") ?: extractAttr(aAttrs, "name") ?: ""
            if (href.isEmpty() || innerText.isEmpty()) {
                innerText
            } else {
                val epubType = (extractAttr(aAttrs, "epub:type") ?: "") + " " +
                    (extractAttr(aAttrs, "role") ?: "") + " " +
                    (extractAttr(aAttrs, "class") ?: "")
                val fragment = href.substringAfter("#", "").trim()
                val isExplicitNoteRef = epubType.contains("noteref", ignoreCase = true) ||
                    epubType.contains("glossref", ignoreCase = true) ||
                    epubType.contains("biblioref", ignoreCase = true) ||
                    epubType.contains("footnote", ignoreCase = true) ||
                    epubType.contains("doc-noteref", ignoreCase = true) ||
                    epubType.contains("doc-glossref", ignoreCase = true) ||
                    epubType.contains("doc-biblioref", ignoreCase = true) ||
                    epubType.contains("backlink", ignoreCase = true) ||
                    (fragment.isNotEmpty() && (
                        footnoteEntries.containsKey(fragment) ||
                            fragment.matches(Regex("^(?:fn|footnote|note|endnote|rearnote|en|fnref|gloss|dfn|bib|biblio)[_\\-.:]?[a-zA-Z0-9_\\-]+$", RegexOption.IGNORE_CASE))
                        )) ||
                    innerText.matches(Regex("^\\[?\\d+\\]?$"))
                val displayLabel = if (isExplicitNoteRef && innerText.matches(Regex("^\\d+$"))) {
                    "[$innerText]"
                } else {
                    innerText
                }
                val prefixSpace = if (isExplicitNoteRef && displayLabel.startsWith("[")) " " else ""
                val metaPayload = encodeLinkPayload(
                    href = href,
                    isFn = isExplicitNoteRef,
                    anchorId = idVal,
                    rawMarker = innerText.trim('[', ']', ' ')
                )
                "$prefixSpace$LINK_START_TOKEN$metaPayload$LINK_MID_TOKEN$displayLabel$LINK_END_TOKEN"
            }
        }

        // 3. Replace inline formatting tags with sentinel markers
        val spanRegex = Regex("<span\\b([^>]*)>(.*?)</span>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        working = spanRegex.replace(working) { sm ->
            val attrs = sm.groups[1]?.value.orEmpty()
            val inner = sm.groups[2]?.value.orEmpty()
            val lowerAttrs = attrs.lowercase()
            when {
                lowerAttrs.contains("dropcap") || lowerAttrs.contains("first-letter") ->
                    "⟦FMT:DROPCAP⟧$inner⟦/FMT:DROPCAP⟧"
                lowerAttrs.contains("small-caps") || lowerAttrs.contains("font-variant: small-caps") || lowerAttrs.contains(Regex("\\bsc\\b")) ->
                    "⟦FMT:SMALLCAPS⟧$inner⟦/FMT:SMALLCAPS⟧"
                lowerAttrs.contains("font-style: italic") || lowerAttrs.contains(Regex("\\bitalic\\b")) ->
                    "⟦FMT:ITALIC⟧$inner⟦/FMT:ITALIC⟧"
                lowerAttrs.contains("font-weight: bold") || lowerAttrs.contains(Regex("\\bbold\\b")) ->
                    "⟦FMT:BOLD⟧$inner⟦/FMT:BOLD⟧"
                lowerAttrs.contains("underline") ->
                    "⟦FMT:UNDERLINE⟧$inner⟦/FMT:UNDERLINE⟧"
                else -> inner
            }
        }

        // Standard formatting tags (em, i, cite, dfn, var)
        working = working.replace(Regex("<(?:em|i|cite|dfn|var)\\b[^>]*>", RegexOption.IGNORE_CASE), "⟦FMT:ITALIC⟧")
            .replace(Regex("</(?:em|i|cite|dfn|var)>", RegexOption.IGNORE_CASE), "⟦/FMT:ITALIC⟧")
        // strong, b
        working = working.replace(Regex("<(?:strong|b)\\b[^>]*>", RegexOption.IGNORE_CASE), "⟦FMT:BOLD⟧")
            .replace(Regex("</(?:strong|b)>", RegexOption.IGNORE_CASE), "⟦/FMT:BOLD⟧")
        // u, ins
        working = working.replace(Regex("<(?:u|ins)\\b[^>]*>", RegexOption.IGNORE_CASE), "⟦FMT:UNDERLINE⟧")
            .replace(Regex("</(?:u|ins)>", RegexOption.IGNORE_CASE), "⟦/FMT:UNDERLINE⟧")
        // s, strike, del
        working = working.replace(Regex("<(?:s|strike|del)\\b[^>]*>", RegexOption.IGNORE_CASE), "⟦FMT:STRIKE⟧")
            .replace(Regex("</(?:s|strike|del)>", RegexOption.IGNORE_CASE), "⟦/FMT:STRIKE⟧")
        // sup (non-footnote)
        working = working.replace(Regex("<sup\\b[^>]*>", RegexOption.IGNORE_CASE), "⟦FMT:SUP⟧")
            .replace(Regex("</sup>", RegexOption.IGNORE_CASE), "⟦/FMT:SUP⟧")
        // sub
        working = working.replace(Regex("<sub\\b[^>]*>", RegexOption.IGNORE_CASE), "⟦FMT:SUB⟧")
            .replace(Regex("</sub>", RegexOption.IGNORE_CASE), "⟦/FMT:SUB⟧")
        // small
        working = working.replace(Regex("<small\\b[^>]*>", RegexOption.IGNORE_CASE), "⟦FMT:SMALL⟧")
            .replace(Regex("</small>", RegexOption.IGNORE_CASE), "⟦/FMT:SMALL⟧")
        // code, kbd, samp, tt
        working = working.replace(Regex("<(?:code|kbd|samp|tt)\\b[^>]*>", RegexOption.IGNORE_CASE), "⟦FMT:MONO⟧")
            .replace(Regex("</(?:code|kbd|samp|tt)>", RegexOption.IGNORE_CASE), "⟦/FMT:MONO⟧")
        // mark
        working = working.replace(Regex("<mark\\b[^>]*>", RegexOption.IGNORE_CASE), "⟦FMT:MARK⟧")
            .replace(Regex("</mark>", RegexOption.IGNORE_CASE), "⟦/FMT:MARK⟧")

        // 4. Strip any other HTML tags and decode entities
        val cleanedWithTokens = cleanHtmlText(working, preserveNewlines = true)

        // 5. Tokenize sentinels and build clean text + spans
        val generalTokenRegex = Regex(
            "${Regex.escape(LINK_START_TOKEN)}(.*?)${Regex.escape(LINK_MID_TOKEN)}(.*?)${Regex.escape(LINK_END_TOKEN)}|⟦FMT:(ITALIC|BOLD|UNDERLINE|STRIKE|SUP|SUB|SMALL|MONO|MARK|DROPCAP|SMALLCAPS)⟧|⟦/FMT:(ITALIC|BOLD|UNDERLINE|STRIKE|SUP|SUB|SMALL|MONO|MARK|DROPCAP|SMALLCAPS)⟧|⟦EPUB_BR⟧"
        )

        val outText = StringBuilder()
        val links = mutableListOf<EpubInlineLink>()
        val styles = mutableListOf<EpubInlineStyleSpan>()
        val openStyleStacks = mutableMapOf<EpubInlineStyleType, ArrayDeque<Int>>()
        for (t in EpubInlineStyleType.values()) {
            openStyleStacks[t] = ArrayDeque()
        }

        var cursor = 0
        for (m in generalTokenRegex.findAll(cleanedWithTokens)) {
            if (m.range.first > cursor) {
                outText.append(cleanedWithTokens.substring(cursor, m.range.first))
            }
            val matchVal = m.value
            when {
                matchVal == "⟦EPUB_BR⟧" -> {
                    outText.append("\n")
                }
                matchVal.startsWith(LINK_START_TOKEN) -> {
                    val payload = m.groups[1]?.value.orEmpty()
                    val label = m.groups[2]?.value.orEmpty()
                    val (href, isFnFlag, _, rawMarker) = decodeLinkPayload(payload)
                    val isFn = isFnFlag == "1"
                    val startPos = outText.length
                    outText.append(label)
                    val endPos = outText.length
                    val rawFilePart = href.substringBefore("#").substringAfterLast("/").trim()
                    val targetFile = rawFilePart.ifBlank { currentFileKey }
                    val targetAnchor = if (href.contains("#")) href.substringAfter("#").trim() else ""
                    val fnId = if (isFn) {
                        when {
                            targetAnchor.isNotEmpty() && footnoteEntries.containsKey(targetAnchor) -> targetAnchor
                            rawMarker.isNotEmpty() && footnoteEntries.containsKey(rawMarker) -> rawMarker
                            targetAnchor.isNotEmpty() -> targetAnchor
                            else -> rawMarker
                        }
                    } else ""
                    val refKind = when {
                        targetAnchor.startsWith("gloss", ignoreCase = true) || targetAnchor.startsWith("dfn", ignoreCase = true) -> "glossref"
                        targetAnchor.startsWith("bib", ignoreCase = true) -> "biblioref"
                        isFn -> "noteref"
                        else -> ""
                    }
                    links.add(
                        EpubInlineLink(
                            startChar = startPos,
                            endChar = endPos,
                            label = label,
                            targetHref = href,
                            targetFileKey = targetFile,
                            targetAnchorId = targetAnchor,
                            isFootnoteRef = isFn,
                            footnoteId = fnId,
                            refType = refKind
                        )
                    )
                }
                matchVal.startsWith("⟦FMT:") -> {
                    val tagType = m.groups[3]?.value.orEmpty()
                    val styleType = mapTagTypeToStyleType(tagType)
                    if (styleType != null) {
                        openStyleStacks[styleType]?.addLast(outText.length)
                    }
                }
                matchVal.startsWith("⟦/FMT:") -> {
                    val tagType = m.groups[4]?.value.orEmpty()
                    val styleType = mapTagTypeToStyleType(tagType)
                    if (styleType != null) {
                        val stack = openStyleStacks[styleType]
                        if (stack != null && stack.isNotEmpty()) {
                            val startChar = stack.removeLast()
                            val endChar = outText.length
                            if (endChar > startChar) {
                                styles.add(EpubInlineStyleSpan(startChar = startChar, endChar = endChar, type = styleType))
                            }
                        }
                    }
                }
            }
            cursor = m.range.last + 1
        }
        if (cursor < cleanedWithTokens.length) {
            outText.append(cleanedWithTokens.substring(cursor))
        }

        // Close any dangling open styles
        for ((styleType, stack) in openStyleStacks) {
            while (stack.isNotEmpty()) {
                val startChar = stack.removeLast()
                val endChar = outText.length
                if (endChar > startChar) {
                    styles.add(EpubInlineStyleSpan(startChar = startChar, endChar = endChar, type = styleType))
                }
            }
        }

        val rawResultText = outText.toString()
        val trimmedText = rawResultText.trim()
        val leadingTrim = rawResultText.length - rawResultText.trimStart().length
        val adjustedLinks = if (leadingTrim > 0) {
            links.mapNotNull {
                val s = (it.startChar - leadingTrim).coerceIn(0, trimmedText.length)
                val e = (it.endChar - leadingTrim).coerceIn(s, trimmedText.length)
                if (e > s) it.copy(startChar = s, endChar = e) else null
            }
        } else links

        val adjustedStyles = if (leadingTrim > 0) {
            styles.mapNotNull {
                val s = (it.startChar - leadingTrim).coerceIn(0, trimmedText.length)
                val e = (it.endChar - leadingTrim).coerceIn(s, trimmedText.length)
                if (e > s) it.copy(startChar = s, endChar = e) else null
            }
        } else styles

        return FormattedTextExtraction(
            cleanText = trimmedText,
            inlineLinks = adjustedLinks,
            inlineStyles = adjustedStyles,
            authorAlignment = authorAlign,
            isVerse = isVerse
        )
    }

    private fun mapTagTypeToStyleType(tagType: String): EpubInlineStyleType? {
        return when (tagType) {
            "ITALIC" -> EpubInlineStyleType.ITALIC
            "BOLD" -> EpubInlineStyleType.BOLD
            "UNDERLINE" -> EpubInlineStyleType.UNDERLINE
            "STRIKE" -> EpubInlineStyleType.STRIKETHROUGH
            "SUP" -> EpubInlineStyleType.SUPERSCRIPT
            "SUB" -> EpubInlineStyleType.SUBSCRIPT
            "SMALL" -> EpubInlineStyleType.SMALL
            "MONO" -> EpubInlineStyleType.MONOSPACE
            "MARK" -> EpubInlineStyleType.MARK
            "DROPCAP" -> EpubInlineStyleType.DROP_CAP
            "SMALLCAPS" -> EpubInlineStyleType.SMALL_CAPS
            else -> null
        }
    }

    /**
     * Extracts plain readable text while preserving the exact character spans and targets of
     * `<a href="...">` links and footnote/glossary/bibliography references (`epub:type="noteref|glossref|biblioref"`).
     */
    fun extractTextAndInlineLinks(
        rawHtmlFragment: String,
        currentFileKey: String,
        footnoteEntries: Map<String, EpubFootnoteEntry>
    ): Pair<String, List<EpubInlineLink>> {
        val res = extractTextAndInlineFormatting(rawHtmlFragment, currentFileKey, footnoteEntries)
        return Pair(res.cleanText, res.inlineLinks)
    }

    private fun findBacklinkInFootnoteHtml(footnoteInnerHtml: String): Pair<String, String>? {
        val anchorRegex = Regex(
            "<a\\b([^>]*)>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val matches = anchorRegex.findAll(footnoteInnerHtml).toList()
        if (matches.isEmpty()) return null
        // Prefer explicit doc-backlink / backlink / return arrow link, or a fragment link in the footnote
        val explicit = matches.firstOrNull { m ->
            val attrs = m.groups[1]?.value ?: ""
            val inner = cleanHtmlText(m.groups[2]?.value ?: "")
            attrs.contains("backlink", ignoreCase = true) ||
                attrs.contains("noteref", ignoreCase = true) ||
                inner.contains("↩") ||
                inner.contains("↑") ||
                inner.contains("back", ignoreCase = true)
        } ?: matches.firstOrNull { m ->
            val href = extractAttr(m.groups[1]?.value ?: "", "href").orEmpty()
            href.contains("#")
        }
        if (explicit != null) {
            val href = extractAttr(explicit.groups[1]?.value ?: "", "href").orEmpty()
            val label = cleanHtmlText(explicit.groups[2]?.value ?: "")
            if (href.isNotEmpty()) return Pair(href, label)
        }
        return null
    }

    private fun stripBacklinkArrows(text: String): String {
        return text
            .replace(Regex("\\s*[↩↑↵]+\\s*$"), "")
            .trim()
    }

    private fun extractFootnoteMarkerFromIdOrText(idVal: String, noteText: String): String {
        val fromText = Regex("^(?:\\[(\\d+)\\]|(\\d+)[.):])\\s+").find(noteText)
        if (fromText != null) {
            val n = fromText.groups[1]?.value ?: fromText.groups[2]?.value
            if (!n.isNullOrEmpty()) return n
        }
        val fromId = Regex("(\\d+[a-zA-Z]?)$").find(idVal)?.groups?.get(1)?.value
        return fromId ?: idVal
    }

    private fun buildContextSnippetAroundRange(fullText: String, start: Int, end: Int): String {
        if (fullText.isBlank()) return ""
        val clean = fullText.trim()
        if (clean.length <= 220) return clean
        val safeStart = (start - 90).coerceAtLeast(0)
        val safeEnd = (end + 110).coerceAtMost(clean.length)
        val prefix = if (safeStart > 0) "…" else ""
        val suffix = if (safeEnd < clean.length) "…" else ""
        return "$prefix${clean.substring(safeStart, safeEnd).trim()}$suffix"
    }

    private fun encodeLinkPayload(href: String, isFn: Boolean, anchorId: String, rawMarker: String): String {
        fun esc(s: String) = s.replace("|", "%7C")
        return "${esc(href)}|${if (isFn) "1" else "0"}|${esc(anchorId)}|${esc(rawMarker)}"
    }

    private fun decodeLinkPayload(payload: String): List<String> {
        val parts = payload.split("|")
        fun unesc(s: String) = s.replace("%7C", "|")
        return listOf(
            unesc(parts.getOrElse(0) { "" }),
            parts.getOrElse(1) { "0" },
            unesc(parts.getOrElse(2) { "" }),
            unesc(parts.getOrElse(3) { "" })
        )
    }

    private fun extractAttr(attrs: String, name: String): String? {
        val regex = Regex("\\b${Regex.escape(name)}\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        return regex.find(attrs)?.groups?.get(1)?.value?.trim()
    }

    private fun extractAllIds(attrs: String): List<String> {
        if (attrs.isBlank()) return emptyList()
        val ids = mutableListOf<String>()
        val regex = Regex("\\b(?:id|name)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        for (m in regex.findAll(attrs)) {
            val v = m.groups[1]?.value?.trim() ?: continue
            if (v.isNotEmpty()) ids.add(v)
        }
        return ids.distinct()
    }

    private fun extractAllIdsFromHtml(htmlFragment: String): List<String> {
        if (htmlFragment.isBlank()) return emptyList()
        val ids = mutableListOf<String>()
        val regex = Regex("<[a-zA-Z0-9:]+\\b[^>]*\\b(?:id|name)\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>", RegexOption.IGNORE_CASE)
        for (m in regex.findAll(htmlFragment)) {
            val v = m.groups[1]?.value?.trim() ?: continue
            if (v.isNotEmpty() && !v.startsWith("page", ignoreCase = true)) {
                ids.add(v)
            }
        }
        return ids.distinct()
    }

    private fun resolveRelativePath(baseDir: String, relativeHref: String): String {
        if (baseDir.isEmpty() || relativeHref.startsWith("/")) return relativeHref.removePrefix("/")
        val combined = (baseDir + relativeHref).split("/")
        val stack = ArrayList<String>()
        for (seg in combined) {
            when (seg) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> stack.add(seg)
            }
        }
        return stack.joinToString("/")
    }

    private fun cleanHtmlText(raw: String, preserveNewlines: Boolean = false): String {
        // Remove sentinel pagebreak markers if any leaked into fragment
        var result = raw
            .replace(Regex("⟦EPUB_PAGE_EQ:.*?⟧"), " ")
            .replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&#160;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&rsquo;", "’")
            .replace("&lsquo;", "‘")
            .replace("&rdquo;", "”")
            .replace("&ldquo;", "“")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&bull;", "•")

        result = Regex("&#(\\d+);").replace(result) { m ->
            val code = m.groups[1]?.value?.toIntOrNull()
            if (code != null && code in 32..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        result = Regex("&#x([0-9a-fA-F]+);").replace(result) { m ->
            val code = m.groups[1]?.value?.toIntOrNull(16)
            if (code != null && code in 32..0x10FFFF) String(Character.toChars(code)) else m.value
        }

        return if (preserveNewlines) {
            result
                .split("\n")
                .joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trim() }
                .trim()
        } else {
            result
                .replace(Regex("[ \\t\\r\\n]+"), " ")
                .trim()
        }
    }
}
