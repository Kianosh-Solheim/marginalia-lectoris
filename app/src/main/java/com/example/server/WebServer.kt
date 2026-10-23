package com.example.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * “Library in the browser”: a web server on the tablet that any browser on the same Wi‑Fi can
 * open. It is switched on and off in Settings, runs as a foreground service (with a notification)
 * until switched off or unused for the chosen time, and asks for a PIN shown on the tablet.
 */
object WebServer {
    const val PREFS = "web_server_prefs"
    private val ports = listOf(8080, 8088, 8181, 8888, 9090, 0)

    val running = mutableStateOf(false)
    val url = mutableStateOf("")

    /** Every way the library can be reached right now: Wi‑Fi, Ethernet… */
    data class Address(val label: String, val url: String)
    val addresses = mutableStateOf<List<Address>>(emptyList())
    val pin = mutableStateOf("")
    val error = mutableStateOf("")
    /** Browsers that used the server in the last two minutes. */
    val activeBrowsers = mutableIntStateOf(0)

    @Volatile internal var http: MiniHttpServer? = null
    @Volatile internal var api: LibraryApi? = null
    @Volatile var lastActivity: Long = 0L

    /** Keep the tablet answering while its screen is off (wake lock + Wi‑Fi lock). On by default. */
    val stayAwake = mutableStateOf(true)

    /** The short name the library answers to on the Wi‑Fi: http://<name>.local:8080 */
    val hostName = mutableStateOf("marginalia")
    @Volatile internal var mdns: MdnsResponder? = null
    @Volatile private var appContext: Context? = null

    fun loadPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        stayAwake.value = prefs.getBoolean("stay_awake", true)
        hostName.value = prefs.getString("host_name", "marginalia") ?: "marginalia"
    }

    fun cleanHostName(raw: String): String =
        raw.trim().lowercase(java.util.Locale.ROOT).removeSuffix(".local").replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(40).ifBlank { "marginalia" }

    fun setHostName(context: Context, raw: String) {
        val clean = cleanHostName(raw)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("host_name", clean).apply()
        hostName.value = clean
        if (running.value) {
            runCatching { mdns?.stop() }
            mdns = MdnsResponder(context.applicationContext, clean).also { it.start() }
            refreshAddresses()
        }
    }

    fun setStayAwake(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("stay_awake", on).apply()
        stayAwake.value = on
        if (running.value) context.startService(Intent(context, WebServerService::class.java).setAction(WebServerService.ACTION_LOCKS))
    }

    fun idleMinutes(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("idle_minutes", 60)

    fun setIdleMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("idle_minutes", minutes).apply()
    }

    fun start(context: Context) {
        val intent = Intent(context, WebServerService::class.java).setAction(WebServerService.ACTION_START)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    fun stop(context: Context) {
        context.startService(Intent(context, WebServerService::class.java).setAction(WebServerService.ACTION_STOP))
    }

    /** Signs out every browser that was remembered. */
    fun forgetBrowsers(context: Context) {
        Sessions.clear(context)
    }

    internal fun newPin(): String = (SecureRandom().nextInt(900_000) + 100_000).toString()

    /** The tablet's Wi‑Fi (or other LAN) IPv4 address. */
    fun localAddress(): String? {
        val candidates = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it.hostAddress.orEmpty() } }
            .filter { it.second.isNotBlank() && !it.second.startsWith("169.254") }
        return (candidates.firstOrNull { it.first.startsWith("wlan") } ?: candidates.firstOrNull { it.first.startsWith("eth") }
            ?: candidates.firstOrNull { !it.first.startsWith("rmnet") && !it.first.startsWith("tun") } ?: candidates.firstOrNull())?.second
    }

    private fun labelFor(name: String): String? {
        val n = name.lowercase()
        return when {
            n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> null
            n.startsWith("swlan") || n.startsWith("ap") || n.startsWith("softap") -> "Tablet hotspot"
            n.startsWith("wlan") || n.startsWith("wifi") -> "Wi‑Fi"
            n.startsWith("eth") -> "Ethernet"
            n.startsWith("rmnet") || n.startsWith("tun") || n.startsWith("dummy") || n.startsWith("p2p") || n.startsWith("lo") -> null
            else -> "Network ($name)"
        }
    }

    /** Re-reads the tablet's addresses (the network can change while the server runs). */
    fun refreshAddresses() {
        val port = http?.port ?: return
        val suffix = if (port == 80) "" else ":$port"
        val found = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif ->
                val label = labelFor(nif.name) ?: return@flatMap emptyList()
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { a ->
                    a.hostAddress?.takeIf { it.isNotBlank() && !it.startsWith("169.254") }?.let { Address(label, "http://$it$suffix") }
                }
            }
            .distinctBy { it.url }
            .sortedBy { when (it.label) { "Wi‑Fi" -> 0; "Ethernet" -> 1; else -> 2 } }
        val withName = if (found.isNotEmpty() && mdns != null) listOf(Address("Short name", "http://${hostName.value}.local$suffix")) + found else found
        if (withName != addresses.value) addresses.value = withName
        val main = found.firstOrNull()?.url ?: "http://127.0.0.1$suffix"
        if (url.value != main) url.value = main
        val msg = if (found.isEmpty()) "The tablet does not seem to be on a Wi‑Fi network." else ""
        if (error.value != msg && (error.value.isBlank() || error.value.startsWith("The tablet "))) error.value = msg
    }

    internal fun startServer(context: Context): String {
        stopServer()
        val newPin = newPin()
        val libraryApi = LibraryApi(context.applicationContext) { newPinCheck -> newPinCheck == pin.value }
        val server = MiniHttpServer(libraryApi::handle)
        server.start(ports)
        http = server
        api = libraryApi
        pin.value = newPin
        error.value = ""
        loadPrefs(context)
        appContext = context.applicationContext
        mdns = runCatching { MdnsResponder(context.applicationContext, hostName.value).also { it.start() } }.getOrNull()
        refreshAddresses()
        lastActivity = System.currentTimeMillis()
        running.value = true
        return url.value
    }

    internal fun stopServer() {
        runCatching { mdns?.stop() }
        mdns = null
        runCatching { http?.stop() }
        runCatching { api?.close() }
        http = null
        api = null
        running.value = false
        activeBrowsers.intValue = 0
    }
}

