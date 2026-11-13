package com.orokichi.orbitpalette

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.tasks.await
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
import java.math.BigDecimal
import java.math.MathContext

// ===================== Handwriting canvas =====================
class InkStroke(val color: Int, val width: Float) {
    val pts = ArrayList<Offset>()
    val times = ArrayList<Long>()
    fun add(p: Offset, t: Long) { pts.add(p); times.add(t) }
    fun bounds(): Rect {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        pts.forEach { l = minOf(l, it.x); t = minOf(t, it.y); r = maxOf(r, it.x); b = maxOf(b, it.y) }
        return Rect(l, t, r, b)
    }
}

class InkGroup(val strokes: List<InkStroke>, val bounds: Rect, val text: String, val links: List<Detected>)

object InkRecognizer {
    private var recognizer: DigitalInkRecognizer? = null
    var ready by mutableStateOf(false)

    fun prepare() {
        if (recognizer != null) return
        runCatching {
            val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag("ja") ?: return
            val model = DigitalInkRecognitionModel.builder(id).build()
            recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
            RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().build())
                .addOnSuccessListener { ready = true }
        }
    }

    /** Recognize regardless of writing direction: try several rotations and keep the most confident result. */
    suspend fun recognize(strokes: List<InkStroke>): String {
        val r = recognizer ?: return ""
        if (!ready || strokes.isEmpty()) return ""
        val all = strokes.flatMap { it.pts }
        val mx = all.sumOf { it.x.toDouble() } / all.size
        val my = all.sumOf { it.y.toDouble() } / all.size
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        all.forEach { val dx = it.x - mx; val dy = it.y - my; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
        val axis = 0.5 * atan2(2 * sxy, sxx - syy)
        val angles = listOf(0.0, -axis, PI - axis, PI / 2, -PI / 2, PI)
            .distinctBy { (((it % (2 * PI)) + 2 * PI) % (2 * PI) * 20).roundToInt() }
        val results = angles.mapNotNull { a ->
            val c = cos(a); val sn = sin(a)
            val ink = Ink.builder()
            strokes.forEach { s ->
                val sb = Ink.Stroke.builder()
                s.pts.forEachIndexed { i, p ->
                    val dx = p.x - mx; val dy = p.y - my
                    sb.addPoint(Ink.Point.create((c * dx - sn * dy + mx).toFloat(), (sn * dx + c * dy + my).toFloat(), s.times[i]))
                }
                ink.addStroke(sb.build())
            }
            runCatching { r.recognize(ink.build()).await() }.getOrNull()?.candidates?.firstOrNull()?.takeIf { it.text.isNotBlank() }
        }
        if (results.isEmpty()) return ""
        val scored = results.filter { it.score != null }
        val best = when {
            scored.isNotEmpty() -> scored.minBy { it.score!! }
            else -> results.firstOrNull { LinkDetector.detect(it.text).isNotEmpty() } ?: results.first()
        }
        return best.text
    }
}

class CanvasImage(val bmp: Bitmap, val rect: Rect, val file: String) { val ib = bmp.asImageBitmap() }

class CanvasState(private val ctx: Context, private val name: String = "canvas", val smart: Boolean = true) {
    val strokes = mutableStateListOf<InkStroke>()
    val images = mutableStateListOf<CanvasImage>()
    val groups = mutableStateListOf<InkGroup>()
    private val pending = ArrayList<InkStroke>()
    private val ops = ArrayList<Any>()
    var color by mutableIntStateOf(0) // 0 = theme ink
    var erasing by mutableStateOf(false)
    var penDp by mutableFloatStateOf(3f)
    var eraserDp by mutableFloatStateOf(16f)
    var tick by mutableIntStateOf(0)
    var size = IntSize.Zero
    private val file = File(ctx.filesDir, "$name.txt")
    private val imgIndex = File(ctx.filesDir, "$name.img")

    init { load() }

    fun commit(s: InkStroke) { strokes.add(s); ops.add(s); if (smart) pending.add(s); save() }

