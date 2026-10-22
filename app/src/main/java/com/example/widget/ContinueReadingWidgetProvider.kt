package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class ContinueWidgetSizeTier(
    val layoutResId: Int,
    val minSize: SizeF,
    val label: String,
    val displayLabel: String = label
) {
    COMPACT(R.layout.widget_continue_compact, SizeF(110f, 90f), "Compact (2×2)"),
    MEDIUM(R.layout.widget_continue_medium, SizeF(250f, 130f), "Medium (4×2)"),
    EXPANDED(R.layout.widget_continue_expanded, SizeF(250f, 220f), "Expanded (4×3)"),
    TABLET_WIDE(R.layout.widget_continue_tablet_wide, SizeF(480f, 130f), "Tablet Wide (6×2)"),
    TABLET_LARGE(R.layout.widget_continue_tablet_large, SizeF(480f, 240f), "Tablet Large (6×3)")
}

enum class ContinueLayout(
    val layoutResId: Int,
    val minSize: SizeF,
    val label: String
) {
    COMPACT(R.layout.widget_continue_compact, SizeF(110f, 90f), "Compact (2×2)"),
    MEDIUM(R.layout.widget_continue_medium, SizeF(250f, 130f), "Medium (4×2)"),
    EXPANDED(R.layout.widget_continue_expanded, SizeF(250f, 220f), "Expanded (4×3)"),
    TABLET_WIDE(R.layout.widget_continue_tablet_wide, SizeF(480f, 130f), "Tablet Wide (6×2)"),
    TABLET_LARGE(R.layout.widget_continue_tablet_large, SizeF(480f, 240f), "Tablet Large (6×3)"),
    TABLET_XL(R.layout.widget_cr_tablet_xl, SizeF(500f, 420f), "Tablet XL (6×5)")
}

class ContinueReadingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, appWidgetId, null)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateSingleWidget(context, appWidgetManager, appWidgetId, newOptions)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        when (intent.action) {
            ACTION_REFRESH_CONTINUE_WIDGET -> {
                refreshAllWidgets(context)
            }
            ACTION_CYCLE_CONTINUE_FILTER -> {
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val current = getFilterForWidget(context, widgetId)
                    val next = when (current) {
                        ContinueFilter.ALL -> ContinueFilter.BOOKS
                        ContinueFilter.BOOKS -> ContinueFilter.ARTICLES
                        ContinueFilter.ARTICLES -> ContinueFilter.ALL
                    }
                    setFilterForWidget(context, widgetId, next)
                    setShelfOffset(context, widgetId, isArticles = false, offset = 0)
                    setShelfOffset(context, widgetId, isArticles = true, offset = 0)
                    val manager = AppWidgetManager.getInstance(context)
                    updateSingleWidget(context, manager, widgetId, null)
                } else {
                    refreshAllWidgets(context)
                }
            }
            ACTION_SCROLL_BOOKS_PREV -> {
                shiftShelfOffset(context, widgetId, isArticles = false, delta = -1)
            }
            ACTION_SCROLL_BOOKS_NEXT -> {
                shiftShelfOffset(context, widgetId, isArticles = false, delta = 1)
            }
            ACTION_SCROLL_ARTICLES_PREV -> {
                shiftShelfOffset(context, widgetId, isArticles = true, delta = -1)
            }
            ACTION_SCROLL_ARTICLES_NEXT -> {
                shiftShelfOffset(context, widgetId, isArticles = true, delta = 1)
            }
        }
    }

    private fun shiftShelfOffset(context: Context, widgetId: Int, isArticles: Boolean, delta: Int) {
        val current = getShelfOffset(context, widgetId, isArticles)
        setShelfOffset(context, widgetId, isArticles, (current + delta).coerceAtLeast(0))
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val manager = AppWidgetManager.getInstance(context)
            updateSingleWidget(context, manager, widgetId, null)
        } else {
            refreshAllWidgets(context)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        for (id in appWidgetIds) {
            prefs.remove(KEY_PREFIX_FILTER + id)
            prefs.remove(KEY_PREFIX_BOOKS_OFFSET + id)
            prefs.remove(KEY_PREFIX_ARTICLES_OFFSET + id)
        }
        prefs.apply()
    }

    companion object {
        const val ACTION_REFRESH_CONTINUE_WIDGET = "com.example.widget.ACTION_REFRESH_CONTINUE_WIDGET"
        const val ACTION_CYCLE_CONTINUE_FILTER = "com.example.widget.ACTION_CYCLE_CONTINUE_FILTER"
        const val ACTION_SCROLL_BOOKS_PREV = "com.example.widget.ACTION_SCROLL_BOOKS_PREV"
        const val ACTION_SCROLL_BOOKS_NEXT = "com.example.widget.ACTION_SCROLL_BOOKS_NEXT"
        const val ACTION_SCROLL_ARTICLES_PREV = "com.example.widget.ACTION_SCROLL_ARTICLES_PREV"
        const val ACTION_SCROLL_ARTICLES_NEXT = "com.example.widget.ACTION_SCROLL_ARTICLES_NEXT"

        private const val PREFS_NAME = "continue_reading_widget_prefs"
        private const val KEY_PREFIX_FILTER = "filter_widget_"
        private const val KEY_DEFAULT_FILTER = "filter_default"
        private const val KEY_PREFIX_BOOKS_OFFSET = "books_offset_widget_"
        private const val KEY_PREFIX_ARTICLES_OFFSET = "articles_offset_widget_"

        // Shared cover bitmap cache keyed by "${doc.id}_${width}x${height}_${coverUri}_${fileType}_${category}"
        // Ensures one bitmap per document is shared across layouts.
        private val sharedCoverBitmapCache = ConcurrentHashMap<String, Bitmap>()

        @Volatile
        private var useReducedXlBitmapSize: Boolean = false

        @Volatile
        private var lastRefreshAtMs: Long = 0L

        /** Drops the cached cover bitmaps of one document, after its cover image changed. */
        fun forgetCover(documentId: Long) {
            val prefix = "${documentId}_"
            sharedCoverBitmapCache.keys.toList().filter { it.startsWith(prefix) }.forEach { sharedCoverBitmapCache.remove(it) }
        }

        fun getFilterForWidget(context: Context, appWidgetId: Int): ContinueFilter {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                KEY_PREFIX_FILTER + appWidgetId
            } else {
                KEY_DEFAULT_FILTER
            }
            val raw = prefs.getString(key, null) ?: prefs.getString(KEY_DEFAULT_FILTER, ContinueFilter.ALL.name)
            return ContinueFilter.fromName(raw)
        }

        fun setFilterForWidget(context: Context, appWidgetId: Int, filter: ContinueFilter) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                prefs.putString(KEY_PREFIX_FILTER + appWidgetId, filter.name)
            }
            prefs.putString(KEY_DEFAULT_FILTER, filter.name)
            prefs.apply()
        }

        fun getShelfOffset(context: Context, appWidgetId: Int, isArticles: Boolean): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val prefix = if (isArticles) KEY_PREFIX_ARTICLES_OFFSET else KEY_PREFIX_BOOKS_OFFSET
            return prefs.getInt(prefix + appWidgetId, 0).coerceAtLeast(0)
        }

        fun setShelfOffset(context: Context, appWidgetId: Int, isArticles: Boolean, offset: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            val prefix = if (isArticles) KEY_PREFIX_ARTICLES_OFFSET else KEY_PREFIX_BOOKS_OFFSET
            prefs.putInt(prefix + appWidgetId, offset.coerceAtLeast(0))
            prefs.apply()
        }

        /**
         * Resolves the 6-tier layout including ContinueLayout.TABLET_XL with minSize SizeF(500f, 420f),
         * chosen when the widget is at least 420 dp tall.
         */
        fun resolveLayout(minWidthDp: Int, minHeightDp: Int): ContinueLayout {
            return when {
                minWidthDp >= 500 && minHeightDp >= 420 -> ContinueLayout.TABLET_XL
                minWidthDp >= 420 && minHeightDp >= 240 -> ContinueLayout.TABLET_LARGE
                minWidthDp >= 420 -> ContinueLayout.TABLET_WIDE
                minWidthDp < 230 || minHeightDp < 100 -> ContinueLayout.COMPACT
                minHeightDp >= 200 -> ContinueLayout.EXPANDED
                else -> ContinueLayout.MEDIUM
            }
        }

        fun selectLayoutForSize(widthDp: Float, heightDp: Float): ContinueLayout {
            return resolveLayout(widthDp.toInt(), heightDp.toInt())
        }

        fun resolveSizeTier(minWidthDp: Int, minHeightDp: Int): ContinueWidgetSizeTier {
            return when (resolveLayout(minWidthDp, minHeightDp)) {
                ContinueLayout.COMPACT -> ContinueWidgetSizeTier.COMPACT
                ContinueLayout.MEDIUM -> ContinueWidgetSizeTier.MEDIUM
                ContinueLayout.EXPANDED -> ContinueWidgetSizeTier.EXPANDED
                ContinueLayout.TABLET_WIDE -> ContinueWidgetSizeTier.TABLET_WIDE
                ContinueLayout.TABLET_LARGE, ContinueLayout.TABLET_XL -> ContinueWidgetSizeTier.TABLET_LARGE
            }
        }

        fun refreshAll(context: Context) {
            refreshAllWidgets(context)
        }

        fun refreshAllThrottled(context: Context, minIntervalMs: Long = 1500L) {
            val now = System.currentTimeMillis()
            if (now - lastRefreshAtMs >= minIntervalMs) {
                lastRefreshAtMs = now
                refreshAllWidgets(context)
            }
        }

        fun refreshAllWidgets(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, ContinueReadingWidgetProvider::class.java)
                val ids = manager.getAppWidgetIds(componentName)
                if (ids != null && ids.isNotEmpty()) {
                    for (id in ids) {
                        updateSingleWidget(context, manager, id, null)
                    }
                }
            } catch (_: Throwable) {
                // Ignore if AppWidgetManager is not available in test/headless environment
            }
        }

        fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            optionsBundle: Bundle? = null
        ) {
            try {
                val views = buildRemoteViewsForWidget(context, appWidgetManager, appWidgetId, optionsBundle)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Throwable) {
                val msg = e.message.orEmpty()
                if (!useReducedXlBitmapSize && (e is IllegalArgumentException || msg.contains("bitmap", ignoreCase = true) || msg.contains("TransactionTooLarge", ignoreCase = true))) {
                    // Fallback from 300x420 to 240x336 if a RemoteViews bitmap-size error appears
                    useReducedXlBitmapSize = true
                    sharedCoverBitmapCache.clear()
                    val retryViews = buildRemoteViewsForWidget(context, appWidgetManager, appWidgetId, optionsBundle)
                    appWidgetManager.updateAppWidget(appWidgetId, retryViews)
                } else {
                    throw e
                }
            }
        }

        /**
         * Shares one cover bitmap per document across layouts.
         * For XL / large tablet cover bitmaps uses 300x420 px (falling back to 240x336 if needed).
         * For articles whose file type is PDF and that have no coverUri, uses ArticleThumbnailRenderer
         * instead of the downscaled first page.
         */
        fun loadOrCreateSharedCoverBitmap(
            context: Context,
            doc: DocumentEntity,
            layout: ContinueLayout
        ): Bitmap {
            val isXl = layout == ContinueLayout.TABLET_XL || layout == ContinueLayout.TABLET_LARGE
            val maxW = when {
                useReducedXlBitmapSize -> 240
                isXl -> 300
                else -> 300
            }
            val maxH = when {
                useReducedXlBitmapSize -> 336
                isXl -> 420
                else -> 420
            }
            val cacheKey = "${doc.id}_${maxW}x${maxH}_${doc.coverUri.orEmpty()}_${doc.fileType}_${doc.category}"
            sharedCoverBitmapCache[cacheKey]?.let { cached ->
                if (!cached.isRecycled) return cached
            }

            val isArticle = ContinueReadingSelector.isArticle(doc)
            val isPdf = doc.fileType.equals("PDF", ignoreCase = true)
            val hasCustomCover = !doc.coverUri.isNullOrBlank()

            val generated: Bitmap = if (isArticle && isPdf && !hasCustomCover) {
                ArticleThumbnailRenderer.render(context, doc, maxW, maxH)
            } else if (hasCustomCover) {
                decodeCustomCoverFit(context, doc.coverUri!!, maxW, maxH)
                    ?: if (isArticle) {
                        ArticleThumbnailRenderer.render(context, doc, maxW, maxH)
                    } else {
                        generateBookMonogramBitmap(doc, maxW, maxH)
                    }
            } else if (!isArticle && isPdf) {
                renderFirstPdfPageForBook(context, doc.filePath, maxW, maxH)
                    ?: generateBookMonogramBitmap(doc, maxW, maxH)
            } else if (isArticle) {
                ArticleThumbnailRenderer.render(context, doc, maxW, maxH)
            } else {
                val baseBmp = PinnedBooksWidgetProvider.loadOrGenerateWidgetCoverBitmap(context, doc)
                scalePreservingAspectRatio(baseBmp, maxW, maxH)
            }

            sharedCoverBitmapCache[cacheKey] = generated
            return generated
        }

        private fun scalePreservingAspectRatio(src: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
            val srcW = src.width.coerceAtLeast(1)
            val srcH = src.height.coerceAtLeast(1)
            val ratio = minOf(maxWidth.toFloat() / srcW.toFloat(), maxHeight.toFloat() / srcH.toFloat())
            val outW = (srcW * ratio).toInt().coerceAtLeast(1)
            val outH = (srcH * ratio).toInt().coerceAtLeast(1)
            return if (outW == src.width && outH == src.height) {
                src
            } else {
                Bitmap.createScaledBitmap(src, outW, outH, true)
            }
        }

        private fun generateBookMonogramBitmap(doc: DocumentEntity, widthPx: Int, heightPx: Int): Bitmap {
            val ratio = if (doc.coverAspectRatio > 0.2f && doc.coverAspectRatio < 2.5f) {
                doc.coverAspectRatio
            } else {
                widthPx.toFloat() / heightPx.toFloat()
            }
            val w = (heightPx * ratio).toInt().coerceIn(120, widthPx)
            val h = heightPx
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.parseColor("#FAF8F2"))

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#111111")
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            canvas.drawRect(2f, 2f, w - 2f, h - 2f, borderPaint)

            val letter = doc.title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "B"
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#111111")
                textSize = w * 0.42f
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val bounds = Rect()
            textPaint.getTextBounds(letter, 0, letter.length, bounds)
            val centerY = (h / 2f) - bounds.exactCenterY()
            canvas.drawText(letter, w / 2f, centerY, textPaint)
            return bitmap
        }

        private fun decodeCustomCoverFit(
            context: Context,
            coverUriString: String,
            targetWidth: Int,
            targetHeight: Int
        ): Bitmap? {
            return try {
                val parsed = Uri.parse(coverUriString)
                val rawBitmap: Bitmap? = when (parsed.scheme) {
                    "android.resource" -> {
                        val resName = parsed.lastPathSegment ?: return null
                        val resId = context.resources.getIdentifier(resName, "drawable", context.packageName)
                        if (resId != 0) BitmapFactory.decodeResource(context.resources, resId) else null
                    }
                    "file" -> {
                        val path = parsed.path ?: return null
                        if (File(path).exists()) BitmapFactory.decodeFile(path) else null
                    }
                    "content" -> {
                        context.contentResolver.openInputStream(parsed)?.use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                    }
                    else -> {
                        val f = File(coverUriString)
                        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
                    }
                }
                rawBitmap?.let { bmp ->
                    scalePreservingAspectRatio(bmp, targetWidth, targetHeight)
                }
            } catch (_: Throwable) {
                null
            }
        }

        private fun renderFirstPdfPageForBook(
            context: Context,
            uriString: String,
            targetWidth: Int,
            targetHeight: Int
        ): Bitmap? {
            var pfd: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            var page: PdfRenderer.Page? = null
            return try {
                val uri = Uri.parse(uriString)
                pfd = when (uri.scheme) {
                    "file" -> {
                        val path = uri.path ?: return null
                        val file = File(path)
                        if (!file.exists()) return null
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    }
                    "content" -> context.contentResolver.openFileDescriptor(uri, "r")
                    else -> {
                        val file = File(uriString)
                        if (file.exists()) ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) else null
                    }
                } ?: return null

                renderer = PdfRenderer(pfd)
                if (renderer.pageCount <= 0) return null
                page = renderer.openPage(0)

                val pageW = page.width.coerceAtLeast(1)
                val pageH = page.height.coerceAtLeast(1)
                val scale = minOf(targetWidth.toFloat() / pageW.toFloat(), targetHeight.toFloat() / pageH.toFloat())
                val bmpW = (pageW * scale).toInt().coerceAtLeast(1)
                val bmpH = (pageH * scale).toInt().coerceAtLeast(1)

                val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } catch (_: Throwable) {
                null
            } finally {
                try { page?.close() } catch (_: Throwable) {}
                try { renderer?.close() } catch (_: Throwable) {}
                try { pfd?.close() } catch (_: Throwable) {}
            }
        }

        fun buildRemoteViewsForWidget(
            context: Context,
            appWidgetManager: AppWidgetManager?,
            appWidgetId: Int,
            optionsBundle: Bundle? = null
        ): RemoteViews {
            val options = optionsBundle ?: appWidgetManager?.getAppWidgetOptions(appWidgetId) ?: Bundle()
            val minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260).coerceAtLeast(110)
            val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140).coerceAtLeast(90)

            val layout = resolveLayout(minWidthDp, minHeightDp)
            val filter = getFilterForWidget(context, appWidgetId)

            val allDocs: List<DocumentEntity> = runBlocking {
                try {
                    com.example.reader.FolderBooks.init(context)
                    com.example.reader.FolderBooks.visible(AppDatabase.getInstance(context).documentDao().getAllDocumentsOrderedByLastReadSnapshot())
                } catch (_: Throwable) {
                    emptyList()
                }
            }

            // Use selectFeaturedAndShelves so the top displays the 2 most recently read items
            // regardless of type, while Recent Books and Recent Articles shelves are populated beneath.
            val selection = when (layout) {
                ContinueLayout.COMPACT -> ContinueReadingSelector.select(allDocs, filter, 0)
                else -> ContinueReadingSelector.selectFeaturedAndShelves(allDocs, filter, shelfMaxItems = 12)
            }

            val views = RemoteViews(context.packageName, layout.layoutResId)

            // Filter chip cycling PendingIntent
            val cycleFilterIntent = Intent(context, ContinueReadingWidgetProvider::class.java).apply {
                action = ACTION_CYCLE_CONTINUE_FILTER
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val cyclePendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId + 8000,
                cycleFilterIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val filterText = if (layout == ContinueLayout.COMPACT) {
                filter.name
            } else {
                "FILTER: ${filter.name}"
            }
            views.setTextViewText(R.id.continue_filter_chip, filterText)
            views.setOnClickPendingIntent(R.id.continue_filter_chip, cyclePendingIntent)

            val primary = selection.primary
            if (primary == null) {
                views.setViewVisibility(R.id.continue_empty_container, View.VISIBLE)
                views.setViewVisibility(R.id.continue_primary_container, View.GONE)

                val openLibIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                val openLibPi = PendingIntent.getActivity(
                    context,
                    appWidgetId + 8500,
                    openLibIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.continue_empty_container, openLibPi)
                views.setOnClickPendingIntent(R.id.continue_empty_action, openLibPi)
                return views
            }

            views.setViewVisibility(R.id.continue_empty_container, View.GONE)
            views.setViewVisibility(R.id.continue_primary_container, View.VISIBLE)

            // Bind Featured Item 1
            bindDocumentToViews(
                context = context,
                views = views,
                doc = primary,
                layout = layout,
                isSecondary = false,
                requestCodeBase = appWidgetId * 31 + 10000
            )

            // Bind Featured Item 2 on all non-compact layouts (side-by-side on wide, stacked on narrow)
            if (layout != ContinueLayout.COMPACT) {
                val secondary = selection.secondary ?: selection.recent.firstOrNull()
                val showSecondary = secondary != null && (layout != ContinueLayout.MEDIUM || minHeightDp >= 165)
                if (showSecondary && secondary != null) {
                    views.setViewVisibility(R.id.continue_secondary_divider, View.VISIBLE)
                    views.setViewVisibility(R.id.continue_secondary_card, View.VISIBLE)
                    bindDocumentToViews(
                        context = context,
                        views = views,
                        doc = secondary,
                        layout = layout,
                        isSecondary = true,
                        requestCodeBase = appWidgetId * 31 + 20000
                    )
                } else {
                    views.setViewVisibility(R.id.continue_secondary_divider, View.GONE)
                    views.setViewVisibility(R.id.continue_secondary_card, View.GONE)
                }

                // Populate Horizontally Scrollable Shelves:
                // Row 1: RECENT BOOKS
                // Row 2: RECENT ARTICLES
                bindScrollableShelves(
                    context = context,
                    views = views,
                    selection = selection,
                    layout = layout,
                    minWidthDp = minWidthDp,
                    appWidgetId = appWidgetId
                )
            }

            return views
        }

        private fun buildScrollActionPendingIntent(
            context: Context,
            appWidgetId: Int,
            action: String,
            requestCodeOffset: Int
        ): PendingIntent {
            val intent = Intent(context, ContinueReadingWidgetProvider::class.java).apply {
                this.action = action
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            return PendingIntent.getBroadcast(
                context,
                appWidgetId * 31 + requestCodeOffset,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun bindScrollableShelves(
            context: Context,
            views: RemoteViews,
            selection: Selection,
            layout: ContinueLayout,
            minWidthDp: Int,
            appWidgetId: Int
        ) {
            val books = selection.recentBooks
            val articles = selection.recentArticles

            // Calculate how many covers fit comfortably across the available width (~76dp per cover + 8dp gap)
            val visibleCoversPerRow = ((minWidthDp - 24) / 80).coerceIn(3, 8)

            // Row 1: RECENT BOOKS
            if (books.isEmpty()) {
                views.setViewVisibility(R.id.continue_shelf_books_section, View.GONE)
            } else {
                views.setViewVisibility(R.id.continue_shelf_books_section, View.VISIBLE)
                views.removeAllViews(R.id.continue_shelf_books_container)

                val maxOffset = (books.size - visibleCoversPerRow).coerceAtLeast(0)
                val offset = getShelfOffset(context, appWidgetId, isArticles = false).coerceIn(0, maxOffset)
                val visibleBooks = books.drop(offset).take(visibleCoversPerRow)

                val showPager = books.size > visibleCoversPerRow
                views.setViewVisibility(R.id.continue_shelf_books_prev, if (showPager) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.continue_shelf_books_next, if (showPager) View.VISIBLE else View.GONE)
                views.setOnClickPendingIntent(
                    R.id.continue_shelf_books_prev,
                    buildScrollActionPendingIntent(context, appWidgetId, ACTION_SCROLL_BOOKS_PREV, 9101)
                )
                views.setOnClickPendingIntent(
                    R.id.continue_shelf_books_next,
                    buildScrollActionPendingIntent(context, appWidgetId, ACTION_SCROLL_BOOKS_NEXT, 9102)
                )

                visibleBooks.forEachIndexed { idx, bookDoc ->
                    val itemViews = buildShelfItemRemoteViews(
                        context = context,
                        doc = bookDoc,
                        layout = layout,
                        requestCode = appWidgetId * 31 + 40000 + offset + idx
                    )
                    views.addView(R.id.continue_shelf_books_container, itemViews)
                }
            }

            // Row 2: RECENT ARTICLES
            if (articles.isEmpty()) {
                views.setViewVisibility(R.id.continue_shelf_articles_section, View.GONE)
            } else {
                views.setViewVisibility(R.id.continue_shelf_articles_section, View.VISIBLE)
                views.removeAllViews(R.id.continue_shelf_articles_container)

                val maxOffset = (articles.size - visibleCoversPerRow).coerceAtLeast(0)
                val offset = getShelfOffset(context, appWidgetId, isArticles = true).coerceIn(0, maxOffset)
                val visibleArticles = articles.drop(offset).take(visibleCoversPerRow)

                val showPager = articles.size > visibleCoversPerRow
                views.setViewVisibility(R.id.continue_shelf_articles_prev, if (showPager) View.VISIBLE else View.GONE)
                views.setViewVisibility(R.id.continue_shelf_articles_next, if (showPager) View.VISIBLE else View.GONE)
                views.setOnClickPendingIntent(
                    R.id.continue_shelf_articles_prev,
                    buildScrollActionPendingIntent(context, appWidgetId, ACTION_SCROLL_ARTICLES_PREV, 9201)
                )
                views.setOnClickPendingIntent(
                    R.id.continue_shelf_articles_next,
                    buildScrollActionPendingIntent(context, appWidgetId, ACTION_SCROLL_ARTICLES_NEXT, 9202)
                )

                visibleArticles.forEachIndexed { idx, articleDoc ->
                    val itemViews = buildShelfItemRemoteViews(
                        context = context,
                        doc = articleDoc,
                        layout = layout,
                        requestCode = appWidgetId * 31 + 45000 + offset + idx
                    )
                    views.addView(R.id.continue_shelf_articles_container, itemViews)
                }
            }

            // Keep legacy recents row hidden so reading info is not duplicated at the bottom
            views.setViewVisibility(R.id.continue_recents_row, View.GONE)
        }

        private fun buildShelfItemRemoteViews(
            context: Context,
            doc: DocumentEntity,
            layout: ContinueLayout,
            requestCode: Int
        ): RemoteViews {
            val itemRv = RemoteViews(context.packageName, R.layout.widget_continue_shelf_item)
            val coverBmp = loadOrCreateSharedCoverBitmap(context, doc, layout)
            val progressBmp = renderEInkProgressBitmap(doc.readingProgress, widthPx = 100, heightPx = 8)
            val resumePi = buildResumePendingIntent(context, doc, requestCode)

            itemRv.setImageViewBitmap(R.id.shelf_item_cover, coverBmp)
            itemRv.setImageViewBitmap(R.id.shelf_item_progress, progressBmp)
            itemRv.setContentDescription(R.id.shelf_item_cover, doc.title)
            itemRv.setOnClickPendingIntent(R.id.shelf_item_root, resumePi)
            itemRv.setOnClickPendingIntent(R.id.shelf_item_cover, resumePi)
            return itemRv
        }

        private fun bindDocumentToViews(
            context: Context,
            views: RemoteViews,
            doc: DocumentEntity,
            layout: ContinueLayout,
            isSecondary: Boolean,
            requestCodeBase: Int
        ) {
            runCatching { com.example.reader.BibStore.init(context); com.example.reader.EditedVolumes.init(context) }
            val isArticle = ContinueReadingSelector.isArticle(doc)
            val badgeLabel = when {
                isArticle -> "ARTICLE · ${doc.fileType.uppercase()}"
                doc.fileType.equals("EPUB", ignoreCase = true) -> doc.epubVersion.ifBlank { "EPUB" }
                else -> doc.fileType.uppercase()
            }
            val pct = ContinueFormatter.percent(doc)
            val timeLeft = ContinueFormatter.timeLeft(doc)
            val locus = ContinueFormatter.locus(doc)
            val snippet = ContinueFormatter.snippet(doc)
            val authorDisplay = ContinueFormatter.author(doc).trim().ifBlank {
                if (isArticle) "Saved Article" else "Unknown Author"
            }

            val coverBmp = loadOrCreateSharedCoverBitmap(context, doc, layout)
            val progressBmp = renderEInkProgressBitmap(doc.readingProgress)
            val resumePi = buildResumePendingIntent(context, doc, requestCodeBase)

            // E-ink friendly: black outline only on tags (no colour fills)
            val eInkOutlineTagRes = R.drawable.bg_continue_tag_outline

            if (!isSecondary) {
                views.setImageViewBitmap(R.id.continue_primary_cover, coverBmp)
                views.setTextViewText(R.id.continue_primary_badge, badgeLabel)
                views.setInt(
                    R.id.continue_primary_badge,
                    "setBackgroundResource",
                    eInkOutlineTagRes
                )
                views.setTextViewText(R.id.continue_primary_title, doc.title)
                views.setTextViewText(R.id.continue_primary_author, authorDisplay)
                views.setTextViewText(R.id.continue_primary_percent, "$pct%")
                views.setTextViewText(R.id.continue_primary_time_left, timeLeft)
                views.setViewVisibility(
                    R.id.continue_primary_time_left,
                    if (timeLeft.isNotBlank()) View.VISIBLE else View.GONE
                )
                views.setTextViewText(R.id.continue_primary_locus, locus)
                views.setTextViewText(R.id.continue_primary_snippet, snippet)
                views.setViewVisibility(
                    R.id.continue_primary_snippet,
                    if (snippet.isNotBlank()) View.VISIBLE else View.GONE
                )
                views.setImageViewBitmap(R.id.continue_primary_progress_img, progressBmp)

                if (layout != ContinueLayout.COMPACT) {
                    views.setOnClickPendingIntent(R.id.continue_primary_cta, resumePi)
                    views.setOnClickPendingIntent(R.id.continue_primary_card, resumePi)
                } else {
                    views.setOnClickPendingIntent(R.id.continue_primary_container, resumePi)
                }

                views.setOnClickPendingIntent(R.id.continue_primary_cover, resumePi)
                views.setOnClickPendingIntent(R.id.continue_primary_title, resumePi)
                views.setOnClickPendingIntent(R.id.continue_primary_author, resumePi)
                views.setOnClickPendingIntent(R.id.continue_primary_snippet, resumePi)
            } else {
                views.setImageViewBitmap(R.id.continue_secondary_cover, coverBmp)
                views.setTextViewText(R.id.continue_secondary_badge, badgeLabel)
                views.setInt(
                    R.id.continue_secondary_badge,
                    "setBackgroundResource",
                    eInkOutlineTagRes
                )
                views.setTextViewText(R.id.continue_secondary_title, doc.title)
                views.setTextViewText(R.id.continue_secondary_author, authorDisplay)
                views.setTextViewText(R.id.continue_secondary_percent, "$pct%")
                views.setTextViewText(R.id.continue_secondary_time_left, timeLeft)
                views.setViewVisibility(
                    R.id.continue_secondary_time_left,
                    if (timeLeft.isNotBlank()) View.VISIBLE else View.GONE
                )
                views.setTextViewText(R.id.continue_secondary_locus, locus)
                views.setTextViewText(R.id.continue_secondary_snippet, snippet)
                views.setViewVisibility(
                    R.id.continue_secondary_snippet,
                    if (snippet.isNotBlank()) View.VISIBLE else View.GONE
                )
                views.setImageViewBitmap(R.id.continue_secondary_progress_img, progressBmp)

                views.setOnClickPendingIntent(R.id.continue_secondary_cta, resumePi)
                views.setOnClickPendingIntent(R.id.continue_secondary_card, resumePi)
                views.setOnClickPendingIntent(R.id.continue_secondary_cover, resumePi)
                views.setOnClickPendingIntent(R.id.continue_secondary_title, resumePi)
                views.setOnClickPendingIntent(R.id.continue_secondary_author, resumePi)
                views.setOnClickPendingIntent(R.id.continue_secondary_snippet, resumePi)
            }
        }

        fun buildResumeIntent(context: Context, doc: DocumentEntity): Intent {
            return Intent(context, MainActivity::class.java).apply {
                action = PinnedBooksWidgetProvider.ACTION_OPEN_BOOK_FROM_WIDGET
                putExtra(PinnedBooksWidgetProvider.EXTRA_OPEN_DOCUMENT_ID, doc.id)
                putExtra("extra_resume_cfi", doc.lastPositionCfi)
                putExtra("extra_resume_paragraph", doc.lastPositionParagraph)
                putExtra("extra_resume_offset", doc.lastPositionOffset)
                putExtra("extra_resume_loc", doc.lastPositionLoc)
                putExtra("extra_resume_page", doc.currentPage.coerceAtLeast(1))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }

        private fun buildResumePendingIntent(context: Context, doc: DocumentEntity, requestCode: Int): PendingIntent {
            val intent = buildResumeIntent(context, doc)
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Renders the signature Marginalia Lectoris E-Paper horizontal progress bar:
         * 1.5dp top horizontal baseline, left vertical tick mark, and solid black progress fill block.
         */
        fun renderEInkProgressBitmap(progress: Float, widthPx: Int = 300, heightPx: Int = 12): Bitmap {
            val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)

            val blackPaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.FILL
                isAntiAlias = false
            }

            // Full-width top baseline (3px thick)
            canvas.drawRect(0f, 0f, widthPx.toFloat(), 3f, blackPaint)
            // Left vertical tick mark
            canvas.drawRect(0f, 0f, 3f, heightPx.toFloat(), blackPaint)

            val clamped = progress.coerceIn(0f, 1f)
            if (clamped > 0f) {
                val fillW = (widthPx * clamped.coerceAtLeast(0.04f)).coerceAtMost(widthPx.toFloat())
                canvas.drawRect(0f, 0f, fillW, heightPx.toFloat(), blackPaint)
            }
            return bmp
        }
    }
}
