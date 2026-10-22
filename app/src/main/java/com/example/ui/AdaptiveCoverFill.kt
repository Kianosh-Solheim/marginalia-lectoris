package com.example.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Adaptive Cover Fill renderer for Grid view cards.
 *
 * Implements the exact adaptive cover fill algorithm:
 * 1. Measures the cover slot (W x H) and the image's natural size (iw x ih).
 * 2. Computes crop loss:
 *    a = iw / ih; b = W / H; loss = 1 - min(a / b, b / a)
 * 3. If loss <= maxCrop (0.25f):
 *    Expands the cover to fill the slot (`cover` fit, positioned at `center top` so titles at the top are preserved).
 * 4. Otherwise:
 *    Keeps the WHOLE cover visible, centred (`contain` fit), and fills the empty bands using an edge gradient:
 *    - Left/right empty bands: samples the outermost ~4px column of the cover's left edge and right edge,
 *      averages each strip into a tiny 1x10 bitmap with bilinear filtering (`isFilterBitmap = true`),
 *      and stretches that 1x10 bitmap across the respective empty band with smoothing enabled.
 *    - Top/bottom empty bands: samples the outermost ~4px row of the cover's top edge and bottom edge,
 *      averages each strip into a tiny 10x1 bitmap, and stretches it across the respective empty band.
 *    - Draws the full uncropped cover centred on top.
 * 5. Renders at slot size multiplied by device density (capped at 2x) and caches results by image key + slot dimensions.
 * 6. Silently falls back to plain `ContentScale.Fit` if anything fails or if the image has zero dimensions.
 */
object AdaptiveCoverFillRenderer {

    const val DEFAULT_MAX_CROP = 0.25f
    private const val EDGE_SAMPLE_PX = 4
    private const val EDGE_STOPS = 10

    // Memory cache keyed by "<cacheKey>_<canvasW>x<canvasH>_<maxCrop>"
    // Max size ~24 MB in KB units
    private val renderedCache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    data class FitRect(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float
    )

    /**
     * Computes target placement rectangle inside (W x H).
     * When [cover] is true, scales by max(W/iw, H/ih) and aligns horizontally centered, vertically at top (y = 0).
     * When [cover] is false, scales by min(W/iw, H/ih) and centers both horizontally and vertically.
     */
    fun fit(iw: Int, ih: Int, wSlot: Float, hSlot: Float, cover: Boolean): FitRect {
        val iwF = iw.toFloat()
        val ihF = ih.toFloat()
        val scale = if (cover) {
            max(wSlot / iwF, hSlot / ihF)
        } else {
            min(wSlot / iwF, hSlot / ihF)
        }
        val w = iwF * scale
        val h = ihF * scale
        val x = (wSlot - w) / 2f
        val y = if (cover) 0f else (hSlot - h) / 2f
        return FitRect(x = x, y = y, w = w, h = h)
    }

    /**
     * Computes the fraction of the image that would be cut off if it filled the slot:
     * a = iw / ih; b = W / H; loss = 1 - min(a / b, b / a)
     */
    fun cropLoss(iw: Int, ih: Int, wSlot: Float, hSlot: Float): Float {
        if (iw <= 0 || ih <= 0 || wSlot <= 0f || hSlot <= 0f) return 1f
        val a = iw.toFloat() / ih.toFloat()
        val b = wSlot / hSlot
        return 1f - min(a / b, b / a)
    }

    fun getCached(cacheKey: String, canvasW: Int, canvasH: Int, maxCrop: Float = DEFAULT_MAX_CROP): Bitmap? {
        val fullKey = "$cacheKey:${canvasW}x${canvasH}:$maxCrop"
        return renderedCache.get(fullKey)
    }