/** Remembered browsers (a signed-in browser stays signed in for 30 days). */
internal object Sessions {
    private const val KEY = "sessions"
    private const val DAYS_30 = 30L * 24 * 60 * 60 * 1000

    fun create(context: Context): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        val prefs = context.getSharedPreferences(WebServer.PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val kept = prefs.getStringSet(KEY, emptySet()).orEmpty().filter { (it.substringAfter('|').toLongOrNull() ?: 0L) > now }.toMutableSet()
        kept += "$token|${now + DAYS_30}"
        prefs.edit().putStringSet(KEY, kept).apply()
        return token
    }

    fun valid(context: Context, token: String): Boolean {
        if (token.length < 32) return false
        val now = System.currentTimeMillis()
        return context.getSharedPreferences(WebServer.PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()
            .any { it.substringBefore('|') == token && (it.substringAfter('|').toLongOrNull() ?: 0L) > now }
    }

    fun remove(context: Context, token: String) {
        val prefs = context.getSharedPreferences(WebServer.PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY, prefs.getStringSet(KEY, emptySet()).orEmpty().filter { it.substringBefore('|') != token }.toSet()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(WebServer.PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}

class WebServerService : Service() {
    companion object {
        const val ACTION_START = "com.example.server.START"
        const val ACTION_STOP = "com.example.server.STOP"
        const val ACTION_LOCKS = "com.example.server.LOCKS"
        private const val CHANNEL = "web_server"
        private const val NOTIFICATION_ID = 4711
    }

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var watchdog: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        com.example.reader.FolderSync.start(applicationContext)
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_LOCKS) {
            if (WebServer.running.value) acquireLocks()
            return START_NOT_STICKY
        }
        // Must go to the foreground at once, before the (quick) server start.
        goForeground("Starting…")
        try {
            val address = WebServer.startServer(this)
            goForeground(address)
            acquireLocks()
            startWatchdog()
        } catch (e: Exception) {
            WebServer.error.value = "Could not start: ${e.message}"
            shutdown()
        }
        return START_NOT_STICKY
    }

    private fun goForeground(address: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Library in the browser", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the library can be opened from a browser on the same Wi‑Fi"
                setShowBadge(false)
            })
        }
        val flagsImmutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, WebServerService::class.java).setAction(ACTION_STOP), flagsImmutable or PendingIntent.FLAG_UPDATE_CURRENT)
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 2, it, flagsImmutable or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val pinText = WebServer.pin.value.takeIf { it.isNotBlank() }?.let { "  ·  PIN ${it.chunked(3).joinToString(" ")}" }.orEmpty()
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Library open in the browser")
            .setContentText("$address$pinText")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Open $address in a browser on the same Wi‑Fi.$pinText"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { openIntent?.let { setContentIntent(it) } }
            .addAction(0, "Switch off", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        WebServer.loadPrefs(this)
        val awake = WebServer.stayAwake.value
        runCatching {
            if (awake) {
                if (wifiLock == null) {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Marginalia:web").apply { setReferenceCounted(false) }
                }
                if (wifiLock?.isHeld != true) wifiLock?.acquire()
            } else if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        runCatching {
            if (awake) {
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Marginalia:web").apply { setReferenceCounted(false) }
                }
                // Held until the server is switched off (it also switches off by itself when unused).
                if (wakeLock?.isHeld != true) wakeLock?.acquire()
            } else if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }

    private fun startWatchdog() {
        watchdog?.interrupt()
        watchdog = Thread {
            try {
                while (!Thread.currentThread().isInterrupted && WebServer.running.value) {
                    Thread.sleep(30_000)
                    WebServer.refreshAddresses()
                    WebServer.api?.let { WebServer.activeBrowsers.intValue = it.activeBrowsers() }
                    val idle = WebServer.idleMinutes(this)
                    if (idle > 0 && System.currentTimeMillis() - WebServer.lastActivity > idle * 60_000L) {
                        WebServer.error.value = "Switched off after $idle minutes without use."
                        shutdown()
                        break
                    }
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
    }

    private fun shutdown() {
        watchdog?.interrupt()
        watchdog = null
        WebServer.stopServer()
        runCatching { wifiLock?.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wifiLock = null
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (WebServer.running.value) {
            WebServer.stopServer()
        }
        runCatching { wifiLock?.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        super.onDestroy()
    }
}
