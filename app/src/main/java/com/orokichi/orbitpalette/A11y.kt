package com.orokichi.orbitpalette

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent

/** Takes screenshots without the per-shot MediaProjection consent (enable once in Accessibility settings). */
class OrbitA11yService : AccessibilityService() {
    companion object {
        var instance: OrbitA11yService? = null
        fun enabled(ctx: Context): Boolean =
            instance != null || (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .contains(ctx.packageName + "/")
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
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
