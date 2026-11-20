package com.example.server

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Take notes here": while a browser holds this, a highlight made in the tablet's reader is saved at
 * once and handed to that browser, where its note dialog opens (title, note, tags, [[links]]). When the
 * browser is gone for a while, the tablet's own dialog is used again.
 *
 * A quotation can go on to the next page, asked for from the browser or from the tablet: the tablet
 * turns the page, the rest selected there is joined to the same note, and the note is handed over again.
 */
object NoteRelay {
    private val lock = Object()
    @Volatile private var holder: String? = null
    @Volatile private var lastSeen = 0L
    @Volatile private var polling = 0
    /** Note id → how many times it has been handed over (so a note handed over again shows again). */
    private val queue = LinkedHashMap<Long, Int>()
    private var rounds = HashMap<Long, Int>()
    private val armed = AtomicBoolean(false)
    private const val GONE_AFTER = 15_000L

    /** The note the browser asked to continue on the next page (taken by the reader that has it). */
    @Volatile var continueRequest: Long? = null
    /** The note last handed over (for the tablet's own "Continue on next page"). */
    @Volatile var lastPushed: Long? = null
        private set
    @Volatile var lastPushedAt = 0L
        private set

    /** Is a browser taking the notes right now? */
    fun active(): Boolean = holder != null && (polling > 0 || System.currentTimeMillis() - lastSeen < GONE_AFTER)

    fun claim(client: String) = synchronized(lock) { holder = client; lastSeen = System.currentTimeMillis(); lock.notifyAll() }

    /** Ends it: from that browser ([client]), or from the tablet (null). */
    fun release(client: String?) = synchronized(lock) {
        if (client == null || holder == client) { holder = null; queue.clear(); continueRequest = null }
        lock.notifyAll()
    }

    /** The next highlight saved by the reader goes to the browser. */
    fun armNext() = armed.set(true)
    fun takeArmed(): Boolean = armed.getAndSet(false) && holder != null

    fun push(id: Long) = synchronized(lock) {
        val n = (rounds[id] ?: 0) + 1
        rounds[id] = n; queue[id] = n
        lastPushed = id; lastPushedAt = System.currentTimeMillis()
        lock.notifyAll()
    }
    fun done(id: Long) = synchronized(lock) { queue.remove(id); Unit }

    /** The browser asks for [id] to go on to the next page. */
    fun requestContinue(id: Long) = synchronized(lock) { queue.remove(id); continueRequest = id }

    /** What waits for the browser ("id:round" it has not seen), waiting up to [waitMs] for something new. */
    fun poll(client: String, seen: Set<String>, waitMs: Long): JSONObject = synchronized(lock) {
        if (holder != client) return JSONObject().put("active", false).put("taken", holder != null && active())
        polling++
        lastSeen = System.currentTimeMillis()
        try {
            val until = System.currentTimeMillis() + waitMs.coerceIn(0, 25_000)
            while (holder == client && queue.all { (id, n) -> "$id:$n" in seen }) {
                val left = until - System.currentTimeMillis(); if (left <= 0) break
                lock.wait(left)
            }
        } finally { polling--; lastSeen = System.currentTimeMillis() }
        JSONObject().put("active", holder == client)
            .put("items", JSONArray().apply { queue.forEach { (id, n) -> put(JSONObject().put("id", id).put("round", n)) } })
            .put("continuing", continueRequest ?: JSONObject.NULL)
    }
}
