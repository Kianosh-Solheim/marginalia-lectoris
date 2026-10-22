package com.example.reader

import kotlin.math.max

/**
 * Puts the words of a PDF page in reading order when the page is set in columns.
 *
 * PDF text is grouped into lines straight across the page, so on a two-column page a line holds the
 * left column's words followed by the right column's, and a selection from one word to another
 * weaves through both columns. This finds the gutters (vertical strips with no text between
 * populated columns), splits every line at them, and orders the result: down the first column,
 * then down the next, with full-width lines (titles, headings across the page) kept in place as
 * separators between bands of columns.
 */
object PdfColumnOrder {

    data class Result(val words: List<PdfPageWord>, val columns: Int, val paragraphs: List<String>)

    fun reorder(words: List<PdfPageWord>): Result {
        if (words.size < 40) return Result(words, 1, emptyList())
        val strips = findGutterStrips(words)
        if (strips.isEmpty()) return Result(words, 1, emptyList())
        val gutters = strips.map { (it.first + it.second) / 2f }

        fun colOf(w: PdfPageWord): Int {
            val cx = (w.leftRatio + w.rightRatio) / 2f
            return gutters.count { cx > it }
        }

        // Lines as extracted (top to bottom), split into one piece per column.
        val lines = words.groupBy { it.lineIndex }.toSortedMap().values.map { it.sortedBy { w -> w.leftRatio } }
        data class Piece(val col: Int, val words: List<PdfPageWord>, val fullWidth: Boolean) {
            val top get() = words.minOf { it.topRatio }
            val bottom get() = words.maxOf { it.bottomRatio }
        }
        val pieces = mutableListOf<Piece>()
        for (line in lines) {
            val byCol = line.groupBy { colOf(it) }
            // A line whose text runs across a gutter without a gap is full width (a title or heading).
            var crosses = false
            for (i in 1 until line.size) {
                val a = line[i - 1]; val b = line[i]
                if (colOf(a) != colOf(b) && b.leftRatio - a.rightRatio < 0.008f) { crosses = true; break }
            }
            // …or whose words reach into a gutter strip (a centred title, a heading across the page).
            val intoGutter = line.any { w -> strips.any { (gl, gr) -> w.rightRatio > gl + 0.006f && w.leftRatio < gr - 0.006f } }
            if (crosses || intoGutter) {
                pieces += Piece(-1, line, true)
            } else {
                byCol.toSortedMap().forEach { (c, ws) -> pieces += Piece(c, ws, false) }
            }
        }

        // Bands of columns between full-width lines.
        val ordered = mutableListOf<List<PdfPageWord>>()
        val band = mutableListOf<Piece>()
        fun flushBand() {
            band.groupBy { it.col }.toSortedMap().values.forEach { colPieces ->
                colPieces.sortedBy { it.top }.forEach { ordered += it.words }
            }
            band.clear()
        }
        for (p in pieces.sortedBy { it.top }) {
            if (p.fullWidth) { flushBand(); ordered += p.words } else band += p
        }
        flushBand()

        // Re-number words and lines in the new order.
        var wi = 0
        val out = ArrayList<PdfPageWord>(words.size)
        ordered.forEachIndexed { li, ws -> ws.forEach { w -> out += w.copy(lineIndex = li, wordIndex = wi++) } }

        // Paragraphs in reading order: a new paragraph at a column change or a clear vertical gap.
        val heights = ordered.map { ws -> ws.maxOf { it.bottomRatio } - ws.minOf { it.topRatio } }.sorted()
        val lineH = heights.getOrElse(heights.size / 2) { 0.015f }.coerceAtLeast(0.004f)
        val paragraphs = mutableListOf<String>()
        val cur = StringBuilder()
        var prevBottom = -1f; var prevLeft = -1f; var prevTop = -1f
        ordered.forEach { ws ->
            val top = ws.minOf { it.topRatio }; val left = ws.minOf { it.leftRatio }
            val newPara = prevBottom >= 0f && (top - prevBottom > lineH * 0.9f || top < prevTop || left > prevLeft + 0.04f && top - prevBottom > lineH * 0.2f)
            if (newPara && cur.isNotBlank()) { paragraphs += cur.toString().trim(); cur.clear() }
            val text = ws.joinToString(" ") { it.text }
            if (cur.endsWith("-") && text.firstOrNull()?.isLowerCase() == true) cur.setLength(cur.length - 1) else if (cur.isNotEmpty()) cur.append(' ')
            cur.append(text)
            prevBottom = ws.maxOf { it.bottomRatio }; prevLeft = left; prevTop = top
        }
        if (cur.isNotBlank()) paragraphs += cur.toString().trim()
        return Result(out, gutters.size + 1, paragraphs)
    }

    fun findGutters(words: List<PdfPageWord>): List<Float> = findGutterStrips(words).map { (it.first + it.second) / 2f }

    /** Column gutters as (left, right) strips in 0..1: no text in them, text on both sides, much wider than word gaps. */
    fun findGutterStrips(words: List<PdfPageWord>): List<Pair<Float, Float>> {
        val minX = words.minOf { it.leftRatio }; val maxX = words.maxOf { it.rightRatio }
        val span = maxX - minX
        if (span < 0.3f) return emptyList()
        val bins = 240
        // Occupancy counts lines, not words: how many lines put ink in each vertical strip.
        val occ = IntArray(bins)
        fun bin(x: Float) = (((x - minX) / span) * (bins - 1)).toInt().coerceIn(0, bins - 1)
        val lines = words.groupBy { it.lineIndex }.values
        lines.forEach { l ->
            val hit = BooleanArray(bins)
            l.forEach { w -> for (b in bin(w.leftRatio)..bin(w.rightRatio)) hit[b] = true }
            for (b in 0 until bins) if (hit[b]) occ[b]++
        }
        val lineCount = lines.size
        // Headings, the chapter title, the author's name and running footers often sit across the
        // gutter; up to a fifth of the lines may cross it without hiding the columns.
        val limit = max(1, (lineCount * 0.2f).toInt())
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (b in 0 until bins) {
            val empty = occ[b] <= limit
            if (empty && start < 0) start = b
            if ((!empty || b == bins - 1) && start >= 0) {
                val end = if (empty) b else b - 1
                runs += start..end; start = -1
            }
        }
        // Typical gap between words on a line: a gutter must be clearly wider than that.
        val gaps = words.groupBy { it.lineIndex }.values.flatMap { l ->
            l.sortedBy { it.leftRatio }.zipWithNext { a, b -> b.leftRatio - a.rightRatio }.filter { it > 0f }
        }.sorted()
        // Only gaps that can be ordinary word spaces (a "word" may be a whole run of text when the PDF stores spaces).
        val small = gaps.filter { it < 0.025f }
        val wordGap = if (small.size >= 5) small[small.size / 2] else 0.006f
        val minWidth = (max(0.012f, wordGap * 2.5f) / span * bins).toInt().coerceAtLeast(2)
        return runs.filter { r ->
            val center = (r.first + r.last) / 2f / (bins - 1)
            r.last - r.first + 1 >= minWidth && center in 0.18f..0.82f
        }.mapNotNull { r ->
            val gl = minX + r.first.toFloat() / (bins - 1) * span
            val gr = minX + (r.last + 1).toFloat() / (bins - 1) * span
            val gx = (gl + gr) / 2f
            val left = words.count { it.rightRatio <= gx }
            val right = words.count { it.leftRatio >= gx }
            if (left >= words.size * 0.2f && right >= words.size * 0.2f) gl to gr else null
        }.take(2)
    }
}
