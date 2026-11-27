package com.example.reader

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.data.local.AppDatabase
import com.example.data.local.SyncTriggers
import org.json.JSONArray
import org.json.JSONObject

/**
 * Highlights and notes between a paired phone and the tablet: the same rules as the backup merge, in
 * small pieces. Each highlight is matched by its uuid and its book by the book's uuid; where both sides
 * changed it, the later change wins; a deletion wins over an older change and loses to a newer one.
 *
 * Rows are sent as all their columns (but the local ids), so a column added later travels too.
 * Times are sent on the sender's clock; the receiver shifts them by [apply]'s `shift` onto its own.
 */
object NoteSync {
    private val skip = setOf("id", "documentId")

    private fun db(ctx: Context) = AppDatabase.getInstance(ctx).openHelper.writableDatabase

    private fun columns(ctx: Context): List<String> =
        db(ctx).query("PRAGMA table_info(highlights)").use { c -> buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) } }

    /** Highlights of the books [docUuids] changed after [since], and highlight deletions after [since]. */
    fun changes(ctx: Context, docUuids: Collection<String>, since: Long): JSONObject {
        val cols = columns(ctx).filter { it !in skip }
        val out = JSONArray()
        val d = db(ctx)
        docUuids.filter { it.isNotBlank() }.chunked(200).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            d.query(
                "SELECT h.*, d.uuid AS docUuid FROM highlights h JOIN documents d ON d.id = h.documentId WHERE d.uuid IN ($marks) AND h.updatedAt > ? AND h.uuid <> ''",
                (chunk + since.toString()).toTypedArray()
            ).use { c ->
                while (c.moveToNext()) {
                    val o = JSONObject().put("docUuid", c.getString(c.getColumnIndexOrThrow("docUuid")))
                    for (name in cols) {
                        val i = c.getColumnIndex(name); if (i < 0) continue
                        when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_INTEGER -> o.put(name, c.getLong(i))
                            android.database.Cursor.FIELD_TYPE_FLOAT -> o.put(name, c.getDouble(i))
                            android.database.Cursor.FIELD_TYPE_NULL -> o.put(name, JSONObject.NULL)
                            else -> o.put(name, c.getString(i))
                        }
                    }
                    out.put(o)
                }
            }
        }
        val deleted = JSONArray()
        d.query("SELECT uuid, deletedAt FROM tombstones WHERE kind = 'highlight' AND deletedAt > ?", arrayOf(since.toString())).use { c ->
            while (c.moveToNext()) deleted.put(JSONObject().put("uuid", c.getString(0)).put("deletedAt", c.getLong(1)))
        }
        return JSONObject().put("highlights", out).put("deleted", deleted).put("now", System.currentTimeMillis())
    }

    data class Result(val added: Int, val changed: Int, val removed: Int) {
        val total get() = added + changed + removed
    }

    /**
     * Applies changes from the other side. [shift] is added to their times to put them on this clock.
     * The sync triggers are off while this runs (in one transaction), so each row keeps the time it was changed.
     */
    fun apply(ctx: Context, changes: JSONObject, shift: Long): Result {
        val cols = columns(ctx).filter { it !in skip }.toSet()
        val d = db(ctx)
        var added = 0; var changed = 0; var removed = 0
        d.beginTransaction()
        try {
            SyncTriggers.drop(d::execSQL)
            val del = changes.optJSONArray("deleted") ?: JSONArray()
            for (i in 0 until del.length()) {
                val o = del.getJSONObject(i)
                val u = o.optString("uuid"); val at = o.optLong("deletedAt") + shift
                if (u.isBlank()) continue
                val mine = d.query("SELECT updatedAt FROM highlights WHERE uuid = ?", arrayOf(u)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                if (mine != null && mine < at) { d.delete("highlights", "uuid = ?", arrayOf(u)); removed++ }
                if (mine == null || mine < at) d.insert("tombstones", SQLiteDatabase.CONFLICT_REPLACE, ContentValues().apply { put("kind", "highlight"); put("uuid", u); put("deletedAt", at) })
            }
            val rows = changes.optJSONArray("highlights") ?: JSONArray()
            for (i in 0 until rows.length()) {
                val o = rows.getJSONObject(i)
                val u = o.optString("uuid"); if (u.isBlank()) continue
                val docId = d.query("SELECT id FROM documents WHERE uuid = ? LIMIT 1", arrayOf(o.optString("docUuid"))).use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: continue
                val at = o.optLong("updatedAt") + shift
                val gone = d.query("SELECT deletedAt FROM tombstones WHERE kind = 'highlight' AND uuid = ?", arrayOf(u)).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                if (gone != null && gone >= at) continue
                val mine = d.query("SELECT id, updatedAt FROM highlights WHERE uuid = ?", arrayOf(u)).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null }
                if (mine != null && mine.second >= at) continue
                val v = ContentValues()
                o.keys().forEach { k ->
                    if (k !in cols) return@forEach
                    when (val x = o.get(k)) {
                        JSONObject.NULL -> v.putNull(k)
                        is Int -> v.put(k, x.toLong()); is Long -> v.put(k, x); is Double -> v.put(k, x)
                        is Boolean -> v.put(k, if (x) 1L else 0L)
                        else -> v.put(k, x.toString())
                    }
                }
                v.put("documentId", docId); v.put("updatedAt", at)
                if (o.has("createdTimestamp")) v.put("createdTimestamp", o.optLong("createdTimestamp") + shift)
                if (mine == null) { d.insert("highlights", SQLiteDatabase.CONFLICT_ABORT, v); added++ }
                else { d.update("highlights", SQLiteDatabase.CONFLICT_ABORT, v, "id = ?", arrayOf(mine.first)); changed++ }
                if (gone != null) d.delete("tombstones", "kind = 'highlight' AND uuid = ?", arrayOf(u))
            }
            SyncTriggers.create(d::execSQL)
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        // Outside a successful transaction the drop was rolled back with it; make sure the triggers exist either way.
        runCatching { SyncTriggers.create(d::execSQL) }
        return Result(added, changed, removed)
    }
}
