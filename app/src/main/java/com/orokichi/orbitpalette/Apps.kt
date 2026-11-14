@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.orokichi.orbitpalette

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.FilterNone
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class AppEntry(val label: String, val cmp: ComponentName, val icon: ImageBitmap, val kind: Int)

object Apps {
    private var cache: List<AppEntry>? = null
    // kinds
    const val VIDEO = 1; const val AUDIO = 2; const val SOCIAL = 3; const val NEWS = 4; const val MAPS = 5
    const val IMAGE = 6; const val GAME = 7; const val PROD = 8; const val TOOL = 9; const val OTHER = 0

    suspend fun load(ctx: Context): List<AppEntry> = cache ?: withContext(Dispatchers.Default) {
        val pm = ctx.packageManager
        val px = (48 * ctx.resources.displayMetrics.density).toInt()
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { ri ->
                val ai = ri.activityInfo
                AppEntry(ri.loadLabel(pm).toString(), ComponentName(ai.packageName, ai.name),
                    runCatching { ri.loadIcon(pm).toBitmap(px, px).asImageBitmap() }.getOrElse { android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap() },
                    kindOf(ai.applicationInfo, ai.packageName))
            }.sortedBy { it.label.lowercase() }
            .also { cache = it }
    }

    private fun kindOf(ai: ApplicationInfo, pkg: String): Int {
        val p = pkg.lowercase()
        return when {
            Regex("calc|clock|alarm|timer|keep|note|todo|task|calendar").containsMatchIn(p) -> TOOL
            Regex("youtube|netflix|primevideo|avod|tver|abema|hulu|disney|twitch|video|\\.tv").containsMatchIn(p) -> VIDEO
            Regex("music|spotify|podcast|radio|audio|soundcloud").containsMatchIn(p) -> AUDIO
            Regex("line|messag|twitter|instagram|discord|threads|facebook|whatsapp|telegram|slack|talk|chat|tiktok").containsMatchIn(p) -> SOCIAL
            Regex("map|navi|transit").containsMatchIn(p) -> MAPS
            Regex("chrome|browser|firefox|news|reader").containsMatchIn(p) -> NEWS
            Regex("photo|camera|gallery|lens").containsMatchIn(p) -> IMAGE
            else -> when (ai.category) {
                ApplicationInfo.CATEGORY_VIDEO -> VIDEO
                ApplicationInfo.CATEGORY_AUDIO -> AUDIO
                ApplicationInfo.CATEGORY_SOCIAL -> SOCIAL
                ApplicationInfo.CATEGORY_NEWS -> NEWS
                ApplicationInfo.CATEGORY_MAPS -> MAPS
                ApplicationInfo.CATEGORY_IMAGE -> IMAGE
                ApplicationInfo.CATEGORY_GAME -> GAME
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> PROD
                else -> OTHER
            }
        }
    }

    /** Apps that suit a window of this shape/size, best first. */
    fun recommend(all: List<AppEntry>, r: Rect, screen: Rect): List<AppEntry> {
        val ratio = r.width().toFloat() / r.height().coerceAtLeast(1)
        val frac = r.width().toFloat() * r.height() / (screen.width().toFloat() * screen.height()).coerceAtLeast(1f)
        val score = IntArray(10)
        when {
            ratio > 1.25f -> { score[VIDEO] += 4; score[GAME] += 3; score[MAPS] += 2; score[IMAGE] += 1 }
            ratio < 0.8f -> { score[SOCIAL] += 4; score[AUDIO] += 2; score[NEWS] += 3; score[PROD] += 1 }
            else -> { score[PROD] += 3; score[IMAGE] += 2; score[SOCIAL] += 1; score[TOOL] += 1 }
        }
        if (frac < 0.15f) { score[TOOL] += 4; score[AUDIO] += 2; score[VIDEO] -= 1; score[GAME] -= 2 }
        else if (frac > 0.45f) { score[VIDEO] += 1; score[NEWS] += 1; score[TOOL] -= 2 }
        return all.filter { it.kind != OTHER && score[it.kind] > 1 }.sortedByDescending { score[it.kind] }.take(4)
    }

