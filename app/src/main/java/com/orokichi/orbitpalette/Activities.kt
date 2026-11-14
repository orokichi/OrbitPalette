package com.orokichi.orbitpalette

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

// ===================== Home / setup =====================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { OrbitTheme { Home() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var overlay by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var running by remember { mutableStateOf(OverlayService.instance != null) }
    fun notifOk() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var notif by remember { mutableStateOf(notifOk()) }
    var a11y by remember { mutableStateOf(OrbitA11yService.enabled(ctx)) }
    LifecycleResumeEffect(Unit) {
        overlay = Settings.canDrawOverlays(ctx); running = OverlayService.instance != null; notif = notifOk(); a11y = OrbitA11yService.enabled(ctx)
        onPauseOrDispose { }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notif = it }

    Scaffold(topBar = { LargeTopAppBar(title = { Text("Android AirCommand", fontWeight = FontWeight.Bold) }) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(shape = RoundedCornerShape(32.dp), colors = CardDefaults.cardColors(containerColor = cs.primaryContainer)) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).background(cs.primary, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                        Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_pen), null, tint = cs.onPrimary, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Text("どのアプリの上でも、丸いボタンからツールを呼び出せます。", style = MaterialTheme.typography.bodyLarge, color = cs.onPrimaryContainer)
                }
            }
            PermissionCard("他のアプリの上に表示", "フローティングボタンとポップアップウィンドウの表示に必要です", overlay) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
            }
            PermissionCard(
                "ユーザー補助（推奨）",
                "ユーザー補助で「Android AirCommand」をオンにすると、ScreenShot・TouchView・ScreenNote が毎回の確認なしで使え、ロック画面のダブルタップで Note が開きます。オンにできない場合は「アプリ情報」右上の︙から「制限付き設定を許可」してください",
                a11y, extra = "アプリ情報" to {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
                }
            ) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            if (Build.VERSION.SDK_INT >= 33) PermissionCard("通知", "常駐中であることを通知に表示します", notif) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            val src = remember { MutableInteractionSource() }
            val pressed by src.collectIsPressedAsState()
            val corner by animateDpAsState(if (pressed) 16.dp else 36.dp, spring(0.5f, 500f), label = "b")
            Button(
                onClick = {
                    val i = Intent(ctx, OverlayService::class.java)
                    if (running) ctx.stopService(i) else ContextCompat.startForegroundService(ctx, i)
                    running = !running
                },
                enabled = overlay, interactionSource = src, shape = RoundedCornerShape(corner),
                colors = if (running) ButtonDefaults.buttonColors(containerColor = cs.tertiary, contentColor = cs.onTertiary) else ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().height(72.dp)
            ) {
                Icon(if (running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(10.dp))
                Text(if (running) "パレットを停止" else "パレットを開始", style = MaterialTheme.typography.titleLarge)
            }
            if (!Apps.freeformEnabled(ctx)) PermissionCard(
                "Apps のウィンドウ表示",
                "開発者向けオプションで「フリーフォーム ウィンドウを有効にする」をオンにして再起動すると、Apps で囲んだサイズでアプリが開きます",
                false
            ) { runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } }
            BubbleSettings()
            var pencil by remember { mutableStateOf(PenPrefs.pencil(ctx)) }
            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh)) {
                androidx.compose.foundation.layout.Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("鉛筆機能", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("書いている間、紙に書くような細かい振動を加えます", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(pencil, { pencil = it; PenPrefs.setPencil(ctx, it) })
                }
            }
            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("使い方", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    listOf(
                        "画面のどこかをダブルタップでパレットを開き、ツールをタップ（ユーザー補助オン時）",
                        "Apps：▢を描くとその範囲にアプリ一覧、タップでそのサイズで起動",
                        "ウィンドウの▢を長押し→全部最小化→▢を描くとサイズ変更",
                        "ウィンドウはタイトルバーで移動、右下の角でサイズ変更",
                        "スクリーンショットはサムネイルをタップで編集、保存ボタンでそのまま保存"
                    ).forEach { Text("・$it", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PermissionCard(title: String, desc: String, granted: Boolean, extra: Pair<String, () -> Unit>? = null, onGrant: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.Lock, null, tint = if (granted) cs.primary else cs.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (!granted) Column(horizontalAlignment = Alignment.End) {
                FilledTonalButton(onClick = onGrant) { Text(if (extra != null) "オンにする" else "許可") }
                extra?.let { (label, act) -> TextButton(onClick = act) { Text(label) } }
            }
        }
    }
}

// ===================== Light editor (markup) =====================
sealed interface Mark
class PenMark(val pts: List<Offset>, val color: Int, val width: Float, val alpha: Float) : Mark
data class TextMark(val text: String, val pos: Offset, val color: Int, val size: Float) : Mark

enum class EditTool { PEN, MARKER, ERASER, TEXT }

private fun textPaint(m: TextMark) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = m.color; textSize = m.size; typeface = Typeface.DEFAULT_BOLD
    setShadowLayer(m.size * 0.08f, 0f, 0f, 0x88000000.toInt())
}

private fun drawMark(c: android.graphics.Canvas, m: Mark) {
    when (m) {
        is PenMark -> c.drawPath(smoothPath(m.pts), strokePaint(m.color, m.width, if (m.alpha < 0f) 1f else m.alpha).apply {
            if (m.alpha < 0f) xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        })
        is TextMark -> c.drawText(m.text, m.pos.x, m.pos.y, textPaint(m))
    }
}

private fun hitText(m: TextMark, p: Offset): Boolean {
    val w = textPaint(m).measureText(m.text)
    return p.x in (m.pos.x - 20)..(m.pos.x + w + 20) && p.y in (m.pos.y - m.size - 20)..(m.pos.y + m.size * 0.3f + 20)
}

class EditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val path = intent.getStringExtra("path")
        val bmp = path?.let { BitmapFactory.decodeFile(it) }
        if (bmp == null) { finish(); return }
        setContent { OrbitTheme { EditorScreen(bmp) { finish() } } }
    }
}

