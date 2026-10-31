package com.example.reader

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The speech timer: counts down from the set time, then counts the overtime. At each warning point
 * (and at zero) it gives a cue — a vibration and a flash of the screen edge; a short tone only when
 * the timer is not silent. It keeps running when Speech mode is closed.
 */
object SpeechTimer {
    data class State(val running: Boolean = false, val durationMs: Long = 600_000, val elapsedMs: Long = 0, val started: Boolean = false) {
        val remainingMs get() = durationMs - elapsedMs
        val overtime get() = remainingMs < 0
    }

    /** A cue at a warning point: seconds left (0 = time is up). */
    data class Cue(val secondsLeft: Int)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _cues = MutableSharedFlow<Cue>(extraBufferCapacity = 4)
    val cues: SharedFlow<Cue> = _cues.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var startedAt = 0L
    private var base = 0L
    private var warnings: List<Int> = emptyList()
    private var fired = HashSet<Int>()
    private var silent = true
    private var vibrate = true
    private var app: Context? = null

    /** Sets the time and cues (only changes the time when the timer has not been started). */
    fun configure(ctx: Context, durationSec: Int, warningsSec: List<Int>, silent: Boolean, vibrate: Boolean) {
        app = ctx.applicationContext
        warnings = (warningsSec + 0).distinct().sortedDescending()
        this.silent = silent; this.vibrate = vibrate
        val s = _state.value
        if (!s.started) _state.value = s.copy(durationMs = durationSec * 1000L, elapsedMs = 0)
        else fired.addAll(warnings.filter { it * 1000L >= s.remainingMs })
    }

    fun start(ctx: Context) {
        app = ctx.applicationContext
        val s = _state.value
        if (s.running) return
        if (!s.started) fired = HashSet(warnings.filter { it * 1000L >= s.durationMs && it > 0 })
        base = s.elapsedMs
        startedAt = System.currentTimeMillis()
        _state.value = s.copy(running = true, started = true)
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                val el = base + (System.currentTimeMillis() - startedAt)
                val cur = _state.value.copy(elapsedMs = el)
                _state.value = cur
                warnings.firstOrNull { it !in fired && cur.remainingMs <= it * 1000L }?.let { w ->
                    fired.add(w); cue(w)
                }
                delay(200)
            }
        }
    }

    fun pause() {
        job?.cancel(); job = null
        val s = _state.value
        if (s.running) _state.value = s.copy(running = false, elapsedMs = base + (System.currentTimeMillis() - startedAt))
    }

    fun reset() {
        job?.cancel(); job = null
        fired.clear()
        _state.value = _state.value.copy(running = false, started = false, elapsedMs = 0)
    }

    private fun cue(secondsLeft: Int) {
        _cues.tryEmit(Cue(secondsLeft))
        val ctx = app ?: return
        if (vibrate) runCatching {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            val pattern = if (secondsLeft == 0) longArrayOf(0, 220, 140, 220, 140, 220) else longArrayOf(0, 180, 120, 180)
            v?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
        if (!silent) runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tg.startTone(if (secondsLeft == 0) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 300)
            scope.launch { delay(600); tg.release() }
        }
    }

    fun format(ms: Long): String {
        val total = kotlin.math.abs(ms) / 1000
        val s = if (ms < 0) (total + if (kotlin.math.abs(ms) % 1000 > 0) 1 else 0) else total + if (ms % 1000 > 0) 1 else 0
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return (if (ms < 0) "+" else "") + if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    fun label(sec: Int): String = when {
        sec == 0 -> "Time is up"
        sec % 60 == 0 -> "${sec / 60} min left"
        sec > 60 -> "${sec / 60} min ${sec % 60} s left"
        else -> "$sec s left"
    }
}
