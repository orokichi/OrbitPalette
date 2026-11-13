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
    private var lockView: View? = null
    private var armed = false
    private var lastTap = 0L
    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) arm()
                else -> disarm()
            }
        }
    }

    private fun arm() {
        armed = true; lastTap = 0L
        if (lockView != null) return
        val v = object : View(this) {
            override fun onTouchEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) onOutsideTap()
                return false
            }
        }
        val p = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { getSystemService(WindowManager::class.java).addView(v, p); lockView = v }
    }

    private fun disarm() {
        armed = false
        lockView?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }; lockView = null
    }

    /** Only the first gesture after waking counts, so PIN typing never triggers it. */
    private fun onOutsideTap() {
        if (!armed) return
        val now = SystemClock.uptimeMillis()
        when {
            lastTap == 0L -> lastTap = now
            now - lastTap <= 350 -> {
                armed = false
                startActivity(Intent(this, LockNoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
            else -> armed = false
        }
    }

    override fun onServiceConnected() {
        instance = this
        ContextCompat.registerReceiver(this, rx, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_EXPORTED)
        if (getSystemService(PowerManager::class.java).isInteractive && getSystemService(KeyguardManager::class.java).isKeyguardLocked) arm()
    }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; disarm(); runCatching { unregisterReceiver(rx) }; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; disarm(); super.onDestroy() }
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
