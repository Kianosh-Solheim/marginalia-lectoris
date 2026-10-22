package com.example.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.reader.EpubParser
import com.example.reader.FolderBooks
import com.example.widget.ContinueReadingWidgetProvider
import com.example.widget.PinnedBooksWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Changes a book's cover from the browser: a new image (uploaded, pasted or a PDF page the browser
 * drew), or back to the original. Books and articles go through the same path as the app's own
 * “Edit Info, Tags & Cover Image”; folder books keep their cover in their own folder.
 */
object CoverChanger {
    /** Bumped on every change, so the app's shelf redraws covers whose file name stayed the same. */
    val version = MutableStateFlow(0)

    private const val MAX_SIDE = 1600

    fun decode(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "That file is not an image the tablet can read." }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("That file is not an image the tablet can read.")
    }

    suspend fun setImage(context: Context, ops: LibraryOps, doc: DocumentEntity, bytes: ByteArray): DocumentEntity {
        val bmp = decode(bytes)
        val out = if (FolderBooks.isFolder(doc)) folderCover(context, doc, bmp)
        else ops.updateDocumentInfo(doc, doc.title, doc.author, doc.tags, newCoverBitmap = bmp, newSummary = doc.summary)
        changed(context, doc.id)
        return out
    }

    suspend fun reset(context: Context, ops: LibraryOps, doc: DocumentEntity): DocumentEntity {
        val out = if (FolderBooks.isFolder(doc)) {
            val by = doc.author.trim().let { if (it.isBlank()) "" else "$it (ed.)" }
            folderCover(context, doc, EpubParser.generateBookCoverBitmap(doc.title, by))
        } else {
            ops.updateDocumentInfo(doc, doc.title, doc.author, doc.tags, resetToDefaultCover = true, newSummary = doc.summary)
        }
        changed(context, doc.id)
        return out
    }

    private suspend fun folderCover(context: Context, doc: DocumentEntity, bmp: Bitmap): DocumentEntity = withContext(Dispatchers.IO) {
        val dir = File(doc.filePath).parentFile?.takeIf { it.isDirectory }
            ?: throw IllegalArgumentException("The folder book's folder is missing on the tablet.")
        // Never write over a chapter's cover (the folder shows its first chapter's cover until it has its own).
        val own = doc.coverUri?.let { File(it) }?.takeIf { it.parentFile?.canonicalPath == dir.canonicalPath }
        val target = own ?: File(dir, "${dir.name} - front cover.WebP")
        FileOutputStream(target).use { out ->
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            bmp.compress(format, 90, out)
        }
        val ratio = if (bmp.height > 0) (bmp.width.toFloat() / bmp.height).coerceIn(0.2f, 5f) else 2f / 3f
        val updated = doc.copy(coverUri = target.absolutePath, coverAspectRatio = ratio)
        AppDatabase.getInstance(context).documentDao().updateDocument(updated)
        updated
    }

    private fun changed(context: Context, id: Long) {
        version.value = version.value + 1
        runCatching { ContinueReadingWidgetProvider.forgetCover(id) }
        runCatching { PinnedBooksWidgetProvider.refreshAllWidgets(context) }
        runCatching { ContinueReadingWidgetProvider.refreshAll(context) }
    }
}
