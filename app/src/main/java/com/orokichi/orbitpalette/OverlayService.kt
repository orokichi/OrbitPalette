package com.orokichi.orbitpalette

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class PaletteState {
    var center by mutableStateOf(Offset.Zero)
    var highlighted by mutableStateOf<Tool?>(null)
    var radius = 0f
    var inner = 0f

    fun dist(x: Float, y: Float) = hypot(x - center.x, y - center.y)

    fun track(x: Float, y: Float) {
        val d = dist(x, y)
        if (d < inner || d > radius * 2.4f) { highlighted = null; return }
        val a = Math.toDegrees(atan2((y - center.y).toDouble(), (x - center.x).toDouble())).toFloat()
        highlighted = Tool.entries.minBy { t -> val diff = abs(((a - t.angleDeg) % 360f + 540f) % 360f - 180f); diff }
    }
}

class OverlayService : LifecycleService(), SavedStateRegistryOwner {
    companion object {
        const val ACTION_STOP = "stop"
        var instance: OverlayService? = null
    }

    private val ssrc = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = ssrc.savedStateRegistry

    lateinit var wm: WindowManager
    val main = Handler(Looper.getMainLooper())
    val palette = PaletteState()
    val canvasState by lazy { CanvasState(this) }
    private val prefs by lazy { getSharedPreferences("overlay", MODE_PRIVATE) }

    private var bubble: View? = null
    private lateinit var bubbleParams: LP
    private var paletteView: View? = null
    private var loupeView: View? = null
    private var thumbView: View? = null
    private val windows = mutableMapOf<Tool, FloatingWindow>()

    fun dp(v: Float) = v * resources.displayMetrics.density
    fun screen(): Rect = wm.maximumWindowMetrics.bounds
    fun windowCount() = windows.size

