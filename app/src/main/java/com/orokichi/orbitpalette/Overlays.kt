package com.orokichi.orbitpalette

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import android.view.WindowManager.LayoutParams as LP
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ================= Bubble =================
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Bubble(onTouch: (MotionEvent) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().padding(4.dp).pointerInteropFilter { onTouch(it); true }) {
        Surface(shape = CircleShape, color = cs.primary, contentColor = cs.onPrimary, shadowElevation = 6.dp, modifier = Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Hub, null, Modifier.size(28.dp)) }
        }
    }
}

// ================= Radial palette =================
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PaletteOverlay(st: PaletteState, onTouch: (MotionEvent) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val prog = remember { Animatable(0f) }
    LaunchedEffect(Unit) { prog.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 380f)) }
    LaunchedEffect(st.highlighted) { if (st.highlighted != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    val sweep = 360f / Tool.entries.size
    val hlAngle by animateFloatAsState((st.highlighted?.angleDeg ?: 0f), spring(0.7f, Spring.StiffnessMedium), label = "a")

    Box(Modifier.fillMaxSize().pointerInteropFilter { onTouch(it); true }.drawBehind {
        val p = prog.value
        drawRect(Color.Black.copy(alpha = 0.22f * p.coerceIn(0f, 1f)))
        val outer = (st.radius + 46.dp.toPx()) * p
        drawCircle(cs.surfaceContainerHigh, outer, st.center)
        drawCircle(cs.outlineVariant, outer, st.center, style = Stroke(1.dp.toPx()))
        if (st.highlighted != null) drawArc(
            cs.primaryContainer, hlAngle - sweep / 2, sweep, true,
            topLeft = st.center - Offset(outer, outer), size = Size(outer * 2, outer * 2)
        )
    }) {
        Tool.entries.forEach { t ->
            val hl = st.highlighted == t
            val scale by animateFloatAsState(if (hl) 1.25f else 1f, spring(0.45f, 520f), label = "s")
            val corner by animateDpAsState(if (hl) 18.dp else 28.dp, spring(0.5f, 400f), label = "c")
            val bg by animateColorAsState(if (hl) cs.primary else cs.secondaryContainer, label = "b")
            val fg by animateColorAsState(if (hl) cs.onPrimary else cs.onSecondaryContainer, label = "f")
            val a = Math.toRadians(t.angleDeg.toDouble())
            val r = st.radius * prog.value
            val half = with(density) { 28.dp.toPx() }
            val x = st.center.x + (cos(a) * r).toFloat() - half
            val y = st.center.y + (sin(a) * r).toFloat() - half
            Box(
                Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.size(56.dp)
                    .graphicsLayer { scaleX = scale * prog.value.coerceAtMost(1.1f); scaleY = scaleX }
                    .background(bg, RoundedCornerShape(corner)),
                contentAlignment = Alignment.Center
            ) { Icon(t.icon, t.label, tint = fg) }
        }
        val hub = with(density) { 46.dp.toPx() }
        Surface(
            shape = CircleShape, color = cs.surfaceContainerHighest, shadowElevation = 2.dp,
            modifier = Modifier.offset { IntOffset((st.center.x - hub).roundToInt(), (st.center.y - hub).roundToInt()) }
                .size(92.dp).graphicsLayer { scaleX = prog.value; scaleY = prog.value }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(8.dp)) {
                val h = st.highlighted
                if (h == null) Icon(Icons.Rounded.Close, null, tint = cs.onSurfaceVariant)
                else Text(h.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cs.primary, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

// ================= Screenshot thumbnail =================
@Composable
fun ShotThumb(bmp: Bitmap, onOpen: () -> Unit, onSave: () -> Unit, onClose: () -> Unit) {
    val img = remember { bmp.asImageBitmap() }
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 8.dp, color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxSize().padding(4.dp)) {
        Box {
            Image(img, "スクリーンショット", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).clickable(onClick = onOpen))
            Row(Modifier.align(Alignment.BottomCenter).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallFloatingActionButton(onClick = onSave, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Download, "保存", Modifier.size(18.dp)) }
                SmallFloatingActionButton(onClick = onClose, modifier = Modifier.size(36.dp), containerColor = cs.surfaceContainerHighest) { Icon(Icons.Rounded.Close, "閉じる", Modifier.size(18.dp)) }
            }
        }
    }
}

// ================= Touch view (magnifier) =================
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LoupeOverlay(onClose: () -> Unit) {
    val ver by FrameHub.version.collectAsState()
    var finger by remember { mutableStateOf<Offset?>(null) }
    var zoom by remember { mutableFloatStateOf(2.5f) }
    val cs = MaterialTheme.colorScheme
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG) }
    val path = remember { android.graphics.Path() }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().pointerInteropFilter { e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> finger = Offset(e.rawX, e.rawY)
                else -> finger = null
            }
            true
        }.drawBehind {
            ver
            val f = finger ?: return@drawBehind
            val bmp = FrameHub.latest ?: return@drawBehind
            val r = 76.dp.toPx(); val gap = r + 56.dp.toPx()
            val c = if (f.y - gap - r < 0) Offset(f.x, f.y + gap) else Offset(f.x, f.y - gap)
            val half = r / zoom
            val src = android.graphics.Rect((f.x - half).toInt(), (f.y - half).toInt(), (f.x + half).toInt(), (f.y + half).toInt())
            val dst = RectF(c.x - r, c.y - r, c.x + r, c.y + r)
            drawCircle(Color.Black.copy(alpha = 0.25f), r + 6.dp.toPx(), c + Offset(0f, 4.dp.toPx()))
            drawIntoCanvas { cv ->
                val n = cv.nativeCanvas
                n.save(); path.reset(); path.addCircle(c.x, c.y, r, android.graphics.Path.Direction.CW)
                n.clipPath(path); n.drawColor(cs.surface.toArgb()); n.drawBitmap(bmp, src, dst, paint); n.restore()
            }
            drawCircle(cs.primary, r, c, style = Stroke(5.dp.toPx()))
            drawCircle(cs.primary, 18.dp.toPx(), f, style = Stroke(2.dp.toPx()))
        })
        Surface(
            shape = CircleShape, color = cs.surfaceContainerHigh, shadowElevation = 6.dp,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
                IconButton(onClick = { zoom = (zoom - 0.5f).coerceAtLeast(1.5f) }) { Icon(Icons.Rounded.ZoomOut, "縮小") }
                Text("タッチビュー  ${"%.1f".format(zoom)}x", style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { zoom = (zoom + 0.5f).coerceAtMost(6f) }) { Icon(Icons.Rounded.ZoomIn, "拡大") }
                FilledIconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "終了") }
            }
        }
        if (finger == null) Text(
            "画面をなぞると拡大します", color = Color.White, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(horizontal = 20.dp, vertical = 10.dp)
        )
    }
}

