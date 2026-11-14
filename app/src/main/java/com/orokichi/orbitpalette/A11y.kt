package com.orokichi.orbitpalette

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat

/** Takes screenshots without the per-shot MediaProjection consent (enable once in Accessibility settings). */
class OrbitA11yService : AccessibilityService() {
    companion object {
        var instance: OrbitA11yService? = null
        fun enabled(ctx: Context): Boolean =
            instance != null || (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .contains(ctx.packageName + "/")
    }

    // ---- Lock screen double-tap → LockNoteActivity ----
    // A 1x1 overlay watches every touch-down (ACTION_OUTSIDE) while the screen is on; whether it counts
    // is decided at tap time (keyguard really locked, note not already open). A 3rd quick tap means
    // PIN typing: cancel and ignore taps until the screen turns off.
    private var lockView: View? = null
    private var lastTap = 0L
    private var count = 0
    private var suppressed = false
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private val fire = Runnable {
        count = 0
        if (locked() && !suppressed && !LockNoteActivity.visible)
            startActivity(Intent(this, LockNoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }
    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> { suppressed = false; count = 0; attach() }
                Intent.ACTION_SCREEN_OFF -> { suppressed = false; count = 0; h.removeCallbacks(fire) }
                else -> { count = 0; h.removeCallbacks(fire) } // USER_PRESENT
            }
        }
    }

    private fun locked() = getSystemService(PowerManager::class.java).isInteractive &&
        getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun attach() {
        if (lockView != null) return
        val v = object : View(this) {
            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { if (locked()) onOutsideTap() else onUnlockedTap(e.x == 0f && e.y == 0f) }
                return false
            }
        }
        val p = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { getSystemService(WindowManager::class.java).addView(v, p); lockView = v }
    }

    private fun detach() {
        h.removeCallbacks(fire); removeCatcher()
        lockView?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }; lockView = null
    }

    private fun onOutsideTap() {
        if (suppressed || LockNoteActivity.visible || !locked()) { count = 0; h.removeCallbacks(fire); return }
        val now = SystemClock.uptimeMillis()
        count = if (now - lastTap <= 320) count + 1 else 1
        lastTap = now
        when {
            count == 2 -> h.postDelayed(fire, 330)
            count >= 3 -> { h.removeCallbacks(fire); count = 0; suppressed = true }
        }
    }

    // ---- Unlocked: tap, then tap again and HOLD the 2nd tap for 2 s → palette ----
    // Outside-touch events only report touch-down, so after a foreign tap we briefly put a full-screen
    // catcher on top. If the next touch lands within 320 ms we own it: holding still for 2 s opens the
    // palette; anything else is replayed to the app underneath with dispatchGesture.
    private var catcher: HoldCatcher? = null
    private var replayUntil = 0L
    private val disarm = Runnable { catcher?.let { if (!it.active) removeCatcher() } }

    private fun imeVisible() = runCatching { windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)

    private fun onUnlockedTap(foreign: Boolean) {
        if (!foreign || catcher != null || SystemClock.uptimeMillis() < replayUntil) return
        if (OverlayService.instance == null || !UiPrefs.doubleTap(this) || imeVisible()) return
        val v = HoldCatcher(this)
        val p = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (runCatching { getSystemService(WindowManager::class.java).addView(v, p) }.isSuccess) {
            catcher = v; h.postDelayed(disarm, 320)
        }
    }

    private fun removeCatcher() {
        h.removeCallbacks(disarm)
        catcher?.let { it.stop(); runCatching { getSystemService(WindowManager::class.java).removeView(it) } }; catcher = null
    }

    internal fun onHoldFired() {
        removeCatcher()
        runCatching { getSystemService(android.os.Vibrator::class.java).vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_HEAVY_CLICK)) }
        OverlayService.instance?.openPaletteFromTap()
    }

    internal fun onCatcherTouchStart() { h.removeCallbacks(disarm) }

    /** Gesture was not a 2-second hold: hand it back to whatever is underneath. */
    internal fun replay(xs: FloatArray, ys: FloatArray, n: Int, durationMs: Long) {
        removeCatcher()
        if (n == 0) return
        val path = android.graphics.Path().apply { moveTo(xs[0], ys[0]); for (i in 1 until n) lineTo(xs[i], ys[i]) }
        val d = durationMs.coerceIn(1L, 10_000L)
        replayUntil = SystemClock.uptimeMillis() + d + 250
        h.postDelayed({
            runCatching {
                dispatchGesture(android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, d)).build(), null, null)
            }
        }, 16)
    }

    override fun onServiceConnected() {
        instance = this
        OverlayService.instance?.onUiPrefsChanged()
        ContextCompat.registerReceiver(this, rx, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_EXPORTED)
        attach()
    }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; detach(); runCatching { unregisterReceiver(rx) }; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; detach(); super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun shoot(cb: (Bitmap?) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hb = r.hardwareBuffer
                val b = runCatching { Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                hb.close(); cb(b)
            }
            override fun onFailure(errorCode: Int) { cb(null) }
        })
    }
}

/** Full-screen catcher for the second touch; draws a ring that fills while you hold. */
class HoldCatcher(private val svc: OrbitA11yService) : View(svc) {
    var active = false; private set
    private val xs = FloatArray(512); private val ys = FloatArray(512); private var n = 0
    private var downAt = 0L; private var moved = false; private var fired = false
    private val slop = 24 * resources.displayMetrics.density
    private val hold = Runnable { if (active && !moved) { fired = true; svc.onHoldFired() } }
    private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 6 * resources.displayMetrics.density
        strokeCap = android.graphics.Paint.Cap.ROUND; color = 0xFF7C6BFF.toInt()
    }
    private val track = android.graphics.Paint(ring).apply { color = 0x337C6BFF }

    fun stop() { active = false; removeCallbacks(hold) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = true; downAt = e.eventTime; n = 0; moved = false
                add(e.rawX, e.rawY); svc.onCatcherTouchStart(); postDelayed(hold, 2000); invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> { moved = true; removeCallbacks(hold) }
            MotionEvent.ACTION_MOVE -> {
                add(e.rawX, e.rawY)
                if (!moved && kotlin.math.hypot(e.rawX - xs[0], e.rawY - ys[0]) > slop) { moved = true; removeCallbacks(hold) }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                if (!fired) { add(e.rawX, e.rawY); active = false; svc.replay(xs, ys, n, e.eventTime - downAt) }
            }
        }
        return true
    }

    private fun add(x: Float, y: Float) { if (n < xs.size) { xs[n] = x; ys[n] = y; n++ } }

    override fun onDraw(c: android.graphics.Canvas) {
        if (!active || moved || n == 0) return
        val t = SystemClock.uptimeMillis() - downAt
        if (t < 250) { postInvalidateOnAnimation(); return }
        val loc = IntArray(2); getLocationOnScreen(loc)
        val cx = xs[0] - loc[0]; val cy = ys[0] - loc[1]; val r = 40 * resources.displayMetrics.density
        val o = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
        c.drawOval(o, track)
        c.drawArc(o, -90f, 360f * ((t - 250).coerceAtMost(1750) / 1750f), false, ring)
        postInvalidateOnAnimation()
    }
}
