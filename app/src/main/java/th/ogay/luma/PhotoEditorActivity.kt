package th.ogay.luma

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Photo editor: crop and rotate, light and colour, film looks and a pen. Nothing touches the original; "บันทึก"
 * writes a new JPEG next to it in Pictures/Lumora with the camera EXIF carried over, and it joins the Lumora list.
 *
 * Every edit is kept as numbers (turns, crop rectangle, adjustment values, look, pen strokes), so the preview and
 * the saved file are drawn by the same code at two sizes.
 */
class PhotoEditorActivity : ComponentActivity() {
    companion object {
        const val RESULT_ID = "id"
        fun intent(c: Context, uri: Uri): Intent = Intent(c, PhotoEditorActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        private val ASPECTS = listOf("อิสระ" to 0f, "ต้นฉบับ" to -1f, "1:1" to 1f, "4:3" to 4f / 3, "3:4" to 3f / 4, "16:9" to 16f / 9, "9:16" to 9f / 16)
        private val COLORS = intArrayOf(Color.WHITE, Color.BLACK, 0xffff453a.toInt(), 0xffff9f0a.toInt(), 0xffffd60a.toInt(), 0xff30d158.toInt(), 0xff0a84ff.toInt(), 0xffbf5af2.toInt())
        private val SIZES = listOf("เล็ก" to 0.006f, "กลาง" to 0.012f, "ใหญ่" to 0.024f)
    }
    /** A pen stroke in image coordinates (0..1 of the turned picture); [width] is a share of the shorter side. */
    class Stroke(val color: Int, val width: Float, val points: MutableList<PointF> = mutableListOf())

    // ---- Edit state ----
    private var turns = 0; private var flipped = false
    private val crop = RectF(0f, 0f, 1f, 1f); private var aspect = 0
    private val values = FloatArray(Develop.NAMES.size); private var adjustIndex = 0
    /** 8 colours × hue, saturation, luminance. */
    private val hsl = FloatArray(24); private var hslColor = 0
    private val curve = Develop.IDENTITY_CURVE
    private var curveChannel = 0
    private var curveHistogram: Array<FloatArray>? = null
    private var film: Film? = null; private var filmStrength = 1f
    private val strokes = mutableListOf<Stroke>(); private var penColor = COLORS[2]; private var penSize = 1

    private lateinit var canvasView: EditCanvas
    private lateinit var tools: FrameLayout
    private lateinit var toolBar: EditorKit.ToolBar
    private var tool = 0
    private var exif = ExifInterface.ORIENTATION_NORMAL
    private var saving = false
    private lateinit var saveButton: View
    private lateinit var source: Uri

    // ---- Undo / redo: a snapshot after each finished change ----
    private class Snap(val turns: Int, val flipped: Boolean, val crop: RectF, val aspect: Int, val values: FloatArray, val film: Film?, val strength: Float, val strokes: List<Stroke>, val hsl: FloatArray, val curve: FloatArray)
    private val history = mutableListOf<Snap>(); private var at = -1
    private lateinit var undoButton: View; private lateinit var redoButton: View
    private val commitLater = Runnable { commit() }
    private fun copyStrokes(list: List<Stroke>) = list.map { s -> Stroke(s.color, s.width, s.points.map { PointF(it.x, it.y) }.toMutableList()) }
    private fun snap() = Snap(turns, flipped, RectF(crop), aspect, values.copyOf(), film, filmStrength, copyStrokes(strokes), hsl.copyOf(), curve.copyOf())
    private fun same(a: Snap, b: Snap) = a.turns == b.turns && a.flipped == b.flipped && a.crop == b.crop && a.aspect == b.aspect && a.values.contentEquals(b.values) &&
        a.film?.id == b.film?.id && a.strength == b.strength && a.hsl.contentEquals(b.hsl) && a.curve.contentEquals(b.curve) && a.strokes.size == b.strokes.size && a.strokes.zip(b.strokes).all { (x, y) -> x.color == y.color && x.points.size == y.points.size }
    private fun commit() {
        canvasView.removeCallbacks(commitLater)
        val s = snap(); if (at >= 0 && same(history[at], s)) return
        while (history.size > at + 1) history.removeAt(history.lastIndex)
        history += s; if (history.size > 50) history.removeAt(0); at = history.lastIndex; markHistory()
    }
    /** A change that may continue (a slider): the preview follows at once, the undo step is taken once it rests. */
    private fun edited() { canvasView.refresh(); canvasView.removeCallbacks(commitLater); canvasView.postDelayed(commitLater, 450) }
    private fun stepHistory(d: Int) {
        commit()
        val t = at + d; if (t !in history.indices) return
        at = t; val s = history[at]
        turns = s.turns; flipped = s.flipped; crop.set(s.crop); aspect = s.aspect; s.values.copyInto(values); film = s.film; filmStrength = s.strength; s.hsl.copyInto(hsl); s.curve.copyInto(curve)
        strokes.clear(); strokes += copyStrokes(s.strokes)
        markHistory(); showTool(tool, false); canvasView.refresh(true)
    }
    private fun markHistory() {
        if (!::undoButton.isInitialized) return
        val u = at > 0; val r = at < history.lastIndex
        undoButton.isEnabled = u; redoButton.isEnabled = r
        undoButton.animate().alpha(if (u) 1f else 0.32f).setDuration(160).start(); redoButton.animate().alpha(if (r) 1f else 0.32f).setDuration(160).start()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        source = intent.data ?: run { finish(); return }
        state?.let { restore(it) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        installRoot(root)
        // Top: close on the left; undo, redo, compare and save on the right. Icons only.
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(6)) }
        bar.addView(EditorKit.round(this, Icons.CLOSE, "ปิดตัวแก้ไข") { leave() }, dp(48), dp(48))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        undoButton = EditorKit.round(this, EditorKit.UNDO, "ย้อนกลับขั้นก่อน") { stepHistory(-1) }
        redoButton = EditorKit.round(this, EditorKit.REDO, "ทำซ้ำ") { stepHistory(1) }
        bar.addView(undoButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(6) })
        bar.addView(redoButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(14) })
        // Hold to see the original, release to return to the edit.
        bar.addView(FrameLayout(this).apply {
            background = glass(24, 0x40ffffff); contentDescription = "กดค้างเพื่อดูต้นฉบับ"; isClickable = true
            addView(IconView(this@PhotoEditorActivity, EditorKit.COMPARE, 2f), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnTouchListener { v, e -> when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { Glass.tick(v); canvasView.original = true; v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(120).start() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { canvasView.original = false; v.animate().scaleX(1f).scaleY(1f).setInterpolator(Glass.SPRING).setDuration(360).start(); if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick() }
            }; true }
            setOnClickListener { }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(8) })
        // AI detail for this picture: a sparkle pill in the accent colour, like the AI buttons elsewhere.
        bar.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER; background = glass(24, Glass.ACCENT_SOFT); isClickable = true; contentDescription = "ปรับด้วย AI"; glassTouch()
            setPadding(dp(12), 0, dp(14), 0)
            addView(IconView(this@PhotoEditorActivity, Icons.AI, 1.9f).apply { tint = Glass.ACCENT }, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(text("AI", 14f, Glass.ACCENT, true).apply { setPadding(dp(6), 0, 0, 0) })
            setOnClickListener { Glass.tick(this); enhance() }
        }, LinearLayout.LayoutParams(-2, dp(48)).apply { rightMargin = dp(8) })
        saveButton = EditorKit.roundAccent(this, Icons.CHECK, "บันทึก") { save() }
        bar.addView(saveButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(bar, -1, -2)

        canvasView = EditCanvas(this)
        root.addView(canvasView, LinearLayout.LayoutParams(-1, 0, 1f))
        tools = FrameLayout(this).apply { clipChildren = false }
        root.addView(tools, LinearLayout.LayoutParams(-1, dp(172)))
        toolBar = EditorKit.ToolBar(this, listOf(EditorKit.CROP to "ครอบตัด", EditorKit.ADJUST to "ปรับ", HSL_ICON to "สี", CURVE_ICON to "เส้นโค้ง", Icons.FILM to "ฟิลเตอร์", EditorKit.PEN to "วาด")) { showTool(it) }
        root.addView(toolBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(8), dp(2), dp(8), dp(8)) })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = leave() })
        markHistory()

        luma.readIo.execute {
            val loaded = runCatching { load(source, 1_600_000) }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (loaded == null) { notice("เปิดภาพนี้ไม่ได้"); finish(); return@runOnUiThread }
                canvasView.bitmap = loaded; canvasView.refresh(); showTool(tool, false); commit()
                if (!Glass.reduced(this)) { canvasView.alpha = 0f; canvasView.scaleX = 0.96f; canvasView.scaleY = 0.96f; canvasView.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(Glass.EASE).setDuration(320).start() }
            }
        }
    }

    private fun changed() = turns != 0 || flipped || crop != RectF(0f, 0f, 1f, 1f) || values.any { it != 0f } || film != null || strokes.isNotEmpty() || hsl.any { it != 0f } || !curve.contentEquals(Develop.IDENTITY_CURVE)
    private fun leave() {
        if (saving) return
        if (!changed()) { finish(); return }
        EditorKit.askSave(this, "บันทึกการแก้ไขไหม", "บันทึกเป็นภาพใหม่ ต้นฉบับยังอยู่ครบ", "บันทึก", { save() }, { finish() })
    }

    // ---------- Tools ----------
    private fun showTool(i: Int, animate: Boolean = true) {
        val dir = if (animate) i.compareTo(tool) else 0
        val modeChange = (tool == 0) != (i == 0)
        tool = i; toolBar.select(i, animate)
        // The curve and colour mixer need more room than one strip of dials; the picture gives way smoothly.
        val want = dp(when (i) { 3 -> 268; 2 -> 206; else -> 172 })
        if (tools.layoutParams.height != want) {
            val from = tools.layoutParams.height
            if (!animate || Glass.reduced(this)) { tools.layoutParams.height = want; tools.requestLayout() }
            else android.animation.ValueAnimator.ofInt(from, want).apply { duration = 260; interpolator = Glass.EASE
                addUpdateListener { tools.layoutParams.height = it.animatedValue as Int; tools.requestLayout() } }.start()
        }
        canvasView.mode = i; canvasView.refresh(animate && modeChange)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        when (i) {
            0 -> cropTools(col)
            1 -> adjustTools(col)
            2 -> hslTools(col)
            3 -> curveTools(col)
            4 -> filmTools(col)
            else -> penTools(col)
        }
        EditorKit.swap(tools, col, dir)
    }

    private fun cropTools(col: LinearLayout) {
        val items = ASPECTS.map { (name, _) -> EditorKit.StripItem(this, when (name) { "อิสระ" -> EditorKit.FREE; "ต้นฉบับ" -> Icons.PHOTO; else -> aspectIconPath(name) }, name) }
        col.addView(EditorKit.strip(this, items.map { it to 62 }, aspect, 60, { k -> items.forEachIndexed { j, v -> v.lit(j == k) } }) { k ->
            aspect = k; fitAspect(); canvasView.refresh(true); commit()
        }, LinearLayout.LayoutParams(-1, dp(68)))
        col.addView(EditorKit.actions(this, listOf(
            EditorKit.action(this, EditorKit.ROTATE, "หมุน 90°") { turn() },
            EditorKit.action(this, Icons.MIRROR, "กลับด้าน") { flip() },
            EditorKit.action(this, Icons.RESET, "คืนค่า") { turns = 0; flipped = false; crop.set(0f, 0f, 1f, 1f); aspect = 0; commit(); showTool(0, false); canvasView.refresh(true) })),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }
    /** Turn a quarter clockwise. A turn of a mirrored picture equals a mirror of a picture turned the other way. */
    private fun turn() {
        turns = ((if (flipped) turns - 1 else turns + 1) + 4) % 4
        crop.set(1f - crop.bottom, crop.left, 1f - crop.top, crop.right)
        strokes.forEach { s -> s.points.forEach { p -> val u = p.x; p.x = 1f - p.y; p.y = u } }
        val r = ASPECTS[aspect].second; if (r > 0f) { aspect = ASPECTS.indexOfFirst { abs(it.second - 1f / r) < 0.01f }.takeIf { it >= 0 } ?: 0; showTool(0, false) }
        canvasView.refresh(); commit()
        // The new picture turns in from where the old one was.
        if (!Glass.reduced(this)) { canvasView.rotation = -90f; canvasView.scaleX = 0.86f; canvasView.scaleY = 0.86f; canvasView.animate().rotation(0f).scaleX(1f).scaleY(1f).setInterpolator(Glass.EASE).setDuration(380).start() }
    }
    private fun flip() {
        flipped = !flipped
        crop.set(1f - crop.right, crop.top, 1f - crop.left, crop.bottom)
        strokes.forEach { s -> s.points.forEach { p -> p.x = 1f - p.x } }
        canvasView.refresh(); commit()
        if (!Glass.reduced(this)) { canvasView.scaleX = -1f; canvasView.animate().scaleX(1f).setInterpolator(Glass.EASE).setDuration(380).start() }
    }
    /** Largest centred crop with the chosen shape. */
    private fun fitAspect() {
        val r = ASPECTS[aspect].second; if (r == 0f) return
        val (w, h) = canvasView.turnedSize(); if (w == 0 || h == 0) return
        val want = if (r < 0f) w.toFloat() / h else r
        // Width and height as shares of the picture, for a crop whose pixel shape is [want].
        var cw = 1f; var ch = w / want / h
        if (ch > 1f) { ch = 1f; cw = h * want / w }
        crop.set((1f - cw) / 2, (1f - ch) / 2, (1f + cw) / 2, (1f + ch) / 2)
    }

    private val HSL_ICON = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18 M12 3v18 M3.5 8.5l17 7 M3.5 15.5l17-7"
    private val CURVE_ICON = "M4 20h16 M4 4v16 M4 18c5 0 6-12 16-14"
    private val PEN = 5
    private fun sign(v: Float) = (v * 100).roundToInt().let { if (it > 0) "+$it" else "$it" }
    private fun adjustTools(col: LinearLayout) {
        // One strip of dials (light, colour, effects in that order); swipe through them.
        val name = text("", 12f, Glass.TEXT2, true).apply { gravity = Gravity.CENTER }
        val value = text("", 15f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        val dials = Develop.NAMES.mapIndexed { k, n -> EditorKit.Dial(this, Develop.ICONS[k], n).apply { amount = values[k] } }
        val slider = GlassSlider(this).apply { values = (-100..100).map { if (it > 0) "+$it" else "$it" }; grad = intArrayOf(0xff8e8e93.toInt(), Color.WHITE, Glass.ACCENT) }
        fun show(k: Int) {
            adjustIndex = k; dials.forEachIndexed { j, d -> d.lit = j == k }
            name.text = Develop.NAMES[k]; value.text = sign(values[k]); value.setTextColor(if (values[k] == 0f) Glass.TEXT2 else Glass.ACCENT)
            slider.index = (values[k] * 100).roundToInt() + 100; slider.contentDescription = Develop.NAMES[k]
        }
        lateinit var strip: CenterStrip
        strip = EditorKit.strip(this, dials.map { it to 60 }, adjustIndex, 0, { k -> show(k) },
            tapAgain = { k -> if (values[k] != 0f) { values[k] = 0f; dials[k].amount = 0f; Glass.tick(dials[k]); show(k); edited() } }) { k -> show(k) }
        col.addView(LinearLayout(this).apply { gravity = Gravity.CENTER; addView(name); addView(value.apply { setPadding(dp(8), 0, 0, 0) }) }, -1, -2)
        col.addView(strip, LinearLayout.LayoutParams(-1, dp(60)))
        var zero = values[adjustIndex] == 0f
        slider.onChange = { idx ->
            val v = (idx - 100) / 100f; values[adjustIndex] = v; dials[adjustIndex].amount = v
            value.text = sign(v); value.setTextColor(if (v == 0f) Glass.TEXT2 else Glass.ACCENT)
            // A firmer tick when the value passes through zero, like a detent.
            if ((v == 0f) != zero) { zero = v == 0f; if (zero) Glass.tick(slider, android.view.HapticFeedbackConstants.CONTEXT_CLICK) }
            edited()
        }
        col.addView(slider, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(12), 0, dp(12), 0) })
        show(adjustIndex)
    }

    /** Per-colour hue, saturation and luminance, like Lightroom's colour mixer. */
    private fun hslTools(col: LinearLayout) {
        val dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val views = Develop.HSL_COLORS.mapIndexed { k, color -> View(this).apply {
            isClickable = true; contentDescription = "สี" + Develop.HSL_NAMES[k]
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color); setStroke(dp(if (k == hslColor) 3 else 0), Color.WHITE) }
            if (k == hslColor) { scaleX = 1.18f; scaleY = 1.18f }
            setOnClickListener { Glass.tick(this); hslColor = k; showTool(2, false) }
        }.also { dots.addView(it, LinearLayout.LayoutParams(dp(30), dp(30)).apply { setMargins(dp(7), dp(6), dp(7), dp(6)) }) } }
        col.addView(dots, -1, -2)
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(12), 0) }
        head.addView(View(this).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Develop.HSL_COLORS[hslColor]) } }, dp(10), dp(10))
        head.addView(text("สี" + Develop.HSL_NAMES[hslColor], 13f, Color.WHITE, true).apply { setPadding(dp(8), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        // Reset: this colour first; when it is already untouched, every colour.
        val anyThis = (0 until 3).any { hsl[hslColor * 3 + it] != 0f }
        head.addView(EditorKit.chip(this, if (anyThis) "คืนค่าสีนี้" else "คืนค่าทุกสี", Icons.RESET) {
            if ((0 until 3).any { hsl[hslColor * 3 + it] != 0f }) for (j in 0 until 3) hsl[hslColor * 3 + j] = 0f else hsl.fill(0f)
            canvasView.refresh(); commit(); showTool(2, false)
        }.apply { alpha = if (hsl.any { it != 0f }) 1f else 0.45f }, LinearLayout.LayoutParams(-2, dp(32)))
        col.addView(head, LinearLayout.LayoutParams(-1, dp(36)))
        listOf("เฉดสี" to 0, "ความอิ่ม" to 1, "ความสว่าง" to 2).forEach { (label, part) ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(8), 0) }
            row.addView(text(label, 12f, Glass.TEXT2, true), dp(64), -2)
            val idx = hslColor * 3 + part
            val slider = GlassSlider(this).apply { values = (-100..100).map { if (it > 0) "+$it" else "$it" }; index = (hsl[idx] * 100).roundToInt() + 100
                contentDescription = "$label สี${Develop.HSL_NAMES[hslColor]}"
                grad = when (part) { 0 -> intArrayOf(Develop.HSL_COLORS[(hslColor + 7) % 8], Develop.HSL_COLORS[hslColor], Develop.HSL_COLORS[(hslColor + 1) % 8]); 1 -> intArrayOf(0xff8e8e93.toInt(), Develop.HSL_COLORS[hslColor]); else -> intArrayOf(0xff1c1c1e.toInt(), Develop.HSL_COLORS[hslColor], Color.WHITE) }
                onChange = { i -> hsl[idx] = (i - 100) / 100f; views[hslColor].alpha = 1f; edited() } }
            row.addView(slider, LinearLayout.LayoutParams(0, dp(40), 1f))
            col.addView(row, -1, -2)
        }
    }

    /** Point curve with RGB, red, green and blue channels, like Lightroom. */
    private fun curveTools(col: LinearLayout) {
        val view = CurveView(this, curve).apply { channel = curveChannel; histogram = curveHistogram; onChange = { canvasView.refresh() }; onRelease = { commit() } }
        if (curveHistogram == null) canvasView.bitmap?.let { b -> luma.readIo.execute { val h = CurveView.histogram(b); runOnUiThread { curveHistogram = h; view.histogram = h } } }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, dp(12), 0) }
        val name = text(Develop.CURVE_NAMES[curveChannel], 13f, Color.WHITE, true)
        val dots = ArrayList<View>()
        fun paint() = dots.forEachIndexed { k, v ->
            (v.background as android.graphics.drawable.GradientDrawable).setStroke(dp(if (k == curveChannel) 3 else 0), Glass.ACCENT)
            v.animate().scaleX(if (k == curveChannel) 1.15f else 1f).scaleY(if (k == curveChannel) 1.15f else 1f).setDuration(160).start()
        }
        for (k in 0 until 4) View(this).apply {
            contentDescription = "ช่อง " + Develop.CURVE_NAMES[k]; isClickable = true
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Develop.CURVE_COLORS[k]) }
            setOnClickListener { Glass.tick(this); curveChannel = k; view.channel = k; name.text = Develop.CURVE_NAMES[k]; paint() }
        }.also { dots += it; top.addView(it, LinearLayout.LayoutParams(dp(24), dp(24)).apply { setMargins(dp(6), 0, dp(6), 0) }) }
        top.addView(name.apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(EditorKit.chip(this, "คืนค่า", Icons.RESET) {
            // Resets the channel on screen; when it is already straight, all four channels.
            val base = curveChannel * 5
            val straight = (0 until 5).all { kotlin.math.abs(curve[base + it] - it * 0.25f) < 1e-4f }
            if (straight) Develop.IDENTITY_CURVE.copyInto(curve) else for (n in 0 until 5) curve[base + n] = n * 0.25f
            view.invalidate(); canvasView.refresh(); commit()
        }, LinearLayout.LayoutParams(-2, dp(32)))
        paint()
        col.addView(top, LinearLayout.LayoutParams(-1, dp(40)))
        col.addView(view, LinearLayout.LayoutParams(-1, dp(220)))
    }

    private fun filmTools(col: LinearLayout) {
        val thumb = canvasView.bitmap?.let { b -> val s = 144f / max(b.width, b.height); Bitmap.createScaledBitmap(b, max(1, (b.width * s).toInt()), max(1, (b.height * s).toInt()), true) }
        val looks = listOf<Film?>(null) + Films.groups.flatMap { Films.inGroup(it) }
        val cards = looks.map { EditorKit.FilmCard(this, thumb, it) }
        val strength = GlassSlider(this).apply { values = (0..100).map { "$it%" }; index = (filmStrength * 100).toInt(); grad = intArrayOf(0xff8e8e93.toInt(), Glass.ACCENT); contentDescription = "ความเข้มฟิลเตอร์" }
        fun mark(k: Int) { cards.forEachIndexed { j, c -> c.lit(j == k) }; strength.animate().alpha(if (looks[k] == null) 0f else 1f).setDuration(180).start(); strength.isEnabled = looks[k] != null }
        val start = looks.indexOfFirst { it?.id == film?.id }.coerceAtLeast(0)
        // The look follows the strip while it moves, so sliding through the looks previews each one.
        col.addView(EditorKit.strip(this, cards.map { it to 70 }, start, 90, { k -> mark(k); if (film?.id != looks[k]?.id) { film = looks[k]; if (film != null && filmStrength == 0f) { filmStrength = 1f; strength.index = 100 }; canvasView.refresh() } }) { k ->
            film = looks[k]; mark(k); edited()
        }, LinearLayout.LayoutParams(-1, dp(96)))
        strength.onChange = { i -> filmStrength = i / 100f; edited() }
        col.addView(strength, LinearLayout.LayoutParams(-1, dp(44)).apply { setMargins(dp(12), 0, dp(12), 0) })
        mark(start)
    }

    private fun penTools(col: LinearLayout) {
        val swatches = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0) }
        val dots = mutableListOf<View>()
        fun markDots(animate: Boolean) = dots.forEachIndexed { k, v ->
            val on = COLORS[k] == penColor
            v.background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(COLORS[k]); setStroke(dp(if (on) 3 else 1), if (on) Color.WHITE else 0x66ffffff) }
            v.isSelected = on
            val s = if (on) 1.18f else 1f
            if (animate && !Glass.reduced(this)) v.animate().scaleX(s).scaleY(s).setInterpolator(Glass.SPRING).setDuration(320).start() else { v.scaleX = s; v.scaleY = s }
        }
        COLORS.forEachIndexed { k, color ->
            val v = View(this).apply { isClickable = true; contentDescription = "สีปากกา ${k + 1}"; setOnClickListener { Glass.tick(this); penColor = color; markDots(true) } }
            dots += v; swatches.addView(v, LinearLayout.LayoutParams(dp(32), dp(32)).apply { setMargins(dp(6), dp(8), dp(6), dp(8)) })
        }
        markDots(false)
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(swatches) }, -1, -2)
        val sizes = SIZES.mapIndexed { k, (n, _) -> EditorKit.action(this, EditorKit.dot(2.5f + k * 2.5f), n, k == penSize) { penSize = k; showTool(PEN, false) } }
        col.addView(EditorKit.actions(this, sizes + listOf(
            EditorKit.action(this, EditorKit.UNDO, "ลบเส้นล่าสุด") { if (strokes.isNotEmpty()) { strokes.removeAt(strokes.lastIndex); canvasView.refresh(); commit() } },
            EditorKit.action(this, EditorKit.ERASE, "ล้างทั้งหมด") { if (strokes.isNotEmpty()) { strokes.clear(); canvasView.refresh(); commit() } })).apply { setPadding(dp(4), 0, dp(4), 0) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    // ---------- Pixels ----------
    /** Decodes the picture at most [maxPixels] in size; the EXIF turn is kept aside and applied when drawing. */
    private fun load(uri: Uri, maxPixels: Long): Bitmap? {
        exif = runCatching { contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var s = 1; while (bounds.outWidth.toLong() * bounds.outHeight / (s.toLong() * s) > maxPixels) s *= 2
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.ARGB_8888 }) }
    }

    /** Matrix from decoded pixels to the turned picture, and that picture's size: first the EXIF turn, then the editor's. */
    fun orient(w: Int, h: Int): Pair<Matrix, Point> {
        val m = Matrix()
        when (exif) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        }
        m.postRotate(turns * 90f); if (flipped) m.postScale(-1f, 1f)
        val r = RectF(0f, 0f, w.toFloat(), h.toFloat()); m.mapRect(r); m.postTranslate(-r.left, -r.top)
        return m to Point(r.width().toInt(), r.height().toInt())
    }
    fun develop() = Develop(values, hsl, curve, EditorKit.film(film, filmStrength))

    /** Draws the finished picture: turned, cropped, coloured, vignette and pen strokes. Shared by preview thumbnails and saving. */
    private fun render(src: Bitmap): Bitmap {
        val (m, size) = orient(src.width, src.height)
        val left = crop.left * size.x; val top = crop.top * size.y
        val w = max(1, (crop.width() * size.x).toInt()); val h = max(1, (crop.height() * size.y).toInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); m.postTranslate(-left, -top)
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        develop().apply(out)
        drawVignette(c, RectF(0f, 0f, w.toFloat(), h.toFloat()), values[Develop.VIGNETTE])
        val shorter = min(size.x, size.y).toFloat()
        drawStrokes(c, strokes, { x -> x * size.x - left }, { y -> y * size.y - top }, shorter)
        return out
    }

    /**
     * AI detail from the editor. Unchanged: the picture itself goes to the AI panel. Edited: the edit is saved as a
     * new picture first and that one is enhanced, so the AI works on what is on screen. Once the job is queued the
     * editor closes and the gallery shows its progress.
     */
    private fun enhance() {
        if (canvasView.bitmap == null) return
        val done: (Shot) -> Unit = { shot -> setResult(RESULT_OK, Intent().putExtra(RESULT_ID, shot.id)); finish() }
        if (!changed()) { th.ogay.luma.ai.EnhanceUi.showForUri(this, source, done); return }
        save { id -> setResult(RESULT_OK, Intent().putExtra(RESULT_ID, id))
            luma.readIo.execute { val shot = luma.shots.get(id); runOnUiThread { if (!isDestroyed && shot != null) th.ogay.luma.ai.EnhanceUi.show(this, shot) { done(shot) } } } }
    }

    private fun save(then: ((String) -> Unit)? = null) {
        val uri = source
        if (saving || canvasView.bitmap == null) return
        if (!changed()) { notice("ยังไม่มีการแก้ไข"); return }
        saving = true; saveButton.alpha = 0.5f; notice("กำลังบันทึก…")
        luma.fileIo.execute {
            val time = System.currentTimeMillis(); val id = UUID.randomUUID().toString()
            val file = File(cacheDir, "edit-$id.jpg")
            val result = runCatching {
                val full = load(uri, 16_000_000) ?: error("อ่านภาพไม่ได้")
                val out = render(full); full.recycle()
                PhotoEffects.write(out, file, 95)
                val dims = Dimensions(out.width, out.height); out.recycle()
                ExifCarry.write(file, ExifCarry.read(this, uri) + (ExifInterface.TAG_SOFTWARE to "Lumora Editor"))
                runCatching { ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "${ExifInterface.ORIENTATION_NORMAL}"); saveAttributes() } }
                val published = PhotoMedia.publish(this, file, "Lumora_EDIT_${time}.jpg", time)
                luma.shots.insert(Shot(id, time, published, dims, 1f, true, null, null, Decision.OFF, Stage.ORIGINAL, null, 0, null, false))
                id
            }
            file.delete()
            runOnUiThread {
                saving = false; saveButton.alpha = 1f
                result.onSuccess { if (then != null) { notice("บันทึกการแก้ไขแล้ว"); then(it) } else { notice("บันทึกเป็นภาพใหม่แล้ว · ต้นฉบับยังอยู่"); setResult(RESULT_OK, Intent().putExtra(RESULT_ID, it)); finish() } }
                    .onFailure { notice("บันทึกไม่สำเร็จ · ${it.localizedMessage}") }
            }
        }
    }

    // ---------- State across recreation ----------
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("turns", turns); out.putBoolean("flipped", flipped); out.putFloatArray("crop", floatArrayOf(crop.left, crop.top, crop.right, crop.bottom))
        out.putFloatArray("hsl", hsl); out.putFloatArray("curve", curve)
        out.putInt("aspect", aspect); out.putFloatArray("values", values); out.putString("film", film?.id); out.putFloat("strength", filmStrength); out.putInt("tool", tool)
        out.putIntArray("sc", strokes.map { it.color }.toIntArray()); out.putFloatArray("sw", strokes.map { it.width }.toFloatArray())
        out.putFloatArray("sp", strokes.flatMap { s -> listOf(s.points.size.toFloat()) + s.points.flatMap { listOf(it.x, it.y) } }.toFloatArray())
    }
    private fun restore(s: Bundle) {
        turns = s.getInt("turns"); flipped = s.getBoolean("flipped"); s.getFloatArray("crop")?.let { crop.set(it[0], it[1], it[2], it[3]) }
        s.getFloatArray("hsl")?.takeIf { it.size == hsl.size }?.copyInto(hsl); Develop.loadCurve(s.getFloatArray("curve"), curve)
        aspect = s.getInt("aspect"); s.getFloatArray("values")?.takeIf { it.size == values.size }?.copyInto(values); film = Films.byId(s.getString("film")); filmStrength = s.getFloat("strength", 1f); tool = s.getInt("tool")
        val colors = s.getIntArray("sc") ?: return; val widths = s.getFloatArray("sw") ?: return; val pts = s.getFloatArray("sp") ?: return
        var k = 0
        for (i in colors.indices) { val n = pts[k++].toInt(); val st = Stroke(colors[i], widths[i]); repeat(n) { st.points += PointF(pts[k], pts[k + 1]); k += 2 }; strokes += st }
    }

    // ---------- Preview ----------
    /**
     * Shows the edit. In the crop tool the whole turned picture is shown with the crop frame on top (drag a corner,
     * an edge or the middle); in the other tools only the cropped part is shown, filling the space. The pen draws
     * in the pen tool.
     */
    inner class EditCanvas(context: Context) : View(context) {
        var bitmap: Bitmap? = null
        var mode = 0
        var original = false; set(v) { field = v; invalidate() }
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dim = Paint().apply { color = 0x99000000.toInt() }
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66ffffff }
        private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
        /** Where the whole turned picture lands on screen, and the visible clip. */
        private val image = RectF(); private val clip = RectF()
        private val draw = Matrix()

        init { contentDescription = "ภาพที่กำลังแก้ไข" }
        fun turnedSize(): Pair<Int, Int> { val b = bitmap ?: return 0 to 0; val (_, s) = orient(b.width, b.height); return s.x to s.y }
        private var glide: android.animation.ValueAnimator? = null
        /** Redraws the edit; with [animate] the picture glides from where it was to its new place (crop ↔ other tools). */
        /** The picture with the develop settings, rendered off the main thread; null until the first one is ready. */
        private var shown: Bitmap? = null; private var spare: Bitmap? = null
        private var source: IntArray? = null
        private var rendering = false; private var again = false
        private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        /** Renders the newest settings; while one render runs, later changes collapse into a single follow-up. */
        private fun develop() {
            val b = bitmap ?: return
            if (rendering) { again = true; return }
            rendering = true; again = false
            val d = this@PhotoEditorActivity.develop()
            val back = spare?.takeIf { !it.isRecycled && it.width == b.width && it.height == b.height } ?: Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
            spare = null
            worker.execute {
                val src = source ?: IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height); source = it }
                val work = src.copyOf()
                runCatching { d.apply(work, b.width, b.height); back.setPixels(work, 0, b.width, 0, 0, b.width, b.height) }
                post {
                    rendering = false
                    if (isAttachedToWindow) { spare = shown; shown = back; invalidate() }
                    if (again) develop()
                }
            }
        }
        override fun onDetachedFromWindow() { worker.shutdownNow(); super.onDetachedFromWindow() }
        fun refresh(animate: Boolean = false) {
            develop()
            val fromImage = RectF(image); val fromClip = RectF(clip)
            glide?.cancel(); layoutImage()
            if (animate && !Glass.reduced(context) && !fromImage.isEmpty && width > 0) {
                val toImage = RectF(image); val toClip = RectF(clip)
                fun mix(out: RectF, a: RectF, b: RectF, t: Float) = out.set(a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t, a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t)
                glide = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 380; interpolator = Glass.EASE
                    addUpdateListener { val t = it.animatedValue as Float; mix(image, fromImage, toImage, t); mix(clip, fromClip, toClip, t); place(); invalidate() }
                    start()
                }
            }
            invalidate()
        }
        private val base = Matrix(); private var turnedW = 1; private var turnedH = 1
        private fun place() { draw.set(base); draw.postScale(image.width() / turnedW, image.height() / turnedH); draw.postTranslate(image.left, image.top) }

        private fun layoutImage() {
            val b = bitmap ?: return; if (width == 0) return
            val (m, s) = orient(b.width, b.height)
            base.set(m); turnedW = max(1, s.x); turnedH = max(1, s.y)
            val pad = dp(if (mode == 0) 28 else 12).toFloat()
            val aw = width - 2 * pad; val ah = height - 2 * pad
            if (mode == 0) {
                val k = min(aw / s.x, ah / s.y); val w = s.x * k; val h = s.y * k
                image.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2); clip.set(image)
            } else {
                val cw = crop.width() * s.x; val ch = crop.height() * s.y
                val k = min(aw / cw, ah / ch); val w = cw * k; val h = ch * k
                clip.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
                image.set(clip.left - crop.left * s.x * k, clip.top - crop.top * s.y * k, 0f, 0f)
                image.right = image.left + s.x * k; image.bottom = image.top + s.y * k
            }
            place()
        }
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { layoutImage() }

        private fun cropRect() = RectF(image.left + crop.left * image.width(), image.top + crop.top * image.height(), image.left + crop.right * image.width(), image.top + crop.bottom * image.height())

        override fun onDraw(c: Canvas) {
            val b = bitmap ?: return
            c.save(); c.clipRect(clip)
            c.drawBitmap(if (original) b else shown ?: b, draw, paint)
            if (!original) {
                val visible = if (mode == 0) cropRect() else clip
                drawVignette(c, visible, values[Develop.VIGNETTE])
                val (sw, sh) = turnedSize()
                drawStrokes(c, strokes, { x -> image.left + x * image.width() }, { y -> image.top + y * image.height() }, min(sw, sh) * image.width() / max(1, sw))
            }
            c.restore()
            if (mode == 0) {
                val r = cropRect()
                c.drawRect(image.left, image.top, image.right, r.top, dim); c.drawRect(image.left, r.bottom, image.right, image.bottom, dim)
                c.drawRect(image.left, r.top, r.left, r.bottom, dim); c.drawRect(r.right, r.top, image.right, r.bottom, dim)
                grid.strokeWidth = dp(1).toFloat(); frame.strokeWidth = resources.displayMetrics.density * 1.5f
                for (i in 1..2) { val x = r.left + r.width() * i / 3; val y = r.top + r.height() * i / 3; c.drawLine(x, r.top, x, r.bottom, grid); c.drawLine(r.left, y, r.right, y, grid) }
                c.drawRect(r, frame)
                corner.strokeWidth = dp(4).toFloat(); val l = dp(18).toFloat()
                for ((x, dx) in listOf(r.left to l, r.right to -l)) for ((y, dy) in listOf(r.top to l, r.bottom to -l)) { c.drawLine(x, y, x + dx, y, corner); c.drawLine(x, y, x, y + dy, corner) }
            }
        }

        // ---- Touch: crop frame or pen ----
        private var grab = 0  // bit 1 left, 2 top, 4 right, 8 bottom; 15 = move
        private var lastX = 0f; private var lastY = 0f
        private var stroke: Stroke? = null
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (bitmap == null) return false
            if (mode == 0) return cropTouch(e)
            if (mode == PEN) return penTouch(e)
            return false
        }
        private fun cropTouch(e: MotionEvent): Boolean {
            val r = cropRect(); val near = dp(28).toFloat()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    grab = 0
                    if (abs(e.x - r.left) < near) grab = grab or 1; if (abs(e.x - r.right) < near) grab = grab or 4
                    if (abs(e.y - r.top) < near) grab = grab or 2; if (abs(e.y - r.bottom) < near) grab = grab or 8
                    if (e.y < r.top - near || e.y > r.bottom + near) grab = grab and 0b0101.inv()
                    if (e.x < r.left - near || e.x > r.right + near) grab = grab and 0b1010.inv()
                    if (ASPECTS[aspect].second != 0f && Integer.bitCount(grab) == 1) grab = 0
                    if (grab == 0 && r.contains(e.x, e.y)) grab = 15
                    lastX = e.x; lastY = e.y; parent?.requestDisallowInterceptTouchEvent(grab != 0)
                    return grab != 0
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.x - lastX) / image.width(); val dy = (e.y - lastY) / image.height(); lastX = e.x; lastY = e.y
                    moveCrop(dx, dy); invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (grab != 0) commit(); grab = 0 }
            }
            return true
        }
        private fun moveCrop(dx: Float, dy: Float) {
            val min = 0.08f
            if (grab == 15) {
                val x = dx.coerceIn(-crop.left, 1f - crop.right); val y = dy.coerceIn(-crop.top, 1f - crop.bottom); crop.offset(x, y); return
            }
            if (grab and 1 != 0) crop.left = (crop.left + dx).coerceIn(0f, crop.right - min)
            if (grab and 4 != 0) crop.right = (crop.right + dx).coerceIn(crop.left + min, 1f)
            if (grab and 2 != 0) crop.top = (crop.top + dy).coerceIn(0f, crop.bottom - min)
            if (grab and 8 != 0) crop.bottom = (crop.bottom + dy).coerceIn(crop.top + min, 1f)
            val ratio = ASPECTS[aspect].second; if (ratio == 0f) return
            // A fixed shape: the width leads, the height follows from the anchored corner and is clamped to the picture.
            val (w, h) = turnedSize(); val want = if (ratio < 0f) w.toFloat() / h else ratio
            var ch = crop.width() * w / want / h
            val anchorBottom = grab and 2 != 0
            val room = if (anchorBottom) crop.bottom else 1f - crop.top
            if (ch > room) { ch = room; val cw = ch * h * want / w; if (grab and 1 != 0) crop.left = crop.right - cw else crop.right = crop.left + cw }
            if (anchorBottom) crop.top = crop.bottom - ch else crop.bottom = crop.top + ch
        }
        private fun penTouch(e: MotionEvent): Boolean {
            val u = ((e.x - image.left) / image.width()).coerceIn(crop.left, crop.right); val v = ((e.y - image.top) / image.height()).coerceIn(crop.top, crop.bottom)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); stroke = Stroke(penColor, SIZES[penSize].second).also { it.points += PointF(u, v); strokes += it } }
                MotionEvent.ACTION_MOVE -> { val s = stroke ?: return true; val p = s.points.last()
                    if (hypot((u - p.x) * image.width(), (v - p.y) * image.height()) > 2f) s.points += PointF(u, v) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { if (stroke != null) commit(); stroke = null }
            }
            invalidate(); return true
        }
    }

    private fun drawVignette(c: Canvas, r: RectF, amount: Float) {
        if (amount == 0f || r.width() <= 0f) return
        val radius = hypot(r.width(), r.height()) / 2
        val edge = if (amount > 0) ((amount * 200).toInt() shl 24) else ((-amount * 160).toInt() shl 24) or 0xffffff
        c.drawRect(r, Paint().apply { shader = RadialGradient(r.centerX(), r.centerY(), radius, intArrayOf(0, 0, edge), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP) })
    }
    private fun drawStrokes(c: Canvas, list: List<Stroke>, x: (Float) -> Float, y: (Float) -> Float, shorter: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        for (s in list) {
            p.color = s.color; p.strokeWidth = max(1f, s.width * shorter)
            if (s.points.size == 1) { p.style = Paint.Style.FILL; c.drawCircle(x(s.points[0].x), y(s.points[0].y), p.strokeWidth / 2, p); p.style = Paint.Style.STROKE; continue }
            val path = Path(); path.moveTo(x(s.points[0].x), y(s.points[0].y))
            for (i in 1 until s.points.size) {
                val a = s.points[i - 1]; val b = s.points[i]
                path.quadTo(x(a.x), y(a.y), (x(a.x) + x(b.x)) / 2, (y(a.y) + y(b.y)) / 2)
            }
            path.lineTo(x(s.points.last().x), y(s.points.last().y)); c.drawPath(path, p)
        }
    }
}
