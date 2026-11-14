package com.orokichi.orbitpalette

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

// ======================= Kana tables / kana-kanji conversion =======================

object Kana {
    /** Flick: center, left, up, right, down (Galaxy / standard Japanese 10-key). */
    val kanaKeys = listOf(
        listOf("あ", "い", "う", "え", "お"), listOf("か", "き", "く", "け", "こ"), listOf("さ", "し", "す", "せ", "そ"),
        listOf("た", "ち", "つ", "て", "と"), listOf("な", "に", "ぬ", "ね", "の"), listOf("は", "ひ", "ふ", "へ", "ほ"),
        listOf("ま", "み", "む", "め", "も"), listOf("や", "（", "ゆ", "）", "よ"), listOf("ら", "り", "る", "れ", "ろ"),
        listOf("わ", "を", "ん", "ー", "〜"), listOf("、", "。", "？", "！", "…")
    )
    val engKeys = listOf(
        listOf("@", "#", "/", "&", "_"), listOf("a", "b", "c", "", ""), listOf("d", "e", "f", "", ""),
        listOf("g", "h", "i", "", ""), listOf("j", "k", "l", "", ""), listOf("m", "n", "o", "", ""),
        listOf("p", "q", "r", "s", ""), listOf("t", "u", "v", "", ""), listOf("w", "x", "y", "z", ""),
        listOf("'", "\"", "(", ")", ""), listOf(".", ",", "?", "!", "-")
    )
    val numKeys = listOf(
        listOf("1", "☆", "♪", "→", ""), listOf("2", "¥", "$", "€", ""), listOf("3", "%", "°", "#", ""),
        listOf("4", "○", "*", "・", ""), listOf("5", "+", "×", "÷", ""), listOf("6", "<", "=", ">", ""),
        listOf("7", "「", "」", ":", ""), listOf("8", "〒", "々", "〆", ""), listOf("9", "^", "|", "\\", ""),
        listOf("(", ")", "[", "]", ""), listOf("0", "~", "…", "", ""), listOf(".", ",", "-", "/", "")
    )

    private val cycles = listOf("かが", "きぎ", "くぐ", "けげ", "こご", "さざ", "しじ", "すず", "せぜ", "そぞ", "ただ", "ちぢ", "つっづ", "てで", "とど",
        "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ", "あぁ", "いぃ", "うぅゔ", "えぇ", "おぉ", "やゃ", "ゆゅ", "よょ", "わゎ")

    fun cycle(c: Char): Char? {
        val g = cycles.firstOrNull { c in it } ?: return null
        return g[(g.indexOf(c) + 1) % g.length]
    }

    fun kata(s: String) = buildString { s.forEach { append(if (it in 'ぁ'..'ゖ') it + 0x60 else it) } }

