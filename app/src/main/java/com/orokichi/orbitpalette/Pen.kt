package com.orokichi.orbitpalette

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import kotlin.math.abs

object PenPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun pencil(c: Context) = sp(c).getBoolean("pencil", false)
    fun setPencil(c: Context, v: Boolean) = sp(c).edit().putBoolean("pencil", v).apply()
}

/**
 * Shared pen input for every drawing surface: uses all batched (historical) samples at sub-pixel
 * precision and filters jitter. Pencil mode: one continuous vibration whose strength follows the
 * writing speed (smooth "buuuun", not ticks), stopping when the pen rests.
 */
class PenTracker(@Suppress("unused") private val view: View, private val ctx: Context) {
    private var pencil = false
    private var vib: Vibrator? = null
    private var amp = false
    private var last: Offset? = null
    private var lastT = 0L
    private var speed = 0f          // smoothed px/ms
    private var curAmp = 0
    private var lastSet = 0L
    private var lastMove = 0L
    private val dens = ctx.resources.displayMetrics.density
    private val h = Handler(Looper.getMainLooper())
    private val idle = object : Runnable {
        override fun run() {
            if (curAmp == 0) return
            if (SystemClock.uptimeMillis() - lastMove > 70) { speed = 0f; setAmp(0) } else h.postDelayed(this, 40)
        }
    }

    fun begin(@Suppress("UNUSED_PARAMETER") scope: AwaitPointerEventScope, down: PointerInputChange): Offset {
        pencil = PenPrefs.pencil(ctx)
        if (pencil && vib == null) {
            vib = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
            amp = vib?.hasAmplitudeControl() == true
        }
        last = down.position; lastT = down.uptimeMillis; speed = 0f; curAmp = 0
        return down.position
    }

    /** All new points for this change (historical + current), jitter < 0.25px dropped. */
    fun move(@Suppress("UNUSED_PARAMETER") scope: AwaitPointerEventScope, c: PointerInputChange): List<Offset> {
        val out = ArrayList<Offset>(c.historical.size + 1)
        c.historical.forEach { add(out, it.position, it.uptimeMillis) }
        add(out, c.position, c.uptimeMillis)
        if (pencil && out.isNotEmpty()) feel()
        return out
    }

    fun end() { last = null; h.removeCallbacks(idle); if (curAmp != 0) setAmp(0) }

    private fun add(out: MutableList<Offset>, p: Offset, t: Long) {
        val l = last
        if (l != null && (p - l).getDistance() < 0.25f) return
        if (l != null) {
            val dt = (t - lastT).coerceAtLeast(1).toFloat()
            val v = (p - l).getDistance() / dens / dt   // dp per ms
            speed += (v - speed) * 0.25f
        }
        out.add(p); last = p; lastT = t
    }

    private fun feel() {
        lastMove = SystemClock.uptimeMillis()
        // ~0.05 dp/ms (slow) → faint, ~2 dp/ms (fast) → stronger. Kept subtle.
        val a = if (speed < 0.02f) 0 else (18 + speed * 45f).toInt().coerceIn(18, 110)
        val now = lastMove
        if ((abs(a - curAmp) >= 8 || (a == 0) != (curAmp == 0)) && now - lastSet >= 45) setAmp(a)
        h.removeCallbacks(idle); h.postDelayed(idle, 40)
    }

    private fun setAmp(a: Int) {
        val v = vib ?: return
        lastSet = SystemClock.uptimeMillis()
        curAmp = a
        runCatching {
            if (a == 0) v.cancel()
            else if (amp) v.vibrate(VibrationEffect.createWaveform(longArrayOf(5000), intArrayOf(a), 0))
            else v.vibrate(VibrationEffect.createWaveform(longArrayOf(5000), 0))
        }
    }
}
