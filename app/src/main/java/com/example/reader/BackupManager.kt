package com.example.reader

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.StorageManager
import com.example.data.local.AppDatabase
import com.example.data.local.SyncTriggers
import android.content.ContentValues
import android.database.Cursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Full backups in one .zip written through Android's file picker, so it can go to Google Drive,
 * OneDrive, Dropbox, a USB drive or the tablet's own storage.
 *
 * Format 2 contains everything the app keeps:
 *  - db/        the library database (books, highlights, Glossae, tags, reading positions …)
 *  - prefs/     settings
 *  - files/     the app's own data folder: audiobooks and their sync maps, speeches, spoken comments,
 *               Zotero data, sketches, bibliography, lexicon, courses, folder-sync state …
 *               (downloadable models and caches are left out; they can be fetched again)
 *  - root/      the library folder: book folders with their files, Markdown notes, RIS/BibTeX,
 *               recordings and transcripts, presentations, courses, writing
 *  - ext/       book, cover and audio files kept outside the library folder
 *  - manifest.json, written last, with the size and SHA-256 of every file
 *
 * Restoring checks every file against the manifest and refuses a backup made by a newer version of
 * the app before anything is changed, and swaps the data in so a failure rolls back to the library
 * as it was.
 *
 * A backup can be encrypted with a password ([BackupCrypto]); only then can it also carry the API
 * keys (settings/secrets.json), which otherwise never leave the tablet.
 */
object BackupManager {
    private const val TAG = "Backup"
    private const val DB_NAME = "readmark_database.db"
    private const val PREFS = "backup_prefs"
    private const val WORK_NAME = "marginalia_daily_backup"
    private const val FORMAT = 2

    /** Top-level items in filesDir that are downloaded models, generated caches or temporary. */
    private val SKIP_IN_FILES = setOf(
        "whisper", "latex", "voices", "models", "pandoc", "typst", "reflow",
        "gcal_cache.json", "restore", "profileInstalled"
    )
    /** Settings files that belong to this device rather than to the library. */
    private val DEVICE_PREFS = setOf("$PREFS.xml")
    /** Already-compressed formats: stored without compressing again, which is much faster. */
    private val STORE_EXT = setOf(
        "pdf", "epub", "jpg", "jpeg", "png", "webp", "gif", "heic", "avif", "mp3", "m4a", "m4b", "aac",
        "ogg", "opus", "flac", "mp4", "webm", "zip", "cbz", "docx", "pptx", "xlsx", "odp", "odt", "gz", "7z"
    )

    /** How a backup is made. The password is never stored. */
    class Options(val password: CharArray? = null, val includeKeys: Boolean = false) {
        val encrypted: Boolean get() = password != null && password.isNotEmpty()
    }

    /** Replace: the library becomes the backup. Merge: the backup is combined with what is here, newest change wins. */
    enum class RestoreMode { REPLACE, MERGE }

    class MergeReport {
        var booksAdded = 0; var booksUpdated = 0; var booksRemoved = 0
        var highlightsAdded = 0; var highlightsUpdated = 0; var highlightsRemoved = 0
        var filesAdded = 0; var filesUpdated = 0; var skippedOlder = 0
        fun summary(): String {
            val parts = mutableListOf<String>()
            fun part(n: Int, what: String) { if (n > 0) parts += "$n $what" }
            part(booksAdded, "books added"); part(booksUpdated, "books updated"); part(booksRemoved, "books removed (deleted elsewhere)")
            part(highlightsAdded, "highlights added"); part(highlightsUpdated, "highlights updated"); part(highlightsRemoved, "highlights removed (deleted elsewhere)")
            part(filesAdded, "files added"); part(filesUpdated, "files updated")
            if (skippedOlder > 0) parts += "$skippedOlder older items kept as they are here"
            return if (parts.isEmpty()) "Merged: nothing in the backup was newer than what is here." else "Merged: " + parts.joinToString(", ") + "."
        }
    }

    class PasswordRequiredException : IllegalArgumentException("This backup is encrypted. Enter its password.")

    data class Progress(val stage: String, val done: Long, val total: Long, val current: String = "") {
        val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    fun suggestedFileName(): String =
        "Marginalia-Lectoris-backup-${SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.ROOT).format(Date())}.zip"

    // ------------------------------------------------------------------ what goes in

    private class Item(val entry: String, val file: File)
    private class Plan(
        val items: List<Item>,
        val external: JSONArray,
        val root: File,
        val books: Int,
        val counts: JSONObject
    ) {
        val totalBytes: Long get() = items.sumOf { it.file.length() }
    }

    private fun isTemp(f: File) = f.name.endsWith(".tmp") || f.name.endsWith(".part") || f.name == ".incoming"

    private fun under(f: File, dir: File) = f.path == dir.path || f.path.startsWith(dir.path + File.separator)

    private fun walkFiles(dir: File): Sequence<File> =
        dir.walkTopDown().onEnter { !isTemp(it) }.filter { it.isFile && !isTemp(it) }

    private suspend fun libraryRoot(app: Context): File {
        val s = AppDatabase.getInstance(app).settingsDao().getSettingsSnapshot()
        return StorageManager.getRootDirectory(app, s?.customStoragePath.orEmpty(), s?.storageLocationType ?: "DOCUMENTS").canonicalFile
    }

