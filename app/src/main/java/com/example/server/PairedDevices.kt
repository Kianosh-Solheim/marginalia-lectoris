package com.example.server

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Phones paired with this tablet (Settings → Library in the browser → Phones).
 *
 * The tablet shows a QR code holding its addresses and a short one-time pairing code, valid for ten
 * minutes. The phone sends the code to `POST /api/pair` and gets a long device key back, which it then
 * sends with every request (`Authorization: Bearer <key>`), so it never needs the PIN. Only a hash of the
 * key is kept here. Removing a phone makes its key stop working at once.
 */
object PairedDevices {
    private const val KEY_DEVICES = "paired_devices"
    private const val KEY_TABLET_ID = "tablet_id"
    private const val OFFER_MS = 10 * 60 * 1000L
    private val random = SecureRandom()

    data class Device(val id: String, val name: String, val created: Long, val lastSeen: Long)
    data class Offer(val code: String, val until: Long)

    /** The code shown right now, if any. */
    val offer = mutableStateOf<Offer?>(null)
    /** For the settings list; refreshed on every change. */
    val devices = mutableStateOf<List<Device>>(emptyList())

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(WebServer.PREFS, Context.MODE_PRIVATE)

    /** A stable ID for this tablet, so a phone knows it is talking to the tablet it paired with. */
    fun tabletId(ctx: Context): String = prefs(ctx).getString(KEY_TABLET_ID, null) ?: hex(12).also { prefs(ctx).edit().putString(KEY_TABLET_ID, it).apply() }

    /** A new one-time code (8 letters and digits, without look-alikes such as 0/O and 1/I). */
    fun newOffer(): Offer {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val code = (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        return Offer(code, System.currentTimeMillis() + OFFER_MS).also { offer.value = it }
    }

    fun cancelOffer() { offer.value = null }

    /** What the QR code holds: marginalia://pair?c=CODE&t=TABLET&n=NAME&u=URL&u=URL… */
    fun qrText(ctx: Context, o: Offer, urls: List<String>, name: String): String =
        Uri.Builder().scheme("marginalia").authority("pair")
            .appendQueryParameter("c", o.code).appendQueryParameter("t", tabletId(ctx)).appendQueryParameter("n", name)
            .apply { urls.distinct().forEach { appendQueryParameter("u", it) } }
            .build().toString()

    /** Trades a valid code for a device key (shown to the phone once), or null when the code is wrong or old. */
    @Synchronized
    fun redeem(ctx: Context, code: String, deviceName: String): Pair<String, String>? {
        val o = offer.value ?: return null
        if (System.currentTimeMillis() > o.until) { offer.value = null; return null }
        if (!MessageDigest.isEqual(o.code.toByteArray(), code.uppercase().filter { it.isLetterOrDigit() }.toByteArray())) return null
        offer.value = null
        val key = hex(32)
        val id = hex(8)
        val now = System.currentTimeMillis()
        val list = load(ctx)
        list.put(JSONObject().put("id", id).put("name", deviceName.take(60).ifBlank { "Phone" }).put("hash", sha(key)).put("created", now).put("lastSeen", now))
        save(ctx, list)
        return id to key
    }

    /** The paired device for an `Authorization: Bearer …` header, or null. Also notes when it was last seen. */
    fun check(ctx: Context, authorization: String): String? {
        val key = authorization.removePrefix("Bearer ").trim()
        if (key.length < 32) return null
        val h = sha(key)
        val list = load(ctx)
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (MessageDigest.isEqual(o.optString("hash").toByteArray(), h.toByteArray())) {
                val now = System.currentTimeMillis()
                if (now - o.optLong("lastSeen") > 60_000) { o.put("lastSeen", now); save(ctx, list) }
                return o.optString("id")
            }
        }
        return null
    }

    fun remove(ctx: Context, id: String) {
        val list = load(ctx)
        save(ctx, JSONArray().apply { for (i in 0 until list.length()) list.getJSONObject(i).takeIf { it.optString("id") != id }?.let { put(it) } })
    }

    fun refresh(ctx: Context) { publish(load(ctx)) }

    private fun load(ctx: Context): JSONArray = runCatching { JSONArray(prefs(ctx).getString(KEY_DEVICES, "[]")) }.getOrDefault(JSONArray())

    private fun save(ctx: Context, list: JSONArray) { prefs(ctx).edit().putString(KEY_DEVICES, list.toString()).apply(); publish(list) }

    private fun publish(list: JSONArray) {
        devices.value = (0 until list.length()).map { list.getJSONObject(it) }
            .map { Device(it.optString("id"), it.optString("name"), it.optLong("created"), it.optLong("lastSeen")) }
    }

    private fun hex(bytes: Int) = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
