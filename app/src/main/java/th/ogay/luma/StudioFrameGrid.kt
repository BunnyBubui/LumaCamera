package th.ogay.luma

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Camera-style guide lines over the preview (not in the export): thirds, quarters, a cross, diagonals, the golden ratio and frames
 * for other picture shapes. [box] is the picture as it is on screen.
 */
class FrameGridView(c: Context) : View(c) {
    companion object {
        val NAMES = listOf("ปิดเส้น", "สามส่วน 3×3", "สี่ส่วน 4×4", "กากบาทกลาง", "เส้นทแยง", "สัดส่วนทอง", "กรอบ 1:1", "กรอบ 4:5", "กรอบ 9:16", "กรอบ 16:9")
        const val NOTES_START = 6
    }
    var mode = 0; set(v) { field = v; invalidate() }
    val box = RectF()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xb3ffffff.toInt(); strokeWidth = 1.2f * c.resources.displayMetrics.density; setShadowLayer(3f, 0f, 1f, 0x99000000.toInt()) }
    private val dim = Paint().apply { color = 0x88000000.toInt() }
    init { isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private fun v(f: Float, c: Canvas) { val x = box.left + box.width() * f; c.drawLine(x, box.top, x, box.bottom, line) }
    private fun h(f: Float, c: Canvas) { val y = box.top + box.height() * f; c.drawLine(box.left, y, box.right, y, line) }
    override fun onDraw(c: Canvas) {
        if (mode == 0 || box.isEmpty) return
        when (mode) {
            1 -> { v(1 / 3f, c); v(2 / 3f, c); h(1 / 3f, c); h(2 / 3f, c) }
            2 -> { for (i in 1..3) { v(i / 4f, c); h(i / 4f, c) } }
            3 -> { v(0.5f, c); h(0.5f, c) }
            4 -> { c.drawLine(box.left, box.top, box.right, box.bottom, line); c.drawLine(box.right, box.top, box.left, box.bottom, line) }
            5 -> { v(0.382f, c); v(0.618f, c); h(0.382f, c); h(0.618f, c) }
            else -> {
                val ratio = when (mode) { 6 -> 1f; 7 -> 4f / 5f; 8 -> 9f / 16f; else -> 16f / 9f }
                var w = box.width(); var hh = w / ratio
                if (hh > box.height()) { hh = box.height(); w = hh * ratio }
                val f = RectF(box.centerX() - w / 2, box.centerY() - hh / 2, box.centerX() + w / 2, box.centerY() + hh / 2)
                c.save(); c.clipRect(box)
                c.drawRect(box.left, box.top, box.right, f.top, dim); c.drawRect(box.left, f.bottom, box.right, box.bottom, dim)
                c.drawRect(box.left, f.top, f.left, f.bottom, dim); c.drawRect(f.right, f.top, box.right, f.bottom, dim)
                c.restore(); c.drawRect(f, line)
            }
        }
    }
}