// ================= Popup window (title bar, move, resize, minimize, maximize) =================
class FloatingWindow(
    private val svc: OverlayService, val tool: Tool, wDp: Int, hDp: Int,
    private val wantsFocus: Boolean, val body: @Composable () -> Unit
) {
    private val wm = svc.wm
    var minimized by mutableStateOf(false); private set
    var maximized by mutableStateOf(false); private set
    private val beforeMax = IntArray(4)
    private val beforeMin = IntArray(4)
    private fun px(v: Int) = svc.dp(v.toFloat()).toInt()

    val params = svc.overlayParams(px(wDp), px(hDp), LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCH_MODAL or LP.FLAG_WATCH_OUTSIDE_TOUCH).apply {
        val s = svc.screen()
        val off = svc.windowCount() * px(28)
        x = ((s.width() - width) / 2 + off).coerceAtLeast(0)
        y = ((s.height() - height) / 3 + off).coerceAtLeast(px(32))
        softInputMode = LP.SOFT_INPUT_ADJUST_NOTHING
    }

    private val root = object : FrameLayout(svc) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { focus(false); return false }
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) focus(true)
            return super.dispatchTouchEvent(ev)
        }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) focus(false); return true }
            return super.dispatchKeyEvent(event)
        }
    }

    fun show() {
        root.addView(svc.composeView { WindowChrome(this) })
        wm.addView(root, params)
    }

    private fun focus(f: Boolean) {
        if (!wantsFocus || (f && minimized)) return
        val nf = if (f) params.flags and LP.FLAG_NOT_FOCUSABLE.inv() else params.flags or LP.FLAG_NOT_FOCUSABLE
        if (nf != params.flags) { params.flags = nf; update() }
    }

    private fun update() { runCatching { wm.updateViewLayout(root, params) } }
    private fun save(a: IntArray) { a[0] = params.x; a[1] = params.y; a[2] = params.width; a[3] = params.height }
    private fun load(a: IntArray) { params.x = a[0]; params.y = a[1]; params.width = a[2]; params.height = a[3] }

    fun moveBy(dx: Float, dy: Float) {
        val s = svc.screen()
        params.x = (params.x + dx.toInt()).coerceIn(-params.width + px(80), s.width() - px(80))
        params.y = (params.y + dy.toInt()).coerceIn(0, s.height() - px(56))
        update()
    }

    fun resizeBy(dx: Float, dy: Float) {
        if (maximized) return
        val s = svc.screen()
        params.width = (params.width + dx.toInt()).coerceIn(px(220), s.width())
        params.height = (params.height + dy.toInt()).coerceIn(px(200), s.height())
        update()
    }

    fun toggleMax() {
        val s = svc.screen()
        if (!maximized) {
            save(beforeMax)
            params.x = px(8); params.y = px(40); params.width = s.width() - px(16); params.height = s.height() - px(120)
        } else load(beforeMax)
        maximized = !maximized; update()
    }

    fun minimize() {
        focus(false); save(beforeMin); minimized = true
        params.width = px(64); params.height = px(64); update()
    }

    fun restore() { minimized = false; load(beforeMin); update() }

    fun close() { runCatching { wm.removeView(root) }; svc.onWindowClosed(tool) }
}