    fun addImage(b: Bitmap) {
        val w = size.width.toFloat().coerceAtLeast(100f); val h = size.height.toFloat().coerceAtLeast(100f)
        val k = minOf(w * 0.85f / b.width, h * 0.6f / b.height, 1f)
        val iw = b.width * k; val ih = b.height * k
        var top = images.maxOfOrNull { it.rect.bottom + 16f } ?: 24f
        if (top + ih > h) top = 24f
        val f = "${name}_img_${System.currentTimeMillis()}.png"
        runCatching { File(ctx.filesDir, f).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val ci = CanvasImage(b, Rect((w - iw) / 2, top, (w + iw) / 2, top + ih), f)
        images.add(ci); ops.add(ci); saveImages(); tick++
    }

    /** Pixel-like eraser: cuts the parts of strokes within radius r of p. */
    fun eraseAt(p: Offset, r: Float) {
        val r2 = r * r
        var changed = false
        for (i in strokes.indices.reversed()) {
            val s = strokes[i]
            if (s.pts.none { (it - p).getDistanceSquared() <= r2 }) continue
            changed = true
            val parts = ArrayList<InkStroke>()
            var cur: InkStroke? = null
            for (j in s.pts.indices) {
                val q = s.pts[j]
                if ((q - p).getDistanceSquared() <= r2) cur = null
                else {
                    val c = cur ?: InkStroke(s.color, s.width).also { parts.add(it); cur = it }
                    c.add(q, s.times[j])
                }
            }
            strokes.removeAt(i); pending.remove(s); ops.remove(s)
            groups.removeAll { s in it.strokes }
            parts.filter { it.pts.size >= 2 }.forEachIndexed { k, n -> strokes.add(i + k, n) }
        }
        if (changed) tick++
    }

    fun eraseDone() = save()

    suspend fun recognizePending() {
        if (!smart || pending.isEmpty()) return
        val g = pending.toList(); pending.clear()
        val text = InkRecognizer.recognize(g)
        if (text.isBlank()) return
        val links = LinkDetector.detect(text)
        var b = g[0].bounds()
        g.drop(1).forEach { val o = it.bounds(); b = Rect(minOf(b.left, o.left), minOf(b.top, o.top), maxOf(b.right, o.right), maxOf(b.bottom, o.bottom)) }
        groups.add(InkGroup(g, b, text, links))
    }

    /** All handwriting as text, top-to-bottom. */
    suspend fun convertAll(): String {
        recognizePending()
        val live = groups.filter { g -> g.strokes.all { it in strokes } }
        val t = live.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left })).joinToString("\n") { it.text }.trim()
        return if (t.isNotBlank() && live.sumOf { it.strokes.size } >= strokes.size) t else InkRecognizer.recognize(strokes.toList()).ifBlank { t }
    }

    fun undo() {
        while (ops.isNotEmpty()) {
            val o = ops.removeAt(ops.lastIndex)
            if (o is InkStroke && strokes.remove(o)) { pending.remove(o); groups.removeAll { o in it.strokes }; save(); return }
            if (o is CanvasImage && images.remove(o)) { File(ctx.filesDir, o.file).delete(); saveImages(); tick++; return }
        }
        strokes.removeLastOrNull()?.let { s -> groups.removeAll { s in it.strokes }; save() }
    }

    fun clear() {
        images.forEach { File(ctx.filesDir, it.file).delete() }
        strokes.clear(); images.clear(); groups.clear(); pending.clear(); ops.clear(); save(); saveImages(); tick++
    }

    private fun save() = runCatching {
        file.writeText(strokes.joinToString("\n") { s ->
            "${s.color}|${s.width}|" + s.pts.indices.joinToString(";") { "${s.pts[it].x},${s.pts[it].y},${s.times[it]}" }
        })
    }

    private fun saveImages() = runCatching {
        imgIndex.writeText(images.joinToString("\n") { "${it.file}|${it.rect.left},${it.rect.top},${it.rect.right},${it.rect.bottom}" })
    }

    private fun load() = runCatching {
        if (file.exists()) file.readLines().forEach { line ->
            runCatching {
                val (c, w, p) = line.split("|", limit = 3)
                val s = InkStroke(c.toInt(), w.toFloat())
                p.split(";").forEach { q -> val v = q.split(","); s.add(Offset(v[0].toFloat(), v[1].toFloat()), v[2].toLong()) }
                if (s.pts.isNotEmpty()) strokes.add(s)
            }
        }
        if (imgIndex.exists()) imgIndex.readLines().forEach { line ->
            runCatching {
                val (f, r) = line.split("|")
                val v = r.split(",").map { it.toFloat() }
                val b = android.graphics.BitmapFactory.decodeFile(File(ctx.filesDir, f).absolutePath) ?: return@runCatching
                images.add(CanvasImage(b, Rect(v[0], v[1], v[2], v[3]), f))
            }
        }
    }

    fun render(inkColor: Int, bg: Int): Bitmap {
        val w = size.width.coerceAtLeast(1); val h = size.height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp); c.drawColor(bg)
        val ip = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        images.forEach { c.drawBitmap(it.bmp, null, android.graphics.RectF(it.rect.left, it.rect.top, it.rect.right, it.rect.bottom), ip) }
        strokes.forEach { s -> c.drawPath(smoothPath(s.pts), strokePaint(if (s.color == 0) inkColor else s.color, s.width)) }
        return bmp
    }
}

