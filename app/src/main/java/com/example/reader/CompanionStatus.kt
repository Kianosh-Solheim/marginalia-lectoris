package com.example.reader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * What Marginalia Companion on a computer is doing: it sends its jobs here every few seconds while it can reach
 * the tablet (PUT /api/companion/status); a paired phone fetches them from the tablet. Shown on the book, as a
 * strip in the library, in a notification and in the web view. Older than three minutes counts as gone.
 */
object CompanionStatus {
    data class Job(val uuid: String, val title: String, val state: String, val frac: Double, val msg: String, val label: String) {
        val active get() = state in setOf("queued", "transcribing", "matching", "sending", "send", "waiting")
        /** A short line: "Transcribing, 42% · about 12 min left". */
        fun line(): String = buildString {
            append(label.ifBlank { state })
            if (state in setOf("transcribing", "sending")) append(", ${(frac * 100).toInt()}%")
            msg.substringAfter(" · ", "").takeIf { it.isNotBlank() && state == "transcribing" }?.let { append(" · ").append(it.substringBefore(" · on ")) }
        }
    }
    data class Status(val computer: String, val at: Long, val jobs: List<Job>)

    private const val STALE_MS = 3 * 60_000L
    private const val CHANNEL = "computer"
    private const val NOTE_ID = 4711
    val state = mutableStateOf<Status?>(null)
    @Volatile private var raw: JSONObject? = null
    private var lastNote = 0L
    private var lastNoteKey = ""
    private val finished = mutableSetOf<String>()

    fun json(): JSONObject = (raw ?: JSONObject().put("jobs", org.json.JSONArray())).let { JSONObject(it.toString()).put("age", System.currentTimeMillis() - it.optLong("receivedAt")) }

    /** From the computer (on the tablet), or from the tablet (on a phone; [ageMs] is how old it was there). */
    fun update(ctx: Context, o: JSONObject, ageMs: Long = 0) {
        val received = System.currentTimeMillis() - ageMs
        raw = JSONObject(o.toString()).put("receivedAt", received)
        val a = o.optJSONArray("jobs")
        val jobs = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }.map {
            Job(it.optString("uuid"), it.optString("title"), it.optString("state"), it.optDouble("frac", 0.0), it.optString("msg"), it.optString("label"))
        }
        state.value = Status(o.optString("computer", "the computer"), received, jobs)
        notify(ctx.applicationContext)
    }

    fun current(): Status? = state.value?.takeIf { System.currentTimeMillis() - it.at < STALE_MS }
    fun activeJobs(): List<Job> = current()?.jobs?.filter { it.active }.orEmpty()
    fun forBook(uuid: String?): Job? = if (uuid.isNullOrBlank()) null else current()?.jobs?.firstOrNull { it.uuid == uuid && (it.active || it.state == "done") }

    /** A notification with a progress bar while the computer works (redrawn at most every 20 s, for e-ink). */
    private fun notify(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Computer: audiobook sync", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of Marginalia Companion on a computer"
                setShowBadge(false)
            })
        }
        val s = state.value ?: return
        val open = PendingIntent.getActivity(ctx, 0, ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // A job that just finished: one notification saying so.
        s.jobs.filter { it.state == "done" && it.uuid !in finished }.forEach { j ->
            finished += j.uuid
            if (lastNoteKey.contains(j.uuid)) runCatching {
                nm.notify(NOTE_ID + 1 + (j.uuid.hashCode() and 0xffff), NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Sync map ready: ${j.title}")
                    .setContentText(j.msg.ifBlank { "The text now follows the audio." }).setContentIntent(open).setAutoCancel(true).build())
            }
        }
        s.jobs.filter { it.active }.forEach { finished -= it.uuid }
        val active = s.jobs.filter { it.active }
        if (active.isEmpty()) { nm.cancel(NOTE_ID); lastNoteKey = ""; return }
        val j = active.firstOrNull { it.state == "transcribing" || it.state == "sending" } ?: active.first()
        val key = active.joinToString { it.uuid + it.state }
        val now = System.currentTimeMillis()
        if (key == lastNoteKey && now - lastNote < 20_000) return
        lastNote = now; lastNoteKey = key
        val more = if (active.size > 1) " (+${active.size - 1} more)" else ""
        runCatching {
            nm.notify(NOTE_ID, NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("${s.computer}: ${j.title}$more")
                .setContentText(j.line())
                .setProgress(1000, (j.frac * 1000).toInt(), j.state != "transcribing" && j.state != "sending")
                .setOnlyAlertOnce(true).setOngoing(true).setSilent(true).setContentIntent(open).build())
        }
    }
}
