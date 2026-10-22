package com.example.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.TextLayoutResult
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Checks whether a character should be treated as part of a selectable word.
 * Includes letters, digits, and internal word connectors like apostrophes and hyphens.
 */
private fun isSelectableWordChar(text: String, index: Int): Boolean {
    if (index !in text.indices) return false
    val c = text[index]
    if (Character.isLetterOrDigit(c)) return true
    if (c == '\'' || c == '’' || c == '-') {
        val prevIsWord = index > 0 && Character.isLetterOrDigit(text[index - 1])
        val nextIsWord = index + 1 < text.length && Character.isLetterOrDigit(text[index + 1])
        return prevIsWord && nextIsWord
    }
    return false
}

/**
 * Finds the start (inclusive) and end (exclusive) character indices of the word at or nearest to [charIndex].
 * If [charIndex] falls on whitespace or punctuation, snaps to the nearest adjacent word so that
 * touching slightly beside a word still reliably selects it.
 */
fun findWordBoundaries(text: String, charIndex: Int): Pair<Int, Int> {
    if (text.isEmpty()) return Pair(0, 0)
    var idx = charIndex.coerceIn(0, text.length - 1)

    // If the touched character is whitespace/punctuation, search nearby for the closest word character
    if (!isSelectableWordChar(text, idx)) {
        var found = -1
        for (delta in 1..6) {
            if (idx - delta >= 0 && isSelectableWordChar(text, idx - delta)) {
                found = idx - delta
                break
            }
            if (idx + delta < text.length && isSelectableWordChar(text, idx + delta)) {
                found = idx + delta
                break
            }
        }
        if (found != -1) {
            idx = found
        } else {
            return Pair(idx, (idx + 1).coerceAtMost(text.length))
        }
    }

    var start = idx
    while (start > 0 && isSelectableWordChar(text, start - 1)) {
        start--
    }
    var end = idx
    while (end < text.length && isSelectableWordChar(text, end)) {
        end++
    }
    return Pair(start, maxOf(end, start + 1))
}

/**
 * Safely maps a pointer [position] to a character offset in [textLayoutResult] by clamping
 * the coordinates within the layout bounds. This ensures dragging slightly outside the paragraph
 * box during multi-word or multi-line selection continues tracking smoothly.
 */
fun getClampedCharOffset(
    layout: TextLayoutResult,
    position: Offset,
    textLength: Int
): Int {
    if (textLength <= 0) return 0
    val maxX = layout.size.width.toFloat().coerceAtLeast(1f)
    val maxY = layout.size.height.toFloat().coerceAtLeast(1f)
    val clamped = Offset(
        x = position.x.coerceIn(0f, maxX),
        y = position.y.coerceIn(0f, maxY)
    )
    return layout.getOffsetForPosition(clamped).coerceIn(0, textLength)
}

/**
 * Custom gesture detector for text selection in the reader screen that prioritizes long-press
 * interactions and selection-range adjustments over side-panel or page-turn swiping:
 *
 * 1. Intercepts pointer events in [PointerEventPass.Initial] so parent scrollables ([HorizontalPager],
 *    [LazyColumn], or navigation drawers) cannot steal horizontal drags once selection begins.
 * 2. Consumes natural finger roll/jitter within [holdTolerancePx] during the initial hold window so
 *    pressing down on a word never triggers premature page movement or cancels the long-press.
 * 3. Promotes hold-then-swipe gestures (>= [minHoldBeforeDragPromotionMs]) directly into word selection
 *    so users who pause briefly on a word and immediately swipe across a sentence highlight smoothly.
 * 4. When a selection is already active ([getActiveSelection] != null), touching and dragging adjusts
 *    or expands the selection immediately (0ms wait) without requiring a second long-press.
 */
/**
 * Checks whether a pointer [offset] inside [layout] lands on or within a comfortable touch radius
 * ([touchTolerancePx]) of an interactive inline range (such as a footnote marker `[4]` or `<a href="...">`),
 * returning the matching index in [inlineLinkRanges] or null.
 */
