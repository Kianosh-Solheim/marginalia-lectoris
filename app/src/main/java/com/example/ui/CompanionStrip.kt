package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.reader.CompanionStatus
import kotlinx.coroutines.delay

/** Re-reads the computer's status now and then, so "gone" (older than three minutes) shows without a new message. */
@Composable
private fun companionJobs(): Pair<CompanionStatus.Status?, List<CompanionStatus.Job>> {
    val s by CompanionStatus.state
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }
    return remember(s, tick) { CompanionStatus.current() to CompanionStatus.activeJobs() }
}

/**
 * A slim strip while Marginalia Companion on a computer works: "On KIANOSH-PC: Min skyld · Transcribing, 42% …"
 * with a thin progress line. Tap: every job in its queue.
 */
@Composable
fun CompanionStrip(modifier: Modifier = Modifier) {
    val (status, jobs) = companionJobs()
    if (status == null || jobs.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val j = jobs.firstOrNull { it.state == "transcribing" || it.state == "sending" || it.state == "matching" } ?: jobs.first()
    Column(
        modifier.widthIn(max = 640.dp).drawBehind { val o = 3.dp.toPx(); drawRect(Color.Black, topLeft = Offset(o, o), size = size) }
            .background(Color.White).border(1.5.dp, Color.Black).clickable { open = true }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("On ${status.computer}:", fontSize = 12.sp, color = Color(0xFF3F3F46), maxLines = 1)
            Text(j.title, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (jobs.size > 1) Text("+${jobs.size - 1}", fontSize = 12.sp, color = Color(0xFF3F3F46))
        }
        Text(j.line(), fontSize = 12.sp, color = Color.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFFD4D4D8))) {
            Box(Modifier.fillMaxWidth(j.frac.toFloat().coerceIn(0f, 1f)).height(3.dp).background(Color.Black))
        }
    }
    if (open) Dialog(onDismissRequest = { open = false }) {
        Column(Modifier.fillMaxWidth().background(Color.White).border(1.5.dp, Color.Black).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Marginalia Companion on ${status.computer}", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Its queue, in order. Change it on the computer.", fontSize = 12.sp, color = Color(0xFF3F3F46))
            jobs.forEachIndexed { i, job ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${i + 1}. ${job.title}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(job.line(), fontSize = 13.sp)
                    if (job.msg.isNotBlank()) Text(job.msg, fontSize = 12.sp, color = Color(0xFF3F3F46))
                    if (job.state == "transcribing" || job.state == "sending") Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFFD4D4D8))) {
                        Box(Modifier.fillMaxWidth(job.frac.toFloat().coerceIn(0f, 1f)).height(3.dp).background(Color.Black))
                    }
                }
            }
            InkButton("Close", onClick = { open = false })
        }
    }
}

/** On a book: what the computer is doing with it ("Being transcribed on KIANOSH-PC: 42% · about 12 min left"). */
@Composable
fun CompanionBookLine(bookUuid: String?, modifier: Modifier = Modifier) {
    val (status, _) = companionJobs()
    val job = status?.let { CompanionStatus.forBook(bookUuid) } ?: return
    Column(modifier.fillMaxWidth().border(1.dp, Color.Black).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (job.state == "done") "Sync map made on ${status.computer}" else "On ${status.computer}: ${job.line()}",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.Black
        )
        if (job.msg.isNotBlank() && job.state != "transcribing") Text(job.msg, fontSize = 12.sp, color = Color(0xFF3F3F46))
        if (job.state == "transcribing" || job.state == "sending") Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0xFFD4D4D8))) {
            Box(Modifier.fillMaxWidth(job.frac.toFloat().coerceIn(0f, 1f)).height(3.dp).background(Color.Black))
        }
    }
}
