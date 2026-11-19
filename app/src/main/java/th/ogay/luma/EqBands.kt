package th.ogay.luma

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Centre frequencies (Hz) of the graphic equaliser, one octave apart. */
object EqBands {
    val HZ = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val NAMES = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    const val RANGE = 12

    /** Level in dB at [hz] for the given band levels, read along the log-frequency axis between the two nearest bands. */
    fun levelAt(db: FloatArray, hz: Float): Float {
        if (hz <= HZ.first()) return db.first()
        if (hz >= HZ.last()) return db.last()
        var i = 0; while (i < HZ.size - 2 && hz > HZ[i + 1]) i++
        val t = (kotlin.math.ln(hz / HZ[i]) / kotlin.math.ln(HZ[i + 1] / HZ[i]))
        return db[i] + (db[i + 1] - db[i]) * t
    }

    /** A row of vertical sliders that scrolls sideways; the first band starts in the middle, so every band can reach the middle. */
    fun strip(c: Context, db: FloatArray, height: Int, changed: () -> Unit): View {
        val scroll = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true }
        val row = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        val cellW = c.dp(64)
        val cells = db.indices.map { i -> Band(c, i, db, changed) }
        scroll.addView(row, FrameLayout.LayoutParams(-2, -1))
        val padL = View(c); val padR = View(c)
        row.addView(padL, LinearLayout.LayoutParams(0, 1)); cells.forEach { row.addView(it, LinearLayout.LayoutParams(cellW, height)) }; row.addView(padR, LinearLayout.LayoutParams(0, 1))
        // Half the visible width on each side lets the first and the last band stand in the middle.
        scroll.viewTreeObserver.addOnGlobalLayoutListener {
            val side = ((scroll.width - cellW) / 2).coerceAtLeast(0)
            if ((padL.layoutParams as ViewGroup.LayoutParams).width != side) { padL.layoutParams.width = side; padR.layoutParams.width = side; padL.requestLayout(); padR.requestLayout() }
        }
        // The band nearest the middle is the one in focus: its name is lit.
        fun focus() { val mid = scroll.scrollX + scroll.width / 2; var best = 0; var gap = Int.MAX_VALUE
            cells.forEachIndexed { i, b -> val d = abs((b.left + b.width / 2) - mid); if (d < gap) { gap = d; best = i } }
            cells.forEachIndexed { i, b -> b.lit(i == best) } }
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> focus() }
        scroll.post { scroll.scrollTo(0, 0); focus() }
        return scroll
    }

    private class Band(c: Context, private val i: Int, private val db: FloatArray, private val changed: () -> Unit) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val slop = ViewConfiguration.get(c).scaledTouchSlop
        private var downX = 0f; private var downY = 0f; private var mode = 0   // 0 undecided, 1 sideways (the strip scrolls), 2 dragging the level
        private var on = false
        private val d = c.resources.displayMetrics.density
        init { isClickable = true; contentDescription = "ความถี่ ${NAMES[i]} เฮิรตซ์" }
        fun lit(v: Boolean) { if (on != v) { on = v; invalidate() } }
        private fun top() = 26 * d; private fun bottom() = height - 24 * d
        private fun yOf(v: Float) = top() + (1f - (v + RANGE) / (2 * RANGE)) * (bottom() - top())
        private fun valueAt(y: Float) = ((1f - (y - top()) / (bottom() - top())) * 2 * RANGE - RANGE).roundToInt().coerceIn(-RANGE, RANGE).toFloat()
        override fun onDraw(cv: Canvas) {
            val cx = width / 2f; val v = db[i]
            paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER
            paint.color = if (v != 0f) Glass.ACCENT else Glass.TEXT2; paint.textSize = 12f * d * 1.0f; paint.isFakeBoldText = true
            cv.drawText((if (v > 0) "+" else "") + v.toInt(), cx, 16 * d, paint)
            paint.color = 0x33ffffff; cv.drawRoundRect(cx - 2.5f * d, top(), cx + 2.5f * d, bottom(), 3 * d, 3 * d, paint)
            paint.color = 0x66ffffff; cv.drawRect(cx - 9 * d, yOf(0f) - 0.8f * d, cx + 9 * d, yOf(0f) + 0.8f * d, paint)
            paint.color = Glass.ACCENT; val y = yOf(v); cv.drawRoundRect(cx - 2.5f * d, minOf(y, yOf(0f)), cx + 2.5f * d, maxOf(y, yOf(0f)), 3 * d, 3 * d, paint)
            paint.color = 0x55000000; cv.drawCircle(cx, y + d, 11 * d, paint)
            paint.color = if (v != 0f) 0xffffffff.toInt() else 0xffe6e6ea.toInt(); cv.drawCircle(cx, y, 10 * d, paint)
            paint.color = if (on) Glass.ACCENT else Glass.TEXT2; paint.textSize = 11.5f * d; paint.isFakeBoldText = on
            cv.drawText(NAMES[i], cx, height - 6 * d, paint)
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; mode = 0; return true }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == 0) { val dx = abs(e.x - downX); val dy = abs(e.y - downY)
                        if (dy > slop && dy > dx) { mode = 2; var p = parent; while (p != null) { p.requestDisallowInterceptTouchEvent(true); p = p.parent } }
                        else if (dx > slop) mode = 1 }
                    if (mode == 2) set(valueAt(e.y))
                }
                MotionEvent.ACTION_UP -> { if (mode == 0) set(valueAt(e.y)); mode = 0; performClick() }
                MotionEvent.ACTION_CANCEL -> mode = 0
            }
            return true
        }
        private fun set(v: Float) { if (db[i] != v) { db[i] = v; Glass.tick(this); invalidate(); changed() } }
    }
}
