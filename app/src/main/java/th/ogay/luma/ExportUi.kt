package th.ogay.luma

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.ceil

/** What the export sheet hands back: the short side in pixels, the frame rate and whether AI may enlarge. */
data class ExportChoice(val shortSide: Int, val fps: Float, val ai: Boolean)

/**
 * Export sheet in the style of TikTok's: the clip at the top, then two sliders (resolution and frame rate) like
 * the photo adjustments. Going above the source shows what will make the difference: AI for more pixels,
 * optical flow for more frames, with a rough time and file size.
 */
object ExportSheet {
    val SIDES = listOf(480, 720, 1080, 1440, 2160)
    val SIDE_NAMES = listOf("480p", "720p", "1080p", "2K", "4K")
    val RATES = listOf(24f, 25f, 30f, 50f, 60f)

    class Source(val shortSide: Int, val longSide: Int, val fps: Float, val durationMs: Long, val thumb: Bitmap?)

    fun show(a: Activity, src: Source, onExport: (ExportChoice) -> Unit) {
        val root = a.window.decorView as FrameLayout
        val dim = FrameLayout(a).apply { setBackgroundColor(0x99000000.toInt()); isClickable = true; alpha = 0f }
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true
            background = GradientDrawable().apply { setColor(0xff151a2e.toInt()); cornerRadii = FloatArray(8) { i -> if (i < 4) a.dp(28).toFloat() else 0f } }
            setPadding(a.dp(20), a.dp(10), a.dp(20), a.dp(24))
        }
        fun close() { card.animate().translationY(card.height.toFloat()).setDuration(220).start(); dim.animate().alpha(0f).setDuration(220).withEndAction { root.removeView(dim) }.start() }
        card.addView(View(a).apply { background = GradientDrawable().apply { setColor(0x40ffffff); cornerRadius = a.dp(3).toFloat() } }, LinearLayout.LayoutParams(a.dp(40), a.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = a.dp(14) })

