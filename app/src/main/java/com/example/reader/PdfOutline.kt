package com.example.reader

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File

/**
 * Reads a PDF's bookmark tree (outline) with PDFBox: every level, real titles (PDFBox decodes
 * UTF-16 and escaped strings) and the real destination page, plus the printed page label.
 */
object PdfOutline {

    data class Entry(val title: String, val page: Int, val level: Int, val pageLabel: String = "")

    const val MAX_DEPTH = 6

    fun ensureLoader(context: Context?) {
        if (PDFBoxResourceLoader.isReady()) return
        val ctx = context?.applicationContext ?: BibStore.appContext ?: EditedVolumes.appContext ?: return
        PDFBoxResourceLoader.init(ctx)
    }

    fun read(file: File, context: Context? = null): List<Entry> {
        if (!file.isFile) return emptyList()
        ensureLoader(context)
        return PDDocument.load(file, MemoryUsageSetting.setupMixed(16L * 1024L * 1024L)).use { read(it) }
    }

    fun read(pd: PDDocument): List<Entry> {
        val outline = pd.documentCatalog?.documentOutline ?: return emptyList()
        val labels = runCatching { pd.documentCatalog?.pageLabels?.labelsByPageIndices }.getOrNull()
        val out = mutableListOf<Entry>()
        var guard = 0
        fun walk(first: PDOutlineItem?, level: Int) {
            var cur = first
            while (cur != null && guard++ < 5000) {
                val node: PDOutlineItem = cur
                val page = runCatching { node.findDestinationPage(pd)?.let { pd.pages.indexOf(it) + 1 } }.getOrNull() ?: -1
                val title = cleanTitle(node.title.orEmpty())
                if (title.isNotBlank()) {
                    // Entries without a destination take the next entry's page (fixed below).
                    out += Entry(title, page, level, if (page > 0) labels?.getOrNull(page - 1).orEmpty() else "")
                }
                if (level + 1 < MAX_DEPTH && node.hasChildren()) walk(node.firstChild, level + 1)
                cur = node.nextSibling
            }
        }
        walk(outline.firstChild, 0)
        // A heading without its own page (common for "Part" entries) starts where its first child starts.
        for (i in out.indices.reversed()) {
            if (out[i].page <= 0) {
                val next = out.drop(i + 1).firstOrNull { it.page > 0 }
                if (next != null) out[i] = out[i].copy(page = next.page, pageLabel = next.pageLabel)
            }
        }
        return out.filter { it.page > 0 }
    }

    /** Removes control characters, dot leaders with page numbers, and stray whitespace. */
    fun cleanTitle(raw: String): String = raw
        .replace(Regex("[\\u0000-\\u001F\\u007F\\uFEFF]"), " ")
        .replace(Regex("(?:\\s*\\.){3,}\\s*[0-9ivxlcdmIVXLCDM]+\\s*$"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
