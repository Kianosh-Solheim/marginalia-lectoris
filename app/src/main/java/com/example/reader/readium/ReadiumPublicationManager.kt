package com.example.reader.readium

import android.content.Context
import android.util.Log
import android.view.ActionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Spread
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.epub.layout
import org.readium.r2.shared.publication.presentation.presentation
import org.readium.r2.shared.publication.services.positionsServiceFactory
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.resource.TransformingContainer
import org.readium.r2.shared.util.resource.map
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

/**
 * Manages loading and caching of EPUB publications using the Readium Mobile Kotlin Toolkit.
 * Supports EPUB 3 fixed-layout (FXL) and reflowable publications with full publisher fidelity,
 * explicit single-column vs two-column reading modes, and logical interaction between publisher
 * styling and custom reader typography.
 */
@OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)
object ReadiumPublicationManager {

    private const val TAG = "ReadiumPubManager"

    @Volatile
    private var cachedPublication: Publication? = null
    @Volatile
    private var cachedFilePath: String? = null
    @Volatile
    private var cachedLayoutMode: PrintPageLayoutMode = PrintPageLayoutMode.OFF

    suspend fun openPublication(
        context: Context,
        file: File,
        printLayoutMode: PrintPageLayoutMode = PrintPageLayoutMode.OFF
    ): Publication? = withContext(Dispatchers.IO) {
        if (!file.exists()) {
            Log.e(TAG, "File does not exist: ${file.absolutePath}")
            return@withContext null
        }
        val currentPub = cachedPublication
        if (currentPub != null && cachedFilePath == file.absolutePath && cachedLayoutMode == printLayoutMode) {
            return@withContext currentPub
        }

        try {
            val httpClient = DefaultHttpClient()
            val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
            val publicationParser = DefaultPublicationParser(
                context = context,
                httpClient = httpClient,
                assetRetriever = assetRetriever,
                pdfFactory = null
            )
            val opener = PublicationOpener(publicationParser = publicationParser)
            val assetTry = assetRetriever.retrieve(file)
            val asset = assetTry.getOrNull()
            if (asset == null) {
                Log.e(TAG, "Failed to retrieve asset: ${assetTry.failureOrNull()}")
                return@withContext null
            }
            val pubTry = opener.open(
                asset = asset,
                allowUserInteraction = false,
                onCreatePublication = { installPrintPageRewriter(this, printLayoutMode) }
            )
            val pub = pubTry.getOrNull()
            if (pub == null) {
                Log.e(TAG, "Failed to open publication: ${pubTry.failureOrNull()}")
                return@withContext null
            }

            synchronized(this@ReadiumPublicationManager) {
                cachedPublication?.close()
                cachedPublication = pub
                cachedFilePath = file.absolutePath
                cachedLayoutMode = printLayoutMode
            }
            pub
        } catch (e: Exception) {
            Log.e(TAG, "Error opening publication: ${file.absolutePath}", e)
            null
        }
    }

    /**
     * Determines whether the publication has a fixed layout (pre-paginated)
     * either globally or on spine items.
     */
    fun isFixedLayout(publication: Publication): Boolean {
        if (publication.metadata.presentation.layout == EpubLayout.FIXED) {
            return true
        }
        return publication.readingOrder.any { link ->
            link.properties.layout == EpubLayout.FIXED ||
            link.properties["rendition:layout"] == "pre-paginated"
        }
    }

    /**
     * Creates an [EpubNavigatorFactory] for configuring and creating [org.readium.r2.navigator.epub.EpubNavigatorFragment].
     */
    fun createNavigatorFactory(publication: Publication): EpubNavigatorFactory {
        return EpubNavigatorFactory(publication)
    }

    /**
     * Creates the [EpubNavigatorFragment.Configuration]. Printed-page layout is no longer done with
     * global CSS here (that approach could not break inside paragraphs and broke unrelated elements);
     * it is done at serve time by [installPrintPageRewriter].
     */
    fun createNavigatorConfiguration(
        selectionActionModeCallback: ActionMode.Callback? = null
    ): EpubNavigatorFragment.Configuration {
        val templates = HtmlDecorationTemplates.defaultTemplates().copy().apply {
            // Glossae: note cards laid over annotated passages (display only).
            set(com.example.ui.glossae.GlossCardStyle::class, com.example.ui.glossae.Glossae.readiumTemplate())
        }
        return EpubNavigatorFragment.Configuration(
            decorationTemplates = templates,
            selectionActionModeCallback = selectionActionModeCallback
        )
    }