        // The clip and what it is now.
        val head = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(src.thumb); setBackgroundColor(0x22ffffff); clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, a.dp(12).toFloat()) } }, a.dp(62), a.dp(84))
        val heads = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(14), 0, 0, 0) }
        heads.addView(a.text("ส่งออกวิดีโอ", 20f, Color.WHITE, true))
        heads.addView(a.text("ยาว ${clock(src.durationMs)} · ต้นฉบับ ${sideName(src.shortSide)} ${fpsName(src.fps)} fps", 12.5f, Glass.TEXT2))
        head.addView(heads, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(head, -1, -2)

        // The source's own size and rate are always choices (and the starting point), so keeping them never enlarges.
        val sides = (SIDES.filter { kotlin.math.abs(it - src.shortSide) > 8 } + src.shortSide).sorted()
        val rates = (RATES.filter { kotlin.math.abs(it - src.fps) > 0.6f } + src.fps).sorted()
        var side = sides.indexOf(src.shortSide)
        var rate = rates.indexOf(src.fps)
        var ai = true
        val sideValue = a.text("", 15f, Color.WHITE, true); val rateValue = a.text("", 15f, Color.WHITE, true)
        val sideNote = a.text("", 12f, Glass.TEXT2); val rateNote = a.text("", 12f, Glass.TEXT2)
        val aiChip = LinearLayout(a)
        val estimate = a.text("", 12.5f, Glass.TEXT2).apply { gravity = Gravity.CENTER }
        val go = a.text("ส่งออก", 16f, Glass.ON_ACCENT, true).apply { gravity = Gravity.CENTER; background = a.glass(26, Glass.ACCENT); isClickable = true; glassTouch(); contentDescription = "เริ่มส่งออก" }

        fun refresh() {
            val s = sides[side]; val r = rates[rate]
            val bigger = s > src.shortSide * 1.05f; val smoother = r > src.fps * 1.05f
            sideValue.text = sideName(s); sideValue.setTextColor(if (bigger) Glass.ACCENT else Color.WHITE)
            rateValue.text = "${fpsName(r)} fps"; rateValue.setTextColor(if (smoother) Glass.ACCENT else Color.WHITE)
            sideNote.text = when {
                bigger && ai -> "AI สร้างพิกเซลเพิ่ม ×${String.format(Locale.US, "%.1f", s.toFloat() / src.shortSide)} จากโมเดล Detail SR"
                bigger -> "ขยายภาพแบบปกติ ไม่ใช้ AI"
                s < src.shortSide - 8 -> "ย่อลงจากต้นฉบับ ไฟล์เล็กลง"
                else -> "เท่าต้นฉบับ"
            }
            rateNote.text = when {
                smoother -> "สร้างเฟรมแทรก ${String.format(Locale.US, "%.1f", r / src.fps)}× ด้วย Optical flow"
                r < src.fps - 0.6f -> "ลดเฟรมลงจากต้นฉบับ"
                else -> "เท่าต้นฉบับ"
            }
            aiChip.visibility = if (bigger) View.VISIBLE else View.GONE
            val long = (src.longSide * s.toFloat() / src.shortSide).toInt()
            val mb = FrameEncoder.bitrate(long, s, r) / 8.0 * src.durationMs / 1000.0 / 1_000_000.0
            val minutes = minutes(src, s, r, ai && bigger)
            val ok = !(bigger || smoother) || FrameEncoder.pickMime(long / 2 * 2, s, r) != null
            estimate.text = if (!ok) "เครื่องนี้เข้ารหัส ${sideName(s)} ${fpsName(r)} fps ไม่ได้ ลองลดความละเอียดหรือเฟรมเรต"
                else "ไฟล์ประมาณ ${if (mb < 10) String.format(Locale.US, "%.1f", mb) else mb.toInt().toString()} MB" + (if (minutes > 0) " · ใช้เวลาประมาณ ${if (minutes < 1) "ไม่ถึง 1" else ceil(minutes).toInt().toString()} นาที" else "")
            estimate.setTextColor(if (ok) Glass.TEXT2 else 0xffff6b5e.toInt())
            go.isEnabled = ok; go.alpha = if (ok) 1f else 0.4f
        }
        fun row(title: String, value: TextView, note: TextView, names: List<String>, start: Int, set: (Int) -> Unit) {
            val top = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(18), 0, 0) }
            top.addView(a.text(title, 14f, Glass.TEXT2, true), LinearLayout.LayoutParams(0, -2, 1f)); top.addView(value)
            card.addView(top, -1, -2)
            card.addView(GlassSlider(a).apply { values = names; index = start; grad = intArrayOf(0xff64d2ff.toInt(), Color.WHITE, Glass.ACCENT); contentDescription = title
                onChange = { k -> set(k); refresh() } }, LinearLayout.LayoutParams(-1, a.dp(46)))
            card.addView(note, -1, -2)
        }
        row("ความละเอียด", sideValue, sideNote, sides.map { sideName(it) }, side) { side = it }
        row("อัตราเฟรม", rateValue, rateNote, rates.map { fpsName(it) }, rate) { rate = it }
        lateinit var chip: View
        fun buildChip() { aiChip.removeAllViews(); chip = EditorKit.chip(a, if (ai) "AI เพิ่มรายละเอียด · เปิด" else "AI เพิ่มรายละเอียด · ปิด (เร็วกว่า)", Icons.AI, ai) { ai = !ai; buildChip(); refresh() }; aiChip.addView(chip, -2, a.dp(34)) }
        buildChip()
        card.addView(aiChip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(12) })
        card.addView(estimate, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(16) })
        go.setOnClickListener { Glass.tick(go); close(); onExport(ExportChoice(sides[side], rates[rate], ai)) }
        card.addView(go, LinearLayout.LayoutParams(-1, a.dp(52)).apply { topMargin = a.dp(12) })
        card.addView(a.text("ยกเลิก", 14f, Glass.TEXT2, true).apply { gravity = Gravity.CENTER; isClickable = true; setPadding(0, a.dp(12), 0, 0); setOnClickListener { close() } }, -1, a.dp(44))
        refresh()

        dim.setOnClickListener { close() }
        dim.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(dim, -1, -1)
        dim.animate().alpha(1f).setDuration(200).start()
        card.translationY = a.dp(500).toFloat(); card.animate().translationY(0f).setInterpolator(Glass.SPRING).setDuration(420).start()
    }

    /** Rough time on a recent phone: AI tiles (about 45 ms each on the GPU) plus in-between frames. */
    fun minutes(src: Source, side: Int, fps: Float, ai: Boolean): Double {
        val seconds = src.durationMs / 1000.0
        val bigger = side > src.shortSide * 1.05; val smoother = fps > src.fps * 1.05f
        if (!bigger && !smoother) return 0.0
        val frames = seconds * src.fps
        val tiles = ceil(src.longSide / 192.0) * ceil(src.shortSide / 192.0)
        val aiMs = if (ai && bigger) frames * tiles * 45 else 0.0
        val outPixels = side.toDouble() * src.longSide * side / src.shortSide
        val made = if (smoother) seconds * (fps - src.fps) else 0.0
        val flowMs = made * 25 * outPixels / 2_073_600 + frames * 30
        val encodeMs = seconds * fps * 12 * outPixels / 2_073_600
        return (aiMs + flowMs + encodeMs) / 60_000.0
    }

    fun sideName(short: Int) = SIDES.zip(SIDE_NAMES).firstOrNull { kotlin.math.abs(it.first - short) <= 8 }?.second ?: "${short}p"
    fun fpsName(f: Float) = if (kotlin.math.abs(f - f.toInt()) < 0.05f) f.toInt().toString() else String.format(Locale.US, "%.1f", f)
    private fun clock(ms: Long) = String.format(Locale.US, "%d:%02d", ms / 60000, ms / 1000 % 60)
}

