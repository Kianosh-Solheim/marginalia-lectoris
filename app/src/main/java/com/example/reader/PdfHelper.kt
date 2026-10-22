package com.example.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/**
 * Represents a single selectable word extracted from a PDF page, with normalized coordinates
 * in [0f..1f] relative to the rendered PDF page's width and height.
 */
data class PdfPageWord(
    val text: String,
    val leftRatio: Float,
    val topRatio: Float,
    val rightRatio: Float,
    val bottomRatio: Float,
    val lineIndex: Int,
    val wordIndex: Int
)

/**
 * Holds both the spatially positioned words for direct on-bitmap selection, the extracted
 * paragraphs for the page, and the detected printed book page number (Arabic or Roman numeral) if any.
 */
data class PdfPageTextData(
    val pageIndex: Int,
    val words: List<PdfPageWord>,
    val paragraphs: List<String>,
    val fullText: String,
    val printedPageLabel: String = ""
)

class PdfDocumentRenderer(
    private val file: File,
    context: Context? = null
) : AutoCloseable {

    private val renderLock = Any()
    private val pdfBoxLock = Any()
    @Volatile
    private var isClosed: Boolean = false
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var pdDocument: PDDocument? = null
    private var pdfBoxRenderer: PDFRenderer? = null
    private var cachedPageCount: Int = -1
    private var catalogPageLabels: Array<String?>? = null

    /**
     * Dedicated bounded background dispatchers for Coroutine-based PDF page rendering and text extraction,
     * preventing UI thread blocking and avoiding thread-pool starvation across multi-page preloads.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val pageRenderDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val textExtractionDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)

    /**
     * In-memory LRU Bitmap cache (keyed by `"pageIndex:targetWidth:cropLeft:cropTop:cropRight:cropBottom"`)
     * so swiping back/forth or zooming returns previously rendered pages instantaneously without re-rendering.
     */
    private val renderedBitmapCache = object : LruCache<String, Bitmap>(16) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private val pageTextCache = object : LinkedHashMap<Int, PdfPageTextData>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfPageTextData>?): Boolean {
            return size > 48
        }
    }
    private val ocrPageTextCache = object : LinkedHashMap<String, PdfPageTextData>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PdfPageTextData>?): Boolean {
            return size > 48
        }
    }
    private val effectivePageTextCache = object : LinkedHashMap<String, PdfPageTextData>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PdfPageTextData>?): Boolean {
            return size > 48
        }
    }
    private val pageDiagnosticsCache = object : LinkedHashMap<Int, PdfPageDiagnosticReport>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfPageDiagnosticReport>?): Boolean {
            return size > 64
        }
    }
    private val manualPageEdits = java.util.concurrent.ConcurrentHashMap<Int, String>()

    @Volatile
    var documentExtractionMode: PdfTextExtractionMode = PdfTextExtractionMode.AUTO
        private set

    @Volatile
    var pageExtractionOverrides: Map<Int, PdfPageExtractionOverride> = emptyMap()
        private set

    @Volatile
    var ocrLanguageCode: String = "AUTO"
        private set

    fun updateExtractionConfiguration(
        mode: PdfTextExtractionMode,
        overrides: Map<Int, PdfPageExtractionOverride>,
        ocrLanguage: String = "AUTO"
    ) {
        val changed = documentExtractionMode != mode ||
            pageExtractionOverrides != overrides ||
            ocrLanguageCode != ocrLanguage
        documentExtractionMode = mode
        pageExtractionOverrides = overrides
        ocrLanguageCode = ocrLanguage
        if (changed) {
            synchronized(pdfBoxLock) {
                effectivePageTextCache.clear()
            }
        }
    }

    fun setManualPageTextEdit(pageNumber: Int, editedText: String) {
        if (editedText.isBlank()) {
            manualPageEdits.remove(pageNumber)
        } else {
            manualPageEdits[pageNumber] = editedText
        }
        synchronized(pdfBoxLock) {
            effectivePageTextCache.clear()
        }
    }

    fun getManualPageTextEdits(): Map<Int, String> = manualPageEdits.toMap()

    fun invalidateOcrCacheForPage(pageIndex: Int) = synchronized(pdfBoxLock) {
        val keysToRemove = ocrPageTextCache.keys.filter { it.startsWith("$pageIndex:") }
        keysToRemove.forEach { ocrPageTextCache.remove(it) }
        effectivePageTextCache.clear()
        pageDiagnosticsCache.remove(pageIndex)
    }

    fun invalidateAllExtractionCaches() = synchronized(pdfBoxLock) {
        effectivePageTextCache.clear()
        ocrPageTextCache.clear()
        pageDiagnosticsCache.clear()
    }

    val pageCount: Int
        get() {
            if (isClosed) return 0
            if (cachedPageCount >= 0) return cachedPageCount
            pdfRenderer?.let {
                synchronized(renderLock) {
                    if (!isClosed) {
                        cachedPageCount = it.pageCount
                        return cachedPageCount
                    }
                }
            }
            synchronized(pdfBoxLock) {
                if (isClosed) return 0
                val count = pdDocument?.numberOfPages ?: ensurePdDocumentLoaded()?.numberOfPages ?: 0
                cachedPageCount = count
                return count
            }
        }

    init {
        try {
            if (context != null && !PDFBoxResourceLoader.isReady()) {
                PDFBoxResourceLoader.init(context.applicationContext)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        try {
            if (file.exists()) {
                parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pdfRenderer = PdfRenderer(parcelFileDescriptor!!).also {
                    cachedPageCount = it.pageCount
                }
            }
        } catch (e: Throwable) {
            // In local JVM/Robolectric tests, native PdfRenderer may throw NoSuchMethodError
        }
        // Only load PDDocument in constructor if native PdfRenderer is unavailable (e.g. unit tests);
        // otherwise load PDDocument lazily on background coroutine when needed!
        if (pdfRenderer == null && file.exists()) {
            ensurePdDocumentLoaded()
        }
    }

    private fun ensurePdDocumentLoaded(): PDDocument? = synchronized(pdfBoxLock) {
        if (isClosed) return null
        pdDocument?.let { return it }
        if (!file.exists()) return null
        return try {
            val loaded = PDDocument.load(
                file,
                com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(24L * 1024L * 1024L)
            )
            pdDocument = loaded
            pdfBoxRenderer = PDFRenderer(loaded).apply {
                isSubsamplingAllowed = true
            }
            if (cachedPageCount < 0) {
                cachedPageCount = loaded.numberOfPages
            }
            try {
                catalogPageLabels = loaded.documentCatalog?.pageLabels?.labelsByPageIndices
            } catch (_: Throwable) {}
            loaded
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Releases the heavy PDFBox [PDDocument] while keeping [pdfRenderer] open for fast native bitmap rendering.
     */
    fun releasePdDocumentMemory() = synchronized(pdfBoxLock) {
        if (pdfRenderer != null) {
            try {
                pdDocument?.close()
            } catch (_: Throwable) {}
            pdDocument = null
            pdfBoxRenderer = null
        }
    }

    /**
     * Coroutine-based background page rendering that prevents UI thread blocking, checks the LRU bitmap
     * cache first, and uses Android's `PdfRenderer` alongside PDFBox-Android's `PDFRenderer` with subsampling.
     */
    suspend fun renderPageAsync(
        pageIndex: Int,
        targetWidth: Int = 1080,
        cropLeft: Float = 0f,
        cropTop: Float = 0f,
        cropRight: Float = 1f,
        cropBottom: Float = 1f
    ): Bitmap? {
        if (isClosed) return null
        val cacheKey = buildString {
            append(pageIndex).append(':').append(targetWidth).append(':')
            append((cropLeft * 1000).roundToInt()).append(':')
            append((cropTop * 1000).roundToInt()).append(':')
            append((cropRight * 1000).roundToInt()).append(':')
            append((cropBottom * 1000).roundToInt())
        }
        synchronized(renderLock) {
            renderedBitmapCache.get(cacheKey)?.let { cached ->
                if (!cached.isRecycled) return cached
            }
        }
        return withContext(pageRenderDispatcher) {
            renderPage(
                pageIndex = pageIndex,
                targetWidth = targetWidth,
                cropLeft = cropLeft,
                cropTop = cropTop,
                cropRight = cropRight,
                cropBottom = cropBottom
            )
        }
    }

    /**
     * Coroutine-based background page text extraction that runs on a dedicated background dispatcher
     * so PDFBox `PDFTextStripper` / `PDDocument` loading never blocks the main UI thread or page rendering.
     */
    suspend fun extractPageTextDataAsync(pageIndex: Int): PdfPageTextData = withContext(textExtractionDispatcher) {
        extractPageTextData(pageIndex)
    }

    fun renderPage(
        pageIndex: Int,
        targetWidth: Int = 1080,
        cropLeft: Float = 0f,
        cropTop: Float = 0f,
        cropRight: Float = 1f,
        cropBottom: Float = 1f
    ): Bitmap? {
        if (isClosed) return null
        val cL = cropLeft.coerceIn(0f, 0.88f)
        val cT = cropTop.coerceIn(0f, 0.88f)
        val cR = cropRight.coerceIn((cL + 0.1f).coerceAtMost(1f), 1f)
        val cB = cropBottom.coerceIn((cT + 0.1f).coerceAtMost(1f), 1f)
        val isCropped = cL > 0.002f || cT > 0.002f || cR < 0.998f || cB < 0.998f

        val cacheKey = buildString {
            append(pageIndex).append(':').append(targetWidth).append(':')
            append((cL * 1000).roundToInt()).append(':')
            append((cT * 1000).roundToInt()).append(':')
            append((cR * 1000).roundToInt()).append(':')
            append((cB * 1000).roundToInt())
        }

        synchronized(renderLock) {
            renderedBitmapCache.get(cacheKey)?.let { cached ->
                if (!cached.isRecycled) return cached
            }
        }

        // 1. Fast native PdfRenderer path (when available on device)
        val nativeRenderer = pdfRenderer
        if (nativeRenderer != null) {
            val rendered = synchronized(renderLock) {
                if (isClosed || pageIndex < 0 || pageIndex >= nativeRenderer.pageCount) {
                    null
                } else {
                    var page: PdfRenderer.Page? = null
                    try {
                        page = nativeRenderer.openPage(pageIndex)
                        val cropWFrac = (cR - cL).coerceAtLeast(0.15f)
                        val fullRenderWidth = ((targetWidth / cropWFrac).toInt()).coerceIn(targetWidth, 1920)
                        val aspectRatio = page.height.toFloat().coerceAtLeast(1f) / page.width.toFloat().coerceAtLeast(1f)
                        val fullRenderHeight = (fullRenderWidth * aspectRatio).toInt().coerceIn(1, 2800)

                        val fullBitmap = Bitmap.createBitmap(fullRenderWidth, fullRenderHeight, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(fullBitmap)
                        canvas.drawColor(Color.WHITE)

                        page.render(fullBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val finalBmp = if (!isCropped) {
                            fullBitmap
                        } else {
                            cropBitmapToRegion(fullBitmap, cL, cT, cR, cB)
                        }
                        renderedBitmapCache.put(cacheKey, finalBmp)
                        finalBmp
                    } catch (e: Throwable) {
                        e.printStackTrace()
                        null
                    } finally {
                        try {
                            page?.close()
                        } catch (_: Throwable) {}
                    }
                }
            }
            if (rendered != null) return rendered
        }

        // 2. Efficient PDFBox-Android PDFRenderer fallback with reused PDFRenderer instance and subsampling enabled
        return synchronized(pdfBoxLock) {
            if (isClosed) return@synchronized null
            try {
                val doc = ensurePdDocumentLoaded() ?: return@synchronized null
                if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return@synchronized null
                val boxRenderer = pdfBoxRenderer ?: PDFRenderer(doc).apply {
                    isSubsamplingAllowed = true
                }.also { pdfBoxRenderer = it }

                val pdPage = doc.getPage(pageIndex)
                val mediaBox = pdPage.cropBox ?: pdPage.mediaBox
                val pageWidthPt = (mediaBox?.width ?: 595f).coerceAtLeast(72f)
                val cropWFrac = (cR - cL).coerceAtLeast(0.15f)
                val desiredWidthPx = ((targetWidth / cropWFrac).toInt()).coerceIn(360, 1600)
                val scale = (desiredWidthPx.toFloat() / pageWidthPt).coerceIn(0.5f, 2.5f)

                val fullBitmap = boxRenderer.renderImage(pageIndex, scale, ImageType.RGB)
                val finalBmp = if (!isCropped) {
                    fullBitmap
                } else {
                    cropBitmapToRegion(fullBitmap, cL, cT, cR, cB)
                }
                synchronized(renderLock) {
                    renderedBitmapCache.put(cacheKey, finalBmp)
                }
                finalBmp
            } catch (e: Throwable) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Non-destructively crops a rendered page [Bitmap] to the normalized view rectangle
     * `[cropLeft, cropTop, cropRight, cropBottom]` in `[0f..1f]` without modifying the PDF file.
     */
    fun cropBitmapToRegion(
        source: Bitmap,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float
    ): Bitmap {
        val cL = cropLeft.coerceIn(0f, 0.88f)
        val cT = cropTop.coerceIn(0f, 0.88f)
        val cR = cropRight.coerceIn((cL + 0.1f).coerceAtMost(1f), 1f)
        val cB = cropBottom.coerceIn((cT + 0.1f).coerceAtMost(1f), 1f)
        if (cL <= 0.002f && cT <= 0.002f && cR >= 0.998f && cB >= 0.998f) return source

        val x = (cL * source.width).roundToInt().coerceIn(0, (source.width - 1).coerceAtLeast(0))
        val y = (cT * source.height).roundToInt().coerceIn(0, (source.height - 1).coerceAtLeast(0))
        val w = ((cR - cL) * source.width).roundToInt().coerceIn(1, (source.width - x).coerceAtLeast(1))
        val h = ((cB - cT) * source.height).roundToInt().coerceIn(1, (source.height - y).coerceAtLeast(1))
        return try {
            Bitmap.createBitmap(source, x, y, w, h)
        } catch (_: Throwable) {
            source
        }
    }

    /**
     * Remaps extracted [PdfPageTextData] word coordinates so that words inside the user's selected
     * PDF view crop `[cropLeft..cropRight, cropTop..cropBottom]` map seamlessly onto `[0f..1f]` of
     * the zoomed-in view. Also preserves the detected `printedPageLabel` even if the header/footer
     * was trimmed out of the reading view!
     */
    fun transformTextDataForCrop(
        rawData: PdfPageTextData,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float
    ): PdfPageTextData {
        val cL = cropLeft.coerceIn(0f, 0.88f)
        val cT = cropTop.coerceIn(0f, 0.88f)
        val cR = cropRight.coerceIn((cL + 0.1f).coerceAtMost(1f), 1f)
        val cB = cropBottom.coerceIn((cT + 0.1f).coerceAtMost(1f), 1f)
        val isCropped = cL > 0.002f || cT > 0.002f || cR < 0.998f || cB < 0.998f
        if (!isCropped) return rawData

        val cropW = (cR - cL).coerceAtLeast(0.05f)
        val cropH = (cB - cT).coerceAtLeast(0.05f)

        val remappedWords = mutableListOf<PdfPageWord>()
        var newWordIdx = 0
        for (w in rawData.words) {
            val cx = (w.leftRatio + w.rightRatio) * 0.5f
            val cy = (w.topRatio + w.bottomRatio) * 0.5f
            // Include words whose center falls inside (or slightly touches) the selected reading region
            if (cx in (cL - 0.015f)..(cR + 0.015f) && cy in (cT - 0.015f)..(cB + 0.015f)) {
                val newLeft = ((w.leftRatio - cL) / cropW).coerceIn(0f, 0.995f)
                val newTop = ((w.topRatio - cT) / cropH).coerceIn(0f, 0.995f)
                val minRight = (newLeft + 0.004f).coerceAtMost(1f)
                val minBottom = (newTop + 0.004f).coerceAtMost(1f)
                val newRight = ((w.rightRatio - cL) / cropW).coerceIn(minRight, 1f)
                val newBottom = ((w.bottomRatio - cT) / cropH).coerceIn(minBottom, 1f)
                remappedWords.add(
                    w.copy(
                        leftRatio = newLeft,
                        topRatio = newTop,
                        rightRatio = newRight,
                        bottomRatio = newBottom,
                        wordIndex = newWordIdx++
                    )
                )
            }
        }

        return rawData.copy(words = remappedWords)
    }

    /**
     * Automatically detects the tight bounding box `[left, top, right, bottom]` around the text body
     * on [pageIndex] (with a comfortable 2% reading breathability pad) so the user can 1-tap
     * "Auto-Trim Margins" when configuring the book's global PDF view zoom.
     */
    fun detectAutoMarginCropBounds(pageIndex: Int): RectF {
        val data = extractPageTextData(pageIndex)
        if (data.words.isEmpty()) {
            return RectF(0.06f, 0.06f, 0.94f, 0.94f)
        }
        val minL = (data.words.minOf { it.leftRatio } - 0.02f).coerceIn(0f, 0.40f)
        val minT = (data.words.minOf { it.topRatio } - 0.02f).coerceIn(0f, 0.40f)
        val maxR = (data.words.maxOf { it.rightRatio } + 0.02f).coerceIn(0.60f, 1f)
        val maxB = (data.words.maxOf { it.bottomRatio } + 0.02f).coerceIn(0.60f, 1f)
        return RectF(minL, minT, maxR, maxB)
    }

    /**
     * Extracts the raw, unmodified original PDF text layer on [pageIndex] without OCR or repair.
     */
    fun extractRawOriginalPageTextData(pageIndex: Int): PdfPageTextData = synchronized(pdfBoxLock) {
        if (isClosed) {
            return PdfPageTextData(
                pageIndex = pageIndex,
                words = emptyList(),
                paragraphs = emptyList(),
                fullText = "",
                printedPageLabel = ""
            )
        }
        pageTextCache[pageIndex]?.let { return it }

        val words = mutableListOf<PdfPageWord>()
        var extractedFullText = ""

        try {
            val doc = ensurePdDocumentLoaded()
            if (doc != null && !isClosed && pageIndex in 0 until doc.numberOfPages) {
                val rawGlyphs = mutableListOf<TextPosition>()
                val stripper = object : PDFTextStripper() {
                    override fun processTextPosition(text: TextPosition?) {
                        if (text != null && text.unicode != null && text.unicode.isNotEmpty()) {
                            rawGlyphs.add(text)
                        }
                        super.processTextPosition(text)
                    }
                }
                stripper.sortByPosition = true
                stripper.startPage = pageIndex + 1
                stripper.endPage = pageIndex + 1
                extractedFullText = stripper.getText(doc)?.trim() ?: ""

                if (rawGlyphs.isNotEmpty()) {
                    val firstGlyph = rawGlyphs.first()
                    val pageW = firstGlyph.pageWidth.coerceAtLeast(1f)
                    val pageH = firstGlyph.pageHeight.coerceAtLeast(1f)

                    // Group glyphs into lines and words based on Y-baseline and X-spacing
                    data class GlyphItem(
                        val str: String,
                        val left: Float,
                        val top: Float,
                        val right: Float,
                        val bottom: Float,
                        val spaceWidth: Float
                    )

                    val glyphItems = rawGlyphs.mapNotNull { tp ->
                        val u = tp.unicode ?: return@mapNotNull null
                        val x = tp.xDirAdj
                        val w = tp.widthDirAdj.coerceAtLeast(1f)
                        val h = max(tp.heightDir, tp.fontSizeInPt * 0.85f).coerceAtLeast(4f)
                        // PDFTextStripper yDirAdj is baseline from top of page
                        val bottom = tp.yDirAdj + h * 0.22f
                        val top = (tp.yDirAdj - h * 0.92f).coerceAtLeast(0f)
                        GlyphItem(
                            str = u,
                            left = x,
                            top = top,
                            right = x + w,
                            bottom = bottom,
                            spaceWidth = tp.widthOfSpace.coerceAtLeast(w * 0.35f)
                        )
                    }

                    // Group into visual lines (top-to-bottom, then left-to-right within each line)
                    val lines = mutableListOf<MutableList<GlyphItem>>()
                    for (g in glyphItems) {
                        if (g.str.isBlank() && g.str != " ") continue
                        val gHeight = (g.bottom - g.top).coerceAtLeast(4f)
                        val gCenterY = (g.top + g.bottom) * 0.5f
                        val matchingLine = lines.lastOrNull { line ->
                            val first = line.first()
                            val lineCenterY = (first.top + first.bottom) * 0.5f
                            val lineHeight = (first.bottom - first.top).coerceAtLeast(4f)
                            abs(gCenterY - lineCenterY) <= max(gHeight, lineHeight) * 0.65f
                        }
                        if (matchingLine != null) {
                            matchingLine.add(g)
                        } else {
                            lines.add(mutableListOf(g))
                        }
                    }

                    lines.sortBy { line -> line.minOfOrNull { it.top } ?: 0f }

                    var globalWordIdx = 0
                    for ((lineIdx, line) in lines.withIndex()) {
                        line.sortBy { it.left }

                        var curText = StringBuilder()
                        var curLeft = 0f
                        var curTop = 0f
                        var curRight = 0f
                        var curBottom = 0f
                        var lastRight = -1f
                        var lastSpaceW = 4f

                        fun flushWord() {
                            val token = curText.toString().trim()
                            if (token.isNotEmpty()) {
                                val padX = (curRight - curLeft).coerceAtLeast(2f) * 0.04f
                                val padY = (curBottom - curTop).coerceAtLeast(2f) * 0.12f
                                words.add(
                                    PdfPageWord(
                                        text = token,
                                        leftRatio = ((curLeft - padX) / pageW).coerceIn(0f, 1f),
                                        topRatio = ((curTop - padY) / pageH).coerceIn(0f, 1f),
                                        rightRatio = ((curRight + padX) / pageW).coerceIn(0f, 1f),
                                        bottomRatio = ((curBottom + padY) / pageH).coerceIn(0f, 1f),
                                        lineIndex = lineIdx,
                                        wordIndex = globalWordIdx++
                                    )
                                )
                            }
                            curText = StringBuilder()
                            lastRight = -1f
                        }

                        for (g in line) {
                            if (g.str.isBlank()) {
                                if (curText.isNotEmpty()) {
                                    flushWord()
                                }
                                continue
                            }
                            val gap = if (lastRight >= 0f) g.left - lastRight else 0f
                            val glyphW = (g.right - g.left).coerceAtLeast(1f)
                            val wordBreakThreshold = max(
                                min(lastSpaceW, g.spaceWidth) * 0.42f,
                                min((g.bottom - g.top) * 0.16f, glyphW * 0.34f)
                            )
                            val camelBoundaryWithGap = curText.isNotEmpty() &&
                                curText.last().isLowerCase() &&
                                g.str.first().isUpperCase() &&
                                gap > glyphW * 0.12f
                            val punctBoundaryWithGap = curText.isNotEmpty() &&
                                curText.last() in charArrayOf('.', ',', ';', ':', '!', '?', ')') &&
                                g.str.first().isLetterOrDigit() &&
                                gap > glyphW * 0.08f
                            if (curText.isNotEmpty() && (gap > wordBreakThreshold || camelBoundaryWithGap || punctBoundaryWithGap)) {
                                flushWord()
                            }
                            if (curText.isEmpty()) {
                                curLeft = g.left
                                curTop = g.top
                                curRight = g.right
                                curBottom = g.bottom
                            } else {
                                curLeft = min(curLeft, g.left)
                                curTop = min(curTop, g.top)
                                curRight = max(curRight, g.right)
                                curBottom = max(curBottom, g.bottom)
                            }
                            curText.append(g.str)
                            lastRight = g.right
                            lastSpaceW = g.spaceWidth
                        }
                        if (curText.isNotEmpty()) {
                            flushWord()
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        // Pages set in columns: reading order goes down each column instead of straight across the page,
        // so selections, quotes and read-aloud stay within one column.
        var columnParagraphs: List<String> = emptyList()
        if (words.isNotEmpty()) {
            runCatching { PdfColumnOrder.reorder(words) }.getOrNull()?.takeIf { it.columns > 1 }?.let { col ->
                words.clear(); words.addAll(col.words)
                columnParagraphs = col.paragraphs
                if (col.paragraphs.isNotEmpty()) extractedFullText = col.paragraphs.joinToString("\n\n")
            }
        }

        // If PDFBox found text but no positioned words, or if we can synthesize word boxes from paragraphs
        val paragraphs = if (columnParagraphs.isNotEmpty()) columnParagraphs else if (extractedFullText.isNotBlank()) {
            extractedFullText
                .replace("\r\n", "\n")
                .split(Regex("\\n\\s*\\n+"))
                .map { block ->
                    block.lines().joinToString(" ") { it.trim() }.replace(Regex("\\s+"), " ").trim()
                }
                .filter { it.isNotBlank() }
        } else if (words.isNotEmpty()) {
            words.groupBy { it.lineIndex }
                .entries
                .sortedBy { it.key }
                .map { (_, lineWords) -> lineWords.joinToString(" ") { it.text } }
        } else {
            emptyList()
        }

        if (words.isEmpty() && paragraphs.isNotEmpty()) {
            // Synthesize proportional word layout boxes so any extracted text is still selectable by dragging
            val allLines = paragraphs.flatMap { para ->
                para.chunked(65)
            }
            var wIdx = 0
            val totalLines = allLines.size.coerceAtLeast(1)
            for ((lIdx, lineStr) in allLines.withIndex()) {
                val topR = 0.08f + (lIdx.toFloat() / totalLines) * 0.84f
                val bottomR = (topR + (0.78f / totalLines).coerceAtMost(0.045f)).coerceAtMost(0.95f)
                val tokens = lineStr.split(Regex("\\s+")).filter { it.isNotBlank() }
                val totalChars = lineStr.length.coerceAtLeast(1)
                var runningChars = 0
                for (tok in tokens) {
                    val leftR = 0.08f + (runningChars.toFloat() / totalChars) * 0.84f
                    val rightR = (0.08f + ((runningChars + tok.length).toFloat() / totalChars) * 0.84f).coerceAtMost(0.94f)
                    words.add(
                        PdfPageWord(
                            text = tok,
                            leftRatio = leftR,
                            topRatio = topR,
                            rightRatio = rightR,
                            bottomRatio = bottomR,
                            lineIndex = lIdx,
                            wordIndex = wIdx++
                        )
                    )
                    runningChars += tok.length + 1
                }
            }
        }

        val fullJoined = if (extractedFullText.isNotBlank()) {
            extractedFullText
        } else {
            paragraphs.joinToString("\n\n")
        }

        val detectedPrintedPage = detectPrintedPageNumber(
            pageIndex = pageIndex,
            words = words,
            catalogLabel = catalogPageLabels?.getOrNull(pageIndex)
        )

        val data = PdfPageTextData(
            pageIndex = pageIndex,
            words = words,
            paragraphs = paragraphs,
            fullText = fullJoined,
            printedPageLabel = detectedPrintedPage
        )
        pageTextCache[pageIndex] = data
        return data
    }

    /**
     * Runs OCR on the rendered PDF page (or specific region) and caches the result.
     */
    suspend fun extractOcrPageTextData(
        pageIndex: Int,
        regionRectNormalized: RectF = RectF(0f, 0f, 1f, 1f)
    ): PdfPageTextData {
        val cacheKey = "$pageIndex:${regionRectNormalized.left}:${regionRectNormalized.top}:${regionRectNormalized.right}:${regionRectNormalized.bottom}"
        synchronized(pdfBoxLock) {
            ocrPageTextCache[cacheKey]?.let { return it }
        }
        val bmp = renderPage(pageIndex, targetWidth = 1080)
        val ocrResult = PdfPageDiagnosticsAndOcrEngine.recognizePageTextViaOcr(
            pageIndex = pageIndex,
            fullPageBitmap = bmp,
            regionRectNormalized = regionRectNormalized,
            fallbackRawData = extractRawOriginalPageTextData(pageIndex),
            filePath = file.absolutePath
        )
        synchronized(pdfBoxLock) {
            ocrPageTextCache[cacheKey] = ocrResult
        }
        return ocrResult
    }

    /**
     * Synchronous OCR lookup/execution helper so both coroutine and synchronous callers can obtain OCR results.
     */
    fun extractOcrPageTextDataBlocking(
        pageIndex: Int,
        regionRectNormalized: RectF = RectF(0f, 0f, 1f, 1f)
    ): PdfPageTextData {
        val cacheKey = "$pageIndex:${regionRectNormalized.left}:${regionRectNormalized.top}:${regionRectNormalized.right}:${regionRectNormalized.bottom}"
        synchronized(pdfBoxLock) {
            ocrPageTextCache[cacheKey]?.let { return it }
        }
        return kotlinx.coroutines.runBlocking {
            extractOcrPageTextData(pageIndex, regionRectNormalized)
        }
    }

    /**
     * Evaluates and caches the diagnostic health report for [pageIndex] (0-indexed).
     */
    fun getPageDiagnostics(pageIndex: Int, includeBitmapInspection: Boolean = true): PdfPageDiagnosticReport {
        synchronized(pdfBoxLock) {
            val cached = pageDiagnosticsCache[pageIndex]
            if (cached != null && (!includeBitmapInspection || cached.hasVisualInkContent || cached.nativeWordCount > 0)) {
                return cached
            }
        }
        val rawData = extractRawOriginalPageTextData(pageIndex)
        val bmp = if (includeBitmapInspection) renderPage(pageIndex, targetWidth = 480) else null
        val report = PdfPageDiagnosticsAndOcrEngine.analyzePageDiagnostics(
            pageNumber = pageIndex + 1,
            rawTextData = rawData,
            pageBitmap = bmp
        )
        synchronized(pdfBoxLock) {
            pageDiagnosticsCache[pageIndex] = report
        }
        return report
    }

    /**
     * Extracts page text according to the active [documentMode] and per-page [pageOverride]:
     * - ORIGINAL: Raw original PDF text layer
     * - OCR: Force OCR on the rendered page
     * - HYBRID: Healthy native words + OCR/repaired words for missing/corrupted regions without duplication
     * - AUTO: Analyzes page health; uses native if healthy, repairs duplicates/reading order if needed, or falls back to OCR if scanned/corrupted
     * - Per-page repair overrides: REPAIR_DUPLICATES, REPAIR_READING_ORDER, REPAIR_UNICODE_AND_DUPLICATES
     */
    fun extractPageTextDataWithMode(
        pageIndex: Int,
        documentMode: PdfTextExtractionMode = documentExtractionMode,
        pageOverride: PdfPageExtractionOverride = pageExtractionOverrides[pageIndex + 1] ?: PdfPageExtractionOverride.FOLLOW_DOCUMENT
    ): PdfPageTextData {
        val pageNum = pageIndex + 1
        val manualText = manualPageEdits[pageNum]
        if (!manualText.isNullOrBlank()) {
            return synthesizePageTextDataFromEditedString(pageIndex, manualText)
        }

        val cacheKey = "$pageIndex:${documentMode.id}:${pageOverride.id}"
        synchronized(pdfBoxLock) {
            effectivePageTextCache[cacheKey]?.let { return it }
        }

        val rawOriginal = extractRawOriginalPageTextData(pageIndex)

        val result: PdfPageTextData = when (pageOverride) {
            PdfPageExtractionOverride.ORIGINAL -> rawOriginal
            PdfPageExtractionOverride.OCR -> extractOcrPageTextDataBlocking(pageIndex)
            PdfPageExtractionOverride.HYBRID -> {
                val ocr = extractOcrPageTextDataBlocking(pageIndex)
                PdfPageDiagnosticsAndOcrEngine.mergeHybridPageText(pageIndex, rawOriginal, ocr)
            }
            PdfPageExtractionOverride.REPAIR_DUPLICATES -> {
                PdfPageDiagnosticsAndOcrEngine.repairPageTextData(
                    rawTextData = rawOriginal,
                    deduplicateAndCollapseRepeats = true,
                    reconstructReadingOrder = false,
                    stripInvalidUnicode = false,
                    repairMissingSpaces = false
                )
            }
            PdfPageExtractionOverride.REPAIR_MISSING_SPACES -> {
                PdfPageDiagnosticsAndOcrEngine.repairPageTextData(
                    rawTextData = rawOriginal,
                    deduplicateAndCollapseRepeats = false,
                    reconstructReadingOrder = false,
                    stripInvalidUnicode = false,
                    repairMissingSpaces = true
                )
            }
            PdfPageExtractionOverride.REPAIR_READING_ORDER -> {
                PdfPageDiagnosticsAndOcrEngine.repairPageTextData(
                    rawTextData = rawOriginal,
                    deduplicateAndCollapseRepeats = false,
                    reconstructReadingOrder = true,
                    stripInvalidUnicode = false,
                    repairMissingSpaces = false
                )
            }
            PdfPageExtractionOverride.REPAIR_UNICODE_AND_DUPLICATES -> {
                val repaired = PdfPageDiagnosticsAndOcrEngine.repairPageTextData(
                    rawTextData = rawOriginal,
                    deduplicateAndCollapseRepeats = true,
                    reconstructReadingOrder = true,
                    stripInvalidUnicode = true,
                    repairMissingSpaces = true
                )
                val diag = getPageDiagnostics(pageIndex, includeBitmapInspection = false)
                if (repaired.words.isEmpty() ||
                    diag.issues.contains(PdfDiagnosticIssueType.MISSING_OR_DAMAGED_TOUNICODE_MAP) ||
                    diag.issues.contains(PdfDiagnosticIssueType.SCANNED_IMAGE_ONLY_PAGE)
                ) {
                    val ocr = extractOcrPageTextDataBlocking(pageIndex)
                    if (ocr.words.isNotEmpty()) ocr else repaired
                } else {
                    repaired
                }
            }
            PdfPageExtractionOverride.FOLLOW_DOCUMENT -> {
                when (documentMode) {
                    PdfTextExtractionMode.ORIGINAL -> rawOriginal
                    PdfTextExtractionMode.OCR -> extractOcrPageTextDataBlocking(pageIndex)
                    PdfTextExtractionMode.HYBRID -> {
                        val diag = getPageDiagnostics(pageIndex, includeBitmapInspection = false)
                        if (diag.classification == PdfPageHealthClassification.HEALTHY_TEXT_LAYER) {
                            rawOriginal
                        } else {
                            val ocr = extractOcrPageTextDataBlocking(pageIndex)
                            PdfPageDiagnosticsAndOcrEngine.mergeHybridPageText(pageIndex, rawOriginal, ocr)
                        }
                    }
                    PdfTextExtractionMode.AUTO -> {
                        val diag = getPageDiagnostics(pageIndex, includeBitmapInspection = false)
                        when (diag.classification) {
                            PdfPageHealthClassification.HEALTHY_TEXT_LAYER -> rawOriginal
                            PdfPageHealthClassification.IMAGE_ONLY_PAGE,
                            PdfPageHealthClassification.CORRUPTED_OR_UNUSABLE -> {
                                val ocr = extractOcrPageTextDataBlocking(pageIndex)
                                if (ocr.words.isNotEmpty()) {
                                    ocr
                                } else {
                                    PdfPageDiagnosticsAndOcrEngine.repairPageTextData(rawOriginal)
                                }
                            }
                            PdfPageHealthClassification.POTENTIAL_TEXT_PROBLEMS,
                            PdfPageHealthClassification.MIXED_OR_UNCERTAIN -> {
                                when (diag.recommendedOverride) {
                                    PdfPageExtractionOverride.OCR -> {
                                        val ocr = extractOcrPageTextDataBlocking(pageIndex)
                                        if (ocr.words.isNotEmpty()) ocr else PdfPageDiagnosticsAndOcrEngine.repairPageTextData(rawOriginal)
                                    }
                                    PdfPageExtractionOverride.HYBRID -> {
                                        val ocr = extractOcrPageTextDataBlocking(pageIndex)
                                        PdfPageDiagnosticsAndOcrEngine.mergeHybridPageText(pageIndex, rawOriginal, ocr)
                                    }
                                    else -> PdfPageDiagnosticsAndOcrEngine.repairPageTextData(
                                        rawTextData = rawOriginal,
                                        deduplicateAndCollapseRepeats = diag.issues.contains(PdfDiagnosticIssueType.EXCESSIVELY_REPEATED_LETTERS) ||
                                            diag.issues.contains(PdfDiagnosticIssueType.DUPLICATE_OVERLAPPING_TEXT_LAYERS),
                                        reconstructReadingOrder = diag.issues.contains(PdfDiagnosticIssueType.INCORRECT_READING_ORDER),
                                        stripInvalidUnicode = true,
                                        repairMissingSpaces = diag.issues.contains(PdfDiagnosticIssueType.MISSING_WORD_SPACES)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        synchronized(pdfBoxLock) {
            effectivePageTextCache[cacheKey] = result
        }
        return result
    }

    private fun synthesizePageTextDataFromEditedString(pageIndex: Int, editedText: String): PdfPageTextData {
        val paragraphs = editedText
            .replace("\r\n", "\n")
            .split(Regex("\\n\\s*\\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val allLines = editedText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val words = mutableListOf<PdfPageWord>()
        var wIdx = 0
        val totalLines = allLines.size.coerceAtLeast(1)
        for ((lIdx, lineStr) in allLines.withIndex()) {
            val topR = 0.08f + (lIdx.toFloat() / totalLines) * 0.84f
            val bottomR = (topR + (0.78f / totalLines).coerceAtMost(0.045f)).coerceAtMost(0.95f)
            val tokens = lineStr.split(Regex("\\s+")).filter { it.isNotBlank() }
            val totalChars = lineStr.length.coerceAtLeast(1)
            var runningChars = 0
            for (tok in tokens) {
                val leftR = 0.08f + (runningChars.toFloat() / totalChars) * 0.84f
                val rightR = (0.08f + ((runningChars + tok.length).toFloat() / totalChars) * 0.84f).coerceAtMost(0.94f)
                words.add(
                    PdfPageWord(
                        text = tok,
                        leftRatio = leftR,
                        topRatio = topR,
                        rightRatio = rightR,
                        bottomRatio = bottomR,
                        lineIndex = lIdx,
                        wordIndex = wIdx++
                    )
                )
                runningChars += tok.length + 1
            }
        }
        return PdfPageTextData(
            pageIndex = pageIndex,
            words = words,
            paragraphs = paragraphs,
            fullText = editedText.trim(),
            printedPageLabel = (pageIndex + 1).toString()
        )
    }

    /**
     * Extracts all selectable words and their exact normalized bounding boxes [0..1] on [pageIndex],
     * using the active PDF Text Extraction Mode and per-page override so selection, copying, searching,
     * TTS, and PDF-to-Ebook reflow automatically use the chosen/repaired/OCR text layer!
     */
    fun extractPageTextData(pageIndex: Int): PdfPageTextData {
        return extractPageTextDataWithMode(
            pageIndex = pageIndex,
            documentMode = documentExtractionMode,
            pageOverride = pageExtractionOverrides[pageIndex + 1] ?: PdfPageExtractionOverride.FOLLOW_DOCUMENT
        )
    }

    /** Printed page numbers read directly from page text (Arabic only), per PDF page index. */
    private val directPrintedNumbers = HashMap<Int, Int>()
    private var probingNeighbours = false
    private var probesWithoutNumbers = 0

    /**
     * Detects the printed page number (Arabic e.g. "3", "142" or Roman numeral e.g. "xii", "iv", "XIV")
     * from the page's running header/footer or embedded PDF PageLabels dictionary, then checks it
     * against the neighbouring pages: on a chapter's first page the large chapter number at the top
     * is not the page number, and such pages often print no number at all — the sequence of the
     * pages around it (15, ?, 17) gives the right one.
     */
    private fun detectPrintedPageNumber(
        pageIndex: Int,
        words: List<PdfPageWord>,
        catalogLabel: String?
    ): String {
        val own = readPrintedNumberFromPage(words)
        own.toIntOrNull()?.let { directPrintedNumbers[pageIndex] = it }
        val catalog = catalogLabel?.trim().orEmpty().takeIf { it.isNotBlank() && isValidPageNumberToken(it) }.orEmpty()
        val raw = own.ifBlank { catalog }
        // Roman numerals (front matter) are trusted as read.
        if (raw.isNotBlank() && raw.toIntOrNull() == null) return raw

        val (expected, votes) = expectedFromNeighbours(pageIndex) ?: return raw
        return when {
            raw.isBlank() -> expected.toString()
            raw.toInt() == expected -> raw
            votes >= 2 -> {
                // The page's own number disagrees with the pages around it: it was a chapter number,
                // a note number or similar. Don't let it mislead the next pages either.
                directPrintedNumbers.remove(pageIndex)
                expected.toString()
            }
            else -> raw
        }
    }

    /** Header/footer page number, ignoring a lone large number (a chapter number set in display type). */
    private fun readPrintedNumberFromPage(words: List<PdfPageWord>): String {
        if (words.isEmpty()) return ""
        val heights = words.map { it.bottomRatio - it.topRatio }.sorted()
        val bodyHeight = heights[heights.size / 2].coerceAtLeast(0.004f)
        // Lines by position on the page (reading order may be by column).
        val lines = words.groupBy { it.lineIndex }.values.sortedBy { l -> l.minOf { it.topRatio } }
        if (lines.isEmpty()) return ""
        val candidates = mutableListOf<List<PdfPageWord>>()
        val last = lines.last()
        if ((last.minOfOrNull { it.topRatio } ?: 0.5f) >= 0.72f || lines.size >= 3) candidates += last
        val first = lines.first()
        if (first !== last && ((first.maxOfOrNull { it.bottomRatio } ?: 0.5f) <= 0.25f || lines.size >= 2)) candidates += first
        for (lineWords in candidates) {
            val joined = lineWords.joinToString(" ") { it.text }.trim()
            val token = extractPageTokenFromHeaderOrFooterLine(joined, lineWords)
            if (token.isBlank()) continue
            val w = lineWords.firstOrNull { cleanPageToken(it.text) == token } ?: lineWords.first()
            val isLarge = (w.bottomRatio - w.topRatio) > bodyHeight * 1.6f
            if (isLarge && lineWords.size <= 2) continue
            return token
        }
        return ""
    }

    /**
     * The page number implied by nearby pages (printed number minus PDF index is constant within a
     * run of numbered pages). Returns (number, how many neighbours agree) or null.
     */
    private fun expectedFromNeighbours(pageIndex: Int): Pair<Int, Int>? {
        fun offsets() = (-6..6).filter { it != 0 }.mapNotNull { d -> directPrintedNumbers[pageIndex + d]?.let { it - (pageIndex + d) } }
        var offs = offsets()
        if (offs.size < 2 && !probingNeighbours && probesWithoutNumbers < 12) {
            val total = runCatching { ensurePdDocumentLoaded()?.numberOfPages }.getOrNull() ?: 0
            probingNeighbours = true
            try {
                for (d in listOf(-1, 1, -2, 2, -3, 3)) {
                    val p = pageIndex + d
                    if (p !in 0 until total || pageTextCache.containsKey(p)) continue
                    runCatching { extractRawOriginalPageTextData(p) }
                    if (offsets().size >= 2) break
                }
            } finally {
                probingNeighbours = false
            }
            offs = offsets()
            if (offs.isEmpty()) probesWithoutNumbers++ else probesWithoutNumbers = 0
        }
        if (offs.isEmpty()) return null
        val best = offs.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        val expected = pageIndex + best.key
        if (expected < 1) return null
        // A single neighbour is only trusted when the page itself shows no number.
        return expected to best.value
    }

    override fun close() {
        isClosed = true
        synchronized(renderLock) {
            try {
                renderedBitmapCache.evictAll()
            } catch (_: Throwable) {}
            try {
                pdfRenderer?.close()
                pdfRenderer = null
            } catch (_: Throwable) {}
            try {
                parcelFileDescriptor?.close()
                parcelFileDescriptor = null
            } catch (_: Throwable) {}
        }
        synchronized(pdfBoxLock) {
            pdfBoxRenderer = null
            try {
                pdDocument?.close()
                pdDocument = null
            } catch (_: Throwable) {}
        }
    }

    companion object {
        private val ROMAN_NUMERAL_REGEX = Regex(
            "^(?=[MDCLXVI]+$)M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$",
            RegexOption.IGNORE_CASE
        )
        private val ARABIC_PAGE_REGEX = Regex("^[1-9][0-9]{0,3}$")
        private val EXPLICIT_PAGE_PREFIX_REGEX = Regex(
            "(?:\\bp\\.\\s*|\\bpp\\.\\s*|\\bpage\\s+)([0-9]{1,4}|[ivxlcdmIVXLCDM]{1,8})\\b",
            RegexOption.IGNORE_CASE
        )

        /**
         * Checks whether a single token is a plausible printed book page number
         * (either an Arabic number 1..9999 that isn't a 4-digit publication year unless small,
         * or a valid Roman numeral like "i", "iv", "xii", "XXIV").
         */
        fun isValidPageNumberToken(token: String): Boolean {
            val stripped = token.trim()
                .removePrefix("[").removeSuffix("]")
                .removePrefix("(").removeSuffix(")")
                .removePrefix("-").removeSuffix("-")
                .removePrefix("—").removeSuffix("—")
                .removePrefix("–").removeSuffix("–")
                .trim()
            if (stripped.isEmpty()) return false
            if (ARABIC_PAGE_REGEX.matches(stripped)) {
                val num = stripped.toIntOrNull() ?: return false
                // Avoid mistaking standalone years like 1998 or 2024 in headers/footers unless <= 1800
                return num in 1..1799
            }
            if (stripped.length in 1..8 && ROMAN_NUMERAL_REGEX.matches(stripped)) {
                // Single letter "I" or "V" or "X" in mixed case inside prose could be a word/initial,
                // unless it is lowercase ("i", "ii", "iii", "iv", "v", "vi", "xii") or multi-character Roman
                if (stripped.length == 1) {
                    return stripped[0].isLowerCase() || stripped == "I" || stripped == "V" || stripped == "X"
                }
                return true
            }
            return false
        }

        fun cleanPageToken(token: String): String {
            return token.trim()
                .removePrefix("[").removeSuffix("]")
                .removePrefix("(").removeSuffix(")")
                .removePrefix("-").removeSuffix("-")
                .removePrefix("—").removeSuffix("—")
                .removePrefix("–").removeSuffix("–")
                .trim()
        }

        /**
         * Extracts a printed page number from a running header or footer line:
         * - Standalone page number line: "3", "xiv", "[ 14 ]", "— 27 —"
         * - Explicit "Page 3" or "p. xiv"
         * - Running header/footer with chapter title and outer margin page number:
         *   e.g. "Chapter 1: Introduction   3" or "xiv   Preface"
         */
        fun extractPageTokenFromHeaderOrFooterLine(
            joinedLine: String,
            lineWords: List<PdfPageWord>
        ): String {
            val trimmed = joinedLine.trim()
            if (trimmed.isEmpty()) return ""

            // Case A: Entire header/footer line is a single page number token (e.g. "3", "xiv", "- 12 -")
            if (lineWords.size == 1 && isValidPageNumberToken(lineWords.first().text)) {
                return cleanPageToken(lineWords.first().text)
            }
            if (lineWords.size <= 3 && isValidPageNumberToken(trimmed)) {
                return cleanPageToken(trimmed)
            }

            // Case B: Explicit "Page 3" or "p. xii" in header/footer
            val explicitMatch = EXPLICIT_PAGE_PREFIX_REGEX.find(trimmed)
            if (explicitMatch != null) {
                val tok = explicitMatch.groupValues[1]
                if (isValidPageNumberToken(tok)) return cleanPageToken(tok)
            }

            // Case C: Running header/footer where the first or last word is at the outer margin
            // (or separated from the running title) and is a page number token
            if (lineWords.size in 2..10) {
                val lastW = lineWords.last()
                val prevW = lineWords[lineWords.size - 2]
                val rightGap = lastW.leftRatio - prevW.rightRatio
                val cleanedLast = cleanPageToken(lastW.text)
                if (isValidPageNumberToken(cleanedLast) &&
                    (ARABIC_PAGE_REGEX.matches(cleanedLast) || cleanedLast.all { it.isLowerCase() } || rightGap > 0.04f)
                ) {
                    // Ensure single uppercase "I" at end of sentence isn't mistaken unless separated by gap
                    if (cleanedLast != "I" || rightGap > 0.05f) {
                        return cleanedLast
                    }
                }

                val firstW = lineWords.first()
                val secondW = lineWords[1]
                val leftGap = secondW.leftRatio - firstW.rightRatio
                val cleanedFirst = cleanPageToken(firstW.text)
                if (isValidPageNumberToken(cleanedFirst) &&
                    (ARABIC_PAGE_REGEX.matches(cleanedFirst) || cleanedFirst.all { it.isLowerCase() } || leftGap > 0.04f)
                ) {
                    if (cleanedFirst != "I" || leftGap > 0.05f) {
                        return cleanedFirst
                    }
                }
            }

            return ""
        }

        fun copyUriToFile(context: Context, uri: Uri, destFile: File): Boolean {
            return try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
}

object PdfChapterHelper {

    fun parseChapters(json: String): List<BookChapter> {
        if (json.isBlank()) return emptyList()
        return try {
            json.split("||").mapNotNull { entry ->
                val parts = entry.split("::")
                if (parts.size >= 2) {
                    val page = parts[0].trim().toIntOrNull() ?: 1
                    val title = parts[1].trim()
                    val level = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
                    val href = parts.getOrNull(3)?.trim().orEmpty()
                    val fileKey = parts.getOrNull(4)?.trim().orEmpty()
                    val anchorId = parts.getOrNull(5)?.trim().orEmpty()
                    if (title.isNotEmpty()) {
                        BookChapter(
                            title = title,
                            pageNumber = page,
                            level = level,
                            href = href,
                            fileKey = fileKey,
                            anchorId = anchorId
                        )
                    } else null
                } else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun serializeChapters(chapters: List<BookChapter>): String {
        return chapters.joinToString("||") { ch ->
            if (ch.level > 0 || ch.href.isNotEmpty() || ch.fileKey.isNotEmpty() || ch.anchorId.isNotEmpty()) {
                "${ch.pageNumber}::${ch.title}::${ch.level}::${ch.href}::${ch.fileKey}::${ch.anchorId}"
            } else {
                "${ch.pageNumber}::${ch.title}"
            }
        }
    }

    /**
     * Extracts outline bookmarks from PDF file or generates smart chapter groupings.
     */
    /** Prefix marking a chapters list that holds only the user's own chapter markers. */
    const val USER_MARKERS_PREFIX = "#user||"

    /** The user's own chapter markers stored with the document (none for an old full list). */
    fun userMarkers(customChaptersJson: String): List<BookChapter> =
        if (customChaptersJson.startsWith(USER_MARKERS_PREFIX)) parseChapters(customChaptersJson.removePrefix(USER_MARKERS_PREFIX)) else emptyList()

    fun serializeUserMarkers(markers: List<BookChapter>): String = USER_MARKERS_PREFIX + serializeChapters(markers)

    /**
     * The PDF's bookmark tree (all levels, in reading order) merged with the user's own markers.
     * Older saved lists that copied the whole (byte-scanned) contents are only used when the PDF
     * has no bookmarks at all.
     */
    fun extractOrGenerateChapters(file: File, pageCount: Int, customChaptersJson: String): List<BookChapter> {
        val fromFile = extractBookmarksFromPdf(file, pageCount)
        val markers = userMarkers(customChaptersJson)
        if (fromFile.isNotEmpty()) {
            if (markers.isEmpty()) return fromFile
            val merged = fromFile.toMutableList()
            markers.forEach { m ->
                if (merged.none { it.pageNumber == m.pageNumber && it.title.equals(m.title, true) }) {
                    val at = merged.indexOfFirst { it.pageNumber > m.pageNumber }.let { if (it < 0) merged.size else it }
                    merged.add(at, m.copy(level = 0))
                }
            }
            return merged
        }
        if (markers.isNotEmpty()) return markers.sortedBy { it.pageNumber }
        val legacy = if (customChaptersJson.startsWith(USER_MARKERS_PREFIX)) emptyList() else parseChapters(customChaptersJson)
        if (legacy.isNotEmpty()) return legacy.sortedBy { it.pageNumber }
        return generateDefaultChapters(pageCount)
    }

    /** The PDF's own bookmark tree, every level, read with PDFBox; the old byte scan is only a fallback. */
    private fun extractBookmarksFromPdf(file: File, pageCount: Int): List<BookChapter> {
        val outline = runCatching { PdfOutline.read(file) }.onFailure { it.printStackTrace() }.getOrNull()
        if (outline != null) {
            return outline.filter { it.page in 1..pageCount.coerceAtLeast(1) || pageCount <= 1 }
                .map { BookChapter(title = it.title, pageNumber = it.page, level = it.level, pageLabel = it.pageLabel) }
        }
        return scanRawBookmarks(file, pageCount)
    }

    private fun scanRawBookmarks(file: File, pageCount: Int): List<BookChapter> {
        val chapters = mutableListOf<BookChapter>()
        try {
            if (!file.exists()) return emptyList()
            // Read at most 1.5 MB when scanning raw bytes for /Title outlines to prevent OOM on large PDFs
            val maxBytesToScan = min(file.length(), 1_500_000L).toInt()
            if (maxBytesToScan <= 0) return emptyList()
            val bytes = ByteArray(maxBytesToScan)
            file.inputStream().use { input ->
                var offset = 0
                while (offset < maxBytesToScan) {
                    val read = input.read(bytes, offset, maxBytesToScan - offset)
                    if (read <= 0) break
                    offset += read
                }
            }
            val text = String(bytes, Charsets.ISO_8859_1)

            // PDF bookmarks regex matching /Title (Chapter Name)
            val titleRegex = Regex("/Title\\s*\\(([^)]+)\\)", RegexOption.IGNORE_CASE)
            val matches = titleRegex.findAll(text).toList()

            for ((idx, match) in matches.withIndex()) {
                val title = match.groups[1]?.value?.trim() ?: continue
                if (title.isBlank() || title.length < 2) continue

                // Check for destination page nearby
                val start = match.range.last
                val window = text.substring(start, (start + 200).coerceAtMost(text.length))
                val destMatch = Regex("/(Dest|D)\\s*\\[\\s*([0-9]+)", RegexOption.IGNORE_CASE).find(window)
                val destPage = destMatch?.groups?.get(2)?.value?.toIntOrNull()

                val page = if (destPage != null && destPage in 0 until pageCount) {
                    destPage + 1
                } else {
                    (1 + (idx * pageCount / matches.size.coerceAtLeast(1))).coerceIn(1, pageCount)
                }
                chapters.add(BookChapter(title = title, pageNumber = page))
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return chapters.distinctBy { it.pageNumber to it.title }
    }

    fun generateDefaultChapters(pageCount: Int): List<BookChapter> {
        val total = pageCount.coerceAtLeast(1)
        // No bookmarks: plain page ranges (never invented chapter names).
        if (total <= 1) {
            return listOf(BookChapter("Full Document", 1))
        }
        if (total <= 5) {
            return (1..total).map { BookChapter("Page $it", it) }
        }
        val step = when { total <= 40 -> 10; total <= 200 -> 20; else -> 50 }
        val list = mutableListOf<BookChapter>()
        var p = 1
        while (p <= total) {
            val end = (p + step - 1).coerceAtMost(total)
            list.add(BookChapter("Pages $p–$end", p))
            p += step
        }
        return list
    }
}