    fun freeformEnabled(ctx: Context) =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
            Settings.Global.getInt(ctx.contentResolver, "enable_freeform_support", 0) == 1

    fun launch(ctx: Context, cmp: ComponentName, r: Rect): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cmp)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val o = ActivityOptions.makeBasic().setLaunchBounds(r)
        // Hidden API: request freeform windowing mode (5) so the bounds are honoured.
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28) org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityOptions;")
            ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(o, 5)
        }
        return runCatching { ctx.startActivity(i, o.toBundle()); true }.getOrDefault(false)
    }
}

@Composable
fun AppsPicker(rect: Rect, screen: Rect, onPick: (AppEntry) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val all by produceState<List<AppEntry>?>(null) { value = Apps.load(ctx) }
    val list = all
    if (list == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    val rec = remember(list) { Apps.recommend(list, rect, screen) }
    LazyVerticalGrid(GridCells.Adaptive(72.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
        if (rec.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("このサイズにおすすめ", style = MaterialTheme.typography.labelLarge, color = cs.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(6.dp))
            }
            items(rec, key = { "r" + it.cmp.flattenToString() }) { AppCell(it, true, onPick) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("すべてのアプリ", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(6.dp))
            }
        }
        items(list, key = { it.cmp.flattenToString() }) { AppCell(it, false, onPick) }
    }
}

@Composable
private fun AppCell(a: AppEntry, hl: Boolean, onPick: (AppEntry) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.padding(3.dp).clip(RoundedCornerShape(16.dp)).background(if (hl) cs.secondaryContainer else Color.Transparent)
            .clickable { onPick(a) }.padding(vertical = 8.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Image(a.icon, a.label, Modifier.size(44.dp))
        Text(a.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** Full-screen layer: draw a rough box freehand → it snaps to a clean rectangle (screen coordinates). */
@Composable
fun RectDrawOverlay(hint: String, onRect: (Rect) -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val pts = remember { mutableStateListOf<Offset>() }
    var snapped by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val ctx = LocalContext.current
    val minPx = 72 * ctx.resources.displayMetrics.density
    LaunchedEffect(snapped) {
        val s = snapped ?: return@LaunchedEffect
        delay(260)
        onRect(Rect((s.left + origin.x).toInt(), (s.top + origin.y).toInt(), (s.right + origin.x).toInt(), (s.bottom + origin.y).toInt()))
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.localToScreen(Offset.Zero) }
        .background(Color.Black.copy(alpha = 0.28f))
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(); down.consume()
                if (snapped != null) return@awaitEachGesture
                pts.clear(); pts.add(down.position)
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    c.historical.forEach { pts.add(it.position) }; pts.add(c.position); c.consume()
                }
                if (pts.size > 4) {
                    val r = androidx.compose.ui.geometry.Rect(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
                    if (r.width >= minPx && r.height >= minPx) snapped = r
                }
                pts.clear()
            }
        }
        .drawBehind {
            if (pts.size > 1) drawIntoCanvas { it.nativeCanvas.drawPath(smoothPath(pts.toList()), strokePaint(cs.primary.toArgb(), 4.dp.toPx())) }
            snapped?.let {
                drawRoundRect(cs.primary.copy(alpha = 0.18f), it.topLeft, it.size, CornerRadius(24.dp.toPx()))
                drawRoundRect(cs.primary, it.topLeft, it.size, CornerRadius(24.dp.toPx()), style = Stroke(3.dp.toPx()))
            }
        }
    ) {
        Text(hint, color = Color.White, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(horizontal = 20.dp, vertical = 10.dp))
        FilledIconButton(onClick = onCancel, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)) { Icon(Icons.Rounded.Close, "キャンセル") }
    }
}