    fun overlayParams(w: Int, h: Int, flags: Int) = LP(
        w, h, LP.TYPE_APPLICATION_OVERLAY,
        flags or LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = LP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    fun composeView(content: @Composable () -> Unit) = ComposeView(this).apply {
        setViewTreeLifecycleOwner(this@OverlayService)
        setViewTreeSavedStateRegistryOwner(this@OverlayService)
        setContent { OrbitTheme(content) }
    }

    override fun onCreate() {
        ssrc.performRestore(null)
        super.onCreate()
        instance = this
        wm = getSystemService(WindowManager::class.java)
        Notif.ensure(this)
        val stop = PendingIntent.getService(this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, Notif.CH)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("Android AirCommand 表示中").setOngoing(true)
            .addAction(0, "停止", stop).build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        listOfNotNull(bubble, paletteView, loupeView, thumbView, rectView).forEach { runCatching { wm.removeView(it) } }
        windows.values.toList().forEach { it.close() }
        appFrames.toList().forEach { it.remove() }
        stopService(Intent(this, CaptureService::class.java))
        instance = null
        super.onDestroy()
    }

    // ---------- Bubble ----------
    private var bubbleSize = 0

    /** Floating button only as a fallback when accessibility (double-tap to open) is off. */
    fun showBubble() {
        if (bubble != null) return
        if (OrbitA11yService.enabled(this) && !UiPrefs.bubble(this)) return
        bubbleSize = dp(UiPrefs.bubbleDp(this)).toInt()
        val s = screen()
        bubbleParams = overlayParams(bubbleSize, bubbleSize, LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCH_MODAL).apply {
            x = prefs.getInt("bx", s.width() - bubbleSize - dp(8f).toInt())
            y = prefs.getInt("by", s.height() / 3)
        }
        val v = composeView { Bubble(UiPrefs.bubbleAlpha(this)) { onBubbleTouch(it) } }
        wm.addView(v, bubbleParams); bubble = v
    }

    fun onUiPrefsChanged() { main.post { hideBubble(); if (paletteView == null && rectView == null && snoteView == null && loupeView == null) showBubble() } }

    fun hideBubble() { bubble?.let { runCatching { wm.removeView(it) } }; bubble = null }

    private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0
    private var moved = false; private var dragMode = false
    private val longPress = Runnable {
        if (!moved) { dragMode = true; hidePalette(); vibrate() }
    }

    private fun vibrate() = runCatching {
        getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
    }

    private fun onBubbleTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; startX = bubbleParams.x; startY = bubbleParams.y
                moved = false; dragMode = false
                main.postDelayed(longPress, 450)
                openPalette(); palette.track(e.rawX, e.rawY)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX; val dy = e.rawY - downY
                if (!moved && hypot(dx, dy) > dp(10f)) { moved = true; if (!dragMode) main.removeCallbacks(longPress) }
                if (dragMode) {
                    bubbleParams.x = startX + dx.toInt(); bubbleParams.y = startY + dy.toInt()
                    bubble?.let { runCatching { wm.updateViewLayout(it, bubbleParams) } }
                } else palette.track(e.rawX, e.rawY)
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPress)
                when {
                    dragMode -> {
                        val s = screen()
                        if (UiPrefs.bubbleSnap(this)) bubbleParams.x = if (bubbleParams.x + bubbleSize / 2 < s.width() / 2) dp(4f).toInt() else s.width() - bubbleSize - dp(4f).toInt()
                        else bubbleParams.x = bubbleParams.x.coerceIn(0, s.width() - bubbleSize)
                        bubbleParams.y = bubbleParams.y.coerceIn(dp(24f).toInt(), s.height() - bubbleSize - dp(24f).toInt())
                        bubble?.let { runCatching { wm.updateViewLayout(it, bubbleParams) } }
                        prefs.edit().putInt("bx", bubbleParams.x).putInt("by", bubbleParams.y).apply()
                    }
                    moved -> palette.highlighted?.let { select(it) } ?: hidePalette()
                    else -> palette.highlighted = null // tap: palette stays open
                }
            }
            MotionEvent.ACTION_CANCEL -> { main.removeCallbacks(longPress); if (!dragMode) hidePalette() }
        }
    }

    // ---------- Palette ----------
    private fun openPalette() = openPaletteAt(bubbleParams.x + bubbleSize / 2f, bubbleParams.y + bubbleSize / 2f)

    /** Double-tap anywhere (accessibility) opens the palette in the middle of the screen. */
    fun openPaletteFromTap() {
        if (paletteView != null || rectView != null || snoteView != null || loupeView != null) return
        val s = screen(); openPaletteAt(s.width() / 2f, s.height() / 2f)
    }

    private fun openPaletteAt(cx: Float, cy: Float) {
        if (paletteView != null) return
        val s = screen()
        val r = dp(90f); val m = r + dp(48f)
        palette.center = Offset(cx.coerceIn(m, s.width() - m), cy.coerceIn(m, s.height() - m))
        palette.radius = r; palette.inner = dp(34f); palette.highlighted = null
        val p = overlayParams(LP.MATCH_PARENT, LP.MATCH_PARENT, LP.FLAG_NOT_FOCUSABLE).apply { x = 0; y = 0 }
        val v = composeView { PaletteOverlay(palette) { onPaletteTouch(it) } }
        wm.addView(v, p); paletteView = v
    }

    fun hidePalette() { paletteView?.let { runCatching { wm.removeView(it) } }; paletteView = null }

    private fun onPaletteTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> palette.track(e.rawX, e.rawY)
            MotionEvent.ACTION_UP -> palette.highlighted?.let { select(it) } ?: hidePalette()
            MotionEvent.ACTION_CANCEL -> hidePalette()
        }
    }

    private fun select(t: Tool) {
        hidePalette()
        when (t) {
            Tool.SCREENSHOT -> project("shot")
            Tool.TOUCHVIEW -> project("loupe")
            Tool.APPS -> drawRect("アプリを開く範囲を▢で囲む") { r -> openApps(r) }
            else -> openWindow(t)
        }
    }

    fun openNoteWith(t: String) {
        NoteStore.init(this)
        NoteStore.set(if (NoteStore.text.isBlank()) t else NoteStore.text.trimEnd() + "\n" + t)
        openWindow(Tool.NOTE)
    }

    private var captureCb: ((android.graphics.Bitmap?) -> Unit)? = null

    /** One screenshot: accessibility (no prompt) when enabled, else MediaProjection. */
    fun captureOnce(cb: (android.graphics.Bitmap?) -> Unit) {
        withA11y { a11y -> if (a11y != null) main.postDelayed({ a11y.shoot(cb) }, 300) else projectCb(cb) }
    }

    /**
     * Accessibility may be enabled but not (re)bound yet, e.g. after the process was killed overnight.
     * Wait for it instead of falling back to the MediaProjection consent dialog.
     */
    private fun withA11y(block: (OrbitA11yService?) -> Unit) {
        OrbitA11yService.instance?.let { block(it); return }
        if (!OrbitA11yService.enabled(this)) { block(null); return }
        val start = android.os.SystemClock.uptimeMillis()
        val poll = object : Runnable {
            override fun run() {
                val i = OrbitA11yService.instance
                if (i != null) block(i)
                else if (android.os.SystemClock.uptimeMillis() - start > 4000) {
                    android.widget.Toast.makeText(this@OverlayService, "ユーザー補助が停止中です。設定でオフ→オンしてください", android.widget.Toast.LENGTH_LONG).show()
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    block(null)
                } else main.postDelayed(this, 100)
            }
        }
        main.post(poll)
    }

    private fun projectCb(cb: (android.graphics.Bitmap?) -> Unit) {
        if (OrbitA11yService.enabled(this)) { cb(null); return }
        captureCb = cb
        main.postDelayed({
            startActivity(Intent(this, ProjectionActivity::class.java).putExtra("mode", "cb")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }, 120)
    }

    fun deliverCapture(b: android.graphics.Bitmap?) { val c = captureCb; captureCb = null; c?.invoke(b) }

    private fun project(mode: String) {
        hideBubble()
        if (!OrbitA11yService.enabled(this)) {
            main.postDelayed({
                startActivity(Intent(this, ProjectionActivity::class.java).putExtra("mode", mode)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }, 120)
            return
        }
        captureOnce { bmp ->
            if (bmp == null) { showBubble(); return@captureOnce }
            if (mode == "loupe") { FrameHub.setStatic(bmp); onLoupeStart(); return@captureOnce }
            Thread {
                val f = java.io.File(cacheDir, "shot_${System.currentTimeMillis()}.png")
                f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                main.post { onShotDone(f.absolutePath) }
            }.start()
        }
    }

    // ---------- ScreenNote ----------
    private var snoteView: View? = null

    fun startScreenNote() {
        val w = windows[Tool.CANVAS] ?: return
        if (snoteView != null) return
        if (!w.minimized) w.minimize()
        w.setHidden(true); hideBubble()
        captureOnce { bmp ->
            w.setHidden(false)
            if (bmp == null) { showBubble(); return@captureOnce }
            val v = composeView {
                ScreenNoteOverlay(bmp,
                    onAdd = { crop -> endScreenNote(); canvasState.addImage(crop); windows[Tool.CANVAS]?.let { if (it.minimized) it.restore() } },
                    onCancel = { endScreenNote(); windows[Tool.CANVAS]?.let { if (it.minimized) it.restore() } })
            }
            wm.addView(v, overlayParams(LP.MATCH_PARENT, LP.MATCH_PARENT, LP.FLAG_NOT_FOCUSABLE).apply { x = 0; y = 0 }); snoteView = v
            w.bringToFront()
        }
    }

    private fun endScreenNote() { snoteView?.let { runCatching { wm.removeView(it) } }; snoteView = null; showBubble() }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        main.postDelayed({
            val s = screen()
            if (bubble != null) {
                bubbleParams.x = bubbleParams.x.coerceIn(0, s.width() - bubbleSize); bubbleParams.y = bubbleParams.y.coerceIn(0, s.height() - bubbleSize)
                runCatching { wm.updateViewLayout(bubble, bubbleParams) }
            }
            windows.values.forEach { it.clamp() }
        }, 200)
    }

    // ---------- Draw a box (Apps / resize) ----------
    private var rectView: View? = null
    private val appFrames = mutableListOf<AppFrame>()
    fun onAppFrameClosed(f: AppFrame) { appFrames.remove(f) }

    fun drawRect(hint: String, onRect: (Rect) -> Unit) {
        if (rectView != null) return
        hidePalette(); hideBubble()
        val v = composeView { RectDrawOverlay(hint, { r -> endRect(); onRect(r) }, { endRect() }) }
        wm.addView(v, overlayParams(LP.MATCH_PARENT, LP.MATCH_PARENT, LP.FLAG_NOT_FOCUSABLE).apply { x = 0; y = 0 }); rectView = v
    }

    private fun endRect() { rectView?.let { runCatching { wm.removeView(it) } }; rectView = null; showBubble() }

    fun resizeByDrawing(w: FloatingWindow) {
        vibrate()
        windows.values.forEach { if (!it.minimized) it.minimize() }
        drawRect("新しいサイズを▢で描く") { r -> w.setBounds(r); w.bringToFront() }
    }

    private fun openApps(r: Rect) {
        windows[Tool.APPS]?.close()
        val s = screen()
        lateinit var w: FloatingWindow
        w = FloatingWindow(this, Tool.APPS, 300, 380, false) {
            AppsPicker(r, s) { a ->
                w.close()
                if (!Apps.freeformEnabled(this)) Toast.makeText(this, "フリーフォームが無効のため全画面で開きます（アプリの設定から有効化）", Toast.LENGTH_LONG).show()
                if (Apps.launch(this, a.cmp, r)) AppFrame(this, a, r).also { appFrames.add(it); it.show() }
            }
        }
        w.setBounds(r)
        windows[Tool.APPS] = w; w.show()
    }

    // ---------- Popup windows ----------
    private fun openWindow(t: Tool) {
        windows[t]?.let { if (it.minimized) it.restore(); return }
        val w = when (t) {
            Tool.CANVAS -> FloatingWindow(this, t, 330, 440, false) { CanvasTool(canvasState) }
            Tool.CALC -> FloatingWindow(this, t, 290, 420, false) { CalculatorTool() }
            else -> FloatingWindow(this, t, 300, 380, true) { NoteTool() }
        }
        windows[t] = w; w.show()
    }

    fun onWindowClosed(t: Tool) { windows.remove(t) }

    // ---------- Screenshot ----------
    fun onShotDone(path: String) {
        showBubble()
        thumbView?.let { runCatching { wm.removeView(it) } }
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 }) ?: return
        val s = screen()
        val w = dp(112f).toInt(); val h = (w * bmp.height.toFloat() / bmp.width).toInt() + dp(8f).toInt()
        val p = overlayParams(w, h, LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCH_MODAL).apply {
            x = dp(16f).toInt(); y = s.height() - h - dp(140f).toInt()
        }
        lateinit var v: View
        val dismiss = { runCatching { wm.removeView(v) }; if (thumbView === v) thumbView = null }
        v = composeView {
            ShotThumb(bmp,
                onOpen = {
                    dismiss()
                    startActivity(Intent(this, EditorActivity::class.java).putExtra("path", path).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                onSave = {
                    dismiss()
                    val ok = BitmapFactory.decodeFile(path)?.let { Gallery.save(this, it, "Screenshot") } ?: false
                    Toast.makeText(this, if (ok) "保存しました" else "保存に失敗しました", Toast.LENGTH_SHORT).show()
                },
                onClose = { dismiss() })
        }
        wm.addView(v, p); thumbView = v
        main.postDelayed({ if (thumbView === v) dismiss() }, 7000)
    }

    fun onCaptureFailed() { if (captureCb != null) deliverCapture(null) else showBubble() }

    // ---------- Touch view (loupe) ----------
    fun onLoupeStart() {
        if (loupeView != null) return
        val s = screen()
        val v = composeView { LoupeOverlay(onClose = { endLoupe() }) }
        wm.addView(v, overlayParams(LP.MATCH_PARENT, LP.MATCH_PARENT, LP.FLAG_NOT_FOCUSABLE).apply { x = 0; y = 0 }); loupeView = v
    }

    fun endLoupe() {
        stopService(Intent(this, CaptureService::class.java))
        onLoupeEnded()
    }

    fun onLoupeEnded() {
        loupeView?.let { runCatching { wm.removeView(it) } }; loupeView = null
        FrameHub.clear(); showBubble()
    }
}
