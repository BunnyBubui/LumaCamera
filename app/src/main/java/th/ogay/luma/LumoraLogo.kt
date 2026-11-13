package th.ogay.luma

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The Lumora mark drawn as lines: the lens ring, the orbit around it, the inner lens and the sparkle.
 * [progress] 0..1 draws the strokes one after another (each overlaps the next a little); near the end the
 * inner lens and sparkle fill in and the mark glows.
 */
class LumoraLogoView(context: Context) : View(context) {
    var progress = 1f; set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    private val d = resources.displayMetrics.density
    private val strokes: List<Path>
    /** When each stroke starts and ends, as shares of [progress]. */
    private val windows = listOf(0f to 0.55f, 0.35f to 0.8f, 0.65f to 0.92f)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val scaled = ArrayList<Path>()
    private val partial = Path()

    init {
        // 108-unit grid, like the launcher icon.
        // Camera body with its bump, the lens ring and a small star of light.
        val body = Path().apply {
            moveTo(30f, 40f); lineTo(39f, 40f); lineTo(43f, 33f); lineTo(65f, 33f); lineTo(69f, 40f); lineTo(78f, 40f)
            quadTo(82f, 40f, 82f, 44f); lineTo(82f, 72f); quadTo(82f, 76f, 78f, 76f); lineTo(30f, 76f); quadTo(26f, 76f, 26f, 72f); lineTo(26f, 44f); quadTo(26f, 40f, 30f, 40f); close()
        }
        val ring = Path().apply { addCircle(54f, 58f, 12f, Path.Direction.CW) }
        val spark = Path().apply {
            moveTo(61f, 50f); quadTo(61.7f, 53.3f, 65f, 54f); quadTo(61.7f, 54.7f, 61f, 58f); quadTo(60.3f, 54.7f, 57f, 54f); quadTo(60.3f, 53.3f, 61f, 50f); close()
        }
        strokes = listOf(body, ring, spark)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val s = minOf(w, h) / 108f
        val m = Matrix().apply { setScale(s, s); postTranslate((w - 108f * s) / 2f, (h - 108f * s) / 2f) }
        scaled.clear(); strokes.forEach { p -> scaled += Path(p).apply { transform(m) } }
        line.strokeWidth = 3.4f * s; glow.strokeWidth = 9f * s
        line.color = Color.WHITE; glow.color = Color.WHITE
    }

    override fun onDraw(c: Canvas) {
        if (scaled.isEmpty()) return
        val end = ((progress - 0.88f) / 0.12f).coerceIn(0f, 1f)
        // Soft glow under the finished mark.
        if (end > 0f) { glow.alpha = (60 * end).toInt(); scaled.forEach { c.drawPath(it, glow) } }
        scaled.forEachIndexed { i, p ->
            val (a, b) = windows[i]
            val t = ((progress - a) / (b - a)).coerceIn(0f, 1f)
            if (t <= 0f) return@forEachIndexed
            val pm = PathMeasure(p, false)
            partial.reset()
            var drawn = 0f; val total = run { var len = 0f; do { len += pm.length } while (pm.nextContour()); len }
            val pm2 = PathMeasure(p, false)
            do {
                val len = pm2.length; val want = total * ease(t) - drawn
                if (want > 0f) pm2.getSegment(0f, minOf(len, want), partial, true)
                drawn += len
            } while (pm2.nextContour())
            c.drawPath(partial, line)
        }
        if (end > 0f && scaled.size > 2) {
            fill.color = Color.WHITE; fill.alpha = (235 * end).toInt()
            c.drawPath(scaled[2], fill)
        }
    }

    private fun ease(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
}

/** Opening animation: the mark draws itself line by line, the name follows, then the camera shows through. */
object LumoraIntro {
    fun show(host: FrameLayout, onDone: () -> Unit = {}) {
        val c = host.context
        val d = c.resources.displayMetrics.density
        val layer = FrameLayout(c).apply {
            setBackgroundColor(Glass.BG); isClickable = true; elevation = 40 * d
            contentDescription = "Lumora กำลังเปิด"
        }
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val logo = LumoraLogoView(c).apply { progress = 0f }
        col.addView(logo, LinearLayout.LayoutParams((150 * d).toInt(), (150 * d).toInt()))
        val name = TextView(c).apply {
            text = "Lumora"; setTextColor(Glass.TEXT); textSize = 38f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            letterSpacing = 0.5f; alpha = 0f; gravity = Gravity.CENTER
        }
        col.addView(name, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (6 * d).toInt() })
        val tag = TextView(c).apply { text = "shot on lumora"; setTextColor(0x99ffffff.toInt()); textSize = 13f; letterSpacing = 0.3f; alpha = 0f; gravity = Gravity.CENTER }
        col.addView(tag, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (4 * d).toInt() })
        layer.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        host.addView(layer, -1, -1)

        var finished = false
        fun finish() {
            if (finished) return; finished = true
            logo.animate().scaleX(1.18f).scaleY(1.18f).setDuration(380).start()
            layer.animate().alpha(0f).setDuration(380).setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f))
                .withEndAction { host.removeView(layer); onDone() }.start()
        }
        val draw = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300; interpolator = PathInterpolator(0.3f, 0f, 0.3f, 1f)
            addUpdateListener { a ->
                val p = a.animatedValue as Float
                logo.progress = p
                // The name tightens in as the mark finishes.
                val t = ((p - 0.5f) / 0.5f).coerceIn(0f, 1f)
                name.alpha = t; name.letterSpacing = 0.5f - 0.45f * t; name.translationY = (1 - t) * 14 * d
                tag.alpha = ((p - 0.8f) / 0.2f).coerceIn(0f, 1f)
            }
        }
        layer.setOnClickListener { draw.end(); finish() }
        draw.start()
        layer.postDelayed({ finish() }, 1850)
    }
}
