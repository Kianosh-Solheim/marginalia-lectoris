package com.example.reader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The printed page numbers of a PDF as the reader has laid them down, in place of reading them off
 * each page: "PDF page 4 is printed page 183", and for a book a section in roman numerals followed
 * by one that starts again at 1. Pages before the first section count backwards from it.
 *
 * Also remembers which notes had their page typed in by hand, so renumbering leaves those alone.
 */
object PageNumbering {
    /** From PDF page [from] (1-based) the printed numbers run on from [start], in arabic or roman numerals. */
    data class Section(val from: Int, val start: Int, val roman: Boolean)

    private val lock = Any()
    private var cache: JSONObject? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "page_numbers.json")
    private fun all(ctx: Context): JSONObject = synchronized(lock) {
        cache ?: (runCatching { file(ctx).takeIf { it.isFile }?.let { JSONObject(it.readText()) } }.getOrNull() ?: JSONObject()).also { cache = it }
    }
    private fun store(ctx: Context, o: JSONObject) = synchronized(lock) {
        cache = o
        val f = file(ctx); val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(o.toString()); tmp.renameTo(f)
    }
    private fun docs(ctx: Context) = all(ctx).optJSONObject("docs") ?: JSONObject()

    /** The sections laid down for a document, in order; empty when its numbers are read automatically. */
    fun sections(ctx: Context, docId: Long): List<Section> {
        val a = docs(ctx).optJSONArray(docId.toString()) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Section(it.optInt("from", 1).coerceAtLeast(1), it.optInt("start", 1).coerceAtLeast(1), it.optBoolean("roman")) }
            .sortedBy { it.from }
    }

    fun setSections(ctx: Context, docId: Long, sections: List<Section>) {
        val root = JSONObject(all(ctx).toString()); val d = root.optJSONObject("docs") ?: JSONObject()
        if (sections.isEmpty()) d.remove(docId.toString())
        else d.put(docId.toString(), JSONArray().apply { sections.sortedBy { it.from }.forEach { put(JSONObject().put("from", it.from).put("start", it.start).put("roman", it.roman)) } })
        root.put("docs", d); store(ctx, root)
    }

    fun toRoman(n: Int): String {
        if (n <= 0) return ""
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i")
        val sb = StringBuilder(); var x = n
        for (i in v.indices) while (x >= v[i]) { sb.append(s[i]); x -= v[i] }
        return sb.toString()
    }

    /** The printed number of PDF page [page] under the document's sections; null when it has none (automatic), "" when the page has no number. */
    fun label(ctx: Context, docId: Long, page: Int): String? = label(sections(ctx, docId), page)

    fun label(secs: List<Section>, page: Int): String? {
        if (secs.isEmpty()) return null
        val s = secs.lastOrNull { it.from <= page } ?: secs.first()      // before the first section: counting backwards from it
        val n = s.start + (page - s.from)
        return if (n <= 0) "" else if (s.roman) toRoman(n) else n.toString()
    }

    /**
     * The page label to store for a quotation on PDF page [page] whose label so far is [stored]
     * (possibly a span, "183–184|PDF:4–5"): under the document's sections when it has them, else unchanged.
     */
    fun relabel(ctx: Context, docId: Long, page: Int, stored: String): String = relabel(sections(ctx, docId), page, stored)

    fun relabel(secs: List<Section>, page: Int, stored: String): String {
        if (secs.isEmpty()) return stored
        val (range, _) = PdfToEbookParser.parseStoredPageLabels(page, stored)
        val a = range.substringBefore('–').substringBefore('-').trim().toIntOrNull() ?: page
        val b = range.substringAfterLast('–').substringAfterLast('-').trim().toIntOrNull() ?: a
        val la = label(secs, a).orEmpty()
        return if (b > a) PdfQuotePageSpan(a, b, la, label(secs, b).orEmpty()).toStoredPrintedPageLabel() else la
    }

    /** A note whose page would change under [secs]. */
    data class Change(val note: com.example.data.local.HighlightEntity, val newLabel: String)

    /**
     * What laying down [secs] would change among a document's notes. Notes whose page was typed in
     * by hand, and notes made in another file of the item, are left as they are.
     */
    fun changes(ctx: Context, docId: Long, secs: List<Section>, notes: List<com.example.data.local.HighlightEntity>): List<Change> {
        if (secs.isEmpty()) return emptyList()
        return notes.filter { it.pageNumber > 0 && !isManual(ctx, it.id) && ItemFiles.noteFile(ctx, docId, it.id).isNullOrBlank() }.mapNotNull { n ->
            val next = relabel(secs, n.pageNumber, n.printedPageLabel)
            if (next != n.printedPageLabel) Change(n, next) else null
        }
    }

    // ---- pages typed in by hand

    fun isManual(ctx: Context, noteId: Long): Boolean = (all(ctx).optJSONObject("manual") ?: JSONObject()).has(noteId.toString())
    fun setManual(ctx: Context, noteId: Long, manual: Boolean) {
        val root = JSONObject(all(ctx).toString()); val m = root.optJSONObject("manual") ?: JSONObject()
        if (manual) m.put(noteId.toString(), true) else m.remove(noteId.toString())
        root.put("manual", m); store(ctx, root)
    }
}