/**
 * Galaxy-style pop-up bar pinned above a freeform app launched from Apps.
 * With Shizuku, the real app window follows the bar live (IActivityTaskManager.resizeTask) and the corner grip
 * resizes it. Without Shizuku, Android gives us no control of another app's window, so we fall back to relaunching.
 */
class AppFrame(private val svc: OverlayService, val app: AppEntry, var bounds: Rect) {
    private val wm = svc.wm
    private fun px(v: Float) = svc.dp(v).toInt()
    var minimized by mutableStateOf(false)
    var maximized by mutableStateOf(false)
    private var beforeMax = Rect(bounds)
    private val barH get() = px(30f)
    private val gripS get() = px(40f)
    private val params = svc.overlayParams(0, 0, android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
    private val gripParams = svc.overlayParams(0, 0, android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
    private var view: android.view.View? = null
    private var grip: android.view.View? = null
    private var taskId: Int? = null
    private var lastPush = 0L
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    val live get() = TaskCtl.granted()

    fun show() {
        place(); view = svc.composeView { AppBar(this) }; wm.addView(view, params)
        grip = svc.composeView { AppGrip(this) }; wm.addView(grip, gripParams); placeGrip()
        findTask()
    }

    private fun findTask() {
        if (!live) return
        repeat(4) { i -> h.postDelayed({ if (taskId == null) taskId = TaskCtl.taskIdOf(app.cmp.packageName) }, 300L + i * 400L) }
    }

    private fun place() {
        if (minimized) { params.width = px(64f); params.height = px(64f); params.x = (bounds.right - px(64f)).coerceAtLeast(0); params.y = (bounds.top - barH).coerceAtLeast(0) }
        else {
            params.width = bounds.width(); params.height = barH
            params.x = bounds.left
            params.y = (bounds.top - barH).coerceAtLeast(0)
        }
        view?.let { runCatching { wm.updateViewLayout(it, params) } }
        placeGrip()
    }

    private fun placeGrip() {
        val g = grip ?: return
        g.visibility = if (minimized || maximized) android.view.View.GONE else android.view.View.VISIBLE
        gripParams.width = gripS; gripParams.height = gripS
        gripParams.x = bounds.right - gripS / 2; gripParams.y = bounds.bottom - gripS / 2
        runCatching { wm.updateViewLayout(g, gripParams) }
    }

    /** Push the bounds to the real app window (throttled while dragging). */
    private fun push(force: Boolean = false) {
        val id = taskId ?: TaskCtl.taskIdOf(app.cmp.packageName)?.also { taskId = it } ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && now - lastPush < 24) return
        lastPush = now
        TaskCtl.resize(id, Rect(bounds))
    }

    fun moveBy(dx: Float, dy: Float) {
        if (minimized) { params.x += dx.toInt(); params.y += dy.toInt(); view?.let { runCatching { wm.updateViewLayout(it, params) } }; return }
        val s = svc.screen()
        val nx = (bounds.left + dx.toInt()).coerceIn(-bounds.width() + px(60f), s.width() - px(60f))
        val ny = (bounds.top + dy.toInt()).coerceIn(barH, s.height() - px(60f))
        bounds.offsetTo(nx, ny); maximized = false
        place()
        if (live) push()
    }

    fun moveDone() {
        if (minimized) return
        if (live) push(true) else relaunch()
    }

    fun resizeBy(dx: Float, dy: Float) {
        val s = svc.screen()
        bounds.right = (bounds.right + dx.toInt()).coerceIn(bounds.left + px(160f), s.width())
        bounds.bottom = (bounds.bottom + dy.toInt()).coerceIn(bounds.top + px(160f), s.height())
        maximized = false; place()
        if (live) push()
    }

    fun resizeDone() { if (live) push(true) else relaunch() }

    fun relaunch() { Apps.launch(svc, app.cmp, bounds); place(); if (live) h.postDelayed({ push(true) }, 350) }

    fun resizeTo(r: Rect) { bounds = Rect(r); maximized = false; minimized = false; if (live) { push(true); place() } else relaunch() }

    fun toggleMax() {
        val s = svc.screen()
        if (!maximized) { beforeMax = Rect(bounds); bounds = Rect(0, px(28f) + barH, s.width(), s.height()); maximized = true }
        else { bounds = Rect(beforeMax); maximized = false }
        if (live) { push(true); place() } else relaunch()
    }

    fun minimize() {
        minimized = true
        OrbitA11yService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        place()
    }

    fun restore() { minimized = false; relaunch() }

    fun close() {
        val id = taskId
        remove()
        if (id != null && TaskCtl.remove(id)) return
        val a = OrbitA11yService.instance ?: return
        repeat(3) { i -> h.postDelayed({ a.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) }, 80L + i * 150L) }
    }

