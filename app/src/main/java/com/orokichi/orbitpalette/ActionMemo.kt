package com.orokichi.orbitpalette

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

// ===================== Action Memo (Galaxy Note style pop-up memo) =====================

object MemoStore {
    val PAPERS = listOf(0xFFFFF3A6.toInt(), 0xFFFFFFFF.toInt(), 0xFFCDEBFF.toInt(), 0xFFD6F2D0.toInt(), 0xFFFFD9E6.toInt(), 0xFF2B2B2B.toInt())
    private fun sp(c: Context) = c.getSharedPreferences("memos", Context.MODE_PRIVATE)
    fun ids(c: Context): List<String> = (sp(c).getString("ids", "") ?: "").split(",").filter { it.isNotBlank() }
    private fun setIds(c: Context, l: List<String>) = sp(c).edit().putString("ids", l.joinToString(",")).apply()
    fun create(c: Context): String { val id = System.currentTimeMillis().toString(); setIds(c, listOf(id) + ids(c)); return id }
    fun delete(c: Context, id: String) {
        setIds(c, ids(c) - id)
        File(c.filesDir, "memo_$id.txt").delete(); File(c.filesDir, "memo_$id.img").delete()
        sp(c).edit().remove("bg_$id").remove("t_$id").apply()
    }
    fun bg(c: Context, id: String) = sp(c).getInt("bg_$id", PAPERS[0])
    fun setBg(c: Context, id: String, v: Int) = sp(c).edit().putInt("bg_$id", v).apply()
    fun text(c: Context, id: String) = sp(c).getString("t_$id", "") ?: ""
    fun setText(c: Context, id: String, v: String) = sp(c).edit().putString("t_$id", v).apply()
}

class ActionMemoState(private val ctx: Context) {
    var id by mutableStateOf(MemoStore.ids(ctx).firstOrNull() ?: MemoStore.create(ctx)); private set
    var canvas by mutableStateOf(CanvasState(ctx, "memo_$id", smart = false)); private set
    var bg by mutableIntStateOf(MemoStore.bg(ctx, id)); private set
    var text by mutableStateOf(MemoStore.text(ctx, id)); private set
    /** 0 = pen, 1 = eraser, 2 = link action (lasso), 3 = text */
    var mode by mutableIntStateOf(0)
    var showList by mutableStateOf(false)
    var showPaper by mutableStateOf(false)
    var listTick by mutableIntStateOf(0)

    val dark get() = bg == MemoStore.PAPERS.last()
    val ink get() = if (dark) 0xFFF2F2F2.toInt() else 0xFF202124.toInt()
    fun isEmpty() = canvas.strokes.isEmpty() && canvas.images.isEmpty() && text.isBlank()

    fun open(nid: String) {
        id = nid; canvas = CanvasState(ctx, "memo_$nid", smart = false)
        bg = MemoStore.bg(ctx, nid); text = MemoStore.text(ctx, nid)
        showList = false; showPaper = false; mode = 0
    }

    /** Like Galaxy: every launch starts a blank memo (reuses the current one when it is still blank). */
    fun fresh() { if (!isEmpty()) open(MemoStore.create(ctx)) else { showList = false; mode = 0 } }

    fun updateText(v: String) { text = v; MemoStore.setText(ctx, id, v) }
    fun updateBg(v: Int) { bg = v; MemoStore.setBg(ctx, id, v) }
    fun deleteMemo(d: String) {
        MemoStore.delete(ctx, d); listTick++
        if (d == id) open(MemoStore.ids(ctx).firstOrNull() ?: MemoStore.create(ctx)).also { showList = true }
    }
}

private fun inside(p: Offset, poly: List<Offset>): Boolean {
    var c = false; var j = poly.size - 1
    for (i in poly.indices) {
        val a = poly[i]; val b = poly[j]
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) c = !c
        j = i
    }
    return c
}

private class MemoAction(val label: String, val icon: ImageVector, val run: (Context, String) -> Unit)

private fun go(ctx: Context, i: Intent) {
    runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(ctx, "対応するアプリがありません", Toast.LENGTH_SHORT).show() }
}

private fun digits(s: String) = s.filter { it.isDigit() || it == '+' || it in '０'..'９' }.map { if (it in '０'..'９') '0' + (it - '０') else it }.joinToString("")

