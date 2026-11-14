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
