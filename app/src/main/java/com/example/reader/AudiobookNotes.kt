package com.example.reader

import android.content.Context
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.server.EpubIndex
import java.io.File

/**
 * "Note here" while listening: a highlight of the sentence being read at that moment, found with the
 * sentence map, so it sits in the right place in the text (also on the tablet, after a sync).
 * Without a map it is a note at the chapter, with the time in the audiobook.
 */
object AudiobookNotes {
    /** The sentence at [ms], for showing before the note is saved; blank without a map. */
    suspend fun sentenceAt(ctx: Context, doc: DocumentEntity, ms: Long): String {
        val map = AudiobookSyncMap.load(ctx, doc) ?: return ""
        return map.text.getOrNull(map.indexAt(ms)).orEmpty()
    }

    suspend fun noteAt(ctx: Context, doc: DocumentEntity, ms: Long, note: String, chapterName: String): HighlightEntity {
        val ops = com.example.server.LibraryOps(ctx)
        val color = "#FFEB3B"
        val time = clock(ms)
        val map = AudiobookSyncMap.load(ctx, doc)
        val i = map?.indexAt(ms) ?: -1
        if (map == null || i < 0) {
            return ops.addHighlight(documentId = doc.id, title = "", highlightedText = "", userNotes = note.ifBlank { "Listening at $time" },
                pageNumber = 1, chapterTitle = chapterName.ifBlank { "Audiobook" }, colorHex = color, paragraphCitation = "Audiobook, $time")
        }
        val text = map.text[i]
        if (map.kind == "pdf") {
            val page = map.page[i].coerceAtLeast(1)
            return ops.addHighlight(documentId = doc.id, title = "", highlightedText = text, userNotes = note, pageNumber = page,
                chapterTitle = chapterName.ifBlank { "Page $page" }, colorHex = color)
        }
        val href = map.href[i]
        val index = runCatching { EpubIndex.of(ctx, File(doc.filePath)) }.getOrNull()
        // Where in its chapter file the sentence is, by its share of that file's sentences.
        val base = href.substringAfterLast('/')
        val inFile = (0 until map.size).filter { map.href[it].substringAfterLast('/') == base }
        val progression = if (inFile.size > 1) inFile.indexOf(i).toDouble() / (inFile.size - 1) else 0.0
        val position = if (index != null && href.isNotBlank()) EpubIndex.positionFor(index, href, progression) else 1
        val chapter = index?.let { EpubIndex.resourceFor(it, href)?.title }.orEmpty().ifBlank { chapterName }.ifBlank { "Chapter" }
        return ops.addHighlight(documentId = doc.id, title = "", highlightedText = text, userNotes = note, pageNumber = position,
            chapterTitle = chapter, colorHex = color, paragraphCitation = "$chapter, pos. $position")
    }

    private fun clock(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
}
