package com.example.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.View
import com.example.reader.Speeches
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A page to write on with the pen, made for low latency: points (including the in-between points
 * the system batches) are drawn straight into a bitmap as they arrive, smoothed with curves through
 * the midpoints, and only the small changed area is redrawn — which suits e-ink screens too.
 *
 * Coordinates are kept in page widths, so the writing keeps its shape when the page changes size.
 * The pen draws; its eraser end (or the eraser tool) removes whole strokes; fingers only draw when
 * [fingerDraws] is on, so a resting hand leaves no marks.
 */
@SuppressLint("ViewConstructor")
class InkCanvasView(context: Context) : View(context) {
    var onChanged: (() -> Unit)? = null
    var fingerDraws = false
    var eraser = false
        set(v) { field = v; updatePen() }
    /** True while something covers the page (a dialog): the BOOX pen engine then lets the pen through. */
    var paused = false
        set(v) { field = v; updatePen() }

    // BOOX pen engine (null elsewhere): draws the ink on the e-ink screen itself, almost without delay.
    private var onyx: OnyxPen? = null
    private val penListener = object : OnyxPen.Listener {
        override fun onStroke(points: FloatArray) {
            val w = width.toFloat().takeIf { it > 0 } ?: return
            val norm = FloatArray(points.size)
            for (k in 0 until points.size / 4) { norm[k * 4] = points[k * 4] / w; norm[k * 4 + 1] = points[k * 4 + 1] / w; norm[k * 4 + 2] = points[k * 4 + 2]; norm[k * 4 + 3] = points[k * 4 + 3] }
            val st = Speeches.Stroke(norm)
            strokes.add(st); history.add(Action.Add(st))
            bmpCanvas?.let { drawStroke(it, st, w) }
            onChanged?.invoke()
        }
        override fun onErase(points: FloatArray) {
            var any = false
            for (k in 0 until points.size / 2) if (eraseAt(points[k * 2], points[k * 2 + 1], refresh = false)) any = true
            if (any) { redrawAll(); onChanged?.invoke() }
            onyx?.refresh()
        }
        override fun onPenUp() { invalidate() }
    }
    /** Whether the fast BOOX pen engine is in use. */
    val hardwarePen get() = onyx != null

    private fun updatePen() { onyx?.setEnabled(!eraser && !paused) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (onyx == null) onyx = OnyxPen.create(this, penListener)
        if (width > 0 && height > 0) post { onyx?.open(width, height, baseWidth); updatePen() }
    }

    override fun onDetachedFromWindow() {
        onyx?.close(); onyx = null
        super.onDetachedFromWindow()
    }
    /** Ruled line distance in page widths (also used to split lines when converting to text). */
    val lineHeight get() = if (width > 0) lineSpacingPx / width else 0.06f
    private val lineSpacingPx get() = 48f * resources.displayMetrics.density

    private val strokes = ArrayList<Speeches.Stroke>()
    private sealed class Action { class Add(val s: Speeches.Stroke) : Action(); class Erase(val items: List<Pair<Int, Speeches.Stroke>>) : Action() }
    private val history = ArrayList<Action>()

