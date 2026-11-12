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

        var side = SIDES.indexOfLast { it <= src.shortSide + 8 }.coerceAtLeast(0)
        var rate = RATES.indexOfLast { it <= src.fps + 0.6f }.coerceAtLeast(0)
        var ai = true
        val sideValue = a.text("", 15f, Color.WHITE, true); val rateValue = a.text("", 15f, Color.WHITE, true)
        val sideNote = a.text("", 12f, Glass.TEXT2); val rateNote = a.text("", 12f, Glass.TEXT2)
        val aiChip = LinearLayout(a)
        val estimate = a.text("", 12.5f, Glass.TEXT2).apply { gravity = Gravity.CENTER }
        val go = a.text("ส่งออก", 16f, Glass.ON_ACCENT, true).apply { gravity = Gravity.CENTER; background = a.glass(26, Glass.ACCENT); isClickable = true; glassTouch(); contentDescription = "เริ่มส่งออก" }

        fun refresh() {
            val s = SIDES[side]; val r = RATES[rate]
            val bigger = s > src.shortSide * 1.05f; val smoother = r > src.fps * 1.05f
            sideValue.text = SIDE_NAMES[side]; sideValue.setTextColor(if (bigger) Glass.ACCENT else Color.WHITE)
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
            estimate.text = if (!ok) "เครื่องนี้เข้ารหัส ${SIDE_NAMES[side]} ${fpsName(r)} fps ไม่ได้ ลองลดความละเอียดหรือเฟรมเรต"
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
        row("ความละเอียด", sideValue, sideNote, SIDE_NAMES, side) { side = it }
        row("อัตราเฟรม", rateValue, rateNote, RATES.map { fpsName(it) }, rate) { rate = it }
        lateinit var chip: View
        fun buildChip() { aiChip.removeAllViews(); chip = EditorKit.chip(a, if (ai) "AI เพิ่มรายละเอียด · เปิด" else "AI เพิ่มรายละเอียด · ปิด (เร็วกว่า)", Icons.AI, ai) { ai = !ai; buildChip(); refresh() }; aiChip.addView(chip, -2, a.dp(34)) }
        buildChip()
        card.addView(aiChip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(12) })
        card.addView(estimate, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(16) })
        go.setOnClickListener { Glass.tick(go); close(); onExport(ExportChoice(SIDES[side], RATES[rate], ai)) }
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
 * percentage, and the steps (edit, more frames, AI pixels, save) each marked waiting, working or done.
 */
class ExportProgress(private val a: Activity, thumb: Bitmap?, private val steps: List<String>, private val weights: List<Float>, onCancel: () -> Unit) {
    private val layer = FrameLayout(a).apply { setBackgroundColor(0xf20b0f1e.toInt()); isClickable = true; alpha = 0f }
    val view: View get() = layer
    private val ring = Ring(a)
    private val percent = a.text("0%", 34f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
    private val detail = a.text("", 13f, Glass.TEXT2).apply { gravity = Gravity.CENTER }
    private val eta = a.text("", 12.5f, Glass.TEXT3).apply { gravity = Gravity.CENTER }
    private val marks = ArrayList<TextView>(); private val labels = ArrayList<TextView>()
    private var current = 0

    init {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(a.dp(28), 0, a.dp(28), 0) }
        val frame = FrameLayout(a)
        frame.addView(ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(thumb); setBackgroundColor(0x22ffffff); clipToOutline = true; alpha = 0.85f
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, a.dp(18).toFloat()) } },
            FrameLayout.LayoutParams(a.dp(132), a.dp(176), Gravity.CENTER))
        frame.addView(ring, FrameLayout.LayoutParams(a.dp(150), a.dp(194), Gravity.CENTER))
        col.addView(frame, a.dp(160), a.dp(200))
        col.addView(percent, LinearLayout.LayoutParams(-2, -2).apply { topMargin = a.dp(14) })
        col.addView(detail, -1, -2)
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = a.glass(20, 0x14ffffff); setPadding(a.dp(16), a.dp(10), a.dp(16), a.dp(10)) }
        steps.forEach { s ->
            val r = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, a.dp(6), 0, a.dp(6)) }
            val m = a.text("○", 15f, Glass.TEXT3, true).apply { gravity = Gravity.CENTER }; marks += m
            r.addView(m, a.dp(26), -2)
            val l = a.text(s, 14f, Glass.TEXT3); labels += l
            r.addView(l, LinearLayout.LayoutParams(0, -2, 1f))
            list.addView(r, -1, -2)
        }
        col.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(20) })
        col.addView(eta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.dp(12) })
        col.addView(a.text("เปิดหน้านี้ไว้จนเสร็จ", 12f, Glass.TEXT3).apply { gravity = Gravity.CENTER }, -1, -2)
        col.addView(EditorKit.chip(a, "ยกเลิก", Icons.CLOSE) { onCancel() }, LinearLayout.LayoutParams(-2, a.dp(40)).apply { topMargin = a.dp(16) })
        layer.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        (a.window.decorView as FrameLayout).addView(layer, -1, -1)
        layer.animate().alpha(1f).setDuration(220).start()
        update(0, 0f, "")
    }

    /** Step [i] is [f] (0..1) done; earlier steps count as done. */
    fun update(i: Int, f: Float, text: String) {
        current = i
        val before = weights.take(i).sum(); val total = weights.sum().coerceAtLeast(0.001f)
        val p = ((before + weights.getOrElse(i) { 0f } * f.coerceIn(0f, 1f)) / total).coerceIn(0f, 1f)
        ring.progress = p; percent.text = "${(p * 100).toInt()}%"
        if (text.isNotEmpty()) detail.text = text
        marks.forEachIndexed { k, m ->
            m.text = if (k < i) "✓" else if (k == i) "●" else "○"
            m.setTextColor(if (k < i) 0xff30d158.toInt() else if (k == i) Glass.ACCENT else Glass.TEXT3)
            labels[k].setTextColor(if (k <= i) Color.WHITE else Glass.TEXT3)
        }
    }
    fun eta(ms: Long) { eta.text = if (ms <= 0) "" else if (ms < 60_000) "เหลืออีกไม่ถึง 1 นาที" else "เหลืออีกประมาณ ${ms / 60_000 + 1} นาที" }
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
            // Start at the top middle.
            val len = pm.length; val start = len * 0.125f
            val end = start + len * progress
            if (end <= len) pm.getSegment(start, end, part, true) else { pm.getSegment(start, len, part, true); pm.getSegment(0f, end - len, part, true) }
            cv.drawPath(part, lit)
        }
    }
}