    /**
     * Renders the adaptive cover bitmap for a slot of [slotWidthPx] x [slotHeightPx] with density [density].
     * Caps effective devicePixelRatio at 2.0f as specified.
     */
    fun renderAdaptiveCover(
        source: Bitmap,
        cacheKey: String,
        slotWidthPx: Int,
        slotHeightPx: Int,
        density: Float,
        maxCrop: Float = DEFAULT_MAX_CROP
    ): Bitmap? {
        val iw = source.width
        val ih = source.height
        if (iw <= 0 || ih <= 0 || slotWidthPx <= 0 || slotHeightPx <= 0) return null

        // Target canvas size = CSS slot size * min(devicePixelRatio, 2).
        // Since slotWidthPx and slotHeightPx in Compose are already physical pixels (dp * density),
        // we cap the effective pixel scale at 2f relative to dp so high-DPI (3x+) screens cap at 2x.
        val dprScale = if (density > 2f) (2f / density) else 1f
        val canvasW = (slotWidthPx * dprScale).roundToInt().coerceAtLeast(1)
        val canvasH = (slotHeightPx * dprScale).roundToInt().coerceAtLeast(1)

        val fullKey = "$cacheKey:${canvasW}x${canvasH}:$maxCrop"
        renderedCache.get(fullKey)?.let { cached ->
            if (!cached.isRecycled) return cached
        }

        return try {
            val outBitmap = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(outBitmap)
            val wF = canvasW.toFloat()
            val hF = canvasH.toFloat()

            val smoothPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

            val loss = cropLoss(iw, ih, wF, hF)
            if (loss <= maxCrop) {
                // Step 3: loss <= 0.25 -> expand cover to fill the slot with object-fit: cover; object-position: center top
                val rect = fit(iw, ih, wF, hF, cover = true)
                val srcRect = Rect(0, 0, iw, ih)
                val dstRect = RectF(rect.x, rect.y, rect.x + rect.w, rect.y + rect.h)
                canvas.drawBitmap(source, srcRect, dstRect, smoothPaint)
            } else {
                // Step 4: keep the WHOLE cover visible, centred (contain), and fill empty bands using an edge gradient
                val r = fit(iw, ih, wF, hF, cover = false)

                // Fill left & right bands if horizontal empty bands exist
                if (r.x > 0.5f) {
                    fillBand(canvas, source, side = 'l', r = r, wSlot = wF, hSlot = hF, paint = smoothPaint)
                    fillBand(canvas, source, side = 'r', r = r, wSlot = wF, hSlot = hF, paint = smoothPaint)
                }
                // Fill top & bottom bands if vertical empty bands exist
                if (r.y > 0.5f) {
                    fillBand(canvas, source, side = 't', r = r, wSlot = wF, hSlot = hF, paint = smoothPaint)
                    fillBand(canvas, source, side = 'b', r = r, wSlot = wF, hSlot = hF, paint = smoothPaint)
                }

                // Draw the cover itself on top
                val srcRect = Rect(0, 0, iw, ih)
                // Expand destination rect by a sub-pixel (0.5px) or snap seamlessly so no hairline gap appears at the seam
                val dstRect = RectF(r.x, r.y, r.x + r.w, r.y + r.h)
                canvas.drawBitmap(source, srcRect, dstRect, smoothPaint)
            }

            renderedCache.put(fullKey, outBitmap)
            outBitmap
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Samples the outermost ~4px strip of the cover's edge ('l', 'r', 't', 'b'),
     * averages it down to 1x10 (for 'l'/'r') or 10x1 (for 't'/'b') with high-quality filtering,
     * and stretches that tiny canvas across the empty band with smoothing on.
     */
    private fun fillBand(
        canvas: Canvas,
        source: Bitmap,
        side: Char,
        r: FitRect,
        wSlot: Float,
        hSlot: Float,
        paint: Paint
    ) {
        val iw = source.width
        val ih = source.height
        val kX = min(EDGE_SAMPLE_PX, iw).coerceAtLeast(1)
        val kY = min(EDGE_SAMPLE_PX, ih).coerceAtLeast(1)
        val horiz = side == 'l' || side == 'r'

        val srcRect: Rect
        val dstRect: RectF

        // Overlap by 1px under the cover image so the seam between band and cover is completely invisible
        when (side) {
            'l' -> {
                srcRect = Rect(0, 0, kX, ih)
                dstRect = RectF(0f, 0f, ceil(r.x) + 1f, hSlot)
            }
            'r' -> {
                srcRect = Rect((iw - kX).coerceAtLeast(0), 0, iw, ih)
                val startX = (floor(r.x + r.w) - 1f).coerceAtLeast(0f)
                dstRect = RectF(startX, 0f, wSlot, hSlot)
            }
            't' -> {
                srcRect = Rect(0, 0, iw, kY)
                dstRect = RectF(0f, 0f, wSlot, ceil(r.y) + 1f)
            }
            'b' -> {
                srcRect = Rect(0, (ih - kY).coerceAtLeast(0), iw, ih)
                val startY = (floor(r.y + r.h) - 1f).coerceAtLeast(0f)
                dstRect = RectF(0f, startY, wSlot, hSlot)
            }
            else -> return
        }

        if (dstRect.width() <= 0f || dstRect.height() <= 0f) return

        // Create tiny 1x10 (side edges) or 10x1 (top/bottom edges) canvas to average the sampled strip into ~10 colour stops
        val tinyW = if (horiz) 1 else EDGE_STOPS
        val tinyH = if (horiz) EDGE_STOPS else 1

        // Average each stop directly from the sampled strip (multi-pixel area average + bilinear smoothing)
        // Drawing into the 1x10 / 10x1 bitmap via area-averaging ensures true averaging across all pixels in the 4px strip
        val tinyBitmap = createAveragedStripBitmap(
            source = source,
            srcRect = srcRect,
            tinyW = tinyW,
            tinyH = tinyH
        )

        val tinySrcRect = Rect(0, 0, tinyW, tinyH)
        canvas.drawBitmap(tinyBitmap, tinySrcRect, dstRect, paint)
    }

    /**
     * Averages the sampled strip (`srcRect`) of `source` into a `tinyW x tinyH` bitmap (1x10 or 10x1),
     * equivalent to drawing the strip into a tiny canvas with `imageSmoothingQuality = "high"`.
     */
    private fun createAveragedStripBitmap(
        source: Bitmap,
        srcRect: Rect,
        tinyW: Int,
        tinyH: Int
    ): Bitmap {
        val tinyBmp = Bitmap.createBitmap(tinyW, tinyH, Bitmap.Config.ARGB_8888)
        val stripW = srcRect.width()
        val stripH = srcRect.height()
        if (stripW <= 0 || stripH <= 0) return tinyBmp

        val pixels = IntArray(stripW * stripH)
        source.getPixels(pixels, 0, stripW, srcRect.left, srcRect.top, stripW, stripH)

        if (tinyW == 1) {
            // Vertical strip (1 x 10): average each vertical segment of (stripW x (stripH / 10))
            for (stop in 0 until tinyH) {
                val y0 = (stop * stripH) / tinyH
                val y1 = (((stop + 1) * stripH) / tinyH).coerceAtLeast(y0 + 1).coerceAtMost(stripH)
                var rSum = 0L
                var gSum = 0L
                var bSum = 0L
                var aSum = 0L
                var count = 0L
                for (y in y0 until y1) {
                    val rowOffset = y * stripW
                    for (x in 0 until stripW) {
                        val c = pixels[rowOffset + x]
                        aSum += (c ushr 24) and 0xFF
                        rSum += (c ushr 16) and 0xFF
                        gSum += (c ushr 8) and 0xFF
                        bSum += c and 0xFF
                        count++
                    }
                }
                val argb = if (count > 0L) {
                    val a = (aSum / count).toInt().coerceIn(0, 255)
                    val r = (rSum / count).toInt().coerceIn(0, 255)
                    val g = (gSum / count).toInt().coerceIn(0, 255)
                    val b = (bSum / count).toInt().coerceIn(0, 255)
                    (a shl 24) or (r shl 16) or (g shl 8) or b
                } else {
                    0xFFFFFFFF.toInt()
                }
                tinyBmp.setPixel(0, stop, argb)
            }
        } else {
            // Horizontal strip (10 x 1): average each horizontal segment of ((stripW / 10) x stripH)
            for (stop in 0 until tinyW) {
                val x0 = (stop * stripW) / tinyW
                val x1 = (((stop + 1) * stripW) / tinyW).coerceAtLeast(x0 + 1).coerceAtMost(stripW)
                var rSum = 0L
                var gSum = 0L
                var bSum = 0L
                var aSum = 0L
                var count = 0L
                for (y in 0 until stripH) {
                    val rowOffset = y * stripW
                    for (x in x0 until x1) {
                        val c = pixels[rowOffset + x]
                        aSum += (c ushr 24) and 0xFF
                        rSum += (c ushr 16) and 0xFF
                        gSum += (c ushr 8) and 0xFF
                        bSum += c and 0xFF
                        count++
                    }
                }
                val argb = if (count > 0L) {
                    val a = (aSum / count).toInt().coerceIn(0, 255)
                    val r = (rSum / count).toInt().coerceIn(0, 255)
                    val g = (gSum / count).toInt().coerceIn(0, 255)
                    val b = (bSum / count).toInt().coerceIn(0, 255)
                    (a shl 24) or (r shl 16) or (g shl 8) or b
                } else {
                    0xFFFFFFFF.toInt()
                }
                tinyBmp.setPixel(stop, 0, argb)
            }
        }
        return tinyBmp
    }
}

/**
 * Composable that renders a book/article cover inside the Grid view cover slot using the
 * Adaptive Cover Fill algorithm:
 * - Measures the slot (W x H) dynamically via `BoxWithConstraints` (automatically reacts to
 *   grid column count changes, screen rotation, tile scale toggles, and Grid/Cards switching).
 * - Preserves `altText` as accessibility `contentDescription` and `Role.Image`.
 * - Falls back silently to `ContentScale.Fit` while rendering or if anything fails.
 */
@Composable
fun AdaptiveGridCoverImage(
    bitmap: Bitmap,
    cacheKey: String,
    altText: String,
    modifier: Modifier = Modifier,
    maxCrop: Float = AdaptiveCoverFillRenderer.DEFAULT_MAX_CROP
) {
    val density = LocalDensity.current.density

    BoxWithConstraints(
        modifier = modifier
            .semantics {
                contentDescription = altText
                role = Role.Image
            }
            .testTag("adaptive_grid_cover_$cacheKey"),
        contentAlignment = Alignment.Center
    ) {
        val slotWidthPx = constraints.maxWidth
        val slotHeightPx = constraints.maxHeight

        val dprScale = if (density > 2f) (2f / density) else 1f
        val targetCanvasW = (slotWidthPx * dprScale).roundToInt().coerceAtLeast(1)
        val targetCanvasH = (slotHeightPx * dprScale).roundToInt().coerceAtLeast(1)

        var adaptiveBitmap by remember(cacheKey, bitmap, targetCanvasW, targetCanvasH, maxCrop) {
            mutableStateOf(
                if (slotWidthPx > 0 && slotHeightPx > 0) {
                    AdaptiveCoverFillRenderer.getCached(cacheKey, targetCanvasW, targetCanvasH, maxCrop)
                } else {
                    null
                }
            )
        }

        LaunchedEffect(cacheKey, bitmap, slotWidthPx, slotHeightPx, density, maxCrop) {
            if (slotWidthPx > 0 && slotHeightPx > 0 && bitmap.width > 0 && bitmap.height > 0) {
                val existing = AdaptiveCoverFillRenderer.getCached(cacheKey, targetCanvasW, targetCanvasH, maxCrop)
                if (existing != null && !existing.isRecycled) {
                    adaptiveBitmap = existing
                } else {
                    val rendered = withContext(Dispatchers.Default) {
                        AdaptiveCoverFillRenderer.renderAdaptiveCover(
                            source = bitmap,
                            cacheKey = cacheKey,
                            slotWidthPx = slotWidthPx,
                            slotHeightPx = slotHeightPx,
                            density = density,
                            maxCrop = maxCrop
                        )
                    }
                    adaptiveBitmap = rendered
                }
            }
        }

        val displayBitmap = adaptiveBitmap
        if (displayBitmap != null && !displayBitmap.isRecycled) {
            Image(
                bitmap = displayBitmap.asImageBitmap(),
                contentDescription = altText,
                contentScale = ContentScale.FillBounds,
                alignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Silent fallback to plain object-fit: contain (ContentScale.Fit)
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = altText,
                contentScale = ContentScale.Fit,
                alignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