    private var bmp: Bitmap? = null
    private var bmpCanvas: Canvas? = null
    private val baseWidth get() = 2.2f * resources.displayMetrics.density
    private val inkPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true }
    private val linePaint = Paint().apply { color = Color.rgb(0xE4, 0xE4, 0xE7); strokeWidth = 1f }

    // The stroke being written (in pixels while writing).
    private var pts = FloatArray(512)
    private var n = 0
    private var startT = 0L
    private var activeId = -1
    private var erasing = false

    init { setBackgroundColor(Color.WHITE); isFocusable = false }

    fun setStrokes(list: List<Speeches.Stroke>) {
        strokes.clear(); strokes.addAll(list); history.clear()
        redrawAll(); onyx?.refresh()
    }

    fun strokes(): List<Speeches.Stroke> = strokes.toList()
    fun canUndo() = history.isNotEmpty()
    fun isEmpty() = strokes.isEmpty()

    fun undo() {
        when (val a = history.removeLastOrNull() ?: return) {
            is Action.Add -> strokes.remove(a.s)
            is Action.Erase -> a.items.sortedBy { it.first }.forEach { (i, s) -> strokes.add(i.coerceAtMost(strokes.size), s) }
        }
        redrawAll(); onyx?.refresh(); onChanged?.invoke()
    }

    fun clear() {
        if (strokes.isEmpty()) return
        history.add(Action.Erase(strokes.mapIndexed { i, s -> i to s }))
        strokes.clear(); redrawAll(); onyx?.refresh(); onChanged?.invoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        bmp?.recycle()
        bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmpCanvas = Canvas(it) }
        redrawAll()
        post { onyx?.open(w, h, baseWidth); updatePen() }
    }

    private fun widthFor(pressure: Float, scale: Float) = baseWidth * scale * (0.55f + 0.9f * pressure.coerceIn(0f, 1f))

    private fun redrawAll() {
        val c = bmpCanvas ?: return
        c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        val w = width.toFloat()
        strokes.forEach { s -> drawStroke(c, s, w) }
        invalidate()
    }

    private fun drawStroke(c: Canvas, s: Speeches.Stroke, w: Float) {
        if (s.count == 0) return
        if (s.count == 1) {
            inkPaint.strokeWidth = widthFor(s.p(0), s.width)
            c.drawPoint(s.x(0) * w, s.y(0) * w, inkPaint); return
        }
        for (i in 1 until s.count) segment(c, s.x(i - 1) * w, s.y(i - 1) * w, s.x(i) * w, s.y(i) * w,
            if (i >= 2) s.x(i - 2) * w else Float.NaN, if (i >= 2) s.y(i - 2) * w else Float.NaN, s.p(i), s.width, null)
        val l = s.count - 1
        inkPaint.strokeWidth = widthFor(s.p(l), s.width)
        c.drawLine((s.x(l - 1) + s.x(l)) / 2f * w, (s.y(l - 1) + s.y(l)) / 2f * w, s.x(l) * w, s.y(l) * w, inkPaint)
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
        var y = lineSpacingPx
        while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, linePaint); y += lineSpacingPx }
        bmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    private fun allowed(e: MotionEvent, i: Int): Boolean = when (e.getToolType(i)) {
        MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> true
        MotionEvent.TOOL_TYPE_FINGER -> fingerDraws
        else -> true
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

    private fun eraseAt(x: Float, y: Float, refresh: Boolean = true): Boolean {
        val w = width.toFloat().takeIf { it > 0 } ?: return false
        val r = 14f * resources.displayMetrics.density
        val hit = ArrayList<Pair<Int, Speeches.Stroke>>()
        strokes.forEachIndexed { idx, s ->
            for (k in 0 until s.count) if (hypot(s.x(k) * w - x, s.y(k) * w - y) <= r) { hit += idx to s; break }
        }
        if (hit.isEmpty()) return false
        history.add(Action.Erase(hit))
        strokes.removeAll(hit.map { it.second }.toSet())
        if (refresh) { redrawAll(); onyx?.refresh(); onChanged?.invoke() }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (paused) return false
        // The BOOX engine already takes the pen (unless the on-screen eraser is on): ignore the pen's ordinary events.
        if (onyx != null && !eraser && activeId < 0 && e.actionMasked == MotionEvent.ACTION_DOWN && e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) return true
        if (onyx != null && !eraser && activeId < 0 && e.actionMasked != MotionEvent.ACTION_DOWN && e.getToolType(e.actionIndex.coerceAtMost(e.pointerCount - 1)) == MotionEvent.TOOL_TYPE_STYLUS) return true
        val idx = if (activeId >= 0) e.findPointerIndex(activeId) else e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (activeId >= 0) return true
                val i = e.actionIndex
                if (!allowed(e, i)) return false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) requestUnbufferedDispatch(e)
                parent?.requestDisallowInterceptTouchEvent(true)
                activeId = e.getPointerId(i)
                erasing = isEraser(e, i)
                if (erasing) { eraseAt(e.getX(i), e.getY(i)); return true }
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
                    // Finish the curve at the pen's last point.
                    if (n >= 2) {
                        val c = bmpCanvas
                        val i = n - 1
                        val mx = (pts[(i - 1) * 4] + pts[i * 4]) / 2f; val my = (pts[(i - 1) * 4 + 1] + pts[i * 4 + 1]) / 2f
                        inkPaint.strokeWidth = widthFor(pts[i * 4 + 2], 1f)
                        c?.drawLine(mx, my, pts[i * 4], pts[i * 4 + 1], inkPaint)
                    }
                }
                val w = width.toFloat()
                if (n > 0 && w > 0) {
                    val norm = FloatArray(n * 4)
                    for (k in 0 until n) { norm[k * 4] = pts[k * 4] / w; norm[k * 4 + 1] = pts[k * 4 + 1] / w; norm[k * 4 + 2] = pts[k * 4 + 2]; norm[k * 4 + 3] = pts[k * 4 + 3] }
                    val s = Speeches.Stroke(norm)
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
