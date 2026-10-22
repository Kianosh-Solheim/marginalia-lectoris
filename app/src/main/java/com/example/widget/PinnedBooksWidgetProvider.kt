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
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.MainActivity
import com.example.R
import com.example.data.StorageManager
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.reader.EpubParser
import com.example.reader.PdfDocumentRenderer
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Resizable Android Home Screen / Tablet Widget (`AppWidgetProvider`) that displays the user's
 * pinned books with their covers, current reading page, and progress bar.
 *
 * - Supports horizontal & vertical resizing on Android phones and reading tablets.
 * - Supports per-widget or global configuration of how many books to pin (1..12) and which books are pinned.
 * - Tapping any book cover launches [MainActivity] directly into that book at `document.currentPage`
 *   (where the user last left off reading).
 */
class PinnedBooksWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, appWidgetId)
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
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_books_grid)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        for (id in appWidgetIds) {
            prefs.remove("$KEY_MAX_BOOKS_PREFIX$id")
        }
        prefs.apply()
    }

    companion object {
        const val PREFS_NAME = "readmark_widget_prefs"
        const val KEY_MAX_BOOKS_PREFIX = "widget_max_books_"
        const val EXTRA_OPEN_DOCUMENT_ID = "extra_open_document_id"
        const val EXTRA_OPEN_WIDGET_MANAGER = "extra_open_widget_manager"
        const val ACTION_OPEN_BOOK_FROM_WIDGET = "com.example.action.OPEN_BOOK_FROM_WIDGET"
        const val ACTION_CONFIGURE_WIDGET_PINS = "com.example.action.CONFIGURE_WIDGET_PINS"

        fun setMaxBooksForWidget(context: Context, appWidgetId: Int, maxBooks: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt("$KEY_MAX_BOOKS_PREFIX$appWidgetId", maxBooks.coerceIn(1, 16))
                .apply()
        }

        fun getMaxBooksForWidget(context: Context, appWidgetId: Int, fallbackMax: Int = 6): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt("$KEY_MAX_BOOKS_PREFIX$appWidgetId", fallbackMax).coerceIn(1, 16)
        }

        fun refreshAllWidgets(context: Context) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, PinnedBooksWidgetProvider::class.java)
                val ids = appWidgetManager.getAppWidgetIds(componentName)
                if (ids != null && ids.isNotEmpty()) {
                    for (id in ids) {
                        updateSingleWidget(context, appWidgetManager, id)
                    }
                    appWidgetManager.notifyAppWidgetViewDataChanged(ids, R.id.widget_books_grid)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            optionsBundle: Bundle? = null
        ) {
            val options = optionsBundle ?: appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 220).coerceAtLeast(110)
            val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160).coerceAtLeast(110)

            val views = RemoteViews(context.packageName, R.layout.widget_pinned_books)

            // RemoteViewsService intent for GridView items
            val serviceIntent = Intent(context, PinnedBooksRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra("min_width_dp", minWidthDp)
                putExtra("min_height_dp", minHeightDp)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.widget_books_grid, serviceIntent)
            views.setEmptyView(R.id.widget_books_grid, R.id.widget_empty_view)

            // PendingIntent template for clicking any book cover in the GridView
            val openBookTemplateIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_BOOK_FROM_WIDGET
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val templatePendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId + 2000,
                openBookTemplateIntent,
                pendingIntentFlags
            )
            views.setPendingIntentTemplate(R.id.widget_books_grid, templatePendingIntent)

            // PendingIntent for empty view tap
            val configIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_CONFIGURE_WIDGET_PINS
                putExtra(EXTRA_OPEN_WIDGET_MANAGER, true)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val configPendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId + 5000,
                configIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_empty_view, configPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /**
         * Resolves or generates a compact cover Bitmap suitable for RemoteViews IPC limits
         * (scaled to ~180x250 px) so the book cover is always visible on the home screen widget.
         */
        fun loadOrGenerateWidgetCoverBitmap(context: Context, doc: DocumentEntity): Bitmap {
            try {
                // 1. Check explicit coverUri
                if (!doc.coverUri.isNullOrBlank()) {
                    val f = File(doc.coverUri)
                    if (f.exists() && f.length() > 0) {
                        decodeScaledBitmap(f, 180, 250)?.let { return it }
                    }
                }

                // 2. Check cached cover in .covers directory
                val prefix = when {
                    doc.fileType.equals("PDF", ignoreCase = true) -> "pdf_${doc.id}"
                    doc.fileType.equals("HTML", ignoreCase = true) -> "html_${doc.id}"
                    doc.fileType.equals("TXT", ignoreCase = true) -> "txt_${doc.id}"
                    else -> "epub_${doc.id}"
                }
                val coversDir = File(StorageManager.getRootDirectory(context), ".covers")
                val cachedCover = File(coversDir, "${StorageManager.sanitizeName(prefix)}.jpg")
                if (cachedCover.exists() && cachedCover.length() > 0) {
                    decodeScaledBitmap(cachedCover, 180, 250)?.let { return it }
                }

                // 3. Render first page / extract cover from source file
                val sourceFile = File(doc.filePath)
                if (sourceFile.exists()) {
                    if (doc.fileType.equals("PDF", ignoreCase = true)) {
                        val renderer = PdfDocumentRenderer(sourceFile, context)
                        val bmp = renderer.renderPage(0, targetWidth = 220)
                        renderer.close()
                        if (bmp != null) {
                            StorageManager.saveCoverBitmap(context, prefix, bmp)
                            return scaleBitmapToWidgetSize(bmp, 180, 250)
                        }
                    } else if (doc.fileType.equals("EPUB", ignoreCase = true)) {
                        val bmp = EpubParser.extractCoverBitmap(sourceFile, doc.title, doc.author)
                        StorageManager.saveCoverBitmap(context, prefix, bmp)
                        return scaleBitmapToWidgetSize(bmp, 180, 250)
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }

            // 4. Fallback stylized e-paper book cover with title & author
            return createStylizedFallbackCover(doc.title, doc.author, doc.fileType)
        }

        private fun decodeScaledBitmap(file: File, reqW: Int, reqH: Int): Bitmap? {
            return try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                var sampleSize = 1
                while (opts.outWidth / sampleSize > reqW * 2 || opts.outHeight / sampleSize > reqH * 2) {
                    sampleSize *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val raw = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null
                scaleBitmapToWidgetSize(raw, reqW, reqH)
            } catch (_: Throwable) {
                null
            }
        }

        private fun scaleBitmapToWidgetSize(src: Bitmap, maxW: Int, maxH: Int): Bitmap {
            if (src.width <= maxW && src.height <= maxH) return src
            val ratio = minOf(maxW.toFloat() / src.width.coerceAtLeast(1), maxH.toFloat() / src.height.coerceAtLeast(1))
            val w = (src.width * ratio).toInt().coerceAtLeast(1)
            val h = (src.height * ratio).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(src, w, h, true)
        }

        private fun createStylizedFallbackCover(title: String, author: String, fileType: String): Bitmap {
            val w = 180
            val h = 250
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#18181B")
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawRect(3f, 3f, w - 3f, h - 3f, borderPaint)

            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#18181B")
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(RectF(14f, 14f, 68f, 34f), 4f, 4f, badgePaint)

            val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 12f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText(fileType.uppercase().take(4), 22f, 28f, badgeTextPaint)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#18181B")
                textSize = 16f
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            }
            val words = title.split(" ")
            var line = ""
            var y = 78f
            for (word in words) {
                val test = if (line.isEmpty()) word else "$line $word"
                if (titlePaint.measureText(test) > w - 28f) {
                    canvas.drawText(line, 14f, y, titlePaint)
                    line = word
                    y += 22f
                    if (y > 165f) break
                } else {
                    line = test
                }
            }
            if (line.isNotEmpty() && y <= 165f) {
                canvas.drawText(line, 14f, y, titlePaint)
            }

            val authorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#71717A")
                textSize = 12f
            }
            canvas.drawText(author.take(20), 14f, h - 22f, authorPaint)
            return bmp
        }
    }
}

class PinnedBooksRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return PinnedBooksRemoteViewsFactory(applicationContext, intent)
    }
}

class PinnedBooksRemoteViewsFactory(
    private val context: Context,
    intent: Intent
) : RemoteViewsService.RemoteViewsFactory {

    private val appWidgetId: Int = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID
    )
    private var pinnedDocs: List<DocumentEntity> = emptyList()

    override fun onCreate() {
        loadData()
    }

    override fun onDataSetChanged() {
        loadData()
    }

    private fun loadData() {
        runBlocking {
            try {
                val db = AppDatabase.getInstance(context)
                val settings = db.settingsDao().getSettingsSnapshot()
                val globalMax = settings?.maxPinnedBooksInWidget ?: 6
                val effectiveMax = PinnedBooksWidgetProvider.getMaxBooksForWidget(context, appWidgetId, globalMax)

                val explicitlyPinned = db.documentDao().getPinnedWidgetDocumentsSnapshot()
                pinnedDocs = if (explicitlyPinned.isNotEmpty()) {
                    explicitlyPinned.take(effectiveMax)
                } else {
                    // If user hasn't explicitly pinned any books yet, show their most recently read books up to effectiveMax
                    com.example.reader.FolderBooks.init(context)
                    com.example.reader.FolderBooks.visible(db.documentDao().getAllDocumentsOrderedByLastReadSnapshot()).take(effectiveMax)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                pinnedDocs = emptyList()
            }
        }
    }

    override fun onDestroy() {
        pinnedDocs = emptyList()
    }

    override fun getCount(): Int = pinnedDocs.size

    override fun getViewAt(position: Int): RemoteViews? {
        if (position < 0 || position >= pinnedDocs.size) return null
        val doc = pinnedDocs[position]

        val rv = RemoteViews(context.packageName, R.layout.widget_pinned_book_item)
        rv.setTextViewText(R.id.widget_book_title, doc.title)

        val percent = (doc.readingProgress * 100).toInt().coerceIn(0, 100)
        val total = doc.totalPages.coerceAtLeast(1)
        val pageLabel = if (total > 1) "p. ${doc.currentPage}/$total" else "$percent%"
        rv.setTextViewText(R.id.widget_book_page_badge, pageLabel)
        rv.setProgressBar(R.id.widget_book_progress, 100, percent, false)

        val coverBitmap = PinnedBooksWidgetProvider.loadOrGenerateWidgetCoverBitmap(context, doc)
        rv.setImageViewBitmap(R.id.widget_book_cover_image, coverBitmap)

        // Fill-in intent so clicking this book opens MainActivity directly at its exact citable position or page
        val fillInIntent = Intent().apply {
            putExtra(PinnedBooksWidgetProvider.EXTRA_OPEN_DOCUMENT_ID, doc.id)
            putExtra("extra_resume_cfi", doc.lastPositionCfi)
            putExtra("extra_resume_paragraph", doc.lastPositionParagraph)
            putExtra("extra_resume_offset", doc.lastPositionOffset)
            putExtra("extra_resume_loc", doc.lastPositionLoc)
            putExtra("extra_resume_page", doc.currentPage.coerceAtLeast(1))
        }
        rv.setOnClickFillInIntent(R.id.widget_book_item_root, fillInIntent)

        return rv
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        pinnedDocs.getOrNull(position)?.id ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
