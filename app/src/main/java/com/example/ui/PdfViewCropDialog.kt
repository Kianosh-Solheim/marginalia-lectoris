package com.example.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.DocumentEntity
import com.example.reader.PdfDocumentRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Interactive non-destructive PDF Reading Region & Margin Zoom Dialog:
 * Lets the user visually drag the 4 edges/corners on the current PDF page (or use 1-tap presets /
 * Auto-Trim Margins / precision sliders) to select what part of the PDF page to read and zoom in
 * by trimming outer margins as a global setting for this book/article without modifying the file.
 */
@Composable
fun PdfViewCropDialog(
    document: DocumentEntity,
    currentPageIndex: Int,
    pdfRenderer: PdfDocumentRenderer?,
    onDismiss: () -> Unit,
    onSaveCropForBook: (left: Float, top: Float, right: Float, bottom: Float) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var cropLeft by remember(document.id, document.pdfCropLeft) {
        mutableFloatStateOf(document.pdfCropLeft.coerceIn(0f, 0.85f))
    }
    var cropTop by remember(document.id, document.pdfCropTop) {
        mutableFloatStateOf(document.pdfCropTop.coerceIn(0f, 0.85f))
    }
    var cropRight by remember(document.id, document.pdfCropRight) {
        mutableFloatStateOf(document.pdfCropRight.coerceIn((cropLeft + 0.15f).coerceAtMost(1f), 1f))
    }
    var cropBottom by remember(document.id, document.pdfCropBottom) {
        mutableFloatStateOf(document.pdfCropBottom.coerceIn((cropTop + 0.15f).coerceAtMost(1f), 1f))
    }

    var fullPageBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(pdfRenderer, currentPageIndex) {
        val renderer = pdfRenderer ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            fullPageBitmap = renderer.renderPage(
                pageIndex = currentPageIndex,
                targetWidth = 720,
                cropLeft = 0f,
                cropTop = 0f,
                cropRight = 1f,
                cropBottom = 1f
            )
        }
    }

    val zoomPercent = ((1f / (cropRight - cropLeft).coerceIn(0.15f, 1f)) * 100).toInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, Color.Black, RoundedCornerShape(6.dp))
            .testTag("pdf_view_crop_dialog"),
        title = {
            com.example.ui.theme.DisableDialogDimming()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Crop, contentDescription = null, tint = Color(0xFF7C3AED))
                        Text(
                            text = "PDF Reading Region & Zoom",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                    Surface(
                        color = Color(0xFF7C3AED),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${zoomPercent}% Text Scale",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Text(
                    text = "Global view setting for \"${document.title}\" • Does not modify the PDF file",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF71717A)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Quick Presets Row (Auto-Detect Text Area, Trim 8%, Trim 14%, Full Page)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = {
                            val renderer = pdfRenderer
                            if (renderer != null) {
                                coroutineScope.launch(Dispatchers.IO) {
                                    val bounds = renderer.detectAutoMarginCropBounds(currentPageIndex)
                                    withContext(Dispatchers.Main) {
                                        cropLeft = bounds.left
                                        cropTop = bounds.top
                                        cropRight = bounds.right
                                        cropBottom = bounds.bottom
                                    }
                                }
                            } else {
                                cropLeft = 0.08f
                                cropTop = 0.07f
                                cropRight = 0.92f
                                cropBottom = 0.93f
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF7C3AED),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("pdf_crop_auto_trim_btn")
                    ) {
                        Text("✨ Auto-Trim Margins", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            cropLeft = 0.08f
                            cropTop = 0.06f
                            cropRight = 0.92f
                            cropBottom = 0.94f
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF27272A)),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("pdf_crop_preset_medium_btn")
                    ) {
                        Text("Medium Trim (8%)", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = {
                            cropLeft = 0.14f
                            cropTop = 0.10f
                            cropRight = 0.86f
                            cropBottom = 0.90f
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF27272A)),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("pdf_crop_preset_tight_btn")
                    ) {
                        Text("Tight Trim (14%)", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = {
                            cropLeft = 0f
                            cropTop = 0f
                            cropRight = 1f
                            cropBottom = 1f
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF71717A)),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("pdf_crop_reset_btn")
                    ) {
                        Text("Full Page", fontSize = 11.sp, color = Color.Black)
                    }
                }

                // Interactive Visual Page Preview with Draggable Selection Box & Corner Handles
                Text(
                    text = "Drag corners/edges on the page preview or use sliders below to frame the reading area:",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF52525B)
                )

                var previewSizePx by remember { mutableStateOf(Size.Zero) }
                val currentLeft by rememberUpdatedState(cropLeft)
                val currentTop by rememberUpdatedState(cropTop)
                val currentRight by rememberUpdatedState(cropRight)
                val currentBottom by rememberUpdatedState(cropBottom)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(270.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF27272A))
                        .border(1.dp, Color(0xFFD4D4D8), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(8.dp)
                            .onGloballyPositioned { coords ->
                                previewSizePx = Size(
                                    coords.size.width.toFloat().coerceAtLeast(1f),
                                    coords.size.height.toFloat().coerceAtLeast(1f)
                                )
                            }
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val w = previewSizePx.width.coerceAtLeast(size.width.toFloat().coerceAtLeast(1f))
                                    val h = previewSizePx.height.coerceAtLeast(size.height.toFloat().coerceAtLeast(1f))
                                    val startRx = (down.position.x / w).coerceIn(0f, 1f)
                                    val startRy = (down.position.y / h).coerceIn(0f, 1f)

                                    val distLeft = kotlin.math.abs(startRx - currentLeft)
                                    val distRight = kotlin.math.abs(startRx - currentRight)
                                    val distTop = kotlin.math.abs(startRy - currentTop)
                                    val distBottom = kotlin.math.abs(startRy - currentBottom)
                                    val edgeThreshold = 0.09f

                                    val dragLeftEdge = distLeft <= edgeThreshold && distLeft <= distRight
                                    val dragRightEdge = distRight <= edgeThreshold && distRight < distLeft
                                    val dragTopEdge = distTop <= edgeThreshold && distTop <= distBottom
                                    val dragBottomEdge = distBottom <= edgeThreshold && distBottom < distTop
                                    val isEdgeDrag = dragLeftEdge || dragRightEdge || dragTopEdge || dragBottomEdge

                                    down.consume()
                                    var pointerId = down.id
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == pointerId }
                                            ?: event.changes.firstOrNull()
                                            ?: break
                                        if (!change.pressed) break
                                        pointerId = change.id
                                        val rx = (change.position.x / w).coerceIn(0f, 1f)
                                        val ry = (change.position.y / h).coerceIn(0f, 1f)
                                        change.consume()

                                        if (isEdgeDrag) {
                                            if (dragLeftEdge) {
                                                cropLeft = rx.coerceIn(0f, (currentRight - 0.15f).coerceAtLeast(0f))
                                            }
                                            if (dragRightEdge) {
                                                cropRight = rx.coerceIn((currentLeft + 0.15f).coerceAtMost(1f), 1f)
                                            }
                                            if (dragTopEdge) {
                                                cropTop = ry.coerceIn(0f, (currentBottom - 0.15f).coerceAtLeast(0f))
                                            }
                                            if (dragBottomEdge) {
                                                cropBottom = ry.coerceIn((currentTop + 0.15f).coerceAtMost(1f), 1f)
                                            }
                                        } else {
                                            // Freeform rectangle selection when dragging inside the preview
                                            val minX = minOf(startRx, rx)
                                            val maxX = maxOf(startRx, rx)
                                            val minY = minOf(startRy, ry)
                                            val maxY = maxOf(startRy, ry)
                                            if (maxX - minX >= 0.15f && maxY - minY >= 0.15f) {
                                                cropLeft = minX
                                                cropRight = maxX
                                                cropTop = minY
                                                cropBottom = maxY
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                        if (fullPageBitmap != null) {
                            Image(
                                bitmap = fullPageBitmap!!.asImageBitmap(),
                                contentDescription = "PDF Crop Preview",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxHeight()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .width(180.dp)
                                    .fillMaxHeight()
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Page ${currentPageIndex + 1}", color = Color.Black, fontSize = 12.sp)
                            }
                        }

                        // Shaded outside margin mask + purple crop rectangle + 4 corner handles
                        Canvas(modifier = Modifier.matchParentSize()) {
                            val cw = size.width
                            val ch = size.height
                            val lPx = (cropLeft * cw).coerceIn(0f, (cw - 2f).coerceAtLeast(0f))
                            val tPx = (cropTop * ch).coerceIn(0f, (ch - 2f).coerceAtLeast(0f))
                            val rPx = (cropRight * cw).coerceIn((lPx + 10f).coerceAtMost(cw), cw)
                            val bPx = (cropBottom * ch).coerceIn((tPx + 10f).coerceAtMost(ch), ch)

                            val dimColor = Color.Black.copy(alpha = 0.52f)
                            // Top shaded margin
                            drawRect(color = dimColor, topLeft = Offset(0f, 0f), size = Size(cw, tPx))
                            // Bottom shaded margin
                            drawRect(color = dimColor, topLeft = Offset(0f, bPx), size = Size(cw, ch - bPx))
                            // Left shaded margin
                            drawRect(color = dimColor, topLeft = Offset(0f, tPx), size = Size(lPx, bPx - tPx))
                            // Right shaded margin
                            drawRect(color = dimColor, topLeft = Offset(rPx, tPx), size = Size(cw - rPx, bPx - tPx))

                            // Active reading region border
                            drawRect(
                                color = Color(0xFF7C3AED),
                                topLeft = Offset(lPx, tPx),
                                size = Size(rPx - lPx, bPx - tPx),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx())
                            )

                            // 4 corner handles
                            val handleR = 6.dp.toPx()
                            val corners = listOf(
                                Offset(lPx, tPx),
                                Offset(rPx, tPx),
                                Offset(lPx, bPx),
                                Offset(rPx, bPx)
                            )
                            corners.forEach { pt ->
                                drawCircle(color = Color.White, radius = handleR + 2f, center = pt)
                                drawCircle(color = Color(0xFF7C3AED), radius = handleR, center = pt)
                            }
                        }
                    }
                }

                // Precision Sliders for Left/Right and Top/Bottom Margin Trim
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Left Margin: ${(cropLeft * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Black
                        )
                        Text(
                            text = "Right Margin: ${((1f - cropRight) * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Black
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Slider(
                            value = cropLeft,
                            onValueChange = { cropLeft = it.coerceIn(0f, (cropRight - 0.15f).coerceAtLeast(0f)) },
                            valueRange = 0f..0.40f,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("pdf_crop_left_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF7C3AED),
                                activeTrackColor = Color(0xFF7C3AED)
                            )
                        )
                        Slider(
                            value = 1f - cropRight,
                            onValueChange = { rightTrim ->
                                cropRight = (1f - rightTrim).coerceIn((cropLeft + 0.15f).coerceAtMost(1f), 1f)
                            },
                            valueRange = 0f..0.40f,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("pdf_crop_right_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF7C3AED),
                                activeTrackColor = Color(0xFF7C3AED)
                            )
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Top Margin: ${(cropTop * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Black
                        )
                        Text(
                            text = "Bottom Margin: ${((1f - cropBottom) * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.Black
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Slider(
                            value = cropTop,
                            onValueChange = { cropTop = it.coerceIn(0f, (cropBottom - 0.15f).coerceAtLeast(0f)) },
                            valueRange = 0f..0.40f,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("pdf_crop_top_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF7C3AED),
                                activeTrackColor = Color(0xFF7C3AED)
                            )
                        )
                        Slider(
                            value = 1f - cropBottom,
                            onValueChange = { bottomTrim ->
                                cropBottom = (1f - bottomTrim).coerceIn((cropTop + 0.15f).coerceAtMost(1f), 1f)
                            },
                            valueRange = 0f..0.40f,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("pdf_crop_bottom_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF7C3AED),
                                activeTrackColor = Color(0xFF7C3AED)
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveCropForBook(cropLeft, cropTop, cropRight, cropBottom)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF7C3AED),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("save_pdf_crop_global_btn")
            ) {
                Text("Apply to Entire Book", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Black)
            }
        }
    )
}
