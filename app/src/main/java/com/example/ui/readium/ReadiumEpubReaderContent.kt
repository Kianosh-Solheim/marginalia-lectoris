package com.example.ui.readium

import com.example.reader.readium.PrintPageLayoutMode
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.toArgb

import android.content.Context
import android.content.ContextWrapper
import android.graphics.PointF
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.model.parseHighlightColor
import com.example.reader.BookChapter
import com.example.reader.readium.ReadiumPublicationManager
import com.example.ui.ActiveSelectionToolbarState
import com.example.ui.ReaderTheme
import com.example.ui.SelectionActionToolbarCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.Selection
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.epub.pageList
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.util.AbsoluteUrl
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches exact Readium [Locator] instances (including DOM range & CSS selector) by document and quote
 * so newly created highlights render immediately in the Readium navigator.
 */
/**
 * Keeps the reading position when the reader is recreated because the printed-page layout mode
 * changed (e.g. toggling 1:1 pagination or switching to vertical scroll). Only a very recent
 * handoff is honoured, so opening a book later still uses the requested initial page.
 */
private object ReadiumLocatorHandoff {
    private val latest = ConcurrentHashMap<Long, Locator>()
    private val handoffAt = ConcurrentHashMap<Long, Long>()

    fun remember(documentId: Long, locator: Locator) { latest[documentId] = locator }

    fun markHandoff(documentId: Long) { handoffAt[documentId] = System.currentTimeMillis() }

    fun take(documentId: Long): Locator? {
        val at = handoffAt.remove(documentId) ?: return null
        val loc = latest[documentId] ?: return null
        return if (System.currentTimeMillis() - at < 5_000L) loc else null
    }
}

/** First part of a selection that continues on the next page (one note for both parts). */
private data class CarriedEpubSelection(
    val quote: String,
    val locator: Locator,
    val page: Int,
    val chapterTitle: String,
    val citation: String,
    val printLabel: String,
    val cfi: String
)

private object ReadiumHighlightLocatorCache {
    private val map = ConcurrentHashMap<String, Locator>()

    private fun key(documentId: Long, quote: String): String =
        "$documentId::${quote.trim().lowercase()}"

    fun put(documentId: Long, quote: String, locator: Locator) {
        if (quote.isNotBlank()) {
            map[key(documentId, quote)] = locator
        }
    }

    fun get(documentId: Long, quote: String): Locator? =
        map[key(documentId, quote)]
}

/**
 * Hosts the Readium Mobile [EpubNavigatorFragment] in Jetpack Compose.
 *
 * Implements:
 * - Native EPUB 3 rendering with exact publisher CSS fidelity
 * - Explicit Single-Column vs Two-Column reading modes that persist and update in-place without losing position
 * - Logical interaction between Publisher Styling and custom reader typography/layout controls
 * - Custom Paper & Ink Highlighting Menu on text selection and persistent highlight decorations
 * - Safe fragment attachment and lifecycle handling with fallback recovery
 */
@OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
@Composable
fun ReadiumEpubReaderContent(
    document: DocumentEntity,
    initialPage: Int,
    isUiVisible: Boolean,
    activeTheme: ReaderTheme,
    publisherStylesEnabled: Boolean,
    columnCount: Int,
    viewModeSetting: String,
    fontSizeSp: Float,
    marginDp: Int,
    topMarginDp: Int = 16,
    bottomMarginDp: Int = 16,
    textAlignSetting: String,
    fontFamilySetting: String,
    lineSpacingMultiplier: Float,
    paragraphStyleSetting: String = "BOTH",
    paginateByEpubPageEquivalents: Boolean = false,
    printLayoutMode: PrintPageLayoutMode = PrintPageLayoutMode.OFF,
    glossaeEnabled: Boolean = false,
    highlights: List<HighlightEntity> = emptyList(),
    pageTurnTrigger: kotlinx.coroutines.flow.SharedFlow<Int>,
    targetPageRequest: Int?,
    targetHighlightRequest: HighlightEntity? = null,
    targetPrintPageRequest: String? = null,
    jumpRequestToken: Int = 0,
    onToggleUi: () -> Unit,
    onPageChanged: (page: Int, total: Int, chapterTitle: String) -> Unit,
    onProgressUpdate: (page: Int, total: Int) -> Unit,
    onActiveEpubCfiChanged: (String) -> Unit = {},
    onActiveEpubPageEquivalentChanged: (label: String, hasPageList: Boolean) -> Unit = { _, _ -> },
    onChaptersLoaded: (List<BookChapter>) -> Unit = {},
    onFixedLayoutDetected: (Boolean) -> Unit = {},
    onFallbackToClassic: () -> Unit = {},
    onSpeakSelection: (quote: String, pageNumber: Int) -> Unit = { _, _ -> },
    onRequestHighlight: (quote: String, pageNumber: Int, chapterTitle: String, colorHex: String, paragraphCitation: String, pageEquivalent: String, epubCfi: String) -> Unit = { _, _, _, _, _, _, _ -> },
    onQuickSaveHighlight: (quote: String, pageNumber: Int, chapterTitle: String, colorHex: String, paragraphCitation: String, pageEquivalent: String, epubCfi: String) -> Unit = { _, _, _, _, _, _, _ -> },
    onAddAsAuthor: (String) -> Unit = {},
    onSetAsAbstract: (String) -> Unit = {},
    onEditHighlight: (HighlightEntity) -> Unit = {},
    /** Open at this resource#anchor (e.g. "p0016.xhtml#pdfpage-34"), before any saved position. */
    initialHref: String? = null,
    /** The page-list anchor id of the page shown (e.g. "pdfpage-34"). */
    onPrintPageAnchorChanged: (String) -> Unit = {},
    /** Resource (file name) holding a saved highlight, when page numbers are not EPUB positions (reflowed PDFs). */
    highlightHref: ((HighlightEntity) -> String?)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val coroutineScope = rememberCoroutineScope()

    var publication by remember(document.id) { mutableStateOf<Publication?>(null) }
    var publicationPositions by remember(document.id) { mutableStateOf<List<Locator>>(emptyList()) }
    var isLoading by remember(document.id) { mutableStateOf(true) }
    var errorMessage by remember(document.id) { mutableStateOf<String?>(null) }
    var navigatorFragment by remember(document.id) { mutableStateOf<EpubNavigatorFragment?>(null) }
    var isFixedLayoutBook by remember(document.id) { mutableStateOf(false) }
    var latestLocator by remember(document.id) { mutableStateOf<Locator?>(null) }
    var currentTrackedPosition by remember(document.id) { mutableIntStateOf(initialPage.coerceAtLeast(1)) }
    var currentPrintPageLabel by remember(document.id) { mutableStateOf("") }
    var resourcePageLists by remember(document.id) { mutableStateOf<List<ReadiumResourcePageList>>(emptyList()) }

    // Readium WebView text selection state for custom Highlighting Menu
    var isSelectionModeActive by remember(document.id) { mutableStateOf(false) }
    var selectionPollTick by remember(document.id) { mutableIntStateOf(0) }
    var activeReadiumSelection by remember(document.id) { mutableStateOf<Selection?>(null) }
    // Finger tracking over the book page: the selection is only read, and the highlight menu only
    // shown, once the finger is lifted, so dragging the selection handles is never interrupted.
    var isTouchDown by remember(document.id) { mutableStateOf(false) }
    var carriedEpub by remember(document.id) { mutableStateOf<CarriedEpubSelection?>(null) }
    var touchUpTick by remember(document.id) { mutableIntStateOf(0) }
    var readerHeightPx by remember(document.id) { mutableIntStateOf(0) }

    val selectionActionModeCallback = remember(document.id) {
        object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                menu?.clear()
                isSelectionModeActive = true
                selectionPollTick++
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                menu?.clear()
                isSelectionModeActive = true
                selectionPollTick++
                return true
            }

            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false

            override fun onDestroyActionMode(mode: ActionMode?) {
                isSelectionModeActive = false
                activeReadiumSelection = null
                selectionPollTick++
            }
        }
    }

    val isVerticalScroll = viewModeSetting.equals("VERTICAL_SCROLL", ignoreCase = true)

    // Glossae: which gloss cards are currently flipped to show the original text, and which one just flipped.
    var glossFlipped by remember(document.id) { mutableStateOf(emptySet<Long>()) }
    var glossLastFlipped by remember(document.id) { mutableStateOf<Long?>(null) }
    var glossPreview by remember(document.id) { mutableStateOf<HighlightEntity?>(null) }
    val glossDensity = androidx.compose.ui.platform.LocalDensity.current.density
    val currentGlossaeEnabled by rememberUpdatedState(glossaeEnabled)
    val currentGlossFlipped by rememberUpdatedState(glossFlipped)
    val currentFontSizeSp by rememberUpdatedState(fontSizeSp)
    LaunchedEffect(glossaeEnabled) {
        glossFlipped = emptySet()
        glossLastFlipped = null
    }
    // Strict 1:1 print pagination needs exactly one printed page per screen, so two-column spreads are disabled.
    val forceSingleColumn = printLayoutMode == PrintPageLayoutMode.PAGINATED_STRICT

    // Unique view container and fragment tag for this document instance
    val containerId = remember(document.id) { View.generateViewId() }
    val fragmentTag = remember(document.id, printLayoutMode) { "readium_epub_fragment_${document.id}_${printLayoutMode.name}" }

    // Load publication via Readium
    LaunchedEffect(document.id, document.filePath) {
        isLoading = true
        errorMessage = null
        try {
            val file = File(document.filePath)
            val pub = ReadiumPublicationManager.openPublication(context, file, printLayoutMode)
            if (pub != null) {
                val positions = try {
                    pub.positions()
                } catch (_: Throwable) {
                    emptyList()
                }
                publicationPositions = positions
                publication = pub
                val fxl = ReadiumPublicationManager.isFixedLayout(pub)
                isFixedLayoutBook = fxl
                onFixedLayoutDetected(fxl)

                // Convert Table of Contents with accurate position/spine mapping
                val chapters = flattenToc(pub.tableOfContents, pub.readingOrder, positions)
                onChaptersLoaded(chapters)
                isLoading = false
            } else {
                isLoading = false
                errorMessage = "Unable to open EPUB with Readium engine."
            }
        } catch (t: Throwable) {
            Log.e("ReadiumReader", "Error opening publication", t)
            isLoading = false
            errorMessage = "Failed to load book: ${t.localizedMessage}"
        }
    }

    // Build print page index once publication is opened
    LaunchedEffect(publication) {
        val pub = publication ?: return@LaunchedEffect
        val pageList = pub.pageList
        val hasPageList = pageList.isNotEmpty()
        onActiveEpubPageEquivalentChanged("", hasPageList)
        if (hasPageList) {
            val index = buildPrintPageIndex(pub)
            resourcePageLists = index
        } else {
            resourcePageLists = emptyList()
        }
    }

    // Settle Readium navigator currentSelection() after the user pauses or completes dragging selection handles.
    // Debouncing avoids executing JavaScript into the WebView during active drag gestures,
    // which prevents Android's selection handles from jittering or jumping erratically across paragraphs.
    LaunchedEffect(navigatorFragment, isSelectionModeActive, selectionPollTick, touchUpTick) {
        val frag = navigatorFragment ?: return@LaunchedEffect
        if (!isSelectionModeActive) {
            activeReadiumSelection = null
            return@LaunchedEffect
        }
        if (!frag.awaitReady()) return@LaunchedEffect
        // Never query the WebView while a finger is on the page (dragging a handle); lifting the
        // finger bumps touchUpTick and restarts this effect.
        if (isTouchDown) return@LaunchedEffect
        delay(180L)
        if (isTouchDown) return@LaunchedEffect
        if (!frag.isAdded || frag.view == null || !isSelectionModeActive) return@LaunchedEffect
        val sel = try {
            frag.currentSelection()
        } catch (_: Throwable) {
            null
        }
        val quote = sel?.locator?.text?.highlight?.trim().orEmpty()
        if (quote.isNotEmpty()) {
            if (activeReadiumSelection?.locator?.text?.highlight != quote) {
                activeReadiumSelection = sel
            }
        } else {
            activeReadiumSelection = null
        }
    }

    // Apply saved highlights as Readium Decorations and listen for taps on existing highlights
    LaunchedEffect(navigatorFragment, publication, publicationPositions, highlights, glossaeEnabled, glossFlipped, activeTheme.id) {
        val frag = navigatorFragment ?: return@LaunchedEffect
        val pub = publication ?: return@LaunchedEffect
        if (!frag.awaitReady()) return@LaunchedEffect
        try {
            val glossBg = (if (isFixedLayoutBook) Color.White else activeTheme.backgroundColor).toArgb()
            val glossFg = (if (isFixedLayoutBook) Color.Black else activeTheme.textColor).toArgb()
            val decorations = highlights.mapNotNull { hl ->
                val quote = hl.highlightedText.trim()
                if (quote.isEmpty()) return@mapNotNull null
                val cachedLoc = ReadiumHighlightLocatorCache.get(document.id, quote)
                val hrefLoc = highlightHref?.invoke(hl)?.let { name ->
                    pub.readingOrder.firstOrNull { l -> l.href.toString().substringBefore('#').let { it == name || it.endsWith("/$name") } }
                        ?.let { pub.locatorFromLink(it)?.copy(text = Locator.Text(highlight = quote)) }
                }
                val baseLocator = cachedLoc ?: hrefLoc ?: run {
                    val posLoc = publicationPositions.getOrNull((hl.pageNumber - 1).coerceAtLeast(0))
                    val linkMatch = pub.readingOrder.firstOrNull { link ->
                        hl.chapterTitle.isNotBlank() && link.title?.equals(hl.chapterTitle, ignoreCase = true) == true
                    } ?: posLoc?.let { pl ->
                        pub.readingOrder.firstOrNull { it.href.toString() == pl.href.toString() }
                    } ?: pub.readingOrder.firstOrNull()
                    if (linkMatch != null) {
                        pub.locatorFromLink(linkMatch)?.copy(
                            text = Locator.Text(highlight = quote)
                        )
                    } else {
                        posLoc?.copy(text = Locator.Text(highlight = quote))
                    }
                } ?: return@mapNotNull null

                val tintInt = parseHighlightColor(hl.colorHex).toInt()
                val style: Decoration.Style = if (glossaeEnabled && com.example.ui.glossae.Glossae.hasGloss(hl)) {
                    com.example.ui.glossae.GlossCardStyle(
                        title = hl.title,
                        note = hl.userNotes,
                        flipped = hl.id in glossFlipped,
                        animate = hl.id == glossLastFlipped,
                        background = glossBg,
                        foreground = glossFg
                    )
                } else {
                    Decoration.Style.Highlight(tint = tintInt, isActive = false)
                }
                Decoration(
                    id = "hl_${hl.id}",
                    locator = baseLocator,
                    style = style
                )
            }
            frag.applyDecorations(decorations, group = "highlights")
        } catch (t: Throwable) {
            Log.e("ReadiumReader", "Error applying highlight decorations", t)
        }
    }

    LaunchedEffect(navigatorFragment, highlights) {
        val frag = navigatorFragment ?: return@LaunchedEffect
        if (!frag.awaitReady()) return@LaunchedEffect
        val listener = object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                val hlId = event.decoration.id.removePrefix("hl_").toLongOrNull()
                val matched = highlights.firstOrNull { it.id == hlId }
                if (matched != null && currentGlossaeEnabled && com.example.ui.glossae.Glossae.hasGloss(matched)) {
                    // Glossae: "… more" corner opens the full note, any other tap flips the card.
                    val rect = event.rect
                    val point = event.point
                    val isFront = matched.id !in currentGlossFlipped
                    val wantsMore = isFront && rect != null && point != null &&
                        com.example.ui.glossae.Glossae.isInMoreZone(point.x, point.y, rect.left, rect.top, rect.right, rect.bottom, glossDensity) &&
                        com.example.ui.glossae.Glossae.noteLikelyOverflows(matched, rect.width(), rect.height(), currentFontSizeSp * glossDensity)
                    if (wantsMore) {
                        glossPreview = matched
                    } else {
                        glossLastFlipped = matched.id
                        glossFlipped = if (isFront) currentGlossFlipped + matched.id else currentGlossFlipped - matched.id
                    }
                    return true
                }
                return if (matched != null) {
                    onEditHighlight(matched)
                    true
                } else {
                    false
                }
            }
        }
        try {
            frag.addDecorationListener("highlights", listener)
            kotlinx.coroutines.awaitCancellation()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e("ReadiumReader", "Error registering decoration listener", t)
        } finally {
            try {
                if (frag.isAdded) {
                    frag.removeDecorationListener(listener)
                }
            } catch (_: Throwable) {}
        }
    }

    // React to reading preference changes (Single-Column vs Two-Column, Publisher Styling vs Custom Typography, Theme, Size, Margins)
    // Calling submitPreferences updates the navigator in-place immediately without disrupting the current reading position!
    LaunchedEffect(
        navigatorFragment,
        publisherStylesEnabled,
        columnCount,
        isVerticalScroll,
        activeTheme.id,
        fontSizeSp,
        marginDp,
        textAlignSetting,
        fontFamilySetting,
        lineSpacingMultiplier,
        paragraphStyleSetting,
        isFixedLayoutBook,
        forceSingleColumn
    ) {
        val frag = navigatorFragment ?: return@LaunchedEffect
        if (!frag.awaitReady()) return@LaunchedEffect
        try {
            val prefs = ReadiumPublicationManager.buildPreferences(
                publisherStyles = publisherStylesEnabled,
                columnCount = columnCount,
                isVerticalScroll = isVerticalScroll,
                readerThemeId = activeTheme.id,
                fontSizeSp = fontSizeSp,
                marginDp = marginDp,
                fontFamily = fontFamilySetting.takeIf { it.isNotBlank() && it != "DEFAULT" },
                textAlign = textAlignSetting,
                lineHeight = lineSpacingMultiplier.toDouble(),
                paragraphStyle = paragraphStyleSetting,
                isFixedLayout = isFixedLayoutBook,
                forceSingleColumn = forceSingleColumn
            )
            frag.submitPreferences(prefs)
        } catch (t: Throwable) {
            Log.e("ReadiumReader", "Error submitting preferences", t)
        }
    }

    // Handle external page turn triggers (volume keys / nav buttons)
    LaunchedEffect(navigatorFragment, pageTurnTrigger) {
        pageTurnTrigger.collect { trigger ->
            val frag = navigatorFragment ?: return@collect
            if (!frag.isAdded || frag.view == null) return@collect
            try {
                if (trigger > 0) {
                    frag.goForward(animated = true)
                } else if (trigger < 0) {
                    frag.goBackward(animated = true)
                }
            } catch (t: Throwable) {
                Log.e("ReadiumReader", "Error during page turn", t)
            }
        }
    }

    // Handle target page, print page, or highlight jump request (from TOC, Quick Scrubber, or Highlight jump)
    LaunchedEffect(targetPageRequest, targetHighlightRequest, targetPrintPageRequest, jumpRequestToken) {
        val frag = navigatorFragment ?: return@LaunchedEffect
        val pub = publication ?: return@LaunchedEffect
        if (!frag.awaitReady()) return@LaunchedEffect
        try {
            val printTarget = targetPrintPageRequest?.trim().orEmpty()
            if (printTarget.isNotBlank()) {
                val cleanTarget = cleanPrintPageLabel(printTarget.removePrefix("pp.").removePrefix("p.").trim())
                val allPageLinks = flattenPageListLinks(pub.pageList)
                val matchedLink = allPageLinks.firstOrNull { link ->
                    val fragId = link.url().fragment
                        ?: link.href.toString().substringAfter('#', "").takeIf { it.isNotBlank() }
                        ?: ""
                    val raw = link.title?.takeIf { it.isNotBlank() } ?: fragId
                    cleanPrintPageLabel(raw).equals(cleanTarget, ignoreCase = true)
                }
                if (matchedLink != null) {
                    frag.go(matchedLink, animated = true)
                    return@LaunchedEffect
                }
            }
            val hReq = targetHighlightRequest
            if (hReq != null) {
                val cachedLoc = ReadiumHighlightLocatorCache.get(document.id, hReq.highlightedText)
                if (cachedLoc != null) {
                    frag.go(cachedLoc, animated = true)
                    return@LaunchedEffect
                }
            }
            val target = targetPageRequest ?: return@LaunchedEffect
            val targetLocator = publicationPositions.getOrNull(target)
            if (targetLocator != null) {
                frag.go(targetLocator, animated = true)
            } else {
                val targetLink = pub.readingOrder.getOrNull(target)
                if (targetLink != null) {
                    frag.go(targetLink, animated = true)
                }
            }
        } catch (t: Throwable) {
            Log.e("ReadiumReader", "Error navigating to target page", t)
        }
    }

    // Observe reading progress from navigator and evaluate current print page
    LaunchedEffect(navigatorFragment, publication, publicationPositions, resourcePageLists, viewModeSetting) {
        val frag = navigatorFragment ?: return@LaunchedEffect
        val pub = publication ?: return@LaunchedEffect
        if (!frag.awaitReady()) return@LaunchedEffect
        val totalItems = if (publicationPositions.isNotEmpty()) {
            publicationPositions.size
        } else {
            pub.readingOrder.size.coerceAtLeast(1)
        }

        frag.currentLocator.collectLatest { locator ->
            try {
                val previousLocator = latestLocator
                latestLocator = locator
                ReadiumLocatorHandoff.remember(document.id, locator)
                if (glossFlipped.isNotEmpty() && previousLocator != null) {
                    val sameResource = previousLocator.href.toString() == locator.href.toString()
                    val samePage = isVerticalScroll ||
                        (previousLocator.locations.progression ?: -1.0) == (locator.locations.progression ?: -1.0)
                    if (!sameResource || !samePage) {
                        glossFlipped = emptySet()
                        glossLastFlipped = null
                    }
                }
                val posFromLocations = locator.locations.position
                val totalProg = locator.locations.totalProgression ?: 0.0
                val position = when {
                    posFromLocations != null && posFromLocations in 1..totalItems -> posFromLocations
                    totalProg > 0.0 -> (totalProg * totalItems).toInt().coerceIn(1, totalItems)
                    else -> {
                        val spineIdx = pub.readingOrder.indexOfFirst { it.href.toString() == locator.href.toString() }
                        if (spineIdx >= 0) (spineIdx + 1).coerceIn(1, totalItems) else 1
                    }
                }
                currentTrackedPosition = position
                val chapterTitle = locator.title ?: ""
                onPageChanged(position, totalItems, chapterTitle)
                onProgressUpdate(position, totalItems)

                val cfi = locator.locations.fragments.firstOrNull() ?: ""
                if (cfi.isNotBlank()) {
                    onActiveEpubCfiChanged(cfi)
                }

                // Resolve current print page from page-list
                if (resourcePageLists.isNotEmpty()) {
                    try {
                        val currentResourceIdx = resourcePageLists.indexOfFirst {
                            areResourceUrlsMatching(it.resourceUrl, locator.href)
                        }
                        if (currentResourceIdx >= 0) {
                            val currentResource = resourcePageLists[currentResourceIdx]
                            val entries = currentResource.entries
                            if (entries.isNotEmpty()) {
                                delay(150)
                                val isScroll = viewModeSetting.equals("SCROLL", ignoreCase = true) ||
                                    viewModeSetting.equals("VERTICAL_SCROLL", ignoreCase = true)
                                val idsJson = org.json.JSONArray(entries.map { it.id }).toString()
                                val script = """
                                    (function(ids, scroll){
                                      var size = scroll ? window.innerHeight : window.innerWidth;
                                      var start = -1, end = -1;
                                      for (var i = 0; i < ids.length; i++) {
                                        var el = document.getElementById(ids[i]);
                                        if (!el || el.getClientRects().length === 0) continue;
                                        var r = el.getClientRects()[0];
                                        var pos = scroll ? r.top : r.left;
                                        if (pos <= 1) start = i;
                                        if (pos < size - 1) end = i;
                                      }
                                      return JSON.stringify({start: start, end: end});
                                    })($idsJson, $isScroll);
                                """.trimIndent()

                                var computedLabel: String? = null
                                if (printLayoutMode == PrintPageLayoutMode.PAGINATED_STRICT) {
                                    // In strict 1:1 mode each screen is one <section data-page>; read it directly.
                                    try {
                                        val raw = frag.evaluateJavascript(
                                            "(function(){var w=window.innerWidth,s=document.querySelectorAll('section.ml-printpage[data-page]');" +
                                                "for(var i=0;i<s.length;i++){var r=s[i].getClientRects()[0];if(r&&r.left>=-1&&r.left<w-1)return s[i].getAttribute('data-page');}return '';})();"
                                        )
                                        val label = raw?.trim()?.removeSurrounding("\"")?.trim().orEmpty()
                                        if (label.isNotEmpty() && label != "null") computedLabel = label
                                    } catch (_: Throwable) {}
                                }
                                if (computedLabel == null) try {
                                    val jsResult = frag.evaluateJavascript(script)
                                    val startEnd = parseJsStartEndResult(jsResult)
                                    if (startEnd != null) {
                                        val (start, end) = startEnd
                                        val startLabel = if (start >= 0 && start < entries.size) {
                                            entries[start].label
                                        } else {
                                            var prevLabel: String? = null
                                            for (pIdx in (currentResourceIdx - 1) downTo 0) {
                                                val pEntries = resourcePageLists.getOrNull(pIdx)?.entries
                                                if (!pEntries.isNullOrEmpty()) {
                                                    prevLabel = pEntries.last().label
                                                    break
                                                }
                                            }
                                            prevLabel ?: entries.first().label
                                        }
                                        val endLabel = if (end >= 0 && end < entries.size) {
                                            entries[end].label
                                        } else {
                                            startLabel
                                        }
                                        computedLabel = if (startLabel == endLabel || endLabel.isBlank()) {
                                            startLabel
                                        } else {
                                            "$startLabel–$endLabel"
                                        }
                                    }
                                } catch (t: Throwable) {
                                    Log.w("ReadiumReader", "Failed evaluating page marker JS", t)
                                }

                                // Fallback if the JS returns null or throws: estimate with locator.locations.progression
                                if (computedLabel == null) {
                                    val progression = locator.locations.progression ?: 0.0
                                    val estIndex = (progression * entries.size).toInt().coerceIn(0, entries.lastIndex)
                                    computedLabel = entries[estIndex].label
                                }

                                if (!computedLabel.isNullOrBlank()) {
                                    val first = computedLabel.substringBefore('–')
                                    val id = entries.firstOrNull { it.label == first }?.id
                                        ?: resourcePageLists.flatMap { it.entries }.firstOrNull { it.label == first }?.id
                                    if (id != null) onPrintPageAnchorChanged(id)
                                }
                                if (!computedLabel.isNullOrBlank() && computedLabel != currentPrintPageLabel) {
                                    currentPrintPageLabel = computedLabel
                                    onActiveEpubPageEquivalentChanged(computedLabel, true)
                                }
                            }
                        } else {
                            // Nearest previous resource fallback when spine item has no page markers of its own
                            val spineIdx = pub.readingOrder.indexOfFirst { areResourceUrlsMatching(it.url(), locator.href) }
                            var prevLabel: String? = null
                            if (spineIdx > 0) {
                                for (i in (spineIdx - 1) downTo 0) {
                                    val candidateLink = pub.readingOrder.getOrNull(i) ?: continue
                                    val matchedRes = resourcePageLists.firstOrNull { areResourceUrlsMatching(it.resourceUrl, candidateLink.url()) }
                                    if (matchedRes != null && matchedRes.entries.isNotEmpty()) {
                                        prevLabel = matchedRes.entries.last().label
                                        break
                                    }
                                }
                            }
                            val fallback = prevLabel ?: currentPrintPageLabel.takeIf { it.isNotBlank() } ?: resourcePageLists.first().entries.first().label
                            resourcePageLists.flatMap { it.entries }.lastOrNull { it.label == fallback }?.id?.let { onPrintPageAnchorChanged(it) }
                            if (fallback.isNotBlank() && fallback != currentPrintPageLabel) {
                                currentPrintPageLabel = fallback
                                onActiveEpubPageEquivalentChanged(fallback, true)
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w("ReadiumReader", "Error finding current print page", t)
                    }
                }
            } catch (t: Throwable) {
                Log.e("ReadiumReader", "Error updating progress", t)
            }
        }
    }

    // Clean up fragment when removed from composition
    DisposableEffect(containerId) {
        onDispose {
            ReadiumLocatorHandoff.markHandoff(document.id)
            try {
                activity?.supportFragmentManager?.let { fm ->
                    val frag = fm.findFragmentByTag(fragmentTag)
                    if (frag != null && !fm.isStateSaved) {
                        fm.beginTransaction().remove(frag).commitAllowingStateLoss()
                    }
                }
            } catch (t: Throwable) {
                Log.e("ReadiumReader", "Error disposing fragment", t)
            }
        }
    }

    glossPreview?.let { previewed ->
        com.example.ui.glossae.GlossNotePreviewDialog(
            highlight = previewed,
            onDismiss = { glossPreview = null },
            onEdit = { onEditHighlight(it) }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (isFixedLayoutBook) Color.White else activeTheme.backgroundColor)
            .testTag("readium_epub_reader_container")
    ) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = activeTheme.textColor)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Loading book with Readium Engine…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = activeTheme.textColor
                        )
                    }
                }
            }
            errorMessage != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFFEE2E2),
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Readium Engine Notice",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFDC2626)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = errorMessage.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Black,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onFallbackToClassic,
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Open in Classic Reader", color = Color.White)
                            }
                        }
                    }
                }
            }
            publication != null && activity != null -> {
                val effectiveTopMargin = if (!isVerticalScroll && !isFixedLayoutBook) topMarginDp.coerceIn(0, 160).dp else 0.dp
                val effectiveBottomMargin = if (!isVerticalScroll && !isFixedLayoutBook) bottomMarginDp.coerceIn(0, 160).dp else 0.dp
                AndroidView(
                    factory = { ctx ->
                        TouchObservingFrameLayout(ctx) { down ->
                            isTouchDown = down
                            if (!down) touchUpTick++
                        }.apply {
                            id = containerId
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                            // Post to ensure view is attached to Activity hierarchy before fragment transaction
                            post {
                                try {
                                    val fm = activity.supportFragmentManager
                                    val pub = publication ?: return@post
                                    val prefs = ReadiumPublicationManager.buildPreferences(
                                        publisherStyles = publisherStylesEnabled,
                                        columnCount = columnCount,
                                        isVerticalScroll = isVerticalScroll,
                                        readerThemeId = activeTheme.id,
                                        fontSizeSp = fontSizeSp,
                                        marginDp = marginDp,
                                        fontFamily = fontFamilySetting.takeIf { it.isNotBlank() && it != "DEFAULT" },
                                        textAlign = textAlignSetting,
                                        lineHeight = lineSpacingMultiplier.toDouble(),
                                        paragraphStyle = paragraphStyleSetting,
                                        isFixedLayout = isFixedLayoutBook,
                                        forceSingleColumn = forceSingleColumn
                                    )

                                    val existingFrag = fm.findFragmentByTag(fragmentTag) as? EpubNavigatorFragment
                                    if (existingFrag != null) {
                                        navigatorFragment = existingFrag
                                        if (existingFrag.isAdded && existingFrag.view != null) {
                                            existingFrag.submitPreferences(prefs)
                                        }
                                    } else if (!fm.isStateSaved) {
                                        val hrefInitial: Locator? = initialHref?.let { h ->
                                            val name = h.substringBefore('#'); val anchor = h.substringAfter('#', "")
                                            pub.readingOrder.firstOrNull { l -> l.href.toString().substringBefore('#').let { it == name || it.endsWith("/$name") } }
                                                ?.let { pub.locatorFromLink(it) }
                                                ?.let { if (anchor.isNotBlank()) it.copy(locations = it.locations.copy(fragments = listOf(anchor))) else it }
                                        }
                                        val resolvedInitialLocator: Locator? = hrefInitial
                                            ?: latestLocator
                                            ?: ReadiumLocatorHandoff.take(document.id)
                                            ?: run {
                                            val targetIdx = (initialPage - 1).coerceAtLeast(0)
                                            if (targetIdx > 0) {
                                                publicationPositions.getOrNull(targetIdx)
                                                    ?: pub.readingOrder.getOrNull(targetIdx)?.let { pub.locatorFromLink(it) }
                                            } else {
                                                null
                                            }
                                        }

                                        val factory = ReadiumPublicationManager.createNavigatorFactory(pub)
                                        val fragmentFactory = factory.createFragmentFactory(
                                            initialLocator = resolvedInitialLocator,
                                            initialPreferences = prefs,
                                            listener = object : EpubNavigatorFragment.Listener {
                                                override fun onTap(point: PointF): Boolean {
                                                    if (activeReadiumSelection != null || isSelectionModeActive) {
                                                        coroutineScope.launch {
                                                            try {
                                                                navigatorFragment?.clearSelection()
                                                            } catch (_: Throwable) {}
                                                            activeReadiumSelection = null
                                                            isSelectionModeActive = false
                                                        }
                                                        return true
                                                    }
                                                    onToggleUi()
                                                    return true
                                                }

                                                override fun onExternalLinkActivated(url: AbsoluteUrl) {
                                                    // External link activated
                                                }
                                            },
                                            configuration = ReadiumPublicationManager.createNavigatorConfiguration(
                                                selectionActionModeCallback = selectionActionModeCallback
                                            )
                                        )

                                        fm.fragmentFactory = fragmentFactory
                                        val fragment = fragmentFactory.instantiate(
                                            activity.classLoader,
                                            EpubNavigatorFragment::class.java.name
                                        ) as EpubNavigatorFragment

                                        try {
                                            fm.beginTransaction()
                                                .replace(containerId, fragment, fragmentTag)
                                                .commitNowAllowingStateLoss()
                                            navigatorFragment = fragment
                                        } catch (_: Throwable) {
                                            fm.beginTransaction()
                                                .replace(containerId, fragment, fragmentTag)
                                                .commitAllowingStateLoss()
                                            post {
                                                navigatorFragment = fragment
                                            }
                                        }
                                    }
                                } catch (t: Throwable) {
                                    Log.e("ReadiumReader", "Error instantiating or attaching fragment", t)
                                    errorMessage = "Could not initialize reader layout: ${t.localizedMessage}"
                                }
                            }
                        }
                    },
                    update = {
                        // View container maintained; preferences handled via LaunchedEffect without recreating fragment
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = effectiveTopMargin, bottom = effectiveBottomMargin)
                        .onSizeChanged { readerHeightPx = it.height }
                )

                // Waiting for the rest of a selection begun on the previous page.
                val waitingEpub = carriedEpub
                if (waitingEpub != null && activeReadiumSelection == null) {
                    androidx.compose.material3.Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
                        color = Color.White,
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.Black)
                    ) {
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 14.dp, end = 4.dp)
                        ) {
                            androidx.compose.material3.Text(
                                "Select the rest of the passage — it joins the part you began on the previous page",
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                color = Color.Black,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                            androidx.compose.material3.TextButton(onClick = { carriedEpub = null }) {
                                androidx.compose.material3.Text("Cancel", color = Color.Black, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            }
                        }
                    }
                }

                // Custom Paper & Ink Highlighting Menu when text is selected in Readium WebView
                val currentSel = activeReadiumSelection
                val pageSelectedQuote = currentSel?.locator?.text?.highlight?.trim().orEmpty()
                val carriedPart = carriedEpub
                val selectedQuote = if (carriedPart != null && pageSelectedQuote.isNotEmpty()) {
                    val a = carriedPart.quote.trimEnd()
                    if (a.endsWith("-") && a.length > 1 && a[a.length - 2].isLetter() && pageSelectedQuote.first().isLowerCase()) a.dropLast(1) + pageSelectedQuote
                    else "$a $pageSelectedQuote"
                } else pageSelectedQuote
                if (currentSel != null && selectedQuote.isNotEmpty() && !isTouchDown) {
                    // Keep the menu away from the selection (and its handles).
                    val selRect = currentSel.rect
                    val menuAtTop = selRect != null && readerHeightPx > 0 && selRect.centerY() > readerHeightPx * 0.5f
                    val totalItems = if (publicationPositions.isNotEmpty()) {
                        publicationPositions.size
                    } else {
                        publication?.readingOrder?.size?.coerceAtLeast(1) ?: 1
                    }
                    val posFromSel = currentSel.locator.locations.position ?: latestLocator?.locations?.position
                    val totalProg = currentSel.locator.locations.totalProgression ?: latestLocator?.locations?.totalProgression ?: 0.0
                    val resolvedPage = when {
                        posFromSel != null && posFromSel in 1..totalItems -> posFromSel
                        totalProg > 0.0 -> (totalProg * totalItems).toInt().coerceIn(1, totalItems)
                        else -> currentTrackedPosition
                    }
                    val resolvedChapter = currentSel.locator.title?.takeIf { it.isNotBlank() }
                        ?: latestLocator?.title?.takeIf { it.isNotBlank() }
                        ?: document.title
                    val rawFragment = currentSel.locator.locations.fragments.firstOrNull()
                        ?: (currentSel.locator.locations.otherLocations["partialCfi"] as? String)
                        ?: (currentSel.locator.locations.otherLocations["cssSelector"] as? String)
                        ?: ""
                    val resolvedCfi = if (rawFragment.isNotBlank()) {
                        com.example.reader.EpubCfiEngine.normalizeCfi(rawFragment, includeHashPrefix = false)
                    } else {
                        com.example.reader.EpubCfiEngine.generateCfiForBookSelection(
                            epubBook = null,
                            quote = selectedQuote,
                            fallbackChapterTitle = resolvedChapter,
                            fallbackParagraphCitation = "$resolvedChapter, ¶ 1"
                        )
                    }
                    val printLabel = currentPrintPageLabel.trim()
                    val hasPrintPage = printLabel.isNotEmpty()
                    val pPrefix = if (printLabel.contains("–") || printLabel.contains("-")) "pp." else "p."
                    val resolvedCitation = if (hasPrintPage) "$resolvedChapter, $pPrefix $printLabel" else "$resolvedChapter, pos. $resolvedPage"
                    val resolvedPlainLocus = if (hasPrintPage) "$resolvedChapter • $pPrefix $printLabel" else "$resolvedChapter • pos. $resolvedPage"

                    // Across a page break: the note keeps the first part's place, and spans both printed pages.
                    val savePage = carriedPart?.page ?: resolvedPage
                    val saveChapter = carriedPart?.chapterTitle ?: resolvedChapter
                    val saveCfi = carriedPart?.cfi ?: resolvedCfi
                    val savePrintLabel = run {
                        val here = if (hasPrintPage) printLabel else ""
                        val first = carriedPart?.printLabel.orEmpty()
                        when {
                            carriedPart == null -> here
                            first.isNotBlank() && here.isNotBlank() && !first.equals(here, true) -> "${first.substringBefore('–')}–${here.substringAfterLast('–')}"
                            else -> first.ifBlank { here }
                        }
                    }
                    val saveCitation = if (carriedPart != null && savePrintLabel.isNotBlank()) {
                        "$saveChapter, ${if (savePrintLabel.contains('–')) "pp." else "p."} $savePrintLabel"
                    } else carriedPart?.citation ?: resolvedCitation
                    val saveLocator = if (carriedPart != null) {
                        carriedPart.locator.copy(
                            text = Locator.Text(before = carriedPart.locator.text.before, highlight = selectedQuote, after = currentSel.locator.text.after)
                        )
                    } else currentSel.locator
                    val clearReadiumSel: () -> Unit = {
                        coroutineScope.launch {
                            try {
                                navigatorFragment?.clearSelection()
                            } catch (_: Throwable) {}
                            activeReadiumSelection = null
                            isSelectionModeActive = false
                        }
                    }

                    SelectionActionToolbarCard(
                        state = ActiveSelectionToolbarState(
                            dictionaryContext = listOfNotNull(currentSel.locator.text.before, selectedQuote, currentSel.locator.text.after).joinToString(" "),
                            quote = selectedQuote,
                            pageNumber = savePage,
                            paragraphCitation = saveCitation,
                            epubCfi = saveCfi,
                            plainLocus = resolvedPlainLocus,
                            isPdfDocument = false,
                            chapterTitle = saveChapter,
                            isCurrentlyDragging = false,
                            canAddNextParagraph = false,
                            showWordAdjustmentControls = false,
                            continuedFrom = if (carriedPart != null) (if (carriedPart.printLabel.isNotBlank()) "p. ${carriedPart.printLabel}" else "the previous page") else "",
                            onCancelContinuation = if (carriedPart != null) ({ carriedEpub = null }) else null,
                            onContinueOnNextPage = if (carriedPart == null && (selRect == null || readerHeightPx <= 0 || selRect.bottom > readerHeightPx * 0.55f)) ({
                                carriedEpub = CarriedEpubSelection(
                                    quote = pageSelectedQuote, locator = currentSel.locator, page = resolvedPage, chapterTitle = resolvedChapter,
                                    citation = resolvedCitation, printLabel = if (hasPrintPage) printLabel else "", cfi = resolvedCfi
                                )
                                clearReadiumSel()
                                coroutineScope.launch {
                                    try { navigatorFragment?.goForward(animated = true) } catch (_: Throwable) {}
                                }
                            }) else null,
                            onClearSelection = clearReadiumSel,
                            onSaveNoteWithDialog = { colorHex ->
                                ReadiumHighlightLocatorCache.put(document.id, selectedQuote, saveLocator)
                                onRequestHighlight(selectedQuote, savePage, saveChapter, colorHex, saveCitation, savePrintLabel, saveCfi)
                                carriedEpub = null
                                clearReadiumSel()
                            },
                            onQuickSaveHighlight = { colorHex ->
                                ReadiumHighlightLocatorCache.put(document.id, selectedQuote, saveLocator)
                                onQuickSaveHighlight(selectedQuote, savePage, saveChapter, colorHex, saveCitation, savePrintLabel, saveCfi)
                                carriedEpub = null
                                clearReadiumSel()
                            },
                            onSpeakSelection = {
                                onSpeakSelection(selectedQuote, resolvedPage)
                                clearReadiumSel()
                            },
                            onAddAsAuthor = onAddAsAuthor,
                            onSetAsAbstract = onSetAsAbstract
                        ),
                        onAddAsAuthor = onAddAsAuthor,
                        onSetAsAbstract = onSetAsAbstract,
                        modifier = Modifier
                            .align(if (menuAtTop) Alignment.TopCenter else Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

/**
 * Recursively flattens Readium Table of Contents links into [BookChapter] objects.
 */
private fun flattenToc(
    links: List<Link>,
    readingOrder: List<Link>,
    positions: List<Locator>,
    level: Int = 0
): List<BookChapter> {
    val result = mutableListOf<BookChapter>()
    for (link in links) {
        val title = link.title ?: "Chapter"
        val hrefStr = link.href.toString()
        val cleanHref = hrefStr.substringBefore("#")

        val posMatch = positions.firstOrNull {
            it.href.toString() == hrefStr || it.href.toString() == cleanHref
        }?.locations?.position

        val pageNum = if (posMatch != null && posMatch > 0) {
            posMatch
        } else {
            val indexInSpine = readingOrder.indexOfFirst {
                it.href.toString() == hrefStr || it.href.toString() == cleanHref
            }
            if (indexInSpine >= 0) indexInSpine + 1 else 1
        }

        result.add(
            BookChapter(
                title = title,
                pageNumber = pageNum,
                level = level,
                href = hrefStr,
                fileKey = cleanHref,
                anchorId = hrefStr.substringAfter("#", "")
            )
        )

        if (link.children.isNotEmpty()) {
            result.addAll(flattenToc(link.children, readingOrder, positions, level + 1))
        }
    }
    return result
}

/**
 * Suspends until the [EpubNavigatorFragment] is attached to its host Activity and its View hierarchy
 * (including `resourcePager` and Fragment `ViewModel`) is initialized.
 */
private suspend fun EpubNavigatorFragment.awaitReady(): Boolean {
    repeat(30) {
        if (isAdded && view != null) return true
        delay(40L)
    }
    return isAdded && view != null
}

/**
 * Finds the hosting [FragmentActivity] from a given [Context].
 */
private fun Context.findFragmentActivity(): FragmentActivity? {
    var cur: Context? = this
    while (cur is ContextWrapper) {
        if (cur is FragmentActivity) return cur
        cur = cur.baseContext
    }
    return null
}

data class ReadiumPrintPageEntry(
    val id: String,
    val label: String
)

data class ReadiumResourcePageList(
    val resourceUrl: org.readium.r2.shared.util.Url,
    val normalizedKey: String,
    val entries: List<ReadiumPrintPageEntry>
)

internal fun cleanPrintPageLabel(raw: String): String {
    var cleaned = raw.trim()
    val prefixes = listOf("Side ", "side ", "Page ", "page ", "p. ", "P. ", "p ", "P ")
    for (prefix in prefixes) {
        if (cleaned.startsWith(prefix, ignoreCase = true)) {
            cleaned = cleaned.substring(prefix.length).trim()
            break
        }
    }
    return cleaned
}

internal fun flattenPageListLinks(links: List<Link>): List<Link> {
    val result = mutableListOf<Link>()
    for (link in links) {
        result.add(link)
        if (link.children.isNotEmpty()) {
            result.addAll(flattenPageListLinks(link.children))
        }
    }
    return result
}

internal fun normalizeResourceKey(url: org.readium.r2.shared.util.Url): String {
    val clean = url.removeFragment().toString().trim()
    val decoded = runCatching { java.net.URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
    return decoded.trimStart('/')
}

internal fun areResourceUrlsMatching(url1: org.readium.r2.shared.util.Url, url2: org.readium.r2.shared.util.Url): Boolean {
    val u1 = url1.removeFragment()
    val u2 = url2.removeFragment()
    if (u1.isEquivalent(u2)) return true
    if (u1 == u2) return true
    val k1 = normalizeResourceKey(u1)
    val k2 = normalizeResourceKey(u2)
    if (k1 == k2) return true
    val p1 = u1.path?.trimStart('/') ?: k1
    val p2 = u2.path?.trimStart('/') ?: k2
    if (p1 == p2) return true
    if (p1.endsWith("/$p2") || p2.endsWith("/$p1")) return true
    return false
}

internal fun buildPrintPageIndex(pub: Publication): List<ReadiumResourcePageList> {
    val allPageLinks = flattenPageListLinks(pub.pageList)
    if (allPageLinks.isEmpty()) return emptyList()

    val grouped = mutableListOf<ReadiumResourcePageList>()
    val matchedLinkIndices = mutableSetOf<Int>()

    // First pass: align strictly by publication.readingOrder (spine)
    for (spineLink in pub.readingOrder) {
        val spineUrl = spineLink.url().removeFragment()
        val spineEntries = mutableListOf<ReadiumPrintPageEntry>()
        allPageLinks.forEachIndexed { idx, pageLink ->
            val pageUrl = pageLink.url().removeFragment()
            if (areResourceUrlsMatching(pageUrl, spineUrl)) {
                matchedLinkIndices.add(idx)
                val fragId = pageLink.url().fragment
                    ?: pageLink.href.toString().substringAfter('#', "").takeIf { it.isNotBlank() }
                    ?: ""
                val rawLabel = pageLink.title?.takeIf { it.isNotBlank() } ?: fragId
                val cleaned = cleanPrintPageLabel(rawLabel)
                if (fragId.isNotBlank() && cleaned.isNotBlank()) {
                    spineEntries.add(ReadiumPrintPageEntry(id = fragId, label = cleaned))
                }
            }
        }
        if (spineEntries.isNotEmpty()) {
            grouped.add(
                ReadiumResourcePageList(
                    resourceUrl = spineUrl,
                    normalizedKey = normalizeResourceKey(spineUrl),
                    entries = spineEntries
                )
            )
        }
    }

    // Second pass: any page links whose resource was not in readingOrder
    allPageLinks.forEachIndexed { idx, pageLink ->
        if (idx !in matchedLinkIndices) {
            val pageUrl = pageLink.url().removeFragment()
            val existing = grouped.firstOrNull { areResourceUrlsMatching(it.resourceUrl, pageUrl) }
            val fragId = pageLink.url().fragment
                ?: pageLink.href.toString().substringAfter('#', "").takeIf { it.isNotBlank() }
                ?: ""
            val rawLabel = pageLink.title?.takeIf { it.isNotBlank() } ?: fragId
            val cleaned = cleanPrintPageLabel(rawLabel)
            if (fragId.isNotBlank() && cleaned.isNotBlank()) {
                if (existing != null) {
                    (existing.entries as? MutableList)?.add(ReadiumPrintPageEntry(id = fragId, label = cleaned))
                } else {
                    grouped.add(
                        ReadiumResourcePageList(
                            resourceUrl = pageUrl,
                            normalizedKey = normalizeResourceKey(pageUrl),
                            entries = mutableListOf(ReadiumPrintPageEntry(id = fragId, label = cleaned))
                        )
                    )
                }
            }
        }
    }

    return grouped
}

internal fun parseJsStartEndResult(raw: String?): Pair<Int, Int>? {
    if (raw == null || raw == "null") return null
    var text = raw.trim()
    if (text.startsWith("\"") && text.endsWith("\"") && text.length >= 2) {
        val unquoted = text.substring(1, text.length - 1)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
        text = unquoted.trim()
    }
    return try {
        val json = org.json.JSONObject(text)
        val start = json.optInt("start", -1)
        val end = json.optInt("end", -1)
        Pair(start, end)
    } catch (_: Exception) {
        null
    }
}

/**
 * Container for the Readium navigator that reports whether a finger is on the page, without
 * intercepting or altering any touch events.
 */
private class TouchObservingFrameLayout(
    context: android.content.Context,
    private val onTouchState: (down: Boolean) -> Unit
) : FrameLayout(context) {
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> onTouchState(true)
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> onTouchState(false)
        }
        return super.dispatchTouchEvent(ev)
    }
}
