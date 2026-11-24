package com.example.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.AppDatabase
import com.example.data.local.DocumentEntity
import com.example.data.local.HighlightEntity
import com.example.reader.ActiveCourse
import com.example.reader.Courses
import com.example.reader.courseTag
import com.example.reader.pdfPageOfPrinted
import com.example.reader.readingRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Opened from the side menu: your programmes, semesters and courses, with each course's outline and readings. */
val coursesOpen = mutableStateOf(false)

private val CGrey = Color(0xFF52525B)
private val CRule = Color(0xFFD4D4D8)

@Composable
fun CoursesHost(onOpenDocument: (DocumentEntity) -> Unit, onOpenDocumentAtPage: (DocumentEntity, Int, HighlightEntity?) -> Unit) {
    if (!coursesOpen.value) return
    Dialog(onDismissRequest = { coursesOpen.value = false }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        }
        Surface(Modifier.fillMaxSize(), color = Color.White) { CoursesScreen(onClose = { coursesOpen.value = false }, onOpenDocument, onOpenDocumentAtPage) }
    }
}

/** The course shown (kept while a reading is open, so Back from the reader returns to it). */
private val openCourseId = mutableStateOf<String?>(null)

private class CourseRow(val programme: String, val semester: String, val course: JSONObject)

private fun allCourses(data: JSONObject): List<CourseRow> {
    val out = ArrayList<CourseRow>()
    val ps = data.optJSONArray("programmes") ?: JSONArray()
    for (a in 0 until ps.length()) { val p = ps.getJSONObject(a); val ss = p.optJSONArray("semesters") ?: continue
        for (b in 0 until ss.length()) { val s = ss.getJSONObject(b); val cs = s.optJSONArray("courses") ?: continue
            for (c in 0 until cs.length()) out.add(CourseRow(p.optString("name"), s.optString("name"), cs.getJSONObject(c))) } }
    return out
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

/** Undated sessions with only a week number sort among the dated ones of that week (approximately). */
private fun weekKey(x: JSONObject): String = x.optString("week").toIntOrNull()?.let { "~w" + it.toString().padStart(2, '0') } ?: "9999"

private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
private fun niceDate(iso: String): String = runCatching { LocalDate.parse(iso).format(dayFmt) }.getOrDefault(iso.ifBlank { "No date" })

@Composable
fun CoursesScreen(onClose: () -> Unit, onOpenDocument: (DocumentEntity) -> Unit, onOpenDocumentAtPage: (DocumentEntity, Int, HighlightEntity?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var docs by remember { mutableStateOf<Map<Long, DocumentEntity>>(emptyMap()) }
    var notes by remember { mutableStateOf<List<HighlightEntity>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    val active by ActiveCourse.current
    // Re-read whenever the screen comes back (a reading may have been marked as done in the reader).
    LaunchedEffect(version, active?.done) {
        withContext(Dispatchers.IO) {
            data = runCatching { Courses.data(context) }.getOrNull()
            val db = AppDatabase.getInstance(context)
            docs = db.documentDao().getAllDocumentsSnapshot().associateBy { it.id }
            notes = runCatching { db.highlightDao().getAllHighlights().first() }.getOrDefault(emptyList())
        }
    }
    val courseId = openCourseId.value
    BackHandler { if (courseId != null) openCourseId.value = null else onClose() }
    val d = data
    val rows = remember(d) { if (d == null) emptyList() else allCourses(d) }
    val row = rows.firstOrNull { it.course.optString("id") == courseId }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InkButton("←", onClick = { if (courseId != null) openCourseId.value = null else onClose() })
            Text(row?.course?.let { it.optString("code").ifBlank { it.optString("title") } } ?: "Courses",
                fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Color.Black, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            InkButton("Refresh", onClick = { version++ })
        }
        when {
            d == null -> Text("Reading the courses…", color = CGrey)
            row != null -> CourseDetail(row, docs, notes,
                onStatus = { readingId, status ->
                    scope.launch { withContext(Dispatchers.IO) { Courses.setStatus(context, row.course.optString("id"), readingId, status) }; version++ }
                },
                onOpen = { reading, doc ->
                    val c = row.course
                    val range = readingRange(reading.optString("pages"))
                    val isPdf = doc.fileType.equals("PDF", true)
                    val start = if (range != null && isPdf) pdfPageOfPrinted(context, doc.id, range.first, doc.totalPages) else null
                    val end = if (range != null && isPdf) pdfPageOfPrinted(context, doc.id, range.second, doc.totalPages) else null
                    val label = listOf(reading.optString("chapter"), reading.optString("pages").takeIf { it.isNotBlank() }?.let { "pp. $it" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
                    ActiveCourse.current.value = ActiveCourse.Reading(doc.id, c.optString("id"), reading.optString("id"), c.optString("code").ifBlank { c.optString("title") },
                        courseTag(c), label.ifBlank { "reading" }, end, reading.optString("status") == "done")
                    if (reading.optString("status") == "unread" || reading.optString("status").isBlank())
                        scope.launch(Dispatchers.IO) { Courses.setStatus(context, c.optString("id"), reading.optString("id"), "started") }
                    if (start != null) onOpenDocumentAtPage(doc, start, null) else onOpenDocument(doc)
                })
            rows.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No courses yet.", fontSize = 18.sp, color = Color.Black)
                Text("Make your programmes, semesters and courses in the web view (Courses tab); they show here, with their outlines and readings.", color = CGrey)
            }
            else -> CourseList(rows) { openCourseId.value = it }
        }
    }
}

@Composable
private fun CourseList(rows: List<CourseRow>, onOpen: (String) -> Unit) {
    val today = LocalDate.now().toString()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.groupBy { it.programme + " · " + it.semester }.forEach { (head, list) ->
            item(key = "h$head") { Text(head.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CGrey, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)) }
            items(list, key = { it.course.optString("id") }) { r ->
                val c = r.course
                val reads = c.optJSONArray("sessions").objects().flatMap { it.optJSONArray("readings").objects() }
                val done = reads.count { it.optString("status") == "done" }
                val next = c.optJSONArray("sessions").objects().filter { it.optString("date") >= today && it.optString("date").isNotBlank() }.minByOrNull { it.optString("date") + it.optString("time") }
                Column(Modifier.fillMaxWidth().border(1.dp, Color.Black).clickable { onOpen(c.optString("id")) }.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (c.optString("code").isNotBlank()) Text(c.optString("code"), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(c.optString("title"), fontFamily = FontFamily.Serif, fontSize = 18.sp, color = Color.Black, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (reads.isNotEmpty()) ProgressLine(done, reads.count { it.optString("status") == "started" }, reads.size)
                    next?.let { Text("Next: ${niceDate(it.optString("date"))}${it.optString("time").takeIf { t -> t.isNotBlank() }?.let { t -> " · $t" } ?: ""} — ${it.optString("topic").ifBlank { it.optString("kind", "Lecture") }}", fontSize = 13.sp, color = CGrey, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

@Composable
private fun ProgressLine(done: Int, started: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(160.dp).height(8.dp).border(1.dp, Color.Black)) {
            Row(Modifier.fillMaxSize()) {
                if (done > 0) Box(Modifier.fillMaxHeight().weight(done.toFloat()).background(Color.Black))
                if (started > 0) Box(Modifier.fillMaxHeight().weight(started.toFloat()).background(Color(0xFF9CA3AF)))
                if (total - done - started > 0) Spacer(Modifier.weight((total - done - started).toFloat()))
            }
        }
        Text("$done of $total readings done${if (started > 0) ", $started started" else ""}", fontSize = 12.sp, color = CGrey)
    }
}

@Composable
private fun CourseDetail(row: CourseRow, docs: Map<Long, DocumentEntity>, notes: List<HighlightEntity>, onStatus: (String, String) -> Unit, onOpen: (JSONObject, DocumentEntity) -> Unit) {
    val context = LocalContext.current
    val c = row.course
    val today = LocalDate.now().toString()
    val sessions = remember(c) { c.optJSONArray("sessions").objects().sortedWith(compareBy({ it.optString("date").ifBlank { weekKey(it) } }, { it.optString("time") })) }
    val nextIndex = sessions.indexOfFirst { it.optString("date").isNotBlank() && it.optString("date") >= today }
    val tag = courseTag(c)
    val tagged = remember(notes, tag) { notes.count { n -> n.tags.split(',').any { it.trim().removePrefix("#").equals(tag, true) } } }
    val reads = sessions.flatMap { it.optJSONArray("readings").objects() }
    val listState = rememberLazyListState()
    LaunchedEffect(c.optString("id")) { if (nextIndex > 0) listState.scrollToItem(nextIndex + 2) }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(listOf(c.optString("code"), c.optString("credits").takeIf { it.isNotBlank() && it != "0" }?.let { "$it credits" }.orEmpty(), row.semester).filter { it.isNotBlank() }.joinToString(" · ").uppercase(),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = CGrey)
                Text(c.optString("title"), fontFamily = FontFamily.Serif, fontSize = 22.sp, color = Color.Black)
                if (c.optString("description").isNotBlank()) Text(c.optString("description"), fontSize = 14.sp, color = Color.Black, maxLines = 6, overflow = TextOverflow.Ellipsis)
                if (reads.isNotEmpty()) ProgressLine(reads.count { it.optString("status") == "done" }, reads.count { it.optString("status") == "started" }, reads.size)
                if (tagged > 0) Text("$tagged note${if (tagged == 1) "" else "s"} tagged #$tag", fontSize = 12.sp, color = CGrey)
            }
        }
        val teachers = c.optJSONArray("teachers").objects()
        if (teachers.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("TEACHERS", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CGrey, modifier = Modifier.padding(top = 8.dp))
                teachers.forEach { t -> TeacherRow(t) }
            }
        }
        item { Text("COURSE OUTLINE", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CGrey, modifier = Modifier.padding(top = 8.dp)) }
        if (sessions.isEmpty()) item { Text("No sessions yet. Add them in the web view (Courses ▸ the course).", color = CGrey) }
        items(sessions.size) { i ->
            val x = sessions[i]
            val isNext = i == nextIndex
            val past = x.optString("date").isNotBlank() && x.optString("date") < today
            Column(Modifier.fillMaxWidth().border(if (isNext) 2.dp else 1.dp, if (past) CRule else Color.Black).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (x.optString("date").isBlank() && x.optString("week").isNotBlank()) "Week " + x.optString("week") else niceDate(x.optString("date")), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (past) CGrey else Color.Black)
                    Text(listOf(x.optString("time"), x.optString("room")).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 13.sp, color = CGrey)
                    if (isNext) Text("NEXT", modifier = Modifier.background(Color.Black).padding(horizontal = 6.dp, vertical = 1.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(x.optString("kind", "Lecture"), fontSize = 12.sp, color = CGrey)
                }
                if (x.optString("topic").isNotBlank() || x.optString("lecturer").isNotBlank()) Text(x.optString("topic") + (x.optString("lecturer").takeIf { it.isNotBlank() }?.let { (if (x.optString("topic").isBlank()) "" else " · ") + it } ?: ""), fontFamily = FontFamily.Serif, fontSize = 17.sp, color = Color.Black)
                if (x.optString("note").isNotBlank()) Text(x.optString("note"), fontSize = 13.sp, color = CGrey)
                x.optJSONArray("readings").objects().forEach { r ->
                    ReadingLine(r, r.optLong("docId", 0L).takeIf { it > 0 }?.let { docs[it] }, notes, tag,
                        onStatus = { onStatus(r.optString("id"), it) },
                        onOpen = { doc -> onOpen(r, doc) })
                }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun TeacherRow(t: JSONObject) {
    val context = LocalContext.current
    val photo = remember(t.optString("photo")) {
        t.optString("photo").takeIf { it.isNotBlank() }?.let { name -> runCatching { BitmapFactory.decodeFile(Courses.photo(context, name).path)?.asImageBitmap() }.getOrNull() }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (photo != null) Image(photo, null, Modifier.size(48.dp).border(1.dp, Color.Black), contentScale = ContentScale.Crop)
        else Box(Modifier.size(48.dp).border(1.dp, Color.Black), contentAlignment = Alignment.Center) {
            Text(t.optString("name").split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }, fontWeight = FontWeight.Bold)
        }
        Column {
            Text(t.optString("name") + (t.optString("role").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), fontWeight = FontWeight.SemiBold, color = Color.Black)
            Text(listOf(t.optString("title"), t.optString("email"), t.optString("phone"), t.optString("office")).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = CGrey)
        }
    }
}

@Composable
private fun ReadingLine(r: JSONObject, doc: DocumentEntity?, notes: List<HighlightEntity>, tag: String, onStatus: (String) -> Unit, onOpen: (DocumentEntity) -> Unit) {
    val status = r.optString("status").ifBlank { "unread" }
    val count = remember(notes, doc, tag) { if (doc == null) 0 else notes.count { n -> n.documentId == doc.id && n.tags.split(',').any { it.trim().removePrefix("#").equals(tag, true) } } }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Tap the box to go unread → started → done → unread.
        Box(Modifier.size(26.dp).border(1.dp, Color.Black).background(if (status == "done") Color.Black else Color.White)
            .clickable { onStatus(when (status) { "unread" -> "started"; "started" -> "done"; else -> "unread" }) }, contentAlignment = Alignment.Center) {
            Text(when (status) { "done" -> "✓"; "started" -> "½"; else -> "" }, color = if (status == "done") Color.White else Color.Black, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(doc?.title ?: r.optString("text").ifBlank { "(reading)" }, color = if (status == "done") CGrey else Color.Black, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(doc?.author?.takeIf { it.isNotBlank() }, r.optString("chapter").takeIf { it.isNotBlank() }, r.optString("pages").takeIf { it.isNotBlank() }?.let { "pp. $it" },
                "optional".takeIf { r.optString("required") == "optional" }, if (count > 0) "$count note${if (count == 1) "" else "s"}" else null,
                "not in the library".takeIf { doc == null }).joinToString(" · "), fontSize = 12.sp, color = CGrey, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (doc != null) InkButton("Open", filled = status != "done", onClick = { onOpen(doc) })
    }
}

/** The bar at the bottom of the reader while a course reading is open: the course, the reading, Done, and × to stop tagging. */
@Composable
fun CourseReadingPill(docId: Long) {
    val context = LocalContext.current
    val r = ActiveCourse.current.value?.takeIf { it.docId == docId } ?: return
    androidx.compose.ui.window.Popup(alignment = Alignment.BottomStart) {
        Row(Modifier.padding(start = 8.dp, bottom = 100.dp).border(1.dp, Color.Black).background(Color.White).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(r.code, fontWeight = FontWeight.Bold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text("${r.label} · notes get #${r.tag}", fontSize = 12.sp, color = CGrey, maxLines = 1)
            Text(if (r.done) "✓ Done" else "Done?", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.border(1.dp, Color.Black).background(if (r.done) Color.Black else Color.White).clickable {
                    ActiveCourse.toggleDone(context)
                    if (!r.done) Toast.makeText(context, "Reading done · ${r.code}", Toast.LENGTH_SHORT).show()
                }.padding(horizontal = 6.dp, vertical = 2.dp), color = if (r.done) Color.White else Color.Black)
            Text("×", fontSize = 16.sp, modifier = Modifier.clickable { ActiveCourse.current.value = null }.padding(horizontal = 4.dp))
        }
    }
}
