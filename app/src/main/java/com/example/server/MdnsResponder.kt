package com.example.server

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.Locale

/**
 * Answers “<name>.local” on the Wi‑Fi (multicast DNS, the way printers announce themselves), so
 * a browser on Windows 10/11, macOS, iOS or Linux can open http://marginalia.local:8080 on any
 * network instead of the numeric address. Only A (IPv4) questions for our own name are answered.
 */
class MdnsResponder(private val context: Context, hostName: String) {
    private val name = hostName.lowercase(Locale.ROOT).removeSuffix(".local").trim('.') + ".local"
    @Volatile private var running = false
    private var socket: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var thread: Thread? = null

    private val group: InetAddress = InetAddress.getByName("224.0.0.251")

    fun start() {
        if (running) return
        running = true
        runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wm.createMulticastLock("Marginalia:mdns").apply { setReferenceCounted(false); acquire() }
        }
        thread = Thread({ loop() }, "MdnsResponder").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        runCatching { lock?.release() }
        socket = null
        thread?.interrupt()
    }

    private fun lanInterfaces(): List<Pair<NetworkInterface, Inet4Address>> =
        runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() }.getOrDefault(false) }
            .filter { n -> val l = n.name.lowercase(Locale.ROOT); !l.startsWith("rmnet") && !l.startsWith("tun") && !l.startsWith("dummy") && !l.startsWith("p2p") }
            .flatMap { n -> n.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { !it.isLinkLocalAddress }.map { n to it } }

    private fun loop() {
        var joinedOn = emptySet<String>()
        while (running) {
            try {
                val s = MulticastSocket(null as java.net.SocketAddress?).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(5353))
                    timeToLive = 255
                    soTimeout = 15_000
                }
                socket = s
                val lan = lanInterfaces()
                lan.forEach { (nif, _) -> runCatching { s.joinGroup(InetSocketAddress(group, 5353), nif) } }
                joinedOn = lan.map { it.second.hostAddress.orEmpty() }.toSet()
                announce(s, lan)
                val buf = ByteArray(9000)
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (_: java.net.SocketTimeoutException) {
                        // Network changed (new Wi‑Fi)? Rejoin on the new interface.
                        val now = lanInterfaces().map { it.second.hostAddress.orEmpty() }.toSet()
                        if (now != joinedOn) break else continue
                    }
                    handle(s, p)
                }
                runCatching { s.close() }
            } catch (e: Exception) {
                if (running) Log.w("MdnsResponder", "mDNS error", e)
                runCatching { Thread.sleep(5000) }
            }
        }
    }

    private fun announce(s: MulticastSocket, lan: List<Pair<NetworkInterface, Inet4Address>>) {
        lan.forEach { (nif, addr) ->
            runCatching {
                s.networkInterface = nif
                val data = response(0, addr)
                s.send(DatagramPacket(data, data.size, group, 5353))
            }
        }
    }

    private fun handle(s: MulticastSocket, p: DatagramPacket) {
        val d = p.data
        val len = p.length
        if (len < 12) return
        val id = ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
        val flags = ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)
        if (flags and 0x8000 != 0) return // a response, not a question
        val qd = ((d[4].toInt() and 0xFF) shl 8) or (d[5].toInt() and 0xFF)
        var pos = 12
        repeat(qd) {
            val (qname, next) = readName(d, pos, len) ?: return
            pos = next
            if (pos + 4 > len) return
            val qtype = ((d[pos].toInt() and 0xFF) shl 8) or (d[pos + 1].toInt() and 0xFF)
            val qclass = ((d[pos + 2].toInt() and 0xFF) shl 8) or (d[pos + 3].toInt() and 0xFF)
            pos += 4
            if (!qname.equals(name, ignoreCase = true) || (qtype != 1 && qtype != 255)) return@repeat
            val addr = addressFor(p.address) ?: return
            val unicast = (qclass and 0x8000) != 0 || p.port != 5353
            // Legacy (one-shot) queries from a port other than 5353 must echo the id and question.
            val data = response(if (p.port != 5353) id else 0, addr)
            runCatching {
                if (unicast) s.send(DatagramPacket(data, data.size, p.address, p.port))
                else s.send(DatagramPacket(data, data.size, group, 5353))
            }
        }
    }

    /** The tablet's address on the same network as the asker. */
    private fun addressFor(asker: InetAddress?): Inet4Address? {
        val lan = lanInterfaces()
        val a = asker as? Inet4Address
        if (a != null) {
            lan.forEach { (nif, mine) ->
                val prefix = nif.interfaceAddresses.firstOrNull { it.address == mine }?.networkPrefixLength?.toInt() ?: 24
                if (sameSubnet(mine, a, prefix)) return mine
            }
        }
        return lan.firstOrNull { it.first.name.startsWith("wlan") }?.second ?: lan.firstOrNull()?.second
    }

    private fun sameSubnet(x: Inet4Address, y: Inet4Address, prefix: Int): Boolean {
        val a = x.address; val b = y.address
        var bits = prefix.coerceIn(0, 32)
        for (i in 0 until 4) {
            val m = if (bits >= 8) 0xFF else if (bits <= 0) 0 else (0xFF shl (8 - bits)) and 0xFF
            if ((a[i].toInt() and m) != (b[i].toInt() and m)) return false
            bits -= 8
        }
        return true
    }

    private fun response(id: Int, addr: Inet4Address): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { out.write((v shr 8) and 0xFF); out.write(v and 0xFF) }
        fun u32(v: Int) { u16((v shr 16) and 0xFFFF); u16(v and 0xFFFF) }
        u16(id); u16(0x8400)          // response, authoritative
        u16(0); u16(1); u16(0); u16(0)  // 0 questions, 1 answer
        name.split('.').forEach { label -> val b = label.toByteArray(Charsets.UTF_8); out.write(b.size); out.write(b) }
        out.write(0)
        u16(1)                        // type A
        u16(0x8001)                   // class IN, cache-flush
        u32(120)                      // TTL
        u16(4); out.write(addr.address)
        return out.toByteArray()
    }

    private fun readName(d: ByteArray, start: Int, len: Int): Pair<String, Int>? {
        val labels = mutableListOf<String>()
        var pos = start
        var end = -1
        var jumps = 0
        while (pos < len) {
            val l = d[pos].toInt() and 0xFF
            when {
                l == 0 -> { if (end < 0) end = pos + 1; return labels.joinToString(".") to end }
                l and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= len || ++jumps > 10) return null
                    if (end < 0) end = pos + 2
                    pos = ((l and 0x3F) shl 8) or (d[pos + 1].toInt() and 0xFF)
                }
                else -> {
                    if (pos + 1 + l > len) return null
                    labels += String(d, pos + 1, l, Charsets.UTF_8)
                    pos += 1 + l
                }
            }
        }
        return null
    }
}
