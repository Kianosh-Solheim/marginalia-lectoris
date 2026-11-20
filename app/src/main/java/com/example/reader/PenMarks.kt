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

    /** At least a third of the word lies within the stroke's width (a word cut by the very tip still counts when short). */
    private fun overlap(word: PdfPageWord, minX: Float, maxX: Float): Boolean {
        val ww = (word.rightRatio - word.leftRatio).coerceAtLeast(0.0001f)
        val o = minOf(word.rightRatio, maxX) - maxOf(word.leftRatio, minX)
        return o >= ww * 0.34f || (o > 0 && abs(o) >= 0.01f)
    }
}