private val inkColors = listOf(0, 0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFFFB8C00.toInt())

/** Note canvas. full = lock-screen version (no text conversion, link detection or ScreenNote). */
@Composable
fun CanvasTool(st: CanvasState, full: Boolean = false, onClose: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    LaunchedEffect(Unit) { if (st.smart) InkRecognizer.prepare() }
    val job = remember { arrayOfNulls<Job>(1) }
    var current by remember { mutableStateOf<InkStroke?>(null) }
    var eraserPos by remember { mutableStateOf<Offset?>(null) }
    val ink = cs.onSurface.toArgb()
    val accent = cs.primary
    val outline = cs.outlineVariant

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { st.size = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(); down.consume()
                        job[0]?.cancel()
                        if (st.erasing) {
                            val r = with(density) { st.eraserDp.dp.toPx() }
                            eraserPos = down.position; st.eraseAt(down.position, r)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                eraserPos = c.position; st.eraseAt(c.position, r); c.consume()
                            }
                            eraserPos = null; st.eraseDone()
                            return@awaitEachGesture
                        }
                        val s = InkStroke(st.color, with(density) { st.penDp.dp.toPx() })
                        s.add(down.position, down.uptimeMillis); current = s; st.tick++
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            s.add(c.position, c.uptimeMillis); c.consume(); st.tick++
                        }
                        current = null
                        val b = s.bounds()
                        if (st.smart && b.width < 14f && b.height < 14f) {
                            val hit = st.groups.lastOrNull { it.links.isNotEmpty() && it.bounds.inflate(28f).contains(b.center) }
                            if (hit != null) { st.tick++; LinkDetector.launch(ctx, hit.links.first()); return@awaitEachGesture }
                        }
                        st.commit(s)
                        if (st.smart) job[0] = scope.launch { delay(900); st.recognizePending() }
                    }
                }
                .drawBehind {
                    st.tick
                    st.images.forEach { im ->
                        val r = im.rect
                        drawImage(im.ib, dstOffset = IntOffset(r.left.toInt(), r.top.toInt()), dstSize = IntSize(r.width.toInt(), r.height.toInt()))
                        drawRect(outline, r.topLeft, r.size, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                    }
                    drawIntoCanvas { cv ->
                        val n = cv.nativeCanvas
                        st.strokes.forEach { s -> n.drawPath(smoothPath(s.pts), strokePaint(if (s.color == 0) ink else s.color, s.width)) }
                        current?.let { s -> n.drawPath(smoothPath(s.pts), strokePaint(if (s.color == 0) ink else s.color, s.width)) }
                    }
                    if (st.smart) st.groups.forEach { g ->
                        if (g.links.isNotEmpty()) {
                            val y = g.bounds.bottom + 8.dp.toPx()
                            drawLine(accent, Offset(g.bounds.left, y), Offset(g.bounds.right, y), 3.dp.toPx(), StrokeCap.Round)
                        }
                    }
                    eraserPos?.let { drawCircle(accent, st.eraserDp.dp.toPx(), it, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) }
                }
        ) {
            if (!full) {
                var converting by remember { mutableStateOf(false) }
                Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallFloatingActionButton(
                        onClick = { OverlayService.instance?.startScreenNote() },
                        containerColor = cs.secondaryContainer, contentColor = cs.onSecondaryContainer
                    ) { Icon(Icons.Rounded.Crop, "ScreenNote") }
                    if (st.strokes.isNotEmpty()) SmallFloatingActionButton(
                        onClick = {
                            if (!InkRecognizer.ready) Toast.makeText(ctx, "文字認識モデルを準備中です", Toast.LENGTH_SHORT).show()
                            else if (!converting) scope.launch {
                                converting = true
                                job[0]?.cancel()
                                val t = st.convertAll()
                                converting = false
                                if (t.isBlank()) Toast.makeText(ctx, "文字を認識できませんでした", Toast.LENGTH_SHORT).show()
                                else OverlayService.instance?.openNoteWith(t)
                            }
                        },
                        containerColor = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer
                    ) {
                        if (converting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.TextFields, "テキストに変換")
                    }
                }
            }
            if (st.strokes.isEmpty() && st.images.isEmpty()) Text(
                if (!st.smart || InkRecognizer.ready) "指やペンで自由に手書きメモ" else "指やペンで自由に手書きメモ\n（文字認識モデルを準備中…）",
                color = cs.onSurfaceVariant.copy(alpha = 0.6f), textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center)
            )
        }
        val links = if (st.smart) st.groups.flatMap { it.links } else emptyList()
        if (links.isNotEmpty()) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            links.forEach { d ->
                AssistChip(onClick = { LinkDetector.launch(ctx, d) }, label = { Text(d.value, maxLines = 1) },
                    leadingIcon = { Icon(d.type.icon, null, Modifier.size(18.dp)) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (st.erasing) Icons.Rounded.AutoFixNormal else Icons.Rounded.Brush, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
            Slider(
                value = if (st.erasing) st.eraserDp else st.penDp,
                onValueChange = { if (st.erasing) st.eraserDp = it else st.penDp = it },
                valueRange = if (st.erasing) 6f..60f else 1f..14f,
                modifier = Modifier.weight(1f).padding(start = 8.dp, end = if (full) 0.dp else 24.dp)
            )
        }
        Surface(shape = CircleShape, color = cs.surfaceContainerHighest, modifier = Modifier.padding(start = 8.dp, end = if (full) 8.dp else 34.dp, bottom = 8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                inkColors.forEach { c ->
                    val sel = !st.erasing && st.color == c
                    Box(
                        Modifier.padding(3.dp).size(if (sel) 26.dp else 20.dp)
                            .background(if (c == 0) cs.onSurface else Color(c), CircleShape)
                            .then(if (sel) Modifier.border(3.dp, cs.primary, CircleShape) else Modifier)
                            .clickable { st.color = c; st.erasing = false }
                    )
                }
                if (st.erasing) FilledIconButton(onClick = { st.erasing = false }, Modifier.size(36.dp)) { Icon(Icons.Rounded.AutoFixNormal, "消しゴム", Modifier.size(20.dp)) }
                else IconButton(onClick = { st.erasing = true }, Modifier.size(36.dp)) { Icon(Icons.Rounded.AutoFixNormal, "消しゴム", Modifier.size(20.dp)) }
                IconButton(onClick = { st.undo() }, Modifier.size(36.dp)) { Icon(Icons.Rounded.Undo, "元に戻す", Modifier.size(20.dp)) }
                IconButton(onClick = { st.clear() }, Modifier.size(36.dp)) { Icon(Icons.Rounded.DeleteSweep, "消去", Modifier.size(20.dp)) }
                IconButton(onClick = {
                    val ok = Gallery.save(ctx, st.render(0xFF1B1B1F.toInt(), android.graphics.Color.WHITE), "Note")
                    Toast.makeText(ctx, if (ok) "保存しました" else "保存に失敗しました", Toast.LENGTH_SHORT).show()
                }, Modifier.size(36.dp)) { Icon(Icons.Rounded.Save, "保存", Modifier.size(20.dp)) }
                if (onClose != null) IconButton(onClick = onClose, Modifier.size(36.dp)) { Icon(Icons.Rounded.Close, "閉じる", Modifier.size(20.dp)) }
            }
        }
    }
}