@Composable
private fun ChromeButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) { Icon(icon, desc, Modifier.size(18.dp)) }
}

@Composable
fun WindowChrome(w: FloatingWindow) {
    val cs = MaterialTheme.colorScheme
    if (w.minimized) {
        Box(Modifier.fillMaxSize().padding(4.dp)) {
            Surface(shape = RoundedCornerShape(20.dp), color = cs.primaryContainer, contentColor = cs.onPrimaryContainer, shadowElevation = 6.dp,
                modifier = Modifier.fillMaxSize().rawDrag(onTap = { w.restore() }) { dx, dy -> w.moveBy(dx, dy) }) {
                Box(contentAlignment = Alignment.Center) { Icon(w.tool.icon, w.tool.label) }
            }
        }
        return
    }
    Surface(
        shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerLow, shadowElevation = 10.dp,
        modifier = Modifier.fillMaxSize().padding(6.dp)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().height(48.dp).background(cs.surfaceContainerHigh).padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(Modifier.weight(1f).fillMaxHeight().rawDrag { dx, dy -> w.moveBy(dx, dy) }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(w.tool.icon, null, tint = cs.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(w.tool.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
                ChromeButton(Icons.Rounded.Remove, "最小化") { w.minimize() }
                ChromeButton(if (w.maximized) Icons.Rounded.FilterNone else Icons.Rounded.CropSquare, "最大化") { w.toggleMax() }
                ChromeButton(Icons.Rounded.Close, "閉じる") { w.close() }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                w.body()
                if (!w.maximized) Canvas(
                    Modifier.align(Alignment.BottomEnd).size(30.dp).rawDrag { dx, dy -> w.resizeBy(dx, dy) }
                ) {
                    val c = cs.outline; val s = size.width
                    drawLine(c, Offset(s * 0.35f, s * 0.8f), Offset(s * 0.8f, s * 0.35f), 2.dp.toPx())
                    drawLine(c, Offset(s * 0.6f, s * 0.8f), Offset(s * 0.8f, s * 0.6f), 2.dp.toPx())
                }
            }
        }
    }
}