    /**
     * Rewrites every reflowable HTML resource of the reading order before the WebView loads it, so
     * printed page breaks / dividers are present from the first frame, also in preloaded chapters.
     * Positions are still computed from the ORIGINAL resources so page numbers, progress and saved
     * highlight positions are identical whether or not printed-page layout is on.
     */
    private fun installPrintPageRewriter(builder: Publication.Builder, mode: PrintPageLayoutMode) {
        if (mode == PrintPageLayoutMode.OFF) return
        val manifest = builder.manifest
        if (manifest.metadata.presentation.layout == EpubLayout.FIXED) return

        val pageLinks = flattenLinks(manifest.subcollections["pageList"]?.firstOrNull()?.links.orEmpty())
        val targetsByResource = HashMap<String, MutableList<PrintPageTarget>>()
        for (link in pageLinks) {
            val url = link.url()
            val fragment = url.fragment ?: link.href.toString().substringAfter('#', "")
            if (fragment.isBlank()) continue
            val label = link.title?.takeIf { it.isNotBlank() } ?: fragment
            targetsByResource.getOrPut(resourceKey(url)) { mutableListOf() }.add(PrintPageTarget(fragment, label))
        }

        val htmlResources = HashMap<String, Boolean>() // key -> isXhtml
        for (link in manifest.readingOrder) {
            if (link.properties.layout == EpubLayout.FIXED) continue
            val mt = link.mediaType
            val href = link.url().toString().lowercase()
            val isHtml = mt?.isHtml == true || href.endsWith(".xhtml") || href.endsWith(".html") || href.endsWith(".htm")
            if (!isHtml) continue
            val isXhtml = mt?.toString()?.contains("xhtml") == true || href.endsWith(".xhtml")
            htmlResources[resourceKey(link.url())] = isXhtml
        }
        if (htmlResources.isEmpty()) return

        val originalContainer = builder.container
        val positionsFactory = builder.servicesBuilder.positionsServiceFactory
        if (positionsFactory != null) {
            builder.servicesBuilder.positionsServiceFactory = { ctx ->
                positionsFactory(Publication.Service.Context(ctx.manifest, originalContainer, ctx.services))
            }
        }

        builder.container = TransformingContainer(originalContainer) { url, resource ->
            val key = resourceKey(url)
            val isXhtml = htmlResources[key] ?: return@TransformingContainer resource
            val targets = targetsByResource[key].orEmpty()
            resource.map { bytes ->
                val rewritten = try {
                    val text = bytes.toString(Charsets.UTF_8)
                    val out = PrintPageHtmlRewriter.rewrite(text, isXhtml, targets, mode)
                    if (out === text) bytes else out.toByteArray(Charsets.UTF_8)
                } catch (t: Throwable) {
                    Log.w(TAG, "Print page rewrite failed for $url", t)
                    bytes
                }
                Try.success(rewritten)
            }
        }
    }

    private fun flattenLinks(links: List<org.readium.r2.shared.publication.Link>): List<org.readium.r2.shared.publication.Link> =
        links.flatMap { listOf(it) + flattenLinks(it.children) }

