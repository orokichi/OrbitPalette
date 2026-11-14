package com.orokichi.orbitpalette

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ======================= Live handwriting pad (shared by Flux Search and the Flux keyboard) =======================

/**
 * Write anywhere on the pad. While writing, the best guess is previewed live; after a short pause the text is
 * committed and the ink clears, so you can keep writing continuously.
 */
@Composable
fun HandwritePad(
    modifier: Modifier = Modifier,
    preContext: () -> String = { "" },
    commitDelayMs: Long = 800,
    onPreview: (List<String>) -> Unit,
    onCommit: (String) -> Unit,
    onTap: ((Offset) -> Unit)? = null,
    inkWidthDp: Float = 4f,
    onScratch: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    clearKey: Int = 0,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val onScratchU by rememberUpdatedState(onScratch)
    val onTapU by rememberUpdatedState(onTap)
    val cs = MaterialTheme.colorScheme
    val onPreview by rememberUpdatedState(onPreview)
    val onCommit by rememberUpdatedState(onCommit)
    val preContext by rememberUpdatedState(preContext)
    val scope = rememberCoroutineScope()
    val strokes = remember { mutableStateListOf<InkStroke>() }
    var current by remember { mutableStateOf<InkStroke?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val jobs = remember { arrayOfNulls<Job>(2) }
    var best by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { InkRecognizer.prepare() }
    val inkColor = cs.primary.toArgb()
    LaunchedEffect(clearKey) { if (clearKey != 0) { jobs[0]?.cancel(); jobs[1]?.cancel(); best = ""; strokes.clear(); tick++ } }
    fun commitNow() {
        jobs[0]?.cancel(); jobs[1]?.cancel()
        if (best.isNotEmpty()) onCommit(best)
        best = ""; strokes.clear(); onPreview(emptyList()); tick++
    }
    Box(modifier.clipToBounds().pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(); down.consume()
            jobs[1]?.cancel()
            val s = InkStroke(0, inkWidthDp.dp.toPx()); s.add(down.position, down.uptimeMillis); current = s; tick++
            while (true) {
                val ev = awaitPointerEvent()
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                s.add(c.position, c.uptimeMillis); c.consume(); tick++
            }
            current = null
            val b = s.bounds()
            val tap = onTapU
            if (tap != null && strokes.isEmpty() && b.width < 12.dp.toPx() && b.height < 12.dp.toPx() && s.times.last() - s.times.first() < 300) {
                tick++; tap(down.position); return@awaitEachGesture
            }
            val sc = onScratchU
            if (sc != null && isScratch(s, 8.dp.toPx())) {
                // Galaxy-style scratch: erase pending ink, or delete the text under the stroke
                if (strokes.isNotEmpty()) { jobs[0]?.cancel(); jobs[1]?.cancel(); best = ""; strokes.clear(); onPreview(emptyList()) }
                else sc(b)
                tick++; return@awaitEachGesture
            }
            strokes.add(s); tick++
            jobs[0]?.cancel()
            jobs[0] = scope.launch {
                val c = InkRecognizer.candidates(strokes.toList(), preContext())
                best = c.firstOrNull() ?: ""
                onPreview(c)
            }
            jobs[1] = scope.launch { delay(commitDelayMs); jobs[0]?.join(); commitNow() }
        }
    }.drawBehind {
        tick
        drawIntoCanvas { cv ->
            val n = cv.nativeCanvas
            strokes.forEach { n.drawPath(smoothPath(it.pts), strokePaint(inkColor, it.width)) }
            current?.let { n.drawPath(smoothPath(it.pts), strokePaint(inkColor, it.width)) }
        }
    }) { content() }
}

/** Zig-zag "scratch out" stroke: several left/right reversals over a mostly horizontal area. */
fun isScratch(s: InkStroke, minPx: Float): Boolean {
    if (s.pts.size < 8) return false
    val b = s.bounds()
    if (b.width < minPx * 3 || b.height > b.width * 1.3f) return false
    var ext = s.pts[0].x; var dir = 0; var rev = 0; var len = 0f
    for (i in s.pts.indices) {
        val x = s.pts[i].x
        if (i > 0) len += (s.pts[i] - s.pts[i - 1]).getDistance()
        when {
            dir == 0 -> if (kotlin.math.abs(x - ext) > minPx) { dir = if (x > ext) 1 else -1; ext = x }
            dir == 1 -> if (x > ext) ext = x else if (ext - x > minPx) { dir = -1; ext = x; rev++ }
            else -> if (x < ext) ext = x else if (x - ext > minPx) { dir = 1; ext = x; rev++ }
        }
    }
    return rev >= 3 && len > b.width * 2.5f
}

