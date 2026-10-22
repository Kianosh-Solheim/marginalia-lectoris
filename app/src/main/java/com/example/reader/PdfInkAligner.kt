package com.example.reader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lines up a PDF's text layer with the printed page.
 *
 * Word boxes come from the PDF's text layer (PDFBox) while the page image is drawn by a different
 * renderer. In some PDFs, typically scans with an invisible OCR text layer, the text layer sits a
 * little to one side of the printed letters, so selections and highlights appear shifted. This
 * measures that offset on each page by comparing word boxes with the dark "ink" in the rendered
 * page, and moves the words onto the ink. If the evidence is weak the words are left untouched.
 */
object PdfInkAligner {

    /** Offset to add to every word, as fractions of the page image width / height. */
    data class Shift(val dx: Float, val dy: Float, val samples: Int, val agreement: Float)

    /** A word box in fractions of the page image (same convention as [PdfPageWord]). */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float, val chars: Int)

    /**
     * Estimates the shift from a grayscale page image.
     *
     * @param lum luminance 0..255 per pixel, row-major, size [w] x [h]
     */
    fun estimate(lum: IntArray, w: Int, h: Int, boxes: List<Box>): Shift? {
        if (w < 50 || h < 50 || boxes.size < 6) return null

        // Ink threshold relative to the page's paper colour.
        var sum = 0L
        val step = max(1, lum.size / 20000)
        var n = 0
        var i = 0
        while (i < lum.size) { sum += lum[i]; n++; i += step }
        val paper = (sum / max(1, n)).toInt()
        val threshold = min(170, paper - 55).coerceAtLeast(60)

        // Integral image of ink pixels for O(1) box sums.
        val iw = w + 1
        val integral = IntArray(iw * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0
            val rowOff = y * w
            val outOff = (y + 1) * iw
            val prevOff = y * iw
            for (x in 0 until w) {
                if (lum[rowOff + x] < threshold) rowSum++
                integral[outOff + x + 1] = integral[prevOff + x + 1] + rowSum
            }
        }
        fun ink(l: Int, t: Int, r: Int, b: Int): Int {
            val l2 = l.coerceIn(0, w); val r2 = r.coerceIn(0, w)
            val t2 = t.coerceIn(0, h); val b2 = b.coerceIn(0, h)
            if (r2 <= l2 || b2 <= t2) return 0
            return integral[b2 * iw + r2] - integral[t2 * iw + r2] - integral[b2 * iw + l2] + integral[t2 * iw + l2]
        }

        // Words used for matching, trimmed inwards so a box only covers its own letters.
        data class R(val l: Int, val t: Int, val r: Int, val b: Int)
        val rects = boxes
            .filter { it.chars >= 2 && (it.right - it.left) * w >= 8f && (it.bottom - it.top) * h >= 4f }
            .let { list -> if (list.size > 400) list.filterIndexed { idx, _ -> idx % (list.size / 400 + 1) == 0 } else list }
            .map { bx ->
                val bw = (bx.right - bx.left) * w
                val bh = (bx.bottom - bx.top) * h
                R(
                    ((bx.left * w) + bw * 0.10f).roundToInt(),
                    ((bx.top * h) + bh * 0.22f).roundToInt(),
                    ((bx.right * w) - bw * 0.10f).roundToInt(),
                    ((bx.bottom * h) - bh * 0.22f).roundToInt()
                )
            }
            .filter { it.r - it.l >= 4 && it.b - it.t >= 2 }
        if (rects.size < 8) return null
        val totalArea = rects.sumOf { (it.r - it.l).toLong() * (it.b - it.t) }.toFloat()
        val medianH = rects.map { it.b - it.t }.sorted()[rects.size / 2]

        // Page-wide match: the right offset puts the word boxes on letters, a wrong one on the
        // spaces between words and lines, so total ink inside the boxes peaks at the true offset.
        val maxDx = (w * 0.06f).roundToInt().coerceAtLeast(4)
        val maxDy = (medianH * 2.5f).roundToInt().coerceIn(3, (h * 0.04f).roundToInt().coerceAtLeast(3))
        fun score(dx: Int, dy: Int): Float {
            var acc = 0L
            for (r in rects) acc += ink(r.l + dx, r.t + dy, r.r + dx, r.b + dy)
            return acc / totalArea
        }
        val zero = score(0, 0)
        var best = zero
        var bestDx = 0
        var bestDy = 0
        val all = ArrayList<Float>((2 * maxDx + 1) * (2 * maxDy + 1))
        for (dy in -maxDy..maxDy) {
            for (dx in -maxDx..maxDx) {
                val sc = score(dx, dy)
                all.add(sc)
                if (sc > best + 1e-6f) { best = sc; bestDx = dx; bestDy = dy }
            }
        }
        val typical = all.sorted()[all.size / 2]
        val contrast = if (typical > 0f) best / typical else 0f
        // Move only when the peak is clearly better than leaving the text layer where it is.
        if (best < 0.05f || contrast < 1.25f || best < zero * 1.06f || (abs(bestDx) < 2 && abs(bestDy) < 2)) {
            return Shift(0f, 0f, rects.size, contrast)
        }
        return Shift(bestDx.toFloat() / w, bestDy.toFloat() / h, rects.size, contrast)
    }

    private fun median(values: List<Int>): Int {
        val s = values.sorted()
        return s[s.size / 2]
    }

    /** Applies [shift] to every word of [data]. */
    fun apply(data: PdfPageTextData, shift: Shift): PdfPageTextData {
        if (shift.dx == 0f && shift.dy == 0f) return data
        return data.copy(words = data.words.map { wd ->
            wd.copy(
                leftRatio = (wd.leftRatio + shift.dx).coerceIn(0f, 1f),
                rightRatio = (wd.rightRatio + shift.dx).coerceIn(0f, 1f),
                topRatio = (wd.topRatio + shift.dy).coerceIn(0f, 1f),
                bottomRatio = (wd.bottomRatio + shift.dy).coerceIn(0f, 1f)
            )
        })
    }

    private val cache = object : LinkedHashMap<String, Shift>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Shift>?): Boolean = size > 200
    }

    /**
     * Android entry point: measures the shift on [bitmap] (downsampled for speed), caches it under [key]
     * and returns [data] with its words moved onto the ink.
     */
    fun align(key: String, bitmap: android.graphics.Bitmap, data: PdfPageTextData): PdfPageTextData {
        if (data.words.size < 6 || bitmap.isRecycled) return data
        val cached = synchronized(cache) { cache[key] }
        val shift = cached ?: run {
            val scale = min(1f, 720f / bitmap.width.coerceAtLeast(1))
            val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
            val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            if (small !== bitmap) small.recycle()
            val lum = IntArray(px.size) { idx ->
                val c = px[idx]
                ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            }
            val boxes = data.words.map { Box(it.leftRatio, it.topRatio, it.rightRatio, it.bottomRatio, it.text.length) }
            val s = estimate(lum, w, h, boxes) ?: Shift(0f, 0f, 0, 0f)
            synchronized(cache) { cache[key] = s }
            s
        }
        if (shift.dx != 0f || shift.dy != 0f) {
            android.util.Log.i("PdfInkAligner", "page $key: shifting text layer by dx=${shift.dx} dy=${shift.dy} (${shift.samples} words, ${(shift.agreement * 100).toInt()}% agree)")
        }
        return apply(data, shift)
    }
}