/**
 * Progress while exporting, TikTok style with the AI work beside it: the clip with a ring that fills, the
 * percentage, frames done out of all frames, the time left, and the steps marked waiting / working / done.
 * Behind it a background animation that changes with the step (editing, making frames, AI pixels, saving).
 * "ทำงานเบื้องหลัง" leaves the page; the export goes on with a live notification.
 */
class ExportProgress(private val a: Activity, thumb: Bitmap?, private val steps: List<String>, private val kinds: List<Int>, onCancel: () -> Unit, onBackground: () -> Unit) {
    private val layer = FrameLayout(a).apply { setBackgroundColor(0xf20b0f1e.toInt()); isClickable = true; alpha = 0f }
    val view: View get() = layer
    private val backdrop = Backdrop(a)
    private val ring = Ring(a)
    private val percent = a.text("0%", 40f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
    private val frames = a.text("", 13.5f, Glass.TEXT2).apply { gravity = Gravity.CENTER }
    private val eta = a.text("", 17f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
    private val etaBox = LinearLayout(a)
    private val marks = ArrayList<TextView>(); private val labels = ArrayList<TextView>()

    init {
        layer.addView(backdrop, -1, -1)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(a.dp(28), 0, a.dp(28), 0) }
        val frame = FrameLayout(a)
        frame.addView(ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(thumb); setBackgroundColor(0x22ffffff); clipToOutline = true; alpha = 0.9f
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, a.dp(18).toFloat()) } },
            FrameLayout.LayoutParams(a.dp(132), a.dp(176), Gravity.CENTER))
        frame.addView(ring, FrameLayout.LayoutParams(a.dp(150), a.dp(194), Gravity.CENTER))
        col.addView(frame, a.dp(160), a.dp(200))
        col.addView(percent, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(10) })
        col.addView(frames, -1, -2)
        // Time left, highlighted: a glass pill with a clock.
        etaBox.apply { gravity = Gravity.CENTER_VERTICAL; background = a.glass(22, (Glass.ACCENT and 0x00ffffff) or 0x2e000000); setPadding(a.dp(16), a.dp(8), a.dp(18), a.dp(8)); visibility = View.GONE
            addView(IconView(a, Icons.TIMER, 2f).apply { tint = Glass.ACCENT }, a.dp(20), a.dp(20)); addView(eta.apply { setPadding(a.dp(8), 0, 0, 0) }) }
        col.addView(etaBox, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(12) })
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = a.glass(20, 0x14ffffff); setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(10)) }
        steps.forEach { st ->
            val r = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(6), 0, a.dp(6)) }
            val m = a.text("○", 15f, Glass.TEXT3, true).apply { gravity = Gravity.CENTER }; marks += m
            r.addView(m, a.dp(26), -2)
            val l = a.text(st, 14f, Glass.TEXT3); labels += l
            r.addView(l, LinearLayout.LayoutParams(0, -2, 1f))
            list.addView(r, -1, -2)
        }
        col.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(16) })
        val buttons = LinearLayout(a).apply { gravity = Gravity.CENTER }
        buttons.addView(EditorKit.chip(a, "ทำงานเบื้องหลัง", "M12 4v10 M7 10l5 5 5-5 M5 20h14") { onBackground() }, LinearLayout.LayoutParams(-2, a.dp(44)).apply { rightMargin = a.dp(8) })
        buttons.addView(EditorKit.chip(a, "ยกเลิก", Icons.CLOSE) { onCancel() }, LinearLayout.LayoutParams(-2, a.dp(44)))
        col.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(18) })
        col.addView(a.text("ออกจากหน้านี้ได้ ความคืบหน้าจะแสดงในการแจ้งเตือน", 11.5f, Glass.TEXT3).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(8) })
        layer.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        (a.window.decorView as FrameLayout).addView(layer, -1, -1)
        layer.animate().alpha(1f).setDuration(220).start()
    }

    /** Shows the export's current state. */
    fun render(s: ExportJob.State) {
        val p = s.percent() / 100f
        ring.progress = p; percent.text = "${(p * 100).toInt()}%"
        val i = s.stage
        backdrop.mode = kinds.getOrElse(i) { Backdrop.SAVE }
        frames.text = listOfNotNull(if (s.totalFrames > 0) "${String.format(Locale.US, "%,d", s.frames)} / ${String.format(Locale.US, "%,d", s.totalFrames)} เฟรม" else null, s.detail.takeIf { it.isNotEmpty() }).joinToString(" · ")
        val ms = s.etaMs
        etaBox.visibility = if (ms > 0) View.VISIBLE else View.GONE
        eta.text = if (ms < 60_000) "เหลืออีกไม่ถึง 1 นาที" else "เหลืออีกประมาณ ${ms / 60_000 + 1} นาที"
        marks.forEachIndexed { k, m ->
            m.text = if (k < i) "✓" else if (k == i) "●" else "○"
            m.setTextColor(if (k < i) 0xff30d158.toInt() else if (k == i) Glass.ACCENT else Glass.TEXT3)
            labels[k].setTextColor(if (k <= i) Color.WHITE else Glass.TEXT3)
        }
    }
    fun remove() { layer.animate().alpha(0f).setDuration(200).withEndAction { (layer.parent as? FrameLayout)?.removeView(layer) }.start() }

    /** Rounded ring around the thumbnail; the lit part grows clockwise from the top. */
    private class Ring(c: Context) : View(c) {
        var progress = 0f; set(v) { field = v; invalidate() }
        private val d = c.resources.displayMetrics.density
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * d; color = 0x26ffffff }
        private val lit = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * d; strokeCap = Paint.Cap.ROUND; color = Glass.ACCENT }
        private val path = android.graphics.Path(); private val part = android.graphics.Path()
        override fun onDraw(cv: Canvas) {
            val r = RectF(4 * d, 4 * d, width - 4 * d, height - 4 * d)
            path.reset(); path.addRoundRect(r, 22 * d, 22 * d, android.graphics.Path.Direction.CW)
            cv.drawPath(path, track)
            val pm = android.graphics.PathMeasure(path, true); part.reset()
            val len = pm.length; val start = len * 0.125f
            val end = start + len * progress
            if (end <= len) pm.getSegment(start, end, part, true) else { pm.getSegment(start, len, part, true); pm.getSegment(0f, end - len, part, true) }
            cv.drawPath(part, lit)
        }
    }

    /**
     * Background that always moves and changes with the step: EDIT soft colour clouds turning, FLOW light streaks
     * racing sideways (new frames), AI a pixel grid lighting up under a scanning band (new pixels), SAVE sparks
     * rising. Modes cross-fade.
     */
    class Backdrop(c: Context) : View(c) {
        companion object { const val EDIT = 0; const val FLOW = 1; const val AI = 2; const val SAVE = 3 }
        var mode = EDIT; set(v) { if (v != field) { previous = field; field = v; fade = 0f } }
        private var previous = EDIT; private var fade = 1f
        private var t = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d = c.resources.displayMetrics.density
        private val rnd = java.util.Random(7)
        private val streaks = List(26) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), 0.3f + rnd.nextFloat(), 0.2f + rnd.nextFloat() * 0.6f) }
        private val sparks = List(40) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), 0.4f + rnd.nextFloat(), 1f + rnd.nextFloat() * 3f) }
        private val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply { duration = 1000; repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator(); addUpdateListener { t += 0.016f; fade = (fade + 0.03f).coerceAtMost(1f); invalidate() } }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!Glass.reduced(context)) anim.start() }
        override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
        override fun onDraw(cv: Canvas) {
            if (fade < 1f) draw(cv, previous, 1f - fade)
            draw(cv, mode, fade)
        }
        private fun draw(cv: Canvas, m: Int, a: Float) {
            val w = width.toFloat(); val h = height.toFloat(); if (w <= 0f || a <= 0f) return
            p.shader = null; p.style = Paint.Style.FILL
            when (m) {
                EDIT -> listOf(Glass.ACCENT, 0xff8e7dff.toInt(), 0xffff5e8a.toInt()).forEachIndexed { i, col ->
                    val ang = t * 0.5f + i * 2.1f
                    val x = w * (0.5f + 0.35f * kotlin.math.cos(ang)); val y = h * (0.45f + 0.28f * kotlin.math.sin(ang * 1.2f)); val r = w * 0.6f
                    p.shader = android.graphics.RadialGradient(x, y, r, (col and 0x00ffffff) or ((0x50 * a).toInt() shl 24), 0, android.graphics.Shader.TileMode.CLAMP)
                    cv.drawCircle(x, y, r, p)
                }
                FLOW -> streaks.forEach { s ->
                    val x = ((s[0] + t * 0.25f * s[2]) % 1.3f - 0.15f) * w; val y = s[1] * h; val len = w * 0.25f * s[2]
                    p.shader = android.graphics.LinearGradient(x - len, y, x, y, 0, (0x64d2ff or ((0xb0 * a * s[3]).toInt() shl 24)), android.graphics.Shader.TileMode.CLAMP)
                    p.strokeWidth = 2.5f * d * s[2]; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
                    cv.drawLine(x - len, y, x, y, p)
                }
                AI -> {
                    val cell = 22 * d; val cols = (w / cell).toInt() + 1; val rows = (h / cell).toInt() + 1
                    val band = ((t * 0.18f) % 1.2f - 0.1f) * h
                    for (gy in 0 until rows) for (gx in 0 until cols) {
                        val cy = gy * cell; val near = (1f - kotlin.math.abs(cy - band) / (h * 0.18f)).coerceIn(0f, 1f)
                        val twinkle = ((kotlin.math.sin(t * 3f + gx * 1.7f + gy * 2.3f) + 1f) / 2f)
                        val alpha = (a * (0.05f + 0.35f * near * twinkle + if (cy < band) 0.06f else 0f) * 255).toInt().coerceIn(0, 255)
                        p.color = (if ((gx + gy) % 3 == 0) 0x64d2ff else Glass.ACCENT and 0x00ffffff) or (alpha shl 24)
                        cv.drawRect(gx * cell + 2 * d, cy + 2 * d, gx * cell + cell - 2 * d, cy + cell - 2 * d, p)
                    }
                    p.shader = android.graphics.LinearGradient(0f, band - 40 * d, 0f, band + 4 * d, 0, (Glass.ACCENT and 0x00ffffff) or ((0x80 * a).toInt() shl 24), android.graphics.Shader.TileMode.CLAMP)
                    cv.drawRect(0f, band - 40 * d, w, band + 4 * d, p)
                }
                else -> sparks.forEach { s ->
                    val y = h * (1.1f - ((s[1] + t * 0.12f * s[2]) % 1.2f)); val x = w * s[0] + 10 * d * kotlin.math.sin(t * 2 + s[1] * 9)
                    p.color = 0x30d158 or ((a * 200 * (y / h).coerceIn(0f, 1f)).toInt().coerceIn(0, 255) shl 24)
                    cv.drawCircle(x, y, s[3] * d, p)
                }
            }
        }
    }
}
