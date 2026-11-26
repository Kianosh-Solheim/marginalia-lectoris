package com.example

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why the app closed: the error of each crash (saved as it happens), and Android's own record of the
 * app's last exits (crash, out of memory, not responding, closed by the system…). Read in the web view's
 * Settings → Why the app closed, and from `GET /api/diagnostics/exits`.
 */
object CrashLog {
    private const val KEEP = 12
    private var installed = false

    private fun dir(ctx: Context) = File(ctx.filesDir, "crashes").apply { mkdirs() }

    /** Saves the error of any crash in this process, then lets Android handle it as before. */
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val time = System.currentTimeMillis()
                val text = buildString {
                    append("Time: ").append(stamp(time)).append('\n')
                    append("Thread: ").append(thread.name).append('\n')
                    append("App: ").append(versionOf(app)).append('\n')
                    append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE).append('\n')
                    val rt = Runtime.getRuntime()
                    append("Memory: ").append((rt.totalMemory() - rt.freeMemory()) / 1_048_576).append(" MB used of ").append(rt.maxMemory() / 1_048_576).append(" MB\n\n")
                    append(android.util.Log.getStackTraceString(error))
                }
                File(dir(app), "crash-$time.txt").writeText(text)
                dir(app).listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, error)
        }
    }

    private fun stamp(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(t))

    private fun versionOf(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (_: Throwable) { "" }

    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "Crash (an error in the app)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Crash (in native code, e.g. the book view)"
        ApplicationExitInfo.REASON_ANR -> "Not responding (froze)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Closed by the system: low memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Closed by the system: used too much"
        ApplicationExitInfo.REASON_SIGNALED -> "Stopped by a signal (often: out of memory)"
        ApplicationExitInfo.REASON_EXIT_SELF -> "Closed itself"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "Closed by you (force stop or swipe away)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "Stopped by you"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Something it depends on stopped"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Could not start"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "A permission was changed"
        ApplicationExitInfo.REASON_OTHER -> "Closed by the system (other)"
        else -> "Unknown ($r)"
    }

    /** The last exits Android recorded, newest first, with the saved errors next to the crashes. */
    fun exitsJson(ctx: Context): JSONObject {
        val out = JSONObject()
        val exits = JSONArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                for (e in am.getHistoricalProcessExitReasons(ctx.packageName, 0, 16)) {
                    val o = JSONObject()
                        .put("time", stamp(e.timestamp))
                        .put("reason", reasonName(e.reason))
                        .put("description", e.description ?: "")
                        .put("process", e.processName ?: "")
                        .put("memoryMB", e.pss / 1024)
                        .put("status", e.status)
                    // a freeze (and some native crashes) come with Android's trace: the first part of it
                    if (e.reason == ApplicationExitInfo.REASON_ANR || e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                        try {
                            e.traceInputStream?.bufferedReader()?.use { r -> o.put("trace", r.readLines().take(160).joinToString("\n")) }
                        } catch (_: Throwable) {}
                    }
                    exits.put(o)
                }
            } catch (t: Throwable) {
                out.put("exitsError", t.toString())
            }
        } else {
            out.put("exitsError", "Android before 11 does not keep this record.")
        }
        out.put("exits", exits)
        val crashes = JSONArray()
        dir(ctx).listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name }?.forEach {
            crashes.put(JSONObject().put("file", it.name).put("text", it.readText().take(20_000)))
        }
        out.put("crashes", crashes)
        out.put("app", versionOf(ctx))
        return out
    }
}
