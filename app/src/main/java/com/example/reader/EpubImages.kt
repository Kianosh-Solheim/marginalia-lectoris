package com.example.reader

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.zip.ZipFile

/**
 * The pictures of an EPUB, for the larger view: in reading order, each with its caption, and read
 * from the book's file at full size (the reader keeps only small copies).
 */
object EpubImages {
    data class Picture(val path: String, val caption: String)

    /** The book's pictures in order, once each. */
    fun list(book: EpubBook): List<Picture> {
        val seen = HashSet<String>()
        val out = ArrayList<Picture>()
        book.chapters.forEach { ch ->
            ch.semanticBlocks.forEach { sb ->
                val p = sb.imagePath.trim()
                if (p.isNotBlank() && seen.add(p)) out += Picture(p, sb.captionText.trim().ifBlank { sb.imageAlt.trim() })
            }
        }
        return out
    }

    /** The path of the picture shown as [bitmap] (the small copy the reader has). */
    fun pathOf(book: EpubBook, bitmap: Bitmap): String? =
        book.embeddedImages.entries.filter { it.value === bitmap }.map { it.key }.maxByOrNull { it.length }

    private fun entryName(zip: ZipFile, path: String): String? {
        val clean = path.substringBefore('#').substringBefore('?').replace('\\', '/').trimStart('/')
        val tail = clean.split('/').filter { it.isNotBlank() && it != "." && it != ".." }.joinToString("/")
        zip.getEntry(clean)?.let { return it.name }
        val names = zip.entries().asSequence().map { it.name }.toList()
        return names.firstOrNull { it.equals(tail, true) || it.endsWith("/$tail", true) }
            ?: names.firstOrNull { it.substringAfterLast('/').equals(tail.substringAfterLast('/'), true) }
    }

    /** The picture's own bytes from the book's file. */
    fun bytes(book: EpubBook, path: String): ByteArray? = runCatching {
        val f = File(book.sourceArchivePath)
        if (!f.isFile) return null
        ZipFile(f).use { z -> entryName(z, path)?.let { n -> z.getInputStream(z.getEntry(n)).use { it.readBytes() } } }
    }.getOrNull()

    /** The picture at up to [maxDim] pixels on its longest side. */
    fun full(book: EpubBook, path: String, maxDim: Int = 2600): Bitmap? = runCatching {
        val b = bytes(book, path) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > maxDim) sample *= 2
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Saves the picture in Pictures/Marginalia and returns where it went. */
    fun save(context: Context, book: EpubBook, path: String, fallback: Bitmap?): String {
        val raw = bytes(book, path)
        val ext = path.substringAfterLast('.', "png").lowercase().let { if (it in setOf("jpg", "jpeg", "png", "gif", "webp", "svg")) it else "png" }
        val data = raw ?: fallback?.let { bmp -> java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
            ?: throw IllegalStateException("The picture could not be read.")
        val useExt = if (raw == null) "png" else ext
        val base = (book.title.take(40).replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().ifBlank { "Book" }) + " - " +
            path.substringAfterLast('/').substringBeforeLast('.').take(40)
        val name = "$base.$useExt"
        val mime = when (useExt) { "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; "svg" -> "image/svg+xml"; else -> "image/png" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Marginalia")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Android did not let the app save the picture.")
            context.contentResolver.openOutputStream(uri)?.use { it.write(data) } ?: throw IllegalStateException("Could not write the picture.")
            return "Pictures/Marginalia/$name"
        }
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Marginalia").apply { mkdirs() }
        File(dir, name).writeBytes(data)
        return File(dir, name).absolutePath
    }
}