// ======================= Flux Search =======================

sealed class FluxHit {
    data class AppHit(val app: AppEntry) : FluxHit()
    data class Contact(val name: String, val number: String?, val uri: Uri) : FluxHit()
    data class Media(val uri: Uri, val video: Boolean) : FluxHit()
    data class Row(val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: String, val sub: String, val intent: Intent) : FluxHit()
}

class FluxResults(
    val calc: String? = null,
    val apps: List<AppEntry> = emptyList(),
    val contacts: List<FluxHit.Contact> = emptyList(),
    val photos: List<FluxHit.Media> = emptyList(),
    val videos: List<FluxHit.Media> = emptyList(),
    val events: List<FluxHit.Row> = emptyList(),
    val music: List<FluxHit.Row> = emptyList(),
    val files: List<FluxHit.Row> = emptyList(),
    val settings: List<FluxHit.Row> = emptyList()
)

object FluxEngine {
    val perms: Array<String> get() = buildList {
        if (Build.VERSION.SDK_INT >= 33) { add(Manifest.permission.READ_MEDIA_IMAGES); add(Manifest.permission.READ_MEDIA_VIDEO); add(Manifest.permission.READ_MEDIA_AUDIO) }
        else add(Manifest.permission.READ_EXTERNAL_STORAGE)
        add(Manifest.permission.READ_CONTACTS); add(Manifest.permission.READ_CALENDAR)
    }.toTypedArray()

