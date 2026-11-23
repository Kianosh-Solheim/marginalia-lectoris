package com.example.reader

import android.content.Context
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Google Calendar, read and write, through your own Google Cloud OAuth client (type “Desktop app”).
 *
 * The client ID, the client secret and the sign-in (refresh token) are kept encrypted with the other keys
 * ([AiPreferences] secrets), never in backups. Google sends the sign-in back to http://127.0.0.1:<port>/ —
 * the tablet's own server when you sign in on the tablet; from another computer that page does not load,
 * and you paste its address into the app instead. Nothing is fetched unless a page asks (the calendar
 * page when it opens, or Refresh).
 */
object GoogleCalendar {
    private const val S_ID = "gcal.clientId"
    private const val S_SECRET = "gcal.clientSecret"
    private const val S_REFRESH = "gcal.refresh"
    private const val AUTH = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN = "https://oauth2.googleapis.com/token"
    private const val API = "https://www.googleapis.com/calendar/v3"
    private const val SCOPE_READ = "https://www.googleapis.com/auth/calendar.readonly"
    private const val SCOPE_WRITE = "https://www.googleapis.com/auth/calendar.events"
    /** Marks events the app made for a course session, so they can be found and updated again. */
    const val SESSION_KEY = "marginaliaSession"

    private val http by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build() }
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val lock = Any()

    @Volatile private var access: String = ""
    @Volatile private var accessUntil: Long = 0L
    private class Pending(val verifier: String, val redirect: String, val at: Long)
    private val pending = HashMap<String, Pending>()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("gcal", Context.MODE_PRIVATE)
    private fun keys(ctx: Context) = AiPreferences(ctx.applicationContext)
    private fun cacheFile(ctx: Context) = File(ctx.applicationContext.filesDir, "gcal_cache.json")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun bad(msg: String): Nothing = throw IllegalArgumentException(msg)

    // ------------------------------------------------------------------ settings and sign-in

    fun status(ctx: Context): JSONObject {
        val k = keys(ctx); val p = prefs(ctx)
        val id = runCatching { k.readSecret(S_ID) }.getOrDefault("")
        return JSONObject()
            .put("configured", id.isNotBlank() && k.hasSecret(S_SECRET))
            .put("clientTail", id.substringBefore('.').takeLast(6))
            .put("connected", k.hasSecret(S_REFRESH))
            .put("email", p.getString("email", "").orEmpty())
            .put("canWrite", p.getBoolean("canWrite", false))
            .put("shown", JSONArray(shown(ctx)))
            .put("writeTo", p.getString("writeTo", "").orEmpty())
            .put("writeToName", calMeta(ctx).optJSONObject(p.getString("writeTo", "").orEmpty())?.optString("name").orEmpty())
            .put("lastFetch", p.getLong("lastFetch", 0L))
    }

    fun setClient(ctx: Context, id: String, secret: String): JSONObject {
        val i = id.trim(); val s = secret.trim()
        if (!i.endsWith(".apps.googleusercontent.com")) bad("The client ID ends with .apps.googleusercontent.com — copy it from Google Cloud ▸ APIs & Services ▸ Credentials.")
        if (s.length < 10) bad("Paste the client secret too (shown next to the client ID).")
        val k = keys(ctx)
        if (runCatching { k.readSecret(S_ID) }.getOrDefault("") != i) forget(ctx)
        k.saveSecret(S_ID, i); k.saveSecret(S_SECRET, s)
        return status(ctx)
    }

    private fun verifier(): String {
        val b = ByteArray(48); SecureRandom().nextBytes(b)
        return android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }
    private fun challenge(v: String): String = android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(v.toByteArray()),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)

    /** The Google sign-in address to open; Google returns to the tablet's own server on [port]. */
    fun startAuth(ctx: Context, port: Int): JSONObject {
        val id = keys(ctx).readSecret(S_ID).ifBlank { bad("Save the client ID and secret first.") }
        val state = verifier().take(24); val v = verifier()
        val redirect = "http://127.0.0.1:$port/"
        synchronized(pending) {
            val now = System.currentTimeMillis()
            pending.entries.removeAll { now - it.value.at > 30 * 60_000 }
            pending[state] = Pending(v, redirect, now)
        }
        val url = AUTH + "?response_type=code&access_type=offline&prompt=consent&include_granted_scopes=true" +
            "&client_id=" + enc(id) + "&redirect_uri=" + enc(redirect) + "&scope=" + enc("$SCOPE_READ $SCOPE_WRITE") +
            "&state=" + enc(state) + "&code_challenge=" + challenge(v) + "&code_challenge_method=S256"
        return JSONObject().put("url", url).put("state", state)
    }

    /** True when [state] belongs to a sign-in this app started (so the unsigned-in callback page can act on it). */
    fun isPending(state: String): Boolean = synchronized(pending) { pending.containsKey(state) }

    /** Finishes a sign-in from Google's answer: either the pasted address of the page Google sent you to, or code + state. */
    fun finish(ctx: Context, pastedOrCode: String, stateArg: String = ""): JSONObject {
        val raw = pastedOrCode.trim()
        fun param(name: String) = Regex("[?&#]$name=([^&#\\s]+)").find(raw)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        param("error")?.let { bad(if (it == "access_denied") "You said no on Google's page; nothing was connected." else "Google answered: $it") }
        val code = param("code") ?: raw.takeIf { !it.contains("://") && it.length > 10 } ?: bad("Paste the whole address of the page Google sent you to (it starts with http://127.0.0.1).")
        val state = param("state") ?: stateArg
        val p = synchronized(pending) { pending.remove(state) } ?: synchronized(pending) {
            // A pasted bare code (no state): accept it for the single most recent sign-in.
            if (state.isBlank() && pending.size == 1) pending.entries.first().let { e -> pending.remove(e.key) } else null
        } ?: bad("This sign-in is old or was already used. Press “Sign in with Google” again.")
        val k = keys(ctx)
        val body = FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("code_verifier", p.verifier)
            .add("redirect_uri", p.redirect).add("client_id", k.readSecret(S_ID)).add("client_secret", k.readSecret(S_SECRET)).build()
        val o = tokenCall(body)
        val refresh = o.optString("refresh_token")
        if (refresh.isBlank()) bad("Google gave no lasting sign-in. Remove the app's access at myaccount.google.com/permissions and sign in again.")
        val scopes = o.optString("scope")
        if (!scopes.contains("calendar")) bad("Calendar access was not ticked on Google's page. Sign in again and tick it.")
        synchronized(lock) {
            k.saveSecret(S_REFRESH, refresh)
            access = o.optString("access_token"); accessUntil = System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 120) * 1000
        }
        val canWrite = scopes.contains(SCOPE_WRITE) || scopes.contains("auth/calendar ")  || scopes.endsWith("auth/calendar")
        val pr = prefs(ctx); pr.edit().putBoolean("canWrite", canWrite).apply()
        // Your primary calendar's ID is your address; show it and write to it unless you choose otherwise.
        runCatching {
            val prim = call(ctx, "GET", "$API/users/me/calendarList/primary", null)
            val email = prim.optString("id")
            val e = pr.edit().putString("email", email)
            if (shown(ctx).isEmpty()) e.putString("shown", JSONArray(listOf(email)).toString())
            if (pr.getString("writeTo", "").isNullOrBlank()) e.putString("writeTo", email)
            e.apply()
        }
        return status(ctx)
    }

    private fun tokenCall(body: FormBody): JSONObject =
        http.newCall(Request.Builder().url(TOKEN).post(body).build()).execute().use { r ->
            val o = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrDefault(JSONObject())
            if (!r.isSuccessful) {
                val err = o.optString("error")
                when (err) {
                    "invalid_client" -> bad("Google did not accept the client ID or secret. Check them in Settings ▸ Google Calendar.")
                    "invalid_grant" -> throw SignInGone()
                    "redirect_uri_mismatch" -> bad("The OAuth client must be of type “Desktop app”.")
                    else -> bad("Google sign-in failed: ${o.optString("error_description").ifBlank { err.ifBlank { "HTTP ${r.code}" } }}")
                }
            }
            o
        }

    private class SignInGone : IllegalArgumentException("The Google sign-in has run out or was withdrawn. Sign in with Google again in Settings.")

    private fun token(ctx: Context, force: Boolean = false): String = synchronized(lock) {
        if (!force && access.isNotBlank() && System.currentTimeMillis() < accessUntil) return access
        val k = keys(ctx)
        val refresh = runCatching { k.readSecret(S_REFRESH) }.getOrDefault("").ifBlank { bad("Connect Google Calendar first, in Settings.") }
        val o = try {
            tokenCall(FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh)
                .add("client_id", k.readSecret(S_ID)).add("client_secret", k.readSecret(S_SECRET)).build())
        } catch (e: SignInGone) { k.saveSecret(S_REFRESH, ""); access = ""; throw e }
        access = o.optString("access_token"); accessUntil = System.currentTimeMillis() + (o.optLong("expires_in", 3600) - 120) * 1000
        access
    }

    private fun call(ctx: Context, method: String, url: String, body: JSONObject?, retried: Boolean = false): JSONObject {
        val b = Request.Builder().url(url).header("Authorization", "Bearer " + token(ctx, retried))
        when (method) {
            "GET" -> b.get()
            "DELETE" -> b.delete()
            else -> b.method(method, (body ?: JSONObject()).toString().toRequestBody(JSON_TYPE))
        }
        http.newCall(b.build()).execute().use { r ->
            if (r.code == 401 && !retried) return call(ctx, method, url, body, true)
            val text = r.body?.string().orEmpty()
            if (r.code == 204 || (r.isSuccessful && text.isBlank())) return JSONObject()
            val o = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (!r.isSuccessful) {
                val msg = o.optJSONObject("error")?.optString("message").orEmpty()
                when {
                    r.code == 403 && msg.contains("insufficient", true) -> bad("The sign-in does not allow this. Sign in with Google again and tick both calendar boxes.")
                    r.code == 403 && msg.contains("has not been used", true) -> bad("Turn on the Google Calendar API for your Google Cloud project (APIs & Services ▸ Library ▸ Google Calendar API).")
                    r.code == 404 -> throw NoSuchElementException("Google Calendar: not found (it may have been deleted).")
                    r.code == 410 -> throw NoSuchElementException("Google Calendar: the event was deleted.")
                    else -> bad("Google Calendar answered HTTP ${r.code}${if (msg.isNotBlank()) ": $msg" else ""}")
                }
            }
            return o
        }
    }

    fun disconnect(ctx: Context): JSONObject {
        val refresh = runCatching { keys(ctx).readSecret(S_REFRESH) }.getOrDefault("")
        if (refresh.isNotBlank()) runCatching {
            http.newCall(Request.Builder().url("https://oauth2.googleapis.com/revoke").post(FormBody.Builder().add("token", refresh).build()).build()).execute().close()
        }
        forget(ctx)
        return status(ctx)
    }

    /** Removes the sign-in, the account and the cached events (keeps the client ID and secret). */
    private fun forget(ctx: Context) {
        synchronized(lock) { keys(ctx).saveSecret(S_REFRESH, ""); access = ""; accessUntil = 0 }
        prefs(ctx).edit().clear().apply()
        cacheFile(ctx).delete()
    }

    /** Also forgets the client ID and secret. */
    fun removeClient(ctx: Context): JSONObject {
        disconnect(ctx)
        keys(ctx).saveSecret(S_ID, ""); keys(ctx).saveSecret(S_SECRET, "")
        return status(ctx)
    }

    // ------------------------------------------------------------------ calendars and events

    private fun shown(ctx: Context): List<String> = runCatching {
        val a = JSONArray(prefs(ctx).getString("shown", "[]")); (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    fun calendars(ctx: Context): JSONObject {
        val list = JSONArray(); val show = shown(ctx).toSet()
        var page: String? = null
        do {
            val o = call(ctx, "GET", "$API/users/me/calendarList?maxResults=250" + (page?.let { "&pageToken=" + enc(it) } ?: ""), null)
            val items = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) items.getJSONObject(i).let { c ->
                list.put(JSONObject().put("id", c.optString("id")).put("name", c.optString("summaryOverride").ifBlank { c.optString("summary") })
                    .put("color", c.optString("backgroundColor", "#4a6fa5")).put("primary", c.optBoolean("primary"))
                    .put("canWrite", c.optString("accessRole") in setOf("owner", "writer")).put("shown", c.optString("id") in show))
            }
            page = o.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        return status(ctx).put("calendars", list)
    }

    fun setChoice(ctx: Context, shownIds: List<String>, writeTo: String, names: JSONObject?): JSONObject {
        prefs(ctx).edit().putString("shown", JSONArray(shownIds.distinct()).toString()).putString("writeTo", writeTo)
            .apply { if (names != null) putString("calendars", names.toString()) }.apply()
        return status(ctx)
    }

    private fun calMeta(ctx: Context): JSONObject = runCatching { JSONObject(prefs(ctx).getString("calendars", "{}")) }.getOrDefault(JSONObject())

    private fun eventJson(ev: JSONObject, cal: String, meta: JSONObject): JSONObject {
        val st = ev.optJSONObject("start") ?: JSONObject(); val en = ev.optJSONObject("end") ?: JSONObject()
        val allDay = st.has("date")
        val m = meta.optJSONObject(cal)
        return JSONObject().put("id", ev.optString("id")).put("cal", cal)
            .put("calName", m?.optString("name").orEmpty()).put("color", m?.optString("color") ?: "#4a6fa5").put("canWrite", m?.optBoolean("canWrite", true) ?: true)
            .put("title", ev.optString("summary")).put("description", ev.optString("description")).put("location", ev.optString("location"))
            .put("allDay", allDay).put("start", if (allDay) st.optString("date") else st.optString("dateTime"))
            .put("end", if (allDay) en.optString("date") else en.optString("dateTime"))
            .put("link", ev.optString("htmlLink"))
            .put("session", ev.optJSONObject("extendedProperties")?.optJSONObject("private")?.optString(SESSION_KEY).orEmpty())
    }

    /** Events in the shown calendars between [from] and [to] (RFC 3339), saved as the last copy fetched. */
    fun events(ctx: Context, from: String, to: String): JSONObject {
        require(from.isNotBlank() && to.isNotBlank()) { "from and to" }
        val meta = calMeta(ctx); val out = JSONArray(); val errors = JSONArray()
        for (cal in shown(ctx)) {
            try {
                var page: String? = null; var n = 0
                do {
                    val o = call(ctx, "GET", "$API/calendars/${enc(cal)}/events?singleEvents=true&orderBy=startTime&maxResults=2500" +
                        "&timeMin=" + enc(from) + "&timeMax=" + enc(to) + (page?.let { "&pageToken=" + enc(it) } ?: ""), null)
                    val items = o.optJSONArray("items") ?: JSONArray()
                    for (i in 0 until items.length()) items.getJSONObject(i).takeIf { it.optString("status") != "cancelled" }?.let { out.put(eventJson(it, cal, meta)) }
                    page = o.optString("nextPageToken").ifBlank { null }; n++
                } while (page != null && n < 8)
            } catch (e: SignInGone) { throw e } catch (e: Exception) { errors.put((meta.optJSONObject(cal)?.optString("name") ?: cal) + ": " + (e.message ?: "failed")) }
        }
        val now = System.currentTimeMillis()
        prefs(ctx).edit().putLong("lastFetch", now).apply()
        val res = JSONObject().put("events", out).put("from", from).put("to", to).put("fetched", now).put("errors", errors)
        runCatching { val f = cacheFile(ctx); val t = File(f.parentFile, f.name + ".tmp"); t.writeText(res.toString()); t.renameTo(f) }
        return res
    }

    /** The last events fetched (no call to Google). */
    fun cached(ctx: Context): JSONObject = runCatching { JSONObject(cacheFile(ctx).readText()) }.getOrDefault(JSONObject().put("events", JSONArray()).put("fetched", 0))

    /**
     * Makes or updates one event. [b]: cal (default: the calendar chosen for writing), id (to update), title,
     * description, location, allDay, start, end (dates for all-day, otherwise RFC 3339 times), timeZone, session.
     */
    fun save(ctx: Context, b: JSONObject): JSONObject {
        if (!prefs(ctx).getBoolean("canWrite", false)) bad("This sign-in can only read. Sign in with Google again and allow editing events.")
        val cal = b.optString("cal").ifBlank { prefs(ctx).getString("writeTo", "").orEmpty() }.ifBlank { "primary" }
        val allDay = b.optBoolean("allDay")
        val tz = b.optString("timeZone").ifBlank { java.util.TimeZone.getDefault().id }
        fun time(v: String) = if (allDay) JSONObject().put("date", v.take(10)) else JSONObject().put("dateTime", v).put("timeZone", tz)
        val start = b.optString("start").ifBlank { bad("The event has no start.") }
        val end = b.optString("end").ifBlank { start }
        val ev = JSONObject().put("summary", b.optString("title")).put("description", b.optString("description")).put("location", b.optString("location"))
            .put("start", time(start)).put("end", time(end))
        b.optString("session").takeIf { it.isNotBlank() }?.let { ev.put("extendedProperties", JSONObject().put("private", JSONObject().put(SESSION_KEY, it))) }
        val id = b.optString("id")
        val o = if (id.isNotBlank()) {
            try { call(ctx, "PATCH", "$API/calendars/${enc(cal)}/events/${enc(id)}", ev) }
            catch (_: NoSuchElementException) { call(ctx, "POST", "$API/calendars/${enc(cal)}/events", ev) } // deleted in Google: make it again
        } else call(ctx, "POST", "$API/calendars/${enc(cal)}/events", ev)
        return eventJson(o, cal, calMeta(ctx))
    }

    fun delete(ctx: Context, cal: String, id: String): JSONObject {
        require(id.isNotBlank()) { "id" }
        try { call(ctx, "DELETE", "$API/calendars/${enc(cal.ifBlank { "primary" })}/events/${enc(id)}", null) } catch (_: NoSuchElementException) {}
        return JSONObject().put("ok", true)
    }

    /** The small page shown where Google sends the browser after sign-in. */
    fun callbackPage(ctx: Context, query: Map<String, String>): String {
        val msg = try {
            val q = query.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
            val st = finish(ctx, "http://127.0.0.1/?$q")
            "Google Calendar is connected" + (st.optString("email").takeIf { it.isNotBlank() }?.let { " as " + escape(it) } ?: "") + ". You can close this page and go back to Marginalia."
        } catch (e: Exception) { "Not connected: " + escape(e.message ?: "failed") }
        return "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><title>Google Calendar</title>" +
            "<body style='font:17px/1.5 Georgia,serif;max-width:32em;margin:3em auto;padding:0 1em'><h1 style='font-weight:normal'>Marginalia Lectoris</h1><p>$msg</p></body>"
    }
    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
