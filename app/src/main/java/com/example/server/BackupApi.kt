package com.example.server

import android.content.Context
import com.example.reader.BackupCrypto
import com.example.reader.BackupJobs
import com.example.reader.BackupManager
import org.json.JSONObject

/**
 * Backup and restore for the web view. The tablet does all the work (and the encryption); the
 * browser receives the backup as a streamed download, or uploads one in resumable chunks.
 *
 *   GET  backup/status                    progress of the current job, and any restore awaiting the tablet
 *   POST backup/prepare                   {password?, includeKeys} → {token, name}
 *   GET  backup/download?t=TOKEN          the backup, streamed as it is made
 *   POST backup/upload                    {name, size} → {id, chunk}
 *   GET  backup/upload/ID                 {received, size}, to resume
 *   PUT  backup/upload/ID?offset=N        one chunk (header X-Sha256: its SHA-256)
 *   POST backup/upload/ID/restore         {password?, restoreKeys} → waits for confirmation on the tablet
 */
class BackupApi(private val context: Context) {

    private fun password(b: JSONObject): CharArray? = b.optString("password").takeIf { it.isNotEmpty() }?.toCharArray()

    suspend fun route(req: HttpRequest, s: List<String>): HttpResponse {
        val m = req.method
        return when {
            s == listOf("status") && m == "GET" -> HttpResponse.json(status().toString())

            s == listOf("prepare") && m == "POST" -> {
                val b = JSONObject(req.bodyText())
                val pw = password(b)
                require(pw == null || pw.size >= 8) { "Use a password of at least 8 characters." }
                val options = BackupManager.Options(pw, includeKeys = pw != null && b.optBoolean("includeKeys"))
                val token = BackupJobs.prepareWebDownload(options)
                HttpResponse.json(JSONObject().put("token", token).put("name", BackupManager.suggestedFileName()).toString())
            }

            s == listOf("download") && (m == "GET" || m == "HEAD") -> {
                val stream = BackupJobs.openWebDownload(context, req.query["t"].orEmpty())
                HttpResponse.Stream(200, "application/zip") { stream }
                    .header("Content-Disposition", "attachment; filename=\"${BackupManager.suggestedFileName()}\"")
            }

            s == listOf("upload") && m == "POST" -> {
                val b = JSONObject(req.bodyText())
                val u = BackupJobs.startUpload(context, b.optString("name"), b.optLong("size"))
                HttpResponse.json(JSONObject().put("id", u.id).put("chunk", BackupJobs.UPLOAD_CHUNK).toString())
            }

            s.size == 2 && s[0] == "upload" && m == "GET" -> {
                val u = BackupJobs.upload(s[1])
                HttpResponse.json(JSONObject().put("received", u.received).put("size", u.size).toString())
            }

            s.size == 2 && s[0] == "upload" && m == "PUT" -> {
                val offset = req.query["offset"]?.toLongOrNull() ?: throw IllegalArgumentException("Missing offset.")
                val received = try {
                    BackupJobs.writeChunk(s[1], offset, req.contentLength, req.body, req.header("x-sha256"))
                } finally {
                    req.drain()
                }
                HttpResponse.json(JSONObject().put("received", received).toString())
            }

            s.size == 3 && s[0] == "upload" && s[2] == "restore" && m == "POST" -> {
                val b = JSONObject(req.bodyText())
                try {
                    BackupJobs.requestWebRestore(s[1], password(b), b.optBoolean("restoreKeys", true))
                } catch (e: BackupCrypto.WrongPasswordException) {
                    throw IllegalArgumentException(e.message)
                } catch (e: BackupCrypto.DamagedException) {
                    throw IllegalArgumentException(e.message)
                }
                HttpResponse.json(status().toString())
            }

            else -> throw NoSuchElementException("Not found")
        }.let { if (it is HttpResponse.Bytes) it.header("Cache-Control", "no-store") else it }
    }

    private fun status(): JSONObject {
        val st = BackupJobs.state.value
        val o = JSONObject()
            .put("kind", st.kind)
            .put("running", st.running)
            .put("message", st.message)
            .put("error", st.error)
            .put("finishedAt", st.finishedAt)
            .put("restarting", st.restarting)
            .put("lastBackup", BackupManager.lastBackupTime(context))
            .put("lastBackupBytes", BackupManager.lastBackupBytes(context))
        st.progress?.let { p -> o.put("progress", JSONObject().put("stage", p.stage).put("done", p.done).put("total", p.total)) }
        BackupJobs.pendingWebRestore.value?.let { p ->
            o.put("awaitingTablet", JSONObject().put("name", p.name).put("size", p.size).put("encrypted", p.encrypted))
        }
        return o
    }
}
