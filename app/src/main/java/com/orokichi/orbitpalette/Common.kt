package com.orokichi.orbitpalette

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OrbitTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(36.dp)
        ),
        content = content
    )
}

enum class Tool(val label: String, val icon: ImageVector) {
    SCREENSHOT("ScreenShot", Icons.Rounded.Screenshot),
    CANVAS("Note", Icons.Rounded.Draw),
    TOUCHVIEW("TouchView", Icons.Rounded.ZoomIn),
    APPS("Apps", Icons.Rounded.Apps),
    CALC("電卓", Icons.Rounded.Calculate),
    NOTE("WriteNote", Icons.Rounded.EditNote);

    val angleDeg: Float get() = -90f + ordinal * 360f / entries.size
}

object Notif {
    const val CH = "orbit"
    fun ensure(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Android AirCommand", NotificationManager.IMPORTANCE_LOW))
    }
}

object Gallery {
    fun save(ctx: Context, bmp: Bitmap, prefix: String): Boolean = runCatching {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${prefix}_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OrbitPalette")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return false
        r.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
        r.update(uri, cv, null, null)
        true
    }.getOrDefault(false)
}

enum class LinkType(val icon: ImageVector) {
    PHONE(Icons.Rounded.Call), EMAIL(Icons.Rounded.Email), URL(Icons.Rounded.Link), ADDRESS(Icons.Rounded.Place)
}

data class Detected(val type: LinkType, val value: String, val start: Int, val end: Int)

object LinkDetector {
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val url = Regex("(https?://|www\\.)[A-Za-z0-9./?=&%#:~+_-]+")
    private val phone = Regex("\\+?\\d[\\d\\- ()]{7,}\\d")
    private val address = Regex("(東京都|北海道|京都府|大阪府|[^\\s、。,]{2,3}県)[^\\s、。,]{0,20}?[市区町村郡][^\\s、。,]{0,30}")

    private fun normalize(s: String) = buildString(s.length) {
        for (c in s) append(
            when (c) {
                in '０'..'９' -> '0' + (c - '０')
                in 'Ａ'..'Ｚ' -> 'A' + (c - 'Ａ')
                in 'ａ'..'ｚ' -> 'a' + (c - 'ａ')
                '＠' -> '@'; '．' -> '.'; '＋' -> '+'; '（' -> '('; '）' -> ')'; '　' -> ' '
                '－', '―', '‐', '−', 'ー' -> '-'
                else -> c
            }
        )
    }

    fun detect(src: String): List<Detected> {
        val s = normalize(src)
        val out = mutableListOf<Detected>()
        fun add(t: LinkType, r: Regex, ok: (String) -> Boolean = { true }) {
            r.findAll(s).forEach { m ->
                val st = m.range.first; val en = m.range.last + 1
                if (ok(m.value) && out.none { it.start < en && st < it.end }) out.add(Detected(t, m.value.trim(), st, en))
            }
        }
        add(LinkType.EMAIL, email); add(LinkType.URL, url)
        add(LinkType.PHONE, phone) { v -> v.count { it.isDigit() } in 10..13 }
        add(LinkType.ADDRESS, address)
        return out.sortedBy { it.start }
    }

    fun launch(ctx: Context, d: Detected) {
        val i = when (d.type) {
            LinkType.PHONE -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + d.value.filter { it.isDigit() || it == '+' }))
            LinkType.EMAIL -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + d.value)).setPackage("com.google.android.gm")
            LinkType.URL -> Intent(Intent.ACTION_VIEW, Uri.parse(if (d.value.startsWith("http")) d.value else "https://" + d.value))
            LinkType.ADDRESS -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(d.value)))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: ActivityNotFoundException) {
            i.setPackage(null); runCatching { ctx.startActivity(i) }
        }
    }
}

/** Drag using raw screen coordinates, so moving the window itself doesn't disturb the gesture. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.rawDrag(onTap: (() -> Unit)? = null, onDrag: (Float, Float) -> Unit): Modifier = composed {
    val st = remember { FloatArray(4) }
    val mv = remember { BooleanArray(1) }
    val slop = with(LocalDensity.current) { 8.dp.toPx() }
    pointerInteropFilter { e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { st[0] = e.rawX; st[1] = e.rawY; st[2] = e.rawX; st[3] = e.rawY; mv[0] = false }
            MotionEvent.ACTION_MOVE -> {
                if (!mv[0] && hypot(e.rawX - st[2], e.rawY - st[3]) > slop) mv[0] = true
                if (mv[0]) onDrag(e.rawX - st[0], e.rawY - st[1])
                st[0] = e.rawX; st[1] = e.rawY
            }
            MotionEvent.ACTION_UP -> if (!mv[0]) onTap?.invoke()
        }
        true
    }
}

fun smoothPath(pts: List<Offset>): Path {
    val p = Path()
    if (pts.isEmpty()) return p
    p.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 1) { p.lineTo(pts[0].x + 0.1f, pts[0].y); return p }
    for (i in 1 until pts.size) {
        val a = pts[i - 1]; val b = pts[i]
        p.quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
    }
    p.lineTo(pts.last().x, pts.last().y)
    return p
}

fun strokePaint(color: Int, width: Float, alpha: Float = 1f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE; strokeWidth = width
    strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    this.color = color; this.alpha = (alpha * 255).toInt()
}