    private suspend fun plan(app: Context): Plan {
        val db = AppDatabase.getInstance(app)
        val documents = db.documentDao().getAllDocumentsSnapshot()
        val root = libraryRoot(app)
        val filesDir = app.filesDir.canonicalFile
        val items = mutableListOf<Item>()
        val seen = HashSet<String>()
        fun add(entry: String, f: File) {
            if (f.isFile && seen.add(f.canonicalPath)) items += Item(entry, f)
        }

        // Database and settings
        val dbFile = app.getDatabasePath(DB_NAME)
        add("db/$DB_NAME", dbFile)
        add("db/$DB_NAME-wal", File(dbFile.path + "-wal"))
        File(app.applicationInfo.dataDir, "shared_prefs").listFiles()?.filter {
            it.isFile && it.name.endsWith(".xml") && !it.name.startsWith("androidx.work")
        }?.forEach { add("prefs/${it.name}", it) }

        // The library folder (books, notes, recordings, presentations, courses, writing …)
        if (root.isDirectory && !under(root, filesDir)) {
            walkFiles(root).forEach { add("root/${it.relativeTo(root).invariantPath()}", it) }
        }

        // The app's own data folder
        filesDir.listFiles()?.filter { it.name !in SKIP_IN_FILES && !isTemp(it) }?.forEach { top ->
            if (top.isDirectory) walkFiles(top).forEach { add("files/${it.relativeTo(filesDir).invariantPath()}", it) }
            else add("files/${top.name}", top)
        }

        // Files the library points to outside both folders
        val external = JSONArray()
        val externalSeen = HashSet<String>()
        fun addExternal(stored: String, allowDir: Boolean) {
            val path = stored.removePrefix("file://")
            if (!path.startsWith("/")) return
            val f = runCatching { File(path).canonicalFile }.getOrNull() ?: return
            if (!f.exists() || under(f, root) || under(f, filesDir)) return
            if (f.isDirectory && !allowDir) return
            if (!externalSeen.add(f.path)) return
            val base = "ext/${external.length()}/${f.name}"
            if (f.isDirectory) walkFiles(f).forEach { add("$base/${it.relativeTo(f).invariantPath()}", it) }
            else add(base, f)
            external.put(JSONObject().put("path", path).put("entry", base).put("dir", f.isDirectory))
        }
        documents.forEach { d ->
            addExternal(d.filePath, allowDir = true) // DAISY books are folders
            d.coverUri?.let { addExternal(it, allowDir = false) }
        }
        // Paths stored in the app's own JSON files (audiobook files, attached files …)
        filesDir.listFiles()?.filter { it.name !in SKIP_IN_FILES }?.forEach { top ->
            walkFiles(top).filter { it.extension.equals("json", true) && it.length() < 20L shl 20 }.forEach { jf ->
                runCatching { collectPaths(jf.readText()) }.getOrNull()?.forEach { addExternal(it, allowDir = false) }
            }
        }

        val books = documents.count { File(it.filePath.removePrefix("file://")).exists() }
        val audiobooks = File(filesDir, "audiobooks").listFiles()?.count { it.isDirectory && !isTemp(it) } ?: 0
        val counts = JSONObject()
            .put("books", documents.size)
            .put("bookFiles", books)
            .put("audiobooks", audiobooks)
            .put("files", items.size)
        return Plan(items, external, root, books, counts)
    }

    private fun File.invariantPath() = path.replace(File.separatorChar, '/')

    /** Every string in a JSON document that looks like an absolute file path. */
    private fun collectPaths(text: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: Any?) {
            when (v) {
                is JSONObject -> v.keys().forEach { walk(v.opt(it)) }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
                is String -> if ((v.startsWith("/") || v.startsWith("file:///")) && v.length < 1024 && '\n' !in v) out += v
            }
        }
        val t = text.trimStart()
        walk(if (t.startsWith("[")) JSONArray(t) else JSONObject(t))
        return out
    }

    // ------------------------------------------------------------------ write

    /** Writes a complete backup to [out]. Returns the number of book files included. */
    suspend fun writeBackup(
        context: Context,
        out: OutputStream,
        options: Options = Options(),
        onProgress: (Progress) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val db = AppDatabase.getInstance(app)
        // Flush the write-ahead log so the main database file is complete.
        runCatching { db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() } }
        val dbVersion = runCatching { db.openHelper.readableDatabase.version }.getOrDefault(0)

        onProgress(Progress("Collecting files", 0, 0))
        val plan = plan(app)
        val total = plan.totalBytes
        val throttle = Throttle(onProgress)
        var done = 0L
        val files = JSONArray()
        val buffer = ByteArray(1 shl 16)

