package com.example.reader

import org.json.JSONArray
import org.json.JSONObject

/**
 * A sync map by printed page, at once, without Whisper: a DAISY audiobook says when each printed page begins,
 * an EPUB with page numbers says where. Each page becomes one "sentence" of the map: the first words of the
 * page, at the time the page begins (so the reader marks the start of the page being read, and turns to it).
 * Whisper can refine it to every sentence later; that map replaces this one.
 *
 * The front matter of a talking book is often read differently from how it is printed (the cover text first,
 * say), so a page is only used where the headings agree: the DAISY page's headings and the EPUB chapter the
 * page lies in are the same chapter, or neither has a counterpart in the other at all.
 * Pure (org.json only), so it can be tested.
 */
object DaisyPageSync {
    /** An audiobook page: its number, when it begins on the audiobook's timeline, the headings it lies under. */
    data class AudioPage(val label: String, val ms: Long, val headings: List<String>, val next: String? = null)
    /**
     * A chapter of the book as the reader has it: its file, its titles (all the table of contents has for the file),
     * paragraphs, and each paragraph's printed page.
     */
    data class Chapter(val fileKey: String, val titles: List<String>, val paragraphs: List<String>, val pageLabels: List<String>)

    data class Result(val sentences: JSONArray, val pages: Int, val placed: Int, val skipped: List<String>)

    fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun key(label: String) = norm(EpubPageMarks.cleanLabel(label))

    /** Two titles name the same chapter: one holds the other, or most words of the shorter are in the longer. */
    fun sameTitle(a: String, b: String): Boolean {
        val x = norm(a); val y = norm(b)
        if (x.length < 3 || y.length < 3) return false
        if (x.contains(y) || y.contains(x)) return true
        val wa = x.split(' ').filter { it.length > 2 }.toSet(); val wb = y.split(' ').filter { it.length > 2 }.toSet()
        if (wa.isEmpty() || wb.isEmpty()) return false
        return wa.intersect(wb).size.toDouble() / minOf(wa.size, wb.size) >= 0.6
    }

    private fun last(path: String) = path.substringAfterLast('/')

    fun build(audio: List<AudioPage>, marks: List<EpubPageMarks.Mark>, chapters: List<Chapter>): Result {
        val byLabel = LinkedHashMap<String, EpubPageMarks.Mark>()
        marks.forEach { m -> byLabel.putIfAbsent(key(m.label), m) }
        // the page before each page in the book (a page that begins inside a paragraph: it began on that one)
        val prevLabel = HashMap<String, String>()
        marks.zipWithNext().forEach { (a, b) -> prevLabel.putIfAbsent(key(b.label), key(a.label)) }
        val chapterOf = HashMap<String, Int>()
        chapters.forEachIndexed { i, c -> chapterOf.putIfAbsent(last(c.fileKey), i) }
        val epubTitles = chapters.flatMap { it.titles }.filter { it.isNotBlank() }.distinct()
        val audioTitles = audio.flatMap { it.headings + listOfNotNull(it.next) }.distinct()

        val out = ArrayList<JSONObject>(); val skipped = ArrayList<String>()
        var lastC = -1; var lastP = -1
        for (ap in audio) {
            val m = byLabel[key(ap.label)]
            if (m == null) { skipped += "${ap.label} (no such page in the book)"; continue }
            val c = chapterOf[last(m.file)]
            if (c == null) { skipped += "${ap.label} (outside the text)"; continue }
            val ch = chapters[c]
            // do the headings agree?
            val heard = ap.headings + listOfNotNull(ap.next)
            val here = heard.any { h -> ch.titles.any { sameTitle(h, it) } }
            val audioAlone = heard.none { h -> epubTitles.any { sameTitle(h, it) } }
            val epubAlone = ch.titles.none { t -> audioTitles.any { sameTitle(it, t) } }
            if (!here && !(audioAlone && epubAlone)) { skipped += "${ap.label} (other chapter: ${heard.lastOrNull()} / ${ch.titles.firstOrNull()})"; continue }

            // the paragraph the page begins in: the one holding its first words, else the first one of that page
            val words = norm(m.text).split(' ').filter { it.isNotEmpty() }
            val probe = words.take(6).joinToString(" ")
            val from = if (c == lastC) maxOf(0, lastP) else 0
            // a paragraph that holds the words and lies on this page (or begins on the page before: the page
            // begins inside it); words not in any paragraph (a heading) → the page's first paragraph
            val want = key(ap.label); val before = prevLabel[want]
            fun onPage(i: Int): Boolean {
                val l = ch.pageLabels.getOrNull(i)?.let { key(it) } ?: return true
                return l.isEmpty() || l == want || l == before
            }
            var p = -1
            if (probe.length >= 8) p = (from until ch.paragraphs.size).firstOrNull { norm(ch.paragraphs[it]).contains(probe) && onPage(it) } ?: -1
            if (p < 0) p = ch.pageLabels.indexOfFirst { key(it) == want }
            if (p < 0) { skipped += "${ap.label} (not in the text)"; continue }
            val para = ch.paragraphs[p]
            // where in the paragraph: its first words, matched loosely (spaces, punctuation)
            val first3 = m.text.split(Regex("\\s+")).filter { it.isNotBlank() }.take(3)
            val s = if (first3.isEmpty()) 0 else Regex(first3.joinToString("\\W+") { Regex.escape(it) }, RegexOption.IGNORE_CASE).find(para)?.range?.first ?: 0
            val e = minOf(para.length, s + maxOf(m.text.length, 1))
            out += JSONObject()
                .put("t", ap.ms)
                .put("l", JSONObject().put("c", c).put("p", p).put("s", s).put("e", e).put("h", ch.fileKey))
                .put("x", m.text.ifBlank { para.take(80) })
                .put("page", EpubPageMarks.cleanLabel(ap.label))
            lastC = c; lastP = p
        }
        out.sortBy { it.optLong("t") }
        return Result(JSONArray(out), audio.size, out.size, skipped)
    }
}
