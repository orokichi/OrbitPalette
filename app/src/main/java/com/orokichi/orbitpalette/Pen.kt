package com.orokichi.orbitpalette

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.input.motionprediction.MotionEventPredictor

object PenPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun pencil(c: Context) = sp(c).getBoolean("pencil", false)
    fun setPencil(c: Context, v: Boolean) = sp(c).edit().putBoolean("pencil", v).apply()
}

/**
 * Shared pen input for every drawing surface: uses all batched (historical) samples at sub-pixel
 * precision, predicts the next position with MotionEventPredictor to hide latency, filters jitter,
 * and (pencil mode) gives fine haptic grain while writing.
 */
class PenTracker(private val view: View, private val ctx: Context) {
    private val predictor = runCatching { MotionEventPredictor.newInstance(view) }.getOrNull()
    private var pencil = false
    private var vib: Vibrator? = null
    private var travel = 0f
    private var lastBuzz = 0L
    private var last: Offset? = null
    private var lastT = 0L
    private val step = 4f * ctx.resources.displayMetrics.density
    private var prims = false
    var predicted: Offset? = null; private set

    fun begin(scope: AwaitPointerEventScope, down: PointerInputChange): Offset {
        scope.currentEvent.motionEvent?.let { runCatching { predictor?.record(it) } }
        pencil = PenPrefs.pencil(ctx)
        if (pencil && vib == null) {
            vib = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
            prims = vib?.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK) == true
        }
        travel = 0f; last = down.position; lastT = down.uptimeMillis; predicted = null
        return down.position
    }

    /** All new points for this change (historical + current), jitter < 0.25px dropped. */
    fun move(scope: AwaitPointerEventScope, c: PointerInputChange): List<Offset> {
        val out = ArrayList<Offset>(c.historical.size + 1)
        c.historical.forEach { add(out, it.position, it.uptimeMillis) }
        add(out, c.position, c.uptimeMillis)
        val me = scope.currentEvent.motionEvent
        predicted = null
        if (me != null && predictor != null) runCatching {
            predictor.record(me)
            val idx = (0 until me.pointerCount).minByOrNull { (Offset(me.getX(it), me.getY(it)) - c.position).getDistanceSquared() } ?: 0
            val delta = c.position - Offset(me.getX(idx), me.getY(idx))
            predictor.predict()?.let { p ->
                val pi = if (p.pointerCount > idx) idx else 0
                val q = Offset(p.getX(pi), p.getY(pi)) + delta
                p.recycle()
                if ((q - c.position).getDistance() < 120f) predicted = q
            }
        }
        return out
    }

    fun end() { predicted = null; last = null }

    private fun add(out: MutableList<Offset>, p: Offset, t: Long) {
        val l = last
        if (l != null && (p - l).getDistance() < 0.25f) return
        if (l != null && pencil) grain((p - l).getDistance(), (t - lastT).coerceAtLeast(1))
        out.add(p); last = p; lastT = t
    }

    private fun grain(d: Float, dt: Long) {
        travel += d
        if (travel < step) return
        travel = 0f
        val now = SystemClock.uptimeMillis()
        if (now - lastBuzz < 14) return
        lastBuzz = now
        val speed = d / dt // px per ms
        val v = vib ?: return
        runCatching {
            if (prims) {
                val s = (0.12f + speed * 0.08f).coerceIn(0.1f, 0.45f)
                v.vibrate(VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, s).compose())
            } else if (v.hasAmplitudeControl()) v.vibrate(VibrationEffect.createOneShot(3, (30 + speed * 20).toInt().coerceIn(20, 90)))
        }
    }
}
