package com.example.reader

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What you said about a highlight while recording, taken from a recording's transcript.
 * Kept per highlight in files/spoken_comments.json and written into the highlight's note.
 */
object SpokenComments {
    data class Entry(val recording: String, val audio: String, val startMs: Long, val text: String)

    val version = MutableStateFlow(0)
    private var file: File? = null
    private val map = HashMap<Long, MutableList<Entry>>()

    @Synchronized fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "spoken_comments.json")
        file = f
        runCatching {
            if (!f.isFile) return@runCatching
            val o = JSONObject(f.readText())
            o.keys().forEach { k ->
                val a = o.getJSONArray(k)
                map[k.toLong()] = (0 until a.length()).map { i -> a.getJSONObject(i) }
                    .map { Entry(it.optString("rec"), it.optString("audio"), it.optLong("t"), it.optString("text")) }.toMutableList()
            }
        }
    }

    @Synchronized fun forHighlight(id: Long): List<Entry> = map[id].orEmpty().sortedBy { it.startMs }

    /** Replaces everything one recording said about highlights. */
    @Synchronized fun setForRecording(recording: String, byHighlight: Map<Long, List<Entry>>) {
        map.values.forEach { l -> l.removeAll { it.recording == recording } }
        byHighlight.forEach { (id, l) -> map.getOrPut(id) { mutableListOf() }.addAll(l) }
        map.entries.removeAll { it.value.isEmpty() }
        save()
    }

    @Synchronized fun removeRecording(recording: String) {
        val before = map.values.sumOf { it.size }
        map.values.forEach { l -> l.removeAll { it.recording == recording } }
        map.entries.removeAll { it.value.isEmpty() }
        if (map.values.sumOf { it.size } != before) save()
    }

    private fun save() {
        val f = file ?: return
        val o = JSONObject()
        map.forEach { (id, l) ->
            o.put(id.toString(), JSONArray().apply { l.forEach { e -> put(JSONObject().put("rec", e.recording).put("audio", e.audio).put("t", e.startMs).put("text", e.text)) } })
        }
        runCatching { f.writeText(o.toString()) }
        version.value++
    }

    fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** The section added at the end of a highlight's note. */
    fun markdownFor(id: Long): String {
        val l = forHighlight(id)
        if (l.isEmpty()) return ""
        val sb = StringBuilder("\n## Spoken comments\n")
        l.forEach { e ->
            sb.append("\n**[[").append(e.audio).append("#t=").append(e.startMs / 1000).append("|▶ ").append(clock(e.startMs)).append("]]** ")
                .append(e.text.trim()).append('\n')
        }
        return sb.toString()
    }
}