// ===================== Calculator =====================
object Calc {
    fun eval(src: String): Double {
        val t = src.replace('×', '*').replace('÷', '/').replace('−', '-')
        var i = 0
        fun peek() = if (i < t.length) t[i] else '\u0000'
        lateinit var expr: () -> Double
        fun factor(): Double {
            if (peek() == '-') { i++; return -factor() }
            if (peek() == '(') { i++; val v = expr(); if (peek() == ')') i++; return v }
            val s = i
            while (peek().isDigit() || peek() == '.') i++
            var v = t.substring(s, i).toDouble()
            if (peek() == '%') { i++; v /= 100 }
            return v
        }
        fun term(): Double {
            var v = factor()
            while (peek() == '*' || peek() == '/') { val op = t[i++]; val r = factor(); v = if (op == '*') v * r else v / r }
            return v
        }
        expr = {
            var v = term()
            while (peek() == '+' || peek() == '-') { val op = t[i++]; val r = term(); v = if (op == '+') v + r else v - r }
            v
        }
        val v = expr()
        require(i == t.length)
        return v
    }

    fun format(v: Double): String =
        if (v.isNaN() || v.isInfinite()) "エラー"
        else BigDecimal(v).round(MathContext(12)).stripTrailingZeros().toPlainString()
}