private val editColors = listOf(0xFFE53935.toInt(), 0xFFFFC107.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BubbleSettings() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var on by remember { mutableStateOf(UiPrefs.bubble(ctx)) }
    var dbl by remember { mutableStateOf(UiPrefs.doubleTap(ctx)) }
    var size by remember { mutableStateOf(UiPrefs.bubbleDp(ctx)) }
    var alpha by remember { mutableStateOf(UiPrefs.bubbleAlpha(ctx)) }
    var snap by remember { mutableStateOf(UiPrefs.bubbleSnap(ctx)) }
    @Composable fun row(t: String, d: String, v: Boolean, f: (Boolean) -> Unit) =
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(d, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(v, f)
        }
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            row("ダブルタップで起動", "画面のどこかをダブルタップでパレット（ユーザー補助が必要）", dbl) { dbl = it; UiPrefs.set(ctx, "doubleTap", it) }
            row("フローティングボタン", "ボタンからパレットを開く方式も使う", on) { on = it; UiPrefs.set(ctx, "bubble", it) }
            if (on) {
                Text("サイズ ${size.toInt()}dp", style = MaterialTheme.typography.labelLarge)
                Slider(size, { size = it }, valueRange = 32f..80f, onValueChangeFinished = { UiPrefs.set(ctx, "bubbleDp", size) })
                Text("不透明度 ${(alpha * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(alpha, { alpha = it }, valueRange = 0.2f..1f, onValueChangeFinished = { UiPrefs.set(ctx, "bubbleAlpha", alpha) })
                row("端に吸着", "離すと画面の左右端に寄せる", snap) { snap = it; UiPrefs.set(ctx, "bubbleSnap", it) }
            }
        }
    }
}

