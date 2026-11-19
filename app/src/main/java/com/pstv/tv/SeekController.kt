package com.pstv.tv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * One fixed step per press; a slowly growing step while the key is held (capped); and seeks are debounced:
 * the key only moves a pending target and a single seekTo is issued after the keys stop, so seeks never stack
 * and a held key can never fire dozens of overlapping seeks.
 */
class SeekController(private val engine: () -> Engine?, private val onPreview: (target: Long, duration: Long) -> Unit) {
    private val h = Handler(Looper.getMainLooper())
    private var pending: Long? = null
    private var lastTick = 0L
    private var hold = 0

    companion object {
        /** One step while a key is held: the configured step grows with every repeat, up to 15 steps (max 10 min). */
        fun stepFor(baseMs: Long, hold: Int, accel: Double): Long =
            minOf((baseMs * (1 + hold * accel)).toLong(), maxOf(baseMs, minOf(baseMs * 15, 600_000L)))
    }

    val pendingTarget: Long? get() = pending

    private val commit = Runnable {
        pending?.let { engine()?.seekTo(it) }
        pending = null
        hold = 0
    }

    fun press(dir: Int, repeat: Int) {
        val p = engine() ?: return
        val base = (if (dir > 0) Prefs.ffStep else Prefs.rwStep) * 1000L
        val step: Long
        if (repeat == 0) {
            hold = 0
            step = base
        } else {
            val now = SystemClock.uptimeMillis()
            if (now - lastTick < 200) return        // held key: at most 5 steps per second
            lastTick = now
            hold++
            step = stepFor(base, hold, when (Prefs.seekAccel) { 0 -> 0.0; 1 -> 0.7; else -> 1.4 })
        }
        val dur = p.duration.takeIf { it > 0 }
        val cur = pending ?: p.position
        var t = cur + dir * step
        t = if (dur != null) t.coerceIn(0L, maxOf(0L, dur - 1500)) else t.coerceAtLeast(0L)
        pending = t
        onPreview(t, dur ?: 0L)
        h.removeCallbacks(commit)
        h.postDelayed(commit, 450)
    }

    fun cancel() { h.removeCallbacks(commit); pending = null; hold = 0 }
}
