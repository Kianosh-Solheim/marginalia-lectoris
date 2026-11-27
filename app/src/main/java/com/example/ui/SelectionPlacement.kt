package com.example.ui

import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Where the current text selection is on screen, in root (window) pixels: its top and bottom.
 * Set by the reader that owns the selection (the classic EPUB paragraph, the PDF page), read by the
 * selection bar so it opens right below the selection, or above it near the foot of the page.
 */
object SelectionAnchor {
    var topRoot by mutableStateOf<Float?>(null)
        private set
    var bottomRoot by mutableStateOf<Float?>(null)
        private set
    private var owner: Any? = null

    fun set(owner: Any, top: Float, bottom: Float) {
        this.owner = owner
        if (topRoot != top) topRoot = top
        if (bottomRoot != bottom) bottomRoot = bottom
    }

    fun clear(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            topRoot = null
            bottomRoot = null
        }
    }
}

/**
 * Places the selection bar right below the selection ([anchorTopRoot]..[anchorBottomRoot], root px),
 * or right above it when there is no room below; at the foot of its box when the place is unknown.
 * Use with `.align(Alignment.TopCenter)` inside the reader's full-size Box.
 */
fun Modifier.selectionBarPlacement(anchorTopRoot: Float?, anchorBottomRoot: Float?): Modifier = composed {
    var parent by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var barH by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val gap = with(density) { 12.dp.roundToPx() }
    val edge = with(density) { 8.dp.roundToPx() }
    this
        .onPlaced { parent = it.parentLayoutCoordinates }
        .onSizeChanged { barH = it.height }
        .offset {
            val p = parent
            if (p == null || !p.isAttached || barH == 0) return@offset IntOffset(0, 0)
            val hostTop = p.positionInRoot().y
            val hostH = p.size.height
            val lowest = (hostH - barH - edge).coerceAtLeast(0)
            val top = anchorTopRoot
            val bottom = anchorBottomRoot
            val y = if (top == null || bottom == null) lowest else {
                val below = (bottom - hostTop).toInt() + gap
                val above = (top - hostTop).toInt() - gap - barH
                when {
                    below + barH <= hostH - edge -> below
                    above >= edge -> above
                    else -> lowest
                }.coerceIn(0, lowest)
            }
            IntOffset(0, y)
        }
}
