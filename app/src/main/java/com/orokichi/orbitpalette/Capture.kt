package com.orokichi.orbitpalette

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.nio.ByteBuffer

object FrameHub {
    @Volatile var latest: Bitmap? = null
    private var spare: Bitmap? = null
    private var tmp: ByteBuffer? = null
    var contentWidth = 0
    val version = MutableStateFlow(0L)

    fun push(img: Image) {
        val p = img.planes[0]
        val rw = p.rowStride / p.pixelStride
        val h = img.height
        var b = spare
        if (b == null || b.width != rw || b.height != h) b = Bitmap.createBitmap(rw, h, Bitmap.Config.ARGB_8888)
        val buf = p.buffer; buf.rewind()
        if (buf.remaining() >= b.byteCount) b.copyPixelsFromBuffer(buf) else {
            var t = tmp
            if (t == null || t.capacity() != b.byteCount) { t = ByteBuffer.allocateDirect(b.byteCount); tmp = t }
            t!!.clear(); t.put(buf); t.rewind(); b.copyPixelsFromBuffer(t)
        }
        spare = latest; latest = b; contentWidth = img.width
        version.value = version.value + 1
    }

    fun snapshot(): Bitmap? = latest?.let { Bitmap.createBitmap(it, 0, 0, contentWidth.coerceAtMost(it.width), it.height) }

    fun setStatic(b: Bitmap) { latest = b; contentWidth = b.width; version.value = version.value + 1 }

    fun clear() { latest = null; spare = null; tmp = null }
}

class ProjectionActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val mode = intent.getStringExtra("mode") ?: "shot"
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            startForegroundService(Intent(this, CaptureService::class.java)
                .putExtra("mode", mode).putExtra("code", r.resultCode).putExtra("data", r.data))
        } else OverlayService.instance?.onCaptureFailed()
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null)
            launcher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }
}

class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val thread = HandlerThread("capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private var mode = "shot"
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || projection != null) return START_NOT_STICKY
        mode = intent.getStringExtra("mode") ?: "shot"
        Notif.ensure(this)
        val n = NotificationCompat.Builder(this, Notif.CH).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (mode == "loupe") "TouchView 実行中" else "ScreenShot").setOngoing(true).build()
        ServiceCompat.startForeground(this, 2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val data = IntentCompat.getParcelableExtra(intent, "data", Intent::class.java)
        if (data == null) { fail(); return START_NOT_STICKY }
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent.getIntExtra("code", 0), data)
        if (mp == null) { fail(); return START_NOT_STICKY }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, handler)
        val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val w = b.width(); val h = b.height()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ ir -> runCatching { ir.acquireLatestImage()?.use { FrameHub.push(it) } } }, handler)
        display = mp.createVirtualDisplay("orbit", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler)

        if (mode == "loupe") main.post { OverlayService.instance?.onLoupeStart() }
        else handler.postDelayed({
            val bmp = FrameHub.snapshot()
            if (mode == "cb") main.post { OverlayService.instance?.deliverCapture(bmp) }
            else if (bmp != null) {
                val f = File(cacheDir, "shot_${System.currentTimeMillis()}.png")
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                main.post { OverlayService.instance?.onShotDone(f.absolutePath) }
            } else main.post { OverlayService.instance?.onCaptureFailed() }
            main.post { stopSelf() }
        }, 700)
        return START_NOT_STICKY
    }

    private fun fail() { OverlayService.instance?.onCaptureFailed(); stopSelf() }

    override fun onDestroy() {
        if (!stopped) {
            stopped = true
            runCatching { display?.release() }
            runCatching { reader?.close() }
            runCatching { projection?.stop() }
            thread.quitSafely()
            FrameHub.clear()
            if (mode == "loupe") OverlayService.instance?.onLoupeEnded()
        }
        super.onDestroy()
    }
}
