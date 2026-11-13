package th.ogay.luma

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * One lane per layer under the main clip, like a small multi-track timeline. Each layer is a coloured bar over
 * the finished video's length; the white line is the playhead. Tap a lane to select it, drag the selected bar
 * sideways to move when the layer starts.
 */
class LayerLanes(context: Context) : View(context) {
    var layers: List<VideoLayer> = emptyList(); set(v) { field = v; requestLayout(); invalidate() }
    var total = 1L; set(v) { field = v.coerceAtLeast(1); invalidate() }
    var playhead = 0L; set(v) { field = v; invalidate() }
    var selected = -1; set(v) { field = v; invalidate() }
    var onSelect: (Int) -> Unit = {}
    var onMove: (Int, Long) -> Unit = { _, _ -> }
    var onMoved: () -> Unit = {}
    var names: (VideoLayer) -> String = { "" }

    private val d = resources.displayMetrics.density
    private val lane = 30 * d
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14ffffff }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = 0xffffffff.toInt() }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffffffff.toInt(); strokeWidth = 2 * d }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff0a0a0b.toInt(); textSize = 11 * d; isFakeBoldText = true }
    private var dragging = false; private var lastX = 0f; private var downX = 0f

    init { contentDescription = "เลเยอร์ แตะเพื่อเลือก ลากแถบเพื่อเลื่อนเวลาเริ่ม"; isClickable = true }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (layers.size.coerceAtLeast(1) * lane + 4 * d).toInt())

    private fun x(ms: Long) = paddingLeft + (width - paddingLeft - paddingRight) * (ms.toFloat() / total).coerceIn(0f, 1f)

    override fun onDraw(c: Canvas) {
        if (layers.isEmpty()) return
        layers.forEachIndexed { i, l ->
            val top = i * lane + 3 * d; val bottom = top + lane - 6 * d
            c.drawRoundRect(RectF(paddingLeft.toFloat(), top, (width - paddingRight).toFloat(), bottom), 8 * d, 8 * d, track)
            val r = RectF(x(l.at), top, x(l.end).coerceAtLeast(x(l.at) + 10 * d), bottom)
            bar.color = color(l.kind); bar.alpha = if (selected < 0 || selected == i) 255 else 140
            c.drawRoundRect(r, 8 * d, 8 * d, bar)
            if (i == selected) c.drawRoundRect(r, 8 * d, 8 * d, edge)
            c.save(); c.clipRect(r)
            c.drawText(names(l), r.left + 8 * d, (top + bottom) / 2 + 4 * d, label)
            c.restore()
        }
        val px = x(playhead); c.drawLine(px, 0f, px, height.toFloat(), head)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = (e.y / lane).toInt().coerceIn(0, (layers.size - 1).coerceAtLeast(0))
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (layers.isEmpty()) return false
                downX = e.x; lastX = e.x; dragging = false
                if (i != selected) { selected = i; onSelect(i); Glass.tick(this) }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val l = layers.getOrNull(selected) ?: return true
                if (!dragging && kotlin.math.abs(e.x - downX) > 8 * d) dragging = true
                if (dragging) {
                    val ms = ((e.x - lastX) / (width - paddingLeft - paddingRight) * total).toLong()
                    if (ms != 0L) { onMove(selected, (l.at + ms).coerceIn(0L, (total - l.length).coerceAtLeast(0L))); lastX = e.x }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) onMoved()
        }
        return true
    }

    companion object {
        fun color(kind: Int) = when (kind) { VideoLayer.VIDEO -> 0xff64d2ff.toInt(); VideoLayer.IMAGE -> Glass.ACCENT; else -> 0xff30d158.toInt() }
    }
}