private val memoActions = listOf(
    MemoAction("電話", Icons.Rounded.Call) { c, t -> go(c, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + digits(t)))) },
    MemoAction("メッセージ", Icons.Rounded.Sms) { c, t ->
        val d = digits(t)
        go(c, if (d.length >= 6) Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$d")) else Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).putExtra("sms_body", t))
    },
    MemoAction("メール", Icons.Rounded.Email) { c, t ->
        go(c, if (t.contains("@") || t.contains("＠")) Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + t.replace("＠", "@").trim()))
        else Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_TEXT, t))
    },
    MemoAction("連絡先", Icons.Rounded.PersonAdd) { c, t ->
        val i = Intent(Intent.ACTION_INSERT).setType(ContactsContract.RawContacts.CONTENT_TYPE)
        val d = digits(t)
        when {
            t.contains("@") -> i.putExtra(ContactsContract.Intents.Insert.EMAIL, t.trim())
            d.length >= 6 -> i.putExtra(ContactsContract.Intents.Insert.PHONE, d)
            else -> i.putExtra(ContactsContract.Intents.Insert.NAME, t.trim())
        }
        go(c, i)
    },
    MemoAction("ブラウザ", Icons.Rounded.Language) { c, t ->
        val s = t.trim()
        val u = if (s.startsWith("http") || (s.contains(".") && !s.contains(" "))) (if (s.startsWith("http")) s else "https://$s")
        else "https://www.google.com/search?q=" + Uri.encode(s)
        go(c, Intent(Intent.ACTION_VIEW, Uri.parse(u)))
    },
    MemoAction("地図", Icons.Rounded.Place) { c, t -> go(c, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(t.trim())))) },
    MemoAction("検索", Icons.Rounded.Search) { c, t -> go(c, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, t.trim())) },
    MemoAction("予定", Icons.Rounded.Event) { c, t ->
        go(c, Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, t.trim()))
    },
    MemoAction("コピー", Icons.Rounded.ContentCopy) { c, t ->
        c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("memo", t))
        Toast.makeText(c, "コピーしました", Toast.LENGTH_SHORT).show()
    },
    MemoAction("共有", Icons.Rounded.Share) { c, t ->
        go(c, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "共有"))
    },
)

/** Suggested actions first, like Galaxy's link action. */
private fun ranked(t: String): List<MemoAction> {
    val types = LinkDetector.detect(t).map { it.type }.toSet()
    val pri = buildList {
        if (LinkType.PHONE in types || digits(t).length >= 10) addAll(listOf("電話", "メッセージ", "連絡先"))
        if (LinkType.EMAIL in types) addAll(listOf("メール", "連絡先"))
        if (LinkType.URL in types) add("ブラウザ")
        if (LinkType.ADDRESS in types) add("地図")
    }.distinct()
    return memoActions.sortedBy { a -> pri.indexOf(a.label).let { if (it < 0) 99 else it } }
}

@Composable
private fun MemoToolButton(icon: ImageVector, desc: String, on: Boolean, fg: Color, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.padding(2.dp).size(34.dp).clip(CircleShape).background(if (on) cs.primary else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, Modifier.size(19.dp), tint = if (on) cs.onPrimary else fg) }
}

private val memoInks = listOf(0, 0xFFE53935.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt())

@Composable
fun ActionMemoTool(st: ActionMemoState) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val paper = Color(st.bg)
    val fg = if (st.dark) Color(0xFFEDEDED) else Color(0xFF3A3A3A)
    Column(Modifier.fillMaxSize().background(paper)) {
        // ---- toolbar ----
        Row(
            Modifier.fillMaxWidth().background(if (st.dark) Color(0x22FFFFFF) else Color(0x14000000))
                .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MemoToolButton(Icons.Rounded.Brush, "ペン", st.mode == 0, fg) { st.mode = 0; st.showList = false }
            MemoToolButton(Icons.Rounded.TextFields, "テキスト", st.mode == 3, fg) { st.mode = 3; st.showList = false }
            MemoToolButton(Icons.Rounded.AutoFixNormal, "消しゴム", st.mode == 1, fg) { st.mode = 1; st.showList = false }
            MemoToolButton(Icons.Rounded.Link, "リンク操作", st.mode == 2, fg) {
                st.mode = 2; st.showList = false; InkRecognizer.prepare()
                Toast.makeText(ctx, "操作したい文字を囲んでください", Toast.LENGTH_SHORT).show()
            }
            MemoToolButton(Icons.Rounded.Undo, "元に戻す", false, fg) { st.canvas.undo() }
            MemoToolButton(Icons.Rounded.Palette, "背景色", st.showPaper, fg) { st.showPaper = !st.showPaper }
            MemoToolButton(Icons.Rounded.GridView, "メモ一覧", st.showList, fg) { st.showList = !st.showList; st.listTick++ }
            MemoToolButton(Icons.Rounded.Add, "新規メモ", false, fg) { st.fresh() }
        }
        if (st.showPaper) Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MemoStore.PAPERS.forEach { p ->
                Box(Modifier.size(24.dp).clip(CircleShape).background(Color(p)).border(if (p == st.bg) 3.dp else 1.dp, if (p == st.bg) cs.primary else Color(0x33000000), CircleShape)
                    .clickable { st.updateBg(p); st.showPaper = false })
            }
            Spacer(Modifier.width(6.dp))
            memoInks.forEach { c ->
                val sel = st.canvas.color == c
                Box(Modifier.size(if (sel) 22.dp else 16.dp).clip(CircleShape).background(if (c == 0) Color(st.ink) else Color(c))
                    .clickable { st.canvas.color = c; st.mode = 0 })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (st.showList) MemoList(st) else MemoPage(st, fg)
        }
    }
}

