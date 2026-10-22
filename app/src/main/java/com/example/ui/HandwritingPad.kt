package com.example.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.reader.Sketches
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private data class TimedPoint(val x: Float, val y: Float, val t: Long)

/** Waits for a Google Play services Task without extra dependencies. */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/** Finds a handwriting model for Norwegian or English (tags vary between ML Kit releases). */
private fun modelFor(norwegian: Boolean): DigitalInkRecognitionModel? {
    val tags = if (norwegian) listOf("nb-NO", "nb", "no", "nn-NO") else listOf("en-GB", "en-US", "en")
    for (tag in tags) {
        val id = runCatching { DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag) }.getOrNull() ?: continue
        return DigitalInkRecognitionModel.builder(id).build()
    }
    return null
}

/**
 * Handwriting pad for the note window. Strokes are recognised with ML Kit Digital Ink (the language
 * model downloads once, then works offline) and the writing itself is saved as a PNG sketch that is
 * embedded in the note (![[sketch-….png]]) and included in exports.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HandwritingPadDialog(
    norwegian: Boolean,
    onDismiss: () -> Unit,
    onResult: (text: String, sketchEmbed: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strokes = remember { mutableStateListOf<List<TimedPoint>>() }
    var current by remember { mutableStateOf<List<TimedPoint>>(emptyList()) }
    var padSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var keepSketch by remember { mutableStateOf(true) }
    var lang by remember { mutableStateOf(norwegian) }

    fun saveSketch(): String? {
        if (strokes.isEmpty() || padSize.width == 0) return null
        val all = strokes.flatten()
        val pad = 24f
        val minX = (all.minOf { it.x } - pad).coerceAtLeast(0f)
        val minY = (all.minOf { it.y } - pad).coerceAtLeast(0f)
        val maxX = (all.maxOf { it.x } + pad).coerceAtMost(padSize.width.toFloat())
        val maxY = (all.maxOf { it.y } + pad).coerceAtMost(padSize.height.toFloat())
        val w = (maxX - minX).toInt().coerceAtLeast(1)
        val h = (maxY - minY).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        val paint = Paint().apply {
            color = android.graphics.Color.BLACK; strokeWidth = 4f; style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true
        }
        strokes.forEach { s ->
            val p = android.graphics.Path()
            s.forEachIndexed { i, pt -> if (i == 0) p.moveTo(pt.x - minX, pt.y - minY) else p.lineTo(pt.x - minX, pt.y - minY) }
            if (s.size == 1) p.lineTo(s[0].x - minX + 0.5f, s[0].y - minY + 0.5f)
            c.drawPath(p, paint)
        }
        val name = "sketch-${System.currentTimeMillis()}.png"
        File(Sketches.dir(context), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return "![[${name}]]"
    }

    suspend fun recognise(): String {
        val model = modelFor(lang) ?: error(if (lang) "Norwegian handwriting isn't supported by the recogniser." else "English handwriting model unavailable.")
        val manager = RemoteModelManager.getInstance()
        if (!manager.isModelDownloaded(model).awaitResult()) {
            status = "Downloading the handwriting model (once, needs internet)…"
            manager.download(model, DownloadConditions.Builder().build()).awaitResult()
        }
        val recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        try {
            val ink = Ink.builder().apply {
                strokes.forEach { s ->
                    val sb = Ink.Stroke.builder()
                    s.forEach { sb.addPoint(Ink.Point.create(it.x, it.y, it.t)) }
                    addStroke(sb.build())
                }
            }.build()
            val ctx = RecognitionContext.builder()
                .setPreContext("")
                .setWritingArea(WritingArea(padSize.width.toFloat(), padSize.height.toFloat()))
                .build()
            val result = recognizer.recognize(ink, ctx).awaitResult()
            return result.candidates.firstOrNull()?.text.orEmpty()
        } finally {
            recognizer.close()
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .background(Color.White, RoundedCornerShape(4.dp))
                .border(1.5.dp, Color.Black, RoundedCornerShape(4.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Handwrite note", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.Black)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InkChip("Norsk", lang, { lang = true })
                InkChip("English", !lang, { lang = false })
                InkChip("Keep sketch", keepSketch, { keepSketch = !keepSketch })
            }
            // Writing surface
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .border(1.dp, Color.Black)
                    .background(Color.White)
                    .onSizeChanged { padSize = it }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val pts = mutableListOf(TimedPoint(down.position.x, down.position.y, System.currentTimeMillis()))
                            current = pts.toList()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.historical.forEach { h -> pts.add(TimedPoint(h.position.x, h.position.y, h.uptimeMillis)) }
                                pts.add(TimedPoint(change.position.x, change.position.y, System.currentTimeMillis()))
                                change.consume()
                                current = pts.toList()
                            }
                            strokes.add(pts.toList())
                            current = emptyList()
                        }
                    }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    // Ruled lines like a notebook
                    var y = 56.dp.toPx()
                    while (y < size.height) {
                        drawLine(Color(0xFFE4E4E7), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                        y += 56.dp.toPx()
                    }
                    (strokes + listOf(current)).forEach { s ->
                        if (s.isEmpty()) return@forEach
                        val path = Path().apply {
                            moveTo(s[0].x, s[0].y)
                            s.drop(1).forEach { lineTo(it.x, it.y) }
                            if (s.size == 1) lineTo(s[0].x + 0.5f, s[0].y + 0.5f)
                        }
                        drawPath(path, Color.Black, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                if (strokes.isEmpty() && current.isEmpty()) {
                    Text(
                        "Write here with the stylus",
                        modifier = Modifier.padding(12.dp),
                        fontFamily = FontFamily.Serif, color = Color(0xFF9CA3AF)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton("Undo", icon = Icons.Default.Undo, enabled = strokes.isNotEmpty() && !busy, onClick = { strokes.removeAt(strokes.lastIndex) })
                InkButton("Clear", icon = Icons.Default.Delete, enabled = strokes.isNotEmpty() && !busy, onClick = { strokes.clear() })
                Spacer(Modifier.weight(1f))
                InkButton("Cancel", enabled = !busy, onClick = onDismiss)
                InkButton(
                    label = if (busy) "Converting…" else "Add to note",
                    icon = Icons.Default.Check,
                    filled = true,
                    enabled = strokes.isNotEmpty() && !busy,
                    onClick = {
                        busy = true; status = ""
                        scope.launch {
                            val embed = if (keepSketch) withContext(Dispatchers.IO) { runCatching { saveSketch() }.getOrNull() } else null
                            val text = runCatching { recognise() }.getOrElse {
                                status = "Couldn't convert the handwriting (${it.message ?: "recogniser unavailable"})." +
                                    if (embed != null) " The sketch is added to the note." else ""
                                ""
                            }
                            busy = false
                            if (text.isNotBlank() || embed != null) {
                                onResult(text, embed.orEmpty())
                                if (text.isNotBlank() || status.isBlank()) onDismiss()
                            }
                        }
                    }
                )
            }
            if (status.isNotBlank()) Text(status, fontSize = 12.sp, color = Color(0xFF3F3F46))
        }
    }
}