    private val roma: Map<String, String> = buildMap {
        val v = "aiueo"
        val rows = mapOf("" to "あいうえお", "k" to "かきくけこ", "s" to "さしすせそ", "t" to "たちつてと", "n" to "なにぬねの", "h" to "はひふへほ",
            "m" to "まみむめも", "y" to "やいゆいぇよ", "r" to "らりるれろ", "w" to "わうぃうぇを", "g" to "がぎぐげご", "z" to "ざじずぜぞ",
            "d" to "だぢづでど", "b" to "ばびぶべぼ", "p" to "ぱぴぷぺぽ", "x" to "ぁぃぅぇぉ", "l" to "ぁぃぅぇぉ")
        rows.forEach { (c, k) -> if (k.length == 5) v.forEachIndexed { i, ch -> put(c + ch, k[i].toString()) } }
        put("ya", "や"); put("yu", "ゆ"); put("yo", "よ"); put("ye", "いぇ"); put("wa", "わ"); put("wo", "を"); put("wi", "うぃ"); put("we", "うぇ")
        val yo = mapOf("ky" to "き", "sh" to "し", "sy" to "し", "ch" to "ち", "ty" to "ち", "cy" to "ち", "ny" to "に", "hy" to "ひ", "my" to "み", "ry" to "り",
            "gy" to "ぎ", "j" to "じ", "jy" to "じ", "zy" to "じ", "by" to "び", "py" to "ぴ", "dy" to "ぢ")
        yo.forEach { (c, k) -> put(c + "a", k + "ゃ"); put(c + "u", k + "ゅ"); put(c + "o", k + "ょ"); put(c + "e", k + "ぇ") }
        put("shi", "し"); put("chi", "ち"); put("tsu", "つ"); put("fu", "ふ"); put("ji", "じ"); put("fa", "ふぁ"); put("fi", "ふぃ"); put("fe", "ふぇ"); put("fo", "ふぉ")
        put("thi", "てぃ"); put("dhi", "でぃ"); put("xtu", "っ"); put("ltu", "っ"); put("xya", "ゃ"); put("xyu", "ゅ"); put("xyo", "ょ"); put("nn", "ん"); put("n'", "ん")
        put("va", "ゔぁ"); put("vi", "ゔぃ"); put("vu", "ゔ"); put("ve", "ゔぇ"); put("vo", "ゔぉ")
        put("-", "ー"); put(",", "、"); put(".", "。"); put("?", "？"); put("!", "！")
    }

    /** Feed romaji; returns (kana produced, romaji still pending). */
    fun feed(pending: String): Pair<String, String> {
        var r = pending.lowercase(); val out = StringBuilder()
        loop@ while (r.isNotEmpty()) {
            roma[r]?.let { out.append(it); r = ""; break@loop }
            if (r.length >= 2 && r[0] == r[1] && r[0] !in "aiueon" && r[0].isLetter()) { out.append("っ"); r = r.drop(1); continue }
            if (r.length >= 2 && r[0] == 'n' && r[1] !in "aiueoyn'") { out.append("ん"); r = r.drop(1); continue }
            if (roma.keys.any { it.startsWith(r) }) break
            // no possible match: emit the first letter as is
            roma[r.take(1)]?.let { out.append(it) } ?: out.append(r[0]); r = r.drop(1)
        }
        return out.toString() to r
    }
}

/** Kana → kanji candidates (Google CGI API for Japanese Input), with hiragana / katakana always available offline. */
object KanaKanji {
    private val cache = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<String>>?) = size > 200
    }

    suspend fun convert(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        cache[raw]?.let { return it }
        val net = withContext(Dispatchers.IO) {
            runCatching {
                val u = java.net.URL("https://www.google.com/transliterate?langpair=ja-Hira%7Cja&text=" + java.net.URLEncoder.encode(raw, "UTF-8"))
                val c = (u.openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 1500; readTimeout = 1500 }
                val body = c.inputStream.bufferedReader().use { it.readText() }
                val arr = org.json.JSONArray(body)
                val segs = (0 until arr.length()).map { i ->
                    val cs = arr.getJSONArray(i).getJSONArray(1)
                    (0 until cs.length()).map { cs.getString(it) }
                }
                if (segs.isEmpty()) emptyList() else buildList {
                    add(segs.joinToString("") { it.first() })
                    val rest = segs.drop(1).joinToString("") { it.first() }
                    segs[0].drop(1).forEach { add(it + rest) }
                    if (segs.size > 1) segs.last().drop(1).take(4).forEach { add(segs.dropLast(1).joinToString("") { s -> s.first() } + it) }
                }
            }.getOrDefault(emptyList())
        }
        val out = (net + listOf(raw, Kana.kata(raw))).distinct().take(14)
        if (net.isNotEmpty()) cache[raw] = out
        return out
    }
}

// ======================= Flux keyboard (Galaxy style) =======================

/**
 * Galaxy-style keyboard: 10-key flick (かな / 英字 / 数字), QWERTY (ローマ字かな / English) and handwriting mode.
 * Everything typed in Japanese goes into a composing buffer and is converted to kanji (space = 変換, enter = 確定).
 * In handwriting mode you can also write directly on the input box (S Pen-like), and a zig-zag scratch over text deletes it.
 */