@Composable
private fun MemoPage(st: ActionMemoState, fg: Color) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val cv = st.canvas
    val pen = remember(cv) { PenTracker(view, ctx) }
    var current by remember(cv) { mutableStateOf<InkStroke?>(null) }
    val lasso = remember(cv) { mutableStateListOf<Offset>() }
    var picked by remember(cv) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var eraserPos by remember { mutableStateOf<Offset?>(null) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(st.mode) { if (st.mode == 3) { delay(200); runCatching { fr.requestFocus() } } }
    val inkArgb = st.ink
    val accent = cs.primary

    Box(Modifier.fillMaxSize().clipToBounds()) {
        // typed text layer (editable only in text mode, ink can be written over it otherwise)
        if (st.mode == 3) BasicTextField(
            value = st.text, onValueChange = { st.updateText(it) },
            textStyle = TextStyle(color = fg, fontSize = 17.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
            modifier = Modifier.fillMaxSize().padding(12.dp).focusRequester(fr)
        ) else if (st.text.isNotBlank()) Text(st.text, color = fg, fontSize = 17.sp, modifier = Modifier.padding(12.dp))

        if (st.mode != 3) Box(
            Modifier.fillMaxSize().onSizeChanged { cv.size = it }
                .pointerInput(cv, st.mode) {
                    awaitEachGesture {
                        val down = awaitFirstDown(); down.consume()
                        when (st.mode) {
                            1 -> {
                                val r = with(density) { cv.eraserDp.dp.toPx() }
                                eraserPos = down.position; cv.eraseAt(down.position, r)
                                while (true) {
                                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    eraserPos = c.position; cv.eraseAt(c.position, r); c.consume()
                                }
                                eraserPos = null; cv.eraseDone()
                            }
                            2 -> {
                                picked = null; lasso.clear(); lasso.add(down.position)
                                while (true) {
                                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    lasso.add(c.position); c.consume()
                                }
                                val poly = lasso.toList()
                                val sel = if (poly.size > 5) cv.strokes.filter { s -> s.pts.count { inside(it, poly) } >= s.pts.size * 0.6f } else emptyList()
                                if (sel.isEmpty()) { lasso.clear(); Toast.makeText(ctx, "手書きの文字を囲んでください", Toast.LENGTH_SHORT).show() }
                                else scope.launch {
                                    if (!InkRecognizer.ready) { lasso.clear(); Toast.makeText(ctx, "文字認識モデルを準備中です", Toast.LENGTH_SHORT).show(); return@launch }
                                    busy = true
                                    val t = InkRecognizer.recognize(sel)
                                    busy = false
                                    if (t.isBlank()) { lasso.clear(); Toast.makeText(ctx, "文字を認識できませんでした", Toast.LENGTH_SHORT).show() } else picked = t
                                }
                            }
                            else -> {
                                val s = InkStroke(cv.color, with(density) { cv.penDp.dp.toPx() })
                                s.add(pen.begin(this, down), down.uptimeMillis); current = s; cv.tick++
                                while (true) {
                                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    pen.move(this, c).forEach { s.add(it, c.uptimeMillis) }; c.consume(); cv.tick++
                                }
                                pen.end(); current = null; cv.commit(s)
                            }
                        }
                    }
                }
                .drawBehind {
                    cv.tick
                    drawIntoCanvas { c ->
                        val n = c.nativeCanvas
                        cv.strokes.forEach { s -> n.drawPath(smoothPath(s.pts), strokePaint(if (s.color == 0) inkArgb else s.color, s.width)) }
                        current?.let { s -> n.drawPath(smoothPath(s.pts), strokePaint(if (s.color == 0) inkArgb else s.color, s.width)) }
                    }
                    if (lasso.size > 1) {
                        val p = androidx.compose.ui.graphics.Path().apply { moveTo(lasso[0].x, lasso[0].y); lasso.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                        drawPath(p, accent.copy(alpha = 0.12f))
                        drawPath(p, accent, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                    }
                    eraserPos?.let { drawCircle(accent, cv.eraserDp.dp.toPx(), it, style = Stroke(2.dp.toPx())) }
                }
        )

        if (st.isEmpty() && current == null) Text(
            if (st.mode == 3) "" else "アクションメモ\nペンで書いて、リンク操作で囲むと電話・メール・地図などを開けます",
            color = fg.copy(alpha = 0.45f), fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(20.dp)
        )
        if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 3.dp)

        picked?.let { t ->
            var edit by remember(t) { mutableStateOf(t) }
            Surface(
                shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerHigh, shadowElevation = 8.dp,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(edit, { edit = it }, singleLine = true, textStyle = TextStyle(fontSize = 15.sp), modifier = Modifier.weight(1f))
                        IconButton(onClick = { picked = null; lasso.clear() }) { Icon(Icons.Rounded.Close, "閉じる") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ranked(edit).forEachIndexed { i, a ->
                            val chip: @Composable () -> Unit = {
                                Column(
                                    Modifier.clip(RoundedCornerShape(14.dp)).background(if (i < 3) cs.primaryContainer else cs.surfaceContainerHighest)
                                        .clickable {
                                            a.run(ctx, edit); picked = null; lasso.clear()
                                            if (a.label != "コピー") OverlayService.instance?.minimizeMemo()
                                        }.padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(a.icon, null, Modifier.size(20.dp), tint = if (i < 3) cs.onPrimaryContainer else cs.onSurfaceVariant)
                                    Text(a.label, fontSize = 11.sp)
                                }
                            }
                            chip()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoList(st: ActionMemoState) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val ids = remember(st.listTick) { MemoStore.ids(ctx) }
    LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
        items(ids, key = { it }) { id ->
            val strokes = remember(id, st.listTick) { CanvasState(ctx, "memo_$id", smart = false).strokes.toList() }
            val bg = MemoStore.bg(ctx, id); val txt = MemoStore.text(ctx, id)
            val ink = if (bg == MemoStore.PAPERS.last()) 0xFFF2F2F2.toInt() else 0xFF202124.toInt()
            Box(
                Modifier.padding(4.dp).aspectRatio(0.8f).clip(RoundedCornerShape(12.dp)).background(Color(bg))
                    .border(if (id == st.id) 2.dp else 1.dp, if (id == st.id) cs.primary else Color(0x22000000), RoundedCornerShape(12.dp))
                    .combinedClickable(onClick = { st.open(id) }, onLongClick = { st.deleteMemo(id); Toast.makeText(ctx, "削除しました", Toast.LENGTH_SHORT).show() })
                    .drawBehind {
                        if (strokes.isEmpty()) return@drawBehind
                        val pts = strokes.flatMap { it.pts }
                        val l = pts.minOf { it.x }; val t = pts.minOf { it.y }
                        val w = (pts.maxOf { it.x } - l).coerceAtLeast(1f); val h = (pts.maxOf { it.y } - t).coerceAtLeast(1f)
                        val k = minOf(size.width * 0.8f / w, size.height * 0.8f / h, 0.6f)
                        drawIntoCanvas { c ->
                            val n = c.nativeCanvas
                            n.save(); n.translate(size.width * 0.1f, size.height * 0.1f); n.scale(k, k); n.translate(-l, -t)
                            strokes.forEach { s -> n.drawPath(smoothPath(s.pts), strokePaint(if (s.color == 0) ink else s.color, s.width)) }
                            n.restore()
                        }
                    }
            ) {
                if (txt.isNotBlank()) Text(txt, fontSize = 10.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, color = Color(ink), modifier = Modifier.padding(6.dp))
            }
        }
    }
    if (ids.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("メモはありません") }
}

/** Minimized action memo = a small sticky note on screen (tap to reopen, drag to the trash to close). */
@Composable
fun MemoMini(st: ActionMemoState) {
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color(st.bg)).border(1.dp, Color(0x33000000), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.EditNote, "アクションメモ", Modifier.size(30.dp), tint = Color(st.ink))
    }
}
