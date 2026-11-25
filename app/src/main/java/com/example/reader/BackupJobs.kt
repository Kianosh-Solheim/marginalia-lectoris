package com.example.reader

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Runs backups and restores outside any screen, so leaving Settings doesn't stop them, and lets
 * the web view start them: a backup streamed to the browser as a download, and a restore from a
 * file uploaded in chunks. A restore asked for from the browser only starts once it is confirmed
 * on the tablet. One job at a time.
 */
object BackupJobs {
    private const val TAG = "BackupJobs"
    const val UPLOAD_CHUNK = 8 shl 20

    data class State(
        val kind: String = "",              // backup | restore | web-backup | web-restore
        val running: Boolean = false,
        val progress: BackupManager.Progress? = null,
        val message: String = "",
        val error: Boolean = false,
        val finishedAt: Long = 0L,
        val restarting: Boolean = false
    )

    data class PendingRestore(val uploadId: String, val name: String, val size: Long, val encrypted: Boolean, val requestedAt: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _pending = MutableStateFlow<PendingRestore?>(null)
    val pendingWebRestore: StateFlow<PendingRestore?> = _pending.asStateFlow()

    @Volatile private var job: Job? = null
    val busy: Boolean get() = job?.isActive == true

    private fun describe(p: BackupManager.Progress): String =
        if (p.total > 0) "${p.stage}… ${(p.fraction * 100).toInt()} % (${bytes(p.done)} of ${bytes(p.total)})" else "${p.stage}…"

    fun bytes(n: Long): String = when {
        n >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", n / (1L shl 30).toDouble())
        n >= 1L shl 20 -> String.format(Locale.ROOT, "%.0f MB", n / (1L shl 20).toDouble())
        else -> String.format(Locale.ROOT, "%.0f KB", n / 1024.0)
    }

    private fun friendly(t: Throwable): String = when {
        t is IOException && (t.message?.contains("Pipe closed") == true || t is StoppedException) -> "The download was stopped in the browser."
        else -> t.message ?: t.javaClass.simpleName
    }

    /** Starts [block] unless something else is running. The block returns the message shown when done. */
    private fun start(kind: String, first: String, block: suspend (onProgress: (BackupManager.Progress) -> Unit) -> String): Boolean {
        synchronized(this) {
            if (busy) return false
            _state.value = State(kind, running = true, message = first)
            job = scope.launch {
                val result = runCatching {
                    block { p -> _state.value = _state.value.copy(progress = p, message = describe(p)) }
                }
                val now = System.currentTimeMillis()
                result.onSuccess { msg -> _state.value = State(kind, running = false, message = msg, finishedAt = now) }
                result.onFailure { t ->
                    Log.w(TAG, "$kind failed", t)
                    val what = if (kind.endsWith("restore")) "Restore failed" else "Backup failed"
                    _state.value = State(kind, running = false, message = "$what: ${friendly(t)}", error = true, finishedAt = now)
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------------ on the tablet

    fun backup(context: Context, uri: Uri, options: BackupManager.Options): Boolean {
        val app = context.applicationContext
        return start("backup", "Backing up… (large libraries can take a few minutes)") { onProgress ->
            try {
                val n = BackupManager.backupToUri(app, uri, options, onProgress)
                "Backup saved: ${bytes(BackupManager.lastBackupBytes(app))}, including $n book files" +
                    (if (options.encrypted) ", encrypted." else ".")
            } finally {
                options.password?.fill('\u0000')
            }
        }
    }

    fun restore(context: Context, uri: Uri, password: CharArray?, restoreKeys: Boolean): Boolean {
        val app = context.applicationContext
        return start("restore", "Restoring…") { onProgress ->
            try {
                BackupManager.restoreFromUri(app, uri, password, restoreKeys, onProgress)
            } finally {
                password?.fill('\u0000')
            }
            restartSoon(app, 400)
            "Restored. Restarting…"
        }
    }

    private fun restartSoon(app: Context, ms: Long) {
        _state.value = _state.value.copy(restarting = true, message = "Restored. Restarting…")
        scope.launch {
            delay(ms)
            withContext(Dispatchers.Main) { BackupManager.restartApp(app) }
        }
    }

    // ------------------------------------------------------------------ download to the browser

    private class Download(val options: BackupManager.Options, val created: Long)
    private val downloads = ConcurrentHashMap<String, Download>()

    private fun token(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Remembers the options for a download the browser fetches next; the token is valid for 5 minutes. */
    fun prepareWebDownload(options: BackupManager.Options): String {
        require(!busy) { "A backup or restore is already running." }
        val now = System.currentTimeMillis()
        downloads.entries.removeIf { now - it.value.created > 5 * 60_000 }
        return token().also { downloads[it] = Download(options, now) }
    }

    /** Starts the backup and returns the stream the HTTP response sends. */
    fun openWebDownload(context: Context, token: String): InputStream {
        val d = downloads.remove(token) ?: throw NoSuchElementException("This download link has expired. Start the backup again.")
        val app = context.applicationContext
        val pipe = Pipe()
        val started = start("web-backup", "Backing up to the browser…") { onProgress ->
            try {
                val n = BackupManager.writeBackup(app, pipe.output, d.options, onProgress)
                pipe.output.close()
                "Backup sent to the browser: ${bytes(BackupManager.lastBackupBytes(app))}, including $n book files" +
                    (if (d.options.encrypted) ", encrypted." else ".")
            } catch (t: Throwable) {
                pipe.fail()
                throw t
            } finally {
                d.options.password?.fill('\u0000')
            }
        }
        check(started) { "A backup or restore is already running." }
        return pipe.input
    }

    private class StoppedException : IOException("The download was stopped in the browser.")

    /** A thread-independent pipe (coroutines hop threads, which java.io's piped streams dislike). */
    private class Pipe {
        private val queue = ArrayBlockingQueue<ByteArray>(32)
        @Volatile private var readerClosed = false
        @Volatile private var writerFailed = false
        private val END = ByteArray(0)

        val output = object : OutputStream() {
            private val buf = ByteArray(1 shl 16); private var pos = 0; private var closed = false
            private fun push(b: ByteArray) {
                while (!queue.offer(b, 200, TimeUnit.MILLISECONDS)) if (readerClosed) throw StoppedException()
            }
            private fun flushBuf() { if (pos > 0) { push(buf.copyOf(pos)); pos = 0 } }
            override fun write(b: Int) { if (readerClosed) throw StoppedException(); if (pos == buf.size) flushBuf(); buf[pos++] = b.toByte() }
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (readerClosed) throw StoppedException()
                var o = off; var left = len
                while (left > 0) { if (pos == buf.size) flushBuf(); val n = minOf(left, buf.size - pos); System.arraycopy(b, o, buf, pos, n); pos += n; o += n; left -= n }
            }
            override fun flush() = flushBuf()
            override fun close() { if (closed) return; closed = true; flushBuf(); push(END) }
        }

        fun fail() { writerFailed = true; queue.offer(END) }

        val input = object : InputStream() {
            private var cur = ByteArray(0); private var pos = 0; private var done = false
            private fun fill(): Boolean {
                if (done) return false
                while (pos >= cur.size) {
                    val next = queue.poll(30, TimeUnit.MINUTES) ?: throw IOException("The backup stopped responding.")
                    if (next === END) {
                        done = true
                        if (writerFailed) throw IOException("The backup failed on the tablet.")
                        return false
                    }
                    cur = next; pos = 0
                }
                return true
            }
            override fun read(): Int = if (fill()) cur[pos++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (!fill()) return -1
                val n = minOf(len, cur.size - pos); System.arraycopy(cur, pos, b, off, n); pos += n; return n
            }
            override fun close() { readerClosed = true; queue.clear() }
        }
    }

    // ------------------------------------------------------------------ restore from the browser

    class Upload(val id: String, val file: File, val name: String, val size: Long) {
        @Volatile var received = 0L
    }
    private val uploads = ConcurrentHashMap<String, Upload>()
    private var pendingPassword: CharArray? = null
    private var pendingKeys = true

    private fun uploadDir(context: Context) = File(context.applicationContext.applicationInfo.dataDir, "web_restore_upload")

    fun startUpload(context: Context, name: String, size: Long): Upload {
        require(size > 0) { "The file is empty." }
        require(!busy && _pending.value == null) { "A backup or restore is already running." }
        val dir = uploadDir(context).apply { deleteRecursively(); mkdirs() }
        uploads.clear()
        val free = StatFs(dir.path).availableBytes
        val need = size * 2 + size / 10 + (300L shl 20)
        require(free >= need) {
            "Not enough free space on the tablet: restoring a ${bytes(size)} backup from the browser needs about ${bytes(need)} free " +
                "(the upload plus room to unpack it), but only ${bytes(free)} is free."
        }
        val id = token()
        return Upload(id, File(dir, "$id.upload"), name.take(200), size).also { uploads[id] = it }
    }

    fun upload(id: String): Upload = uploads[id] ?: throw NoSuchElementException("The upload was not found. Start it again.")

    /** Writes one chunk at [offset]; a chunk sent again after a dropped connection replaces the old one. */
    fun writeChunk(id: String, offset: Long, length: Long, body: InputStream, sha256: String): Long {
        val u = upload(id)
        synchronized(u) {
            require(offset in 0..u.received) { "Chunk out of order." }
            require(length in 1..UPLOAD_CHUNK.toLong() && offset + length <= u.size) { "Bad chunk size." }
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            RandomAccessFile(u.file, "rw").use { raf ->
                raf.setLength(offset)
                raf.seek(offset)
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = body.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n); digest.update(buf, 0, n); written += n
                }
                val ok = written == length && (sha256.isBlank() || digest.digest().joinToString("") { "%02x".format(it) } == sha256.lowercase(Locale.ROOT))
                if (!ok) {
                    raf.setLength(offset)
                    u.received = offset
                    throw IllegalArgumentException("A piece of the upload arrived damaged; it will be sent again.")
                }
            }
            u.received = offset + written
            return u.received
        }
    }

    /** Asks the tablet to confirm restoring the uploaded backup. The password is checked first. */
    suspend fun requestWebRestore(id: String, password: CharArray?, restoreKeys: Boolean): PendingRestore = withContext(Dispatchers.IO) {
        val u = upload(id)
        require(u.received == u.size && u.file.length() == u.size) { "The upload isn't complete yet." }
        require(!busy && _pending.value == null) { "A backup or restore is already running." }
        val encrypted = BackupManager.isEncrypted(u.file)
        if (encrypted) {
            if (password == null || password.isEmpty()) throw BackupManager.PasswordRequiredException()
            // Opening the first chunk tells a wrong password apart before the tablet is asked.
            u.file.inputStream().use { BackupCrypto.DecryptingInputStream(it, password.copyOf()).read() }
        }
        pendingPassword = password?.copyOf()
        password?.fill('\u0000')
        pendingKeys = restoreKeys
        PendingRestore(id, u.name, u.size, encrypted, System.currentTimeMillis()).also {
            _pending.value = it
            _state.value = State("web-restore", running = false, message = "Waiting for the restore to be confirmed on the tablet…")
        }
    }

    fun confirmWebRestore(context: Context) {
        val p = _pending.value ?: return
        val u = uploads[p.uploadId] ?: run { _pending.value = null; return }
        val password = pendingPassword; val keys = pendingKeys
        pendingPassword = null
        _pending.value = null
        val app = context.applicationContext
        val started = start("web-restore", "Restoring…") { onProgress ->
            try {
                u.file.inputStream().use { BackupManager.restore(app, it, u.size, password, keys, onProgress) }
            } finally {
                password?.fill('\u0000')
                uploads.remove(u.id)
                uploadDir(app).deleteRecursively()
            }
            // Give the browser a moment to see that it worked before the app (and its server) restarts.
            restartSoon(app, 2500)
            "Restored. Restarting…"
        }
        if (!started) password?.fill('\u0000')
    }

    fun declineWebRestore(context: Context) {
        val p = _pending.value ?: return
        pendingPassword?.fill('\u0000'); pendingPassword = null
        _pending.value = null
        uploads.remove(p.uploadId)
        uploadDir(context).deleteRecursively()
        _state.value = State("web-restore", running = false, message = "The restore was declined on the tablet.", error = true, finishedAt = System.currentTimeMillis())
    }
}