    fun has(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    fun allFiles() = Environment.isExternalStorageManager()

    private fun kana(s: String) = buildString { s.lowercase().forEach { append(if (it in 'ァ'..'ヶ') it - 0x60 else it) } }

    private val settingsList = listOf(
        "Wi-Fi ワイファイ ネットワーク インターネット" to Settings.ACTION_WIFI_SETTINGS,
        "Bluetooth ブルートゥース" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "ディスプレイ 画面 明るさ ダークモード" to Settings.ACTION_DISPLAY_SETTINGS,
        "音 サウンド 着信音 音量 マナー" to Settings.ACTION_SOUND_SETTINGS,
        "バッテリー 電池" to Intent.ACTION_POWER_USAGE_SUMMARY,
        "通知" to Settings.ACTION_APP_NOTIFICATION_SETTINGS,
        "アプリ" to Settings.ACTION_APPLICATION_SETTINGS,
        "ストレージ 容量" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
        "位置情報 GPS" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "セキュリティ ロック 指紋" to Settings.ACTION_SECURITY_SETTINGS,
        "ユーザー補助 アクセシビリティ" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
        "キーボード 入力 言語" to Settings.ACTION_INPUT_METHOD_SETTINGS,
        "日付 時刻 時間" to Settings.ACTION_DATE_SETTINGS,
        "開発者向けオプション 開発者" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        "機内モード" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
        "設定" to Settings.ACTION_SETTINGS
    )

    /** "今日", "昨日", "2025", "11月", "2025年11月" → [from, to) in millis. */
    private fun dateRange(q: String): LongRange? {
        val cal = java.util.Calendar.getInstance()
        fun startOfDay(c: java.util.Calendar) = c.apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        when (q.trim()) {
            "今日", "きょう" -> { val s = startOfDay(cal).timeInMillis; return s until s + 86_400_000 }
            "昨日", "きのう" -> { val s = startOfDay(cal).timeInMillis - 86_400_000; return s until s + 86_400_000 }
            "今週" -> { val e = System.currentTimeMillis(); return e - 7 * 86_400_000L until e }
            "今月" -> { val c = startOfDay(cal).apply { set(java.util.Calendar.DAY_OF_MONTH, 1) }; return c.timeInMillis until System.currentTimeMillis() }
        }
        Regex("^(\\d{4})年?(?:(\\d{1,2})月)?$").find(q.trim())?.let { m ->
            val y = m.groupValues[1].toInt(); val mo = m.groupValues[2].toIntOrNull()
            val c = startOfDay(java.util.Calendar.getInstance()).apply { set(y, (mo ?: 1) - 1, 1) }
            val s = c.timeInMillis; c.add(if (mo == null) java.util.Calendar.YEAR else java.util.Calendar.MONTH, 1)
            return s until c.timeInMillis
        }
        Regex("^(\\d{1,2})月$").find(q.trim())?.let { m ->
            val c = startOfDay(java.util.Calendar.getInstance()).apply { set(java.util.Calendar.MONTH, m.groupValues[1].toInt() - 1); set(java.util.Calendar.DAY_OF_MONTH, 1) }
            if (c.timeInMillis > System.currentTimeMillis()) c.add(java.util.Calendar.YEAR, -1)
            val s = c.timeInMillis; c.add(java.util.Calendar.MONTH, 1); return s until c.timeInMillis
        }
        return null
    }

    private fun calc(q: String): String? {
        val e = q.replace('×', '*').replace('÷', '/').replace('ー', '-').replace('−', '-').replace(" ", "")
        if (!Regex("^[0-9.+\\-*/()]+$").matches(e) || !Regex("[+\\-*/]").containsMatchIn(e.drop(1))) return null
        var i = 0
        fun num(): Double {
            if (e.getOrNull(i) == '(') { i++; val v = expr(); i++; return v }
            if (e.getOrNull(i) == '-') { i++; return -num() }
            val s = i; while (i < e.length && (e[i].isDigit() || e[i] == '.')) i++
            return e.substring(s, i).toDouble()
        }
        fun term(): Double { var v = num(); while (i < e.length && (e[i] == '*' || e[i] == '/')) { val o = e[i++]; val r = num(); v = if (o == '*') v * r else v / r }; return v }
        fun exprImpl(): Double { var v = term(); while (i < e.length && (e[i] == '+' || e[i] == '-')) { val o = e[i++]; val r = term(); v = if (o == '+') v + r else v - r }; return v }
        expr = ::exprImpl
        return runCatching { val v = exprImpl(); if (i != e.length || v.isNaN()) null else if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else "%.8f".format(v).trimEnd('0').trimEnd('.') }.getOrNull()
    }
    private var expr: () -> Double = { 0.0 }

    suspend fun search(ctx: Context, raw: String): FluxResults = withContext(Dispatchers.IO) {
        val q = raw.trim()
        if (q.isEmpty()) return@withContext FluxResults(photos = media(ctx, null, null, false, 24))
        val kq = kana(q)
        val apps = runCatching { Apps.load(ctx).filter { kana(it.label).contains(kq) }.take(12) }.getOrDefault(emptyList())
        val range = dateRange(q)
        FluxResults(
            calc = calc(q),
            apps = apps,
            contacts = contacts(ctx, q),
            photos = media(ctx, if (range == null) q else null, range, false, 30),
            videos = media(ctx, if (range == null) q else null, range, true, 20),
            events = events(ctx, q),
            music = music(ctx, q),
            files = files(ctx, q),
            settings = settingsList.filter { (k, _) -> kana(k).contains(kq) }.map { (k, a) -> FluxHit.Row(Icons.Rounded.Settings, k.substringBefore(' '), "設定", Intent(a)) }
        )
    }

    private fun contacts(ctx: Context, q: String): List<FluxHit.Contact> {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = LinkedHashMap<Long, FluxHit.Contact>()
        runCatching {
            ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q)),
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY),
                null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < 10) {
                    val id = c.getLong(0)
                    if (id !in out) out[id] = FluxHit.Contact(c.getString(1) ?: "", c.getString(2), ContactsContract.Contacts.getLookupUri(id, c.getString(3)))
                }
            }
        }
        if (out.size < 10) runCatching {
            ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(q)),
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.LOOKUP_KEY), null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < 10) {
                    val id = c.getLong(0)
                    if (id !in out) out[id] = FluxHit.Contact(c.getString(1) ?: "", null, ContactsContract.Contacts.getLookupUri(id, c.getString(2)))
                }
            }
        }
        return out.values.toList()
    }

    private fun media(ctx: Context, q: String?, range: LongRange?, video: Boolean, limit: Int): List<FluxHit.Media> {
        val perm = if (Build.VERSION.SDK_INT >= 33) (if (video) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES) else Manifest.permission.READ_EXTERNAL_STORAGE
        if (!has(ctx, perm)) return emptyList()
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val sel = StringBuilder(); val args = ArrayList<String>()
        if (q != null) { sel.append("(${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} LIKE ?)"); args += "%$q%"; args += "%$q%" }
        if (range != null) { sel.append("${MediaStore.MediaColumns.DATE_TAKEN} >= ? AND ${MediaStore.MediaColumns.DATE_TAKEN} < ?"); args += range.first.toString(); args += range.last.plus(1).toString() }
        val out = ArrayList<FluxHit.Media>()
        runCatching {
            ctx.contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID), sel.toString().ifEmpty { null }, args.toTypedArray().ifEmpty { null },
                "${MediaStore.MediaColumns.DATE_TAKEN} DESC")?.use { c ->
                while (c.moveToNext() && out.size < limit) out += FluxHit.Media(ContentUris.withAppendedId(base, c.getLong(0)), video)
            }
        }
        return out
    }

    private fun events(ctx: Context, q: String): List<FluxHit.Row> {
        if (!has(ctx, Manifest.permission.READ_CALENDAR)) return emptyList()
        val out = ArrayList<FluxHit.Row>()
        val fmt = java.text.SimpleDateFormat("yyyy/M/d H:mm", java.util.Locale.JAPAN)
        runCatching {
            ctx.contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.EVENT_LOCATION),
                "${CalendarContract.Events.TITLE} LIKE ? OR ${CalendarContract.Events.EVENT_LOCATION} LIKE ?", arrayOf("%$q%", "%$q%"), "${CalendarContract.Events.DTSTART} DESC")?.use { c ->
                while (c.moveToNext() && out.size < 8) {
                    val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, c.getLong(0))
                    out += FluxHit.Row(Icons.Rounded.Event, c.getString(1) ?: "(無題)", fmt.format(java.util.Date(c.getLong(2))) + (c.getString(3)?.takeIf { it.isNotBlank() }?.let { "・$it" } ?: ""),
                        Intent(Intent.ACTION_VIEW, uri))
                }
            }
        }
        return out
    }

    private fun music(ctx: Context, q: String): List<FluxHit.Row> {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (!has(ctx, perm)) return emptyList()
        val out = ArrayList<FluxHit.Row>()
        runCatching {
            ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST),
                "${MediaStore.Audio.Media.TITLE} LIKE ? OR ${MediaStore.Audio.Media.ARTIST} LIKE ? OR ${MediaStore.Audio.Media.ALBUM} LIKE ?", arrayOf("%$q%", "%$q%", "%$q%"), null)?.use { c ->
                while (c.moveToNext() && out.size < 8) {
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    out += FluxHit.Row(Icons.Rounded.MusicNote, c.getString(1) ?: "", c.getString(2) ?: "", Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            }
        }
        return out
    }

    private fun files(ctx: Context, q: String): List<FluxHit.Row> {
        val out = ArrayList<FluxHit.Row>()
        val uri = MediaStore.Files.getContentUri("external")
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.RELATIVE_PATH),
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} NOT IN (1,2,3)", arrayOf("%$q%"),
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < 10) {
                    val u = ContentUris.withAppendedId(uri, c.getLong(0))
                    val mime = c.getString(2) ?: "*/*"
                    out += FluxHit.Row(Icons.Rounded.Description, c.getString(1) ?: "", c.getString(3) ?: "",
                        Intent(Intent.ACTION_VIEW).setDataAndType(u, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            }
        }
        return out
    }

    fun thumb(ctx: Context, uri: Uri): ImageBitmap? = runCatching { ctx.contentResolver.loadThumbnail(uri, Size(320, 320), null).asImageBitmap() }.getOrNull()

    fun imeEnabled(ctx: Context) = ctx.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == ctx.packageName }
    fun imeDefault(ctx: Context) = (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "").startsWith(ctx.packageName + "/")
}

class FluxSearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("flux", MODE_PRIVATE)
        setContent {
            OrbitTheme {
                var tutorial by remember { mutableStateOf(!prefs.getBoolean("tutorialDone", false)) }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    if (tutorial) FluxTutorial { prefs.edit().putBoolean("tutorialDone", true).apply(); tutorial = false }
                    else FluxSearchScreen(onClose = { finish() }, onHelp = { tutorial = true })
                }
            }
        }
    }
}

@Composable
private fun FluxBadge(size: Int = 96) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.28f).dp))
        .background(Brush.linearGradient(listOf(cs.surfaceContainerLowest, cs.primaryContainer))), contentAlignment = Alignment.Center) {
        Icon(FluxIcon, null, tint = cs.primary, modifier = Modifier.size((size * 0.55f).dp))
    }
}

@Composable
private fun FluxTutorial(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var page by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle.lifecycle.addObserver(o); onDispose { lifecycle.lifecycle.removeObserver(o) }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(0.6f))
        FluxBadge(if (page == 0) 120 else 84)
        Spacer(Modifier.height(28.dp))
        refresh
        when (page) {
            0 -> {
                Text("Flux Search", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("アプリ・連絡先・写真・動画・音楽・ファイル・予定・設定を、ひとつの検索ですばやく見つけます。", textAlign = TextAlign.Center, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }
            1 -> {
                Text("書いて探す", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("キーボードは使いません。検索ボックスに指やペンで直接書くと、その場で文字に変わります。少し手を止めると確定、続けて書けます。\n「今日」「11月」「2025」と書けばその日の写真や動画も探せます。", textAlign = TextAlign.Center, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }
            2 -> {
                Text("アクセスを許可", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                val runtimeOk = FluxEngine.perms.all { FluxEngine.has(ctx, it) }
                TutorialStep("写真・動画・音楽・連絡先・カレンダー", runtimeOk) { launcher.launch(FluxEngine.perms) }
                Spacer(Modifier.height(10.dp))
                TutorialStep("すべてのファイル（書類など）", FluxEngine.allFiles()) {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))) }
                }
            }
            else -> {
                Text("どこでも手書き入力", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("Flux 手書きキーボードをデフォルトにすると、どのアプリでも入力ボックスの上に直接書いて入力できます（ユーザー補助オンで位置を検出）。", textAlign = TextAlign.Center, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                TutorialStep("1. キーボードを有効にする", FluxEngine.imeEnabled(ctx)) { runCatching { ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) } }
                Spacer(Modifier.height(10.dp))
                TutorialStep("2. デフォルトに設定", FluxEngine.imeDefault(ctx)) { ctx.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { repeat(4) { i -> Box(Modifier.size(if (i == page) 22.dp else 8.dp, 8.dp).clip(CircleShape).background(if (i == page) cs.primary else cs.outlineVariant)) } }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onDone) { Text("スキップ") }
            Button(onClick = { if (page < 3) page++ else onDone() }, modifier = Modifier.height(52.dp)) { Text(if (page < 3) "次へ" else "はじめる", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun TutorialStep(title: String, done: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = { if (!done) onClick() }, shape = RoundedCornerShape(24.dp), color = if (done) cs.secondaryContainer else cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (done) Icon(Icons.Rounded.CheckCircle, "完了", tint = cs.primary) else Text("許可", color = cs.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FluxSearchScreen(onClose: () -> Unit, onHelp: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf(listOf<String>()) }
    var typing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(FluxResults()) }
    val shown = query + (preview.firstOrNull() ?: "")
    LaunchedEffect(shown) { delay(150); results = FluxEngine.search(ctx, shown) }
    fun open(i: Intent) = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "閉じる") }
            Text("Flux Search", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { typing = !typing }) { Icon(if (typing) Icons.Rounded.Draw else Icons.Rounded.Keyboard, "入力方法") }
            IconButton(onClick = onHelp) { Icon(Icons.AutoMirrored.Rounded.HelpOutline, "チュートリアル") }
        }
        // The search box IS the writing area.
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(if (typing) 64.dp else 180.dp).clip(RoundedCornerShape(28.dp))
            .background(cs.surfaceContainerHigh).border(2.dp, if (shown.isEmpty()) cs.outlineVariant else cs.primary, RoundedCornerShape(28.dp))) {
            if (typing) {
                BasicTextField(query, { query = it }, singleLine = true, textStyle = MaterialTheme.typography.headlineSmall.copy(color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary), modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp))
            } else HandwritePad(Modifier.fillMaxSize(), preContext = { query }, commitDelayMs = 700,
                onPreview = { preview = it }, onCommit = { query += it; preview = emptyList() }) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(FluxIcon, null, tint = cs.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(buildAnnotatedQuery(query, preview.firstOrNull() ?: "", cs.primary), style = MaterialTheme.typography.headlineSmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = query.dropLast(1) }) { Icon(Icons.AutoMirrored.Rounded.Backspace, "1文字削除") }
                        IconButton(onClick = { query = ""; preview = emptyList() }) { Icon(Icons.Rounded.Close, "クリア") }
                    }
                }
                if (shown.isEmpty()) Text("ここに手書きで検索", color = cs.onSurfaceVariant.copy(alpha = 0.5f), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.align(Alignment.Center).padding(top = 24.dp))
            }
        }
        if (preview.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            preview.forEach { c -> SuggestionChip(onClick = { query += c; preview = emptyList() }, label = { Text(c) }) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            results.calc?.let { v -> item { Card(colors = CardDefaults.cardColors(containerColor = cs.tertiaryContainer)) { Text("= $v", Modifier.padding(18.dp), style = MaterialTheme.typography.headlineMedium) } } }
            if (results.apps.isNotEmpty()) item {
                Section("アプリ")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(results.apps) { a ->
                        Column(Modifier.width(72.dp).clip(RoundedCornerShape(16.dp)).clickable { open(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.cmp)) }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(a.icon, a.label, Modifier.size(48.dp))
                            Text(a.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (results.contacts.isNotEmpty()) item {
                Section("連絡先")
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer)) {
                    results.contacts.forEach { c ->
                        ListItem(headlineContent = { Text(c.name) }, supportingContent = if (c.number != null) { { Text(c.number) } } else null,
                            leadingContent = { Box(Modifier.size(40.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) { Text(c.name.take(1), color = cs.onPrimaryContainer) } },
                            trailingContent = if (c.number != null) { { IconButton(onClick = { open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + c.number))) }) { Icon(Icons.Rounded.Call, "電話") } } } else null,
                            colors = ListItemDefaults.colors(containerColor = cs.surfaceContainer),
                            modifier = Modifier.clickable { open(Intent(Intent.ACTION_VIEW, c.uri)) })
                    }
                }
            }
            if (results.photos.isNotEmpty()) item { Section(if (shown.isEmpty()) "最近の写真" else "写真"); MediaRow(results.photos) { open(Intent(Intent.ACTION_VIEW).setDataAndType(it.uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } }
            if (results.videos.isNotEmpty()) item { Section("動画"); MediaRow(results.videos) { open(Intent(Intent.ACTION_VIEW).setDataAndType(it.uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } }
            listOf("予定" to results.events, "音楽" to results.music, "ファイル" to results.files, "設定" to results.settings).forEach { (title, rows) ->
                if (rows.isNotEmpty()) item {
                    Section(title)
                    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer)) {
                        rows.forEach { r ->
                            ListItem(headlineContent = { Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = if (r.sub.isNotBlank()) { { Text(r.sub, maxLines = 1, overflow = TextOverflow.Ellipsis) } } else null,
                                leadingContent = { Icon(r.icon, null, tint = cs.primary) },
                                colors = ListItemDefaults.colors(containerColor = cs.surfaceContainer),
                                modifier = Modifier.clickable { open(r.intent) })
                        }
                    }
                }
            }
            if (shown.isNotBlank()) item {
                OutlinedCard(onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(shown)))) }) {
                    ListItem(headlineContent = { Text("「$shown」をWebで検索") }, leadingContent = { Icon(Icons.Rounded.TravelExplore, null, tint = cs.primary) })
                }
            }
            val missing = FluxEngine.perms.any { !FluxEngine.has(ctx, it) }
            if (missing) item {
                TextButton(onClick = onHelp) { Text("権限を許可するともっと多くの情報を検索できます") }
            }
        }
    }
}

@Composable
private fun buildAnnotatedQuery(q: String, p: String, accent: androidx.compose.ui.graphics.Color) = androidx.compose.ui.text.buildAnnotatedString {
    append(q)
    if (p.isNotEmpty()) { pushStyle(androidx.compose.ui.text.SpanStyle(color = accent, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)); append(p); pop() }
}

@Composable
private fun Section(t: String) = Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))

@Composable
private fun MediaRow(list: List<FluxHit.Media>, onOpen: (FluxHit.Media) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { it.uri.toString() }) { m ->
            val bmp by produceState<ImageBitmap?>(null, m.uri) { value = withContext(Dispatchers.IO) { FluxEngine.thumb(ctx, m.uri) } }
            Box(Modifier.size(104.dp).clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerHigh).clickable { onOpen(m) }) {
                bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                if (m.video) Icon(Icons.Rounded.PlayCircle, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
            }
        }
    }
}
