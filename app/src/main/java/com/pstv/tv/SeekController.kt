package com.pstv.tv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * One fixed step per press; a slowly growing step while the key is held (capped); and seeks are debounced:
 * the key only moves a pending target and a single seekTo is issued after the keys stop, so seeks never stack
 * and a held key can never fire dozens of overlapping seeks.
 */
class SeekController(private val player: () -> Player?, private val onPreview: (target: Long, duration: Long) -> Unit) {
    private val h = Handler(Looper.getMainLooper())
    private var pending: Long? = null
    private var lastTick = 0L
    private var hold = 0

    val pendingTarget: Long? get() = pending

    private val commit = Runnable {
        pending?.let { player()?.seekTo(it) }
        pending = null
        hold = 0
    }

    fun press(dir: Int, repeat: Int) {
        val p = player() ?: return
        val base = Prefs.seekStep * 1000L
        val step: Long
        if (repeat == 0) {
            hold = 0
            step = base
        } else {
            val now = SystemClock.uptimeMillis()
            if (now - lastTick < 250) return        // held key: at most 4 steps per second
            lastTick = now
            hold++
            val cap = maxOf(base, minOf(base * 6, 300_000L))
            step = minOf(base * (1 + hold / 4), cap)  // grows by one base step every ~1 s of holding
        }
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val cur = pending ?: p.currentPosition
        var t = cur + dir * step
        t = if (dur != null) t.coerceIn(0L, maxOf(0L, dur - 1500)) else t.coerceAtLeast(0L)
        pending = t
        onPreview(t, dur ?: 0L)
        h.removeCallbacks(commit)
        h.postDelayed(commit, 450)
    }

    fun cancel() { h.removeCallbacks(commit); pending = null; hold = 0 }
}