fun findTappedInlineLinkIndex(
    layout: TextLayoutResult?,
    offset: Offset,
    text: String,
    inlineLinkRanges: List<IntRange>,
    touchTolerancePx: Float = 32f
): Int? {
    if (layout == null || text.isEmpty() || inlineLinkRanges.isEmpty()) return null
    val tapChar = getClampedCharOffset(layout, offset, text.length)

    // 1. Direct character-range hit (with ±1 char tolerance for narrow markers like [4])
    val directIdx = inlineLinkRanges.indexOfFirst { range ->
        tapChar in (range.first - 1)..(range.last + 1)
    }
    if (directIdx >= 0) return directIdx

    // 2. Bounding-box proximity hit for small footnote markers
    var bestIdx: Int? = null
    var bestDistSq = touchTolerancePx * touchTolerancePx
    for ((idx, range) in inlineLinkRanges.withIndex()) {
        val s = range.first.coerceIn(0, text.length - 1)
        val e = range.last.coerceIn(s, text.length - 1)
        val startBox = layout.getBoundingBox(s)
        val endBox = layout.getBoundingBox(e)
        val left = minOf(startBox.left, endBox.left) - touchTolerancePx
        val right = maxOf(startBox.right, endBox.right) + touchTolerancePx
        val top = minOf(startBox.top, endBox.top) - (touchTolerancePx * 0.75f)
        val bottom = maxOf(startBox.bottom, endBox.bottom) + (touchTolerancePx * 0.75f)
        if (offset.x in left..right && offset.y in top..bottom) {
            val centerX = (minOf(startBox.left, endBox.left) + maxOf(startBox.right, endBox.right)) * 0.5f
            val centerY = (minOf(startBox.top, endBox.top) + maxOf(startBox.bottom, endBox.bottom)) * 0.5f
            val dx = offset.x - centerX
            val dy = offset.y - centerY
            val distSq = dx * dx + dy * dy
            if (distSq <= bestDistSq) {
                bestDistSq = distSq
                bestIdx = idx
            }
        }
    }
    return bestIdx
}

