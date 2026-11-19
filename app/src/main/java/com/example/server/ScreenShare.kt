package com.example.server

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * The tablet's screen in the browser: Android's screen capture (asked for each session) sends
 * pictures of the screen, and — when "Marginalia remote control" is switched on under Accessibility —
 * clicks and typing in the browser become taps, swipes and text on the tablet.
 */
object ScreenShare {
    enum class Phase { OFF, WAITING, ON }

    @Volatile var phase = Phase.OFF
        private set
    @Volatile var message = ""
    private val lock = Object()
    @Volatile private var frame: ByteArray? = null
    @Volatile private var seq = 0L
    @Volatile var frameW = 0
        private set
    @Volatile var frameH = 0
        private set
    /** When a browser last asked for a picture: pictures are made only while someone watches. */
    @Volatile var lastViewer = 0L

    const val CHANNEL = "screen_share"
    private const val ASK_ID = 4711

    fun controlOn(): Boolean = ScreenControlService.instance != null

    fun json(): JSONObject = JSONObject()
        .put("phase", phase.name.lowercase())
        .put("message", message)
        .put("width", frameW).put("height", frameH).put("seq", seq)
        .put("control", controlOn())

    internal fun publish(bytes: ByteArray, w: Int, h: Int) {
        synchronized(lock) { frame = bytes; frameW = w; frameH = h; seq++; lock.notifyAll() }
    }

    /** The newest picture after [after], waiting up to [waitMs] for one. */
    fun frameAfter(after: Long, waitMs: Long = 1500): Pair<Long, ByteArray>? {
        lastViewer = System.currentTimeMillis()
        val until = System.currentTimeMillis() + waitMs
        synchronized(lock) {
            while (seq <= after && phase == Phase.ON) {
                val left = until - System.currentTimeMillis(); if (left <= 0) break
                lock.wait(left)
            }
            val f = frame ?: return null
            return seq to f
        }
    }

    internal fun setPhase(p: Phase, msg: String = "") { phase = p; message = msg; if (p == Phase.OFF) synchronized(lock) { frame = null; lock.notifyAll() } }

    private fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen in the browser", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks before the screen is shown in a browser, and shows while it is"
            })
        }
    }

    /** The browser asks for the screen: Android's own question must be answered on the tablet. */
    fun request(ctx: Context) {
        if (phase == Phase.ON) return
        setPhase(Phase.WAITING, "Waiting for the tablet: allow the screen to be shared there.")
        val app = ctx.applicationContext
        val consent = Intent(app, ScreenConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // When Marginalia is in front, Android's question comes up at once; otherwise a notification asks for a tap.
        runCatching { app.startActivity(consent) }
        channel(app)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Show the screen in the browser?")
            .setContentText("Tap to choose, then allow it in Android's question.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(app, 7, consent, flags))
            .build()
        runCatching { app.getSystemService(NotificationManager::class.java).notify(ASK_ID, n) }
    }

    fun cancelAsk(ctx: Context) { runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(ASK_ID) } }

    fun stop(ctx: Context) {
        runCatching { ctx.startService(Intent(ctx, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_STOP)) }
        cancelAsk(ctx)
        setPhase(Phase.OFF)
    }

    @Suppress("DEPRECATION")
    fun realSize(ctx: Context): Pair<Int, Int> {
        val d = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics(); d.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    // ---- control

    /** Carries out one input from the browser. [o]: { type, x, y, x2, y2, ms, key, text } with x and y from 0 to 1. */
    fun input(ctx: Context, o: JSONObject) {
        val svc = ScreenControlService.instance
            ?: throw IllegalStateException("Control is off. On the tablet: Settings → Accessibility → Marginalia remote control → on.")
        val (w, h) = realSize(ctx)
        fun px(k: String) = (o.optDouble(k, 0.0).coerceIn(0.0, 1.0) * (if (k.startsWith("x")) w - 1 else h - 1)).toFloat()
        when (o.optString("type")) {
            "tap" -> svc.stroke(px("x"), px("y"), px("x"), px("y"), 60)
            "long" -> svc.stroke(px("x"), px("y"), px("x"), px("y"), 750)
            "swipe" -> svc.stroke(px("x"), px("y"), px("x2"), px("y2"), o.optLong("ms", 250).coerceIn(40, 3000))
            "key" -> svc.key(o.optString("key"))
            "text" -> svc.type(o.optString("text"))
            else -> throw IllegalArgumentException("Unknown input.")
        }
    }
}

/** Asks Android for the screen (its own question), then hands the answer to the service. */
class ScreenConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenShare.cancelAsk(this)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        runCatching { startActivityForResult(mpm.createScreenCaptureIntent(), 1) }
            .onFailure { ScreenShare.setPhase(ScreenShare.Phase.OFF, "Android would not ask for the screen."); finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            val i = Intent(this, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_START)
                .putExtra("code", resultCode).putExtra("data", data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } else ScreenShare.setPhase(ScreenShare.Phase.OFF, "Sharing the screen was not allowed on the tablet.")
        finish()
    }
}