class FluxIme : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {
    private val lr = LifecycleRegistry(this)
    private val ssrc = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lr
    override val savedStateRegistry: SavedStateRegistry get() = ssrc.savedStateRegistry
    private val scope = MainScope()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ime", MODE_PRIVATE) }

    /** 0 = 手書き, 1 = 10キー, 2 = QWERTY */
    private var mode by mutableIntStateOf(1)
    /** 10-key sub mode: 0 かな, 1 英字, 2 数字 */
    private var sub by mutableIntStateOf(0)
    private var qJa by mutableStateOf(true)
    private var shift by mutableStateOf(false)
    private var raw by mutableStateOf("")
    private var roma by mutableStateOf("")
    private var cands by mutableStateOf(listOf<String>())
    private var sel by mutableIntStateOf(-1)
    private var inkCands by mutableStateOf(listOf<String>())
    private var padClear by mutableIntStateOf(0)
    private var convJob: Job? = null

    // direct-on-field ink layer (handwriting mode)
    private var layer: View? = null
    private var layerWm: android.view.WindowManager? = null
    private var layerParams: android.view.WindowManager.LayoutParams? = null
    private var anchorField: android.graphics.Rect? = null

    override fun onCreate() {
        super.onCreate()
        ssrc.performRestore(null)
        lr.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        mode = prefs.getInt("mode", 1); qJa = prefs.getBoolean("qJa", true)
    }

