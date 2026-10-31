package com.example.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import com.example.reader.Speeches
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The speech page: the script, with room to write anywhere on it with the pen — over the text, in the
 * margins, or on the ruled space below. Text and writing scroll together (one finger, or two when
 * fingers draw).
 *
 * Writing is made for low latency: on BOOX tablets the e-ink controller draws the ink itself
 * ([OnyxPen]); elsewhere points are drawn straight into a bitmap as they arrive, smoothed with curves,
 * and only the changed area is redrawn. Ink is kept in page widths from the top of the speech, so it
 * keeps its shape when the page changes size. The pen draws; its eraser end (or the eraser tool)
 * removes whole strokes.
 */
@SuppressLint("ViewConstructor")
class InkCanvasView(context: Context) : View(context) {
    var onChanged: (() -> Unit)? = null
    var fingerDraws = false
    var eraser = false
        set(v) { field = v; updatePen() }
    /** True while something covers the page (a dialog) or the page is hidden: the BOOX pen then lets the pen through. */
    var paused = false
        set(v) { field = v; updatePen() }
    var showInk = true
        set(v) { field = v; inkDirty = true; invalidate(); onyx?.refresh() }
    var showText = true
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private val pad get() = 20f * density

    // ------------------------------------------------------------------ the script

    private var script = ""
    private var fontSp = 22
    private var layout: StaticLayout? = null
    /** Each displayed block: where it ends in the shown text and in the Markdown source. */
    private class Block(val shownEnd: Int, val sourceEnd: Int)
    private var blocks: List<Block> = emptyList()
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = Typeface.SERIF }

    fun setScript(text: String, fontSizeSp: Int) {
        if (text == script && fontSizeSp == fontSp && layout != null) return
        script = text; fontSp = fontSizeSp
        relayout(); invalidate()
    }

    private fun relayout() {
        val w = width - 2 * pad
        if (w <= 0) { layout = null; return }
        textPaint.textSize = fontSp * resources.displayMetrics.scaledDensity
        val sb = SpannableStringBuilder()
        val bl = ArrayList<Block>()
        var pos = 0
        var prevKind = -1
        val lines = script.split("\n")
        var i = 0
        fun srcEndOf(lineIdx: Int): Int { var e = 0; for (k in 0..lineIdx) e += lines[k].length + 1; return (e - 1).coerceAtMost(script.length) }
        while (i < lines.size) {
            val l = lines[i].trimEnd()
            if (l.isBlank()) { i++; prevKind = -1; continue }
            val heading = l.trimStart().startsWith("#")
            val listItem = !heading && Regex("^\\s*([-*+]|\\d+[.)])\\s+").containsMatchIn(l)
            var text: String
            var last = i
            when {
                heading -> text = l.trimStart().trimStart('#').trim()
                listItem -> text = "•  " + l.replace(Regex("^\\s*([-*+]|\\d+[.)])\\s+"), "")
                else -> {
                    val parts = arrayListOf(l.trim())
                    while (last + 1 < lines.size && lines[last + 1].isNotBlank() && !lines[last + 1].trimStart().startsWith("#") &&
                        !Regex("^\\s*([-*+]|\\d+[.)])\\s+").containsMatchIn(lines[last + 1])) { last++; parts += lines[last].trim() }
                    text = parts.joinToString(" ")
                }
            }
            text = text.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1").replace(Regex("(?<![*\\w])\\*(?!\\s)(.+?)\\*"), "$1")
            val kind = if (heading) 1 else if (listItem) 2 else 0
            if (sb.isNotEmpty()) sb.append(if (kind == 2 && prevKind == 2) "\n" else "\n\n")
            val start = sb.length
            sb.append(text)
            if (heading) { sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(RelativeSizeSpan(1.3f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (listItem) sb.setSpan(LeadingMarginSpan.Standard((8 * density).toInt(), (26 * density).toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            bl += Block(sb.length, srcEndOf(last))
            prevKind = kind
            i = last + 1
        }
        pos = sb.length
        blocks = bl
        layout = StaticLayout.Builder.obtain(sb, 0, pos, textPaint, w.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.35f).setIncludePad(true).build()
        clampScroll()
    }

    private val textHeight get() = (layout?.height ?: 0).toFloat()
    /** Where the ruled writing space starts (in pixels from the top of the speech). */
    private val writingTop get() = if (script.isBlank()) 0f else pad + textHeight + lineSpacingPx * 0.6f
    private val lineSpacingPx get() = 44f * density

    /**
     * Where writing at height [yPage] (page widths from the top) belongs in the Markdown source:
     * after the paragraph it is next to, or at the end when it is below the text.
     */
    fun sourceOffsetAt(yPage: Float): Int {
        val l = layout ?: return script.length
        val y = yPage * width - pad
        if (y >= l.height || blocks.isEmpty()) return script.length
        if (y < 0) return 0
        val shown = l.getLineEnd(l.getLineForVertical(y.toInt())) - 1
        return (blocks.firstOrNull { it.shownEnd >= shown } ?: blocks.last()).sourceEnd
    }

    // ------------------------------------------------------------------ scrolling

    private var scroll = 0f
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private var scrollId = -1
    private var lastScrollY = 0f
    private var scrolling = false

    private val contentHeight: Float get() {
        val inkBottom = strokes.maxOfOrNull { it.maxY() }?.times(width) ?: 0f
        return max(max(writingTop + height * 0.9f, inkBottom + height * 0.6f), height.toFloat())
    }

    private fun clampScroll() { scroll = scroll.coerceIn(0f, max(0f, contentHeight - height)) }

    private fun scrollTo(y: Float) {
        val old = scroll
        scroll = y; clampScroll()
        if (scroll != old) { inkDirty = true; invalidate() }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { scrollTo(scroller.currY.toFloat()); postInvalidateOnAnimation() }
        else if (scrolling && scrollId < 0) { scrolling = false; updatePen(); onyx?.refresh() }
    }

    fun scrollToTop() { scroller.forceFinished(true); scrollTo(0f) }

    // ------------------------------------------------------------------ BOOX pen engine

    private var onyx: OnyxPen? = null
    private val penListener = object : OnyxPen.Listener {
        override fun onStroke(points: FloatArray) {
            val w = width.toFloat().takeIf { it > 0 } ?: return
            val norm = FloatArray(points.size)
            for (k in 0 until points.size / 4) {
                norm[k * 4] = points[k * 4] / w; norm[k * 4 + 1] = (points[k * 4 + 1] + scroll) / w
                norm[k * 4 + 2] = points[k * 4 + 2]; norm[k * 4 + 3] = points[k * 4 + 3]
            }
            val dur = norm[norm.size - 1].toLong()
            val st = Speeches.Stroke(norm, start = System.currentTimeMillis() - dur)
            strokes.add(st); history.add(Action.Add(st))
            bmpCanvas?.let { drawStroke(it, st, w) }
            if (!showInk) showInk = true
            onChanged?.invoke()
        }
        override fun onErase(points: FloatArray) {
            var any = false
            for (k in 0 until points.size / 2) if (eraseAt(points[k * 2], points[k * 2 + 1], refresh = false)) any = true
            if (any) { inkDirty = true; invalidate(); onChanged?.invoke() }
            onyx?.refresh()
        }
        override fun onPenUp() { invalidate() }
    }
    /** Whether the fast BOOX pen engine is in use. */
    val hardwarePen get() = onyx != null

    private fun updatePen() { onyx?.setEnabled(!eraser && !paused && !scrolling && visibility == VISIBLE) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (onyx == null) onyx = OnyxPen.create(this, penListener)
        if (width > 0 && height > 0) post { onyx?.open(width, height, baseWidth); updatePen() }
    }

    override fun onDetachedFromWindow() {
        onyx?.close(); onyx = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updatePen()
    }

    // ------------------------------------------------------------------ ink

    private val strokes = ArrayList<Speeches.Stroke>()
    private sealed class Action { class Add(val s: Speeches.Stroke) : Action(); class Erase(val items: List<Pair<Int, Speeches.Stroke>>) : Action() }
    private val history = ArrayList<Action>()

    private var bmp: Bitmap? = null
    private var bmpCanvas: Canvas? = null
    private var inkDirty = true
    private val baseWidth get() = 2.2f * density
    private val inkPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true }
    private val linePaint = Paint().apply { color = Color.rgb(0xE4, 0xE4, 0xE7); strokeWidth = 1f }

    // The stroke being written (in view pixels while writing).
    private var pts = FloatArray(512)
    private var n = 0
    private var startT = 0L
    private var activeId = -1
    private var erasing = false

    init { setBackgroundColor(Color.WHITE); isFocusable = false }

    fun setStrokes(list: List<Speeches.Stroke>) {
        strokes.clear(); strokes.addAll(list); history.clear()
        clampScroll(); inkDirty = true; invalidate(); onyx?.refresh()
    }

    fun strokes(): List<Speeches.Stroke> = strokes.toList()
    fun isEmpty() = strokes.isEmpty()

    fun undo() {
        when (val a = history.removeLastOrNull() ?: return) {
            is Action.Add -> strokes.remove(a.s)
            is Action.Erase -> a.items.sortedBy { it.first }.forEach { (i, s) -> strokes.add(i.coerceAtMost(strokes.size), s) }
        }
        inkDirty = true; invalidate(); onyx?.refresh(); onChanged?.invoke()
    }

    fun clear() {
        if (strokes.isEmpty()) return
        history.add(Action.Erase(strokes.mapIndexed { i, s -> i to s }))
        strokes.clear(); inkDirty = true; invalidate(); onyx?.refresh(); onChanged?.invoke()
    }

    /** Removes these strokes (after they were converted to text); Undo brings them back. */
    fun remove(list: Collection<Speeches.Stroke>) {
        val set = list.toSet()
        val gone = strokes.mapIndexedNotNull { i, s -> if (s in set) i to s else null }
        if (gone.isEmpty()) return
        history.add(Action.Erase(gone))
        strokes.removeAll(set); inkDirty = true; invalidate(); onyx?.refresh(); onChanged?.invoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        bmp?.recycle()
        bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmpCanvas = Canvas(it) }
        relayout()
        inkDirty = true
        post { onyx?.open(w, h, baseWidth); updatePen() }
    }

    private fun widthFor(pressure: Float, scale: Float) = baseWidth * scale * (0.55f + 0.9f * pressure.coerceIn(0f, 1f))

    private fun redrawInk() {
        val c = bmpCanvas ?: return
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        inkDirty = false
        if (!showInk) return
        val w = width.toFloat()
        val top = scroll / w; val bottom = (scroll + height) / w
        strokes.forEach { s -> if (s.maxY() >= top - 0.05f && s.minY() <= bottom + 0.05f) drawStroke(c, s, w) }
    }

    /** Draws a stroke (page widths, from the top of the speech) into the visible area. */
    private fun drawStroke(c: Canvas, s: Speeches.Stroke, w: Float) {
        if (s.count == 0) return
        val oy = -scroll
        if (s.count == 1) {
            inkPaint.strokeWidth = widthFor(s.p(0), s.width)
            c.drawPoint(s.x(0) * w, s.y(0) * w + oy, inkPaint); return
        }
        for (i in 1 until s.count) segment(c, s.x(i - 1) * w, s.y(i - 1) * w + oy, s.x(i) * w, s.y(i) * w + oy,
            if (i >= 2) s.x(i - 2) * w else Float.NaN, if (i >= 2) s.y(i - 2) * w + oy else Float.NaN, s.p(i), s.width, null)
        val l = s.count - 1
        inkPaint.strokeWidth = widthFor(s.p(l), s.width)
        c.drawLine((s.x(l - 1) + s.x(l)) / 2f * w, (s.y(l - 1) + s.y(l)) / 2f * w + oy, s.x(l) * w, s.y(l) * w + oy, inkPaint)
    }

    private val seg = Path()
    /** Draws the piece between the midpoints of (a→b) and (prev→a), curving through a. Grows [dirty] by its bounds. */
    private fun segment(c: Canvas, ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float, p: Float, scale: Float, dirty: RectF?) {
        val sw = widthFor(p, scale)
        inkPaint.strokeWidth = sw
        seg.reset()
        val m2x = (ax + bx) / 2f; val m2y = (ay + by) / 2f
        if (px.isNaN()) { seg.moveTo(ax, ay); seg.lineTo(m2x, m2y) }
        else { seg.moveTo((px + ax) / 2f, (py + ay) / 2f); seg.quadTo(ax, ay, m2x, m2y) }
        c.drawPath(seg, inkPaint)
        dirty?.let {
            val l = min(min(ax, m2x), if (px.isNaN()) ax else (px + ax) / 2f) - sw
            val t = min(min(ay, m2y), if (py.isNaN()) ay else (py + ay) / 2f) - sw
            val r = max(max(ax, m2x), if (px.isNaN()) ax else (px + ax) / 2f) + sw
            val b = max(max(ay, m2y), if (py.isNaN()) ay else (py + ay) / 2f) + sw
            if (it.isEmpty) it.set(l, t, r, b) else it.union(l, t, r, b)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (inkDirty && n == 0) redrawInk()
        // Ruled space for writing below the text.
        val wt = writingTop
        var y = if (wt <= scroll) wt + (((scroll - wt) / lineSpacingPx).toInt() + 1) * lineSpacingPx else wt + lineSpacingPx
        while (y - scroll < height) { canvas.drawLine(pad * 0.5f, y - scroll, width - pad * 0.5f, y - scroll, linePaint); y += lineSpacingPx }
        if (showText) layout?.let { l ->
            canvas.save(); canvas.translate(pad, pad - scroll); l.draw(canvas); canvas.restore()
        }
        if (script.isBlank() && strokes.isEmpty() && n == 0) {
            textPaint.textSize = 16 * resources.displayMetrics.scaledDensity
            val hint = textPaint.color; textPaint.color = Color.rgb(0x9C, 0xA3, 0xAF)
            canvas.drawText("Write here with the pen, type the speech (Edit), or load a file.", pad, pad + 22 * density, textPaint)
            textPaint.color = hint; textPaint.textSize = fontSp * resources.displayMetrics.scaledDensity
        }
        bmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    private fun isEraser(e: MotionEvent, i: Int) = eraser || e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER ||
        (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0

    private fun addPoint(x: Float, y: Float, p: Float, t: Long) {
        if (n * 4 + 4 > pts.size) pts = pts.copyOf(pts.size * 2)
        pts[n * 4] = x; pts[n * 4 + 1] = y; pts[n * 4 + 2] = p; pts[n * 4 + 3] = (t - startT).toFloat()
        n++
    }

    private val dirty = RectF()
    private val dirtyI = Rect()

    private fun drawLatest() {
        val c = bmpCanvas ?: return
        if (n < 2) return
        val i = n - 1
        segment(c, pts[(i - 1) * 4], pts[(i - 1) * 4 + 1], pts[i * 4], pts[i * 4 + 1],
            if (i >= 2) pts[(i - 2) * 4] else Float.NaN, if (i >= 2) pts[(i - 2) * 4 + 1] else Float.NaN, pts[i * 4 + 2], 1f, dirty)
    }

    /** [x], [y] in view pixels. */
    private fun eraseAt(x: Float, y: Float, refresh: Boolean = true): Boolean {
        val w = width.toFloat().takeIf { it > 0 } ?: return false
        val r = 14f * density
        val dy = y + scroll
        val hit = ArrayList<Pair<Int, Speeches.Stroke>>()
        strokes.forEachIndexed { idx, s ->
            for (k in 0 until s.count) if (hypot(s.x(k) * w - x, s.y(k) * w - dy) <= r) { hit += idx to s; break }
        }
        if (hit.isEmpty()) return false
        history.add(Action.Erase(hit))
        strokes.removeAll(hit.map { it.second }.toSet())
        if (refresh) { inkDirty = true; invalidate(); onyx?.refresh(); onChanged?.invoke() }
        return true
    }

    private fun isPen(e: MotionEvent, i: Int) = e.getToolType(i) == MotionEvent.TOOL_TYPE_STYLUS || e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER

    /** Finger scrolling: one finger, or two when fingers draw. */
    private fun handleScroll(e: MotionEvent): Boolean {
        val vt = velocity ?: VelocityTracker.obtain().also { velocity = it }
        vt.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                scroller.forceFinished(true)
                scrollId = e.getPointerId(e.actionIndex); lastScrollY = e.getY(e.actionIndex)
                if (!scrolling) { scrolling = true; updatePen() }
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(scrollId).takeIf { it >= 0 } ?: return true
                val y = e.getY(i)
                scrollTo(scroll + (lastScrollY - y)); lastScrollY = y
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == scrollId) {
                val k = if (e.actionIndex == 0) 1 else 0
                scrollId = e.getPointerId(k); lastScrollY = e.getY(k)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt.computeCurrentVelocity(1000)
                val vy = -vt.getYVelocity(scrollId.coerceAtLeast(0))
                scrollId = -1
                vt.recycle(); velocity = null
                if (abs(vy) > 400 * density) {
                    scroller.fling(0, scroll.toInt(), 0, vy.toInt(), 0, 0, 0, max(0f, contentHeight - height).toInt())
                    postInvalidateOnAnimation()
                } else { scrolling = false; updatePen(); onyx?.refresh() }
            }
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (paused) return false
        // Fingers: scroll (one finger, or two when fingers draw).
        if (activeId < 0 && (scrollId >= 0 || (!isPen(e, e.actionIndex.coerceAtMost(e.pointerCount - 1)) && (!fingerDraws || e.pointerCount >= 2)))) {
            if (scrollId >= 0 || e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) return handleScroll(e)
        }
        // The BOOX engine already takes the pen (unless the on-screen eraser is on): ignore the pen's ordinary events.
        if (onyx != null && !eraser && activeId < 0 && e.getToolType(e.actionIndex.coerceAtMost(e.pointerCount - 1)) == MotionEvent.TOOL_TYPE_STYLUS) return true
        val idx = if (activeId >= 0) e.findPointerIndex(activeId) else e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (activeId >= 0) return true
                val i = e.actionIndex
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) requestUnbufferedDispatch(e)
                parent?.requestDisallowInterceptTouchEvent(true)
                activeId = e.getPointerId(i)
                erasing = isEraser(e, i)
                if (erasing) { eraseAt(e.getX(i), e.getY(i)); return true }
                if (!showInk) showInk = true
                n = 0; startT = e.eventTime
                dirty.setEmpty()
                addPoint(e.getX(i), e.getY(i), e.getPressure(i), e.eventTime)
                inkPaint.strokeWidth = widthFor(e.getPressure(i), 1f)
                bmpCanvas?.drawPoint(e.getX(i), e.getY(i), inkPaint)
                val sw = inkPaint.strokeWidth
                invalidate((e.getX(i) - sw).toInt(), (e.getY(i) - sw).toInt(), (e.getX(i) + sw + 1).toInt(), (e.getY(i) + sw + 1).toInt())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeId < 0 || idx < 0) return false
                if (erasing) {
                    for (h in 0 until e.historySize) eraseAt(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
                    eraseAt(e.getX(idx), e.getY(idx)); return true
                }
                dirty.setEmpty()
                for (h in 0 until e.historySize) {
                    addPoint(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h), e.getHistoricalPressure(idx, h), e.getHistoricalEventTime(h))
                    drawLatest()
                }
                addPoint(e.getX(idx), e.getY(idx), e.getPressure(idx), e.eventTime)
                drawLatest()
                if (!dirty.isEmpty) { dirty.roundOut(dirtyI); invalidate(dirtyI) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeId < 0) return false
                if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && e.getPointerId(e.actionIndex) != activeId) return true
                activeId = -1
                if (erasing) { erasing = false; return true }
                if (e.actionMasked != MotionEvent.ACTION_CANCEL && idx >= 0) {
                    val lx = e.getX(idx); val ly = e.getY(idx)
                    if (n == 0 || pts[(n - 1) * 4] != lx || pts[(n - 1) * 4 + 1] != ly) { addPoint(lx, ly, e.getPressure(idx), e.eventTime); dirty.setEmpty(); drawLatest() }
                    if (n >= 2) {
                        val i = n - 1
                        val mx = (pts[(i - 1) * 4] + pts[i * 4]) / 2f; val my = (pts[(i - 1) * 4 + 1] + pts[i * 4 + 1]) / 2f
                        inkPaint.strokeWidth = widthFor(pts[i * 4 + 2], 1f)
                        bmpCanvas?.drawLine(mx, my, pts[i * 4], pts[i * 4 + 1], inkPaint)
                    }
                }
                val w = width.toFloat()
                if (n > 0 && w > 0) {
                    val norm = FloatArray(n * 4)
                    for (k in 0 until n) { norm[k * 4] = pts[k * 4] / w; norm[k * 4 + 1] = (pts[k * 4 + 1] + scroll) / w; norm[k * 4 + 2] = pts[k * 4 + 2]; norm[k * 4 + 3] = pts[k * 4 + 3] }
                    // When it was written, in wall-clock time (event times count from boot).
                    val start = System.currentTimeMillis() - (SystemClock.uptimeMillis() - startT)
                    val s = Speeches.Stroke(norm, start = start)
                    strokes.add(s); history.add(Action.Add(s))
                    onChanged?.invoke()
                }
                n = 0
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