/** Holds the screen capture while it lasts (Android wants a service with a notification for it). */
class ScreenShareService : Service() {
    companion object {
        const val ACTION_START = "com.example.server.SCREEN_START"
        const val ACTION_STOP = "com.example.server.SCREEN_STOP"
        private const val NOTIFICATION_ID = 4712
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastFrame = 0L
    private var curW = 0
    private var curH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopAll(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        goForeground()
        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION") val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("data", Intent::class.java) else intent.getParcelableExtra("data")
        if (data == null) { ScreenShare.setPhase(ScreenShare.Phase.OFF, "No permission came from Android."); stopSelf(); return START_NOT_STICKY }
        try {
            start(code, data)
            ScreenShare.setPhase(ScreenShare.Phase.ON)
        } catch (e: Throwable) {
            ScreenShare.setPhase(ScreenShare.Phase.OFF, "The screen could not be shared: ${e.message}")
            stopAll(); stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(ScreenShare.CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(ScreenShare.CHANNEL, "Screen in the browser", NotificationManager.IMPORTANCE_HIGH))
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stop = PendingIntent.getService(this, 8, Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP), flags)
        val n: Notification = NotificationCompat.Builder(this, ScreenShare.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("The screen is shown in a browser")
            .setContentText(if (ScreenShare.controlOn()) "It can also be controlled from there." else "View only.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, n)
    }

    private fun size(): Triple<Int, Int, Int> {
        val (w, h) = ScreenShare.realSize(this)
        val scale = minOf(1f, 1280f / maxOf(w, h))
        return Triple((w * scale).toInt() / 2 * 2, (h * scale).toInt() / 2 * 2, resources.displayMetrics.densityDpi)
    }

    private fun start(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("Android gave no screen.")
        projection = mp
        thread = HandlerThread("screen-share").also { it.start() }
        handler = Handler(thread!!.looper)
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { ScreenShare.setPhase(ScreenShare.Phase.OFF, "Sharing stopped on the tablet."); stopAll(); stopSelf() }
        }, handler)
        val (w, h, dpi) = size()
        curW = w; curH = h
        reader = newReader(w, h)
        display = mp.createVirtualDisplay("Marginalia screen", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
        // Turning the tablet: the picture follows.
        handler?.postDelayed(object : Runnable {
            override fun run() {
                if (projection == null) return
                val (nw, nh, nd) = size()
                if ((nw > nh) != (curW > curH)) {
                    val old = reader
                    reader = newReader(nw, nh); curW = nw; curH = nh
                    runCatching { display?.resize(nw, nh, nd); display?.surface = reader!!.surface }
                    runCatching { old?.close() }
                }
                handler?.postDelayed(this, 1000)
            }
        }, 1000)
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd ->
            val img = runCatching { rd.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                // Only while a browser is watching, and at most about six pictures a second.
                if (now - ScreenShare.lastViewer > 5000 || now - lastFrame < 160) return@setOnImageAvailableListener
                lastFrame = now
                val plane = img.planes[0]
                val rowPx = plane.rowStride / plane.pixelStride
                val bmp = Bitmap.createBitmap(rowPx, img.height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val crop = if (rowPx != img.width) Bitmap.createBitmap(bmp, 0, 0, img.width, img.height) else bmp
                val out = ByteArrayOutputStream(80_000)
                crop.compress(Bitmap.CompressFormat.JPEG, 60, out)
                if (crop !== bmp) crop.recycle(); bmp.recycle()
                ScreenShare.publish(out.toByteArray(), img.width, img.height)
            } catch (_: Throwable) {
            } finally { img.close() }
        }, handler)
        return r
    }

    private fun stopAll() {
        runCatching { display?.release() }; display = null
        runCatching { reader?.close() }; reader = null
        runCatching { projection?.stop() }; projection = null
        runCatching { thread?.quitSafely() }; thread = null; handler = null
        if (ScreenShare.phase == ScreenShare.Phase.ON) ScreenShare.setPhase(ScreenShare.Phase.OFF)
        runCatching { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true) }
    }

    override fun onDestroy() { stopAll(); super.onDestroy() }
}

/**
 * "Marginalia remote control": taps, swipes, keys and text from the browser. Switched on by the user
 * under Settings → Accessibility; does nothing unless the screen is being shared in a browser.
 */
class ScreenControlService : AccessibilityService() {
    companion object { @Volatile var instance: ScreenControlService? = null }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun guard() { check(ScreenShare.phase == ScreenShare.Phase.ON) { "The screen is not being shared." } }

    fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        guard()
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        check(dispatchGesture(g, null, null)) { "Android did not accept the gesture." }
    }

    fun key(k: String) {
        guard()
        when (k) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "backspace" -> edit { t, a, b -> if (a != b) Triple(t.removeRange(a, b), a, a) else if (a > 0) Triple(t.removeRange(a - 1, a), a - 1, a - 1) else null }
            "enter" -> {
                val n = focused()
                val done = n != null && Build.VERSION.SDK_INT >= 30 && n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                if (!done) type("\n")
            }
            else -> throw IllegalArgumentException("Unknown key.")
        }
    }

    private fun focused(): AccessibilityNodeInfo? = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    /** Changes the text of the field that has the cursor; [f] gets (text, selection start, end). */
    private fun edit(f: (String, Int, Int) -> Triple<String, Int, Int>?) {
        val n = focused()?.takeIf { it.isEditable } ?: throw IllegalStateException("Tap a text field on the tablet first.")
        val showingHint = Build.VERSION.SDK_INT >= 26 && n.isShowingHintText
        val t = if (showingHint) "" else n.text?.toString().orEmpty()
        val a = n.textSelectionStart.takeIf { it in 0..t.length } ?: t.length
        val b = n.textSelectionEnd.takeIf { it in a..t.length } ?: a
        val (nt, s, e) = f(t, minOf(a, b), maxOf(a, b)) ?: return
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, nt) })
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, s); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, e)
        })
    }

    fun type(text: String) {
        guard()
        if (text.isEmpty()) return
        edit { t, a, b -> Triple(t.substring(0, a) + text + t.substring(b), a + text.length, a + text.length) }
    }
}

/** Opens Android's Accessibility settings (to switch the remote control on). */
fun openAccessibilitySettings(ctx: Context) {
    runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