    override fun onDestroy() { removeLayer(); scope.cancel(); lr.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY); super.onDestroy() }

    override fun onCreateInputView(): View {
        window.window?.decorView?.let { it.setViewTreeLifecycleOwner(this); it.setViewTreeSavedStateRegistryOwner(this) }
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FluxIme); setViewTreeSavedStateRegistryOwner(this@FluxIme)
            setContent { OrbitTheme { Keyboard() } }
        }
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        clearComposing(); anchorField = null
        val cls = (info?.inputType ?: 0) and EditorInfo.TYPE_MASK_CLASS
        if (cls == EditorInfo.TYPE_CLASS_NUMBER || cls == EditorInfo.TYPE_CLASS_PHONE) { if (mode == 1) sub = 2 } else if (sub == 2) sub = 0
        InkRecognizer.prepare()
        runCatching { currentInputConnection?.requestCursorUpdates(android.view.inputmethod.InputConnection.CURSOR_UPDATE_MONITOR or android.view.inputmethod.InputConnection.CURSOR_UPDATE_IMMEDIATE) }
        main.removeCallbacks(place); main.postDelayed(place, 120); main.postDelayed(place, 450)
    }

    override fun onFinishInputView(finishingInput: Boolean) { currentInputConnection?.finishComposingText(); clearComposing(); removeLayer() }
    override fun onWindowHidden() { removeLayer() }

    override fun onUpdateSelection(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int) {
        super.onUpdateSelection(a, b, c, d, e, f)
        // cursor moved away by the user → forget the composing state
        if ((raw.isNotEmpty() || roma.isNotEmpty()) && e < 0) clearComposing()
        main.removeCallbacks(place); main.postDelayed(place, 60)
    }

    override fun onUpdateCursorAnchorInfo(info: android.view.inputmethod.CursorAnchorInfo) {
        if (Build.VERSION.SDK_INT >= 33) info.editorBoundsInfo?.editorBounds?.let { rf ->
            val r = android.graphics.RectF(rf); info.matrix.mapRect(r)
            anchorField = android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
        }
        main.removeCallbacks(place); main.postDelayed(place, 60)
    }

    private fun setMode(m: Int) { commitAll(); mode = m; prefs.edit().putInt("mode", m).apply(); if (m == 0) main.post(place) else removeLayer() }

    // ---------------- composing / conversion ----------------
    private fun shown() = if (sel in cands.indices) cands[sel] else raw + roma

    private fun clearComposing() { convJob?.cancel(); raw = ""; roma = ""; cands = emptyList(); sel = -1; inkCands = emptyList() }

    private fun refresh() {
        currentInputConnection?.setComposingText(shown(), 1)
        convJob?.cancel()
        if (raw.isEmpty()) { cands = emptyList(); return }
        val r = raw
        convJob = scope.launch { delay(90); val c = KanaKanji.convert(r); if (isActive && raw == r) { cands = c } }
    }

    private fun addKana(t: String) { if (sel >= 0) { commitAll() }; raw += t; refresh() }

    private fun commitText(t: String) { commitAll(); currentInputConnection?.commitText(t, 1) }

    private fun commitAll() {
        val ic = currentInputConnection
        if (raw.isNotEmpty() || roma.isNotEmpty()) { ic?.setComposingText(shown(), 1); ic?.finishComposingText() }
        clearComposing()
    }

    private fun pick(c: String) { val ic = currentInputConnection ?: return; ic.setComposingText(c, 1); ic.finishComposingText(); clearComposing() }

    private fun convertKey() {
        if (raw.isEmpty() && roma.isEmpty()) { currentInputConnection?.commitText(" ", 1); return }
        if (roma.isNotEmpty()) { raw += Kana.feed(roma + "'").first.ifEmpty { roma }; roma = "" }
        if (cands.isEmpty()) { refresh(); return }
        sel = (sel + 1) % cands.size; currentInputConnection?.setComposingText(shown(), 1)
    }

    private fun smallKey() {
        if (sel >= 0) { sel = -1; refresh(); return }
        val c = raw.lastOrNull() ?: return
        Kana.cycle(c)?.let { raw = raw.dropLast(1) + it; refresh() }
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        when {
            roma.isNotEmpty() -> { roma = roma.dropLast(1); refresh() }
            sel >= 0 -> { sel = -1; refresh() }
            raw.isNotEmpty() -> { raw = raw.dropLast(1); if (raw.isEmpty()) { ic.setComposingText("", 1); ic.finishComposingText(); clearComposing() } else refresh() }
            else -> { val s = ic.getSelectedText(0); if (!s.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingTextInCodePoints(1, 0) }
        }
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        if (raw.isNotEmpty() || roma.isNotEmpty()) { commitAll(); return }
        val ei = currentInputEditorInfo
        val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (ei != null && (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0 && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED)
            ic.performEditorAction(action)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    private fun cursor(left: Boolean) { commitAll(); sendDownUpKeyEvents(if (left) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT) }

    private fun qwertyChar(ch: String) {
        val c = if (shift) ch.uppercase() else ch
        if (shift) shift = false
        if (!qJa || c[0].isUpperCase()) { commitText(c); return }
        if (sel >= 0) commitAll()
        val (k, rest) = Kana.feed(roma + c)
        raw += k; roma = rest; refresh()
    }

    private fun nextIme() { commitAll(); if (!switchToNextInputMethod(false)) getSystemService(InputMethodManager::class.java).showInputMethodPicker() }

    private fun pre() = currentInputConnection?.getTextBeforeCursor(20, 0)?.toString() ?: ""

    /** Handwriting result → composing buffer, so it can be converted to kanji like typed kana. */
    private fun inkCommit(t: String) { inkCands = emptyList(); if (t.isNotEmpty()) addKana(t) }
    private fun inkPreview(c: List<String>) { inkCands = c; currentInputConnection?.setComposingText(raw + (c.firstOrNull() ?: ""), 1) }

    /** Scratch on the keyboard pad: delete what is being composed, else the last character. */
    private fun padScratch() { if (raw.isNotEmpty() || roma.isNotEmpty()) { currentInputConnection?.setComposingText("", 1); currentInputConnection?.finishComposingText(); clearComposing() } else backspace() }

    /** Scratch on the field: delete the characters under the zig-zag. */
    private fun fieldScratch(r: androidx.compose.ui.geometry.Rect) {
        val p = layerParams ?: return
        val ic = currentInputConnection ?: return
        commitAll()
        val sr = android.graphics.RectF(p.x + r.left, p.y + r.top, p.x + r.right, p.y + r.bottom)
        val cb = OrbitA11yService.instance?.focusedCharBounds()
        if (cb == null) { backspace(); return }
        val (start, rects) = cb
        val hit = rects.indices.filter { i ->
            val c = rects[i] ?: return@filter false
            c.centerX() in sr.left..sr.right && c.bottom > sr.top - c.height() * 0.3f && c.top < sr.bottom + c.height() * 0.3f
        }
        if (hit.isEmpty()) return
        val a = start + hit.first(); val b = start + hit.last() + 1
        ic.beginBatchEdit(); ic.setSelection(a, b); ic.commitText("", 1); ic.endBatchEdit()
    }

    // ---------------- direct-on-field ink layer ----------------
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private val place = Runnable { placeLayer() }

    private fun placeLayer() {
        if (!isInputViewShown || mode != 0) { if (mode != 0) removeLayer(); return }
        val r = OrbitA11yService.instance?.focusedInputBounds() ?: anchorField ?: run { removeLayer(); return }
        val sw = resources.displayMetrics.widthPixels
        val padV = maxOf(dp(28f), (dp(120f) - r.height()) / 2f).toInt()
        val x = (r.left - dp(12f).toInt()).coerceAtLeast(0)
        val w = (r.right + dp(12f).toInt()).coerceAtMost(sw) - x
        val y = (r.top - padV).coerceAtLeast(0)
        val h = r.height() + padV * 2
        val a11y = OrbitA11yService.instance
        val wm = (a11y ?: this).getSystemService(android.view.WindowManager::class.java)
        val type = if (a11y != null) android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else if (Settings.canDrawOverlays(this)) android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else return
        val p = layerParams
        if (layer != null && p != null && layerWm === wm && p.type == type) {
            if (p.x != x || p.y != y || p.width != w || p.height != h) { p.x = x; p.y = y; p.width = w; p.height = h; runCatching { wm.updateViewLayout(layer, p) } }
            return
        }
        removeLayer()
        val np = android.view.WindowManager.LayoutParams(w, h, type,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START; this.x = x; this.y = y
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val v = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FluxIme); setViewTreeSavedStateRegistryOwner(this@FluxIme)
            setContent { OrbitTheme { InkLayer() } }
        }
        if (runCatching { wm.addView(v, np) }.isSuccess) { layer = v; layerWm = wm; layerParams = np }
    }

    private fun removeLayer() {
        main.removeCallbacks(place)
        layer?.let { v -> runCatching { layerWm?.removeView(v) } }
        layer = null; layerWm = null; layerParams = null
    }

    private fun passTap(pos: Offset) {
        val p = layerParams ?: return
        val v = layer ?: return
        val wm = layerWm ?: return
        commitAll()
        p.flags = p.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, p) }
        OrbitA11yService.instance?.tapAt(p.x + pos.x, p.y + pos.y)
        main.postDelayed({
            if (layer === v) { p.flags = p.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv(); runCatching { wm.updateViewLayout(v, p) } }
            main.postDelayed(place, 80)
        }, 250)
    }

    @Composable
    private fun InkLayer() {
        val cs = MaterialTheme.colorScheme
        HandwritePad(
            Modifier.fillMaxSize().border(1.5.dp, cs.primary.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
            preContext = { pre() }, commitDelayMs = 750, inkWidthDp = 3.5f,
            onPreview = { inkPreview(it) }, onCommit = { inkCommit(it) },
            onTap = { passTap(it) }, onScratch = { fieldScratch(it) }, clearKey = padClear
        )
    }

    // ---------------- UI ----------------
    @Composable
    private fun Keyboard() {
        val cs = MaterialTheme.colorScheme
        Column(Modifier.fillMaxWidth().background(cs.surfaceContainer).navigationBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp)) {
            CandidateBar()
            when (mode) {
                0 -> HandPanel()
                2 -> QwertyPanel()
                else -> FlickPanel()
            }
        }
    }

    @Composable
    private fun CandidateBar() {
        val cs = MaterialTheme.colorScheme
        val list = if (inkCands.isNotEmpty()) inkCands else cands
        Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
            if (list.isEmpty()) {
                // Galaxy toolbar
                ToolIcon(Icons.Rounded.Gesture, "手書き", mode == 0) { setMode(0) }
                ToolIcon(Icons.Rounded.Dialpad, "10キー", mode == 1) { setMode(1) }
                ToolIcon(Icons.Rounded.Keyboard, "QWERTY", mode == 2) { setMode(2) }
                Spacer(Modifier.weight(1f))
                ToolIcon(Icons.Rounded.KeyboardHide, "閉じる", false) { commitAll(); requestHideSelf(0) }
            } else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                list.forEachIndexed { i, c ->
                    val on = inkCands.isEmpty() && i == sel
                    Box(Modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(10.dp)).background(if (on) cs.primaryContainer else Color.Transparent)
                        .clickable {
                            if (inkCands.isNotEmpty()) { padClear++; inkCommit(c) } else pick(c)
                        }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(c, fontSize = 18.sp, color = if (on) cs.onPrimaryContainer else cs.onSurface, maxLines = 1)
                    }
                    if (i < list.lastIndex) Box(Modifier.size(1.dp, 18.dp).background(cs.outlineVariant))
                }
            }
        }
    }

    @Composable
    private fun ToolIcon(icon: ImageVector, desc: String, on: Boolean, onClick: () -> Unit) {
        val cs = MaterialTheme.colorScheme
        Box(Modifier.padding(horizontal = 4.dp).size(40.dp).clip(CircleShape).background(if (on) cs.primaryContainer else Color.Transparent).clickable(onClick = onClick),
            contentAlignment = Alignment.Center) { Icon(icon, desc, tint = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant) }
    }

    /** Plain key. [repeat] = auto-repeat while held (backspace). */
    @Composable
    private fun Key(modifier: Modifier, label: String? = null, icon: ImageVector? = null, kind: Int = 0, repeat: Boolean = false, onClick: () -> Unit) {
        val cs = MaterialTheme.colorScheme
        val bg = when (kind) { 1 -> cs.surfaceContainerHighest; 2 -> cs.primary; else -> cs.surfaceBright }
        val fg = if (kind == 2) cs.onPrimary else cs.onSurface
        val click by rememberUpdatedState(onClick)
        Box(modifier.padding(2.dp).clip(RoundedCornerShape(10.dp)).background(bg)
            .pointerInput(repeat) {
                awaitEachGesture {
                    val d = awaitFirstDown(); d.consume(); click()
                    if (!repeat) { while (awaitPointerEvent().changes.any { it.pressed }) {}; return@awaitEachGesture }
                    val start = System.currentTimeMillis()
                    var next = start + 400
                    while (true) {
                        val ev = withTimeoutOrNull(30) { awaitPointerEvent() }
                        if (ev != null && ev.changes.none { it.pressed }) break
                        val now = System.currentTimeMillis()
                        if (now >= next) { click(); next = now + 60 }
                    }
                }
            }, contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, label, tint = fg, modifier = Modifier.size(22.dp))
            else Text(label ?: "", color = fg, fontSize = if ((label?.length ?: 0) > 2) 13.sp else 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }

    /** Flick key: center / left / up / right / down. Shows the chosen character while flicking (Galaxy style). */
    @Composable
    private fun FlickKey(modifier: Modifier, chars: List<String>, onChar: (String) -> Unit) {
        val cs = MaterialTheme.colorScheme
        var dir by remember { mutableIntStateOf(-1) }
        val onCharU by rememberUpdatedState(onChar)
        Box(modifier.padding(2.dp).clip(RoundedCornerShape(10.dp)).background(if (dir >= 0) cs.primaryContainer else cs.surfaceBright)
            .pointerInput(chars) {
                val th = 22.dp.toPx()
                awaitEachGesture {
                    val d = awaitFirstDown(); d.consume(); dir = 0
                    var last = d.position
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == d.id } ?: break
                        if (!c.pressed) break
                        last = c.position; c.consume()
                        val v = last - d.position
                        val nd = if (v.getDistance() < th) 0 else if (abs(v.x) > abs(v.y)) (if (v.x < 0) 1 else 3) else (if (v.y < 0) 2 else 4)
                        dir = if (chars.getOrNull(nd).isNullOrEmpty()) 0 else nd
                    }
                    chars.getOrNull(dir)?.takeIf { it.isNotEmpty() }?.let { onCharU(it) }
                    dir = -1
                }
            }, contentAlignment = Alignment.Center) {
            if (dir > 0) Text(chars[dir], fontSize = 26.sp, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
            else {
                Text(chars[0], fontSize = 20.sp, color = cs.onSurface, fontWeight = FontWeight.Medium)
                val hint = chars.drop(1).filter { it.isNotEmpty() }.joinToString("")
                if (hint.isNotEmpty() && dir < 0) Text(hint, fontSize = 8.sp, color = cs.onSurfaceVariant, maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp))
            }
        }
    }

    @Composable
    private fun FlickPanel() {
        val composing = raw.isNotEmpty() || roma.isNotEmpty()
        val keys = when (sub) { 1 -> Kana.engKeys; 2 -> Kana.numKeys; else -> Kana.kanaKeys }
        val onChar: (String) -> Unit = { s -> if (sub == 0) addKana(s) else commitText(if (sub == 1 && shift) s.uppercase() else s) }
        val rowH = Modifier.height(54.dp)
        Column {
            Row(rowH) {
                Key(Modifier.weight(1f).fillMaxHeight(), "←", Icons.AutoMirrored.Rounded.KeyboardArrowLeft, 1) { cursor(true) }
                (0..2).forEach { FlickKey(Modifier.weight(1f).fillMaxHeight(), keys[it], onChar) }
                Key(Modifier.weight(1f).fillMaxHeight(), "削除", Icons.AutoMirrored.Rounded.Backspace, 1, repeat = true) { backspace() }
            }
            Row(rowH) {
                Key(Modifier.weight(1f).fillMaxHeight(), "→", Icons.AutoMirrored.Rounded.KeyboardArrowRight, 1) { cursor(false) }
                (3..5).forEach { FlickKey(Modifier.weight(1f).fillMaxHeight(), keys[it], onChar) }
                Key(Modifier.weight(1f).fillMaxHeight(), if (composing) "変換" else "空白", kind = 1) { convertKey() }
            }
            Row(rowH) {
                Key(Modifier.weight(1f).fillMaxHeight(), "手書き", Icons.Rounded.Gesture, 1) { setMode(0) }
                (6..8).forEach { FlickKey(Modifier.weight(1f).fillMaxHeight(), keys[it], onChar) }
                Key(Modifier.weight(1f).fillMaxHeight(), when (sub) { 0 -> "あ→A"; 1 -> "A→1"; else -> "1→あ" }, kind = 1) { commitAll(); sub = (sub + 1) % 3 }
            }
            Row(rowH) {
                Key(Modifier.weight(1f).fillMaxHeight(), "切替", Icons.Rounded.Language, 1) { nextIme() }
                when (sub) {
                    0 -> Key(Modifier.weight(1f).fillMaxHeight(), "小゛゜", kind = 1) { smallKey() }
                    1 -> Key(Modifier.weight(1f).fillMaxHeight(), if (shift) "A/a" else "a/A", kind = 1) { shift = !shift }
                    else -> FlickKey(Modifier.weight(1f).fillMaxHeight(), keys[9], onChar)
                }
                FlickKey(Modifier.weight(1f).fillMaxHeight(), keys[if (sub == 2) 10 else 9], onChar)
                FlickKey(Modifier.weight(1f).fillMaxHeight(), keys[if (sub == 2) 11 else 10], onChar)
                Key(Modifier.weight(1f).fillMaxHeight(), if (composing) "確定" else "改行", Icons.AutoMirrored.Rounded.KeyboardReturn.takeIf { !composing }, 2) { enter() }
            }
        }
    }

    @Composable
    private fun QwertyPanel() {
        val composing = raw.isNotEmpty() || roma.isNotEmpty()
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val rowH = Modifier.height(52.dp)
        Column {
            Row(rowH) { rows[0].forEach { c -> Key(Modifier.weight(1f).fillMaxHeight(), if (shift) c.uppercase() else c.toString()) { qwertyChar(c.toString()) } } }
            Row(rowH.padding(horizontal = 14.dp)) { rows[1].forEach { c -> Key(Modifier.weight(1f).fillMaxHeight(), if (shift) c.uppercase() else c.toString()) { qwertyChar(c.toString()) } } }
            Row(rowH) {
                Key(Modifier.weight(1.5f).fillMaxHeight(), "Shift", Icons.Rounded.ArrowUpward, if (shift) 2 else 1) { shift = !shift }
                rows[2].forEach { c -> Key(Modifier.weight(1f).fillMaxHeight(), if (shift) c.uppercase() else c.toString()) { qwertyChar(c.toString()) } }
                Key(Modifier.weight(1.5f).fillMaxHeight(), "削除", Icons.AutoMirrored.Rounded.Backspace, 1, repeat = true) { backspace() }
            }
            Row(rowH) {
                Key(Modifier.weight(1.2f).fillMaxHeight(), "切替", Icons.Rounded.Language, 1) { nextIme() }
                Key(Modifier.weight(1.2f).fillMaxHeight(), if (qJa) "かな" else "英", kind = 1) { commitAll(); qJa = !qJa; prefs.edit().putBoolean("qJa", qJa).apply() }
                Key(Modifier.weight(1f).fillMaxHeight(), if (qJa) "、" else ",") { if (qJa) addKana("、") else commitText(",") }
                Key(Modifier.weight(3.4f).fillMaxHeight(), if (composing) "変換" else "空白") { convertKey() }
                Key(Modifier.weight(1f).fillMaxHeight(), if (qJa) "。" else ".") { if (qJa) addKana("。") else commitText(".") }
                Key(Modifier.weight(1.6f).fillMaxHeight(), if (composing) "確定" else "改行", Icons.AutoMirrored.Rounded.KeyboardReturn.takeIf { !composing }, 2) { enter() }
            }
        }
    }

    /** Galaxy handwriting mode: big pad + side keys. Also write directly on the input box (ink layer). */
    @Composable
    private fun HandPanel() {
        val cs = MaterialTheme.colorScheme
        val composing = raw.isNotEmpty() || roma.isNotEmpty()
        Row(Modifier.fillMaxWidth().height(222.dp)) {
            HandwritePad(
                Modifier.weight(1f).fillMaxHeight().padding(2.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceBright),
                preContext = { pre() }, onPreview = { inkPreview(it) }, onCommit = { inkCommit(it) },
                onScratch = { padScratch() }, clearKey = padClear
            ) {
                Text("ここに書く・入力ボックスにも直接書けます\nこすると消去", fontSize = 11.sp, color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center))
            }
            Column(Modifier.width(72.dp).fillMaxHeight()) {
                Key(Modifier.weight(1f).fillMaxWidth(), "削除", Icons.AutoMirrored.Rounded.Backspace, 1, repeat = true) { backspace() }
                Key(Modifier.weight(1f).fillMaxWidth(), if (composing) "変換" else "空白", kind = 1) { convertKey() }
                Key(Modifier.weight(1f).fillMaxWidth(), "切替", Icons.Rounded.Language, 1) { nextIme() }
                Key(Modifier.weight(1f).fillMaxWidth(), if (composing) "確定" else "改行", Icons.AutoMirrored.Rounded.KeyboardReturn.takeIf { !composing }, 2) { enter() }
            }
        }
    }
}