@Composable
fun EditorScreen(bmp: Bitmap, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val img = remember { bmp.asImageBitmap() }
    val marks = remember { mutableStateListOf<Mark>() }
    val redo = remember { mutableStateListOf<Mark>() }
    val live = remember { mutableStateListOf<Offset>() }
    val view = androidx.compose.ui.platform.LocalView.current
    val pen = remember { PenTracker(view, ctx) }
    var tool by remember { mutableStateOf(EditTool.PEN) }
    var color by remember { mutableIntStateOf(editColors[0]) }
    var dialog by remember { mutableStateOf<Pair<Offset, Int>?>(null) } // position, index (-1 = new)
    var saving by remember { mutableStateOf(false) }
    val sizes = remember { mutableStateMapOf<EditTool, Float>() }
    val sizeK = sizes[tool] ?: 1f
    val penW = bmp.width * 0.008f * (sizes[EditTool.PEN] ?: 1f); val markerW = bmp.width * 0.04f * (sizes[EditTool.MARKER] ?: 1f)
    val eraserW = bmp.width * 0.04f * (sizes[EditTool.ERASER] ?: 1f); val textSize = bmp.width * 0.055f * (sizes[EditTool.TEXT] ?: 1f)
    fun liveMark(pts: List<Offset>) = when (tool) {
        EditTool.MARKER -> PenMark(pts, color, markerW, 0.4f)
        EditTool.ERASER -> PenMark(pts, 0xFF000000.toInt(), eraserW, -1f)
        else -> PenMark(pts, color, penW, 1f)
    }

    fun save() {
        saving = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
                val layer = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
                val lc = android.graphics.Canvas(layer)
                marks.toList().forEach { drawMark(lc, it) }
                android.graphics.Canvas(out).drawBitmap(layer, 0f, 0f, null)
                Gallery.save(ctx, out, "Screenshot")
            }
            Toast.makeText(ctx, if (ok) "保存しました" else "保存に失敗しました", Toast.LENGTH_SHORT).show()
            saving = false
            if (ok) onClose()
        }
    }

    Scaffold(
        containerColor = cs.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text("編集") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "閉じる") } },
                actions = {
                    IconButton(onClick = { marks.removeLastOrNull()?.let { redo.add(it) } }, enabled = marks.isNotEmpty()) { Icon(Icons.Rounded.Undo, "元に戻す") }
                    IconButton(onClick = { redo.removeLastOrNull()?.let { marks.add(it) } }, enabled = redo.isNotEmpty()) { Icon(Icons.Rounded.Redo, "やり直し") }
                    Button(onClick = { save() }, enabled = !saving, modifier = Modifier.padding(end = 8.dp)) {
                        Icon(Icons.Rounded.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("保存")
                    }
                }
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = CircleShape, color = cs.surfaceContainerHigh, shadowElevation = 4.dp, modifier = Modifier.widthIn(max = 420.dp)) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (tool == EditTool.TEXT) "文字" else "太さ", style = MaterialTheme.typography.labelLarge)
                        Slider(value = sizeK, onValueChange = { sizes[tool] = it }, valueRange = 0.3f..4f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                        val dot = ((if (tool == EditTool.MARKER || tool == EditTool.ERASER) 10f else 4f) * sizeK).dp
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(dot.coerceIn(3.dp, 30.dp)).then(
                                if (tool == EditTool.ERASER) Modifier.border(2.dp, cs.onSurface, CircleShape)
                                else Modifier.background(Color(color).copy(alpha = if (tool == EditTool.MARKER) 0.4f else 1f), CircleShape)))
                        }
                    }
                }
                Surface(shape = CircleShape, color = cs.surfaceContainerHigh, shadowElevation = 6.dp) {
                    Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(EditTool.PEN to Icons.Rounded.Brush, EditTool.MARKER to Icons.Rounded.BorderColor, EditTool.ERASER to Icons.Rounded.AutoFixNormal, EditTool.TEXT to Icons.Rounded.TextFields).forEach { (t, ic) ->
                            val sel = tool == t
                            val corner by animateDpAsState(if (sel) 14.dp else 24.dp, spring(0.5f, 500f), label = "t")
                            Surface(onClick = { tool = t }, shape = RoundedCornerShape(corner),
                                color = if (sel) cs.primary else Color.Transparent, contentColor = if (sel) cs.onPrimary else cs.onSurfaceVariant,
                                modifier = Modifier.padding(2.dp).size(48.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(ic, t.name) }
                            }
                        }
                        VerticalDivider(Modifier.height(28.dp).padding(horizontal = 6.dp))
                        editColors.forEach { c ->
                            val sel = color == c
                            Box(
                                Modifier.padding(3.dp).size(if (sel) 28.dp else 22.dp).background(Color(c), CircleShape)
                                    .border(if (sel) 3.dp else 1.dp, if (sel) cs.primary else cs.outlineVariant, CircleShape)
                                    .clickable { color = c }
                            )
                        }
                    }
                }
            }
        }
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            val scale = min(constraints.maxWidth.toFloat() / bmp.width, constraints.maxHeight.toFloat() / bmp.height)
            val density = LocalDensity.current
            val dw = bmp.width * scale; val dh = bmp.height * scale
            Box(
                Modifier.size(with(density) { dw.toDp() }, with(density) { dh.toDp() })
                    .pointerInput(tool, color, penW, markerW, eraserW) {
                        if (tool == EditTool.TEXT) detectTapGestures { p ->
                            val ip = p / scale
                            val idx = marks.indexOfLast { it is TextMark && hitText(it, ip) }
                            dialog = ip to idx
                        } else awaitEachGesture {
                            val down = awaitFirstDown(); down.consume()
                            live.clear(); live.add(pen.begin(this, down) / scale)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                pen.move(this, c).forEach { live.add(it / scale) }; c.consume()
                            }
                            pen.end()
                            if (live.size > 0) marks.add(liveMark(live.toList()))
                            redo.clear(); live.clear()
                        }
                    }
                    .pointerInput(tool) {
                        if (tool != EditTool.TEXT) return@pointerInput
                        var idx = -1
                        detectDragGestures(
                            onDragStart = { p -> val ip = p / scale; idx = marks.indexOfLast { it is TextMark && hitText(it, ip) } },
                            onDrag = { c, d ->
                                if (idx >= 0) { c.consume(); val m = marks[idx] as TextMark; marks[idx] = m.copy(pos = m.pos + d / scale) }
                            }
                        )
                    }
                    .drawBehind {
                        drawImage(img, dstSize = IntSize(dw.toInt(), dh.toInt()))
                        drawIntoCanvas { cv ->
                            val n = cv.nativeCanvas
                            n.saveLayer(null, null); n.scale(scale, scale)
                            marks.forEach { drawMark(n, it) }
                            if (live.isNotEmpty()) drawMark(n, liveMark(live.toList()))
                            n.restore()
                        }
                    }
            )
        }
    }

    dialog?.let { (pos, idx) ->
        val existing = (if (idx >= 0) marks.getOrNull(idx) else null) as? TextMark
        var input by remember(pos, idx) { mutableStateOf(existing?.text ?: "") }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (existing == null) "テキストを追加" else "テキストを編集") },
            text = { OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = false, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    if (input.isNotBlank()) {
                        if (existing != null) marks[idx] = existing.copy(text = input)
                        else { marks.add(TextMark(input, pos, color, textSize)); redo.clear() }
                    }
                    dialog = null
                }) { Text("OK") }
            },
            dismissButton = {
                if (existing != null) TextButton(onClick = { marks.removeAt(idx); dialog = null }) { Text("削除") }
                else TextButton(onClick = { dialog = null }) { Text("キャンセル") }
            }
        )
    }
}


/** Full-screen Note shown over the lock screen (double-tap on the lock screen). */
class LockNoteActivity : ComponentActivity() {
    companion object { @Volatile var visible = false }
    override fun onResume() { super.onResume(); visible = true }
    override fun onPause() { visible = false; super.onPause() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        enableEdgeToEdge()
        val st = CanvasState(this, "locknote", smart = false)
        setContent {
            OrbitTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.fillMaxSize().systemBarsPadding()) { CanvasTool(st, full = true) { finish() } }
                }
            }
        }
    }
}