@Composable
private fun CalcKey(label: String, kind: Int, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val corner by androidx.compose.animation.core.animateDpAsState(
        if (pressed) 12.dp else 30.dp,
        androidx.compose.animation.core.spring(0.5f, 600f), label = "k"
    )
    val (bg, fg) = when (kind) {
        1 -> cs.secondaryContainer to cs.onSecondaryContainer
        2 -> cs.tertiaryContainer to cs.onTertiaryContainer
        3 -> cs.primary to cs.onPrimary
        else -> cs.surfaceContainerHighest to cs.onSurface
    }
    Surface(onClick = onClick, interactionSource = src, shape = RoundedCornerShape(corner), color = bg, contentColor = fg, modifier = modifier) {
        Box(contentAlignment = Alignment.Center) { Text(label, fontSize = 22.sp, fontWeight = FontWeight.Medium) }
    }
}

@Composable
fun CalculatorTool() {
    val cs = MaterialTheme.colorScheme
    var expr by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf<String?>(null) }
    val preview = remember(expr) { runCatching { if (expr.isEmpty()) "" else Calc.format(Calc.eval(expr)) }.getOrDefault("") }
    val rows = listOf(
        listOf("C", "( )", "%", "÷"), listOf("7", "8", "9", "×"), listOf("4", "5", "6", "−"),
        listOf("1", "2", "3", "+"), listOf("0", ".", "⌫", "=")
    )
    fun press(k: String) {
        when (k) {
            "C" -> { expr = ""; shown = null }
            "⌫" -> { expr = expr.dropLast(1); shown = null }
            "=" -> { shown = runCatching { Calc.format(Calc.eval(expr)) }.getOrDefault("エラー"); if (shown != "エラー") expr = shown!! }
            "( )" -> { val open = expr.count { it == '(' } - expr.count { it == ')' }; expr += if (open > 0 && (expr.lastOrNull()?.isDigit() == true || expr.endsWith(")"))) ")" else "("; shown = null }
            else -> { expr += k; shown = null }
        }
    }
    Column(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, bottom = 26.dp)) {
        Column(Modifier.fillMaxWidth().weight(0.32f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.End) {
            Text(expr.ifEmpty { "0" }, style = MaterialTheme.typography.headlineSmall, color = cs.onSurfaceVariant, maxLines = 2, textAlign = TextAlign.End)
            Text(shown ?: preview, style = MaterialTheme.typography.displaySmall, color = cs.primary, maxLines = 1)
        }
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { k ->
                        val kind = when (k) { "=" -> 3; "÷", "×", "−", "+" -> 1; "C", "( )", "%" -> 2; else -> 0 }
                        CalcKey(k, kind, Modifier.weight(1f).fillMaxHeight()) { press(k) }
                    }
                }
            }
        }
    }
}

// ===================== Light note =====================
object NoteStore {
    private var prefs: android.content.SharedPreferences? = null
    var text by mutableStateOf("")
        private set
    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.getSharedPreferences("note", Context.MODE_PRIVATE)
        text = prefs?.getString("t", "") ?: ""
    }
    fun set(v: String) { text = v; prefs?.edit()?.putString("t", v)?.apply() }
}

@Composable
fun NoteTool() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    remember { NoteStore.init(ctx) }
    val text = NoteStore.text
    var editing by remember { mutableStateOf(text.isBlank()) }
    Box(Modifier.fillMaxSize()) {
        if (editing) {
            val fr = remember { FocusRequester() }
            BasicTextField(
                value = text, onValueChange = { NoteStore.set(it) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.fillMaxSize().padding(16.dp).focusRequester(fr)
            )
            LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
        } else {
            val links = remember(text) { LinkDetector.detect(text) }
            val style = TextLinkStyles(SpanStyle(color = cs.primary, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium))
            val annotated = buildAnnotatedString {
                var last = 0
                links.forEach { d ->
                    append(text.substring(last, d.start))
                    withLink(LinkAnnotation.Clickable(d.type.name, style, LinkInteractionListener { LinkDetector.launch(ctx, d) })) {
                        append(text.substring(d.start, d.end))
                    }
                    last = d.end
                }
                append(text.substring(last))
            }
            Text(annotated, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp))
        }
        FloatingActionButton(
            onClick = { editing = !editing }, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 12.dp).size(48.dp)
        ) { Icon(if (editing) Icons.Rounded.Check else Icons.Rounded.Edit, if (editing) "完了" else "編集") }
    }
}
