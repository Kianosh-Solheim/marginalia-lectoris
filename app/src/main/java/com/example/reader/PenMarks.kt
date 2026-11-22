package com.example.reader

import kotlin.math.abs

/**
 * Reading pen marks on a PDF page. Points and words are in page ratios (0…1).
 *
 * An underline is a mostly level stroke lying on or just below a line of text: the words of that line
 * it runs beneath are the ones meant. Strokes that are not level (diagonal, up and down) are left to
 * the caller, which selects from the word at the stroke's start to the word at its end.
 */
object PenMarks {
    /** A stroke this short is a dot or a tap, not a line. */
    private const val MIN_LENGTH = 0.012f

    fun isDot(points: List<Pair<Float, Float>>): Boolean {
        if (points.size < 2) return true
        val xs = points.map { it.first }; val ys = points.map { it.second }
        return (xs.max() - xs.min()) < MIN_LENGTH && (ys.max() - ys.min()) < MIN_LENGTH
    }

    /** The words (first..last word index) an underline marks, or null when the stroke is not an underline. */
    fun underline(words: List<PdfPageWord>, points: List<Pair<Float, Float>>, reach: Float = 1.0f): IntRange? {
        if (words.isEmpty() || points.size < 2) return null
        val xs = points.map { it.first }; val ys = points.map { it.second }
        val minX = xs.min(); val maxX = xs.max()
        val w = maxX - minX; val h = ys.max() - ys.min()
        if (w < MIN_LENGTH) return null
        val lines = words.groupBy { it.lineIndex }
        val typical = words.map { it.bottomRatio - it.topRatio }.sorted().let { it[it.size / 2] }.coerceAtLeast(0.004f)
        // Level enough: no taller than a line and a half, and much wider than tall.
        if (h > maxOf(w * 0.45f, typical * 0.6f) || h > typical * 1.6f) return null
        val y = ys.sorted()[ys.size / 2]

        var best: List<PdfPageWord>? = null
        var bestScore = Float.MAX_VALUE
        for ((_, lw) in lines) {
            val under = lw.filter { overlap(it, minX, maxX) }
            if (under.isEmpty()) continue
            val top = lw.minOf { it.topRatio }; val bottom = lw.maxOf { it.bottomRatio }
            val lh = (bottom - top).coerceAtLeast(typical * 0.5f)
            val score = when {
                y in top..bottom -> (bottom - y) * 0.25f            // through the line or on its foot
                y > bottom && y - bottom <= lh * reach -> y - bottom // below it
                else -> continue
            }
            if (score < bestScore) { bestScore = score; best = under }
        }
        val chosen = best ?: return null
        return chosen.minOf { it.wordIndex }..chosen.maxOf { it.wordIndex }
    }

    /**
     * A loop drawn round some text (a lasso): the stroke ends near where it began and encloses an area.
     * A loop that is not quite closed still counts when its ends are close compared with its size.
     */
    fun isLoop(points: List<Pair<Float, Float>>): Boolean {
        if (points.size < 8) return false
        val xs = points.map { it.first }; val ys = points.map { it.second }
        val w = xs.max() - xs.min(); val h = ys.max() - ys.min()
        if (w < 0.03f || h < 0.012f) return false
        val diag = kotlin.math.hypot(w, h)
        val (x0, y0) = points.first(); val (x1, y1) = points.last()
        val gap = kotlin.math.hypot(x1 - x0, y1 - y0)
        var length = 0f
        for (i in 1 until points.size) length += kotlin.math.hypot(points[i].first - points[i - 1].first, points[i].second - points[i - 1].second)
        // Closed enough, and the path goes most of the way round (not a line back and forth).
        return gap <= maxOf(0.04f, diag * 0.28f) && length >= diag * 1.8f && kotlin.math.abs(area(points)) >= w * h * 0.25f
    }

    /** The words inside a loop (their middles within it): first..last word index, or null. */
    fun lasso(words: List<PdfPageWord>, points: List<Pair<Float, Float>>): IntRange? {
        val inside = words.filter { w ->
            val cx = (w.leftRatio + w.rightRatio) / 2f; val cy = (w.topRatio + w.bottomRatio) / 2f
            contains(points, cx, cy)
        }
        if (inside.isEmpty()) return null
        return inside.minOf { it.wordIndex }..inside.maxOf { it.wordIndex }
    }

    private fun area(p: List<Pair<Float, Float>>): Float {
        var a = 0f
        for (i in p.indices) { val (x0, y0) = p[i]; val (x1, y1) = p[(i + 1) % p.size]; a += x0 * y1 - x1 * y0 }
        return a / 2f
    }
    /** Is (x, y) inside the polygon [p] (closed from its last point to its first)? */
    private fun contains(p: List<Pair<Float, Float>>, x: Float, y: Float): Boolean {
        var inside = false
        var j = p.size - 1
        for (i in p.indices) {
            val (xi, yi) = p[i]; val (xj, yj) = p[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / ((yj - yi).takeIf { it != 0f } ?: 1e-6f) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** At least a third of the word lies within the stroke's width (a word cut by the very tip still counts when short). */
    private fun overlap(word: PdfPageWord, minX: Float, maxX: Float): Boolean {
        val ww = (word.rightRatio - word.leftRatio).coerceAtLeast(0.0001f)
        val o = minOf(word.rightRatio, maxX) - maxOf(word.leftRatio, minX)
        return o >= ww * 0.34f || (o > 0 && abs(o) >= 0.01f)
    }
}