        val sink: OutputStream = if (options.encrypted) BackupCrypto.EncryptingOutputStream(out, options.password!!) else out
        val secrets = if (options.encrypted && options.includeKeys) AiPreferences(app).exportSecrets() else null
        ZipOutputStream(sink.buffered(1 shl 16)).use { zip ->
            if (secrets != null && secrets.length() > 0) {
                val bytes = secrets.toString().toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry("settings/secrets.json"))
                zip.write(bytes)
                zip.closeEntry()
                files.put(JSONObject().put("entry", "settings/secrets.json").put("bytes", bytes.size)
                    .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).hex()))
            }
            for (item in plan.items) {
                coroutineContext.ensureActive()
                val f = item.file
                if (!f.isFile) continue // removed while backing up
                zip.setLevel(if (f.extension.lowercase(Locale.ROOT) in STORE_EXT) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
                zip.putNextEntry(ZipEntry(item.entry).apply { time = f.lastModified() })
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                f.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        zip.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        bytes += n
                        done += n
                        throttle.emit(Progress("Backing up", done, total, item.entry))
                    }
                }
                zip.closeEntry()
                files.put(JSONObject().put("entry", item.entry).put("bytes", bytes).put("sha256", digest.digest().hex()))
            }

            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("created", System.currentTimeMillis())
                .put("app", app.packageName)
                .put("appVersion", appVersion(app))
                .put("dbVersion", dbVersion)
                .put("encrypted", options.encrypted)
                .put("includesKeys", secrets != null && secrets.length() > 0)
                .put("paths", JSONObject()
                    .put("root", plan.root.path)
                    .put("files", app.filesDir.canonicalPath)
                    .put("external", app.getExternalFilesDir(null)?.canonicalPath.orEmpty()))
                .put("counts", plan.counts)
                .put("totalBytes", done)
                .put("external", plan.external)
                .put("files", files)
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(1).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        onProgress(Progress("Done", total, total))
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("lastBackup", System.currentTimeMillis())
            .putLong("lastBackupBytes", done)
            .putString("lastSignature", signature(app))
            .apply()
        plan.books
    }

    suspend fun backupToUri(context: Context, uri: Uri, options: Options = Options(), onProgress: (Progress) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the backup destination.")
        try {
            out.use { writeBackup(context, it, options, onProgress) }
        } catch (t: Throwable) {
            // Don't leave a half-written backup behind that looks like a good one.
            runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw t
        }
    }

    fun lastBackupTime(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("lastBackup", 0L)

    fun lastBackupBytes(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("lastBackupBytes", 0L)

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    /** Cheap fingerprint of everything a backup contains, to skip unchanged daily backups. */
    private suspend fun signature(context: Context): String {
        val app = context.applicationContext
        val dbFile = app.getDatabasePath(DB_NAME)
        var count = 0L; var size = 0L; var newest = 0L
        fun visit(f: File) { count++; size += f.length(); newest = maxOf(newest, f.lastModified()) }
        listOf(dbFile, File(dbFile.path + "-wal")).filter { it.isFile }.forEach(::visit)
        File(app.applicationInfo.dataDir, "shared_prefs").listFiles()?.filter { it.isFile && it.name !in DEVICE_PREFS }?.forEach(::visit)
        app.filesDir.listFiles()?.filter { it.name !in SKIP_IN_FILES && !isTemp(it) }?.forEach { top ->
            if (top.isDirectory) walkFiles(top).forEach(::visit) else visit(top)
        }
        val root = libraryRoot(app)
        if (root.isDirectory && !under(root, app.filesDir.canonicalFile)) walkFiles(root).forEach(::visit)
        return "$FORMAT:$count:$size:$newest"
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
        // Not backupToUri: a failed automatic run must not delete the previous good backup file.
        withContext(Dispatchers.IO) {
            val out = context.contentResolver.openOutputStream(target, "wt") ?: error("Could not open the backup destination.")
            out.use { writeBackup(context, it) }
        }
        return true
    }

    // ------------------------------------------------------------------ restore

    /** Size of the file behind [uri], or -1 when the provider doesn't say. */
    fun sizeOf(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    /** True when the backup behind [uri] is password-encrypted. */
    suspend fun isEncrypted(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { isEncrypted(it) } ?: false
    }

    suspend fun isEncrypted(file: File): Boolean = withContext(Dispatchers.IO) { file.inputStream().use { isEncrypted(it) } }

    private fun isEncrypted(input: InputStream): Boolean {
        val first = ByteArray(4)
        var n = 0
        while (n < 4) { val r = input.read(first, n, 4 - n); if (r < 0) break; n += r }
        return BackupCrypto.isEncrypted(first.copyOf(n))
    }

    suspend fun restoreFromUri(
        context: Context, uri: Uri, password: CharArray? = null, restoreKeys: Boolean = true,
        mode: RestoreMode = RestoreMode.REPLACE, onProgress: (Progress) -> Unit = {}
    ): String {
        val size = sizeOf(context, uri)
        val input = context.contentResolver.openInputStream(uri) ?: error("Could not open the backup file.")
        return input.use { restore(context, it, size, password, restoreKeys, mode, onProgress) }
    }

    /**
     * Restores a backup: over the current library ([RestoreMode.REPLACE]) or combined with it
     * ([RestoreMode.MERGE]). Returns a line describing what happened. Call [restartApp] afterwards
     * so every screen and setting is reloaded.
     */
    suspend fun restore(
        context: Context,
        input: InputStream,
        sizeHint: Long = -1L,
        password: CharArray? = null,
        restoreKeys: Boolean = true,
        mode: RestoreMode = RestoreMode.REPLACE,
        onProgress: (Progress) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val dataDir = File(app.applicationInfo.dataDir)
        // Staging lives next to filesDir (same disk), so swapping in is a rename, not a copy.
        val staging = File(dataDir, "restore_staging").apply { deleteRecursively(); mkdirs() }
        File(app.cacheDir, "restore").deleteRecursively() // left over by older versions
        try {
            if (sizeHint > 0) {
                val free = StatFs(staging.path).availableBytes
                val need = sizeHint + sizeHint / 10 + (200L shl 20)
                require(free >= need) {
                    "Not enough free space on the tablet: the restore needs about ${gb(need)}, but only ${gb(free)} is free."
                }
            }
            val throttle = Throttle(onProgress)

            // 1. Unpack into staging, hashing every file and validating entry paths.
            val hashes = HashMap<String, String>()
            val counting = CountingInputStream(input.buffered(1 shl 16))
            counting.mark(8)
            val encrypted = isEncrypted(counting)
            counting.reset()
            val plain: InputStream = if (encrypted) {
                if (password == null || password.isEmpty()) throw PasswordRequiredException()
                onProgress(Progress("Checking the password", 0, 0))
                BackupCrypto.DecryptingInputStream(counting, password).buffered(1 shl 16)
            } else counting
            ZipInputStream(plain).use { zip ->
                val buffer = ByteArray(1 shl 16)
                var e = zip.nextEntry
                while (e != null) {
                    coroutineContext.ensureActive()
                    val target = File(staging, e.name).canonicalFile
                    require(target.path.startsWith(staging.canonicalPath + File.separator)) { "Invalid backup file." }
                    if (e.isDirectory) target.mkdirs() else {
                        target.parentFile?.mkdirs()
                        val digest = MessageDigest.getInstance("SHA-256")
                        target.outputStream().use { out ->
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                throttle.emit(Progress("Unpacking", counting.count, sizeHint, e.name))
                            }
                        }
                        hashes[e.name] = digest.digest().hex()
                        if (e.time > 0) target.setLastModified(e.time)
                    }
                    e = zip.nextEntry
                }
            }

            // 2. Check it is a complete backup this version of the app can read.
            onProgress(Progress("Checking", 0, 0))
            val manifestFile = File(staging, "manifest.json")
            require(manifestFile.isFile) { "This file isn't a complete Marginalia Lectoris backup (it may have been cut off)." }
            val manifest = JSONObject(manifestFile.readText())
            val format = manifest.optInt("format", 1)
            require(format <= FORMAT) { "This backup was made by a newer version of the app. Update the app first, then restore." }
            val stagedDb = File(staging, "db/$DB_NAME")
            require(stagedDb.isFile) { "The backup contains no library database." }

            manifest.optJSONArray("files")?.let { list ->
                val bad = mutableListOf<String>()
                for (i in 0 until list.length()) {
                    val f = list.getJSONObject(i)
                    val entry = f.getString("entry")
                    if (hashes[entry] != f.optString("sha256")) bad += entry
                }
                require(bad.isEmpty()) {
                    "The backup is damaged: ${bad.size} file(s) don't match their checksum (first: ${bad.first().substringAfterLast('/')}). Nothing was changed."
                }
            }

            val currentVersion = runCatching { AppDatabase.getInstance(app).openHelper.readableDatabase.version }.getOrDefault(0)
            val backupVersion = SQLiteDatabase.openDatabase(stagedDb.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version }
            require(currentVersion == 0 || backupVersion <= currentVersion) {
                "This backup comes from a newer version of the app (library version $backupVersion, this app has $currentVersion). Update the app first, then restore."
            }

            val merge = mode == RestoreMode.MERGE
            require(!merge || format >= 2) { "This backup was made by an older version of the app and can only be restored with Replace." }
            val report = MergeReport()

            // 3. Place library files and fix stored paths that point somewhere else on this tablet.
            if (format >= 2) placeLibraryFiles(app, staging, manifest, stagedDb, throttle, if (merge) report else null)
            else placeLegacyBooks(app, staging, manifest, stagedDb)

            // 4. Replace: swap database, settings and the app's data folder in, with rollback on failure.
            //    Merge: combine the databases in one transaction, then the data folder file by file.
            onProgress(Progress(if (merge) "Merging" else "Installing", 0, 0))
            if (merge) mergeInstall(app, staging, report) else install(app, staging, replaceAll = format >= 2)
            if (restoreKeys) File(staging, "settings/secrets.json").takeIf { it.isFile }?.let { f ->
                runCatching {
                    val keys = JSONObject(f.readText())
                    if (merge) { // keep keys already on this tablet
                        val here = AiPreferences(app).exportSecrets()
                        here.keys().forEach { keys.remove(it) }
                    }
                    AiPreferences(app).importSecrets(keys)
                }.onFailure { Log.w(TAG, "Could not restore the API keys", it) }
            }
            onProgress(Progress("Done", 1, 1))
            if (merge) report.summary() else "Restored."
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Format 2: library folder, external files and path fixes. */
    private suspend fun placeLibraryFiles(app: Context, staging: File, manifest: JSONObject, stagedDb: File, throttle: Throttle, merge: MergeReport?) {
        val paths = manifest.optJSONObject("paths") ?: JSONObject()

        // Where the library folder goes on this tablet: the same custom folder if it can be used here,
        // otherwise the app's default folder.
        var custom = ""; var type = "DOCUMENTS"
        if (merge == null) SQLiteDatabase.openDatabase(stagedDb.path, null, SQLiteDatabase.OPEN_READWRITE).use { sdb ->
            runCatching {
                sdb.rawQuery("SELECT customStoragePath, storageLocationType FROM app_settings LIMIT 1", null).use { c ->
                    if (c.moveToFirst()) { custom = c.getString(0).orEmpty(); type = c.getString(1) ?: "DOCUMENTS" }
                }
            }
            if (custom.isNotBlank()) {
                val dir = File(custom)
                val usable = (dir.isDirectory || dir.mkdirs()) && dir.canWrite()
                if (!usable) {
                    sdb.execSQL("UPDATE app_settings SET customStoragePath = ''")
                    custom = ""
                }
            }
        }
        // Merging keeps this tablet's settings, and so its library folder.
        val newRoot = if (merge != null) libraryRoot(app) else StorageManager.getRootDirectory(app, custom, type).canonicalFile
        val mappings = mutableListOf<Pair<String, String>>()
        paths.optString("root").takeIf { it.isNotBlank() }?.let { mappings += it to newRoot.path }
        paths.optString("files").takeIf { it.isNotBlank() }?.let { mappings += it to app.filesDir.canonicalPath }
        paths.optString("external").takeIf { it.isNotBlank() }?.let { old ->
            app.getExternalFilesDir(null)?.canonicalPath?.let { mappings += old to it }
        }

        // Library folder: files from the backup are written over what is there. Other files in the
        // folder (it may be inside an Obsidian vault) are left alone.
        val stagedRoot = File(staging, "root")
        val placedJson = mutableListOf<File>()
        if (stagedRoot.isDirectory) {
            val all = walkFiles(stagedRoot).toList()
            val total = all.sumOf { it.length() }
            var done = 0L
            all.forEach { f ->
                coroutineContext.ensureActive()
                val target = File(newRoot, f.relativeTo(stagedRoot).path)
                done += f.length()
                val mtime = f.lastModified()
                val existed = target.isFile
                if (merge != null && existed && target.lastModified() >= mtime) {
                    merge.skippedOlder++; f.delete()
                } else {
                    moveFile(f, target)
                    if (mtime > 0) target.setLastModified(mtime)
                    if (merge != null) { if (existed) merge.filesUpdated++ else merge.filesAdded++ }
                    if (target.extension.equals("json", true)) placedJson += target
                }
                throttle.emit(Progress("Restoring library folder", done, total, target.name))
            }
        }

        // Files that lived outside the library folder: back to the same place if possible.
        val external = manifest.optJSONArray("external") ?: JSONArray()
        for (i in 0 until external.length()) {
            val x = external.getJSONObject(i)
            val staged = File(staging, x.getString("entry"))
            if (!staged.exists()) continue
            val original = File(x.getString("path"))
            if (merge != null && original.exists()) continue // already here
            val fallback = File(newRoot, "Restored files/$i/${original.name}")
            val placed = listOf(original, fallback).firstOrNull { dest ->
                runCatching { dest.parentFile?.mkdirs(); moveFile(staged, dest); true }.getOrDefault(false)
            } ?: error("Could not restore ${original.name}.")
            if (placed.path != original.path) mappings.add(0, original.path to placed.path)
        }

        val effective = mappings.filter { (old, new) -> old != new }
        if (effective.isEmpty()) return
        rewriteDatabasePaths(stagedDb, effective)
        val stagedFiles = File(staging, "files")
        val jsonFiles = (if (stagedFiles.isDirectory) walkFiles(stagedFiles).filter { it.extension.equals("json", true) }.toList() else emptyList()) + placedJson
        jsonFiles.filter { it.length() < 20L shl 20 }.forEach { f ->
            runCatching {
                val text = f.readText()
                val fixed = rewriteText(text, effective)
                if (fixed != text) f.writeText(fixed)
            }
        }
    }

    /** Format 1 (older backups): books and covers back to their original location if possible. */
    private fun placeLegacyBooks(app: Context, staging: File, manifest: JSONObject, stagedDb: File) {
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
                    val ok = runCatching { dest.parentFile?.mkdirs(); moveFile(staged, dest); true }.getOrDefault(false)
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
        if (pathFixes.isNotEmpty() || coverFixes.isNotEmpty()) {
            SQLiteDatabase.openDatabase(stagedDb.path, null, SQLiteDatabase.OPEN_READWRITE).use { sdb ->
                pathFixes.forEach { (id, p) -> sdb.execSQL("UPDATE documents SET filePath = ? WHERE id = ?", arrayOf(p, id)) }
                coverFixes.forEach { (id, p) -> sdb.execSQL("UPDATE documents SET coverUri = ? WHERE id = ?", arrayOf(p, id)) }
            }
        }
    }

    /**
     * Moves the staged database, settings and data folder into place. Everything replaced is first
     * moved aside, so any failure puts the library back exactly as it was.
     */
    private fun install(app: Context, staging: File, replaceAll: Boolean) {
        val dataDir = File(app.applicationInfo.dataDir)
        val previous = File(dataDir, "restore_previous").apply { deleteRecursively(); mkdirs() }
        val stashed = mutableListOf<Pair<File, File>>() // original location → where it was moved
        val installed = mutableListOf<File>()
        fun stash(f: File) {
            if (!f.exists()) return
            val dest = File(previous, "${stashed.size}_${f.name}")
            check(f.renameTo(dest)) { "Could not move ${f.name} aside." }
            stashed += f to dest
        }
        fun put(staged: File, target: File) {
            stash(target)
            target.parentFile?.mkdirs()
            moveFile(staged, target)
            installed += target
        }

        val filesDir = app.filesDir
        val prefsDir = File(dataDir, "shared_prefs").apply { mkdirs() }
        val dbFile = app.getDatabasePath(DB_NAME)
        val stagedFiles = File(staging, "files")
        val stagedPrefs = File(staging, "prefs")
        val stagedDb = File(staging, "db")
        AppDatabase.closeForRestore()
        try {
            // App data folder (downloaded models and caches stay as they are)
            val incoming = stagedFiles.listFiles()?.toList().orEmpty()
            if (replaceAll) {
                val names = incoming.map { it.name }.toSet()
                filesDir.listFiles()?.filter { it.name !in SKIP_IN_FILES && it.name !in names }?.forEach(::stash)
            }
            incoming.filter { it.name !in SKIP_IN_FILES }.forEach { put(it, File(filesDir, it.name)) }

            // Settings
            val incomingPrefs = stagedPrefs.listFiles()?.filter { it.isFile && it.name !in DEVICE_PREFS }.orEmpty()
            if (replaceAll) {
                val names = incomingPrefs.map { it.name }.toSet()
                prefsDir.listFiles()?.filter {
                    it.isFile && it.name !in names && it.name !in DEVICE_PREFS && !it.name.startsWith("androidx.work")
                }?.forEach(::stash)
            }
            incomingPrefs.forEach { put(it, File(prefsDir, it.name)) }

            // Database
            listOf("", "-wal", "-shm", "-journal").forEach { stash(File(dbFile.path + it)) }
            put(File(stagedDb, DB_NAME), dbFile)
            File(stagedDb, "$DB_NAME-wal").takeIf { it.isFile }?.let { put(it, File(dbFile.path + "-wal")) }
        } catch (t: Throwable) {
            Log.w(TAG, "Restore failed; rolling back", t)
            installed.asReversed().forEach { it.deleteRecursively() }
            stashed.asReversed().forEach { (original, moved) -> original.parentFile?.mkdirs(); moved.renameTo(original) }
            previous.deleteRecursively()
            throw IllegalStateException("Restore failed and your library was left as it was: ${t.message}", t)
        }
        previous.deleteRecursively()
        com.example.reader.Lexicon.reload()
    }

    // ------------------------------------------------------------------ merge

    /** Per-book folders in the app's data folder, named after the book's database id. */
    private val PER_BOOK_DIRS = setOf("audiobooks", "folder_sync")

    private fun columns(db: SQLiteDatabase, table: String): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c ->
                val i = c.getColumnIndex("name")
                while (c.moveToNext()) out += c.getString(i)
            }
        }
        return out
    }

    private fun rows(db: SQLiteDatabase, table: String): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        db.rawQuery("SELECT * FROM \"$table\"", null).use { c ->
            while (c.moveToNext()) {
                val row = HashMap<String, Any?>()
                for (i in 0 until c.columnCount) {
                    row[c.getColumnName(i)] = when (c.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_STRING -> c.getString(i)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        else -> null
                    }
                }
                out += row
            }
        }
        return out
    }

    private fun ContentValues.putAny(k: String, v: Any?) {
        when (v) {
            null -> putNull(k)
            is Long -> put(k, v)
            is Double -> put(k, v)
            is String -> put(k, v)
            is ByteArray -> put(k, v)
            else -> put(k, v.toString())
        }
    }

    private fun Map<String, Any?>.long(k: String): Long = (this[k] as? Number)?.toLong() ?: 0L
    private fun Map<String, Any?>.str(k: String): String = this[k]?.toString().orEmpty()

    private fun docUpdated(r: Map<String, Any?>) = r.long("updatedAt").takeIf { it > 0 } ?: maxOf(r.long("dateAdded"), r.long("lastReadTimestamp"))
    private fun hlUpdated(r: Map<String, Any?>) = r.long("updatedAt").takeIf { it > 0 } ?: r.long("createdTimestamp")

    /** The same book on two devices that never shared IDs (e.g. backups from before merging existed). */
    private fun docKey(r: Map<String, Any?>) = listOf("title", "author", "fileType", "category")
        .joinToString("|") { r.str(it).trim().lowercase(Locale.ROOT) }

    private fun hlKey(docId: Long, r: Map<String, Any?>) =
        "$docId|${r.str("highlightedText").trim().take(500)}|${r.str("epubCfi")}|${r.long("pageNumber")}"

    private fun tombstones(db: SQLiteDatabase): MutableMap<String, Long> {
        val out = HashMap<String, Long>()
        runCatching {
            db.rawQuery("SELECT kind, uuid, deletedAt FROM tombstones", null).use { c ->
                while (c.moveToNext()) out["${c.getString(0)}:${c.getString(1)}"] = c.getLong(2)
            }
        }
        return out
    }

    /**
     * Merges the staged backup into the library here. Books and highlights are matched by their
     * permanent ID (or, for items from before IDs existed, by title/author and by quote + position);
     * where both sides have an item, the one changed most recently wins. Deletions recorded on either
     * side are respected. Settings stay as they are on this tablet.
     */
    private fun mergeInstall(app: Context, staging: File, report: MergeReport) {
        val stagedDbFile = File(staging, "db/$DB_NAME")
        // Make the live database one complete file, then work on it directly.
        runCatching { AppDatabase.getInstance(app).openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } }
        AppDatabase.closeForRestore()
        val docIdMap = HashMap<Long, Long>() // backup id → id here (-1: deleted here, not brought back)

        SQLiteDatabase.openDatabase(stagedDbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { b ->
            SQLiteDatabase.openDatabase(app.getDatabasePath(DB_NAME).path, null, SQLiteDatabase.OPEN_READWRITE).use { live ->
                live.setForeignKeyConstraintsEnabled(true) // deleting a book removes its highlights
                SyncTriggers.drop(live::execSQL)
                live.beginTransaction()
                try {
                    mergeTables(live, b, docIdMap, report)
                    live.setTransactionSuccessful()
                } finally {
                    live.endTransaction()
                    SyncTriggers.create(live::execSQL)
                }
            }
        }

        // The app's data folder, file by file: newer files from the backup replace older ones here.
        val stagedFiles = File(staging, "files")
        val filesDir = app.filesDir
        stagedFiles.listFiles()?.filter { it.name !in SKIP_IN_FILES }?.forEach { top ->
            val files = if (top.isDirectory) walkFiles(top).toList() else listOf(top)
            files.forEach { f ->
                var rel = f.relativeTo(stagedFiles).invariantPath()
                if (top.name in PER_BOOK_DIRS) {
                    val parts = rel.split('/')
                    val id = parts.getOrNull(1)?.toLongOrNull()
                    if (id != null) {
                        val mapped = docIdMap[id] ?: id
                        if (mapped < 0) return@forEach
                        rel = (listOf(parts[0], mapped.toString()) + parts.drop(2)).joinToString("/")
                    }
                }
                val target = File(filesDir, rel)
                val mtime = f.lastModified()
                if (target.isFile && target.lastModified() >= mtime) { report.skippedOlder++; return@forEach }
                val existed = target.isFile
                moveFile(f, target)
                if (mtime > 0) target.setLastModified(mtime)
                if (existed) report.filesUpdated++ else report.filesAdded++
            }
        }
        com.example.reader.Lexicon.reload()
    }

    private fun mergeTables(live: SQLiteDatabase, b: SQLiteDatabase, docIdMap: HashMap<Long, Long>, report: MergeReport) {
        val liveTombs = tombstones(live)
        val backupTombs = tombstones(b)

        // ---- documents
        val docCols = columns(live, "documents").toSet()
        val bDocCols = columns(b, "documents").filter { it in docCols }
        val liveDocs = rows(live, "documents")
        val liveByUuid = liveDocs.filter { it.str("uuid").isNotBlank() }.associateBy { it.str("uuid") }
        val liveByKey = liveDocs.groupBy { docKey(it) }.mapValues { it.value.first() }
        val liveIds = liveDocs.map { it.long("id") }.toMutableSet()
        val matchedLive = HashSet<Long>()

        for (r in rows(b, "documents")) {
            val bid = r.long("id")
            val uuid = r.str("uuid")
            val upd = docUpdated(r)
            val match = (if (uuid.isNotBlank()) liveByUuid[uuid] else null) ?: liveByKey[docKey(r)]?.takeIf { it.long("id") !in matchedLive }
            if (match != null) {
                val id = match.long("id")
                matchedLive += id
                docIdMap[bid] = id
                if (upd > docUpdated(match)) {
                    val cv = ContentValues()
                    bDocCols.filter { it != "id" && it != "uuid" }.forEach { cv.putAny(it, r[it]) }
                    // Keep this tablet's file locations when the files are here.
                    if (File(match.str("filePath").removePrefix("file://")).exists()) cv.putAny("filePath", match["filePath"])
                    if (match.str("coverUri").isNotBlank() && File(match.str("coverUri").removePrefix("file://")).exists()) cv.putAny("coverUri", match["coverUri"])
                    if ("updatedAt" in docCols) cv.put("updatedAt", upd)
                    live.update("documents", cv, "id = ?", arrayOf(id.toString()))
                    report.booksUpdated++
                } else if (upd < docUpdated(match)) report.skippedOlder++
                continue
            }
            val deletedHere = if (uuid.isNotBlank()) liveTombs["document:$uuid"] else null
            if (deletedHere != null && deletedHere >= upd) { docIdMap[bid] = -1; report.skippedOlder++; continue }
            val cv = ContentValues()
            bDocCols.filter { it != "id" }.forEach { cv.putAny(it, r[it]) }
            if (bid > 0 && bid !in liveIds) cv.put("id", bid) // keeps per-book folders and notes lined up
            if ("uuid" in docCols && uuid.isBlank()) cv.put("uuid", java.util.UUID.randomUUID().toString().replace("-", ""))
            if ("updatedAt" in docCols) cv.put("updatedAt", upd)
            val newId = live.insertOrThrow("documents", null, cv)
            liveIds += newId
            docIdMap[bid] = newId
            if (deletedHere != null) live.delete("tombstones", "kind = 'document' AND uuid = ?", arrayOf(uuid))
            report.booksAdded++
        }
        // Books deleted on the other device after they were last changed here
        for (d in liveDocs) {
            val uuid = d.str("uuid")
            val deleted = backupTombs["document:$uuid"] ?: continue
            if (uuid.isNotBlank() && deleted > docUpdated(d)) {
                live.delete("documents", "id = ?", arrayOf(d.long("id").toString()))
                report.booksRemoved++
            }
        }

        // ---- highlights
        val hlCols = columns(live, "highlights").toSet()
        val bHlCols = columns(b, "highlights").filter { it in hlCols }
        val liveHls = rows(live, "highlights")
        val hlByUuid = liveHls.filter { it.str("uuid").isNotBlank() }.associateBy { it.str("uuid") }
        val hlByKey = liveHls.associateBy { hlKey(it.long("documentId"), it) }
        val hlIds = liveHls.map { it.long("id") }.toMutableSet()
        val liveDocIds = rows(live, "documents").map { it.long("id") }.toSet()

        for (r in rows(b, "highlights")) {
            val docId = docIdMap[r.long("documentId")] ?: continue
            if (docId < 0 || docId !in liveDocIds) continue
            val uuid = r.str("uuid")
            val upd = hlUpdated(r)
            val match = (if (uuid.isNotBlank()) hlByUuid[uuid] else null) ?: hlByKey[hlKey(docId, r)]
            if (match != null) {
                if (upd > hlUpdated(match)) {
                    val cv = ContentValues()
                    bHlCols.filter { it != "id" && it != "uuid" }.forEach { cv.putAny(it, r[it]) }
                    cv.put("documentId", docId)
                    if ("updatedAt" in hlCols) cv.put("updatedAt", upd)
                    live.update("highlights", cv, "id = ?", arrayOf(match.long("id").toString()))
                    report.highlightsUpdated++
                } else if (upd < hlUpdated(match)) report.skippedOlder++
                continue
            }
            val deletedHere = if (uuid.isNotBlank()) liveTombs["highlight:$uuid"] else null
            if (deletedHere != null && deletedHere >= upd) { report.skippedOlder++; continue }
            val cv = ContentValues()
            bHlCols.filter { it != "id" }.forEach { cv.putAny(it, r[it]) }
            val bid = r.long("id")
            if (bid > 0 && bid !in hlIds) cv.put("id", bid)
            cv.put("documentId", docId)
            if ("uuid" in hlCols && uuid.isBlank()) cv.put("uuid", java.util.UUID.randomUUID().toString().replace("-", ""))
            if ("updatedAt" in hlCols) cv.put("updatedAt", upd)
            hlIds += live.insertOrThrow("highlights", null, cv)
            if (deletedHere != null) live.delete("tombstones", "kind = 'highlight' AND uuid = ?", arrayOf(uuid))
            report.highlightsAdded++
        }
        for (h in liveHls) {
            val uuid = h.str("uuid")
            val deleted = backupTombs["highlight:$uuid"] ?: continue
            if (uuid.isNotBlank() && deleted > hlUpdated(h)) {
                if (live.delete("highlights", "id = ?", arrayOf(h.long("id").toString())) > 0) report.highlightsRemoved++
            }
        }

        // ---- deletion records: keep the most recent of both sides
        if (columns(live, "tombstones").isNotEmpty()) {
            backupTombs.forEach { (key, at) ->
                val kind = key.substringBefore(':'); val uuid = key.substringAfter(':')
                val here = liveTombs[key]
                val stillHere = when (kind) {
                    "document" -> live.rawQuery("SELECT 1 FROM documents WHERE uuid = ? LIMIT 1", arrayOf(uuid)).use { it.moveToFirst() }
                    else -> live.rawQuery("SELECT 1 FROM highlights WHERE uuid = ? LIMIT 1", arrayOf(uuid)).use { it.moveToFirst() }
                }
                if (!stillHere && (here == null || at > here)) {
                    live.execSQL("INSERT OR REPLACE INTO tombstones (kind, uuid, deletedAt) VALUES (?, ?, ?)", arrayOf(kind, uuid, at))
                }
            }
        }
    }

    // ------------------------------------------------------------------ path rewriting

    /** The forms an absolute path appears in: plain, followed by a separator or quote, and JSON-escaped. */
    private fun variants(old: String, new: String): List<Pair<String, String>> {
        val eo = old.replace("/", "\\/"); val en = new.replace("/", "\\/")
        return listOf("$old/" to "$new/", "$old\"" to "$new\"", "$eo\\/" to "$en\\/", "$eo\"" to "$en\"")
    }

    private fun rewriteText(text: String, mappings: List<Pair<String, String>>): String {
        var t = text
        mappings.forEach { (old, new) -> variants(old, new).forEach { (a, b) -> t = t.replace(a, b) } }
        return t
    }

    /** Replaces old path prefixes in every text column of every ordinary table in the staged database. */
    private fun rewriteDatabasePaths(dbFile: File, mappings: List<Pair<String, String>>) {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { sdb ->
            // Path fixes are not edits: keep the change times (the triggers come back when the app opens it).
            SyncTriggers.drop(sdb::execSQL)
            val tables = mutableListOf<Pair<String, String>>()
            sdb.rawQuery("SELECT name, sql FROM sqlite_master WHERE type = 'table'", null).use { c ->
                while (c.moveToNext()) tables += c.getString(0) to (c.getString(1) ?: "")
            }
            val virtual = tables.filter { it.second.trimStart().startsWith("CREATE VIRTUAL", true) }.map { it.first }
            val ordinary = tables.map { it.first }.filter { name ->
                !name.startsWith("sqlite_") && name != "android_metadata" && name != "room_master_table" &&
                    name !in virtual && virtual.none { name.startsWith(it + "_") }
            }
            sdb.beginTransaction()
            try {
                ordinary.forEach { table ->
                    val q = "\"" + table.replace("\"", "\"\"") + "\""
                    val textCols = mutableListOf<String>()
                    sdb.rawQuery("PRAGMA table_info($q)", null).use { c ->
                        val nameIdx = c.getColumnIndex("name"); val typeIdx = c.getColumnIndex("type")
                        while (c.moveToNext()) {
                            val type = c.getString(typeIdx).orEmpty().uppercase(Locale.ROOT)
                            if (type.isEmpty() || "TEXT" in type || "CHAR" in type || "CLOB" in type) textCols += c.getString(nameIdx)
                        }
                    }
                    textCols.forEach { col ->
                        val qc = "\"" + col.replace("\"", "\"\"") + "\""
                        mappings.forEach { (old, new) ->
                            // Exact matches (with or without file://), then occurrences inside longer text.
                            sdb.execSQL("UPDATE $q SET $qc = ? WHERE $qc = ?", arrayOf(new, old))
                            sdb.execSQL("UPDATE $q SET $qc = ? WHERE $qc = ?", arrayOf("file://$new", "file://$old"))
                            variants(old, new).forEach { (a, b) ->
                                sdb.execSQL("UPDATE $q SET $qc = REPLACE($qc, ?, ?) WHERE instr($qc, ?) > 0", arrayOf(a, b, a))
                            }
                        }
                    }
                }
                sdb.setTransactionSuccessful()
            } finally {
                sdb.endTransaction()
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Moves a file or folder, renaming when on the same disk and copying otherwise. */
    private fun moveFile(src: File, dest: File) {
        dest.parentFile?.mkdirs()
        if (dest.exists() && dest.isFile && src.isFile) dest.delete()
        if (src.renameTo(dest)) return
        if (src.isDirectory) {
            check(src.copyRecursively(dest, overwrite = true)) { "Could not copy ${src.name}." }
            src.deleteRecursively()
        } else {
            val tmp = File(dest.parentFile, ".${dest.name}.restoring")
            src.copyTo(tmp, overwrite = true)
            dest.delete()
            check(tmp.renameTo(dest)) { "Could not write ${dest.name}." }
            src.delete()
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun gb(bytes: Long): String = if (bytes >= 1L shl 30) "%.1f GB".format(Locale.ROOT, bytes / (1L shl 30).toDouble())
        else "%.0f MB".format(Locale.ROOT, bytes / (1L shl 20).toDouble())

    /** Passes progress on at most a few times a second. */
    private class Throttle(private val sink: (Progress) -> Unit) {
        private var last = 0L
        fun emit(p: Progress) {
            val now = System.currentTimeMillis()
            if (now - last >= 250) { last = now; sink(p) }
        }
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L; private set
        private var marked = 0L
        override fun mark(readlimit: Int) { super.mark(readlimit); marked = count }
        override fun reset() { super.reset(); count = marked }
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
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