suspend fun PointerInputScope.detectCustomTextSelectionGestures(
    getParagraphText: () -> String,
    getTextLayoutResult: () -> TextLayoutResult?,
    getActiveSelection: () -> Pair<Int, Int>?,
    getInteractiveRanges: () -> List<IntRange> = { emptyList() },
    onSelectionChange: (Pair<Int, Int>?) -> Unit,
    onDragPreviewChange: (start: Int?, current: Int?, isDragging: Boolean) -> Unit,
    onSelectionActive: (Boolean) -> Unit,
    onTapNonSelection: (Offset) -> Unit,
    onLongPressActivated: () -> Unit = {},
    onDragPositionInParent: (localPos: Offset?) -> Unit = {},
    longPressTimeoutMs: Long = 380L,
    minHoldBeforeDragPromotionMs: Long = 220L
) {
    var lastTapTimeMs = 0L
    var lastTapPosition = Offset.Zero

    awaitEachGesture {
        val text = getParagraphText()
        if (text.isEmpty()) return@awaitEachGesture

        // Tighter touch-slop (~10dp) during initial touch so vertical scrolling and page swipes
        // immediately pass through to LazyColumn / HorizontalPager without accidentally selecting text,
        // while still absorbing micro finger roll when holding still on a word.
        val holdTolerancePx = 10f * density
        val dragSlopPx = 8f * density
        val linkTouchTolerancePx = 24f * density

        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val downTimeMs = System.currentTimeMillis()
        val downPos = down.position
        val isStylus = down.type == PointerType.Stylus || down.type == PointerType.Eraser
        val initialLayout = getTextLayoutResult()
        // If touch is below the Text composable (e.g. inside the footnote's 'Tilbake til tekst' button row),
        // yield immediately so the child clickable handles the tap cleanly without interference!
        if (initialLayout != null && downPos.y > initialLayout.size.height + (4f * density)) {
            return@awaitEachGesture
        }
        val interactiveRanges = getInteractiveRanges()
        val hitInteractiveLinkOnDown = findTappedInlineLinkIndex(
            layout = initialLayout,
            offset = downPos,
            text = text,
            inlineLinkRanges = interactiveRanges,
            touchTolerancePx = linkTouchTolerancePx
        ) != null

        // Branch 1: Stylus / Pen highlights immediately on contact
        if (isStylus) {
            down.consume()
            val layout = getTextLayoutResult() ?: return@awaitEachGesture
            val startChar = getClampedCharOffset(layout, downPos, text.length)
            val (anchorStart, anchorEnd) = findWordBoundaries(text, startChar)

            onSelectionActive(true)
            onLongPressActivated()
            onSelectionChange(Pair(anchorStart, anchorEnd))
            onDragPreviewChange(anchorStart, anchorEnd, true)
            onDragPositionInParent(downPos)

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break

                onDragPositionInParent(change.position)
                val curText = getParagraphText()
                val curLayout = getTextLayoutResult() ?: layout
                if (curText.isNotEmpty()) {
                    val curChar = getClampedCharOffset(curLayout, change.position, curText.length)
                    val (curStart, curEnd) = findWordBoundaries(curText, curChar)
                    val newStart = minOf(anchorStart, curStart).coerceIn(0, curText.length)
                    val newEnd = maxOf(anchorEnd, curEnd).coerceIn(0, curText.length)
                    onSelectionChange(Pair(newStart, newEnd))
                    onDragPreviewChange(newStart, newEnd, true)
                }
            }

            onDragPositionInParent(null)
            onDragPreviewChange(null, null, false)
            onSelectionActive(true)
            return@awaitEachGesture
        }

        // Branch 2: A selection is ALREADY active in this paragraph.
        // Immediately lock out page/drawer swiping and allow instant handle/range dragging or tap-to-extend!
        val existingSelection = getActiveSelection()
        if (existingSelection != null) {
            down.consume()
            onSelectionActive(true)

            val layout = getTextLayoutResult()
            if (layout == null) {
                onSelectionChange(null)
                onSelectionActive(false)
                return@awaitEachGesture
            }

            val downChar = getClampedCharOffset(layout, downPos, text.length)
            val (selStart, selEnd) = existingSelection

            // Determine which side of the existing selection serves as the fixed anchor during drag
            val distToStart = abs(downChar - selStart)
            val distToEnd = abs(downChar - selEnd)
            val (anchorStart, anchorEnd) = if (distToStart < distToEnd && downChar < (selStart + selEnd) / 2) {
                // User grabbed near the start handle -> anchor at the end word
                findWordBoundaries(text, (selEnd - 1).coerceAtLeast(0))
            } else {
                // User grabbed near the end handle or outside -> anchor at the start word
                findWordBoundaries(text, selStart)
            }

            var hasDragged = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break

                val moveDist = hypot(change.position.x - downPos.x, change.position.y - downPos.y)
                if (moveDist > dragSlopPx) {
                    hasDragged = true
                }
                if (hasDragged) {
                    onDragPositionInParent(change.position)
                    val curText = getParagraphText()
                    val curLayout = getTextLayoutResult() ?: layout
                    if (curText.isNotEmpty()) {
                        val curChar = getClampedCharOffset(curLayout, change.position, curText.length)
                        val (curStart, curEnd) = findWordBoundaries(curText, curChar)
                        val newStart = minOf(anchorStart, curStart).coerceIn(0, curText.length)
                        val newEnd = maxOf(anchorEnd, curEnd).coerceIn(0, curText.length)
                        onSelectionChange(Pair(newStart, newEnd))
                        onDragPreviewChange(newStart, newEnd, true)
                    }
                }
            }

            onDragPositionInParent(null)
            onDragPreviewChange(null, null, false)

            if (!hasDragged) {
                // If user tapped a footnote or inline link while a selection was active, clear selection and open the footnote/link!
                if (hitInteractiveLinkOnDown) {
                    onSelectionChange(null)
                    onSelectionActive(false)
                    onTapNonSelection(downPos)
                    return@awaitEachGesture
                }
                // Quick tap while selection was active:
                // If tapped outside the current selection, extend the selection to that word;
                // if tapped inside the current selection, dismiss the selection.
                val latestText = getParagraphText().ifEmpty { text }
                if (downChar > selEnd) {
                    val (_, wEnd) = findWordBoundaries(latestText, downChar)
                    onSelectionChange(Pair(selStart, wEnd))
                    onSelectionActive(true)
                } else if (downChar < selStart) {
                    val (wStart, _) = findWordBoundaries(latestText, downChar)
                    onSelectionChange(Pair(wStart, selEnd))
                    onSelectionActive(true)
                } else {
                    onSelectionChange(null)
                    onSelectionActive(false)
                }
            } else {
                onSelectionActive(true)
            }
            return@awaitEachGesture
        }

        // Branch 3: No active selection yet.
        // If the user pressed on a footnote or inline link, use a longer hold threshold (750ms) and only promote
        // to selection if the user drags horizontally across words, so deliberate taps on small footnote markers
        // ALWAYS open the footnote popup instead of highlighting surrounding text!
        val effectiveLongPressMs = if (hitInteractiveLinkOnDown) 750L else longPressTimeoutMs
        var longPressTriggered = false
        var gestureAbortedToScroll = false
        var releasedBeforeLongPress = false
        var releasePosition = downPos

        while (!longPressTriggered && !gestureAbortedToScroll && !releasedBeforeLongPress) {
            val elapsed = System.currentTimeMillis() - downTimeMs
            val remaining = effectiveLongPressMs - elapsed
            if (remaining <= 0L) {
                longPressTriggered = true
                break
            }

            val event = withTimeoutOrNull(remaining) {
                awaitPointerEvent(PointerEventPass.Initial)
            }

            if (event == null) {
                // Timeout reached while finger remained held -> Long-press activated!
                longPressTriggered = true
                break
            }

            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed) {
                change?.consume()
                releasePosition = change?.position ?: downPos
                releasedBeforeLongPress = true
                break
            }

            // If a parent scrollable (LazyColumn / HorizontalPager) already consumed the movement, yield immediately
            if (change.isConsumed) {
                gestureAbortedToScroll = true
                break
            }

            val dx = change.position.x - downPos.x
            val dy = change.position.y - downPos.y
            val dist = hypot(dx, dy)
            val holdDuration = System.currentTimeMillis() - downTimeMs
            val effectiveHoldTol = if (hitInteractiveLinkOnDown) holdTolerancePx * 1.6f else holdTolerancePx

            if (dist <= effectiveHoldTol) {
                // Consume micro finger roll/jitter when pressing on a footnote or holding still >= 120ms
                if (hitInteractiveLinkOnDown || holdDuration >= 120L) {
                    change.consume()
                }
            } else if (!hitInteractiveLinkOnDown && holdDuration >= minHoldBeforeDragPromotionMs && abs(dx) > abs(dy) * 1.25f) {
                // The user paused deliberately on a word (>= 220ms) and dragged horizontally across text:
                // promote to text selection and suppress side-panel / pager swiping!
                change.consume()
                longPressTriggered = true
            } else {
                // Immediate scroll or swipe before the hold threshold -> allow normal vertical scroll or page turn!
                gestureAbortedToScroll = true
            }
        }

        if (gestureAbortedToScroll) {
            return@awaitEachGesture
        }

        if (releasedBeforeLongPress) {
            if (hitInteractiveLinkOnDown) {
                // Tapped on a footnote or inline link -> ALWAYS trigger onTapNonSelection immediately (never double-tap word select)!
                onTapNonSelection(downPos)
                return@awaitEachGesture
            }
            val now = System.currentTimeMillis()
            val isDoubleTap = (now - lastTapTimeMs) <= 300L &&
                hypot(releasePosition.x - lastTapPosition.x, releasePosition.y - lastTapPosition.y) <= holdTolerancePx * 1.5f

            if (isDoubleTap) {
                lastTapTimeMs = 0L
                val layout = getTextLayoutResult()
                if (layout != null) {
                    val charIdx = getClampedCharOffset(layout, releasePosition, text.length)
                    val (wStart, wEnd) = findWordBoundaries(text, charIdx)
                    onSelectionActive(true)
                    onLongPressActivated()
                    onSelectionChange(Pair(wStart, wEnd))
                }
            } else {
                lastTapTimeMs = now
                lastTapPosition = releasePosition
                onTapNonSelection(releasePosition)
            }
            return@awaitEachGesture
        }

        // Long-press selection is now LOCKED IN!
        val layout = getTextLayoutResult() ?: return@awaitEachGesture
        val initialText = getParagraphText().ifEmpty { text }
        val startChar = getClampedCharOffset(layout, downPos, initialText.length)
        val (anchorStart, anchorEnd) = findWordBoundaries(initialText, startChar)

        onSelectionActive(true)
        onLongPressActivated()
        onSelectionChange(Pair(anchorStart, anchorEnd))
        onDragPreviewChange(anchorStart, anchorEnd, true)
        onDragPositionInParent(downPos)

        // Track continuous drag across words and lines in PointerEventPass.Initial,
        // consuming every event so navigation movements are completely suppressed.
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) break

            onDragPositionInParent(change.position)
            val curText = getParagraphText().ifEmpty { initialText }
            val curLayout = getTextLayoutResult() ?: layout
            if (curText.isNotEmpty()) {
                val curChar = getClampedCharOffset(curLayout, change.position, curText.length)
                val (curStart, curEnd) = findWordBoundaries(curText, curChar)
                val newStart = minOf(anchorStart, curStart).coerceIn(0, curText.length)
                val newEnd = maxOf(anchorEnd, curEnd).coerceIn(0, curText.length)
                onSelectionChange(Pair(newStart, newEnd))
                onDragPreviewChange(newStart, newEnd, true)
            }
        }

        onDragPositionInParent(null)
        onDragPreviewChange(null, null, false)
        onSelectionActive(true)
    }
}

