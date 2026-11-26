package com.example.data.local

/**
 * SQLite triggers that keep `uuid` and `updatedAt` current on documents and highlights and record
 * deletions in `tombstones`. Because the database does it, every way the app edits a book or a
 * highlight (Room @Update, @Insert(REPLACE), @Query UPDATE …) is covered without changing that code.
 *
 * Plain SQL with an exec function, so it works with Room's SupportSQLiteDatabase and with the
 * android.database.sqlite.SQLiteDatabase used on backup copies.
 */
object SyncTriggers {
    private const val NOW = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
    private const val NEW_UUID = "lower(hex(randomblob(16)))"

    private val tables = listOf("documents" to "document", "highlights" to "highlight")

    val names: List<String> = tables.flatMap { (t, _) -> listOf("${t}_sync_insert", "${t}_sync_update", "${t}_sync_delete") }

    /**
     * Creates the triggers, replacing any older version of them (so a fix to them reaches databases made
     * before it: this runs each time the database is opened).
     */
    fun create(exec: (String) -> Unit) {
        drop(exec)
        tables.forEach { (t, kind) ->
            // New rows (and rows rewritten by INSERT OR REPLACE): give them an ID if missing, and mark them as changed now.
            exec("""CREATE TRIGGER IF NOT EXISTS ${t}_sync_insert AFTER INSERT ON $t BEGIN
                UPDATE $t SET uuid = CASE WHEN NEW.uuid = '' THEN $NEW_UUID ELSE NEW.uuid END, updatedAt = $NOW WHERE rowid = NEW.rowid;
                END""")
            // Edits: a change that doesn't move updatedAt forward itself gets the current time, and always a
            // later time than before (MAX(now, old + 1)). Plain "now" was not enough: two edits in the same
            // millisecond (turning pages saves the place twice), or a change time ahead of this clock, left
            // updatedAt where it was, so the trigger's own UPDATE set it off again, until SQLite stopped it
            // with "too many levels of trigger recursion" and the app crashed.
            exec("""CREATE TRIGGER IF NOT EXISTS ${t}_sync_update AFTER UPDATE ON $t WHEN NEW.updatedAt <= OLD.updatedAt BEGIN
                UPDATE $t SET updatedAt = MAX($NOW, OLD.updatedAt + 1) WHERE rowid = NEW.rowid;
                END""")
            exec("""CREATE TRIGGER IF NOT EXISTS ${t}_sync_delete AFTER DELETE ON $t WHEN OLD.uuid <> '' BEGIN
                INSERT OR REPLACE INTO tombstones (kind, uuid, deletedAt) VALUES ('$kind', OLD.uuid, $NOW);
                END""")
        }
    }

    /** Removed while a backup copy is edited (path fixes, merging), so those edits keep their own times. */
    fun drop(exec: (String) -> Unit) {
        names.forEach { exec("DROP TRIGGER IF EXISTS $it") }
    }

    /** Gives existing rows an ID and a change time (run before the triggers exist). */
    fun backfill(exec: (String) -> Unit) {
        exec("UPDATE documents SET uuid = $NEW_UUID WHERE uuid = ''")
        exec("UPDATE documents SET updatedAt = MAX(dateAdded, lastReadTimestamp) WHERE updatedAt = 0")
        exec("UPDATE highlights SET uuid = $NEW_UUID WHERE uuid = ''")
        exec("UPDATE highlights SET updatedAt = createdTimestamp WHERE updatedAt = 0")
    }
}
