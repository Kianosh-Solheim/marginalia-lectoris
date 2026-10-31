package com.example.ui

import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/**
 * BOOX pen engine (Onyx Pen SDK): the e-ink controller draws the ink itself as the pen moves, with
 * almost no delay, and hands the finished stroke to the app. Only on BOOX devices; elsewhere
 * [create] returns null and the ordinary drawing is used.
 *
 * Points are in the view's own pixels; pressure is 0…1.
 */
class OnyxPen private constructor(private val view: View, private val listener: Listener) {
    interface Listener {
        fun onStroke(points: FloatArray)       // x, y, pressure, time (ms from the stroke's start) per point
        fun onErase(points: FloatArray)        // x, y pairs touched by the pen's eraser end
        fun onPenUp()
    }

    private var helper: TouchHelper? = null
    private var open = false
    private var enabled = true
    private val maxPressure: Float by lazy {
        runCatching { EpdController.getMaxTouchPressure() }.getOrDefault(0f).takeIf { it > 0f } ?: 4096f
    }

    private fun convert(list: List<TouchPoint>?, withPressure: Boolean): FloatArray? {
        if (list.isNullOrEmpty()) return null
        val t0 = list.first().timestamp
        val step = if (withPressure) 4 else 2
        val out = FloatArray(list.size * step)
        list.forEachIndexed { i, p ->
            out[i * step] = p.x; out[i * step + 1] = p.y
            if (withPressure) { out[i * step + 2] = (p.pressure / maxPressure).coerceIn(0f, 1f); out[i * step + 3] = (p.timestamp - t0).toFloat() }
        }
        return out
    }

    private val callback = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, p: TouchPoint?) {}
        override fun onEndRawDrawing(b: Boolean, p: TouchPoint?) {}
        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint?) {}
        override fun onRawDrawingTouchPointListReceived(list: TouchPointList?) {
            val pts = convert(list?.points, true) ?: return
            view.post { listener.onStroke(pts) }
        }
        override fun onBeginRawErasing(b: Boolean, p: TouchPoint?) {}
        override fun onEndRawErasing(b: Boolean, p: TouchPoint?) {}
        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint?) {}
        override fun onRawErasingTouchPointListReceived(list: TouchPointList?) {
            val pts = convert(list?.points, false) ?: return
            view.post { listener.onErase(pts) }
        }
        override fun onPenUpRefresh(rect: android.graphics.RectF?) { view.post { listener.onPenUp() } }
    }

    /** Opens the pen area over the whole view (call when its size is known). */
    fun open(width: Int, height: Int, strokeWidthPx: Float) {
        val h = helper ?: return
        runCatching {
            if (open) h.closeRawDrawing()
            h.setStrokeWidth(strokeWidthPx).setLimitRect(Rect(0, 0, width, height), ArrayList()).openRawDrawing()
            h.setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL)
            h.setStrokeColor(android.graphics.Color.BLACK)
            h.setRawDrawingRenderEnabled(true)
            h.setRawDrawingEnabled(enabled)
            open = true
        }.onFailure { Log.w(TAG, "Could not open the BOOX pen", it) }
    }

    /** Off while something covers the page (a dialog) or the on-screen eraser is used. */
    fun setEnabled(on: Boolean) {
        enabled = on
        val h = helper ?: return
        if (open) runCatching { h.setRawDrawingEnabled(on) }
    }

    /** Clears the controller's own ink layer so the page's redrawn content shows (after undo, erase, page change). */
    fun refresh() {
        val h = helper ?: return
        if (open && enabled) runCatching { h.setRawDrawingEnabled(false); h.setRawDrawingEnabled(true) }
    }

    fun close() {
        val h = helper ?: return
        runCatching { h.setRawDrawingEnabled(false); h.closeRawDrawing() }
        open = false
    }

    companion object {
        private const val TAG = "OnyxPen"
        @Volatile private var bypassed = false

        fun isBoox(): Boolean = Build.MANUFACTURER.contains("onyx", true) || Build.BRAND.contains("onyx", true) || Build.BRAND.contains("boox", true)

        fun create(view: View, listener: Listener): OnyxPen? {
            if (!isBoox()) return null
            return runCatching {
                if (!bypassed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("") }
                    bypassed = true
                }
                OnyxPen(view, listener).also { it.helper = TouchHelper.create(view, it.callback) }
            }.onFailure { Log.w(TAG, "BOOX pen unavailable; using the normal drawing", it) }.getOrNull()
        }
    }
}