/**
 * Custom gesture detector for PDF text & region selection that supports:
 * - **Pinch-to-Zoom & 2-Finger Pan** (1.0x up to 4.5x magnification around the pinch centroid)
 * - **Single-Finger Pan when Zoomed In** (when scale > 1.02f and no text selection is actively being dragged)
 * - **Double-Tap to Zoom or Select Word**
 * - **Long-Press, Active-Selection Handle Dragging, and Stylus Dragging** in [PointerEventPass.Initial]
 *   with automatic conversion of zoomed screen coordinates into unzoomed page coordinates!
 */
suspend fun PointerInputScope.detectCustomPdfSelectionGestures(
    getZoomScale: () -> Float = { 1f },
    getZoomOffset: () -> Offset = { Offset.Zero },
    onZoomTransform: (newScale: Float, newOffset: Offset) -> Unit = { _, _ -> },
    hasActiveSelection: () -> Boolean = { false },
    onActiveSelectionDragStart: (Offset) -> Unit = {},
    onActiveSelectionDragMove: (Offset) -> Unit = {},
    onActiveSelectionDragEnd: (hasDragged: Boolean, releasePos: Offset) -> Unit = { _, _ -> },
    onDoubleTap: (Offset) -> Unit = {},
    onDragStart: (Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragFinish: (start: Offset, end: Offset) -> Unit,
    onDragCancel: () -> Unit,
    onTap: (Offset) -> Unit,
    longPressTimeoutMs: Long = 380L
) {
    var lastTapTimeMs = 0L
    var lastTapPosition = Offset.Zero

    fun toUnzoomedPoint(rawPoint: Offset): Offset {
        val scale = getZoomScale().coerceAtLeast(1f)
        if (scale <= 1.005f || size.width <= 0 || size.height <= 0) return rawPoint
        val offset = getZoomOffset()
        val centerX = size.width * 0.5f
        val centerY = size.height * 0.5f
        val unzoomedX = ((rawPoint.x - centerX - offset.x) / scale) + centerX
        val unzoomedY = ((rawPoint.y - centerY - offset.y) / scale) + centerY
        return Offset(unzoomedX, unzoomedY)
    }

    fun clampZoomOffset(scale: Float, candidateOffset: Offset): Offset {
        if (scale <= 1.005f || size.width <= 0 || size.height <= 0) return Offset.Zero
        val maxDx = (size.width * (scale - 1f) * 0.5f).coerceAtLeast(0f)
        val maxDy = (size.height * (scale - 1f) * 0.5f).coerceAtLeast(0f)
        return Offset(
            x = candidateOffset.x.coerceIn(-maxDx, maxDx),
            y = candidateOffset.y.coerceIn(-maxDy, maxDy)
        )
    }

    fun applyPinchEvent(zoomFactor: Float, panDelta: Offset, centroid: Offset) {
        val oldScale = getZoomScale().coerceIn(1f, 4.5f)
        val newScale = (oldScale * zoomFactor).coerceIn(1f, 4.5f)
        val actualZoom = newScale / oldScale
        val oldOffset = getZoomOffset()
        val center = Offset(size.width * 0.5f, size.height * 0.5f)
        val rawOffset = if (centroid != Offset.Unspecified) {
            val focusShift = (centroid - center) * (1f - actualZoom)
            (oldOffset * actualZoom) + focusShift + panDelta
        } else {
            (oldOffset * actualZoom) + panDelta
        }
        val snappedScale = if (newScale < 1.02f) 1f else newScale
        onZoomTransform(snappedScale, clampZoomOffset(snappedScale, rawOffset))
    }

    awaitEachGesture {
        val holdTolerancePx = 10f * density
        val dragSlopPx = 8f * density
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val downTimeMs = System.currentTimeMillis()
        val rawDownPos = down.position
        val downPos = toUnzoomedPoint(rawDownPos)
        val isStylus = down.type == PointerType.Stylus || down.type == PointerType.Eraser

        if (isStylus) {
            down.consume()
            onDragStart(downPos)
            var currentPos = downPos
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
                currentPos = toUnzoomedPoint(change.position)
                onDragMove(currentPos)
            }
            onDragFinish(downPos, currentPos)
            return@awaitEachGesture
        }

        // If a PDF word selection is ALREADY active on this page, allow immediate 0ms handle/range adjustment,
        // or 2-finger pinch-to-zoom if a second finger touches down!
        if (hasActiveSelection()) {
            down.consume()
            onActiveSelectionDragStart(downPos)
            var hasDragged = false
            var currentPos = downPos
            var switchedToPinch = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    switchedToPinch = true
                    event.changes.forEach { it.consume() }
                    break
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
                val rawCur = change.position
                currentPos = toUnzoomedPoint(rawCur)
                val dist = hypot(rawCur.x - rawDownPos.x, rawCur.y - rawDownPos.y)
                if (dist > dragSlopPx) {
                    hasDragged = true
                }
                if (hasDragged) {
                    onActiveSelectionDragMove(currentPos)
                }
            }
            if (switchedToPinch) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    val zoomFactor = event.calculateZoom()
                    val panDelta = event.calculatePan()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    applyPinchEvent(zoomFactor, panDelta, centroid)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                return@awaitEachGesture
            }
            onActiveSelectionDragEnd(hasDragged, currentPos)
            return@awaitEachGesture
        }

        var longPressTriggered = false
        var abortedToScroll = false
        var multiTouchPinch = false
        var singleFingerPanWhenZoomed = false
        var releasedEarly = false
        var rawReleasePos = rawDownPos

        while (!longPressTriggered && !abortedToScroll && !releasedEarly && !multiTouchPinch && !singleFingerPanWhenZoomed) {
            val elapsed = System.currentTimeMillis() - downTimeMs
            val remaining = longPressTimeoutMs - elapsed
            if (remaining <= 0L) {
                longPressTriggered = true
                break
            }
            val event = withTimeoutOrNull(remaining) {
                awaitPointerEvent(PointerEventPass.Initial)
            }
            if (event == null) {
                longPressTriggered = true
                break
            }
            val pressedPointers = event.changes.filter { it.pressed }
            if (pressedPointers.size >= 2) {
                multiTouchPinch = true
                event.changes.forEach { it.consume() }
                break
            }
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || !change.pressed) {
                change?.consume()
                rawReleasePos = change?.position ?: rawDownPos
                releasedEarly = true
                break
            }
            if (change.isConsumed) {
                abortedToScroll = true
                break
            }
            val dx = change.position.x - rawDownPos.x
            val dy = change.position.y - rawDownPos.y
            val dist = hypot(dx, dy)
            val holdDuration = System.currentTimeMillis() - downTimeMs
            if (dist <= holdTolerancePx) {
                if (holdDuration >= 120L) {
                    change.consume()
                }
            } else if (holdDuration >= 220L && abs(dx) > abs(dy) && getZoomScale() <= 1.03f) {
                change.consume()
                longPressTriggered = true
            } else if (getZoomScale() > 1.02f) {
                // When zoomed in, dragging with 1 finger pans the zoomed PDF page smoothly
                change.consume()
                singleFingerPanWhenZoomed = true
            } else {
                abortedToScroll = true
            }
        }

        if (multiTouchPinch) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                val zoomFactor = event.calculateZoom()
                val panDelta = event.calculatePan()
                val centroid = event.calculateCentroid(useCurrent = true)
                applyPinchEvent(zoomFactor, panDelta, centroid)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
            return@awaitEachGesture
        }

        if (singleFingerPanWhenZoomed) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    val zoomFactor = event.calculateZoom()
                    val panDelta = event.calculatePan()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    applyPinchEvent(zoomFactor, panDelta, centroid)
                } else {
                    val panDelta = event.calculatePan()
                    val curScale = getZoomScale().coerceIn(1f, 4.5f)
                    val nextOffset = clampZoomOffset(curScale, getZoomOffset() + panDelta)
                    onZoomTransform(curScale, nextOffset)
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
            return@awaitEachGesture
        }

        if (abortedToScroll) {
            // Even if 1 finger started moving at 1x, watch in Initial pass in case a 2nd finger touches down for pinch-zoom!
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    val zoomFactor = event.calculateZoom()
                    val panDelta = event.calculatePan()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    applyPinchEvent(zoomFactor, panDelta, centroid)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
            return@awaitEachGesture
        }

        if (releasedEarly) {
            val now = System.currentTimeMillis()
            val releaseUnzoomed = toUnzoomedPoint(rawReleasePos)
            val isDoubleTap = (now - lastTapTimeMs) <= 300L &&
                hypot(rawReleasePos.x - lastTapPosition.x, rawReleasePos.y - lastTapPosition.y) <= holdTolerancePx * 1.8f
            if (isDoubleTap) {
                lastTapTimeMs = 0L
                onDoubleTap(releaseUnzoomed)
            } else {
                lastTapTimeMs = now
                lastTapPosition = rawReleasePos
                onTap(releaseUnzoomed)
            }
            return@awaitEachGesture
        }

        onDragStart(downPos)
        var currentPos = downPos
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) break
            currentPos = toUnzoomedPoint(change.position)
            onDragMove(currentPos)
        }
        onDragFinish(downPos, currentPos)
    }
}

