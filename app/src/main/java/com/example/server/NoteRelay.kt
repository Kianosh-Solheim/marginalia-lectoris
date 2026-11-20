package com.example.server

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Take notes here": while a browser holds this, a highlight made in the tablet's reader is saved at
 * once and handed to that browser, where its note dialog opens (title, note, tags, [[links]]). When the
 * browser is gone for a while, the tablet's own dialog is used again.
 */
object NoteRelay {
    private val lock = Object()
    @Volatile private var holder: String? = null
    @Volatile private var lastSeen = 0L
    @Volatile private var polling = 0
    private val queue = LinkedHashSet<Long>()
    private val armed = AtomicBoolean(false)
    private const val GONE_AFTER = 15_000L

    /** Is a browser taking the notes right now? */
    fun active(): Boolean = holder != null && (polling > 0 || System.currentTimeMillis() - lastSeen < GONE_AFTER)

    fun claim(client: String) = synchronized(lock) { holder = client; lastSeen = System.currentTimeMillis(); lock.notifyAll() }

    /** Ends it: from that browser ([client]), or from the tablet (null). */
    fun release(client: String?) = synchronized(lock) {
        if (client == null || holder == client) { holder = null; queue.clear() }
        lock.notifyAll()
    }

    /** The next highlight saved by the reader goes to the browser. */
    fun armNext() = armed.set(true)
    fun takeArmed(): Boolean = armed.getAndSet(false) && holder != null

    fun push(id: Long) = synchronized(lock) { queue.add(id); lock.notifyAll() }
    fun done(id: Long) = synchronized(lock) { queue.remove(id); Unit }

    /** What waits for the browser, waiting up to [waitMs] for something new. */
    fun poll(client: String, seen: Set<Long>, waitMs: Long): JSONObject = synchronized(lock) {
        if (holder != client) return JSONObject().put("active", false).put("taken", holder != null && active())
        polling++
        lastSeen = System.currentTimeMillis()
        try {
            val until = System.currentTimeMillis() + waitMs.coerceIn(0, 25_000)
            while (holder == client && queue.all { it in seen }) {
                val left = until - System.currentTimeMillis(); if (left <= 0) break
                lock.wait(left)
            }
        } finally { polling--; lastSeen = System.currentTimeMillis() }
        JSONObject().put("active", holder == client).put("items", JSONArray(queue.toList()))
    }
}
