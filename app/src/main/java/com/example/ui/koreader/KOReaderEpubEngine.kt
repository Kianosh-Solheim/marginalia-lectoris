package com.example.ui.koreader

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.reader.BookChapter
import com.example.reader.EpubBook
import com.example.reader.EpubChapter
import com.example.reader.EpubParser
import com.example.ui.ReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * KOReader WebKit Reflow Engine:
 * Uses Android WebKit (the engine behind KOReader/Readium) to reflow EPUB XHTML chapters with
 * full typographic fidelity, multi-column pagination, and butter-smooth native text selection.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun KOReaderEpubEngine(
    document: DocumentEntity,
    initialPage: Int,
    isUiVisible: Boolean,
    activeTheme: ReaderTheme,
    fontSizeSp: Float,
    textAlignSetting: String,
    marginDp: Int,
    lineSpacingMultiplier: Float,
    fontFamilySetting: String,
    highlights: List<HighlightEntity>,
    pageTurnTrigger: SharedFlow<Int>,
    targetPageRequest: Int?,
    onToggleUi: () -> Unit,
    onChaptersLoaded: (List<BookChapter>) -> Unit,
    onPageChanged: (page: Int, total: Int, chapterTitle: String) -> Unit,
    onRequestHighlight: (quote: String, page: Int, chapterTitle: String, epubCfi: String) -> Unit,
    onFootnoteClick: (number: String, text: String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var epubBook by remember { mutableStateOf<EpubBook?>(null) }
    var currentChapterIndex by remember { mutableIntStateOf(0) }
    var currentColumnInChapter by remember { mutableIntStateOf(0) }
    var totalColumnsInChapter by remember { mutableIntStateOf(1) }
    var isLoading by remember { mutableStateOf(true) }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var lastLoadedHtmlKey by remember { mutableStateOf("") }

    // Active Selection State
    var activeSelectionText by remember { mutableStateOf("") }
    var activeSelectionCfi by remember { mutableStateOf("") }
    var isSelectionCardVisible by remember { mutableStateOf(false) }

    // Chapters list for TOC
    var bookChapters by remember { mutableStateOf<List<BookChapter>>(emptyList()) }

    // Load EPUB Book
    LaunchedEffect(document.filePath) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(document.filePath)
                if (file.exists()) {
                    val lowerPath = document.filePath.lowercase()
                    val isHtmlFile = document.fileType.equals("HTML", ignoreCase = true) ||
                        lowerPath.endsWith(".html") ||
                        lowerPath.endsWith(".htm") ||
                        lowerPath.endsWith(".xhtml")
                    if (isHtmlFile) {
                        val rawHtml = file.readText(Charsets.UTF_8)
                        val book = EpubParser.parseHtmlDocument(
                            rawHtml = rawHtml,
                            fallbackTitle = document.title,
                            fallbackAuthor = document.author
                        )
                        epubBook = book
                        var accumulatedPage = 1
                        val chList = book.chapters.map { ch ->
                            val c = BookChapter(title = ch.title, pageNumber = accumulatedPage)
                            accumulatedPage += (ch.paragraphs.size / 3).coerceAtLeast(1)
                            c
                        }
                        bookChapters = chList
                    } else if (document.fileType.equals("TXT", ignoreCase = true) || lowerPath.endsWith(".txt")) {
                        val text = file.readText(Charsets.UTF_8)
                        val paragraphs = text.split("\n\n").filter { it.isNotBlank() }
                        val htmlBody = paragraphs.joinToString("") { "<p>${it.replace("<", "&lt;").replace(">", "&gt;")}</p>" }
                        val ch = EpubChapter(
                            id = "txt-ch",
                            title = document.title,
                            content = text,
                            paragraphs = paragraphs,
                            htmlContent = htmlBody
                        )
                        epubBook = EpubBook(
                            title = document.title,
                            author = document.author,
                            description = "",
                            chapters = listOf(ch)
                        )
                        bookChapters = listOf(BookChapter(title = document.title, pageNumber = 1))
                    } else {
                        val book = file.inputStream().use { stream -> EpubParser.parseEpub(stream) }
                        epubBook = book
                        var accumulatedPage = 1
                        val chList = book.chapters.mapIndexed { idx, ch ->
                            val c = BookChapter(title = ch.title, pageNumber = accumulatedPage)
                            accumulatedPage += (ch.paragraphs.size / 3).coerceAtLeast(1)
                            c
                        }
                        bookChapters = chList
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(bookChapters) {
        if (bookChapters.isNotEmpty()) {
            onChaptersLoaded(bookChapters)
        }
    }

    val currentChapter = epubBook?.chapters?.getOrNull(currentChapterIndex)
    val totalChapters = epubBook?.chapters?.size ?: 1

    // Global page estimation
    val globalEstimatedPage = remember(currentChapterIndex, currentColumnInChapter, totalChapters) {
        val totalPagesEst = document.totalPages.coerceAtLeast(1)
        val frac = (currentChapterIndex.toFloat() + (currentColumnInChapter.toFloat() / totalColumnsInChapter.coerceAtLeast(1))) / totalChapters.coerceAtLeast(1)
        (frac * totalPagesEst).toInt().coerceIn(1, totalPagesEst)
    }

    LaunchedEffect(globalEstimatedPage, currentChapter) {
        if (currentChapter != null) {
            onPageChanged(globalEstimatedPage, document.totalPages.coerceAtLeast(1), currentChapter.title)
        }
    }

    // Handle Hardware Volume Button Trigger
    LaunchedEffect(pageTurnTrigger) {
        pageTurnTrigger.collect { direction ->
            if (direction > 0) {
                // Next page
                if (currentColumnInChapter < totalColumnsInChapter - 1) {
                    currentColumnInChapter++
                    webViewRef?.evaluateJavascript("window.scrollToPage($currentColumnInChapter);", null)
                } else if (currentChapterIndex < totalChapters - 1) {
                    currentChapterIndex++
                    currentColumnInChapter = 0
                }
            } else if (direction < 0) {
                // Previous page
                if (currentColumnInChapter > 0) {
                    currentColumnInChapter--
                    webViewRef?.evaluateJavascript("window.scrollToPage($currentColumnInChapter);", null)
                } else if (currentChapterIndex > 0) {
                    currentChapterIndex--
                    currentColumnInChapter = 0
                }
            }
        }
    }

    // Handle TOC Jump Request
    LaunchedEffect(targetPageRequest) {
        if (targetPageRequest != null && bookChapters.isNotEmpty()) {
            val reqPage = targetPageRequest + 1
            val targetChIdx = bookChapters.indexOfLast { it.pageNumber <= reqPage }.coerceAtLeast(0)
            if (targetChIdx in 0 until totalChapters) {
                currentChapterIndex = targetChIdx
                currentColumnInChapter = 0
            }
        }
    }

    // JS Bridge
    class KOReaderJSBridge {
        @JavascriptInterface
        fun onSelectionChanged(text: String) {
            coroutineScope.launch(Dispatchers.Main) {
                val trimmed = text.trim()
                activeSelectionText = trimmed
                isSelectionCardVisible = trimmed.isNotEmpty()
                activeSelectionCfi = if (trimmed.isNotEmpty()) {
                    com.example.reader.EpubCfiEngine.generateCfiForBookSelection(
                        epubBook = epubBook,
                        quote = trimmed,
                        fallbackChapterTitle = currentChapter?.title ?: "Chapter ${currentChapterIndex + 1}"
                    )
                } else ""
            }
        }

        @JavascriptInterface
        fun onContentMetrics(totalCols: Int, activeCol: Int) {
            coroutineScope.launch(Dispatchers.Main) {
                totalColumnsInChapter = totalCols.coerceAtLeast(1)
                currentColumnInChapter = activeCol.coerceIn(0, totalColumnsInChapter - 1)
            }
        }

        @JavascriptInterface
        fun onFootnoteClicked(fnNum: String, fnText: String) {
            coroutineScope.launch(Dispatchers.Main) {
                onFootnoteClick(fnNum, fnText)
            }
        }

        @JavascriptInterface
        fun onTapCenter() {
            coroutineScope.launch(Dispatchers.Main) {
                onToggleUi()
            }
        }

        @JavascriptInterface
        fun onPageNext() {
            coroutineScope.launch(Dispatchers.Main) {
                if (currentColumnInChapter < totalColumnsInChapter - 1) {
                    currentColumnInChapter++
                    webViewRef?.evaluateJavascript("window.scrollToPage($currentColumnInChapter);", null)
                } else if (currentChapterIndex < totalChapters - 1) {
                    currentChapterIndex++
                    currentColumnInChapter = 0
                }
            }
        }

        @JavascriptInterface
        fun onPagePrev() {
            coroutineScope.launch(Dispatchers.Main) {
                if (currentColumnInChapter > 0) {
                    currentColumnInChapter--
                    webViewRef?.evaluateJavascript("window.scrollToPage($currentColumnInChapter);", null)
                } else if (currentChapterIndex > 0) {
                    currentChapterIndex--
                    currentColumnInChapter = 0
                }
            }
        }
    }

    val jsBridge = remember { KOReaderJSBridge() }

    // Generate HTML for current chapter
    fun buildChapterHtml(chapter: EpubChapter?): String {
        if (chapter == null) return "<html><body></body></html>"

        val bgHex = String.format("#%06X", 0xFFFFFF and activeTheme.backgroundColor.toArgb())
        val textHex = String.format("#%06X", 0xFFFFFF and activeTheme.textColor.toArgb())
        val secondaryHex = String.format("#%06X", 0xFFFFFF and activeTheme.secondaryTextColor.toArgb())

        val fontCss = when (fontFamilySetting.uppercase()) {
            "SANS" -> "sans-serif"
            "MONO" -> "monospace"
            else -> "serif"
        }

        val alignCss = when (textAlignSetting.uppercase()) {
            "LEFT" -> "left"
            "CENTER" -> "center"
            else -> "justify"
        }

        val rawBody = if (chapter.htmlContent.isNotBlank()) {
            val bodyMatch = Regex("<body[^>]*>(.*?)</body>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(chapter.htmlContent)
            bodyMatch?.groupValues?.getOrNull(1) ?: chapter.htmlContent
        } else {
            chapter.paragraphs.joinToString("") { "<p>${it}</p>" }
        }

        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
                * {
                    box-sizing: border-box;
                    -webkit-tap-highlight-color: transparent;
                }
                html {
                    margin: 0;
                    padding: 0;
                    width: 100%;
                    height: 100%;
                    overflow: hidden;
                    background-color: $bgHex;
                }
                body {
                    margin: 0;
                    padding: 0;
                    width: 100%;
                    height: 100%;
                    overflow: hidden;
                    background-color: $bgHex;
                    color: $textHex;
                    font-family: $fontCss;
                    font-size: ${fontSizeSp}px;
                    line-height: $lineSpacingMultiplier;
                    text-align: $alignCss;
                    -webkit-user-select: text;
                    user-select: text;
                }
                #koreader-columns {
                    width: 100vw;
                    height: 100vh;
                    column-width: 100vw;
                    column-gap: 0px;
                    column-fill: auto;
                    box-sizing: border-box;
                    padding: ${marginDp}px;
                    overflow: hidden;
                }
                p {
                    margin-top: 0.6em;
                    margin-bottom: 0.6em;
                    text-indent: 1.2em;
                    word-wrap: break-word;
                    hyphens: auto;
                }
                h1, h2, h3, h4, h5, h6 {
                    font-family: $fontCss;
                    text-align: center;
                    margin-top: 1.2em;
                    margin-bottom: 0.8em;
                    page-break-after: avoid;
                    break-after: avoid;
                    color: $textHex;
                }
                img {
                    max-width: 100%;
                    height: auto;
                    display: block;
                    margin: 8px auto;
                }
                a {
                    color: $textHex;
                    text-decoration: underline;
                }
                .koreader-highlight {
                    background-color: #FFEB3B !important;
                    color: #18181B !important;
                    border-radius: 2px;
                    padding: 1px 2px;
                }
                blockquote {
                    margin: 1em 16px;
                    padding-left: 12px;
                    border-left: 3px solid $secondaryHex;
                    font-style: italic;
                    color: $secondaryHex;
                }
            </style>
        </head>
        <body>
            <div id="koreader-columns">
                <h2>${chapter.title}</h2>
                $rawBody
            </div>
            <script>
                function updateMetrics() {
                    const el = document.getElementById('koreader-columns');
                    const w = window.innerWidth || document.documentElement.clientWidth;
                    const totalCols = Math.max(1, Math.round(el.scrollWidth / w));
                    const currentX = window.scrollX || window.pageXOffset || 0;
                    const activeCol = Math.round(currentX / w);
                    if (window.KOReader) {
                        window.KOReader.onContentMetrics(totalCols, activeCol);
                    }
                }

                window.scrollToPage = function(colIndex) {
                    const w = window.innerWidth || document.documentElement.clientWidth;
                    window.scrollTo({ left: colIndex * w, top: 0, behavior: 'instant' });
                    updateMetrics();
                };

                window.expandSelectionWord = function(direction) {
                    const sel = window.getSelection();
                    if (!sel || sel.rangeCount === 0) return;
                    if (direction > 0) {
                        sel.modify('extend', 'forward', 'word');
                    } else {
                        sel.modify('extend', 'backward', 'word');
                    }
                    if (window.KOReader) {
                        window.KOReader.onSelectionChanged(sel.toString());
                    }
                };

                document.addEventListener('selectionchange', () => {
                    const sel = window.getSelection();
                    const text = sel ? sel.toString().trim() : '';
                    if (window.KOReader) {
                        window.KOReader.onSelectionChanged(text);
                    }
                });

                document.addEventListener('click', (e) => {
                    const sel = window.getSelection();
                    if (sel && sel.toString().trim().length > 0) {
                        return; // User was selecting text
                    }
                    const w = window.innerWidth || document.documentElement.clientWidth;
                    const x = e.clientX;
                    if (x < w * 0.2) {
                        if (window.KOReader) window.KOReader.onPagePrev();
                    } else if (x > w * 0.8) {
                        if (window.KOReader) window.KOReader.onPageNext();
                    } else {
                        if (window.KOReader) window.KOReader.onTapCenter();
                    }
                });

                window.addEventListener('load', () => {
                    updateMetrics();
                    setTimeout(updateMetrics, 200);
                });
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(activeTheme.backgroundColor)
    ) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = activeTheme.textColor)
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            textZoom = 100
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            cacheMode = WebSettings.LOAD_NO_CACHE
                        }
                        setBackgroundColor(activeTheme.backgroundColor.toArgb())
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        overScrollMode = View.OVER_SCROLL_NEVER

                        addJavascriptInterface(jsBridge, "KOReader")
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                view?.evaluateJavascript("window.scrollToPage($currentColumnInChapter);", null)
                            }
                        }
                        webViewRef = this
                    }
                },
                update = { webView ->
                    webViewRef = webView
                    webView.setBackgroundColor(activeTheme.backgroundColor.toArgb())
                    val currentKey = "${currentChapter?.id ?: ""}_${currentChapterIndex}_${fontSizeSp}_${activeTheme.name}_${textAlignSetting}_${lineSpacingMultiplier}_${fontFamilySetting}_${marginDp}"
                    if (currentKey != lastLoadedHtmlKey) {
                        lastLoadedHtmlKey = currentKey
                        val html = buildChapterHtml(currentChapter)
                        webView.loadDataWithBaseURL("file://${document.filePath}", html, "text/html", "UTF-8", null)
                    } else {
                        webView.evaluateJavascript("window.scrollToPage($currentColumnInChapter);", null)
                    }
                },
                onRelease = { webView ->
                    try {
                        webView.stopLoading()
                        webView.loadUrl("about:blank")
                        webView.destroy()
                    } catch (_: Exception) {}
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Floating KOReader Selection Action Bar (when user drags and selects text)
        AnimatedVisibility(
            visible = isSelectionCardVisible && activeSelectionText.isNotBlank(),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (isUiVisible) 80.dp else 24.dp, start = 16.dp, end = 16.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(BorderStroke(1.dp, Color.Black), RoundedCornerShape(12.dp)),
                color = Color(0xFF18181B),
                shape = RoundedCornerShape(12.dp),
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Selected Excerpt",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFA1A1AA),
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = {
                                isSelectionCardVisible = false
                                activeSelectionText = ""
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }

                    Text(
                        text = "\"$activeSelectionText\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // - Word
                        OutlinedButton(
                            onClick = {
                                webViewRef?.evaluateJavascript("window.expandSelectionWord(-1);", null)
                            },
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            border = BorderStroke(1.dp, Color(0xFF52525B))
                        ) {
                            Text("- Word", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        // + Word
                        OutlinedButton(
                            onClick = {
                                webViewRef?.evaluateJavascript("window.expandSelectionWord(1);", null)
                            },
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            border = BorderStroke(1.dp, Color(0xFF52525B))
                        ) {
                            Text("+ Word", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Copy
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Quote", activeSelectionText))
                                Toast.makeText(context, "Quote copied to clipboard", Toast.LENGTH_SHORT).show()
                                isSelectionCardVisible = false
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Quote", tint = Color.White, modifier = Modifier.size(16.dp))
                        }

                        // Highlight & Note
                        Button(
                            onClick = {
                                val cfi = activeSelectionCfi.ifBlank {
                                    com.example.reader.EpubCfiEngine.generateCfiForBookSelection(
                                        epubBook = epubBook,
                                        quote = activeSelectionText,
                                        fallbackChapterTitle = currentChapter?.title ?: "Chapter ${currentChapterIndex + 1}"
                                    )
                                }
                                onRequestHighlight(activeSelectionText, globalEstimatedPage, currentChapter?.title ?: "Chapter ${currentChapterIndex + 1}", cfi)
                                isSelectionCardVisible = false
                                activeSelectionCfi = ""
                            },
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFEB3B), contentColor = Color.Black)
                        ) {
                            Text("✏️ Highlight", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