    private fun resourceKey(url: Url): String {
        val clean = url.removeFragment().removeQuery().toString().trim()
        val decoded = runCatching { java.net.URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        return decoded.trimStart('/')
    }

    /**
     * Builds [EpubPreferences] that strictly respect:
     * 1. Explicit Single-Column (`ColumnCount.ONE`, `Spread.NEVER`) vs Two-Column (`ColumnCount.TWO`, `Spread.ALWAYS`)
     *    mode so opening a book never unexpectedly defaults to two columns.
     * 2. Logical interaction between Publisher Styling (`publisherStyles = true`) and reader customizations:
     *    - Page layout (columns, scroll/paginated), theme, font size scaling, and page margins work alongside
     *      publisher styling on reflowable books.
     *    - Typography overrides (font family, text alignment, line spacing) are preserved from publisher CSS
     *      when `publisherStyles = true`, and customized when `publisherStyles = false`.
     */
    fun buildPreferences(
        publisherStyles: Boolean = true,
        columnCount: Int = 1,
        isVerticalScroll: Boolean = false,
        readerThemeId: String = "WHITE",
        fontSizeSp: Float = 18f,
        marginDp: Int = 24,
        fontFamily: String? = null,
        textAlign: String? = null,
        lineHeight: Double? = null,
        paragraphStyle: String? = null,
        isFixedLayout: Boolean = false,
        forceSingleColumn: Boolean = false
    ): EpubPreferences {
        @Suppress("NAME_SHADOWING")
        val columnCount = if (forceSingleColumn) 1 else columnCount
        val effectiveScroll = !isFixedLayout && isVerticalScroll
        val resolvedColumnCount = when {
            effectiveScroll -> ColumnCount.ONE
            columnCount == 2 -> ColumnCount.TWO
            else -> ColumnCount.ONE
        }
        val resolvedSpread = when {
            effectiveScroll -> Spread.NEVER
            columnCount == 2 -> Spread.ALWAYS
            else -> Spread.NEVER
        }
        val resolvedTheme = when (readerThemeId.uppercase()) {
            "DARK" -> Theme.DARK
            "CREAM" -> Theme.SEPIA
            else -> Theme.LIGHT
        }
        val resolvedFontSize = if (isFixedLayout) {
            null
        } else {
            (fontSizeSp / 18.0).coerceIn(0.5, 4.0)
        }
        val resolvedPageMargins = if (isFixedLayout) {
            null
        } else {
            (marginDp / 24.0).coerceIn(0.2, 4.0)
        }

        return if (publisherStyles || isFixedLayout) {
            EpubPreferences(
                publisherStyles = true,
                columnCount = resolvedColumnCount,
                spread = resolvedSpread,
                scroll = effectiveScroll,
                theme = resolvedTheme,
                fontSize = resolvedFontSize,
                pageMargins = resolvedPageMargins
            )
        } else {
            val mappedFamily = when (fontFamily?.trim()?.uppercase()) {
                "SERIF" -> FontFamily.SERIF
                "SANS", "SANS_SERIF", "SANS-SERIF" -> FontFamily.SANS_SERIF
                "MONO", "MONOSPACE" -> FontFamily.MONOSPACE
                "OPEN_DYSLEXIC" -> FontFamily.OPEN_DYSLEXIC
                null, "", "DEFAULT" -> null
                else -> FontFamily(fontFamily.trim())
            }
            val mappedTextAlign = when (textAlign?.trim()?.uppercase()) {
                "JUSTIFY" -> TextAlign.JUSTIFY
                "LEFT" -> TextAlign.LEFT
                "CENTER" -> TextAlign.CENTER
                "RIGHT" -> TextAlign.RIGHT
                else -> null
            }
            val mappedLineHeight = lineHeight?.coerceIn(1.0, 3.0)
            val (mappedParagraphIndent, mappedParagraphSpacing) = when (paragraphStyle?.trim()?.uppercase()) {
                "INDENT" -> 1.25 to 0.0
                "SPACED" -> 0.0 to 0.65
                "BOTH" -> 1.25 to 0.5
                "NONE" -> 0.0 to 0.0
                else -> null to null
            }

            EpubPreferences(
                publisherStyles = false,
                columnCount = resolvedColumnCount,
                spread = resolvedSpread,
                scroll = effectiveScroll,
                theme = resolvedTheme,
                fontSize = resolvedFontSize,
                pageMargins = resolvedPageMargins,
                fontFamily = mappedFamily,
                textAlign = mappedTextAlign,
                lineHeight = mappedLineHeight,
                paragraphIndent = mappedParagraphIndent,
                paragraphSpacing = mappedParagraphSpacing
            )
        }
    }

    fun close() {
        synchronized(this) {
            cachedPublication?.close()
            cachedPublication = null
            cachedFilePath = null
            cachedLayoutMode = PrintPageLayoutMode.OFF
        }
    }
}
