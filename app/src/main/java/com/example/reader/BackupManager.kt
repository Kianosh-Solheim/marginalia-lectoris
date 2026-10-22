package com.example.reader

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Full backups: library database, settings, bibliographic data, handwriting sketches, covers and the
 * book files themselves, in one .zip written through Android's file picker, so it can go to Google
 * Drive, OneDrive, Dropbox, a USB drive or the tablet's own storage. API keys are never included
 * (they are encrypted to this device).
 */
object BackupManager {
    private const val TAG = "Backup"
    private const val DB_NAME = "readmark_database.db"
    private const val PREFS = "backup_prefs"
    private const val WORK_NAME = "marginalia_daily_backup"
    private const val FORMAT = 1

    fun suggestedFileName(): String =
        "Marginalia-Lectoris-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())}.zip"

    // ------------------------------------------------------------------ write

    /** Writes a complete backup to [out]. Returns the number of book files included. */
    suspend fun writeBackup(context: Context, out: OutputStream): Int = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val db = AppDatabase.getInstance(app)
        // Flush the write-ahead log so the main database file is complete.
        runCatching { db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() } }
        val documents = db.documentDao().getAllDocumentsSnapshot()

        var books = 0
        ZipOutputStream(out.buffered(1 shl 16)).use { zip ->
            fun addFile(entry: String, f: File) {
                if (!f.isFile) return
                zip.putNextEntry(ZipEntry(entry))
                f.inputStream().use { it.copyTo(zip, 1 shl 16) }
                zip.closeEntry()
            }

            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("created", System.currentTimeMillis())
                .put("app", app.packageName)

            // Database
            val dbFile = app.getDatabasePath(DB_NAME)
            addFile("db/$DB_NAME", dbFile)
            addFile("db/$DB_NAME-wal", File(dbFile.path + "-wal"))

            // Settings (shared preferences), excluding WorkManager/internal files
            File(app.applicationInfo.dataDir, "shared_prefs").listFiles()?.filter {
                it.isFile && it.name.endsWith(".xml") && !it.name.startsWith("androidx.work")
            }?.forEach { addFile("prefs/${it.name}", it) }

            // Bibliography + sketches
            addFile("files/bibliography.json", File(app.filesDir, "bibliography.json"))
            addFile("files/edited_volumes.json", File(app.filesDir, "edited_volumes.json"))
            addFile("files/folder_books.json", File(app.filesDir, "folder_books.json"))
            File(app.filesDir, "sketches").listFiles()?.forEach { addFile("files/sketches/${it.name}", it) }

            // Books and covers, with their original locations
            val bookList = JSONArray()
            documents.forEach { doc ->
                val src = File(doc.filePath)
                val item = JSONObject().put("id", doc.id).put("path", doc.filePath)
                if (src.isFile) {
                    val entry = "books/${doc.id}/${src.name}"
                    addFile(entry, src)
                    item.put("entry", entry)
                    books++
                } else if (src.isDirectory) {
                    // DAISY books are folders
                    src.walkTopDown().filter { it.isFile }.forEach { f ->
                        addFile("books/${doc.id}/${src.name}/${f.relativeTo(src).path}", f)
                    }
                    item.put("entryDir", "books/${doc.id}/${src.name}")
                    books++
                }
                doc.coverUri?.let { cover ->
                    val cf = File(cover.removePrefix("file://"))
                    if (cf.isFile) {
                        val entry = "covers/${doc.id}/${cf.name}"
                        addFile(entry, cf)
                        item.put("coverEntry", entry).put("coverPath", cf.path)
                    }
                }
                bookList.put(item)
            }
            manifest.put("books", bookList)

            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("lastBackup", System.currentTimeMillis())
            .putString("lastSignature", signature(app))
            .apply()
        books
    }

    suspend fun backupToUri(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the backup destination.")
        out.use { writeBackup(context, it) }
    }

    fun lastBackupTime(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("lastBackup", 0L)

    /** Cheap fingerprint of everything a backup contains, to skip unchanged daily backups. */
    private fun signature(context: Context): String {
        val app = context.applicationContext
        val dbFile = app.getDatabasePath(DB_NAME)
        val parts = mutableListOf(
            dbFile.lastModified(), dbFile.length(),
            File(dbFile.path + "-wal").let { it.lastModified() + it.length() },
            File(app.filesDir, "bibliography.json").lastModified(),
            File(app.filesDir, "edited_volumes.json").lastModified(),
            File(app.filesDir, "folder_books.json").lastModified(),
            File(app.filesDir, "sketches").listFiles()?.sumOf { it.lastModified() } ?: 0L,
            File(app.applicationInfo.dataDir, "shared_prefs").listFiles()?.sumOf { it.lastModified() } ?: 0L
        )
        return parts.joinToString(":")
    }

    // ------------------------------------------------------------------ automatic

    /** The file that automatic backups overwrite (chosen once with the system file picker). */
    fun autoTarget(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("autoUri", null)?.let(Uri::parse)

    fun enableAuto(context: Context, target: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                target, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("autoUri", target.toString()).apply()
        val request = PeriodicWorkRequestBuilder<DailyBackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun disableAuto(context: Context) {
        autoTarget(context)?.let { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("autoUri").apply()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** Runs the automatic backup if anything changed since the last one. */
    suspend fun runAutoBackup(context: Context): Boolean {
        val target = autoTarget(context) ?: return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("lastSignature", null) == signature(context)) {
            Log.i(TAG, "Nothing changed since the last backup; skipping.")
            return true
        }
        backupToUri(context, target)
        return true
    }

    // ------------------------------------------------------------------ restore

    /**
     * Restores a backup over the current library. The app restarts afterwards so every screen and
     * setting is reloaded from the restored data.
     */
    suspend fun restore(context: Context, input: InputStream) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val staging = File(app.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }

        // 1. Unpack into a staging folder (validating entry paths).
        ZipInputStream(input.buffered(1 shl 16)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val target = File(staging, e.name).canonicalFile
                require(target.path.startsWith(staging.canonicalPath + File.separator)) { "Invalid backup file." }
                if (e.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it, 1 shl 16) }
                }
                e = zip.nextEntry
            }
        }
        val manifestFile = File(staging, "manifest.json")
        require(manifestFile.isFile) { "This file isn't a Marginalia Lectoris backup." }
        val manifest = JSONObject(manifestFile.readText())
        val stagedDb = File(staging, "db/$DB_NAME")
        require(stagedDb.isFile) { "The backup contains no library database." }

        // 2. Books and covers: original location if possible, otherwise the app's own storage.
        val pathFixes = mutableMapOf<Long, String>()
        val coverFixes = mutableMapOf<Long, String>()
        val books = manifest.optJSONArray("books") ?: JSONArray()
        for (i in 0 until books.length()) {
            val b = books.getJSONObject(i)
            val id = b.getLong("id")
            val original = File(b.getString("path"))
            val fallbackDir = File(app.getExternalFilesDir(null) ?: app.filesDir, "restored_books/$id")
            fun place(staged: File, preferred: File, fallback: File): String? {
                if (!staged.exists()) return null
                for (dest in listOf(preferred, fallback)) {
                    val ok = runCatching {
                        dest.parentFile?.mkdirs()
                        if (staged.isDirectory) staged.copyRecursively(dest, overwrite = true) else staged.copyTo(dest, overwrite = true)
                        true
                    }.getOrDefault(false)
                    if (ok) return dest.path
                }
                return null
            }
            val entry = b.optString("entry").ifBlank { b.optString("entryDir") }
            if (entry.isNotBlank()) {
                val placed = place(File(staging, entry), original, File(fallbackDir, original.name))
                if (placed != null && placed != original.path) pathFixes[id] = placed
            }
            val coverEntry = b.optString("coverEntry")
            if (coverEntry.isNotBlank()) {
                val coverOriginal = File(b.optString("coverPath"))
                val placed = place(File(staging, coverEntry), coverOriginal, File(fallbackDir, coverOriginal.name))
                if (placed != null && placed != coverOriginal.path) coverFixes[id] = placed
            }
        }

        // 3. Fix moved paths inside the restored database before installing it.
        if (pathFixes.isNotEmpty() || coverFixes.isNotEmpty()) {
            val walStaged = File(staging, "db/$DB_NAME-wal")
            SQLiteDatabase.openDatabase(stagedDb.path, null, SQLiteDatabase.OPEN_READWRITE).use { sdb ->
                pathFixes.forEach { (id, p) -> sdb.execSQL("UPDATE documents SET filePath = ? WHERE id = ?", arrayOf(p, id)) }
                coverFixes.forEach { (id, p) -> sdb.execSQL("UPDATE documents SET coverUri = ? WHERE id = ?", arrayOf(p, id)) }
            }
            walStaged.delete()
        }

        // 4. Install database, settings, bibliography and sketches.
        AppDatabase.closeForRestore()
        val dbFile = app.getDatabasePath(DB_NAME)
        listOf("", "-wal", "-shm").forEach { File(dbFile.path + it).delete() }
        stagedDb.copyTo(dbFile, overwrite = true)
        File(staging, "db/$DB_NAME-wal").takeIf { it.isFile }?.copyTo(File(dbFile.path + "-wal"), overwrite = true)

        val prefsDir = File(app.applicationInfo.dataDir, "shared_prefs")
        File(staging, "prefs").listFiles()?.forEach { it.copyTo(File(prefsDir, it.name), overwrite = true) }
        File(staging, "files/bibliography.json").takeIf { it.isFile }?.copyTo(File(app.filesDir, "bibliography.json"), overwrite = true)
        File(staging, "files/edited_volumes.json").takeIf { it.isFile }?.copyTo(File(app.filesDir, "edited_volumes.json"), overwrite = true)
        File(staging, "files/folder_books.json").takeIf { it.isFile }?.copyTo(File(app.filesDir, "folder_books.json"), overwrite = true)
        File(staging, "files/sketches").takeIf { it.isDirectory }?.copyRecursively(File(app.filesDir, "sketches"), overwrite = true)
        staging.deleteRecursively()
    }

    /** Restarts the app so restored data and settings are loaded fresh. */
    fun restartApp(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)
        Runtime.getRuntime().exit(0)
    }
}

class DailyBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (BackupManager.runAutoBackup(applicationContext)) Result.success() else Result.failure()
    } catch (t: Throwable) {
        Log.w("Backup", "Automatic backup failed", t)
        Result.retry()
    }
}