    fun remove() {
        view?.let { runCatching { wm.removeView(it) } }; view = null
        grip?.let { runCatching { wm.removeView(it) } }; grip = null
        svc.onAppFrameClosed(this)
    }
}

/** Corner grip at the app window's bottom-right: drag to resize (live with Shizuku). */
@Composable
fun AppGrip(f: AppFrame) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().rawDragFull(onEnd = { _, _ -> f.resizeDone() }) { dx, dy, _, _ -> f.resizeBy(dx, dy) }) {
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(cs.primary.copy(alpha = 0.25f), size.width / 2f)
        drawArc(cs.primary, 270f, 180f, false, Offset(c.x - size.width * 0.3f, c.y - size.height * 0.3f), androidx.compose.ui.geometry.Size(size.width * 0.6f, size.height * 0.6f),
            style = Stroke(3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

@Composable
fun AppBar(f: AppFrame) {
    val cs = MaterialTheme.colorScheme
    if (f.minimized) {
        MiniIcon(onTap = { f.restore() }, onMove = { dx, dy -> f.moveBy(dx, dy) }, onClose = { f.close() }) {
            androidx.compose.material3.Surface(shape = CircleShape, color = cs.surfaceContainerHigh, shadowElevation = 6.dp,
                border = androidx.compose.foundation.BorderStroke(2.dp, cs.primary), modifier = Modifier.fillMaxSize()) {
                Box(contentAlignment = Alignment.Center) { Image(f.app.icon, f.app.label, Modifier.size(34.dp)) }
            }
        }
        return
    }
    var menu by remember { mutableStateOf(false) }
    androidx.compose.material3.Surface(shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), color = cs.surfaceContainerHigh, shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(2.dp, cs.primary), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().rawDragFull(onTap = { menu = !menu }, onEnd = { _, _ -> f.moveDone() }) { dx, dy, _, _ -> f.moveBy(dx, dy) })
            Image(f.app.icon, null, Modifier.align(Alignment.CenterStart).padding(start = 10.dp).size(18.dp))
            Box(Modifier.align(Alignment.Center).size(40.dp, 5.dp).background(cs.primary, CircleShape))
            if (menu) androidx.compose.foundation.layout.Row(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
                androidx.compose.material3.IconButton(onClick = { menu = false; f.minimize() }, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.Remove, "最小化", Modifier.size(17.dp)) }
                Box(Modifier.size(30.dp).clip(CircleShape).combinedClickable(onClick = { menu = false; f.toggleMax() },
                    onLongClick = { menu = false; OverlayService.instance?.drawRect("新しいサイズを▢で描く") { r -> f.resizeTo(r) } }), contentAlignment = Alignment.Center) {
                    Icon(if (f.maximized) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull, "全画面（長押しでサイズを描く）", Modifier.size(17.dp))
                }
                androidx.compose.material3.IconButton(onClick = { f.close() }, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.Close, "閉じる", Modifier.size(17.dp)) }
            }
        }
    }
}
